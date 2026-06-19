# Security — Design Notes

**Status:** Thinking in progress. Not yet part of the specification.  
**Date:** June 2026

---

## The problem

Itara's wiring model is the authoritative declaration of what connects to what.
A connection that is not in the wiring config should not exist at runtime. At
present, Itara enforces this logically — the agent wires only what is declared
— but it does not enforce it physically. Nothing prevents an undeclared caller
from reaching a component's transport listener if it knows the address.

Security in Itara has two distinct concerns:

1. **Origin access control (OAC):** ensuring that only declared connections can
   make requests — that the physical communication matches the declared topology.
2. **Authentication and authorisation (auth/authz):** ensuring that a caller is
   who it claims to be and is permitted to invoke a specific operation.

These are different problems at different levels of design maturity. OAC is
closer to a direction; auth/authz is still being mapped.

---

## Origin access control

### The goal

If a connection between node A and node B is not declared in the wiring config,
node A MUST NOT be able to reach node B's listener. This is the physical
enforcement of Itara's core promise: the wiring config is the single source of
truth for topology.

### OAC is a topology concern

OAC enforcement requires knowledge of the wiring model — specifically, whether
a given caller corresponds to a declared connection. The transport moves bytes;
it does not know the wiring model and should not need to. OAC therefore does
not belong in the transport SPI.

Where exactly it belongs is an open question. The agent knows the wiring model
and is the natural enforcer, but OAC may warrant its own layer — a dedicated
OAC SPI sitting between inbound transport delivery and the agent's dispatch,
receiving caller identity and returning allow/deny. This keeps OAC pluggable
and independent of both transport and business logic. No decision has been made
on the exact placement.

### Per-connection OAC levels

Different parts of a system may require different levels of OAC. A connection
between two colocated internal components in a trusted network is a different
threat model from a connection accepting external traffic. The security model
must respect this — OAC levels should be declarable per connection (or per
node), not only globally.

### The spectrum

OAC is not binary. Itara should support a spectrum to accommodate different
deployment environments and threat models:

**Level 0 — Network trust:** no Itara-level OAC. The deployment environment
is trusted (private network, on-prem isolated network). Itara declares the
topology; the network enforces access. No additional configuration required.

**Level 1 — Shared secret:** each declared connection carries a pre-shared
key. The caller includes a signed token; the callee verifies it. Simpler to
operate than certificates — no PKI required. Weaker security model: key
compromise is harder to detect and rotate than cert expiry.

**Level 2 — Mutual TLS:** caller and callee mutually authenticate via
certificates. Strong, industry-standard, auditable. Operationally heavier —
certificates must be issued, distributed, and rotated. Per-connection handshake
cost, not per-request (connections are persistent).

OAC MUST be opt-in or opt-out, not mandatory. Certificates are not free —
neither in operational cost nor in infrastructure requirements. A deployment
on a trusted private network should not be required to operate a PKI.

### Context integrity

If OAC is in place, context integrity follows: a verified caller's context
headers (including `itaraTraceId`) can be trusted. Context integrity is not a
separate concern — it is a consequence of OAC.

### External callers

Connections with no `from` node (inbound external connections per §4.4) accept
calls from callers not managed by Itara. OAC cannot verify these callers —
they are outside the topology by definition. How external inbound connections
are secured is the deployment environment's responsibility. This boundary must
be made explicit in the specification.

---

## Authentication and authorisation

### The problem

Auth/authz sits at the intersection of topology and business logic in a way
that does not resolve cleanly. The central question is where rules live and
who enforces them.

Putting rules in the wiring config is topologically consistent but bloats a
file that is already load-bearing, and connection-level rules are too coarse
to express method-level access control. Putting rules in the implementation
couples security policy to a specific framework (Spring Security annotations,
for example) and leaks topology concern into the business layer. Putting rules
on the API contract prevents multiple implementations of the same contract from
having different access rules.

### A direction worth exploring

The `.itara` metadata file is already the place where per-component
declarations live — contracts, non-idempotent methods, and similar. Auth rules
could follow the same pattern: declarative, per-method, expressed in the
`.itara` file, evaluated by an auth SPI at the proxy boundary.

The shape would be roughly: the `.itara` file declares required claims or roles
per method. The agent, at proxy dispatch time, passes the declared rules and
the inbound context headers to an auth SPI. The SPI returns allow or deny. The
SPI implementation handles the actual claim verification — against a JWT, a
session token, a role database, whatever the deployment uses. Itara provides
the hook and the rule format; the enforcement is pluggable.

This is the same pattern as the non-idempotent methods list: a declaration in
metadata that the agent acts on at the proxy boundary, without the business
logic knowing it exists.

This direction is not decided. It is recorded here because it resolves the
placement problem without putting security policy in the business layer or in
the wiring config, and because it is consistent with existing patterns in the
metadata model.

### Framework-native path

Allowing the implementation to be decorated with framework-native security
(Spring Security annotations, etc.) is a viable path for teams that already
have an auth framework and are comfortable with it. It is not mutually
exclusive with an auth SPI — both can coexist, with the framework-native path
as the fallback for teams that do not need Itara-managed auth.

Whether this constitutes a leaky abstraction (topology concern in the business
layer) depends on whether access control is classified as topology or business
logic — a question that does not have a clean answer.

### Current status

Auth/authz is explicitly deferred. Pilot conversations — particularly with
regulated environments such as financial institutions — will surface concrete
requirements. Designing an auth model before those requirements are known risks
solving the wrong problem.

---

## Credential hygiene

Transport connections carry credentials: broker passwords, API keys, TLS
private keys. These MUST NOT be hardcoded in the wiring configuration.

The wiring config already supports environment variable substitution
(`${VAR_NAME:-default}`). Secret management backends (Vault, AWS Secrets
Manager, Kubernetes Secrets, etc.) typically surface secrets as environment
variables and are therefore compatible without any Itara-specific integration.

This is a usage guideline, not a design problem.

---

## Audit trail

Itara's four-event observability model fires a structured event at every call
boundary, carrying `itaraTraceId`, `itaraSpanId`, component identity, and
timestamps. This is a natural audit trail — every inter-component call is
observable and attributable without additional instrumentation. Observer
implementations can direct these events to any audit or logging backend.

---

## Open questions

**OAC placement:** does OAC enforcement belong in the agent directly, or in a
dedicated OAC SPI layer between transport delivery and agent dispatch? The SPI
approach is more pluggable but adds a layer. The agent approach is simpler but
couples security logic to the agent.

**OAC default level:** should Itara default to Level 0 (network trust) with
stronger levels as opt-in, or Level 1 (shared secret) with Level 0 as an
explicit opt-out? The former is simpler to get started; the latter is safer by
default. The bank audience would expect the latter; the OSS contributor
audience would prefer the former. Per-connection defaults complicate this
further.

**Shared secret distribution:** if Level 1 is supported, how are shared
secrets distributed to nodes? They cannot live in the wiring config in
plaintext. Environment variable injection is the likely mechanism, but
per-connection secret assignment needs specifying.

**Auth rule format:** if the `.itara` metadata direction is pursued, what is
the format for expressing auth rules? Required claims, role names, permission
strings? The format must be auth-framework-agnostic — the SPI implementation
translates it into whatever the deployment uses.

**Auth SPI input:** what does the auth SPI receive? At minimum: the method
being called, the declared rules for that method, and the inbound context
headers (where caller identity tokens would live). Whether it also receives
the full `ItaraContext` or a reduced security-specific view is an open
question.

**External callers and auth:** OAC cannot verify external callers, but auth
might still apply — an external caller might carry a JWT that the auth SPI
can validate. The interaction between OAC level and auth enforcement for
external connections needs to be specified.

---

## What this is not

- **Network-level encryption** — TLS configuration is a deployment concern.
  Itara does not mandate or configure it directly.
- **DDoS protection and rate limiting** — infrastructure concerns.
- **Identity provider integration** — OAuth, OIDC, Kerberos, and similar
  protocols are auth/authz concerns. An auth SPI would delegate to these;
  Itara would not implement them.
- **Secret management backends** — compatible via environment variable
  injection. No Itara-specific integration planned.
