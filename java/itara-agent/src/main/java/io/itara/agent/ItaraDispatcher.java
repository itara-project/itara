package io.itara.agent;

import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.CallTargetPropagation;
import io.itara.runtime.ComponentScope;
import io.itara.runtime.ComponentScopeHandle;
import io.itara.runtime.DispatchHandler;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraCallTarget;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ItaraScope;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.authentication.AuthenticationOutcome;
import io.itara.spi.authentication.ItaraAuthentication;
import io.itara.spi.authentication.ItaraAuthenticationConfig;
import io.itara.spi.authorization.AuthorizationDecision;
import io.itara.spi.authorization.ItaraAuthorization;
import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.identity.ItaraTransportCredential;
import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent-owned inbound call pipeline. Implements DispatchHandler.
 *
 * The transport calls dispatch() with raw bytes and receives raw bytes back.
 * This class owns everything in between:
 *
 *   0. Open this node's ComponentScope — outermost, before anything else,
 *      so registry lookup, method resolution, and every step below can rely
 *      on it being active (restore-as-soon-as-possible, matching how inbound
 *      observability context is already restored first).
 *   1. registry lookup      ← outside all further scopes, fails fast
 *   2. method resolution    ← outside all further scopes, fails fast. The
 *      method name is the only part of the call target carried over the
 *      wire (CallTargetPropagation) — node and component are already known
 *      authoritatively from this dispatcher's own construction.
 *   3. restoreInboundContext (inbound scope opened — fires onInboundContextReleased on close)
 *   4. authenticate          ← ADR 0027: after context reconstruction, before authorization
 *   5. authorize             ← ADR 0027: after authentication, before deserialization
 *   6. deserialize args      ← within inbound context, measurable in future
 *   7. CALL_RECEIVED         ← Itara/component boundary (callee scope opened)
 *   8. component.invoke()    ← TCCL already set to the component's own
 *      classloader, as part of step 0 opening the ComponentScope
 *   9. callee scope.close()  → RETURN_SENT
 *  10. serialize result      ← within inbound context, measurable in future
 *  11. inbound scope.close() → onInboundContextReleased, context popped
 *  12. ComponentScopeHandle.close() — restores the previous scope and TCCL,
 *      last, matching step 0 being first
 *
 * The callee scope wraps component invocation, and the TCCL swap around it —
 * setting and restoring TCCL is not itself observable overhead worth its own
 * span, but the invocation it wraps is exactly what the callee scope already
 * measures. Deserialization and serialization are within the inbound scope
 * but outside the callee scope — their cost is visible as transport
 * overhead and available for future measurement as dedicated spans.
 *
 * Constructed once per inbound connection at startup. All dependencies are
 * wired in, including the component's classloader (fetched once from the
 * registry at construction) — nothing is looked up at call time.
 */
public class ItaraDispatcher implements DispatchHandler {

    private static final Logger log = Logger.getLogger(ItaraDispatcher.class.getName());

    private final String dispatchKey;
    private final String componentId;
    private final String nodeId;
    private final String transportId;
    private final ItaraSerializer serializer;
    private final ItaraSerializerConfig serializerConfig;
    private final ItaraAuthentication authentication;
    private final ItaraAuthenticationConfig authenticationConfig;
    private final ItaraAuthorization authorization;
    private final ItaraAuthorizationConfig authorizationConfig;
    private final ItaraRegistry registry;
    private final ObservabilityFacade facade;
    private final ExchangePattern exchangePattern;
    private final ComponentScope scope;

    public ItaraDispatcher(String dispatchKey,
                           String componentId,
                           String transportId,
                           ItaraSerializer serializer,
                           ItaraSerializerConfig serializerConfig,
                           ItaraRegistry registry,
                           ExchangePattern exchangePattern,
                           ItaraAuthentication authentication,
                           ItaraAuthenticationConfig authenticationConfig,
                           ItaraAuthorization authorization,
                           ItaraAuthorizationConfig authorizationConfig,
                           ComponentScope scope) {
        this.dispatchKey      = Objects.requireNonNull(dispatchKey,
                "[Itara] ItaraDispatcher requires a non-null dispatchKey for component '" + componentId + "'.");
        this.componentId          = componentId;
        this.transportId          = transportId;
        this.serializer           = serializer;
        this.serializerConfig     = serializerConfig;
        this.registry             = registry;
        this.facade               = ObservabilityFacade.instance();
        this.exchangePattern      = exchangePattern;
        this.authentication       = authentication;
        this.authenticationConfig = authenticationConfig;
        this.authorization        = authorization;
        this.authorizationConfig  = authorizationConfig;

        // Received, not built — one ComponentScope exists per node, created
        // once at agent startup (ItaraAgent). This dispatcher holds the same
        // reference every other dispatcher or proxy for this node holds.
        this.scope = Objects.requireNonNull(scope,
                "[Itara] ItaraDispatcher requires a non-null ComponentScope for component '" + componentId + "'.");
        this.nodeId               = scope.getNodeId();
    }

    @Override
    public String getDispatchKey() {
        return dispatchKey;
    }

    @Override
    public byte[] dispatch(byte[] requestBytes, Map<String, String> headers, ItaraTransportCredential transportCredential) throws Exception {

        // The method name is the only part of the call target actually
        // carried over the wire — node and component are already known
        // authoritatively from this dispatcher's own construction.
        String methodName = CallTargetPropagation.decodeMethod(headers);

        // 0. Open this node's scope — outermost, so registry lookup, method
        //    resolution, and every step below can rely on it being active.
        try (ComponentScopeHandle scopeHandle = ComponentScopeHandle.open(this.scope)) {

            // 1. Registry lookup — outside all scopes, fails fast before any context is opened
            Object instance;
            try {
                instance = registry.getRawImplementation(componentId, Object.class);
            } catch (Exception e) {
                log.log(Level.SEVERE, "[Itara] registry lookup failed component=" + componentId
                        + " error=" + e.getMessage(), e);
                throw serialized(new ItaraRemoteException(
                                ItaraRemoteException.ErrorKind.TRANSPORT,
                                e.getClass().getName(),
                                "Registry failure for component '" + componentId + "': " + e.getMessage(), e),
                        methodName);
            }

            // 2. Method resolution — outside all scopes
            Method method = findMethod(instance.getClass(), methodName);
            if (method == null) {
                throw serialized(new ItaraRemoteException(
                                ItaraRemoteException.ErrorKind.TRANSPORT,
                                "MethodNotFoundException",
                                "Method '" + methodName + "' not found on component '" + componentId + "'"),
                        methodName);
            }

            // Built locally, not decoded — both fields not covered by
            // methodName come straight from this dispatcher's own identity.
            ItaraCallTarget target = ItaraCallTarget.of(nodeId, componentId, methodName);

            // 3. Restore inbound context — wraps authentication, authorization,
            //    deserialization, invocation, and serialization
            try (ItaraScope inboundScope = facade.restoreInboundContext(headers, exchangePattern)) {

                // 4. Authenticate (§15.6) — after context reconstruction, before
                //    authorization (ADR 0027)
                AuthenticationOutcome authnOutcome;
                try {
                    authnOutcome = authentication.authenticate(authenticationConfig, headers, transportCredential);
                } catch (Exception e) {
                    inboundScope.setError(true);
                    throw serialized(new ItaraRemoteException(
                                    ItaraRemoteException.ErrorKind.TRANSPORT,
                                    e.getClass().getName(),
                                    "Authentication implementation failed for '" + methodName
                                            + "' on '" + componentId + "': " + e.getMessage(), e),
                            methodName);
                }
                if (!authnOutcome.isAccepted()) {
                    inboundScope.setError(true);
                    throw serialized(new ItaraRemoteException(
                                    ItaraRemoteException.ErrorKind.PERMISSION,
                                    "AuthenticationRejected",
                                    authnOutcome.getRejectionReason()),
                            methodName);
                }

                // 5. Authorize (§16.5) — after authentication, before deserialization (ADR 0027)
                AuthorizationDecision authzDecision;
                try {
                    authzDecision = authorization.authorize(authorizationConfig, authnOutcome.getIdentity(), target, headers);
                } catch (Exception e) {
                    inboundScope.setError(true);
                    throw serialized(new ItaraRemoteException(
                                    ItaraRemoteException.ErrorKind.TRANSPORT,
                                    e.getClass().getName(),
                                    "Authorization implementation failed for '" + methodName
                                            + "' on '" + componentId + "': " + e.getMessage(), e),
                            methodName);
                }
                if (!authzDecision.isPermitted()) {
                    inboundScope.setError(true);
                    throw serialized(new ItaraRemoteException(
                                    ItaraRemoteException.ErrorKind.PERMISSION,
                                    "AuthorizationDenied",
                                    authzDecision.getDenialReason()),
                            methodName);
                }

                // 6. Deserialize args — within inbound context
                Object[] args;
                try {
                    args = serializer.deserializeArgs(requestBytes, method.getParameterTypes(), serializerConfig);
                } catch (Exception e) {
                    throw serialized(new ItaraRemoteException(
                                    ItaraRemoteException.ErrorKind.TRANSPORT,
                                    e.getClass().getName(),
                                    "Failed to deserialize arguments for '" + methodName
                                            + "' on '" + componentId + "': " + e.getMessage(), e),
                            methodName);
                }

                // 7. CALL_RECEIVED — callee scope wraps component invocation only
                Object result = null;
                try (ItaraScope calleeScope = facade.fireCallReceived(componentId, methodName, transportId, exchangePattern)) {
                    log.fine("[Itara] dispatch component=" + componentId + " method=" + methodName
                            + " classLoader=" + scope.getClassLoader());
                    try {
                        // 8. Component invocation
                        result = method.invoke(instance, args);
                    } catch (InvocationTargetException e) {
                        calleeScope.setError(true);
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        throw serialized(new ItaraRemoteException(
                                        cause instanceof RuntimeException || cause instanceof Error
                                                ? ItaraRemoteException.ErrorKind.RUNTIME
                                                : ItaraRemoteException.ErrorKind.CHECKED,
                                        cause.getClass().getName(),
                                        cause.getMessage(),
                                        cause),
                                methodName);
                    } catch (Exception e) {
                        calleeScope.setError(true);
                        throw e;
                    }
                } // 9. calleeScope.close() → RETURN_SENT

                // 10. Serialize result — within inbound context, after RETURN_SENT
                try {
                    return serializer.serializeResult(result, serializerConfig);
                } catch (Exception e) {
                    throw serialized(new ItaraRemoteException(
                                    ItaraRemoteException.ErrorKind.TRANSPORT,
                                    e.getClass().getName(),
                                    "Failed to serialize result for '" + methodName
                                            + "' on '" + componentId + "': " + e.getMessage(), e),
                            methodName);
                }
            } // 11. Close inbound context
        } // 12. scopeHandle.close() — TCCL and ComponentScope restored last
    }

    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    /**
     * Attaches a serialized error payload to an ItaraRemoteException before throwing.
     * Every exception that leaves the dispatcher carries a payload — the transport
     * server writes it back as-is, and the proxy deserializes it. No empty bodies.
     */
    private ItaraRemoteException serialized(ItaraRemoteException ex, String methodName) {
        try {
            ex.withSerializedPayload(serializer.serializeResult(ex.toPayload(), serializerConfig));
        } catch (Exception e) {
            log.log(Level.WARNING, "[Itara] failed to serialize error payload component=" + componentId
                    + " method=" + methodName + " — caller will receive empty error body", e);
        }
        return ex;
    }
}
