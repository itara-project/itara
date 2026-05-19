# Itara Specification

**Status:** Draft  
**Version:** 0.1-draft  
**Repository:** https://github.com/itara-project/itara  
**License:** Apache 2.0

---

## Abstract

This document specifies Itara — a platform for building distributed software systems in which the communication topology between components is a declarative configuration decision, not a code decision. A conforming Itara implementation allows component authors to define business logic independently of how that logic is connected to the rest of the system. The topology — whether two components communicate directly within a single process, over HTTP, over a message broker, or any other mechanism — is expressed in a wiring configuration and applied at startup without modifying component code.

**Pronunciation:** Itara is pronounced *ee-tah-rah* — each vowel as in Latin or Hungarian: the *i* as in *machine*, the *a* as in *father*. Not *eye-tara*.

This specification defines the component model, the wiring model, the agent contract, the transport interface, the serializer interface, the observer interface, and the context propagation model. It does not prescribe any particular implementation language, framework, or deployment mechanism. The Java implementation maintained by the Itara project is the primary reference implementation. A Rust implementation is maintained alongside Java and serves as the reference for native language implementations. Conforming implementations MAY be built in any language or runtime environment.

---

## Status of This Document

This is a **Draft** specification. It reflects the current design of the reference implementations and the decisions made by the core team as of the date of this document. Sections marked **[OPEN]** contain unresolved design questions. The specification SHOULD NOT be considered stable. Breaking changes are possible before a versioned release is published.

Feedback, objections, and proposals are welcome as GitHub issues on the Itara repository.

---

## Table of Contents

1. Vision and Values
2. Terminology
3. Component Model
4. Wiring Model
5. Agent Contract
6. Transport Interface
7. Serializer Interface
8. Observer Interface
9. Context Propagation
10. Conformance

---

## 1. Vision and Values

### 1.1 Summary for Architects

Itara separates two concerns that software systems have historically conflated: **what a component does** and **how it communicates with other components**. A component author defines a contract — an interface — and an implementation. A system architect defines the topology — which components communicate, over which mechanism, in which deployment configuration — in a wiring configuration file. The Itara agent enforces the topology at startup and makes it transparent to both sides.

The result is a system where topology is cheap to change. Moving from a colocated deployment to a distributed one, or from HTTP to a message broker, requires a configuration change, not a code change. This makes architectural decisions reversible — a property that most distributed systems lack entirely.

### 1.2 Core Values

The following values inform every design decision in this specification. Implementations that conflict with these values are not conforming, even if they satisfy the letter of the normative requirements.

**Topology is declarative, never coded.**  
A component implementation MUST NOT be required to contain knowledge of how it is connected to other components. Connection mechanism, transport protocol, and deployment topology are expressed in configuration and applied by the agent. Itara lifts the burden of topology from the component author — it does not prescribe how component code is written.

**Semantics are explicit. Mechanics are abstracted.**  
Component contracts express what is communicated. The wiring configuration expresses how components are connected. Itara does not require these concerns to be mixed, and a conforming implementation MUST NOT force or encourage component authors to encode transport or topology concerns in their contracts or implementations.

**Colocation is as close to zero-overhead as the runtime environment allows.**  
When two components are colocated in the same process and share the same type system, a direct connection MUST be dispatched as a direct method call with no intermediate serialisation or network hop. The agent resolves a proxy at startup — the proxy fires the required observability events and then calls the implementation directly. At call time, the only overhead beyond a plain method call is the four observability events, which are structural properties of the platform and cannot be removed.

For components colocated on the same host but in separate processes or runtimes — for example, a JVM component and a Rust component — the wiring configuration MUST declare the local IPC mechanism to use. The agent follows the configuration exactly. It does not select or substitute a mechanism autonomously.

**Remote calls are transparent but observable.**  
A component implementation MUST NOT need to know whether it is calling another component directly or over a network. The call looks identical from the caller's perspective. The agent MUST emit observable events for every interaction, regardless of transport type, so that the topology can be monitored, traced, and audited.

**Components are defined by contracts, not frameworks.**  
A component contract is expressed as an interface or trait in the implementation language. No heavy framework dependencies, no manually written infrastructure code, no framework-managed lifecycle — beyond the minimal declarations required to signal to the agent that an interface is an Itara contract — are required or expected. The minimal declaration MAY be an annotation, a marker trait, a macro, a naming convention, or a metadata file entry, as appropriate to the implementation language.

**Refactoring is cheap.**  
The architecture of the system MUST remain easy to change. Topology decisions made at the start of a project SHOULD be reversible at any point in the project's lifecycle without requiring code changes to component implementations.

**Language neutrality is a core value.**  
This specification is intentionally language-agnostic. The component model, wiring model, and interface contracts SHOULD be implementable in any language that can be compiled or interpreted on its target platform.

**The implementation is pluggable.**  
Transport mechanisms, serializers, observability backends, and service discovery strategies are plugins, not built-in concerns. The agent provides stable interfaces. Implementations are discovered from a designated artifact directory and loaded at startup.

**Auditability is a first-class property.**  
Every component interaction is observable and auditable by design. A conforming implementation MUST emit events for every call, regardless of transport type or deployment topology. Systems built on Itara MUST be auditable without additional instrumentation by the component author.

**Software must become soft again.**  
Topology decisions should be cheap to change. A system whose topology cannot be modified without code changes is not soft — it is rigid. Itara exists to make topology soft.

---

## 2. Terminology

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHALL**, **SHALL NOT**, **SHOULD**, **SHOULD NOT**, **RECOMMENDED**, **MAY**, and **OPTIONAL** in this document are to be interpreted as described in [RFC 2119](https://www.ietf.org/rfc/rfc2119.txt).

**Component**  
A unit of business logic with a defined contract and a concrete implementation. A component has an identifier that is unique within a deployment.

**Contract**  
An interface or trait that defines the operations a component exposes. A contract MUST NOT contain any knowledge of transport mechanism, deployment topology, or runtime infrastructure.

**Implementation**  
A concrete class or struct that satisfies a contract. An implementation MUST NOT contain any knowledge of how it is connected to other components.

**Activator**  
A factory responsible for creating an instance of a component implementation. The activator is the single point where an implementation is instantiated and its dependencies are resolved from the registry.

**Node**  
A deployment identity declared in the wiring configuration. A node has an identifier and references a component. Multiple nodes may reference the same component — they are independent deployment positions in the topology. The number of running instances of a node is an orchestrator concern, not an Itara concern.

**Wiring Configuration**  
A declarative description of the nodes present in a deployment and the connections between them. The wiring configuration is the sole source of topology information. It is consumed by the agent at startup. A master wiring configuration describes the complete topology of a system. Each agent instance self-selects the relevant slice based on which nodes it is responsible for.

**Connection**  
A directed relationship between two nodes expressing that one node (the caller) communicates with another node (the callee) over a specified transport type.

**Transport**  
A pluggable mechanism for carrying calls between components. A transport provides both the outbound proxy (for the caller) and the inbound listener (for the callee). Transports are loaded by the agent at startup and are invisible to component code.

**Serializer**  
A pluggable mechanism for converting typed method arguments and return values to and from byte arrays. Serializers are loaded by the agent at startup. A serializer is selected per connection in the wiring configuration.

**Proxy**  
An object that satisfies a component contract but delegates calls to a remote or local implementation via the agent-resolved mechanism. A proxy is created by the agent and registered in the component registry. Component code interacts with the proxy as if it were the real implementation. For direct connections, the proxy fires observability events and then calls the implementation directly.

**Listener**  
A transport-specific server that receives inbound calls and dispatches them to the local component implementation via the component registry.

**Registry**  
The agent's store of component instances and proxies, keyed by component identifier. The registry is the mechanism by which one component obtains a reference to another.

**Agent**  
The component of the implementation that reads the wiring configuration, loads plugins, constructs proxies and listeners, and registers activators. The agent initialises the system before application code executes. In Java this is a JVM premain agent. In Rust this is an `itara_init()` library call. Other languages follow the same pattern.

**Observer**  
A pluggable receiver of runtime events. Observers record, export, or react to component interactions for the purposes of monitoring, tracing, and auditing.

**Context**  
A set of metadata associated with a single request as it travels through the system. Context is propagated automatically by the agent across component boundaries, both within a process and across network hops.

**Plugin artifact**  
A loadable artifact (jar, shared library, or equivalent) that implements one of the Itara SPIs (transport, serializer, observer). Each plugin artifact MUST ship a companion `.itara` metadata file that describes its kind, identifier, version, and compatibility requirements.

---

## 3. Component Model

### 3.1 Summary

A component is a named unit of business logic. It has a contract — an interface or trait describing what it can do — and an implementation that does it. The agent connects components to each other according to the wiring configuration. Neither the contract nor the implementation contains any knowledge of this connection.

### 3.2 Component Identifier

Every component MUST have an identifier that is:

- A non-empty string
- Unique within the deployment described by a wiring configuration
- Stable across restarts

The component identifier is used in the wiring configuration to reference the component via nodes. It is also used in runtime events emitted by the observer interface.

### 3.3 Contract

A component contract MUST be expressed as an interface or trait in the implementation language. The contract MUST NOT:

- Contain parameters or return types that encode transport-specific concerns
- Contain lifecycle methods managed by the Itara agent

The contract MAY extend, implement, or inherit from Itara-provided marker types where required by the implementation language's type system. For example, in Rust the `ItaraComponent` marker trait must be implemented to enable type-erased registry storage. Such requirements are implementation-language-specific and do not represent a violation of the contract model — they are signals to the agent, not framework dependencies.

The contract MUST be compilable and usable independently of any transport or serializer dependency. It MUST be possible to create an instance of a class or struct implementing the contract and call its methods without the agent being present.

A conforming implementation MUST be able to generate a proxy that satisfies any contract, subject to the constraints of the implementation language's type system.

#### 3.3.1 Contract Declaration

A conforming implementation MUST provide a mechanism to associate a contract interface with a component identifier. Acceptable mechanisms include:

- An annotation on the interface (Java reference implementation: `@ComponentInterface`)
- A marker trait or supertrait (Rust implementation: `ItaraComponent`)
- A generated macro applied to the interface (planned: `#[itara_component]`)
- A metadata file or naming convention

The mechanism MUST NOT require modifying the interface's method signatures or introducing transport-specific types into the contract.

### 3.4 Implementation

A component implementation MUST:

- Satisfy the component contract
- Be instantiable by the activator

A component implementation MUST NOT:

- Reference the component registry directly — dependencies are obtained by the activator, not the implementation
- Contain knowledge of which transport will be used to deliver calls to it
- Contain knowledge of which transport will be used when it calls another component

### 3.5 Activator

An activator is a factory that creates a component implementation instance. The agent invokes the activator when a component instance is first needed. The activator receives a reference to the component registry and MAY use it to obtain references to other components required by the implementation.

An activator MUST:

- Be discoverable by the agent from the component artifact or its companion `.itara` metadata file
- Be invokable by the agent with access to the registry as its only external dependency
- Return an instance that satisfies the component contract

An activator MAY be lazy — instantiated only when the component is first requested from the registry — or eager — instantiated at startup. Conforming implementations MUST support lazy activation. Support for eager activation is OPTIONAL.

---

## 4. Wiring Model

### 4.1 Summary

The wiring configuration is a directed graph. Nodes are deployment identities that reference components. Edges are connections between nodes. The configuration is read by the agent at startup. It is the only place where topology is expressed. No component code reads or interprets the wiring configuration.

### 4.2 Configuration Format

The wiring configuration format is not prescribed by this specification. The reference implementation uses YAML. Conforming implementations MAY use any format capable of expressing the data model defined in this section, provided the format is:

- Human-readable
- Stored outside of compiled component artifacts
- Loadable at startup without recompilation
- Supportive of environment variable substitution using the syntax `${VAR_NAME:-default_value}`

### 4.3 Node Declarations

The wiring configuration MUST declare nodes using the following structure:

```yaml
nodes:
  - id: "calculatorNode"      # node identity — unique in this deployment
    component: "calculator"   # component identity — references the implementation
```

The agent uses the node identifier to filter which parts of the wiring configuration are relevant to a given process. It uses the component identifier to locate the activator and register the implementation.

### 4.4 Connection Declarations

A connection declaration MUST include:

- The identifier of the calling node (`from`)
- The identifier of the called node (`to`)
- The transport type

The `from` field MAY be absent or empty, indicating that the caller is external to this topology — the connection defines an inbound entry point for the `to` node.

A connection declaration MAY include:

- Transport-specific parameters (host, port, topic name, queue name, etc.)
- Serializer selection

```yaml
connections:
  - from: "gatewayNode"
    to: "calculatorNode"
    type: http
    host: "${CALC_HOST:-localhost}"
    port: 8081
    serializer: "json"

  - from:                     # absent = external caller
    to: "gatewayNode"
    type: http
    port: 8082
    serializer: "json"
```

### 4.5 Master Configuration and Agent Slicing

A master wiring configuration describes the complete topology of a system — all nodes and all connections. Each agent instance reads the master configuration and self-selects the relevant slice based on which node identifiers it is responsible for. The agent determines its responsibilities from an external declaration (environment variable, system property, or equivalent) at startup.

A conforming implementation MUST support master configuration files. The agent MUST filter the configuration to the relevant nodes and connections without modifying the configuration file.

### 4.6 Connection Semantics

#### 4.6.1 Direct Connections

A direct connection declares that the calling node and the called node are colocated and that the agent MUST use a direct method call.

For components sharing the same process and type system, a direct connection MUST be dispatched through a proxy that fires observability events and then calls the implementation directly — no serialisation, no network hop. A conforming implementation MUST guarantee that such a connection introduces no overhead beyond the four observability events, which are a mandatory structural property of the platform.

For components colocated on the same host but in separate processes or runtimes, the wiring configuration MUST declare the local IPC mechanism. The agent uses exactly the mechanism declared.

#### 4.6.2 Transport Connections

A transport connection declares that the calling node and the called node communicate via a named transport. The transport type MUST correspond to a transport implementation loaded by the agent.

For a transport connection, the agent MUST:

- On the caller side: create a proxy that satisfies the contract and delegates calls through the serializer and transport
- On the callee side: start a listener that receives calls from the transport, passes them through the serializer, and dispatches them to the local implementation via the registry

#### 4.6.3 Inbound External Connections

When a connection has no `from` node, the agent MUST start a listener that accepts calls from external callers not managed by this agent instance. The listener is otherwise identical to a transport connection callee listener.

### 4.7 Multiple Connections to a Single Node

A conforming implementation MUST support a node being the target of connections of different transport types simultaneously. The agent MUST start a listener for each declared inbound connection and route all inbound calls to the same component instance via the registry.

---

## 5. Agent Contract

### 5.1 Summary

The agent bootstraps the system. It runs before application code, reads the wiring configuration, loads plugins, registers activators, and establishes connections. When the agent completes startup, the system is ready and application code may execute.

### 5.2 Startup Sequence

A conforming implementation MUST perform the following operations, in the following order, before application code executes:

1. **Load the wiring configuration** from the location specified by the deployment
2. **Determine local nodes** — which nodes this agent instance is responsible for
3. **Scan the plugin artifact directory** — read `.itara` metadata files before loading any artifact
4. **Load required plugin artifacts** — transports, serializers, observers required by the wiring configuration
5. **Register activators** for local components
6. **Process connections**: for each connection involving a local node:
   - Caller side (local → remote): create a proxy wrapping the transport and serializer, preregister in the registry
   - Callee side (remote → local): start an inbound listener
   - Both local: register activator for direct call
7. **Freeze the registry** — no further registration is permitted after this point
8. **Signal readiness** — a clear, observable log message MUST be emitted before control returns to application code

### 5.3 Configuration Properties

A conforming implementation MUST support the following configuration properties:

| Property | Required | Description |
|----------|----------|-------------|
| `itara.config` / `ITARA_CONFIG` | REQUIRED | Location of the wiring configuration file |
| `itara.nodes` / `ITARA_NODES` | REQUIRED | Comma-separated list of node identifiers this agent instance is responsible for |
| `itara.lib.dir` / `ITARA_LIB_DIR` | OPTIONAL | Directory from which plugin artifacts are loaded |

Implementations MAY use platform-specific naming conventions (system properties for Java, environment variables for Rust and others).

### 5.4 Plugin Loading

All SPI implementations — transports, serializers, observers, and future plugin types — are loaded from the plugin artifact directory. A conforming implementation MUST:

- Read all `.itara` metadata files in the directory before loading any artifact
- Load only the artifacts required by the wiring configuration
- Where the platform permits, isolate plugin dependencies from application dependencies
- Make loaded implementations available through their respective SPI interfaces

If the plugin directory is not specified, implementations MUST fall back to default implementations where available (e.g., a built-in JSON serializer, a built-in HTTP transport).

### 5.5 Failure Handling

A conforming implementation MUST fail at startup — with a clear error message — if:

- The wiring configuration cannot be loaded or is malformed
- A connection references a transport type for which no implementation is loaded
- A required activator cannot be instantiated
- A declared node has no corresponding component

A conforming implementation MUST NOT start the application in a partially initialised state.

---

## 6. Transport Interface

### 6.1 Summary

A transport is a plugin that carries serialized bytes between components across a process boundary. A transport provides two capabilities: sending bytes on the caller side, and receiving bytes on the callee side. The transport is invisible to component code. It does not know about component contracts, method signatures, or serialization formats.

### 6.2 Transport Type Identifier

Every transport implementation MUST declare a type identifier — a non-empty string — that matches the type name used in connection declarations in the wiring configuration. Type identifiers are case-insensitive. The following type identifiers are reserved:

| Identifier | Meaning |
|------------|---------|
| `direct` | Collocated direct call — not a transport; handled natively by the agent |
| `http` | HTTP-based transport |
| `kafka` | Kafka-based transport |
| `grpc` | gRPC-based transport |

Implementations MAY define additional transport types.

### 6.3 Plugin Discovery

Transport implementations are discovered via their companion `.itara` metadata file, which MUST declare `kind = "transport"`. The agent reads this file before loading the artifact. The transport artifact MUST export a factory symbol (`itara_transport_factory` for native implementations, or equivalent for managed runtimes) that the agent calls to obtain a configured transport instance.

### 6.4 Caller Side

A transport MUST be capable of:

- Sending a byte payload to a remote callee identified by component identifier and method name
- Propagating the current `ItaraContext` as part of the call (via `ItaraHeaders` or equivalent)
- Returning the response byte payload to the caller

The transport receives serialized bytes from the serializer. It does not perform serialization itself.

### 6.5 Callee Side (Listener)

A transport MUST be capable of starting a listener that:

- Receives inbound byte payloads from remote callers
- Extracts and restores the `ItaraContext` propagated by the caller
- Dispatches the byte payload to a registered `Dispatcher` for the component
- Returns the response byte payload to the transport for transmission back to the caller

### 6.6 Listener Lifecycle

A conforming implementation MUST stop all active listeners cleanly when the process terminates.

### 6.7 Transport Independence

A transport implementation MUST NOT:

- Require modification of any component contract or implementation
- Perform serialization or deserialization of method arguments or return values
- Require the calling component to be aware of the transport type used for a specific connection

---

## 7. Serializer Interface

### 7.1 Summary

A serializer is a plugin that converts typed method arguments and return values to and from byte arrays. Serializers operate at the boundary between component code and the transport layer. Neither the component code nor the transport knows about the serializer — the agent wires them together.

### 7.2 Serializer Type Identifier

Every serializer implementation MUST declare a type identifier. The following are reserved:

| Identifier | Meaning |
|------------|---------|
| `json` | JSON serialization. Default if not specified. |
| `java` | Java object serialization. JVM-only. Legacy opt-in. |
| `protobuf` | Protocol Buffers serialization. |

### 7.3 Plugin Discovery

Serializer implementations are discovered via their companion `.itara` metadata file, which MUST declare `kind = "serializer"`.

### 7.4 Serializer Contract

A serializer MUST be capable of:

- Serializing method arguments to a byte array
- Deserializing a byte array back to typed method arguments
- Serializing a return value to a byte array
- Deserializing a byte array back to a typed return value
- Declaring its content type (e.g. `application/json`, `application/octet-stream`)

The content type declared by the serializer MUST be propagated to the transport via `ItaraHeaders` so the transport can set appropriate protocol-level metadata.

### 7.5 Serializer Independence

A serializer implementation MUST NOT:

- Perform network operations
- Know about transport mechanisms
- Require modification of any component contract or implementation

---

## 8. Observer Interface

### 8.1 Summary

The observer interface is the mechanism by which the agent reports component interactions to external systems for monitoring, distributed tracing, and auditing. Observers are plugins loaded at startup. Multiple observers MAY be active simultaneously.

### 8.2 Plugin Discovery

Observer implementations are discovered via their companion `.itara` metadata file, which MUST declare `kind = "observer"`.

### 8.3 Event Model

A conforming implementation MUST emit the following four events for every component interaction, regardless of transport type. The placement of events relative to serialization is normative:

| Event | Emitted by | Fires |
|-------|------------|-------|
| `CALL_SENT` | Caller proxy | Before serialization of arguments |
| `CALL_RECEIVED` | Callee dispatcher | After deserialization of arguments |
| `RETURN_SENT` | Callee dispatcher | Before serialization of the return value |
| `RETURN_RECEIVED` | Caller proxy | After deserialization of the return value |

Everything transport-related — serialization, network transmission, deserialization — happens between these events. This placement is intentional:

- The **caller span** (CALL_SENT → RETURN_RECEIVED) measures the full round trip from the caller's perspective, including all serialization and transport cost
- The **callee span** (CALL_RECEIVED → RETURN_SENT) measures pure component processing time, independent of serialization format or transport
- Serialization cost is directly measurable as the gap between CALL_SENT and bytes leaving the process, and between bytes arriving and CALL_RECEIVED
- The agent's own overhead is transparent and measurable — it is never hidden from operators

For direct (colocated) connections, the proxy fires all four events and then calls the implementation directly. The component implementation fires no events. All four events MUST be emitted for direct connections — they provide the observable proof that colocation is functioning and that the observability guarantee holds regardless of transport type.

**Clarification on "emit":** Emitting an event means invoking the registered observer implementations at the point indicated. Observers are responsible for forwarding events to external backends. The agent MUST NOT wait for external delivery before continuing execution.

### 8.4 Event Payload

Every event MUST carry at minimum:

- The event type
- The current `ItaraContext` at the time of the event
- The component identifier of the component reporting the event
- The name of the method or operation being called
- A timestamp with at least millisecond precision
- Whether the interaction resulted in an error, and if so the error cause

### 8.5 Multiple Observers

A conforming implementation MUST support registering multiple observer implementations simultaneously. A failure in one observer MUST NOT prevent delivery to other observers.

### 8.6 Observer Independence

Observer implementations MUST NOT affect the outcome of component interactions or significantly delay the call path.

---

## 9. Context Propagation

### 9.1 Summary

Every request that enters the system is associated with a context object that travels with it through the entire call chain — within a process and across process boundaries. The context is managed by the agent. Component code MAY read the current context but MUST NOT be required to manage it.

### 9.2 Context Fields

A conforming `ItaraContext` MUST carry at minimum:

| Field | Type | Description |
|-------|------|-------------|
| `requestId` | String | Unique identifier for the originating request |
| `correlationId` | String | Business-level identifier, optionally set by the entry point |
| `traceId` | String | Distributed trace identifier, propagated across process boundaries |
| `spanId` | String | Identifier for the current span within the trace |
| `parentSpanId` | String | Identifier of the caller's span (null for root) |
| `sourceNode` | String | Node identifier where the request originated |
| `edgePath` | List of Strings | Ordered list of node identifiers traversed by this request |

### 9.3 Context Lifecycle

A conforming implementation MUST:

- Create a new `ItaraContext` when a request enters the system with no existing context
- Restore an existing `ItaraContext` when a request arrives carrying context propagated from a caller
- Make the current context accessible to component code without requiring the component to manage it explicitly
- Clear the context when the request completes, including on exception

### 9.4 Cross-Process Propagation

When a transport dispatches a call to a remote component, the agent MUST serialise the current `ItaraContext` and include it in the transport-level message via `ItaraHeaders`. When the listener receives the message, it MUST deserialise the context and make it available for the duration of the call.

The reference implementation uses W3C `traceparent` and `tracestate` headers for HTTP transport. Itara-specific fields are Base64-encoded into the `tracestate` header. Implementations SHOULD follow this convention for HTTP transports to enable interoperability.

### 9.5 Thread and Execution Model

The reference implementation propagates context using thread-local storage. Conforming implementations targeting reactive or async execution models MUST provide an alternative propagation mechanism appropriate to the execution model and MUST document which execution models are supported.

---

## 10. Conformance

### 10.1 Conformance Criteria

A implementation is conforming if it satisfies all MUST and MUST NOT requirements in this specification.

An implementation that satisfies all MUST and MUST NOT requirements but does not satisfy one or more SHOULD requirements is conforming with noted deviations.

### 10.2 Reference Implementations

The Java implementation maintained at https://github.com/itara-project/itara is the primary reference implementation. The Rust implementation in the same repository is the reference for native language implementations. Where this specification is ambiguous, the behaviour of the Java reference implementation is normative. Where the Java and Rust implementations disagree, the discrepancy is treated as a specification gap and resolved via the issue tracker.

### 10.3 Extensibility

Conforming implementations MAY provide capabilities beyond those specified here, provided those capabilities do not conflict with the requirements of this specification.

New transport types, serializer formats, observer backends, and service discovery mechanisms are explicitly encouraged as independent contributions.

### 10.4 Versioning

This document is a Draft. It will be assigned a stable version number when the core team determines the specification is stable enough to build against.

---

## Appendix A: Resolved Decisions

The following questions were open in earlier drafts and have since been resolved.

**A.1 Observer interface shape**  
Resolved: separate default methods per event type — `onCallSent`, `onCallReceived`, `onReturnSent`, `onReturnReceived`. All methods have default no-op implementations so implementors override only the events they care about. Timestamps are provided by the observability facade at fire time so all observers receive the same value for the same event. See ADR 0003.

**A.2 Context creation at entry point**  
Resolved: the agent creates a new `ItaraContext` at inbound entry points where no context is present. The agent generates `requestId`. `traceId` is generated by the OTel bridge when present, or by the agent's built-in context generator otherwise. See ADR 0004.

**A.5 Component versioning**  
Resolved: component and API versions are declared in the `.itara` metadata file. The agent reads metadata before loading any artifact. External tooling uses metadata for compatibility checking and deployment validation. See ADR 0008.

**A.6 Service discovery**  
Resolved as an SPI. Service discovery is a pluggable plugin type, discoverable via `.itara` metadata files with `kind = "discovery"`. Different transports and environments require different discovery mechanisms — Consul, Kubernetes DNS, Kafka topic registries, or others. The discovery SPI interface will be defined in a future version of this specification.

---

## Appendix B: Non-Goals

The following are explicitly outside the scope of this specification:

- Topology optimisation or intelligent topology management (the domain of the controller, a separate product)
- Security and authentication between components
- Schema evolution and backward compatibility of component contracts
- Deployment tooling, orchestration, or container management
- Any controller, dashboard, or visualisation tooling

---

*End of Itara Specification — Draft 0.1*
