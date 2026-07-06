# Component Classloader Isolation — Design Notes

**Status:** Under discussion. Not yet part of the specification.
**Date:** July 2026

---

## The problem

When previously separate components are colocated into a single deployment
group, their transitive dependency trees collide in the same JVM. Libraries
used by both services may exist at different versions. The current Itara model
loads all components in the same classloader, which means only one version of
any given class can exist at runtime — whichever is resolved first by the
classloader wins, silently.

This is the primary practical barrier to colocating components that were
developed independently.

---

## The two strategies

### Strategy A — Common classloader (current behaviour)

All components in a deployment group share the same classloader. If two
components depend on different versions of the same library, one of them
will silently get the wrong version. The only way to avoid this is to align
all dependencies manually — which is effectively merging the codebases at
the dependency level.

**Appropriate for:** greenfield components designed together from the start,
or components that intentionally share a transaction boundary and have
aligned dependencies.

**Not appropriate for:** colocating independently developed components with
independent dependency trees.

### Strategy B — Separate classloaders (new)

Each component in a deployment group gets its own classloader, pointing to
that component's dedicated jar directory. API artifacts and event contract
artifacts are loaded by the system classloader and shared across all
components. Component implementation jars and their private dependencies are
loaded exclusively by the component's own classloader. Whether the component
classloader delegates parent-first or child-first is an open design question
— see below.

This is the application server model — OSGi, JBoss, WebLogic all use
variations of this pattern. Itara applies it at the component level.

**Appropriate for:** colocating independently developed components with
divergent dependency trees, which is the primary "service merging" use case
for existing systems.

---

## Detailed design — Strategy B

### Classloader hierarchy

```
System classloader
  ├── itara-common.jar          (registry, proxy, SPI interfaces)
  ├── itara-agent.jar           (agent itself)
  ├── order-api.jar             (API artifact — interfaces only)
  ├── inventory-api.jar
  ├── order-events.jar          (event contract artifacts)
  └── payment-api.jar

  Child classloader — order component (delegation order TBD)
    └── order/
          ├── order-impl.jar
          ├── spring-boot-*.jar
          └── (all order-specific dependencies)

  Child classloader — inventory component (delegation order TBD)
    └── inventory/
          ├── inventory-impl.jar
          ├── spring-boot-*.jar
          └── (all inventory-specific dependencies)
```

### Child-first vs parent-first loading

**[OPEN]** The delegation order of the component classloaders is an open
design question.

**Child-first:** the component classloader attempts to load from its own
directory before delegating to the parent. Provides stronger dependency
isolation — a component can use a different version of a library than the
parent has. Risk: if a class that should be shared (e.g. a Spring internal
or a JDBC driver) exists in both the component directory and the system
classpath, two distinct class identities exist in the JVM, which can cause
subtle failures at boundaries.

**Parent-first (standard Java delegation):** the parent classloader is
consulted first. Any class available on the system classpath is always loaded
from there, guaranteeing a single class identity for shared infrastructure.
A component cannot override a parent-provided version. Dependency isolation
is weaker — the component directory only adds what the parent doesn't have.

The correct choice depends on what ends up on the system classpath and
requires a spike to determine empirically. The API and event contract
artifacts being on the system classpath provides the guarantee that matters
most — either delegation order will use the same API class identity, since
the component directories explicitly exclude those jars.

### Proxy and activator registration

The agent loads each activator using the appropriate component classloader.
The activator instantiates the implementation — also loaded by the component
classloader. The implementation is then registered in the Itara registry
against the API interface, which was loaded by the system classloader.

Because the API interface class is always loaded by the same (system)
classloader, the proxy can be typed against it without any risk of class
identity mismatch between caller and callee. The proxy sees the system
classloader's version of the interface. The implementation behind it was
loaded by the component classloader but implements the same interface class.
Java's type system allows this because the interface was loaded by the parent.

### Deployment layout

```
deployment-root/
  lib/                          ← system classloader
    itara-common.jar
    itara-agent.jar
    order-api.jar
    inventory-api.jar
    order-events.jar
    payment-api.jar

  components/
    order/                      ← order component classloader
      order-impl.jar
      (order dependencies, excluding API jars)

    inventory/                  ← inventory component classloader
      inventory-impl.jar
      (inventory dependencies, excluding API jars)
```

---

## Transaction boundary

By default, separate classloaders mean separate transaction contexts.
Spring's `@Transactional` proxies are wired by each component's own
ApplicationContext and are not aware of each other. Calls across components
via Itara proxies cross the transaction boundary.

This is the correct default for services that were previously distributed —
they did not share transactions before, and they should not be expected to
share them after being colocated in the same JVM. Colocation is at the
communication layer, not at the data layer.

If shared transactions are required, Strategy A (common classloader) is the
appropriate choice — but at that point, proper codebase alignment is likely
the more honest solution.

The transaction boundary tradeoff must be documented clearly so it is a
conscious architectural decision, not a runtime surprise.

---

## Known challenges

**Spring Boot dual-application problem** — two Spring Boot applications in
the same JVM both want to bind an embedded server, scan the classpath, and
run auto-configuration. Separate classloaders help isolate auto-configuration
scanning. Embedded server conflicts still need to be resolved explicitly.

**Thread context classloader** — certain libraries (JAXB, JDBC drivers,
some Spring internals) use `Thread.currentThread().getContextClassLoader()`
rather than the class's own classloader for resource loading. The agent must
set the thread context classloader to the appropriate component classloader
before invoking activators and restore it afterwards. This is the same
requirement that already exists for the Kafka transport implementation.

**Static state in shared libraries** — if a library that exists in the
system classloader (because it's an Itara dependency) has static state,
that state is shared across all components. This is unavoidable and follows
the same rules as any shared library. Libraries that must have per-component
static state should be in the component directory, not the system classloader.

---

## Agent changes required

- Agent constructs classloader hierarchy at startup before loading any
  activator
- Each activator is loaded using the appropriate component classloader
- Thread context classloader set per component activation
- System classloader receives only itara-common, itara-agent, and all API
  and event contract artifacts

---

## Relationship to the existing single-classloader model

Both strategies must be supported. The wiring config should allow the operator
to choose per deployment group:

- **Isolated** (Strategy B, colocating independently developed
  components): separate classloaders per component
- **Shared** (Strategy A, current behaviour): common classloader, components
  share the full classpath

This is a deployment concern, not a component concern. The component code
does not change between the two strategies. The choice is made in the wiring
config based on the deployment topology and dependency situation.
