# ADR 0010 — Observability Events Fired by the Agent, Not the Transport

**Date:** May 2026  
**Status:** Accepted

## Context

The four-event observability model (ADR 0003) requires CALL_SENT, CALL_RECEIVED, RETURN_SENT, and RETURN_RECEIVED to fire for every component interaction. The question is which layer is responsible for firing them, and precisely where in the call sequence they fire relative to serialization and deserialization.

The initial Java implementation fires these events from within the transport implementation. Each transport calls the ObservabilityFacade directly at the appropriate points in its own execution. This works but has significant drawbacks:

- Every transport implementation must correctly handle observability. It can be implemented incorrectly, inconsistently, or forgotten entirely by a transport author.
- The transport's job is to move bytes. Firing observability events is not moving bytes. This violates the same separation-of-concerns principle that motivated splitting the serializer from the transport (ADR 0007).
- A transport that skips observability silently violates Itara's guarantees without any way for the framework to detect or prevent it.

The current Java implementation is in violation of this decision and must be updated to conform.

## Decision

Observability events are the responsibility of the agent layer, not the transport. The transport is completely unaware that observability exists.

**Caller side — the generated proxy fires events around the full outbound operation:**

```
CALL_SENT
  serialize args
  transport.invoke()
  deserialize response
RETURN_RECEIVED
```

**Callee side — the generated dispatcher fires events around the component method only:**

```
deserialize args
CALL_RECEIVED
  component method
RETURN_SENT
serialize result
```

The transport receives bytes and returns bytes. It fires nothing. A transport implementor writes zero observability code.

## Why serialization sits outside the callee span

The callee span (CALL_RECEIVED → RETURN_SENT) measures pure component processing time — what the component itself costs, independent of any serialization format or transport choice. This is a deliberate decision with two important consequences.

**First, it makes Itara's overhead directly measurable and transparent.** The cost of serialization and deserialization is fully visible in the traces — on the caller side as the gap between CALL_SENT and when bytes actually leave, and on the callee side as the gap between bytes arriving and CALL_RECEIVED. Itara does not hide its overhead. It makes it the most visible, measurable thing in the system. An operator or potential adopter asking "what does Itara add to my latency?" gets an exact answer from the traces, broken down by component.

**Second, it produces stable, format-agnostic component metrics.** The callee span is identical regardless of whether JSON, Protobuf, or any other serializer is in use. This makes it a reliable signal for Orca's topology reasoning — if Orca is deciding whether to colocate or separate a component based on its processing cost, it needs numbers that are not contaminated by serialization format choices. Swapping serializers shows up cleanly in the serialization gaps, not in the component span.

**Third, it leaves room for future instrumentation without changing the event model.** Serialization, registry lookups, retry attempts, queue wait time, and any other future concern can be added as child spans of the outer caller or callee span, as siblings of the component execution span. The four events are the stable anchors. Everything else hangs off them. The model is extensible by construction.

## Transparency as a core requirement

Itara must remain transparent at all times — to operators, to adopters, and to future tooling including Orca. A system that cannot be observed cannot be safely automated (ADR 0004). The placement of observability events is not just a technical decision — it is a commitment that Itara will never obscure what it costs or what it does.

This transparency is also a commercial asset. The data Itara produces about its own overhead is the data that justifies its adoption and that Orca uses to make topology decisions. If that data is incomplete or inconsistent, both the trust and the tooling break.

## Consequences

- Transport implementations have no observability responsibility. Observability cannot be forgotten, skipped, or implemented incorrectly by a transport author.
- The four-event model fires uniformly for all transports by construction. No transport can silently violate the observability guarantee.
- Transport implementations are simpler and have a single, well-defined responsibility: move bytes.
- The callee span measures component processing time only, independent of serialization format. This is the correct input for performance-based topology decisions.
- Serialization cost is fully visible in both directions as gaps around the transport call and the component span. Comparing serializer implementations produces clean, unambiguous data.
- Future child spans — serialization, retry attempts, queue wait, registry lookups — attach to the existing anchor spans without changing the event model.
- The thin binding compliance level (`itara-compliance = "thin"`) still fires observability events correctly because the agent layer remains native and owns the event firing.
- The Java implementation currently violates this decision. The observability calls must be moved from the transport implementations into the generated proxy and dispatcher code. This is a required refactor before the Java implementation can be considered conformant.
