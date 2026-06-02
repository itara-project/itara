use assert_cmd::Command;
use predicates::prelude::*;

fn fixture(name: &str) -> String {
    format!("{}/tests/fixtures/{}", env!("CARGO_MANIFEST_DIR"), name)
}

fn itara() -> Command {
    Command::cargo_bin("itara").unwrap()
}

// ── itara inspect ─────────────────────────────────────────────────────────────

mod inspect {
    use super::*;

    #[test]
    fn simple_exits_successfully() {
        itara().args(["inspect", &fixture("simple.yaml")])
            .assert().success();
    }

    #[test]
    fn simple_contains_section_headers() {
        itara().args(["inspect", &fixture("simple.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("Nodes:"))
            .stdout(predicate::str::contains("Connections:"))
            .stdout(predicate::str::contains("Deployment groups (derived):"))
            .stdout(predicate::str::contains("Graph:"));
    }

    #[test]
    fn simple_lists_both_nodes() {
        itara().args(["inspect", &fixture("simple.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("gatewayNode"))
            .stdout(predicate::str::contains("calculatorNode"));
    }

    #[test]
    fn simple_marks_external_entry_point() {
        // gatewayNode has an external inbound connection; calculatorNode does not.
        itara().args(["inspect", &fixture("simple.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("(external entry point)"));
    }

    #[test]
    fn simple_shows_connection_arrow() {
        itara().args(["inspect", &fixture("simple.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("gatewayNode"))
            .stdout(predicate::str::contains("calculatorNode"));
    }

    #[test]
    fn simple_two_nodes_two_groups() {
        // No direct connections → each node is its own group.
        let out = itara().args(["inspect", &fixture("simple.yaml")])
            .assert().success()
            .get_output()
            .stdout
            .clone();
        let text = String::from_utf8(out).unwrap();
        assert!(text.contains("Group A:"), "expected Group A in output");
        assert!(text.contains("Group B:"), "expected Group B in output");
        assert!(!text.contains("Group C:"), "unexpected Group C in output");
    }

    #[test]
    fn colocated_groups_direct_nodes_together() {
        // gatewayNode and calculatorNode share a direct connection → same group.
        let out = itara().args(["inspect", &fixture("colocated.yaml")])
            .assert().success()
            .get_output()
            .stdout
            .clone();
        let text = String::from_utf8(out).unwrap();

        // There should be exactly two groups.
        assert!(text.contains("Group A:"), "expected Group A");
        assert!(text.contains("Group B:"), "expected Group B");
        assert!(!text.contains("Group C:"), "unexpected Group C");

        // The colocated group header must list both node ids on the same line.
        let colocated_line = text.lines()
            .find(|l| l.contains("Group A:"))
            .expect("Group A line not found");
        assert!(
            colocated_line.contains("gatewayNode") && colocated_line.contains("calculatorNode"),
            "Group A should contain both colocated nodes, got: {}",
            colocated_line
        );
    }

    #[test]
    fn colocated_separate_node_in_own_group() {
        let out = itara().args(["inspect", &fixture("colocated.yaml")])
            .assert().success()
            .get_output()
            .stdout
            .clone();
        let text = String::from_utf8(out).unwrap();
        let group_b_line = text.lines()
            .find(|l| l.contains("Group B:"))
            .expect("Group B line not found");
        assert!(
            group_b_line.contains("notifierNode"),
            "Group B should contain notifierNode, got: {}",
            group_b_line
        );
    }

    #[test]
    fn colocated_shows_calls_for_direct_connection() {
        // Internal direct calls should still appear in the output.
        itara().args(["inspect", &fixture("colocated.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("Calls:").and(
                predicate::str::contains("calculatorNode")
            ));
    }

    #[test]
    fn multi_external_entry_point_shown() {
        // apiNode has two external inbound connections; it should be marked.
        itara().args(["inspect", &fixture("multi_external.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("(external entry point)"));
    }

    #[test]
    fn multi_external_entry_point_shown_only_once_per_node() {
        // Even with two external connections, the marker appears once on the node line.
        let out = itara().args(["inspect", &fixture("multi_external.yaml")])
            .assert().success()
            .get_output()
            .stdout
            .clone();
        let text = String::from_utf8(out).unwrap();
        let marker_count = text.lines()
            .filter(|l| l.contains("(external entry point)"))
            .count();
        assert_eq!(marker_count, 1, "marker should appear exactly once in nodes section");
    }

    #[test]
    fn all_direct_single_group() {
        // Three nodes all connected via direct → one deployment group.
        let out = itara().args(["inspect", &fixture("all_direct.yaml")])
            .assert().success()
            .get_output()
            .stdout
            .clone();
        let text = String::from_utf8(out).unwrap();
        assert!(text.contains("Group A:"), "expected Group A");
        assert!(!text.contains("Group B:"), "unexpected Group B — all nodes should be in one group");

        let group_a_line = text.lines()
            .find(|l| l.contains("Group A:"))
            .expect("Group A line not found");
        assert!(group_a_line.contains("nodeA"), "Group A should contain nodeA");
        assert!(group_a_line.contains("nodeB"), "Group A should contain nodeB");
        assert!(group_a_line.contains("nodeC"), "Group A should contain nodeC");
    }

    #[test]
    fn branching_shows_both_downstream_nodes() {
        itara().args(["inspect", &fixture("branching.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("serviceANode"))
            .stdout(predicate::str::contains("serviceBNode"));
    }

    #[test]
    fn branching_three_separate_groups() {
        // No direct connections in branching.yaml → three singleton groups.
        let out = itara().args(["inspect", &fixture("branching.yaml")])
            .assert().success()
            .get_output()
            .stdout
            .clone();
        let text = String::from_utf8(out).unwrap();
        assert!(text.contains("Group A:"));
        assert!(text.contains("Group B:"));
        assert!(text.contains("Group C:"));
    }

    #[test]
    fn missing_file_exits_nonzero() {
        itara().args(["inspect", "does_not_exist.yaml"])
            .assert().failure();
    }

    #[test]
    fn missing_file_prints_error_to_stderr() {
        itara().args(["inspect", "does_not_exist.yaml"])
            .assert().failure()
            .stderr(predicate::str::contains("error:"));
    }
}

// ── itara verify ──────────────────────────────────────────────────────────────

mod verify {
    use super::*;

    #[test]
    fn clean_config_exits_zero() {
        itara().args(["verify", &fixture("verify_clean.yaml")])
            .assert().success();
    }

    #[test]
    fn clean_config_shows_tick_and_no_issues() {
        itara().args(["verify", &fixture("verify_clean.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("✓"))
            .stdout(predicate::str::contains("No issues found."));
    }

    #[test]
    fn clean_config_shows_node_and_connection_counts() {
        itara().args(["verify", &fixture("verify_clean.yaml")])
            .assert().success()
            .stdout(predicate::str::contains("2 nodes, 2 connections"));
    }

    #[test]
    fn orphan_node_exits_nonzero() {
        itara().args(["verify", &fixture("verify_orphan_node.yaml")])
            .assert().failure();
    }

    #[test]
    fn orphan_node_shows_cross_and_error() {
        itara().args(["verify", &fixture("verify_orphan_node.yaml")])
            .assert().failure()
            .stdout(predicate::str::contains("✗"))
            .stdout(predicate::str::contains("orphanNode"))
            .stdout(predicate::str::contains("not referenced"));
    }

    #[test]
    fn orphan_connection_exits_nonzero() {
        itara().args(["verify", &fixture("verify_orphan_connection.yaml")])
            .assert().failure();
    }

    #[test]
    fn orphan_connection_names_the_undeclared_node() {
        itara().args(["verify", &fixture("verify_orphan_connection.yaml")])
            .assert().failure()
            .stdout(predicate::str::contains("undeclaredNode"))
            .stdout(predicate::str::contains("undeclared"));
    }

    #[test]
    fn duplicate_id_exits_nonzero() {
        itara().args(["verify", &fixture("verify_duplicate_id.yaml")])
            .assert().failure();
    }

    #[test]
    fn duplicate_id_names_the_duplicate() {
        itara().args(["verify", &fixture("verify_duplicate_id.yaml")])
            .assert().failure()
            .stdout(predicate::str::contains("nodeA"))
            .stdout(predicate::str::contains("declared 2 times"));
    }

    #[test]
    fn self_connection_exits_nonzero() {
        itara().args(["verify", &fixture("verify_self_connection.yaml")])
            .assert().failure();
    }

    #[test]
    fn self_connection_identifies_the_node() {
        itara().args(["verify", &fixture("verify_self_connection.yaml")])
            .assert().failure()
            .stdout(predicate::str::contains("self-connection"));
    }

    #[test]
    fn unknown_transport_exits_nonzero() {
        itara().args(["verify", &fixture("verify_unknown_transport.yaml")])
            .assert().failure();
    }

    #[test]
    fn unknown_transport_names_the_type() {
        itara().args(["verify", &fixture("verify_unknown_transport.yaml")])
            .assert().failure()
            .stdout(predicate::str::contains("grpc"))
            .stdout(predicate::str::contains("unknown transport"));
    }

    #[test]
    fn multiple_errors_exits_nonzero() {
        itara().args(["verify", &fixture("verify_multiple_errors.yaml")])
            .assert().failure();
    }

    #[test]
    fn multiple_errors_shows_correct_count() {
        // verify_multiple_errors.yaml has: 1 orphaned node + 1 undeclared
        // node reference + 1 self-connection = 3 errors.
        itara().args(["verify", &fixture("verify_multiple_errors.yaml")])
            .assert().failure()
            .stdout(predicate::str::contains("3 errors"));
    }

    #[test]
    fn multiple_errors_all_listed_individually() {
        let out = itara().args(["verify", &fixture("verify_multiple_errors.yaml")])
            .assert().failure()
            .get_output()
            .stdout
            .clone();
        let text = String::from_utf8(out).unwrap();
        let error_lines: Vec<_> = text.lines()
            .filter(|l| l.trim_start().starts_with("ERROR"))
            .collect();
        assert_eq!(error_lines.len(), 3, "expected 3 ERROR lines, got: {:?}", error_lines);
    }

    #[test]
    fn missing_file_exits_nonzero() {
        itara().args(["verify", "does_not_exist.yaml"])
            .assert().failure();
    }

    #[test]
    fn missing_file_shows_cross_in_output() {
        itara().args(["verify", "does_not_exist.yaml"])
            .assert().failure()
            .stdout(predicate::str::contains("✗"));
    }

    #[test]
    fn singular_node_and_connection_words() {
        // verify_self_connection.yaml has 1 node and 2 connections.
        // Check that "node" (not "nodes") is used for count of 1.
        itara().args(["verify", &fixture("verify_self_connection.yaml")])
            .assert()
            .stdout(predicate::str::contains("1 node,"));
    }
}
