# Itara

**Make software soft again.**

Itara is a language-neutral specification for treating distributed system topology as a configuration decision, not a code decision. Reference implementations exist in Java and Rust. More languages are planned.

Change how your components communicate — collocated direct calls, HTTP, message queues — by changing a config file. No code changes. No redeployment ceremony. No migration scripts. Restart with a new config and the topology changes.

---

## The problem

Every production system in the world handles topology change through ritual.
Want to split a service? Months of parallel running, dual-write patterns, careful traffic migration. Want to merge two services? Same thing in reverse. Want to change from HTTP to a message queue between two components? Touch both services, coordinate deployment, pray.

This is the state of the art. The patterns are elegant — blue-green deployments, expand-and-contract, strangler fig — but every one of them is ceremony. External scaffolding bolted around systems that fundamentally cannot evolve themselves.

Itara proposes that topology should be a continuously adjustable variable, not a hardcoded consequence of how services were originally written.

---

## The idea

A component declares its contract — what it accepts and what it returns. It does not declare how it is called. That is the runtime's decision.

The Itara agent reads a wiring config at startup and connects components to each other using whatever transport the config specifies. The component code is identical regardless of whether it is called as a direct in-process method or over HTTP from a separate process — in any supported language.

```yaml
# One master config describes the entire topology.
# Each process is told which node it represents at startup.
# Change this file. Restart. Topology changes.

nodes:
  - id: gatewayNode
    component: gateway
  - id: calculatorNode
    component: calculator

connections:
  - from: gatewayNode
    to:   calculatorNode
    type: direct      # or: http, kafka — code does not change
```

---

## The proof of concept

Two components. One adds numbers. One accepts requests and delegates. Implemented in both Java and Rust.

**Direct topology — both components in one process:**

```
[Gateway] Received request: add(3, 4)
[Calculator] add(3, 4) = 7
[Gateway] Returning: The result of 3 + 4 = 7
```

**HTTP topology — two separate processes:**

```
# Gateway process:
[Gateway] Received request: add(3, 4)
[Itara/HTTP] -> add on calculator at localhost:8081
[Gateway] Returning: The result of 3 + 4 = 7

# Calculator process:
[Itara/HTTP] <- add on calculator
[Calculator] add(3, 4) = 7
```

Same gateway code. Same calculator code. Different config file. This works across Java and Rust — a Java gateway can call a Rust calculator and vice versa, with no code changes in either component.

**External HTTP entry point:**

```bash
curl -X POST http://localhost:8082/itara/gateway/calculate \
     -H "Content-Type: application/json" \
     -d "[32, 41]"
# → "The result of 32 + 41 = 73"
```

Add an inbound HTTP connection to any component in the wiring config and Itara automatically starts an HTTP server for it. No code changes.

---

## Language support

| Language | Status | Notes |
|----------|--------|-------|
| Java | Reference implementation | JVM agent, full observability, Spring Boot compatible |
| Rust | Working implementation | Transport SPI, config parser, agent library |
| Go | Planned | — |
| Python | Planned | — |
| C++ | Planned | — |

Components in different languages participate in the same topology graph and produce the same distributed traces.

---

## Observability

Itara treats observability as a first-class citizen. Every component call produces four events regardless of transport:

- **CALL_SENT** — caller side, before dispatch
- **CALL_RECEIVED** — callee side, on arrival  
- **RETURN_SENT** — callee side, before response
- **RETURN_RECEIVED** — caller side, on return

This makes network latency directly observable: the gap between CALL_SENT and CALL_RECEIVED is the outbound transport cost. The gap between RETURN_SENT and RETURN_RECEIVED is the return path. Component processing time is measured independently of transport cost on both sides.

**OpenTelemetry is built in** for the Java implementation. Distributed traces appear in Kibana with correct parent-child relationships across JVMs, using W3C traceparent headers for propagation. No code changes required. OTel support for Rust is planned.

---

## Repository structure

```
java/          Java reference implementation (JVM agent, Spring Boot compatible)
rust/          Rust implementation (transport SPI, config parser, agent library)
docs/
  adr/         Architecture Decision Records
spec/          VISION.md, MANIFESTO.md, SPEC.md
```

---

## Running the Java demo

```bash
cd java && mvn install
cd java/itara-demo
docker compose -f docker-compose-http.yml up
```

Wait ~60 seconds, then:
- **Kibana APM**: http://localhost:5601 → Observability → APM → Services
- **Make a call**: `curl -X POST http://localhost:8082/itara/gateway/calculate -H "Content-Type: application/json" -d "[32, 41]"`

## Running the Rust demo

```bash
cd rust

# Direct topology
ITARA_CONFIG=../demo/wiring-direct.yaml \
ITARA_NODES=gatewayNode,calculatorNode \
cargo run -p gateway-component --bin gateway

# HTTP topology — two terminals
ITARA_CONFIG=../demo/wiring-http.yaml ITARA_NODES=calculatorNode \
cargo run -p calculator-component --bin calculator-server

ITARA_CONFIG=../demo/wiring-http.yaml ITARA_NODES=gatewayNode \
CALCULATOR_URL=http://127.0.0.1:8081 \
cargo run -p gateway-component --bin gateway
```

---

## Current state

**Working:**
- Direct and HTTP topologies in Java and Rust
- Cross-language topology (Java ↔ Rust over HTTP)
- Master wiring config — one file, each process self-selects its slice
- Inbound HTTP server — any component can accept external calls via config
- JSON serializer (pluggable via SPI)
- Full observability — four-event model across all transports and languages
- OpenTelemetry bridge — distributed traces in Kibana across JVMs and Rust processes
- W3C traceparent propagation
- Spring Boot compatible — components as Spring beans fetched from the Itara registry
- YAML wiring config with environment variable substitution

**Planned:**
- Kafka transport
- Rust observability SPI and OTel bridge
- Language-neutral contract descriptor (IDL)
- Controller (Orca) for runtime topology management
- itara-cli — topology inspection and validation
- Service discovery integration

See [VISION.md](spec/VISION.md) for the full architectural vision and [SPEC.md](spec/SPEC.md) for the formal specification.

---

## Author

Gabor Kiss — concept, architecture, initial implementation. April 2026.

## License

Apache License 2.0 — see LICENSE.
