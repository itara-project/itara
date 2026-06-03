# Itara

**Make software soft again.**

Itara is a compiler and linker for distributed system topology. It treats topology — how components connect, communicate, and are observed — as a concentrated, separately declared layer, not a consequence of how code was written.

This is achieved through two co-equal parts: a language-specific wiring agent that reads the wiring config before the application starts, resolves all connections once, wires the components together, and then steps aside — the application runs at full speed with no intermediary, no proxy in the call path, no decisions made at call time — and a tooling ecosystem that makes the topology layer safe and manageable. The tooling validates configurations before deployment, catches mismatches and incompatibilities at authoring time, visualises the topology as a graph, and guides engineers through changes. Incorrect topologies cannot be deployed silently. The layer Itara introduces is the layer the tooling understands completely.

Change how your components communicate — collocated direct calls, HTTP, message queues — by changing a config file. No code changes. No redeployment ceremony. No migration scripts. Reference implementations of the wiring agent exist in Java and Rust. More languages are planned. The tooling ecosystem has begun: `itara-cli` ships two commands — `itara inspect` to visualise a topology and `itara verify` to catch configuration errors before deployment. The visual editor and controller are planned.

---

## The problem

Every production system in the world handles topology change through ritual.
Want to split a service? Months of parallel running, dual-write patterns, careful traffic migration. Want to merge two services? Same thing in reverse. Want to change from HTTP to a message queue between two components? Touch both services, coordinate deployment, pray.

This is the state of the art. The patterns are elegant — blue-green deployments, expand-and-contract, strangler fig — but every one of them is ceremony. External scaffolding bolted around systems that fundamentally cannot evolve themselves. And ceremony is where mistakes live: in the coordination between teams, in the timing of deployments, in the assumptions that were true last week and aren't today.

The deeper problem is that topology is invisible. It lives in HTTP clients, retry policies, timeout configurations, and message producer settings scattered across every service. Nobody has the full picture. Changes are made by reading code, making assumptions, and hoping the assumptions hold. The system cannot tell you what it is. It cannot tell you what will break if you change it. It cannot tell you if the change you just made is correct.

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
  itara-cli/   CLI tooling — itara inspect and itara verify
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

## Running the CLI

```bash
cd rust && cargo build -p itara-cli

# Visualise a topology — nodes, connections, deployment groups, graph
./target/debug/itara inspect ../java/itara-demo/wiring-http.yaml

# Verify a topology — catches orphaned nodes, undeclared references,
# duplicate ids, self-connections, and unknown transport types
./target/debug/itara verify ../java/itara-demo/wiring-http.yaml
```

`itara verify` exits non-zero on errors, making it suitable for CI pipelines.

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
- itara-cli — `itara inspect` visualises topology and deployment groups, `itara verify` catches configuration errors before deployment

**Planned:**
- Kafka transport
- Language-neutral contract descriptor (IDL)
- Controller (Orca) for runtime topology management
- Service discovery integration

See [VISION.md](spec/VISION.md) for the full architectural vision and [SPEC.md](spec/SPEC.md) for the formal specification.

---

## Author

Gabor Kiss — concept, architecture, initial implementation. April 2026.

## License

Apache License 2.0 — see LICENSE.
