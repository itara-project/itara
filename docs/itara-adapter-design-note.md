# Itara Adapter — Design Note

**Status:** Idea stage, not yet an ADR
**Date:** July 2026
**Related:** GLOSSARY.md (proxy, component, external connection)

---

## Overview

Itara's component model assumes participation: a component implements a
contract, has an activator, and is wired by the agent. This is the source of
Itara's guarantees — validated contracts, placement flexibility, structural
observability — but it is also an adoption cost. A system with legacy
components, components in an unsupported language, or components the client
does not want to touch cannot join the topology today except as an
[external connection](#relationship-to-external-connections), which carries
none of those guarantees.

This document describes the Itara Adapter: a way to bring a non-Itara system
into the topology as a fully participating node, without modifying that
system, at the cost of colocation and an added runtime hop.

The motivation is adoption. Service meshes have one real structural advantage
over Itara: they require zero code integration, because they operate beneath
the application entirely. The adapter is Itara's answer to that advantage —
not by matching it everywhere, but by making it available at the specific
boundary where it is needed, without paying its cost everywhere else.

---

## Concept

**An Itara Adapter is a component that wraps a non-Itara system and presents
it to the rest of the topology as an ordinary component with a declared API
contract.**

From every other component's perspective, an adapter is indistinguishable
from any other node: it has an API artifact, its contract is validated by the
tooling, and connections to it are declared in the wiring config the same way
as any other connection. The difference is entirely internal to the adapter:
instead of an activator constructing a normal implementation, the adapter's
implementation is a translation layer that speaks the wrapped system's native
protocol on one side and the declared API contract on the other.

This is not a new mechanism. The translation is a serializer/transport SPI
implementation, the same kind of plugin Itara already supports — pointed at
a legacy protocol instead of a clean one.

---

## Why not just use an external connection

Itara already supports external connections: a connection with no `from`
node, where the agent prepares a dispatcher and accepts inbound traffic from
a caller outside the topology. This is the minimal-effort bridge and it
already works today. It should remain the right choice when the caller is
willing to speak Itara's format directly.

An external connection does not solve the adoption case this document is
about: a legacy system that cannot or should not be modified at all. It
requires the outside caller to know the API format and produce/consume it
correctly. Nothing is validated on the external side, because there is no
component there for the tooling to check against.

The adapter is a stronger structure: the wrapping is declared and owned by
the adapter component itself, not left to an untrusted caller. The tooling
validates the adapter's contract exactly as it would any other component. The
legacy system's compliance is not assumed — it is mediated.

---

## Reusability

An adapter wraps a protocol, not a specific deployment of a specific system.
An adapter written for a given legacy protocol (a specific SOAP interface
shape, a proprietary binary TCP format, a particular message queue's wire
format) is reusable across any system that speaks that same protocol,
regardless of the language or runtime the legacy system is written in.

This makes adapters a distinct category of reusable artifact, alongside
transports and serializers — written once, applicable to every future system
that shares the same legacy interface shape. Over time this could become a
small library of adapters for common legacy shapes, the same way transports
and serializers are expected to grow as a plugin ecosystem.

---

## Colocation

**Adapters cannot be colocated.**

The wrapped system runs in its own process, with its own lifecycle, outside
the agent's control. There is no shared type system or process boundary to
collapse. This is reflected in the adapter component's metadata — a flag
marking it as non-colocatable — so that deployment group derivation and
compatibility checks reject any attempt to place an adapter into a direct
connection. This is the same mechanism already used for other compatibility
constraints; no new validation machinery is required.

---

## Runtime cost

An adapter introduces a real hop and a real translation cost — comparable in
kind to a service mesh sidecar's overhead, though scoped differently. The
important difference from mesh is that this cost applies only where it is
needed: at the specific legacy boundary, for the specific components that
require it. It is not a tax paid across the entire estate, and it is not
mandatory infrastructure — a system with no legacy components pays nothing
for this mechanism existing.

For some systems this cost is an acceptable, deliberate trade: full topology
visibility and validated contracts for a component that would otherwise be
invisible to the tooling entirely, at the cost of an extra hop and no
colocation option.

---

## Relationship to external connections

These are two different bridges and should not be conflated in the glossary
or in conversation:

- **External connection** — no node, no declared contract, no validation.
  The caller is trusted to know the format. Minimal effort, minimal
  guarantee.
- **Itara Adapter** — a real node with a declared, validated API contract.
  The legacy system's native protocol is mediated by the adapter's
  translation SPI. Full topology participation except colocation.

---

## Open questions

- **Deployment shape of the adapter itself.** Does it run as a sidecar
  process alongside the legacy system, or as a standalone node with its own
  lifecycle? Both seem plausible; this affects how it is described in the
  wiring config and how the CLI visualises it.
- **Metadata and header handling.** Raised independently in community
  feedback: some legacy protocols carry filtering- or authZ-relevant metadata
  that does not map cleanly onto a single contract shape. Whether this is
  handled generically by the adapter SPI or requires per-adapter extension is
  still open.
- **Where the SPI boundary sits precisely.** Whether the translation SPI is
  a specialisation of the existing serializer/transport SPIs or warrants its
  own SPI category.
- **Packaging and distribution of adapters.** Whether adapters become part
  of the open-source plugin ecosystem alongside transports and serializers,
  or something more commercially distinct given their role in easing
  pilot adoption.

---

## Relationship to roadmap

This is not part of the currently planned proto/gRPC work and does not block
it. Its value is primarily commercial: it is a plausible answer to the most
common pilot objection — "we have systems we can't or won't touch" — and is
worth having designed, even loosely, before that objection is raised in a
real pilot conversation.
