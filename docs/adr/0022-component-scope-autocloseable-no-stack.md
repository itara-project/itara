# ADR 0022 — Scope Restoration via AutoCloseable, Not an Explicit Stack

**Status:** Accepted
**Date:** August 2026

---

## Context

Component scope needs to nest correctly: a proxy dispatching into another
component, which itself dispatches into a third, must restore each
caller's scope on the way back out, in the right order.

---

## Decision

No explicit stack data structure. The thread-local (ADR 0021) holds only
the single, currently-active scope. Every crossing point opens an
`AutoCloseable` handle — the same pattern already used for observability
context (`ItaraScope`): entering captures whatever scope was previously
active and sets the thread-local to the new one; closing restores the
previous value. Used in try-with-resources, restoration is enforced by
the compiler.

---

## Reasoning

**The call stack already provides correct nesting; an explicit stack
would only duplicate it.** Every scope-open is paired with a scope-close
at the same call site, and calls nest the way calls already nest in a
synchronous language — three components deep unwinds through three
try-with-resources blocks closing in the right order, automatically,
because that's what nested method calls already do. A separate stack
structure would track information the runtime is already tracking for
free.

**AutoCloseable over manual try/finally**, matching the existing
`ItaraScope` precedent. A manual try/finally is correct but requires every
call site to remember it; try-with-resources makes the mistake
structurally impossible to make.

---

## Alternatives considered

**An explicit stack structure**, pushed and popped alongside scope
changes. Rejected: duplicates what the call stack already guarantees, for
no benefit.

**Manual try/finally instead of AutoCloseable.** Rejected: correct in
principle, but leaves restoration as something every call site has to
remember rather than something the compiler enforces.

---

## Consequences

- Zero additional memory cost beyond the thread-local itself — no stack
  allocation, no growing structure.
- This depends on synchronous execution. It is not expected to extend to
  reactive or non-blocking execution models without further design work.

---

## References

- Spec §3.6 — Component Scope
- component-scope-design.md
- ADR 0021 — Thread-Local Storage for Component Scope
- `ItaraScope` — existing AutoCloseable precedent for observability context
