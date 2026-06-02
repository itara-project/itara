use clap;
use itara_config::{parse_file, WiringConfig, ConnectionEntry};

use crate::output::{kv, section, blank};

#[derive(clap::Args)]
pub struct Args {
    /// Path to the master wiring config file.
    pub config: String,
}

/// Exit codes: 0 on success, 1 if the config cannot be loaded.
pub fn run(args: Args) -> i32 {
    let config = match parse_file(&args.config) {
        Ok(c) => c,
        Err(e) => {
            eprintln!("error: {}", e);
            return 1;
        }
    };

    print_header(&args.config);
    print_nodes(&config);
    print_connections(&config);
    print_deployment_groups(&config);
    print_graph(&config);

    0
}

// ── Sections ──────────────────────────────────────────────────────────────────

fn print_header(path: &str) {
    println!("Itara topology — {}", path);
    blank();
}

fn print_nodes(config: &WiringConfig) {
    // Column width: longest node id, at least 16 chars.
    let id_width = config.nodes.iter()
        .map(|n| n.id.len())
        .max()
        .unwrap_or(0)
        .max(16);

    section("Nodes");
    if config.nodes.is_empty() {
        println!("  (none)");
    }
    for node in &config.nodes {
        let is_entry = has_external_inbound(config, &node.id);
        let component_part = format!("component: {}", node.component);
        if is_entry {
            kv(&node.id, &format!("{:<30} (external entry point)", component_part), id_width);
        } else {
            kv(&node.id, &component_part, id_width);
        }
    }
    blank();
}

fn print_connections(config: &WiringConfig) {
    let internal: Vec<&ConnectionEntry> = config.connections.iter()
        .filter(|c| !c.is_external())
        .collect();

    let from_width = internal.iter()
        .filter_map(|c| c.from.as_deref())
        .map(|f| f.len())
        .max()
        .unwrap_or(0)
        .max(16);

    section("Connections");
    if internal.is_empty() {
        println!("  (none)");
    }
    for conn in &internal {
        let from = conn.from.as_deref().unwrap_or("?");
        kv(
            &format!("{} →", from),
            &format!("{:<20} [{}]", conn.to, conn.transport_type),
            from_width + 2, // +2 for " →"
        );
    }
    blank();
}

fn print_deployment_groups(config: &WiringConfig) {
    section("Deployment groups (derived)");
    if config.nodes.is_empty() {
        println!("  (none)");
        blank();
        return;
    }

    let groups = derive_deployment_groups(config);

    for (i, group) in groups.iter().enumerate() {
        // Header: group label + all node ids on one line for a quick overview.
        println!("  Group {}: {}", group_label(i), group.join(", "));

        for node_id in group {
            let component = config.component_of_node(node_id).unwrap_or("?");
            println!("    {} ({})", node_id, component);

            // Inbound cross-group connections (non-direct) to this node.
            let inbound: Vec<&ConnectionEntry> = config.connections.iter()
                .filter(|c| c.to == *node_id && !c.is_direct())
                .collect();

            for conn in &inbound {
                if conn.is_external() {
                    println!("      Receives: external {} on {}", conn.transport_type, port_str(conn.port));
                } else {
                    let from = conn.from.as_deref().unwrap_or("?");
                    let port = conn.port.map(|p| format!(" on :{}", p)).unwrap_or_default();
                    println!("      Receives: {} via {}{}", from, conn.transport_type, port);
                }
            }

            // Outbound cross-group connections (non-direct) from this node.
            let outbound: Vec<&ConnectionEntry> = config.connections.iter()
                .filter(|c| c.from.as_deref() == Some(node_id.as_str()))
                .collect();

            for conn in &outbound {
                println!("      Calls:    {} via {}", conn.to, conn.transport_type);
            }
        }

        blank();
    }
}

/// Derives deployment groups from the wiring config.
///
/// Nodes joined by `direct` (in-process) connections belong to the same
/// deployment group — they will be co-located in the same container.
/// All other connection types (http, etc.) cross group boundaries.
///
/// This is a connected-components search over the subgraph of direct edges.
fn derive_deployment_groups(config: &WiringConfig) -> Vec<Vec<String>> {
    use std::collections::{HashMap, HashSet, VecDeque};

    // Build an undirected adjacency list for direct connections only.
    // Direct is always in-process so directionality doesn't matter for grouping.
    let mut direct_neighbours: HashMap<&str, Vec<&str>> = HashMap::new();
    for conn in config.connections.iter().filter(|c| c.is_direct()) {
        if let Some(from) = conn.from.as_deref() {
            if !from.trim().is_empty() {
                direct_neighbours.entry(from).or_default().push(conn.to.as_str());
                direct_neighbours.entry(conn.to.as_str()).or_default().push(from);
            }
        }
    }

    // BFS over declared nodes to find connected components.
    // Declaration order is preserved so the output is deterministic.
    let mut visited: HashSet<&str> = HashSet::new();
    let mut groups: Vec<Vec<String>> = Vec::new();

    for node in &config.nodes {
        let id = node.id.as_str();
        if visited.contains(id) {
            continue;
        }

        let mut group: Vec<String> = Vec::new();
        let mut queue: VecDeque<&str> = VecDeque::new();
        queue.push_back(id);
        visited.insert(id);

        while let Some(current) = queue.pop_front() {
            group.push(current.to_string());
            if let Some(neighbours) = direct_neighbours.get(current) {
                for &neighbour in neighbours {
                    if !visited.contains(neighbour) {
                        visited.insert(neighbour);
                        queue.push_back(neighbour);
                    }
                }
            }
        }

        groups.push(group);
    }

    groups
}

// ── Graph chain builder ────────────────────────────────────────────────────────

fn print_graph(config: &WiringConfig) {
    if config.connections.is_empty() {
        return;
    }

    section("Graph");

    // Build chains: start from external connections and follow edges.
    // Falls back to listing individual arrows when the graph branches.
    let chains = build_chains(config);
    for chain in chains {
        println!("  {}", chain);
    }
    blank();
}

/// Attempts to render the topology as one or more arrow chains.
///
/// For a linear path  A → B → C  this produces a single readable line.
/// For branching or merging topologies it produces one line per connection,
/// which is still unambiguous even if not as compact.
fn build_chains(config: &WiringConfig) -> Vec<String> {
    use std::collections::{HashMap, HashSet};

    // Map from node-id to outbound connections for quick lookup.
    let mut outbound: HashMap<&str, Vec<&ConnectionEntry>> = HashMap::new();
    for conn in &config.connections {
        if let Some(from) = conn.from.as_deref() {
            if !from.trim().is_empty() {
                outbound.entry(from).or_default().push(conn);
            }
        }
    }

    // Count how many times each node appears as a "to" target — nodes with
    // more than one inbound edge are merge points and break simple chains.
    let mut inbound_count: HashMap<&str, usize> = HashMap::new();
    for conn in &config.connections {
        *inbound_count.entry(conn.to.as_str()).or_default() += 1;
    }

    let is_branch_point = |id: &str| outbound.get(id).map(|v| v.len() > 1).unwrap_or(false);
    let is_merge_point  = |id: &str| inbound_count.get(id).copied().unwrap_or(0) > 1;

    let mut lines: Vec<String> = Vec::new();
    let mut rendered: HashSet<*const ConnectionEntry> = HashSet::new();

    // Walk from each external entry point.
    for root_conn in config.connections.iter().filter(|c| c.is_external()) {
        let mut parts: Vec<String> = vec![
            "[external]".to_string(),
            arrow_label(root_conn),
            format!("[{}]", root_conn.to),
        ];
        rendered.insert(root_conn as *const _);

        let mut cursor = root_conn.to.as_str();

        loop {
            if is_branch_point(cursor) || is_merge_point(cursor) {
                break;
            }
            match outbound.get(cursor).and_then(|v| v.first()) {
                None => break,
                Some(next) => {
                    if rendered.contains(&(*next as *const _)) {
                        break;
                    }
                    parts.push(arrow_label(next));
                    parts.push(format!("[{}]", next.to));
                    rendered.insert(*next as *const _);
                    cursor = next.to.as_str();
                }
            }
        }

        lines.push(parts.join(" "));
    }

    // Any connections not yet rendered (mid-graph branches etc.) get their
    // own individual arrow lines.
    for conn in config.connections.iter().filter(|c| !c.is_external()) {
        if !rendered.contains(&(conn as *const _)) {
            let from = conn.from.as_deref().unwrap_or("?");
            lines.push(format!(
                "[{}] {} [{}]",
                from,
                arrow_label(conn),
                conn.to,
            ));
        }
    }

    lines
}

fn arrow_label(conn: &ConnectionEntry) -> String {
    let port = conn.port
        .map(|p| format!(":{}", p))
        .unwrap_or_default();
    format!("--{}{}-->", conn.transport_type, port)
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Returns true if the node has at least one external inbound connection.
/// A node can have multiple external inbound connections (e.g. http + gRPC),
/// so we check for existence rather than returning a single description.
fn has_external_inbound(config: &WiringConfig, node_id: &str) -> bool {
    config.connections.iter()
        .any(|c| c.is_external() && c.to == node_id)
}

fn port_str(port: Option<u16>) -> String {
    port.map(|p| format!(":{}", p)).unwrap_or_else(|| "(no port)".to_string())
}

/// Converts a 0-based index to a spreadsheet-style group label: A, B, … Z, AA, AB, …
fn group_label(i: usize) -> String {
    if i < 26 {
        char::from(b'A' + i as u8).to_string()
    } else {
        let high = group_label(i / 26 - 1);
        let low  = char::from(b'A' + (i % 26) as u8);
        format!("{}{}", high, low)
    }
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
 
    fn config(nodes: Vec<NodeEntry>, connections: Vec<ConnectionEntry>) -> WiringConfig {
        WiringConfig { nodes, connections, local_node_ids: vec![] }
    }
 
    // ── group_label ───────────────────────────────────────────────────────────
 
    #[test]
    fn group_label_single_letters() {
        assert_eq!(group_label(0),  "A");
        assert_eq!(group_label(1),  "B");
        assert_eq!(group_label(25), "Z");
    }
 
    #[test]
    fn group_label_double_letters() {
        assert_eq!(group_label(26), "AA");
        assert_eq!(group_label(27), "AB");
        assert_eq!(group_label(51), "AZ");
        assert_eq!(group_label(52), "BA");
    }
 
    // ── has_external_inbound ──────────────────────────────────────────────────
 
    #[test]
    fn external_inbound_with_one_external() {
        let cfg = config(
            vec![node("nodeA", "compA")],
            vec![http(None, "nodeA", 8080)],
        );
        assert!(has_external_inbound(&cfg, "nodeA"));
    }
 
    #[test]
    fn external_inbound_with_multiple_external() {
        let cfg = config(
            vec![node("nodeA", "compA")],
            vec![
                http(None, "nodeA", 8080),
                http(None, "nodeA", 9090),
            ],
        );
        assert!(has_external_inbound(&cfg, "nodeA"));
    }
 
    #[test]
    fn external_inbound_only_internal_connections() {
        let cfg = config(
            vec![node("nodeA", "compA"), node("nodeB", "compB")],
            vec![
                http(None, "nodeA", 8080),
                http(Some("nodeA"), "nodeB", 8081),
            ],
        );
        // nodeB has only internal inbound
        assert!(!has_external_inbound(&cfg, "nodeB"));
    }
 
    #[test]
    fn external_inbound_no_connections() {
        let cfg = config(vec![node("nodeA", "compA")], vec![]);
        assert!(!has_external_inbound(&cfg, "nodeA"));
    }
 
    // ── derive_deployment_groups ──────────────────────────────────────────────
 
    #[test]
    fn groups_no_direct_all_singletons() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("c", "cc")],
            vec![
                http(None, "a", 8080),
                http(Some("a"), "b", 8081),
                http(Some("b"), "c", 8082),
            ],
        );
        let groups = derive_deployment_groups(&cfg);
        assert_eq!(groups.len(), 3);
        assert_eq!(groups[0], vec!["a"]);
        assert_eq!(groups[1], vec!["b"]);
        assert_eq!(groups[2], vec!["c"]);
    }
 
    #[test]
    fn groups_one_direct_pair() {
        // a and b are direct → one group; c is separate
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("c", "cc")],
            vec![
                http(None, "a", 8080),
                direct("a", "b"),
                http(Some("a"), "c", 8090),
            ],
        );
        let groups = derive_deployment_groups(&cfg);
        assert_eq!(groups.len(), 2);
        assert!(groups[0].contains(&"a".to_string()));
        assert!(groups[0].contains(&"b".to_string()));
        assert_eq!(groups[1], vec!["c"]);
    }
 
    #[test]
    fn groups_chain_of_direct_all_in_one() {
        // a→b direct, b→c direct → a, b, c all colocated
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("c", "cc")],
            vec![
                http(None, "a", 8080),
                direct("a", "b"),
                direct("b", "c"),
            ],
        );
        let groups = derive_deployment_groups(&cfg);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].len(), 3);
        assert!(groups[0].contains(&"a".to_string()));
        assert!(groups[0].contains(&"b".to_string()));
        assert!(groups[0].contains(&"c".to_string()));
    }
 
    #[test]
    fn groups_two_disjoint_direct_pairs() {
        // a-b direct, c-d direct → two groups
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("c", "cc"), node("d", "cd")],
            vec![
                http(None, "a", 8080),
                direct("a", "b"),
                http(Some("b"), "c", 8081),
                direct("c", "d"),
            ],
        );
        let groups = derive_deployment_groups(&cfg);
        assert_eq!(groups.len(), 2);
        // First group starts from declaration order: a, then b via direct
        assert!(groups[0].contains(&"a".to_string()));
        assert!(groups[0].contains(&"b".to_string()));
        // Second group: c, then d via direct
        assert!(groups[1].contains(&"c".to_string()));
        assert!(groups[1].contains(&"d".to_string()));
    }
 
    #[test]
    fn groups_empty_config() {
        let cfg = config(vec![], vec![]);
        let groups = derive_deployment_groups(&cfg);
        assert!(groups.is_empty());
    }
 
    #[test]
    fn groups_single_node_no_connections() {
        let cfg = config(vec![node("a", "ca")], vec![]);
        let groups = derive_deployment_groups(&cfg);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0], vec!["a"]);
    }
 
    // ── build_chains ──────────────────────────────────────────────────────────
 
    #[test]
    fn chains_linear_produces_single_line() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb")],
            vec![http(None, "a", 8080), http(Some("a"), "b", 8081)],
        );
        let chains = build_chains(&cfg);
        assert_eq!(chains.len(), 1);
        assert!(chains[0].starts_with("[external]"));
        assert!(chains[0].contains("[a]"));
        assert!(chains[0].contains("[b]"));
    }
 
    #[test]
    fn chains_branching_produces_multiple_lines() {
        let cfg = config(
            vec![node("a", "ca"), node("b", "cb"), node("c", "cc")],
            vec![
                http(None, "a", 8080),
                http(Some("a"), "b", 8081),
                http(Some("a"), "c", 8082),
            ],
        );
        let chains = build_chains(&cfg);
        // Branching breaks the single-line representation
        assert!(chains.len() > 1);
    }
 
    #[test]
    fn chains_no_connections_is_empty() {
        let cfg = config(vec![node("a", "ca")], vec![]);
        let chains = build_chains(&cfg);
        assert!(chains.is_empty());
    }
}
