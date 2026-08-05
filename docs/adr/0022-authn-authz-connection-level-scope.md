# ADR 0022 — Authentication and Authorization Configured at Connection Level

**Status:** Accepted
**Date:** August 2026

---

## Context

Authentication and authorization implementations need to be selected
somewhere in the wiring model. The candidates are the node, a future
deployment-unit-level configuration layer (discussed informally but not
yet designed, for concerns like logging strategy or manifest generation),
or the connection.

---

## Decision

Authentication and authorization are configured per connection, in the
same `{ id, params }` block shape already used for transport, serializer,
and failure semantics (spec §15.4, §16.4). This includes `direct`
connections — colocation is a placement optimization, not a trust
boundary, and nothing in this design exempts a colocated connection from
either check.

---

## Reasoning

**A single node may need different security postures on different
connections.** The same node might accept calls from within a trusted
internal subnet on one connection and from a less trusted network on
another. Node-level configuration cannot express this — it would force one
posture across every connection touching that node, regardless of how
different those connections actually are.

**This is consistent with everything else pluggable in Itara, and loses
nothing by it.** Transport, serializer, and failure semantics are already
connection-level, and each has a factory with a grouping key (spec §7.3,
§8.3) letting multiple connections share one instance when configuration
matches. The same applies here: declaring per connection doesn't mean
instantiating per connection — a node with uniform security posture across
all its connections can still end up with one shared authentication or
authorization instance underneath, exactly like it already can for a
shared transport or serializer.

**A deployment-unit-level layer, if it materializes, isn't the right home
for this either, and for a sharper reason than orthogonality.** Colocated
components do not share an identity merely because they share a process —
colocation is a placement decision made to optimize communication
overhead, not a statement about trust (see Identity in the glossary).
Attaching authentication or authorization to a deployment unit would
conflate those two, letting a placement optimization silently become a
security boundary decision.

---

## Alternatives considered

**Node-level configuration.** Rejected — see Reasoning.

**A future deployment-unit-level configuration layer.** Rejected for this
concern specifically: it's a mismatch between what that layer would group
(deployment/packaging concerns) and what authentication and authorization
actually decide (a specific relationship between two nodes).

---

## Consequences

- Authentication and authorization configuration follows the same shape
  and precedent already established for transport, serializer, and
  failure semantics — no new configuration paradigm introduced.
- The same node can require different authentication and/or authorization
  behaviour on different connections, by design.
- Colocated components are not exempt from authentication or authorization
  by virtue of being colocated — a `direct` connection can carry the same
  checks as any other. The exact mechanics of how a claimed identity
  reaches the callee side of a `direct` connection are not specified by
  this ADR, the same way the identity type's own representation is not
  specified beyond what it must carry (spec §15.6/§16.5) — an
  implementation concern, not an architectural one.

---

## References

- Spec §15.4 (Authentication SPI, Wiring Configuration)
- Spec §16.4 (Authorization SPI, Wiring Configuration)
- ADR 0021 — Authentication and Authorization as Separate SPIs
