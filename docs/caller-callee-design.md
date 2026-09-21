# Caller/Callee Side Connection Configuration — Design

A connection declaration gains two nested blocks, **caller** and
**callee**, replacing the current top-level `from`/`to` fields entirely.
Each existing plugin-configuration block (`transport`, `serializer`,
`failureSemantics`, `authentication`, `authorization`) is restricted to
whichever of {connection-level, caller-side, callee-side} actually makes
sense for it.

## Structure

```yaml
connections:
  - id: "gateway-to-calculator"
    callee:
      nodeId: "calculatorNode"
      transport:
        id: http
        params: { host: "${CALC_HOST:-localhost}", port: "8081" }
    caller:
      nodeId: "gatewayNode"

  - id: "external-to-gateway"
    callee:
      nodeId: "gatewayNode"
      transport:
        id: http
        params: { port: "8082" }
    # no caller block — external connection
```

`callee` is mandatory. Every connection has a target; `callee.nodeId` is
always required.

`caller` is optional as a whole, not just its `nodeId`. Its absence is
what an external connection means now — there's no Itara-managed caller
process for an external connection to begin with, so there's nothing for
a caller-side block to configure. This replaces the old model of a
present-but-blank `from:` field, which was really expressing the same
thing awkwardly.

Both node-reference fields are named `nodeId`, not bare `id` — the
connection already has its own top-level `id`; reusing `id` for the node
reference inside a nested block risks exactly the "which id is this"
ambiguity the move away from `from`/`to` is meant to resolve.

## Override granularity

Override happens per plugin kind, within a side block, not per side as a
whole. A `caller` block can override just `transport` while `serializer`
still falls back to connection-level, and vice versa. Where a
side-specific block for a given plugin kind is present, it replaces the
connection-level block of that kind for that side entirely — block-level,
not field-level; nothing merges.

## Placement per plugin kind

| Plugin kind | Connection-level | Caller-side | Callee-side |
|---|---|---|---|
| `transport` | ✅ | ✅ | ✅ |
| `serializer` | ✅ | ✅ | ✅ |
| `failureSemantics` | ❌ | ✅ only | ❌ |
| `authentication` | ✅ | ✅ | ✅ |
| `authorization` | ❌ | ❌ | ✅ only |

`transport` and `serializer` keep all three placements — caller and
callee can validly run different, independently-chosen-but-compatible
implementations.

`failureSemantics` moves to caller-side only. Retry/timeout is
exclusively the caller's concern — the callee has no role in deciding
whether a call gets retried — so it never had coherent meaning at
connection-level or callee-side. An external connection, having no
caller block, correctly has nowhere to declare `failureSemantics` at all:
Itara has no caller-side code running for an external connection to
apply retry behavior to in the first place.

`authorization` moves to callee-side only, by the same reasoning mirrored
the other direction: it's purely the callee's own access-control
decision, never a caller concern.

`authentication` keeps all three placements, without further restriction.
Not enough is known yet to commit to a narrower answer.

## Compatibility checking

Interim rule for tooling: identical `id` on both sides is compatible;
different `id` but matching `type` is compatible; otherwise it isn't. A
richer compatibility model is expected later.
