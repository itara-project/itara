# ADR 0013 — Rust: Dynamic Approach Evaluation and Future Direction

**Date:** May 2026
**Status:** Accepted
**Applies to:** Rust implementation

---

## Context

The Rust reference implementation was built using the same dynamic plugin architecture as the Java implementation: component API crates produce cdylibs, the agent loads them at startup via `libloading`, and observability is wired through a context handler loaded from a separate cdylib.

This approach was deliberately chosen as the first attempt. It proved something important: Itara's core claim — that topology is a runtime configuration decision, independent of the language a component is written in — holds in Rust. Cross-language calls between Java and Rust components work. Distributed traces propagate correctly across the language boundary via W3C headers. The spec is genuinely language-neutral.

That proof of concept was worth building. This ADR records what was learned from it and where the Rust implementation goes next.

---

## Why the full dynamic approach is suboptimal for Rust

Rust's design philosophy is built around making correctness a compile-time property. Its ownership model, type system, and lack of runtime reflection are not limitations — they are deliberate choices that produce safe, fast, predictable software. The dynamic plugin architecture works against these properties in several specific ways.

**Unsafe surfaces.** Passing fat pointers across cdylib boundaries, reconstructing trait object references from raw pointer pairs, asserting Send and Sync through newtype wrappers — these are all correct but inherently unsafe. Every proxy call site carries unsafe blocks that a Rust developer would rightly question. The unsafety is contained and documented, but it is noise that the language was designed to avoid.

**FFI-unsafe warnings.** The compiler warns on every factory function signature that crosses the cdylib boundary with Rust-specific types. These warnings are acknowledged debt, not mistakes, but they signal that the design is working against the grain of the language.

**Context propagation complexity.** The thread local span stack — needed for correct context chaining across colocated component calls — requires a separate cdylib to avoid static duplication across multiple loaded libraries. This is a solvable problem, and the current implementation solves it, but the solution is more elaborate than it should be for what is conceptually a simple operation.

**Runtime overhead that Rust shouldn't have.** Dynamic library loading, fat pointer casting, and trait object dispatch are all present in a language that is chosen precisely because developers want to avoid these costs. The zero overhead principle applies here too.

None of this makes the current implementation wrong. It makes it a proof of concept rather than a long-term architecture.

---

## A mixed approach is the next direction to investigate

Rather than abandoning the dynamic approach entirely or accepting it as final, the next direction is a mixed approach — some things resolved at build time, some remaining configurable at runtime. The exact boundary between these has not been decided and will not be decided prematurely.

The reason a mixed approach is viable — and why it fits naturally with Itara's design — comes from one of Itara's core properties: topology is concentrated. Component code expresses business logic. The interaction between components — how they find each other, how they communicate, how context is propagated — is Itara's concern, not the component's. This means the complexity of the dynamic approach is not spread across the codebase. It is localised in the agent, the proxy, and the dispatcher — the layer Itara owns entirely.

Because Itara owns this layer completely, it can choose how to implement it per language and per deployment model. A component author writes a plain trait and an activator. What sits between components — the proxies, the dispatchers, the context threading, the observability hooks — can be generated, composed, or loaded dynamically without the component knowing or caring. In Rust, generating that layer at build time from the wiring config is a natural fit for the language. The component code does not change. The wiring config does not change. Only the mechanism by which Itara assembles the layer changes — from runtime loading to build-time generation.

This is not a workaround. It is the mixed approach following directly from the principle that topology is Itara's concern, localised in Itara's layer, and therefore fully within Itara's control to implement as best suits the language.

What is clear is that Itara's tooling ecosystem, which is already a core part of the vision, is the natural mechanism for whatever build-time composition is needed. Itara has always planned a CLI that understands the wiring config and the component metadata. That tooling can do more than validate — it can participate in the build. The form this takes in Rust, and which decisions belong at build time versus runtime, will be shaped by experimentation and by the Rust community's input. The implementation team has opinions and will follow them, but good ideas from the community will be taken seriously.

---

## This does not conflict with Itara's values

The core values are unchanged:

- Component code does not know about topology. This remains true regardless of whether topology is resolved at build time or runtime.
- Wiring is configuration, not code. The wiring config remains the single source of truth.
- The zero overhead principle is strengthened, not weakened, by moving toward compile-time composition where appropriate.
- The tooling ecosystem is load-bearing. This direction relies on the CLI and build tooling that were already planned — it is a natural extension of the vision, not a detour.

---

## This is not a failure

The dynamic Rust implementation proved what it was built to prove. It demonstrated that a Rust component and a Java component can participate in the same Itara topology, share the same wiring config format, and produce a single distributed trace. It validated the spec. It produced working code that runs today.

The dynamic approach is not the long-term Rust architecture. That is a conclusion reached by building and learning, which is the correct way to reach it. The alternative — declaring the architecture before building — would have produced a more polished ADR and a less honest one.

---

## Status of the current Rust implementation

The current dynamic implementation remains in the repository as the working reference for the Java-Rust cross-language story. Observability will be completed to the same standard as the Java implementation. The implementation will then be stabilised at this level while the mixed approach is investigated.

No timeline is set for the mixed approach. It will be informed by Show HN feedback, community engagement, and the team's own judgment about where Rust fits in the broader Itara ecosystem.
