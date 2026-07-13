# Itara Maven Archetypes

Scaffolding for new Itara Java projects. Four archetypes, covering the
combinations of project shape you're likely to want:

| Archetype | Generates | Use when |
|---|---|---|
| `itara-archetype-events` | A single events artifact | You need to declare event contracts for async/Kafka-style communication |
| `itara-archetype-component` | A two-module project (API + implementation) with a parent pom | You're starting a new component and both modules live together, in one repo |
| `itara-archetype-component-api` | The API module, standalone | Polyrepo setups — the API contract needs to live in its own repo, separate from any implementation |
| `itara-archetype-component-impl` | The implementation module, standalone | Polyrepo setups — implementing a component whose API artifact already exists elsewhere |

All four are published under `groupId=io.itara`, alongside the rest of the
Itara reference implementation, currently at `version=1.0-SNAPSHOT`.

---

## Common parameters

Every archetype asks for the standard Maven properties (`groupId`,
`artifactId`, `version`, `package`) — `groupId` is your own group id, not
Itara's, and is never defaulted.

In addition, every archetype asks for:

| Parameter | Meaning | Default |
|---|---|---|
| `componentName` | PascalCase name used to derive generated class/interface names (e.g. `Calculator` → interface `Calculator`, impl `CalculatorImpl`, activator `CalculatorActivator`) | *(required)* |
| `javaVersion` | Target Java version, used for `maven.compiler.source`/`target` | `21` |
| `itaraVersion` | Version of `itara-common` (and other `io.itara` artifacts) to depend on | *(required — no default, since versions change frequently pre-1.0)* |

`itara-archetype-component-impl` additionally asks for the coordinates of the
API artifact it implements, since — unlike the two-module archetype — there's
no sibling module in the same reactor to depend on:

| Parameter | Meaning |
|---|---|
| `apiGroupId` | `groupId` of the API artifact being implemented |
| `apiArtifactId` | `artifactId` of the API artifact being implemented |
| `apiVersion` | `version` of the API artifact being implemented |

---

## `itara-archetype-events`

Generates a single-module events artifact with one example event contract.

```bash
mvn archetype:generate \
  -DarchetypeGroupId=io.itara \
  -DarchetypeArtifactId=itara-archetype-events \
  -DarchetypeVersion=1.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=order-events \
  -Dversion=1.0-SNAPSHOT \
  -Dpackage=com.example.events \
  -DcomponentName=OrderEvents \
  -DjavaVersion=21 \
  -DitaraVersion=1.0-SNAPSHOT \
  -DinteractiveMode=false
```

Note that `componentName` here names the *example contract*
(`${componentName}EventContract`), not the artifact itself — an events
artifact can (and usually will) contain many contracts. Rename the generated
example and add further contracts alongside it, following the same pattern.

---

## `itara-archetype-component`

Generates a parent pom plus `${artifactId}-api` and `${artifactId}-component`
submodules.

```bash
mvn archetype:generate \
  -DarchetypeGroupId=io.itara \
  -DarchetypeArtifactId=itara-archetype-component \
  -DarchetypeVersion=1.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=calculator \
  -Dversion=1.0-SNAPSHOT \
  -Dpackage=com.example.calculator \
  -DcomponentName=Calculator \
  -DjavaVersion=21 \
  -DitaraVersion=1.0-SNAPSHOT \
  -DinteractiveMode=false
```

Produces:
- `calculator/` — parent pom, aggregates the two modules below
- `calculator/calculator-api/` — the `Calculator` interface, package `${package}.api`
- `calculator/calculator-component/` — `CalculatorImpl`, `CalculatorActivator`, package `${package}.component`

The activator's registry lookup is left commented out, since a fresh scaffold
has no real dependency to fetch — uncomment and adapt once the implementation
actually needs something from another component.

---

## `itara-archetype-component-api`

Generates the API module standalone, for polyrepo setups. No `-api` suffix is
added to `artifactId` — name it whatever you'd name the repo/module.

```bash
mvn archetype:generate \
  -DarchetypeGroupId=io.itara \
  -DarchetypeArtifactId=itara-archetype-component-api \
  -DarchetypeVersion=1.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=calculator-api \
  -Dversion=1.0-SNAPSHOT \
  -Dpackage=com.example.calculator \
  -DcomponentName=Calculator \
  -DjavaVersion=21 \
  -DitaraVersion=1.0-SNAPSHOT \
  -DinteractiveMode=false
```

Generates the `Calculator` interface under `${package}.api`.

---

## `itara-archetype-component-impl`

Generates the implementation module standalone, for polyrepo setups. Depends
on an API artifact generated separately (elsewhere, possibly by someone
else) via `apiGroupId`/`apiArtifactId`/`apiVersion`.

```bash
mvn archetype:generate \
  -DarchetypeGroupId=io.itara \
  -DarchetypeArtifactId=itara-archetype-component-impl \
  -DarchetypeVersion=1.0-SNAPSHOT \
  -DgroupId=com.example \
  -DartifactId=calculator-component \
  -Dversion=1.0-SNAPSHOT \
  -Dpackage=com.example.calculator \
  -DcomponentName=Calculator \
  -DjavaVersion=21 \
  -DitaraVersion=1.0-SNAPSHOT \
  -DapiGroupId=com.example \
  -DapiArtifactId=calculator-api \
  -DapiVersion=1.0-SNAPSHOT \
  -DinteractiveMode=false
```

**Known rough edge:** the generated `${componentName}Impl` and
`${componentName}Activator` classes `import ${package}.api.${componentName}`
— an *inferred* guess at the API artifact's package and interface name, based
on the convention `itara-archetype-component`/`-component-api` follow. If the
API artifact you're implementing used a different package suffix or a
different interface name, fix the import and `implements` clause by hand;
it'll surface immediately as a compile error, so it's hard to miss.

---

## A note on the generated `.itara` files

Every archetype generates a minimal `.itara` metadata file stub — just the
required `[artifact]` fields, marked with a `TODO` comment. This is
intentional, not an oversight: full `.itara` generation (deriving fields from
the actual code, keeping them in sync, etc.) is planned as a future build
tool responsibility, not something the archetypes attempt today.
