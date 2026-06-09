use std::any::{Any, TypeId};
use std::sync::Arc;
use itara_core::{
    ItaraContext, ItaraComponent, ItaraTransport, Dispatcher,
    ItaraContextHandler, ObservabilityFacade, SpanGuard,
    context_to_headers, context_from_headers,
};
use itara_serializer_json as json;
 
// ── HandlerPtr ────────────────────────────────────────────────────────────────
 
struct HandlerPtr(*const dyn ItaraContextHandler);
unsafe impl Send for HandlerPtr {}
unsafe impl Sync for HandlerPtr {}
 
impl HandlerPtr {
    fn words(&self) -> (*const (), *const ()) {
        unsafe { std::mem::transmute(self.0) }
    }
 
    unsafe fn push(&self, component: &str, method: &str, transport: &str) -> ItaraContext {
        (*self.0).push(component, method, transport)
    }
 
    unsafe fn push_incoming(
        &self, incoming: Option<ItaraContext>, component: &str,
        method: &str, transport: &str,
    ) -> ItaraContext {
        (*self.0).push_incoming(incoming, component, method, transport)
    }
}
 
// ── Contract ──────────────────────────────────────────────────────────────────
 
pub trait PaymentService: ItaraComponent {
    fn process_payment(&self, order_id: String, amount_cents: i64, currency: String) -> bool;
}
 
// ── HTTP proxy ────────────────────────────────────────────────────────────────
 
pub struct PaymentServiceProxy {
    transport:     Box<dyn ItaraTransport>,
    component_id:  String,
    serializer_id: String,
    facade:        Arc<ObservabilityFacade>,
    handler:       HandlerPtr,
}
 
impl PaymentServiceProxy {
    pub fn new(
        transport:     Box<dyn ItaraTransport>,
        component_id:  &str,
        serializer_id: &str,
        facade:        Arc<ObservabilityFacade>,
        handler:       *const dyn ItaraContextHandler,
    ) -> Self {
        PaymentServiceProxy {
            transport,
            component_id:  component_id.to_string(),
            serializer_id: serializer_id.to_string(),
            facade,
            handler: HandlerPtr(handler),
        }
    }
 
    fn call(&self, method: &str, payload: Vec<u8>) -> Vec<u8> {
        let ctx = unsafe { self.handler.push(&self.component_id, method, "http") };
        let (data, vtable) = self.handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };
 
        self.facade.fire_call_sent(Some(&ctx), &self.component_id, method, "http");
 
        let (traceparent, tracestate) = context_to_headers(&ctx);
 
        let response = self.transport.invoke(
            &self.component_id, method, &payload,
            &traceparent, &tracestate,
        );
 
        self.facade.fire_return_received(&ctx, &self.component_id, method, false);
 
        response
    }
}
 
impl PaymentService for PaymentServiceProxy {
    fn process_payment(&self, order_id: String, amount_cents: i64, currency: String) -> bool {
        let payload  = serialize_args_process_payment(&self.serializer_id, order_id, amount_cents, currency);
        let response = self.call("process_payment", payload);
        deserialize_return_bool(&self.serializer_id, &response)
    }
}
 
impl ItaraComponent for PaymentServiceProxy {
    fn as_any(&self) -> &dyn Any { self }
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn PaymentService>() {
            let fat = self as &dyn PaymentService as *const dyn PaymentService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}
 
// ── Direct proxy ──────────────────────────────────────────────────────────────
 
pub struct PaymentServiceDirectProxy {
    _owner:  Box<dyn ItaraComponent>,
    inner:   *const dyn PaymentService,
    facade:  Arc<ObservabilityFacade>,
    handler: HandlerPtr,
}
 
unsafe impl Send for PaymentServiceDirectProxy {}
unsafe impl Sync for PaymentServiceDirectProxy {}
 
impl PaymentServiceDirectProxy {
    fn service(&self) -> &dyn PaymentService {
        unsafe { &*self.inner }
    }
}
 
impl PaymentService for PaymentServiceDirectProxy {
    fn process_payment(&self, order_id: String, amount_cents: i64, currency: String) -> bool {
        let caller_ctx = unsafe { self.handler.push("payment", "process_payment", "direct") };
        let (data, vtable) = self.handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };
 
        self.facade.fire_call_sent(Some(&caller_ctx), "payment", "process_payment", "direct");
 
        let callee_ctx = caller_ctx.new_callee_span("payment");
        self.facade.fire_call_received(Some(callee_ctx.clone()), "payment", "process_payment", "direct");
 
        let result = self.service().process_payment(order_id, amount_cents, currency);
 
        self.facade.fire_return_sent(&callee_ctx, "payment", "process_payment", false);
        self.facade.fire_return_received(&caller_ctx, "payment", "process_payment", false);
 
        result
    }
}
 
impl ItaraComponent for PaymentServiceDirectProxy {
    fn as_any(&self) -> &dyn Any { self }
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn PaymentService>() {
            let fat = self as &dyn PaymentService as *const dyn PaymentService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}
 
// ── cdylib symbols ────────────────────────────────────────────────────────────
 
#[unsafe(no_mangle)]
pub extern "C" fn itara_create_direct_proxy_payment(
    data:    *const (),
    vtable:  *const (),
    facade:  *const ObservabilityFacade,
    handler: *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent> {
    let fat: (*const (), *const ()) = (data, vtable);
    let fat_ptr: *mut dyn ItaraComponent = unsafe { std::mem::transmute_copy(&fat) };
    let owner: Box<dyn ItaraComponent> = unsafe { Box::from_raw(fat_ptr) };
 
    let api_fat = owner
        .cast_to(TypeId::of::<dyn PaymentService>())
        .unwrap_or_else(|| panic!(
            "[PaymentApi] itara_create_direct_proxy: component does not implement PaymentService"
        ));
    let inner: *const dyn PaymentService = unsafe { std::mem::transmute_copy(&api_fat) };
 
    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);
 
    Box::new(PaymentServiceDirectProxy {
        _owner:  owner,
        inner,
        facade:  facade_clone,
        handler: HandlerPtr(handler),
    })
}
 
#[unsafe(no_mangle)]
pub extern "C" fn itara_create_proxy_payment(
    transport:     Box<dyn ItaraTransport>,
    serializer_id: *const std::os::raw::c_char,
    facade:        *const ObservabilityFacade,
    handler:       *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent> {
    let id = unsafe { std::ffi::CStr::from_ptr(serializer_id) }
        .to_str()
        .expect("[PaymentApi] invalid serializer_id");
 
    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);
 
    Box::new(PaymentServiceProxy::new(transport, "payment", id, facade_clone, handler))
}
 
#[unsafe(no_mangle)]
pub extern "C" fn itara_create_dispatcher_payment(
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
        .cast_to(TypeId::of::<dyn PaymentService>())
        .unwrap_or_else(|| panic!(
            "[PaymentApi] Component does not implement PaymentService."
        ));
 
    let api_fat_ptr: *const dyn PaymentService =
        unsafe { std::mem::transmute_copy(&api_fat) };
    let service: &'static dyn PaymentService = unsafe { &*api_fat_ptr };
 
    let id = unsafe { std::ffi::CStr::from_ptr(serializer_id) }
        .to_str()
        .expect("[PaymentApi] invalid serializer_id")
        .to_string();
 
    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);
 
    payment_dispatcher(service, id, facade_clone, handler)
}
 
// ── Dispatcher ────────────────────────────────────────────────────────────────
 
pub fn payment_dispatcher(
    component:     &'static dyn PaymentService,
    serializer_id: String,
    facade:        Arc<ObservabilityFacade>,
    handler:       *const dyn ItaraContextHandler,
) -> Dispatcher {
    let handler = HandlerPtr(handler);
    Box::new(move |method: &str, args: &[u8], traceparent: &str, tracestate: &str| -> Vec<u8> {
        let incoming = context_from_headers(
            Some(traceparent).filter(|s| !s.is_empty()),
            Some(tracestate).filter(|s| !s.is_empty()),
        );
 
        let callee_ctx = incoming
            .unwrap_or_else(|| ItaraContext::new_root("payment"))
            .new_callee_span("payment");
 
        let ctx = unsafe {
            handler.push_incoming(Some(callee_ctx), "payment", method, "http")
        };
 
        let (data, vtable) = handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };
 
        facade.fire_call_received(Some(ctx.clone()), "payment", method, "http");
 
        let result = dispatch(component, method, args, &serializer_id);
 
        facade.fire_return_sent(&ctx, "payment", method, false);
 
        result
    })
}
 
fn dispatch(
    component:     &dyn PaymentService,
    method:        &str,
    args:          &[u8],
    serializer_id: &str,
) -> Vec<u8> {
    match method {
        "process_payment" => {
            let (order_id, amount_cents, currency) =
                deserialize_args_process_payment(serializer_id, args);
            serialize_return_bool(serializer_id, component.process_payment(order_id, amount_cents, currency))
        }
        unknown => panic!("[PaymentDispatcher] unknown method: '{}'", unknown),
    }
}
 
// ── Serialization ─────────────────────────────────────────────────────────────
 
fn serialize_args_process_payment(
    serializer_id: &str,
    order_id: String, amount_cents: i64, currency: String,
) -> Vec<u8> {
    match serializer_id {
        "json" => json::serialize(&(order_id, amount_cents, currency)),
        u => panic!("[PaymentProxy] process_payment: unsupported serializer '{}'", u),
    }
}
 
fn deserialize_args_process_payment(serializer_id: &str, bytes: &[u8]) -> (String, i64, String) {
    match serializer_id {
        "json" => json::deserialize::<(String, i64, String)>(bytes),
        u => panic!("[PaymentDispatcher] process_payment: unsupported serializer '{}'", u),
    }
}
 
fn deserialize_return_bool(serializer_id: &str, bytes: &[u8]) -> bool {
    match serializer_id {
        "json" => json::deserialize::<bool>(bytes),
        u => panic!("[PaymentProxy] deserialize_return: unsupported serializer '{}'", u),
    }
}
 
fn serialize_return_bool(serializer_id: &str, value: bool) -> Vec<u8> {
    match serializer_id {
        "json" => json::serialize(&value),
        u => panic!("[PaymentDispatcher] serialize_return: unsupported serializer '{}'", u),
    }
}
