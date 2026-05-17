use calculator_api::CalculatorServiceProxy;
use gateway_api::GatewayService;
use itara_agent::{itara_init, itara_get, itara_preregister};
use itara_transport_http::HttpTransport;

fn main() {
    // TODO: remove once API cdylib proxy registration is implemented
    // (see GitHub issue: "Agent-driven proxy registration in Rust via API cdylib and metadata files")
    // Until then, register remote proxies before itara_init().
    // Only pre-register HTTP proxy if calculator is remote
    if std::env::var("CALCULATOR_URL").is_ok() {
        let transport = Box::new(HttpTransport::new(
            std::env::var("CALCULATOR_URL").unwrap(), 0));
        itara_preregister("calculator", 
            Box::new(CalculatorServiceProxy::new(transport, "calculator")));
    }

    itara_init();
 
    let gateway = itara_get::<dyn GatewayService>("gateway");

    println!("[app] running calculations\n");

    let sum = gateway.calculate("add", 3, 4);
    println!("[app] 3 + 4 = {}\n", sum);

    let product = gateway.calculate("multiply", 6, 7);
    println!("[app] 6 * 7 = {}\n", product);

    let chained = gateway.calculate("add", sum, product);
    println!("[app] {} + {} = {}\n", sum, product, chained);
}