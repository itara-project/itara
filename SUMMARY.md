# Itara — Project Summary

## 1. The Core Problem: Invisible, Calcified Topology
In modern distributed systems, **topology** — which components exist, how they communicate, what transport protocols they use, and how failures are handled — is scattered across HTTP clients, Kafka consumers, retry policies, and config files throughout the codebase. Today's distributed systems don't have an explicit topology layer; topology emerges from the combined implementation of individual components.

This creates three fundamental architectural issues:
1. **High Churn Risk:** Changing a communication boundary (e.g., refactoring an internal HTTP call to a direct in-process call or Kafka message) requires touching business logic on both ends.
2. **Invisible Blast Radius:** Because topology is implicit, no single artifact describes the exact directed runtime graph or predicts what breaks if a connection shifts.
3. **Calcification:** Early architectural decisions lock into code, making future evolution (splitting monoliths or consolidating microservices) prohibitively expensive.

---

## 2. The Core Reframe: Executable Topology
Itara applies the principles of **Infrastructure-as-Code (IaC) to internal component communication**.

* **Components declare *what* they accept and return** (business contract).
* **The wiring config declares *how* components connect** (executable topology graph).
* **Code expresses intent, never transport mechanics** (no HTTP client boilerplate or queue consumer logic in business code).

Topology becomes an explicit, versioned, and verifiable engineering artifact rather than a side effect of implementation.

---

## 3. How It Works: The Two Pillars

### Pillar I: The Startup Wiring Agent (Zero Call-Time Overhead)
The language-specific wiring agent (reference implementations in **Java** and **Rust**) runs once at application startup:
1. Reads the master wiring configuration.
2. Resolves all component dependencies, transports, serializers, and failure policies.
3. Prepares connections and **steps aside**.

**At runtime, no additional process or intermediary service sits in the execution path**. Colocated calls execute through thin, in-memory proxies, while remote calls execute over the declared transport, keeping the business logic independent of communication concerns.

```
┌─────────────────────────────────────────────────────────────┐
│                      Wiring Config                          │
│  (Nodes, Connections, Transports, Failure Semantics, etc.)  │
└──────────────────────────────┬──────────────────────────────┘
                               │ Reads at Startup
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                     Itara Wiring Agent                      │
│        (Resolves proxies, binds transports & observers)     │
└──────────────────────────────┬──────────────────────────────┘
                               │ Wires & Steps Aside
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   Running Business Application              │
│      (Direct in-memory calls OR Remote transport calls)     │
└──────────────────────────────┴──────────────────────────────┘
```

### Pillar II: The Topology Compiler & Tooling
A dedicated CLI (`itara-cli`) understands the system's exact topology before anything is deployed:
* **Pre-deployment Verification (`itara verify`):** Catches orphaned nodes, API semver mismatches, and invalid transport configurations in CI/CD before deployment.
* **Derived Deployment Groups (`itara inspect`):** Renders the topology: nodes, connections, transport types, and — most usefully — derived deployment groups.

---

## 4. Architectural Proof: 1 Codebase, 3 Topologies
The Itara demo proves language-neutral topology execution using four Java components and one Rust payment service. **The business code never changes across all three topologies**:

| Topology Target | Execution Strategy | Measured Outcome | 
| :--- | :--- | :--- |
| **Monolith** | Colocate Order, Inventory, and Fulfilment in one process; Payment & Notification separate. | Near-zero overhead on direct calls. Zero network latency between internal domains. |
| **Microservices** | Distribute every component into its own container. | Transport overhead (serialization/network) becomes explicitly measurable in traces. |
| **Informed** | Colocate Order + Inventory (high-frequency calls), distribute the rest. | Selective domain optimization without touching a single line of business code. |

Every component call automatically emits a standard 4-event boundary trace (`CALL_SENT`, `CALL_RECEIVED`, `RETURN_SENT`, `RETURN_RECEIVED`), producing unified, cross-language OpenTelemetry traces (e.g., Java calling Rust).

---

## 5. Scope Boundary: What Itara Is Not
* **Not a Service Mesh / Sidecar:** Itara does not own network infrastructure or run proxy processes at call time.
* **Not a Container Orchestrator:** Itara does not manage pods, deployments, or scaling (it complements Kubernetes and Docker).
* **Not an Application Framework:** Itara coexists alongside Spring Boot, Actix, or native runtimes—it strictly owns component topology.

Itara fills the missing layer between architecture diagrams and running code: making distributed system topology **declarative, verifiable, and executable**.

---

### Links & Further Reading
* **The "why":** [VISION.md](spec/VISION.md)
* **The principles:** [MANIFESTO.md](spec/MANIFESTO.md)
* **The "how":** [SPEC.md](spec/SPEC.md)
* **What it looks like:** [Demo](demo/README.md)
