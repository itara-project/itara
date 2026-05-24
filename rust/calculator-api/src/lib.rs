use std::any::{Any, TypeId};
use std::sync::Arc;
use itara_core::{
    ItaraContext, ItaraComponent, ItaraTransport, Dispatcher,
    ItaraContextHandler, ObservabilityFacade, SpanGuard,
    context_to_headers, context_from_headers,
};
use itara_serializer_json as json;

// ── HandlerPtr ────────────────────────────────────────────────────────────────
//
// Thin newtype that asserts Send + Sync for *const dyn ItaraContextHandler.
//
// SAFETY: The pointer is owned by the agent and valid for process lifetime.
// ItaraContextHandler requires Send + Sync, so the pointee is safe to share
// and send across threads. The raw pointer itself lacks these impls by default
// in Rust — this wrapper restores them.

struct HandlerPtr(*const dyn ItaraContextHandler);
unsafe impl Send for HandlerPtr {}
unsafe impl Sync for HandlerPtr {}

impl HandlerPtr {
    /// Returns the two fat pointer words — use with SpanGuard::from_words
    /// so the raw fat pointer never appears inside closures.
    fn words(&self) -> (*const (), *const ()) {
        unsafe { std::mem::transmute(self.0) }
    }

    /// Call push on the underlying handler.
    unsafe fn push(&self, component: &str, method: &str, transport: &str) -> ItaraContext {
        (*self.0).push(component, method, transport)
    }

    /// Call push_incoming on the underlying handler.
    unsafe fn push_incoming(
        &self, incoming: Option<ItaraContext>, component: &str,
        method: &str, transport: &str,
    ) -> ItaraContext {
        (*self.0).push_incoming(incoming, component, method, transport)
    }
}

// ── Contract ──────────────────────────────────────────────────────────────────

pub trait CalculatorService: ItaraComponent {
    fn add(&self, a: i64, b: i64) -> i64;
    fn multiply(&self, a: i64, b: i64) -> i64;
}

// ── Generated proxy (future: #[itara_component] macro output) ─────────────────

pub struct CalculatorServiceProxy {
    transport:     Box<dyn ItaraTransport>,
    component_id:  String,
    serializer_id: String,
    facade:        Arc<ObservabilityFacade>,
    handler:       HandlerPtr,
}

impl CalculatorServiceProxy {
    pub fn new(
        transport:     Box<dyn ItaraTransport>,
        component_id:  &str,
        serializer_id: &str,
        facade:        Arc<ObservabilityFacade>,
        handler:       *const dyn ItaraContextHandler,
    ) -> Self {
        CalculatorServiceProxy {
            transport,
            component_id:  component_id.to_string(),
            serializer_id: serializer_id.to_string(),
            facade,
            handler: HandlerPtr(handler),
        }
    }

    fn call(&self, method: &str, payload: Vec<u8>) -> Vec<u8> {
        // Push a new span onto the handler's thread local stack.
        // The handler creates a child of the current span (or a root if empty),
        // so context chains correctly through N levels of component calls.
        let ctx = unsafe { self.handler.push(&self.component_id, method, "http") };

        // SpanGuard pops the stack on drop — even if the call panics.
        let (data, vtable) = self.handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };

        // Fire CALL_SENT with the context the handler just created.
        self.facade.fire_call_sent(Some(&ctx), &self.component_id, method, "http");

        // Encode the context into W3C headers for the transport.
        let (traceparent, tracestate) = context_to_headers(&ctx);

        let response = self.transport.invoke(
            &self.component_id, method, &payload,
            &traceparent, &tracestate,
        );

        // RETURN_RECEIVED — span closes on the caller side.
        // _guard hasn't dropped yet so the context is still on the stack.
        self.facade.fire_return_received(&ctx, &self.component_id, method, false);

        // _guard drops here, popping the stack and restoring the parent span.
        response
    }
}

impl CalculatorService for CalculatorServiceProxy {
    fn add(&self, a: i64, b: i64) -> i64 {
        let payload  = serialize_args_add(&self.serializer_id, a, b);
        let response = self.call("add", payload);
        deserialize_return_i64(&self.serializer_id, &response)
    }

    fn multiply(&self, a: i64, b: i64) -> i64 {
        let payload  = serialize_args_multiply(&self.serializer_id, a, b);
        let response = self.call("multiply", payload);
        deserialize_return_i64(&self.serializer_id, &response)
    }
}

impl ItaraComponent for CalculatorServiceProxy {
    fn as_any(&self) -> &dyn Any { self }
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn CalculatorService>() {
            let fat = self as &dyn CalculatorService as *const dyn CalculatorService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}

// ── Direct proxy (future: #[itara_component] macro output) ──────────────────
//
// Wraps a local component implementation. Used when topology is "direct" —
// both caller and callee in the same process. Fires all four observability
// events around the real method call, with transport="direct".

pub struct CalculatorServiceDirectProxy {
    // _owner keeps the original Box alive so inner remains valid.
    // inner is derived from _owner and must not outlive it.
    _owner:  Box<dyn ItaraComponent>,
    inner:   *const dyn CalculatorService,
    facade:  Arc<ObservabilityFacade>,
    handler: HandlerPtr,
}

unsafe impl Send for CalculatorServiceDirectProxy {}
unsafe impl Sync for CalculatorServiceDirectProxy {}

impl CalculatorServiceDirectProxy {
    fn service(&self) -> &dyn CalculatorService {
        unsafe { &*self.inner }
    }
}

impl CalculatorService for CalculatorServiceDirectProxy {
    fn add(&self, a: i64, b: i64) -> i64 {
        // Caller span — pushed onto the handler stack
        let caller_ctx = unsafe { self.handler.push("calculator", "add", "direct") };
        let (data, vtable) = self.handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };

        self.facade.fire_call_sent(Some(&caller_ctx), "calculator", "add", "direct");

        // Callee span — child of caller, represents the calculator's view of the call
        let callee_ctx = caller_ctx.new_callee_span("calculator");
        self.facade.fire_call_received(Some(callee_ctx.clone()), "calculator", "add", "direct");

        let result = self.service().add(a, b);

        self.facade.fire_return_sent(&callee_ctx, "calculator", "add", false);
        self.facade.fire_return_received(&caller_ctx, "calculator", "add", false);

        result
    }

    fn multiply(&self, a: i64, b: i64) -> i64 {
        // Caller span
        let caller_ctx = unsafe { self.handler.push("calculator", "multiply", "direct") };
        let (data, vtable) = self.handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };

        self.facade.fire_call_sent(Some(&caller_ctx), "calculator", "multiply", "direct");

        // Callee span — child of caller
        let callee_ctx = caller_ctx.new_callee_span("calculator");
        self.facade.fire_call_received(Some(callee_ctx.clone()), "calculator", "multiply", "direct");

        let result = self.service().multiply(a, b);

        self.facade.fire_return_sent(&callee_ctx, "calculator", "multiply", false);
        self.facade.fire_return_received(&caller_ctx, "calculator", "multiply", false);

        result
    }
}

impl ItaraComponent for CalculatorServiceDirectProxy {
    fn as_any(&self) -> &dyn Any { self }
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn CalculatorService>() {
            let fat = self as &dyn CalculatorService as *const dyn CalculatorService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}

// ── API cdylib symbols ────────────────────────────────────────────────────────

/// Create a direct proxy wrapping a local component implementation.
/// Takes ownership of the Box<dyn ItaraComponent> to prevent use-after-free
/// when the registry replaces the original instance.
///
/// # Safety
/// - (data, vtable) must be the fat pointer words of a Box<dyn ItaraComponent>
///   that was extracted via Box::into_raw by the agent. Ownership is transferred.
/// - facade and handler must be valid process-lifetime pointers.
#[unsafe(no_mangle)]
pub extern "C" fn itara_create_direct_proxy_calculator(
    data:    *const (),
    vtable:  *const (),
    facade:  *const ObservabilityFacade,
    handler: *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent> {
    let fat: (*const (), *const ()) = (data, vtable);
    let fat_ptr: *mut dyn ItaraComponent = unsafe { std::mem::transmute_copy(&fat) };
    let owner: Box<dyn ItaraComponent> = unsafe { Box::from_raw(fat_ptr) };

    let api_fat = owner
        .cast_to(TypeId::of::<dyn CalculatorService>())
        .unwrap_or_else(|| panic!(
            "[CalculatorApi] itara_create_direct_proxy: component does not implement CalculatorService"
        ));
    let inner: *const dyn CalculatorService = unsafe { std::mem::transmute_copy(&api_fat) };

    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);

    Box::new(CalculatorServiceDirectProxy {
        _owner:  owner,
        inner,
        facade:  facade_clone,
        handler: HandlerPtr(handler),
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn itara_create_proxy_calculator(
    transport:     Box<dyn ItaraTransport>,
    serializer_id: *const std::os::raw::c_char,
    facade:        *const ObservabilityFacade,
    handler:       *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent> {
    let id = unsafe { std::ffi::CStr::from_ptr(serializer_id) }
        .to_str()
        .expect("[CalculatorApi] invalid serializer_id");

    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);

    Box::new(CalculatorServiceProxy::new(transport, "calculator", id, facade_clone, handler))
    // handler is stored as HandlerPtr internally
}

#[unsafe(no_mangle)]
pub extern "C" fn itara_create_dispatcher_calculator(
    data:          *const (),
    vtable:        *const (),
    serializer_id: *const std::os::raw::c_char,
    facade:        *const ObservabilityFacade,
    handler:       *const dyn ItaraContextHandler,
) -> Dispatcher {
    let fat: (*const (), *const ()) = (data, vtable);
    let fat_ptr: *const dyn ItaraComponent = unsafe { std::mem::transmute_copy(&fat) };
    let component: &'static dyn ItaraComponent = unsafe { &*fat_ptr };

    let api_fat = component
        .cast_to(TypeId::of::<dyn CalculatorService>())
        .unwrap_or_else(|| panic!(
            "[CalculatorApi] Component does not implement CalculatorService."
        ));

    let api_fat_ptr: *const dyn CalculatorService =
        unsafe { std::mem::transmute_copy(&api_fat) };
    let service: &'static dyn CalculatorService = unsafe { &*api_fat_ptr };

    let id = unsafe { std::ffi::CStr::from_ptr(serializer_id) }
        .to_str()
        .expect("[CalculatorApi] invalid serializer_id")
        .to_string();

    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);

    calculator_dispatcher(service, id, facade_clone, handler)
}

// ── Generated dispatcher ──────────────────────────────────────────────────────

pub fn calculator_dispatcher(
    component:     &'static dyn CalculatorService,
    serializer_id: String,
    facade:        Arc<ObservabilityFacade>,
    handler:       *const dyn ItaraContextHandler,
) -> Dispatcher {
    let handler = HandlerPtr(handler);
    Box::new(move |method: &str, args: &[u8], traceparent: &str, tracestate: &str| -> Vec<u8> {
        // Restore the incoming context from W3C headers.
        let incoming = context_from_headers(
            Some(traceparent).filter(|s| !s.is_empty()),
            Some(tracestate).filter(|s| !s.is_empty()),
        );

        let callee_ctx = incoming.unwrap_or_else(|| ItaraContext::new_root("calculator")).new_callee_span("calculator");

        // Push the restored context onto the handler's stack.
        // If the component makes further outbound calls, their proxies will
        // see this as the parent and create correct child spans.
        let ctx = unsafe {
            handler.push_incoming(Some(callee_ctx), "calculator", method, "http")
        };

        // SpanGuard pops on drop — even if component panics.
        let (data, vtable) = handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };

        // CALL_RECEIVED — callee span opens.
        facade.fire_call_received(Some(ctx.clone()), "calculator", method, "http");

        let result = dispatch(component, method, args, &serializer_id);

        // RETURN_SENT — callee span closes.
        facade.fire_return_sent(&ctx, "calculator", method, false);

        // _guard drops here, popping the stack.
        result
    })
}

fn dispatch(
    component:     &dyn CalculatorService,
    method:        &str,
    args:          &[u8],
    serializer_id: &str,
) -> Vec<u8> {
    match method {
        "add" => {
            let (a, b) = deserialize_args_add(serializer_id, args);
            serialize_return_i64(serializer_id, component.add(a, b))
        }
        "multiply" => {
            let (a, b) = deserialize_args_multiply(serializer_id, args);
            serialize_return_i64(serializer_id, component.multiply(a, b))
        }
        unknown => panic!("[CalculatorDispatcher] unknown method: '{}'", unknown),
    }
}

// ── Serialization dispatch ────────────────────────────────────────────────────

fn serialize_args_add(serializer_id: &str, a: i64, b: i64) -> Vec<u8> {
    match serializer_id {
        "json" => json::serialize(&[a, b]),
        u => panic!("[CalculatorProxy] add: unsupported serializer '{}'", u),
    }
}

fn serialize_args_multiply(serializer_id: &str, a: i64, b: i64) -> Vec<u8> {
    match serializer_id {
        "json" => json::serialize(&[a, b]),
        u => panic!("[CalculatorProxy] multiply: unsupported serializer '{}'", u),
    }
}

fn deserialize_return_i64(serializer_id: &str, bytes: &[u8]) -> i64 {
    match serializer_id {
        "json" => json::deserialize::<i64>(bytes),
        u => panic!("[CalculatorProxy] deserialize_return: unsupported serializer '{}'", u),
    }
}

fn deserialize_args_add(serializer_id: &str, bytes: &[u8]) -> (i64, i64) {
    match serializer_id {
        "json" => { let v: [i64; 2] = json::deserialize(bytes); (v[0], v[1]) }
        u => panic!("[CalculatorDispatcher] add: unsupported serializer '{}'", u),
    }
}

fn deserialize_args_multiply(serializer_id: &str, bytes: &[u8]) -> (i64, i64) {
    match serializer_id {
        "json" => { let v: [i64; 2] = json::deserialize(bytes); (v[0], v[1]) }
        u => panic!("[CalculatorDispatcher] multiply: unsupported serializer '{}'", u),
    }
}

fn serialize_return_i64(serializer_id: &str, value: i64) -> Vec<u8> {
    match serializer_id {
        "json" => json::serialize(&value),
        u => panic!("[CalculatorDispatcher] serialize_return: unsupported serializer '{}'", u),
    }
}
