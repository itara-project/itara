# ADR 0023 — `PERMISSION` as a Single Error Kind, Not a Dedicated Exception Type

**Status:** Accepted
**Date:** August 2026

---

## Context

An authentication failure or an authorization denial doesn't fit any
existing `ErrorKind` (spec §6.6) cleanly — not a business-declared
condition (`CHECKED`), not an unexpected failure (`RUNTIME`), not Itara
infrastructure malfunctioning (`TRANSPORT`). A new kind is needed, and it
needs a reconstruction rule: `CHECKED` errors MAY be reconstructed into
the original declared type, but only where the API opts in, and even then
falling back to the generic `ItaraRemoteException` equivalent if
reconstruction fails. `RUNTIME`/`TRANSPORT` skip reconstruction entirely
and are always that generic type.

---

## Decision

One new kind, `PERMISSION`, covers both authentication failure and
authorization denial. It follows the same reconstruction rule as
`RUNTIME`/`TRANSPORT`: always surfaced as `ItaraRemoteException`, carrying
`ErrorKind = PERMISSION` and the message. No dedicated exception type.
Recommended HTTP status mapping: 403 Forbidden. Whether and how
`PERMISSION` interacts with retry is a question of where authentication
and authorization sit relative to the retry loop, not a property of the
kind itself — that's a separate decision.

---

## Reasoning

**One kind, not two.** From the caller's side there's no actionable
difference between "your identity didn't verify" and "your identity
verified but isn't permitted here" — either way the call didn't go
through because the caller wasn't permitted to make it, and the message
carries the specifics. Splitting would double the reconstruction rule,
retry entry, and HTTP mapping for a distinction nothing downstream acts on
differently.

**No dedicated exception type.** `ErrorKind` is already data carried *on*
`ItaraRemoteException`, not something that requires its own type to be
visible — code that wants to branch on `PERMISSION` specifically checks
the kind field on the one exception type it already catches. A dedicated
type was seriously considered and rejected because it erodes the entire
point of that model: one exception type as the boundary between Itara and
business logic. Growing a new type per kind doesn't scale past this one
case either — it would mean a new exception type for every future
`ErrorKind` value too.

---

## Alternatives considered

**Two separate kinds** (authentication failure, authorization denial).
Rejected — no actionable difference for the caller; doubles spec surface
for no benefit.

**A dedicated, reconstructible exception type for `PERMISSION`.**
Rejected — undermines the single-exception-type boundary `ItaraRemoteException`
exists to provide; `ErrorKind` as data already gives callers the
distinguishability without a new type.

---

## Consequences

- One new value across the `ErrorKind` table and the HTTP status mapping
  table (spec §6.6). Whether it also gets a retry-table entry, and what
  that entry says, depends on the separate decision about where
  authentication and authorization sit relative to the retry loop.
- No growth in exception surface area for calling code — still exactly one
  exception type to catch for any remote or undeclared failure.
- Code that cares can branch on `kind == PERMISSION`; code that doesn't
  treats it like any other `ItaraRemoteException`.

---

## References

- Spec §6.6 (Runtime Error Handling)
- ADR 0021 — Authentication and Authorization as Separate SPIs
