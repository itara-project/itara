# Island Scope for Direct-Connection AuthN/AuthZ — Deferred Design Note

**Status:** Identified during the authN/authZ implementation task, deferred to its
own epic. Not started.
**Related:** ADR 0021–0024, spec §15–§16, `ItaraRegistry`, `ItaraDispatcher`,
`ObservabilityDecorator` (scheduled for retirement per its own tracking issue,
independent of this note).

---

## How this came up

The authN/authZ task built full support for authentication and authorization
on remote (transport-based) connections: the two SPIs, the shared identity/
target/credential types, the registries, and the wiring through
`ItaraProxyHandler` and `ItaraDispatcher`. Direct (colocated, in-process)
connections were deliberately left for last, since today's direct-call path
is a simple pass-through with no per-connection state at all —
`ObservabilityDecorator.wrap()` decorates once per *component*, shared by
every caller in the JVM slice.

While scoping the direct-connection work, a pre-existing structural issue
surfaced that direct-connection authN/authZ cannot be built correctly
without addressing first:

**`ItaraRegistry`'s outbound lookup (`get(id, type)`) is keyed only by the
target's contract id, globally, with no notion of "who is asking."**

This was already slightly wrong before authN/authZ existed — if two
colocated components both depend on the same target component, but are
wired with different connection configuration (e.g. different failure
semantics), they silently share one proxy today, and whichever connection
activates it first wins. It didn't cause visible problems because nothing
observable depended on "which caller" a direct connection served. AuthN/AuthZ
makes it load-bearing: two colocated components calling the same target
might legitimately need *different* authentication or authorization
configuration for that connection, and a shared, wrongly-keyed proxy would
silently apply the wrong one.

The direct fix — key the proxy map by `(from, to)` instead of `to` alone —
is straightforward on its own. It runs into a real problem the moment the
"from" side has to come from somewhere.

## The security problem

If a component (or its generated/activator code) supplies its own "from"
identity as an ordinary argument when looking up a proxy, that identity is
trivially spoofable within a shared-classloader JVM slice. Concretely: if
components A and B are colocated, and A has a more permissive
authorization outcome for calls to C than B does, B's own code could call
the lookup claiming to be A and receive A's more permissive proxy — id
theft between colocated components, in-process, with no isolation needed
to pull it off (isolated classloader mode doesn't fully close this either,
since the lookup API itself is what would be trusting the caller's word).

This is the same category of problem authN/authZ already had to solve on
the callee side of remote connections — see why `ItaraDispatcher` verifies
the caller-declared `ItaraCallTarget` against its own fixed configuration
rather than trusting it outright, and why `produceAssertion()`/
`authenticate()` never take caller-suppliable identity as an argument that
influences the outcome. The same principle applies here: **"from" must be
established by the agent, at a point the calling code cannot influence,**
not supplied as a parameter.

## Proposed direction

The codebase already has a working precedent for exactly this shape of
problem, just applied to one piece of state: **TCCL switching.**
`ItaraRegistry.activateRaw()` and `ItaraDispatcher.dispatch()` both already
do "flip one piece of ambient, agent-controlled state before invoking
someone else's code, flip it back after" — component code never sets its
own thread context classloader; the agent sets it *at the boundary*,
unconditionally, every time control crosses from the agent into a
component.

The proposed direction generalizes this into a proper **scope object** —
not just a classloader swap, a full ambient-state carrier, switched via the
same try-with-resources idiom already used throughout this codebase
(`ItaraScope` for observability spans, `ItaraContext`'s stack, the existing
TCCL swaps). Working name: **island scope**, following the "islands and
tunnels" framing — each component is an isolated island (already true for
classloading in isolated mode; conceptually true for every component
regardless of mode), and the agent-maintained proxies/dispatchers are the
only tunnels between them. Whenever control moves from one island into
another through a tunnel, the island scope for the new island needs to be
in effect for the duration of that call.

At minimum, the scope needs to carry:
- **Itara-internal identity** — which component/node is currently
  executing, readable only by Itara's own internals (the registry, in
  particular), never influenced by anything the component supplies as an
  argument.
- Possibly, some subset exposed to component code itself, if a real use
  case for that emerges (not yet identified).

### Every tunnel-crossing point needs it, not just the new direct-proxy one

This is not scoped to "the new direct-call proxy." Three existing crossing
points would all need to open this scope, or the mechanism has a gap the
moment any of them is skipped:

1. `ItaraDispatcher.dispatch()` — already TCCL-switches before
   `method.invoke()`. Needs to open the island scope for the same
   duration, so a component reached over a *remote* connection still has a
   trustworthy "who am I" available if it turns around and calls another
   component directly.
2. `ItaraRegistry.activateRaw()` — already TCCL-switches before
   `activator.activate()`. Activation code (constructors, dependency
   wiring) can itself make outbound calls before the component is fully
   up; it needs the scope too.
3. The new direct-call proxy (not yet built) — the actual trigger for this
   note. Every direct-to-direct call is itself a tunnel crossing.

### Connection to what authN/authZ already built

The "current node" concept this scope needs to carry is the same concept
`ItaraCallTarget.getNode()` and `ItaraDispatcher`'s fixed `nodeId`
constructor field already represent — just static per dispatcher instance
today instead of ambient. Whether the dispatcher's own `nodeId` field
becomes redundant once the scope exists, or remains the value the scope
gets *populated from* at that particular boundary, needs to be worked out
explicitly as part of this design — not left as two competing sources of
truth for "what node is this."

### Why this is its own epic, not a tail-end addition to this task

Everything above is a security-relevant, cross-cutting mechanism that
several existing boundary points depend on getting uniformly right. Doing
it under this task's remaining time risked exactly the failure mode this
kind of mechanism is least forgiving of: a design that looks locally
sensible at each of the three crossing points individually, but has a gap
at the seam between them that only shows up once something actively probes
for it — the same class of mistake this task's own design conversations
spent real effort avoiding elsewhere (the transport-credential typing, the
per-call config passing, the target-mismatch check). It deserves its own
design pass and its own test plan, not a bolt-on.

## Current state and the interim safety rail

- Direct connections remain exactly what they were before this task: a
  zero-overhead pass-through, no authentication, no authorization,
  unaffected by anything built here.
- `ConnectionEntry.validate()` now fails fast — a hard configuration error
  at startup, not a silent no-op — if a direct connection declares an
  `authentication` or `authorization` block with anything other than
  `noop`. Before this task, direct auth was simply "not built yet, coming
  later in this task"; a silent no-op was a reasonable interim state.
  Once it's deliberately deferred to a future, unscheduled epic, the same
  silence becomes a trap — someone could configure `authentication: {id:
  mtls}` on a direct connection and get zero enforcement with no
  indication anything is wrong. The validation converts that into
  something that can't be misconfigured by accident.
- Everything else built in this task — both SPIs, the shared identity/
  target/credential types, the two registries, the remote-side proxy and
  dispatcher wiring, the `PERMISSION` error kind, and the HTTP/Kafka
  transport updates — is complete, tested, and entirely unaffected by this
  deferral. It only concerns direct connections specifically.

## Scope for the follow-up epic

- Design and implement the island scope type: what it carries, its
  lifecycle/API, and how it composes with the existing TCCL swap (replaces
  it, wraps it, or sits alongside it needs a decision, not an assumption).
- Wire it into all three crossing points: `ItaraDispatcher.dispatch()`,
  `ItaraRegistry.activateRaw()`, and the new direct-call proxy.
- Rework `ItaraRegistry`'s outbound proxy map to key by `(from, to)`,
  sourcing `from` exclusively from the island scope — never from a
  caller-supplied argument.
- Build the actual direct-call proxy that can hold and invoke
  authentication and authorization per `(from, to)` connection, without
  losing the zero-overhead property for connections that configure
  neither — the original ask this task deferred.
- Remove the `ConnectionEntry.validate()` restriction added above once
  direct authN/authZ is real.
- Test coverage: the boundary-crossing behavior specifically — a component
  attempting to claim another's identity must fail, across all three
  crossing points, not just the happy path of correct self-identification.
