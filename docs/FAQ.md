# Itara — Frequently Asked Questions

---

### What is Itara?

Itara is a specification for topology-as-configuration, with reference implementations in Java and Rust. The core idea: which components exist, how they communicate, and where they run are configuration decisions — not code decisions. Component logic expresses what the system does. The wiring config expresses how the parts connect. These are different concerns and they live in different places.

In practice: you write a plain interface and an implementation. The agent reads the wiring config at startup, wires the components together — directly in-process, over HTTP, or over any other supported transport — and hands off to your application. Your code never changes when the topology changes. Only the config changes.

---

### Is this a framework? A runtime? A service mesh?

None of those exactly. It is closer to a specification with reference implementations. The Java and Rust agents are engines that read the spec and apply it at startup. Your application compiles and runs as a normal binary. There is no Itara API in your business logic, no annotations on your domain objects, no framework base classes to extend.

The closest analogy is a classloader for distributed systems topology — something that wires things together at startup and then gets out of the way.

---

### How is this different from Dapr?

The difference is where the abstraction sits.

Dapr is a sidecar: a separate process injected alongside your container. Every call your service makes to another service goes through a localhost network hop to the Dapr sidecar, which routes it to the target's sidecar, which delivers it. This is true even when both services are on the same host. The sidecar tax is always paid.

Itara works at the method call level. When two components are declared colocated in the wiring config, the proxy resolves at startup and the call is a direct in-process function call — no serialization, no network, no sidecar. When they are remote, the call goes over HTTP or whichever transport is configured. The code is identical in both cases. Dapr cannot offer zero-overhead colocation because the sidecar model structurally prevents it.

There is also a granularity difference. Dapr operates at the service level — the unit of deployment is a service, and the sidecar wires services together. Itara operates at the component level, which is deliberately finer-grained than a service. Multiple components can live in one process, be split across processes, or be merged back together — all through config changes. Service boundaries are not fixed deployment artifacts in Itara; they are adjustable topology decisions.

The other difference: Dapr abstracts infrastructure (state stores, brokers, secrets). Itara abstracts topology — where components run and how they connect. These are different problems.

---

### What does zero-overhead colocation actually mean?

When two components share the same process and type system — two JVM components wired as `direct`, or two Rust components in the same process — the proxy fires the four observability events and calls the implementation directly. No serialization, no network, no transport overhead of any kind. The observability events are the only addition, and they are a structural property of the platform rather than optional instrumentation.

The corollary: running two components colocated in Itara costs nothing compared to writing them as a single monolith. The boundary is free. This is what makes topology switching meaningful — moving from colocated to remote changes latency numbers in your traces, nothing else.

---

### If topology is hidden from code, how do I handle network failures?

Failure semantics — retries, timeouts, circuit breaking — are connection-level configuration, not component-level code. They belong in the wiring config alongside the transport declaration. The component code never sees them.

This is on the roadmap and not yet implemented. The current implementation surfaces failures as panics or exceptions at the call site. Pluggable failure semantics are a pre-1.0 milestone.

---

### Doesn't hiding topology make debugging harder?

The opposite, for two reasons.

First, the wiring config is the single source of truth for how the system is connected. There is no need to read every service's code to understand the topology — it is all in one place, readable by a human and parseable by tooling.

Second, Itara's four-event observability model fires at every component boundary regardless of transport. `CALL_SENT`, `CALL_RECEIVED`, `RETURN_SENT`, `RETURN_RECEIVED` — these events give you precise latency decomposition: serialization cost, network cost, and component processing time are all separately measurable without any instrumentation in your code. When you switch a connection from direct to HTTP, the trace structure is identical and the latency numbers change. That is the topology switch made visible.

---

### Does this replace DDD, event sourcing, or other architectural patterns?

No. As the manifesto puts it: Itara does not replace architecture, it concentrates it.

You still design your bounded contexts, aggregates, and domain events exactly as before. What changes is where the plumbing lives. Instead of transport configuration, retry policies, and service discovery calls scattered across every service, the communication structure of your system is expressed in one place. The patterns are still yours. The topology that gives them shape is now auditable and changeable without touching code.

---

### Why not just write standard HTTP or gRPC clients?

Because the moment you write an HTTP client into a service you make an architectural commitment that is expensive to reverse. Splitting the service later means refactoring the client. Moving to async messaging means rewriting both sides. The transport choice calcifies.

Itara makes that decision a configuration entry. Changing from HTTP to Kafka is one line in the wiring config. The component code does not change because it never knew which transport was being used. Architectural decisions remain reversible as the system evolves and understanding grows.

---

### Does this work with Spring Boot?

Yes. Itara and Spring Boot are different layers and coexist naturally — neither knows about the other. A Spring `@Bean` method can call the Itara registry to fetch a component implementation and return it as a bean. Spring manages its context, Itara manages the wiring beneath it. There is no dedicated adapter and none is needed.

Migrating an existing Spring Boot service is incremental: extract the service interface into a separate API artifact, write an activator (a single-method factory that receives the registry and returns the implementation), and update the relevant `@Bean` method to pull from the registry instead of constructing directly. The rest of the Spring context is untouched. Deeper integration — for example reusing Spring's servlet infrastructure for HTTP transport — may happen through optional separate libraries in the future, but will never be a core feature.

---

### Why was Rust chosen as the second implementation language?

Two reasons, and they are related.

The first is practical: Rust is the planned implementation language for Itara's tooling — the CLI, the deployment tool, and eventually the controller. A working Rust component implementation means the tooling can be tested against real artifacts from day one.

The second is philosophical. Rust's central value is that correctness should be a compile-time property — errors caught before the program runs, not at runtime when something breaks in production. Itara's central value for topology is the same thing expressed at the system level. The `.itara` metadata files that every component artifact carries, the `[serializers]` declarations, the contract version information — these exist specifically so that tooling can validate the entire system topology before anything deploys. A serializer mismatch, a missing component, an incompatible contract version: these are caught at configuration time, not discovered when the first request fails.

The parallel is genuine, not coincidental. A language community that thinks carefully about compile-time correctness is the right audience for a framework that extends that philosophy to distributed system topology.

### What languages are supported?

Java and Rust today. Both have working implementations with cross-language calls demonstrated — a Java gateway calling a Rust calculator over HTTP produces a single distributed trace in Kibana. Go and Python are on the roadmap. The specification is language-neutral; any language capable of dynamic linking or RPC can implement it.

---

### What happens if two colocated components have conflicting dependency versions?

This is not a new problem — it is the same diamond dependency issue that appears whenever two libraries share a transitive dependency at different versions. In a single-component build, the compiler or build tool catches it. In a distributed system where components are built independently, it has traditionally been invisible until something breaks at runtime.

Itara's position is that this is exactly the kind of problem a topology compiler should catch. The `.itara` metadata file that every component artifact carries is the natural place to declare key dependency versions. The CLI, when validating a wiring configuration that collocates two components, can compare their dependency manifests and apply straightforward rules: matching major and minor versions are approved, a minor version difference produces a warning, a major version difference is rejected before the configuration is accepted.

This is on the roadmap for the CLI's wiring configuration verification phase. The goal is the same as it is for serializer compatibility and contract version checking — topology errors caught before deployment, not discovered when the first request fails.

It is worth acknowledging that Itara's component composition model — whether dynamic loading at runtime or static assembly at build time, depending on the language implementation — introduces its own angle on this problem — similar to the challenges found in plugin architectures, such as those in message brokers or application servers, where independently built plugins share a runtime and can conflict. Itara does not make this problem worse than it already is in those ecosystems, but it does not make it disappear either. That is a real drawback and it is treated as one. The tooling is the planned mitigation — making the conflict visible and rejectable at configuration time rather than leaving it to surface at runtime.

In the meantime, colocation is a decision made by someone who controls the build. Components built by the same team with a shared dependency strategy are the natural colocation candidates. The tooling will make the safe boundary explicit and enforceable rather than a matter of discipline.

### What is the current state? Is it production-ready?

Not yet. The current milestone is Show HN — a public demonstration of the core concepts working end-to-end. What works today: direct and HTTP topologies in Java and Rust, cross-language calls, pluggable serializers, self-describing lib dir via `.itara` metadata files, Spring Boot integration, and distributed traces via OTel. What is still in progress: Kafka transport, full observability SPI in Rust, the CLI, and the formal spec reaching v1.0. Use it for experimentation and architecture exploration. Production use requires the missing pieces.

---

### What is Orca?

Orca is the planned commercial controller — the intelligent layer above the orchestrator. It observes OTel metrics, builds a model of system behaviour, and recommends or executes topology changes. The open-source runtime is and will remain free. Orca is the commercial product built on top of it.

The trust ladder governs how much autonomy Orca is granted: from recommendations the engineer approves, to prepared reversible actions, to full automation that is always auditable and always stoppable.

---

### Where does the name come from?

Itara means "of the other" or "belonging to another" in Sanskrit — which fits rather well, since the whole point is that topology belongs to configuration, not to the component. The component does not own its connections.

That said, the honest answer is that the core team is not good at naming things. The name came from browsing fantasy, anime, manga, and sci-fi references, merging two names that sounded reasonable together, and checking that the result was not already overused on GitHub. It turned out to also mean something relevant in Sanskrit. That part was a lucky accident.
