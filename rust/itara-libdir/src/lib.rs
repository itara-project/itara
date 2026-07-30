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
    /// kind = "component" | "api" | "events" | "transport" | "serializer" | "observer"
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

/// A single entry in the [serializers] section of an API artifact's
/// `.itara` metadata file.
///
/// Declares one serializer this API artifact was compiled with support
/// for. The id matches a serializer's artifact.id (e.g. "json",
/// "protobuf"); the version is a semver range checked against that
/// serializer's own artifact.version.
///
/// Neither field is validated here — this struct only carries the
/// declared data. Checking a version range's syntax, and evaluating it
/// against an actual serializer's version, is tooling's job (the CLI,
/// which already depends on the semver crate for exactly this) — this
/// crate does not need one.
///
/// Mirrors ApiDependency exactly.
#[derive(Debug, Clone, Deserialize)]
pub struct SupportedSerializer {
    /// Matches the artifact.id of a serializer implementation.
    pub id: String,

    /// Version range this artifact was compiled/verified against.
    pub version: String,
}
 
/// Serializer declarations for api artifacts.
/// Lists the serializers the artifact was compiled with support for,
/// each as an id + version range.
/// Used by tooling to validate wiring config connections at configuration time.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct SerializersMeta {
    /// Serializers supported by this artifact.
    /// Populated for kind = "api" artifacts only.
    #[serde(default)]
    pub supported: Vec<SupportedSerializer>,
}

/// Capability declarations for a serializer artifact.
///
/// Declares which message formats this serializer implementation
/// handles beyond plain, hand-written types — e.g. a protobuf serializer
/// declares "protobuf" here, meaning it can generically handle
/// proto-generated types via reflection (ADR 0019).
///
/// Unlike TransportCapabilities, which defaults permissively (true)
/// since a transport is assumed capable unless it says otherwise, this
/// defaults to an empty list when the section is absent — a serializer
/// is assumed to handle plain types only until it explicitly declares a
/// structural message format it supports.
///
/// This has no bearing on error-payload handling, which is unconditional
/// for every serializer regardless of declared message-formats (ADR 0020).
#[derive(Debug, Clone, Deserialize, Default)]
pub struct SerializerCapabilities {
    #[serde(default, rename = "message-formats")]
    pub message_formats: Vec<String>,
}

/// The [serializer] section of a serializer `.itara` metadata file.
/// Only meaningful when artifact.kind = "serializer" — mirrors
/// TransportMeta's shape, including its same non-enforcement: `type` is
/// not required at parse time here either, exactly like transport_type
/// isn't enforced on TransportMeta. Enforcing "this section belongs only
/// to this kind" is left to tooling (the CLI), same as everywhere else
/// in this crate.
#[derive(Debug, Clone, Deserialize)]
pub struct SerializerMeta {
    /// The serialization category — describes what this implementation
    /// serializes to/from, e.g. "json", "protobuf". Distinct from
    /// artifact.id, which is the unique identifier of a specific
    /// implementation.
    #[serde(default, rename = "type")]
    pub serializer_type: Option<String>,

    #[serde(default)]
    pub capabilities: SerializerCapabilities,
}

/// The [contract] section of an API artifact's `.itara` metadata file.
/// Only meaningful when artifact.kind = "api" or artifact.kind = "events".
///
/// Declares the message format the contract's method parameter and
/// return types are generated from — e.g. "protobuf" (ADR 0019). This is
/// a structural property of the contract's own types, unrelated to
/// which serializer ids the artifact is compatible with (see
/// SerializersMeta) — message format and serializer choice vary
/// independently.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct ContractMeta {
    /// The declared message format, e.g. "protobuf". Empty string is
    /// treated identically to the section being absent entirely — both
    /// mean the contract uses plain, hand-written types.
    #[serde(default, rename = "message-format")]
    pub message_format: String,
}

impl ContractMeta {
    /// Returns true if this contract declares a message format other
    /// than plain hand-written types. False for both an absent
    /// [contract] section and an explicit empty-string declaration.
    pub fn has_message_format(&self) -> bool {
        !self.message_format.trim().is_empty()
    }
}

/// Capability declarations for a transport artifact.
#[derive(Debug, Clone, Deserialize)]
pub struct TransportCapabilities {
    /// Whether the transport can enforce the per-call timeout natively.
    /// Defaults to true when absent.
    #[serde(default = "default_true", rename = "native-call-timeout")]
    pub native_call_timeout: bool,

    /// Whether the transport is safe to interrupt externally on timeout.
    /// Defaults to true when absent.
    #[serde(default = "default_true", rename = "externally-interruptible")]
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

/// Capability declarations for a failure semantics artifact.
///
/// Defaults to false when the section is absent — a failure semantics
/// implementation that does not declare this capability is assumed not
/// to support it (§14.10).
#[derive(Debug, Clone, Deserialize)]
pub struct FailureSemanticsCapabilities {
    /// Whether this implementation can enforce the per-attempt timeout
    /// by external interruption of the transport thread (§14.10).
    /// Defaults to false — implementations must opt in explicitly.
    #[serde(default, rename = "supports-external-timeout")]
    pub supports_external_timeout: bool,
}

impl Default for FailureSemanticsCapabilities {
    fn default() -> Self {
        Self { supports_external_timeout: false }
    }
}

/// The [failure-semantics] section of a failure-semantics `.itara` metadata file.
/// Only meaningful when artifact.kind = "failure-semantics".
#[derive(Debug, Clone, Deserialize)]
pub struct FailureSemanticsMeta {
    #[serde(default)]
    pub capabilities: FailureSemanticsCapabilities,
}

/// A single entry in the [api-dependencies] section of a component
/// `.itara` metadata file.
///
/// Declares one synchronous API contract this component was compiled
/// against. The id matches the artifact.id of the callee's kind = "api"
/// `.itara` file. The version is the exact version the component was
/// built against.
///
/// Example TOML:
///
///   [[api-dependencies.calls]]
///   id = "calculator"
///   version = "1.0.0"
#[derive(Debug, Clone, Deserialize)]
pub struct ApiDependency {
    /// Matches artifact.id of the callee's kind = "api" artifact.
    pub id: String,

    /// Exact version this component was compiled against.
    pub version: String,
}

/// The [api-dependencies] section of a component `.itara` metadata file.
///
/// Lists the synchronous API contracts this component calls, with the
/// exact version each was compiled against. Only meaningful on
/// kind = "component" artifacts.
///
/// Absent means the component declares no outbound API calls — valid
/// for leaf components.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct ApiDependenciesMeta {
    #[serde(default)]
    pub calls: Vec<ApiDependency>,
}

/// Parsed contents of a single `.itara` metadata file.
#[derive(Debug, Clone, Deserialize)]
pub struct MetadataFile {
    pub artifact: ArtifactMeta,

    #[serde(default)]
    pub runtime: Option<RuntimeMeta>,

    #[serde(default)]
    pub itara: Option<ItaraMeta>,

    /// Declared serializers — present on kind = "api" and kind = "events" artifacts.
    #[serde(default)]
    pub serializers: Option<SerializersMeta>,

    /// The [contract] section — present on kind = "api" and kind = "events" artifacts that
    /// declare a message format (ADR 0019).
    #[serde(default)]
    pub contract: Option<ContractMeta>,

    /// The [serializer] section — present on kind = "serializer" artifacts.
    #[serde(default)]
    pub serializer: Option<SerializerMeta>,

    #[serde(default)]
    pub transport: Option<TransportMeta>,

    #[serde(default, rename = "failure-semantics")]
    pub failure_semantics: Option<FailureSemanticsMeta>,

    #[serde(default, rename = "api-dependencies")]
    pub api_dependencies: Option<ApiDependenciesMeta>,
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
 
    /// Return the serializers declared as supported by an API artifact.
    /// Returns an empty slice if the artifact is not in the index or
    /// declares no serializers.
    ///
    /// Used by tooling to validate that the serializer declared in a wiring
    /// config connection is supported by both ends before the system starts.
    pub fn supported_serializers(&self, api_id: &str) -> &[SupportedSerializer] {
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

// ── MetadataIndex ─────────────────────────────────────────────────────────────
//
// Purpose-built for the CLI. Scans a directory of `.itara` files with no
// sibling lib requirement. Returns raw metadata for the CLI to reason over.
// Makes no policy decisions — parse failures and duplicates are surfaced to
// the caller for interpretation.

/// The result of a metadata directory scan.
///
/// `index` is always populated (possibly empty). `parse_failures` and
/// `duplicates` are collected without aborting the scan — the CLI decides
/// how to surface them.
pub struct ScanResult {
    pub index: MetadataIndex,
    /// Files that could not be parsed: (path, error message).
    pub parse_failures: Vec<(PathBuf, String)>,
    /// Duplicate (kind, id) pairs: (kind, id, path of the ignored file).
    pub duplicates: Vec<(String, String, PathBuf)>,
}

/// An index of `.itara` metadata files scanned from a directory.
///
/// Keyed by (kind, id), both lowercased. The CLI uses this to look up
/// metadata by the identifiers present in the wiring config, without
/// loading any artifact.
///
/// Distinct from `LibIndex` which is agent-facing and requires sibling
/// lib files. `MetadataIndex` is tooling-facing and requires nothing
/// beyond the `.itara` files themselves.
#[derive(Debug, Default)]
pub struct MetadataIndex {
    entries: HashMap<(String, String), MetadataFile>,
}

impl MetadataIndex {
    /// Scan a directory for `.itara` files and build the index.
    ///
    /// Returns `Err` only if the directory itself cannot be read.
    /// Per-file parse failures and duplicate entries are collected in
    /// `ScanResult` for the caller to interpret.
    pub fn scan(dir: &Path) -> Result<ScanResult, String> {
        let entries = fs::read_dir(dir).map_err(|e| {
            format!("cannot read metadata directory '{}': {}", dir.display(), e)
        })?;

        let mut index = MetadataIndex::default();
        let mut parse_failures = Vec::new();
        let mut duplicates = Vec::new();

        for entry in entries.flatten() {
            let path = entry.path();
            if path.extension().and_then(|e| e.to_str()) != Some("itara") {
                continue;
            }

            let content = match fs::read_to_string(&path) {
                Ok(c) => c,
                Err(e) => {
                    parse_failures.push((path, e.to_string()));
                    continue;
                }
            };

            let meta: MetadataFile = match toml::from_str(&content) {
                Ok(m) => m,
                Err(e) => {
                    parse_failures.push((path, e.to_string()));
                    continue;
                }
            };

            let key = (
                meta.artifact.kind.to_lowercase(),
                meta.artifact.id.to_lowercase(),
            );

            if index.entries.contains_key(&key) {
                duplicates.push((key.0, key.1, path));
            } else {
                index.entries.insert(key, meta);
            }
        }

        Ok(ScanResult { index, parse_failures, duplicates })
    }

    /// Look up a component artifact by its artifact.id.
    pub fn component(&self, id: &str) -> Option<&MetadataFile> {
        self.entries.get(&("component".to_string(), id.to_lowercase()))
    }

    /// Look up a transport artifact by its artifact.id.
    pub fn transport(&self, id: &str) -> Option<&MetadataFile> {
        self.entries.get(&("transport".to_string(), id.to_lowercase()))
    }

    /// Look up a serializer artifact by its artifact.id.
    pub fn serializer(&self, id: &str) -> Option<&MetadataFile> {
        self.entries.get(&("serializer".to_string(), id.to_lowercase()))
    }

    /// Look up a failure-semantics artifact by its artifact.id.
    pub fn failure_semantics(&self, id: &str) -> Option<&MetadataFile> {
        self.entries.get(&("failure-semantics".to_string(), id.to_lowercase()))
    }

    /// Look up an API artifact by its artifact.id.
    pub fn api(&self, id: &str) -> Option<&MetadataFile> {
        self.entries.get(&("api".to_string(), id.to_lowercase()))
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
supported = [
  { id = "json", version = "1.x" },
  { id = "protobuf", version = "1.x" },
]
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let serializers = meta.serializers.unwrap();
        assert_eq!(serializers.supported.len(), 2);
        assert_eq!(serializers.supported[0].id, "json");
        assert_eq!(serializers.supported[0].version, "1.x");
        assert_eq!(serializers.supported[1].id, "protobuf");
        assert_eq!(serializers.supported[1].version, "1.x");
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
    fn parses_serializer_type_and_capabilities() {
        let toml = r#"
[artifact]
kind = "serializer"
id = "protobuf"
version = "0.1.0"

[serializer]
type = "protobuf"

[serializer.capabilities]
message-formats = ["protobuf"]
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let serializer = meta.serializer.unwrap();
        assert_eq!(serializer.serializer_type.as_deref(), Some("protobuf"));
        assert_eq!(serializer.capabilities.message_formats, vec!["protobuf"]);
    }

    #[test]
    fn serializer_capabilities_default_to_empty_when_capabilities_section_absent() {
        let toml = r#"
[artifact]
kind = "serializer"
id = "json"
version = "0.1.0"

[serializer]
type = "json"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.serializer.unwrap().capabilities.message_formats.is_empty());
    }

    #[test]
    fn serializer_section_is_none_for_non_serializer_artifacts() {
        let toml = r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.serializer.is_none());
    }

    #[test]
    fn unknown_fields_in_serializer_section_ignored() {
        let toml = r#"
[artifact]
kind = "serializer"
id = "protobuf"
version = "0.1.0"

[serializer]
type = "protobuf"
future-field = "ignored"

[serializer.capabilities]
message-formats = ["protobuf"]
future-capability = "ignored"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert_eq!(meta.serializer.unwrap().capabilities.message_formats, vec!["protobuf"]);
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
native-call-timeout = true
externally-interruptible = true
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
            "native-call-timeout should default to true");
        assert!(transport.capabilities.externally_interruptible,
            "externally-interruptible should default to true");
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
native-call-timeout = false
externally-interruptible = true
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let transport = meta.transport.unwrap();
        assert_eq!(transport.transport_type.as_deref(), Some("kafka"));
        assert!(!transport.capabilities.native_call_timeout);
        assert!(transport.capabilities.externally_interruptible);
    }

    // ── FailureSemanticsCapabilities ──────────────────────────────────────────

    #[test]
    fn failure_semantics_capabilities_default_to_false_when_absent() {
        let toml = r#"
[artifact]
kind = "failure-semantics"
id = "built-in"
version = "0.1.0"

[failure-semantics]
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let caps = meta.failure_semantics.unwrap().capabilities;
        assert!(!caps.supports_external_timeout);
    }

    #[test]
    fn failure_semantics_capabilities_parsed_when_true() {
        let toml = r#"
[artifact]
kind = "failure-semantics"
id = "built-in"
version = "0.1.0"

[failure-semantics.capabilities]
supports-external-timeout = true
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.failure_semantics.unwrap().capabilities.supports_external_timeout);
    }

    #[test]
    fn failure_semantics_absent_for_non_fs_artifacts() {
        let toml = r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.failure_semantics.is_none());
    }

    #[test]
    fn unknown_fields_in_failure_semantics_ignored() {
        let toml = r#"
[artifact]
kind = "failure-semantics"
id = "built-in"
version = "0.1.0"

[failure-semantics.capabilities]
supports-external-timeout = true
future-field = "ignored"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.failure_semantics.unwrap().capabilities.supports_external_timeout);
    }

    // ── ApiDependenciesMeta ───────────────────────────────────────────────────

    #[test]
    fn api_dependencies_absent_is_none() {
        let toml = r#"
[artifact]
kind = "component"
id = "gateway"
version = "1.0.0"
api-version = "1.0.0"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.api_dependencies.is_none());
    }

    #[test]
    fn api_dependencies_single_entry() {
        let toml = r#"
[artifact]
kind = "component"
id = "gateway"
version = "1.0.0"
api-version = "1.0.0"

[api-dependencies]
calls = [
  { id = "calculator", version = "1.0.0" },
]
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let calls = &meta.api_dependencies.unwrap().calls;
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].id, "calculator");
        assert_eq!(calls[0].version, "1.0.0");
    }

    #[test]
    fn api_dependencies_multiple_entries() {
        let toml = r#"
[artifact]
kind = "component"
id = "gateway"
version = "1.0.0"
api-version = "1.0.0"

[api-dependencies]
calls = [
  { id = "calculator", version = "1.0.0" },
  { id = "inventory",  version = "2.1.0" },
]
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let calls = &meta.api_dependencies.unwrap().calls;
        assert_eq!(calls.len(), 2);
        assert_eq!(calls[0].id, "calculator");
        assert_eq!(calls[0].version, "1.0.0");
        assert_eq!(calls[1].id, "inventory");
        assert_eq!(calls[1].version, "2.1.0");
    }

    #[test]
    fn api_dependencies_absent_for_non_component_artifacts() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.api_dependencies.is_none());
    }

    #[test]
    fn unknown_fields_in_api_dependencies_ignored() {
        let toml = r#"
[artifact]
kind = "component"
id = "gateway"
version = "1.0.0"
api-version = "1.0.0"

[api-dependencies]
calls = [
  { id = "calculator", version = "1.0.0", future-field = "ignored" },
]
future-section-field = "also ignored"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let calls = &meta.api_dependencies.unwrap().calls;
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].id, "calculator");
    }

    #[test]
    fn parses_contract_message_format() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"

[contract]
message-format = "protobuf"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let contract = meta.contract.unwrap();
        assert_eq!(contract.message_format, "protobuf");
        assert!(contract.has_message_format());
    }

    #[test]
    fn parses_contract_message_format_on_events_artifact() {
        // Events artifacts are contract types too — just without an
        // implementation of their own — so [contract] applies to them
        // exactly as it does to api artifacts.
        let toml = r#"
[artifact]
kind = "events"
id = "order-events/order-placed"
version = "1.0.0"

[contract]
message-format = "protobuf"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let contract = meta.contract.unwrap();
        assert_eq!(contract.message_format, "protobuf");
        assert!(contract.has_message_format());
    }

    #[test]
    fn contract_is_none_when_section_absent() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(meta.contract.is_none());
    }

    #[test]
    fn message_format_defaults_to_empty_when_absent_from_declared_contract_section() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"

[contract]
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        let contract = meta.contract.unwrap();
        assert_eq!(contract.message_format, "");
        assert!(!contract.has_message_format());
    }

    #[test]
    fn explicit_empty_string_message_format_treated_identically_to_absent() {
        let toml = r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"

[contract]
message-format = ""
"#;
        let meta: MetadataFile = toml::from_str(toml).unwrap();
        assert!(!meta.contract.unwrap().has_message_format());
    }

    // ── MetadataIndex ─────────────────────────────────────────────────────────

    #[test]
    fn scan_indexes_component_and_api_with_same_id() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();

        write_temp(p, "calculator-component.itara", r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
api-version = "1.0.0"
"#);
        write_temp(p, "calculator-api.itara", r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"
"#);

        let result = MetadataIndex::scan(p).unwrap();
        assert!(result.parse_failures.is_empty());
        assert!(result.duplicates.is_empty());
        assert!(result.index.component("calculator").is_some());
        assert!(result.index.api("calculator").is_some());
    }

    #[test]
    fn scan_transport_lookup_by_artifact_id() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();

        write_temp(p, "itara-http.itara", r#"
[artifact]
kind = "transport"
id = "http"
version = "0.1.0"

[transport]
type = "http"

[transport.capabilities]
native-call-timeout = true
externally-interruptible = true
"#);

        let result = MetadataIndex::scan(p).unwrap();
        assert!(result.parse_failures.is_empty());
        let meta = result.index.transport("http").unwrap();
        assert_eq!(meta.artifact.id, "http");
        assert!(meta.transport.as_ref().unwrap().capabilities.native_call_timeout);
    }

    #[test]
    fn scan_failure_semantics_lookup_by_artifact_id() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();

        write_temp(p, "built-in.itara", r#"
[artifact]
kind = "failure-semantics"
id = "built-in"
version = "0.1.0"

[failure-semantics.capabilities]
supports-external-timeout = true
"#);

        let result = MetadataIndex::scan(p).unwrap();
        assert!(result.parse_failures.is_empty());
        let meta = result.index.failure_semantics("built-in").unwrap();
        assert!(meta.failure_semantics.as_ref().unwrap().capabilities.supports_external_timeout);
    }

    #[test]
    fn scan_missing_dir_returns_error() {
        let result = MetadataIndex::scan(Path::new("/nonexistent/metadata/dir"));
        assert!(result.is_err());
    }

    #[test]
    fn scan_unparseable_file_collected_not_fatal() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();

        write_temp(p, "broken.itara", "this is not valid toml = = =");
        write_temp(p, "calculator-api.itara", r#"
[artifact]
kind = "api"
id = "calculator"
version = "1.0.0"
"#);

        let result = MetadataIndex::scan(p).unwrap();
        assert_eq!(result.parse_failures.len(), 1);
        assert!(result.parse_failures[0].0.ends_with("broken.itara"));
        assert!(result.index.api("calculator").is_some());
    }

    #[test]
    fn scan_duplicate_kind_id_keeps_first_collects_duplicate() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();

        write_temp(p, "calculator-a.itara", r#"
[artifact]
kind = "component"
id = "calculator"
version = "1.0.0"
api-version = "1.0.0"
"#);
        write_temp(p, "calculator-b.itara", r#"
[artifact]
kind = "component"
id = "calculator"
version = "2.0.0"
api-version = "2.0.0"
"#);

        let result = MetadataIndex::scan(p).unwrap();
        assert_eq!(result.duplicates.len(), 1);
        assert_eq!(result.duplicates[0].0, "component");
        assert_eq!(result.duplicates[0].1, "calculator");
        // the index still has an entry — first one wins
        assert!(result.index.component("calculator").is_some());
    }

    #[test]
    fn component_not_found_returns_none() {
        let dir = tempfile::tempdir().unwrap();
        let result = MetadataIndex::scan(dir.path()).unwrap();
        assert!(result.index.component("nonexistent").is_none());
    }

    #[test]
    fn scan_serializer_lookup_by_artifact_id() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path();

        write_temp(p, "itara-protobuf.itara", r#"
[artifact]
kind = "serializer"
id = "protobuf"
version = "0.1.0"

[serializer]
type = "protobuf"

[serializer.capabilities]
message-formats = ["protobuf"]
"#);

        let result = MetadataIndex::scan(p).unwrap();
        assert!(result.parse_failures.is_empty());
        let meta = result.index.serializer("protobuf").unwrap();
        assert_eq!(meta.artifact.id, "protobuf");
        assert_eq!(
            meta.serializer.as_ref().unwrap().capabilities.message_formats,
            vec!["protobuf"]
        );
    }

    #[test]
    fn serializer_not_found_returns_none() {
        let dir = tempfile::tempdir().unwrap();
        let result = MetadataIndex::scan(dir.path()).unwrap();
        assert!(result.index.serializer("nonexistent").is_none());
    }
}
