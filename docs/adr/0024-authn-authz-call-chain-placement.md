# ADR 0024 — Authentication and Authorization Placement in the Call Chain

**Status:** Accepted
**Date:** August 2026

---

## Context

With authentication and authorization as topology-layer SPIs (ADR 0021), a
placement decision is needed: where do they run relative to context
reconstruction, argument deserialization, and the four observability
events on the callee side, and relative to the failure semantics retry
loop on the caller side.

---

## Decision

**Callee side**, in order: context reconstruction, then authentication,
then authorization, then argument deserialization, then `CALL_RECEIVED`
and business logic. A rejection at either step stops the call there; it
never reaches deserialization or `CALL_RECEIVED`.

This requires the transport to convey whatever authentication and
authorization need — routing information (node, component, method) and
any identity signal — independent of the serialized payload, since both
run before deserialization happens (spec §7.5).

**Caller side:** authentication's identity assertion is produced once,
before the failure semantics implementation is invoked, and reused across
any retry attempts of that call — it is not regenerated per attempt and is
not itself part of the retryable unit of work.

---

## Reasoning

**Context reconstruction first, so custom spans are possible.**
Authentication and authorization MAY emit custom observability events
(spec §9.7), the same escape hatch failure semantics already has (ADR
0017). That requires an active context to attach to, so context must exist
before either runs.

**Before deserialization, not after.** Both operate only on identity and
routing information, never on deserialized arguments (spec §15.6, §16.5).
Running them first avoids paying the cost of deserializing a call about to
be rejected, and keeps their inputs exactly as narrow as their contract
already says.

**The transport requirement is a direct consequence, not a separate
choice.** If authentication and authorization must run before
deserialization, whatever they need has to be extractable before
deserialization too — which only the transport can guarantee.

**Caller-side identity production sits outside the retry loop for the same
reason serialization does (ADR 0017).** The caller's identity doesn't
change between retry attempts of the same logical call, so recomputing it
per attempt is wasteful and answers a question that was already settled.
Reusing it across attempts is the same treatment already given to the
serialized payload.

---

## Alternatives considered

**Run authentication and authorization after deserialization.** Rejected:
wastes deserialization work on calls about to be rejected for no benefit.

**Regenerate the caller-side identity assertion on every retry attempt.**
Rejected — mirrors ADR 0017's rejection of retrying serialization, for the
same reason: the answer doesn't change between attempts, so recomputing it
is wasted work, not added correctness.

---

## Consequences

- Callee-side call order is now fixed: context reconstruction →
  authentication → authorization → deserialization → `CALL_RECEIVED` →
  business logic.
- The §7.5 transport requirement exists because of this ordering — without
  it, this call order isn't achievable for any transport.
- The caller-side identity assertion is produced once per logical call and
  reused across retries, the same treatment the serialized payload already
  gets.
- This ADR does not decide whether a `PERMISSION` error triggers a retry —
  that remains the failure semantics implementation's own configurable
  choice (ADR 0023).

---

## References

- Spec §7.5 (Transport Interface, Callee Side)
- Spec §9.7 (Observer Interface, custom spans)
- Spec §15.5/§15.6 (Authentication SPI)
- Spec §16.5 (Authorization SPI)
- ADR 0017 — Failure Semantics Observability Boundary (precedent for both
  the custom-span escape hatch and the reused-not-retried treatment)
- ADR 0021 — Authentication and Authorization as Separate SPIs
- ADR 0023 — `PERMISSION` Error Kind
