use std::any::{Any, TypeId};
use itara_core::{ItaraComponent, ItaraTransport, Dispatcher};

// ── Contract ──────────────────────────────────────────────────────────────────

pub trait CalculatorService: ItaraComponent {
    fn add(&self, a: i64, b: i64) -> i64;
    fn multiply(&self, a: i64, b: i64) -> i64;
}

// ── Generated proxy (future: #[itara_component] macro output) ─────────────────

pub struct CalculatorServiceProxy {
    transport:    Box<dyn ItaraTransport>,
    component_id: String,
}

impl CalculatorServiceProxy {
    pub fn new(transport: Box<dyn ItaraTransport>, component_id: &str) -> Self {
        CalculatorServiceProxy { transport, component_id: component_id.to_string() }
    }
}

impl CalculatorService for CalculatorServiceProxy {
    fn add(&self, a: i64, b: i64) -> i64 {
        let payload = serde_json::to_vec(&[a, b]).expect("[CalculatorProxy] Failed to serialize args");
        let response = self.transport.invoke(&self.component_id, "add", &payload);
        serde_json::from_slice::<i64>(&response).expect("[CalculatorProxy] Expected i64 from add")
    }
    fn multiply(&self, a: i64, b: i64) -> i64 {
        
        let payload = serde_json::to_vec(&[a, b]).expect("[CalculatorProxy] Failed to serialize args");
        let response = self.transport.invoke(&self.component_id, "multiply", &payload);
        serde_json::from_slice::<i64>(&response).expect("[CalculatorProxy] Expected i64 from multiply")
    }
}

impl ItaraComponent for CalculatorServiceProxy {
    fn as_any(&self) -> &dyn Any { self }
    fn cast_to(&self, trait_id: TypeId) -> Option<(*const (), *const ())> {
        if trait_id == TypeId::of::<dyn CalculatorService>() {
            let fat = self as &dyn CalculatorService as *const dyn CalculatorService;
            Some(unsafe { std::mem::transmute(fat) })
        } else {
            None
        }
    }
}

// ── Generated dispatcher (future: #[itara_component] macro output) ────────────

pub fn calculator_dispatcher(component: &'static dyn CalculatorService) -> Dispatcher {
    Box::new(move |method: &str, args: &[u8]| -> Vec<u8> {
        match method {
            "add" => {
                let vals: [i64; 2] = serde_json::from_slice(args)
                    .expect("[CalculatorDispatcher] add: expected [i64, i64]");
                serde_json::to_vec(&component.add(vals[0], vals[1]))
                    .expect("[CalculatorDispatcher] Failed to serialize result")
            }
            "multiply" => {
                let vals: [i64; 2] = serde_json::from_slice(args)
                    .expect("[CalculatorDispatcher] multiply: expected [i64, i64]");
                serde_json::to_vec(&component.multiply(vals[0], vals[1]))
                    .expect("[CalculatorDispatcher] Failed to serialize result")
            }
            unknown => panic!("[CalculatorDispatcher] unknown method: '{}'", unknown),
        }
    })
}