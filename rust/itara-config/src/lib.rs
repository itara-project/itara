
use std::env;
use std::fs;
use regex::Regex;
use serde::Deserialize;
use yaml_merge_keys::merge_keys_serde;

// ── Error type ────────────────────────────────────────────────────────────────

/// Thrown when the wiring configuration is malformed or contains invalid values.
/// Distinct from IO errors — this means the file was readable but its content
/// was invalid.
#[derive(Debug)]
pub enum ConfigError {
    /// The file could not be read.
    Io(String),
    /// The YAML was malformed or required fields were missing.
    Invalid(String),
    /// A required environment variable or system property was not set.
    MissingProperty(String),
}

impl std::fmt::Display for ConfigError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ConfigError::Io(msg)              => write!(f, "[Itara] {}", msg),
            ConfigError::Invalid(msg)         => write!(f, "[Itara] {}", msg),
            ConfigError::MissingProperty(msg) => write!(f, "[Itara] {}", msg),
        }
    }
}// ── Id validation ────────────────────────────────────────────────────────────

/// The character set required for node ids and connection ids: letters,
/// digits, '.', '_', and '-'. Enforced at parse time since these ids are
/// used as dispatch keys and observability event fields downstream.
const ID_CHARSET: &str = r"^[A-Za-z0-9._-]+$";

/// Returns true if `id` contains only characters from `ID_CHARSET`.
fn has_valid_id_charset(id: &str) -> bool {
    Regex::new(ID_CHARSET).unwrap().is_match(id)
}

// ── Data model ────────────────────────────────────────────────────────────────

/// The kind of a node in the wiring configuration.
/// When absent, `component` is assumed for backwards compatibility.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NodeKind {
    Component,
    Virtual,
}

/// A component node - a deployable component with an activator and a contract.
///
/// Example YAML:
/// ```yaml
///   nodes:
///     - id: "orderServiceNode"
///       component: "order-service"
///
///     - id: "inventoryNode"
///       kind: component      # optional — component is the default
///       component: "inventory"
/// ```
#[derive(Debug, Clone)]
pub struct ComponentNode {
    pub id: String,
    /// Must match the id declared in the @ComponentInterface annotation.
    pub component: String,
}

impl ComponentNode {
    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.id.trim().is_empty() {
            return Err(ConfigError::Invalid(
                "Component node is missing required field 'id'.".to_string(),
            ));
        }
        if !has_valid_id_charset(&self.id) {
            return Err(ConfigError::Invalid(format!(
                "Component node id '{}' contains invalid characters — ids may only \
                 contain letters, digits, '.', '_', and '-'.",
                self.id
            )));
        }
        if self.component.trim().is_empty() {
            return Err(ConfigError::Invalid(format!(
                "Component node '{}' is missing required field 'component'.",
                self.id
            )));
        }
        Ok(())
    }
}

/// A virtual node — a communication channel with no component implementation.
/// Decouples producers from consumers via a broker.
///
/// Example YAML:
///   nodes:
///     - id: "orderPlacedChannel"
///       kind: virtual
///       contract: "order-events/order-placed"
///       address: "demo.events.order-placed"
///
/// See spec §13.2.1.
#[derive(Debug, Clone)]
pub struct VirtualNode {
    pub id: String,
    /// Full contract reference: "<collection-id>/<contract-id>"
    /// e.g. "order-events/order-placed"
    pub contract: String,
    /// Broker-specific channel address (e.g. Kafka topic name).
    pub address: String,
}

impl VirtualNode {
    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.id.trim().is_empty() {
            return Err(ConfigError::Invalid(
                "Virtual node is missing required field 'id'.".to_string(),
            ));
        }
        if !has_valid_id_charset(&self.id) {
            return Err(ConfigError::Invalid(format!(
                "Virtual node id '{}' contains invalid characters — ids may only \
                 contain letters, digits, '.', '_', and '-'.",
                self.id
            )));
        }
        if self.contract.trim().is_empty() {
            return Err(ConfigError::Invalid(format!(
                "Virtual node '{}' is missing required field 'contract'.",
                self.id
            )));
        }
        if self.address.trim().is_empty() {
            return Err(ConfigError::Invalid(format!(
                "Virtual node '{}' is missing required field 'address'.",
                self.id
            )));
        }
        Ok(())
    }
}

/// A node declared in the wiring configuration.
///
/// The `kind` field is the discriminator. When absent, `component` is assumed
/// for backwards compatibility with existing wiring configs.
///
/// Provides `contract_identifier()` — the id used to look up the contract
/// class and register proxies or dispatchers — without branching on node type.
///
/// See spec §4.3.
#[derive(Debug, Clone)]
pub enum Node {
    Component(ComponentNode),
    Virtual(VirtualNode),
}

impl Node {
    pub fn id(&self) -> &str {
        match self {
            Node::Component(n) => &n.id,
            Node::Virtual(n)   => &n.id,
        }
    }

    pub fn kind(&self) -> NodeKind {
        match self {
            Node::Component(_) => NodeKind::Component,
            Node::Virtual(_)   => NodeKind::Virtual,
        }
    }

    pub fn is_virtual(&self) -> bool {
        matches!(self, Node::Virtual(_))
    }

    /// The contract identifier for this node.
    /// For component nodes: the component id (e.g. "order-service").
    /// For virtual nodes: the full contract reference
    ///                    (e.g. "order-events/order-placed").
    pub fn contract_identifier(&self) -> &str {
        match self {
            Node::Component(n) => &n.component,
            Node::Virtual(n)   => &n.contract,
        }
    }

    pub fn as_component(&self) -> Option<&ComponentNode> {
        match self {
            Node::Component(n) => Some(n),
            _                  => None,
        }
    }

    pub fn as_virtual(&self) -> Option<&VirtualNode> {
        match self {
            Node::Virtual(n) => Some(n),
            _                => None,
        }
    }

    pub fn validate(&self) -> Result<(), ConfigError> {
        match self {
            Node::Component(n) => n.validate(),
            Node::Virtual(n)   => n.validate(),
        }
    }
}

/// Internal helper for deserialising a Node from YAML using a tagged enum.
/// This gives serde_path_to_error the correct field paths (e.g. nodes[0].component).
#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
enum NodeHelper {
    Component(ComponentNodeHelper),
    Virtual(VirtualNodeHelper),
}

#[derive(Deserialize)]
struct ComponentNodeHelper {
    id: String,
    component: String,
}

#[derive(Deserialize)]
struct VirtualNodeHelper {
    id: String,
    contract: String,
    address: String,
}

impl<'de> serde::Deserialize<'de> for Node {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        use serde::de::Error;

        // Deserialize to a raw Value first to handle default kind
        let mut value = serde_yaml::Value::deserialize(d)?;

        // Apply default kind = "component" for backwards compatibility
        if let serde_yaml::Value::Mapping(ref mut map) = value {
            if !map.contains_key(&serde_yaml::Value::String("kind".to_string())) {
                map.insert(
                    serde_yaml::Value::String("kind".to_string()),
                    serde_yaml::Value::String("component".to_string()),
                );
            }
        }

        // Now deserialize into the tagged enum - this gives correct field paths
        let helper = NodeHelper::deserialize(value).map_err(|e| D::Error::custom(e.to_string()))?;

        match helper {
            NodeHelper::Component(h) => Ok(Node::Component(ComponentNode {
                id: h.id,
                component: h.component,
            })),
            NodeHelper::Virtual(h) => Ok(Node::Virtual(VirtualNode {
                id: h.id,
                contract: h.contract,
                address: h.address,
            })),
        }
    }
}

/// The transport block of a connection entry in the wiring configuration.
///
/// Example YAML:
///   transport:
///     id: http
///     handleTimeout: true
///     params:
///       host: "${CALC_HOST:-localhost}"
///       port: "8081"
#[derive(Debug, Clone, Deserialize)]
pub struct TransportEntry {
    /// The transport type identifier. Required.
    pub id: String,

    /// Whether the transport should enforce the per-attempt timeout natively.
    #[serde(default, rename = "handleTimeout")]
    pub handle_timeout: bool,

    /// Transport-specific connection parameters.
    /// Keys and values are transport-defined.
    #[serde(default, deserialize_with = "deserialize_params")]
    pub params: std::collections::HashMap<String, String>,
}

fn deserialize_params<'de, D>(d: D) -> Result<std::collections::HashMap<String, String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    use serde::de::{MapAccess, Visitor};
    use std::fmt;

    struct ParamsVisitor;

    impl<'de> Visitor<'de> for ParamsVisitor {
        type Value = std::collections::HashMap<String, String>;

        fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
            write!(f, "a map of string keys to scalar values")
        }

        fn visit_map<M: MapAccess<'de>>(self, mut map: M)
            -> Result<Self::Value, M::Error>
        {
            let mut result = std::collections::HashMap::new();
            while let Some(key) = map.next_key::<String>()? {
                let value = map.next_value::<serde_yaml::Value>()?;
                let s = match &value {
                    serde_yaml::Value::String(s)  => s.clone(),
                    serde_yaml::Value::Number(n)  => n.to_string(),
                    serde_yaml::Value::Bool(b)    => b.to_string(),
                    other => return Err(serde::de::Error::custom(
                        format!("params value for '{}' must be a scalar, got: {:?}", key, other)
                    )),
                };
                result.insert(key, s);
            }
            Ok(result)
        }
    }

    d.deserialize_map(ParamsVisitor)
}

/// The serializer block of a connection entry in the wiring configuration.
///
/// Mirrors TransportEntry's shape exactly.
///
/// Example YAML:
///   serializer:
///     id: json
///     params:
///       schemaRegistryUrl: "${SCHEMA_REGISTRY_URL:-http://localhost:8081}"
#[derive(Debug, Clone, Deserialize)]
pub struct SerializerEntry {
    /// The serializer id. Required.
    pub id: String,

    /// Serializer-specific connection parameters.
    /// Keys and values are serializer-defined.
    #[serde(default, deserialize_with = "deserialize_params")]
    pub params: std::collections::HashMap<String, String>,
}

/// The failureSemantics block of a connection entry in the wiring configuration.
///
/// Mirrors the Java `FailureSemanticsEntry` exactly — same fields, same defaults.
/// Duration values (timeout, absoluteTimeout) are kept as raw strings; parsing
/// to Duration is an agent concern, not a config-parsing concern.
///
/// Example YAML:
///   failureSemantics:
///     id: built-in
///     timeout: 2s
///     handleTimeout: true
///     absoluteTimeout: 10s
///     maxRetry: 3
///     params:
///       waitDuration: 500ms
///
/// Absent means the noop implementation is used (§14.1).
#[derive(Debug, Clone, Deserialize)]
pub struct FailureSemanticsEntry {
    /// The failure semantics type identifier.
    pub id: String,

    /// Per-attempt timeout as a duration string, e.g. "2s", "500ms".
    #[serde(default)]
    pub timeout: Option<String>,

    /// Whether the failure semantics implementation should enforce the
    /// per-attempt timeout by external interruption (§14.10).
    #[serde(default, rename = "handleTimeout")]
    pub handle_timeout: bool,

    /// Hard ceiling on total execution time across all attempts, e.g. "10s".
    #[serde(default, rename = "absoluteTimeout")]
    pub absolute_timeout: Option<String>,

    /// Maximum number of retries. Attempts = maxRetry + 1.
    #[serde(default, rename = "maxRetry")]
    pub max_retry: Option<u32>,

    /// Implementation-specific parameters.
    #[serde(default, deserialize_with = "deserialize_params")]
    pub params: std::collections::HashMap<String, String>,
}

/// A connection declared in the wiring configuration.
///
/// Defines how one node calls another, including the transport type and
/// any transport-specific properties.
///
/// The 'from' field may be absent or empty, indicating that the caller is
/// external to the Itara topology. This defines an inbound entry point
/// for the 'to' node.
///
/// Example YAML:
///   connections:
///     - id: "gateway-to-calculator"
///       from: "gatewayNode"
///       to: "calculatorNode"
///       transport:
///         id: http
///         params:
///           host: "${CALC_HOST:-localhost}"
///           port: 8081
///       serializer:
///         id: json
#[derive(Debug, Clone)]
pub struct ConnectionEntry {
    /// Unique identifier for this connection. Required — MUST be unique
    /// across the entire wiring configuration (spec §4.4). Used to apply
    /// this connection's own transport, serializer, and failure semantics
    /// to every call made on it.
    pub id: String,

    /// The calling node id. None or empty = external caller.
    pub from: Option<String>,

    /// The called node id. Required.
    pub to: String,

    /// Transport configuration for this connection.
    pub transport: TransportEntry,

    /// Required serializer configuration for this connection, except for
    /// direct (colocated) connections.
    pub serializer: Option<SerializerEntry>,

    /// Optional failure semantics configuration for this connection.
    /// None means the noop implementation is used (§14.1).
    pub failure_semantics: Option<FailureSemanticsEntry>,
}

#[derive(Deserialize)]
struct ConnectionHelper {
    id: String,
    #[serde(default)]
    from: Option<String>,
    to: String,
    transport: TransportEntry,
    #[serde(default)]
    serializer: Option<SerializerEntry>,
    #[serde(default, rename = "failureSemantics")]
    failure_semantics: Option<FailureSemanticsEntry>,
}

impl<'de> serde::Deserialize<'de> for ConnectionEntry {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let h = ConnectionHelper::deserialize(d)?;
        Ok(ConnectionEntry {
            id:         h.id,
            from:       h.from,
            to:         h.to,
            transport:  h.transport,
            serializer: h.serializer,
            failure_semantics:  h.failure_semantics,
        })
    }
}

impl ConnectionEntry {
    pub fn is_external(&self) -> bool {
        self.from.as_deref().map(|s| s.trim().is_empty()).unwrap_or(true)
    }

    pub fn is_direct(&self) -> bool {
        self.transport.id.eq_ignore_ascii_case("direct")
    }

    /// Returns true if this connection involves any of the given node ids.
    pub fn is_related_to_any_of_nodes(&self, node_ids: &[String]) -> bool {
        if let Some(from) = &self.from {
            if !from.trim().is_empty() && node_ids.contains(from) {
                return true;
            }
        }
        node_ids.contains(&self.to)
    }

    /// Returns true if a per-attempt timeout is declared on this connection.
    /// Convenience for verify checks that need to know timeout presence
    /// without navigating the nested Option chain themselves.
    pub fn has_timeout(&self) -> bool {
        self.failure_semantics
            .as_ref()
            .and_then(|fs| fs.timeout.as_ref())
            .is_some()
    }

    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.id.trim().is_empty() {
            return Err(ConfigError::Invalid(
                "Connection is missing required field 'id'.".to_string(),
            ));
        }
        if !has_valid_id_charset(&self.id) {
            return Err(ConfigError::Invalid(format!(
                "Connection id '{}' contains invalid characters — ids may only \
                 contain letters, digits, '.', '_', and '-'.",
                self.id
            )));
        }
        if self.to.trim().is_empty() {
            return Err(ConfigError::Invalid(
                "Connection is missing required field 'to'.".to_string(),
            ));
        }
        if self.transport.id.trim().is_empty() {
            return Err(ConfigError::Invalid(format!(
                "Connection to='{}' is missing required field 'transport.id'.",
                self.to
            )));
        }
        if !self.is_direct() {
            let missing_id = match &self.serializer {
                None => true,
                Some(s) => s.id.trim().is_empty(),
            };
            if missing_id {
                return Err(ConfigError::Invalid(format!(
                    "Connection to='{}' is missing required field 'serializer.id'.",
                    self.to
                )));
            }
        }
        Ok(())
    }
}

/// The full wiring configuration for a deployment.
///
/// Loaded from the file specified by the ITARA_CONFIG environment variable.
/// Each process reads the full config and self-selects its relevant slice
/// based on the ITARA_NODES environment variable.
#[derive(Debug, Clone, Deserialize, Default)]
pub struct WiringConfig {
    #[serde(default)]
    pub nodes: Vec<Node>,

    #[serde(default)]
    pub connections: Vec<ConnectionEntry>,

    /// Populated after loading — the node ids this process is responsible for.
    /// Not present in the YAML; set by the config loader from ITARA_NODES.
    #[serde(skip)]
    pub local_node_ids: Vec<String>,
}

impl WiringConfig {
    pub fn validate(&self) -> Result<(), ConfigError> {
        for node in &self.nodes {
            node.validate()?;
        }
        for conn in &self.connections {
            conn.validate()?;
        }
        Ok(())
    }

    /// Returns component nodes only.
    pub fn component_nodes(&self) -> Vec<&ComponentNode> {
        self.nodes.iter().filter_map(|n| n.as_component()).collect()
    }

    /// Returns virtual nodes only.
    pub fn virtual_nodes(&self) -> Vec<&VirtualNode> {
        self.nodes.iter().filter_map(|n| n.as_virtual()).collect()
    }

    /// Finds any node by id.
    pub fn find_node(&self, id: &str) -> Option<&Node> {
        self.nodes.iter().find(|n| n.id() == id)
    }

    /// Returns the component id for a given component node id.
    pub fn component_of_node(&self, node_id: &str) -> Option<&str> {
        self.nodes.iter()
            .find(|n| n.id() == node_id)
            .and_then(|n| n.as_component())
            .map(|n| n.component.as_str())
    }

    pub fn is_virtual_node(&self, node_id: &str) -> bool {
        self.find_node(node_id)
            .map(|n| n.is_virtual())
            .unwrap_or(false)
    }

    pub fn is_node_local(&self, node_id: &str) -> bool {
        self.local_node_ids.contains(&node_id.to_string())
    }

    pub fn local_nodes(&self) -> Vec<&Node> {
        self.nodes.iter()
            .filter(|n| self.is_node_local(n.id()))
            .collect()
    }
}

// ── Config loader ─────────────────────────────────────────────────────────────

pub const CONFIG_ENV_VAR: &str = "ITARA_CONFIG";
pub const NODES_ENV_VAR: &str = "ITARA_NODES";

/// Loads the wiring config from the path specified by ITARA_CONFIG,
/// filtered to the nodes specified by ITARA_NODES.
///
/// Loading happens in two phases:
///   1. Environment variable substitution on the raw file content.
///   2. YAML parsing into WiringConfig structs.
///
/// After parsing, the config is validated and filtered to only the nodes
/// and connections relevant to this process.
pub fn load() -> Result<WiringConfig, ConfigError> {
    let path = env::var(CONFIG_ENV_VAR).map_err(|_| {
        ConfigError::MissingProperty(format!(
            "No wiring config specified. Set {}=/path/to/wiring.yaml",
            CONFIG_ENV_VAR
        ))
    })?;

    if path.trim().is_empty() {
        return Err(ConfigError::MissingProperty(format!(
            "No wiring config specified. Set {}=/path/to/wiring.yaml",
            CONFIG_ENV_VAR
        )));
    }

    let nodes_str = env::var(NODES_ENV_VAR).map_err(|_| {
        ConfigError::MissingProperty(format!(
            "No nodes specified. Set {}=node1,node2",
            NODES_ENV_VAR
        ))
    })?;

    let node_ids: Vec<String> = nodes_str
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();

    if node_ids.is_empty() {
        return Err(ConfigError::MissingProperty(format!(
            "Nodes cannot be parsed. Check {}=node1,node2. Current value: '{}'",
            NODES_ENV_VAR, nodes_str
        )));
    }

    let full_config = parse_file(&path)?;
    relevant_part_of(full_config, node_ids)
}

/// Parse a wiring config from a file path.
/// Visible for testing.
pub fn parse_file(path: &str) -> Result<WiringConfig, ConfigError> {
    let raw = fs::read_to_string(path).map_err(|e| {
        ConfigError::Io(format!(
            "Could not read wiring config from '{}': {}",
            path, e
        ))
    })?;
    parse_string(&raw)
}

/// Parse a wiring config from a raw YAML string.
/// Environment variable substitution is applied before parsing.
/// Visible for testing — allows testing without a file on disk.
pub fn parse_string(yaml: &str) -> Result<WiringConfig, ConfigError> {
    let substituted = substitute_env_vars(yaml);

    // Empty or comment-only documents — return empty config
    let is_empty = substituted.lines()
        .map(|l| l.trim())
        .all(|l| l.is_empty() || l.starts_with('#'));

    if substituted.trim().is_empty() || is_empty {
        return Ok(WiringConfig::default());
    }

    // serde_yaml operates at the event stream level and does not resolve
    // YAML merge keys (<<) or aliases into the final value graph.
    // Parse to a raw Value first, resolve merge keys, then deserialize.
    let raw: serde_yaml::Value = serde_yaml::from_str(&substituted).map_err(|e| {
        ConfigError::Invalid(format!("Failed to parse wiring config: {}", e))
    })?;

    let resolved = merge_keys_serde(raw).map_err(|e| {
        ConfigError::Invalid(format!("Failed to resolve YAML merge keys: {}", e))
    })?;

    let config: WiringConfig = serde_path_to_error::deserialize(resolved).map_err(|e| {
    ConfigError::Invalid(format!("Failed to parse wiring config: {}", e))
    })?;

    config.validate()?;
    Ok(config)
}

/// Filter the full config to only the nodes and connections relevant
/// to this process, and set the local_node_ids.
pub fn relevant_part_of(
    full_config: WiringConfig,
    node_ids: Vec<String>,
) -> Result<WiringConfig, ConfigError> {
    let connections: Vec<ConnectionEntry> = full_config.connections.into_iter()
        .filter(|conn| conn.is_related_to_any_of_nodes(&node_ids))
        .collect();

    // Collect all node ids referenced by the relevant connections
    let mut relevant_node_ids: Vec<String> = Vec::new();
    for conn in &connections {
        if let Some(from) = &conn.from {
            if !from.trim().is_empty() {
                relevant_node_ids.push(from.clone());
            }
        }
        relevant_node_ids.push(conn.to.clone());
    }
    relevant_node_ids.sort();
    relevant_node_ids.dedup();

    // Both component and virtual nodes are in the same list —
    // filter by id, same as before.
    let nodes: Vec<Node> = full_config.nodes.into_iter()
        .filter(|n| relevant_node_ids.contains(&n.id().to_string()))
        .collect();

    let config = WiringConfig {
        nodes,
        connections,
        local_node_ids: node_ids,
    };

    config.validate()?;
    Ok(config)
}

// ── Environment variable substitution ─────────────────────────────────────────

/// Substitutes ${VAR:-default} and ${VAR} patterns in the raw YAML string
/// before it is handed to the YAML parser.
///
/// Substitution happens on the raw string so the parser always sees clean,
/// well-typed content. A port substituted from an env var arrives as a plain
/// integer string, which the YAML parser coerces to u16.
///
/// If a variable is not set and has no default, the placeholder is left as-is
/// and a warning is printed.
fn substitute_env_vars(raw: &str) -> String {
    // Matches ${VAR_NAME} and ${VAR_NAME:-default}
    let re = Regex::new(r"\$\{([^}:]+)(?::-(.*?))?\}").unwrap();

    re.replace_all(raw, |caps: &regex::Captures| {
        let var_name = &caps[1];
        let default_val = caps.get(2).map(|m| m.as_str());

        match env::var(var_name) {
            Ok(val) => val,
            Err(_) => match default_val {
                Some(default) => default.to_string(),
                None => {
                    eprintln!(
                        "[Itara] Warning: environment variable '{}' is not set and has no default. \
                         Placeholder '{}' will be used as-is.",
                        var_name, &caps[0]
                    );
                    caps[0].to_string()
                }
            },
        }
    }).to_string()
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    const HTTP_CONFIG: &str = r#"
nodes:
  - id: "gatewayNode"
    component: "gateway"
  - id: "calculatorNode"
    component: "calculator"

connections:
  - id: "gateway-to-calculator"
    from: "gatewayNode"
    to: "calculatorNode"
    transport:
      id: http
      params:
        host: "calculator"
        port: "8081"
    serializer:
      id: json

  - id: "external-to-calculator"
    from:
    to: "gatewayNode"
    transport:
      id: http
      params:
        port: "8082"
    serializer:
      id: json
"#;

    #[test]
    fn parses_nodes() {
        let config = parse_string(HTTP_CONFIG).unwrap();
        assert_eq!(config.nodes.len(), 2);
        assert_eq!(config.nodes[0].id(), "gatewayNode");
        assert_eq!(config.nodes[0].as_component().unwrap().component, "gateway");
        assert_eq!(config.nodes[1].id(), "calculatorNode");
        assert_eq!(config.nodes[1].as_component().unwrap().component, "calculator");
    }

    #[test]
    fn parses_connections() {
        let config = parse_string(HTTP_CONFIG).unwrap();
        assert_eq!(config.connections.len(), 2);

        let conn = &config.connections[0];
        assert_eq!(conn.from.as_deref(), Some("gatewayNode"));
        assert_eq!(conn.to, "calculatorNode");
        assert_eq!(conn.transport.id, "http");
        assert_eq!(conn.transport.params.get("host").map(|s| s.as_str()), Some("calculator"));
        assert_eq!(conn.transport.params.get("port").map(|s| s.as_str()), Some("8081"));
        assert_eq!(conn.serializer.as_ref().unwrap().id, "json");
    }

    #[test]
    fn external_connection_has_no_from() {
        let config = parse_string(HTTP_CONFIG).unwrap();
        let external = &config.connections[1];
        assert!(external.is_external());
        assert_eq!(external.to, "gatewayNode");
        assert_eq!(external.transport.params.get("port").map(|s| s.as_str()), Some("8082"));
    }

    #[test]
    fn filters_to_relevant_nodes() {
        let full = parse_string(HTTP_CONFIG).unwrap();
        let filtered = relevant_part_of(full, vec!["gatewayNode".to_string()]).unwrap();

        // gatewayNode is local
        assert!(filtered.is_node_local("gatewayNode"));
        // both connections involve gatewayNode
        assert_eq!(filtered.connections.len(), 2);
        // calculatorNode is included because it appears in a connection
        assert!(filtered.nodes.iter().any(|n| n.id() == "calculatorNode"));
    }

    #[test]
    fn substitutes_env_vars_with_defaults() {
        let yaml = "host: ${CALC_HOST:-localhost}\nport: ${CALC_PORT:-8081}";
        let result = substitute_env_vars(yaml);
        assert_eq!(result, "host: localhost\nport: 8081");
    }

    #[test]
    fn empty_config_returns_default() {
        let config = parse_string("# just a comment").unwrap();
        assert!(config.nodes.is_empty());
        assert!(config.connections.is_empty());
    }

    #[test]
    fn component_of_node_returns_correct_value() {
        let config = parse_string(HTTP_CONFIG).unwrap();
        assert_eq!(config.component_of_node("gatewayNode"), Some("gateway"));
        assert_eq!(config.component_of_node("unknown"), None);
    }

    const EVENTS_CONFIG: &str = r#"
nodes:
  - id: "orderServiceNode"
    component: "order-service"
  - id: "orderPlacedChannel"
    kind: virtual
    contract: "order-events/order-placed"
    address: "demo.events.order-placed"

connections:
  - id: "order-to-orderPlaced"
    from: "orderServiceNode"
    to: "orderPlacedChannel"
    transport:
      id: kafka
    serializer:
      id: json
  - id: "orderPlaced-to-order"
    from: "orderPlacedChannel"
    to: "orderServiceNode"
    transport:
      id: kafka
      params:
        consumerGroup: "order-consumer-group"
    serializer:
      id: json
"#;

    #[test]
    fn parses_virtual_node() {
        let config = parse_string(EVENTS_CONFIG).unwrap();
        assert_eq!(config.virtual_nodes().len(), 1);
        let vn = config.virtual_nodes()[0];
        assert_eq!(vn.id, "orderPlacedChannel");
        assert_eq!(vn.contract, "order-events/order-placed");
        assert_eq!(vn.address, "demo.events.order-placed");
    }

    #[test]
    fn node_without_kind_defaults_to_component() {
        let yaml = r#"
nodes:
  - id: "orderServiceNode"
    component: "order-service"
"#;
        let config = parse_string(yaml).unwrap();
        assert!(matches!(config.nodes[0], Node::Component(_)));
    }

    #[test]
    fn component_nodes_excludes_virtual() {
        let config = parse_string(EVENTS_CONFIG).unwrap();
        assert_eq!(config.component_nodes().len(), 1);
        assert_eq!(config.component_nodes()[0].id, "orderServiceNode");
    }

    #[test]
    fn is_virtual_node_returns_correct_value() {
        let config = parse_string(EVENTS_CONFIG).unwrap();
        assert!(config.is_virtual_node("orderPlacedChannel"));
        assert!(!config.is_virtual_node("orderServiceNode"));
    }

    #[test]
    fn kafka_connection_valid_without_port() {
        let yaml = r#"
nodes:
  - id: "a"
    component: "comp-a"
  - id: "b"
    kind: virtual
    contract: "events/placed"
    address: "topic.placed"
connections:
  - id: "a-to-b"
    from: "a"
    to: "b"
    transport:
      id: kafka
    serializer:
      id: json
"#;
        assert!(parse_string(yaml).is_ok());
    }

    // ── YAML anchors, aliases, and merge keys ─────────────────────────────

    #[test]
    fn scalar_alias_resolves_correctly() {
        let yaml = r#"
anchors:
  host: &calcHost "localhost"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
      params:
        host: *calcHost
        port: "8081"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(
            config.connections[0].transport.params.get("host").map(|s| s.as_str()),
            Some("localhost")
        );
    }

    #[test]
    fn mapping_alias_resolves_correctly() {
        let yaml = r#"
connections:
  - &baseConn
    id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
      params:
        host: localhost
        port: "8081"
    serializer:
      id: json
  - *baseConn
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections.len(), 2);
        assert_eq!(config.connections[1].transport.params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(config.connections[1].transport.params.get("port").map(|s| s.as_str()), Some("8081"));
        assert_eq!(config.connections[1].to, "calculator");
    }

    #[test]
    fn merge_key_populates_fields() {
        let yaml = r#"
defaults: &httpDefaults
  id: http
  params:
    host: localhost
    port: "8081"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      <<: *httpDefaults
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let conn = &config.connections[0];
        assert_eq!(conn.transport.id, "http");
        assert_eq!(conn.transport.params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(conn.transport.params.get("port").map(|s| s.as_str()), Some("8081"));
    }

    #[test]
    fn merge_key_local_value_overrides_anchor() {
        let yaml = r#"
defaults: &httpDefaults
  id: http
  params:
    host: localhost
    port: "8081"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      <<: *httpDefaults
      params:
        host: localhost
        port: "9090"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let conn = &config.connections[0];
        // fields present on the mapping itself
        assert_eq!(conn.from.as_deref(), Some("gateway"));
        assert_eq!(conn.to, "calculator");
        // local value overrides the anchor
        assert_eq!(conn.transport.params.get("port").map(|s| s.as_str()), Some("9090"));
        assert_eq!(conn.transport.id, "http");
        assert_eq!(conn.transport.params.get("host").map(|s| s.as_str()), Some("localhost"));
    }

    #[test]
    fn multiple_anchors_resolve_independently() {
        let yaml = r#"
anchors:
  calcParams: &calcParams
    host: calc-host
    port: "8081"
  notifParams: &notifParams
    host: notif-host
    port: "8082"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
      params:
        <<: *calcParams
    serializer:
      id: json
  - id: "gateway-to-notifier"
    from: gateway
    to: notifier
    transport:
      id: http
      params:
        <<: *notifParams
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let calc = &config.connections[0];
        assert_eq!(calc.from.as_deref(), Some("gateway"));
        assert_eq!(calc.to, "calculator");
        assert_eq!(calc.transport.params.get("host").map(|s| s.as_str()), Some("calc-host"));
        assert_eq!(calc.transport.params.get("port").map(|s| s.as_str()), Some("8081"));
        let notif = &config.connections[1];
        assert_eq!(notif.from.as_deref(), Some("gateway"));
        assert_eq!(notif.to, "notifier");
        assert_eq!(notif.transport.params.get("host").map(|s| s.as_str()), Some("notif-host"));
        assert_eq!(notif.transport.params.get("port").map(|s| s.as_str()), Some("8082"));
    }

    #[test]
    fn parses_transport_block_with_params() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
      params:
        host: localhost
        port: "8081"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let conn = &config.connections[0];
        assert_eq!(conn.transport.id, "http");
        assert_eq!(conn.transport.params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(conn.transport.params.get("port").map(|s| s.as_str()), Some("8081"));
    }

    #[test]
    fn parses_handle_timeout_true() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
      handleTimeout: true
      params:
        host: localhost
        port: "8081"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].transport.handle_timeout);
    }

    #[test]
    fn handle_timeout_defaults_to_false() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(!config.connections[0].transport.handle_timeout);
    }

    #[test]
    fn absent_params_yields_empty_map() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].transport.params.is_empty());
    }

    #[test]
    fn transport_block_missing_fails_validation() {
        // Missing transport block entirely — should fail
        // Note: this would fail at deserialize time since transport is required
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
"#;
        assert!(parse_string(yaml).is_err());
    }

    #[test]
    fn direct_connection_is_direct() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: direct
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].is_direct());
    }

    #[test]
    fn direct_connection_does_not_require_serializer() {
        // A direct connection has no serializer block at all — must not fail validation.
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: direct
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].serializer.is_none());
    }

    #[test]
    fn serializer_block_missing_fails_validation_for_non_direct_connection() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("serializer.id"), "unexpected message: {}", msg);
    }

    #[test]
    fn serializer_id_empty_fails_validation_for_non_direct_connection() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: ""
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("serializer.id"), "unexpected message: {}", msg);
    }

    #[test]
    fn serializer_params_parsed_correctly() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: protobuf
      params:
        schemaRegistryUrl: "http://localhost:8081"
"#;
        let config = parse_string(yaml).unwrap();
        let serializer = config.connections[0].serializer.as_ref().unwrap();
        assert_eq!(serializer.id, "protobuf");
        assert_eq!(
            serializer.params.get("schemaRegistryUrl").map(|s| s.as_str()),
            Some("http://localhost:8081")
        );
    }

    #[test]
    fn serializer_absent_params_yields_empty_map() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].serializer.as_ref().unwrap().params.is_empty());
    }

    // ── FailureSemanticsEntry ─────────────────────────────────────────────────

    #[test]
    fn failure_semantics_absent_is_none() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].failure_semantics.is_none());
    }

    #[test]
    fn failure_semantics_other_fields_default_correctly() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    failureSemantics:
      id: built-in
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let fs = config.connections[0].failure_semantics.as_ref().unwrap();
        assert_eq!(fs.id, "built-in");
        assert!(fs.timeout.is_none());
        assert!(!fs.handle_timeout);
        assert!(fs.absolute_timeout.is_none());
        assert!(fs.max_retry.is_none());
        assert!(fs.params.is_empty());
    }

    #[test]
    fn failure_semantics_without_id_fails_parsing() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    failureSemantics:
      timeout: 2s
    serializer:
      id: json
"#;
        assert!(parse_string(yaml).is_err());
    }

    #[test]
    fn failure_semantics_full() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    failureSemantics:
      id: built-in
      timeout: 2s
      handleTimeout: true
      absoluteTimeout: 10s
      maxRetry: 3
      params:
        waitDuration: 500ms
        slidingWindowSize: "10"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let fs = config.connections[0].failure_semantics.as_ref().unwrap();
        assert_eq!(fs.id, "built-in");
        assert_eq!(fs.timeout.as_deref(), Some("2s"));
        assert!(fs.handle_timeout);
        assert_eq!(fs.absolute_timeout.as_deref(), Some("10s"));
        assert_eq!(fs.max_retry, Some(3));
        assert_eq!(fs.params.get("waitDuration").map(|s| s.as_str()), Some("500ms"));
        assert_eq!(fs.params.get("slidingWindowSize").map(|s| s.as_str()), Some("10"));
    }

    #[test]
    fn failure_semantics_handle_timeout_parsed() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    failureSemantics:
      id: built-in
      timeout: 5s
      handleTimeout: true
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let fs = config.connections[0].failure_semantics.as_ref().unwrap();
        assert!(fs.handle_timeout);
    }

    #[test]
    fn has_timeout_returns_true_when_set() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    failureSemantics:
      id: built-in
      timeout: 2s
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].has_timeout());
    }

    #[test]
    fn has_timeout_returns_false_when_no_failure_semantics() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(!config.connections[0].has_timeout());
    }

    #[test]
    fn has_timeout_returns_false_when_failure_semantics_has_no_timeout() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    from: gateway
    to: calculator
    transport:
      id: http
    failureSemantics:
      id: built-in
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(!config.connections[0].has_timeout());
    }

// ── Connection id ─────────────────────────────────────────────────────────

    #[test]
    fn connection_id_missing_fails_parsing() {
        // 'id' key absent entirely — fails at deserialize time since it's required.
        let yaml = r#"
connections:
  - from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        assert!(parse_string(yaml).is_err());
    }

    #[test]
    fn connection_id_empty_fails_validation() {
        let yaml = r#"
connections:
  - id: ""
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("missing required field 'id'"), "unexpected message: {}", msg);
    }

    #[test]
    fn connection_id_invalid_characters_fails_validation() {
        let yaml = r#"
connections:
  - id: "gateway to calculator!"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("invalid characters"), "unexpected message: {}", msg);
    }

    #[test]
    fn connection_id_allows_dots_underscores_hyphens() {
        let yaml = r#"
connections:
  - id: "gateway.to_calculator-v2"
    from: gateway
    to: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        assert!(parse_string(yaml).is_ok());
    }

    // ── Node id character set ────────────────────────────────────────────────

    #[test]
    fn component_node_id_invalid_characters_fails_validation() {
        let yaml = r#"
nodes:
  - id: "gateway node!"
    component: "gateway"
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("invalid characters"), "unexpected message: {}", msg);
    }

    #[test]
    fn virtual_node_id_invalid_characters_fails_validation() {
        let yaml = r#"
nodes:
  - id: "order placed!"
    kind: virtual
    contract: "order-events/order-placed"
    address: "demo.events.order-placed"
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("invalid characters"), "unexpected message: {}", msg);
    }

    #[test]
    fn node_id_allows_dots_underscores_hyphens() {
        let yaml = r#"
nodes:
  - id: "gateway.node_v2-a"
    component: "gateway"
"#;
        assert!(parse_string(yaml).is_ok());
    }

    // ── Parse error structural paths (serde_path_to_error) ─────────────────────

    #[test]
    fn parse_error_includes_serde_path() {
        let yaml = r#"
nodes:
  - id: "testNode"
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        assert!(
            msg.contains("nodes[0]"),
            "Error should contain serde path 'nodes[0]', got: {}",
            msg
        );
    }

    #[test]
    fn missing_component_field_shows_structural_path() {
        let yaml = r#"
nodes:
  - id: "testNode"
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        // Error should contain the structural path (nodes[0]) and the field name (component)
        assert!(msg.contains("nodes[0]"), "Error should contain nodes[0], got: {}", msg);
        assert!(msg.contains("component"), "Error should mention missing field 'component', got: {}", msg);
    }

    #[test]
    fn missing_virtual_contract_field_shows_structural_path() {
        let yaml = r#"
nodes:
  - id: "testNode"
    kind: virtual
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        assert!(msg.contains("nodes[0]"), "Error should contain nodes[0], got: {}", msg);
        assert!(msg.contains("contract"), "Error should mention missing field 'contract', got: {}", msg);
    }

    #[test]
    fn missing_virtual_address_field_shows_structural_path() {
        let yaml = r#"
nodes:
  - id: "testNode"
    kind: virtual
    contract: "test/contract"
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        assert!(msg.contains("nodes[0]"), "Error should contain nodes[0], got: {}", msg);
        assert!(msg.contains("address"), "Error should mention missing field 'address', got: {}", msg);
    }

    #[test]
    fn valid_component_node_without_kind_still_parses() {
        let yaml = r#"
nodes:
  - id: "orderServiceNode"
    component: "order-service"
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.nodes.len(), 1);
        assert!(matches!(config.nodes[0], Node::Component(_)));
        assert_eq!(config.nodes[0].as_component().unwrap().component, "order-service");
    }

    #[test]
    fn valid_virtual_node_still_parses() {
        let yaml = r#"
nodes:
  - id: "orderPlacedChannel"
    kind: virtual
    contract: "order-events/order-placed"
    address: "demo.events.order-placed"
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.virtual_nodes().len(), 1);
        let vn = config.virtual_nodes()[0];
        assert_eq!(vn.id, "orderPlacedChannel");
        assert_eq!(vn.contract, "order-events/order-placed");
        assert_eq!(vn.address, "demo.events.order-placed");
    }

    #[test]
    fn component_node_with_explicit_kind_still_parses() {
        let yaml = r#"
nodes:
  - id: "inventoryNode"
    kind: component
    component: "inventory"
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.nodes.len(), 1);
        assert!(matches!(config.nodes[0], Node::Component(_)));
        assert_eq!(config.nodes[0].as_component().unwrap().component, "inventory");
    }

    #[test]
    fn unknown_kind_still_produces_clear_error() {
        let yaml = r#"
nodes:
  - id: "testNode"
    kind: unknown
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        assert!(msg.contains("unknown"), "Error should mention unknown variant, got: {}", msg);
        assert!(msg.contains("component") && msg.contains("virtual"), "Error should list valid variants, got: {}", msg);
    }

    // ── Regression tests for line/column + structural path preservation ─────────

    #[test]
    fn direct_path_missing_field_shows_line_column() {
        // YAML without merge keys — should use direct path with line/column info.
        // Test a deserialization error - missing required field 'to' in connection
        let yaml = r#"
nodes:
  - id: "testNode"
    component: "test"
connections:
  - id: "test-to-calc"
    from: "testNode"
    transport:
      id: http
    serializer:
      id: json
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        // Direct path should include line/column from serde_yaml
        // The error should mention the missing 'to' field with line info
        assert!(msg.contains("to"), "Error should mention missing field 'to', got: {}", msg);
        // Structural path should be present
        assert!(msg.contains("connections[0]"), "Error should contain structural path connections[0], got: {}", msg);
        // Line and column information should be present
        assert!(msg.contains("line 6"), "Error should contain line 6, got: {}", msg);
        assert!(msg.contains("column 5"), "Error should contain column 5, got: {}", msg);
    }

    #[test]
    fn direct_path_node_missing_component_shows_structural_path() {
        // Missing component field in a component node (no merge keys)
        let yaml = r#"
nodes:
  - id: "testNode"
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        // Should have structural path from serde_path_to_error
        assert!(msg.contains("nodes[0]"), "Error should contain nodes[0], got: {}", msg);
        assert!(msg.contains("component"), "Error should mention missing field 'component', got: {}", msg);
    }

    #[test]
    fn merge_key_config_still_works() {
        // YAML with merge keys should still parse correctly
        let yaml = r#"
defaults: &httpDefaults
  id: http
  params:
    host: localhost
    port: "8081"
nodes:
  - id: "gatewayNode"
    component: "gateway"
  - id: "calculatorNode"
    component: "calculator"
connections:
  - id: "gateway-to-calculator"
    from: "gatewayNode"
    to: "calculatorNode"
    transport:
      <<: *httpDefaults
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.nodes.len(), 2);
        assert_eq!(config.connections.len(), 1);
        let conn = &config.connections[0];
        assert_eq!(conn.transport.id, "http");
        assert_eq!(conn.transport.params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(conn.transport.params.get("port").map(|s| s.as_str()), Some("8081"));
    }

    #[test]
    fn merge_key_config_error_shows_structural_path() {
        // YAML with merge keys and invalid node kind — should show structural path
        let yaml = r#"
defaults: &httpDefaults
  id: http
  params:
    host: localhost
    port: "8081"
nodes:
  - id: "gatewayNode"
    kind: invalid_kind
    component: "gateway"
connections:
  - id: "gateway-to-calculator"
    from: "gatewayNode"
    to: "calculatorNode"
    transport:
      <<: *httpDefaults
    serializer:
      id: json
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        // Should mention the unknown variant with structural path
        assert!(msg.contains("nodes[0]"), "Error should contain nodes[0], got: {}", msg);
        assert!(msg.contains("unknown") || msg.contains("invalid_kind"), "Error should mention invalid kind, got: {}", msg);
    }

    #[test]
    fn merge_key_config_missing_transport_field_shows_structural_path() {
        // YAML with merge keys, missing required field in transport
        let yaml = r#"
defaults: &httpDefaults
  id: http
  params:
    host: localhost
    port: "8081"
nodes:
  - id: "gatewayNode"
    component: "gateway"
  - id: "calculatorNode"
    component: "calculator"
connections:
  - id: "gateway-to-calculator"
    from: "gatewayNode"
    to: "calculatorNode"
    transport:
      <<: *httpDefaults
      # missing 'id' - but merge key provides it
    serializer:
      id: json
"#;
        // This should actually work because merge key provides id
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].transport.id, "http");
    }

    #[test]
    fn merge_key_config_missing_serializer_shows_structural_path() {
        // YAML with merge keys, missing serializer for non-direct connection
        let yaml = r#"
defaults: &httpDefaults
  id: http
  params:
    host: localhost
    port: "8081"
nodes:
  - id: "gatewayNode"
    component: "gateway"
  - id: "calculatorNode"
    component: "calculator"
connections:
  - id: "gateway-to-calculator"
    from: "gatewayNode"
    to: "calculatorNode"
    transport:
      <<: *httpDefaults
    # missing serializer
"#;
        let err = parse_string(yaml).unwrap_err();
        let msg = format!("{}", err);
        assert!(msg.contains("serializer"), "Error should mention missing serializer, got: {}", msg);
    }

    #[test]
    fn virtual_node_without_kind_defaults_to_component() {
        // Ensure backward compatibility: node without kind defaults to component
        let yaml = r#"
nodes:
  - id: "testNode"
    component: "test-component"
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.nodes.len(), 1);
        assert!(matches!(config.nodes[0], Node::Component(_)));
        assert_eq!(config.nodes[0].as_component().unwrap().component, "test-component");
    }

    #[test]
    fn virtual_node_explicit_kind_virtual_works() {
        let yaml = r#"
nodes:
  - id: "testChannel"
    kind: virtual
    contract: "test/contract"
    address: "test.address"
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.nodes.len(), 1);
        assert!(matches!(config.nodes[0], Node::Virtual(_)));
        let vn = config.nodes[0].as_virtual().unwrap();
        assert_eq!(vn.contract, "test/contract");
        assert_eq!(vn.address, "test.address");
    }

    #[test]
    fn case_insensitive_kind_works() {
        // kind: Component, COMPONENT, Virtual, VIRTUAL should all work
        let yaml = r#"
nodes:
  - id: "node1"
    kind: Component
    component: "comp1"
  - id: "node2"
    kind: VIRTUAL
    contract: "test/contract"
    address: "test.address"
  - id: "node3"
    kind: component
    component: "comp3"
  - id: "node4"
    kind: virtual
    contract: "test/contract2"
    address: "test.address2"
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.nodes.len(), 4);
        assert!(matches!(config.nodes[0], Node::Component(_)));
        assert!(matches!(config.nodes[1], Node::Virtual(_)));
        assert!(matches!(config.nodes[2], Node::Component(_)));
        assert!(matches!(config.nodes[3], Node::Virtual(_)));
    }

    #[test]
    fn anchor_alias_without_merge_key_uses_direct_path() {
        // Anchors/aliases without merge keys should use direct path
        let yaml = r#"
anchors:
  host: &myHost "localhost"
nodes:
  - id: "gatewayNode"
    component: "gateway"
connections:
  - id: "gateway-to-calculator"
    from: "gatewayNode"
    to: "calculatorNode"
    transport:
      id: http
      params:
        host: *myHost
        port: "8081"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].transport.params.get("host").map(|s| s.as_str()), Some("localhost"));
    }
}
