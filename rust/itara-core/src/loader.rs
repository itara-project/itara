use crate::component::{ActivatorFn, ItaraComponent, Dispatcher};
use crate::transport::ItaraTransport;
use crate::registry::ItaraRegistry;
use crate::observability::{ObservabilityFacade, ItaraContextHandler};

/// Proxy factory function signature exported by every API cdylib.
///
/// serializer_id — null-terminated UTF-8 string identifying the serializer.
/// facade        — raw pointer to the ObservabilityFacade, process lifetime.
/// handler       — raw pointer to the ItaraContextHandler, process lifetime.
pub type ProxyFactoryFn = unsafe extern "C" fn(
    transport:     Box<dyn ItaraTransport>,
    serializer_id: *const std::os::raw::c_char,
    facade:        *const ObservabilityFacade,
    handler:       *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent>;

/// Dispatcher factory function signature exported by every API cdylib.
///
/// serializer_id — null-terminated UTF-8 string identifying the serializer.
/// facade        — raw pointer to the ObservabilityFacade, process lifetime.
/// handler       — raw pointer to the ItaraContextHandler, process lifetime.
pub type DispatcherFactoryFn = unsafe extern "C" fn(
    data:          *const (),
    vtable:        *const (),
    serializer_id: *const std::os::raw::c_char,
    facade:        *const ObservabilityFacade,
    handler:       *const dyn ItaraContextHandler,
) -> Dispatcher;

/// Direct proxy factory function exported by every API cdylib.
/// Called by the agent after activation to wrap a local component in a
/// direct proxy that fires observability events for colocated calls.
pub type DirectProxyFactoryFn = unsafe extern "C" fn(
    data:    *const (),
    vtable:  *const (),
    facade:  *const ObservabilityFacade,
    handler: *const dyn ItaraContextHandler,
) -> Box<dyn ItaraComponent>;

/// Load an API cdylib and return all three factory functions.
/// component_id is used to construct the component-specific direct proxy
/// symbol: itara_create_direct_proxy_{component_id}
pub fn load_api_cdylib_full(
    lib_path:     &str,
    component_id: &str,
) -> Result<(ProxyFactoryFn, DispatcherFactoryFn, DirectProxyFactoryFn), String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load API cdylib '{}': {}", lib_path, e))?;

        let proxy_symbol = format!("itara_create_proxy_{}\0", component_id);
        let proxy_factory: libloading::Symbol<ProxyFactoryFn> = lib
            .get(proxy_symbol.as_bytes())
            .map_err(|e| format!("[Itara] No itara_create_proxy_{} in '{}': {}", component_id, lib_path, e))?;

        let dispatcher_symbol = format!("itara_create_dispatcher_{}\0", component_id);
        let dispatcher_factory: libloading::Symbol<DispatcherFactoryFn> = lib
            .get(dispatcher_symbol.as_bytes())
            .map_err(|e| format!("[Itara] No itara_create_dispatcher_{} in '{}': {}", component_id, lib_path, e))?;

        // Direct proxy symbol is component-specific to avoid collision when
        // multiple API rlibs are linked into the same cdylib.
        let direct_symbol = format!("itara_create_direct_proxy_{}\0", component_id);
        let direct_factory: libloading::Symbol<DirectProxyFactoryFn> = lib
            .get(direct_symbol.as_bytes())
            .map_err(|e| format!(
                "[Itara] No itara_create_direct_proxy_{} in '{}': {}",
                component_id, lib_path, e
            ))?;

        let proxy_fn: ProxyFactoryFn = *proxy_factory;
        let dispatcher_fn: DispatcherFactoryFn = *dispatcher_factory;
        let direct_fn: DirectProxyFactoryFn = *direct_factory;
        std::mem::forget(lib);

        Ok((proxy_fn, dispatcher_fn, direct_fn))
    }
}

/// Load only the direct proxy symbol from an API cdylib.
/// Used for components that are never called remotely and therefore
/// do not export itara_create_proxy or itara_create_dispatcher.
pub fn load_direct_proxy_cdylib(
    lib_path:     &str,
    component_id: &str,
) -> Result<DirectProxyFactoryFn, String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load API cdylib '{}': {}", lib_path, e))?;

        let direct_symbol = format!("itara_create_direct_proxy_{}\0", component_id);
        let direct_factory: libloading::Symbol<DirectProxyFactoryFn> = lib
            .get(direct_symbol.as_bytes())
            .map_err(|e| format!(
                "[Itara] No itara_create_direct_proxy_{} in '{}': {}",
                component_id, lib_path, e
            ))?;

        let direct_fn: DirectProxyFactoryFn = *direct_factory;
        std::mem::forget(lib);
        Ok(direct_fn)
    }
}

/// Load a component .so and register its activator.
pub fn load_and_register(
    registry:     &mut ItaraRegistry,
    component_id: &str,
    lib_path:     &str,
) -> Result<(), String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load '{}': {}", lib_path, e))?;

        let activator: libloading::Symbol<ActivatorFn> = lib
            .get(b"itara_activator\0")
            .map_err(|e| format!("[Itara] No itara_activator in '{}': {}", lib_path, e))?;

        let activator_fn: ActivatorFn = *activator;
        std::mem::forget(lib);

        registry.register_activator(component_id, activator_fn);
        Ok(())
    }
}

/// Load an API cdylib and return its proxy and dispatcher factory functions.
pub fn load_api_cdylib(
    lib_path:     &str,
    component_id: &str,
) -> Result<(ProxyFactoryFn, DispatcherFactoryFn), String> {
    unsafe {
        let lib = libloading::Library::new(lib_path)
            .map_err(|e| format!("[Itara] Failed to load API cdylib '{}': {}", lib_path, e))?;

        let proxy_symbol = format!("itara_create_proxy_{}\0", component_id);
        let proxy_factory: libloading::Symbol<ProxyFactoryFn> = lib
            .get(proxy_symbol.as_bytes())
            .map_err(|e| format!("[Itara] No itara_create_proxy_{} in '{}': {}", component_id, lib_path, e))?;

        let dispatcher_symbol = format!("itara_create_dispatcher_{}\0", component_id);
        let dispatcher_factory: libloading::Symbol<DispatcherFactoryFn> = lib
            .get(dispatcher_symbol.as_bytes())
            .map_err(|e| format!("[Itara] No itara_create_dispatcher_{} in '{}': {}", component_id, lib_path, e))?;

        let proxy_fn: ProxyFactoryFn = *proxy_factory;
        let dispatcher_fn: DispatcherFactoryFn = *dispatcher_factory;
        std::mem::forget(lib);

        Ok((proxy_fn, dispatcher_fn))
    }
}
