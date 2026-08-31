# Example: Two Spring Boot services, colocated under Itara

This example shows two independently-developed Spring Boot services —
`order-service` and `inventory-service` — running as normal, complete
Spring Boot applications. Each can run standalone, exactly as it would
without Itara. Each can also run colocated with the other, in a single
JVM, wired together by Itara — with no changes to its existing business
logic, and identical business behavior either way. This example shows
both modes, and the structure a Spring Boot component needs in order to
be usable both ways.

## The problem this demonstrates

Two Spring Boot services normally run as two separate processes, each with
its own embedded Tomcat, its own dependency tree, its own Spring context.
Colocating them naively — same JVM, same classpath — doesn't work: their
dependency trees don't have to agree, and even the plumbing each of them
takes for granted (an embedded servlet container, in particular) makes
assumptions that only hold when it's the only one in the process. Itara's
classloader isolation gives each component its own classloader, so two
Spring Boot services can share a process the same way two Docker
containers share a host — coexisting, not merging.

## Running each service standalone (no Itara at all)

Neither service is packaged as a runnable Spring Boot jar (no
`spring-boot-maven-plugin` repackaging, no manifest `Main-Class`) — both
are built as plain jars. Spring Boot jars, due to their unique structure,
are not compatible with Itara. Run either directly by naming its main class on
the classpath:

```bash
mvn clean install

java -cp inventory-service/target/inventory-service-1.0-SNAPSHOT.jar \
     com.example.inventory.InventoryApplication
```
```bash
java -cp order-service/target/order-service-1.0-SNAPSHOT.jar \
     com.example.order.OrderApplication
```

- `inventory-service` listens on `:8082` — `GET /inventory/{itemId}`,
  `POST /inventory/{itemId}/reserve`, `GET /inventory/lazy-check`.
- `order-service` listens on `:8081` — `POST /order/{itemId}` (calls
  inventory over plain HTTP via `RestClient`), `GET /order/lazy-check`.

Try them independently, exactly as you would any two microservices:

```bash
curl http://localhost:8082/inventory/widget
curl -X POST http://localhost:8081/order/widget
```

## What a Spring Boot component needs, to also run under Itara

A component that runs both standalone and under Itara has this structure:

1. **An `-api` module** (`order-api`, `inventory-api`) — a plain interface
   annotated `@ComponentInterface`, describing the contract Itara wires
   other components against.

2. **An activator** (`OrderActivator`, `InventoryActivator`) implementing
   `ItaraActivator`. Its `activate()` method calls `SpringApplication.run(...)`
   — the same call `main()` makes — and returns the bean implementing the
   component's own contract interface.

3. **A second `@SpringBootApplication`-annotated bootstrap class**
   (`OrderItaraConfig`, `InventoryItaraConfig`), used only by the
   activator, separate from the standalone entry point (`OrderApplication`,
   `InventoryApplication`). Both classes live in the same package, so each
   explicitly excludes the other from its own `@ComponentScan` — without
   the exclude filter, Spring picks up both and collides on duplicate bean
   definitions.

4. **A `META-INF/itara/activator` file** inside the component's jar,
   containing the activator's fully qualified class name — the mechanism
   Itara uses to discover it — and a matching `.itara` metadata file per
   artifact (`order-api.itara`, `order-service.itara`, etc.) declaring its
   kind, id, and version. Both are required for any component, Spring
   Boot or otherwise; see `metafiles/` in the deployment layout below.

5. **`order-service`'s `InventoryClient` implementation differs by run
   mode.** The standalone bootstrap (`OrderApplication`) wires a
   `RestClient`-backed implementation, calling inventory over real HTTP.
   The Itara bootstrap (`OrderItaraConfig`) wires an implementation that
   fetches the real `InventoryClient` directly from `ItaraRegistry` — an
   in-process call, no network hop, no serialization — which is what the
   `direct` transport provides.

Everything else — `OrderController`, `OrderService`, `InventoryController`,
`InventoryService`, the `@Lazy` diagnostic beans — is identical in both
modes.

### A deployment-side requirement: promoting Tomcat to the shared classloader

Embedded Tomcat (unlike standalone Tomcat) never installs a
classloader-aware `LogManager` replacement, and separately,
`TomcatURLStreamHandlerFactory` guards `URL.setURLStreamHandlerFactory()`
— a genuine JDK-enforced singleton, callable once per JVM, ever — with a
guard scoped to whichever classloader loaded it. Two isolated components,
each bundling their own private copy of `tomcat-embed-core`, each think
they're the first to register — the second one crashes the process on
startup.

The fix is deployment layout, not code: `tomcat-embed-core`,
`tomcat-embed-websocket`, `tomcat-embed-el`, and `jakarta.annotation-api`
all belong in the **shared** classloader (`lib/`), not each component's
own private directory. Parent-first delegation makes this correct
automatically — each component's own jar still contains its own copy of
these classes, it's just shadowed and never loaded.

## Running them colocated, under Itara

### 1. Build everything

```bash
mvn clean install
```

### 2. Assemble the deployment layout

```
deployment/
  lib/                              ← system classloader (shared)
    itara-core.jar
    order-api.jar
    inventory-api.jar
    tomcat-embed-core-10.1.31.jar
    tomcat-embed-websocket-10.1.31.jar
    tomcat-embed-el-10.1.31.jar
    jakarta.annotation-api-<version>.jar

  agent/
    itara-agent.jar                 ← referenced only via -javaagent, never on -cp

  components/                       ← isolated, per component
    order/
      order-service-1.0-SNAPSHOT.jar
    inventory/
      inventory-service-1.0-SNAPSHOT.jar

  metafiles/
    order-api.itara
    order-service.itara
    inventory-api.itara
    inventory-service.itara
  
  itara-libs/                       ← if you want any Itara plugins, drop them here

  wiring.yaml
```

The four `tomcat-embed-*`/`jakarta.annotation-api` jars can be pulled
straight from your local `.m2` (Maven already downloaded them building
the services) — see the `pom.xml` files' resolved versions for exact
coordinates. **The directory names under `components/` must exactly
match the component ids declared in `wiring.yaml`** — `order` and
`inventory` here — this is enforced, not just a convention.

`itara-agent.jar` is kept in its own directory, deliberately not inside
`lib/`: it is an instrumentation agent, referenced only by `-javaagent`,
and has no reason to sit on the application classpath.

### 3. `wiring.yaml`

```yaml
nodes:
  - id: "orderNode"
    component: "order"
  - id: "inventoryNode"
    component: "inventory"

connections:
  - id: "order-to-inventory"
    from: "orderNode"
    to: "inventoryNode"
    transport:
      id: "direct"
```

No `http` transport entries — each service still exposes its own external
REST endpoints directly through its own embedded Tomcat, exactly as it
does standalone. Only the `order` → `inventory` call goes through Itara,
using `direct`.

### 4. Run it

```bash
ITARA_COMPONENTS_DIR=deployment/components java \
  -Ditara.nodes="orderNode,inventoryNode" \
  -Ditara.config=deployment/wiring.yaml \
  -Ditara.metadata.dir=deployment/metafiles \
  -Ditara.lib.dir=deployment/itara-libs \
  -javaagent:deployment/agent/itara-agent.jar \
  -cp "deployment/lib/*" \
  dev.itara.runtime.ItaraMain
```

On PowerShell: `$env:ITARA_COMPONENTS_DIR="deployment/components"; java ...`.

## What to expect in the logs

`ItaraMain` eagerly activates every local component at startup and fails
fast if any of them can't start — so you should see, in order:

1. Isolation mode confirmed: `activator scan mode=isolated`.
2. **Both** Spring Boot banners print, one after the other, in the same
   process — two full `SpringApplication.run()` bootstraps, two embedded
   Tomcats, two ports (`8081`, `8082`), both listening by the time startup
   finishes. No crash, no `factory already defined` error — that error is
   exactly what you'd see if the `tomcat-embed-*` jars were left in each
   component's own private directory instead of `lib/`; try moving them
   back in to see it for yourself.
3. `[Itara] component ready` — both components are up and serving real
   HTTP traffic on their own ports, independently.

Then, exercising it:

```bash
curl -X POST http://localhost:8081/order/widget
```
This goes through `order`'s own controller → the real, in-process
`InventoryClient` fetched from the registry → `inventory`'s actual Spring
bean — no network call for this hop, even though both services are
otherwise indistinguishable from their standalone selves.

```bash
curl http://localhost:8081/order/lazy-check
curl http://localhost:8082/inventory/lazy-check
```
Each `@Lazy` diagnostic bean fires on first access, on a real Tomcat
request thread, and logs the classloader it observes — confirming each
component sees its *own* isolated classloader, not the other's and not
the shared one, even for framework-level lazy initialization deep inside
Spring's own machinery.
