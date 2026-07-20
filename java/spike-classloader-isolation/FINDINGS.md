# Classloader Isolation Spike — Running Findings

Raw notes captured as the spike progresses. Not the final conclusions
document — that gets written at the end, from this. Kept here so nothing
gets lost between now and then.

---

## Finding 1: In-process proxy can be defined under the wrong classloader —
## happens on every activation, not just when it nests

**Status:** Confirmed via logs, on two separate occasions — once during
nested activation (`conflict-a`/`conflict-b`), once with no nesting at all
(`inventory`, activated directly by `ItaraMain`). Currently harmless. Real,
reproducible root cause, not a fluke.

**Correction from the original write-up:** this was first described as
something that happens "when activation nests" — that framing was wrong.
The `inventory` run (below) shows it happening on a plain, top-level,
non-nested activation triggered directly by `ItaraMain.activateAllLocal()`.
Nesting was simply how it was first noticed, not the actual condition.

**What actually happens:** `ItaraRegistry.decorate()` uses
`Thread.currentThread().getContextClassLoader()` as the *defining*
classloader for the dynamic `Proxy` it creates
(`ObservabilityDecorator.wrap(...)`). `decorate()` is always called from
`get()`'s `computeIfAbsent`, strictly *after* `activateRaw()`'s `finally`
block has already restored TCCL to whatever it was before activation
started. So `decorate()` always reads a TCCL that has already been
reverted — never the target component's own classloader — regardless of
whether another component's activation happens to be on the call stack at
the time. In the nested case (`conflict-a`/`conflict-b`), that reverted
value happened to be another component's loader. In the plain top-level
case (`inventory`, activated directly from `ItaraMain`'s main thread), it's
simply the system classloader baseline. Same root cause, two different
manifestations.

**Confirmed via log** (nested case, 2026-07-15 17:27:39):
```
decorate component=conflict-b thread=HTTP-Dispatcher(24)
  tcclAtDecorateTime=conflict-a@1343441044
  definingProxyUnder=conflict-a@1343441044
  targetOwnClassLoader=conflict-b@1789447862
```
**Confirmed via log** (non-nested case, 2026-07-20 14:38:05):
```
decorate component=inventory thread=main(1)
  tcclAtDecorateTime=AppClassLoader(system)
  definingProxyUnder=AppClassLoader(system)
  targetOwnClassLoader=inventory@1586270964
```

**Why it's harmless today:** the defining classloader only affects which
loader generates the `Proxy` subclass's bytecode — it's independent of which
classloader gets set as TCCL when the proxy's `invoke()` actually runs later
(that's a fresh `classLoaders.get(id)` lookup in
`ObservabilityDecorator`/`ItaraDispatcher`, not a live TCCL read). Under
parent-first delegation, any ancestor classloader (system, or another
component's loader) can see the shared API interface fine, since it's on
the system classloader either way, so `Proxy.newProxyInstance` succeeds
regardless of which classloader ends up defining it.

**Why it matters anyway / effect on the real implementation:** it's an
**accident of whatever TCCL happens to be current post-restore**, not a
designed guarantee — and now confirmed to occur on literally every
activation, not just nested ones. It currently causes no failure only
because parent-first delegation gives every classloader visibility into
everything on the system classloader. If a future change ever narrows that
visibility for some component (e.g. a child-first override for a specific
deployment group — floated as a future option in ADR-0018's
"Consequences" section), this becomes a latent bug whose trigger
(activation order / which thread happens to call `get()` first) is
unrelated to anything anyone would think to check.

**Recommended fix for the full implementation:** `decorate()` should use
the *target's own* registered classloader (`classLoaders.get(id)`) as the
defining classloader for the proxy, not whatever TCCL happens to be at the
moment `decorate()` runs. One-line change, removes the dependency on
post-activation TCCL state entirely. Deferred in the spike itself since
it's spike code and the risk is currently dormant, but should not be
deferred in the real implementation.

---

## Finding 2: Spring Boot initialises correctly end-to-end under an
## isolated component classloader, including its own internal machinery

**Status:** Confirmed, no caveats, for the single-component case
(`inventory` activated alone, no colocated peer yet).

**What was validated:** `SpringApplication.run()` — `@ComponentScan`,
`SpringFactoriesLoader`'s auto-configuration resolution, embedded Tomcat
startup, `DispatcherServlet` initialisation — ran entirely under
`inventory`'s isolated classloader (the TCCL `activateRaw()` swapped in),
with no fallback to the system classloader anywhere in the bootstrap.

**The strongest part of this result:** Tomcat's own internally-created
`TomcatEmbeddedWebappClassLoader` correctly parented itself to
`inventory`'s isolated loader, not the system classloader — confirmed by
matching object identity (`java.net.URLClassLoader@5e8c92f4` in Tomcat's
own log output is the same object, in hex, as `inventory@1586270964` from
the activation log). This wasn't something explicitly coded for — it's
Spring Boot/Tomcat's own internal logic correctly inheriting the ambient
TCCL several layers deep, which is exactly what needs to happen for
isolation to hold through Spring's *entire* stack, not just at the
outermost `activate()` call.

**Also confirmed:** the `@Lazy` diagnostic bean, firing later on a genuine
Tomcat request thread (`http-nio-8082-exec-3`), correctly saw
`TomcatEmbeddedWebappClassLoader` with `inventory`'s isolated loader as
parent — the same shape as the pre-Itara standalone baseline, just
correctly anchored one level lower, as isolation should shift it. And
`InventoryService.class.getClassLoader()` resolved to `inventory`'s own
loader, confirming the component's private class is served correctly.

**Also confirmed:** external HTTP calls to `inventory`'s own endpoints
(`/inventory/{itemId}`, `/reserve`, `/lazy-check`) never touch
`ItaraDispatcher` or `decorate()` at all — activation happens exactly once,
at `ItaraMain` startup, and everything after that is plain Spring Boot
serving requests with zero Itara involvement. Matches the intended shape
for a component that owns its own external listener rather than being
reached only through Itara's transports.

**Not yet tested by this result:** property conflicts and
`SpringFactoriesLoader` cross-contamination — both require the *colocated*
pair (`inventory` + `order` together), which this single-component run
doesn't exercise. This finding establishes the mechanism works correctly
for one isolated Spring Boot app; the two MUST-investigate items from the
issue still need the pair running together.

---

## Finding 3: TCCL correctly resets between calls under load — but the test
## didn't exercise real cross-component thread-pool reuse

**Status:** Partially confirmed, partially inconclusive. Positive result on
a narrower claim than intended; the real question is still open.

**Setup:** ~150 rapid alternating concurrent HTTP requests to `conflict-a`
(port 8081) and `conflict-b` (port 8082), attempting to force a shared
thread pool to reuse a worker across both components, to check whether
`ItaraDispatcher.dispatch()`'s TCCL swap leaks state between requests on a
reused thread.

**What was confirmed:** Across the entire run, `dispatch()`'s
`tcclWillSetTo` was 100% consistent per component (`conflict-a` always
resolved to its own classloader, `conflict-b` to its own, never once
drifted or picked up the wrong value), and `tcclBefore` was always the
plain system classloader baseline — never a stale component classloader
left over from a previous request. The `finally`-block restore in
`activateRaw`/`dispatch` holds up under sustained concurrent load, not
just in the single-request case.

**Initial run (separate ports 8081/8082) did NOT test what it intended to:**
every `dispatch()` call for `conflict-a` ran on `HTTP-Dispatcher(24)`, and
every one for `conflict-b` ran on `HTTP-Dispatcher(26)` — no exceptions.
Each listener apparently gets its own dedicated handler thread rather than
sharing a pool across components, so no cross-component thread reuse
actually occurred in that run.

**Follow-up run (both components on the same port) provided the real
test, and it passed cleanly.** With one shared listener, every single
request — hundreds of them, alternating rapidly between `conflict-a` and
`conflict-b` — was handled by the exact same thread (`HTTP-Dispatcher(24)`
appears in 100% of log lines; no other thread ID appears anywhere in that
run). This is genuine cross-component thread reuse: the same thread
repeatedly switches between dispatching to `conflict-a` and `conflict-b`.
Across the entire run, `dispatch()`'s `tcclBefore` was still always the
plain system baseline — never once inheriting the other component's
classloader from the immediately preceding call on that same thread.

**Status upgraded:** this is now a genuine confirmation, not the narrower
claim from the first attempt. The `finally`-block TCCL restore in
`ItaraDispatcher.dispatch()`/`ItaraRegistry.activateRaw()` correctly
prevents cross-component TCCL leakage even under real, sustained,
same-thread reuse across different components.

**Still open:** this confirms the *dispatcher's own* TCCL handling is
leak-safe. It says nothing about thread pools a *component's own code*
creates or shares (e.g. `ForkJoinPool.commonPool()`, called out explicitly
in the design doc as JVM-global and TCCL-unpredictable). That's a
different code path — `dispatch()`'s explicit per-call lookup doesn't run
there — and is still untested. Testable directly with the existing
`conflict-a`/`conflict-b` pair: have component code call
`CompletableFuture.supplyAsync(...)` (default executor = common pool) and
log the observed TCCL inside the lambda, to check for cross-component
leakage on a genuinely shared, JVM-global pool. Planned as the next test.

---

## Finding 4: The HTTP transport appears to have no request concurrency —
## every request on a given listener is handled by a single thread

**Status:** Confirmed via logs, across two independent runs. Out of scope
for this spike to fix, but significant enough to flag and track separately.

**Evidence:** in the separate-ports run, port 8081 exclusively used
`HTTP-Dispatcher(24)` and port 8082 exclusively used `HTTP-Dispatcher(26)`
— one thread per listener, never more than one. In the same-port run, all
traffic — including bursts of concurrently-fired requests — funneled
through a single thread (`HTTP-Dispatcher(24)`) with no other thread ID
appearing anywhere in the log. This is consistent with `ItaraHttpServer`
using `com.sun.net.httpserver.HttpServer` without calling `setExecutor(...)`
— the JDK default in that case processes exchanges serially on an internal
thread rather than concurrently.

**Effect:** not a classloader-isolation problem, but a real limitation of
the HTTP transport as it stands — it cannot serve concurrent requests in
parallel today, regardless of isolation mode. Incidentally *useful* for
this spike (it's what let the same-port test above produce a clean,
unambiguous cross-component thread-reuse signal instead of a racy one),
but should be tracked and fixed as its own issue against `itara-transport-http`
— straightforward fix is calling `setExecutor(Executors.newFixedThreadPool(n))`
or similar before `start()`. Out of scope for this spike to fix.

---

## Finding 5: `ForkJoinPool.commonPool()` never carries the correct TCCL —
## confirmed deterministic, not intermittent

**Status:** Confirmed. Severity: high for any component code that submits
work to the common pool (directly via `CompletableFuture.supplyAsync(...)`
with no explicit executor, or indirectly via parallel streams) and expects
correct classloading inside that work.

**Setup:** both `ConflictAServiceImpl.describe()` and
`ConflictBServiceImpl.describe()` wrap their core logic in
`CompletableFuture.supplyAsync(...)` (default executor = common pool) and
log the pool thread's observed TCCL against the TCCL that was active in
the caller just before the submission.

**Result:** across the entire same-port alternating-call run —
hundreds of calls to both components — `observedTccl` was **always**
`jdk.internal.loader.ClassLoaders$AppClassLoader@76ed5528` (the plain
system classloader), for both components, on every single call, 100% of
the time. `expectedTccl` correctly varied per component
(`conflict-a`'s vs `conflict-b`'s own loader) as it should, but the
common-pool worker thread (`ForkJoinPool.commonPool-worker-1`, the same
one throughout) never reflected it.

**What this means:** this is not cross-component contamination (which
would show one component's code observing the *other* component's
classloader) — it's simpler and arguably worse. The common pool worker
was created once, early, with the system classloader as its TCCL, and
`supplyAsync` does not propagate the submitting thread's TCCL into the
worker at submission time. This only matters for work that actually reads
TCCL — dynamic proxy generation, `ServiceLoader`, JAXB, Spring's
reflective bean instantiation, or any other classloading-sensitive
operation. Pure computation submitted to the common pool (arithmetic,
string handling, anything that never asks a classloader to resolve a name)
is unaffected regardless of which TCCL the worker carries. For the subset
of work that does depend on it, the failure is deterministic and 100%
reproducible, which is actually good news for anyone who hits it: it fails
loudly and consistently in testing rather than intermittently in
production.

**Confirms and quantifies** the design doc's existing "Known limitations
— ForkJoinPool" section — this was already correctly flagged as a risk
there; this spike turns that theoretical risk into a confirmed, 100%-
reproducible empirical result.

**Recommended mitigation for the full implementation:** the design doc
already recommends component-managed executors instead of the common pool
for TCCL-sensitive work — this finding strengthens that from "recommended"
to "required if the component does anything classloading-sensitive
asynchronously." Two options were considered:
- **Global**: override the common pool's thread factory JVM-wide via
  `-Djava.util.concurrent.ForkJoinPool.common.threadFactory=...`. Rejected
  as the recommended path — it wouldn't actually fix the underlying issue
  (workers are long-lived and handle tasks from many callers over their
  lifetime; setting TCCL once at worker creation doesn't help the next
  task submitted by a different component), and it's a blunt, JVM-global
  change affecting every use of the common pool, not just Itara's.
- **Opt-in helper (recommended)**: a small `itara-common` utility that
  wraps a `Supplier`/`Runnable`/`Callable`, capturing the caller's TCCL at
  submission time and swapping it in/out around the actual work — safe
  regardless of which thread or pool ends up running it. Component authors
  who submit TCCL-sensitive work to any shared pool (common pool or
  otherwise) opt in by wrapping their task with it; nothing changes for
  everyone else. The equally valid alternative — component-owned executors
  created during activation, which inherit the correct TCCL naturally
  (per the design doc's "Thread pools and spawned threads" section) — 
  remains the simplest fix and doesn't require any Itara-provided utility
  at all; the wrapper is for the case where a shared pool is unavoidable.

---

## Finding 6: Fat jars work correctly under parent-first delegation —
## ADR-0018's claim confirmed directly, not just in theory

**Status:** Confirmed. No caveats.

**Setup:** `conflict-a-component` repackaged via `maven-shade-plugin` with
no exclusions — a single fat jar bundling its own class, `itara-common`,
`conflict-b-api` (the API interface it also gets from the shared
directory), and `shared-lib-v1`, deployed as the component's *only* jar
(`components/conflict-a/` contains just the one shaded jar; the old thin
jar and separate `shared-lib-v1.jar` were removed). `conflict-b` was left
in its normal, non-fat-jar configuration.

**Result:** `ConflictBService.class.getClassLoader()`, read from inside
`conflict-a`'s own running code, resolved to the system classloader — not
`conflict-a`'s own `URLClassLoader`, despite the fat jar containing its own
bundled copy of that exact class. The bundled copy was never loaded; the
system classloader's copy (from `conflict-b-api.jar` in the shared
directory) was used instead, silently and correctly. The call itself
completed successfully end to end, with no `ClassCastException` and normal
`RETURN_SENT`/`RETURN_RECEIVED` observability events.

**Confirms exactly what ADR-0018 predicts**: "the system classloader loads
the shared artifacts from the shared directory first, and the component
classloader loads everything else from the fat jar. The shared artifacts
inside the fat jar are simply shadowed by the system classloader copy and
never loaded, which is the correct outcome." No caveats or edge cases
surfaced — matches the design as written.

---

## Finding 7: Circular activation dependencies between colocated components
## are a hard failure — and specifically a *colocation* problem, not a
## general distributed-systems one

**Status:** Identified by inspection/design discussion, not yet triggered
in a run (no test pair with a genuine mutual dependency has been built).
Real, structural, and worth tooling support before it bites a real
migration.

**The core issue:** `activate()` resolves a component's dependencies
synchronously, via `registry.get(...)`, at construction time. If
component `A`'s activator calls `registry.get("B", ...)` and `B`'s
activator calls `registry.get("A", ...)`, there is no valid construction
order — neither can finish building without the other already existing.
The registry's existing same-thread reentrancy check (the `activating`
map in `ItaraRegistry.activateRaw()`) catches this and throws a clear
`IllegalStateException` rather than silently stack-overflowing, so this
is not a silent failure — but it is a **runtime** one, surfacing only when
something actually triggers activation of the cycle (which, per the
`ItaraMain.activateAllLocal()` change, is now at process startup rather
than on first request — an improvement, but still runtime, still after
the JVM is already up).

**Why this is specifically a colocation problem, not a general one:** two
independently-deployed services can have `A` call `B` for some flows and
`B` call `A` for others, indefinitely, with no issue at all — a network
call is evaluated per-request, at whatever time it happens, with no
requirement that the other side already exist. This is completely normal
and sometimes even good practice between genuinely independent services.
Colocating them under Itara's activator model changes the nature of the
relationship categorically: from "a live call that happens whenever" to
"a hard construction-time dependency." A pattern that was perfectly fine
as two separate deployments becomes a hard impossibility the moment both
sides are colocated in the same JVM slice with mutual activation-time
dependencies.

**Why this matters for real migrations:** a client moving an existing
system onto Itara may have exactly this kind of mutual relationship
between two services they intend to colocate for the performance benefit,
without realising the relationship is now structurally incompatible with
colocation until activation actually fails. Discovering this after
substantial migration effort — rather than on day one — is a real cost to
a migration timeline.

**Recommended: a static cycle-detection check in the CLI/build tooling.**
The wiring config already declares, per connection, which two components
are involved and (implicitly, by both being local nodes in the same
deployment group) whether they'd be colocated. This is enough information
to build a directed graph — local-node-to-local-node connections only,
excluding any connection where one side is remote (a pre-registered proxy
has no construction-time dependency at all, so remote relationships in a
cycle are never a problem) — and run ordinary cycle detection (DFS-based,
or Tarjan's for reporting the full cycle membership) entirely statically,
before ever building or running the JVM. This should be a build-time or
pre-deploy validation step (an `itara validate`/`itara doctor`-style
command), producing an error that names the exact cycle
(e.g. `circular local dependency: order -> inventory -> order`).
Catching this on day one of a migration, rather than after days or weeks
of work, is a genuine adoption advantage worth treating as a priority for
the tooling roadmap, not an afterthought.

**Migration playbook — recommended mitigations, for documentation aimed at
clients cleaning up an existing system's dependencies to colocate them:**
1. **Defer the dependency fetch from the constructor to first real use.**
   Instead of the activator calling `registry.get(...)` synchronously
   during construction, store the registry reference and resolve the
   dependency lazily, inside the contract methods that actually need it.
   This is the same resolution Spring itself uses for circular
   *field/setter* injection (as opposed to circular *constructor*
   injection, which Spring explicitly refuses to support, for the same
   underlying reason) — a well-understood, standard pattern, not a hack
   specific to Itara.
2. **Keep the specific mutually-dependent connection remote, colocate the
   rest.** Colocation is opt-in per connection, not all-or-nothing for a
   deployment group — if refactoring the calling code is out of scope for
   a migration's timeline, leaving just that one relationship as a network
   call (with everything else colocated) sidesteps the cycle entirely
   without blocking the rest of the migration.
3. **Extract the shared concern into a third component.** If two
   components genuinely need each other, that's sometimes a sign the
   thing they both need is really a separate shared abstraction that
   should exist on its own — standard "break the cycle by extracting a
   shared dependency" DI advice, applicable here exactly as in any other
   dependency-injection context.

---

## Finding 8: Two colocated embedded-Tomcat Spring Boot apps crash the
## second one to start — a genuine JVM-global singleton collision,
## not a classloader bug

**Status:** Confirmed, root-caused precisely, mitigated. Severity: high
(hard crash, not a subtle behavioral quirk) for the specific case of two
private, independently-versioned copies of an embedded servlet container
colocated in one JVM.

**What happened:** the moment `order` and `inventory` were both actually
colocated (each with its own private, shaded `tomcat-embed-core` bundled
in its own fat jar), the second component's embedded Tomcat failed to
start with `java.lang.Error: factory already defined`, thrown from
`URL.setURLStreamHandlerFactory()`.

**Root cause, confirmed against Spring Boot's own maintainers** (via a
GitHub issue discussing the identical symptom in a different multi-
classloader context): `TomcatURLStreamHandlerFactory` guards against
double-registration with a **static field scoped to its own class** — but
`URL.setURLStreamHandlerFactory()` itself is a genuine JDK-enforced,
JVM-wide singleton, one static field on `java.net.URL` (a bootstrap class),
shared across every classloader in the process with no exceptions. Since
`inventory` and `order` each load their *own private copy* of
`TomcatURLStreamHandlerFactory` (one per isolated classloader), each
copy's own guard has no way to know the other one exists — both think
they're first, both attempt to register, and the second hits the real,
unfixable-from-Tomcat's-side JDK wall. Quoting a Spring Boot maintainer on
the exact mechanism: *"If `setURLStreamHandlerFactory` is being called
multiple times, there must be multiple class loaders in the same JVM...
That's something that is out of the control of Spring Boot and Tomcat."*

**Mitigation applied and validated:** promoted `tomcat-embed-core`,
`tomcat-embed-websocket`, `tomcat-embed-el`, and `jakarta.annotation-api`
(a transitive dependency needed for `@PostConstruct`/`@PreDestroy`
processing, whose absence surfaced as a *second*, different error —
`NoClassDefFoundError: jakarta/annotation/PostConstruct` — once Tomcat's
own classes were promoted but its own dependency wasn't) from each
component's private fat jar to the shared directory (system classloader).
This is exactly the mechanism ADR-0018 was designed around — parent-first
delegation means promoting a library to the shared directory silently and
correctly shadows each component's own private bundled copy, no rebuild
of the fat jars needed, no exclusions required at build time. After the
promotion, both components started and ran correctly side by side (see
Finding 9).

**Generalizable lesson — this is a category, not a one-off Tomcat quirk:**
any library that enforces true JVM-global singleton state via a
classloader-scoped static guard (rather than, say, a file lock or a
genuinely JVM-wide registry) will hit exactly this failure mode when two
components each load their own private copy. Tomcat's URL stream handler
factory is one concrete instance; there is no guarantee it's the only one
a real migration will encounter. Worth stating in the design doc's "Known
limitations" section as its own category, alongside logging and
`ForkJoinPool` — not folded into either, since the failure mode here (hard
crash at startup) is qualitatively more severe than either of those.

**Also worth noting precisely:** promoting a library to the shared
classloader means promoting its *entire relevant dependency closure*, not
just the one jar that throws the first error — a naive partial promotion
doesn't fail the same way as no promotion at all; it fails differently,
later, deeper into startup. This cost us a second troubleshooting round
in this spike and is worth calling out explicitly in any tooling or
documentation that recommends this mitigation.

**Real tradeoff of the fix, stated plainly:** this forces every colocated
Spring-Boot-based component in a deployment group to share one Tomcat
version — narrowly reintroducing, just for this one library, exactly the
constraint isolation exists to avoid. Everything else in each component
remains independently versioned; only the embedded container itself
becomes a shared exception. For a real migration, this is the kind of
decision that needs to be made deliberately and documented, not discovered
by accident.

---

## Finding 9: A real cross-component call between two colocated, otherwise-
## unmodified Spring Boot apps works correctly end to end

**Status:** Confirmed, no caveats. This is the headline result of the
entire spike.

**What was validated:** `order`'s controller called `OrderService`, which
called `inventory.reserve(...)` through a genuine Itara direct proxy — not
a mock, not a simplified stand-in — while both components ran as
fully-featured, independently-versioned, real Spring Boot applications,
each with its own private classloader and its own embedded Tomcat (per
Finding 8's mitigation, sharing only the Tomcat jars themselves).

**Confirmed via matching object identity across two independent log
lines**, the same technique used in Finding 2: the TCCL that
`ObservabilityDecorator`'s `decoratorInvoke` swapped to for the
cross-component call (`java.net.URLClassLoader@f2a0b8e`) is the exact same
object later confirmed as `inventory`'s own isolated classloader by its
`@Lazy` diagnostic bean's independently-logged parent classloader. The
call itself returned the correct result (`reserve` → `true` → `"order
placed for widget"`), with full, correct observability events
(`CALL_SENT`/`CALL_RECEIVED`/`RETURN_SENT`/`RETURN_RECEIVED`) on both
sides of the direct transport.

**What this closes out:** the design doc's core hypothesis — that
independently-developed Spring Boot components with conflicting
dependencies can be colocated and call each other directly, with correct
class identity and correct TCCL throughout — is now validated under the
real framework it needs to work with, not just under the deliberately
simple `conflict-a`/`conflict-b` pair. Neither component's own request
handling shows any awareness the other component exists in the same
process.

---

## Finding 10: JVM-global logging properties genuinely collide between
## colocated Spring Boot apps — confirmed, self-correcting in this
## instance, but the underlying risk is broader than what was observed

**Status:** Confirmed via reproducible log evidence. Severity: currently
cosmetic in the specific case observed; the underlying mechanism could
plausibly affect non-cosmetic properties too (see below) — that broader
risk is not yet confirmed either way.

**What was observed:** Spring Boot's `LoggingSystemProperties` (or an
equivalent internal mechanism — the precise property key was not pinned
down with full confidence, see caveat below) writes several logging
configuration values as JVM-wide `System` properties once during each
Spring Boot application's own startup, which Logback's own pattern-based
`ConsoleAppender` layout then substitutes into the leading bracket of
every log line (visually: `[order-service]` / `[inventory-service]`
immediately before the thread-name bracket). Since this is `System`-
property state — JVM-global by definition, unaffected by classloader
isolation, exactly like the `ForkJoinPool` case (Finding 5) — both
components' startup sequences write to the *same* slot.

**Confirmed reproducibly, twice, in the same shape both times**: at the
exact moment both components' embedded Tomcats first handle a request
(`DispatcherServlet` initializing on first inbound call to each), two
consecutive log lines at the identical timestamp and thread name show the
bracket flip between `[order-service]` and `[inventory-service]` — for
what is genuinely the same request on the same thread. After that initial
moment, every subsequent line from each component correctly and stably
shows its own name for the remainder of the run.

**Precisely characterizing severity:** this is a **one-time race at the
moment of concurrent first-write**, not persistent, ongoing corruption of
log output — worth being exact about that distinction rather than
overstating the finding. For this specific property (a cosmetic display
token), the practical impact observed so far is genuinely minor: a couple
of misattributed lines at startup, self-correcting immediately after.

**Why this still matters more than the cosmetic impact suggests:** the
same mechanism — `System.setProperty` calls made once per Spring Boot
app's own startup, read by Logback's configuration — is very likely used
by Boot for other logging-related values too, including (unconfirmed,
worth testing directly next) the **log file path** itself. If two
colocated components both use file-based logging and both write to a
JVM-global `LOG_FILE`-style property, the consequence would not be
cosmetic and self-correcting — it could mean one component's log output
physically ends up in the other's log file, silently, for as long as both
processes run. This spike used console logging only, so that specific,
more severe variant has not actually been tested — flagging it explicitly
as the natural next test rather than assuming console-log cosmetics is
the full extent of the risk.

**Confirms, empirically, the design doc's own predicted risk category**
("JVM property isolation," listed under "Open items") — this is no longer
a theoretical concern awaiting a spike; it's an observed, reproducible
instance of exactly that category, with the console-log-bracket case as
the mild end of what's possible and file-path collision as a plausible,
more severe, not-yet-tested variant.

**Recommended follow-up before closing this out fully:** configure both
components with distinct `logging.file.name` values and confirm directly
whether file-based output ends up in the correct file for each, before
concluding the severity assessment above. This is a small, cheap test
relative to how much it would change the recommended mitigation guidance
if file paths turn out to collide the same way the console bracket did.

---

## Finding 11: Tomcat's own internal log lines are misrouted to the wrong
## component's log file entirely — confirmed, and meaningfully more
## severe than Finding 10's cosmetic bracket case

**Status:** Confirmed via direct file inspection (not just console
output). Severity: real — genuine content loss from one component's log
file, with the missing lines silently appearing in a different
component's file instead. This directly answers Finding 10's own
recommended follow-up, and the answer is worse than "self-correcting."

**Setup:** both components configured with distinct `logging.file.name`
values (`inventory.log`, `order.log`), same colocated run as before.

**What was found:** every log line from an `org.apache.catalina.*`-named
logger (Tomcat's internal container lifecycle logging — `StandardService`,
`StandardEngine`, the `[Tomcat].[localhost].[/]` context logger) from
*both* components ended up in the *same single file* (`order.log` in this
run), correctly timestamped but with the wrong component name attached,
and **entirely absent from the other component's file**. Concretely:
`inventory.log` is missing `Starting service [Tomcat]`, `Starting Servlet
engine`, and `Initializing Spring embedded WebApplicationContext` for its
own startup — those three lines exist only in `order.log`, mislabeled.

**Precisely what's affected and what isn't:** every line from Spring's
own framework loggers (`o.s.b.w.embedded.tomcat.TomcatWebServer`,
`ServletWebServerApplicationContext`) and every line from application code
(`com.example.*`) routed to the correct file, every single time, no
exceptions. Only Catalina's own internal logger output was affected.

**Root cause:** Tomcat ships a classloader-aware logging replacement
(JULI, a custom `LogManager`) specifically designed to solve this exact
problem — but it's only installed when Tomcat boots the standard
standalone way (`bin/catalina.sh`), which sets a system property pointing
`java.util.logging` at JULI's implementation as part of Tomcat's own
bootstrap sequence. **Embedded Tomcat, as used by Spring Boot, never does
this** — it runs as a plain library inside the application's own `main()`,
so Catalina's internal logging (routed via `org.apache.juli.logging.LogFactory`)
falls back to the ordinary JDK `java.util.logging.LogManager` — a true,
single, JVM-wide singleton, loaded by the bootstrap classloader, in the
same category as `URL`'s stream handler factory (Finding 8). Spring Boot
bridges JUL into SLF4J/Logback via `SLF4JBridgeHandler`, installed once
per Spring Boot app's own startup — but since there's only one JUL
`LogManager` in the whole JVM, whichever component's bridge installation
is active at any given moment determines where *every* Catalina-sourced
line ends up, regardless of which component's own embedded Tomcat instance
actually produced it. This is consistent with, and a second confirmed
instance of, the same general category identified in Finding 8: a library
whose true JVM-global singleton state is guarded by mechanisms that assume
one classloader per JVM, quietly broken by giving each component its own.

**Practical consequence, stated plainly:** an operator debugging a
container-level issue specific to one component (a bind failure, a
connector-level error, a thread-pool exhaustion message Tomcat itself
logs) by checking that component's own log file could find nothing
there — the diagnostic information genuinely exists, but silently in a
different component's file. This is a real operational trap, not a
cosmetic inconvenience — the console-bracket case (Finding 10) actively
undersold how bad this category could get.

**Mitigation options, not yet tested:**
1. **Accept it as a known, documented limitation** for Catalina-level
   diagnostic logging specifically — application and framework-level logs
   are unaffected and route correctly, so the practical blast radius is
   narrower than "logging is broken," but this still needs explicit
   documentation so nobody loses time debugging a "missing" log file that
   was never actually missing, just misfiled.
2. **Explicitly configure JULI's classloader-aware `LogManager`**
   (`-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager`)
   for the whole JVM, matching what standalone Tomcat does automatically —
   untested in this spike, and worth verifying it actually behaves
   correctly under embedded Tomcat rather than standalone, since embedded
   mode was never JULI's intended use case.
3. **Route Catalina's own internal logging to console only, not file**,
   if the specific content in these lines is judged low value relative to
   the confusion of it landing in the wrong file — sidesteps the problem
   rather than solving it, but may be an acceptable tradeoff for many
   deployments.

---
