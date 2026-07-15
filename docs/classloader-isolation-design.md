# Classloader Isolation Design

**Status:** In progress  
**Date:** July 2026  
**Related ADRs:** ADR-0018

---

## Overview

Itara's colocation promise is that components connected by a `direct` transport
run in the same process and communicate via zero-overhead in-memory calls. The
current implementation loads all components from the system classloader, meaning
their transitive dependency trees must be compatible. This is a significant
practical barrier for independently developed components, which frequently
depend on different versions of the same library.

This document describes the design for lifting that barrier using per-component
classloaders. It covers the classloader hierarchy, deployment layout, and the
changes required to the wiring agent.

---

## Classloader hierarchy

The system classloader holds all shared artifacts:

- Itara internals (`itara-common.jar`, `itara-agent.jar`)
- API jars (component contracts)
- Event contract jars

Each component in a deployment group that uses classloader isolation gets its
own child classloader, pointing to that component's dedicated directory. The
child classloader has the system classloader as its parent.

**Delegation order: parent-first.** See ADR-0018 for the full rationale. The
short version: parent-first ensures that API and Itara classes always have a
single class identity across all components. Child-first would break this
guarantee whenever an API jar appears in a component directory — whether by
accident or because of a fat jar — producing ClassCastException failures at
call boundaries that are extremely difficult to diagnose. Parent-first makes
that misconfiguration harmless.

A further benefit: fat jars work correctly without modification under
parent-first delegation. The system classloader loads shared artifacts from
the shared directory first; copies bundled inside a fat jar are simply
shadowed and never loaded. Child-first would break fat jars unless they were
built with shared artifacts explicitly excluded.

---

## API artifact handling

API jars and event contract jars are always loaded by the system classloader.
This is what guarantees a single class identity for every interface across all
components. The proxy is created in the system classloader context against the
API interface. The implementation is created by the component classloader but
implements the same interface class, which was loaded by the parent. Java's
type system allows this because the interface was loaded by the common parent
— the call boundary is safe.

No component directory should contain an API jar or event contract jar. Under
parent-first delegation, if one is present by accident, it is silently shadowed
by the system classloader copy. This is harmless but should be flagged by the
wiring agent at startup as a warning.

---

## Deployment layout

```
deployment-root/
  lib/                              ← system classloader
    itara-common.jar
    itara-agent.jar
    order-api.jar
    inventory-api.jar
    order-events.jar

  components/
    order/                          ← order component classloader
      order-impl.jar
      (order private dependencies)

    inventory/                      ← inventory component classloader
      inventory-impl.jar
      (inventory private dependencies)
```

Components packaged as fat jars place the fat jar in the component directory.
Shared artifacts inside the fat jar are shadowed by the system classloader
and never loaded by the component classloader.

---

## Isolation modes

Two modes are supported, selected by how the deployment environment is
assembled:

- **Isolated** — the components root directory exists and contains at least
  one subdirectory. The wiring agent creates one classloader per component
  subdirectory and operates in isolated mode. The system classloader is not
  scanned for activators.
- **Shared** — the components root directory is absent, empty, or contains
  no subdirectories. The wiring agent operates as today: all components are
  loaded from the system classloader.

The components root directory defaults to `lib/components` relative to the
deployment root but can be overridden via the `ITARA_COMPONENTS_DIR`
environment variable. This allows isolated mode to be used outside of
containers — for example, during local testing — without requiring the default
directory structure.

The mode is determined once at startup based on the resolved components root
directory. The two modes are mutually exclusive. A hybrid — some components
isolated, some on the system classloader — is not supported. Under
parent-first delegation, system classloader contents are always visible to
component classloaders, so a hybrid would allow isolated components to
silently pick up activators or implementations from the system classloader
through the parent, breaking the isolation guarantee. The hard switch
eliminates this ambiguity. The deployment layout alone determines the mode.

---

## Wiring agent changes

The current agent startup sequence is:

1. Load wiring configuration
2. Build metadata index
3. Scan classpath for component API and event contract artifacts
4. Scan classpath for activators
5. Register activators in the registry
6. Application starts; components are activated on first use

Steps 1–3 are unchanged. Steps 4–5 change as follows.

### Activator scanning

**Isolated mode:** the agent detects the presence of the `components/`
directory. For each subdirectory, it creates a child classloader with the
system classloader as parent and the subdirectory as its classpath. It then
scans each child classloader for activators, explicitly bounding the scan to
the classloader's own directory — the parent's contents are excluded from
the scan to avoid registering the same activator twice. Each discovered
activator is registered in the registry with its classloader stored alongside
it.

**Shared mode:** the agent scans the system classloader as today. No child
classloaders are created.

The agent MUST NOT scan both the `components/` directories and the system
classloader for activators in the same startup. The mode is determined once,
at startup, based on the presence of the `components/` directory.

### Activation and thread context classloader

Components are activated lazily — on first use. When the registry activates
a component, it:

1. Retrieves the activator and its associated classloader
2. Sets the thread context classloader to the component's classloader
3. Calls the activator to create the component instance
4. Restores the thread context classloader to its previous value
5. Registers the component instance in the registry

### Thread context classloader on every inbound call

The TCCL must be set to the component's classloader not only at activation
time but on every inbound call to the component, for the lifetime of that
call.

The reason is deferred initialisation. Frameworks such as Spring support lazy
beans — beans that are not instantiated when the ApplicationContext is
refreshed but on first access, which may happen during a request well after
activation. At that point the TCCL is whatever the calling thread carries,
which under normal circumstances would be the system classloader. The framework
would attempt to load the bean's class through the wrong classloader, either
failing outright or — worse — loading it with a different class identity than
the rest of the component expects.

Setting the TCCL on every inbound call prevents this class of failure
entirely, covering lazy beans and any other deferred initialisation a framework
might perform on first access.

The dispatcher is the correct place for this. It already intercepts every
inbound call to fire the observability events. The TCCL swap fits naturally
there: set the component's classloader before dispatching to the
implementation, restore the previous value after the call returns — including
on exception paths.

The performance cost is negligible. Setting and reading the TCCL is a single
field write and read on the current `Thread` object — O(1), no allocation, no
locking. The JIT will inline it. It is in the same cost category as the
observability event firing that already happens on every call.

### Thread pools and spawned threads

A directly spawned thread inherits the TCCL of its parent thread at creation
time. If a component spawns a thread or creates a thread pool during a call —
while the TCCL is correctly set by the dispatcher — the spawned threads will
inherit the correct classloader. This covers the common case of Spring-managed
thread pools created during ApplicationContext initialisation.

The TCCL is set correctly on every inbound call before any component code
runs, so any threads or pools the component creates during that call inherit
the right classloader naturally.

### Proxy and dispatcher creation

Proxies and dispatchers are created in the system classloader context against
the API interface, as today. No changes are required. When a proxy invokes an
implementation, it calls the registry to fetch the activated instance. The
registry holds the instance reference directly — no classloader switching is
needed at call time. The component's classloader was only needed at activation
time to create the instance; once created, the instance is referenced and
called normally through its API interface.

---

## Transaction boundaries

In isolated mode, transactions stop at component boundaries. Each component
has its own classloader and its own framework context, so transaction managers
are not shared. A call from one component to another crosses a transaction
boundary unconditionally — the callee executes in its own transaction context,
or none at all, regardless of what the caller's transaction state is.

In shared mode, transactions behave as they do today — the transaction context
is shared across all components and extends as far as the framework allows.

This is the correct default for independently developed components. They did
not share transactions before being colocated, and they should not be expected
to share them after. Colocation is an optimisation of the communication path,
not a merging of runtime contexts.

---

## Known limitations and edge cases

The following are known constraints of the classloader isolation model. They
are not blockers but operators and component authors should be aware of them.

**Logging**  
Logging frameworks such as Log4j2 and Logback use the TCCL for configuration
discovery and appender resolution. With per-component classloaders, each
component may initialise its own logging context, producing separate log
streams or conflicting appender registrations. The recommended mitigation is
to place the logging framework on the system classloader so it is shared across
all components. A routing solution for seamless unified logging across
components is a future concern and out of scope for the initial implementation.

**Native libraries**  
Native libraries loaded via `System.loadLibrary()` or `System.load()` are
global to the JVM process — they cannot be loaded more than once. If two
components attempt to load the same native library through their respective
classloaders, the second load will either no-op silently or throw an
`UnsatisfiedLinkError` depending on the JVM implementation. Native libraries
shared across components should be placed on the system classloader and loaded
once at startup.

**ForkJoinPool**  
The common `ForkJoinPool` (`ForkJoinPool.commonPool()`) is a JVM-global
resource. Tasks submitted to it execute with whatever TCCL the pool thread
happens to carry, which is not guaranteed to be any particular component's
classloader. Components that rely on the common pool for work that requires
the TCCL — framework operations, resource loading — should use a
component-managed executor instead, which will inherit the correct TCCL at
creation time.

**JVM-global state**  
System properties, security managers, and other JVM-global state are shared
across all components regardless of classloader isolation. This is an
inherent constraint of the single-JVM model and cannot be addressed without
process-level isolation.

---

## Isolation mode is a deployment decision

The isolation mode — shared or isolated — is not expressed in the wiring
configuration. It is a deployment decision, made per deployment group, by how
the deployment environment is assembled. The wiring configuration declares
topology; the deployment layout determines isolation. This separation is
intentional: the same wiring configuration can be deployed in shared mode for
development and isolated mode for production without any configuration change.

---

## Open items

- **JVM property isolation** — when two components are colocated, JVM-global
  properties conflict if both use the same property key for different values.
  This affects any framework or library that reads configuration from system
  properties, environment variables, or other JVM-global sources. There is no
  clean, generic solution identified yet. Candidates explored: routing
  `Properties` subclass installed via `System.setProperties()` keyed on TCCL;
  external per-component property files attached to component classloaders.
  Both have significant edge cases. Deferred until the spike produces concrete
  evidence of which properties actually conflict in practice and how severe the
  problem is.

- **`SpringFactoriesLoader` and `META-INF` scanning** — risk that Itara's own
  `META-INF` entries are picked up by Spring auto-configuration under
  parent-first delegation. To be verified during the spike.
