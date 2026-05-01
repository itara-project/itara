# Itara

**Make software soft again.**

Itara is a JVM runtime that treats distributed system topology as a
configuration decision, not a code decision.

Change how your components communicate — collocated direct calls, HTTP,
message queues — by changing a config file. No code changes. No redeployment
ceremony. No migration scripts. Restart the JVM with a new config and the
topology changes.

---

## The problem

Every production system in the world handles topology change through ritual.
Want to split a service? Months of parallel running, dual-write patterns,
careful traffic migration. Want to merge two services? Same thing in reverse.
Want to change from HTTP to a message queue between two components? Touch
both services, coordinate deployment, pray.

This is the state of the art. The patterns are elegant — blue-green deployments,
expand-and-contract, strangler fig — but every one of them is ceremony. External
scaffolding bolted around systems that fundamentally cannot evolve themselves.

Itara proposes that topology should be a continuously adjustable variable,
not a hardcoded consequence of how services were originally written.

---

## The idea

A component declares its contract — what it accepts and what it returns.
It does not declare how it is called. That is the runtime's decision.

The Itara agent intercepts the JVM startup, reads a wiring config, and
connects components to each other using whatever transport the config specifies.
The component code is identical regardless of whether it is called as a direct
in-process method or over HTTP from a separate JVM.

```yaml
# Change this file. Restart the JVM. Topology changes.

connections:
  - from: gateway
    to:   calculator
    type: direct      # or: http, kafka — code does not change
```

---

## The proof of concept

Two components. One adds numbers. One accepts requests and delegates.

**Direct topology — both components in one JVM:**

```
[Gateway] Received request: add(3, 4)
[Calculator] add(3, 4) = 7
[Gateway] Returning: The result of 3 + 4 = 7
```

**HTTP topology — two separate JVMs:**

```
# Gateway JVM:
[Gateway] Received request: add(3, 4)
[Itara/HTTP] -> add on calculator at localhost:8081
[Gateway] Returning: The result of 3 + 4 = 7

# Calculator JVM:
[Itara/HTTP] <- add on calculator
[Calculator] add(3, 4) = 7
```

Same gateway code. Same calculator code. Different config file.
The gateway JVM does not have calculator-component.jar on its classpath
in the HTTP run — it never sees the implementation. The agent generates
a proxy from the API jar alone.

**External HTTP entry point — call the gateway directly:**

```bash
curl -X POST http://localhost:8082/itara/gateway/calculate \
     -H "Content-Type: application/json" \
     -d "[32, 41]"
# → "The result of 32 + 41 = 73"
```

Add an inbound HTTP connection to any component in the wiring config and
Itara automatically starts an HTTP server for it. No code changes.

---

## Observability

Itara treats observability as a first-class citizen. Every component call
produces four events regardless of transport:

- **CALL_SENT** — caller side, before dispatch
- **CALL_RECEIVED** — callee side, on arrival
- **RETURN_SENT** — callee side, before response
- **RETURN_RECEIVED** — caller side, on return

This makes network latency directly observable: the gap between CALL_SENT
and CALL_RECEIVED is the transport overhead. The gap between RETURN_SENT
and RETURN_RECEIVED is the return path. Both sides of every call are measured
independently.

**OpenTelemetry is built in.** Drop `itara-observability-otel` into
`itara.lib.dir` and add the OTel SDK to your classpath. Itara generates
distributed traces with correct parent-child relationships across JVMs,
using W3C traceparent headers for propagation. No code changes required.

Each span carries:
- `itara.component` — the component being called
- `itara.method` — the method name
- `itara.transport` — actual transport used (direct, http, kafka)
- `itara.edge.path` — the full call chain (e.g. `[gateway, calculator]`)
- `itara.request.id` — for cross-signal correlation
- `itara.source.node` — the originating node

Latency metrics are recorded as a histogram (`itara.call.duration`)
with component, method, transport, and error dimensions — sufficient
for latency alerting and SLO tracking without additional configuration.

**The observer SPI** allows custom observability implementations. Multiple
observers can run simultaneously — a logging observer and a custom metrics
sink, for example. OTel is built-in infrastructure, not an observer
implementation; the SPI is for passive consumers of events.

---

## Structure

```
itara-common/                  SPIs, registries, ItaraContext, OtelBridge, ObservabilityFacade
itara-agent/                   JVM premain, classloader, wiring, OtelBridgeLoader, ObserverLoader
itara-transport-http/          HttpTransport, HttpRemoteProxy, ItaraHttpServer
itara-serializer-json/         JSON serializer (default, shaded Jackson)
itara-serializer-java/         Java serializer (legacy opt-in)
itara-observability-otel/      OtelBridgeImpl — spans and metrics via OTel API
itara-observability-logging/   LoggingObserver — structured event logging
itara-integration-tests/       HttpTransportIntegrationTest
itara-demo/                    calculator-api, calculator-component, gateway-api, gateway-component
```

### Key concepts

**Contract** — an interface annotated `@ComponentInterface`. Lives in an
API jar. Defines what the component does. Says nothing about how it is called.

**Component** — one implementation of a contract. Lives in a component jar.
Has no knowledge of transport or topology.

**Activator** — one class per component jar implementing `ItaraActivator`.
Constructs the component's internal object graph and returns the root instance.
Discovered via `META-INF/itara/activator`.

**Wiring config** — a YAML file defining components and connections.
Supports environment variable substitution (`${VAR:-default}`).
The agent reads this at JVM startup.

**itara.lib.dir** — a directory of SPI jars loaded by the agent's child-first
classloader. Transports, serializers, and observers go here. The application
classpath never needs to change.

---

## Running the demo

**Build everything from the repo root:**

```bash
mvn install
```

### Option A — Docker (recommended)

Requires Docker Desktop.

**Collect OTel jars** (needed for distributed tracing):

```bash
# From itara-demo/
chmod +x collect-otel-libs.sh && ./collect-otel-libs.sh
```

**Direct topology — both components in one container:**

```bash
cd itara-demo
docker compose -f docker-compose-direct.yml up
```

**HTTP topology — two separate containers with full observability:**

```bash
cd itara-demo
docker compose -f docker-compose-http.yml up
```

Wait about 60 seconds (ElasticSearch takes a while to start up), then:
- **Kibana APM**: http://localhost:5601 → Observability → APM → Services  
- **Make a call**: `curl -X POST http://localhost:8082/itara/gateway/calculate -H "Content-Type: application/json" -d "[32, 41]"`
- **View the trace** in Kibana

### Option B — Docker without observability

**Direct topology — both components in one container:**

```bash
cd itara-demo
docker compose -f docker-compose-direct.yml up
```

The HTTP setup also works without observability of course, but since it's not the intended use, there is no demo for that. It is easy to modify the appropriate compose file though, if someone wants avoid the ELK stack.

### Option C — Native (local JDK 21+)

**Direct topology (one JVM):**

```bash
java -Ditara.lib.dir=libs \
     -Ditara.config=itara-demo/wiring-direct.yaml \
     -javaagent:itara-agent/target/itara-agent-1.0-SNAPSHOT.jar \
     -cp "itara-common/target/itara-common-1.0-SNAPSHOT.jar:\
          itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar:\
          itara-demo/calculator-component/target/calculator-component-1.0-SNAPSHOT.jar:\
          itara-demo/gateway-api/target/gateway-api-1.0-SNAPSHOT.jar:\
          itara-demo/gateway-component/target/gateway-component-1.0-SNAPSHOT.jar" \
     demo.gateway.component.DemoMain
```

**HTTP topology — start calculator first, then gateway:**

```bash
# Terminal 1 — calculator JVM
java -Ditara.lib.dir=libs \
     -Ditara.config=itara-demo/wiring-http-calculator.yaml \
     -javaagent:itara-agent/target/itara-agent-1.0-SNAPSHOT.jar \
     -cp "itara-common/target/itara-common-1.0-SNAPSHOT.jar:\
          itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar:\
          itara-demo/calculator-component/target/calculator-component-1.0-SNAPSHOT.jar" \
     io.itara.runtime.ItaraMain

# Terminal 2 — gateway JVM (after calculator prints "Server listening on port 8081")
java -Ditara.lib.dir=libs \
     -Ditara.config=itara-demo/wiring-http-gateway.yaml \
     -javaagent:itara-agent/target/itara-agent-1.0-SNAPSHOT.jar \
     -cp "itara-common/target/itara-common-1.0-SNAPSHOT.jar:\
          itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar:\
          itara-demo/gateway-api/target/gateway-api-1.0-SNAPSHOT.jar:\
          itara-demo/gateway-component/target/gateway-component-1.0-SNAPSHOT.jar" \
     demo.gateway.component.DemoMain
```

`calculator-component.jar` is absent from the gateway classpath.
The gateway never sees the implementation.

---

## Current state

**Working:**
- Direct and HTTP topologies
- Inbound HTTP server — any component can accept external HTTP calls via wiring config
- JSON and Java serializers (pluggable via SPI)
- Custom classloader for runtime/application classpath separation
- Activator-based lazy instantiation
- Full observability with four-event model (CALL_SENT, CALL_RECEIVED, RETURN_SENT, RETURN_RECEIVED)
- OpenTelemetry bridge — distributed traces in Kibana/Jaeger/any OTel backend
- W3C traceparent propagation across JVMs
- Edge path tracking across the call chain
- Error taxonomy (CHECKED / RUNTIME / TRANSPORT) with correct HTTP status codes
- YAML wiring config with environment variable substitution
- Logging observer SPI
- Integration tests

**Planned:**
- Kafka transport
- Spring Boot adapter
- Elastic sink for direct ELK export
- Controller (Orca) for runtime topology management
- Mathematical models for topology optimization
- Build plugin
- Language-neutral contract descriptor

See VISION.md for the full architectural vision.

---

## Author

Gabor Kiss — concept, architecture, initial implementation. April 2026.

## License

Apache License 2.0 — see LICENSE.
