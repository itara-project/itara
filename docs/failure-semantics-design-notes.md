# Failure Semantics SPI — Design Notes

**Status:** Resolved. Incorporated into spec §14.  
**Date:** June 2026

---

## The problem

Itara currently surfaces infrastructure failures as typed error contracts
(`ItaraRemoteException` with CHECKED, RUNTIME, or TRANSPORT kind) at the
call site. The caller receives the error and decides what to do with it.

This is correct behaviour for the current state of the platform, but it is
incomplete. In production systems, the response to a transient failure is
rarely "surface the error to the caller." It is more often "retry with
backoff," "try a fallback," "open a circuit after N failures," or some
combination of these. These are topology concerns — they belong in the
wiring config alongside the transport declaration, not in the component code.

The FAQ already commits to this: "Failure semantics — retries, timeouts,
circuit breaking — are connection-level configuration, not component-level
code. They belong in the wiring config alongside the transport declaration."
This design note captures the thinking on how to fulfil that commitment.

---

## The two approaches

### Option A — Single failure semantics SPI

A single SPI that receives the call as a lambda and owns the complete
execution strategy: retry loops, waits, timeouts, circuit breaking,
idempotency checks, fallback logic.

The implementation owns the strategy entirely. A Resilience4j-backed
implementation, a service-mesh-aware implementation, a business-specific
implementation that accounts for domain rules — all are possible without
Itara anticipating them.

The idempotency flag comes from the `.itara` metadata file, where API
artifacts declare which methods are not idempotent. Methods not listed
are assumed idempotent and safe to retry.

### Option B — Separate SPIs per concern

Separate SPI interfaces for retry, timeout, and circuit breaking, each
applied independently by the proxy.

The problems with this approach:

- **Composition is hard.** Retry, timeout, and circuit breaker interact in
  non-trivial ways. What happens when a retry fires inside a circuit that is
  half-open? What timeout applies — per attempt or total? Frameworks that
  layer these independently produce well-documented configuration complexity.
  Itara should not inherit that complexity.

- **Extensibility is limited.** A fixed set of separate SPIs cannot
  accommodate strategies Itara didn't anticipate — a bulkhead, a rate
  limiter, a custom backoff that reads from a business rule engine. The
  single SPI approach has no such limitation.

- **The implementation owns it anyway.** In practice, most teams reach for
  a library like Resilience4j or Polly that already composes these concerns.
  Separate SPIs force that library to be split across Itara's SPI boundaries,
  which serves nobody.

---

## Why the single SPI is the right approach

The pattern is consistent with how Itara handles every other pluggable
concern. The transport SPI owns byte movement. The serializer SPI owns
encoding. The observer SPI owns event handling. None of them are split into
sub-SPIs. Failure semantics follows the same principle: define the contract,
let the implementation own the strategy.

The lambda approach is what makes this work. The proxy doesn't retry — it
hands the call to the failure semantics implementation and asks for a result.
The implementation decides everything: how many attempts, what backoff,
what circuit state, when to give up.

---

## Timeout enforcement model

Timeout enforcement is split across two sides — the transport and the failure
semantics implementation — and each side declares its capability and
configuration independently. This keeps them decoupled.

**Why two independent flags, not a strategy enum**

An earlier design considered a `timeoutHandling: native | external | both`
enum on the failure semantics block. This was rejected because it would require
the failure semantics block to describe the transport's behaviour, creating
coupling between the two sides. The failure semantics implementation would need
to know that the transport is configured to handle timeout natively in order to
avoid doing it itself, or the wiring config author would need to understand both
sides simultaneously to pick the right enum value.

Two independent boolean flags — one on the transport connection, one on the
failure semantics block — mean each side declares itself without reference to
the other. The tooling validates the combination. Neither side needs to know
what the other declared.

**Per-attempt timeout vs absolute timeout**

A per-attempt timeout constrains a single call attempt. It is passed to the
transport on every call regardless of who enforces it. Either the transport
enforces it natively, the failure semantics implementation enforces it by
external interruption, or both — independently declared.

An absolute timeout is a hard ceiling on total execution time across all
attempts. It is always enforced externally by the failure semantics
implementation if it supports it. It is exempt from the transport's
safe-to-interrupt declaration by design: its purpose is to catch infinite
loops and total hangs where no per-attempt mechanism would fire. It is an
escape hatch, not a primary timeout mechanism.

**Transport interrupt safety**

Not all transports are safe to interrupt externally. A transport that may
leave connections in an inconsistent state when interrupted must declare this.
The failure semantics implementation must not apply external interruption of
the per-attempt timeout to such transports. The absolute timeout may still
interrupt — that is its purpose.

**Tooling as the safety net**

The combination of capability declarations and configuration flags gives the
tooling enough information to catch mismatches statically. A timeout configured
with no enforcement mechanism is a warning. A timeout configured against a
declared incapability is an error. This is the Itara pattern: make the
configuration explicit enough that violations are detectable before deployment.

---

## Resolved open questions

**Timeout scope** — the per-attempt timeout and the absolute timeout are
separate fields with unambiguous scope. The implementation is free to define
additional semantics in its `params` block.

**Fallback** — deferred. Not required for v0.2. The single-lambda approach
does not preclude adding a second lambda for fallback in a future version.

**Observability** — the four-event model is not affected by failure semantics.
`CALL_SENT` and `RETURN_RECEIVED` fire once per logical call. Retry attempts
are observable via custom spans (§9.7). See ADR 0017 for full reasoning.

**Direct connections** — failure semantics do not apply to direct (colocated)
connections. A direct call that throws is a real failure with no transport
involved; the overhead and indirection of a failure semantics wrapper is
inconsistent with the zero-overhead colocation guarantee.

**Default implementation** — the built-in `noop` implementation ships as the
default, requiring zero configuration. The built-in standard implementation
(`built-in`) ships alongside it, covering retry, timeout, and basic circuit
breaking for teams that want it out of the box.
