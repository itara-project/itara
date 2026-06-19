# Spike Findings — Event-Driven Topology (Spec §13)

**Spike:** `feature/event-driven`  
**Spec section:** §13  
**Status:** Complete — CI passing, demo running end-to-end with logging and OTel observers  
**Follow-up issues:** see sections marked _→ issue_

---

## Spike questions answered

### Is a new proxy type needed?

No. The existing `ItaraProxyHandler` is reused unchanged for the producer side.
The `ExchangePattern` field carries the sync/async distinction cleanly — simple
branching takes precedence over composition or inheritance for now, as it is
easier to read, debug, and maintain. This can be revisited if the proxy grows
more exchange-specific behaviour.

### Is a new transport SPI needed?

No. `ItaraTransport` is untouched. Kafka fits the existing byte-carrier shape:
`send()` blocks until broker ack and returns empty bytes, which maps correctly
to the void return type of event contract methods. The transport layer remained
entirely outside the sync/async story — it moves bytes and nothing else. This
is a good sign for the failure semantics work ahead: the transport layer will
be transparent there too.

### How is `ItaraContext` serialised into and deserialised from message headers?

The existing `ContextPropagation.toHeaders()` / `fromHeaders()` mechanism is
reused. `fromHeaders()` gained an `ExchangePattern` parameter (not an overload — no
external implementations exist yet, so a direct signature change was cleaner):
for `FIRE_AND_FORGET`, the restored context preserves `itaraTraceId` but
generates a fresh `spanId` and sets `parentSpanId` to null, satisfying spec §13.5.
The Kafka transport carries routing information (`x-itara-component-id`,
`x-itara-method-name`) alongside the standard Itara context headers.

### How does the consumer-side agent discover which contract method to dispatch to?

Via two dedicated Kafka message headers set by the producer transport:
- `x-itara-component-id` — the target component id (the event contract
  reference, e.g. `order-events/order-placed`)
- `x-itara-method-name` — the target method name (e.g. `onOrderPlaced`)

The consumer poll loop extracts these headers and calls
`dispatcher.dispatch(componentId, methodName, payload, headers)` — the same
`DispatchHandler` interface used by the HTTP transport.

---

## Design decisions made

### Virtual node placement in the wiring config

Virtual nodes are declared as a separate `virtualNodes` list at the root level
of the wiring config, alongside the existing `nodes` list. This works and is
clean for the current two-node-type world.

However, as additional node types are introduced, a flat list-per-type
structure may not scale well. A more structured approach — closer to a typed
node hierarchy — may be worth considering. The current structure is intentional
for the spike and should be revisited when the wiring config model is next
reviewed.

_→ open a follow-up issue: wiring config node type structure_

### Mandatory API artifact for all components, including event consumers

Every component requires a dedicated API artifact with a `@ComponentInterface`-
annotated interface, even if that interface is empty. This applies to
event consumer components — they must have an API even if they expose no
callable methods of their own. The `@ComponentInterface` class doc was updated
to reflect that contracts are interfaces, not abstract classes.

This is accepted as a rule for now. It keeps the component model uniform and
avoids special cases in the agent startup logic. It also has an evangelisation
benefit: the structure is consistent and predictable regardless of component
role. Synthetic API generation is a possible future improvement.

### Registry aliasing for event contract dispatch

The registry gained an alias mechanism: `registerAlias(contractId, componentId)`
maps an event contract id (e.g. `order-events/order-placed`) to the component
id of the consumer activator (e.g. `order-consumer`). This allows the
dispatcher to look up the consumer implementation by contract id while
preserving lazy activation and single instantiation.

The alias is registered before `startListener()` is called to avoid a race
condition on the first incoming message.

### `ExchangePattern` as the carrier of sync/async semantics

`ExchangePattern` (`REQUEST_REPLY` / `FIRE_AND_FORGET`) is set once at startup
on both `ItaraProxyHandler` (producer side) and `ItaraDispatcher` (consumer
side). It flows through to `ContextPropagation.fromHeaders()`,
`ObservabilityFacade`, and the observer SPI (`onCallSent`, `onCallReceived`,
`restoreContext`). Everything that behaves differently between sync and async —
span relationships, OTel span kinds, future failure semantics — branches on
this single value. The information describes the circumstance; receivers decide
what to do with it.

Adding `ExchangePattern` to `onCallSent` and `onCallReceived` in `ItaraObserver`
is a breaking SPI change. Observer implementations outside this repo must be
updated.

_→ communicate breaking change clearly in release notes_

### `META-INF/itara/event-contract` discovery

Event contract interfaces are discovered via `META-INF/itara/event-contract`
descriptor files — one fully qualified class name per line. This spike also
introduced `META-INF/itara/contract` descriptor files for component contract
interfaces, replacing the previous classpath scan in `ContractScanner`. The
classpath scan was inefficient and had potential side effects (loading classes
it had no business loading); the descriptor-based approach is consistent with
the existing `META-INF/itara/activator` mechanism and the broader Itara SPI
pattern. The collection id for event contracts is resolved from the events
artifact's `.itara` metadata file via codesource, same pattern as
`ActivatorScanner`.

Descriptor files are hand-authored for the spike. The `itara-processor`
annotation processor will generate them automatically at build time.

_→ open a follow-up issue: itara-processor annotation processor_

The processor design decision: a base annotation class carries the minimum
information needed to determine the descriptor filename and content, so third
parties can add their own descriptor types without modifying the processor.

---

## Known rough edges and deferred work

### Agent code review after virtual node placement is settled

The agent startup loop handles virtual node connections correctly but the code
could use a review pass once the final structure of virtual nodes in the wiring
config is decided. The current implementation is correct per the spec but the
branching logic in the connection processor will benefit from a cleanup.

_→ review `ItaraAgent` connection processing after wiring config node type decision_

### `ItaraActivator<T>` strain under event-driven model

`ItaraActivator<T>` was designed for the one-component-one-contract world. A
consumer component that handles multiple event contracts exposes the N:1 mapping
problem: one activator, multiple contract ids. The alias mechanism is a working
patch but not a principled solution. A richer activator interface or a
multi-contract activator concept is needed before this pattern scales.

_→ open a follow-up issue: multi-contract activator model_

### Transport-specific config on `ConnectionEntry`

`bootstrapServers` and `consumerGroup` are currently added as optional fields
on `ConnectionEntry`, consistent with how `host` and `port` work today. A
generic transport-config map (`Map<String, String> transportConfig`) was
deferred deliberately. This should be the next step for the connection model,
especially as additional async transports follow Kafka's shape.

_→ open a follow-up issue: generic transport config map on ConnectionEntry_

### Classloader context requirement for transport implementations

The Kafka transport required `Thread.currentThread().setContextClassLoader()`
around `KafkaProducer` and `KafkaConsumer` construction because Kafka's internal
`Class.forName()` calls use the thread context classloader, which at premain
time is the system classloader rather than the Itara child-first classloader.

This is a pattern every future transport author will need to know about. It
should be documented as a known transport implementation requirement and
potentially abstracted into a helper in the transport SPI base.

_→ open a follow-up issue: transport author guide / classloader helper_

### `HttpTransport.stopListener()` only stops the last listener

A pre-existing bug surfaced during the spike: when two component nodes are
colocated and both have external inbound HTTP connections on different ports,
`stopListener()` only stops the last one because `activeServer` is a single
field. The first server leaks on shutdown. Both ports do listen correctly
because `HttpServer.create()` binds the socket at construction time.

This will be addressed during the pluggable HTTP server and service discovery
SPI work already on the roadmap.

_→ existing known issue, tracked separately_

### OTel and async traces

The OTel observer produces correct output for `FIRE_AND_FORGET` — `PRODUCER`
span kind on the producer side, `CONSUMER` span kind on the consumer side, no
parent span on the consumer, `SpanLink` connecting the two spans — but OTel
backends (Kibana, Jaeger, Tempo) render linked spans as separate traces rather
than one unified view. The Itara trace id is consistent across both sides and
is set as a span attribute (`itara.trace.id`), allowing cross-side correlation
by searching.

This is technically correct but not the user experience people expect from
distributed tracing. The root cause is that async is an afterthought in OTel's
data model — `SpanLink` is a second-class citizen in most backends. Two paths
forward worth considering:

1. Accept the two-trace representation and document it. Use the Itara trace id
   for async correlation. Keep OTel for sync topologies in demos until backend
   support for linked spans improves.
2. Use the same OTel trace id on both sides by extracting the W3C trace id from
   the inbound context but starting the consumer span without a parent span id.
   This produces one trace in Kibana but requires a synthetic context
   construction that fights OTel's model and will need maintenance as the OTel
   SDK evolves.

No decision made. Deferred pending further consideration of what users should
expect and what is sustainable to maintain.

_→ open a follow-up issue: OTel async trace representation strategy_

---

## Spec clarification candidates

No changes to the spec are required. The implementation follows §13 as written.
One point worth clarifying in a future spec revision:

**§13.5 — `parentSpanId` absent on consumer side:** the spec states
`parentSpanId` must be absent on the consumer-side `ItaraContext`. The
implementation satisfies this. However, the spec does not address observer-level
context (e.g. W3C `traceparent`) — whether observers should also sever the
parent relationship or may maintain their own linking strategy is left to the
observer implementation. Clarifying this boundary would help future observer
authors.

---

## Before merging to main

The spike implementation is correct per the spec and the demo passes, but
additional targeted test coverage is required before this branch is merged:

- `ConfigLoaderTest` — virtual node parsing and filtering cases were added
  during the spike and should be reviewed for completeness
- `ItaraDispatcher` — test that `ExchangePattern.FIRE_AND_FORGET` produces a
  context with null `parentSpanId` and a fresh `spanId`
- `ContextPropagation` — test both `fromHeaders()` paths explicitly
- `KafkaTransport` — integration-level tests against an embedded Kafka broker
  (e.g. Testcontainers) covering send, receive, header propagation, and
  `stopListener()` behaviour
- Agent startup — test that virtual node connections wire producer proxy and
  consumer listener correctly, and that the registry alias is registered before
  the listener starts
- `EventContractScanner` — test descriptor parsing and collection id resolution
  from `.itara` metadata

_→ open a follow-up issue: event-driven test coverage before merge_

---

## Demo

A minimal two-component event-driven demo is implemented in
`itara-demo-events/`:

- `order-event-api` — `@EventContractInterface(id = "order-placed")` on
  `OrderPlacedContract`
- `order-producer` — receives HTTP requests, publishes via Kafka proxy
- `order-consumer` — listens on Kafka, logs received events

Runs as two separate JVM processes connected via Kafka. Tested with both the
logging observer and the OTel observer (Elastic APM backend). Docker Compose
setup included, referencing the existing `docker-compose-otel.yml` as an
external stack.

Trace ids are consistent across processes. All four observability events fire
correctly on both sides. Lazy activation is preserved — the consumer component
is not instantiated until the first message arrives.
