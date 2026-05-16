# ADR 0005 — API and Implementation as Separate Artifacts

**Date:** April 2026  
**Status:** Accepted

## Context

A component has two distinct concerns: its contract (what it accepts and returns) and its implementation (how it fulfils that contract). These could be packaged together in a single artifact or separated into two artifacts — an API artifact containing only the contract, and an implementation artifact containing the logic.

In a distributed topology, callers and callees may run in separate processes. The question is what each process needs to know about the other.

## Decision

Every Itara component consists of two separate artifacts:

- **API artifact** — contains only the contract: the interface/trait definition, parameter types, and return types. No implementation logic. No transport. No topology knowledge. This is what callers depend on.
- **Implementation artifact** — contains the implementation, the activator, and nothing else. Callers never depend on this artifact.

In Java: separate jars (`calculator-api.jar`, `calculator-component.jar`).  
In Rust: separate crates (`calculator-api`, `calculator-component`), where the component crate produces both a dynamic library (`.so`/`.dll`) and optionally a standalone binary.

The Itara agent generates proxies from the API artifact alone. In HTTP topology, the implementation artifact is absent from the caller's classpath/link path entirely — the caller genuinely cannot see the implementation.

## Consequences

- The separation is enforced by the build system, not by convention. A caller that accidentally depends on the implementation artifact will fail to compile if the implementation is absent, which is the correct failure mode.
- Callers are guaranteed to depend only on the contract. This makes topology switching safe — there is no path by which caller code can call implementation code directly when the topology says otherwise.
- API versioning and implementation versioning are independent. A new implementation that satisfies the same API contract can be deployed without recompiling callers.
- The API artifact is the unit of contract management. Schema registries, compatibility checks, and IDL generation all operate on the API artifact.
- This separation is the same idea as gRPC's `.proto` files — a language-neutral contract that both sides compile against. Itara's IDL will eventually replace the language-specific API artifact with a generated equivalent.
