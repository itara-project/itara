# ADR 0008 — Language-Neutral Metadata File Over META-INF

**Date:** May 2026  
**Status:** Accepted

## Context

The Java implementation uses META-INF service files — the standard Java ServiceLoader convention — to declare SPI implementations (activators, transports, serializers, observers). This works well in Java but is entirely Java-specific and cannot be used by Rust, Go, Python, or any other language implementation.

As Itara moves toward language neutrality, a discovery and metadata mechanism is needed that works across all languages and runtimes.

## Decision

Every Itara artifact — component implementations, transport implementations, serializer implementations — ships with a companion metadata file. The metadata file has the same name as the artifact with a `.itara` extension:

```
calculator-component.dll
calculator-component.itara

itara-transport-http.dll
itara-transport-http.itara
```

The metadata file is TOML format. Minimum required fields:

```toml
[artifact]
kind = "component"          # component | transport | serializer | observer
id = "calculator"           # component-id for components, name for SPIs
version = "1.2.0"           # semver, implementation version
api-version = "1.x"         # semver range this implementation satisfies

[runtime]
language = "rust"           # rust | java | go | python | ...
compiler = "1.78+"          # minimum compiler/runtime version

[itara]
spec-version = "0.1"        # Itara spec version this artifact targets
core-version = "0.1+"       # minimum itara-core version required
```

Additional fields (idempotency declarations, supported transports, communication restrictions) will be added as the ecosystem matures. The format is extensible — unknown fields are ignored by older agents.

The Java META-INF mechanism will be replaced by this format. During transition, both may be supported.

Metadata files for SPI implementations (transports, serializers) are hand-written — there are few enough of them that automation is not necessary. Build tool generation may be added later.

## Consequences

- The agent scans the lib directory for `.itara` files before loading any `.dll`/`.so`. It understands what is installed without loading anything, enabling fast startup validation and clear error messages.
- Version compatibility checks happen before any code is loaded. If a component requires spec-version 0.2 and the agent implements 0.1, the failure is immediate and explicit.
- The `itara inspect` CLI can read a lib directory and report what is installed, what versions, and what compatibility issues exist — without starting a runtime.
- Orca reads metadata files to understand what is deployed, what API versions are in use, and what topology changes are safe before approving them. Component metadata files are therefore required for Orca to reason about application compatibility — a component without a metadata file cannot be safely managed by Orca.
- Components are not required to ship metadata files for the agent to load and run them. However, metadata files are required for Orca compatibility checking and for participation in managed deployments. As the tooling matures, metadata files will become effectively mandatory for any production use.
- The compiler version field in the metadata addresses the fat pointer layout compatibility constraint identified in ADR 0006. Components and the agent must be compiled with compatible toolchains — this is now declared and checkable rather than assumed.
