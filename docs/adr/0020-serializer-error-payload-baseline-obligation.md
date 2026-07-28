# ADR 0020 — Serializer Error Payload Handling Is Unconditional

**Status:** Accepted
**Date:** July 2026

---

## Context

The error payload (spec §6.6.2) is a structure of fixed, specification-
defined shape — error kind, a platform-specific type identifier, and a
message — produced by the dispatcher (spec §6.6.3) and passed to the
connection's configured serializer for serialization before the error
reaches the transport. The proxy deserializes it using the same serializer
(spec §6.6.4). It is never itself a business contract type.

The introduction of contract message formats (ADR 0019) and per-serializer
`message-formats` capability declarations (spec §5.4, spec §8.6) raises a
question
this ADR resolves: does a serializer's obligation to handle the error
payload depend on its declared message-format capabilities?

The concern is concrete, not hypothetical. A serializer built around a
structural message format — a protobuf serializer, for instance — is
designed to operate generically over message-format-generated types via
reflection (outbound `toByteArray()`, inbound `parseFrom()`). The error
payload is not a message-format-generated type. A serializer whose only
serialization strategy is "handle `GeneratedMessageV3`-shaped objects"
would, without an explicit decision, have no way to serialize the error
payload at all.

---

## Decision

Every serializer implementation MUST be capable of serializing and
deserializing the fixed error payload structure (spec §6.6.2), regardless
of its declared `serializer.type` or `message-formats` capability (spec
§5.4). This is a
baseline obligation, not conditional on any capability declaration.

A serializer whose primary strategy is generic/reflective over
message-format-generated types MUST additionally special-case the error
payload — for example, by shipping one well-known, fixed message
definition for it as part of the serializer plugin itself, never something
a component author declares. The business-payload path and the
error-payload path may be, and in practice will be, two different code
paths inside the same serializer implementation.

---

## Reasoning

**The error payload's shape is fixed and specification-defined — not
user-defined, and not something a serializer author has to anticipate
arbitrary variation in.** Its fields are whatever this specification says
they are, versioned along with the specification itself, not derived from
any contract. This makes special-casing it a known, bounded target for any
serializer author to implement against.

**Making error handling conditional on message-format capability would
produce a strictly worse failure mode.** If a serializer not built for a
given message format could not report errors at all on a message-format
connection, a business failure on that connection would surface as an
inability to report the failure — precisely in the path where clarity
matters most. Whether a business payload happens to be a proto message has
nothing to do with whether the callee threw an exception; the two should
not be coupled.

**This mirrors the reasoning in ADR 0017.** ADR 0017 established that
certain platform guarantees — there, the four-event observability model —
are unconditional structural properties, not something a pluggable
implementation gets to opt out of. Error-payload handling is the same kind
of guarantee: a baseline the platform holds regardless of what a specific
plugin chooses to support elsewhere.

---

## Alternatives considered

**Conditional error-payload serialization with a platform fallback format.**
When the configured serializer doesn't support the connection's declared
message format, fall back to a default encoding (e.g. always-plain) for the
error payload specifically. Rejected: this reintroduces exactly the
ambiguity the message-format work was meant to remove — the fallback format
itself needs its own compatibility guarantee, relocating the problem rather
than solving it, and it creates a silent special case a component author
has no visibility into.

**Message-format-specific canonical error encodings.** Require every
message format to define its own standard schema for the error payload
(e.g. a `.proto` definition for `ItaraErrorPayload` published alongside the
format itself, importable by contracts). Rejected: this turns the error
payload into a contract-level artifact, contradicting spec §6.6.2's
existing
position that it is not a business contract type. It would also require
every future message format to define an equivalent, growing what should
remain an internal serializer-implementation detail into public surface
area.

---

## Consequences

- Every serializer, regardless of message-format specialization, can always
  report an error. No connection can end up in a state where a business
  call fails but the failure itself cannot be communicated to the caller.
- Serializer implementations targeting a structural message format carry a
  fixed, specification-defined amount of built-in special-casing for the
  error payload, in addition to their generic business-payload handling —
  a bounded, known cost.
- `[serializer.capabilities] message-formats` (spec §5.4) governs business
  payloads only. It has no bearing on error-payload handling, which is
  unconditional.
- No change to transport behaviour — how the error is carried across the
  wire remains entirely a transport implementation concern (spec §6.6.5),
  unaffected by this decision.

---

## References

- Spec §6.6.2 — Error Payload
- Spec §5.4 — Serializer Artifacts
- Spec §8.4 — Serializer Contract
- Spec §8.6 — Message Format Compatibility
- ADR 0017 — Failure Semantics Observability Boundary (same reasoning
  pattern: unconditional platform guarantees vs. pluggable strategy)
- ADR 0019 — Message Format as a Serializer Concern
