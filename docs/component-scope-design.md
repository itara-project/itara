# Component Scope Design

**Status:** Accepted
**Date:** August 2026
**Related:** SPEC.md §3.6 (Component Scope), GLOSSARY.md (Component Scope),
classloader-isolation-design.md (TCCL precedent this generalizes)

---

## Overview

SPEC.md §3.6 requires that every executing component instance have a
scope: a boundary of what it can reach, plus whatever information is
needed to enforce that boundary, established exclusively by the agent and
never influenced by caller-supplied input. This document describes how
that guarantee is actually implemented.

The model is synchronous-only. Reactive programming introduces ordering
guarantees this design does not attempt to provide and is explicitly out
of scope, to be revisited separately if it's ever needed.

---

## Scope objects

A scope object is created once per node, at the point that node starts
executing, and is never replaced. Its reference stays constant for the
lifetime of the node — the same way nodes themselves are not created or
destroyed at runtime. Scope objects are immutable. If code anywhere
appears to need a new scope object mid-execution, that's a sign something
upstream is wrong; scope objects are not a per-call or per-request
construct.

A scope carries, at minimum:

- Which node this is, for boundary enforcement
- The thread context classloader this node's code should run under (see
  TCCL, below)

This list is expected to grow as boundary-enforcement needs are
identified, but every addition follows the same rule: information the
agent needs to enforce the boundary, never information a caller supplies.

---

## Current scope: no stack, thread-local, set-and-restore

There is no explicit stack data structure. A single thread-local holds
whichever scope object is currently active on that thread. Whenever
control passes from one component into another — through a proxy on the
outbound side, through a dispatcher on the inbound side — the code doing
the crossing opens an `AutoCloseable` scope handle, the same pattern
already used for observability context (`ItaraScope`): entering captures
whatever scope was previously active and sets the thread-local to the new
one; closing restores the previous value. Used in try-with-resources, the
restore is structurally forced by the compiler, not a discipline that has
to be remembered and can be forgotten — this is a strict improvement over
a manual try/finally, and in retrospect the existing TCCL swap should have
been built this way from the start. The dispatcher refactor described
below (TCCL is Part of Scope, not Separate From It) is the natural point
to fix that, not just move the behavior.

Because this happens at every crossing point and always restores on close,
correct nesting falls out of the language's own call stack — a call three
components deep unwinds through three try-with-resources blocks closing
in the right order, without Itara needing to maintain a second, explicit
structure that duplicates what the call stack already guarantees. This
depends entirely on execution being synchronous; it is not intended to
survive anything reactive.

This is the same discipline already used for thread-context-classloader
swapping in the dispatcher today (see Relationship to Existing TCCL Code,
below) — generalized from one piece of ambient state to the scope object
that now carries it.

---

## TCCL is part of scope, not separate from it

The classloader isolation design currently has the dispatcher swap TCCL
directly, per inbound call. That swap becomes part of what setting a
scope does — TCCL is one of the pieces of information a scope carries,
not a parallel mechanism operating independently. The dispatcher no
longer swaps TCCL itself; it sets scope, and scope-setting swaps TCCL as
part of what it does.

This is a real refactor of shipped, tested code, not a new addition —
call this out explicitly in implementation planning.

---

## Proxies and dispatchers carry their scope, don't rely on ambient state

A proxy or dispatcher captures the scope reference it needs at
construction time and holds onto it directly. It does not read the
current thread-local scope to determine identity when dispatching a call
— it sets thread-local *to* its own held reference, uses it, and restores
afterward. This is deliberate: proxies and dispatchers are legitimately
invoked from threads with no correct ambient scope set (a shared thread
pool, for instance), and must work correctly regardless. The ambient
thread-local is what *component code* running underneath a proxy
observes, via TCCL and whatever else scope carries — it is never what a
proxy or dispatcher itself depends on to know who it is.

---

## Thread pools and inheritance

A scope object is immutable and never replaced for the life of a node.
This means "whichever scope was active when a thread pool was created" is
not an approximation of the correct answer for a pool that pool — it *is*
the correct answer, permanently, because nothing about that node's scope
can ever drift from what it was at pool-creation time. `InheritableThreadLocal`,
capturing at pool creation, is therefore complete and sufficient for any
thread pool a component creates and owns for itself, for the pool's
entire lifetime — not a partial solution.

The one case this does not cover: a pool that is shared across multiple
components with different scopes, or that predates any component's scope
being active — `ForkJoinPool.commonPool()` being the standing example,
since it is JVM-global with no single owning node. A utility (a
`Callable`/`Runnable`/`ExecutorService` wrapper capturing the submitting
thread's current scope and restoring it on the executing thread) is
provided for this case specifically. It is not intended for general use,
and using a component-owned pool without it is expected and correct — the
utility exists only for shared, global, or pre-existing pools.

Submitting work to a shared pool without using the utility is a
documented bad practice, and doubly so: it breaks observability context
propagation (trace/span) exactly the same way it breaks component scope,
since both ride the same thread-local mechanism. Neither is something the
design defends against, but both must be called out plainly in
implementation and adoption documentation, since the failure mode (a
registry lookup rejected for lack of scope, or a trace that silently loses
its parent span) is otherwise confusing to debug.

---

## The registry

The registry's outbound lookup is keyed by `(from, to)` — a compound key
of the calling node and the target — rather than by target alone. `from`
is never accepted as an explicit parameter anywhere in the lookup path;
it is read directly from the current thread-local scope inside the
lookup method itself, so there is no call site, present or future, that
could pass the wrong value, because there is no parameter to pass it
through.

**Populate and query are different operations, not the same operation
gated by different permissions.** Populating the registry happens during
agent-controlled wiring construction, at startup, before any node's scope
exists to check against — it does not go through the scope gate at all,
because it isn't the same code path. Querying is what components
(indirectly, through proxies and dispatchers) actually do at runtime, and
it is the query path specifically that MUST throw if no scope is active
on the calling thread.

Today, the registry does not expose a narrower interface for querying
versus populating — this needs fixing. Components must only ever be able
to reach a restricted, query-only view of the registry; the agent alone
has access to the full interface, including populate. How that
restriction is enforced (a narrower public interface, package-private
access, a separate façade type) is an implementation choice, not a design
decision this document needs to settle.

If no scope is active when a query is attempted, the lookup throws. This
is safe by construction, not by careful checking: whenever control
legitimately passes between components, the proxy or dispatcher doing the
crossing guarantees scope is set before anything downstream runs. Code
attempting to query the registry from an unscoped thread — a shared pool
used without the propagation utility, for instance — gains nothing by
trying, because there is no scope for the query to succeed against.

---

## Worked example: remote dispatcher, no thread inheritance involved

A remote connection's inbound-call thread is typically owned by the
transport framework itself (a servlet container's worker pool, for
example), not by Itara, and is reused across unrelated requests with no
relationship to any component's scope. This case requires no reliance on
thread inheritance at all: the dispatcher's own node identity is fixed
for the dispatcher's lifetime, captured at construction. On every inbound
call, regardless of which thread the transport happens to hand it, the
dispatcher explicitly opens its own scope handle, does its work, and lets
close restore whatever was there before, via try-with-resources — the same
set-and-restore discipline as everywhere else, requiring nothing from the
thread itself.

---

## Out of scope

- Reactive programming. The synchronous, `finally`-based nesting this
  design relies on does not extend to non-blocking or reactive execution
  models. A separate design effort if and when this becomes necessary.
- Defending against reflection, direct construction, or other means of
  circumventing the registry and proxies entirely (see SPEC.md §3.6) —
  this specification concerns the ordinary, prepared path, not defense
  against a determined attacker with reflection access.
