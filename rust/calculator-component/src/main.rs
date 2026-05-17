use calculator_api::{CalculatorService, calculator_dispatcher};
use itara_agent::{itara_init, itara_run, itara_register_dispatcher};

fn main() {
    itara_register_dispatcher("calculator", Box::new(|method: &str, args: &[u8]| {
        let calc = itara_core::ItaraRegistry::global()
            .get::<dyn CalculatorService>("calculator");
        calculator_dispatcher(calc)(method, args)  // call the generated dispatcher
    }));
    
    itara_init();
    itara_run();
}