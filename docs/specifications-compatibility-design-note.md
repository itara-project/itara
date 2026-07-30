# Specifications for Cross-Plugin Compatibility — Design Note

**Status:** Idea stage, not yet an ADR. No glossary terms finalized.
**Date:** July 2026
**Related:** classloader-isolation-design.md style precedent for early-stage
capture; GLOSSARY.md (Contract); spec §5.4, §8.6

---

## Overview

Itara's plugin model (transports, serializers, and any future plugin kind)
faces a recurring compatibility question: two independently developed
implementations may both claim to handle the same category of thing —
"json," "http," "protobuf" — without actually agreeing on the details that
matter. Field naming conventions, string encoding, status code mappings,
header names for error propagation, and similar low-level behavioral
choices are not captured by a type identifier alone.

The instinct so far has been to model these properties directly in plugin
metadata — declare capabilities, declare supported formats, declare
compatibility whitelists. This works, but it doesn't generalize: every new
axis of compatibility (error-payload encoding strategy, header conventions,
whatever comes next) would need its own bespoke metadata field and its own
bespoke compatibility check. Modeling every possible behavioral property
this way is open-ended and, in practice, unlikely to converge.

This note describes a different direction: instead of modeling properties,
let plugins declare conformance to a **specification** — a separately
published, versioned, non-executable artifact describing how a category of
behavior must work. Compatibility becomes "do these two plugins share a
specification," not "do these two plugins happen to agree by convention."

---

## Concept

**A specification is a non-executable artifact.** It is not a library, not
a runtime dependency, not code — it is a document describing required
behavior for some concern (an error-payload wire format, a status-code
mapping convention, a header-naming scheme). It has its own identity: an
id, a semver version, and presumably its own `.itara` metadata file, the
same way components, APIs, transports, and serializers do.

**Plugins declare which specification(s) they conform to,** by id and
version range, the same shape already used elsewhere in this project for
compatibility declarations (component-to-API version ranges; the
serializer-to-API whitelist introduced for message formats). Two plugins
sharing a declared specification are compatible for whatever that
specification governs. Two plugins with no shared specification are
unverified — the tooling can say "we don't know," not "this is broken," the
same non-blocking posture already established for message-format
compatibility.

**Conformance is self-declared, not automatically verified.** A
specification is a document; nothing in Itara itself can prove an
implementation actually behaves as the document says. This is the same
trust boundary every plugin declaration already has — it doesn't disappear,
it becomes reusable and named instead of ad hoc per case.

---

## What this replaces

This idea surfaced from a concrete gap: the serializer error-payload
handling decision (ADR 0020) established that every serializer must handle
the fixed error payload, but left open *how*, and a follow-up discussion
surfaced that "native" (each serializer's own idiomatic encoding) isn't
safely convergent across independent implementations — a Java JSON
serializer and a Rust JSON serializer will naturally disagree on field
casing, for instance, with nothing forcing them to align.

A three-way split was proposed as a stopgap: `built-in` (an encoding Itara
owns and ships), `native` (each implementation's own idiomatic choice,
assumed safe within a type), `custom` (a bespoke encoding, gated by a
matching scheme identifier).

Specifications, if they hold up, collapse this to something simpler:

- `built-in` was never a separate mechanism — it's just the specification
  Itara itself publishes and ships an official reference implementation
  of. No special case needed.
- `native` disappears. There is no such thing as a safely assumed
  idiomatic convention across independent implementations — if it isn't
  declared against a shared specification, it's unverified, full stop.
- What remains is one mechanism, used everywhere: declare a specification,
  check for a shared one, warn if there isn't one.

---

## Relationship to existing mechanisms

This does not replace `message-format` (spec §5.4) or
`[serializer.capabilities] message-formats` (spec §5.4, §8.6) — those
answer "what shape are the contract's types," a different question from
"do these two plugins behave the same way for some cross-cutting concern."
Specifications are a complementary mechanism, likely most relevant to
exactly the kind of cross-side behavioral agreement error-payload encoding
needs, and possibly other concerns not yet identified.

---

## Governance and ecosystem implications

Self-declared conformance doesn't remove trust from the system — nor
should it. The realistic path is a separate product: a gateway that only
allows publishing a plugin claiming conformance to a specification once it
passes tests and review against that specification. This concentrates
trust at one review point instead of asking every adopter to re-derive it,
the same pattern app stores and package registries with trusted-publishing
models already use successfully.

This also creates a natural incentive against fragmentation: once a
specification exists with an official, tested reference implementation,
most teams have no reason to write their own — "reinventing this" becomes
the wrong kind of freedom, not a virtue, the same way arbitrary unreviewed
releases aren't a virtue in most ecosystems with any quality bar.

**Likely distribution shape:** Itara publishes specifications and open
reference implementations for the common cases as part of the open-source
core. Specialized implementations — lower latency, different tradeoffs,
targeting the same specification — are a plausible commercial layer on top,
without fragmenting the specification itself. Most teams are expected to
never think about this directly; they use the default conformant
implementation and move on.

---

## Open questions

- **Glossary and naming.** "Specification" as a term needs to be
  distinguished clearly from "contract" and "message format" in
  GLOSSARY.md before this goes further — right now the word is doing a lot
  of informal work.
- **Artifact kind and metadata shape.** Whether a specification gets its
  own `kind = "specification"` artifact type, what its `.itara` file
  contains, and how plugins reference it (a new metadata section,
  presumably, analogous to `[serializers] supported`).
- **Namespacing.** Specification ids face the same collision risk
  components, APIs, and other plugins already face — not a new problem,
  but not yet solved for any of them either.
- **Verification.** Whether "conformance" ever becomes anything more than
  self-declaration within Itara itself, versus being entirely a governance
  concern handled by the separate gateway product described above.
- **Scope of first specifications.** Error-payload wire format is the
  concern that surfaced this idea; whether it's the first one actually
  built, or whether something simpler proves the mechanism first, is
  undecided.

---

## Relationship to roadmap

Not part of the proto/message-format work currently in progress, and does
not block it. The error-payload cross-side compatibility gap this idea
responds to was explicitly deferred to its own future sprint — this note
is a starting point for that sprint's design discussion, not a commitment
to build it now. Reasonable next step is a GitHub Discussion to pressure-test
the idea before it becomes an ADR.
