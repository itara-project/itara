# Template-Based Deployment Manifest Generation — Design Notes

**Status:** Thinking in progress. Not yet part of the specification.  
**Date:** June 2026

This document captures the design thinking for topology-aware deployment
manifest generation in Itara. It will be refined and merged into the
specification once the core tooling is stable and Show HN has landed.

---

## The problem

Itara's deployment groups (§11.4) tell you which components must be colocated
in the same process. That is the right first step. But it doesn't tell you how
to deploy that process — what the k8s manifest looks like, what the Docker
Compose service block contains, what environment variables to set, what startup
command to run.

The naive solutions don't work:

- **Generate full manifests from scratch** — requires an enormous extension
  surface. Resource limits, replica counts, namespace conventions, annotations,
  sidecars, secrets — every org does these differently. A tool that generates
  complete manifests either becomes a monster that contains everything, or
  produces manifests that need heavy manual editing every time anyway.

- **Generate manifest chunks to copy** — reduces manual work slightly but
  doesn't solve the automation problem. A topology change still requires a
  human to update the manifests.

- **Leave it entirely to the user** — the current state. Defeats the purpose
  of having deployment groups in the first place.

The root cause: manifest generation is a two-part problem. Itara owns the
topology-derived parts. The organisation owns everything else. Any solution
that conflates the two will either be too rigid or too much work to maintain.

---

## The idea

Itara provides a `generate` command that accepts a template and fills in a
defined set of topology-derived symbols. The template is authored by the team
once — it is their existing manifest format, with Itara-specific placeholders
dropped in where topology-derived values belong.

```
# Existing k8s Deployment manifest, with Itara placeholders

apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{itara.group.id}}
spec:
  template:
    spec:
      containers:
        - name: {{itara.group.id}}
          image: my-registry/{{itara.group.id}}:latest
          env:
            - name: ITARA_CONFIG
              value: /config/wiring.yaml
            - name: ITARA_NODES
              value: {{itara.nodes}}
          ports:
            - containerPort: {{itara.port.inbound}}
```

Everything outside the placeholders is the team's own — resource limits,
replica counts, namespace, annotations, sidecars, secrets. Itara touches
nothing it doesn't own. The team touches nothing Itara owns.

---

## Symbol set

The symbols Itara recognises and fills in are derived from the wiring
configuration and the `.itara` metadata files. Minimum viable set:

| Symbol | Value |
|--------|-------|
| `{{itara.group.id}}` | Derived deployment group identifier |
| `{{itara.nodes}}` | Comma-separated list of node IDs in this group (for `ITARA_NODES`) |
| `{{itara.component.id}}` | Component identifier for a single-component group |
| `{{itara.config.path}}` | Path or reference to the wiring configuration |
| `{{itara.port.inbound}}` | Declared inbound port for this group |
| `{{itara.language}}` | Runtime language from `.itara` metadata |
| `{{itara.version}}` | Component version from `.itara` metadata |

The symbol set will grow as the tooling matures. Unknown symbols in a template
MUST be flagged as errors, not silently left unreplaced.

---

## Template authoring model

One template per deployment group type. Groups that are structurally identical
— same language, same connection pattern — can share a template. Groups that
differ need their own.

The tool alerts when a new deployment group appears in the wiring configuration
and no template covers it. This is the same principle as verify: catch the gap
before deployment, not after.

The template format is not prescribed. YAML, TOML, shell scripts, Docker
Compose fragments — any text format works. The tool performs text substitution;
it does not parse or validate the output format. That is the template author's
responsibility, and deliberately so.

---

## Versatility

The same mechanism works for any deployment target:

- Kubernetes manifests
- Docker Compose service blocks
- Nomad job definitions
- systemd unit files
- Bare metal startup scripts
- CI/CD pipeline definitions

The template is the adapter. Itara provides the topology-derived values.
The org provides the rest. Neither needs to know about the other's concerns.

---

## Registry integration

When the tooling gains registry integration, the generate command can:

- Fetch `.itara` metadata from the registry rather than a local lib dir
- Alert when a new deployment group appears in the wiring configuration
  without a registered template
- Validate that the generated output references component versions that
  are actually available in the registry

---

## Open questions

**Template discovery** — how does the tool know which template applies to
which deployment group? By naming convention, by explicit mapping in a config
file, by a registry entry? To be decided.

**Multi-group output** — does one invocation generate output for all groups,
or one group at a time? Likely both modes are useful: per-group for targeted
updates, all-groups for a full topology generation pass.

**Symbol conflicts** — what if a template already uses `{{...}}` syntax for
its own purposes (Helm, Mustache, etc.)? The symbol delimiter may need to be
configurable.

**Validation of output** — should the tool optionally validate the generated
output against a schema (e.g. validate generated k8s manifests against the
k8s API schema)? Probably an extension concern, not a core one.

---

## What this is not

This is not a full deployment tool. Itara does not manage deployments,
orchestrators, or infrastructure. The generate command produces text files.
What happens to those files — how they are applied, versioned, or rolled
back — is the concern of the team's existing deployment pipeline.

This is not a templating engine with logic, loops, or conditionals. The symbol
set is intentionally simple. Complex template logic belongs in the template
language the team already uses (Helm, Kustomize, etc.), not in Itara.
