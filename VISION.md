# Itara — Architectural Vision

This document describes where Itara is going, not just where it is.
It is a living document. Last updated: April 2026.

---

## The north star

A production system should be able to change its topology — how its
components communicate and where they run — without touching code,
without migration ceremony, and without downtime.

This is physically possible. What has been missing is the architectural
model that makes it real.

---

## The core reframe

The topology of a system — which components exist, how they communicate,
where they are deployed — is not a fixed artifact of a deployment pipeline.
It is a continuously adjustable variable, managed separately from the
component logic itself.

This is the Java Stream API for distributed systems architecture. It does not
provide anything that cannot be done without it. It allows engineers to do it
declaratively instead of imperatively, shifting what they have to think about.
Engineers stop thinking about topology mechanics and start thinking about the
business problem.

---

## A hard guarantee: zero overhead on collocated calls

When two components share the same process and type system — for example, two JVM components wired as direct — the call between them is a plain method invocation. There is no proxy overhead, no serialization, no indirection at call time. The agent resolves the wiring at startup — by the time the application runs, the call is identical to any normal Java method call.

For components colocated on the same host but running in separate runtimes — such as components written in different languages — the developer declares the local IPC mechanism in the wiring configuration (Unix domain socket, shared memory, named pipe, or any other supported local transport). The runtime uses exactly the mechanism declared and nothing else. No network leaves the host. No transport decision is made autonomously by the runtime. If a developer does not trust a particular mechanism, or discovers a problem with an implementation, they change the configuration. The runtime follows.

This is a design commitment, not an aspiration. Itara will never introduce transport overhead for collocated components — no serialization, no network hop, no indirection at call time. The only operations the runtime adds to a direct call are those that are structural properties of the platform itself, specifically the observability events that make every interaction traceable and auditable. These are not optional and not removable — they are what makes the topology layer trustworthy. Everything else is zero. If a future version cannot uphold this guarantee for a given connection type, that connection type will not be classified as direct.

The corollary: collocating two components in Itara costs nothing compared to writing them as a single service. The only cost is startup time, paid once.

---

## Two levels of code

Every Itara application has two levels:

Implementation level — component logic, written in a normal language.
No knowledge of transport or topology.

Meta level — a structured description of how components relate, what their
interfaces are, what invariants they maintain, and what the controller is
allowed to change. This starts as annotations and config files. It will
eventually be a language-neutral descriptor — the same idea as gRPC proto
files — generate Java abstract classes, C headers, Rust traits, Go interfaces
from a single source of truth.

---

## The wiring config as a graph

The wiring config is a directed graph. Nodes are components. Edges are typed
connections. The same component can be reached by different callers via
different connection types. This is the data structure the controller will
reason about, the visualisation tool will render, and the engineer will
eventually plan graphically.

---

## The full system architecture

**The agent (exists)** — JVM premain. Reads the wiring config, instruments
callsites, generates proxies, synthesizes HTTP servers, hands off to the app.

**The orchestrator (no new tool required)** — Itara is designed to be orchestrator-agnostic. Kubernetes, Nomad, plain systemd, or any other process management tool can serve as the orchestrator. The framework's job is to make components orchestrator-friendly — each JVM is a self-contained unit that reads a wiring config at startup and exposes health endpoints automatically. The orchestrator's job is what it already does: start, stop, and monitor processes.
A Kubernetes operator is a natural implementation of the controller layer — it watches the topology graph, observes component metrics via the agent's built-in instrumentation, and updates wiring configs and restarts pods when topology changes are warranted. This is the recommended path for teams already running Kubernetes.
A custom orchestrator may unlock capabilities that general-purpose tools cannot — finer-grained scheduling, topology-aware placement, tighter integration with the controller's decision model. That is a future option, not a requirement. Wide adoption comes first, and wide adoption means meeting engineers where they already are.

**The controller (planned)** — the intelligent layer above the orchestrator.
Observes metrics, builds a model of system behavior, and recommends or
executes topology changes.

Trust ladder:
1. Self-service: engineer decides, tool makes it cheap and reversible
2. Recommendations: controller suggests with reasoning, engineer approves
3. Prepared actions: one button, fully described, reversible
4. Full automation: opt-in, scoped, with kill switches

The controller's decision-making approach is an open research question.
Control theory — specifically adaptive control applied to variable-dimension
state spaces — is a promising direction given existing academic work on
queuing network models and feedback control of computing systems. It is not
the only direction. The requirement is that the controller's reasoning must
be grounded in a formal model, not heuristics, so that its decisions can be
explained, audited, and trusted.

---

## Built-in observability

Observability is not an afterthought in Itara — it is a structural property
of the architecture. The agent intercepts every call between components. That
interception point is the natural place to collect latency, throughput, error
rates, and payload characteristics without any instrumentation burden on the
developer.

The long-term goal: every connection in the topology graph has live metrics
attached to it automatically. Engineers see not just the structure of their
system but its behavior — in real time, without writing a single line of
monitoring code.

This observability is also what makes the controller trustworthy. Before
full automation is ever enabled, the engineer can watch the controller's
reasoning against real data and verify that it is correct. Trust is built
on transparency, not on promises.

A system that cannot be observed cannot be safely automated. Itara treats
these as inseparable requirements.

---

## The mathematical foundation

Individual components can be modeled as queuing systems.
For a component with N worker threads and service time S:

  Q(t) = integral(I_in) - integral(I_out)
  D(t) = S * max(1, Q(t)/N)
  I_out(t) = I_in(t - D(t))

This is a delay differential equation. Linearization enables standard linear
control techniques. Composition follows Network Calculus — the same
cumulative formulation, extended to arbitrary topologies with worst-case
delay bounds. The goal: predict the effect of a topology change before
making it, the way an engineer analyzes a circuit before building it.

This mathematical work is a research direction, not a committed implementation
plan. Academic collaboration is the realistic path for taking it from theory
to a runtime the controller can use.

---

## Open questions

- The description language: minimum declaration for correct controller decisions
- Scale-invariant reasoning: making correct decisions at any component granularity
- The goal language: expressing heterogeneous, conflicting optimization targets
- Controller decision model: formal approach that can be explained and audited
- Compositional mathematical models: composing component models at runtime

---

## Language agnosticism

Java-first. Long-term target is language neutrality. The interface jar will
be replaced by a language-neutral descriptor. Components in different languages
participate in the same topology graph. Native components (.so / .dll) are a
natural extension — a shared library call after load is essentially free, and
the collocated zero-overhead guarantee applies equally in native contexts.

---

## Why now

ByteBuddy makes JVM bytecode manipulation accessible. Kubernetes has trained
engineers to think in external controllers and declarative topology. The
microservices explosion has made the operational pain acute and universal.
Academic work on queuing models and feedback control of computing systems
has existed for two decades without a practical application to pull it into
production use.

Itara is the missing application.

---

## Author and origin

This vision was conceived and first implemented by Gabor Kiss in April 2026.
The proof of concept — topology change between direct and HTTP connections
without code modification — was completed on April 12, 2026.

This repository is the origin of that work.
