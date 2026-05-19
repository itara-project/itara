use gateway_api::GatewayService;
use itara_agent::{itara_init, itara_get};
 
fn main() {
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