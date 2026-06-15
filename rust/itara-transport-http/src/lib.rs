use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use itara_core::{Dispatcher, ItaraTransport};

pub struct HttpTransport {
    base_url:    String,
    listen_port: u16,
    dispatchers: Arc<Mutex<HashMap<String, Dispatcher>>>,
}

impl HttpTransport {
    pub fn new(base_url: String, listen_port: u16) -> Self {
        HttpTransport {
            base_url,
            listen_port,
            dispatchers: Arc::new(Mutex::new(HashMap::new())),
        }
    }
}

impl ItaraTransport for HttpTransport {
    fn invoke(
        &self,
        component_id: &str,
        method:       &str,
        args:         &[u8],
        headers:      &HashMap<String, String>,
    ) -> Vec<u8> {
        let url = format!("{}/itara/{}/{}", self.base_url, component_id, method);
        println!("[Itara/HTTP] -> {} to {}", method, url);

        let mut request = ureq::post(&url)
            .set("Content-Type", "application/octet-stream");

        for (key, value) in headers {
            request = request.set(key, value);
        }
 
        let response = request
            .send_bytes(args)
            .unwrap_or_else(|e| panic!(
                "[Itara/HTTP] Call to {}/{} failed: {}", component_id, method, e
            ));

        let mut bytes = Vec::new();
        response.into_reader()
            .read_to_end(&mut bytes)
            .expect("[Itara/HTTP] Failed to read response body");
        bytes
    }

    fn register_listener(&self, component_id: &str, dispatcher: Dispatcher) {
        println!("[Itara/HTTP] Registered listener for: {}", component_id);
        self.dispatchers.lock().unwrap()
            .insert(component_id.to_string(), dispatcher);
    }

    fn start(&self) {
        let addr = format!("0.0.0.0:{}", self.listen_port);
        println!("[Itara/HTTP] Starting server on {}", addr);

        let dispatchers = Arc::clone(&self.dispatchers);

        std::thread::spawn(move || {
            let server = tiny_http::Server::http(&addr)
                .unwrap_or_else(|e| panic!(
                    "[Itara/HTTP] Failed to start server on {}: {}", addr, e
                ));

            println!("[Itara/HTTP] Server listening on {}", addr);

            for mut request in server.incoming_requests() {
                let http_method = request.method().as_str().to_string();
                let path        = request.url().to_string();
                let parts: Vec<&str> = path.splitn(5, '/').collect();

                if http_method != "POST"
                    || parts.len() < 4
                    || parts.get(1) != Some(&"itara")
                {
                    request.respond(err_response(
                        &format!(
                            "expected POST /itara/{{componentId}}/{{method}}, got {} {}",
                            http_method, path
                        ),
                        400,
                    )).ok();
                    continue;
                }

                let component_id = parts[2].to_string();
                let method_name  = parts[3].to_string();

                // Collect all inbound headers into a lowercase-key map.
                // HTTP headers are case-insensitive — lowercase normalisation
                // ensures ContextPropagation and observers find their keys
                // regardless of what the sender's HTTP stack produced.
                let mut headers: HashMap<String, String> = HashMap::new();
                for h in request.headers() {
                    headers.insert(
                        h.field.as_str().as_str().to_lowercase(),
                        h.value.as_str().to_string(),
                    );
                }

                let mut body_bytes = Vec::new();
                request.as_reader().read_to_end(&mut body_bytes).unwrap_or(0);

                println!(
                    "[Itara/HTTP] <- {}/{} {:?}",
                    component_id, method_name, body_bytes.as_slice()
                );

                let result = {
                    let dispatchers = dispatchers.lock().unwrap();
                    match dispatchers.get(&component_id) {
                        Some(dispatcher) => dispatcher(
                            &method_name,
                            body_bytes.as_slice(),
                            &headers,
                        ),
                        None => {
                            drop(dispatchers);
                            request.respond(err_response(
                                &format!("no listener for component: {}", component_id),
                                404,
                            )).ok();
                            continue;
                        }
                    }
                };

                request.respond(
                    tiny_http::Response::from_data(result)
                        .with_status_code(200)
                ).ok();
            }
        });
    }
}

fn err_response(msg: &str, status: u16) -> tiny_http::Response<std::io::Cursor<Vec<u8>>> {
    tiny_http::Response::from_string(format!("{{\"error\":\"{}\"}}",
        msg.replace('"', "\\\"")))
        .with_status_code(status)
}

/// Factory function exported from the cdylib.
#[unsafe(no_mangle)]
pub extern "C" fn itara_transport_factory(
    base_url_ptr: *const std::os::raw::c_char,
    listen_port:  u16,
) -> Box<dyn ItaraTransport> {
    let base_url = if base_url_ptr.is_null() {
        String::new()
    } else {
        unsafe { std::ffi::CStr::from_ptr(base_url_ptr) }
            .to_string_lossy()
            .into_owned()
    };
    Box::new(HttpTransport::new(base_url, listen_port))
}
