# ADR 0017 — Failure Semantics Observability Boundary

**Status:** Accepted  
**Date:** June 2026

---

## Context

The failure semantics SPI wraps the transport call at the proxy level. Given
that an implementation may execute the transport call more than once — on
retry — the question arises: where do observability events fire relative to
the failure semantics implementation?

Two options were considered:

**Option A — The proxy wraps the retry loop.**
`CALL_SENT` and `RETURN_RECEIVED` fire once per logical call as seen by the
business layer. The failure semantics implementation is invisible to the
four-event model. Retry attempts are not individually represented in the four
key events.

**Option B — The failure semantics SPI fires its own events.**
Each retry attempt fires its own `CALL_SENT` / `RETURN_RECEIVED` pair, or
the failure semantics SPI is given direct access to the observer facade to
emit attempt-level events as first-class observability events.

---

## Decision

**Option A.** The four-event model is not affected by failure semantics.
`CALL_SENT` fires once, before the failure semantics implementation is invoked.
`RETURN_RECEIVED` fires once, after it returns — whether on the first attempt
or after retries.

Individual retry attempts MAY be made observable via custom spans (§9.7),
which are additive and optional. This is the correct mechanism for attempt-
level observability, not modification of the four key events.

---

## Reasoning

**The four events record what the business layer observed.**

The placement of the four events at the business/topology boundary is not
incidental — it is their definition. `CALL_SENT` fires when the business layer
hands off a call to the topology layer. `RETURN_RECEIVED` fires when the
topology layer returns a result. What happens in between — how many transport
attempts were made, what backoff was applied, whether a circuit breaker was
consulted — is an infrastructure concern. The business layer handed off a call
and got a result back. That is what the four events record.

Firing `CALL_SENT` once per retry attempt would redefine the events as
transport-layer events, not business-layer events. The entire value of the
four-event model rests on its placement at the boundary. Moving the events
inward to accommodate the failure SPI undermines the model for all consumers
of it — not just for failure semantics.

**Retrying serialization makes no sense.**

Serialization happens before the failure semantics implementation is invoked
and produces a byte payload that is reused across retry attempts. If the
failure semantics SPI were to fire its own `CALL_SENT` / `RETURN_RECEIVED`
pairs, serialization would need to be inside the retry loop to be consistent
— which is wasteful and semantically wrong. Serialization is not a retry
candidate; it either succeeds or it doesn't.

**Responsibility belongs at the right layer.**

Giving the failure semantics SPI direct responsibility for firing observability
events assigns it a concern that belongs to the proxy and dispatcher. The SPI's
contract is to execute the unit of work according to a failure strategy. It
should not also be responsible for maintaining the observability invariants
of the platform.

**Custom spans are the correct escape hatch.**

The legitimate want — seeing retry attempts in traces — is addressed by the
custom span extension to the observer SPI (spec §9.7). A failure semantics
implementation that wishes to make retry attempts observable MAY emit a custom
span per attempt. Each custom span becomes part of the active context.
Whatever is the active context at the time a retry attempt executes is what
gets propagated to the callee — the callee side handles that context however
its observer sees fit. This gives full observability of retry behaviour
without modifying the four-event model.

Custom spans are additive and optional. An observer that does not implement
custom span handling ignores them without error. The four-event model remains
intact for all consumers regardless of whether custom spans are emitted.

---

## Consequences

- A call that succeeds after two retries is indistinguishable in the four key
  events from a call that succeeded on the first attempt. This is intentional.
  The latency difference is visible in the outer span; the retry count is not,
  unless the failure semantics implementation emits custom spans.

- The failure semantics SPI does not receive the observer facade as a
  mandatory input. If an implementation wishes to emit custom spans, the
  platform makes the observer facade available to it; this is opt-in, not
  required.

- The four-event model's placement guarantee holds unconditionally: the four
  events always and only fire at the business/topology boundary, regardless
  of what the topology layer does internally to deliver the result.

- Future observability extensions — metrics, attempt counters, latency
  histograms per retry — are additive and do not require revisiting this
  decision. They belong in the custom span layer, not the four-event model.
