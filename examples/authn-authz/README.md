# Example: Authentication and Authorization

This example shows Itara's authentication and authorization SPIs end to
end: two illustrative, example-only plugins — a shared-secret
authentication and an allow/deny-by-method authorization — wired onto
real connections between three components, run two ways: distributed
(three separate processes, real HTTP) and colocated (one process,
isolated classloader mode, `direct` transport). Same component code,
completely unchanged, in both. Only `wiring.yaml` differs.

## What this demonstrates

Three components: `backend` (the callee, two trivial methods), and two
externally-reachable gateways, `gateway-a` and `gateway-b`, each
delegating straight through to the matching method on `backend`. Neither
gateway nor `backend` contains a single line of authentication or
authorization code — both are entirely connection-level concerns,
configured in `wiring.yaml` and invisible to component code, exactly as
the spec requires (§15, §16).

- `gateway-a`'s connection to `backend` presents the correct shared
  secret and authenticates as `gateway-a`. Its authorization rules allow
  `shout` and deny `whisper` — same identity, same secret, different
  outcome depending purely on which method is called.
- `gateway-b`'s connection to `backend` presents the wrong secret.
  Authentication rejects the call before authorization is ever
  consulted — regardless of which method was requested, since the
  caller's identity was never established in the first place.

The point this example exists to make: running `gateway-a`'s connection
to `backend` as `direct` instead of `http` produces identical
authorization behavior. Colocation is a placement decision, not a trust
boundary (ADR 0025) — nothing about being in the same process exempts a
connection from the same checks a connection between two separate
processes gets.

**A known, deliberate gap in the colocated case, for now:** the colocated
deployment only demonstrates authorization, not authentication
rejection, and only involves `gateway-a` (`gateway-b`'s scenario isn't
reproduced there). A `direct` connection's authentication is currently
resolved once and shared between its caller and callee sides, so a
shared-secret mismatch can't yet be demonstrated on this path the way it
can across a real process boundary. This is tracked, not an oversight —
planned alongside splitting a connection's wiring config into separate
caller-side and callee-side halves generally (transport, serializer,
and authentication alike). This example will be extended to cover it
once that lands.

## The two plugins

Both live only in this example — not part of Itara's core distribution,
and neither is production-grade (see each class's own javadoc for
specifics on what's cut).

**`shared-secret-authn`** (`ItaraAuthentication`) — the caller presents a
secret and proves possession of it; it never gets to declare its own
identity. The identity a successful authentication produces (`subject`)
comes entirely from the *callee's own* configuration for that connection,
never from anything the caller sent — the same connection config both
sides read, just from their own process.

**`rule-table-authz`** (`ItaraAuthorization`) — reads two lists of method
names from its connection's own config, `allow` and `deny`. Deny always
wins; a non-empty allow list then restricts everything else.

## Layout

```
authn-authz/
  pom.xml                          ← parent, single `mvn install` builds everything
  auth-plugins/
    shared-secret-authn/           ← the ItaraAuthentication example plugin
    rule-table-authz/              ← the ItaraAuthorization example plugin
  backend/
    backend-api/                  ← BackendService: shout(String), whisper(String)
    backend-impl/                 ← trivial impl + activator, no auth-awareness
  gateway-a/
    gateway-a-api/                ← GatewayAService, mirrors backend 1:1
    gateway-a-impl/                ← delegates to backend, no auth-awareness
  gateway-b/
    gateway-b-api/
    gateway-b-impl/                ← identical shape to gateway-a
  deployment/
    wiring-distributed.yaml        ← 3 nodes, http, all three components
    wiring-colocated.yaml          ← 2 nodes, direct, gateway-a + backend only
    metafiles/                     ← .itara files for every artifact, both scenarios
    lib/                           ← shared classloader: itara-core + every -api jar
    itara-libs/                    ← Itara plugins: http transport, json serializer,
                                       both example auth plugins
    components/                    ← -impl jars, one subdirectory per component id,
                                       used only by the colocated run
      backend/
        backend-impl-1.0-SNAPSHOT.jar
      gateway-a/
        gateway-a-impl-1.0-SNAPSHOT.jar
      gateway-b/
        gateway-b-impl-1.0-SNAPSHOT.jar
    agent/
      itara-agent.jar
```

One `deployment/` directory serves both scenarios — which wiring file and
which `-cp`/`-javaagent` invocation you use is what selects distributed
vs. colocated, not a different directory tree. `deployment/components/`
holds all three `-impl` jars regardless of scenario; the distributed
commands put a process's own component jar directly on `-cp` (no
isolation needed — separate OS processes already provide it), while the
colocated command instead sets `ITARA_COMPONENTS_DIR` and relies on
isolated classloader mode to find each one by its component id.

Six `.itara` metadata files (three components × api/impl) plus two more
for the auth plugins, plus the two core plugins already published
alongside Itara itself (`itara-transport-http`, `itara-serializer-json`)
— all ten sit in the one `metafiles/` directory and cover both wiring
files, since a `.itara` file describes an artifact, not a scenario.

## Building it

```bash
mvn clean install
```

Builds every module — both auth plugins, and all three components'
`-api`/`-impl` jars.

## Running it — distributed

Three separate processes. `backend` needs to be listening before either
gateway tries to call it, so start it first.

**1. Start `backend`.** It's the callee for both `gateway-a-to-backend`
and `gateway-b-to-backend`, so its own environment carries *its*
expectation for each — `s3cr3t-a` for `gateway-a` (matching what
`gateway-a` will actually present), and `backend-b-expected-secret` for
`gateway-b` (which, notably, `gateway-b` itself never gets told — see
step 3):

```bash
GATEWAY_A_SECRET=s3cr3t-a GATEWAY_B_SECRET=backend-b-expected-secret java \
  -Ditara.nodes="backendNode" \
  -Ditara.config="deployment/wiring-distributed.yaml" \
  -Ditara.metadata.dir="deployment/metafiles" \
  -Ditara.lib.dir="deployment/itara-libs" \
  -javaagent:deployment/agent/itara-agent.jar \
  -cp "deployment/lib/*:deployment/components/backend/*" \
  dev.itara.runtime.ItaraMain
```

**2. Start `gateway-a`** in a second terminal, once `backend` shows
`[Itara] component ready`. Its own secret matches what `backend` expects
for this connection:

```bash
GATEWAY_A_SECRET=s3cr3t-a java \
  -Ditara.nodes="gateway-aNode" \
  -Ditara.config="deployment/wiring-distributed.yaml" \
  -Ditara.metadata.dir="deployment/metafiles" \
  -Ditara.lib.dir="deployment/itara-libs" \
  -javaagent:deployment/agent/itara-agent.jar \
  -cp "deployment/lib/*:deployment/components/gateway-a/*" \
  dev.itara.runtime.ItaraMain
```

**3. Start `gateway-b`** in a third terminal. Deliberately: no
`GATEWAY_B_SECRET` set here at all. It falls through to
`wiring-distributed.yaml`'s own default (`not-the-real-secret`) — which
was never told to anyone as the real one, because it isn't. This is the
misconfiguration the third scenario below demonstrates:

```bash
java \
  -Ditara.nodes="gateway-bNode" \
  -Ditara.config="deployment/wiring-distributed.yaml" \
  -Ditara.metadata.dir="deployment/metafiles" \
  -Ditara.lib.dir="deployment/itara-libs" \
  -javaagent:deployment/agent/itara-agent.jar \
  -cp "deployment/lib/*:deployment/components/gateway-b/*" \
  dev.itara.runtime.ItaraMain
```

On PowerShell, replace the leading `VAR=value` prefixes with
`$env:VAR="value"; ...` before each `java` command.

All three processes will keep running — `Ctrl+C` each when done.

### What to expect

```bash
curl -X POST http://localhost:8080/itara/gateway-a/shout \
     -H "x-itara-dispatch-key: external-to-gateway-a" \
     -H "x-itara-target-method: shout" \
     -d '["somethiNG"]'
```
```
"SOMETHING!"
```
`gateway-a`'s connection to `backend` presents the correct shared secret
— authentication succeeds, producing the identity `gateway-a` — and
`shout` is on that connection's allow list, so authorization permits it.
The call reaches `BackendServiceImpl.shout()`, which knows nothing about
any of this.

```bash
curl -X POST http://localhost:8080/itara/gateway-a/whisper \
     -H "x-itara-dispatch-key: external-to-gateway-a" \
     -H "x-itara-target-method: whisper" \
     -d '["somethiNG"]'
```
```json
{"errorKind":"PERMISSION","remoteExceptionClass":"AuthorizationDenied","message":"method 'whisper' is on this connection's deny list"}
```
Same connection, same secret, same authenticated identity — `whisper` is
on the deny list, and `backend` never gets called. `errorKind` is the one
thing to branch on programmatically; `remoteExceptionClass` says which
check failed, and the message is whatever the authorization
implementation chose to say.

```bash
curl -X POST http://localhost:8090/itara/gateway-b/shout \
     -H "x-itara-dispatch-key: external-to-gateway-b" \
     -H "x-itara-target-method: shout" \
     -d '["somethiNG"]'
```
```json
{"errorKind":"PERMISSION","remoteExceptionClass":"AuthenticationRejected","message":"shared secret did not match this connection's configured secret"}
```
`gateway-b`'s connection to `backend` presents the wrong secret.
Authentication rejects the call before authorization is ever consulted —
`whisper` would fail exactly the same way, for the same reason, since the
caller's identity was never established. `errorKind`, `remoteExceptionClass`,
and message shape are identical to the authorization-denial case above —
deliberate (ADR 0026): from the caller's side, "you aren't who you
claimed to be" and "you are, but you can't do this" are both just "you
weren't permitted to make this call." Code that wants to tell them apart
still can, via `remoteExceptionClass`.

## Running it colocated

```bash
ITARA_COMPONENTS_DIR=deployment/components java \
  -Ditara.nodes="gateway-aNode,backendNode" \
  -Ditara.config="deployment/wiring-colocated.yaml" \
  -Ditara.metadata.dir="deployment/metafiles" \
  -Ditara.lib.dir="deployment/itara-libs" \
  -javaagent:deployment/agent/itara-agent.jar \
  -cp "deployment/lib/*" \
  dev.itara.runtime.ItaraMain
```
On PowerShell: `$env:ITARA_COMPONENTS_DIR="deployment/components"; java ...`.

One process, both nodes, isolated classloader mode — `gateway-a` and
`backend` each get their own classloader, the same guarantee the
distributed deployment gets from being two separate OS processes.

Same two calls, same connection id, same rules — just one process
instead of three, and no separate `backend` process to start first:

```bash
curl -X POST http://localhost:8080/itara/gateway-a/shout \
     -H "x-itara-dispatch-key: external-to-gateway-a" \
     -H "x-itara-target-method: shout" \
     -d '["somethiNG"]'
```
```
"SOMETHING!"
```

```bash
curl -X POST http://localhost:8080/itara/gateway-a/whisper \
     -H "x-itara-dispatch-key: external-to-gateway-a" \
     -H "x-itara-target-method: whisper" \
     -d '["somethiNG"]'
```
```json
{"errorKind":"PERMISSION","remoteExceptionClass":"AuthorizationDenied","message":"method 'whisper' is on this connection's deny list"}
```

Identical results to `gateway-a`'s own calls in the distributed
deployment — same allow, same deny, same error shape — despite
`gateway-a-to-backend` now being an in-process call with no wire at all.
Placement changed how the call gets from `gateway-a` to `backend`, and
changed nothing about whether it's permitted.
