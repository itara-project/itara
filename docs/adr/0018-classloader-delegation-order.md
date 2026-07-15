# ADR 0018 — Parent-First Classloader Delegation for Component Classloaders

**Status:** Accepted  
**Date:** July 2026

---

## Context

Itara's classloader isolation model gives each component its own classloader
to isolate private dependencies, preventing conflicts when independently
developed components are colocated in the same JVM. Each component classloader
is a child of the system classloader, which holds all shared artifacts: Itara
internals, API jars, and event contract jars.

The deployment layout places jars into one of two locations:

- **Shared directory** — loaded by the system classloader: Itara internals,
  API jars, event contract jars
- **Component directories** — one per component, loaded by that component's
  classloader: implementation jars and private dependencies

This layout is established at build and container assembly time, before the
JVM starts.

A child classloader must choose a delegation order when resolving a class:

- **Parent-first:** consult the system classloader first; only look in the
  component directory if the class is not found there
- **Child-first:** look in the component directory first; only delegate to the
  system classloader if the class is not found locally

---

## Decision

**Parent-first delegation.**

The system classloader is consulted before the component classloader for every
class resolution. Whatever is in the shared directory takes precedence over
whatever is in the component directory.

---

## Reasoning

**The isolation guarantee comes from the directory structure, not the
delegation order.**

API and Itara classes are placed in the shared directory. Component private
dependencies are placed in component directories. If the directories are
correctly populated, parent-first naturally produces the right behaviour:
shared classes are always loaded once, from the shared directory, with a
single class identity across all components. Component classes fill in
everything the shared directory does not provide.

**Child-first makes one specific misconfiguration actively dangerous.**

If an API jar is present in a component directory — whether by accident or
because a future artifact type was not correctly moved to the shared directory
— child-first loads it from the component directory. This gives that component
a different class identity for the same interface than the system classloader
has. The proxy, which is created in the system classloader context, holds the
system classloader's version of the interface. The component holds its own
version. The call boundary breaks with a ClassCastException that is extremely
difficult to diagnose.

Parent-first makes the same misconfiguration harmless. The shared directory
copy takes precedence, the component directory copy is ignored, and the single
class identity guarantee holds.

**A missing API jar in the shared directory cannot be defended against by
either delegation order.**

If an API jar is missing from the shared directory entirely, the wiring agent
cannot create proxies or dispatchers — both are created in the system
classloader context against the shared API class. The connection cannot be
established regardless of what the component classloader does. Child-first
offers no protection against this failure; it simply fails differently.
The correct response to a missing shared jar is a clear startup error, not a
silent workaround.
 
**Fat jars remain compatible without modification.**
 
A common packaging choice for Java components is the fat jar — a single jar
containing the component's implementation and all its transitive dependencies.
With parent-first delegation, a fat jar works correctly as-is: the system
classloader loads the shared artifacts from the shared directory first, and
the component classloader loads everything else from the fat jar. The shared
artifacts inside the fat jar are simply shadowed by the system classloader
copy and never loaded, which is the correct outcome.
 
Child-first delegation breaks fat jars. The component classloader would load
the API and Itara classes from inside the fat jar before consulting the system
classloader, producing a different class identity for those types than the
rest of the system uses. Fat jars would need to be built specifically for
Itara, with shared artifacts explicitly excluded — an additional build-time
requirement that increases adoption friction significantly.

**Parent-first matches standard Java classloading expectations.**

Its behaviour is predictable to anyone who understands the JVM. Child-first
is a deliberate deviation from the default that requires explanation and
increases the cognitive load for contributors and operators.

**The decision is easy to reverse.**

The delegation order is a few lines of code in the wiring agent. If experience
reveals a case where child-first is preferable for a specific scenario, the
change is trivial. Making the delegation order configurable per deployment
group via the wiring configuration or an environment variable is a future
option that does not need to be resolved now.

---

## Alternatives considered

**Child-first delegation**  
Provides stronger isolation and is the conventional recommendation for
plugin-style classloader hierarchies. Rejected because the override property
it provides is not needed here — components should not override shared
infrastructure. More importantly, child-first makes the accidental presence
of an API jar in a component directory a source of silent, hard-to-diagnose
ClassCastException failures at call boundaries. Parent-first makes the same
misconfiguration harmless. The isolation guarantee in this model comes from
directory structure, not delegation order, making child-first's primary
advantage irrelevant while its primary risk remains.

**Configurable delegation order per component**  
Would allow operators to choose per deployment group. Rejected as premature —
there is no identified use case that requires child-first for any specific
component. Can be introduced later without changing the default.

---

## Consequences

- API and event contract interfaces always have a single class identity across
  all components — no ClassCastException at call boundaries from duplicate
  jars in component directories.

- The correctness of this approach depends on the build and container assembly
  tooling placing jars in the correct directories. This is an explicit
  dependency on the tooling, not a hidden one. Any tooling that assembles
  Itara deployment artifacts must maintain this invariant: shared artifacts
  go in the shared directory, private dependencies go in component directories.

- If a component directory contains a jar that duplicates something in the
  shared directory, the shared version wins silently. This is the desired
  behaviour, but operators should be aware that component-directory jars do
  not override shared ones.

- Fat jars work correctly without modification. Shared artifacts bundled
  inside a fat jar are shadowed by the system classloader copy and never
  loaded by the component classloader.

- The delegation order is not currently configurable. If a future use case
  requires child-first for a specific deployment group, this decision should
  be revisited at that time.
