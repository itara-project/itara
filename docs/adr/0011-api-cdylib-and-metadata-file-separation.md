# ADR 0011 — API Artifact as Dual-Output with Runtime Proxy Symbol

**Date:** May 2026  
**Status:** Accepted

## Context

The Rust agent needs to create outbound proxies at runtime for remote components. A proxy implements a component's API trait and wraps a transport. The agent must do this without compile-time knowledge of the API trait — the agent must remain ignorant of specific component types.

In Java this is solved by the classpath and reflection: the API jar is on the classpath, the agent uses `Proxy.newProxyInstance()` to create a proxy for any interface at runtime. Rust has no classpath and no reflection.

The metadata file format established in ADR 0008 already solves artifact discovery and description — the agent reads `.itara` files to know what each artifact is before loading anything. This ADR addresses the remaining question: once the agent knows it needs a proxy for a given component, how does it create one without knowing the Rust trait type at compile time?

## Decision

Every component API crate declares both `rlib` and `cdylib` as crate types:

```toml
[lib]
crate-type = ["rlib", "cdylib"]
```

- **`rlib`** — for compile-time use. Other Rust crates depend on it to know the trait.
- **`cdylib`** — for runtime use. The agent loads it to create proxies without knowing the trait at compile time.

The API cdylib's `.itara` metadata file (ADR 0008) declares `kind = "api"`. The agent uses this to distinguish API cdylibs from component and transport cdylibs.

The `#[itara_component]` macro generates one C-compatible symbol into the cdylib:

```rust
#[unsafe(no_mangle)]
pub extern "C" fn itara_create_proxy(
    transport: Box<dyn ItaraTransport>
) -> Box<dyn ItaraComponent> {
    Box::new(CalculatorServiceProxy::new(transport, "calculator"))
}
```

The agent calls this symbol with the configured transport. It never knows the Rust trait type. The proxy construction is fully encapsulated in the API cdylib. The component id comes from the `.itara` metadata file — not from a symbol in the dll.

## Why the component id is not a symbol

An alternative considered was exporting an additional `itara_component_id()` symbol so the dll is fully self-describing without a metadata file. This was rejected for the same reason the metadata file was adopted in ADR 0008: tools that inspect should not need to execute. The agent, CLI, Orca, and deployment tooling all need to know what a dll represents. Loading native code just to read an identifier is unnecessary when a text file serves the same purpose with no dependencies and no platform-specific handling.

A self-describing symbol may be added later as an upgrade — useful for consistency validation between the metadata file and the artifact — but the metadata file remains the authoritative source of descriptive information.

## Consequences

- Every component API crate produces two build outputs. This is one more artifact than Java's single jar, but serves two clearly distinct consumers with different requirements. The deployment tool handles placement — developers never manage lib directories manually.
- The agent startup sequence follows from ADR 0008: read all `.itara` metadata files first, then load artifacts in the correct order. API cdylibs are loaded when the wiring config declares a remote connection for their component id.
- The `#[itara_component]` macro generates `itara_create_proxy()` into the API cdylib. Until the macro exists, this symbol is written by hand. The interim workaround — manual proxy registration before `itara_init()` — is tracked in a separate GitHub issue.
- Non-Rust language implementations follow the same pattern: a loadable artifact exposing `itara_create_proxy()` via C ABI, with a `.itara` metadata file alongside it.
