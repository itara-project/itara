# ADR 0001 — Topology as Configuration, Not Code

**Date:** April 2026  
**Status:** Accepted

## Context

Distributed systems today encode topology — which components exist, how they communicate, where they are deployed — directly in application code. HTTP clients are instantiated with hardcoded URLs, message producers are wired to specific topics, service discovery calls are embedded in business logic. This means that changing how components communicate requires code changes, redeployment, and coordination across teams.

The operational patterns that have emerged to manage this — blue-green deployments, expand-and-contract, strangler fig — are all external scaffolding around systems that fundamentally cannot evolve themselves. They are ceremony, not capability.

## Decision

Topology is a continuously adjustable variable, managed separately from component logic. No component shall encode where another component lives, how it is reached, what protocol is used, or whether a call is local or remote. These decisions belong exclusively to the wiring configuration.

The Itara runtime reads a declarative wiring config at startup, generates the necessary proxies and listeners, and hands off to the application. The application code is identical regardless of whether components are collocated in the same process or distributed across separate deployments.

## Consequences

- Component code is topology-agnostic. The same binary participates in a monolith or a distributed system depending only on configuration.
- Splitting, merging, or relocating components becomes a configuration change rather than a code change.
- The wiring config is the single source of truth for the topology of the entire system. Understanding the architecture does not require reading the application code.
- Components must not use transport-specific APIs, framework-specific annotations that imply topology, or hardcoded addresses. This is enforced by convention and by the contract model, not by the runtime.
- The runtime must validate the wiring config at startup and fail fast with clear errors before the application handles any requests. Topology errors must never surface at call time.
