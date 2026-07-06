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

### What is Itara not trying to do?
 
Itara is a thin slice — it concentrates one specific concern, topology, into an explicit and executable layer. It is not trying to replace anything in the existing stack.
 
**Container orchestrators** (Kubernetes, Nomad, ECS) own deployment, scaling, and scheduling. Itara declares which components connect and how; orchestrators decide where they run and how many instances exist. These are complementary concerns.
 
**Service meshes** (Istio, Linkerd, Envoy) operate on network traffic between deployment units, typically as sidecars. Itara operates at the component level, above the network, with explicit contracts between components. A service mesh and Itara can coexist — they solve different problems at different layers.
 
**Dapr** provides building blocks (pub/sub, state stores, service invocation) as a sidecar API. Itara declares and realizes the communication structure between components without a sidecar in the call path at runtime. See the Dapr FAQ entry for a longer comparison.
 
**Deployment and CI/CD tooling** (Terraform, Helm, ArgoCD) own how software gets to where it runs. Itara's tooling will eventually generate inputs for these systems from the declared topology, but it does not replace them.
 
**Observability platforms** (Datadog, Jaeger, Grafana, Kibana) own how telemetry is stored, queried, and visualised. Itara emits structured observability events and ships an OTel observer — where that data goes is the operator's choice.
 
The goal is not to own the stack. It is to make one currently invisible layer — topology — explicit, verifiable, and executable, and let everything else continue doing what it already does well.

---

### Isn't this the same as CORBA, Java RMI, or other transparency-oriented systems?

No, and the distinction matters.

CORBA, Java RMI, DCOM, and EJB all attempted to make remote calls indistinguishable from local calls — to hide the network from the developer entirely. The premise was that the network doesn't exist, or at least that you shouldn't have to think about it. This failed for well-documented reasons: the network does exist, it has latency and failure modes that local calls don't, and hiding those differences produces systems that are hard to reason about and harder to debug.

Itara's premise is the opposite. The network exists and its presence is always visible — in the wiring config, in the traces, in the error contracts. What Itara removes from component code is not the awareness that a call might be remote, but the responsibility for encoding how that remote call works. Failure semantics, serialization, transport selection, context propagation — these are topology concerns declared in the wiring config, not infrastructure boilerplate written into the business logic.

The difference in plain terms: CORBA made topology invisible. Itara makes it explicit and concentrated. Topology is declared, validated by tooling before deployment, and visible to everyone — the opposite of hidden.

---

### How is this different from Dapr?

The difference is where the abstraction sits.

Dapr is a sidecar: a separate process injected alongside your container. Every call your service makes to another service goes through a localhost network hop to the Dapr sidecar, which routes it to the target's sidecar, which delivers it. This is true even when both services are on the same host. The sidecar tax is always paid.

Itara works at the method call level. When two components are declared colocated in the wiring config, the proxy resolves at startup and the call is a direct in-process function call — no serialization, no network, no sidecar. When they are remote, the call goes over HTTP or whichever transport is configured. The code is identical in both cases. Dapr cannot offer zero-overhead colocation because the sidecar model structurally prevents it.

There is also a granularity difference. Dapr operates at the service level — the unit of deployment is a service, and the sidecar wires services together. Itara operates at the component level, which is deliberately finer-grained than a service. Multiple components can be colocated in one process or distributed across separate processes — all through config changes. Service boundaries are not fixed deployment artifacts in Itara; they are adjustable topology decisions.

The other difference: Dapr abstracts infrastructure (state stores, brokers, secrets). Itara abstracts topology — where components run and how they connect. These are different problems.

---

### How does Itara compare to Microsoft Aspire?
 
They operate at different layers and solve different problems.
 
Aspire is a development and deployment orchestration tool — it declares which services and infrastructure resources exist, how they start up, and how they get deployed. It significantly improves the experience of running and shipping multi-service applications, particularly for .NET teams.
 
Itara operates one layer deeper: the communication contracts between components. It declares what components call, what they return, how failures are handled, and validates that every connection is correct before deployment. Where Aspire answers "how do I run and deploy my services consistently," Itara answers "what are the communication contracts between my components, are they valid, and can topology change without touching code." The two tools address complementary concerns and can coexist.

---

### What does zero-overhead colocation actually mean?

When two components share the same process and type system — two JVM components wired as `direct`, or two Rust components in the same process — the proxy fires the four observability events and calls the implementation directly. No serialization, no network, no transport overhead of any kind. The observability events are the only addition, and they are a structural property of the platform rather than optional instrumentation.

The corollary: running two components colocated in Itara costs nothing compared to writing them as a single monolith. The boundary is free. This is what makes topology switching meaningful — moving from colocated to remote changes latency numbers in your traces, nothing else.

---

### Topology is no longer in the component code — how do I handle network failures?

Topology is not hidden — it is explicitly declared in the wiring config, where it is visible, auditable, and validated before deployment. What is absent from component code is the responsibility for encoding how failures travel, not the awareness that failures can happen.

Failure semantics — retries, timeouts, circuit breaking — are connection-level configuration, not component-level code. They belong in the wiring config alongside the transport declaration. The component code never sees infrastructure boilerplate, but the failure contracts are explicit and declared.
 
The failure semantics SPI is pluggable. A single implementation owns the complete strategy for a connection — retry logic, timeout enforcement, circuit breaking — as a cohesive unit of behaviour declared in configuration. A built-in implementation ships with the platform covering the common cases. Teams with specific requirements can provide their own. The wiring config carries enough information for the tooling to catch misconfigurations — such as a timeout declared against a transport that cannot enforce it — before deployment.
 
The current implementation surfaces failures as typed error contracts at the call site — `CHECKED` for declared component errors, `RUNTIME` for unexpected component failures, and `TRANSPORT` for infrastructure failures. The failure semantics SPI shipped in v0.2.

---

### With topology out of the component code, doesn't that make debugging harder?

With topology being more visible than ever before, tracking down errors actually becomes easier.

At the code level, every failure that crosses a component boundary surfaces as a typed error contract — `CHECKED` for declared component errors, `RUNTIME` for unexpected component failures, and `TRANSPORT` for infrastructure failures. The error carries the original exception class and message. Without reading a line of transport code, you know immediately whether the failure was in the business logic, the component implementation, or the infrastructure layer.

At the system level, the four-event observability model fires at every component boundary regardless of transport. Every failed request leaves a trace across the full call chain. The trace shows exactly where the failure occurred, with serialization cost, network cost, and component processing time separately measurable.

The error contract is extensible if needed. If proven necessary, future versions might carry more specific failure detail — timeout, serialization error, network failure — without changing how callers handle errors.

---

### Does this replace DDD, event sourcing, or other architectural patterns?

No. As the manifesto puts it: Itara does not replace architecture, it concentrates it.

You still design your bounded contexts, aggregates, and domain events exactly as before. What changes is where the plumbing lives. Instead of transport configuration, retry policies, and service discovery calls scattered across every service, the communication structure of your system is expressed in one place. The patterns are still yours. The topology that gives them shape is now auditable and changeable without touching code.

---

### Isn't the wiring config just a configuration file? What makes it architecture?

The wiring config is not configuration in the sense of database connection strings or feature flags — values that tune a running system. It is the authoritative description of which components exist, how they communicate, and what topology they form. Every connection in the system, every deployment boundary, every transport choice is declared there and nowhere else.

Traditional distributed systems encode this information across hundreds of files: HTTP clients, service discovery calls, retry policies, timeout settings, message producer configurations. Understanding the architecture means reading all of it. Changing the architecture means changing all of it, coordinated across teams, with no single place to validate correctness.

When the wiring config is the single source of truth, it becomes what architecture diagrams have always aspired to be: a complete, accurate, machine-readable description of how the system is structured. The difference is that it isn't a diagram — it's the thing itself. The tooling validates it, the agent enforces it, and the traces reflect it. The diagram and the system are the same artifact.

---

### Why not just write standard HTTP or gRPC clients?

Because the moment you write an HTTP client into a service you make an architectural commitment that is expensive to reverse. Splitting the service later means refactoring the client. Moving to async messaging means rewriting both sides. The transport choice calcifies.

Itara makes that decision a configuration entry. Changing from HTTP to Kafka is one line in the wiring config. The component code does not change because it never knew which transport was being used. Architectural decisions remain reversible as the system evolves and understanding grows.

---

### Does this work with Spring Boot?

Yes. Itara and Spring Boot are different layers and coexist naturally — neither knows about the other. A Spring `@Bean` method can call the Itara registry to fetch a component implementation and return it as a bean. Spring manages its context, Itara manages the wiring beneath it. There is no dedicated adapter at this stage — Itara and Spring Boot coexist, but there is no deeper integration.

Migrating an existing Spring Boot service is incremental: extract the service interface into a separate API artifact, write an activator (a single-method factory that receives the registry and returns the implementation), and update the relevant `@Bean` method to pull from the registry instead of constructing directly. The rest of the Spring context is untouched. Deeper integration — for example reusing Spring's servlet infrastructure for HTTP transport — may happen through optional separate libraries in the future.

---

### Why was Rust chosen as the second implementation language?

Two reasons, and they are related.

The first is practical: Rust is the planned implementation language for Itara's tooling — the CLI, the deployment tool, and eventually the controller. A working Rust component implementation means the tooling can be tested against real artifacts from day one.

The second is philosophical. Rust's central value is that correctness should be a compile-time property — errors caught before the program runs, not at runtime when something breaks in production. Itara's central value for topology is the same thing expressed at the system level. The `.itara` metadata files that every component artifact carries, the `[serializers]` declarations, the contract version information — these exist specifically so that tooling can validate the entire system topology before anything deploys. A serializer mismatch, a missing component, an incompatible contract version: these are caught at configuration time, not discovered when the first request fails.

The parallel is genuine, not coincidental. A language community that thinks carefully about compile-time correctness is the right audience for a framework that extends that philosophy to distributed system topology.

### What languages are supported?

Java and Rust today. Both have working implementations with cross-language calls demonstrated — a Java gateway calling a Rust calculator over HTTP produces a single distributed trace in Kibana. Go and Python are on the roadmap. The specification is language-neutral; any language with sufficient metaprogramming or build-time automation capability can implement it.

---

### What happens if two colocated components have conflicting dependency versions?

This is not a new problem — it is the same diamond dependency issue that appears whenever two libraries share a transitive dependency at different versions. In a single-component build, the compiler or build tool catches it. In a distributed system where components are built independently, it has traditionally been invisible until something breaks at runtime.

Itara's position is that this is exactly the kind of problem a topology compiler should catch. The `.itara` metadata file that every component artifact carries is the natural place to declare key dependency versions. The CLI, when validating a wiring configuration that collocates two components, can compare their dependency manifests and apply straightforward rules: matching major and minor versions are approved, a minor version difference produces a warning, a major version difference is rejected before the configuration is accepted.

This is planned for v0.3 of the CLI's wiring configuration verification. The goal is the same as it is for serializer compatibility and contract version checking — topology errors caught before deployment, not discovered when the first request fails.

It is worth acknowledging that Itara's component composition model — whether dynamic loading at runtime or static assembly at build time, depending on the language implementation — introduces its own angle on this problem — similar to the challenges found in plugin architectures, such as those in message brokers or application servers, where independently built plugins share a runtime and can conflict. Itara does not make this problem worse than it already is in those ecosystems, but it does not make it disappear either. That is a real drawback and it is treated as one. The tooling is the planned mitigation — making the conflict visible and rejectable at configuration time rather than leaving it to surface at runtime.

In the meantime, colocation is a decision made by someone who controls the build. Components built by the same team with a shared dependency strategy are the natural colocation candidates. The tooling will make the safe boundary explicit and enforceable rather than a matter of discipline.

---

### How do I handle secrets in the wiring config?
 
The wiring config supports environment variable substitution — any value can
be written as `${VAR_NAME}` and the agent resolves it at startup from the
environment. This is sufficient for most cases: hostnames, ports, bootstrap
server addresses, and similar connection parameters can all be injected without
hardcoding them.
 
More sophisticated secret management — a secret store SPI that can resolve
secrets from Vault, AWS Secrets Manager, or similar — is a natural future
extension. The substitution pass that already exists provides a clean insertion
point for it. For now, env var substitution is the supported and recommended
approach.
 
---
 
### Can I use Itara without the CLI?
 
Yes. The CLI is not required to run components — the agent reads the wiring
config directly at startup and works without it.
 
What you lose without the CLI is the safety layer: orphaned nodes, version
mismatches, timeout misconfiguration, and transport capability conflicts are
all caught by `itara verify` before deployment. Without it, these surface at
startup or at runtime instead. The CLI is not optional polish — it is how
Itara keeps its promise that incorrect topologies cannot be deployed silently.
The agent alone cannot substitute for it.
 
---
 
### What happens if the wiring config is wrong?
 
Two layers of protection catch configuration errors.
 
The first is the CLI: `itara verify` catches structural errors and
compatibility mismatches before anything starts. The set of checks grows with
the project and with feedback from real usage.
 
The second is the agent itself: whatever the CLI does not catch, the agent
validates at startup time. If a connection cannot be resolved, a referenced
artifact is missing, or a configuration is invalid, the agent fails fast and
the application does not start. The deployment never succeeds in a broken
state. There is no partial startup, no lazy failure on the first call.
 
---
 
### How does Itara interact with service discovery?
 
It doesn't, currently. Itara relies on the infrastructure's ability to resolve
the addresses declared in the wiring config — whether that's DNS, a hosts
file, or a container orchestrator's internal networking. The address in the
config must be resolvable by whatever mechanism the environment provides.
 
A service discovery SPI is on the roadmap for v0.3, which would allow
implementations to resolve component addresses dynamically at startup rather
than requiring them to be statically declared in the wiring config.
 
---
 
### How do I handle schema evolution in event contracts?
 
Nothing special is implemented at this stage. The usual approaches apply:
additive changes are safe, breaking changes require coordinated versioning
across producers and consumers. The event contract version declared in the
`.itara` metadata file is what the CLI uses to check compatibility — a version
bump signals that consumers need to be reviewed.
 
Patterns like consumer-driven contract testing and the expand-contract
technique work alongside Itara without any special support. This is an area
that will develop further as real-world usage surfaces what tooling is
actually needed.
 
---
 
### Should the wiring config be version-controlled?
 
Yes — version controlling the wiring config is the recommended approach. It is
the authoritative declaration of the system's topology, and treating it with
the same discipline as code is the right instinct.
 
How topology changes are managed across environments, how config versions map
to deployments, and how rollback is handled are not yet fully specified.
Design notes exist on the topic but it is not yet part of the formal
specification. Feedback on real-world config management needs is welcome.
 
---
 
### What does migration away from Itara look like?
 
Components are plain classes and interfaces. The API artifacts are plain Java
interfaces or equivalent. Nothing in the component code requires Itara to
compile or run — the only Itara dependency is in the activator, the single
factory method that wires the component into the registry.
 
Migrating out means replacing the Itara wiring with whatever mechanism you
want to use instead, and removing the activator. The business logic, the
contracts, and the tests are all untouched.
 
Migration out can also be gradual, the same way migration in can be. Itara
coexists with other communication mechanisms without interference — you can
move connections out of the wiring config one at a time while the rest of the
system continues running through Itara unchanged.

---

### What is the current state? Is it production-ready?

Not yet. The current release is v0.2, tagged and public on GitHub.

What works today: direct, HTTP, and Kafka topologies in Java and Rust, cross-language calls with Rust over HTTP, pluggable serializers, pluggable observers, Spring Boot coexistence confirmed for basic cases, the four-event observability model with OTel and Kibana integration, self-describing artifacts via `.itara` metadata files, event-driven topology with virtual nodes and event contracts, failure semantics SPI (retry, timeout, and circuit breaking as pluggable connection-level config with idempotency protection), checked error reconstruction, YAML anchor and merge key support in the wiring config, and `itara-cli` with `inspect` and `verify` commands including API version compatibility and timeout capability checks. The specification is at v0.2.
 
Use it for experimentation and architecture exploration. Production use requires further work on tooling maturity, agent-less deployment options, and language coverage.

---

### What is Orca?

Orca is the planned controller — the component that closes the loop. Itara makes topology declared and verifiable; Orca enforces it at runtime. It observes the running system, detects drift between the declared topology and actual behaviour, and acts on it. In its most capable form, it makes suggestions based on observed performance — recommending topology changes the engineer can approve, prepare, and apply with confidence.
 
The trust ladder governs how much autonomy Orca is granted: from recommendations the engineer approves, to prepared reversible actions, to full automation that is always auditable and always stoppable.
 
How much of Orca will be open source and how much will be enterprise features is not yet decided.

---

### Where does the name come from?

Itara means "of the other" or "belonging to another" in Sanskrit — which fits rather well, since the whole point is that topology belongs to configuration, not to the component. The component does not own its connections.

That said, the honest answer is that the core team is not good at naming things. The name came from browsing fantasy, anime, manga, and sci-fi references, merging two names that sounded reasonable together, and checking that the result was not already overused on GitHub. It turned out to also mean something relevant in Sanskrit. That part was a lucky accident.
