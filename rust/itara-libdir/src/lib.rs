use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::fs;
use serde::Deserialize;

// ── Metadata file model ───────────────────────────────────────────────────────
//
// Mirrors the TOML structure defined in ADR 0008.
// Unknown fields are ignored — forward compatibility preserved.

#[derive(Debug, Clone, Deserialize)]
pub struct ArtifactMeta {
    /// kind = "component" | "api" | "transport" | "serializer" | "observer"
    pub kind: String,

    /// Component id for components and apis (e.g. "calculator").
    /// Transport name for transports (e.g. "http").
    pub id: String,

    #[serde(default)]
    pub version: String,

    #[serde(rename = "api-version", default)]
    pub api_version: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct RuntimeMeta {
    #[serde(default)]
    pub language: String,

    #[serde(default)]
    pub compiler: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ItaraMeta {
    #[serde(rename = "spec-version", default)]
    pub spec_version: String,

    #[serde(rename = "core-version", default)]
    pub core_version: String,
}
 
/// Serializer declarations for api artifacts.
/// Lists the serializer ids the artifact was compiled with support for.
/// Used by tooling to validate wiring config connections at configuration time.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct SerializersMeta {
    /// Serializer ids supported by this artifact (e.g. ["json", "protobuf"]).
    /// Populated for kind = "api" artifacts only.
    #[serde(default)]
    pub supported: Vec<String>,
}

/// Capability declarations for a transport artifact.
#[derive(Debug, Clone, Deserialize)]
pub struct TransportCapabilities {
    /// Whether the transport can enforce the per-call timeout natively.
    /// Defaults to true when absent.
    #[serde(default = "default_true", rename = "nativeCallTimeout")]
    pub native_call_timeout: bool,

    /// Whether the transport is safe to interrupt externally on timeout.
    /// Defaults to true when absent.
    #[serde(default = "default_true", rename = "externallyInterruptible")]
    pub externally_interruptible: bool,
}

impl Default for TransportCapabilities {
    fn default() -> Self {
        Self {
            native_call_timeout: true,
            externally_interruptible: true,
        }
    }
}

fn default_true() -> bool { true }

/// The [transport] section of a transport `.itara` metadata file.
/// Only meaningful when artifact.kind = "transport".
#[derive(Debug, Clone, Deserialize)]
pub struct TransportMeta {
    /// The transport category — describes the communication protocol.
    /// Examples: "http", "kafka", "amqp".
    /// Two implementations with the same type are compatible caller/callee pairs.
    #[serde(default, rename = "type")]
    pub transport_type: Option<String>,

    #[serde(default)]
    pub capabilities: TransportCapabilities,
}

/// Parsed contents of a single `.itara` metadata file.
#[derive(Debug, Clone, Deserialize)]
pub struct MetadataFile {
    pub artifact: ArtifactMeta,

    #[serde(default)]
    pub runtime: Option<RuntimeMeta>,

    #[serde(default)]
    pub itara: Option<ItaraMeta>,
 
    /// Declared serializers — present on kind = "api" artifacts.
    #[serde(default)]
    pub serializers: Option<SerializersMeta>,

    #[serde(default)]
    pub transport: Option<TransportMeta>,
}

// ── LibEntry ──────────────────────────────────────────────────────────────────

/// A single entry in the lib index — one artifact that was discovered.
#[derive(Debug, Clone)]
pub struct LibEntry {
    /// Parsed metadata from the .itara file.
    pub meta: MetadataFile,

    /// Full path to the loadable artifact (.dll / .so) alongside the .itara file.
    pub lib_path: PathBuf,
}

// ── LibIndex ─────────────────────────────────────────────────────────────────
//
// Keyed by (kind, id). Both are lowercase for case-insensitive lookup.
// Multiple entries with the same (kind, id) are a configuration error —
// the first one found wins and a warning is printed.

#[derive(Debug, Default)]
pub struct LibIndex {
    entries: HashMap<(String, String), LibEntry>,
}

impl LibIndex {
    /// Scan a directory for `.itara` metadata files and build the index.
    ///
    /// For each `.itara` file found, the agent looks for a sibling file
    /// with the same stem and a platform-appropriate extension (.dll / .so / .dylib).
    /// If no sibling lib is found, the metadata file is recorded as a warning
    /// and skipped — it may belong to a tool (e.g. CLI) that doesn't produce a lib.
    pub fn scan(lib_dir: &Path) -> Result<LibIndex, String> {
        if !lib_dir.exists() {
            return Err(format!(
                "[Itara] Lib dir does not exist: '{}'. \
                 Set ITARA_LIB_DIR or create the directory.",
                lib_dir.display()
            ));
        }

        let mut index = LibIndex::default();

        let entries = fs::read_dir(lib_dir).map_err(|e| {
            format!("[Itara] Cannot read lib dir '{}': {}", lib_dir.display(), e)
        })?;

        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("itara") {
                continue;
            }

            match parse_metadata_file(&path) {
                Ok(meta) => {
                    let lib_path = sibling_lib_path(&path);
                    match lib_path {
                        Some(lib) => {
                            let key = (
                                meta.artifact.kind.to_lowercase(),
                                meta.artifact.id.to_lowercase(),
                            );
                            if index.entries.contains_key(&key) {
                                eprintln!(
                                    "[Itara] Warning: duplicate artifact (kind='{}', id='{}') \
                                     found in lib dir. Keeping first, ignoring: {}",
                                    key.0, key.1, lib.display()
                                );
                            } else {
                                println!(
                                    "[Itara] Discovered {} '{}' at {}",
                                    meta.artifact.kind, meta.artifact.id, lib.display()
                                );
                                index.entries.insert(key, LibEntry { meta, lib_path: lib });
                            }
                        }
                        None => {
                            eprintln!(
                                "[Itara] Warning: no lib found for metadata file '{}'. \
                                 Expected a sibling .dll/.so/.dylib with the same stem.",
                                path.display()
                            );
                        }
                    }
                }
                Err(e) => {
                    eprintln!("[Itara] Warning: could not parse '{}': {}", path.display(), e);
                }
            }
        }

        Ok(index)
    }

    /// Look up the lib path for a component by id.
    /// Returns None if no component with that id is in the index.
    pub fn component_lib(&self, id: &str) -> Option<&Path> {
        self.entries
            .get(&("component".to_string(), id.to_lowercase()))
            .map(|e| e.lib_path.as_path())
    }

    /// Look up the lib path for an API cdylib by component id.
    /// Returns None if no api artifact with that id is in the index.
    pub fn api_lib(&self, id: &str) -> Option<&Path> {
        self.entries
            .get(&("api".to_string(), id.to_lowercase()))
            .map(|e| e.lib_path.as_path())
    }

    /// Look up the lib path for a transport by transport type name (e.g. "http").
    /// Returns None if no transport with that name is in the index.
    pub fn transport_lib(&self, transport_type: &str) -> Option<&Path> {
        self.entries
            .get(&("transport".to_string(), transport_type.to_lowercase()))
            .map(|e| e.lib_path.as_path())
    }
 
    /// Return the serializer ids declared as supported by an API artifact.
    /// Returns an empty slice if the artifact is not in the index or
    /// declares no serializers.
    ///
    /// Used by tooling to validate that the serializer declared in a wiring
    /// config connection is supported by both ends before the system starts.
    pub fn supported_serializers(&self, api_id: &str) -> &[String] {
        self.entries
            .get(&("api".to_string(), api_id.to_lowercase()))
            .and_then(|e| e.meta.serializers.as_ref())
            .map(|s| s.supported.as_slice())
            .unwrap_or(&[])
    }
 
    /// Return the lib paths of all observer artifacts in the index.
    /// Unlike transports and serializers — which are one per connection —
    /// multiple observers can be active simultaneously. All discovered
    /// observer artifacts are returned and the agent loads all of them.
    pub fn observer_libs(&self) -> Vec<&Path> {
        self.entries
            .iter()
            .filter(|((kind, _), _)| kind == "observer")
            .map(|(_, entry)| entry.lib_path.as_path())
            .collect()
    }
 
    /// Return the lib path of the context handler artifact, if present.
    /// Exactly one context handler is expected — the first found is returned.
    pub fn context_handler_lib(&self) -> Option<&Path> {
        self.entries
            .iter()
            .find(|((kind, _), _)| kind == "context-handler")
            .map(|(_, entry)| entry.lib_path.as_path())
    }

    /// Iterate over all entries for debugging / CLI use.
    pub fn all(&self) -> impl Iterator<Item = &LibEntry> {
        self.entries.values()
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn parse_metadata_file(path: &Path) -> Result<MetadataFile, String> {
    let content = fs::read_to_string(path).map_err(|e| {
        format!("could not read '{}': {}", path.display(), e)
    })?;

    toml::from_str::<MetadataFile>(&content).map_err(|e| {
        format!("could not parse '{}': {}", path.display(), e)
    })
}

/// Given a path like `/lib/calculator_component.itara`, return the sibling lib:
/// - Windows: `calculator_component.dll`
/// - Linux:   `libcalculator_component.so`
/// - macOS:   `libcalculator_component.dylib`
///
/// Tries each candidate in order and returns the first that exists on disk.
fn sibling_lib_path(itara_path: &Path) -> Option<PathBuf> {
    let dir = itara_path.parent()?;
    let stem = itara_path.file_stem()?.to_str()?;

    let candidates: &[&str] = if cfg!(target_os = "windows") {
        &["{stem}.dll"]
    } else if cfg!(target_os = "macos") {
        &["lib{stem}.dylib", "lib{stem}.so"]
    } else {
        &["lib{stem}.so"]
    };

    for template in candidates {
        let filename = template.replace("{stem}", stem);
        let candidate = dir.join(&filename);
        if candidate.exists() {
            return Some(candidate);
        }
    }

    None
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::io::Write;

    fn write_temp(dir: &Path, name: &str, content: &str) {
        let mut f = fs::File::create(dir.join(name)).unwrap();
        f.write_all(content.as_bytes()).unwrap();
    }

    #[test]
    fn parses_component_metadata() {
        let toml = r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
api-version = "1.x"

[runtime]
language = "rust"
compiler = "1.78+"

[itara]
spec-version = "0.1"
core-version = "0.1+"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert_eq!(meta.artifact.kind, "component");
        assert_eq!(meta.artifact.id, "calculator");
        assert_eq!(meta.artifact.version, "1.0.0");
        assert_eq!(meta.runtime.unwrap().language, "rust");
    }

    #[test]
    fn parses_api_metadata() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"
api-version = "1.x"

[runtime]
language = "rust"
compiler = "1.78+"

[itara]
spec-version = "0.1"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert_eq!(meta.artifact.kind, "api");
        assert_eq!(meta.artifact.id, "calculator");
    }

    #[test]
    fn parses_transport_metadata() {
        let toml = r#"
[artifact]
kind = "transport"
id = "http"
version = "0.1.0"

[runtime]
language = "rust"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert_eq!(meta.artifact.kind, "transport");
        assert_eq!(meta.artifact.id, "http");
    }

    #[test]
    fn scan_discovers_artifacts() {
        let dir = tempfile::tempdir().unwrap();
        let dir_path = dir.path();

        // Write a metadata file and a fake dll
        write_temp(dir_path, "calculator_component.itara", r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
api-version = "1.x"
[runtime]
language = "rust"
[itara]
spec-version = "0.1"
"#);

        // Create a fake dll with the right name for the platform
        #[cfg(target_os = "windows")]
        write_temp(dir_path, "calculator_component.dll", "fake");
        #[cfg(not(target_os = "windows"))]
        write_temp(dir_path, "libcalculator_component.so", "fake");

        let index = LibIndex::scan(dir_path).unwrap();
        assert!(index.component_lib("calculator").is_some());
        assert!(index.component_lib("gateway").is_none());
        assert!(index.api_lib("calculator").is_none()); // no api entry written
    }

    #[test]
    fn scan_warns_on_missing_lib() {
        let dir = tempfile::tempdir().unwrap();
        // .itara file with no sibling dll — should not panic, just warn
        write_temp(dir.path(), "orphan.itara", r#"
[artifact]
kind = "component"
id = "orphan"
version = "1.0.0"
[runtime]
language = "rust"
"#);
        let index = LibIndex::scan(dir.path()).unwrap();
        assert!(index.component_lib("orphan").is_none());
    }

    #[test]
    fn scan_returns_error_for_missing_dir() {
        let result = LibIndex::scan(Path::new("/nonexistent/path/itara-libs"));
        assert!(result.is_err());
    }
 
    #[test]
    fn parses_api_serializers() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"
api-version = "1.x"
 
[runtime]
language = "rust"
 
[serializers]
supported = ["json", "protobuf"]
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let serializers = meta.serializers.unwrap();
        assert_eq!(serializers.supported, vec!["json", "protobuf"]);
    }
 
    #[test]
    fn api_without_serializers_returns_empty() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"
[runtime]
language = "rust"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.serializers.is_none());
    }
 
    #[test]
    fn scan_discovers_observer() {
        let dir = tempfile::tempdir().unwrap();
        let dir_path = dir.path();
 
        write_temp(dir_path, "itara_observer_logging.itara", r#"
[artifact]
kind = "observer"
id = "logging"
version = "0.1.0"
[runtime]
language = "rust"
"#);
 
        #[cfg(target_os = "windows")]
        write_temp(dir_path, "itara_observer_logging.dll", "fake");
        #[cfg(not(target_os = "windows"))]
        write_temp(dir_path, "libitara_observer_logging.so", "fake");
 
        let index = LibIndex::scan(dir_path).unwrap();
        assert_eq!(index.observer_libs().len(), 1);
    }

    #[test]
    fn unknown_fields_ignored() {
        // Forward compatibility — unknown fields must not cause a parse failure
        let toml = r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
future-field = "ignored"

[runtime]
language = "rust"

[future-section]
something = "also ignored"
"#;
        // Should parse without error
        let result = toml::from_str::<MetadataFile>(toml);
        assert!(result.is_ok());
    }

    #[test]
    fn parses_transport_section_with_capabilities() {
        let toml = r#"
[artifact]
kind = "transport"
id = "http"
version = "0.1.0"

[transport]
type = "http"

[transport.capabilities]
nativeCallTimeout = true
externallyInterruptible = true
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let transport = meta.transport.unwrap();
        assert_eq!(transport.transport_type.as_deref(), Some("http"));
        assert!(transport.capabilities.native_call_timeout);
        assert!(transport.capabilities.externally_interruptible);
    }

    #[test]
    fn transport_capabilities_default_to_true_when_absent() {
        let toml = r#"
[artifact]
kind = "transport"
id = "http"
version = "0.1.0"

[transport]
type = "http"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let transport = meta.transport.unwrap();
        assert!(transport.capabilities.native_call_timeout,
            "nativeCallTimeout should default to true");
        assert!(transport.capabilities.externally_interruptible,
            "externallyInterruptible should default to true");
    }

    #[test]
    fn transport_section_absent_for_non_transport_artifacts() {
        let toml = r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.transport.is_none());
    }

    #[test]
    fn parses_kafka_transport_with_false_capabilities() {
        let toml = r#"
[artifact]
kind = "transport"
id = "kafka"
version = "0.1.0"

[transport]
type = "kafka"

[transport.capabilities]
nativeCallTimeout = false
externallyInterruptible = true
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let transport = meta.transport.unwrap();
        assert_eq!(transport.transport_type.as_deref(), Some("kafka"));
        assert!(!transport.capabilities.native_call_timeout);
        assert!(transport.capabilities.externally_interruptible);
    }
}
