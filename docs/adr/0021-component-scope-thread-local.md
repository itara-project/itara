# ADR 0021 — Thread-Local Storage for Component Scope

**Status:** Accepted
**Date:** August 2026

---

## Context

Component scope (spec §3.6) needs to be available to whatever code is
currently executing, without being passed explicitly through every method
signature, and without being readable or forgeable by component code
itself.

---

## Decision

Scope information is carried in thread-local storage. A scope object is
immutable and lives as long as its node — created once, never replaced,
never mutated. Proxies and dispatchers capture the scope reference they
need at construction time and hold it directly; they do not determine
their own identity by reading the currently-active thread-local.

---

## Reasoning

**Thread-local storage is the natural fit for "what's currently
executing on this thread," without changing every method signature in the
call path.** It's ambient, ubiquitous in the JVM for exactly this kind of
cross-cutting concern, and consistent with how observability context
already works.

**Proxies and dispatchers holding their own reference, rather than
trusting the ambient thread-local, is a safety property, not a
convenience.** If a proxy determined its own identity by reading whatever
happened to be in thread-local storage at dispatch time, correctness of
the dispatch itself would depend on which thread invoked it. Holding a
captured reference from construction time means dispatch is always
correct regardless of the calling thread's state — the thread-local is
something a proxy *sets*, for the benefit of whatever runs underneath it,
never something it *trusts* to know who it is.

---

## Alternatives considered

**Passing scope explicitly as a method parameter** through the call path.
Rejected: touches every signature between the agent and wherever scope is
consulted, and reintroduces exactly the kind of caller-suppliable
parameter spec §3.6 already prohibits for identity information.

**Proxies and dispatchers reading the current thread-local to determine
their own identity**, instead of holding a captured reference. Rejected:
ties dispatch correctness to the invoking thread's state — see Reasoning.

---

## Consequences

- Scope is available anywhere on the current thread without explicit
  passing, at the cost of being implicit — a common and accepted
  trade-off for this class of problem.
- Dispatch correctness does not depend on which thread performs it.

---

## References

- Spec §3.6 — Component Scope
- component-scope-design.md
