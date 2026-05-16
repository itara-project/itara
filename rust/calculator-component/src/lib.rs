use std::any::{Any, TypeId};
use calculator_api::CalculatorService;
use itara_core::{ItaraComponent, ItaraRegistry};

/// The calculator implementation.
/// No knowledge of HTTP, topology, or Itara internals beyond the two trait impls.
pub struct CalculatorServiceImpl;

impl CalculatorService for CalculatorServiceImpl {
    fn add(&self, a: i64, b: i64) -> i64 {
        println!("[calculator] add({}, {})", a, b);
        a + b
    }

    fn multiply(&self, a: i64, b: i64) -> i64 {
        println!("[calculator] multiply({}, {})", a, b);
        a * b
    }
}

/// ItaraComponent impl — enables type-erased storage and trait-object retrieval.
/// cast_to() is the mechanism that lets registry.get::<dyn CalculatorService>()
/// work without the caller ever naming CalculatorServiceImpl.
/// This boilerplate will be a #[derive(ItaraComponent)] macro later.
impl ItaraComponent for CalculatorServiceImpl {
    fn as_any(&self) -> &dyn Any {
        self
    }

    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn CalculatorService>() {
            let fat = self as &dyn CalculatorService as *const dyn CalculatorService;
            // Transmute the fat pointer to extract both words
            let words: (*const (), *const ()) = unsafe { std::mem::transmute(fat) };
            Some(words)
        } else {
            None
        }
    }
}

/// The activator — the single exported symbol the agent looks for in the .so.
/// No parameters. Returns type-erased ownership to the registry.
#[unsafe(no_mangle)]
pub extern "C" fn itara_activator(_registry: *const ItaraRegistry) -> Box<dyn ItaraComponent> {
    println!("[calculator] activator called");
    Box::new(CalculatorServiceImpl)
}