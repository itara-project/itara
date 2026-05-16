use calculator_api::CalculatorServiceProxy;
use gateway_api::GatewayService;
use itara_core::{ItaraRegistry, load_and_register};
use itara_transport_http::HttpTransport;

// ── Agent ─────────────────────────────────────────────────────────────────────
//
// The agent reads the topology config (env vars for the PoC) and wires
// the registry accordingly. No topology knowledge exists anywhere else.
//
// Two topologies supported:
//   direct — calculator loaded as .dll, called in-process
//   http   — calculator is remote, transport loaded from itara-transport-http.dll
//
// The gateway component never changes between topologies.
// The calculator component never changes between topologies.
// Only this file — the agent — knows the topology.

fn main() {
    let mode = std::env::var("ITARA_MODE")
        .unwrap_or_else(|_| "direct".to_string());
    let calc_lib = std::env::var("ITARA_CALCULATOR_LIB")
        .unwrap_or_else(|_| "./target/debug/calculator_component.dll".to_string());
    let gateway_lib = std::env::var("ITARA_GATEWAY_LIB")
        .unwrap_or_else(|_| "./target/debug/gateway_component.dll".to_string());

    println!("[Itara] Starting — topology: {}\n", mode);

    let mut registry = ItaraRegistry::new();

    match mode.as_str() {
        "direct" => {
            // Calculator loaded as .dll — direct in-process calls
            load_and_register(&mut registry, "calculator", &calc_lib)
                .unwrap_or_else(|e| panic!("{}", e));
        }
        "http" => {
            // Calculator is remote — create transport, wrap in generated proxy,
            // preregister proxy as the calculator in the registry.
            // The gateway activator will get this proxy and never know it's HTTP.
            let transport = Box::new(HttpTransport::new(
                std::env::var("CALCULATOR_URL")
                    .unwrap_or_else(|_| "http://localhost:8081".to_string()),
                // listen port not needed on the client side
                0,
            ));

            let proxy = CalculatorServiceProxy::new(transport, "calculator");
            registry.preregister("calculator", Box::new(proxy));
        }
        other => panic!("[Itara] Unknown ITARA_MODE: '{}' — use 'direct' or 'http'", other),
    }

    // Gateway is always local in this process
    load_and_register(&mut registry, "gateway", &gateway_lib)
        .unwrap_or_else(|e| panic!("{}", e));

    // Freeze the registry
    ItaraRegistry::install_global(registry);

    // ── Application code ──────────────────────────────────────────────────
    println!();
    let gateway = ItaraRegistry::global().get::<dyn GatewayService>("gateway");

    println!("[app] running calculations\n");

    let sum = gateway.calculate("add", 3, 4);
    println!("[app] 3 + 4 = {}\n", sum);

    let product = gateway.calculate("multiply", 6, 7);
    println!("[app] 6 * 7 = {}\n", product);

    let chained = gateway.calculate("add", sum, product);
    println!("[app] {} + {} = {}\n", sum, product, chained);
}