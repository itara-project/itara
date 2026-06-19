# Fan-Out Topology — Design Notes

**Status:** Thinking in progress. Not yet part of the specification.  
**Date:** June 2026

---

## The problem

The event-driven topology introduced in spec §13 models publish-subscribe: a
producer publishes to a channel address, and any number of consumers subscribe
to the same address via consumer groups. The broker handles fan-out — all
consumer groups receive all messages from the same topic.

This model does not cover all real-world event distribution patterns. In some
architectures, fan-out is explicit and address-based:

- **SNS → SQS:** a message published to an SNS topic is delivered to N SQS
  queues, each with its own address. Each consumer reads from its own queue,
  not from a shared topic.
- **ActiveMQ Artemis diverts:** a message arriving on one address is copied
  to N other addresses by a broker-level divert rule.
- **RabbitMQ exchanges:** a fanout exchange delivers to N bound queues, each
  with its own address.

In these patterns, the outbound addresses are distinct. A consumer cannot
subscribe to the source address directly — it must subscribe to its own
dedicated outbound address. This is fundamentally different from pub-sub, where
all consumers share the same address and are differentiated by consumer group.

The current virtual node model cannot represent this. A virtual node has one
address. Connecting multiple consumers to it implies they all read from the
same address, which is the pub-sub assumption. That assumption is false for
the patterns above.

---

## Approach — Redirect nodes

Introduce a **redirect node**: an elementary node type that declares a single
inbound address and a single outbound address. The address translation it
represents happens in the broker — Itara declares it in the wiring config for
visibility but does not manage or enforce it. Redirect nodes compose with
virtual nodes and with each other to express fan-out and more complex
distribution patterns.

```yaml
nodes:
  - id: "orderPaidChannel"
    kind: virtual
    contract: "order-events/order-paid"
    address: "arn:aws:sns:eu-west-1:123456789:order-paid"

  - id: "fulfilmentQueue"
    kind: redirect
    address: "https://sqs.eu-west-1.amazonaws.com/123456789/order-paid-fulfilment"

  - id: "notificationQueue"
    kind: redirect
    address: "https://sqs.eu-west-1.amazonaws.com/123456789/order-paid-notification"

connections:
  - from: "orderServiceNode"
    to: "orderPaidChannel"
    type: sns
    serializer: "json"

  - from: "orderPaidChannel"
    to: "fulfilmentQueue"
    type: sns

  - from: "fulfilmentQueue"
    to: "fulfilmentServiceNode"
    type: sqs
    serializer: "json"

  - from: "notificationQueue"
    to: "notificationServiceNode"
    type: sqs
    serializer: "json"
```

The redirect node has no component implementation, no activator, and no
agent-managed lifecycle. Like the virtual node, it is a topology declaration
only. The broker is responsible for the actual address binding.

---

## Why redirect nodes over a purpose-built fanout node

A dedicated fanout node — a single node type with one inbound address and N
declared outbound addresses — would solve the immediate problem but at the
cost of long-term composability. Every new distribution pattern would require
a new node type. Elementary node types that connect to each other scale better:
the wiring model stays uniform, the tooling traverses the same graph structure
regardless of pattern, and new patterns emerge from composition rather than
requiring spec additions.

The composability advantage is clearest when patterns overlap. Consider a
topology where some consumers subscribe to the SNS topic directly (pub-sub)
while others consume from SQS queues populated by the same SNS topic
(fan-out). A purpose-built fanout node cannot express this without becoming
a hybrid type. Redirect nodes compose with virtual nodes naturally — the
virtual node handles the direct subscribers, redirect nodes handle the
address-translated subscribers, both connect from the same virtual node.

On tooling: the tooling operates on a graph. A uniform set of elementary node
types produces a simpler graph to traverse and validate than a growing
catalogue of purpose-built types, even if individual wiring configs are
slightly more verbose.

On verifiability: both approaches declare infrastructure Itara does not manage.
A redirect node representing an SQS queue is no less verifiable than a fanout
node representing an SNS→SQS binding — in both cases, the broker configuration
is external to Itara. The distinction is not meaningful.

---

## Open questions

**Naming within the node:** does a redirect node need a contract reference?
Without one, tooling cannot validate that producer and consumer agree on the
event type across the redirect boundary. With one, it starts to overlap
semantically with the virtual node. One option: contract reference is optional
on a redirect node, and `itara verify` emits a warning when it is absent on a
redirect node that sits between a typed virtual node and a consumer.

**Chained redirects:** redirect nodes can be chained (virtual → redirect →
redirect → consumer) to model multi-hop broker topologies. Whether this should
be explicitly supported or merely not prohibited is an open question.

**Infrastructure tooling integration:** the declared addresses on redirect
nodes are exactly the information needed to generate broker configuration
(SQS queue definitions, SNS subscriptions, Artemis divert rules). This is not
an Itara goal, but the data model should not preclude it. Worth keeping in mind
when finalising the redirect node's data model.

**Notification-only connections:** the connection between a virtual node and
a redirect node represents a broker binding that Itara does not manage. Whether
`itara verify` should flag these connections differently from managed
connections is an open question.

---

## What this is not

Redirect nodes do not replace virtual nodes. Virtual nodes remain the
pub-sub declaration. Redirect nodes are needed only when the underlying broker
distributes messages to distinct per-consumer addresses rather than a shared
topic. Many systems will use only virtual nodes.

Redirect nodes do not represent data transformation. The address changes; the
event contract does not. A redirect node that changes the shape of the payload
is out of scope and would require a different concept.
