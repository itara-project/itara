# Itara — Architectural Vision

This document describes where Itara is going, not just where it is.
It is a living document. Last updated: May 2026.

---

## The north star

A production system should be able to change its topology — how its components communicate and where they run — without touching code, without migration ceremony, and without downtime.

This is physically possible. What has been missing is the architectural model that makes it real.

---

## The core reframe

Today, the topology of a distributed system — which components exist, how they communicate, where they are deployed — is encoded in the system itself. It lives in HTTP clients, message producer configurations, service discovery calls, timeout settings, and retry policies scattered across every service. Changing the topology means changing code. Splitting a service means rewriting clients. Moving from HTTP to a message queue means touching both sides. Architecture decisions made at the start of a project calcify into the codebase.

Itara reframes this. Topology is not a property of the code — it is a continuously adjustable variable declared in configuration and applied by the runtime. Component logic expresses what a system does. The wiring config expresses how the parts connect. These are different concerns and they should live in different places.

This is not a new idea — it is the same insight that made the Java Stream API useful, that made Kubernetes operators powerful, that made gRPC's protocol buffers valuable. Separate the declaration of intent from the mechanics of execution. Itara applies that insight to distributed system topology.

Engineers stop reasoning about transport mechanics and start reasoning about the business problem. The topology becomes something you can change, audit, visualise, and eventually automate — because it lives in one place rather than everywhere.

---

## A hard guarantee: zero overhead on collocated calls

When two components share the same process and type system — for example, two JVM components wired as direct, or two Rust components loaded as separate dynamic libraries in the same process — the agent resolves a proxy at startup. At call time, that proxy fires the four observability events and then calls the implementation directly. There is no serialization, no network hop. The only overhead is the observability events themselves — structural properties of the platform that cannot be removed, because they are what makes the topology layer trustworthy.

For components colocated on the same host but running in separate runtimes — such as components written in different languages — the developer declares the local IPC mechanism in the wiring configuration (Unix domain socket, shared memory, named pipe, or any other supported local transport). The runtime uses exactly the mechanism declared and nothing else. No network leaves the host. No transport decision is made autonomously by the runtime.

This is a design commitment, not an aspiration. Itara will never introduce transport overhead for collocated components. The only operations the runtime adds to a direct call are those that are structural properties of the platform — specifically the observability events that make every interaction traceable and auditable. These are not optional and not removable — they are what makes the topology layer trustworthy. Everything else is zero.

The corollary: collocating two components in Itara costs nothing compared to writing them as a single service. The only cost is startup time, paid once.

---

## Two levels of code

Every Itara application has two levels:

**Implementation level** — component logic, written in a normal language. No knowledge of transport or topology.

**Meta level** — a structured description of how components relate, what their interfaces are, what invariants they maintain, and what the controller is allowed to change. This starts as annotations and config files. It will eventually be a language-neutral descriptor — the same idea as gRPC proto files — generating Java abstract classes, C headers, Rust traits, Go interfaces from a single source of truth.

---

## The wiring config as a graph

The wiring config is a directed graph. Nodes are components. Edges are typed connections. The same component can be reached by different callers via different connection types. This is the data structure the controller will reason about, the visualisation tool will render, and the engineer will eventually plan graphically.

---

## The full system architecture

**The agent (exists)** — reads the wiring config, loads SPI implementations, generates proxies and listeners, hands off to the application. Implemented as a JVM premain for Java and as a library (`itara_init()`) for Rust. Other language implementations follow the same pattern.

**The orchestrator (no new tool required)** — Itara is designed to be orchestrator-agnostic. Kubernetes, Nomad, plain systemd, or any other process management tool can serve as the orchestrator. The framework's job is to make components orchestrator-friendly — each process is a self-contained unit that reads a wiring config at startup. The orchestrator's job is what it already does: start, stop, and monitor processes.

**The controller (planned)** — the intelligent layer above the orchestrator. Observes metrics, builds a model of system behavior, and recommends or executes topology changes.

Trust ladder:
1. Self-service: engineer decides, tool makes it cheap and reversible
2. Recommendations: controller suggests with reasoning, engineer approves
3. Prepared actions: one button, fully described, reversible
4. Full automation: opt-in, scoped, with kill switches

---

## Built-in observability

Observability is not an afterthought in Itara — it is a structural property of the architecture. The agent intercepts every call between components. That interception point is the natural place to collect latency, throughput, error rates, and payload characteristics without any instrumentation burden on the developer.

The long-term goal: every connection in the topology graph has live metrics attached to it automatically. Engineers see not just the structure of their system but its behavior — in real time, without writing a single line of monitoring code.

This observability is also what makes the controller trustworthy. Before full automation is ever enabled, the engineer can watch the controller's reasoning against real data and verify that it is correct. Trust is built on transparency, not on promises.

A system that cannot be observed cannot be safely automated. Itara treats these as inseparable requirements.

---

## The mathematical foundation

Individual components can be modeled as queuing systems. For a component with N worker threads and service time S:

```
Q(t) = integral(I_in) - integral(I_out)
D(t) = S * max(1, Q(t)/N)
I_out(t) = I_in(t - D(t))
```

This is a delay differential equation. Linearization enables standard linear control techniques. Composition follows Network Calculus — the same cumulative formulation, extended to arbitrary topologies with worst-case delay bounds. The goal: predict the effect of a topology change before making it, the way an engineer analyzes a circuit before building it.

This mathematical work is a research direction, not a committed implementation plan. Academic collaboration is the realistic path for taking it from theory to a runtime the controller can use.

---

## Open questions

- The description language: minimum declaration for correct controller decisions
- Scale-invariant reasoning: making correct decisions at any component granularity
- The goal language: expressing heterogeneous, conflicting optimization targets
- Controller decision model: formal approach that can be explained and audited
- Compositional mathematical models: composing component models at runtime

---

## Language agnosticism

Itara is not a Java project. Java is the first reference implementation. Rust is the second. More languages follow.

The specification defines the component model, wiring model, transport interface, observability model, and context propagation contract. Any language capable of dynamic linking or RPC can implement the specification. The interface jar and trait definitions will eventually be replaced by a language-neutral descriptor — components in any language participating in the same topology graph, producing the same distributed traces.

The Rust implementation proved language agnosticism is not aspirational. The same wiring config switches a Java gateway from calling a Java calculator to calling a Rust calculator. The distributed trace shows both spans. The code changes nothing.

---

## Why now

The microservices explosion has made the operational pain acute and universal. Kubernetes has trained engineers to think in external controllers and declarative topology. ByteBuddy makes JVM bytecode manipulation accessible. Rust makes native agent libraries practical without GC overhead. Academic work on queuing models and feedback control of computing systems has existed for two decades without a practical application to pull it into production use.

Itara is the missing application.

---

## Author and origin

This vision was conceived and first implemented by Gabor Kiss in April 2026.
The Java proof of concept — topology change between direct and HTTP connections without code modification — was completed on April 12, 2026.
The Rust proof of concept — the same topology switch in a second language, proving language agnosticism — was completed in May 2026.

This repository is the origin of that work.
