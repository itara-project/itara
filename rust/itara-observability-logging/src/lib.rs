use std::collections::HashMap;
use std::sync::Mutex;
use itara_core::{ItaraContext, ItaraObserver};

// ── LoggingObserver ───────────────────────────────────────────────────────────
//
// Observer that logs every Itara event to stdout.
//
// Not loaded by default. Add the cdylib and .itara file to the lib dir to
// enable logging output. Useful for development, debugging, and as a reference
// implementation for custom observers.
//
// Calculates two durations:
//   - Total latency:  on_call_sent → on_return_received (caller side)
//   - Execution time: on_call_received → on_return_sent (callee side)
//
// Both are keyed by span_id. Network time = total latency - execution time,
// but that calculation spans two processes and is left to higher-level tooling.

pub struct LoggingObserver {
    /// span_id → timestamp of on_call_sent. Used to calculate total latency.
    call_sent_times:     Mutex<HashMap<String, u64>>,
    /// span_id → timestamp of on_call_received. Used to calculate execution time.
    call_received_times: Mutex<HashMap<String, u64>>,
}

impl LoggingObserver {
    pub fn new() -> Self {
        LoggingObserver {
            call_sent_times:     Mutex::new(HashMap::new()),
            call_received_times: Mutex::new(HashMap::new()),
        }
    }
}

impl ItaraObserver for LoggingObserver {
    fn on_call_sent(
        &self,
        ctx:       &ItaraContext,
        component: &str,
        method:    &str,
        transport: &str,
        timestamp: u64,
    ) {
        self.call_sent_times
            .lock().unwrap()
            .insert(ctx.span_id.clone(), timestamp);

        println!(
            "[Itara/obs] CALL_SENT     {}.{} transport={}{}",
            component, method, transport, format_trace(ctx)
        );
    }

    fn on_call_received(
        &self,
        ctx:       &ItaraContext,
        component: &str,
        method:    &str,
        transport: &str,
        timestamp: u64,
    ) {
        self.call_received_times
            .lock().unwrap()
            .insert(ctx.span_id.clone(), timestamp);

        println!(
            "[Itara/obs] CALL_RECEIVED {}.{} transport={}{}",
            component, method, transport, format_trace(ctx)
        );
    }

    fn on_return_sent(
        &self,
        ctx:       &ItaraContext,
        component: &str,
        method:    &str,
        timestamp: u64,
        error:     bool,
    ) {
        let execution = self.call_received_times
            .lock().unwrap()
            .remove(&ctx.span_id)
            .map(|start| format!(" execution={}ns", timestamp - start))
            .unwrap_or_default();

        println!(
            "[Itara/obs] RETURN_SENT   {}.{}{}{}{}",
            component, method,
            format_trace(ctx),
            execution,
            if error { " ERROR" } else { "" }
        );
    }

    fn on_return_received(
        &self,
        ctx:       &ItaraContext,
        component: &str,
        method:    &str,
        timestamp: u64,
        error:     bool,
    ) {
        let latency = self.call_sent_times
            .lock().unwrap()
            .remove(&ctx.span_id)
            .map(|start| format!(" latency={}ns", timestamp - start))
            .unwrap_or_default();

        println!(
            "[Itara/obs] RETURN_RECV   {}.{}{}{}{}",
            component, method,
            format_trace(ctx),
            latency,
            if error { " ERROR" } else { "" }
        );
    }
}

fn format_trace(ctx: &ItaraContext) -> String {
    let mut s = format!(
        " traceId={} spanId={}",
        ctx.trace_id, ctx.span_id
    );
    if let Some(parent) = &ctx.parent_span_id {
        s.push_str(&format!(" parentSpanId={}", parent));
    }
    s.push_str(&format!(" edgePath={:?}", ctx.edge_path));
    s
}

// ── cdylib export ─────────────────────────────────────────────────────────────

#[unsafe(no_mangle)]
pub extern "C" fn itara_observer_factory() -> Box<dyn ItaraObserver> {
    Box::new(LoggingObserver::new())
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use itara_core::monotonic_nanos;

    fn ctx(source: &str) -> ItaraContext {
        ItaraContext::new_root(source)
    }

    #[test]
    fn call_sent_and_return_received_calculate_latency() {
        let obs = LoggingObserver::new();
        let ctx = ctx("gateway");
        let t0  = monotonic_nanos();

        obs.on_call_sent(&ctx, "calculator", "add", "http", t0);
        assert!(obs.call_sent_times.lock().unwrap().contains_key(&ctx.span_id));

        obs.on_return_received(&ctx, "calculator", "add", t0 + 1_000_000, false);
        // Entry removed after on_return_received
        assert!(!obs.call_sent_times.lock().unwrap().contains_key(&ctx.span_id));
    }

    #[test]
    fn call_received_and_return_sent_calculate_execution() {
        let obs = LoggingObserver::new();
        let ctx = ctx("calculator");
        let t0  = monotonic_nanos();

        obs.on_call_received(&ctx, "calculator", "add", "http", t0);
        assert!(obs.call_received_times.lock().unwrap().contains_key(&ctx.span_id));

        obs.on_return_sent(&ctx, "calculator", "add", t0 + 500_000, false);
        assert!(!obs.call_received_times.lock().unwrap().contains_key(&ctx.span_id));
    }

    #[test]
    fn missing_start_time_produces_no_duration() {
        // on_return_received without a prior on_call_sent — no panic, no duration
        let obs = LoggingObserver::new();
        let ctx = ctx("gateway");
        obs.on_return_received(&ctx, "calculator", "add", monotonic_nanos(), false);
    }

    #[test]
    fn error_flag_logged() {
        let obs = LoggingObserver::new();
        let ctx = ctx("gateway");
        let t0  = monotonic_nanos();
        obs.on_call_received(&ctx, "calculator", "add", "http", t0);
        // Should not panic on error=true
        obs.on_return_sent(&ctx, "calculator", "add", t0 + 1000, true);
    }
}
