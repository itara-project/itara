use std::collections::HashMap;
use std::sync::{mpsc, Mutex};
use std::time::Duration;

use itara_core::{ItaraContext, ItaraObserver};

// W3C parent context extracted from inbound headers by restore_context().
// Stored per-thread to bridge restore_context → on_call_received without
// thread-local storage (which is unsafe in cdylibs under dynamic loading).
struct W3CParent {
    trace_id: String,
    span_id:  String,
}

// ── Span model ────────────────────────────────────────────────────────────────

struct CompletedSpan {
    trace_id:       String,
    span_id:        String,
    parent_span_id: Option<String>,
    name:           String,
    kind:           u8,
    start_nanos:    u64,
    end_nanos:      u64,
    attributes:     Vec<(String, String)>,
    error:          bool,
}

struct PendingSpan {
    trace_id:       String,
    span_id:        String,
    parent_span_id: Option<String>,
    name:           String,
    kind:           u8,
    start_nanos:    u64,
    attributes:     Vec<(String, String)>,
}

impl PendingSpan {
    fn complete(self, end_nanos: u64, error: bool) -> CompletedSpan {
        CompletedSpan {
            trace_id:       self.trace_id,
            span_id:        self.span_id,
            parent_span_id: self.parent_span_id,
            name:           self.name,
            kind:           self.kind,
            start_nanos:    self.start_nanos,
            end_nanos,
            attributes:     self.attributes,
            error,
        }
    }
}

// ── OtlpObserver ─────────────────────────────────────────────────────────────

pub struct OtlpObserver {
    // Keyed by itara_span_id — the Itara span ID is also used as the OTel span ID.
    caller_spans: Mutex<HashMap<String, PendingSpan>>,
    callee_spans: Mutex<HashMap<String, PendingSpan>>,

    // Maps ThreadId → (otel_trace_id, otel_span_id) for the most recently opened
    // CLIENT span on each thread. Populated by on_call_sent, read by
    // serialize_context(), cleared by on_return_received.
    current_caller_per_thread: Mutex<HashMap<std::thread::ThreadId, (String, String)>>,

    // Maps ThreadId → W3C parent extracted from inbound headers by restore_context().
    // Consumed by on_call_received to link the SERVER span to the remote caller's
    // OTel span (cross-language trace continuity).
    pending_inbound_parent: Mutex<HashMap<std::thread::ThreadId, W3CParent>>,

    tx: mpsc::SyncSender<Message>,
}

enum Message {
    Span(CompletedSpan),
    Flush,
}

impl OtlpObserver {
    pub fn new(endpoint: &str, service: &str, batch_size: usize, timeout_ms: u64) -> Self {
        // Bounded channel — backpressure if exporter falls behind
        let (tx, rx) = mpsc::sync_channel::<Message>(1024);
        spawn_exporter(rx, endpoint.to_string(), service.to_string(), batch_size, timeout_ms);
        OtlpObserver {
            caller_spans:               Mutex::new(HashMap::new()),
            callee_spans:               Mutex::new(HashMap::new()),
            current_caller_per_thread:  Mutex::new(HashMap::new()),
            pending_inbound_parent:     Mutex::new(HashMap::new()),
            tx,
        }
    }

    fn send(&self, span: CompletedSpan) {
        if let Err(e) = self.tx.send(Message::Span(span)) {
            eprintln!("[Itara/OTLP] Queue full, span dropped: {}", e);
        }
    }
}

impl ItaraObserver for OtlpObserver {
    fn on_call_sent(
        &self, ctx: &ItaraContext, component: &str,
        method: &str, transport: &str, timestamp: u64,
    ) {
        // If this outbound call is nested within an inbound request, inherit the
        // server span's OTel trace_id so all spans for this request share one trace.
        // Otherwise use the Itara trace_id directly.
        let (trace_id, parent_span_id) =
            if let Some(parent_itara_span_id) = &ctx.itara_parent_span_id {
                let callee_spans = self.callee_spans.lock().unwrap();
                if let Some(server_span) = callee_spans.get(parent_itara_span_id) {
                    (server_span.trace_id.clone(), Some(parent_itara_span_id.clone()))
                } else {
                    (ctx.itara_trace_id.clone(), ctx.itara_parent_span_id.clone())
                }
            } else {
                (ctx.itara_trace_id.clone(), None)
            };

        // Remember this CLIENT span's OTel context so serialize_context() can
        // produce the correct W3C traceparent for the outbound transport headers.
        self.current_caller_per_thread.lock().unwrap()
            .insert(std::thread::current().id(), (trace_id.clone(), ctx.itara_span_id.clone()));

        self.caller_spans.lock().unwrap().insert(ctx.itara_span_id.clone(), PendingSpan {
            trace_id,
            span_id:        ctx.itara_span_id.clone(),
            parent_span_id,
            name:           format!("{}.{}", component, method),
            kind:           3, // CLIENT
            start_nanos:    timestamp,
            attributes:     build_attrs(ctx, component, method, transport),
        });
    }

    fn on_call_received(
        &self, ctx: &ItaraContext, component: &str,
        method: &str, transport: &str, timestamp: u64,
    ) {
        // Check for a W3C parent set by restore_context() for this thread.
        // If present, use it to link the SERVER span to the remote caller's OTel
        // span — this is what makes cross-language traces appear unified in Kibana.
        let tid = std::thread::current().id();
        let (trace_id, parent_span_id) = {
            let mut inbound = self.pending_inbound_parent.lock().unwrap();
            if let Some(w3c) = inbound.remove(&tid) {
                (w3c.trace_id, Some(w3c.span_id))
            } else {
                (ctx.itara_trace_id.clone(), ctx.itara_parent_span_id.clone())
            }
        };

        self.callee_spans.lock().unwrap().insert(ctx.itara_span_id.clone(), PendingSpan {
            trace_id,
            span_id:        ctx.itara_span_id.clone(),
            parent_span_id,
            name:           format!("{}.{}", component, method),
            kind:           2, // SERVER
            start_nanos:    timestamp,
            attributes:     build_attrs(ctx, component, method, transport),
        });
    }

    fn on_return_sent(
        &self, ctx: &ItaraContext, _c: &str, _m: &str, timestamp: u64, error: bool,
    ) {
        if let Some(p) = self.callee_spans.lock().unwrap().remove(&ctx.itara_span_id) {
            self.send(p.complete(timestamp, error));
        }
    }

    fn on_return_received(
        &self, ctx: &ItaraContext, _c: &str, _m: &str, timestamp: u64, error: bool,
    ) {
        if let Some(p) = self.caller_spans.lock().unwrap().remove(&ctx.itara_span_id) {
            self.send(p.complete(timestamp, error));
        }
        // Clean up the thread's caller context — this span is now closed.
        self.current_caller_per_thread.lock().unwrap()
            .remove(&std::thread::current().id());
    }

    fn serialize_context(&self) -> HashMap<String, String> {
        // Produce a W3C traceparent from the CLIENT span most recently opened
        // on this thread. Empty if no outbound call is in progress.
        let tid = std::thread::current().id();
        let current = self.current_caller_per_thread.lock().unwrap();
        if let Some((trace_id, span_id)) = current.get(&tid) {
            let mut headers = HashMap::new();
            headers.insert(
                "traceparent".to_string(),
                format!("00-{}-{}-01", trace_id, span_id),
            );
            headers
        } else {
            HashMap::new()
        }
    }

    fn restore_context(&self, headers: &HashMap<String, String>) {
        // Extract the W3C traceparent from inbound headers and store it for
        // on_call_received to use as the OTel parent for the SERVER span.
        // This is what links a Rust SERVER span to a remote Java CLIENT span.
        if let Some(traceparent) = headers.get("traceparent") {
            if let Some(w3c) = parse_w3c_traceparent(traceparent) {
                self.pending_inbound_parent.lock().unwrap()
                    .insert(std::thread::current().id(), w3c);
            }
        }
    }

    fn on_inbound_context_released(&self) {
        // Safety cleanup — remove any leftover inbound parent for this thread.
        // Normally consumed by on_call_received, but cleans up if something
        // went wrong before on_call_received fired.
        self.pending_inbound_parent.lock().unwrap()
            .remove(&std::thread::current().id());
    }

    fn flush(&self) {
        let _ = self.tx.send(Message::Flush);
        // Block until the exporter has had time to POST
        std::thread::sleep(Duration::from_millis(2000));
    }
}

// ── Attribute helpers ─────────────────────────────────────────────────────────

fn build_attrs(ctx: &ItaraContext, component: &str, method: &str, transport: &str) -> Vec<(String, String)> {
    let mut a = vec![
        ("itara.component".into(), component.into()),
        ("itara.method".into(),    method.into()),
        ("itara.transport".into(), transport.into()),
        ("itara.request.id".into(), ctx.request_id.clone()),
        // Itara-native IDs as custom attributes — allow cross-observer correlation
        // (e.g. finding the logging observer entry that matches this OTel span)
        // without timestamp matching or heuristics.
        ("itara.trace.id".into(),   ctx.itara_trace_id.clone()),
        ("itara.span.id".into(),    ctx.itara_span_id.clone()),
    ];
    if !ctx.edge_path.is_empty() {
        a.push(("itara.edge.path".into(), ctx.edge_path.join(" -> ")));
    }
    if let Some(c) = &ctx.correlation_id { a.push(("itara.correlation".into(), c.clone())); }
    if let Some(n) = &ctx.source_node    { a.push(("itara.source.node".into(), n.clone())); }
    a
}

fn parse_w3c_traceparent(traceparent: &str) -> Option<W3CParent> {
    let parts: Vec<&str> = traceparent.split('-').collect();
    if parts.len() < 4 { return None; }
    Some(W3CParent {
        trace_id: parts[1].to_string(),
        span_id:  parts[2].to_string(),
    })
}

// ── Background export thread ──────────────────────────────────────────────────

fn spawn_exporter(
    rx:         mpsc::Receiver<Message>,
    endpoint:   String,
    service:    String,
    batch_size: usize,
    timeout_ms: u64,
) {
    std::thread::Builder::new()
        .name("itara-otlp-exporter".into())
        .spawn(move || {
            let client  = reqwest::blocking::Client::builder()
                .timeout(Duration::from_secs(5))
                .build()
                .expect("[Itara/OTLP] Failed to build HTTP client");
            let url     = format!("{}/v1/traces", endpoint);
            let timeout = Duration::from_millis(timeout_ms);
            let mut batch: Vec<CompletedSpan> = Vec::with_capacity(batch_size);

            loop {
                let flush = match rx.recv_timeout(timeout) {
                    Ok(Message::Span(span)) => {
                        batch.push(span);
                        // Drain immediately available spans
                        while batch.len() < batch_size {
                            match rx.try_recv() {
                                Ok(Message::Span(s)) => batch.push(s),
                                Ok(Message::Flush)   => break,
                                Err(_)               => break,
                            }
                        }
                        batch.len() >= batch_size
                    }
                    Ok(Message::Flush) => true,
                    Err(mpsc::RecvTimeoutError::Timeout) => !batch.is_empty(),
                    Err(mpsc::RecvTimeoutError::Disconnected) => {
                        if !batch.is_empty() { export(&client, &url, &service, &mut batch); }
                        break;
                    }
                };

                if flush && !batch.is_empty() {
                    export(&client, &url, &service, &mut batch);
                }
            }
        })
        .expect("[Itara/OTLP] Failed to spawn exporter thread");
}

fn export(client: &reqwest::blocking::Client, url: &str, service: &str, batch: &mut Vec<CompletedSpan>) {
    let count   = batch.len();
    let payload = build_payload(service, batch);
    batch.clear();

    println!("[Itara/OTLP] Exporting {} span(s) to {}", count, url);

    match client.post(url).header("Content-Type", "application/json").json(&payload).send() {
        Ok(resp) if resp.status().is_success() => {
            println!("[Itara/OTLP] Export OK ({} spans)", count);
        }
        Ok(resp) => {
            let status = resp.status();
            let body   = resp.text().unwrap_or_default();
            eprintln!("[Itara/OTLP] Export failed: HTTP {} — {}", status, body);
        }
        Err(e) => eprintln!("[Itara/OTLP] Export error: {}", e),
    }
}

// ── OTLP JSON ─────────────────────────────────────────────────────────────────

fn build_payload(service: &str, spans: &[CompletedSpan]) -> serde_json::Value {
    let span_jsons: Vec<serde_json::Value> = spans.iter().map(|s| {
        let mut obj = serde_json::json!({
            "traceId":           s.trace_id,
            "spanId":            s.span_id,
            "name":              s.name,
            "kind":              s.kind,
            "startTimeUnixNano": s.start_nanos.to_string(),
            "endTimeUnixNano":   s.end_nanos.to_string(),
            "attributes":        build_attr_json(&s.attributes),
            "status": if s.error {
                serde_json::json!({"code": 2, "message": "error"})
            } else {
                serde_json::json!({"code": 1})
            },
        });
        if let Some(p) = &s.parent_span_id {
            obj["parentSpanId"] = serde_json::Value::String(p.clone());
        }
        obj
    }).collect();

    serde_json::json!({
        "resourceSpans": [{
            "resource": {
                "attributes": [{
                    "key": "service.name",
                    "value": { "stringValue": service }
                }]
            },
            "scopeSpans": [{
                "scope": { "name": "io.itara", "version": "0.1.0" },
                "spans": span_jsons
            }]
        }]
    })
}

fn build_attr_json(attrs: &[(String, String)]) -> serde_json::Value {
    serde_json::Value::Array(attrs.iter().map(|(k, v)| serde_json::json!({
        "key": k, "value": { "stringValue": v }
    })).collect())
}

// ── cdylib export ─────────────────────────────────────────────────────────────

#[unsafe(no_mangle)]
pub extern "C" fn itara_observer_factory() -> Box<dyn ItaraObserver> {
    let endpoint   = std::env::var("ITARA_OTLP_ENDPOINT").unwrap_or_else(|_| "http://localhost:4318".into());
    let service    = std::env::var("ITARA_OTLP_SERVICE").unwrap_or_else(|_| "itara".into());
    let batch_size = std::env::var("ITARA_OTLP_BATCH").ok().and_then(|v| v.parse().ok()).unwrap_or(50usize);
    let timeout_ms = std::env::var("ITARA_OTLP_TIMEOUT").ok().and_then(|v| v.parse().ok()).unwrap_or(500u64);

    println!("[Itara/OTLP] Observer starting — endpoint={} service={} batch={} timeout={}ms",
        endpoint, service, batch_size, timeout_ms);
    Box::new(OtlpObserver::new(&endpoint, &service, batch_size, timeout_ms))
}
