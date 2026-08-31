package dev.itara.agent;

import dev.itara.exceptions.ItaraRemoteException;
import dev.itara.runtime.CallTargetPropagation;
import dev.itara.runtime.ComponentScope;
import dev.itara.runtime.ComponentScopeHandle;
import dev.itara.runtime.DispatchHandler;
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
import dev.itara.spi.identity.ItaraTransportCredential;
import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent-owned inbound call pipeline. Implements DispatchHandler.
 *
 * <p>The transport calls dispatch() with raw bytes and receives raw bytes back.
 * This class owns everything in between:
 *
 * <ol>
 * <li>Open this node's ComponentScope — outermost, before anything else,
 * so registry lookup, method resolution, and every step below can rely
 * on it being active (restore-as-soon-as-possible, matching how inbound
 * observability context is already restored first).</li>
 * <li>registry lookup — outside all further scopes, fails fast</li>
 * <li>method resolution — outside all further scopes, fails fast. The
 * method name is the only part of the call target carried over the
 * wire (CallTargetPropagation) — node and component are already known
 * authoritatively from this dispatcher's own construction.</li>
 * <li>restoreInboundContext (inbound scope opened — fires onInboundContextReleased on close)</li>
 * <li>authenticate — ADR 0027: after context reconstruction, before authorization</li>
 * <li>authorize — ADR 0027: after authentication, before deserialization</li>
 * <li>deserialize args — within inbound context, measurable in future</li>
 * <li>CALL_RECEIVED — Itara/component boundary (callee scope opened)</li>
 * <li>component.invoke() — runs under the component's own classloader,
 * already active as part of step 0 opening the ComponentScope</li>
 * <li>callee scope.close() → RETURN_SENT</li>
 * <li>serialize result — within inbound context, measurable in future</li>
 * <li>inbound scope.close() → onInboundContextReleased, context popped</li>
 * <li>ComponentScopeHandle.close() — restores the previously-active
 * scope, last, matching step 0 being first</li>
 * </ol>
 *
 * <p>The callee scope wraps component invocation only — not deserialization
 * or serialization, which sit within the inbound scope but outside it, so
 * their cost is visible as transport overhead and available for future
 * measurement as dedicated spans.
 *
 * <p>Constructed once per inbound connection at startup. All dependencies are
 * wired in — the component's classloader is not one of them directly; it is
 * reached only through the {@link ComponentScope} received at construction
 * (received, not built — see the constructor). Nothing is looked up at
 * call time.
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

    /**
     * Constructs a dispatcher for a single inbound connection. All
     * dependencies are wired in once, here, at agent startup — nothing is
     * looked up at call time.
     *
     * @param dispatchKey           identifies which declared connection this
     *                              dispatcher serves; the transport uses this
     *                              to route inbound calls to the right handler
     * @param componentId           the local component this dispatcher invokes
     * @param transportId           the transport type carrying this connection,
     *                              or "direct" — used for observability only
     * @param serializer            the connection's own serializer instance
     * @param serializerConfig      the connection's own parsed serializer config
     * @param registry              the shared ItaraRegistry, for the raw
     *                              component instance lookup at dispatch time
     * @param exchangePattern       the pattern this connection was wired under
     * @param authentication        the connection's own authentication instance
     * @param authenticationConfig  the connection's own parsed authentication config
     * @param authorization         the connection's own authorization instance
     * @param authorizationConfig   the connection's own parsed authorization config
     * @param scope                 this node's own ComponentScope — received,
     *                              not built; see this class's own javadoc
     */
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

    /**
     * Runs the full inbound call pipeline for this dispatcher's connection,
     * as described in this class's own javadoc: scope, registry lookup,
     * method resolution, context restoration, authentication, authorization,
     * deserialization, invocation, and serialization, in that order.
     *
     * @param requestBytes        the serialized argument bytes for this call
     * @param headers             the full inbound header map, including the
     *                            propagated method name and context
     * @param transportCredential a connection-level credential the transport
     *                            itself terminated and surfaced, or null if
     *                            the transport has nothing to surface
     * @return the serialized result bytes to write back to the caller
     * @throws Exception if any pipeline step fails; always an
     *                   {@link ItaraRemoteException} carrying a serialized
     *                   error payload, except for failures too early in the
     *                   pipeline for a payload to be attachable
     */
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

                        if (cause instanceof ItaraRemoteException) {
                            // A chained outbound call itself failed and the component didn't
                            // catch it — propagate that failure's own kind unchanged rather
                            // than reclassifying it as RUNTIME. An ItaraRemoteException is
                            // already exactly the boundary type this method exists to
                            // produce; re-wrapping it here would silently discard PERMISSION
                            // (or any other kind) every time a failure crosses one more hop.
                            throw serialized((ItaraRemoteException) cause, methodName);
                        }

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
        } // 12. scopeHandle.close() — previously-active ComponentScope restored last
    }

    /**
     * Finds a public method on cls by name only.
     *
     * <p>Does not match parameter types — only the method name propagated
     * over the wire is available to resolve against (see
     * CallTargetPropagation). If cls declares more than one public method
     * with this name (overloaded methods), which overload is returned is
     * unspecified — whichever {@link Class#getMethods()} happens to yield
     * first. Contract interfaces with overloaded method names are not
     * currently something this dispatcher can route correctly.
     *
     * @return the first public method named {@code name}, or null if none exists
     */
    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    /**
     * Attaches a serialized error payload to an ItaraRemoteException before throwing.
     *
     * <p>Every exception that leaves the dispatcher carries a payload — the transport
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
