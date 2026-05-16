# ADR 0002 — Eager Wiring Over Instrumentation-on-Load

**Date:** April 2026  
**Status:** Accepted

## Context

Two approaches exist for intercepting component calls at the JVM level:

**Eager wiring (premain):** The agent runs before the application starts, reads the full wiring config, validates the topology, generates proxies, starts infrastructure (HTTP servers, listeners), and hands a fully wired runtime to the application. By the time the first application thread runs, everything is resolved.

**Instrumentation-on-load:** Classes are instrumented as they are loaded by the classloader. Proxies and listeners are set up lazily as relevant classes appear. This is how general-purpose APM agents (Datadog, New Relic) work because they cannot control the application they observe.

The instrumentation approach was raised as a potential solution for framework compatibility (Spring Boot classloading, nested jars).

## Decision

Itara uses eager wiring. All topology resolution, proxy generation, and infrastructure startup happens before the application handles its first request. The invariant is: **by the time the first application thread runs, the topology is fully wired, validated, and all infrastructure is started.**

Instrumentation may be used as a *mechanism* to solve specific classloading problems (such as Spring Boot nested jar structure) but must never change the *semantics* — wiring must still be complete before the application starts.

## Consequences

- Topology errors surface at startup with clear, actionable messages. Missing jars, misconfigured connections, and contract mismatches are caught before any request is handled.
- Infrastructure (HTTP servers, Kafka listeners) is started by the agent before the application runs. This is a hard requirement — an HTTP server cannot wait for the first request to arrive before starting.
- Debugging and maintenance are simpler because the system is fully configured at a single, well-defined point in time.
- Framework compatibility issues (Spring Boot, Quarkus) must be solved within the constraint of eager wiring. The solution may use instrumentation machinery but must preserve startup-time validation semantics.
- The instrumentation-on-load approach is rejected not because it is technically inferior for all use cases, but because Itara is the wiring layer, not an observer. Itara constitutes the infrastructure — it does not observe something that already works.
