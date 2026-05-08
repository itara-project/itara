use calculator_api::CalculatorService;
use calculator_component::CalculatorServiceImpl;
use serde::Serialize;
use serde_json::Value;
use tiny_http::{Response, Server};

// Matches the Java reference implementation (ItaraHttpServer):
//   POST /itara/{componentId}/{methodName}
//   Request body:  JSON array of args  e.g. [3, 4]
//   Response body: serialised result   e.g. 7

#[derive(Serialize)]
struct ItaraError {
    error: String,
}

fn err_str(msg: &str) -> String {
    serde_json::to_string(&ItaraError { error: msg.to_string() }).unwrap()
}

fn main() {
    let port = std::env::var("CALCULATOR_PORT").unwrap_or_else(|_| "8081".to_string());
    let addr = format!("0.0.0.0:{}", port);

    println!("[calculator-server] starting on {}", addr);
    println!("[calculator-server] endpoint: POST /itara/calculator/{{method}}");

    let server = Server::http(&addr).expect("Failed to start HTTP server");
    let calc = CalculatorServiceImpl;

    for mut request in server.incoming_requests() {
        // Capture method and path before any move of `request`
        let method = request.method().as_str().to_string();
        let path = request.url().to_string();

        // Route: POST /itara/{componentId}/{methodName}
        let parts: Vec<&str> = path.splitn(5, '/').collect();
        // parts: ["", "itara", componentId, methodName]

        if method != "POST" || parts.len() < 4 || parts.get(1) != Some(&"itara") {
            request.respond(
                Response::from_string(err_str(&format!(
                    "expected POST /itara/{{componentId}}/{{method}}, got {} {}",
                    method, path
                ))).with_status_code(400)
            ).ok();
            continue;
        }

        let method_name = parts[3].to_string();

        let mut body = String::new();
        request.as_reader().read_to_string(&mut body).unwrap_or(0);

        let args: Vec<Value> = match serde_json::from_str(&body) {
            Ok(a) => a,
            Err(e) => {
                request.respond(
                    Response::from_string(err_str(&format!("failed to deserialise args: {}", e)))
                        .with_status_code(400)
                ).ok();
                continue;
            }
        };

        println!("[calculator-server] <- {} {:?}", method_name, args);

        let result: Value = match method_name.as_str() {
            "add" => {
                match (args.get(0).and_then(Value::as_i64), args.get(1).and_then(Value::as_i64)) {
                    (Some(a), Some(b)) => Value::from(calc.add(a, b)),
                    _ => {
                        request.respond(
                            Response::from_string(err_str("add requires two i64 args"))
                                .with_status_code(400)
                        ).ok();
                        continue;
                    }
                }
            }
            "multiply" => {
                match (args.get(0).and_then(Value::as_i64), args.get(1).and_then(Value::as_i64)) {
                    (Some(a), Some(b)) => Value::from(calc.multiply(a, b)),
                    _ => {
                        request.respond(
                            Response::from_string(err_str("multiply requires two i64 args"))
                                .with_status_code(400)
                        ).ok();
                        continue;
                    }
                }
            }
            unknown => {
                request.respond(
                    Response::from_string(err_str(&format!("unknown method: {}", unknown)))
                        .with_status_code(400)
                ).ok();
                continue;
            }
        };

        request.respond(
            Response::from_string(serde_json::to_string(&result).unwrap())
                .with_status_code(200)
        ).ok();
    }
}