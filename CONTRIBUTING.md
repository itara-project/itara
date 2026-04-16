# Contributing to Itara

Thank you for your interest in contributing. Itara is an early-stage project
with a clear vision and a small core team. Contributions are welcome, but the
vision comes first — please read this document and the manifesto before
starting any work.

---

## Start here

1. Read the [MANIFESTO.md](MANIFESTO.md) — these are the principles that
   guide every design decision. If a contribution conflicts with the manifesto,
   it will not be merged regardless of technical quality.

2. Read the [VISION.md](VISION.md) — understand where the project is going,
   not just where it is.

3. Read the [README.md](README.md) and run the demo — make sure you understand
   what works today before proposing changes.

---

## The design philosophy

**Small modules with clean boundaries.**
Each module does one thing. If you are adding something that feels like it
belongs in two places, the answer is usually a new module or a new SPI,
not a bigger existing module.

**Interfaces before implementations.**
SPIs (Service Provider Interfaces) are defined in `itara-common` with no
external dependencies. Implementations live in separate modules and are
discovered at runtime. If your contribution requires adding a dependency
to `itara-common`, reconsider the design.

**The manifesto is the filter.**
Before proposing a design, ask: does this violate any principle in the
manifesto? If it does, the answer is no. If it doesn't, the conversation
can begin.

**Cheap refactoring is a feature.**
Keep modules small enough that an AI coding assistant can hold the entire
module in context. This is not a joke — it is an active design goal.
Complexity that defeats tooling also defeats human contributors.

---

## Project structure

```
itara-common/              Annotations, SPI interfaces, registry, ItaraMain.
                           No external dependencies. Everything depends on this.

itara-agent/               JVM premain agent and custom class loader.
                           Discovers and wires components.
                           Depends on itara-common and ByteBuddy only.

itara-transport-http/      HTTP transport implementation.
                           Reference implementation of the transport SPI.

itara-demo/                Hello world demo. Two components, three topologies.
                           Read this before writing any integration tests.
```

Planned modules (not yet implemented — see open issues):

```
itara-transport-jms/       JMS transport (ActiveMQ Artemis first)
itara-transport-kafka/     Kafka transport
itara-observability-otel/  OpenTelemetry observer implementation
itara-spring/              Spring Boot integration (fetch helper, not magic)
itara-discovery-*/         Service discovery plugins (Consul, k8s DNS, etc.)
```

---

## How to build

Requirements: JDK 21, Maven 3.8+.

```bash
# Build and install itara-common first (everything depends on it)
cd itara-common && mvn install

# Build the agent fat jar
cd itara-agent && mvn package

# Build the HTTP transport
cd itara-transport-http && mvn package

# Build and run the demo
cd itara-demo && mvn install
```

See README.md for the run commands.

---

## Running the demo

The demo proves the core idea: same code, different topology, different config.
Run it in both direct and HTTP modes before submitting any pull request that
touches the agent, transport layer, or registry.

---

## How to contribute

### Pick an issue

Browse the open issues. Issues labeled `good first issue` are well-scoped and
self-contained. Issues labeled `discussion` need a design conversation before
implementation — comment on the issue before writing code.

### Before you start coding

For anything beyond a bug fix or documentation change:
- Comment on the issue to signal you are working on it
- Describe your intended approach in the comment
- Wait for a response from the core team before investing significant time

This saves everyone from duplicate work and misaligned implementations.

### Branch naming

```
feature/short-description
fix/short-description
docs/short-description
```

### Commit messages

First line: short imperative summary (50 chars max).
Body (optional): explain *why*, not *what*. The diff shows what.

```
Add JMS transport SPI implementation

Artemis 2.x tested. Listener uses session-per-request model to avoid
thread contention. Request-response uses a temporary reply queue.
```

### Pull requests

- One logical change per PR
- Include a description of what was changed and why
- If the PR adds a new module, include a META-INF descriptor and update
  the demo or add a separate test that proves it works end to end
- All existing demo runs must still work after your change

---

## What we are looking for

**Transport implementations** — JMS, Kafka, gRPC. Follow the pattern in
`itara-transport-http`. Define a `META-INF/itara/transport` descriptor.
Test against a real broker, not a mock.

**Observability** — the context propagation design is in progress (see issue).
Do not start implementing observability until the `ItaraContext` design is
finalised. Watch the discussion issue.

**Test infrastructure** — unit tests for the registry and config loader,
integration tests that start real JVMs and verify wiring. The project has
no automated tests yet. This is a high-priority gap.

**Documentation** — clear, accurate, minimal. If you are explaining how
something works, make sure it actually works that way first.

**Native language runtime** — C++, Rust, C# via shared libraries. This is
a research track. See the relevant issue for the design constraints before
starting. Coordinate with the core team — this work shapes the long-term
architecture.

---

## What we are not looking for (yet)

- Auto-injection magic in the Spring adapter
- Live rewiring within a running JVM (this is a closed question — see manifesto)
- Anything that adds call-time overhead to collocated connections
- New dependencies in `itara-common`
- Features that only make sense for one specific transport or framework

If you want to propose something that falls into these categories, open a
discussion issue first. Some of these are closed questions. Others may be
revisited with the right argument.

---

## Code style

- Java 21, no preview features
- Standard Maven project layout
- No Lombok
- No framework magic in module boundaries
- Comments explain *why*, not *what*
- If a class is longer than 300 lines, it is probably doing too much

---

## Questions

Open a discussion issue or start a conversation in the existing issue thread.
The core team is small and responsive. We would rather answer a question
upfront than review a large PR that went in the wrong direction.
