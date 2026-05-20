// itara-serializer-json
//
// A thin, stable wrapper around serde_json.
//
// API crates that support JSON serialization depend on this crate rather than
// on serde_json directly. This means:
//   - Clients carry one fewer transitive dependency to manage.
//   - serde_json version upgrades are Itara's concern, not the client's.
//   - Breaking changes in serde_json are absorbed here before they reach
//     the public API surface.
//
// The wrapper is intentionally minimal — it exposes exactly what an API crate
// needs to serialize method arguments and return values, nothing more.
//
// This crate is a compile-time dependency (rlib). It is not a cdylib and is
// not loaded dynamically — it is compiled directly into the API cdylib.

/// Serialize a value to JSON bytes.
///
/// Panics if serialization fails — this indicates a programming error
/// (a type that claims to be Serialize but cannot produce valid JSON),
/// not a runtime condition that callers should handle.
pub fn serialize<T: serde::Serialize>(value: &T) -> Vec<u8> {
    serde_json::to_vec(value)
        .expect("[itara-serializer-json] serialization failed")
}

/// Deserialize JSON bytes into a value of type T.
///
/// Panics if deserialization fails — a deserialization failure at this layer
/// indicates either a serializer mismatch (the sender used a different format)
/// or a contract mismatch (the types on both sides differ). Both are topology
/// configuration errors that surface at startup in normal operation.
pub fn deserialize<T: serde::de::DeserializeOwned>(bytes: &[u8]) -> T {
    serde_json::from_slice(bytes)
        .expect("[itara-serializer-json] deserialization failed")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_i64() {
        let bytes = serialize(&42i64);
        let back: i64 = deserialize(&bytes);
        assert_eq!(back, 42);
    }

    #[test]
    fn round_trip_i64_pair_as_array() {
        let bytes = serialize(&[3i64, 4i64]);
        let back: [i64; 2] = deserialize(&bytes);
        assert_eq!(back, [3, 4]);
    }

    #[test]
    fn round_trip_string() {
        let bytes = serialize(&"hello");
        let back: String = deserialize(&bytes);
        assert_eq!(back, "hello");
    }
}
