use crate::component::Dispatcher;
use std::collections::HashMap;

/// The transport SPI — implemented by itara-transport-http, itara-transport-kafka, etc.
/// Loaded as a .dll/.so by the agent at startup.
///
/// NOTE: traceparent and tracestate parameters are raw W3C header strings for now.
/// A dedicated ItaraHeaders type and header-handling SPI are planned — these
/// parameters will be replaced when that work lands.
pub trait ItaraTransport: Send + Sync {
    /// Client side — invoke a remote method.
    /// Called by the generated proxy on every outbound call.
    ///
    /// headers is the merged outbound header map produced by
    /// ObservabilityFacade::build_outbound_headers() — contains Itara-native
    /// headers (x-itara-trace-id etc.) plus any observer-contributed headers
    /// (e.g. OTel's traceparent/tracestate). The transport sets all entries
    /// on the outgoing request. All keys are lowercase.
    fn invoke(
        &self,
        component_id: &str,
        method:       &str,
        args:         &[u8],
        headers:      &HashMap<String, String>,
    ) -> Vec<u8>;

    /// Server side — register an inbound handler for a component.
    /// Called by the agent at startup for each local component that needs
    /// to be reachable over this transport.
    fn register_listener(&self, component_id: &str, dispatcher: Dispatcher);

    /// Start all registered listeners.
    /// Called by the agent after all listeners are registered,
    /// before handing control to the application.
    fn start(&self);
}

/// Factory function signature exported by every transport cdylib.
/// The agent passes host/port from the wiring config — the transport
/// is ignorant of topology and component ids.
/// NOTE: this signature is debt — it encodes HTTP-specific parameters.
/// Will be replaced with an opaque config blob per the transport SPI cleanup issue.
pub type TransportFactoryFn = unsafe extern "C" fn(
    base_url_ptr: *const std::os::raw::c_char,
    listen_port:  u16,
) -> Box<dyn ItaraTransport>;

/// Load a transport cdylib and return a configured instance.
/// base_url is the outbound URL (empty string for inbound-only).
/// listen_port is the inbound port (0 for outbound-only).
pub fn load_transport(
    lib_path:    &str,
    base_url:    &str,
    listen_port: u16,
) -> Result<Box<dyn ItaraTransport>, String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load transport '{}': {}", lib_path, e))?;

        let factory: libloading::Symbol<TransportFactoryFn> = lib
            .get(b"itara_transport_factory\0")
            .map_err(|e| format!("[Itara] No itara_transport_factory in '{}': {}", lib_path, e))?;

        let factory_fn: TransportFactoryFn = *factory;
        std::mem::forget(lib);

        let c_url = std::ffi::CString::new(base_url)
            .map_err(|e| format!("[Itara] Invalid base_url string: {}", e))?;

        Ok(factory_fn(c_url.as_ptr(), listen_port))
    }
}
