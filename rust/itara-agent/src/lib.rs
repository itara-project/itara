use std::env;
use std::path::PathBuf;

use itara_config::{load, ConnectionEntry, WiringConfig};
use itara_core::{
    DispatcherFactoryFn, DirectProxyFactoryFn, ItaraRegistry, ItaraTransport, ItaraObserver,
    ObservabilityFacade, ItaraContextHandler, WrappingData,
    load_and_register, load_api_cdylib, load_api_cdylib_full, load_direct_proxy_cdylib, load_transport,
    load_observer, load_context_handler,
};
use std::sync::Arc;
use std::ffi::CString;
use itara_libdir::LibIndex;

// ── Public API ───────────────────────────────────────────────────────────────

static GLOBAL_FACADE: std::sync::Mutex<Option<Arc<ObservabilityFacade>>> =
    std::sync::Mutex::new(None);

/// Flush all observers — call before process exit to ensure buffered
/// spans are exported. Safe to call multiple times.
pub fn itara_flush() {
    if let Some(facade) = GLOBAL_FACADE.lock().unwrap().as_ref() {
        facade.flush();
    }
}
//
//   fn main() {
//       itara_init();
//       let gateway = itara_get::<dyn GatewayService>("gateway");
//       gateway.calculate("add", 3, 4);
//   }
//
//   fn main() {
//       itara_init();
//       itara_run();
//   }

/// Initialise the Itara runtime.
///
/// Reads ITARA_LIB_DIR (default: ./itara-libs), scans it for .itara metadata
/// files, then reads ITARA_CONFIG and ITARA_NODES to wire the registry.
///
/// No per-component env vars are required. All artifact paths are resolved
/// from the lib dir index.
///
/// Panics on any configuration or loading error — topology errors must
/// surface at startup, never at call time.
pub fn itara_init() {
    let lib_dir = lib_dir_path();
    let index = LibIndex::scan(&lib_dir)
        .unwrap_or_else(|e| panic!("{}", e));

    let config = load().unwrap_or_else(|e| panic!("{}", e));
    println!("[Itara] Starting — local nodes: {:?}\n", config.local_node_ids);

    let mut registry = ItaraRegistry::new();

    // Load observers and build the facade before wiring — the facade pointer
    // is passed into every proxy and dispatcher factory during wire().
    let observers   = load_observers(&index);
    let facade      = Arc::new(ObservabilityFacade::new(observers));
    // Store a clone in the global so itara_flush() can reach it.
    *GLOBAL_FACADE.lock().unwrap() = Some(Arc::clone(&facade));
    let facade_ptr  = Arc::into_raw(facade);

    // Load the context handler — provides thread local span stack for
    // correct context chaining across component call chains.
    let handler_ptr = load_handler(&index);

    // Deferred: (component_id, dispatcher_factory, serializer_cstring, transport).
    let mut deferred: Vec<(String, DispatcherFactoryFn, CString, Box<dyn ItaraTransport>)> = Vec::new();
    // Deferred direct proxy wrapping: (component_id, direct_proxy_factory).
    // Applied after install_global() — needs the component to be activated.
    let mut deferred_direct: Vec<(String, DirectProxyFactoryFn)> = Vec::new();
    let mut plain_transports: Vec<Box<dyn ItaraTransport>> = Vec::new();

    wire(&config, &index, facade_ptr, handler_ptr, &mut registry, &mut plain_transports, &mut deferred, &mut deferred_direct);

    // Register direct proxy wrappers before install_global.
    // The registry wraps each component atomically during activation —
    // the raw implementation is never visible, even to other activators
    // that pull dependencies via get() during their own activation.
    for (component_id, direct_fn) in deferred_direct {
        registry.register_wrapper(&component_id, WrappingData {
            direct_fn,
            facade_ptr,
            handler_ptr,
        });
    }

    ItaraRegistry::install_global(registry);

    // Apply deferred dispatcher registrations now that the registry is live.
    for (component_id, dispatcher_fn, serializer_cstring, transport) in deferred {
        let (data, vtable) = ItaraRegistry::global()
            .get_fat_ptr(&component_id)
            .unwrap_or_else(|| panic!(
                "[Itara] Could not obtain fat pointer for '{}' — \
                 component not activated or not registered",
                component_id
            ));

        // SAFETY: (data, vtable) are the two words of a *const dyn ItaraComponent
        // fat pointer for a component stored in the global registry (process lifetime).
        // The cdylib calls cast_to() internally to recover the correctly-typed reference.
        // serializer_cstring is kept alive for the duration of this loop iteration —
        // the dispatcher copies the string internally, so the pointer need not outlive this call.
        let dispatcher = unsafe { dispatcher_fn(data, vtable, serializer_cstring.as_ptr(), facade_ptr, handler_ptr) };
        transport.register_listener(&component_id, dispatcher);
        transport.start();
    }



    for transport in plain_transports {
        transport.start();
    }
}

/// Retrieve a component by id as a reference to the requested trait T.
pub fn itara_get<T: ?Sized + 'static>(id: &str) -> &'static T {
    ItaraRegistry::global().get::<T>(id)
}

/// Block the current thread forever.
/// For server components that have no application logic of their own.
pub fn itara_run() -> ! {
    loop {
        std::thread::sleep(std::time::Duration::from_secs(60));
    }
}

// ── Internal wiring ───────────────────────────────────────────────────────────

fn wire(
    config:           &WiringConfig,
    index:            &LibIndex,
    facade_ptr:       *const ObservabilityFacade,
    handler_ptr:      *const dyn ItaraContextHandler,
    registry:         &mut ItaraRegistry,
    plain_transports: &mut Vec<Box<dyn ItaraTransport>>,
    deferred:         &mut Vec<(String, DispatcherFactoryFn, CString, Box<dyn ItaraTransport>)>,
    deferred_direct:  &mut Vec<(String, DirectProxyFactoryFn)>,
) {
    // Components served over a transport (inbound HTTP, Kafka, etc.) handle
    // observability via their dispatcher — they must NOT also get the direct
    // proxy decorator, which would fire duplicate events.
    let mut transport_handled: std::collections::HashSet<String> = std::collections::HashSet::new();
    for conn in &config.connections {
        if conn.is_external() {
            wire_inbound(config, index, conn, registry, plain_transports, deferred, &mut transport_handled);
        } else {
            let from = conn.from.as_deref().unwrap();
            let to   = &conn.to;

            if config.is_node_local(from) && !config.is_node_local(to) {
                wire_outbound(config, index, conn, facade_ptr, handler_ptr, registry);
            } else if config.is_node_local(from) && config.is_node_local(to) {
                // Wire both ends — from is the caller (e.g. gateway), to is the callee.
                // Both need direct proxy wrapping so application entry points
                // into the caller also produce observability events.
                wire_direct(config, index, from, facade_ptr, handler_ptr, registry, deferred_direct, &transport_handled);
                wire_direct(config, index, to,   facade_ptr, handler_ptr, registry, deferred_direct, &transport_handled);
            } else if !config.is_node_local(from) && config.is_node_local(to) {
                wire_inbound(config, index, conn, registry, plain_transports, deferred, &mut transport_handled);
            }
        }
    }

    // Ensure all local nodes are loaded and queued for direct proxy wrapping.
    // wire_direct guards against duplicate loading and duplicate queueing,
    // so it is safe to call for every local node unconditionally.
    for node in config.local_nodes() {
        wire_direct(config, index, &node.id(), facade_ptr, handler_ptr, registry, deferred_direct, &transport_handled);
    }
}

fn wire_direct(
    config:            &WiringConfig,
    index:             &LibIndex,
    node_id:           &str,
    _facade_ptr:       *const ObservabilityFacade,
    _handler_ptr:      *const dyn ItaraContextHandler,
    registry:          &mut ItaraRegistry,
    deferred_direct:   &mut Vec<(String, DirectProxyFactoryFn)>,
    transport_handled: &std::collections::HashSet<String>,
) {
    let component_id = config.component_of_node(node_id)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", node_id));

    // Load the component so it can be activated later — only if not already registered.
    if !registry.is_registered(component_id) {
        load_local_component_by_id(index, component_id, registry);
    }

    // Skip direct proxy wrapping for components that are transport-handled.
    // Their dispatcher already fires CALL_RECEIVED and RETURN_SENT — wrapping
    // with the direct proxy too would produce duplicate events.
    if transport_handled.contains(component_id) {
        return;
    }

    // Only queue once — skip if already queued
    // Queue direct proxy wrapping — applied after install_global() once
    // the component has been activated.
    match index.api_lib(component_id) {
        Some(api_lib) => {
            let api_lib_str = api_lib.to_string_lossy().to_string();
            let direct_fn_result = load_api_cdylib_full(&api_lib_str, component_id)
                .map(|(_proxy, _disp, direct)| direct)
                .or_else(|_| load_direct_proxy_cdylib(&api_lib_str, component_id));
            match direct_fn_result {
                Ok(direct_fn) => {
                    if !deferred_direct.iter().any(|(id, _)| id == component_id) {
                        println!(
                            "[Itara] Queued direct proxy wrapping for '{}'",
                            component_id
                        );
                        deferred_direct.push((component_id.to_string(), direct_fn));
                    }
                }
                Err(e) => {
                    eprintln!(
                        "[Itara] Warning: could not load API cdylib for direct proxy '{}': {}. \
                         Direct calls will have no observability.",
                        component_id, e
                    );
                }
            }
        }
        None => {
            eprintln!(
                "[Itara] Warning: no API cdylib for direct proxy '{}'.                  Direct calls will have no observability.",
                component_id
            );
        }
    }
}

fn wire_inbound(
    config: &WiringConfig,
    index:  &LibIndex,
    conn:   &ConnectionEntry,
    _registry: &mut ItaraRegistry,
    plain_transports: &mut Vec<Box<dyn ItaraTransport>>,
    deferred: &mut Vec<(String, DispatcherFactoryFn, CString, Box<dyn ItaraTransport>)>,
    transport_handled: &mut std::collections::HashSet<String>,
) {
    let port: u16 = conn.transport.params.get("port")
        .and_then(|p| p.parse().ok())
        .unwrap_or(8080);
    let component_id = config.component_of_node(&conn.to)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", conn.to));

    println!("[Itara] Inbound {} on port {} for '{}' with serializer '{}'", conn.transport.id, port, component_id, conn.serializer);

    validate_serializer(index, component_id, &conn.serializer);

    let transport = load_transport_for(index, &conn.transport.id, "", port);

    // Look up the API cdylib for the component.
    match index.api_lib(component_id) {
        Some(api_lib) => {
            let api_lib_str = api_lib.to_string_lossy();
            match load_api_cdylib(&api_lib_str, component_id) {
                Ok((_proxy_fn, dispatcher_fn)) => {
                    println!(
                        "[Itara] Loaded API cdylib for '{}' — deferring dispatcher registration",
                        component_id
                    );
                    let serializer_cstring = CString::new(conn.serializer.as_str())
                        .expect("[Itara] serializer id contains null byte");
                    transport_handled.insert(component_id.to_string());
                    deferred.push((component_id.to_string(), dispatcher_fn, serializer_cstring, transport));
                }
                Err(e) => {
                    eprintln!(
                        "[Itara] Warning: could not load API cdylib for '{}': {}. \
                         Ensure the cdylib is in the lib dir.",
                        component_id, e
                    );
                    plain_transports.push(transport);
                }
            }
        }
        None => {
            eprintln!(
                "[Itara] Warning: no API cdylib found for component '{}'. \
                 Add a {}_api.itara file and matching cdylib to the lib dir.",
                component_id, component_id
            );
            plain_transports.push(transport);
        }
    }
}

fn wire_outbound(
    config:      &WiringConfig,
    index:       &LibIndex,
    conn:        &ConnectionEntry,
    facade_ptr:  *const ObservabilityFacade,
    handler_ptr: *const dyn ItaraContextHandler,
    registry:    &mut ItaraRegistry,
) {
    let component_id = config.component_of_node(&conn.to)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", conn.to));
    let host     = conn.transport.params.get("host").map(|s| s.as_str()).unwrap_or("localhost");
    let port: u16 = conn.transport.params.get("port")
        .and_then(|p| p.parse().ok())
        .unwrap_or(8080);
    let base_url = format!("http://{}:{}", host, port);

    println!("[Itara] Outbound {} -> '{}' at {} with serializer '{}'", conn.transport.id, component_id, base_url, conn.serializer);

    validate_serializer(index, component_id, &conn.serializer);

    // Load the API cdylib and call itara_create_proxy with the configured transport.
    let api_lib = index.api_lib(component_id)
        .unwrap_or_else(|| panic!(
            "[Itara] No API cdylib found for component '{}'. \
             Add a {}_api.itara file and matching cdylib to the lib dir.",
            component_id, component_id
        ));

    let api_lib_str = api_lib.to_string_lossy();
    let (proxy_fn, _dispatcher_fn) = load_api_cdylib(&api_lib_str, component_id)
        .unwrap_or_else(|e| panic!(
            "[Itara] Cannot load API cdylib for '{}': {}", component_id, e
        ));

    let transport = load_transport_for(index, &conn.transport.id, &base_url, 0);

    // The serializer id is passed to the proxy factory as a null-terminated C string.
    // The proxy copies it into a Rust String internally, so the CString only needs
    // to live for the duration of this call.
    let serializer_cstring = CString::new(conn.serializer.as_str())
        .expect("[Itara] serializer id contains null byte");

    // SAFETY: proxy_fn is itara_create_proxy from the API cdylib.
    // It consumes the transport Box and returns a type-erased proxy Box.
    // serializer_cstring is valid for this call — the proxy copies the string.
    let proxy = unsafe { proxy_fn(transport, serializer_cstring.as_ptr(), facade_ptr, handler_ptr) };
    registry.register_proxy(component_id, proxy);
}

/// Load a transport instance via the lib index.
/// base_url is empty for inbound-only; listen_port is 0 for outbound-only.
fn load_transport_for(
    index:          &LibIndex,
    transport_type: &str,
    base_url:       &str,
    listen_port:    u16,
) -> Box<dyn ItaraTransport> {
    let lib = index.transport_lib(transport_type)
        .unwrap_or_else(|| panic!(
            "[Itara] No transport found for type '{}'. \
             Add a matching .itara file and cdylib to the lib dir.",
            transport_type
        ));

    let lib_str = lib.to_string_lossy();
    load_transport(&lib_str, base_url, listen_port)
        .unwrap_or_else(|e| panic!("{}", e))
}

fn load_local_component(
    config:   &WiringConfig,
    index:    &LibIndex,
    node_id:  &str,
    registry: &mut ItaraRegistry,
) {
    let component_id = config.component_of_node(node_id)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", node_id));
    load_local_component_by_id(index, component_id, registry);
}

fn load_local_component_by_id(
    index:        &LibIndex,
    component_id: &str,
    registry:     &mut ItaraRegistry,
) {
    let lib = index.component_lib(component_id)
        .unwrap_or_else(|| panic!(
            "[Itara] No component lib found for '{}'. \
             Add a {}_component.itara file and matching cdylib to the lib dir.",
            component_id, component_id
        ));

    let lib_str = lib.to_string_lossy();
    load_and_register(registry, component_id, &lib_str)
        .unwrap_or_else(|e| panic!("{}", e));
}

// ── Serializer validation ─────────────────────────────────────────────────────

/// Validate that the serializer declared in the wiring config is supported
/// by the API artifact at the other end of this connection.
///
/// Panics at startup if the serializer is not in the artifact's supported list.
/// This is a topology configuration error — it must surface before any
/// component is activated or any listener is started.
///
/// If the artifact declares no serializers (empty list or missing section),
/// validation is skipped with a warning — the artifact may predate the
/// [serializers] metadata section.
fn validate_serializer(index: &LibIndex, component_id: &str, serializer_id: &str) {
    let supported = index.supported_serializers(component_id);
    if supported.is_empty() {
        eprintln!(
            "[Itara] Warning: no serializer declarations found for api '{}'.              Add a [serializers] section to {}_api.itara to enable validation.",
            component_id, component_id
        );
        return;
    }
    if !supported.iter().any(|s| s == serializer_id) {
        panic!(
            "[Itara] Serializer mismatch for component '{}':              wiring config declares '{}' but the API artifact only supports {:?}.              Fix the wiring config or recompile the API with '{}' support.",
            component_id, serializer_id, supported, serializer_id
        );
    }
}

// ── Lib dir resolution ────────────────────────────────────────────────────────

/// Load the context handler from the lib dir.
/// Exactly one context handler is expected. If none is found, a no-op handler
/// is used so the system runs without context chaining. If multiple are found,
/// the first is used and a warning is printed.
fn load_handler(index: &LibIndex) -> *const dyn ItaraContextHandler {
    match index.context_handler_lib() {
        Some(lib) => {
            let path = lib.to_string_lossy();
            match load_context_handler(&path) {
                Ok(handler) => {
                    println!("[Itara] Loaded context handler from {}", path);
                    // Leak — process lifetime pointer passed to all factories.
                    Box::into_raw(handler)
                }
                Err(e) => {
                    eprintln!("[Itara] Warning: failed to load context handler: {}", e);
                    Box::into_raw(Box::new(NoopContextHandler))
                }
            }
        }
        None => {
            println!("[Itara] No context handler found — context chaining disabled");
            Box::into_raw(Box::new(NoopContextHandler))
        }
    }
}

/// No-op context handler used when no implementation is in the lib dir.
/// Context events still fire but spans are not chained.
struct NoopContextHandler;
impl ItaraContextHandler for NoopContextHandler {
    fn push(&self, component: &str, _method: &str, _transport: &str) -> itara_core::ItaraContext {
        itara_core::ItaraContext::new_root(component)
    }
    fn push_incoming(&self, incoming: Option<itara_core::ItaraContext>, component: &str, _method: &str, _transport: &str) -> itara_core::ItaraContext {
        incoming.unwrap_or_else(|| itara_core::ItaraContext::new_root(component))
    }
    fn pop(&self) {}
    fn current(&self) -> Option<itara_core::ItaraContext> { None }
}

/// Load all observer cdylibs discovered in the lib dir.
/// Multiple observers can be active simultaneously — all are loaded.
/// A failure to load one observer logs a warning and continues;
/// it must not prevent the system from starting.
fn load_observers(index: &LibIndex) -> Vec<Box<dyn ItaraObserver>> {
    let libs = index.observer_libs();
    if libs.is_empty() {
        println!("[Itara] No observers found in lib dir — running without observability output");
        return Vec::new();
    }

    println!("[Itara] Found {} observer lib(s)", libs.len());
    let mut observers: Vec<Box<dyn ItaraObserver>> = Vec::new();
    for lib in libs {
        let path = lib.to_string_lossy();
        match load_observer(&path) {
            Ok(observer) => {
                println!("[Itara] Loaded observer from {}", path);
                observers.push(observer);
            }
            Err(e) => {
                eprintln!("[Itara] Warning: failed to load observer '{}': {}", path, e);
            }
        }
    }
    observers
}

fn lib_dir_path() -> PathBuf {
    env::var("ITARA_LIB_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("./itara-libs"))
}