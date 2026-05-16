# ADR 0009 — Node and Component Identity Separation

**Date:** May 2026  
**Status:** Accepted

## Context

In a distributed system, there is a difference between a piece of code (what runs) and a deployment unit (where it runs). Early versions of the Itara documentation and implementation blurred this distinction, using component identity in places where deployment identity was the correct concept.

This distinction became critical when considering service discovery, multi-instance deployments, and HTTP routing.

## Decision

Itara separates two distinct identity concepts:

**Component identity (component-id):** The code identity. Identifies the contract and implementation — what a thing *is*. Used in the wiring config to declare what code runs at a node, in the registry to look up instances, in HTTP URLs to route calls, and in the API artifact to declare contracts.

**Node identity (node-id):** The deployment unit identity. Identifies a declared deployment position in the topology — a K8s pod definition, a systemd service, a Docker container, whatever the orchestrator manages. A node is not an instance count — how many instances of a node are running is entirely the orchestrator's concern (autoscaling, replicas, etc.) and is not an Itara concept.

The primary purpose of the node/component separation is colocation control. The same component can be declared as a local node (direct in-process calls, zero network overhead) for some callers and as a separate node (remote calls over a transport) for others. The topology decision — colocated for low latency vs separate for isolation — is expressed in the wiring config without changing any component code.

One component can serve multiple nodes with different colocation characteristics. Two nodes with the same component-id but different node-ids represent different deployment positions in the topology, not necessarily different running processes.

HTTP routing uses component-id: `POST /itara/{componentId}/{methodName}`. DNS resolves the node-id to a host and port. The URL determines *what* is being called; the resolved address determines *which deployment unit* receives it.

Service discovery registers node-ids and resolves them to host/port. It does not know about component-ids. The agent maps node-id → component-id internally via the wiring config.

How many instances of a node are running at any given time is the orchestrator's responsibility. Orca may inform such decisions in the future based on observed metrics, but instance count is not an Itara topology concept.

## Consequences

- The same component code participates in different topologies depending on how its node is declared. A calculator component can be colocated (direct call) for a gateway that needs low latency and a separate service for other callers — without any change to the calculator's code.
- Load balancing multiple instances of the same node is handled entirely by DNS and standard load balancing infrastructure. Itara does not need a load balancing layer.
- The HTTP URL is stable across deployments. Moving a node to a different host changes the DNS entry but not the URL that callers use.
- The wiring config is the only place that maps node-ids to component-ids. This mapping is the topology declaration.
- Service discovery (Consul, Kubernetes DNS, controller registry) registers node-ids. Callers resolve node-ids to addresses. Component-ids never appear in service discovery.
- Instance scaling (horizontal pod autoscaling, etc.) operates on nodes as the orchestrator understands them. Itara is unaware of how many instances are running and does not need to be.
