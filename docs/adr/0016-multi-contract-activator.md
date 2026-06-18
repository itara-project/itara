# ADR 0016 — Multi-Contract Activator Model

**Status:** Accepted  
**Date:** June 2026

---

## Context

The event-driven topology introduced in spec §13 and implemented in the
event-driven spike revealed a strain in the activator model. `ItaraActivator<T>`
was designed for the one-component-one-contract world: one activator, one
type parameter, one contract interface. A consumer component that implements
multiple event contracts breaks this assumption — one activator must produce
an instance that satisfies multiple contract ids, and the agent must know
which contract ids that instance handles.

The spike introduced a registry alias mechanism (`registerAlias(contractId,
componentId)`) as a working patch. This ADR records the principled solution
that replaces the ad-hoc alias registration with a deliberate design.

---

## Decision

### Drop the generic from `ItaraActivator`

The type parameter `T` on `ItaraActivator<T>` serves no runtime purpose.
Casting happens at registry lookup time, based on the type requested by the
caller — the activator's declared type is never used for this. The generic
is misleading: it implies the activator owns the type contract, when in fact
the registry does.

The activator becomes a plain factory:

```java
// Before
public interface ItaraActivator<T> {
    T activate(ItaraRegistry registry);
}

// After
public interface ItaraActivator {
    Object activate(ItaraRegistry registry);
}
```

This is a breaking change for existing activator implementations, which must
drop their type parameter. The behavioural change is zero — only the signature
changes.

### Declare implemented event contracts in the component `.itara` file

A component that consumes event contracts declares them explicitly in its
`.itara` metadata file under `[implemented-event-contracts]`, with the full
contract id and the version of the events artifact the implementation was
written against:

```toml
[artifact]
kind    = "component"
id      = "notification-service"
version = "1.0.0"

[implemented-event-contracts]
contracts = [
  { id = "order-events/order-paid",      version = "1.0.0" },
  { id = "order-events/order-cancelled", version = "1.0.0" }
]
```

This declaration is language-neutral — it lives in `.itara`, not in a
Java-specific descriptor. It is the authoritative source of truth for what
event contracts a component implements, at what version. The agent reads it
at startup; `itara verify` validates against it at build time.

Versions follow semantic versioning, consistent with all other version
declarations in Itara. Compatibility checks apply standard semver rules:
a component declaring `version = "1.0.0"` is compatible with any events
artifact version `>=1.0.0 <2.0.0`. A major version mismatch is an ERROR;
a minor or patch mismatch within the same major version is compatible.

### Retain the alias mechanism, promoted from patch to design

The registry alias (`contractId → componentId`) introduced in the spike is
retained as the agent's internal mechanism for N:1 contract-to-component
mapping. It is no longer ad-hoc — it is a direct consequence of reading the
`[implemented-event-contracts]` list from `.itara`. The agent registers all
aliases for a component before starting any listeners, preserving the ordering
guarantee identified in the spike.

---

## Consequences

**Static validation:** `itara verify` gains three new checks on
`[implemented-event-contracts]` declarations:

- A declared contract id not resolvable against any events artifact in the
  artifact directory → ERROR
- A declared version incompatible with the resolved events artifact version
  → ERROR
- A component node wired to a virtual node whose contract is not listed in
  the component's `[implemented-event-contracts]` → ERROR

The last check is the primary payoff: a component cannot be silently wired to
a topic it does not implement. This is the same class of guarantee that
`itara verify` already provides for point-to-point connections — extended to
the event-driven topology.

**Breaking change:** existing `ItaraActivator<T>` implementations must drop
the type parameter. This is a compile-time-only change with no runtime impact.
The breaking change will be communicated clearly in the v0.2 release notes.

**Spec update:** §3.5 (Activator) is updated to remove the reference to the
generic type parameter and to specify the `[implemented-event-contracts]`
declaration in the `.itara` metadata format.

---

## Alternatives considered

**Declare consumed contracts on the activator interface** — an `eventContracts()`
method returning the list of implemented contract types. Rejected: it adds
behaviour to a pure factory interface, it is Java-specific, and it duplicates
information that already belongs in the language-neutral `.itara` metadata.

**Infer implemented contracts from the Java class hierarchy** — scan the
component implementation class for implemented event contract interfaces at
startup. Rejected: reflection-based inference is fragile, slow, and unavailable
in languages without runtime type inspection. The `.itara` declaration is
explicit and language-neutral.

**One activator per event contract** — a separate activator for each contract
the component implements, each returning the same instance. Rejected: it
violates the single-instance principle and produces multiple activator
invocations for one component, which the agent would need special logic to
deduplicate.

---

## References

- Spec §3.5 — Activator (to be updated)
- Spec §13 — Event-Driven Topology
- ADR 0015 — Event-Driven Topology: Virtual Nodes and Events Artifacts
- Spike findings: SPIKE-13-FINDINGS.md
