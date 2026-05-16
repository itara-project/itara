# ADR 0007 — Serializer and Transport as Separate Layers

**Date:** May 2026  
**Status:** Accepted

## Context

The initial Java implementation called the serializer from within the transport implementation. The transport received an array of objects, serialized them internally, sent the bytes, received the response bytes, and deserialized them before returning. This worked in Java because the object array is a natural input to a serializer.

When implementing the Rust PoC, this coupling became a problem. Rust's type system made it impossible to cleanly pass typed method arguments across the transport boundary without explicit serialization. The transport was being asked to own a concern that did not belong to it.

The root cause: the transport and the serializer were accidentally coupled into one layer even though they have entirely different responsibilities.

## Decision

The serializer and transport are strictly separate layers with a clean byte array boundary between them:

```
caller → [serializer] → transport → [wire] → transport → [deserializer] → callee
```

The transport receives `(method_name: string, payload: byte[])` and returns `byte[]`. It moves bytes from one place to another. It has no knowledge of what those bytes represent.

The serializer converts typed method arguments to bytes before the transport is called, and converts the response bytes back to typed values after the transport returns. It has no knowledge of how the bytes are transported.

Neither layer knows about the other. The agent wires them together.

This decision applies to all language implementations. The Java implementation will be updated to match.

## Consequences

- Any serializer works with any transport. JSON over HTTP, Protobuf over Kafka, custom binary format over Unix domain socket — all combinations are valid without any changes to either implementation.
- The transport SPI is genuinely simple: move bytes, return bytes. Transport implementations do not need to understand the Itara type system or component contracts.
- Language neutrality is preserved. The byte array boundary is the same in every language. A Rust component and a Java component can communicate because they agree on bytes, not on language-specific object representations.
- The serializer SPI can support schema registries (Confluent, AWS Glue) without involving the transport layer at all.
- Error handling is cleaner. Transport errors (connection refused, timeout) and serialization errors (type mismatch, schema violation) are distinct failure modes that surface at different layers with different semantics.
- The previous Java implementation coupled these layers accidentally. This was not visible as a problem until the Rust PoC forced the boundary to be explicit. This is an example of a second language implementation revealing a design flaw that was invisible in the first.
- The byte array boundary enables thin language bindings over the Rust core implementation. A language without full native Itara support — Python needing Unix domain sockets, for example — can call into the Rust serializer and transport implementations via FFI, passing and receiving byte arrays. The `.so` call overhead is acceptable for cases where full native support is not yet available or not worth the investment. The separation of serializer and transport makes this binding surface minimal and well-defined.
