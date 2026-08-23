# Itara Glossary

This glossary defines the vocabulary of the Itara project. It is the
reference for contributors, adopters, and anyone explaining Itara to others.
Clear, shared vocabulary matters: if people can talk about the project
fluently, the ideas travel. If the words are missing or inconsistent, the
point gets lost.

The [specification](spec/SPEC.md) references this glossary as its
foundation. Where the spec narrows or extends a definition for formal
precision, the spec takes precedence within that document. Everything here
applies everywhere else.

---

## Component

**A unit of business logic with a defined contract and a concrete
implementation. It knows nothing about how it is called.**

A component consists of three things: a contract (an interface declaring what
it does), an implementation (a class or struct satisfying that contract), and
an activator (a factory method that wires its dependencies at startup). The
component code itself has no knowledge of transport, topology, or deployment.
It does not know whether it is being called locally or remotely, by one caller
or many, over HTTP or Kafka or a direct in-process call. That knowledge lives
in the wiring configuration.

Components are the fixed units of the system. They do not split or merge —
only their placement in a topology changes.

---

## Contract

**The interface a component exposes to its callers. It says what the component
does, not how.**

A contract declares operations — inputs, outputs, and errors. It contains no
reference to transport mechanism, serialization format, or deployment
infrastructure. Callers depend only on the contract. The implementation behind
it is invisible to them, and so is the topology.

---

## Message Format

**A structural encoding scheme whose code generation tooling produces a
contract's types, declared once for the whole contract.**

Some contracts define their parameter and return types by hand, in the
implementation language, as ordinary classes or structs. Others declare a
message format — such as Protocol Buffers — and generate those types from a
schema instead. Either way, the contract is still just an interface: it says
what a component does, not how it is transmitted. A message format
constrains the shape of the data; it says nothing about serialization,
transport, or topology.

A contract commits to a message format entirely or not at all — an API
artifact does not mix hand-written and generated types across its methods.
Given an API artifact, one fact answers whether its types are plain or
generated, for every method it declares.

Message format is independent of serializer choice. A serializer converts a
contract's types to and from bytes; a message format determines what those
types are. The two vary independently — which serializers can handle a
given message format is a separate, per-serializer question.

---

## Topology

**The structure of a system: which components exist, how they connect, and
where they run.**

Topology is the concern Itara makes explicit. In a traditional distributed
system, topology is implicit — encoded across HTTP clients, retry policies,
service discovery calls, and timeout configurations scattered through the
codebase. With Itara, topology is declared in the wiring configuration, the
single source of truth for the entire system structure.

Topology is not a deployment detail. It is an architectural artifact —
declared, validated before deployment, and changeable without touching
business code.

---

## The Three Layers

**Business logic, contracts, and topology are three distinct concerns. Itara
keeps them separate.**

Every Itara system has three layers:

- **Business layer** — the implementation: what the system actually does.
  Written by developers. Has no knowledge of transport, topology, or
  infrastructure.
- **Contract layer** — the interfaces: what each component exposes to its
  callers. Defines operations, inputs, outputs, and errors. Contains no
  transport or topology knowledge.
- **Topology layer** — the wiring: which nodes exist, how they connect, what
  transport they use, how failures are handled. Lives entirely in the wiring
  configuration and the proxies and listeners the wiring agent constructs from
  it.

The separation is the point. Business logic does not leak into topology.
Topology does not leak into business logic. Changing one does not require
touching the other.

---

## Wiring Configuration

**The single file that describes the complete topology of a system.**

The wiring configuration declares which nodes exist, which components they
run, and how they connect. It is the authoritative source of topology
information. The agent reads it at startup. The CLI validates it before
deployment. Nothing about how components communicate exists anywhere else.

Changing the wiring configuration changes the topology. No code changes.

---

## Node

**A named deployment position in the topology. It says where a component
runs.**

A node has an identifier and references a component. Multiple nodes may
reference the same component — they are independent positions in the topology
graph, not copies of each other. How many instances of a node run at any time
is the orchestrator's concern, not Itara's.

---

## Connection

**A declared relationship between two nodes, specifying that one calls the
other and how.**

A connection is directed: it has a caller node and a callee node. It declares
the transport type (direct, HTTP, Kafka, or any other supported transport) and
any additional configuration the transport requires. All failure semantics —
retry behaviour, timeouts, circuit breaking — are connection-level
configuration, not component-level code.

---

## Colocation

**Placing two or more components in the same process so that calls between
them are direct, in-memory, and zero-overhead.**

When components are colocated, the agent resolves a direct proxy at startup.
At call time, the proxy fires the four observability events and calls the
implementation directly — no serialization, no network hop, no transport
overhead of any kind. The component code is identical regardless of whether
the call is colocated or distributed.

Colocation is declared in the wiring configuration by using the `direct`
transport type on a connection. It is a topology decision, not a code
decision.

---

## Distribution

**Placing components in separate processes so that calls between them travel
over a transport.**

When components are distributed, calls cross a process boundary. The transport
— HTTP, Kafka, or any other supported mechanism — carries the call. The
component code does not change. The transport cost becomes visible in the
traces as the gap between the caller's and callee's observability spans.

Distribution is the default assumption of traditional microservice
architectures. Itara makes it an explicit, reversible, config-level decision
rather than an architectural commitment baked into the code.

---

## Deployment Group

**The set of nodes that run in the same process because they are connected by
direct calls.**

Deployment groups are derived automatically by the wiring agent and the CLI
from the connection graph. A deployment group is not declared — it is a
consequence of topology decisions. Nodes connected by direct calls must be
colocated; the deployment group is the set of all nodes that must be together
as a result.

A single component may be referenced by multiple nodes — in different
deployment groups, with different topology roles. The node is the deployment
identity; the component is the business logic behind it. Deployment groups are
about nodes, not components.

`itara inspect` shows the derived deployment groups for any wiring
configuration. They are the practical answer to the question: what goes
together, and what runs separately?

---

## Wiring Agent

**The part of Itara that reads the wiring configuration, wires all components
together before the application starts, and then steps aside.**

The wiring agent runs before application code. It reads the wiring
configuration, loads transport and serializer plugins, constructs proxies and
listeners for every declared connection, and registers everything in the
component registry. By the time the first application thread runs, the
topology is fully wired and all decisions are made. The wiring agent is not
present in the call path at runtime — it does not intercept calls, route
dynamically, or make decisions on the fly. The proxies it created do the
work; the wiring agent is done.

In Java, the wiring agent is a JVM premain agent. In Rust, it is an
`itara_init()` library call. The mechanism is language-specific; the
behaviour is defined by the specification.

---

## Proxy

**The object a caller holds instead of the real implementation. It is
transparent to the caller.**

A proxy satisfies a component contract. The caller cannot tell the difference
between a proxy and the real implementation — the interface is identical. The
proxy is what makes topology transparent to component code: it handles
serialization, transport, context propagation, and observability events
without the caller knowing any of it is happening.

For direct connections, the proxy fires the four observability events and
calls the implementation directly. No serialization, no network. The proxy is
still present — it is what makes observability structural and non-optional.

---

## Transport

**A pluggable mechanism for carrying calls between distributed components.**

A transport provides two things: an outbound proxy for the caller side, and
an inbound listener for the callee side. The component code never sees the
transport. Transports are declared in the wiring configuration and loaded by
the agent at startup. Built-in transports include HTTP and Kafka. Additional
transports can be provided as plugin artifacts.

---

## Observability Events

**Four events that fire precisely at the boundary between the business layer
and the topology layer, on every call, regardless of transport.**

Every call between components — direct or remote, synchronous or asynchronous
— emits four events: `CALL_SENT` and `RETURN_RECEIVED` on the caller side,
`CALL_RECEIVED` and `RETURN_SENT` on the callee side.

The placement is deliberate. `CALL_SENT` fires when the business layer hands
a call to the topology layer. `CALL_RECEIVED` fires when the topology layer
hands it to the callee's business layer. `RETURN_SENT` and `RETURN_RECEIVED`
are the mirror on the way back. Everything that happens between those events
— serialization, network transit, deserialization — is topology overhead,
directly measurable as the gap between the caller span and the callee span.

These events are structural and non-optional: they are fired by the proxy and
dispatcher at the layer boundary, not by the transport. A transport that
skips them cannot exist — it never touches the event model.

---

## Failure Semantics

**How a connection behaves when things go wrong — declared in configuration,
invisible to component code.**

Failure semantics are connection-level configuration. A single pluggable
implementation owns the complete failure strategy for a connection — how it
handles timeouts, retries, circuit breaking, or any other
failure-related concern. The component code is unaware of any of it; it
simply makes calls. Idempotency is declared in the API artifact metadata and
read by the proxy at startup, so methods that cannot be safely repeated are
protected without any explicit handling in the business logic.

---
 
## Identity
 
**The claimed or verified origin of a call — a property of the node, not
the deployment unit it happens to run in.**
 
Colocated nodes do not automatically share an identity just because they
share a process (see Colocation); different identity rules can still
apply to each.
 
---
 
## Authentication
 
**Verifying that a caller's claimed identity (see Identity) is genuine, at
the topology layer.**
 
Authentication is connection-level and pluggable: an implementation
receives whatever identity signal is available for a connection and either
produces a verified identity or rejects the call outright. Used without
qualification, this term refers to authenticating a calling node, not an
end user or account.
 
---
 
## Authorization
 
**Deciding whether an authenticated caller is permitted to invoke a
specific operation — a topology-layer concern about which node may call
what.**
 
Authorization is connection-level and pluggable: an implementation reads
the identity authentication produced, together with the operation being
invoked, and decides allow or deny. Used without qualification, this term
refers to node-to-node permission, not end-user permission.

---

## Agent

*See [Wiring Agent](#wiring-agent).*

---

## Placement

**The topology decision of where a component runs and how it connects to
others.**

Placement is what changes when you move a component from one deployment group
to another, or change its transport. The component itself does not change —
only its position in the topology and the communication path to and from it.
Placement decisions are made in the wiring configuration.

## Component Scope
 
**Defines the boundary of what a running component instance can reach,
and carries what's needed to enforce it — established and maintained
exclusively by the agent.**
 
A component's scope determines which other components' proxies are
actually available to it, prepared by the agent from the wiring
configuration. It also carries whatever the agent needs to enforce that
boundary — at minimum, which node in the topology graph is currently in
control. This is not identity in the authentication sense; it's simply
the agent's own record of where in the graph execution currently is.
 
Colocation does not imply shared scope. Two components sharing a process
for a zero-overhead direct connection (see Colocation) remain distinct —
each has its own scope, and neither can assume the other's.