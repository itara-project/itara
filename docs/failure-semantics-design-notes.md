# Failure Semantics SPI — Design Notes

**Status:** Thinking in progress. Not yet part of the specification.
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

```java
public interface ItaraFailureSemantics {

    /**
     * Execute the call with whatever failure strategy this implementation
     * applies. The call is provided as a lambda — the implementation decides
     * how many times to invoke it, when to wait, when to give up.
     *
     * @param call            the component call to execute
     * @param methodName      name of the method being called, for logging
     * @param idempotent      whether this method is declared idempotent
     *                        in the .itara metadata — safe to retry if true
     * @param config          connection-level configuration from the wiring
     *                        config (timeout, max retries, backoff, etc.)
     * @return                the result of the call
     * @throws ItaraRemoteException if the strategy gives up
     */
    Object execute(Callable<Object> call,
                   String methodName,
                   boolean idempotent,
                   FailureSemanticsConfig config) throws ItaraRemoteException;
}
```

The implementation owns the strategy entirely. A Resilience4j-backed
implementation, a service-mesh-aware implementation, a business-specific
implementation that accounts for domain rules — all are possible without
Itara anticipating them.

The idempotency flag comes from the `.itara` metadata file, where API
artifacts declare which methods are not idempotent. Methods not listed
are assumed idempotent and safe to retry. This was always load-bearing
for this reason.

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
sub-SPIs. Failure semantics should follow the same principle: define the
contract, let the implementation own the strategy.

The lambda approach is what makes this work. The proxy doesn't retry — it
hands the call to the failure semantics implementation and asks for a result.
The implementation decides everything: how many attempts, what backoff,
what circuit state, when to give up. Itara provides the inputs it has
(the call, the method name, the idempotency flag, the connection config)
and steps aside.

---

## Wiring config integration

Failure semantics are declared per connection in the wiring config, alongside
the transport and serializer:

```yaml
connections:
  - from: orderNode
    to: inventoryNode
    type: http
    serializer: json
    failure-semantics: resilience4j
    failure-semantics-config:
      max-attempts: 3
      backoff-ms: 100
      timeout-ms: 2000
      circuit-breaker: true
```

The `failure-semantics` field references the identifier declared in the
implementation's `.itara` metadata file. The `failure-semantics-config`
block is passed as-is to the implementation — Itara does not interpret it.

---

## The idempotency contract

The failure semantics implementation receives the `idempotent` flag for every
call. The flag comes from the `.itara` metadata file of the API artifact,
where the `[methods]` section lists which methods are not idempotent.

A conforming failure semantics implementation MUST NOT retry a non-idempotent
call without explicit configuration permitting it. The default behaviour for
non-idempotent methods on failure MUST be to surface the error immediately,
not to retry.

This is not enforced by Itara — it is a contract requirement on the
implementation. A failure semantics implementation that retries non-idempotent
calls silently is non-conforming.

---

## Open questions

**Timeout scope** — should the timeout in the wiring config apply per attempt
or to the total execution including retries? Both are valid. The implementation
should be free to define this, but the wiring config field name should make
the scope unambiguous.

**Fallback** — should the SPI support a fallback call if all attempts fail?
This would require the proxy to pass two lambdas rather than one. Worth
considering but not required for an initial implementation.

**Observability** — retry attempts should be visible in traces. Each retry
attempt is a separate span. How the failure semantics implementation
participates in Itara's observability model — whether it fires events itself
or delegates to the proxy — needs to be decided. The proxy currently owns
all observability events, which argues for the proxy wrapping each attempt
rather than the failure semantics implementation doing it internally.

**Direct connections** — failure semantics for direct (colocated) connections
are different in nature. A direct call that throws is a real failure with no
transport involved. Whether the failure semantics SPI applies to direct
connections at all, or only to transport connections, needs to be decided.

**Default implementation** — a built-in no-op implementation that surfaces
errors immediately (current behaviour) should ship as the default, requiring
zero configuration for teams that handle failures in their own code.
