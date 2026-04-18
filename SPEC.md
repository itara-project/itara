# Itara Runtime Specification

**Status:** Draft  
**Repository:** https://github.com/itara-project/itara  
**License:** Apache 2.0

---

## Abstract

This document specifies the Itara runtime — a platform for building distributed software systems in which the communication topology between components is a declarative configuration decision, not a code decision. A conforming Itara runtime allows component authors to define business logic independently of how that logic is connected to the rest of the system. The topology — whether two components communicate directly within a single process, over HTTP, over a message broker, or any other mechanism — is expressed in a wiring configuration and applied at startup without modifying component code.

**Pronunciation:** Itara is pronounced *ee-tah-rah* — each vowel as in Latin or Hungarian: the *i* as in *machine*, the *a* as in *father*. Not *eye-tara*.

This specification defines the component model, the wiring model, the agent contract, the transport interface, the observer interface, and the context propagation model. It does not prescribe any particular implementation language, framework, or deployment mechanism. The Java implementation maintained by the Itara project is the reference implementation. Conforming implementations MAY be built in any language or runtime environment.

---

## Status of This Document

This is a **Draft** specification. It reflects the current design of the reference implementation and the decisions made by the core team as of the date of this document. Sections marked **[OPEN]** contain unresolved design questions. The specification SHOULD NOT be considered stable. Breaking changes are possible before a versioned release is published.

Feedback, objections, and proposals are welcome as GitHub issues on the Itara repository.

---

## Table of Contents

1. Vision and Values
2. Terminology
3. Component Model
4. Wiring Model
5. Agent Contract
6. Transport Interface
7. Observer Interface
8. Context Propagation
9. Conformance

---

## 1. Vision and Values

### 1.1 Summary for Architects

Itara separates two concerns that software systems have historically conflated: **what a component does** and **how it communicates with other components**. A component author defines a contract — an interface — and an implementation. A system architect defines the topology — which components communicate, over which mechanism, in which deployment configuration. The Itara runtime enforces the topology at startup and makes it transparent to both sides.

The result is a system where topology is cheap to change. Moving from a colocated deployment to a distributed one, or from HTTP to a message broker, requires a configuration change, not a code change. This makes architectural decisions reversible — a property that most distributed systems lack entirely.

### 1.2 Core Values

The following values inform every design decision in this specification. Implementations that conflict with these values are not conforming, even if they satisfy the letter of the normative requirements.

**Topology is declarative, never coded.**  
A component implementation MUST NOT be required to contain knowledge of how it is connected to other components. Connection mechanism, transport protocol, and deployment topology are expressed in configuration and applied by the runtime. Itara lifts the burden of topology from the component author — it does not prescribe how component code is written.

**Semantics are explicit. Mechanics are abstracted.**  
Component contracts express what is communicated. The runtime expresses how. Itara does not require these concerns to be mixed, and a conforming runtime MUST NOT force or encourage component authors to encode transport or topology concerns in their contracts or implementations.

**Colocation is as close to zero-overhead as the runtime environment allows.**  
When two components are colocated, the runtime MUST use the communication mechanism declared in the wiring configuration. For components sharing the same process and type system — for example, two JVM components, or two native components compiled to the same binary — a direct connection means zero transport overhead, no serialisation, no network hop. For components colocated on the same host but in separate processes or runtimes — for example, a JVM component and a Python component — the wiring configuration declares the IPC mechanism to use (Unix domain socket, shared memory, named pipe, or any other supported local transport). The runtime follows the configuration. It does not select or substitute a mechanism on behalf of the developer. The only operations the runtime adds to any direct call are those that are structural properties of the platform — specifically, the observability events defined in section 7. These are not optional. Everything else is zero. This is not a performance aspiration — it is a design requirement.

**Remote calls are transparent but observable.**  
A component implementation MUST NOT need to know whether it is calling another component directly or over a network. The call looks identical from the caller's perspective. However, the runtime MUST emit observable events for every interaction, regardless of transport type, so that the topology can be monitored, traced, and audited.

**Components are defined by contracts, not frameworks.**  
A component contract is a plain interface. A component implementation is a plain class. No framework annotations, no inherited base classes, no framework-managed lifecycle — beyond the minimal declarations required by the Itara runtime — are required or expected.

**Refactoring is cheap.**  
The architecture of the system MUST remain easy to change. Topology decisions made at the start of a project SHOULD be reversible at any point in the project's lifecycle without requiring code changes to component implementations.

**Language neutrality is a core value.**  
This specification is intentionally language-agnostic. The component model, wiring model, and interface contracts SHOULD be implementable in any language that can be compiled or interpreted on its target platform.

**The runtime is pluggable.**  
Transport mechanisms, observability backends, and service discovery strategies are plugins, not built-in concerns. The runtime provides stable interfaces. Implementations are discovered and loaded at startup.

**Auditability is a first-class property.**  
Every component interaction is observable and auditable by design. A conforming runtime MUST emit events for every call, regardless of transport type or deployment topology. Systems built on Itara MUST be auditable without additional instrumentation by the component author.

**Software must become soft again.**  
Topology decisions should be cheap to change. A system whose topology cannot be modified without code changes is not soft — it is rigid. Itara exists to make topology soft. Other architectural concerns — security, data models, business logic — are beyond the scope of this platform and remain the responsibility of the teams that own them.

---

## 2. Terminology

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **MAY**, and **OPTIONAL** in this document are to be interpreted as described in [RFC 2119](https://www.ietf.org/rfc/rfc2119.txt).

**Component**  
A unit of business logic with a defined contract and a concrete implementation. A component has an identifier that is unique within a deployment.

**Contract**  
An interface that defines the operations a component exposes. A contract MUST NOT contain any knowledge of transport mechanism, deployment topology, or runtime infrastructure.

**Implementation**  
A concrete class that satisfies a contract. An implementation MUST NOT contain any knowledge of how it is connected to other components.

**Activator**  
A factory responsible for creating an instance of a component implementation. The activator is the single point where an implementation is instantiated and its dependencies are resolved.

**Wiring Configuration**  
A declarative description of the components present in a deployment and the connections between them. The wiring configuration is the sole source of topology information for the runtime. It is consumed by the runtime at startup. Component code SHOULD NOT read or depend on the wiring configuration directly — doing so would couple the implementation to a deployment concern and undermine the topology abstraction.

**Connection**  
A directed relationship between two components expressing that one component (the caller) communicates with another component (the callee) over a specified transport type.

**Transport**  
A pluggable mechanism for carrying calls between components. A transport provides both the outbound proxy (for the caller) and the inbound listener (for the callee). Transports are loaded by the runtime at startup and are invisible to component code.

**Proxy**  
An object that satisfies a component contract but delegates calls to a remote implementation via a transport. A proxy is created by the runtime and registered in the component registry. Component code interacts with the proxy as if it were the real implementation.

**Listener**  
A transport-specific server that receives inbound calls and dispatches them to the local component implementation via the component registry.

**Registry**  
The runtime's store of component instances and proxies, keyed by component identifier. The registry is the mechanism by which one component obtains a reference to another.

**Agent**  
The runtime process that reads the wiring configuration, loads transports, constructs proxies and listeners, and registers activators. The agent initialises the system before application code executes.

**Observer**  
A pluggable receiver of runtime events. Observers record, export, or react to component interactions for the purposes of monitoring, tracing, and auditing.

**Context**  
A set of metadata associated with a single request as it travels through the system. Context is propagated automatically by the runtime across component boundaries, both within a process and across network hops.

---

## 3. Component Model

### 3.1 Summary

A component is a named unit of business logic. It has a contract — an interface describing what it can do — and an implementation — a class that does it. The runtime connects components to each other according to the wiring configuration. Neither the contract nor the implementation contains any knowledge of this connection.

### 3.2 Component Identifier

Every component MUST have an identifier that is:

- A non-empty string
- Unique within the deployment described by a wiring configuration
- Stable across restarts of the runtime

The component identifier is used in the wiring configuration to reference the component in connections. It is also used in runtime events emitted by the observer interface.

### 3.3 Contract

A component contract MUST be expressed as an interface in the implementation language. The contract MUST NOT:

- Extend, implement, or inherit from any Itara-specific type
- Contain parameters or return types that encode transport-specific concerns
- Contain lifecycle methods managed by the Itara runtime

The contract MUST be compilable and usable independently of any Itara runtime dependency. Specifically, it MUST be possible to create an instance of a class implementing the contract and call its methods without the Itara runtime being present.

A conforming runtime MUST be able to generate a proxy that satisfies any contract, subject to the constraints of the implementation language's type system.

#### 3.3.1 Contract Declaration

A conforming runtime MUST provide a mechanism to associate a contract interface with a component identifier. In the Java reference implementation, this is achieved with a `@ComponentInterface` annotation on the interface declaration. Other implementations MAY use alternative mechanisms appropriate to their language — for example, a metadata file, a naming convention, or a configuration entry — provided the association is unambiguous and does not require modifying the interface itself.

### 3.4 Implementation

A component implementation MUST:

- Satisfy the component contract
- Be instantiable by the activator without requiring the Itara runtime to be present as a direct dependency of the implementation class

A component implementation MUST NOT:

- Reference the component registry directly to obtain references to other components
- Contain knowledge of which transport will be used to deliver calls to it
- Contain knowledge of which transport will be used when it calls another component

### 3.5 Activator

An activator is a factory that creates a component implementation instance. The runtime invokes the activator when a component instance is first needed. The activator receives a reference to the component registry and MAY use it to obtain references to other components required by the implementation.

An activator MUST:

- Be identified in the wiring configuration or in a deployment descriptor associated with the implementation jar
- Be invokable by the runtime without arguments other than the registry reference
- Return an instance that satisfies the component contract

An activator MAY be lazy — instantiated only when the component is first requested from the registry — or eager — instantiated at startup. Conforming runtimes MUST support lazy activation. Support for eager activation is OPTIONAL.

---

## 4. Wiring Model

### 4.1 Summary

The wiring configuration is a directed graph. Nodes are component declarations. Edges are connections between components. The configuration is read by the runtime at startup. It is the only place where topology is expressed. No component code reads or interprets the wiring configuration.

### 4.2 Configuration Format

The wiring configuration format is not prescribed by this specification. The reference implementation uses YAML. Conforming implementations MAY use any format capable of expressing the data model defined in this section, provided the format is:

- Human-readable
- Stored outside of compiled component artifacts
- Loadable at runtime without recompilation

### 4.3 Component Declarations

The wiring configuration MUST declare every component that participates in the deployment described by that configuration. A component declaration MUST include:

- The component identifier
- A reference to the activator responsible for instantiating the implementation, OR sufficient information for the runtime to discover the activator without an explicit reference

### 4.4 Connection Declarations

A connection declaration MUST include:

- The identifier of the calling component (`from`)
- The identifier of the called component (`to`)
- The transport type

A connection declaration MAY include:

- Transport-specific parameters (host, port, topic name, queue name, etc.)
- A declaration that the `from` end is an external entry point (i.e., the caller is not a component managed by this runtime instance)

### 4.5 Connection Semantics

#### 4.5.1 Direct Connections

A direct connection declares that the calling component and the called component are colocated and that the runtime MUST use the communication mechanism declared in the wiring configuration.

For components sharing the same process and type system, a direct connection MUST be dispatched as a direct method invocation with no intermediate serialisation or network hop. A conforming runtime MUST guarantee that such a connection introduces no overhead beyond that of a direct method call in the implementation language.

For components colocated on the same host but in separate processes or runtimes — for example, components implemented in different languages — the wiring configuration MUST declare the local IPC mechanism to use (Unix domain socket, shared memory, named pipe, or any other supported local transport). The runtime MUST use exactly the mechanism declared. It MUST NOT substitute an alternative mechanism, even if it determines another mechanism would be more efficient. The developer's explicit choice takes precedence.

This principle reflects a core Itara value: the runtime follows configuration. It does not make topology or transport decisions autonomously.

#### 4.5.2 Transport Connections

A transport connection declares that the calling component and the called component communicate via a named transport. The transport type MUST correspond to a transport implementation loaded by the runtime.

For a transport connection, the runtime MUST:

- On the caller side: create a proxy that satisfies the contract and delegates calls to the transport
- On the callee side: start a listener that receives calls from the transport and dispatches them to the local implementation via the registry

### 4.6 Multiple Connections to a Single Component

A conforming runtime MUST support a component being the target of connections of different transport types simultaneously. A component implementation that exposes its contract over HTTP, Kafka, and gRPC simultaneously MUST be representable in the wiring configuration. The runtime MUST start a listener for each declared inbound connection and route all inbound calls to the same component instance via the registry.

### 4.7 Wiring Configuration Scope

A wiring configuration describes a single runtime process's view of the topology. In a multi-process deployment, each process has its own wiring configuration slice. A conforming runtime MUST NOT require a global topology configuration to be present in any single process.

---

## 5. Agent Contract

### 5.1 Summary

The agent is the component of the runtime that bootstraps the system. It runs before application code, reads the wiring configuration, loads transports and observers, registers activators, and establishes connections. When the agent completes startup, the system is ready and application code may execute.

### 5.2 Startup Sequence

A conforming runtime MUST perform the following operations, in the following order, before application code executes:

1. **Install bytecode instrumentation**, if required by the implementation, to support contract proxy generation or context propagation
2. **Load the wiring configuration** from the location specified by the deployment
3. **Scan for component contracts** declared in the deployment
4. **Scan for activator descriptors** associated with component implementations present in the deployment
5. **Load transport implementations** from the transport plugin directory or equivalent mechanism
6. **Load observer implementations** from the observer plugin directory or equivalent mechanism
7. **Register activators** for components declared in the wiring configuration
8. **Process connections**: for each connection in the wiring configuration, either create a proxy (outbound) or start a listener (inbound)
9. **Signal readiness** — the runtime MUST emit a clear, observable signal that startup is complete before control is returned to application code

### 5.3 Configuration Properties

A conforming runtime MUST support the following configuration properties, expressed through whatever mechanism is idiomatic for the implementation platform (environment variables, system properties, command-line arguments, or equivalent):

| Property | Required | Description |
|----------|----------|-------------|
| `itara.config` | REQUIRED | Location of the wiring configuration file or resource |
| `itara.lib.dir` | OPTIONAL | Location of a directory from which transport, observer, and plugin artifacts are loaded |

Implementations MAY use platform-specific naming conventions for these properties provided the mapping is documented.

> **Java reference implementation:** These properties are passed as JVM system properties using the `-D` flag (e.g., `-Ditara.config=wiring.yaml`).

### 5.4 Plugin Loading

Transport and observer implementations are loaded from the location specified by `itara.lib.dir`. A conforming runtime MUST:

- Discover and load all plugin artifacts present in the specified location at startup
- Where the implementation platform permits, isolate the dependencies of loaded plugins from the application's own dependencies to prevent version conflicts
- Make loaded implementations available through their respective interfaces without requiring the application to declare them as direct dependencies

If `itara.lib.dir` is not specified, plugin implementations MUST be discoverable through the standard dependency resolution mechanism of the implementation platform.

> **Java reference implementation:** Plugins are jar files loaded from the specified directory using a child-first `URLClassLoader`, ensuring that plugin dependencies do not conflict with application dependencies. `itara-common` remains on the system classloader as the shared interface boundary.

### 5.5 Failure Handling

A conforming runtime MUST fail at startup — with a clear error message identifying the cause — if:

- The wiring configuration cannot be loaded or is malformed
- A connection references a component whose contract cannot be found
- A connection references a transport type for which no implementation is loaded
- A required activator cannot be instantiated

A conforming runtime MUST NOT start the application in a partially initialised state.

---

## 6. Transport Interface

### 6.1 Summary

A transport is a plugin that carries calls between components across a process boundary. A transport provides two capabilities: creating a proxy on the caller side, and starting a listener on the callee side. The transport is invisible to component code.

### 6.2 Transport Type Identifier

Every transport implementation MUST declare a type identifier — a non-empty string — that matches the type name used in connection declarations in the wiring configuration. Type identifiers are case-insensitive. The following type identifiers are reserved by this specification:

| Identifier | Meaning |
|------------|---------|
| `direct` | Collocated direct call — NOT a transport; handled natively by the runtime |
| `http` | HTTP-based transport |
| `jms` | JMS-based transport |
| `kafka` | Kafka-based transport |
| `grpc` | gRPC-based transport |

Implementations MAY define additional transport types using identifiers not listed above.

### 6.3 Transport Discovery

A conforming runtime MUST discover transport implementations via a descriptor mechanism that does not require modifying the runtime or the application. The reference implementation uses a file at `META-INF/itara/transport` within the transport jar, containing the fully qualified class name of the transport implementation. Other implementations MAY use equivalent mechanisms appropriate to their platform.

### 6.4 Proxy Creation

A transport MUST be capable of creating a proxy object that:

- Satisfies the component contract of the called component
- Delegates method calls to the remote implementation via the transport mechanism
- Is indistinguishable from the real implementation from the perspective of the calling component
- Propagates the current `ItaraContext` to the remote side as part of the call

### 6.5 Listener

A transport MUST be capable of starting a listener that:

- Receives inbound calls from remote callers via the transport mechanism
- Extracts and restores the `ItaraContext` propagated by the caller
- Dispatches the call to the local component implementation via the registry
- Returns the result or exception to the caller

### 6.6 Listener Lifecycle

A conforming runtime MUST stop all active listeners cleanly when the process terminates. Listeners MUST NOT prevent clean shutdown.

### 6.7 Transport Independence

A transport implementation MUST NOT:

- Require modification of any component contract or implementation
- Impose serialization constraints on method parameters or return types beyond what is inherent to the transport mechanism
- Require the calling component to be aware of the transport type used for a specific connection

---

## 7. Observer Interface

### 7.1 Summary

The observer interface is the mechanism by which the runtime reports component interactions to external systems for the purposes of monitoring, distributed tracing, and auditing. Observers are plugins loaded at startup. Multiple observers MAY be active simultaneously.

### 7.2 Observer Discovery

Observer implementations are discovered using the same mechanism as transports. The reference implementation uses `META-INF/itara/observer`.

### 7.3 Event Model

A conforming runtime MUST emit the following events for every component interaction, regardless of the transport type used:

| Event | Emitted by | When |
|-------|------------|------|
| `CALL_SENT` | Caller side (proxy) | Immediately before the call is dispatched |
| `CALL_RECEIVED` | Callee side (listener or direct handler) | Immediately upon receiving the call |
| `RETURN_SENT` | Callee side | Immediately before the response is returned |
| `RETURN_RECEIVED` | Caller side (proxy) | Immediately upon receiving the response |

**Clarification on "emit":** Emitting an event means invoking the registered observer implementations synchronously at the point indicated. It does not imply that the event has been delivered to any external monitoring system. Observer implementations are responsible for forwarding events to their respective backends (e.g., a metrics collector, a tracing system, an audit log). The runtime MUST NOT wait for external delivery to complete before continuing execution. Observer implementations MUST NOT block the call path with network operations or slow I/O (see section 7.6).

For direct (colocated) connections, all four events MUST be emitted. The runtime MUST NOT suppress any events for direct connections. The timing difference between `CALL_SENT` and `CALL_RECEIVED` for a direct connection will be zero or near-zero; this is expected and meaningful — it is the observable proof that colocation is functioning.

### 7.4 Event Payload

Every event MUST carry at minimum:

- The event type
- The current `ItaraContext` at the time of the event
- The component identifier of the component reporting the event
- The name of the method or operation being called
- A timestamp with nanosecond precision
- A boolean indicating whether the interaction resulted in an error
- If error: the error cause or equivalent

Events MAY additionally carry:

- A map of string labels for transport-specific or implementation-specific metadata
- Any additional fields defined by future versions of this specification

A conforming runtime MUST NOT include the call payload (method arguments or return value) in standard events. Payload capture is an application-level concern and is not part of the core event model. Custom observers MAY access payload data through transport-level interception if required.

### 7.5 Multiple Observers

A conforming runtime MUST support registering multiple observer implementations simultaneously. Events MUST be delivered to all registered observers. A failure in one observer MUST NOT prevent delivery to other observers.

### 7.6 Observer Independence

Observer implementations MUST NOT:

- Affect the outcome of component interactions
- Block or significantly delay the call path
- Require modification of any component contract or implementation

---

## 8. Context Propagation

### 8.1 Summary

Every request that enters the system is associated with a context object that travels with it through the entire call chain — within a process and across process boundaries. The context is managed by the runtime. Component code MAY read the current context but MUST NOT be required to manage it.

### 8.2 Context Fields

A conforming `ItaraContext` MUST carry at minimum:

| Field | Type | Description |
|-------|------|-------------|
| `requestId` | String | Unique identifier for the originating request |
| `correlationId` | String | Business-level identifier, optionally set by the entry point |
| `traceId` | String | Distributed trace identifier, propagated across process boundaries |
| `spanId` | String | Identifier for the current span within the trace |
| `parentSpanId` | String | Identifier of the caller's span (null for root) |
| `sourceNode` | String | Component identifier where the request originated |
| `edgePath` | List of Strings | Ordered list of component identifiers traversed by this request |

Implementations MAY extend the context with additional fields provided they do not conflict with the fields defined above.

### 8.3 Context Lifecycle

A conforming runtime MUST:

- Create a new `ItaraContext` when a request enters the system at an entry point that does not carry an existing context
- Restore an existing `ItaraContext` when a request arrives at a listener that carries context propagated from a caller
- Make the current context accessible to component code without requiring the component to manage it explicitly
- Clear the context when the request completes, whether successfully or with an error

Context MUST be cleared in all cases, including on exception. Failure to clear context in a thread pool environment will result in context leaking between requests. This is a conformance requirement.

### 8.4 Thread-Local Propagation

The reference implementation propagates context using thread-local storage. This is appropriate for synchronous, thread-per-request execution models.

**Known limitation:** Thread-local propagation does not function correctly with reactive programming frameworks (Project Reactor, RxJava, etc.) that switch threads between operations. Conforming implementations targeting reactive environments MUST provide an alternative propagation mechanism appropriate to the execution model.

Conforming implementations MUST document clearly which execution models are supported by their context propagation mechanism.

### 8.5 Cross-Process Propagation

When a transport dispatches a call to a remote component, the runtime MUST serialise the current `ItaraContext` and include it in the transport-level message. When the listener receives the message, it MUST deserialise the context and make it available as the current context for the duration of the call.

The serialisation format for cross-process context propagation is not prescribed by this specification. Implementations MUST document the format they use.

---

## 9. Conformance

### 9.1 Conformance Criteria

A runtime implementation is conforming if it satisfies all MUST and MUST NOT requirements in this specification.

An implementation that satisfies all MUST and MUST NOT requirements but does not satisfy one or more SHOULD requirements is conforming with noted deviations.

### 9.2 Reference Implementation

The Java implementation maintained at https://github.com/itara-project/itara is the reference implementation of this specification. Where this specification is ambiguous, the behaviour of the reference implementation is normative for that version of the specification.

### 9.3 Extensibility

This specification is intentionally minimal. Conforming implementations MAY provide capabilities beyond those specified here, provided those capabilities do not conflict with the requirements of this specification.

New transport types, observer backends, service discovery mechanisms, and deployment tooling are explicitly outside the scope of this specification and are encouraged as independent contributions.

### 9.4 Versioning

This document is a Draft. It will be assigned a version number when the core team determines the specification is stable enough to build against. Until that time, implementations SHOULD treat any requirement as subject to change and SHOULD track the latest draft.

---

## Appendix A: Open Questions

The following design questions have not been resolved as of this draft. They are collected here for reference during discussion.

**A.1** Should the observer interface use a single `onEvent(ItaraEvent)` method, separate methods per event type, or default methods per event type? See the observability design discussion issue for options and tradeoffs.

**A.2** How should context be created at the system entry point? Who is responsible for generating `requestId` and `traceId` when no incoming context is present?

**A.3** Should multiple observers be ordered (i.e., guaranteed delivery sequence) or unordered?

**A.4** Should the specification define a minimum serialisation format for cross-process context propagation to enable interoperability between implementations in different languages?

**A.5** How should the specification address component versioning — the case where multiple versions of the same contract exist simultaneously?

**A.6** Service discovery — how should a conforming runtime resolve logical component identifiers to physical network addresses at startup? Options include static configuration (host and port in the wiring config), DNS-based resolution (e.g., Kubernetes service DNS), registry-based resolution (e.g., Consul), and platform-native discovery. The transport SPI provides an extension point but the interface for a service discovery plugin has not been defined. This is an open design question, not a non-goal — Itara will provide and require a service discovery mechanism. The question is how it is expressed and plugged in.

---

## Appendix B: Non-Goals

The following are explicitly outside the scope of this specification:

- Topology optimisation or intelligent topology management (this is the domain of the controller, which is a separate product)
- Security and authentication between components (out of scope for this version)
- Schema evolution and backward compatibility of component contracts
- Deployment tooling, orchestration, or container management
- Any controller, dashboard, or visualisation tooling

---

*End of Itara Runtime Specification — Draft*
