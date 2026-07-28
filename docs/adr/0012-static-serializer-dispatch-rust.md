# ADR 0012 — Static Serializer Dispatch in Rust

**Date:** May 2026  
**Status:** Accepted — identifier model refined; see spec §5.4 (Serializer Artifacts), §7.2, §8.2. Dispatch mechanism unaffected.
**Applies to:** Rust implementation only

## Context

ADR 0007 establishes the serializer and transport as separate layers with a byte array boundary between them. The serializer converts typed method arguments to bytes before the transport is called, and converts response bytes back to typed values after. Neither layer knows about the other. The agent wires them together.

In Java, this maps cleanly to a runtime plugin model. The serializer SPI is a Java interface. The agent loads any implementation at runtime via the classloader and hands it to the proxy and dispatcher as an interface reference. Java's reflection and type erasure make this transparent — any serializer can receive any arguments without compile-time knowledge of their types.

Rust does not have runtime reflection or type erasure in the Java sense. Implementing the same runtime plugin model in Rust requires either:

- Passing pre-serialized bytes per argument to a generic serializer interface — which means double serialization: once in the proxy to satisfy the interface, once in the serializer to produce the actual wire format. This violates the zero overhead principle. Unacceptable.
- A schema-driven approach where the proxy passes raw memory pointers alongside a type descriptor. Sound in principle but requires building and maintaining a type descriptor system, is inherently unsafe at the FFI boundary, and adds significant complexity.
- Erased trait objects via `erased-serde`. Introduces a mandatory dependency on a third-party crate as part of Itara's public SPI surface, and excludes serialization formats that do not implement serde traits.

None of these options provides clean runtime pluggability without either a performance penalty, significant complexity, or a constrained extension surface.

## Decision

In Rust, the serializer is not a runtime plugin loaded dynamically. It is selected at startup based on the serializer identifier declared in the wiring config for each connection — a plain string, the same identifier used by all other language implementations.

The generated proxy and dispatcher contain serialization logic that dispatches on this string. The code generation mechanism — whether a single macro, multiple macros, or another approach — is intentionally left open. What matters is the contract: the agent passes the serializer identifier to the factory function at startup, and the generated code dispatches to the correct serialization path with no intermediate format and no unnecessary overhead. The zero overhead principle is not negotiable.

This means the set of serializers a component supports is determined at compile time, from the source code. The `.itara` metadata file declares which serializers the component supports — this information enables tooling to detect mismatches at configuration time. The metadata file is never an input to the build.

Companies providing a custom serializer declare it by its string identifier in the wiring config. There is no framework enum to extend, no contribution required to register a name. The identifier is opaque to the framework. The component is compiled with support for the serializers its author chose to generate for. If a serializer is requested that the component was not compiled with support for, the mismatch is detectable by tooling at configuration time — before the system starts.

## Consequences

- No double serialization. Each serializer format receives typed values directly and serializes them in its native representation. The zero overhead principle is preserved.
- The set of supported serializers for a component is a compile-time property determined by the source code. Changing which serializers are supported requires recompilation. This is consistent with Rust's design philosophy.
- Companies providing a custom serializer need only declare its string identifier. No framework contribution is required. No enum value needs to be added anywhere.
- The `.itara` metadata file declares which serializers the component supports. This enables tooling to detect mismatches at configuration time. The tooling support is a future capability — this ADR establishes the foundation, not a completed feature.
- This decision does not affect other language implementations. Languages with runtime reflection use dynamic serializer SPIs. The byte array boundary defined in ADR 0007 is maintained in all cases.
- The constraint is honest. Most organisations using Rust for performance-critical components chose their serialization format deliberately and do not change it frequently. The compile-time approach serves this audience correctly. Organisations needing fully dynamic serializer switching at runtime can use a language implementation that supports it.

## Relationship to ADR 0007

This decision refines ADR 0007 for the Rust implementation. The byte array boundary between serializer and transport is preserved. The change is in how typed values reach that boundary: via generated dispatch rather than a runtime trait object. The principle is unchanged; the mechanism is language-appropriate.

## Addendum: identifier model refined

The identifier model described above predates the type/artifact-id split. At the time this ADR was written, a serializer's identifier served as both its type and its unique implementation identity.

Serializers now have two identifiers: a `serializer.type` (the serialization category — `json`, `protobuf`, etc.) and a distinct `artifact.id`. Where this ADR says "a plain string, the same identifier used by all other language implementations," that string is the serializer's `type`. The generated Rust dispatch code now resolves against specific serializer artifacts — id and version — declared in the consuming API artifact's `[serializers] supported` list, each of which declares that same `type`.

The static, compile-time nature of Rust's dispatch mechanism, and every consequence recorded above, is unaffected by this refinement. Only the identifier model it operates on has been generalized, the same way transports already were, to allow more than one implementation to exist per type without ambiguity.