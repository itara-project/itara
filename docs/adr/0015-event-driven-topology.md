# ADR 0015 — Event-Driven Topology: Virtual Nodes and Events Artifacts

**Status:** Accepted  
**Date:** June 2026

---

## Context

Itara v0.1 covers synchronous point-to-point communication. The wiring model
is a directed graph of component nodes connected by transport-typed edges. The
v0.2 milestone introduces event-driven communication — patterns where a
producer emits an event without knowledge of its consumers, and one or more
consumers receive it without knowledge of the producer.

This ADR records the design decisions made when extending the wiring model and
component model to support event-driven topology.

---

## Decision

### Virtual nodes and events artifacts

Event channels are represented as **virtual nodes** in the wiring data model —
a distinct node type with no component implementation, no activator, and no
agent-managed lifecycle. They are topology declarations: named points through
which producers and consumers are connected without direct knowledge of each
other.

The alternative of encoding the channel implicitly — as a property on a
connection edge, or as a specialised connection type with no intermediate node
— was rejected. A channel has identity: an address, a contract reference, and
potentially transport-agnostic metadata. That identity belongs on a named
element. Encoding it across multiple edges would also make publish-subscribe topology
(one producer, many consumers) invisible in the graph; a virtual node makes it
explicit and inspectable.

**Transport is an edge property, not a node property.** The transport type and
all transport-specific connection settings (consumer group ID, acknowledgement
mode, dead letter queue configuration, etc.) are declared on connections. The
virtual node is transport-agnostic. This keeps connection-level operational
concerns at the edge where they belong and allows different connections to the
same virtual node to carry different transport settings independently. The
channel address, by contrast, is declared on the virtual node — it is a
property of the channel itself, declared once, shared by all connections. This
distinction determines what lives on the node versus the edge.

Event contracts are grouped in **events artifacts** — a new artifact kind
(`kind = "events"`) distinct from API artifacts (`kind = "api"`). An events
artifact has no activator and no agent-managed component lifecycle; it is a
compile-time dependency only. Reusing `kind = "api"` would require the kind
to carry optional semantics, weakening it as a discriminator for tooling.
The `.itara` metadata file for an events artifact enumerates the contracts it
contains; tooling uses this enumeration to validate virtual node contract
references without loading any code.

---

## Consequences

`itara inspect` renders virtual nodes as a distinct element type in the
topology graph and excludes them from deployment group computation. `itara
verify` validates contract references against the events artifact's `.itara`
enumeration and checks connection cardinality on virtual nodes. Virtual nodes
are included in agent config slices when adjacent to a node the agent is
responsible for — they carry the channel address and contract reference the
agent needs to wire producer and consumer proxies.

---

## References

- Spec §4.3 — Node Declarations
- Spec §13 — Event-Driven Topology
- ADR 0001 — Topology as Configuration
- ADR 0003 — Four-Event Observability Model
- ADR 0009 — Node and Component Identity Separation
- ADR 0014 — Itara-Native Correlation IDs
