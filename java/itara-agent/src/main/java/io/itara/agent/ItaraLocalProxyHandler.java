package io.itara.agent;

import io.itara.runtime.ComponentScope;
import io.itara.runtime.ComponentScopeHandle;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ItaraScope;
import io.itara.runtime.ObservabilityFacade;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Agent-owned outbound call handler for direct (colocated, in-process)
 * connections.
 *
 * Implements InvocationHandler — the same interface ItaraProxyHandler (the
 * remote/outbound case) implements. From business code's perspective the
 * two are indistinguishable: both are just what a java.lang.reflect.Proxy
 * delegates to.
 *
 * Constructed once per direct ConnectionEntry (see ItaraAgent), replacing
 * ObservabilityDecorator's previous lazy, componentId-shared wrapping —
 * every direct connection now gets its own handler instance, holding its
 * own connectionId, matching how the remote outbound and inbound cases
 * already work.
 *
 * Two things this class deliberately does NOT do at construction time:
 *
 *   - It does not resolve the target's raw instance. It holds `registry`
 *     and `componentId` instead, and resolves the instance fresh, inside
 *     invoke(), on every call — exactly like ItaraDispatcher does for a
 *     remote inbound call. This is built during ItaraAgent's wiring loop,
 *     when activation order across connections is not guaranteed — a
 *     component's own activator can legitimately call out through another
 *     local proxy before every connection has finished being wired.
 *     Deferring resolution to call time means activation only ever happens
 *     on the first real invocation, by which point wiring is complete.
 *
 *   - It does not decide how the caller resolves which connection to use
 *     (the componentId -> connectionId translation via the caller's own
 *     ComponentScope). That is the registry's job, upstream of this class,
 *     and is separate, later work.
 *
 * What it DOES do at call time: open the CALLER's own captured scope
 * (fromScope) first, before CALL_SENT fires — never trusting whatever
 * happens to be ambient on the calling thread, per ADR 0021 — then open
 * the target's own ComponentScope around the callee side of the call:
 * CALL_RECEIVED and the actual invocation. This mirrors the crossing
 * ItaraDispatcher performs for a remote call, just synchronous and
 * in-process instead of over a transport. This replaces the manual TCCL
 * swap ObservabilityDecorator used to do; each scope's own classloader is
 * used instead, via ComponentScopeHandle.
 *
 * CALL_SENT fires under fromScope, opened explicitly first — not merely
 * whatever happens to be ambient — for the same reason the target's own
 * scope must be active before CALL_RECEIVED fires: so ComponentScope.current()
 * is already correct for the whole duration of any observability event,
 * should something ever want to read it for identity. The target's scope
 * closes before CALL_SENT's own close (RETURN_RECEIVED) fires, so control
 * returns to fromScope, not the target's — and fromScope itself closes
 * last, restoring whatever was ambient before this call began.
 *
 * fromScope — the calling node's own scope — is captured at construction
 * and held, not read from ComponentScope.current() at call time, per ADR
 * 0021: a proxy must not determine identity from ambient thread-local
 * state, since it may legitimately be invoked from a thread with no correct
 * ambient scope (a shared pool, for instance).
 */
public class ItaraLocalProxyHandler implements InvocationHandler {

    private static final Logger log = Logger.getLogger(ItaraLocalProxyHandler.class.getName());

    private static final String TRANSPORT = "direct";

    private final String connectionId; // not yet used — held for future scope-resolution work
    private final String componentId;
    private final ItaraRegistry registry;
    private final ComponentScope scope;
    private final ComponentScope fromScope; // the calling node — opened before CALL_SENT, per ADR 0021
    private final ObservabilityFacade facade;

    public ItaraLocalProxyHandler(String connectionId,
                                  String componentId,
                                  ItaraRegistry registry,
                                  ComponentScope scope,
                                  ComponentScope fromScope) {
        this.connectionId = Objects.requireNonNull(connectionId,
                "[Itara] ItaraLocalProxyHandler requires a non-null connectionId.");
        this.componentId = Objects.requireNonNull(componentId,
                "[Itara] ItaraLocalProxyHandler requires a non-null componentId.");
        this.registry = Objects.requireNonNull(registry,
                "[Itara] ItaraLocalProxyHandler requires a non-null registry.");
        this.scope = Objects.requireNonNull(scope,
                "[Itara] ItaraLocalProxyHandler requires a non-null ComponentScope for component '" + componentId + "'.");
        this.fromScope = Objects.requireNonNull(fromScope,
                "[Itara] ItaraLocalProxyHandler requires a non-null ComponentScope for the calling node.");
        this.facade = ObservabilityFacade.instance();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        if (method.getDeclaringClass() == Object.class) {
            try (ComponentScopeHandle handle = ComponentScopeHandle.open(scope)) {
                Object delegate = registry.getRawImplementation(componentId, Object.class);
                return method.invoke(delegate, args);
            }
        }

        // Open the caller's own captured scope first — never trust ambient state
        try (ComponentScopeHandle fromScopeHandle = ComponentScopeHandle.open(fromScope)) {
            // CALL_SENT — fires under the caller's own ambient scope, before
            // crossing into the target.
            try (ItaraScope callerScope = facade.fireCallSent(
                    componentId, method.getName(), TRANSPORT, ExchangePattern.REQUEST_REPLY)) {

                // Cross into the target — open its scope. Registry lookup happens
                // inside, resolving the target's own instance under its own
                // identity, fresh on every call.
                try (ComponentScopeHandle scopeHandle = ComponentScopeHandle.open(scope)) {

                    log.fine("[Itara] direct call component=" + componentId + " method=" + method.getName());
                    Object delegate = registry.getRawImplementation(componentId, Object.class);

                    try (ItaraScope calleeScope = facade.fireCallReceived(
                            componentId, method.getName(), TRANSPORT, ExchangePattern.REQUEST_REPLY)) {

                        try {
                            return method.invoke(delegate, args);
                        } catch (Throwable t) {
                            calleeScope.setError(true);
                            callerScope.setError(true);
                            if (t instanceof InvocationTargetException && t.getCause() != null) {
                                throw t.getCause();
                            }
                            throw t;
                        }
                    } // calleeScope.close() → RETURN_SENT
                } // scopeHandle.close() — target scope + TCCL restored, back to caller's own
            } // callerScope.close() → RETURN_RECEIVED, now correctly under the caller's own scope again
        } // fromScopeHandle.close() — fromScope restored to whatever was ambient before this call
    }
}
