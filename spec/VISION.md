# Itara — Architectural Vision
 
This document describes where Itara is going, not just where it is.
It is a living document. Last updated: May 2026.
 
---
 
## The north star
 
A production system should be able to change its topology — how its components communicate and where they run — without touching code, without migration ceremony, and without downtime. And it should be able to do so safely: with the confidence that the new topology is correct before it is applied, that every connection is validated, and that nothing can be misconfigured silently.
 
This is physically possible. What has been missing is the architectural model that makes it real.
 
---
 
## The core reframe
 
Today, the topology of a distributed system — which components exist, how they communicate, where they are deployed — is encoded in the system itself. It lives in HTTP clients, message producer configurations, service discovery calls, timeout settings, and retry policies scattered across every service. Changing the topology means changing code. Splitting a service means rewriting clients. Moving from HTTP to a message queue means touching both sides. Architecture decisions made at the start of a project calcify into the codebase.
 
Worse, topology is invisible. Nobody has the full picture. Changes are made by reading code, making assumptions, and hoping the assumptions hold. The system cannot tell you what it is. It cannot tell you what will break if you change it. It cannot tell you if the change you just made is correct.
 
Itara reframes this. Topology is not a property of the code — it is a continuously adjustable variable declared in configuration and applied by the wiring agent. Component logic expresses what a system does. The wiring config expresses how the parts connect. These are different concerns and they should live in different places.
 
---
 
## The two pillars
 
Itara is built on two co-equal parts. Neither is sufficient without the other.
 
**The wiring agent** makes topology a separate layer. It reads the wiring config before the application starts, resolves all connections once, wires the components together, and then steps aside. The application runs at full speed with no intermediary, no proxy in the call path, no decisions made at call time. The agent is language-specific — each supported language has its own implementation — but the wiring config and the component model are language-neutral.
 
**The tooling ecosystem** makes that layer safe and manageable. It validates configurations before deployment, catches mismatches and incompatibilities at authoring time, visualises the topology as a graph, and guides engineers through changes. Incorrect topologies cannot be deployed silently. The layer Itara introduces is the layer the tooling understands completely.
 
The wiring agent moves complexity out of the code. The tooling takes responsibility for that complexity. Together they make topology a first-class engineering artifact — declared explicitly, validated before deployment, and easy to change precisely because the system understands itself completely.
 
---
 
## Pillar one: the wiring agent
 
### Zero overhead on collocated calls
 
When two components share the same process and type system — for example, two JVM components wired as direct, or two Rust components loaded as separate dynamic libraries in the same process — the agent resolves a proxy at startup. At call time, that proxy fires the four observability events and then calls the implementation directly. There is no serialization, no network hop. The only overhead is the observability events themselves — structural properties of the platform that cannot be removed, because they are what makes the topology layer trustworthy.
 
For components colocated on the same host but running in separate runtimes — such as components written in different languages — the developer declares the local IPC mechanism in the wiring configuration. The runtime uses exactly the mechanism declared and nothing else. No network leaves the host. No transport decision is made autonomously by the agent.
 
This is a design commitment, not an aspiration. The only operations the agent adds to a direct call are those that are structural properties of the platform — specifically the observability events that make every interaction traceable and auditable. Everything else is zero.
 
### Startup resolution, not call-time decisions
 
The agent's job is finished before the first request arrives. All proxies are resolved, all connections are established, all SPIs are loaded. There is no application server sitting between components. There is no sidecar intercepting calls. The wiring happens once. The application runs free.
 
This is what makes topology change safe to reason about: the state of the system is fully determined by the wiring config at startup time. There are no runtime surprises, no lazy connection establishment, no decisions deferred to call time.

### Language-specific, specification-neutral

The agent is implemented separately for each supported language, following the idioms and capabilities of that language. The separation of topology from code does not vary. See the language agnosticism section for the full picture.
 
---
 
## Pillar two: the tooling ecosystem
 
### The topology compiler
 
The primary authoring and validation tool is a CLI that understands the system completely — which components exist, what contracts they implement, what serializers and transports they support, what versions are compatible. It validates every connection in the wiring config before deployment: API contracts, serializer compatibility, version ranges, dependency completeness.
 
This is the compiler for distributed system topology. It takes intent, validates it against what the system actually contains, and produces a verified artifact. A topology that does not pass validation does not deploy. Incorrect configurations are caught at authoring time, not discovered in production.
 
The same validation logic powers the visual editor. Engineers draw topology on a graph; the tool validates each connection in real time. The CLI and the UI are the same engine with different rendering layers. Everything checkable in one is checkable in the other.
 
### The controller
 
Above the compiler sits the controller — the intelligent layer that observes the running system, builds a model of its behaviour, and recommends or executes topology changes.
 
The trust ladder governs how much autonomy the controller is granted:
 
1. **Self-service** — engineer decides, tooling makes it cheap and reversible
2. **Recommendations** — controller suggests with reasoning, engineer approves
3. **Prepared actions** — one button, fully described, reversible
4. **Full automation** — opt-in, scoped, with kill switches and full audit trail
The controller never operates opaquely. Every recommendation is explained. Every automated action is logged and reversible. Trust is built on transparency, not on promises.
 
### The long-range vision
 
The tooling direction extends further than configuration authoring. As the system matures, the tooling becomes capable of reasoning about the entire lifecycle of a distributed system: validating that a topology change is safe before it is applied, predicting the effect of splitting or merging components against observed behaviour, and eventually driving automated topology decisions that the engineer approves rather than initiates.
 
The long-range vision: a distributed system that can recompile its own components with updated contracts, redeploy them with a new topology, and verify the result — all driven by the same tooling that started as an interactive CLI. Not autonomous in the sense of opaque, but autonomous in the sense of tireless, consistent, and always verifiable.
 
---

## Built-in observability

Observability is not an afterthought in Itara — it is a structural property of the architecture. The agent intercepts every call between components at startup. That interception point is the natural place to collect latency, throughput, error rates, and transport overhead without any instrumentation burden on the developer.

Every connection in the topology graph has metrics attached to it automatically. Engineers see not just the structure of their system but its behaviour — in real time, without writing a single line of monitoring code.

This observability is also what makes the controller trustworthy. Before automation is ever enabled, the engineer can watch the controller's reasoning against real data and verify that it is correct. A system that cannot be observed cannot be safely automated. Itara treats these as inseparable requirements.
 
---
 
## The wiring config as a graph
 
The wiring config is a directed graph. Nodes are components. Edges are typed connections. The same component can be reached by different callers via different connection types. This is the data structure the tooling will reason about, the visualisation layer will render, and the engineer will eventually plan graphically.
 
Today the wiring config is a file — a practical entry point for a system at its current scale. As systems grow to hundreds of components and thousands of connections, the backing store will evolve: a document store, a graph database, a relational schema, or something not yet decided. The agent always works from a concrete configuration snapshot, but where that snapshot lives and how it is managed is an operational concern.
 
---
 
## Language agnosticism
 
Itara is not a Java project. Java is the first reference implementation. Rust is the second. More languages follow.
 
The specification defines the component model, wiring model, transport interface, observability model, and context propagation contract. Any language with sufficient metaprogramming or build-time automation capability — whether through macro systems, bytecode manipulation, annotation processors, or dynamic class loading — can implement the specification. The interface definitions will eventually be replaced by a language-neutral descriptor — components in any language participating in the same topology graph, producing the same distributed traces.
 
The Rust implementation proved language agnosticism is not aspirational. The same wiring config switches a Java gateway from calling a Java calculator to calling a Rust calculator. The distributed trace shows both spans. The code changes nothing.
 
---
 
## The mathematical foundation
 
Individual components can be modeled as queuing systems. Composition follows Network Calculus — the same cumulative formulation, extended to arbitrary topologies with worst-case delay bounds. The goal: predict the effect of a topology change before making it, the way an engineer analyzes a circuit before building it.
 
This mathematical work is a research direction, not a committed implementation plan. If it lands, it becomes the foundation for the controller's predictive reasoning — the difference between a tool that validates topology and a tool that optimises it. Academic collaboration is the realistic path for taking it from theory to a runtime the controller can use.
 
---
 
## Why now
 
The microservices explosion has made the operational pain acute and universal. Kubernetes has trained engineers to think in external controllers and declarative topology. Rust makes native agent libraries practical without GC overhead. Academic work on queuing models and feedback control of computing systems has existed for two decades without a practical application to pull it into production use.
 
Itara is the missing application.
 
---
 
## Open questions
 
- The description language: minimum declaration for correct controller decisions
- Scale-invariant reasoning: making correct decisions at any component granularity
- The goal language: expressing heterogeneous, conflicting optimisation targets
- Controller decision model: formal approach that can be explained and audited
- Compositional mathematical models: composing component models at runtime
- Deployment integration: how the tooling interacts with existing orchestrators during topology transitions
---
 
## Author and origin
 
This vision was conceived and first implemented by Gabor Kiss in April 2026.
The Java proof of concept — topology change between direct and HTTP connections without code modification — was completed on April 12, 2026.
The Rust proof of concept — the same topology switch in a second language, proving language agnosticism — was completed in May 2026.
 
This repository is the origin of that work.