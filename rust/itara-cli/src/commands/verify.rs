use clap;
use itara_config::{parse_file, WiringConfig};
use itara_libdir::MetadataIndex;
use std::collections::{HashMap, HashSet};

use crate::output::{Issue, TICK, CROSS, blank};

/// Transport types the CLI recognises as valid.
/// Extend this list as new transport libs are added to the runtime.
const KNOWN_TRANSPORTS: &[&str] = &["http", "direct", "kafka"];

const VALID_CHECKS: &[&str] = &[
    "orphaned-nodes",
    "orphaned-connections",
    "duplicate-ids",
    "self-connections",
    "unknown-transport",
    "virtual-no-producers",
    "virtual-no-consumers",
    "virtual-transport-mismatch",
    "api-version-compatibility",
    "timeout-capability",
    "transport-interrupt-safety",
];

enum CheckFilter {
    All,
    Skip(HashSet<String>),
    Only(HashSet<String>),
}

impl CheckFilter {
    fn should_run(&self, name: &str) -> bool {
        match self {
            CheckFilter::All     => true,
            CheckFilter::Skip(s) => !s.contains(name),
            CheckFilter::Only(o) => o.contains(name),
        }
    }
}

#[derive(clap::Args)]
pub struct Args {
    /// Path to the master wiring config file.
    pub config: String,
    /// Path to a directory of .itara metadata files.
    /// When provided, enables API version compatibility, event contract version
    /// compatibility, timeout capability, and transport interrupt safety checks.
    /// When absent, metadata checks are skipped and a warning is reported.
    #[arg(long, value_name = "path")]
    pub metadata_dir: Option<String>,
    /// Skip a specific check by name. Can be repeated. Mutually exclusive with --only.
    /// Valid values: orphaned-nodes, orphaned-connections, duplicate-ids,
    ///               self-connections, unknown-transport, virtual-no-producers,
    ///               virtual-no-consumers, virtual-transport-mismatch,
    ///               api-version-compatibility,
    ///               timeout-capability, transport-interrupt-safety
    #[arg(long, value_name = "check", conflicts_with = "only")]
    pub skip: Vec<String>,
    /// Run only the specified check. Can be repeated. Mutually exclusive with --skip.
    /// Valid values: orphaned-nodes, orphaned-connections, duplicate-ids,
    ///               self-connections, unknown-transport, virtual-no-producers,
    ///               virtual-no-consumers, virtual-transport-mismatch,
    ///               api-version-compatibility,
    ///               timeout-capability, transport-interrupt-safety
    #[arg(long, value_name = "check", conflicts_with = "skip")]
    pub only: Vec<String>,
}

/// Exit codes: 0 if no errors, 1 if any errors were found or the config
/// could not be parsed. Warnings do not affect the exit code.
pub fn run(args: Args) -> i32 {
    // Warn on unknown check names before attempting file I/O.
    for name in args.skip.iter().chain(args.only.iter()) {
        if !VALID_CHECKS.contains(&name.as_str()) {
            eprintln!(
                "error: unknown check name '{}' (valid: {})",
                name,
                VALID_CHECKS.join(", "),
            );
            return 1;
        }
    }

    let filter = if !args.only.is_empty() {
        CheckFilter::Only(args.only.into_iter().collect())
    } else if !args.skip.is_empty() {
        CheckFilter::Skip(args.skip.into_iter().collect())
    } else {
        CheckFilter::All
    };

    // ── Parse ─────────────────────────────────────────────────────────────────
    // The config parser already validates individual field requirements.
    // Any parse error is surfaced here as a top-level ERROR so the output
    // format stays consistent regardless of where the problem was caught.

    let config = match parse_file(&args.config) {
        Ok(c) => c,
        Err(e) => {
            println!("{} itara verify — {}", CROSS, args.config);
            blank();
            println!("  {} ERROR  {}", CROSS, e);
            blank();
            println!("  1 error");
            return 1;
        }
    };

    let (metadata_index, mut metadata_issues) = match &args.metadata_dir {
        None => {
            let warning = Issue::warning(
                "no --metadata-dir provided — API version, event contract, \
                 timeout, and transport interrupt checks are skipped".to_string(),
            );
            (None, vec![warning])
        }
        Some(dir) => {
            let path = std::path::Path::new(dir);
            match MetadataIndex::scan(path) {
                Err(e) => {
                    println!("{} itara verify — {}", CROSS, args.config);
                    blank();
                    println!("  {} ERROR  {}", CROSS, e);
                    blank();
                    println!("  1 error");
                    return 1;
                }
                Ok(scan) => {
                    let mut issues: Vec<Issue> = Vec::new();
                    for (path, msg) in scan.parse_failures {
                        issues.push(Issue::warning(format!(
                            "could not parse metadata file '{}': {}",
                            path.display(), msg
                        )));
                    }
                    for (kind, id, path) in scan.duplicates {
                        issues.push(Issue::warning(format!(
                            "duplicate metadata entry (kind='{}', id='{}') in '{}' — first entry wins",
                            kind, id, path.display()
                        )));
                    }
                    (Some(scan.index), issues)
                }
            }
        }
    };
 
    let mut issues = collect_issues(&config, &filter, metadata_index.as_ref());
    issues.append(&mut metadata_issues);
    print_results(&args.config, &config, &issues);
 
    if issues.iter().any(|i| i.is_error()) { 1 } else { 0 }
}
 
/// Runs all logical checks on a parsed config and returns the full list of issues.
///
/// Separated from `run` so it can be called directly in unit tests without
/// going through file I/O or argument parsing.
fn collect_issues(config: &WiringConfig, filter: &CheckFilter, meta: Option<&MetadataIndex>) -> Vec<Issue> {
    let mut issues: Vec<Issue> = Vec::new();

    if filter.should_run("duplicate-ids")        { check_duplicate_ids(config, &mut issues); }
    if filter.should_run("self-connections")      { check_self_connections(config, &mut issues); }
    if filter.should_run("orphaned-nodes")        { check_orphaned_nodes(config, &mut issues); }
    if filter.should_run("orphaned-connections")  { check_orphaned_connections(config, &mut issues); }
    if filter.should_run("unknown-transport")     { check_unknown_transports(config, &mut issues); }
    if filter.should_run("virtual-no-producers")      { check_virtual_no_producers(config, &mut issues); }
    if filter.should_run("virtual-no-consumers")      { check_virtual_no_consumers(config, &mut issues); }
    if filter.should_run("virtual-transport-mismatch"){ check_virtual_transport_mismatch(config, &mut issues); }

    if let Some(meta) = meta {
        if filter.should_run("api-version-compatibility")  { check_api_version_compatibility(config, meta, &mut issues); }
        if filter.should_run("timeout-capability")         { check_timeout_capability(config, meta, &mut issues); }
        if filter.should_run("transport-interrupt-safety") { check_transport_interrupt_safety(config, meta, &mut issues); }
    }

    issues
}
 
// ── Checks ────────────────────────────────────────────────────────────────────
 
fn check_duplicate_ids(config: &WiringConfig, issues: &mut Vec<Issue>) {
    let mut seen: HashMap<&str, usize> = HashMap::new();
    for node in &config.nodes {
        *seen.entry(node.id()).or_default() += 1;
    }
    // Sort for deterministic output order.
    let mut duplicates: Vec<(&str, usize)> = seen.into_iter()
        .filter(|(_, count)| *count > 1)
        .collect();
    duplicates.sort_by_key(|(id, _)| *id);
    for (id, count) in duplicates {
        issues.push(Issue::error(format!(
            "node id '{}' is declared {} times — node ids must be unique",
            id, count
        )));
    }
}
 
fn check_self_connections(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        if let Some(from) = conn.from.as_deref() {
            if !from.trim().is_empty() && from == conn.to {
                issues.push(Issue::error(format!(
                    "connection from '{}' to '{}' is a self-connection",
                    from, conn.to
                )));
            }
        }
    }
}
 
fn check_orphaned_nodes(config: &WiringConfig, issues: &mut Vec<Issue>) {
    let referenced: HashSet<&str> = config.connections.iter()
        .flat_map(|c| {
            let mut ids: Vec<&str> = vec![c.to.as_str()];
            if let Some(from) = c.from.as_deref() {
                if !from.trim().is_empty() {
                    ids.push(from);
                }
            }
            ids
        })
        .collect();
 
    for node in &config.nodes {
        if !referenced.contains(node.id()) {
            issues.push(Issue::error(format!(
                "node '{}' is declared but not referenced in any connection",
                node.id()
            )));
        }
    }
}
 
fn check_orphaned_connections(config: &WiringConfig, issues: &mut Vec<Issue>) {
    let declared: HashSet<&str> = config.nodes.iter()
        .map(|n| n.id())
        .collect();
 
    for conn in &config.connections {
        if !declared.contains(conn.to.as_str()) {
            issues.push(Issue::error(format!(
                "connection references undeclared node '{}'",
                conn.to
            )));
        }
        if let Some(from) = conn.from.as_deref() {
            if !from.trim().is_empty() && !declared.contains(from) {
                issues.push(Issue::error(format!(
                    "connection references undeclared node '{}'",
                    from
                )));
            }
        }
    }
}
 
fn check_unknown_transports(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        let t = conn.transport.id.to_ascii_lowercase();
        if !KNOWN_TRANSPORTS.contains(&t.as_str()) {
            issues.push(Issue::error(format!(
                "connection to '{}' has unknown transport type '{}' (known: {})",
                conn.to,
                conn.transport.id,
                KNOWN_TRANSPORTS.join(", "),
            )));
        }
    }
}

fn check_virtual_no_producers(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for vn in config.virtual_nodes() {
        let has_producer = config.connections.iter()
            .any(|c| c.to == vn.id);
        if !has_producer {
            issues.push(Issue::warning(format!(
                "virtual node '{}' ({}) has no inbound connections — \
                 nothing will publish to this channel",
                vn.id, vn.contract
            )));
        }
    }
}

fn check_virtual_no_consumers(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for vn in config.virtual_nodes() {
        let has_consumer = config.connections.iter()
            .any(|c| c.from.as_deref() == Some(vn.id.as_str()));
        if !has_consumer {
            issues.push(Issue::warning(format!(
                "virtual node '{}' ({}) has no outbound connections — \
                 nothing will consume from this channel",
                vn.id, vn.contract
            )));
        }
    }
}

fn check_virtual_transport_mismatch(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for vn in config.virtual_nodes() {
        let transports: std::collections::HashSet<String> = config.connections.iter()
            .filter(|c| c.to == vn.id
                    || c.from.as_deref() == Some(vn.id.as_str()))
            .map(|c| c.transport.id.to_ascii_lowercase())
            .collect();

        if transports.len() > 1 {
            let mut sorted: Vec<String> = transports.into_iter().collect();
            sorted.sort();
            issues.push(Issue::warning(format!(
                "virtual node '{}' ({}) has connections with mixed transport types: {} — \
                 all connections to a virtual node should use the same transport",
                vn.id, vn.contract, sorted.join(", ")
            )));
        }
    }
}

fn check_api_version_compatibility(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    use semver::{Version, VersionReq};

    for conn in &config.connections {
        // Skip external connections — no caller node to look up.
        if conn.is_external() {
            continue;
        }

        let from_id = match conn.from.as_deref() {
            Some(f) if !f.trim().is_empty() => f,
            _ => continue,
        };

        // Resolve caller component id from its node.
        let caller_component = match config.component_of_node(from_id) {
            Some(c) => c,
            None => continue, // virtual node on the caller side — not applicable
        };

        // Resolve callee component id from its node.
        let callee_component = match config.component_of_node(&conn.to) {
            Some(c) => c,
            None => continue, // virtual node on the callee side — not applicable
        };

        // Look up caller metadata.
        let caller_meta = match meta.component(caller_component) {
            Some(m) => m,
            None => {
                issues.push(Issue::error(format!(
                    "connection '{}' → '{}': no metadata found for caller component '{}' \
                     — api-version-compatibility check skipped for this connection",
                    from_id, conn.to, caller_component
                )));
                continue;
            }
        };

        // Find the caller's declared dependency on the callee's API.
        let api_deps = match &caller_meta.api_dependencies {
            Some(d) => d,
            None => {
                issues.push(Issue::error(format!(
                    "connection '{}' → '{}': caller component '{}' declares no \
                     [api-dependencies] — api-version-compatibility check skipped \
                     for this connection",
                    from_id, conn.to, caller_component
                )));
                continue;
            }
        };

        let dep = match api_deps.calls.iter().find(|d| d.id == callee_component) {
            Some(d) => d,
            None => {
                issues.push(Issue::error(format!(
                    "connection '{}' → '{}': caller component '{}' does not declare \
                     a dependency on callee API '{}' in [api-dependencies] \
                     — api-version-compatibility check skipped for this connection",
                    from_id, conn.to, caller_component, callee_component
                )));
                continue;
            }
        };

        // Parse the caller's exact version.
        let caller_version = match Version::parse(&dep.version) {
            Ok(v) => v,
            Err(e) => {
                issues.push(Issue::error(format!(
                    "connection '{}' → '{}': caller component '{}' declares \
                     invalid semver version '{}' for dependency '{}': {} \
                     — api-version-compatibility check skipped for this connection",
                    from_id, conn.to, caller_component, dep.version, callee_component, e
                )));
                continue;
            }
        };

        // Look up callee metadata.
        let callee_meta = match meta.component(callee_component) {
            Some(m) => m,
            None => {
                issues.push(Issue::error(format!(
                    "connection '{}' → '{}': no metadata found for callee component '{}' \
                     — api-version-compatibility check skipped for this connection",
                    from_id, conn.to, callee_component
                )));
                continue;
            }
        };

        // Parse the callee's declared api-version requirement.
        let api_version_str = &callee_meta.artifact.api_version;
        if api_version_str.is_empty() {
            issues.push(Issue::error(format!(
                "connection '{}' → '{}': callee component '{}' does not declare \
                 api-version in its metadata \
                 — api-version-compatibility check skipped for this connection",
                from_id, conn.to, callee_component
            )));
            continue;
        }

        let callee_req = match VersionReq::parse(api_version_str) {
            Ok(r) => r,
            Err(e) => {
                issues.push(Issue::error(format!(
                    "connection '{}' → '{}': callee component '{}' declares \
                     invalid semver range '{}' for api-version: {} \
                     — api-version-compatibility check skipped for this connection",
                    from_id, conn.to, callee_component, api_version_str, e
                )));
                continue;
            }
        };

        if !callee_req.matches(&caller_version) {
            issues.push(Issue::error(format!(
                "connection '{}' → '{}': caller component '{}' was built against \
                 API '{}' version '{}', but callee component '{}' implements \
                 api-version '{}' — versions are incompatible",
                from_id, conn.to,
                caller_component, callee_component, dep.version,
                callee_component, api_version_str
            )));
        }
    }
}

fn check_timeout_capability(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        if conn.is_external() || conn.is_direct() {
            continue;
        }

        let fs = match &conn.failure_semantics {
            Some(fs) => fs,
            None => continue, // no failure semantics block — no timeout to check
        };

        let has_timeout       = fs.timeout.is_some();
        let has_abs_timeout   = fs.absolute_timeout.is_some();
        let fs_handle_timeout = fs.handle_timeout;
        let t_handle_timeout  = conn.transport.handle_timeout;

        if !has_timeout && !has_abs_timeout {
            continue; // nothing to check
        }

        let conn_label = format!(
            "'{}' → '{}'",
            conn.from.as_deref().unwrap_or("?"),
            conn.to
        );

        // Look up transport metadata if we need capability checks.
        let transport_caps = meta.transport(&conn.transport.id)
            .and_then(|m| m.transport.as_ref())
            .map(|t| &t.capabilities);

        // Look up failure semantics metadata if we need capability checks.
        let fs_caps = meta.failure_semantics(&fs.id)
            .and_then(|m| m.failure_semantics.as_ref())
            .map(|f| &f.capabilities);

        if has_timeout {
            // Transport configured to enforce timeout natively but declares it
            // does not support native timeout enforcement.
            if t_handle_timeout {
                match transport_caps {
                    None => {
                        issues.push(Issue::error(format!(
                            "connection {}: transport '{}' is configured to enforce \
                             timeout natively but no metadata was found for it \
                             — cannot verify native timeout capability",
                            conn_label, conn.transport.id
                        )));
                    }
                    Some(caps) if !caps.native_call_timeout => {
                        issues.push(Issue::error(format!(
                            "connection {}: transport '{}' is configured to enforce \
                             timeout natively (transport.handleTimeout = true) but \
                             its metadata declares native-call-timeout = false",
                            conn_label, conn.transport.id
                        )));
                    }
                    _ => {}
                }
            }

            // Failure semantics configured to enforce timeout externally but
            // declares it does not support external timeout enforcement.
            if fs_handle_timeout {
                match fs_caps {
                    None => {
                        issues.push(Issue::error(format!(
                            "connection {}: failure semantics '{}' is configured to \
                             enforce timeout externally but no metadata was found for it \
                             — cannot verify external timeout capability",
                            conn_label, fs.id
                        )));
                    }
                    Some(caps) if !caps.supports_external_timeout => {
                        issues.push(Issue::error(format!(
                            "connection {}: failure semantics '{}' is configured to \
                             enforce timeout externally (failureSemantics.handleTimeout = true) \
                             but its metadata declares supports-external-timeout = false",
                            conn_label, fs.id
                        )));
                    }
                    _ => {}
                }

                // Failure semantics configured to enforce timeout externally but
                // transport declares it is not safe to interrupt externally.
                match transport_caps {
                    None => {} // already warned above if t_handle_timeout was set
                    Some(caps) if !caps.externally_interruptible => {
                        issues.push(Issue::error(format!(
                            "connection {}: failure semantics '{}' is configured to \
                             enforce timeout externally but transport '{}' declares \
                             externally-interruptible = false",
                            conn_label, fs.id, conn.transport.id
                        )));
                    }
                    _ => {}
                }
            }

            // Timeout declared but neither side configured to enforce it.
            if !t_handle_timeout && !fs_handle_timeout {
                issues.push(Issue::warning(format!(
                    "connection {}: a timeout is declared but neither the transport \
                     nor the failure semantics implementation is configured to enforce it \
                     — the timeout value will be passed to the transport but nothing \
                     will act on it",
                    conn_label
                )));
            }

            // Timeout declared and both sides configured to enforce it.
            if t_handle_timeout && fs_handle_timeout {
                issues.push(Issue::warning(format!(
                    "connection {}: both the transport and the failure semantics \
                     implementation are configured to enforce the timeout — both will \
                     independently attempt enforcement; behaviour depends on which fires first",
                    conn_label
                )));
            }
        }

        // Absolute timeout configured but failure semantics does not declare
        // support for external timeout enforcement.
        if has_abs_timeout {
            match fs_caps {
                None => {
                    issues.push(Issue::error(format!(
                        "connection {}: an absolute timeout is configured but no metadata \
                         was found for failure semantics '{}' \
                         — cannot verify external timeout capability",
                        conn_label, fs.id
                    )));
                }
                Some(caps) if !caps.supports_external_timeout => {
                    issues.push(Issue::error(format!(
                        "connection {}: an absolute timeout is configured but failure \
                         semantics '{}' does not declare supports-external-timeout = true",
                        conn_label, fs.id
                    )));
                }
                _ => {}
            }
        }
    }
}

fn check_transport_interrupt_safety(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        if conn.is_external() || conn.is_direct() {
            continue;
        }

        let fs = match &conn.failure_semantics {
            Some(fs) => fs,
            None => continue,
        };

        if !fs.handle_timeout {
            continue; // external timeout enforcement not configured
        }

        let conn_label = format!(
            "'{}' → '{}'",
            conn.from.as_deref().unwrap_or("?"),
            conn.to
        );

        let transport_meta = match meta.transport(&conn.transport.id) {
            None => {
                issues.push(Issue::error(format!(
                    "connection {}: external timeout enforcement is configured but \
                     no metadata was found for transport '{}' \
                     — cannot verify interrupt safety",
                    conn_label, conn.transport.id
                )));
                continue;
            }
            Some(m) => m,
        };

        let caps = match &transport_meta.transport {
            None => {
                issues.push(Issue::error(format!(
                    "connection {}: external timeout enforcement is configured but \
                     transport '{}' metadata declares no [transport] section \
                     — cannot verify interrupt safety",
                    conn_label, conn.transport.id
                )));
                continue;
            }
            Some(t) => &t.capabilities,
        };

        if !caps.externally_interruptible {
            issues.push(Issue::error(format!(
                "connection {}: external timeout enforcement is configured \
                 (failureSemantics.handleTimeout = true) but transport '{}' \
                 declares externally-interruptible = false — interrupting this \
                 transport externally may leave connections in an inconsistent state",
                conn_label, conn.transport.id
            )));
        }
    }
}
 
// ── Output ────────────────────────────────────────────────────────────────────
 
fn print_results(path: &str, config: &WiringConfig, issues: &[Issue]) {
    let error_count   = issues.iter().filter(|i| i.is_error()).count();
    let warning_count = issues.iter().filter(|i| !i.is_error()).count();
    let has_errors    = error_count > 0;
 
    let status_symbol = if has_errors { CROSS } else { TICK };
    println!("{} itara verify — {}", status_symbol, path);
    blank();
 
    let node_word = if config.nodes.len() == 1 { "node" } else { "nodes" };
    let conn_word = if config.connections.len() == 1 { "connection" } else { "connections" };
    println!(
        "  {} {}, {} {}",
        config.nodes.len(), node_word,
        config.connections.len(), conn_word,
    );
    blank();
 
    if issues.is_empty() {
        println!("  No issues found.");
    } else {
        for issue in issues {
            println!("  {}  {}", issue.label(), issue.message);
        }
        blank();
        println!("  {}", summary_line(error_count, warning_count));
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

fn summary_line(errors: usize, warnings: usize) -> String {
    match (errors, warnings) {
        (0, 0) => "No issues found.".to_string(),
        (e, 0) => format!("{} {}", e, plural(e, "error", "errors")),
        (0, w) => format!("{} {}", w, plural(w, "warning", "warnings")),
        (e, w) => format!(
            "{} {}, {} {}",
            e, plural(e, "error", "errors"),
            w, plural(w, "warning", "warnings"),
        ),
    }
}

fn plural<'a>(n: usize, singular: &'a str, plural: &'a str) -> &'a str {
    if n == 1 { singular } else { plural }
}
 
// ── Unit tests ────────────────────────────────────────────────────────────────
 
#[cfg(test)]
mod tests {
    use super::*;
    use itara_config::{WiringConfig, Node, ComponentNode, VirtualNode, ConnectionEntry};
 
    // ── Test helpers ──────────────────────────────────────────────────────────
 
    fn node(id: &str, component: &str) -> Node {
        Node::Component(ComponentNode { id: id.into(), component: component.into() })
    }

    fn virtual_node(id: &str, contract: &str, address: &str) -> Node {
        Node::Virtual(VirtualNode {
            id: id.into(),
            contract: contract.into(),
            address: address.into(),
        })
    }


    fn transport_entry(id: &str, port: Option<u16>) -> itara_config::TransportEntry {
        let mut params = std::collections::HashMap::new();
        if let Some(p) = port {
            params.insert("port".to_string(), p.to_string());
        }
        itara_config::TransportEntry {
            id: id.into(),
            handle_timeout: false,
            params,
        }
    }
 
    fn http(from: Option<&str>, to: &str, port: u16) -> ConnectionEntry {
        ConnectionEntry {
            from: from.map(Into::into),
            to: to.into(),
            transport: transport_entry("http", Some(port)),
            serializer: "".into(),
            failure_semantics: None,
        }
    }
 
    fn direct(from: &str, to: &str) -> ConnectionEntry {
        ConnectionEntry {
            from: Some(from.into()),
            to: to.into(),
            transport: transport_entry("direct", None),
            serializer: "".into(),
            failure_semantics: None,
        }
    }

    fn kafka(from: Option<&str>, to: &str) -> ConnectionEntry {
        ConnectionEntry {
            from: from.map(Into::into),
            to: to.into(),
            transport: transport_entry("kafka", None),
            serializer: "json".into(),
            failure_semantics: None,
        }
    }
 
    fn conn_with_transport(from: Option<&str>, to: &str, transport: &str, port: u16) -> ConnectionEntry {
        ConnectionEntry {
            from: from.map(Into::into),
            to: to.into(),
            transport: transport_entry(transport, Some(port)),
            serializer: "".into(),
            failure_semantics: None,
        }
    }
 
    fn config(nodes: Vec<Node>, connections: Vec<ConnectionEntry>) -> WiringConfig {
        WiringConfig { nodes, connections, local_node_ids: vec![] }
    }
 
    fn errors(issues: &[Issue]) -> Vec<&Issue> {
        issues.iter().filter(|i| i.is_error()).collect()
    }
 
    // ── Clean config ──────────────────────────────────────────────────────────
 
    #[test]
    fn clean_config_no_issues() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        assert!(collect_issues(&cfg, &CheckFilter::All, None).is_empty());
    }
 
    #[test]
    fn clean_config_with_direct_connection() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), direct("a", "b")],
        );
        assert!(collect_issues(&cfg, &CheckFilter::All, None).is_empty());
    }
 
    // ── Duplicate ids ─────────────────────────────────────────────────────────
 
    #[test]
    fn duplicate_id_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("a", "ca-dup"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 1);
        assert!(issues[0].message.contains("'a'"));
        assert!(issues[0].message.contains("declared 2 times"));
    }
 
    #[test]
    fn multiple_duplicate_ids_all_flagged() {
        let cfg = config(
            vec![
                node("a", "ca"), node("a", "ca2"),
                node("b", "cb"), node("b", "cb2"),
            ],
            vec![
                http(None, "a", 8080),
                http(Some("a"), "b", 8081),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 2);
    }
 
    // ── Self-connections ──────────────────────────────────────────────────────
 
    #[test]
    fn self_connection_flagged() {
        let cfg = config(
            vec![node("a", "ca")],
            vec![http(None, "a", 8080), http(Some("a"), "a", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 1);
        assert!(issues[0].message.contains("self-connection"));
    }
 
    #[test]
    fn non_self_connection_not_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        let self_conn_issues: Vec<_> = collect_issues(&cfg, &CheckFilter::All, None).into_iter()
            .filter(|i| i.message.contains("self-connection"))
            .collect();
        assert!(self_conn_issues.is_empty());
    }
 
    // ── Orphaned nodes ────────────────────────────────────────────────────────
 
    #[test]
    fn orphaned_node_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("orphan", "co")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 1);
        assert!(issues[0].message.contains("'orphan'"));
        assert!(issues[0].message.contains("not referenced"));
    }
 
    #[test]
    fn node_referenced_only_as_to_is_not_orphaned() {
        // "b" only appears as `to`, never as `from` — still referenced
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        assert!(collect_issues(&cfg, &CheckFilter::All, None).is_empty());
    }
 
    #[test]
    fn node_referenced_only_as_from_is_not_orphaned() {
        // "a" only appears as `from` in one connection, `to` in the external one
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), direct("a", "b")],
        );
        assert!(collect_issues(&cfg, &CheckFilter::All, None).is_empty());
    }
 
    // ── Orphaned connections ──────────────────────────────────────────────────
 
    #[test]
    fn undeclared_to_node_flagged() {
        let cfg = config(
            vec![node("a", "ca")],
            vec![http(None, "a", 8080), http(Some("a"), "ghost", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 1);
        assert!(issues[0].message.contains("'ghost'"));
        assert!(issues[0].message.contains("undeclared"));
    }
 
    #[test]
    fn undeclared_from_node_flagged() {
        let cfg = config(
            vec![node("b", "cb")],
            vec![http(None, "b", 8080), http(Some("ghost"), "b", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 1);
        assert!(issues[0].message.contains("'ghost'"));
    }
 
    #[test]
    fn external_connection_from_none_does_not_flag_from() {
        // External connections have from=None; the None should not be treated
        // as an undeclared node reference.
        let cfg = config(
            vec![node("a", "ca")],
            vec![http(None, "a", 8080)],
        );
        assert!(collect_issues(&cfg, &CheckFilter::All, None).is_empty());
    }
 
    // ── Unknown transports ────────────────────────────────────────────────────
 
    #[test]
    fn unknown_transport_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![
                http(None, "a", 8080),
                conn_with_transport(Some("a"), "b", "grpc", 8081),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 1);
        assert!(issues[0].message.contains("grpc"));
        assert!(issues[0].message.contains("unknown transport"));
    }
 
    #[test]
    fn known_transports_not_flagged() {
        for transport in KNOWN_TRANSPORTS {
            let conn = if *transport == "direct" {
                direct("a", "b")
            } else {
                conn_with_transport(Some("a"), "b", transport, 8081)
            };
            let cfg = config(
                vec![node("a", "ca"), node("b", "cb")],
                vec![http(None, "a", 8080), conn],
            );
            let issues = collect_issues(&cfg, &CheckFilter::All, None);
            let transport_issues: Vec<_> = issues.iter()
                .filter(|i| i.message.contains("unknown transport"))
                .collect();
            assert!(
                transport_issues.is_empty(),
                "transport '{}' was incorrectly flagged as unknown",
                transport
            );
        }
    }
 
    #[test]
    fn transport_check_is_case_insensitive() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![
                http(None, "a", 8080),
                conn_with_transport(Some("a"), "b", "HTTP", 8081),
            ],
        );
        let transport_issues: Vec<_> = collect_issues(&cfg, &CheckFilter::All, None).into_iter()
            .filter(|i| i.message.contains("unknown transport"))
            .collect();
        assert!(transport_issues.is_empty());
    }

    // ── virtual node checks ───────────────────────────────────────────────────

    #[test]
    fn virtual_no_producers_warned() {
        let cfg = config(
            vec![
                node("consumerNode", "consumer"),
                virtual_node("channel", "events/placed", "topic.placed"),
            ],
            vec![
                kafka(Some("channel"), "consumerNode"),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert!(issues.iter().any(|i|
            !i.is_error() && i.message.contains("no inbound connections")
        ));
    }

    #[test]
    fn virtual_no_consumers_warned() {
        let cfg = config(
            vec![
                node("producerNode", "producer"),
                virtual_node("channel", "events/placed", "topic.placed"),
            ],
            vec![
                kafka(Some("producerNode"), "channel"),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert!(issues.iter().any(|i|
            !i.is_error() && i.message.contains("no outbound connections")
        ));
    }

    #[test]
    fn virtual_with_both_producer_and_consumer_no_warning() {
        let cfg = config(
            vec![
                node("producerNode", "producer"),
                node("consumerNode", "consumer"),
                virtual_node("channel", "events/placed", "topic.placed"),
            ],
            vec![
                kafka(Some("producerNode"), "channel"),
                kafka(Some("channel"), "consumerNode"),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert!(issues.iter().all(|i| i.is_error()
            || (!i.message.contains("no inbound") 
                && !i.message.contains("no outbound"))));
    }

    #[test]
    fn virtual_transport_mismatch_warned() {
        let cfg = config(
            vec![
                node("producerNode", "producer"),
                node("consumerNode", "consumer"),
                virtual_node("channel", "events/placed", "topic.placed"),
            ],
            vec![
                kafka(Some("producerNode"), "channel"),
                ConnectionEntry {
                    from: Some("channel".into()),
                    to: "consumerNode".into(),
                    transport: transport_entry("http", Some(8081)),
                    serializer: "json".into(),
                    failure_semantics: None,
                },
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert!(issues.iter().any(|i|
            !i.is_error() && i.message.contains("mixed transport types")
        ));
    }

    #[test]
    fn virtual_consistent_transport_no_mismatch_warning() {
        let cfg = config(
            vec![
                node("producerNode", "producer"),
                node("consumerNode", "consumer"),
                virtual_node("channel", "events/placed", "topic.placed"),
            ],
            vec![
                kafka(Some("producerNode"), "channel"),
                kafka(Some("channel"), "consumerNode"),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert!(issues.iter().all(|i|
            !i.message.contains("mixed transport types")
        ));
    }

    #[test]
    fn kafka_transport_not_flagged_as_unknown() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![kafka(Some("a"), "b")],
        );
        let transport_issues: Vec<_> = collect_issues(&cfg, &CheckFilter::All, None)
            .into_iter()
            .filter(|i| i.message.contains("unknown transport"))
            .collect();
        assert!(transport_issues.is_empty());
    }

    // ── MetadataIndex test helper ─────────────────────────────────────────────
    //
    // Builds a MetadataIndex from a list of raw TOML strings without touching
    // the filesystem. Keeps metadata-dependent tests fast and self-contained.

    fn index_from_toml(entries: &[&str]) -> MetadataIndex {
        use std::io::Write;
        let dir = tempfile::tempdir().unwrap();
        for (i, toml) in entries.iter().enumerate() {
            let path = dir.path().join(format!("artifact_{}.itara", i));
            let mut f = std::fs::File::create(&path).unwrap();
            f.write_all(toml.as_bytes()).unwrap();
        }
        MetadataIndex::scan(dir.path()).unwrap().index
    }

    fn conn_with_fs(
        from: Option<&str>,
        to: &str,
        transport: &str,
        transport_handle_timeout: bool,
        fs_id: &str,
        timeout: Option<&str>,
        fs_handle_timeout: bool,
        absolute_timeout: Option<&str>,
    ) -> ConnectionEntry {
        ConnectionEntry {
            from: from.map(Into::into),
            to: to.into(),
            transport: itara_config::TransportEntry {
                id: transport.into(),
                handle_timeout: transport_handle_timeout,
                params: Default::default(),
            },
            serializer: "json".into(),
            failure_semantics: Some(itara_config::FailureSemanticsEntry {
                id: fs_id.into(),
                timeout: timeout.map(Into::into),
                handle_timeout: fs_handle_timeout,
                absolute_timeout: absolute_timeout.map(Into::into),
                max_retry: None,
                params: Default::default(),
            }),
        }
    }

    // ── check_api_version_compatibility ───────────────────────────────────────

    fn caller_meta(component_id: &str, dep_id: &str, dep_version: &str) -> String {
        format!(r#"
[artifact]
kind = "component"
id = "{component_id}"
version = "1.0.0"
api-version = "1.0.0"

[api-dependencies]
calls = [
  {{ id = "{dep_id}", version = "{dep_version}" }},
]
"#)
    }

    fn callee_meta(component_id: &str, api_version: &str) -> String {
        format!(r#"
[artifact]
kind = "component"
id = "{component_id}"
version = "1.0.0"
api-version = "{api_version}"
"#)
    }

    #[test]
    fn api_version_compatible_same_major_higher_minor() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "1.5.0"),
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(issues.is_empty(), "unexpected issues: {:?}", issues);
    }

    #[test]
    fn api_version_compatible_exact_match() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "1.0.0"),
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(issues.is_empty(), "unexpected issues: {:?}", issues);
    }

    #[test]
    fn api_version_compatible_direct_connection() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![direct("caller", "callee")],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "1.0.0"),
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(issues.is_empty(), "unexpected issues: {:?}", issues);
    }

    #[test]
    fn api_version_incompatible_different_major() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "2.0.0"),
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("incompatible"));
    }

    #[test]
    fn api_version_incompatible_caller_behind() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "0.9.0"),
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("incompatible"));
    }

    #[test]
    fn api_version_external_connection_skipped() {
        let cfg = config(
            vec![node("callee", "comp-b")],
            vec![http(None, "callee", 8080)],
        );
        let meta = index_from_toml(&[&callee_meta("comp-b", "^1.0")]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(issues.iter().all(|i| !i.message.contains("api-version")));
    }

    #[test]
    fn api_version_caller_metadata_missing_is_error() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[&callee_meta("comp-b", "^1.0")]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("comp-a"));
    }

    #[test]
    fn api_version_caller_no_api_dependencies_is_error() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            r#"
[artifact]
kind = "component"
id = "comp-a"
version = "1.0.0"
api-version = "1.0.0"
"#,
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("api-dependencies"));
    }

    #[test]
    fn api_version_caller_missing_entry_for_callee_is_error() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "some-other-api", "1.0.0"),
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("comp-b"));
    }

    #[test]
    fn api_version_caller_invalid_semver_is_error() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "not-a-version"),
            &callee_meta("comp-b", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("invalid semver"));
    }

    #[test]
    fn api_version_callee_metadata_missing_is_error() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[&caller_meta("comp-a", "comp-b", "1.0.0")]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("comp-b"));
    }

    #[test]
    fn api_version_callee_empty_api_version_is_error() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "1.0.0"),
            r#"
[artifact]
kind = "component"
id = "comp-b"
version = "1.0.0"
"#,
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("api-version"));
    }

    #[test]
    fn api_version_callee_invalid_semver_range_is_error() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![http(Some("caller"), "callee", 8081)],
        );
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "1.0.0"),
            &callee_meta("comp-b", "not-a-range"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("invalid semver"));
    }

    #[test]
    fn api_version_virtual_caller_side_skipped() {
        // virtual node on the from side — not applicable, no issues
        let cfg = config(
            vec![
                virtual_node("channel", "events/placed", "topic.placed"),
                node("callee", "comp-b"),
            ],
            vec![kafka(Some("channel"), "callee")],
        );
        let meta = index_from_toml(&[&callee_meta("comp-b", "^1.0")]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(issues.iter().filter(|i| i.is_error()).count() == 0);
    }

    #[test]
    fn api_version_virtual_callee_side_skipped() {
        let cfg = config(
            vec![
                node("caller", "comp-a"),
                virtual_node("channel", "events/placed", "topic.placed"),
            ],
            vec![kafka(Some("caller"), "channel")],
        );
        let meta = index_from_toml(&[&caller_meta("comp-a", "channel", "1.0.0")]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(issues.iter().filter(|i| i.is_error()).count() == 0);
    }

    #[test]
    fn api_version_multiple_connections_mixed_results() {
        let cfg = config(
            vec![
                node("caller", "comp-a"),
                node("callee-ok", "comp-b"),
                node("callee-bad", "comp-c"),
            ],
            vec![
                http(Some("caller"), "callee-ok", 8081),
                http(Some("caller"), "callee-bad", 8082),
            ],
        );
        let meta = index_from_toml(&[
            &format!(r#"
[artifact]
kind = "component"
id = "comp-a"
version = "1.0.0"
api-version = "1.0.0"

[api-dependencies]
calls = [
  {{ id = "comp-b", version = "1.0.0" }},
  {{ id = "comp-c", version = "2.0.0" }},
]
"#),
            &callee_meta("comp-b", "^1.0"),
            &callee_meta("comp-c", "^1.0"),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues.iter().any(|i| i.message.contains("comp-c")));
    }

    // ── check_timeout_capability ──────────────────────────────────────────────

    fn transport_meta(id: &str, native_call_timeout: bool, externally_interruptible: bool) -> String {
        format!(r#"
[artifact]
kind = "transport"
id = "{id}"
version = "0.1.0"

[transport]
type = "{id}"

[transport.capabilities]
native-call-timeout = {native_call_timeout}
externally-interruptible = {externally_interruptible}
"#)
    }

    fn fs_meta(id: &str, supports_external_timeout: bool) -> String {
        format!(r#"
[artifact]
kind = "failure-semantics"
id = "{id}"
version = "0.1.0"

[failure-semantics.capabilities]
supports-external-timeout = {supports_external_timeout}
"#)
    }

    #[test]
    fn timeout_no_failure_semantics_block_skipped() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![http(Some("a"), "b", 8081)],
        );
        let meta = index_from_toml(&[&transport_meta("http", true, true)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn timeout_no_timeout_field_skipped() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                None, false, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", true, true),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn timeout_direct_connection_skipped() {
        let mut conn = direct("a", "b");
        conn.failure_semantics = Some(itara_config::FailureSemanticsEntry {
            id: "built-in".into(),
            timeout: Some("2s".into()),
            handle_timeout: true,
            absolute_timeout: None,
            max_retry: None,
            params: Default::default(),
        });
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn],
        );
        let meta = index_from_toml(&[
            &transport_meta("direct", true, true),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn timeout_external_connection_skipped() {
        let mut conn = http(None, "b", 8080);
        conn.failure_semantics = Some(itara_config::FailureSemanticsEntry {
            id: "built-in".into(),
            timeout: Some("2s".into()),
            handle_timeout: false,
            absolute_timeout: None,
            max_retry: None,
            params: Default::default(),
        });
        let cfg = config(
            vec![node("b", "comp-b")],
            vec![conn],
        );
        let meta = index_from_toml(&[&transport_meta("http", true, true)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn timeout_transport_enforces_natively_and_capable() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", true, "built-in",
                Some("2s"), false, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", true, true),
            &fs_meta("built-in", false),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn timeout_transport_enforces_natively_but_not_capable_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", true, "built-in",
                Some("2s"), false, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", false, true),
            &fs_meta("built-in", false),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("native-call-timeout"));
    }

    #[test]
    fn timeout_transport_enforces_natively_metadata_missing_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", true, "built-in",
                Some("2s"), false, None)],
        );
        let meta = index_from_toml(&[&fs_meta("built-in", false)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("http"));
    }

    #[test]
    fn timeout_fs_enforces_externally_and_capable() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", false, true),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn timeout_fs_enforces_externally_but_not_capable_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", false, true),
            &fs_meta("built-in", false),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("supports-external-timeout"));
    }

    #[test]
    fn timeout_fs_enforces_externally_fs_metadata_missing_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[&transport_meta("http", false, true)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("built-in"));
    }

    #[test]
    fn timeout_fs_enforces_externally_transport_not_interruptible_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", false, false),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("externally-interruptible"));
    }

    #[test]
    fn timeout_neither_side_enforces_is_warning() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), false, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", true, true),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 0);
        assert_eq!(issues.iter().filter(|i| !i.is_error()).count(), 1);
        assert!(issues[0].message.contains("nothing will act on it"));
    }

    #[test]
    fn timeout_both_sides_enforce_is_warning() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", true, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", true, true),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 0);
        assert!(issues.iter().any(|i| !i.is_error() && i.message.contains("both")));
    }

    #[test]
    fn timeout_absolute_timeout_fs_supports_external() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                None, false, Some("10s"))],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", true, true),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn timeout_absolute_timeout_fs_not_capable_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                None, false, Some("10s"))],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", true, true),
            &fs_meta("built-in", false),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("absolute timeout"));
    }

    #[test]
    fn timeout_absolute_timeout_fs_metadata_missing_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                None, false, Some("10s"))],
        );
        let meta = index_from_toml(&[&transport_meta("http", true, true)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("built-in"));
    }

    #[test]
    fn timeout_multiple_violations_on_one_connection() {
        // transport handle_timeout = true but not capable AND
        // fs handle_timeout = true but not capable — two errors
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", true, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", false, true),
            &fs_meta("built-in", false),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["timeout-capability"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.iter().filter(|i| i.is_error()).count() >= 2);
    }

    // ── check_transport_interrupt_safety ──────────────────────────────────────

    #[test]
    fn interrupt_safety_no_failure_semantics_skipped() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![http(Some("a"), "b", 8081)],
        );
        let meta = index_from_toml(&[&transport_meta("http", true, false)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn interrupt_safety_fs_handle_timeout_false_skipped() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), false, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", true, false),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn interrupt_safety_direct_connection_skipped() {
        let mut conn = direct("a", "b");
        conn.failure_semantics = Some(itara_config::FailureSemanticsEntry {
            id: "built-in".into(),
            timeout: Some("2s".into()),
            handle_timeout: true,
            absolute_timeout: None,
            max_retry: None,
            params: Default::default(),
        });
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn],
        );
        let meta = index_from_toml(&[
            &transport_meta("direct", true, false),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn interrupt_safety_external_connection_skipped() {
        let mut conn = http(None, "b", 8080);
        conn.failure_semantics = Some(itara_config::FailureSemanticsEntry {
            id: "built-in".into(),
            timeout: Some("2s".into()),
            handle_timeout: true,
            absolute_timeout: None,
            max_retry: None,
            params: Default::default(),
        });
        let cfg = config(vec![node("b", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &transport_meta("http", true, false),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn interrupt_safety_transport_interruptible_clean() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", false, true),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn interrupt_safety_transport_not_interruptible_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            &transport_meta("http", false, false),
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("externally-interruptible"));
        assert!(issues[0].message.contains("http"));
    }

    #[test]
    fn interrupt_safety_transport_metadata_missing_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[&fs_meta("built-in", true)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("http"));
    }

    #[test]
    fn interrupt_safety_transport_missing_transport_section_is_error() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_fs(Some("a"), "b", "http", false, "built-in",
                Some("2s"), true, None)],
        );
        let meta = index_from_toml(&[
            r#"
[artifact]
kind = "transport"
id = "http"
version = "0.1.0"
"#,
            &fs_meta("built-in", true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["transport-interrupt-safety"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("transport"));
    }
 
    // ── Multiple issues ───────────────────────────────────────────────────────
 
    #[test]
    fn multiple_independent_errors_all_reported() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("orphan", "co")],
            vec![
                http(None, "a", 8080),
                http(Some("a"), "b", 8081),
                http(Some("b"), "ghost", 8082), // undeclared to
                http(Some("a"), "a", 8083),      // self-connection
            ],
        );
        // Expect: 1 orphaned node + 1 undeclared connection + 1 self-connection = 3
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert_eq!(errors(&issues).len(), 3);
    }
 
    // ── summary_line ──────────────────────────────────────────────────────────
 
    #[test]
    fn summary_one_error() {
        assert_eq!(summary_line(1, 0), "1 error");
    }
 
    #[test]
    fn summary_many_errors() {
        assert_eq!(summary_line(3, 0), "3 errors");
    }
 
    #[test]
    fn summary_one_warning() {
        assert_eq!(summary_line(0, 1), "1 warning");
    }
 
    #[test]
    fn summary_errors_and_warnings() {
        assert_eq!(summary_line(2, 1), "2 errors, 1 warning");
    }
}
