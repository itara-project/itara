use std::any::{Any, TypeId};
use calculator_api::CalculatorService;
use gateway_api::GatewayService;
use itara_core::{ItaraComponent, ItaraRegistry};

/// The gateway implementation.
/// Holds a reference to whatever the registry provided — real impl or HTTP proxy.
/// Does not know which. That is the point.
pub struct GatewayServiceImpl {
    calculator: &'static dyn CalculatorService,
}

impl GatewayService for GatewayServiceImpl {
    fn calculate(&self, op: &str, a: i64, b: i64) -> i64 {
        println!("[gateway] calculate({}, {}, {})", op, a, b);
        match op {
            "add"      => self.calculator.add(a, b),
            "multiply" => self.calculator.multiply(a, b),
            unknown    => panic!("[gateway] unknown operation: '{}'", unknown),
        }
    }
}

impl ItaraComponent for GatewayServiceImpl {
    fn as_any(&self) -> &dyn Any {
        self
    }

    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn GatewayService>() {
            let fat: *const dyn GatewayService = self as &dyn GatewayService as *const dyn GatewayService;
            // Transmute the fat pointer to extract both words
            let words: (*const (), *const ()) = unsafe { std::mem::transmute(fat) };
            Some(words)
        } else {
            None
        }
    }
}

/// The gateway activator.
/// Pulls CalculatorService from the global registry — receives either a direct
/// instance (collocated topology) or an HTTP proxy (remote topology).
/// This code does not change between topologies. That is the point.
#[unsafe(no_mangle)]
pub extern "C" fn itara_activator(registry: *const ItaraRegistry) -> Box<dyn ItaraComponent> {
    println!("[gateway] activator called — pulling calculator from registry");
    let registry = unsafe { &*registry };
    let calculator = registry.get::<dyn CalculatorService>("calculator");
    println!("[gateway] calculator resolved — creating GatewayServiceImpl");
    Box::new(GatewayServiceImpl { calculator })
}