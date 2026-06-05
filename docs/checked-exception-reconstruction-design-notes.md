# Checked Exception Reconstruction — Design Notes

**Status:** Under discussion. Not yet part of the specification.  
**Date:** June 2026

---

## The problem

When a component call crosses a process boundary, the proxy currently surfaces
all failures as `ItaraRemoteException`. For CHECKED errors — exceptions declared
on the contract interface — this requires the caller to inspect
`getRemoteExceptionClass()` to identify the specific failure:

```java
try {
    inventory.reserveOrder(orderId, productId, quantity);
} catch (InsufficientStockException e) {
    // Direct call — original typed exception preserved
    handle(e);
} catch (ItaraRemoteException e) {
    // Remote call — must inspect class name to identify checked exception
    if (InsufficientStockException.class.getName().equals(e.getRemoteExceptionClass())) {
        handle(e);
    }
    throw e;
}
```

This exposes the topology boundary to the caller. A component that switches
from direct to remote topology suddenly requires different error handling code,
which violates the principle that component code doesn't need to know whether
a call is local or remote.

---

## The proposed behaviour

For CHECKED exceptions that satisfy all of the following conditions, the proxy
reconstructs and rethrows the original exception type:

1. The exception class name matches a type declared in the contract's `throws`
   clause
2. The exception class is available on the caller's classpath
3. The exception can be reconstructed with at minimum a message

The caller then catches `InsufficientStockException` regardless of whether
the call was direct or remote — the topology boundary is invisible for
contract-declared errors:

```java
try {
    inventory.reserveOrder(orderId, productId, quantity);
} catch (InsufficientStockException e) {
    // Works for both direct and remote topologies
    handle(e);
}
```

RUNTIME and TRANSPORT errors continue to surface as `ItaraRemoteException`.
These are not declared on the contract and the caller should not be expected
to handle them as typed exceptions.

---

## Why it was intentionally deferred

Full exception reconstruction was explicitly left out of scope in the initial
design. The stated reason: reconstructing the original type requires the
exception class to be present on the caller's classpath, which cannot be
guaranteed in a topology where components are developed and deployed
independently.

This concern is valid for arbitrary exceptions. It is less valid for exceptions
declared on the contract interface — if the contract declares
`throws InsufficientStockException`, the caller must already have that class
on its classpath to compile against the contract. The classpath guarantee
holds for declared checked exceptions by definition.

---

## Reconstruction approaches

Two approaches are under consideration. Both are starting points for discussion
rather than committed proposals.

**Option A — Convention-based reconstruction**

The proxy attempts reconstruction via reflection if the exception class exposes
a recognised constructor. Two constructors are recognised:

- `ExceptionType(String message)` — reconstructs with message only
- `ExceptionType(String message, Throwable cause)` — reconstructs with message
  and a cause wrapping the remote origin context

If neither constructor is present, the proxy falls back to `ItaraRemoteException`.
No changes to the exception class are required — standard Java exception
conventions are sufficient. The downside: reconstruction is implicit and the
exception author has no way to opt out or control the behaviour.

**Option B — Interface-based reconstruction**

A lightweight `ItaraReconstructable` interface is introduced in `itara-common`.
Exception classes that want to support reconstruction implement it, providing
a factory method that takes the available remote error information and returns
a reconstructed instance:

```java
public interface ItaraReconstructable {
    static Exception from(String message, String remoteClass);
}
```

The proxy checks for the interface before attempting reconstruction. If present,
the factory method is called. If absent, `ItaraRemoteException` is returned.
The exception author explicitly opts in and controls the reconstruction logic.

This fits naturally on the API artifact alongside `@ComponentInterface` and
`@ContractMethod` — the API is already the declared boundary between business
logic and topology, and idempotency and similar concerns are already declared
there. An `ItaraReconstructable` marker on an exception class follows the same
pattern without touching the implementation.

The interface approach is preferred if Itara's presence on the API is considered
acceptable. The convention approach is preferred if zero Itara dependency on
the exception class is a hard requirement.

---

## Open questions

**Reconstruction mechanism** — constructing an arbitrary exception from just
a message loses information (cause chain, custom fields). For the common case
of simple checked exceptions with a message constructor, this is acceptable.
For exceptions with custom fields, reconstruction would produce an incomplete
object. Whether incomplete reconstruction is better or worse than
`ItaraRemoteException` is a judgement call.

**Stack trace** — reconstructed exceptions will have a synthetic stack trace
originating at the proxy, not at the original throw site. This is honest but
may be confusing in debuggers. A clear convention (e.g. a suppressed cause
with a message explaining the remote origin) would help.

**Spec impact** — if reconstruction is adopted, the error handling section
(§6.6) needs updating. The current spec explicitly states that full
reconstruction is out of scope. This would become a SHOULD for declared
checked exceptions available on the caller's classpath.

**Language neutrality** — Java makes this straightforward via reflection.
Other languages may not. The behaviour may need to be language-specific
rather than specified universally.
