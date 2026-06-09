# Wiring Config Versioning — Design Notes

**Status:** Thinking in progress. Not yet part of the specification.
**Date:** June 2026

---

## The problem

As a system grows, multiple artifacts must stay in sync with the master
wiring configuration: component builds, deployment manifests, config slices
delivered to individual processes, generated code for Rust components, and
eventually Orca's model of the running system. Currently there is no
mechanism to track which version of the wiring config any of these artifacts
were built or deployed against.

This creates a class of silent inconsistency. A component built against an
older version of the wiring config can be deployed alongside components built
against a newer one, and nothing catches it. A deployment manifest generated
from config v3 can be applied alongside a config slice that is already at v4.
The system has no way to know it is partially migrated, let alone report it.

---

## The insight

The master wiring configuration is the single source of truth for system
topology. Every artifact that depends on topology — components, manifests,
config slices, generated stubs — is implicitly built against a specific
version of that config. Making that dependency explicit and versioned gives
the tooling, the agent, and the controller a shared reference point for
reasoning about consistency.

The same philosophy already applies at a lower level: the `.itara` metadata
file carries `spec-version` and `core-version` so the agent knows what it
is loading before it loads anything. Wiring config versioning extends this
principle one level up — from the spec and core libraries to the topology
itself.

---

## Immutability

A config version, once published and built against, is immutable. Changes
always produce a new version. There is no mechanism to modify an existing
version — doing so would silently invalidate all artifacts built against it
and defeat the entire purpose of the versioning scheme. The tooling must
enforce this without exception.

---

## The proposed model

The master wiring configuration carries an explicit version field:

```yaml
version: "4"

nodes:
  - id: orderNode
    component: order
  ...
```

The version is a monotonically increasing integer, incremented whenever the
wiring config changes in a way that affects any artifact built against it.
Semantic versioning is intentionally not used here — the config version is
a deployment coordination token, not a compatibility signal. Compatibility
is expressed through the existing `.itara` metadata fields.

---

## What carries the config version

Only artifacts with a build-time dependency on the wiring configuration need
to declare the config version they were built against. The distinction is
between build-time topology dependents and runtime topology dependents.

Every artifact informed by the wiring config at build time must carry the
config version it was built against. The wiring config tells you which
components you connect to — which determines which API artifacts you depend
on, which generated code gets produced, which deployment units are created.
This applies regardless of language or artifact type:

- **Java components** — depend on the API jars of the components they
  connect to, as declared in the wiring config. A change to the relevant
  connections may require updated dependencies.
- **Rust components** — generated proxy code and dispatchers are produced at
  build time from the wiring config. If the relevant part of the config
  changes, the Rust component must be rebuilt.
- **Deployment manifests** — generated from the wiring config via the template
  mechanism. A manifest generated from v3 may be incorrect for v5 if the
  topology changed.
- **Any future artifact where build or code generation is informed by the
  wiring config** — the list grows as the tooling matures.

For Rust components, the `.itara` metadata file gains a `wiring-version` field:

```toml
[artifact]
kind        = "component"
id          = "payment"
version     = "1.2.0"
api-version = "1.x"

[deployment]
wiring-version = "4"    # built against wiring config v4
```

For deployment manifests, the config version is carried as an annotation
or label:

```yaml
# Kubernetes example
metadata:
  labels:
    itara.io/wiring-version: "4"
```

**Config slices** — when the master config is sliced for individual processes,
the slice carries the source config version. The agent reads it at startup
and can report it via the health endpoint.

---

## What the tooling does with this

**`itara verify`** — extended to check that build-time topology dependents
are compatible with the current config version. The check is slice-aware:
if the part of the config relevant to a specific component has not changed
between the version it was built against and the current version, it is
compatible regardless of how many versions have passed. A Rust component
built against v1 is still compatible at v5 if its relevant slice is
unchanged. Only if the relevant slice has changed since the build version
does the component need to be rebuilt.

**`itara inspect`** — can report the current wiring version alongside the
topology summary. When connected to a registry, it can show which running
components are on which version — making partial migrations visible.

**`itara diff <v3> <v4>`** — comparing two config versions produces a
structured description of what changed: nodes added, nodes removed,
connections changed, transport types changed. This is the input for
understanding the blast radius of a config change before applying it.

---

## Agent behaviour

The agent reads the config version from the slice it receives at startup.
It reports this version:

- In the startup log, so it is visible in deployment logs
- Via the health endpoint (`/itara/health`), so orchestrators can query it
- As a tag on all observability events, so traces carry the config version
  of the process that produced them

If a component's declared `wiring-version` does not match the agent's
current config version, the agent SHOULD log a warning. Whether this
becomes a hard failure is a configuration decision — strict mode for
production, permissive for development.

---

## Controller behaviour

Orca tracks the wiring config version as a first-class property of the
running system. A topology change produces a new config version. Orca
knows:

- Which nodes are running against the current version
- Which nodes are still on a previous version
- What the expected final state is and how far the rollout has progressed

This makes partial migrations — which are the normal case for any
non-trivial topology change — visible and manageable rather than implicit
and dangerous.

---

## Audit and compliance

In regulated environments, the config version history provides a complete
record of topology changes over time. Combined with the observability model,
it becomes possible to answer: what was the exact topology at time T, which
components were colocated, what transport was in use between any two
components, and who changed it.

This is a real compliance requirement in financial services and other
regulated industries, where architecture changes must be traceable and
auditable.

---

## Open questions

**Version storage** — the version lives in the wiring config file today.
As the config moves toward a database or registry, the version becomes a
property of the stored document. The semantics are the same; the storage
mechanism changes.

**Backward compatibility** — components built before this feature is
introduced will not carry a wiring version. The standard approach applies:
the feature is introduced in one version of the framework and treated as
optional with a warning, then mandated in a later version. The transition
period is explicit and versioned.

**Version coordination in teams** — whoever owns the master wiring config
owns the version. How the config evolves in a multi-team environment —
who proposes changes, who approves them, how conflicts are resolved — is
not yet worked out and will depend on how the controller and tooling mature.
