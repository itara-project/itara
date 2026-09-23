
use std::env;
use std::fs;
use regex::Regex;
use serde::Deserialize;
use yaml_merge_keys::merge_keys_serde;

/// Recursively checks if a serde_yaml::Value contains any merge keys (<<).
fn contains_merge_keys(value: &serde_yaml::Value) -> bool {
    match value {
        serde_yaml::Value::Mapping(map) => {
            for (key, val) in map {
                if key.as_str() == Some("<<") {
                    return true;
                }
                if contains_merge_keys(val) {
                    return true;
                }
            }
            false
        }
        serde_yaml::Value::Sequence(seq) => seq.iter().any(contains_merge_keys),
        _ => false,
    }
}

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
        // Also normalize kind to lowercase for case-insensitive matching
        if let serde_yaml::Value::Mapping(ref mut map) = value {
            if let Some(kind_val) = map.get_mut(&serde_yaml::Value::String("kind".to_string())) {
                if let serde_yaml::Value::String(kind_str) = kind_val {
                    *kind_str = kind_str.to_lowercase();
                }
            } else {
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

/// The authentication block of a connection entry in the wiring configuration.
///
/// Mirrors SerializerEntry's shape exactly.
///
/// Example YAML:
///   authentication:
///     id: shared-secret
///     params:
///       secret: "${GATEWAY_SECRET}"
///
/// Absent means the noop implementation is used (spec §15.1).
#[derive(Debug, Clone, Deserialize)]
pub struct AuthenticationEntry {
    /// The authentication type identifier. Required.
    pub id: String,

    /// Implementation-specific parameters.
    #[serde(default, deserialize_with = "deserialize_params")]
    pub params: std::collections::HashMap<String, String>,
}

/// The authorization block of a connection entry in the wiring configuration.
///
/// Mirrors AuthenticationEntry's shape exactly.
///
/// Example YAML:
///   authorization:
///     id: rule-table
///     params:
///       allow: "shout"
///       deny: "whisper"
///
/// Absent means the noop implementation is used (spec §16.1).
#[derive(Debug, Clone, Deserialize)]
pub struct AuthorizationEntry {
    /// The authorization type identifier. Required.
    pub id: String,

    /// Implementation-specific parameters.
    #[serde(default, deserialize_with = "deserialize_params")]
    pub params: std::collections::HashMap<String, String>,
}

/// One side of a connection declaration: the `caller` or `callee` block of
/// a `ConnectionEntry`.
///
/// Example YAML:
///   callee:
///     nodeId: "calculatorNode"
///     transport:
///       id: hardened-http
///       params:
///         host: "${CALC_HOST:-localhost}"
///         port: "8081"
///     authorization:
///       id: rule-table
///   caller:
///     nodeId: "gatewayNode"
///     transport:
///       id: http
///     failureSemantics:
///       id: built-in
///       maxRetry: 3
///
/// Both sides deliberately share this one struct, and it carries every
/// plugin block — including ones that are only legal on the other side
/// (failureSemantics is caller-side only, authorization is callee-side
/// only). This struct does not enforce that. It exists so that an illegal
/// placement, such as `authorization` under `caller`, still parses into a
/// real value that `ConnectionEntry::validate()` can reject with a clear
/// error, rather than being silently dropped as an unknown field.
///
/// A plugin block declared here replaces the connection-level block of
/// the same kind for this side entirely — block-level replacement, never
/// field-level merging. This struct only holds what was declared; the
/// fallback to the connection level happens in
/// `ConnectionEntry::resolve_caller()` / `resolve_callee()`.
#[derive(Debug, Clone, Deserialize)]
pub struct ConnectionSide {
    /// The id of the node on this side of the connection. Mandatory on
    /// the callee, and mandatory on the caller whenever a caller block is
    /// present at all — an absent caller block, not a blank nodeId, is
    /// how an external connection is expressed. Presence of the *key* is
    /// enforced at deserialize time (no `#[serde(default)]` here, mirroring
    /// `ConnectionHelper::to`); an *empty* value is caught by
    /// `ConnectionEntry::validate()`.
    #[serde(rename = "nodeId")]
    pub node_id: String,

    /// Side-specific transport; when absent, the connection-level one applies.
    #[serde(default)]
    pub transport: Option<TransportEntry>,

    /// Side-specific serializer; when absent, the connection-level one applies.
    #[serde(default)]
    pub serializer: Option<SerializerEntry>,

    /// Failure semantics. Legal on the caller side only. Kept on this
    /// shared struct solely so an illegal callee-side declaration can be
    /// rejected by `ConnectionEntry::validate()` instead of silently
    /// dropped.
    #[serde(default, rename = "failureSemantics")]
    pub failure_semantics: Option<FailureSemanticsEntry>,

    /// Side-specific authentication; when absent, the connection-level one applies.
    #[serde(default)]
    pub authentication: Option<AuthenticationEntry>,

    /// Authorization. Legal on the callee side only. Kept on this shared
    /// struct solely so an illegal caller-side declaration can be
    /// rejected by `ConnectionEntry::validate()` instead of silently
    /// dropped.
    #[serde(default)]
    pub authorization: Option<AuthorizationEntry>,
}

/// The caller side of a connection after resolution: for each plugin
/// kind, the caller block's own declaration if it has one, otherwise the
/// connection-level one. Built on demand by `ConnectionEntry::resolve_caller()`
/// — not cached, since nothing here is a hot path (the agent resolves once
/// per connection at startup; `itara-cli` resolves once per connection per
/// check run).
///
/// Borrows from the `ConnectionEntry` it was built from, so it cannot
/// outlive it.
///
/// Has no `authorization` field, deliberately: authorization is
/// callee-side only, so nothing on the caller side can ever read one —
/// see `ResolvedCallee`. The mirror holds for failure semantics, which
/// exist only here.
#[derive(Debug, Clone, Copy)]
pub struct ResolvedCaller<'a> {
    pub node_id: &'a str,
    pub transport: &'a TransportEntry,
    pub serializer: Option<&'a SerializerEntry>,
    pub failure_semantics: Option<&'a FailureSemanticsEntry>,
    pub authentication: Option<&'a AuthenticationEntry>,
}

impl<'a> ResolvedCaller<'a> {
    /// Returns the failure semantics type id for this caller.
    /// Defaults to "noop" if no failureSemantics block is declared.
    pub fn failure_semantics_id(&self) -> &str {
        self.failure_semantics.map(|f| f.id.as_str()).unwrap_or("noop")
    }

    /// Returns the authentication type id for this caller.
    /// Defaults to "noop" if no authentication block is declared.
    pub fn authentication_id(&self) -> &str {
        self.authentication.map(|a| a.id.as_str()).unwrap_or("noop")
    }

    /// Returns whether this caller's resolved transport is the direct
    /// (colocated, in-process) one.
    pub fn is_direct(&self) -> bool {
        self.transport.id.eq_ignore_ascii_case("direct")
    }
}

/// The callee side of a connection after resolution: for each plugin
/// kind, the callee block's own declaration if it has one, otherwise the
/// connection-level one. Built on demand by `ConnectionEntry::resolve_callee()`.
/// Every connection has a callee, external ones included.
///
/// Has no `failure_semantics` field, deliberately: retry/timeout is
/// exclusively the caller's concern, so nothing on the callee side can
/// ever read one — see `ResolvedCaller`. The mirror holds for
/// authorization, which exists only here.
#[derive(Debug, Clone, Copy)]
pub struct ResolvedCallee<'a> {
    pub node_id: &'a str,
    pub transport: &'a TransportEntry,
    pub serializer: Option<&'a SerializerEntry>,
    pub authentication: Option<&'a AuthenticationEntry>,
    pub authorization: Option<&'a AuthorizationEntry>,
}

impl<'a> ResolvedCallee<'a> {
    /// Returns the authentication type id for this callee.
    /// Defaults to "noop" if no authentication block is declared.
    pub fn authentication_id(&self) -> &str {
        self.authentication.map(|a| a.id.as_str()).unwrap_or("noop")
    }

    /// Returns the authorization type id for this callee.
    /// Defaults to "noop" if no authorization block is declared.
    pub fn authorization_id(&self) -> &str {
        self.authorization.map(|a| a.id.as_str()).unwrap_or("noop")
    }

    /// Returns whether this callee's resolved transport is the direct
    /// (colocated, in-process) one.
    pub fn is_direct(&self) -> bool {
        self.transport.id.eq_ignore_ascii_case("direct")
    }
}

///
/// Defines how one node calls another. A connection has a mandatory
/// `callee` block and an optional `caller` block, each a `ConnectionSide`;
/// plugin blocks may be declared on the connection itself (shared by both
/// sides) or inside either side block.
///
/// Example YAML:
///   connections:
///     - id: "gateway-to-calculator"
///       serializer:
///         id: json
///       callee:
///         nodeId: "calculatorNode"
///         transport:
///           id: hardened-http
///           params:
///             host: "${CALC_HOST:-localhost}"
///             port: "8081"
///         authorization:
///           id: rule-table
///       caller:
///         nodeId: "gatewayNode"
///         transport:
///           id: http
///         failureSemantics:
///           id: built-in
///           maxRetry: 3
///     - id: "external-to-gateway"
///       callee:
///         nodeId: "gatewayNode"
///         transport:
///           id: http
///           params:
///             port: "8082"
///         serializer:
///           id: json
///       # no caller block - external caller
///
/// External connections: a connection with no `caller` block has no
/// Itara-managed caller process. Absence of the whole block is the only
/// way to express this; a `caller` block that is present must be complete
/// and valid. An external connection has nowhere to declare failure
/// semantics.
///
/// Where each plugin kind may be declared (anything else is rejected by
/// `validate()`, never silently ignored):
///   transport          connection-level, caller-side, callee-side
///   serializer         connection-level, caller-side, callee-side
///   failureSemantics   caller-side only
///   authentication     connection-level, caller-side, callee-side
///   authorization      callee-side only
///
/// Resolution: each side resolves each plugin kind independently — the
/// side's own block if it declares one, otherwise the connection-level
/// block. A side block replaces the connection-level block of the same
/// kind entirely — block-level replacement, never field-level merging.
/// See `resolve_caller()` and `resolve_callee()`.
#[derive(Debug, Clone)]
pub struct ConnectionEntry {
    /// Unique identifier for this connection. Required — MUST be unique
    /// across the entire wiring configuration (spec §4.4).
    pub id: String,

    /// The called side. Mandatory — every connection has a target.
    pub callee: ConnectionSide,

    /// The calling side. Absent means the caller is external to the
    /// Itara topology.
    pub caller: Option<ConnectionSide>,

    /// Connection-level transport, shared by both sides unless a side
    /// declares its own. Every existing side must end up with a
    /// transport, from one place or the other.
    pub transport: Option<TransportEntry>,

    /// Connection-level serializer, shared by both sides unless a side
    /// declares its own. Required for every existing side except direct
    /// (colocated) connections.
    pub serializer: Option<SerializerEntry>,

    /// Connection-level authentication, shared by both sides unless a
    /// side declares its own. Absent on every level means the noop
    /// implementation is used (§15.1).
    pub authentication: Option<AuthenticationEntry>,

    /// NOT a legal placement. Failure semantics are caller-side only;
    /// captured only so validate() can reject a connection-level
    /// declaration instead of silently dropping it as an unknown field.
    /// No public accessor: nothing may read failure semantics from this
    /// level.
    failure_semantics: Option<FailureSemanticsEntry>,

    /// NOT a legal placement. Authorization is callee-side only;
    /// captured only so validate() can reject a connection-level
    /// declaration instead of silently dropping it as an unknown field.
    /// No public accessor: nothing may read authorization from this
    /// level.
    authorization: Option<AuthorizationEntry>,

    /// The retired top-level 'from' field. Captured only so validate()
    /// can reject an old-format config with a migration hint.
    legacy_from: Option<String>,

    /// The retired top-level 'to' field. Captured only so validate()
    /// can reject an old-format config with a migration hint.
    legacy_to: Option<String>,
}

#[derive(Deserialize)]
struct ConnectionHelper {
    id: String,
    #[serde(default)]
    from: Option<String>,
    #[serde(default)]
    to: Option<String>,
    callee: ConnectionSide,
    #[serde(default)]
    caller: Option<ConnectionSide>,
    #[serde(default)]
    transport: Option<TransportEntry>,
    #[serde(default)]
    serializer: Option<SerializerEntry>,
    #[serde(default, rename = "failureSemantics")]
    failure_semantics: Option<FailureSemanticsEntry>,
    #[serde(default)]
    authentication: Option<AuthenticationEntry>,
    #[serde(default)]
    authorization: Option<AuthorizationEntry>,
}

impl<'de> serde::Deserialize<'de> for ConnectionEntry {
    fn deserialize<D: serde::Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        let h = ConnectionHelper::deserialize(d)?;
        Ok(ConnectionEntry {
            id:                 h.id,
            callee:             h.callee,
            caller:             h.caller,
            transport:          h.transport,
            serializer:         h.serializer,
            authentication:     h.authentication,
            failure_semantics:  h.failure_semantics,
            authorization:      h.authorization,
            legacy_from:        h.from,
            legacy_to:          h.to,
        })
    }
}

/// The entire resolution rule for one plugin kind: the side's own block
/// wins whole, and the connection-level block is only ever a fallback
/// for when the side declares none. No merging of any kind.
fn pick<'a, T>(side: Option<&'a T>, connection: Option<&'a T>) -> Option<&'a T> {
    side.or(connection)
}

impl ConnectionEntry {
    /// Returns the calling node's id, or None if this is an external connection.
    pub fn caller_node_id(&self) -> Option<&str> {
        self.caller.as_ref().map(|c| c.node_id.as_str())
    }

    /// Returns the called node's id.
    pub fn callee_node_id(&self) -> &str {
        &self.callee.node_id
    }

    /// Returns true if the caller is external to the Itara topology, i.e.
    /// the connection declares no caller block at all.
    pub fn is_external(&self) -> bool {
        self.caller.is_none()
    }

    /// Returns true if this is a direct (colocated, in-process) connection.
    /// False if no transport can be resolved at all (an invalid state
    /// that validate() would reject).
    pub fn is_direct(&self) -> bool {
        self.resolve_callee().map(|c| c.is_direct()).unwrap_or(false)
    }

    /// Returns true if this connection involves any of the given node ids.
    pub fn is_related_to_any_of_nodes(&self, node_ids: &[String]) -> bool {
        if let Some(caller_id) = self.caller_node_id() {
            if node_ids.iter().any(|n| n == caller_id) {
                return true;
            }
        }
        node_ids.iter().any(|n| n == self.callee_node_id())
    }

    /// Resolves the callee side: for each plugin kind, the callee block's
    /// own declaration if it has one, otherwise the connection-level
    /// block. Block-level replacement, never field-level merging.
    /// Authorization is taken from the callee block only, since it has no
    /// connection-level placement.
    ///
    /// Returns None only if neither the callee block nor the connection
    /// level supplies a transport — an invalid state that validate()
    /// would reject. Does not itself repeat validate()'s other checks
    /// (e.g. a blank id): call validate() first for that guarantee.
    pub fn resolve_callee(&self) -> Option<ResolvedCallee<'_>> {
        let transport = pick(self.callee.transport.as_ref(), self.transport.as_ref())?;
        Some(ResolvedCallee {
            node_id: &self.callee.node_id,
            transport,
            serializer: pick(self.callee.serializer.as_ref(), self.serializer.as_ref()),
            authentication: pick(self.callee.authentication.as_ref(), self.authentication.as_ref()),
            authorization: self.callee.authorization.as_ref(),
        })
    }

    /// Resolves the caller side: for each plugin kind, the caller block's
    /// own declaration if it has one, otherwise the connection-level
    /// block. Block-level replacement, never field-level merging.
    /// Failure semantics are taken from the caller block only, since they
    /// have no connection-level placement.
    ///
    /// Returns None if this is an external connection, or if neither the
    /// caller block nor the connection level supplies a transport.
    pub fn resolve_caller(&self) -> Option<ResolvedCaller<'_>> {
        let caller = self.caller.as_ref()?;
        let transport = pick(caller.transport.as_ref(), self.transport.as_ref())?;
        Some(ResolvedCaller {
            node_id: &caller.node_id,
            transport,
            serializer: pick(caller.serializer.as_ref(), self.serializer.as_ref()),
            failure_semantics: caller.failure_semantics.as_ref(),
            authentication: pick(caller.authentication.as_ref(), self.authentication.as_ref()),
        })
    }

    /// Validates this connection.
    ///
    /// Checks run in this order, so the first error reported is the most
    /// fundamental one: the connection id; retired top-level fields; the
    /// caller/callee node ids; illegal plugin placements; each declared
    /// plugin block on its own; and finally the rules that only make
    /// sense once each side is resolved (a transport and serializer on
    /// every side, and agreement on direct).
    pub fn validate(&self) -> Result<(), ConfigError> {
        self.validate_connection_id()?;
        self.reject_retired_from_to()?;
        self.validate_node_ids()?;
        self.reject_illegal_placements()?;
        self.validate_plugin_blocks()?;
        self.validate_resolved_sides()?;
        Ok(())
    }

    fn validate_connection_id(&self) -> Result<(), ConfigError> {
        if self.id.trim().is_empty() {
            return Err(ConfigError::Invalid(format!(
                "A connection (callee.nodeId='{}') is missing required field 'id'.",
                self.callee.node_id
            )));
        }
        if !has_valid_id_charset(&self.id) {
            return Err(ConfigError::Invalid(format!(
                "Connection id '{}' contains invalid characters — ids may only \
                 contain letters, digits, '.', '_', and '-'.",
                self.id
            )));
        }
        Ok(())
    }

    /// The top-level 'from'/'to' fields are gone. Without this check an
    /// old-format config would parse (unknown fields are ignored) and
    /// then fail with an unrelated error about a missing callee.nodeId.
    /// An old-format external connection (blank 'from') is still caught
    /// here, through 'to' — every old-format connection has one.
    fn reject_retired_from_to(&self) -> Result<(), ConfigError> {
        if self.legacy_from.is_some() || self.legacy_to.is_some() {
            return Err(ConfigError::Invalid(format!(
                "Connection id '{}' uses the retired top-level 'from'/'to' fields. \
                 Declare the target as 'callee: {{ nodeId: ... }}' (mandatory) and \
                 the caller as 'caller: {{ nodeId: ... }}'. An external connection \
                 is expressed by omitting the 'caller' block entirely.",
                self.id
            )));
        }
        Ok(())
    }

    fn validate_node_ids(&self) -> Result<(), ConfigError> {
        self.validate_node_id("callee", &self.callee.node_id)?;
        if let Some(caller) = &self.caller {
            self.validate_node_id("caller", &caller.node_id)?;
        }
        Ok(())
    }

    /// A side's nodeId references a node, so it is held to the very same
    /// character-set rule as everything else in this file
    /// (has_valid_id_charset) — there is only ever one such rule here,
    /// never a second copy of it.
    fn validate_node_id(&self, side: &str, node_id: &str) -> Result<(), ConfigError> {
        if node_id.trim().is_empty() {
            let hint = if side == "caller" {
                " A caller block that is present must be complete; to declare \
                 an external connection, omit the 'caller' block entirely."
            } else {
                ""
            };
            return Err(ConfigError::Invalid(format!(
                "Connection id '{}' is missing required field '{}.nodeId'.{}",
                self.id, side, hint
            )));
        }
        if !has_valid_id_charset(node_id) {
            return Err(ConfigError::Invalid(format!(
                "Connection id '{}' has an invalid {}.nodeId '{}' — only letters, \
                 digits, '.', '_', and '-' are allowed.",
                self.id, side, node_id
            )));
        }
        Ok(())
    }

    /// Rejects the four placements the design forbids. Everything else is
    /// legal by construction: ResolvedCaller has no authorization and
    /// ResolvedCallee has no failure_semantics field, so resolution can
    /// only ever read legal placements. The blocks checked here would
    /// otherwise be silently dropped as unknown fields.
    fn reject_illegal_placements(&self) -> Result<(), ConfigError> {
        self.reject_if_declared(
            self.failure_semantics.is_some(), "failureSemantics", "at the connection level", "caller")?;
        self.reject_if_declared(
            self.callee.failure_semantics.is_some(), "failureSemantics", "in the 'callee' block", "caller")?;
        self.reject_if_declared(
            self.authorization.is_some(), "authorization", "at the connection level", "callee")?;
        if let Some(caller) = &self.caller {
            self.reject_if_declared(
                caller.authorization.is_some(), "authorization", "in the 'caller' block", "callee")?;
        }
        Ok(())
    }

    fn reject_if_declared(&self, declared: bool, kind: &str, where_: &str, valid_side: &str) -> Result<(), ConfigError> {
        if declared {
            return Err(ConfigError::Invalid(format!(
                "Connection id '{}' declares '{}' {}, but '{}' is valid in the '{}' block only.",
                self.id, kind, where_, kind, valid_side
            )));
        }
        Ok(())
    }

    /// Validates each declared authentication/authorization block on its
    /// own — currently, that it does not have a blank id. `owner`
    /// identifies where the block was declared, for the error message.
    fn validate_plugin_blocks(&self) -> Result<(), ConfigError> {
        let owner = format!("Connection id '{}'", self.id);
        if let Some(auth) = &self.authentication {
            validate_blank_id(&auth.id, "authentication", &format!("{} (connection level)", owner))?;
        }
        if let Some(auth) = &self.callee.authentication {
            validate_blank_id(&auth.id, "authentication", &format!("{} (callee)", owner))?;
        }
        if let Some(authz) = &self.callee.authorization {
            validate_blank_id(&authz.id, "authorization", &format!("{} (callee)", owner))?;
        }
        if let Some(caller) = &self.caller {
            if let Some(auth) = &caller.authentication {
                validate_blank_id(&auth.id, "authentication", &format!("{} (caller)", owner))?;
            }
        }
        Ok(())
    }

    /// The rules that depend on what each side ends up with after
    /// resolution: every existing side has a transport; both sides agree
    /// on 'direct'; a direct connection is never external; and every
    /// existing side of a non-direct connection has a serializer.
    fn validate_resolved_sides(&self) -> Result<(), ConfigError> {
        let callee_transport = self.require_transport(
            "callee", pick(self.callee.transport.as_ref(), self.transport.as_ref()))?;
        let callee_direct = callee_transport.id.eq_ignore_ascii_case("direct");

        if let Some(caller) = &self.caller {
            let caller_transport = self.require_transport(
                "caller", pick(caller.transport.as_ref(), self.transport.as_ref()))?;
            let caller_direct = caller_transport.id.eq_ignore_ascii_case("direct");
            if caller_direct != callee_direct {
                return Err(ConfigError::Invalid(format!(
                    "Connection id '{}' uses the 'direct' transport on only one side \
                     (caller resolves to '{}', callee resolves to '{}'). A direct \
                     connection is an in-process call, so both sides must resolve to \
                     'direct'.",
                    self.id, caller_transport.id, callee_transport.id
                )));
            }
        } else if callee_direct {
            return Err(ConfigError::Invalid(format!(
                "Connection id '{}' is direct but has no 'caller' block — direct \
                 connections are colocated, in-process calls and cannot be external.",
                self.id
            )));
        }

        if !callee_direct {
            self.require_serializer(
                "callee", pick(self.callee.serializer.as_ref(), self.serializer.as_ref()))?;
            if let Some(caller) = &self.caller {
                self.require_serializer(
                    "caller", pick(caller.serializer.as_ref(), self.serializer.as_ref()))?;
            }
        }
        Ok(())
    }

    fn require_transport<'a>(&self, side: &str, resolved: Option<&'a TransportEntry>) -> Result<&'a TransportEntry, ConfigError> {
        match resolved {
            Some(t) if !t.id.trim().is_empty() => Ok(t),
            _ => Err(ConfigError::Invalid(format!(
                "Connection id '{}' resolves no 'transport.id' for the {} side — \
                 declare 'transport' at the connection level or inside the '{}' block.",
                self.id, side, side
            ))),
        }
    }

    fn require_serializer(&self, side: &str, resolved: Option<&SerializerEntry>) -> Result<(), ConfigError> {
        match resolved {
            Some(s) if !s.id.trim().is_empty() => Ok(()),
            _ => Err(ConfigError::Invalid(format!(
                "Connection id '{}' resolves no 'serializer.id' for the {} side — \
                 declare 'serializer' at the connection level or inside the '{}' block.",
                self.id, side, side
            ))),
        }
    }

    /// Constructs a connection entry directly from its parts, without going
    /// through YAML parsing or validate().
    ///
    /// For tests only, in this crate and in downstream crates — it exists
    /// because two of ConnectionEntry's fields (the connection-level,
    /// reject-only failureSemantics and authorization placements) are
    /// private outside this crate, and the retired from/to legacy fields
    /// are private everywhere. This constructor always leaves all three at
    /// their default: absent, absent, and "not a legacy-format connection".
    /// It cannot represent an illegal connection-level placement of
    /// failureSemantics/authorization at all — a test that needs to
    /// exercise that rejection has to go through parse_string() on real
    /// YAML instead, the same way any other itara-config placement test
    /// does.
    ///
    /// Deliberately does not call validate() — callers that build a
    /// structurally invalid entry (e.g. a direct, external connection) get
    /// exactly that back, unchanged, so they can exercise logic that is
    /// supposed to run defensively even against a config that bypassed
    /// validation.
    pub fn for_testing(
        id: impl Into<String>,
        callee: ConnectionSide,
        caller: Option<ConnectionSide>,
        transport: Option<TransportEntry>,
        serializer: Option<SerializerEntry>,
        authentication: Option<AuthenticationEntry>,
    ) -> ConnectionEntry {
        ConnectionEntry {
            id: id.into(),
            callee,
            caller,
            transport,
            serializer,
            authentication,
            failure_semantics: None,
            authorization: None,
            legacy_from: None,
            legacy_to: None,
        }
    }
}

/// Validates that a declared plugin block's id is not blank. `owner`
/// identifies where the block was declared, for the error message.
fn validate_blank_id(id: &str, kind: &str, owner: &str) -> Result<(), ConfigError> {
    if id.trim().is_empty() {
        return Err(ConfigError::Invalid(format!(
            "{} declares an {} block with a blank 'id'. \
             Omit the block entirely to use the noop default, or supply a valid type identifier.",
            owner, kind
        )));
    }
    Ok(())
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

    // First, parse to a raw Value to detect whether merge keys (<<) are present.
    // This is cheap and avoids unnecessarily falling back to the Value pipeline.
    let raw: serde_yaml::Value = serde_yaml::from_str(&substituted).map_err(|e| {
        ConfigError::Invalid(format!("Failed to parse wiring config: {}", e))
    })?;

    if contains_merge_keys(&raw) {
        // Merge-key path: resolve merge keys, then deserialize via serde_path_to_error.
        // This preserves structural field paths (e.g. nodes[0].component) but loses
        // original YAML line/column information.
        let resolved = merge_keys_serde(raw).map_err(|e| {
            ConfigError::Invalid(format!("Failed to resolve YAML merge keys: {}", e))
        })?;

        let config: WiringConfig = serde_path_to_error::deserialize(resolved).map_err(|e| {
            ConfigError::Invalid(format!("Failed to parse wiring config: {}", e))
        })?;

        config.validate()?;
        Ok(config)
    } else {
        // Direct path: deserialize straight from the string using serde_yaml's
        // Deserializer, wrapped with serde_path_to_error. This preserves BOTH
        // original line/column information AND structural field paths.
        let deserializer = serde_yaml::Deserializer::from_str(&substituted);
        let config: WiringConfig = serde_path_to_error::deserialize(deserializer)
            .map_err(|e| ConfigError::Invalid(format!("Failed to parse wiring config: {}", e)))?;

        config.validate()?;
        Ok(config)
    }
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
        if let Some(caller_id) = conn.caller_node_id() {
            relevant_node_ids.push(caller_id.to_string());
        }
        relevant_node_ids.push(conn.callee_node_id().to_string());
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

    /// Test helper: whether the caller side's resolved failureSemantics
    /// declares a per-attempt timeout. failureSemantics is caller-side
    /// only, so there is no equivalent question to ask of the callee.
    fn caller_has_timeout(conn: &ConnectionEntry) -> bool {
        conn.resolve_caller()
            .and_then(|c| c.failure_semantics)
            .and_then(|fs| fs.timeout.as_ref())
            .is_some()
    }

    const HTTP_CONFIG: &str = r#"
nodes:
  - id: "gatewayNode"
    component: "gateway"
  - id: "calculatorNode"
    component: "calculator"

connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: "gatewayNode"
    callee:
      nodeId: "calculatorNode"
    transport:
      id: http
      params:
        host: "calculator"
        port: "8081"
    serializer:
      id: json

  - id: "external-to-calculator"
    callee:
      nodeId: "gatewayNode"
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
        assert_eq!(conn.caller_node_id(), Some("gatewayNode"));
        assert_eq!(conn.callee_node_id(), "calculatorNode");
        assert_eq!(conn.transport.as_ref().unwrap().id, "http");
        assert_eq!(conn.transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("calculator"));
        assert_eq!(conn.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8081"));
        assert_eq!(conn.serializer.as_ref().unwrap().id, "json");
    }

    #[test]
    fn external_connection_has_no_caller() {
        let config = parse_string(HTTP_CONFIG).unwrap();
        let external = &config.connections[1];
        assert!(external.is_external());
        assert_eq!(external.callee_node_id(), "gatewayNode");
        assert_eq!(external.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8082"));
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
    caller:
      nodeId: "orderServiceNode"
    callee:
      nodeId: "orderPlacedChannel"
    transport:
      id: kafka
    serializer:
      id: json
  - id: "orderPlaced-to-order"
    caller:
      nodeId: "orderPlacedChannel"
    callee:
      nodeId: "orderServiceNode"
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
    caller:
      nodeId: "a"
    callee:
      nodeId: "b"
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
            config.connections[0].transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()),
            Some("localhost")
        );
    }

    #[test]
    fn mapping_alias_resolves_correctly() {
        let yaml = r#"
connections:
  - &baseConn
    id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
        assert_eq!(config.connections[1].transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(config.connections[1].transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8081"));
        assert_eq!(config.connections[1].callee_node_id(), "calculator");
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      <<: *httpDefaults
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let conn = &config.connections[0];
        assert_eq!(conn.transport.as_ref().unwrap().id, "http");
        assert_eq!(conn.transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(conn.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8081"));
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
        assert_eq!(conn.caller_node_id(), Some("gateway"));
        assert_eq!(conn.callee_node_id(), "calculator");
        // local value overrides the anchor
        assert_eq!(conn.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("9090"));
        assert_eq!(conn.transport.as_ref().unwrap().id, "http");
        assert_eq!(conn.transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("localhost"));
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
      params:
        <<: *calcParams
    serializer:
      id: json
  - id: "gateway-to-notifier"
    caller:
      nodeId: gateway
    callee:
      nodeId: notifier
    transport:
      id: http
      params:
        <<: *notifParams
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let calc = &config.connections[0];
        assert_eq!(calc.caller_node_id(), Some("gateway"));
        assert_eq!(calc.callee_node_id(), "calculator");
        assert_eq!(calc.transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("calc-host"));
        assert_eq!(calc.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8081"));
        let notif = &config.connections[1];
        assert_eq!(notif.caller_node_id(), Some("gateway"));
        assert_eq!(notif.callee_node_id(), "notifier");
        assert_eq!(notif.transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("notif-host"));
        assert_eq!(notif.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8082"));
    }

    #[test]
    fn parses_transport_block_with_params() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
        assert_eq!(conn.transport.as_ref().unwrap().id, "http");
        assert_eq!(conn.transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(conn.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8081"));
    }

    #[test]
    fn parses_handle_timeout_true() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
        assert!(config.connections[0].transport.as_ref().unwrap().handle_timeout);
    }

    #[test]
    fn handle_timeout_defaults_to_false() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(!config.connections[0].transport.as_ref().unwrap().handle_timeout);
    }

    #[test]
    fn absent_params_yields_empty_map() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].transport.as_ref().unwrap().params.is_empty());
    }

    #[test]
    fn transport_block_missing_fails_validation() {
        // Missing transport block entirely — should fail
        // Note: this would fail at deserialize time since transport is required
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("transport.id"), "unexpected message: {}", msg);
    }

    #[test]
    fn direct_connection_is_direct() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].resolve_caller().unwrap().failure_semantics.is_none());
    }

    #[test]
    fn failure_semantics_other_fields_default_correctly() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
      failureSemantics:
        id: built-in
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let caller = config.connections[0].resolve_caller().unwrap();
        let fs = caller.failure_semantics.unwrap();
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
      failureSemantics:
        id: built-in
        timeout: 2s
        handleTimeout: true
        absoluteTimeout: 10s
        maxRetry: 3
        params:
          waitDuration: 500ms
          slidingWindowSize: "10"
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let caller = config.connections[0].resolve_caller().unwrap();
        let fs = caller.failure_semantics.unwrap();
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
    caller:
      nodeId: gateway
      failureSemantics:
        id: built-in
        timeout: 5s
        handleTimeout: true
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let fs = config.connections[0].resolve_caller().unwrap().failure_semantics.unwrap();
        assert!(fs.handle_timeout);
    }

    #[test]
    fn has_timeout_returns_true_when_set() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
      failureSemantics:
        id: built-in
        timeout: 2s
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(caller_has_timeout(&config.connections[0]));
    }

    #[test]
    fn has_timeout_returns_false_when_no_failure_semantics() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(!caller_has_timeout(&config.connections[0]));
    }

    #[test]
    fn has_timeout_returns_false_when_failure_semantics_has_no_timeout() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
      failureSemantics:
        id: built-in
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(!caller_has_timeout(&config.connections[0]));
    }

    // ── Authentication ────────────────────────────────────────────────────────

    #[test]
    fn authentication_absent_is_none() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].authentication.is_none());
        assert_eq!(config.connections[0].resolve_callee().unwrap().authentication_id(), "noop");
    }

    #[test]
    fn authentication_parsed_with_id_and_params() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    authentication:
      id: shared-secret
      params:
        secret: "s3cr3t"
        subject: "gateway"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let auth = config.connections[0].authentication.as_ref().unwrap();
        assert_eq!(auth.id, "shared-secret");
        assert_eq!(auth.params.get("secret").map(|s| s.as_str()), Some("s3cr3t"));
        assert_eq!(auth.params.get("subject").map(|s| s.as_str()), Some("gateway"));
        assert_eq!(config.connections[0].resolve_callee().unwrap().authentication_id(), "shared-secret");
    }

    #[test]
    fn authentication_without_id_fails_parsing() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    authentication:
      params:
        secret: "s3cr3t"
    serializer:
      id: json
"#;
        assert!(parse_string(yaml).is_err());
    }

    #[test]
    fn authentication_blank_id_fails_validation() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    authentication:
      id: ""
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("authentication block") && msg.contains("blank 'id'"),
            "unexpected message: {}", msg);
    }

    #[test]
    fn authentication_permitted_on_direct_connection() {
        // Colocation is not a trust boundary (ADR 0025) — direct connections
        // support authentication exactly like any other transport.
        let yaml = r#"
connections:
  - id: "a-to-b"
    caller:
      nodeId: a
    callee:
      nodeId: b
    transport:
      id: direct
    authentication:
      id: shared-secret
      params:
        secret: "s3cr3t"
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].resolve_callee().unwrap().authentication_id(), "shared-secret");
    }

    // ── Authorization ─────────────────────────────────────────────────────────

    #[test]
    fn authorization_absent_is_none() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].authorization.is_none());
        assert_eq!(config.connections[0].resolve_callee().unwrap().authorization_id(), "noop");
    }

    #[test]
    fn authorization_parsed_with_id_and_params() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      authorization:
        id: rule-table
        params:
          allow: "shout"
          deny: "whisper"
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let authz = config.connections[0].callee.authorization.as_ref().unwrap();
        assert_eq!(authz.id, "rule-table");
        assert_eq!(authz.params.get("allow").map(|s| s.as_str()), Some("shout"));
        assert_eq!(authz.params.get("deny").map(|s| s.as_str()), Some("whisper"));
        assert_eq!(config.connections[0].resolve_callee().unwrap().authorization_id(), "rule-table");
    }

    #[test]
    fn authorization_without_id_fails_parsing() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      authorization:
        params:
          allow: "shout"
    transport:
      id: http
    serializer:
      id: json
"#;
        assert!(parse_string(yaml).is_err());
    }

    #[test]
    fn authorization_blank_id_fails_validation() {
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      authorization:
        id: ""
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("authorization block") && msg.contains("blank 'id'"),
            "unexpected message: {}", msg);
    }

    #[test]
    fn authorization_permitted_on_direct_connection() {
        let yaml = r#"
connections:
  - id: "a-to-b"
    caller:
      nodeId: a
    callee:
      nodeId: b
      authorization:
        id: rule-table
        params:
          allow: "shout"
    transport:
      id: direct
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].resolve_callee().unwrap().authorization_id(), "rule-table");
    }

    // ── Connection id ─────────────────────────────────────────────────────────

    #[test]
    fn connection_id_missing_fails_parsing() {
        // 'id' key absent entirely — fails at deserialize time since it's required.
        let yaml = r#"
connections:
  - caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
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
    caller:
      nodeId: "testNode"
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
    caller:
      nodeId: "gatewayNode"
    callee:
      nodeId: "calculatorNode"
    transport:
      <<: *httpDefaults
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.nodes.len(), 2);
        assert_eq!(config.connections.len(), 1);
        let conn = &config.connections[0];
        assert_eq!(conn.transport.as_ref().unwrap().id, "http");
        assert_eq!(conn.transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(conn.transport.as_ref().unwrap().params.get("port").map(|s| s.as_str()), Some("8081"));
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
    caller:
      nodeId: "gatewayNode"
    callee:
      nodeId: "calculatorNode"
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
    caller:
      nodeId: "gatewayNode"
    callee:
      nodeId: "calculatorNode"
    transport:
      <<: *httpDefaults
      # missing 'id' - but merge key provides it
    serializer:
      id: json
"#;
        // This should actually work because merge key provides id
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].transport.as_ref().unwrap().id, "http");
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
    caller:
      nodeId: "gatewayNode"
    callee:
      nodeId: "calculatorNode"
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
    caller:
      nodeId: "gatewayNode"
    callee:
      nodeId: "calculatorNode"
    transport:
      id: http
      params:
        host: *myHost
        port: "8081"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].transport.as_ref().unwrap().params.get("host").map(|s| s.as_str()), Some("localhost"));
    }

    // ── Legal placements ─────────────────────────────────────────────────────

    #[test]
    fn transport_at_every_legal_placement() {
        let yaml = r#"
connections:
  - id: "connection-level"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
  - id: "caller-side"
    caller:
      nodeId: gateway
      transport:
        id: caller-http
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
  - id: "callee-side"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      transport:
        id: callee-http
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].resolve_caller().unwrap().transport.id, "http");
        assert_eq!(config.connections[0].resolve_callee().unwrap().transport.id, "http");
        assert_eq!(config.connections[1].resolve_caller().unwrap().transport.id, "caller-http");
        assert_eq!(config.connections[1].resolve_callee().unwrap().transport.id, "http");
        assert_eq!(config.connections[2].resolve_caller().unwrap().transport.id, "http");
        assert_eq!(config.connections[2].resolve_callee().unwrap().transport.id, "callee-http");
    }

    #[test]
    fn serializer_at_every_legal_placement() {
        let yaml = r#"
connections:
  - id: "connection-level"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
  - id: "caller-side"
    caller:
      nodeId: gateway
      serializer:
        id: caller-json
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
  - id: "callee-side"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      serializer:
        id: callee-json
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].resolve_caller().unwrap().serializer.unwrap().id, "json");
        assert_eq!(config.connections[0].resolve_callee().unwrap().serializer.unwrap().id, "json");
        assert_eq!(config.connections[1].resolve_caller().unwrap().serializer.unwrap().id, "caller-json");
        assert_eq!(config.connections[1].resolve_callee().unwrap().serializer.unwrap().id, "json");
        assert_eq!(config.connections[2].resolve_caller().unwrap().serializer.unwrap().id, "json");
        assert_eq!(config.connections[2].resolve_callee().unwrap().serializer.unwrap().id, "callee-json");
    }

    #[test]
    fn authentication_at_every_legal_placement() {
        // No connection-level block on the second and third connection, so
        // the side that declares nothing falls back to noop.
        let yaml = r#"
connections:
  - id: "connection-level"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
    authentication:
      id: mtls
  - id: "caller-side"
    caller:
      nodeId: gateway
      authentication:
        id: caller-auth
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
  - id: "callee-side"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      authentication:
        id: callee-auth
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].resolve_caller().unwrap().authentication_id(), "mtls");
        assert_eq!(config.connections[0].resolve_callee().unwrap().authentication_id(), "mtls");
        assert_eq!(config.connections[1].resolve_caller().unwrap().authentication_id(), "caller-auth");
        assert_eq!(config.connections[1].resolve_callee().unwrap().authentication_id(), "noop");
        assert_eq!(config.connections[2].resolve_caller().unwrap().authentication_id(), "noop");
        assert_eq!(config.connections[2].resolve_callee().unwrap().authentication_id(), "callee-auth");
    }

    #[test]
    fn failure_semantics_caller_side_only_legal_placement() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      failureSemantics:
        id: built-in
        maxRetry: 3
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let caller = config.connections[0].resolve_caller().unwrap();
        assert_eq!(caller.failure_semantics_id(), "built-in");
        assert_eq!(caller.failure_semantics.unwrap().max_retry, Some(3));
    }

    #[test]
    fn authorization_callee_side_only_legal_placement() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      authorization:
        id: rule-table
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert_eq!(config.connections[0].resolve_callee().unwrap().authorization_id(), "rule-table");
    }

    #[test]
    fn design_document_example_parses() {
        // Verbatim from the design document: the first connection uses
        // different transports on each side, the second is external.
        let yaml = r#"
connections:
  - id: "gateway-to-calculator"
    serializer:
      id: json
    callee:
      nodeId: "calculatorNode"
      transport:
        id: hardened-http
        params:
          host: "${CALC_HOST:-localhost}"
          port: "8081"
      authorization:
        id: rule-table
    caller:
      nodeId: "gatewayNode"
      transport:
        id: http
        params:
          host: "${CALC_HOST:-localhost}"
          port: "8081"
      failureSemantics:
        id: built-in
        maxRetry: 3

  - id: "external-to-gateway"
    callee:
      nodeId: "gatewayNode"
      transport:
        id: http
        params:
          port: "8082"
      serializer:
        id: json
    # no caller block — external caller
"#;
        let config = parse_string(yaml).unwrap();
        let internal = &config.connections[0];
        let external = &config.connections[1];

        assert!(!internal.is_external());
        assert_eq!(internal.resolve_caller().unwrap().node_id, "gatewayNode");
        assert_eq!(internal.resolve_callee().unwrap().node_id, "calculatorNode");
        assert_eq!(internal.resolve_caller().unwrap().transport.id, "http");
        assert_eq!(internal.resolve_callee().unwrap().transport.id, "hardened-http");
        assert_eq!(internal.resolve_caller().unwrap().serializer.unwrap().id, "json");
        assert_eq!(internal.resolve_callee().unwrap().serializer.unwrap().id, "json");
        assert_eq!(internal.resolve_caller().unwrap().failure_semantics_id(), "built-in");
        assert_eq!(internal.resolve_callee().unwrap().authorization_id(), "rule-table");

        assert!(external.is_external());
        assert_eq!(external.resolve_callee().unwrap().node_id, "gatewayNode");
        assert_eq!(external.resolve_callee().unwrap().transport.params.get("port").map(|s| s.as_str()), Some("8082"));
        assert_eq!(external.resolve_callee().unwrap().serializer.unwrap().id, "json");
    }

    // ── Illegal placements ───────────────────────────────────────────────────

    #[test]
    fn failure_semantics_at_connection_level_is_rejected() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
    failureSemantics:
      id: built-in
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("failureSemantics") && msg.contains("connection level")
            && msg.contains("'caller' block only"), "unexpected message: {}", msg);
    }

    #[test]
    fn failure_semantics_in_callee_block_is_rejected() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      failureSemantics:
        id: built-in
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("failureSemantics") && msg.contains("'callee' block")
            && msg.contains("'caller' block only"), "unexpected message: {}", msg);
    }

    #[test]
    fn authorization_at_connection_level_is_rejected() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
    authorization:
      id: rule-table
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("authorization") && msg.contains("connection level")
            && msg.contains("'callee' block only"), "unexpected message: {}", msg);
    }

    #[test]
    fn authorization_in_caller_block_is_rejected() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      authorization:
        id: rule-table
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("authorization") && msg.contains("'caller' block")
            && msg.contains("'callee' block only"), "unexpected message: {}", msg);
    }

    // ── Caller and callee blocks themselves ──────────────────────────────────

    #[test]
    fn callee_node_id_is_mandatory() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      transport:
        id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
    }

    #[test]
    fn caller_block_that_is_present_requires_a_node_id() {
        // The block is there, so it must be complete. Leaving the caller
        // out entirely is how an external connection is declared.
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      transport:
        id: http
    callee:
      nodeId: calculator
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("caller: missing field `nodeId`"), "unexpected message: {}", msg);
    }

    #[test]
    fn callee_node_id_must_use_the_node_id_character_set() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: "not a valid id!"
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("callee.nodeId") && msg.contains("not a valid id!"), "unexpected message: {}", msg);
    }

    #[test]
    fn caller_node_id_must_use_the_node_id_character_set() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: "not a valid id!"
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("caller.nodeId") && msg.contains("not a valid id!"), "unexpected message: {}", msg);
    }

    #[test]
    fn retired_from_to_is_rejected_with_migration_hint() {
        let yaml = r#"
connections:
  - id: "conn21"
    from: gateway
    to: calculator
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("retired top-level 'from'/'to'") && msg.contains("callee"), "unexpected message: {}", msg);
    }

    #[test]
    fn retired_external_form_blank_from_is_rejected_too() {
        // In the old format an external connection had a blank 'from'. It's
        // caught through 'to', which every old-format connection has.
        let yaml = r#"
connections:
  - id: "conn21"
    from:
    to: gateway
    callee:
      nodeId: gateway
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("retired top-level 'from'/'to'"), "unexpected message: {}", msg);
    }

    #[test]
    fn retired_fields_rejected_even_next_to_valid_caller_callee_blocks() {
        // A half-migrated config: without this check, 'from' would simply
        // be dropped as an unknown field and the config would look fine.
        let yaml = r#"
connections:
  - id: "conn21"
    from: somewhere-else
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("retired top-level 'from'/'to'"), "unexpected message: {}", msg);
    }

    #[test]
    fn a_caller_key_with_no_value_is_an_absent_caller_block() {
        // Documented behaviour: YAML gives a bare 'caller:' the value null,
        // which is indistinguishable from the key being absent.
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].is_external());
    }

    // ── External connections ─────────────────────────────────────────────────

    #[test]
    fn external_connection_resolves_callee_from_connection_level() {
        let yaml = r#"
connections:
  - id: "conn21"
    callee:
      nodeId: gateway
    transport:
      id: http
      params:
        port: "8082"
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let callee = config.connections[0].resolve_callee().unwrap();
        assert_eq!(callee.node_id, "gateway");
        assert_eq!(callee.transport.id, "http");
        assert_eq!(callee.transport.params.get("port").map(|s| s.as_str()), Some("8082"));
        assert_eq!(callee.serializer.unwrap().id, "json");
        assert!(!callee.is_direct());
    }

    #[test]
    fn external_connection_has_no_caller_side_to_resolve() {
        let yaml = r#"
connections:
  - id: "conn21"
    callee:
      nodeId: gateway
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].resolve_caller().is_none());
    }

    #[test]
    fn external_connection_callee_side_can_carry_authentication_and_authorization() {
        // An external entry point is exactly where access control matters
        // most, and both belong to the callee.
        let yaml = r#"
connections:
  - id: "conn21"
    callee:
      nodeId: gateway
      authentication:
        id: mtls
      authorization:
        id: rule-table
    transport:
      id: http
    serializer:
      id: json
"#;
        let config = parse_string(yaml).unwrap();
        let callee = config.connections[0].resolve_callee().unwrap();
        assert_eq!(callee.authentication_id(), "mtls");
        assert_eq!(callee.authorization_id(), "rule-table");
    }

    #[test]
    fn failure_semantics_has_nowhere_to_live_on_an_external_connection() {
        // Retry and timeout are the caller's concern and there is no
        // caller block to hold them; the two remaining places are both
        // illegal placements.
        let at_connection_level = r#"
connections:
  - id: "conn21"
    callee:
      nodeId: gateway
    transport:
      id: http
    serializer:
      id: json
    failureSemantics:
      id: built-in
"#;
        let in_callee_block = r#"
connections:
  - id: "conn21"
    callee:
      nodeId: gateway
      failureSemantics:
        id: built-in
    transport:
      id: http
    serializer:
      id: json
"#;
        assert!(parse_string(at_connection_level).is_err());
        assert!(parse_string(in_callee_block).is_err());
    }

    // ── Direct connections ────────────────────────────────────────────────────

    #[test]
    fn direct_declared_per_side_with_no_connection_level_transport() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      transport:
        id: direct
    callee:
      nodeId: calculator
      transport:
        id: direct
"#;
        let config = parse_string(yaml).unwrap();
        assert!(config.connections[0].is_direct());
        assert!(config.connections[0].resolve_caller().unwrap().is_direct());
        assert!(config.connections[0].resolve_callee().unwrap().is_direct());
    }

    #[test]
    fn direct_on_caller_side_only_is_rejected() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      transport:
        id: direct
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("only one side") && msg.contains("caller resolves to 'direct'")
            && msg.contains("callee resolves to 'http'"), "unexpected message: {}", msg);
    }

    #[test]
    fn direct_on_callee_side_only_is_rejected() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      transport:
        id: direct
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("only one side") && msg.contains("caller resolves to 'http'")
            && msg.contains("callee resolves to 'direct'"), "unexpected message: {}", msg);
    }

    // direct_connection_is_direct, direct_connection_does_not_require_serializer,
    // and the direct+external rejection are already covered by the existing,
    // migrated tests in this module.

    // ── Sides that resolve no transport or serializer ────────────────────────

    #[test]
    fn transport_only_on_callee_leaves_caller_without_one() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      transport:
        id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("'transport.id' for the caller side"), "unexpected message: {}", msg);
    }

    #[test]
    fn transport_only_on_caller_leaves_callee_without_one() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      transport:
        id: http
    callee:
      nodeId: calculator
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("'transport.id' for the callee side"), "unexpected message: {}", msg);
    }

    #[test]
    fn side_transport_with_blank_id_is_not_completed_from_connection_level() {
        // transport.id is a required field, so a side block that omits it
        // entirely would fail at deserialize, before block-level
        // replacement is even relevant. A blank id deserializes fine and
        // reaches resolution: a field-level merge would complete it with
        // 'http' from the connection level, but block-level replacement
        // means the caller's own (blank) block wins whole and is rejected
        // on its own merits.
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      transport:
        id: ""
        handleTimeout: true
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("'transport.id' for the caller side"), "unexpected message: {}", msg);
    }

    #[test]
    fn serializer_only_on_callee_leaves_caller_without_one() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      serializer:
        id: json
    transport:
      id: http
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("'serializer.id' for the caller side"), "unexpected message: {}", msg);
    }

    #[test]
    fn serializer_only_on_caller_leaves_callee_without_one() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      serializer:
        id: json
    callee:
      nodeId: calculator
    transport:
      id: http
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("'serializer.id' for the callee side"), "unexpected message: {}", msg);
    }

    #[test]
    fn external_connection_still_needs_a_serializer_on_its_callee_side() {
        let yaml = r#"
connections:
  - id: "conn21"
    callee:
      nodeId: gateway
    transport:
      id: http
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("'serializer.id' for the callee side"), "unexpected message: {}", msg);
    }

    // ── Per-side resolution ───────────────────────────────────────────────────

    #[test]
    fn overriding_one_kind_leaves_other_kinds_on_connection_level() {
        // The caller overrides only its transport. Its serializer and
        // authentication still fall back, and the callee is untouched.
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      transport:
        id: caller-http
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
    authentication:
      id: mtls
"#;
        let config = parse_string(yaml).unwrap();
        let caller = config.connections[0].resolve_caller().unwrap();
        let callee = config.connections[0].resolve_callee().unwrap();

        assert_eq!(caller.transport.id, "caller-http");
        assert_eq!(caller.serializer.unwrap().id, "json");
        assert_eq!(caller.authentication_id(), "mtls");

        assert_eq!(callee.transport.id, "http");
        assert_eq!(callee.serializer.unwrap().id, "json");
        assert_eq!(callee.authentication_id(), "mtls");
    }

    #[test]
    fn each_side_can_override_a_different_kind() {
        // Not an all-or-nothing switch per side: the caller overrides its
        // serializer and the callee its authentication, and everything else
        // on both sides still comes from the connection level.
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      serializer:
        id: caller-json
    callee:
      nodeId: calculator
      authentication:
        id: callee-auth
    transport:
      id: http
    serializer:
      id: json
    authentication:
      id: mtls
"#;
        let config = parse_string(yaml).unwrap();
        let caller = config.connections[0].resolve_caller().unwrap();
        let callee = config.connections[0].resolve_callee().unwrap();

        assert_eq!(caller.transport.id, "http");
        assert_eq!(caller.serializer.unwrap().id, "caller-json");
        assert_eq!(caller.authentication_id(), "mtls");

        assert_eq!(callee.transport.id, "http");
        assert_eq!(callee.serializer.unwrap().id, "json");
        assert_eq!(callee.authentication_id(), "callee-auth");
    }

    #[test]
    fn side_transport_replaces_whole_block_params_not_inherited() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      transport:
        id: caller-http
    callee:
      nodeId: calculator
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
        let caller_transport = &config.connections[0].resolve_caller().unwrap().transport;
        let callee_transport = &config.connections[0].resolve_callee().unwrap().transport;

        // The caller's own block carries only an id, and nothing else.
        assert_eq!(caller_transport.id, "caller-http");
        assert!(caller_transport.params.is_empty());
        assert!(!caller_transport.handle_timeout);

        // The callee declared nothing, so it gets the whole block.
        assert_eq!(callee_transport.id, "http");
        assert_eq!(callee_transport.params.get("host").map(|s| s.as_str()), Some("localhost"));
        assert_eq!(callee_transport.params.get("port").map(|s| s.as_str()), Some("8081"));
        assert!(callee_transport.handle_timeout);
    }

    #[test]
    fn side_serializer_replaces_whole_block_params_not_inherited() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
    callee:
      nodeId: calculator
      serializer:
        id: callee-json
    transport:
      id: http
    serializer:
      id: json
      params:
        pretty: "true"
"#;
        let config = parse_string(yaml).unwrap();
        let caller_serializer = config.connections[0].resolve_caller().unwrap().serializer.unwrap();
        let callee_serializer = config.connections[0].resolve_callee().unwrap().serializer.unwrap();

        assert_eq!(caller_serializer.id, "json");
        assert_eq!(caller_serializer.params.get("pretty").map(|s| s.as_str()), Some("true"));

        assert_eq!(callee_serializer.id, "callee-json");
        assert!(callee_serializer.params.is_empty());
    }

    #[test]
    fn side_authentication_replaces_whole_block_params_not_inherited() {
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      authentication:
        id: caller-auth
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
    authentication:
      id: mtls
      params:
        trustStore: "/etc/itara/truststore.p12"
"#;
        let config = parse_string(yaml).unwrap();
        let caller = config.connections[0].resolve_caller().unwrap();
        let callee = config.connections[0].resolve_callee().unwrap();

        assert_eq!(caller.authentication_id(), "caller-auth");
        assert!(caller.authentication.unwrap().params.is_empty());

        assert_eq!(callee.authentication_id(), "mtls");
        assert_eq!(
            callee.authentication.unwrap().params.get("trustStore").map(|s| s.as_str()),
            Some("/etc/itara/truststore.p12")
        );
    }

    #[test]
    fn side_serializer_with_blank_id_is_not_completed_from_connection_level() {
        // serializer.id is a required field, so a side block that omits it
        // entirely would fail at deserialize, before block-level
        // replacement is even relevant — that's not what's being tested here. A
        // blank id, by contrast, deserializes fine and reaches resolution: a
        // field-level merge would complete it with 'json' from the
        // connection level, but block-level replacement means the caller's
        // own (blank) block wins whole and is rejected on its own merits.
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      serializer:
        id: ""
        params:
          pretty: "true"
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("'serializer.id' for the caller side"), "unexpected message: {}", msg);
    }

    #[test]
    fn side_authentication_with_blank_id_is_not_completed_from_connection_level() {
        // Same reasoning as the serializer case above: authentication.id is
        // required, so this uses an explicit blank id rather than omitting
        // the field. Block-level replacement means the caller's own (blank)
        // authentication block is rejected on its own merits, rather than
        // silently falling back to the connection-level 'mtls'.
        let yaml = r#"
connections:
  - id: "conn21"
    caller:
      nodeId: gateway
      authentication:
        id: ""
        params:
          note: "params only"
    callee:
      nodeId: calculator
    transport:
      id: http
    serializer:
      id: json
    authentication:
      id: mtls
"#;
        let result = parse_string(yaml);
        assert!(result.is_err());
        let msg = format!("{}", result.unwrap_err());
        assert!(msg.contains("(caller)") && msg.contains("authentication") && msg.contains("blank 'id'"),
            "unexpected message: {}", msg);
    }
}
