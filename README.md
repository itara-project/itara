# Itara

**Make software soft again.**

Itara is a runtime framework that treats distributed system topology as a
configuration decision, not a code decision.

Change how your components communicate — collocated direct calls, HTTP,
message queues — by changing a config file. No code changes. No redeployment
ceremony. No migration scripts. Restart the JVM with a new config and the
topology changes.

---

## The problem

Every production system in the world handles topology change through ritual.
Want to split a service? Months of parallel running, dual-write patterns,
careful traffic migration. Want to merge two services? Same thing in reverse.
Want to change from HTTP to a message queue between two components? Touch
both services, coordinate deployment, pray.

This is the state of the art. The patterns are elegant — blue-green deployments,
expand-and-contract, strangler fig — but every one of them is ceremony. External
scaffolding bolted around systems that fundamentally cannot evolve themselves.

Itara proposes that topology should be a continuously adjustable variable,
not a hardcoded consequence of how services were originally written.

---

## The idea

A component declares its contract — what it accepts and what it returns.
It does not declare how it is called. That is the runtime's decision.

The Itara agent intercepts the JVM startup, reads a wiring config, and
connects components to each other using whatever transport the config specifies.
The component code is identical regardless of whether it is called as a direct
in-process method or over HTTP from a separate JVM.

```yaml
# Change this file. Restart the JVM. Topology changes.

connections:
  - from: gateway
    to:   calculator
    type: direct      # or: http, async — code does not change
```

---

## The proof of concept

Two components. One adds numbers. One accepts requests and delegates.

**Direct topology — both components in one JVM:**

```
[Gateway] Received request: add(3, 4)
[Calculator] add(3, 4) = 7
[Gateway] Returning: The result of 3 + 4 = 7
```

**HTTP topology — two separate JVMs:**

```
# Gateway JVM:
[Gateway] Received request: add(3, 4)
[Itara/HTTP] -> add on calculator at localhost:8081
[Gateway] Returning: The result of 3 + 4 = 7

# Calculator JVM:
[Itara/HTTP] <- add on calculator
[Calculator] add(3, 4) = 7
```

Same gateway code. Same calculator code. Different config file.
The gateway JVM does not have calculator-component.jar on its classpath
in the HTTP run — it never sees the implementation. The agent generates
a proxy from the API jar alone.

---

## Structure

```
itara-common/           Annotations, registry, activator interface, proxy interface. No external deps.
itara-agent/            JVM agent. proxy implementation discovery and generation. custom class loader
itara-transport-http/   ByteBuddy proxy generation, HTTP server/client.
itara-demo/             Hello world demo. Two components, three wiring configs.
```

### Key concepts

**Contract** — an abstract class annotated @ComponentInterface. Lives in an
API jar. Defines what the component does. Says nothing about how it is called.

**Component** — one implementation of a contract. Lives in a component jar.
Has no knowledge of transport or topology.

**Activator** — one class per component jar implementing ItaraActivator.
Constructs the component's internal object graph and returns the root instance.
Discovered via META-INF/itara/activator.

**Wiring config** — a YAML file defining connections between components.
The agent reads this at JVM startup.

---

## Running the demo

Build order:

```
cd itara-common          && mvn install
cd itara-agent           && mvn package
cd itara-transport-http  && mvn install
cd itara-demo            && mvn install
```

The agent loads the runtime jars from the specified dir first (-Ditara.lib.dir=/path/to/runtime/jars/dir),
then from the system class path if not found (child-first approach),
so move the itara-transport-http and other runtime jars to the libs directory

**Direct topology (one JVM):**

```
java "-Ditara.lib.dir=libs"
     "-Ditara.config=itara-demo/wiring-direct.yaml"
     -javaagent:itara-agent/target/itara-agent-1.0-SNAPSHOT.jar
     -cp "itara-common/target/itara-common-1.0-SNAPSHOT.jar;
          itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar;
          itara-demo/calculator-component/target/calculator-component-1.0-SNAPSHOT.jar;
          itara-demo/gateway-api/target/gateway-api-1.0-SNAPSHOT.jar;
          itara-demo/gateway-component/target/gateway-component-1.0-SNAPSHOT.jar"
     demo.gateway.component.DemoMain
```

**HTTP topology — start calculator first, wait for "Server listening on port 8081":**

```
# Terminal 1
java "-Ditara.lib.dir=libs"
     "-Ditara.config=itara-demo/wiring-http-calculator.yaml"
     -javaagent:itara-agent/target/itara-agent-1.0-SNAPSHOT.jar
     -cp "itara-common/target/itara-common-1.0-SNAPSHOT.jar;
          itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar;
          itara-demo/calculator-component/target/calculator-component-1.0-SNAPSHOT.jar"
     itara.runtime.ItaraMain

# Terminal 2
java "-Ditara.lib.dir=libs"
     "-Ditara.config=itara-demo/wiring-http-gateway.yaml"
     -javaagent:itara-agent/target/itara-agent-1.0-SNAPSHOT.jar
     -cp "itara-common/target/itara-common-1.0-SNAPSHOT.jar;
          itara-demo/calculator-api/target/calculator-api-1.0-SNAPSHOT.jar;
          itara-demo/gateway-api/target/gateway-api-1.0-SNAPSHOT.jar;
          itara-demo/gateway-component/target/gateway-component-1.0-SNAPSHOT.jar"
     demo.gateway.component.DemoMain
```

calculator-component.jar is absent from the gateway classpath. The gateway
never sees the implementation.

---

## Current state

Working today: direct and HTTP connections, ByteBuddy proxy generation,
custom class loader for runtime and application classpath separation,
automatic constructor patching, activator-based lazy instantiation,
synthesized inbound HTTP server, zero code change between topologies.

Planned: orchestrator, controller, message queue connections, build plugin,
language-neutral contract descriptor, Spring adapter, mathematical models.

See VISION.md for the full architectural vision.

---

## Author

Gabor Kiss — concept, architecture, initial implementation. April 2026.

## License

Apache License 2.0 — see LICENSE.
