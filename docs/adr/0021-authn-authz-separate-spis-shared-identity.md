# ADR 0021 — Authentication and Authorization as Separate SPIs Sharing an Extensible Identity Type

**Status:** Accepted
**Date:** August 2026

---

## Context

Topology-layer security raises two distinct questions: is the caller who
it claims to be (authentication), and is the caller permitted to invoke
this specific operation (authorization). Whatever design answers both
needs a way for a caller's identity, once established, to travel from
wherever it's established to wherever it's consumed.

---

## Decision

Authentication and authorization are two independently pluggable SPIs,
each with its own type identifier, plugin discovery, and per-connection
configuration (spec §15, §16).

A caller's identity is represented by a single, Itara-defined structured
type shared by both SPIs, not an interface-specific representation each
reinvents. It carries at minimum subject identification, issuer/trust
metadata, and security scope/claims, and MUST be extensible with
implementation-specific fields beyond that (spec §15.6).
Authentication produces an identity or determines none is available;
authorization consumes whatever authentication produced, or its absence,
and decides permit or deny.

---

## Reasoning

**Merging into one interface doesn't reduce complexity, it forces every
implementation to carry both concerns.** A trusted-network deployment may
want authentication with no permission model at all. A deployment behind
an already-authenticating load balancer may want authorization only. Two
SPIs let each be adopted independently — none, either, or both.

**The reversible direction determined the starting point.** If it turns
out nobody wants one without the other, converging two SPIs into one in
practice costs nothing — most implementations end up implementing both
identically. Splitting an already-adopted merged interface back apart
would be a breaking change touching every implementation and every wiring
config referencing it. Starting split is the cheaper mistake to have made.

**A shared identity type is what makes the split actually work.** Without
one, N authentication implementations and M authorization implementations
would need N×M compatibility knowledge between them — the same category of
problem already solved for message formats (ADR 0019) by establishing one
shared, spec-defined structure instead of per-implementation formats.

**Extensibility exists because this specification does not model
authentication mechanisms into categories** — the
minimum shape is enough for authorization to decide against; anything
beyond it is implementation-specific and additive.

---

## Alternatives considered

**Single merged authentication+authorization interface.** Rejected — see
Reasoning.

**No shared identity type**, with authorization implementations declaring
which authentication implementations' representations they understand.
Rejected: reintroduces the N×M compatibility problem ADR 0019 already
eliminated for a different pair of plugins, for no benefit a shared
minimal-plus-extensible type doesn't already provide.

---

## Consequences

- Two new plugin kinds, two new metadata sections, two new wiring-config
  block shapes — real surface area, each independently optional and
  defaulting to a no-op when unconfigured.
- A deployment can adopt authentication without authorization, or
  authorization without authentication — the latter meaning an asserted
  identity is used for permission decisions without being verified, a
  real, weaker, but not disallowed configuration.
- Any future authentication implementation and any future authorization
  implementation are compatible at the identity level without needing to
  know about each other specifically.

---

## References

- Spec §15 (Authentication SPI), §16 (Authorization SPI)
- ADR 0019 — Message Format as a Serializer Concern (same reasoning
  pattern: a shared, spec-defined structure avoiding N×M coupling)
