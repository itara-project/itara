use clap;use itara_config::{
        WiringConfig, ConnectionEntry, parse_file, ResolvedCallee
    };
use itara_libdir::MetadataIndex;
use std::collections::{HashMap, HashSet};

use crate::output::{Issue, TICK, CROSS, blank};

const VALID_CHECKS: &[&str] = &[
    "orphaned-nodes",
    "orphaned-connections",
    "duplicate-ids",
    "connection-id-uniqueness",
    "self-connections",
    "direct-external-conflict",
    "outbound-ambiguity",
    "unknown-transport",
    "unknown-authentication",
    "unknown-authorization",
    "virtual-no-producers",
    "virtual-no-consumers",
    "virtual-transport-mismatch",
    "api-version-compatibility",
    "timeout-capability",
    "transport-interrupt-safety",
    "serializer-compatibility",
    "caller-callee-plugin-incompatibility",
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
    ///               connection-id-uniqueness, self-connections,
    ///               direct-external-conflict, outbound-ambiguity,
    ///               unknown-transport, unknown-authentication, unknown-authorization,
    ///               virtual-no-producers,
    ///               virtual-no-consumers, virtual-transport-mismatch,
    ///               api-version-compatibility,
    ///               timeout-capability, transport-interrupt-safety,
    ///               serializer-compatibility, caller-callee-plugin-incompatibility
    #[arg(long, value_name = "check", conflicts_with = "only")]
    pub skip: Vec<String>,
    /// Run only the specified check. Can be repeated. Mutually exclusive with --skip.
    /// Valid values: orphaned-nodes, orphaned-connections, duplicate-ids,
    ///               connection-id-uniqueness, self-connections,
    ///               direct-external-conflict, outbound-ambiguity,
    ///               unknown-transport, unknown-authentication, unknown-authorization,
    ///               virtual-no-producers,
    ///               virtual-no-consumers, virtual-transport-mismatch,
    ///               api-version-compatibility,
    ///               timeout-capability, transport-interrupt-safety,
    ///               serializer-compatibility, caller-callee-plugin-incompatibility
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
                "no --metadata-dir provided — API version, known transport, \
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
    if filter.should_run("connection-id-uniqueness") { check_connection_id_uniqueness(config, &mut issues); }
    if filter.should_run("self-connections")      { check_self_connections(config, &mut issues); }
    if filter.should_run("direct-external-conflict") { check_direct_external_conflict(config, &mut issues); }
    if filter.should_run("outbound-ambiguity")    { check_outbound_ambiguity(config, &mut issues); }
    if filter.should_run("orphaned-nodes")        { check_orphaned_nodes(config, &mut issues); }
    if filter.should_run("orphaned-connections")  { check_orphaned_connections(config, &mut issues); }
    if filter.should_run("virtual-no-producers")      { check_virtual_no_producers(config, &mut issues); }
    if filter.should_run("virtual-no-consumers")      { check_virtual_no_consumers(config, &mut issues); }
    if filter.should_run("virtual-transport-mismatch"){ check_virtual_transport_mismatch(config, &mut issues); }

    if let Some(meta) = meta {
        if filter.should_run("unknown-transport")          { check_unknown_transports(config, meta, &mut issues); }
        if filter.should_run("unknown-authentication")      { check_unknown_authentication(config, meta, &mut issues); }
        if filter.should_run("unknown-authorization")       { check_unknown_authorization(config, meta, &mut issues); }
        if filter.should_run("api-version-compatibility")  { check_api_version_compatibility(config, meta, &mut issues); }
        if filter.should_run("timeout-capability")         { check_timeout_capability(config, meta, &mut issues); }
        if filter.should_run("transport-interrupt-safety") { check_transport_interrupt_safety(config, meta, &mut issues); }
        if filter.should_run("serializer-compatibility")   { check_serializer_compatibility(config, meta, &mut issues); }
        if filter.should_run("caller-callee-plugin-incompatibility") { check_caller_callee_plugin_incompatibility(config, meta, &mut issues); }
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

fn check_connection_id_uniqueness(config: &WiringConfig, issues: &mut Vec<Issue>) {
    let mut seen: HashMap<&str, usize> = HashMap::new();
    for conn in &config.connections {
        *seen.entry(conn.id.as_str()).or_default() += 1;
    }
    // Sort for deterministic output order.
    let mut duplicates: Vec<(&str, usize)> = seen.into_iter()
        .filter(|(_, count)| *count > 1)
        .collect();
    duplicates.sort_by_key(|(id, _)| *id);
    for (id, count) in duplicates {
        issues.push(Issue::error(format!(
            "connection id '{}' is declared {} times — connection ids must be unique \
             across the entire wiring configuration",
            id, count
        )));
    }
}
 
fn check_self_connections(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        if let Some(from) = conn.caller_node_id() {
            if !from.trim().is_empty() && from == conn.callee_node_id() {
                issues.push(Issue::error(format!(
                    "connection from '{}' to '{}' is a self-connection",
                    from, conn.callee_node_id()
                )));
            }
        }
    }
}

fn check_direct_external_conflict(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        if conn.is_direct() && conn.is_external() {
            issues.push(Issue::error(format!(
                "connection '{}' to '{}' declares transport 'direct' but has no 'from' \
                 — a direct connection requires an in-process caller, which contradicts \
                 being external",
                conn.id, conn.callee_node_id()
            )));
        }
    }
}
 
fn check_orphaned_nodes(config: &WiringConfig, issues: &mut Vec<Issue>) {
    let referenced: HashSet<&str> = config.connections.iter()
        .flat_map(|c| {
            let mut ids: Vec<&str> = vec![c.callee_node_id()];
            if let Some(from) = c.caller_node_id() {
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
        if !declared.contains(conn.callee_node_id()) {
            issues.push(Issue::error(format!(
                "connection references undeclared node '{}'",
                conn.callee_node_id()
            )));
        }
        if let Some(from) = conn.caller_node_id() {
            if !from.trim().is_empty() && !declared.contains(from) {
                issues.push(Issue::error(format!(
                    "connection references undeclared node '{}'",
                    from
                )));
            }
        }
    }
}

fn check_outbound_ambiguity(config: &WiringConfig, issues: &mut Vec<Issue>) {
    // Group outbound connections by their calling node.
    let mut by_from: HashMap<&str, Vec<&ConnectionEntry>> = HashMap::new();
    for conn in &config.connections {
        if let Some(from) = conn.caller_node_id() {
            if !from.trim().is_empty() {
                by_from.entry(from).or_default().push(conn);
            }
        }
    }

    let mut froms: Vec<&str> = by_from.keys().copied().collect();
    froms.sort();

    for from in froms {
        // Map each target component id to every node id that resolves to it.
        let mut targets_by_component: HashMap<&str, Vec<&str>> = HashMap::new();
        for conn in &by_from[from] {
            if let Some(component) = config.component_of_node(&conn.callee_node_id()) {
                let targets = targets_by_component.entry(component).or_default();
                if !targets.contains(&conn.callee_node_id()) {
                    targets.push(conn.callee_node_id());
                }
            }
        }

        let mut ambiguous: Vec<(&str, Vec<&str>)> = targets_by_component.into_iter()
            .filter(|(_, targets)| targets.len() > 1)
            .collect();
        ambiguous.sort_by_key(|(component, _)| *component);

        for (component, mut targets) in ambiguous {
            targets.sort();
            issues.push(Issue::error(format!(
                "node '{}' has outbound connections to '{}' — all resolve to component \
                 '{}', so a call from '{}' for component '{}' cannot be resolved to a \
                 single target",
                from, targets.join("', '"), component, from, component
            )));
        }
    }
}
 
fn check_unknown_transports(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        if let Some(callee) = conn.resolve_callee() {
            let t = &callee.transport.id;
            if !t.eq_ignore_ascii_case("direct") && meta.transport(t).is_none() {
                issues.push(Issue::error(format!(
                    "connection to '{}' has unknown transport type '{}' \
                     — no matching transport metadata found",
                    conn.callee_node_id(), t,
                )));
            }
        }
        if let Some(caller) = conn.resolve_caller() {
            let t = &caller.transport.id;
            if !t.eq_ignore_ascii_case("direct") && meta.transport(t).is_none() {
                issues.push(Issue::error(format!(
                    "connection to '{}' has unknown transport type '{}' \
                     — no matching transport metadata found",
                    conn.callee_node_id(), t,
                )));
            }
        }
    }
}

fn check_unknown_authentication(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        if let Some(callee) = conn.resolve_callee() {
            let a = callee.authentication_id();
            if !a.eq_ignore_ascii_case("noop") && meta.authentication(a).is_none() {
                issues.push(Issue::error(format!(
                    "connection to '{}' has unknown authentication type '{}' \
                     — no matching authentication metadata found",
                    conn.callee_node_id(), a,
                )));
            }
        }
        if let Some(caller) = conn.resolve_caller() {
            let a = caller.authentication_id();
            if !a.eq_ignore_ascii_case("noop") && meta.authentication(a).is_none() {
                issues.push(Issue::error(format!(
                    "connection to '{}' has unknown authentication type '{}' \
                     — no matching authentication metadata found",
                    conn.callee_node_id(), a,
                )));
            }
        }
    }
}

fn check_unknown_authorization(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        let Some(callee) = conn.resolve_callee() else { continue };
        let a = callee.authorization_id();
        if a.eq_ignore_ascii_case("noop") {
            continue; // built-in default, no metadata artifact
        }
        if meta.authorization(a).is_none() {
            issues.push(Issue::error(format!(
                "connection to '{}' has unknown authorization type '{}' \
                 — no matching authorization metadata found",
                conn.callee_node_id(), a,
            )));
        }
    }
}

fn check_virtual_no_producers(config: &WiringConfig, issues: &mut Vec<Issue>) {
    for vn in config.virtual_nodes() {
        let has_producer = config.connections.iter()
            .any(|c| c.callee_node_id() == vn.id);
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
            .any(|c| c.caller_node_id() == Some(vn.id.as_str()));
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
        let mut transports: std::collections::HashSet<String> = std::collections::HashSet::new();
        for c in &config.connections {
            // The virtual node as callee (producer side): what it's received on.
            if c.callee_node_id() == vn.id {
                if let Some(callee) = c.resolve_callee() {
                    transports.insert(callee.transport.id.to_ascii_lowercase());
                }
            }
            // The virtual node as caller (consumer side): what it's sent over.
            if c.caller_node_id() == Some(vn.id.as_str()) {
                if let Some(caller) = c.resolve_caller() {
                    transports.insert(caller.transport.id.to_ascii_lowercase());
                }
            }
        }

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

        let from_id = match conn.caller_node_id() {
            Some(f) => f,
            None => continue,
        };

        // Resolve caller component id from its node.
        let caller_component = match config.component_of_node(from_id) {
            Some(c) => c,
            None => continue, // virtual node on the caller side — not applicable
        };

        // Resolve callee component id from its node.
        let callee_component = match config.component_of_node(conn.callee_node_id()) {
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
                    from_id, conn.callee_node_id(), caller_component
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
                    from_id, conn.callee_node_id(), caller_component
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
                    from_id, conn.callee_node_id(), caller_component, callee_component
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
                    from_id, conn.callee_node_id(), caller_component, dep.version, callee_component, e
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
                    from_id, conn.callee_node_id(), callee_component
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
                from_id, conn.callee_node_id(), callee_component
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
                    from_id, conn.callee_node_id(), callee_component, api_version_str, e
                )));
                continue;
            }
        };

        if !callee_req.matches(&caller_version) {
            issues.push(Issue::error(format!(
                "connection '{}' → '{}': caller component '{}' was built against \
                 API '{}' version '{}', but callee component '{}' implements \
                 api-version '{}' — versions are incompatible",
                from_id, conn.callee_node_id(),
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

        // failureSemantics is caller-side only, and transport.handleTimeout
        // here is checked against that same caller's failure-semantics
        // config — both come from the caller's resolved side.
        let Some(caller) = conn.resolve_caller() else { continue };

        let fs = match caller.failure_semantics {
            Some(fs) => fs,
            None => continue, // no failure semantics block — no timeout to check
        };

        let has_timeout       = fs.timeout.is_some();
        let has_abs_timeout   = fs.absolute_timeout.is_some();
        let fs_handle_timeout = fs.handle_timeout;
        let t_handle_timeout  = caller.transport.handle_timeout;

        if !has_timeout && !has_abs_timeout {
            continue; // nothing to check
        }

        let conn_label = format!(
            "'{}' → '{}'",
            conn.caller_node_id().unwrap_or("?"),
            conn.callee_node_id()
        );

        // Look up transport metadata if we need capability checks.
        let transport_caps = meta.transport(&caller.transport.id)
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
                            conn_label, caller.transport.id
                        )));
                    }
                    Some(caps) if !caps.native_call_timeout => {
                        issues.push(Issue::error(format!(
                            "connection {}: transport '{}' is configured to enforce \
                             timeout natively (transport.handleTimeout = true) but \
                             its metadata declares native-call-timeout = false",
                            conn_label, caller.transport.id
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
                            conn_label, fs.id, caller.transport.id
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

        let Some(caller) = conn.resolve_caller() else { continue };

        let fs = match &caller.failure_semantics {
            Some(fs) => fs,
            None => continue,
        };

        if !fs.handle_timeout {
            continue; // external timeout enforcement not configured
        }

        let conn_label = format!(
            "'{}' → '{}'",
            conn.caller_node_id().unwrap_or("?"),
            conn.callee_node_id()
        );

        let transport_meta = match meta.transport(&caller.transport.id) {
            None => {
                issues.push(Issue::error(format!(
                    "connection {}: external timeout enforcement is configured but \
                     no metadata was found for transport '{}' \
                     — cannot verify interrupt safety",
                    conn_label, caller.transport.id
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
                    conn_label, caller.transport.id
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
                conn_label, caller.transport.id
            )));
        }
    }
}

/// Flags a connection whose configured serializer is not confirmed
/// compatible with the callee API — WARNING severity, never ERROR, per
/// the same non-blocking posture message-format compatibility already
/// has (spec §8.6): the agent does not enforce this either, so the CLI
/// says "we don't know this is safe," not "this is broken."
///
/// This check only applies at all when the API artifact declares at least
/// one of a non-empty [contract] message-format or a non-empty
/// [serializers] supported list. An API declaring neither has no
/// compatibility question to evaluate — this is the common case for
/// plain-DTO APIs with no serializer restriction (e.g. most Java
/// runtime-plugin-style components), and produces no issue at all, not
/// even a downgraded warning (spec §8.6).
///
/// Where the check does apply, two independent paths make a connection's
/// serializer considered compatible with its callee API — either is
/// sufficient:
///
///   Explicit path: the serializer's own artifact.id/artifact.version is
///   covered by an entry in the callee API's [serializers] supported list.
///
///   Capability path: the callee API's non-empty [contract] message-format
///   appears in the serializer's [serializer.capabilities] message-formats.
///
/// Operates on `.itara` metadata directly (via MetadataIndex), so it
/// produces the same result regardless of which language produced either
/// artifact.
///
/// Skips direct connections (no serializer applies) and connections whose
/// callee does not resolve to a component (virtual/event nodes are not
/// request/response API artifacts here). Unlike api-version-compatibility,
/// this check does not skip external connections — it only depends on the
/// callee side, which is checkable regardless of who the caller is.
fn check_serializer_compatibility(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    use semver::{Version, VersionReq};

    for conn in &config.connections {
        if conn.is_direct() {
            continue; // no serializer applies to a direct connection
        }

        let callee_component = match config.component_of_node(conn.callee_node_id()) {
            Some(c) => c,
            None => continue, // virtual/event node — not an API artifact
        };

        let caller_label = conn.caller_node_id().unwrap_or("(external)");

        // In real usage validate() guarantees a non-direct connection has a
        // serializer block with a non-empty id — but this function accepts
        // any WiringConfig, and a hand-built one (as in this file's own
        // tests) can bypass validate() entirely. Never panic on that; skip
        // with a warning instead, same as any other "can't check this"
        // outcome below.
        let callee: ResolvedCallee = match conn.resolve_callee() {
            Some(c) => c,
            None => continue, // no transport resolvable — nothing coherent to check
        };
        let serializer_id = match callee.serializer {
            Some(s) => &s.id,
            None => {
                issues.push(Issue::warning(format!(
                    "connection '{}' -> '{}': no serializer configured for this connection \
                     — serializer-compatibility check skipped for this connection",
                    caller_label, conn.callee_node_id()
                )));
                continue;
            }
        };

        let api_meta = match meta.api(callee_component) {
            Some(m) => m,
            None => {
                issues.push(Issue::warning(format!(
                    "connection '{}' -> '{}': no API metadata found for '{}' \
                     — serializer-compatibility check skipped for this connection",
                    caller_label, conn.callee_node_id(), callee_component
                )));
                continue;
            }
        };

        // Spec §8.6: this check only applies when the API artifact declares
        // at least one of message-format (non-empty) or [serializers]
        // supported (non-empty). A plain-DTO API declaring neither has no
        // compatibility question to evaluate — not even a missing-metadata
        // warning for the configured serializer, since there is nothing
        // this check needs that metadata for in that case. This is the
        // expected, common case for APIs with no serializer restriction at
        // all (e.g. most Java runtime-plugin-style components), so it must
        // stay silent, not just downgraded to a warning.
        let message_format = api_meta.contract.as_ref()
            .map(|c| c.message_format.as_str())
            .unwrap_or("");
        let declares_supported = api_meta.serializers.as_ref()
            .map(|s| !s.supported.is_empty())
            .unwrap_or(false);

        if message_format.is_empty() && !declares_supported {
            continue;
        }

        let serializer_meta = match meta.serializer(serializer_id) {
            Some(m) => m,
            None => {
                issues.push(Issue::warning(format!(
                    "connection '{}' -> '{}': no metadata found for serializer '{}' \
                     — serializer-compatibility check skipped for this connection",
                    caller_label, conn.callee_node_id(), serializer_id
                )));
                continue;
            }
        };

        // Explicit path.
        let explicit_match = api_meta.serializers.as_ref()
            .map(|s| s.supported.iter().any(|entry| {
                if !entry.id.eq_ignore_ascii_case(serializer_id) {
                    return false;
                }
                match (VersionReq::parse(&entry.version), Version::parse(&serializer_meta.artifact.version)) {
                    (Ok(req), Ok(v)) => req.matches(&v),
                    _ => false, // unparseable range/version — not a confirmed match
                }
            }))
            .unwrap_or(false);

        if explicit_match {
            continue;
        }

        // Capability path.
        let message_format = api_meta.contract.as_ref()
            .map(|c| c.message_format.as_str())
            .unwrap_or("");

        let capability_match = !message_format.is_empty()
            && serializer_meta.serializer.as_ref()
                .map(|s| s.capabilities.message_formats.iter()
                    .any(|f| f.eq_ignore_ascii_case(message_format)))
                .unwrap_or(false);

        if capability_match {
            continue;
        }

        issues.push(Issue::warning(format!(
            "connection '{}' -> '{}': serializer '{}' is not confirmed compatible with API '{}' \
             — not listed in the API's [serializers] supported entries, and its declared \
             message format (if any) is not in the serializer's \
             [serializer.capabilities] message-formats",
            caller_label, conn.callee_node_id(), serializer_id, callee_component
        )));
    }
}

/// Compares the caller's and the callee's resolved plugin for each kind
/// that can be declared independently on both sides — transport,
/// serializer, and authentication. failureSemantics and authorization are
/// single-sided by construction, so there is nothing on the other side to
/// compare them against.
///
/// Interim rule from the design document: identical ids are compatible;
/// different ids are compatible if their metadata-declared types match;
/// otherwise it's an error. This is deliberately not a richer model —
/// a fuller compatibility scheme is expected later.
///
/// Applies to direct connections too — only authentication is meaningful
/// there, since a direct connection's transport is 'direct' on both sides
/// by construction (validate() already enforces that) and it never has a
/// serializer at all.
fn check_caller_callee_plugin_incompatibility(config: &WiringConfig, meta: &MetadataIndex, issues: &mut Vec<Issue>) {
    for conn in &config.connections {
        let (Some(caller), Some(callee)) = (conn.resolve_caller(), conn.resolve_callee()) else {
            continue; // external — nothing on the other side to compare against
        };

        if !conn.is_direct() {
            let caller_transport = &caller.transport.id;
            let callee_transport = &callee.transport.id;
            let caller_type = meta.transport(caller_transport)
                .and_then(|m| m.transport.as_ref())
                .and_then(|t| t.transport_type.as_deref());
            let callee_type = meta.transport(callee_transport)
                .and_then(|m| m.transport.as_ref())
                .and_then(|t| t.transport_type.as_deref());
            check_plugin_compatibility(conn, "transport", caller_transport, callee_transport, caller_type, callee_type, issues);

            // validate() guarantees a non-direct connection resolves a
            // serializer on both sides, so these unwraps are safe here.
            let caller_serializer = &caller.serializer.expect("non-direct caller resolves a serializer").id;
            let callee_serializer = &callee.serializer.expect("non-direct callee resolves a serializer").id;
            let caller_type = meta.serializer(caller_serializer)
                .and_then(|m| m.serializer.as_ref())
                .and_then(|s| s.serializer_type.as_deref());
            let callee_type = meta.serializer(callee_serializer)
                .and_then(|m| m.serializer.as_ref())
                .and_then(|s| s.serializer_type.as_deref());
            check_plugin_compatibility(conn, "serializer", caller_serializer, callee_serializer, caller_type, callee_type, issues);
        }

        let caller_auth = caller.authentication_id();
        let callee_auth = callee.authentication_id();
        // "noop" is the built-in default, not a real deployable artifact —
        // same posture as check_unknown_authentication, which skips it too.
        if !caller_auth.eq_ignore_ascii_case("noop") && !callee_auth.eq_ignore_ascii_case("noop") {
            let caller_type = meta.authentication(caller_auth)
                .and_then(|m| m.authentication.as_ref())
                .and_then(|a| a.authentication_type.as_deref());
            let callee_type = meta.authentication(callee_auth)
                .and_then(|m| m.authentication.as_ref())
                .and_then(|a| a.authentication_type.as_deref());
            check_plugin_compatibility(conn, "authentication", caller_auth, callee_auth, caller_type, callee_type, issues);
        }
    }
}

/// The compatibility rule itself, for one plugin kind on one connection:
/// same id is always compatible; different ids are compatible if their
/// (already looked-up) declared types match, a warning if either type is
/// unknown, and an error otherwise. Takes the ids and types as plain data
/// — the lookup itself is the caller's job, since it differs per plugin
/// kind (transport/serializer/authentication metadata, three different
/// MetadataIndex methods and result shapes) and isn't worth abstracting
/// over for three call sites.
fn check_plugin_compatibility(
    conn: &ConnectionEntry,
    kind: &str,
    caller_id: &str,
    callee_id: &str,
    caller_type: Option<&str>,
    callee_type: Option<&str>,
    issues: &mut Vec<Issue>,
) {
    if caller_id == callee_id {
        return;
    }

    match (caller_type, callee_type) {
        (Some(ct), Some(lt)) if ct == lt => {} // different ids, same type — compatible
        (Some(_), Some(_)) => {
            issues.push(Issue::error(format!(
                "connection '{}' -> '{}': incompatible {} — caller uses '{}', callee uses '{}', \
                 and their declared types do not match",
                conn.caller_node_id().unwrap_or("?"), conn.callee_node_id(), kind, caller_id, callee_id
            )));
        }
        _ => {
            issues.push(Issue::warning(format!(
                "connection '{}' -> '{}': cannot confirm {} compatibility between caller '{}' \
                 and callee '{}' — metadata for one or both is missing or has no declared type",
                conn.caller_node_id().unwrap_or("?"), conn.callee_node_id(), kind, caller_id, callee_id
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
    use itara_config::{WiringConfig, Node, ComponentNode, VirtualNode, ConnectionEntry,
        SerializerEntry, ConnectionSide, TransportEntry};
 
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

    fn default_serializer() -> Option<SerializerEntry> {
        Some(SerializerEntry { id: "json".into(), params: Default::default() })
    }

    /// Generates a fresh, unique connection id for test-built connections.
    /// The specific value is never asserted on — only that `id` is present
    /// and structurally valid — so a counter keeps every helper call site
    /// below unchanged while still giving every connection a distinct id.
    fn next_conn_id() -> String {
        use std::sync::atomic::{AtomicUsize, Ordering};
        static COUNTER: AtomicUsize = AtomicUsize::new(0);
        format!("test-conn-{}", COUNTER.fetch_add(1, Ordering::Relaxed))
    }
 
    /// Builds a bare ConnectionSide with just a node id — the shape every
    /// helper below needs for a caller or callee block that declares no
    /// plugin of its own, everything falling back to the connection level.
    fn side(node_id: &str) -> ConnectionSide {
        ConnectionSide {
            node_id: node_id.into(),
            transport: None,
            serializer: None,
            failure_semantics: None,
            authentication: None,
            authorization: None,
        }
    }

    fn http(from: Option<&str>, to: &str, port: u16) -> ConnectionEntry {
        ConnectionEntry::for_testing(
            next_conn_id(),
            side(to),
            from.map(side),
            Some(transport_entry("http", Some(port))),
            default_serializer(),
            None,
        )
    }
 
    fn direct(from: &str, to: &str) -> ConnectionEntry {
        ConnectionEntry::for_testing(
            next_conn_id(),
            side(to),
            Some(side(from)),
            Some(transport_entry("direct", None)),
            None,
            None,
        )
    }

    fn kafka(from: Option<&str>, to: &str) -> ConnectionEntry {
        ConnectionEntry::for_testing(
            next_conn_id(),
            side(to),
            from.map(side),
            Some(transport_entry("kafka", None)),
            default_serializer(),
            None,
        )
    }
 
    fn conn_with_transport(from: Option<&str>, to: &str, transport: &str, port: u16) -> ConnectionEntry {
        ConnectionEntry::for_testing(
            next_conn_id(),
            side(to),
            from.map(side),
            Some(transport_entry(transport, Some(port))),
            default_serializer(),
            None,
        )
    }

    /// Overrides the serializer id on an already-built connection. This sets
    /// the connection-level serializer, which resolve_callee()/resolve_caller()
    /// both fall back to when a side declares none of its own — exactly what
    /// every existing caller of this helper needs, since none of them declare
    /// a side-specific serializer.
    fn with_serializer_id(mut conn: ConnectionEntry, id: &str) -> ConnectionEntry {
        conn.serializer = Some(SerializerEntry { id: id.into(), params: Default::default() });
        conn
    }

    /// Overrides the authentication id on an already-built connection, at
    /// the connection level — same fallback reasoning as with_serializer_id.
    fn with_authentication_id(mut conn: ConnectionEntry, id: &str) -> ConnectionEntry {
        conn.authentication = Some(itara_config::AuthenticationEntry { id: id.into(), params: Default::default() });
        conn
    }

    /// Overrides the authorization id on an already-built connection.
    /// Authorization is callee-side only now, so this sets it on the callee
    /// block directly — there is no connection-level placement to fall back
    /// from.
    fn with_authorization_id(mut conn: ConnectionEntry, id: &str) -> ConnectionEntry {
        conn.callee.authorization = Some(itara_config::AuthorizationEntry { id: id.into(), params: Default::default() });
        conn
    }

    /// Overrides the id on an already-built connection.
    /// Used by tests that need to force a specific (often colliding) id.
    fn with_id(mut conn: ConnectionEntry, id: &str) -> ConnectionEntry {
        conn.id = id.into();
        conn
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

    // ── Connection id uniqueness ──────────────────────────────────────────────

    #[test]
    fn duplicate_connection_id_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("c", "cc")],
            vec![
                with_id(http(None, "a", 8080), "shared"),
                with_id(http(Some("a"), "b", 8081), "shared"),
                http(Some("b"), "c", 8082),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        let matches: Vec<_> = issues.iter()
            .filter(|i| i.message.contains("connection id 'shared'"))
            .collect();
        assert_eq!(matches.len(), 1);
        assert!(matches[0].is_error());
        assert!(matches[0].message.contains("declared 2 times"));
    }

    #[test]
    fn multiple_duplicate_connection_ids_all_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("c", "cc"), node("d", "cd")],
            vec![
                with_id(http(None, "a", 8080), "x"),
                with_id(http(Some("a"), "b", 8081), "x"),
                with_id(http(Some("b"), "c", 8082), "y"),
                with_id(http(Some("c"), "d", 8083), "y"),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        let matches: Vec<_> = issues.iter()
            .filter(|i| i.message.contains("connection id"))
            .collect();
        assert_eq!(matches.len(), 2);
    }

    #[test]
    fn unique_connection_ids_not_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["connection-id-uniqueness"].iter().map(|s| s.to_string()).collect()
        ), None);
        assert!(issues.is_empty());
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

    // ── Direct/external conflict ──────────────────────────────────────────────

    #[test]
    fn direct_connection_with_no_from_flagged() {
        let cfg = config(
            vec![node("a", "ca")],
            vec![ConnectionEntry::for_testing(
                next_conn_id(),
                side("a"),
                None,
                Some(transport_entry("direct", None)),
                None,
                None,
            )],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        let matches: Vec<_> = issues.iter()
            .filter(|i| i.message.contains("direct") && i.message.contains("no 'from'"))
            .collect();
        assert_eq!(matches.len(), 1);
        assert!(matches[0].is_error());
    }

    #[test]
    fn direct_connection_with_from_not_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), direct("a", "b")],
        );
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["direct-external-conflict"].iter().map(|s| s.to_string()).collect()
        ), None);
        assert!(issues.is_empty());
    }

    #[test]
    fn external_non_direct_connection_not_flagged() {
        let cfg = config(
            vec![node("a", "ca")],
            vec![http(None, "a", 8080)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["direct-external-conflict"].iter().map(|s| s.to_string()).collect()
        ), None);
        assert!(issues.is_empty());
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

    // ── Outbound ambiguity ────────────────────────────────────────────────────

    #[test]
    fn outbound_ambiguity_flagged_when_two_nodes_share_a_component() {
        let cfg = config(
            vec![
                node("gateway", "gateway-comp"),
                node("calcNodeA", "calculator"),
                node("calcNodeB", "calculator"),
            ],
            vec![
                http(None, "gateway", 8080),
                http(Some("gateway"), "calcNodeA", 8081),
                http(Some("gateway"), "calcNodeB", 8082),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        let matches: Vec<_> = issues.iter()
            .filter(|i| i.message.contains("'calculator'"))
            .collect();
        assert_eq!(matches.len(), 1);
        assert!(matches[0].is_error());
        assert!(matches[0].message.contains("calcNodeA"));
        assert!(matches[0].message.contains("calcNodeB"));
    }

    #[test]
    fn outbound_ambiguity_not_flagged_for_distinct_components() {
        let cfg = config(
            vec![
                node("gateway", "gateway-comp"),
                node("calcNode", "calculator"),
                node("notifierNode", "notifier"),
            ],
            vec![
                http(None, "gateway", 8080),
                http(Some("gateway"), "calcNode", 8081),
                http(Some("gateway"), "notifierNode", 8082),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["outbound-ambiguity"].iter().map(|s| s.to_string()).collect()
        ), None);
        assert!(issues.is_empty());
    }

    #[test]
    fn outbound_ambiguity_not_flagged_across_different_calling_nodes() {
        // Two different callers each reaching a different node of the same
        // component is fine — the ambiguity is scoped per calling node.
        let cfg = config(
            vec![
                node("callerA", "caller-a"),
                node("callerB", "caller-b"),
                node("calcNodeA", "calculator"),
                node("calcNodeB", "calculator"),
            ],
            vec![
                http(None, "callerA", 8080),
                http(None, "callerB", 8081),
                http(Some("callerA"), "calcNodeA", 8082),
                http(Some("callerB"), "calcNodeB", 8083),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["outbound-ambiguity"].iter().map(|s| s.to_string()).collect()
        ), None);
        assert!(issues.is_empty());
    }

    #[test]
    fn outbound_ambiguity_ignores_virtual_node_targets() {
        // A node calling into two virtual nodes has no component id to
        // collide with — must never be flagged.
        let cfg = config(
            vec![
                node("producerNode", "producer"),
                virtual_node("channelA", "events/a", "topic.a"),
                virtual_node("channelB", "events/b", "topic.b"),
            ],
            vec![
                kafka(Some("producerNode"), "channelA"),
                kafka(Some("producerNode"), "channelB"),
            ],
        );
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["outbound-ambiguity"].iter().map(|s| s.to_string()).collect()
        ), None);
        assert!(issues.is_empty());
    }
 
    // ── Unknown transports ────────────────────────────────────────────────────
 
    #[test]
    fn unknown_transport_flagged() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_transport(Some("a"), "b", "carrier-pigeon", 8081)],
        );
        let meta = index_from_toml(&[&transport_meta("http", true, true)]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["unknown-transport"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 2);
        assert!(issues[0].message.contains("carrier-pigeon"));
    }

    #[test]
    fn known_transports_not_flagged() {
        for transport in ["http", "kafka"] {
            let cfg = config(
                vec![node("a", "comp-a"), node("b", "comp-b")],
                vec![conn_with_transport(Some("a"), "b", transport, 8081)],
            );
            let meta = index_from_toml(&[&transport_meta(transport, true, true)]);
            let issues = collect_issues(&cfg, &CheckFilter::Only(
                ["unknown-transport"].iter().map(|s| s.to_string()).collect()
            ), Some(&meta));
            assert!(
                issues.iter().filter(|i| i.is_error()).count() == 0,
                "transport '{}' was unexpectedly flagged", transport
            );
        }
    }

    #[test]
    fn direct_transport_never_flagged_even_without_metadata() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![direct("a", "b")],
        );
        // empty metadata dir — no transport entries at all, "direct" must
        // still never be flagged
        let meta = index_from_toml(&[]);
        let issues = collect_issues(&cfg, &CheckFilter::Only(
            ["unknown-transport"].iter().map(|s| s.to_string()).collect()
        ), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 0);
    }

    #[test]
    fn unknown_transport_check_skipped_without_metadata() {
        let cfg = config(
            vec![node("a", "comp-a"), node("b", "comp-b")],
            vec![conn_with_transport(Some("a"), "b", "carrier-pigeon", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert!(issues.iter().filter(|i| i.is_error()).count() == 0);
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
                ConnectionEntry::for_testing(
                    next_conn_id(),
                    side("consumerNode"),
                    Some(side("channel")),
                    Some(transport_entry("http", Some(8081))),
                    default_serializer(),
                    None,
                ),
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

    // ── Unknown authentication ────────────────────────────────────────────────

    #[test]
    fn unknown_authentication_flagged() {
        let idx = index_from_toml(&[]);
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![with_authentication_id(http(Some("a"), "b", 8080), "shared-secret")],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&idx));
        assert!(issues.iter().any(|i| i.is_error() && i.message.contains("unknown authentication type")));
    }

    #[test]
    fn known_authentication_not_flagged() {
        let idx = index_from_toml(&[r#"
[artifact]
kind = "authentication"
id = "shared-secret"
version = "1.0.0"

[authentication]
type = "shared-secret"
"#]);
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![with_authentication_id(http(Some("a"), "b", 8080), "shared-secret")],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&idx));
        assert!(issues.iter().all(|i| !i.message.contains("unknown authentication type")));
    }

    #[test]
    fn noop_authentication_exempt_from_unknown_check() {
        let idx = index_from_toml(&[]);
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(Some("a"), "b", 8080)], // no authentication block — defaults to noop
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&idx));
        assert!(issues.iter().all(|i| !i.message.contains("unknown authentication type")));
    }

    // ── Unknown authorization ─────────────────────────────────────────────────

    #[test]
    fn unknown_authorization_flagged() {
        let idx = index_from_toml(&[]);
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![with_authorization_id(http(Some("a"), "b", 8080), "rule-table")],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&idx));
        assert!(issues.iter().any(|i| i.is_error() && i.message.contains("unknown authorization type")));
    }

    #[test]
    fn known_authorization_not_flagged() {
        let idx = index_from_toml(&[r#"
[artifact]
kind = "authorization"
id = "rule-table"
version = "1.0.0"

[authorization]
type = "rule-table"
"#]);
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![with_authorization_id(http(Some("a"), "b", 8080), "rule-table")],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&idx));
        assert!(issues.iter().all(|i| !i.message.contains("unknown authorization type")));
    }

    #[test]
    fn noop_authorization_exempt_from_unknown_check() {
        let idx = index_from_toml(&[]);
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(Some("a"), "b", 8080)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&idx));
        assert!(issues.iter().all(|i| !i.message.contains("unknown authorization type")));
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

    /// Every current caller of this helper passes from = Some(...) — failure
    /// semantics are caller-side only, so a from = None (external) input
    /// would have nowhere to put them; this helper just drops the failure
    /// semantics block in that case rather than panicking, since building
    /// that combination isn't itself invalid, only pointless.
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
        let transport_entry = TransportEntry {
            id: transport.into(),
            handle_timeout: transport_handle_timeout,
            params: Default::default(),
        };
        let fs = itara_config::FailureSemanticsEntry {
            id: fs_id.into(),
            timeout: timeout.map(Into::into),
            handle_timeout: fs_handle_timeout,
            absolute_timeout: absolute_timeout.map(Into::into),
            max_retry: None,
            params: Default::default(),
        };
        let caller = from.map(|f| ConnectionSide {
            node_id: f.into(),
            transport: None,
            serializer: None,
            failure_semantics: Some(fs),
            authentication: None,
            authorization: None,
        });
        ConnectionEntry::for_testing(
            next_conn_id(),
            side(to),
            caller,
            Some(transport_entry),
            default_serializer(),
            None,
        )
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
            &transport_meta("http", true, true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(errors(&issues).is_empty(), "unexpected issues: {:?}", issues);
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
            &transport_meta("http", true, true),
        ]);
        let issues = collect_issues(&cfg, &CheckFilter::All, Some(&meta));
        assert!(errors(&issues).is_empty(), "unexpected issues: {:?}", issues);
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
            &transport_meta("http", true, true),
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
            &transport_meta("http", true, true),
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
        let meta = index_from_toml(&[
            &callee_meta("comp-b", "^1.0"),
            &transport_meta("http", true, true),
        ]);
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
            &transport_meta("http", true, true),
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
            &transport_meta("http", true, true),
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
            &transport_meta("http", true, true),
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
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "comp-b", "1.0.0"),
            &transport_meta("http", true, true),
        ]);
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
            &transport_meta("http", true, true),
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
            &transport_meta("http", true, true),
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
        let meta = index_from_toml(&[
            &callee_meta("comp-b", "^1.0"),
            &transport_meta("kafka", true, true),
        ]);
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
        let meta = index_from_toml(&[
            &caller_meta("comp-a", "channel", "1.0.0"),
            &transport_meta("kafka", true, true),
        ]);
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
            &transport_meta("http", true, true),
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
        // failureSemantics is caller-side only now — direct() always builds
        // a caller block (a direct connection is never external), so this
        // unwrap is safe.
        conn.caller.as_mut().unwrap().failure_semantics = Some(itara_config::FailureSemanticsEntry {
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
        let conn = http(None, "b", 8080);
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
        // failureSemantics is caller-side only now — direct() always builds
        // a caller block (a direct connection is never external), so this
        // unwrap is safe.
        conn.caller.as_mut().unwrap().failure_semantics = Some(itara_config::FailureSemanticsEntry {
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
        let conn = http(None, "b", 8080);
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

    // ── check_serializer_compatibility ────────────────────────────────────────

    /// Scopes collect_issues to only this check — CheckFilter::All would also
    /// run check_unknown_transports and check_api_version_compatibility, which
    /// need transport/component metadata these minimal fixtures don't provide,
    /// producing unrelated errors unrelated to what these tests exercise.
    fn only_serializer_compat() -> CheckFilter {
        CheckFilter::Only(["serializer-compatibility".to_string()].into_iter().collect())
    }

    fn api_meta_with_supported(api_id: &str, message_format: &str, supported: &[(&str, &str)]) -> String {
        let entries: String = supported.iter()
            .map(|(id, version)| format!(r#"  {{ id = "{id}", version = "{version}" }},"#))
            .collect::<Vec<_>>()
            .join("\n");
        format!(r#"
[artifact]
kind = "api"
id = "{api_id}"
version = "1.0.0"

[contract]
message-format = "{message_format}"

[serializers]
supported = [
{entries}
]
"#)
    }

    fn api_meta_no_serializers(api_id: &str) -> String {
        format!(r#"
[artifact]
kind = "api"
id = "{api_id}"
version = "1.0.0"
"#)
    }

    fn serializer_meta(id: &str, version: &str, message_formats: &[&str]) -> String {
        let formats: String = message_formats.iter()
            .map(|f| format!(r#""{f}""#))
            .collect::<Vec<_>>()
            .join(", ");
        format!(r#"
[artifact]
kind = "serializer"
id = "{id}"
version = "{version}"

[serializer]
type = "{id}"

[serializer.capabilities]
message-formats = [{formats}]
"#)
    }

    #[test]
    fn serializer_compatible_via_explicit_supported_entry() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &api_meta_with_supported("comp-b", "", &[("protobuf", "^1.0")]),
            &serializer_meta("protobuf", "1.2.0", &[]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        assert!(issues.is_empty(), "unexpected issues: {:?}", issues);
    }

    #[test]
    fn serializer_compatible_via_capability_path() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &api_meta_with_supported("comp-b", "protobuf", &[]),
            &serializer_meta("protobuf", "1.0.0", &["protobuf"]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        assert!(issues.is_empty(), "unexpected issues: {:?}", issues);
    }

    #[test]
    fn serializer_incompatible_neither_path_warns() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &api_meta_with_supported("comp-b", "", &[("json", "^1.0")]),
            &serializer_meta("protobuf", "1.0.0", &[]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        let matches: Vec<_> = issues.iter()
            .filter(|i| i.message.contains("not confirmed compatible"))
            .collect();
        assert_eq!(matches.len(), 1);
        assert!(!matches[0].is_error(), "must be a warning, not an error");
    }

    #[test]
    fn serializer_explicit_version_out_of_range_falls_back_to_capability_path() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &api_meta_with_supported("comp-b", "protobuf", &[("protobuf", "^2.0")]),
            &serializer_meta("protobuf", "1.0.0", &["protobuf"]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        // Explicit path fails (version out of range), but capability path saves it.
        assert!(issues.iter().all(|i| !i.message.contains("not confirmed compatible")));
    }

    #[test]
    fn serializer_direct_connection_skipped() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![direct("caller", "callee")],
        );
        let meta = index_from_toml(&[
            &api_meta_with_supported("comp-b", "", &[]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        assert!(issues.iter().all(|i| !i.message.contains("serializer")));
    }

    #[test]
    fn serializer_missing_api_metadata_warns_skip() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &serializer_meta("protobuf", "1.0.0", &[]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        let matches: Vec<_> = issues.iter()
            .filter(|i| i.message.contains("no API metadata found"))
            .collect();
        assert_eq!(matches.len(), 1);
        assert!(!matches[0].is_error());
    }

    #[test]
    fn serializer_missing_serializer_metadata_warns_skip() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &api_meta_with_supported("comp-b", "", &[("protobuf", "^1.0")]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        let matches: Vec<_> = issues.iter()
            .filter(|i| i.message.contains("no metadata found for serializer"))
            .collect();
        assert_eq!(matches.len(), 1);
        assert!(!matches[0].is_error());
    }

    #[test]
    fn serializer_api_declaring_neither_field_produces_no_issue() {
        // Spec §8.6: an API declaring neither message-format nor supported
        // serializers has no compatibility question to evaluate — this is
        // the common plain-DTO case and must stay completely silent, not
        // just downgraded to a warning.
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &api_meta_no_serializers("comp-b"),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        assert!(issues.is_empty(), "unexpected issues: {:?}", issues);
    }

    #[test]
    fn serializer_compatibility_never_affects_exit_code() {
        let cfg = config(
            vec![node("caller", "comp-a"), node("callee", "comp-b")],
            vec![with_serializer_id(http(Some("caller"), "callee", 8081), "protobuf")],
        );
        let meta = index_from_toml(&[
            &api_meta_with_supported("comp-b", "", &[("json", "^1.0")]),
            &serializer_meta("protobuf", "1.0.0", &[]),
        ]);
        let issues = collect_issues(&cfg, &only_serializer_compat(), Some(&meta));
        assert!(issues.iter().any(|i| i.message.contains("not confirmed compatible")));
        assert!(errors(&issues).is_empty(), "must never produce an error");
    }

    // ── check_caller_callee_plugin_incompatibility ────────────────────────────

    /// Builds a connection where the caller and callee sides each declare
    /// their own transport, serializer and/or authentication independently
    /// — the exact shape check_caller_callee_plugin_incompatibility exists
    /// to compare. Any parameter left None means that side declares
    /// nothing of that kind (so it has no plugin of that kind at all,
    /// since there is deliberately no connection-level fallback here).
    fn conn_with_side_plugins(
        from: Option<&str>,
        to: &str,
        caller_transport: Option<&str>,
        callee_transport: Option<&str>,
        caller_serializer: Option<&str>,
        callee_serializer: Option<&str>,
        caller_authentication: Option<&str>,
        callee_authentication: Option<&str>,
    ) -> ConnectionEntry {
        let caller_side = from.map(|f| ConnectionSide {
            node_id: f.into(),
            transport: caller_transport.map(|id| transport_entry(id, None)),
            serializer: caller_serializer.map(|id| SerializerEntry { id: id.into(), params: Default::default() }),
            failure_semantics: None,
            authentication: caller_authentication.map(|id| itara_config::AuthenticationEntry { id: id.into(), params: Default::default() }),
            authorization: None,
        });
        let callee_side = ConnectionSide {
            node_id: to.into(),
            transport: callee_transport.map(|id| transport_entry(id, None)),
            serializer: callee_serializer.map(|id| SerializerEntry { id: id.into(), params: Default::default() }),
            failure_semantics: None,
            authentication: callee_authentication.map(|id| itara_config::AuthenticationEntry { id: id.into(), params: Default::default() }),
            authorization: None,
        };
        ConnectionEntry::for_testing(next_conn_id(), callee_side, caller_side, None, None, None)
    }

    /// Same as transport_meta, but lets the declared type differ from the
    /// artifact id — needed to build "different id, same type" fixtures,
    /// which transport_meta can't (it always sets type = id).
    fn transport_meta_with_type(id: &str, type_: &str) -> String {
        format!(r#"
[artifact]
kind = "transport"
id = "{id}"
version = "0.1.0"

[transport]
type = "{type_}"
"#)
    }

    /// Same idea as transport_meta_with_type, for serializers.
    fn serializer_meta_with_type(id: &str, type_: &str) -> String {
        format!(r#"
[artifact]
kind = "serializer"
id = "{id}"
version = "0.1.0"

[serializer]
type = "{type_}"
"#)
    }

    fn authentication_meta(id: &str, type_: &str) -> String {
        format!(r#"
[artifact]
kind = "authentication"
id = "{id}"
version = "0.1.0"

[authentication]
type = "{type_}"
"#)
    }

    fn only_caller_callee_compat() -> CheckFilter {
        CheckFilter::Only(["caller-callee-plugin-incompatibility".to_string()].into_iter().collect())
    }

    #[test]
    fn same_transport_id_on_both_sides_is_compatible_without_any_metadata() {
        // Same id short-circuits before any metadata lookup, so this must
        // pass even with a completely empty metadata index.
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("http"),
            Some("json"), Some("json"),
            None, None,
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn different_transport_ids_with_matching_type_are_compatible() {
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http-v1"), Some("http-v2"),
            Some("json"), Some("json"),
            None, None,
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &transport_meta_with_type("http-v1", "http"),
            &transport_meta_with_type("http-v2", "http"),
        ]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn different_transport_ids_with_different_types_is_an_error() {
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("kafka"),
            Some("json"), Some("json"),
            None, None,
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &transport_meta_with_type("http", "http"),
            &transport_meta_with_type("kafka", "kafka"),
        ]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("http") && issues[0].message.contains("kafka"));
    }

    #[test]
    fn different_serializer_ids_with_different_types_is_an_error() {
        // Same as the transport case, but for serializer — confirms all
        // three plugin kinds are actually checked, not just transport.
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("http"),
            Some("json"), Some("protobuf"),
            None, None,
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &transport_meta_with_type("http", "http"),
            &serializer_meta_with_type("json", "json"),
            &serializer_meta_with_type("protobuf", "protobuf"),
        ]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("json") && issues[0].message.contains("protobuf"));
    }

    #[test]
    fn different_serializer_ids_with_matching_type_are_compatible() {
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("http"),
            Some("json-fast"), Some("json-pretty"),
            None, None,
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &transport_meta_with_type("http", "http"),
            &serializer_meta_with_type("json-fast", "json"),
            &serializer_meta_with_type("json-pretty", "json"),
        ]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn different_authentication_ids_with_different_types_is_an_error() {
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("http"),
            Some("json"), Some("json"),
            Some("shared-secret"), Some("mtls"),
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &transport_meta_with_type("http", "http"),
            &serializer_meta_with_type("json", "json"),
            &authentication_meta("shared-secret", "shared-secret"),
            &authentication_meta("mtls", "mtls"),
        ]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("shared-secret") && issues[0].message.contains("mtls"));
    }

    #[test]
    fn different_ids_with_missing_metadata_is_a_warning_not_an_error() {
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("carrier-pigeon"),
            Some("json"), Some("json"),
            None, None,
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        // No metadata registered for either transport id at all.
        let meta = index_from_toml(&[&serializer_meta_with_type("json", "json")]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 0);
        assert_eq!(issues.iter().filter(|i| !i.is_error()).count(), 1);
        assert!(issues[0].message.contains("cannot confirm"));
    }

    #[test]
    fn check_is_unavailable_without_metadata_dir() {
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("kafka"), // would be a hard error with metadata present
            Some("json"), Some("json"),
            None, None,
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let issues = collect_issues(&cfg, &CheckFilter::All, None);
        assert!(issues.iter().filter(|i| i.is_error()).count() == 0);
    }

    #[test]
    fn external_connection_has_nothing_to_compare() {
        // No caller side at all — the check must skip it outright rather
        // than comparing against a nonexistent caller.
        let conn = http(None, "callee", 8080);
        let cfg = config(vec![node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[&transport_meta("http", true, true)]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert!(issues.is_empty());
    }

    #[test]
    fn direct_connection_only_compares_authentication() {
        // Both sides resolve to 'direct', so transport and serializer are
        // skipped entirely (direct never has a serializer, and 'direct' vs
        // 'direct' would be a no-op comparison anyway). Authentication is
        // still genuinely different here and must still be flagged.
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("direct"), Some("direct"),
            None, None,
            Some("shared-secret"), Some("mtls"),
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &authentication_meta("shared-secret", "shared-secret"),
            &authentication_meta("mtls", "mtls"),
        ]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert_eq!(issues.iter().filter(|i| i.is_error()).count(), 1);
        assert!(issues[0].message.contains("authentication"));
    }

    #[test]
    fn noop_authentication_on_either_side_is_never_compared() {
        // "noop" is the built-in default, not a real artifact — comparing
        // it against a real authentication type would either need metadata
        // for a pseudo-id that will never exist, or produce a warning on
        // every connection that simply doesn't authenticate one side. Both
        // are wrong, so this combination must be skipped outright.
        let conn = conn_with_side_plugins(
            Some("caller"), "callee",
            Some("http"), Some("http"),
            Some("json"), Some("json"),
            None, Some("mtls"), // caller has no authentication block -> "noop"
        );
        let cfg = config(vec![node("caller", "comp-a"), node("callee", "comp-b")], vec![conn]);
        let meta = index_from_toml(&[
            &transport_meta_with_type("http", "http"),
            &serializer_meta_with_type("json", "json"),
            &authentication_meta("mtls", "mtls"),
        ]);
        let issues = collect_issues(&cfg, &only_caller_callee_compat(), Some(&meta));
        assert!(issues.is_empty());
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
