# Itara Demo — Order Processing System

This demo runs the same order processing system in three topologies plus a failure-injection variant.
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

Payment is implemented in Rust and runs as a separate process in all four
topologies. This reflects a common real-world pattern — payment services
running in a separate security boundary. Java-Rust colocation is not
supported; the two runtimes communicate over HTTP, which is visible in
the traces. See [ADR 0013](../docs/adr/0013-rust-dynamic-approach-evaluation.md)
for the current state and direction of the Rust implementation.

Notification runs as a separate process in every topology and communicates
exclusively through events over Kafka — reflecting how notification systems are
typically deployed in practice, and demonstrating Itara's event-driven support.

---

## The four topologies

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

### Microservices — Flaky
The microservices topology with a flaky HTTP transport on all outbound
connections from order. Calls fail at random to simulate transient network
failures. Idempotent methods are retried transparently; non-idempotent ones
surface the failure immediately. Both cases are visible in the traces as
sibling `attempt` spans — no business code was changed.

The failure rate is controlled by `ITARA_FLAKY_FAIL_RATE` on the order
container (0.0–1.0, default 0.4).

The wiring config is the only thing that changed between all four topologies.
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

# 3b. Build the flaky transport (for the microservices-flaky scenario)
mvn install -f demo/flaky-transport/pom.xml


# 4. Build the payment Rust binary for Linux (On Windows, run this in WSL):
cd demo/payment && cargo build --release

# 5. Copy artifacts into the demo lib directories
cd demo && ./collect-libs.sh

# 6. Collect the necessary OTel libraries
cd demo && ./collect-otel-libs.sh
```

---

## What the CLI shows
 
Before running anything, `itara inspect` derives the deployment groups
directly from the wiring config — no containers, no network, just the config:
 
```
$ ./rust/target/release/itara inspect demo/wiring-monolith.yaml
Itara topology — demo/wiring-monolith.yaml

Nodes:
  fulfilmentNode        component: fulfilment
  inventoryNode         component: inventory                     (external entry point)
  notificationNode      component: notification
  paymentNode           component: payment
  orderNode             component: order                         (external entry point)
  orderReservedChannel  virtual:   order-events/order-reserved @ demo.events.order-reserved
  orderFulfilledChannel virtual:   fulfilment-events/order-fulfilled @ demo.events.order-fulfilled
  orderCancelledChannel virtual:   fulfilment-events/order-cancelled @ demo.events.order-cancelled

Connections:
  orderNode →             inventoryNode        [direct]
  orderNode →             fulfilmentNode       [direct]
  orderNode →             paymentNode          [http]
  orderNode →             orderReservedChannel [kafka]
  orderNode →             orderFulfilledChannel [kafka]
  orderNode →             orderCancelledChannel [kafka]
  orderReservedChannel →  notificationNode     [kafka]
  orderFulfilledChannel → notificationNode     [kafka]
  orderCancelledChannel → notificationNode     [kafka]

Deployment groups (derived):
  Group A: fulfilmentNode, orderNode, inventoryNode
    fulfilmentNode (fulfilment)
    orderNode (order)
      Receives: external http on :8081
      Calls:    inventoryNode via direct
      Calls:    fulfilmentNode via direct
      Calls:    paymentNode via http
      Emits:       orderReservedChannel (order-events/order-reserved)
      Emits:       orderFulfilledChannel (fulfilment-events/order-fulfilled)
      Emits:       orderCancelledChannel (fulfilment-events/order-cancelled)
    inventoryNode (inventory)
      Receives: external http on :8081

  Group B: notificationNode
    notificationNode (notification)
      Listens to:  orderReservedChannel (order-events/order-reserved)
      Listens to:  orderFulfilledChannel (fulfilment-events/order-fulfilled)
      Listens to:  orderCancelledChannel (fulfilment-events/order-cancelled)

  Group C: paymentNode
    paymentNode (payment)
      Receives: orderNode via http on :8083

Graph:
  [external] --http:8081--> [orderNode]
  [external] --http:8081--> [inventoryNode]
  [orderNode] --direct--> [inventoryNode]
  [orderNode] --direct--> [fulfilmentNode]
  [orderNode] --http:8083--> [paymentNode]
  [orderNode] --kafka--> [orderReservedChannel]
  [orderNode] --kafka--> [orderFulfilledChannel]
  [orderNode] --kafka--> [orderCancelledChannel]
  [orderReservedChannel] --kafka--> [notificationNode]
  [orderFulfilledChannel] --kafka--> [notificationNode]
  [orderCancelledChannel] --kafka--> [notificationNode]
```
 
All four topology configs can be inspected the same way. The deployment
groups change with the config — in the microservices topology every component
is its own group; in the informed topology order and inventory share a group.
 
`itara verify` checks the config for errors before anything starts:
 
```
$ ./rust/target/release/itara verify --metadata-dir demo/metafiles/ demo/wiring-monolith.yaml
✓ itara verify — demo/wiring-monolith.yaml

  8 nodes, 11 connections

  No issues found.
```
 
```
$ ./rust/target/release/itara verify --metadata-dir demo/metafiles/ demo/wiring-informed-with-error.yaml   # with some errors and warnings fabricated
✗ itara verify — demo/wiring-informed-with-error.yaml

  8 nodes, 10 connections

  ERROR  node 'fulfilmentNode' is declared but not referenced in any connection
  WARN   connection 'orderNode' → 'paymentNode': a timeout is declared but neither the transport nor the failure semantics implementation is configured to enforce it — the timeout value will be passed to the transport but nothing will act on it

  1 error, 1 warning
```

The topology is visible and validated before a single container starts.
See [SPEC §11](../spec/SPEC.md#11-tooling) for the tooling specification.

---

## Running

Start the observability stack once and leave it running:

```bash
docker compose -f demo/docker-compose-otel.yml up -d
```

Wait until Kibana is ready — about 60 seconds on first run.
Check: http://localhost:5601

Then start the Kafka stack for the events and leave it running:

```bash
docker compose -f demo/docker-compose-kafka.yml up -d
```

Then start whichever topology you want to run:

```bash
# Monolith
docker compose -f demo/docker-compose-monolith.yml up

# Microservices
docker compose -f demo/docker-compose-microservices.yml up

# Informed
docker compose -f demo/docker-compose-informed.yml up

# Microservices — Flaky (failure semantics showcase)
docker compose -f demo/docker-compose-microservices-flaky.yml up
```

To adjust the failure rate for the flaky scenario:

```bash
ITARA_FLAKY_FAIL_RATE=0.2 docker compose -f demo/docker-compose-microservices-flaky.yml up
```

The stack is ready when you see `INFO: [Itara] agent ready` in the Java
component logs and `[Itara/HTTP] Server listening on ...` in the Rust payment logs.
Send requests only after both appear.

To switch topologies, stop the current one and start another. The
observability stack keeps running — traces from all topologies accumulate
in Kibana for comparison.

---

## Sending requests

Add stock to the inventory in the microservices topologies:

```bash
curl -X POST http://localhost:8082/itara/inventory/addItem \
     -H "Content-Type: application/json" \
     -d '["WIDGET-A", "Flux Capacitor", 100]'
```

And in the monolith and informed topologies:

```bash
curl -X POST http://localhost:8081/itara/inventory/addItem \
     -H "Content-Type: application/json" \
     -d '["WIDGET-A", "Flux Capacitor", 100]'
```

**Note**: `addItem` uses port 8082 in the microservices topology and 8081 in the monolith and informed topologies. Use the same port for placeOrder as the topology you are running.

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
| `CALL_SENT` | Caller | At the business/topology boundary, before serialization |
| `CALL_RECEIVED` | Callee | At the business/topology boundary, after deserialization |
| `RETURN_SENT` | Callee | At the business/topology boundary, before serialization of result |
| `RETURN_RECEIVED` | Caller | At the business/topology boundary, after deserialization of result |

This produces two spans per call in the trace:

- **Outer span** (CALL_SENT → RETURN_RECEIVED) — the full round trip from
  the caller's perspective, including all transport cost
- **Inner span** (CALL_RECEIVED → RETURN_SENT) — pure component execution
  time, independent of serialization format or transport

The gap between the outer and inner span is the transport overhead:
serialization, network, deserialization. For direct calls the gap is
nearly zero. For remote calls it is directly measurable. This decomposition
happens without any instrumentation in the component code.
 
The same four-event model applies to event-driven calls. The producer side
fires `CALL_SENT` and `RETURN_RECEIVED` at the business/topology boundary when
publishing; the consumer side fires `CALL_RECEIVED` and `RETURN_SENT` at the
same boundary when handling. The OTel observer used in this demo represents
producer and consumer as two separate traces with no parent-child
relationship, correlated by `itaraTraceId` — this is an OTel modelling choice,
not an Itara guarantee. Itara leaves the trace shape up to the observer
implementation; the only thing it guarantees is the correlation ID. In the
traces overview, event handlers appear in the list the same way method calls
do — named `<events-artifact>/<event-name>.<handler-method>`, with the
consuming component as the originating service. Event consumption is
queryable and measurable in the same place as everything else — no separate
dashboard, no special tooling for async flows.

### In the monolith trace

- Direct calls between Java components show two spans with a near-zero gap —
  the outer and inner span are almost identical in duration
- The payment call shows a visible gap — serialization and network cost to
  the Rust process
- `order-events/order-reserved.onOrderReserved` and
  `fulfilment-events/order-fulfilled.onOrderFulfilled` appear as spans within
  the same trace — the events are emitted from within the business flow,
  observable alongside the synchronous calls
- Two services visible in the trace legend: the Java monolith and payment

![Monolith topology trace](../docs/images/trace-monolith.png)

### In the microservices trace

- Every call shows a visible gap between outer and inner span
- Component execution times are unchanged — only the transport costs differ
- The same two events appear as spans within the producer's trace, regardless
  of how distributed the rest of the topology is
- Four services visible in the trace legend: order, inventory, payment,
  fulfilment

![Microservices topology trace](../docs/images/trace-microservices.png)

### In the informed trace

- Inventory calls look like the monolith — near-zero gap, direct
- Everything else looks like microservices — transport overhead visible
- The contrast between the two patterns is visible in the same trace
- Order and inventory share a service name in the trace legend
  (`orderAndInventory`), reflecting their colocation

![Informed topology trace](../docs/images/trace-informed.png)

### In the microservices-flaky trace

Idempotent calls show sibling `attempt` spans when they fail and retry —
visible here on `inventory.releaseReservation`, where the first attempt fails
and a second attempt succeeds. Non-idempotent calls show exactly one attempt:
failure surfaces immediately, no retry. Both cases are visible in the same
trace, nothing changed in the business code.
 
![Microservices-flaky topology trace](../docs/images/trace-microservices-flaky.png)
 
### In the traces overview
 
Event handlers appear in the list the same way method calls do — named
`<events-artifact>/<event-name>.<handler-method>`, with the consuming
component (`notification`) as the originating service. Event consumption is
queryable and measurable in the same place as everything else — no separate
dashboard, no special tooling for async flows.
 
![Traces overview](../docs/images/trace-overview.png)
