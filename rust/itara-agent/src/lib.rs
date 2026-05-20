use std::env;
use std::path::PathBuf;

use itara_config::{load, ConnectionEntry, WiringConfig};
use itara_core::{
    DispatcherFactoryFn, ItaraRegistry, ItaraTransport,
    load_and_register, load_api_cdylib, load_transport,
};
use std::ffi::CString;
use itara_libdir::LibIndex;

// ── Public API ────────────────────────────────────────────────────────────────
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

    // Deferred: (component_id, dispatcher_factory, serializer_cstring, transport).
    // Deferred until after install_global() because dispatcher creation
    // requires the component to be activated, which requires the registry.
    // The CString is kept alive here so the pointer remains valid when we
    // call the dispatcher factory after install_global().
    let mut deferred: Vec<(String, DispatcherFactoryFn, CString, Box<dyn ItaraTransport>)> = Vec::new();
    let mut plain_transports: Vec<Box<dyn ItaraTransport>> = Vec::new();

    wire(&config, &index, &mut registry, &mut plain_transports, &mut deferred);

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
        let dispatcher = unsafe { dispatcher_fn(data, vtable, serializer_cstring.as_ptr()) };
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
    config: &WiringConfig,
    index:  &LibIndex,
    registry: &mut ItaraRegistry,
    plain_transports: &mut Vec<Box<dyn ItaraTransport>>,
    deferred: &mut Vec<(String, DispatcherFactoryFn, CString, Box<dyn ItaraTransport>)>,
) {
    for conn in &config.connections {
        if conn.is_external() {
            wire_inbound(config, index, conn, registry, plain_transports, deferred);
        } else {
            let from = conn.from.as_deref().unwrap();
            let to   = &conn.to;

            if config.is_node_local(from) && !config.is_node_local(to) {
                wire_outbound(config, index, conn, registry);
            } else if config.is_node_local(from) && config.is_node_local(to) {
                load_local_component(config, index, to, registry);
            } else if !config.is_node_local(from) && config.is_node_local(to) {
                wire_inbound(config, index, conn, registry, plain_transports, deferred);
            }
        }
    }

    // Load local nodes not already loaded via a direct connection.
    for node in config.local_nodes() {
        if !registry.is_registered(&node.component) {
            load_local_component_by_id(index, &node.component, registry);
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
) {
    let port = conn.port.unwrap_or(8080);
    let component_id = config.component_of_node(&conn.to)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", conn.to));

    println!("[Itara] Inbound {} on port {} for '{}' with serializer '{}'", conn.transport_type, port, component_id, conn.serializer);
 
    validate_serializer(index, component_id, &conn.serializer);

    let transport = load_transport_for(index, &conn.transport_type, "", port);

    // Look up the API cdylib for the component.
    match index.api_lib(component_id) {
        Some(api_lib) => {
            let api_lib_str = api_lib.to_string_lossy();
            match load_api_cdylib(&api_lib_str) {
                Ok((_proxy_fn, dispatcher_fn)) => {
                    println!(
                        "[Itara] Loaded API cdylib for '{}' — deferring dispatcher registration",
                        component_id
                    );
                    let serializer_cstring = CString::new(conn.serializer.as_str())
                        .expect("[Itara] serializer id contains null byte");
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
    config:   &WiringConfig,
    index:    &LibIndex,
    conn:     &ConnectionEntry,
    registry: &mut ItaraRegistry,
) {
    let component_id = config.component_of_node(&conn.to)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", conn.to));
    let host     = conn.host.as_deref().unwrap_or("localhost");
    let port     = conn.port.unwrap_or(8080);
    let base_url = format!("http://{}:{}", host, port);

    println!("[Itara] Outbound {} -> '{}' at {} with serializer '{}'", conn.transport_type, component_id, base_url, conn.serializer);
 
    validate_serializer(index, component_id, &conn.serializer);

    // Load the API cdylib and call itara_create_proxy with the configured transport.
    let api_lib = index.api_lib(component_id)
        .unwrap_or_else(|| panic!(
            "[Itara] No API cdylib found for component '{}'. \
             Add a {}_api.itara file and matching cdylib to the lib dir.",
            component_id, component_id
        ));

    let api_lib_str = api_lib.to_string_lossy();
    let (proxy_fn, _dispatcher_fn) = load_api_cdylib(&api_lib_str)
        .unwrap_or_else(|e| panic!(
            "[Itara] Cannot load API cdylib for '{}': {}", component_id, e
        ));

    let transport = load_transport_for(index, &conn.transport_type, &base_url, 0);
 
    // The serializer id is passed to the proxy factory as a null-terminated C string.
    // The proxy copies it into a Rust String internally, so the CString only needs
    // to live for the duration of this call.
    let serializer_cstring = CString::new(conn.serializer.as_str())
        .expect("[Itara] serializer id contains null byte");

    // SAFETY: proxy_fn is itara_create_proxy from the API cdylib.
    // It consumes the transport Box and returns a type-erased proxy Box.
    // serializer_cstring is valid for this call — the proxy copies the string.
    let proxy = unsafe { proxy_fn(transport, serializer_cstring.as_ptr()) };
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

fn lib_dir_path() -> PathBuf {
    env::var("ITARA_LIB_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("./itara-libs"))
}