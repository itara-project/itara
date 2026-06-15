use std::collections::HashMap;

// ── ItaraContext ──────────────────────────────────────────────────────────────
//
// Immutable context object that travels with every request through the Itara
// topology. Within a process it is passed explicitly. Across process boundaries
// it travels via W3C Trace Context headers.
//
// No thread local lives here — the context is always passed explicitly to
// avoid the static duplication problem that arises with cdylibs.

/// Immutable request context. Created at the call site, passed through the
/// proxy and dispatcher, propagated across process boundaries via W3C headers.
#[derive(Debug, Clone)]
pub struct ItaraContext {
    /// 32 hex chars — stable across the entire request chain
    pub itara_trace_id: String,
    /// 16 hex chars — unique per component invocation
    pub itara_span_id: String,
    /// 16 hex chars — the caller's itara_span_id, None for root spans
    pub itara_parent_span_id: Option<String>,
    /// Unique per originating request
    pub request_id: String,
    /// Business-level identifier, optionally set by the entry point caller
    pub correlation_id: Option<String>,
    /// Component id where this request originated
    pub source_node: Option<String>,
    /// Ordered list of component ids traversed by this request so far
    pub edge_path: Vec<String>,
}

impl ItaraContext {
    /// Creates a new root context. Generates fresh trace_id, span_id, request_id.
    pub fn new_root(source_node: &str) -> Self {
        ItaraContext {
            itara_trace_id:      generate_trace_id(),
            itara_span_id:       generate_span_id(),
            itara_parent_span_id: None,
            request_id:          generate_request_id(),
            correlation_id:      None,
            source_node:         Some(source_node.to_string()),
            edge_path:           Vec::new(),
        }
    }

    /// Creates a new root context with an explicit correlation_id.
    pub fn new_root_with_correlation(source_node: &str, correlation_id: &str) -> Self {
        ItaraContext {
            correlation_id: Some(correlation_id.to_string()),
            ..Self::new_root(source_node)
        }
    }

    /// Creates a child context for a call crossing a component boundary.
    /// Inherits trace_id and request_id. Generates a new span_id.
    /// Creates a callee-side span for a direct call — used for CALL_RECEIVED.
    /// The edge path IS extended here because CALL_RECEIVED marks the moment
    /// the component boundary is entered, consistent with the Java implementation
    /// where onCallReceived calls newChildSpan(componentId).
    pub fn new_callee_span(&self, component_id: &str) -> Self {
        let mut new_path = self.edge_path.clone();
        new_path.push(component_id.to_string());
        ItaraContext {
            itara_trace_id:      self.itara_trace_id.clone(),
            itara_span_id:       generate_span_id(),
            itara_parent_span_id: Some(self.itara_span_id.clone()),
            request_id:          self.request_id.clone(),
            correlation_id:      self.correlation_id.clone(),
            source_node:         self.source_node.clone(),
            edge_path:           new_path,
        }
    }

    /// Creates a caller-side span for an outbound call — used for CALL_SENT.
    /// Does NOT extend the edge path — edge path is extended on CALL_RECEIVED
    /// when the component boundary is actually entered, consistent with Java.
    pub fn new_outbound_span(&self) -> Self {
        ItaraContext {
            itara_trace_id:      self.itara_trace_id.clone(),
            itara_span_id:       generate_span_id(),
            itara_parent_span_id: Some(self.itara_span_id.clone()),
            request_id:          self.request_id.clone(),
            correlation_id:      self.correlation_id.clone(),
            source_node:         self.source_node.clone(),
            edge_path:           self.edge_path.clone(),
        }
    }

    pub fn new_child_span(&self, next_component_id: &str) -> Self {
        let mut new_path = self.edge_path.clone();
        new_path.push(next_component_id.to_string());
        ItaraContext {
            itara_trace_id:      self.itara_trace_id.clone(),
            itara_span_id:       generate_span_id(),
            itara_parent_span_id: Some(self.itara_span_id.clone()),
            request_id:          self.request_id.clone(),
            correlation_id:      self.correlation_id.clone(),
            source_node:         self.source_node.clone(),
            edge_path:           new_path,
        }
    }

    /// Restores a context received from a remote caller.
    pub fn restore(
        itara_trace_id:      String,
        itara_span_id:       String,
        itara_parent_span_id: Option<String>,
        request_id:          String,
        correlation_id:      Option<String>,
        source_node:         Option<String>,
        edge_path:           Vec<String>,
    ) -> Self {
        ItaraContext {
            itara_trace_id, itara_span_id, itara_parent_span_id,
            request_id, correlation_id, source_node, edge_path,
        }
    }
}

impl std::fmt::Display for ItaraContext {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "ItaraContext{{itara_trace_id={}, itara_span_id={}, \
                   itara_parent_span_id={:?}, request_id={}, \
                   source_node={:?}, edge_path={:?}}}",
               self.itara_trace_id, self.itara_span_id, self.itara_parent_span_id,
               self.request_id, self.source_node, self.edge_path)
    }
}

// ── ID generation ─────────────────────────────────────────────────────────────

pub fn generate_trace_id() -> String {
    format!("{:032x}", uuid::Uuid::new_v4().as_u128())
}

pub fn generate_span_id() -> String {
    format!("{:016x}", uuid::Uuid::new_v4().as_u128() >> 64)
}

pub fn generate_request_id() -> String {
    uuid::Uuid::new_v4().to_string()
}

// ── ContextPropagation ────────────────────────────────────────────────────────
//
// Header protocol — Itara-native headers, independent of W3C Trace Context.
// Observers that maintain their own propagation model (e.g. OTel W3C headers)
// handle their own headers via ItaraObserver::serialize_context() /
// restore_context() — ContextPropagation is only responsible for Itara fields.
//
//   x-itara-trace-id   — itara_trace_id, always present
//   x-itara-span-id    — itara_span_id,  always present
//   x-itara-request-id — request_id,     always present
//   x-itara-correlation — correlation_id, omitted when None
//   x-itara-source-node — source_node,   omitted when None
//   x-itara-edge-path  — comma-separated edge_path, omitted when empty

pub const HEADER_ITARA_TRACE_ID:    &str = "x-itara-trace-id";
pub const HEADER_ITARA_SPAN_ID:     &str = "x-itara-span-id";
pub const HEADER_ITARA_REQUEST_ID:  &str = "x-itara-request-id";
pub const HEADER_ITARA_CORRELATION: &str = "x-itara-correlation";
pub const HEADER_ITARA_SOURCE_NODE: &str = "x-itara-source-node";
pub const HEADER_ITARA_EDGE_PATH:   &str = "x-itara-edge-path";

/// Serializes the context into Itara-native transport headers.
/// Merged into the outbound header map by ObservabilityFacade::build_outbound_headers.
pub fn context_to_headers(ctx: &ItaraContext) -> HashMap<String, String> {
    let mut headers = HashMap::new();
    headers.insert(HEADER_ITARA_TRACE_ID.to_string(),   ctx.itara_trace_id.clone());
    headers.insert(HEADER_ITARA_SPAN_ID.to_string(),    ctx.itara_span_id.clone());
    headers.insert(HEADER_ITARA_REQUEST_ID.to_string(), ctx.request_id.clone());
    if let Some(c) = &ctx.correlation_id {
        headers.insert(HEADER_ITARA_CORRELATION.to_string(), c.clone());
    }
    if let Some(n) = &ctx.source_node {
        headers.insert(HEADER_ITARA_SOURCE_NODE.to_string(), n.clone());
    }
    if !ctx.edge_path.is_empty() {
        headers.insert(HEADER_ITARA_EDGE_PATH.to_string(), ctx.edge_path.join(","));
    }
    headers
}

/// Deserializes an ItaraContext from inbound transport headers.
///
/// The returned context represents the caller's context and is used as the
/// parent by the dispatcher when calling new_callee_span(). itara_parent_span_id
/// is not propagated — it belongs to the caller's trace.
///
/// Returns None when Itara headers are absent (external call with no Itara context).
/// The dispatcher creates a root context in that case.
pub fn context_from_headers(headers: &HashMap<String, String>) -> Option<ItaraContext> {
    let itara_trace_id = headers.get(HEADER_ITARA_TRACE_ID)?.clone();
    let itara_span_id  = headers.get(HEADER_ITARA_SPAN_ID)?.clone();

    let request_id     = headers.get(HEADER_ITARA_REQUEST_ID)
        .cloned()
        .unwrap_or_else(generate_request_id);
    let correlation_id = headers.get(HEADER_ITARA_CORRELATION).cloned();
    let source_node    = headers.get(HEADER_ITARA_SOURCE_NODE).cloned();
    let edge_path      = headers.get(HEADER_ITARA_EDGE_PATH)
        .map(|s| s.split(',').map(String::from).collect())
        .unwrap_or_default();

    Some(ItaraContext::restore(
        itara_trace_id, itara_span_id, None,
        request_id, correlation_id, source_node, edge_path,
    ))
}

// ── ItaraObserver ─────────────────────────────────────────────────────────────

/// Observer SPI for Itara runtime events.
///
/// Four lifecycle events fire for every component interaction regardless of
/// transport type, including direct (colocated) calls. Two additional
/// header-exchange methods fire only when a call crosses a transport boundary.
/// All methods have default no-op implementations.
///
/// Header exchange:
///   serialize_context and restore_context let an observer maintain its own
///   propagation model across process boundaries — independently of
///   ItaraContext, which propagates itara_trace_id/itara_span_id on its own.
///   Observers with no cross-process propagation needs do not override these.
///
///   These methods are skipped entirely for direct (colocated) calls.
///
/// OTel integration:
///   OpenTelemetry is a regular ItaraObserver (itara-observability-otel),
///   using serialize_context and restore_context to maintain its own W3C
///   trace context across process boundaries.
pub trait ItaraObserver: Send + Sync {
    #[allow(unused_variables)]
    fn on_call_sent(
        &self, ctx: &ItaraContext, component: &str,
        method: &str, transport: &str, timestamp: u64,
    ) {}

    /// Called after on_call_sent, only for non-direct transports.
    /// Returns header entries this observer wants sent with the outbound request.
    /// Default returns an empty map.
    fn serialize_context(&self) -> HashMap<String, String> {
        HashMap::new()
    }

    /// Called before on_call_received, only for non-direct transports.
    /// Receives all inbound headers so the observer can rebuild its own
    /// propagation state (e.g. OTel W3C parent span linkage).
    #[allow(unused_variables)]
    fn restore_context(&self, headers: &HashMap<String, String>) {}

    /// Called when the inbound transport scope is fully released —
    /// after response serialization, not just after business logic.
    /// Counterpart to restore_context: clean up any state opened there.
    /// Only fires for non-direct (remote) calls.
    fn on_inbound_context_released(&self) {}

    #[allow(unused_variables)]
    fn on_call_received(
        &self, ctx: &ItaraContext, component: &str,
        method: &str, transport: &str, timestamp: u64,
    ) {}

    #[allow(unused_variables)]
    fn on_return_sent(
        &self, ctx: &ItaraContext, component: &str,
        method: &str, timestamp: u64, error: bool,
    ) {}

    #[allow(unused_variables)]
    fn on_return_received(
        &self, ctx: &ItaraContext, component: &str,
        method: &str, timestamp: u64, error: bool,
    ) {}

    /// Flush any buffered spans and block until export completes.
    /// Called by the agent before process exit. Default is a no-op.
    fn flush(&self) {}
}

/// Factory function signature exported by every observer cdylib.
pub type ObserverFactoryFn = unsafe extern "C" fn() -> Box<dyn ItaraObserver>;

/// Load an observer cdylib and return an instance.
pub fn load_observer(lib_path: &str) -> Result<Box<dyn ItaraObserver>, String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load observer '{}': {}", lib_path, e))?;

        let factory: libloading::Symbol<ObserverFactoryFn> = lib
            .get(b"itara_observer_factory\0")
            .map_err(|e| format!("[Itara] No itara_observer_factory in '{}': {}", lib_path, e))?;

        let factory_fn: ObserverFactoryFn = *factory;
        std::mem::forget(lib);
        Ok(factory_fn())
    }
}

// ── Timestamp ─────────────────────────────────────────────────────────────────

/// Returns nanoseconds since UNIX epoch.
/// Used by the facade to timestamp events — all observers for the same
/// event receive the same value.
pub fn monotonic_nanos() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64
}


// ── ObservabilityFacade ───────────────────────────────────────────────────────
//
// Single point of contact for all observability events in the Itara runtime.
//
// Owned by the agent, constructed after observers are loaded, passed into
// every proxy and dispatcher factory at startup. No singleton — the facade
// is passed explicitly, same as the transport and serializer.
//
// Responsibility split (without OTel):
//   ObservabilityFacade — captures timestamps, fans out to passive observers
//   ItaraObserver SPI   — passive receivers, multiple active simultaneously
//
// OTel bridge is deliberately absent here — added later as a separate concern.
// When OTel is added it will sit alongside the observer fan-out, not replace it.
//
// Caller side:
//   let ctx = facade.fire_call_sent(parent_ctx, component, method, transport);
//   // ... make the call ...
//   facade.fire_return_received(&ctx, component, method, error);
//
// Callee side:
//   let ctx = facade.fire_call_received(incoming_ctx, component, method, transport);
//   // ... handle the call ...
//   facade.fire_return_sent(&ctx, component, method, error);

pub struct ObservabilityFacade {
    observers: Vec<Box<dyn ItaraObserver>>,
}

impl ObservabilityFacade {
    /// Construct a facade with the given observer list.
    /// Called by the agent after all observer cdylibs are loaded.
    /// An empty list is valid — the system runs without observability output.
    pub fn new(observers: Vec<Box<dyn ItaraObserver>>) -> Self {
        ObservabilityFacade { observers }
    }

    /// Returns true if no observers are registered.
    /// Proxies and dispatchers may use this to skip context creation
    /// when there is nothing to observe.
    pub fn is_empty(&self) -> bool {
        self.observers.is_empty()
    }

    // ── Caller side ───────────────────────────────────────────────────────

    /// Fires CALL_SENT.
    ///
    /// ctx — the context already pushed onto the handler's stack by the proxy.
    /// The handler owns context lifecycle. The facade only fires the event.
    /// None is accepted for backward compatibility — a root context is created
    /// if no context is provided, but callers should always pass the handler's
    /// context in normal operation.
    pub fn fire_call_sent(
        &self,
        ctx:       Option<&ItaraContext>,
        component: &str,
        method:    &str,
        transport: &str,
    ) {
        let timestamp = monotonic_nanos();
        if let Some(ctx) = ctx {
            self.fan_out(|obs| obs.on_call_sent(ctx, component, method, transport, timestamp));
        }
    }

    /// Fires RETURN_RECEIVED.
    ///
    /// call_ctx — the context returned by fire_call_sent for this call.
    /// error    — true if the call resulted in a panic or error.
    pub fn fire_return_received(
        &self,
        call_ctx:  &ItaraContext,
        component: &str,
        method:    &str,
        error:     bool,
    ) {
        let timestamp = monotonic_nanos();
        self.fan_out(|obs| obs.on_return_received(call_ctx, component, method, timestamp, error));
    }

    // ── Callee side ───────────────────────────────────────────────────────

    /// Fires CALL_RECEIVED.
    ///
    /// ctx — the context already pushed onto the handler's stack by the dispatcher.
    /// The handler owns context lifecycle. The facade only fires the event.
    pub fn fire_call_received(
        &self,
        ctx:       Option<ItaraContext>,
        component: &str,
        method:    &str,
        transport: &str,
    ) {
        let timestamp = monotonic_nanos();
        if let Some(ctx) = ctx {
            self.fan_out(|obs| obs.on_call_received(&ctx, component, method, transport, timestamp));
        }
    }

    /// Fires RETURN_SENT.
    ///
    /// call_ctx — the context returned by fire_call_received for this call.
    /// error    — true if processing resulted in a panic or error.
    pub fn fire_return_sent(
        &self,
        call_ctx:  &ItaraContext,
        component: &str,
        method:    &str,
        error:     bool,
    ) {
        let timestamp = monotonic_nanos();
        self.fan_out(|obs| obs.on_return_sent(call_ctx, component, method, timestamp, error));
    }

    // ── Outbound headers ──────────────────────────────────────────────────

    /// Assembles the full outbound header map for a non-direct call.
    /// Merges Itara-native headers with each observer's serialize_context().
    /// Call after fire_call_sent, before invoking the transport.
    pub fn build_outbound_headers(&self, ctx: &ItaraContext) -> HashMap<String, String> {
        let mut headers = context_to_headers(ctx);
        for obs in &self.observers {
            let result = std::panic::catch_unwind(
                std::panic::AssertUnwindSafe(|| obs.serialize_context())
            );
            match result {
                Ok(obs_headers) => headers.extend(obs_headers),
                Err(e) => {
                    let msg = e.downcast_ref::<&str>().copied().unwrap_or("unknown panic");
                    eprintln!("[Itara] Observer panicked on serialize_context: {}", msg);
                }
            }
        }
        headers
    }

    /// Notifies all observers of an inbound context restoration.
    /// Call after context_from_headers and before fire_call_received,
    /// for non-direct (remote) calls only.
    pub fn notify_restore_context(&self, headers: &HashMap<String, String>) {
        self.fan_out(|obs| obs.restore_context(headers));
    }

    /// Notifies all observers that the inbound scope is fully released.
    /// Call at the end of the dispatcher closure, after response serialization.
    /// For non-direct (remote) calls only.
    pub fn notify_inbound_context_released(&self) {
        self.fan_out(|obs| obs.on_inbound_context_released());
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /// Fan out an event to all observers.
    /// A panic in one observer is caught and logged — it must never prevent
    /// delivery to remaining observers or affect the call path.
    /// Flush all observers — call before process exit to ensure buffered spans are exported.
    pub fn flush(&self) {
        for obs in &self.observers {
            obs.flush();
        }
    }

    fn fan_out(&self, f: impl Fn(&dyn ItaraObserver)) {
        for observer in &self.observers {
            // catch_unwind requires AssertUnwindSafe because Box<dyn ItaraObserver>
            // is not UnwindSafe by default. This is intentional — observers are
            // external code and we cannot trust them not to panic.
            let result = std::panic::catch_unwind(
                std::panic::AssertUnwindSafe(|| f(observer.as_ref()))
            );
            if let Err(e) = result {
                let msg = e.downcast_ref::<&str>()
                    .copied()
                    .unwrap_or("unknown panic");
                eprintln!("[Itara] Observer panicked on event: {}", msg);
            }
        }
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_root_generates_valid_ids() {
        let ctx = ItaraContext::new_root("gateway");
        assert_eq!(ctx.itara_trace_id.len(), 32);
        assert_eq!(ctx.itara_span_id.len(), 16);
        assert!(ctx.itara_parent_span_id.is_none());
        assert_eq!(ctx.source_node.as_deref(), Some("gateway"));
        assert!(ctx.edge_path.is_empty());
    }

    #[test]
    fn child_span_inherits_trace_id() {
        let root  = ItaraContext::new_root("gateway");
        let child = root.new_child_span("calculator");
        assert_eq!(child.itara_trace_id,   root.itara_trace_id);
        assert_eq!(child.request_id,       root.request_id);
        assert_ne!(child.itara_span_id,    root.itara_span_id);
        assert_eq!(child.itara_parent_span_id.as_deref(), Some(root.itara_span_id.as_str()));
        assert_eq!(child.edge_path, vec!["calculator"]);
    }

    #[test]
    fn header_round_trip_full() {
        let ctx   = ItaraContext::new_root_with_correlation("gateway", "order-123");
        let child = ctx.new_child_span("calculator");
        let headers  = context_to_headers(&child);
        let restored = context_from_headers(&headers).unwrap();
        assert_eq!(restored.itara_trace_id, child.itara_trace_id);
        assert_eq!(restored.itara_span_id,  child.itara_span_id);
        assert_eq!(restored.request_id,     child.request_id);
        assert_eq!(restored.correlation_id.as_deref(), Some("order-123"));
        assert_eq!(restored.source_node.as_deref(),    Some("gateway"));
    }

    #[test]
    fn header_round_trip_minimal() {
        let ctx      = ItaraContext::new_root("gateway");
        let headers  = context_to_headers(&ctx);
        let restored = context_from_headers(&headers).unwrap();
        assert_eq!(restored.itara_trace_id, ctx.itara_trace_id);
    }

    #[test]
    fn missing_itara_headers_returns_none() {
        // Empty map — no Itara headers present (external call with no context)
        assert!(context_from_headers(&HashMap::new()).is_none());
    }

    #[test]
    fn observer_default_methods_are_noop() {
        struct TestObserver;
        impl ItaraObserver for TestObserver {}
        let o   = TestObserver;
        let ctx = ItaraContext::new_root("test");
        o.on_call_sent(&ctx, "calc", "add", "direct", 0);
        o.on_call_received(&ctx, "calc", "add", "direct", 0);
        o.on_return_sent(&ctx, "calc", "add", 0, false);
        o.on_return_received(&ctx, "calc", "add", 0, false);
    }

    #[test]
    fn edge_path_propagates_through_headers() {
        let root   = ItaraContext::new_root("gateway");
        let child1 = root.new_child_span("service-a");
        let child2 = child1.new_child_span("service-b");
        let headers  = context_to_headers(&child2);
        let restored = context_from_headers(&headers).unwrap();
        assert_eq!(restored.edge_path, vec!["service-a", "service-b"]);
    }

    // ── ObservabilityFacade tests ─────────────────────────────────────────

    #[test]
    fn facade_with_no_observers_does_not_panic() {
        let facade = ObservabilityFacade::new(vec![]);
        assert!(facade.is_empty());
        let ctx  = ItaraContext::new_root("calculator");
        let ctx2 = ItaraContext::new_root("calculator");
        facade.fire_call_sent(Some(&ctx), "calculator", "add", "direct");
        facade.fire_return_received(&ctx, "calculator", "add", false);
        facade.fire_call_received(Some(ctx2.clone()), "calculator", "add", "direct");
        facade.fire_return_sent(&ctx2, "calculator", "add", false);
    }
 
    #[test]
    fn fire_call_sent_fans_out_to_observers() {
        // fire_call_sent no longer returns a context — it fans out to observers.
        // Verify it does not panic and accepts an optional parent context.
        let facade = ObservabilityFacade::new(vec![]);
        let root   = ItaraContext::new_root("gateway");
        facade.fire_call_sent(None, "gateway", "calculate", "direct");
        facade.fire_call_sent(Some(&root), "calculator", "add", "http");
    }
 
    #[test]
    fn fire_call_received_fans_out_to_observers() {
        // fire_call_received no longer returns a context — verify no panic.
        let facade   = ObservabilityFacade::new(vec![]);
        let incoming = ItaraContext::new_root("gateway");
        facade.fire_call_received(None, "calculator", "add", "http");
        facade.fire_call_received(Some(incoming), "calculator", "add", "http");
    }
 
    #[test]
    fn context_child_span_carries_trace_and_request_ids() {
        let parent = ItaraContext::new_root("gateway");
        let child  = parent.new_outbound_span();
        assert_eq!(child.itara_trace_id,   parent.itara_trace_id);
        assert_eq!(child.request_id,       parent.request_id);
        assert_eq!(child.itara_parent_span_id.as_deref(), Some(parent.itara_span_id.as_str()));
    }

    #[test]
    fn new_root_has_no_parent() {
        let ctx = ItaraContext::new_root("gateway");
        assert!(ctx.itara_parent_span_id.is_none());
    }
}

// ── ItaraContextHandler ───────────────────────────────────────────────────────
//
// Trait defining the context handler SPI. Lives in itara-core so every crate
// knows it at compile time. The implementation lives in a separate cdylib
// loaded once by the agent — this ensures one thread local, one stack,
// regardless of how many API cdylibs are loaded.
//
// The handler owns the per-thread span stack. Proxies push on entry and pop
// on exit (via SpanGuard). Dispatchers restore an incoming context before
// pushing, so the callee side always has the correct parent.
//
// user instrumentation: any code holding a *const dyn ItaraContextHandler
// can call current() to read the active context and instrument its own spans.

pub trait ItaraContextHandler: Send + Sync {
    /// Push a new child span onto the current thread's stack.
    /// If the stack is empty, creates a root span.
    /// Returns the new context — the caller does not need to store it;
    /// SpanGuard pops automatically on drop.
    fn push(
        &self,
        component:  &str,
        method:     &str,
        transport:  &str,
    ) -> ItaraContext;

    /// Push a restored context (from inbound W3C headers) onto the stack.
    /// Used by the dispatcher on the callee side to restore the caller's span
    /// before the component runs. If incoming is None, creates a root span.
    fn push_incoming(
        &self,
        incoming:  Option<ItaraContext>,
        component: &str,
        method:    &str,
        transport: &str,
    ) -> ItaraContext;

    /// Pop the current span from the stack, restoring the parent.
    /// Called by SpanGuard::drop — should not be called manually.
    fn pop(&self);

    /// Return a clone of the current active context, if any.
    /// Returns None if the stack is empty (no active call on this thread).
    fn current(&self) -> Option<ItaraContext>;
}

/// Factory function signature exported by every context handler cdylib.
pub type ContextHandlerFactoryFn = unsafe extern "C" fn() -> Box<dyn ItaraContextHandler>;

/// Load a context handler cdylib and return an instance.
pub fn load_context_handler(lib_path: &str) -> Result<Box<dyn ItaraContextHandler>, String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load context handler '{}': {}", lib_path, e))?;

        let factory: libloading::Symbol<ContextHandlerFactoryFn> = lib
            .get(b"itara_context_handler_factory\0")
            .map_err(|e| format!(
                "[Itara] No itara_context_handler_factory in '{}': {}", lib_path, e
            ))?;

        let factory_fn: ContextHandlerFactoryFn = *factory;
        std::mem::forget(lib);
        Ok(factory_fn())
    }
}

// ── SpanGuard ─────────────────────────────────────────────────────────────────
//
// RAII guard that pops the span stack on drop. Ensures the stack is always
// balanced even if the component panics mid-call.
//
// Usage in the generated proxy:
//
//   let ctx   = self.handler.push("calculator", "add", "http");
//   let _guard = SpanGuard::new(self.handler);
//   // ... make the call ...
//   // _guard drops here, popping the stack whether we return or panic

pub struct SpanGuard {
    // Stored as two opaque pointer words — never as *const dyn Trait —
    // so the closure that holds SpanGuard has no !Send/!Sync raw fat pointer.
    // Reconstructed in Drop to call pop().
    data:   *const (),
    vtable: *const (),
}

impl SpanGuard {
    /// Construct from the two fat pointer words of a *const dyn ItaraContextHandler.
    /// Accepts (data, vtable) directly so call sites never hold the fat pointer.
    ///
    /// # Safety
    /// (data, vtable) must be the two words of a valid *const dyn ItaraContextHandler
    /// that remains valid for the lifetime of this guard.
    pub unsafe fn from_words(data: *const (), vtable: *const ()) -> Self {
        SpanGuard { data, vtable }
    }
}

impl Drop for SpanGuard {
    fn drop(&mut self) {
        let fat: (*const (), *const ()) = (self.data, self.vtable);
        let handler: *const dyn ItaraContextHandler = unsafe { std::mem::transmute(fat) };
        unsafe { (*handler).pop() };
    }
}

// SAFETY: SpanGuard is only ever created and dropped on the same thread
// within a single synchronous call frame.
unsafe impl Send for SpanGuard {}
unsafe impl Sync for SpanGuard {}
