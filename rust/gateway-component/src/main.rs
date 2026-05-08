use std::any::{Any, TypeId};
use calculator_api::CalculatorService;
use gateway_api::GatewayService;
use itara_core::{ItaraComponent, ItaraRegistry, load_and_register};
use serde_json::Value;

// ── HTTP proxy ────────────────────────────────────────────────────────────────
//
// Registered by the agent when ITARA_MODE=http.
// Implements CalculatorService — the gateway activator cannot tell the difference
// between this and the real CalculatorServiceImpl.
//
// Matches the Java reference implementation (HttpRemoteProxy):
//   POST /itara/{componentId}/{methodName}
//   Request body:  JSON array of args  e.g. [3, 4]
//   Response body: serialised result   e.g. 7

struct HttpCalculatorProxy {
    base_url: String,
}

impl HttpCalculatorProxy {
    fn call(&self, method: &str, args: Vec<Value>) -> Value {
        let url = format!("{}/itara/calculator/{}", self.base_url, method);
        println!("[Itara/HTTP] -> {} to {}", method, url);

        let response_str = ureq::post(&url)
            .set("Content-Type", "application/json")
            .send_string(&serde_json::to_string(&args).unwrap())
            .expect("[Itara/HTTP] Remote calculator call failed")
            .into_string()
            .expect("[Itara/HTTP] Failed to read response");

        serde_json::from_str(&response_str)
            .expect("[Itara/HTTP] Failed to parse response")
    }
}

impl CalculatorService for HttpCalculatorProxy {
    fn add(&self, a: i64, b: i64) -> i64 {
        self.call("add", vec![Value::from(a), Value::from(b)])
            .as_i64().expect("[Itara/HTTP] Expected i64 from add")
    }

    fn multiply(&self, a: i64, b: i64) -> i64 {
        self.call("multiply", vec![Value::from(a), Value::from(b)])
            .as_i64().expect("[Itara/HTTP] Expected i64 from multiply")
    }
}

impl ItaraComponent for HttpCalculatorProxy {
    fn as_any(&self) -> &dyn Any { self }

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

// ── Agent ─────────────────────────────────────────────────────────────────────

fn main() {
    let mode = std::env::var("ITARA_MODE")
        .unwrap_or_else(|_| "direct".to_string());
    let calc_lib = std::env::var("ITARA_CALCULATOR_LIB")
        .unwrap_or_else(|_| "./target/debug/libcalculator_component.so".to_string());
    let gateway_lib = std::env::var("ITARA_GATEWAY_LIB")
        .unwrap_or_else(|_| "./target/debug/libgateway_component.so".to_string());
    let calculator_url = std::env::var("CALCULATOR_URL")
        .unwrap_or_else(|_| "http://localhost:8081".to_string());

    println!("[Itara] Starting — topology: {}\n", mode);

    let mut registry = ItaraRegistry::new();

    // This is the topology decision. Nothing below this block changes.
    match mode.as_str() {
        "direct" => {
            load_and_register(&mut registry, "calculator", &calc_lib)
                .unwrap_or_else(|e| panic!("{}", e));
        }
        "http" => {
            registry.preregister(
                "calculator",
                Box::new(HttpCalculatorProxy { base_url: calculator_url }),
            );
        }
        other => panic!("[Itara] Unknown ITARA_MODE: '{}' — use 'direct' or 'http'", other),
    }

    load_and_register(&mut registry, "gateway", &gateway_lib)
        .unwrap_or_else(|e| panic!("{}", e));

    // Freeze the registry — read-only from this point on.
    ItaraRegistry::install_global(registry);

    // ── Application code ──────────────────────────────────────────────────
    // Agent's job is done. No topology knowledge below this line.

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
