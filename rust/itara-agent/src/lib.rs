use itara_config::{load, ConnectionEntry, WiringConfig};
use itara_core::{ItaraRegistry, load_and_register, ItaraTransport};
use itara_transport_http::HttpTransport;
use std::env;

// ── Public API ────────────────────────────────────────────────────────────────
//
// This is what the application code sees. Everything else is invisible.
//
//   fn main() {
//       itara_init();
//       let gateway = itara_get::<dyn GatewayService>("gateway");
//       gateway.calculate("add", 3, 4);
//   }
//
// For server-only components that have nothing to do after startup:
//
//   fn main() {
//       itara_init();
//       itara_run();
//   }

/// Initialise the Itara runtime.
///
/// Reads the wiring config from ITARA_CONFIG, filters to ITARA_NODES,
/// loads all required component and transport libraries, wires the registry,
/// and starts all inbound listeners.
///
/// Must be called before any component is used.
/// Panics on any configuration or loading error — topology errors must
/// surface at startup, never at call time.
pub fn itara_init() {
    let config = load().unwrap_or_else(|e| panic!("{}", e));
    println!("[Itara] Starting — local nodes: {:?}\n", config.local_node_ids);

    let mut registry = ItaraRegistry::new();
    let mut transports: Vec<Box<dyn ItaraTransport>> = Vec::new();

    // Register any proxies pre-registered by the application before init.
    // Temporary until API cdylibs handle this automatically.
    for (id, proxy) in take_pending_proxies() {
        registry.preregister(&id, proxy);
    }

    wire(&config, &mut registry, &mut transports);

    ItaraRegistry::install_global(registry);

    // Start all inbound listeners after the registry is frozen
    for transport in transports {
        transport.start();
    }
}

/// Retrieve a component by id as a reference to the requested trait T.
/// Shorthand for ItaraRegistry::global().get::<T>(id).
///
/// Panics if itara_init() has not been called, or if the component
/// is not registered or does not implement T.
pub fn itara_get<T: ?Sized + 'static>(id: &str) -> &'static T {
    ItaraRegistry::global().get::<T>(id)
}

/// Block the current thread forever.
/// For server components that have no application logic of their own —
/// Itara handles all inbound requests on background threads.
///
/// Call after itara_init().
pub fn itara_run() -> ! {
    loop {
        std::thread::sleep(std::time::Duration::from_secs(60));
    }
}

// ── Internal wiring ───────────────────────────────────────────────────────────

fn wire(
    config: &WiringConfig,
    registry: &mut ItaraRegistry,
    transports: &mut Vec<Box<dyn ItaraTransport>>,
) {
    for conn in &config.connections {
        if conn.is_external() {
            // Inbound connection — start a listener for the local node
            wire_inbound(config, conn, registry, transports);
        } else {
            let from = conn.from.as_deref().unwrap();
            let to = &conn.to;

            if config.is_node_local(from) && !config.is_node_local(to) {
                // Outbound — local node calling a remote node
                wire_outbound(config, conn, registry);
            } else if config.is_node_local(from) && config.is_node_local(to) {
                // Both local — direct connection, load the component .so
                load_local_component(config, to, registry);
            } else if !config.is_node_local(from) && config.is_node_local(to) {
                // Remote calls local — start a listener for the local node
                wire_inbound(config, conn, registry, transports);
            }
        }
    }

    // Load all local nodes that weren't already loaded via a direct connection
    for node in config.local_nodes() {
        let component_id = &node.component;
        if !registry.is_registered(component_id) {
            load_local_component_by_id(component_id, registry);
        }
    }
}

fn wire_inbound(
    config: &WiringConfig,
    conn: &ConnectionEntry,
    registry: &mut ItaraRegistry,
    transports: &mut Vec<Box<dyn ItaraTransport>>,
) {
    let port = conn.port.unwrap_or(8080);
    let component_id = config.component_of_node(&conn.to)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", conn.to));

    println!("[Itara] Inbound {} on port {} for '{}'", conn.transport_type, port, component_id);

    match conn.transport_type.to_lowercase().as_str() {
        "http" => {
            let transport = Box::new(HttpTransport::new(String::new(), port));
            // Dispatcher registration happens after the component is activated —
            // deferred to post-registry-install. For now, register the transport
            // so start() can be called after install_global().
            // TODO: register dispatcher here once observability facade is in place
            for (id, dispatcher) in take_pending_dispatchers() {
                transport.register_listener(&id, dispatcher);
            }
            transports.push(transport);
        }
        other => panic!("[Itara] Unknown transport type: '{}'. Check your wiring config.", other),
    }
}

fn wire_outbound(
    config: &WiringConfig,
    conn: &ConnectionEntry,
    registry: &mut ItaraRegistry,
) {
    let to = &conn.to;
    let component_id = config.component_of_node(to)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", to));
    let host = conn.host.as_deref().unwrap_or("localhost");
    let port = conn.port.unwrap_or(8080);
    let base_url = format!("http://{}:{}", host, port);

    println!("[Itara] Outbound {} -> '{}' at {}", conn.transport_type, component_id, base_url);

    match conn.transport_type.to_lowercase().as_str() {
        "http" => {
            // The proxy wraps the transport and implements the component trait.
            // This is currently component-specific — will be generalised by the macro.
            // For the PoC, the agent knows about the available proxy types via
            // the lib path convention.
            let lib = component_lib_path(component_id);
            // TODO: once the macro generates proxies, create them generically here.
            // For now, component proxies are pre-registered by the application
            // or by a component-specific bootstrap. The transport is available
            // via ITARA_{COMPONENT}_URL env var as a fallback.
            let _ = base_url; // used when proxy is constructed
            let _ = lib;
            /*panic!(
                "[Itara] Generic proxy construction not yet implemented. \
                 Pre-register the proxy for '{}' before calling itara_init().",
                component_id
            );*/
        }
        other => panic!("[Itara] Unknown transport type: '{}'. Check your wiring config.", other),
    }
}

fn load_local_component(config: &WiringConfig, node_id: &str, registry: &mut ItaraRegistry) {
    let component_id = config.component_of_node(node_id)
        .unwrap_or_else(|| panic!("[Itara] No component found for node '{}'", node_id));
    load_local_component_by_id(component_id, registry);
}

fn load_local_component_by_id(component_id: &str, registry: &mut ItaraRegistry) {
    let lib = component_lib_path(component_id);
    load_and_register(registry, component_id, &lib)
        .unwrap_or_else(|e| panic!("{}", e));
}

fn component_lib_path(component_id: &str) -> String {
    env::var(format!("ITARA_{}_LIB", component_id.to_uppercase()))
        .unwrap_or_else(|_| format!("./target/debug/{}_component.dll", component_id))
}

// ── Interim proxy registration ────────────────────────────────────────────────
//
// Temporary until API cdylibs with generated symbols are implemented.
// See GitHub issue: "Agent-driven proxy registration via API cdylib and metadata files"
//
// Call before itara_init() for each remote component this process calls:
//
//   let transport = Box::new(HttpTransport::new(url, 0));
//   itara_preregister("calculator", CalculatorServiceProxy::new(transport, "calculator"));
//   itara_init();

/// Pre-register a remote proxy before itara_init() is called.
/// The proxy must implement both the component's API trait and ItaraComponent.
///
/// This is a temporary workaround until the agent can create proxies
/// autonomously via API cdylibs. See linked GitHub issue.
use std::sync::Mutex;
use std::sync::OnceLock;

static PENDING_PROXIES: OnceLock<Mutex<Vec<(String, Box<dyn itara_core::ItaraComponent>)>>> 
    = OnceLock::new();

fn pending_proxies() -> &'static Mutex<Vec<(String, Box<dyn itara_core::ItaraComponent>)>> {
    PENDING_PROXIES.get_or_init(|| Mutex::new(Vec::new()))
}

pub fn itara_preregister(component_id: &str, proxy: Box<dyn itara_core::ItaraComponent>) {
    pending_proxies().lock().unwrap().push((component_id.to_string(), proxy));
}

fn take_pending_proxies() -> Vec<(String, Box<dyn itara_core::ItaraComponent>)> {
    pending_proxies().lock().unwrap().drain(..).collect()
}

use itara_core::Dispatcher;

static PENDING_DISPATCHERS: OnceLock<Mutex<Vec<(String, Dispatcher)>>> 
    = OnceLock::new();

fn pending_dispatchers() -> &'static Mutex<Vec<(String, Dispatcher)>> {
    PENDING_DISPATCHERS.get_or_init(|| Mutex::new(Vec::new()))
}

/// Pre-register a dispatcher before itara_init() is called.
/// Call this for each local component that needs to be reachable over a transport.
///
/// Temporary workaround until API cdylibs handle this automatically.
pub fn itara_register_dispatcher(component_id: &str, dispatcher: itara_core::Dispatcher) {
    pending_dispatchers().lock().unwrap().push((component_id.to_string(), dispatcher));
}

fn take_pending_dispatchers() -> Vec<(String, Dispatcher)> {
    pending_dispatchers().lock().unwrap().drain(..).collect()
}
