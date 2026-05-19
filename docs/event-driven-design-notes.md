# Event-Driven Topology — Design Notes

**Status:** Thinking in progress. Not yet part of the specification.  
**Date:** May 2026

This document captures the design thinking for async and event-driven communication patterns in Itara. It will be refined and merged into the specification once the core synchronous model is stable and Show HN has landed.

---

## The problem

The current Itara model covers synchronous point-to-point communication well. A caller invokes a method on a callee — direct or over HTTP. The topology is declared in the wiring config. The code is unaware of the transport.

Event-driven communication introduces two new patterns that don't map cleanly to this model:

1. **Point-to-point async** — one producer, one consumer, communicated via a queue. Simpler case.
2. **Publish-subscribe** — one producer, any number of consumers, communicated via a topic. The typical Kafka use case. Harder case.

The key challenge in publish-subscribe: a producer does not know who its consumers are. That's the point — it's decoupled. But Itara needs to represent this in the wiring config so the topology is visible, auditable, and manageable.

---

## Developer experience requirement

The developer experience must remain identical to synchronous communication. A producer calls a method. The fact that this results in a Kafka publish rather than an HTTP call is a topology decision expressed in the wiring config — not a code decision.

```java
// Producer code — identical whether this goes to Kafka, HTTP, or direct
orderService.orderCreated(order);
```

Consumers implement a contract and receive calls the same way:

```java
// Consumer code — identical whether called from Kafka or direct
public void orderCreated(Order order) { ... }
```

The producer does not know about consumers. The consumer does not know about the producer. Both write plain method calls.

---

## Virtual nodes

To represent publish-subscribe topology in the wiring config, Itara introduces **virtual nodes** — topology declarations that have no component implementation. A virtual node represents a communication channel, not a piece of business logic.

```yaml
nodes:
  - id: "orderCreatedChannel"
    kind: "virtual"            # no component, no activator, no implementation

  - id: "orderServiceNode"
    component: "order-service"

  - id: "inventoryServiceNode"
    component: "inventory-service"

  - id: "notificationServiceNode"
    component: "notification-service"

connections:
  - from: "orderServiceNode"
    to: "orderCreatedChannel"
    type: kafka

  - from: "orderCreatedChannel"
    to: "inventoryServiceNode"
    type: kafka

  - from: "orderCreatedChannel"
    to: "notificationServiceNode"
    type: kafka
```

The virtual node is the hub. Publishers connect to it. Subscribers connect from it. The wiring config shows the complete picture — who produces, who consumes, via what channel. This is the visibility Itara provides that raw Kafka usage does not.

---

## Contract grouping — schemas

A virtual node references a contract. The contract defines the shape of the event — the method signature the producer calls and the consumer implements.

Multiple related event contracts can be grouped into a single artifact (jar, crate) for convenience. This avoids creating one artifact per event type, which would be impractical at scale.

The grouping artifact is called a **schema** — not a component, because it has no implementation. The distinction is explicit and important.

```
order-events-schema.jar / order-events-schema.so
  - OrderCreatedEvent  (interface/trait with one method)
  - OrderCancelledEvent
  - OrderShippedEvent
```

Each virtual node references a specific contract within the schema:

```yaml
nodes:
  - id: "orderCreatedChannel"
    kind: "virtual"
    schema: "order-events"
    contract: "order-created"
```

The schema is the artifact grouping — one jar/so, logical cohesion. The virtual node specifies the exact contract within it. The wiring config controls the granularity of each channel.

---

## Topic address

The Kafka topic address (or equivalent for other brokers) lives in the virtual node's `.itara` metadata file — not in the wiring config and not in the contract.

This is a deliberate boundary decision:
- **Wiring config** — topology: which nodes connect to which channels
- **Metadata file** — deployment detail: where the channel actually lives (topic name, broker address)
- **Contract** — semantics: what the event carries

```toml
# order-created-channel.itara
[artifact]
kind = "virtual-node"
id = "order-created"
schema = "order-events"
contract = "order-created"

[channel]
address = "org.orders.created"   # Kafka topic, MQ queue name, SQS ARN, etc.
```

The transport interprets the address according to its own mechanism. The address format is transport-specific. Itara does not prescribe it.

---

## Observability

The four-event model applies to async communication as well:

- **CALL_SENT** — fired when the producer calls the method (before the message is published)
- **CALL_RECEIVED** — fired when the consumer receives and processes the message
- **RETURN_SENT** — for fire-and-forget: fired when processing completes (no return value)
- **RETURN_RECEIVED** — for fire-and-forget: fired on the producer side when the broker acknowledges the message

The producer span (CALL_SENT → RETURN_RECEIVED) closes on broker acknowledgement — not on consumer processing. This is honest: the producer knows the message was durably accepted. What happens after that is the consumer's concern and shows up in the consumer's span.

Retry behaviour is visible by design. If a retry occurs, both the original attempt and the retry produce separate spans. If the original call triggered downstream side effects, those appear twice in the trace — making non-idempotent retry storms directly observable.

---

## Point-to-point async (simpler case)

For simple queue-based async where there is exactly one consumer, no virtual node is needed. The connection type declares the async transport:

```yaml
connections:
  - from: "orderServiceNode"
    to: "processingServiceNode"
    type: kafka               # or: sqs, jms, rabbitmq
    serializer: "json"
```

The Kafka transport handles the queue-based dispatch. The wiring config is identical in structure to the synchronous case — only the transport type changes. The code changes nothing.

---

## Open questions

**Naming** — "virtual node", "schema", "channel" are working terms. The final names should be decided when the feature is implemented. They should be distinct from "component" and "node" to make the different nature explicit.

**Schema vs component in the API artifact** — the schema artifact (jar/so) currently follows the same structure as a component API artifact, minus the activator. This may need a distinct `kind` in the `.itara` metadata file: `kind = "schema"` rather than `kind = "api"`. To be decided.

**Multi-method schemas** — a schema can contain multiple event contracts. Each maps to one virtual node. Whether a single schema artifact can contain contracts with different arities (methods with different parameter lists) is not yet decided, but there is no obvious reason to restrict it.

**UI representation** — virtual nodes in the topology graph can be rendered as hubs (explicit node in the graph) or collapsed into direct edges between producer and consumer nodes (with the channel implied). This should be a toggle. Both views are useful.

**Async request-reply** — fire-and-forget is the primary case. Request-reply over async transports (e.g. Kafka with reply topics and correlation IDs) is more complex and is deferred. The four-event model accommodates it — the RETURN_RECEIVED event fires when the reply arrives — but the transport implementation is significantly more involved.

---

## What this is not

Virtual nodes are not components. They have no implementation, no activator, no registry entry. They are topology declarations only.

The schema is not a framework dependency. It contains plain interfaces or traits — the same as any other Itara contract. The producer and consumer both depend on the schema artifact at compile time, just as a gateway depends on a calculator-api artifact.

Itara does not manage the broker. Kafka, RabbitMQ, SQS — these run independently. Itara declares connections to them in the wiring config and provides a transport implementation that knows how to talk to them. The broker is infrastructure. The topology is Itara's concern.
