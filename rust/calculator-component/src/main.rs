use calculator_api::{CalculatorService, calculator_dispatcher};
use itara_core::ItaraRegistry;
use itara_transport_http::HttpTransport;
use itara_core::ItaraTransport;

// ── Calculator server ─────────────────────────────────────────────────────────
//
// Standalone runner for the calculator component in HTTP topology.
// The agent starts a minimal registry, activates the calculator, then
// hands it to the HTTP transport via the generated dispatcher.
// No HTTP parsing logic here — that lives entirely in itara-transport-http.

fn main() {
    let port: u16 = std::env::var("CALCULATOR_PORT")
        .ok()
        .and_then(|p| p.parse().ok())
        .unwrap_or(8081);

    println!("[calculator-server] starting on port {}", port);

    // Minimal agent startup for a server-only process:
    // activate the component, register its dispatcher with the transport, start.
    let mut registry = ItaraRegistry::new();

    // Register the calculator activator and activate it
    itara_core::load_and_register(
        &mut registry,
        "calculator",
        &std::env::var("ITARA_CALCULATOR_LIB")
            .unwrap_or_else(|_| "./target/debug/calculator_component.dll".to_string()),
    ).unwrap_or_else(|e| panic!("{}", e));

    ItaraRegistry::install_global(registry);

    // Get the live component instance
    let calc = ItaraRegistry::global().get::<dyn CalculatorService>("calculator");

    // Build the dispatcher from the generated function in calculator-api
    let dispatcher = calculator_dispatcher(calc);

    // Hand it to the transport's server side
    let transport = HttpTransport::new(
        String::new(), // no outbound URL needed on server side
        port,
    );
    transport.register_listener("calculator", dispatcher);
    transport.start();

    println!("[calculator-server] ready — press Ctrl+C to stop");

    // Keep the process alive — the transport runs on a background thread
    loop {
        std::thread::sleep(std::time::Duration::from_secs(60));
    }
}