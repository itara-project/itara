package dev.itara.agent;

import dev.itara.exceptions.ItaraRemoteException;
import dev.itara.runtime.ComponentScope;
import dev.itara.runtime.ComponentScopeHandle;
import dev.itara.runtime.ExchangePattern;
import dev.itara.runtime.ItaraCallTarget;
import dev.itara.runtime.ItaraRegistry;
import dev.itara.runtime.ItaraScope;
import dev.itara.runtime.ObservabilityFacade;
import dev.itara.spi.authentication.AuthenticationOutcome;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authorization.AuthorizationDecision;
import dev.itara.spi.authorization.ItaraAuthorization;
import dev.itara.spi.authorization.ItaraAuthorizationConfig;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Agent-owned outbound call handler for direct (colocated, in-process)
 * connections.
 *
 * <p>Implements InvocationHandler — the same interface ItaraProxyHandler (the
 * remote/outbound case) implements. From business code's perspective the
 * two are indistinguishable: both are just what a java.lang.reflect.Proxy
 * delegates to.
 *
 * <p>Constructed once per direct ConnectionEntry (see ItaraAgent) — every
 * direct connection gets its own handler instance, holding its own
 * connectionId, matching how the remote outbound and inbound cases
 * already work.
 *
 * <p>Two things this class deliberately does NOT do at construction time:
 *
 * <ul>
 * <li>It does not resolve the target's raw instance. It holds `registry`
 * and `componentId` instead, and resolves the instance fresh, inside
 * invoke(), on every call — exactly like ItaraDispatcher does for a
 * remote inbound call. This is built during ItaraAgent's wiring loop,
 * when activation order across connections is not guaranteed — a
 * component's own activator can legitimately call out through another
 * local proxy before every connection has finished being wired.
 * Deferring resolution to call time means activation only ever happens
 * on the first real invocation, by which point wiring is complete.</li>
 * <li>It does not decide how the caller resolves which connection to use
 * (the componentId -> connectionId translation via the caller's own
 * ComponentScope). That is the registry's job, upstream of this class,
 * and is separate, later work.</li>
 * </ul>
 *
 * <p>What it DOES do at call time: open the CALLER's own captured scope
 * (fromScope) first, before CALL_SENT fires — never trusting whatever
 * happens to be ambient on the calling thread, per ADR 0021 — then open
 * the target's own ComponentScope around the callee side of the call:
 * CALL_RECEIVED and the actual invocation. This mirrors the crossing
 * ItaraDispatcher performs for a remote call, just synchronous and
 * in-process instead of over a transport. Each scope's own classloader is
 * applied via ComponentScopeHandle.
 *
 * <p>Authentication and authorization are wired in the same shape the remote
 * path uses — same SPI interfaces, same per-call config passing, same
 * ADR 0027 ordering (authenticate, then authorize, before invocation) —
 * just without a wire in between. The caller's produceAssertion() result
 * (a {@code Map<String,String>}, matching the remote path's shape) is handed
 * straight to the callee's authenticate() as an in-memory reference; there
 * is no header text to encode it into or decode it back out of, since
 * nothing here ever crosses a transport.
 * Authentication is deliberately two separate slots — callerAuthentication
 * (produceAssertion, caller side) and calleeAuthentication (authenticate,
 * callee side) — even though the agent currently resolves and passes the
 * same instance/config for both. A direct connection's wiring config today
 * has only one authentication block to resolve from; once caller-side and
 * callee-side configuration split in the wiring config (planned), only the
 * agent's construction code needs to change to pass genuinely different
 * instances — this class already has the right shape for that. Authorization
 * stays a single slot: it is callee-only, with no caller-side concept to
 * split in the first place.
 *
 * <p>CALL_SENT fires under fromScope, opened explicitly first — not merely
 * whatever happens to be ambient — for the same reason the target's own
 * scope must be active before CALL_RECEIVED fires: so ComponentScope.current()
 * is already correct for the whole duration of any observability event,
 * should something ever want to read it for identity. The target's scope
 * closes before CALL_SENT's own close (RETURN_RECEIVED) fires, so control
 * returns to fromScope, not the target's — and fromScope itself closes
 * last, restoring whatever was ambient before this call began.
 *
 * <p>fromScope — the calling node's own scope — is captured at construction
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
    private final ComponentScope targetScope;
    private final ComponentScope fromScope; // the calling node — opened before CALL_SENT, per ADR 0021
    private final ObservabilityFacade facade;
    private final ItaraAuthentication callerAuthentication;
    private final ItaraAuthenticationConfig callerAuthenticationConfig;
    private final ItaraAuthentication calleeAuthentication;
    private final ItaraAuthenticationConfig calleeAuthenticationConfig;
    private final ItaraAuthorization authorization;
    private final ItaraAuthorizationConfig authorizationConfig;

    /**
     * Constructs a proxy for a single direct connection.
     *
     * @param connectionId               this direct connection's own id
     * @param componentId                the target component this handler invokes
     * @param registry                   the shared ItaraRegistry, for the raw
     *                                   component instance lookup at call time
     * @param targetScope                the target component's own ComponentScope
     * @param fromScope                  the calling node's own ComponentScope —
     *                                   captured here, not read from
     *                                   ComponentScope.current() at call time
     * @param callerAuthentication       the caller-side authentication instance
     * @param callerAuthenticationConfig the caller-side parsed authentication config
     * @param calleeAuthentication       the callee-side authentication instance
     * @param calleeAuthenticationConfig the callee-side parsed authentication config
     * @param authorization              the authorization instance — callee-only,
     *                                   no caller-side counterpart
     * @param authorizationConfig        the parsed authorization config
     */
    public ItaraLocalProxyHandler(String connectionId,
                                  String componentId,
                                  ItaraRegistry registry,
                                  ComponentScope targetScope,
                                  ComponentScope fromScope,
                                  ItaraAuthentication callerAuthentication,
                                  ItaraAuthenticationConfig callerAuthenticationConfig,
                                  ItaraAuthentication calleeAuthentication,
                                  ItaraAuthenticationConfig calleeAuthenticationConfig,
                                  ItaraAuthorization authorization,
                                  ItaraAuthorizationConfig authorizationConfig) {
        this.connectionId = Objects.requireNonNull(connectionId,
                "[Itara] ItaraLocalProxyHandler requires a non-null connectionId.");
        this.componentId = Objects.requireNonNull(componentId,
                "[Itara] ItaraLocalProxyHandler requires a non-null componentId.");
        this.registry = Objects.requireNonNull(registry,
                "[Itara] ItaraLocalProxyHandler requires a non-null registry.");
        this.targetScope = Objects.requireNonNull(targetScope,
                "[Itara] ItaraLocalProxyHandler requires a non-null ComponentScope for component '" + componentId + "'.");
        this.fromScope = Objects.requireNonNull(fromScope,
                "[Itara] ItaraLocalProxyHandler requires a non-null ComponentScope for the calling node.");
        this.callerAuthentication = Objects.requireNonNull(callerAuthentication,
                "[Itara] ItaraLocalProxyHandler requires a non-null caller-side ItaraAuthentication for component '" + componentId + "'.");
        this.callerAuthenticationConfig = Objects.requireNonNull(callerAuthenticationConfig,
                "[Itara] ItaraLocalProxyHandler requires a non-null caller-side ItaraAuthenticationConfig for component '" + componentId + "'.");
        this.calleeAuthentication = Objects.requireNonNull(calleeAuthentication,
                "[Itara] ItaraLocalProxyHandler requires a non-null callee-side ItaraAuthentication for component '" + componentId + "'.");
        this.calleeAuthenticationConfig = Objects.requireNonNull(calleeAuthenticationConfig,
                "[Itara] ItaraLocalProxyHandler requires a non-null callee-side ItaraAuthenticationConfig for component '" + componentId + "'.");
        this.authorization = Objects.requireNonNull(authorization,
                "[Itara] ItaraLocalProxyHandler requires a non-null ItaraAuthorization for component '" + componentId + "'.");
        this.authorizationConfig = Objects.requireNonNull(authorizationConfig,
                "[Itara] ItaraLocalProxyHandler requires a non-null ItaraAuthorizationConfig for component '" + componentId + "'.");
        this.facade = ObservabilityFacade.instance();
    }

    /**
     * Runs the full direct-call pipeline for this handler's connection, as
     * described in this class's own javadoc: the caller's own scope opened
     * first, CALL_SENT, the identity assertion, crossing into the target's
     * scope, authenticate, authorize, CALL_RECEIVED, and the invocation
     * itself, in that order.
     *
     * <p>Calls declared on {@code Object} itself (equals, hashCode, toString)
     * are invoked directly against the target's raw instance, under the
     * target's own scope, but with none of the surrounding pipeline —
     * there is no identity to assert or observability event to fire for
     * these.
     *
     * @param proxy  the proxy instance the method was invoked on; unused,
     *               since this handler already knows exactly which
     *               connection it serves
     * @param method the contract method being invoked
     * @param args   the method arguments, or null for a no-arg method
     * @return the target's return value, unchanged — there is no
     *         serialization on a direct connection
     * @throws Throwable the target method's own exception, unwrapped from
     *                    {@link InvocationTargetException}, if it threw; an
     *                    {@link ItaraRemoteException} if authentication or
     *                    authorization failed or produced an error
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

        if (method.getDeclaringClass() == Object.class) {
            try (ComponentScopeHandle handle = ComponentScopeHandle.open(targetScope)) {
                Object delegate = registry.getRawImplementation(componentId, Object.class);
                return method.invoke(delegate, args);
            }
        }

        ItaraCallTarget target = ItaraCallTarget.of(targetScope.getNodeId(), componentId, method.getName());

        // Open the caller's own captured scope first — never trust ambient state
        try (ComponentScopeHandle fromScopeHandle = ComponentScopeHandle.open(fromScope)) {
            // CALL_SENT — fires under the caller's own ambient scope, before
            // crossing into the target.
            try (ItaraScope callerScope = facade.fireCallSent(
                    componentId, method.getName(), TRANSPORT, ExchangePattern.REQUEST_REPLY)) {

                // Produce the identity assertion under the caller's own scope,
                // once per call — no retries exist on this path, so "once" is
                // simply "before crossing into the target" (ADR 0027's remote
                // rule collapses to this in the absence of failure semantics).
                Map<String, String> assertion;
                try {
                    assertion = callerAuthentication.produceAssertion(callerAuthenticationConfig, target);
                } catch (Exception e) {
                    callerScope.setError(true);
                    throw new ItaraRemoteException(
                            ItaraRemoteException.ErrorKind.TRANSPORT,
                            e.getClass().getName(),
                            "Authentication implementation failed to produce an assertion for '"
                                    + method.getName() + "' on '" + componentId + "': " + e.getMessage(), e);
                }

                // Cross into the target — open its scope. Registry lookup happens
                // inside, resolving the target's own instance under its own
                // identity, fresh on every call.
                try (ComponentScopeHandle scopeHandle = ComponentScopeHandle.open(targetScope)) {

                    // Authenticate (§15.6) — the assertion travels as the same
                    // in-memory Map the caller side just produced; no header
                    // encoding, no transport-surfaced credential (there is no
                    // transport), no wire in between.
                    AuthenticationOutcome authnOutcome;
                    try {
                        authnOutcome = calleeAuthentication.authenticate(calleeAuthenticationConfig, assertion, null);
                    } catch (Exception e) {
                        callerScope.setError(true);
                        throw new ItaraRemoteException(
                                ItaraRemoteException.ErrorKind.TRANSPORT,
                                e.getClass().getName(),
                                "Authentication implementation failed for '" + method.getName()
                                        + "' on '" + componentId + "': " + e.getMessage(), e);
                    }
                    if (!authnOutcome.isAccepted()) {
                        callerScope.setError(true);
                        throw new ItaraRemoteException(
                                ItaraRemoteException.ErrorKind.PERMISSION,
                                "AuthenticationRejected",
                                authnOutcome.getRejectionReason());
                    }

                    // Authorize (§16.5) — after authentication, before CALL_RECEIVED (ADR 0027)
                    AuthorizationDecision authzDecision;
                    try {
                        authzDecision = authorization.authorize(authorizationConfig, authnOutcome.getIdentity(), target, assertion);
                    } catch (Exception e) {
                        callerScope.setError(true);
                        throw new ItaraRemoteException(
                                ItaraRemoteException.ErrorKind.TRANSPORT,
                                e.getClass().getName(),
                                "Authorization implementation failed for '" + method.getName()
                                        + "' on '" + componentId + "': " + e.getMessage(), e);
                    }
                    if (!authzDecision.isPermitted()) {
                        callerScope.setError(true);
                        throw new ItaraRemoteException(
                                ItaraRemoteException.ErrorKind.PERMISSION,
                                "AuthorizationDenied",
                                authzDecision.getDenialReason());
                    }

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
