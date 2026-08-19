# ADR 0023 — Registry Interface Segregation: Lookup-Only Access for Component Code

**Status:** Accepted
**Date:** August 2026

---

## Context

The registry resolves proxies for outbound calls. Today it exposes one
interface to everything that touches it, with no distinction between what
the agent needs (populating the registry, full access) and what a proxy
or dispatcher needs on behalf of running component code (looking up a
target). A lookup that accepted the caller (`from`) as an explicit
parameter would let that value be supplied incorrectly — accidentally or
otherwise — since nothing would stop a caller from passing any value
through it.

---

## Decision

The registry exposes two interfaces. A component-facing API is
lookup-only and takes a single parameter, the target (`to`) — it never
accepts `from` as a parameter at all; `from` is read internally from the
current scope. The full API — including populating the registry — is
available only to the agent, never to component code.

---

## Reasoning

**Removing `from` as a parameter removes an entire class of bug, not just
a risk.** If `from` were accepted as an argument, every call site — now
and in the future — would need to supply it correctly. Sourcing it
internally from scope means there is no parameter to get wrong, by
mistake or otherwise.

**Segregating the interface, not just documenting a convention, makes
the restriction load-bearing.** Component code being expected not to call
the full registry API is not the same as component code being unable to.
A narrower, lookup-only interface is what actually enforces the boundary
spec §3.6 requires, rather than relying on a convention nothing checks.

---

## Alternatives considered

**One interface, with `from` as an explicit parameter**, trusted to be
correct by convention. Rejected: exactly the caller-suppliable identity
spec §3.6 prohibits, and the source of the vulnerability this design
exists to close.

**One interface for both agent and component use, distinguished only by
documentation.** Rejected: not enforced, and the current state this ADR
is replacing.

---

## Consequences

- Component code, however it's invoked, cannot reach registry
  functionality beyond a single, narrow, lookup-only operation.
- The agent's own use of the registry is unaffected — it retains full
  access through its own interface.

---

## Implementation Status

As of this writing, only half of this decision is structurally enforced.
`ComponentLookup` exists as the intended lookup-only, component-facing
entry point — it exposes `get()` and `getSelf()` and nothing else — but
`ItaraRegistry` itself remains a single, fully public class exposing both
the component-facing and agent-only surface together. Nothing today
prevents component code from bypassing `ComponentLookup` and calling
`ItaraRegistry` directly; the restriction currently holds by convention,
not by construction — the same state this ADR's own Alternatives
Considered section rejected.

Closing this gap is tracked as part of the project's planned
modularization work (see the open GitHub issue), which will use
language-level module boundaries to export `ComponentLookup` while
keeping `ItaraRegistry` genuinely unreachable from outside
`io.itara.runtime`. Until that lands, this ADR describes the target
design; the enforcement it promises is not yet in place.

---

## References

- Spec §3.6 — Component Scope
- component-scope-design.md
- ADR 0021 — Thread-Local Storage for Component Scope
