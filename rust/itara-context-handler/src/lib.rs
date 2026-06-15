use std::cell::RefCell;
use itara_core::{
    ItaraContext, ItaraContextHandler,
};

// ── ThreadLocalContextHandler ─────────────────────────────────────────────────
//
// Implements ItaraContextHandler using a thread local span stack.
//
// This cdylib is loaded exactly once by the agent. Because the thread local
// lives inside this cdylib's code — not in an rlib that gets compiled into
// every API cdylib — there is exactly one copy of the stack per thread,
// regardless of how many proxy or dispatcher DLLs are loaded.
//
// The stack is a Vec<ItaraContext> where the last element is the current span.
// push() adds a child of the current span (or a root if empty).
// pop() removes the current span, restoring the parent.
//
// Future: an async variant using tokio::task_local! follows the same pattern
// with a different storage primitive, behind the same trait.

thread_local! {
    static SPAN_STACK: RefCell<Vec<ItaraContext>> = RefCell::new(Vec::new());
}

pub struct ThreadLocalContextHandler;

impl ItaraContextHandler for ThreadLocalContextHandler {
    fn push(
        &self,
        component: &str,
        method:    &str,
        _transport: &str,
    ) -> ItaraContext {
        SPAN_STACK.with(|stack| {
            let mut stack = stack.borrow_mut();
            let ctx = match stack.last() {
                Some(parent) => parent.new_outbound_span(),
                None         => ItaraContext::new_root(component),
            };
            // Log which method we're entering for debugging
            let _ = method; // used by the proxy for observability — not needed here
            stack.push(ctx.clone());
            ctx
        })
    }

    fn push_incoming(
        &self,
        incoming:  Option<ItaraContext>,
        component: &str,
        _method:   &str,
        _transport: &str,
    ) -> ItaraContext {
        SPAN_STACK.with(|stack| {
            let mut stack = stack.borrow_mut();
            // On the callee side we restore the incoming context directly —
            // it already has the correct trace_id, parent_span_id, etc.
            // from the W3C headers. We don't create a child here because
            // context_from_headers already generated a fresh span_id.
            let ctx = incoming.unwrap_or_else(|| ItaraContext::new_root(component));
            stack.push(ctx.clone());
            ctx
        })
    }

    fn pop(&self) {
        SPAN_STACK.with(|stack| {
            stack.borrow_mut().pop();
        });
    }

    fn current(&self) -> Option<ItaraContext> {
        SPAN_STACK.with(|stack| {
            stack.borrow().last().cloned()
        })
    }
}

// ── cdylib export ─────────────────────────────────────────────────────────────

#[unsafe(no_mangle)]
pub extern "C" fn itara_context_handler_factory() -> Box<dyn ItaraContextHandler> {
    Box::new(ThreadLocalContextHandler)
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn handler() -> ThreadLocalContextHandler {
        // Clear stack before each test — tests share thread local
        SPAN_STACK.with(|s| s.borrow_mut().clear());
        ThreadLocalContextHandler
    }

    #[test]
    fn empty_stack_returns_none() {
        let h = handler();
        assert!(h.current().is_none());
    }

    #[test]
    fn push_creates_root_on_empty_stack() {
        let h = handler();
        let ctx = h.push("gateway", "calculate", "direct");
        assert!(ctx.itara_parent_span_id.is_none());
        assert_eq!(ctx.source_node.as_deref(), Some("gateway"));
        assert!(h.current().is_some());
    }

    #[test]
    fn push_creates_child_when_parent_on_stack() {
        let h = handler();
        let root  = h.push("gateway",    "calculate", "direct");
        let child = h.push("calculator", "add",       "direct");
        assert_eq!(child.itara_trace_id,   root.itara_trace_id);
        assert_eq!(child.request_id, root.request_id);
        assert_eq!(child.itara_parent_span_id.as_deref(), Some(root.itara_span_id.as_str()));
        // push() does NOT extend edge_path — that happens at CALL_RECEIVED
        assert!(child.edge_path.is_empty());
    }

    #[test]
    fn pop_restores_parent() {
        let h = handler();
        let root = h.push("gateway",    "calculate", "direct");
        let _    = h.push("calculator", "add",       "direct");
        h.pop();
        let current = h.current().unwrap();
        assert_eq!(current.itara_span_id, root.itara_span_id);
    }

    #[test]
    fn pop_on_empty_stack_does_not_panic() {
        let h = handler();
        h.pop(); // should not panic
    }

    #[test]
    fn push_incoming_restores_remote_context() {
        let h        = handler();
        let remote   = ItaraContext::new_root("remote-gateway");
        let trace_id = remote.itara_trace_id.clone();
        let ctx      = h.push_incoming(Some(remote), "calculator", "add", "http");
        assert_eq!(ctx.itara_trace_id, trace_id);
        assert!(h.current().is_some());
    }

    #[test]
    fn push_incoming_creates_root_when_none() {
        let h   = handler();
        let ctx = h.push_incoming(None, "calculator", "add", "http");
        assert!(ctx.itara_parent_span_id.is_none());
    }

    #[test]
    fn three_level_chain_unwinds_correctly() {
        let h = handler();
        let a = h.push("a", "m", "direct");
        let b = h.push("b", "m", "direct");
        let c = h.push("c", "m", "direct");

        assert_eq!(c.itara_parent_span_id.as_deref(), Some(b.itara_span_id.as_str()));
        assert_eq!(b.itara_parent_span_id.as_deref(), Some(a.itara_span_id.as_str()));
        assert!(a.itara_parent_span_id.is_none());
        // edge_path not extended by push — stays empty
        assert!(a.edge_path.is_empty());
        assert!(b.edge_path.is_empty());
        assert!(c.edge_path.is_empty());

        h.pop(); // c done
        assert_eq!(h.current().unwrap().itara_span_id, b.itara_span_id);
        h.pop(); // b done
        assert_eq!(h.current().unwrap().itara_span_id, a.itara_span_id);
        h.pop(); // a done
        assert!(h.current().is_none());
    }
}
