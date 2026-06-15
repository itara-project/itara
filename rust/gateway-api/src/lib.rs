use std::any::{Any, TypeId};
use std::sync::Arc;
use std::collections::HashMap;
use itara_core::{
    ItaraContext, ItaraComponent, ItaraTransport, Dispatcher,
    ItaraContextHandler, ObservabilityFacade, SpanGuard,
    context_from_headers,
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

pub trait GatewayService: ItaraComponent {
    fn calculate(&self, op: &str, a: i64, b: i64) -> i64;
}

// ── HTTP Proxy ────────────────────────────────────────────────────────────────

pub struct GatewayServiceProxy {
    transport:     Box<dyn ItaraTransport>,
    component_id:  String,
    serializer_id: String,
    facade:        Arc<ObservabilityFacade>,
    handler:       HandlerPtr,
}

impl GatewayServiceProxy {
    pub fn new(
        transport:     Box<dyn ItaraTransport>,
        component_id:  &str,
        serializer_id: &str,
        facade:        Arc<ObservabilityFacade>,
        handler:       *const dyn ItaraContextHandler,
    ) -> Self {
        GatewayServiceProxy {
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

        let headers = self.facade.build_outbound_headers(&ctx);
        let response = self.transport.invoke(
            &self.component_id, method, &payload,
            &headers,
        );

        self.facade.fire_return_received(&ctx, &self.component_id, method, false);
        response
    }
}

impl GatewayService for GatewayServiceProxy {
    fn calculate(&self, op: &str, a: i64, b: i64) -> i64 {
        let payload  = serialize_args_calculate(&self.serializer_id, op, a, b);
        let response = self.call("calculate", payload);
        deserialize_return_i64(&self.serializer_id, &response)
    }
}

impl ItaraComponent for GatewayServiceProxy {
    fn as_any(&self) -> &dyn Any { self }
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn GatewayService>() {
            let fat = self as &dyn GatewayService as *const dyn GatewayService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}

// ── Direct proxy ──────────────────────────────────────────────────────────────

pub struct GatewayServiceDirectProxy {
    _owner:  Box<dyn ItaraComponent>,
    inner:   *const dyn GatewayService,
    facade:  Arc<ObservabilityFacade>,
    handler: HandlerPtr,
}

unsafe impl Send for GatewayServiceDirectProxy {}
unsafe impl Sync for GatewayServiceDirectProxy {}

impl GatewayServiceDirectProxy {
    fn service(&self) -> &dyn GatewayService {
        unsafe { &*self.inner }
    }
}

impl GatewayService for GatewayServiceDirectProxy {
    fn calculate(&self, op: &str, a: i64, b: i64) -> i64 {
        let caller_ctx = unsafe { self.handler.push("gateway", "calculate", "direct") };
        let (data, vtable) = self.handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };

        self.facade.fire_call_sent(Some(&caller_ctx), "gateway", "calculate", "direct");

        let callee_ctx = caller_ctx.new_callee_span("gateway");
        unsafe { self.handler.push_incoming(Some(callee_ctx.clone()), "gateway", "calculate", "direct") };
        let (cd, cv) = self.handler.words();
        let _callee_guard = unsafe { SpanGuard::from_words(cd, cv) };
        self.facade.fire_call_received(Some(callee_ctx.clone()), "gateway", "calculate", "direct");

        let result = self.service().calculate(op, a, b);

        self.facade.fire_return_sent(&callee_ctx, "gateway", "calculate", false);
        self.facade.fire_return_received(&caller_ctx, "gateway", "calculate", false);

        result
    }
}

impl ItaraComponent for GatewayServiceDirectProxy {
    fn as_any(&self) -> &dyn Any { self }
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn GatewayService>() {
            let fat = self as &dyn GatewayService as *const dyn GatewayService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}

// ── API cdylib symbols ────────────────────────────────────────────────────────

#[unsafe(no_mangle)]
pub extern "C" fn itara_create_proxy_gateway(
    transport:     Box<dyn ItaraTransport>,
    serializer_id: *const std::os::raw::c_char,
    facade:        *const ObservabilityFacade,
    handler:       *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent> {
    let id = unsafe { std::ffi::CStr::from_ptr(serializer_id) }
        .to_str()
        .expect("[GatewayApi] invalid serializer_id");

    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);

    Box::new(GatewayServiceProxy::new(transport, "gateway", id, facade_clone, handler))
}

#[unsafe(no_mangle)]
pub extern "C" fn itara_create_dispatcher_gateway(
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
        .cast_to(TypeId::of::<dyn GatewayService>())
        .unwrap_or_else(|| panic!(
            "[GatewayApi] Component does not implement GatewayService."
        ));

    let api_fat_ptr: *const dyn GatewayService =
        unsafe { std::mem::transmute_copy(&api_fat) };
    let service: &'static dyn GatewayService = unsafe { &*api_fat_ptr };

    let id = unsafe { std::ffi::CStr::from_ptr(serializer_id) }
        .to_str()
        .expect("[GatewayApi] invalid serializer_id")
        .to_string();

    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);

    gateway_dispatcher(service, id, facade_clone, handler)
}

#[unsafe(no_mangle)]
pub extern "C" fn itara_create_direct_proxy_gateway(
    data:    *const (),
    vtable:  *const (),
    facade:  *const ObservabilityFacade,
    handler: *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent> {
    let fat: (*const (), *const ()) = (data, vtable);
    let fat_ptr: *mut dyn ItaraComponent = unsafe { std::mem::transmute_copy(&fat) };
    let owner: Box<dyn ItaraComponent> = unsafe { Box::from_raw(fat_ptr) };

    let api_fat = owner
        .cast_to(TypeId::of::<dyn GatewayService>())
        .unwrap_or_else(|| panic!(
            "[GatewayApi] itara_create_direct_proxy: component does not implement GatewayService"
        ));
    let inner: *const dyn GatewayService = unsafe { std::mem::transmute_copy(&api_fat) };

    let facade = unsafe { Arc::from_raw(facade) };
    let facade_clone = Arc::clone(&facade);
    std::mem::forget(facade);

    Box::new(GatewayServiceDirectProxy {
        _owner:  owner,
        inner,
        facade:  facade_clone,
        handler: HandlerPtr(handler),
    })
}

// ── Dispatcher ────────────────────────────────────────────────────────────────

pub fn gateway_dispatcher(
    component:     &'static dyn GatewayService,
    serializer_id: String,
    facade:        Arc<ObservabilityFacade>,
    handler:       *const dyn ItaraContextHandler,
) -> Dispatcher {
    let handler = HandlerPtr(handler);
    Box::new(move |method: &str, args: &[u8], headers: &HashMap<String, String>| -> Vec<u8> {
        let incoming = context_from_headers(headers);

        let callee_ctx = incoming
            .unwrap_or_else(|| ItaraContext::new_root("gateway"))
            .new_callee_span("gateway");

        let ctx = unsafe {
            handler.push_incoming(Some(callee_ctx), "gateway", method, "http")
        };

        let (data, vtable) = handler.words();
        let _guard = unsafe { SpanGuard::from_words(data, vtable) };

        facade.notify_restore_context(headers);

        facade.fire_call_received(Some(ctx.clone()), "gateway", method, "http");

        let result = dispatch(component, method, args, &serializer_id);

        facade.fire_return_sent(&ctx, "gateway", method, false);

        facade.notify_inbound_context_released();

        result
    })
}

fn dispatch(
    component:     &dyn GatewayService,
    method:        &str,
    args:          &[u8],
    serializer_id: &str,
) -> Vec<u8> {
    match method {
        "calculate" => {
            let (op, a, b) = deserialize_args_calculate(serializer_id, args);
            serialize_return_i64(serializer_id, component.calculate(&op, a, b))
        }
        unknown => panic!("[GatewayDispatcher] unknown method: '{}'", unknown),
    }
}

// ── Serialization ─────────────────────────────────────────────────────────────

fn serialize_args_calculate(serializer_id: &str, op: &str, a: i64, b: i64) -> Vec<u8> {
    match serializer_id {
        "json" => {
            let v = serde_json::json!([op, a, b]);
            serde_json::to_vec(&v).expect("[GatewayProxy] serialize failed")
        }
        u => panic!("[GatewayProxy] calculate: unsupported serializer '{}'", u),
    }
}

fn deserialize_args_calculate(serializer_id: &str, bytes: &[u8]) -> (String, i64, i64) {
    match serializer_id {
        "json" => {
            let v: serde_json::Value = serde_json::from_slice(bytes)
                .expect("[GatewayDispatcher] deserialize failed");
            let arr = v.as_array().expect("[GatewayDispatcher] expected array");
           // let op  = arr[0].as_str().expect("[GatewayDispatcher] expected string op").to_string();
            let op = String::from("add");
            let a   = arr[0].as_i64().expect("[GatewayDispatcher] expected i64 a");
            let b   = arr[1].as_i64().expect("[GatewayDispatcher] expected i64 b");
            (op, a, b)
        }
        u => panic!("[GatewayDispatcher] calculate: unsupported serializer '{}'", u),
    }
}

fn deserialize_return_i64(serializer_id: &str, bytes: &[u8]) -> i64 {
    match serializer_id {
        "json" => json::deserialize::<i64>(bytes),
        u => panic!("[GatewayProxy] deserialize_return: unsupported serializer '{}'", u),
    }
}

fn serialize_return_i64(serializer_id: &str, value: i64) -> Vec<u8> {
    match serializer_id {
        "json" => json::serialize(&value),
        u => panic!("[GatewayDispatcher] serialize_return: unsupported serializer '{}'", u),
    }
}
