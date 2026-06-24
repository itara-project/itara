
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

/// Private helper for deserialising a Node from YAML.
///
/// Collects all possible fields across all node types as Options.
/// `kind` defaults to "component" when absent — backwards compatibility
/// with existing wiring configs that have no `kind` field.
///
/// The custom Deserialize impl on Node uses this to dispatch to the
/// correct variant and validate that variant-specific required fields
/// are present, producing clear error messages.
#[derive(Deserialize)]
struct NodeHelper {
    id: String,
    #[serde(default = "default_node_kind")]
    kind: String,
    // ComponentNode fields
    component: Option<String>,
    // VirtualNode fields
    contract: Option<String>,
    address: Option<String>,
}

fn default_node_kind() -> String {
    "component".to_string()
}

impl<'de> serde::Deserialize<'de> for Node {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        use serde::de::Error;

        let h = NodeHelper::deserialize(d)?;

        match h.kind.to_lowercase().as_str() {
            "component" => {
                let component = h.component.ok_or_else(|| {
                    D::Error::custom(format!(
                        "component node '{}' is missing required field 'component'",
                        h.id
                    ))
                })?;
                Ok(Node::Component(ComponentNode {
                    id: h.id,
                    component,
                }))
            }
            "virtual" => {
                let contract = h.contract.ok_or_else(|| {
                    D::Error::custom(format!(
                        "virtual node '{}' is missing required field 'contract'",
                        h.id
                    ))
                })?;
                let address = h.address.ok_or_else(|| {
                    D::Error::custom(format!(
                        "virtual node '{}' is missing required field 'address'",
                        h.id
                    ))
                })?;
                Ok(Node::Virtual(VirtualNode {
                    id: h.id,
                    contract,
                    address,
                }))
            }
            other => Err(D::Error::unknown_variant(
                other,
                &["component", "virtual"],
            )),
        }
    }
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
///     - from: "gatewayNode"
///       to: "calculatorNode"
///       type: http
///       host: "${CALC_HOST:-localhost}"
///       port: 8081
///       serializer: json
#[derive(Debug, Clone, Deserialize)]
pub struct ConnectionEntry {
    /// The calling node id. None or empty = external caller.
    #[serde(default)]
    pub from: Option<String>,

    /// The called node id. Required.
    pub to: String,

    /// The connection type. Required.
    /// Supported values depend on which transport libs are in the lib dir.
    #[serde(rename = "type")]
    pub transport_type: String,

    /// The hostname of the remote node.
    /// Required for non-direct connections where this node is the caller.
    #[serde(default)]
    pub host: Option<String>,

    /// The port of the remote node.
    /// Required for non-direct connections.
    #[serde(default)]
    pub port: Option<u16>,

    /// The serializer type for this connection. Defaults to "json".
    #[serde(default = "default_serializer")]
    pub serializer: String,
}

fn default_serializer() -> String {
    "json".to_string()
}

impl ConnectionEntry {
    /// Returns true if the caller is external to the Itara topology.
    pub fn is_external(&self) -> bool {
        self.from.as_deref().map(|s| s.trim().is_empty()).unwrap_or(true)
    }

    /// Returns true if this is a direct (in-process) connection.
    pub fn is_direct(&self) -> bool {
        self.transport_type.eq_ignore_ascii_case("direct")
    }

    /// Returns true if this is an HTTP connection.
    pub fn is_http(&self) -> bool {
        self.transport_type.eq_ignore_ascii_case("http")
    }

    /// Returns true if this is a kafka connection.
    pub fn is_kafka(&self) -> bool {
        self.transport_type.eq_ignore_ascii_case("kafka")
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

    pub fn validate(&self) -> Result<(), ConfigError> {
        if self.to.trim().is_empty() {
            return Err(ConfigError::Invalid(
                "Connection is missing required field 'to'.".to_string(),
            ));
        }
        if self.transport_type.trim().is_empty() {
            return Err(ConfigError::Invalid(
                format!("Connection to='{}' is missing required field 'type'.", self.to),
            ));
        }
        if !self.is_direct() && !self.is_kafka() {
            if self.port.is_none() || self.port == Some(0) {
                return Err(ConfigError::Invalid(format!(
                    "Connection to='{}' of type '{}' is missing required field 'port'.",
                    self.to, self.transport_type
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

    let config: WiringConfig = serde_yaml::from_value(resolved).map_err(|e| {
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
  - from: "gatewayNode"
    to: "calculatorNode"
    type: http
    host: "calculator"
    port: 8081
    serializer: "json"

  - from:
    to: "gatewayNode"
    type: http
    port: 8082
    serializer: "json"
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
        assert!(conn.is_http());
        assert_eq!(conn.host.as_deref(), Some("calculator"));
        assert_eq!(conn.port, Some(8081));
        assert_eq!(conn.serializer, "json");
    }

    #[test]
    fn external_connection_has_no_from() {
        let config = parse_string(HTTP_CONFIG).unwrap();
        let external = &config.connections[1];
        assert!(external.is_external());
        assert_eq!(external.to, "gatewayNode");
        assert_eq!(external.port, Some(8082));
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
  - from: "orderServiceNode"
    to: "orderPlacedChannel"
    type: kafka
    serializer: "json"
  - from: "orderPlacedChannel"
    to: "orderServiceNode"
    type: kafka
    serializer: "json"
    consumerGroup: "order-consumer-group"
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
  - from: "a"
    to: "b"
    type: kafka
    serializer: "json"
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
  - from: gateway
    to: calculator
    type: http
    host: *calcHost
    port: 8081
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].host.as_deref(), Some("localhost"));
    }

    #[test]
    fn mapping_alias_resolves_correctly() {
        let yaml = r#"
connections:
  - &baseConn
    from: gateway
    to: calculator
    type: http
    host: localhost
    port: 8081
  - *baseConn
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections.len(), 2);
        assert_eq!(config.connections[1].host.as_deref(), Some("localhost"));
        assert_eq!(config.connections[1].port, Some(8081));
        assert_eq!(config.connections[1].to, "calculator");
    }

    #[test]
    fn merge_key_populates_fields() {
        let yaml = r#"
defaults: &httpDefaults
  type: http
  host: localhost
  port: 8081
connections:
  - from: gateway
    to: calculator
    <<: *httpDefaults
"#;
        let config = parse_string(yaml).unwrap();
        let conn = &config.connections[0];
        // fields present on the mapping itself
        assert_eq!(conn.from.as_deref(), Some("gateway"));
        assert_eq!(conn.to, "calculator");
        // fields populated from the anchor
        assert!(conn.is_http());
        assert_eq!(conn.host.as_deref(), Some("localhost"));
        assert_eq!(conn.port, Some(8081));
    }

    #[test]
    fn merge_key_local_value_overrides_anchor() {
        let yaml = r#"
defaults: &httpDefaults
  type: http
  host: localhost
  port: 8081
connections:
  - from: gateway
    to: calculator
    <<: *httpDefaults
    port: 9090
"#;
        let config = parse_string(yaml).unwrap();
        let conn = &config.connections[0];
        // fields present on the mapping itself
        assert_eq!(conn.from.as_deref(), Some("gateway"));
        assert_eq!(conn.to, "calculator");
        // local value overrides the anchor
        assert_eq!(conn.port, Some(9090));
        // remaining fields still come from the anchor
        assert!(conn.is_http());
        assert_eq!(conn.host.as_deref(), Some("localhost"));
    }

    #[test]
    fn multiple_anchors_resolve_independently() {
        let yaml = r#"
anchors:
  calc: &calcDefaults
    host: calc-host
    port: 8081
  notif: &notifDefaults
    host: notif-host
    port: 8082
connections:
  - from: gateway
    to: calculator
    type: http
    <<: *calcDefaults
  - from: gateway
    to: notifier
    type: http
    <<: *notifDefaults
"#;
        let config = parse_string(yaml).unwrap();
        let calc = &config.connections[0];
        assert_eq!(calc.from.as_deref(), Some("gateway"));
        assert_eq!(calc.to, "calculator");
        assert_eq!(calc.host.as_deref(), Some("calc-host"));
        assert_eq!(calc.port, Some(8081));
        let notif = &config.connections[1];
        assert_eq!(notif.from.as_deref(), Some("gateway"));
        assert_eq!(notif.to, "notifier");
        assert_eq!(notif.host.as_deref(), Some("notif-host"));
        assert_eq!(notif.port, Some(8082));
    }
}
