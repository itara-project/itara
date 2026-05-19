# **THE ITARA MANIFESTO**  
### *A Declaration of Principles*

Software was meant to be soft.  
It was meant to adapt, evolve, reshape itself as understanding grows.  
Instead, we have built a world where topology is frozen into code, where boundaries calcify, where architecture becomes a prison.

Itara exists to end that world.

This manifesto defines the principles that will never change.  
These are the hills we stand on.  
These are the lines we do not cross.

---

## **1. Topology Is Declarative, Never Coded**
No component shall ever encode:

 - where another component lives
 - how it is reached
 - what protocol is used
 - whether the call is local or remote
 - whether the call is synchronous or asynchronous

Topology belongs to configuration.  
Code expresses intent — nothing more.

---

## **2. Semantics Are Explicit. Mechanics Are Abstracted.**
A function signature defines:
 - inputs
 - outputs
 - errors

It does **not** define:
 - HTTP
 - Kafka
 - queues
 - threads
 - futures
 - callbacks
 - correlation IDs

These are mechanical concerns.  
They belong to the runtime and the topology layer, not the code.

---

## **3. Colocation Must Be Zero‑Overhead**
If two components share the same process and type system, the runtime must call them as if they were functions in the same file.

 - No serialization
 - No networking
 - No proxies — enforced at startup, never at call time
 - No transport overhead — only the runtime's structural operations (observability) are added, and only because they are inseparable from the platform's guarantees

For components colocated on the same host but in separate runtimes — such as components written in different languages — the developer declares the local communication mechanism in the wiring configuration. The runtime follows that declaration exactly. No network leaves the host. No decision is made on the developer's behalf.

Colocation is not an optimization.  
It is the baseline.  
The developer controls how it is achieved.

---

## **4. Remote Calls Must Be Transparent but Observable**
The code must not know whether a call is remote.  
But the system must.

Observability is built in:

 - tracing
 - metrics
 - logs
 - correlation
 - latency visibility

Transparency for developers.  
Visibility for operators.

---

## **5. Components Are Defined by Contracts, Not Frameworks**
A component is:

 - an interface
 - an implementation
 - an activator

It is **not**:

 - a Spring bean
 - a microservice
 - a module
 - a deployment unit

Frameworks live *inside* components.  
Components live *inside* Itara.

---

## **6. Refactoring Must Be Cheap**
Splitting a component must be a configuration change.  
Merging components must be a configuration change.  
Changing protocols must be a configuration change.  
Moving from local to remote must be a configuration change.

Topology must never force a rewrite.

---

## **7. Language Neutrality Is a Core Value**
Itara is not a Java project.  
Java is merely the first reference runtime.

Itara must support:

 - Java
 - Rust
 - C++
 - Go
 - Python
 - Any language capable of dynamic linking or RPC

The universal layer is sacred.  
It must not drift.  
It must not fragment.

---

## **8. The Runtime Is Pluggable**
Transports are pluggable.  
Activators are pluggable.  
Serialization is pluggable.  
Observability sinks are pluggable.  
Deployment strategies are pluggable.

Itara defines the *shape* of the world, not the implementation of every detail.

---

## **9. Itara Does Not Replace Architecture. It Concentrates It.**

Domain-driven design, saga patterns, event sourcing, CQRS, hexagonal architecture — these remain as relevant as ever. Itara does not make them obsolete.
What Itara changes is where they live.

Today, architectural patterns are distributed across dozens of services, encoded in HTTP clients, message producers, retry policies, and timeout configurations scattered throughout the codebase. Understanding the architecture means reading everything.

With Itara, the topology — the communication structure that gives patterns their shape — is concentrated in one place. The saga is visible in the graph. The bounded contexts are visible as component clusters. The event flow is visible as typed edges.

Architecture patterns are still designed by architects. They are now expressed, enforced, and changed in one place rather than many.

This scales better organizationally for the same reason orchestration scales better than choreography: concentrated intent is easier to understand, audit, and evolve than intent distributed across every participant.

---

## **10. Software Must Become Soft Again**
This is the purpose of Itara.

To make architecture fluid.  
To make boundaries reversible.  
To make refactoring cheap.  
To make topology declarative.  
To make systems evolvable.  
To free developers from plumbing.  
To let intent shine without mechanics leaking through.

This is the war we fight.  
This is the world we build.