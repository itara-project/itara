# Itara Demo — Order Processing System

This demo runs the same order processing system in three different topologies.
The business logic doesn't change. The components don't change. Only the wiring
config changes — and the traces show exactly what each topology decision costs.

---

## The system

An order processing application with five components:

| Component | Language | Responsibility |
|-----------|----------|----------------|
| order | Java | Receives orders, orchestrates the flow |
| inventory | Java | Stock management — reserve and release |
| payment | Rust | Payment processing |
| fulfilment | Java | Order fulfilment after payment |
| notification | Java | Order confirmation |

Payment is implemented in Rust and runs as a separate process in all three
topologies. This reflects a common real-world pattern — payment services
running in a separate security boundary. Java-Rust colocation is not
supported; the two runtimes communicate over HTTP, which is visible in
the traces. See [ADR 0013](../docs/adr/0013-rust-dynamic-approach-evaluation.md)
for the current state and direction of the Rust implementation.

---

## The three topologies

### Monolith
Order, inventory, fulfilment, and notification run in a single JVM.
Payment runs as a separate Rust process.

All calls between the colocated Java components are direct in-process calls —
no serialization, no network. The traces show near-zero overhead between them.
The payment call crosses the network and its cost is directly measurable.

### Microservices
Every component runs in its own container, communicating over HTTP.

The traces show what that decision actually costs: each network hop is visible,
serialization overhead is measurable on both sides of every call.

### Informed
The traces from the microservices topology showed that inventory is called
twice per order — reserve and release — and the two components are always
deployed together. Order and inventory are colocated in this topology.
Everything else stays distributed.

The result: the remote call overhead for inventory disappears from the traces.
The inventory calls still happen — the cost of the transport does not.

The wiring config is the only thing that changed between all three topologies.
See [SPEC §4](../spec/SPEC.md#4-wiring-model) for the full wiring model
specification.

---

## Prerequisites

- Docker and Docker Compose
- Java 21+, Maven 3.9+
- Rust toolchain (for the payment service)
- On Windows: WSL2 for building the Rust payment binary for Linux (you can use `setup-rust-env.sh` for first-time setup)

### OTel libraries (Java)

The Java services require OpenTelemetry libraries at runtime. Collect them
from your local Maven repository after building with the `collect-otel-libs.sh` script.

---

## Building

Build the Itara wiring agent and core libraries first, then the demo components.

> **WSL users:** If your repo is checked out on the Windows filesystem
> (`/mnt/c/...`), Maven will fail with a POSIX permissions error.
> Check out the repo inside the WSL filesystem instead
> (`~/projects/itara` or similar). Alternatively, build the Java
> components on Windows and only use WSL for the Rust payment binary.

```bash
# 1. Build the Itara Java wiring agent and core libraries
mvn install -f java/pom.xml

# 2. Build the Itara Rust wiring agent and core libraries
cd rust && cargo build --release

# 3. Build all demo components
mvn install -f demo/inventory/pom.xml
mvn install -f demo/fulfilment/pom.xml
mvn install -f demo/notification/pom.xml
mvn install -f demo/order/pom.xml
mvn install -f demo/payment/java/payment-api/pom.xml


# 4. Build the payment Rust binary for Linux (On Windows, run this in WSL):
cd demo/payment && cargo build --release

# 5. Copy artifacts into the demo lib directories
cd demo && ./collect-libs.sh

# 6. Collect the necessary OTel libraries
cd demo && ./collect-otel-libs.sh
```

---

## Running

Start the observability stack once and leave it running:

```bash
docker compose -f demo/docker-compose-otel.yml up -d
```

Wait until Kibana is ready — about 60 seconds on first run.
Check: http://localhost:5601

Then start whichever topology you want to run:

```bash
# Monolith
docker compose -f demo/docker-compose-monolith.yml up

# Microservices
docker compose -f demo/docker-compose-microservices.yml up

# Informed
docker compose -f demo/docker-compose-informed.yml up
```

To switch topologies, stop the current one and start another. The
observability stack keeps running — traces from all topologies accumulate
in Kibana for comparison.

---

## Sending requests

Add stock to the inventory:

```bash
curl -X POST http://localhost:8082/itara/inventory/addItem \
     -H "Content-Type: application/json" \
     -d '["WIDGET-A", "Flux Capacitor", 100]'
```

Place an order:

```bash
curl -X POST http://localhost:8081/itara/order/placeOrder \
     -H "Content-Type: application/json" \
     -d '["order-1", "WIDGET-A", 1, 80, "USD"]'
```

Arguments: `[orderId, productId, quantity, price, currency]`

---

## Viewing traces

Open Kibana: http://localhost:5601 → Observability → APM → Traces

Each topology run appears under its own service names. Select a trace to
see the full timeline.

### Understanding what you are looking at

Itara fires four events for every component interaction, regardless of
transport type — see [ADR 0003](../docs/adr/0003-four-event-observability-model.md)
for the full model and [ADR 0010](../docs/adr/0010-observability-fired-by-agent-not-transport.md)
for why the events fire where they do.

| Event | Side | Fires |
|-------|------|-------|
| `CALL_SENT` | Caller | Before serialization |
| `CALL_RECEIVED` | Callee | After deserialization |
| `RETURN_SENT` | Callee | Before serialization of result |
| `RETURN_RECEIVED` | Caller | After deserialization of result |

This produces two spans per call in the trace:

- **Outer span** (CALL_SENT → RETURN_RECEIVED) — the full round trip from
  the caller's perspective, including all transport cost
- **Inner span** (CALL_RECEIVED → RETURN_SENT) — pure component execution
  time, independent of serialization format or transport

The gap between the outer and inner span is the transport overhead:
serialization, network, deserialization. For direct calls the gap is
nearly zero. For remote calls it is directly measurable. This decomposition
happens without any instrumentation in the component code.

### In the monolith trace

- Direct calls between Java components show two spans with a near-zero gap —
  the outer and inner span are almost identical in duration
- The payment call shows a visible gap — serialization and network cost to
  the Rust process
- Two services visible in the trace legend: the Java monolith and payment

### In the microservices trace

- Every call shows a visible gap between outer and inner span
- Component execution times are unchanged — only the transport costs differ
- Five services visible in the trace legend

### In the informed trace

- Inventory calls look like the monolith — near-zero gap, direct
- Everything else looks like microservices — transport overhead visible
- The contrast between the two patterns is visible in the same trace

---

## What the CLI shows

Before running anything, `itara inspect` derives the deployment groups
directly from the wiring config — no containers, no network, just the config:

```bash
./rust/target/release/itara inspect demo/wiring-monolith.yaml
./rust/target/release/itara inspect demo/wiring-microservices.yaml
./rust/target/release/itara inspect demo/wiring-informed.yaml
```

`itara verify` checks the config for errors before anything starts:

```bash
./rust/target/release/itara verify demo/wiring-informed.yaml
```

The topology is visible and validated before a single container starts.
See [SPEC §11](../spec/SPEC.md#11-tooling) for the tooling specification.
