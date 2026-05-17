# ADR 0003 — Four-Event Observability Model

**Date:** April 2026  
**Status:** Accepted

## Context

Itara intercepts every call between components. This interception point is the natural place to collect observability data. The question is what events to fire and what they should represent.

A single-event model (one event per call) would be simpler but cannot distinguish between processing time and transport latency. A two-event model (call and return) per side gives four events total but requires a clear definition of what each event represents and when it fires.

## Decision

Every component interaction produces exactly four events, regardless of transport:

- **CALL_SENT** — fired by the caller immediately before dispatching the call
- **CALL_RECEIVED** — fired by the callee immediately upon receiving the call
- **RETURN_SENT** — fired by the callee immediately before sending the response
- **RETURN_RECEIVED** — fired by the caller immediately upon receiving the response

These four events define two spans:

- **Caller span:** CALL_SENT → RETURN_RECEIVED. Measures the full round trip from the caller's perspective.
- **Callee span:** CALL_RECEIVED → RETURN_SENT. Measures the actual processing time on the callee side.

The gap between CALL_SENT and CALL_RECEIVED is outbound transport latency. The gap between RETURN_SENT and RETURN_RECEIVED is inbound transport latency. Both are directly observable and independently measurable.

For fire-and-forget calls (void methods, async transports): the caller span closes on transport acknowledgement (e.g. Kafka broker ack). The callee span closes when processing is complete. Two honest spans, each measuring what they actually know.

## Consequences

- Network latency is directly measurable and separated from processing time without any additional instrumentation.
- The model is transport-agnostic. Direct calls, HTTP, Kafka — all produce the same four events with the same semantics.
- Retry attempts produce separate spans by definition. If a retry triggers the same downstream side effects as the original call, both spans appear in the trace. This makes non-idempotent retry storms visible in observability tooling — a consequence of getting the model right rather than a deliberate feature.
- The four events fire for direct (in-process) calls as well as remote calls. This makes the topology layer trustworthy — observability is not contingent on transport.
- Observer implementations receive all four events. The OTel bridge uses them to produce correctly parented distributed traces. Custom observers can use them for any purpose.
