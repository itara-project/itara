use clap;
use itara_config::{parse_file, WiringConfig};
use std::collections::{HashMap, HashSet};

use crate::output::{Issue, TICK, CROSS, blank};

/// Transport types the CLI recognises as valid.
/// Extend this list as new transport libs are added to the runtime.
const KNOWN_TRANSPORTS: &[&str] = &["http", "direct"];

const VALID_CHECKS: &[&str] = &[
    "orphaned-nodes",
    "orphaned-connections",
    "duplicate-ids",
    "self-connections",
    "unknown-transport",
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
    /// Skip a specific check by name. Can be repeated. Mutually exclusive with --only.
    /// Valid values: orphaned-nodes, orphaned-connections, duplicate-ids,
    ///               self-connections, unknown-transport
    #[arg(long, value_name = "check", conflicts_with = "only")]
    pub skip: Vec<String>,
    /// Run only the specified check. Can be repeated. Mutually exclusive with --skip.
    /// Valid values: orphaned-nodes, orphaned-connections, duplicate-ids,
    ///               self-connections, unknown-transport
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
 
    let issues = collect_issues(&config, &filter);
    print_results(&args.config, &config, &issues);
 
    if issues.iter().any(|i| i.is_error()) { 1 } else { 0 }
}
 
/// Runs all logical checks on a parsed config and returns the full list of issues.
///
/// Separated from `run` so it can be called directly in unit tests without
/// going through file I/O or argument parsing.
fn collect_issues(config: &WiringConfig, filter: &CheckFilter) -> Vec<Issue> {
    let mut issues: Vec<Issue> = Vec::new();

    if filter.should_run("duplicate-ids")        { check_duplicate_ids(config, &mut issues); }
    if filter.should_run("self-connections")      { check_self_connections(config, &mut issues); }
    if filter.should_run("orphaned-nodes")        { check_orphaned_nodes(config, &mut issues); }
    if filter.should_run("orphaned-connections")  { check_orphaned_connections(config, &mut issues); }
    if filter.should_run("unknown-transport")     { check_unknown_transports(config, &mut issues); }

    issues
}
 
// ── Checks ────────────────────────────────────────────────────────────────────
 
fn check_duplicate_ids(config: &WiringConfig, issues: &mut Vec<Issue>) {
    let mut seen: HashMap<&str, usize> = HashMap::new();
    for node in &config.nodes {
        *seen.entry(node.id.as_str()).or_default() += 1;
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
        if !referenced.contains(node.id.as_str()) {
            issues.push(Issue::error(format!(
                "node '{}' is declared but not referenced in any connection",
                node.id
            )));
        }
    }
}
 
fn check_orphaned_connections(config: &WiringConfig, issues: &mut Vec<Issue>) {
    let declared: HashSet<&str> = config.nodes.iter()
        .map(|n| n.id.as_str())
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
        let t = conn.transport_type.to_ascii_lowercase();
        if !KNOWN_TRANSPORTS.contains(&t.as_str()) {
            issues.push(Issue::error(format!(
                "connection to '{}' has unknown transport type '{}' (known: {})",
                conn.to,
                conn.transport_type,
                KNOWN_TRANSPORTS.join(", "),
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
    use itara_config::{WiringConfig, NodeEntry, ConnectionEntry};
 
    // ── Test helpers ──────────────────────────────────────────────────────────
 
    fn node(id: &str, component: &str) -> NodeEntry {
        NodeEntry { id: id.into(), component: component.into() }
    }
 
    fn http(from: Option<&str>, to: &str, port: u16) -> ConnectionEntry {
        ConnectionEntry {
            from: from.map(Into::into),
            to: to.into(),
            transport_type: "http".into(),
            host: None,
            port: Some(port),
            serializer: "".into(),
        }
    }
 
    fn direct(from: &str, to: &str) -> ConnectionEntry {
        ConnectionEntry {
            from: Some(from.into()),
            to: to.into(),
            transport_type: "direct".into(),
            host: None,
            port: None,
            serializer: "".into(),
        }
    }
 
    fn conn_with_transport(from: Option<&str>, to: &str, transport: &str, port: u16) -> ConnectionEntry {
        ConnectionEntry {
            from: from.map(Into::into),
            to: to.into(),
            transport_type: transport.into(),
            host: None,
            port: Some(port),
            serializer: "".into(),
        }
    }
 
    fn config(nodes: Vec<NodeEntry>, connections: Vec<ConnectionEntry>) -> WiringConfig {
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
        assert!(collect_issues(&cfg, &CheckFilter::All).is_empty());
    }
 
    #[test]
    fn clean_config_with_direct_connection() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), direct("a", "b")],
        );
        assert!(collect_issues(&cfg, &CheckFilter::All).is_empty());
    }
 
    // ── Duplicate ids ─────────────────────────────────────────────────────────
 
    #[test]
    fn duplicate_id_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("a", "ca-dup"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All);
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
        let issues = collect_issues(&cfg, &CheckFilter::All);
        assert_eq!(errors(&issues).len(), 2);
    }
 
    // ── Self-connections ──────────────────────────────────────────────────────
 
    #[test]
    fn self_connection_flagged() {
        let cfg = config(
            vec![node("a", "ca")],
            vec![http(None, "a", 8080), http(Some("a"), "a", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All);
        assert_eq!(errors(&issues).len(), 1);
        assert!(issues[0].message.contains("self-connection"));
    }
 
    #[test]
    fn non_self_connection_not_flagged() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        let self_conn_issues: Vec<_> = collect_issues(&cfg, &CheckFilter::All).into_iter()
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
        let issues = collect_issues(&cfg, &CheckFilter::All);
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
        assert!(collect_issues(&cfg, &CheckFilter::All).is_empty());
    }
 
    #[test]
    fn node_referenced_only_as_from_is_not_orphaned() {
        // "a" only appears as `from` in one connection, `to` in the external one
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), direct("a", "b")],
        );
        assert!(collect_issues(&cfg, &CheckFilter::All).is_empty());
    }
 
    // ── Orphaned connections ──────────────────────────────────────────────────
 
    #[test]
    fn undeclared_to_node_flagged() {
        let cfg = config(
            vec![node("a", "ca")],
            vec![http(None, "a", 8080), http(Some("a"), "ghost", 8081)],
        );
        let issues = collect_issues(&cfg, &CheckFilter::All);
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
        let issues = collect_issues(&cfg, &CheckFilter::All);
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
        assert!(collect_issues(&cfg, &CheckFilter::All).is_empty());
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
        let issues = collect_issues(&cfg, &CheckFilter::All);
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
            let issues = collect_issues(&cfg, &CheckFilter::All);
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
        let transport_issues: Vec<_> = collect_issues(&cfg, &CheckFilter::All).into_iter()
            .filter(|i| i.message.contains("unknown transport"))
            .collect();
        assert!(transport_issues.is_empty());
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
        let issues = collect_issues(&cfg, &CheckFilter::All);
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
