package io.itara.agent;

import io.itara.agent.testsupport.ConfigurableAuthentication;
import io.itara.agent.testsupport.ConfigurableAuthorization;
import io.itara.agent.testsupport.TestAuthenticationConfig;
import io.itara.agent.testsupport.TestAuthorizationConfig;
import io.itara.api.ItaraActivator;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.CallTargetPropagation;
import io.itara.runtime.ComponentScope;
import io.itara.runtime.ExchangePattern;
import io.itara.runtime.ItaraCallTarget;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.identity.ItaraIdentity;
import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.ItaraSerializerGroupingKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ItaraDispatcher — authentication and authorization")
class ItaraDispatcherAuthTest {

    private static final String NODE_ID = "test-node";
    private static final String COMPONENT_ID = "greeter";

    @BeforeAll
    static void initObservability() {
        ObservabilityFacade.initialize();
    }

    @BeforeEach
    void setUp() {
        ItaraRegistry.instance().reset();
        ItaraRegistry.instance().registerActivator(COMPONENT_ID, GreeterActivator.class);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public interface GreeterContract {
        String greet();
    }

    public static class GreeterImpl implements GreeterContract {
        @Override
        public String greet() { return "hello"; }
    }

    public static class GreeterActivator implements ItaraActivator {
        @Override
        public Object activate() { return new GreeterImpl(); }
    }

    static class TestGroupingKey implements ItaraSerializerGroupingKey {
        @Override public boolean equals(Object o) { return o instanceof TestGroupingKey; }
        @Override public int hashCode() { return TestGroupingKey.class.hashCode(); }
    }

    static class TestSerializerConfig implements ItaraSerializerConfig {
        @Override public ItaraSerializerGroupingKey groupingKey() { return new TestGroupingKey(); }
    }

    private static final ItaraSerializerConfig TEST_SERIALIZER_CONFIG = new TestSerializerConfig();

    static class PassthroughSerializer implements ItaraSerializer {
        @Override public String type() { return "passthrough"; }
        @Override public byte[] serializeArgs(Object[] args, ItaraSerializerConfig config) { return new byte[0]; }
        @Override public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes, ItaraSerializerConfig config) {
            return new Object[paramTypes.length];
        }
        @Override public byte[] serializeResult(Object result, ItaraSerializerConfig config) {
            return String.valueOf(result).getBytes();
        }
        @Override public Object deserializeResult(byte[] bytes, Class<?> returnType, ItaraSerializerConfig config) {
            return new String(bytes);
        }
    }

    private static Map<String, String> headers() {
        return CallTargetPropagation.toHeaders(ItaraCallTarget.of(NODE_ID, COMPONENT_ID, "greet"));
    }

    private ItaraDispatcher dispatcherWith(ConfigurableAuthentication authn, ConfigurableAuthorization authz) {
        ComponentScope scope = new ComponentScope.Factory()
                .nodeId(NODE_ID)
                .componentId(COMPONENT_ID)
                .classLoader(Thread.currentThread().getContextClassLoader())
                .build();
        return new ItaraDispatcher(
                "conn-greeter", COMPONENT_ID, "test-transport", new PassthroughSerializer(),
                TEST_SERIALIZER_CONFIG, ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY,
                authn, TestAuthenticationConfig.INSTANCE, authz, TestAuthorizationConfig.INSTANCE, scope);
    }

    @Nested
    @DisplayName("noop pass-through")
    class NoopPassThrough {

        @Test
        @DisplayName("call succeeds with no identity when both accept/permit with nothing configured")
        void succeedsWithNoIdentity() throws Exception {
            ConfigurableAuthorization authz = ConfigurableAuthorization.permitting();
            ItaraDispatcher dispatcher = dispatcherWith(ConfigurableAuthentication.accepting(), authz);

            byte[] result = dispatcher.dispatch(new byte[0], headers(), null);

            assertEquals("hello", new String(result));
            assertEquals(Optional.empty(), authz.lastIdentity);
        }
    }

    @Nested
    @DisplayName("rejection at authentication")
    class AuthenticationRejection {

        @Test
        @DisplayName("throws PERMISSION with the rejection reason, and never calls authorization")
        void rejectsBeforeAuthorization() {
            ConfigurableAuthorization authz = ConfigurableAuthorization.permitting();
            ItaraDispatcher dispatcher = dispatcherWith(
                    ConfigurableAuthentication.rejecting("invalid token"), authz);

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class,
                    () -> dispatcher.dispatch(new byte[0], headers(), null));

            assertEquals(ItaraRemoteException.ErrorKind.PERMISSION, ex.getErrorKind());
            assertEquals("invalid token", ex.getMessage());
            assertEquals(0, authz.authorizeCalls.get(), "authorization must never run after a rejection");
        }

        @Test
        @DisplayName("an unexpected exception from authentication surfaces as TRANSPORT, not PERMISSION")
        void unexpectedFailureSurfacesAsTransport() {
            ConfigurableAuthorization authz = ConfigurableAuthorization.permitting();
            ItaraDispatcher dispatcher = dispatcherWith(
                    ConfigurableAuthentication.throwing(new RuntimeException("jwks unreachable")), authz);

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class,
                    () -> dispatcher.dispatch(new byte[0], headers(), null));

            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
            assertEquals(0, authz.authorizeCalls.get());
        }
    }

    @Nested
    @DisplayName("rejection at authorization")
    class AuthorizationRejection {

        @Test
        @DisplayName("throws PERMISSION with the denial reason, and never invokes the component")
        void deniesBeforeInvocation() {
            ConfigurableAuthentication authn = ConfigurableAuthentication.accepting();
            ItaraDispatcher dispatcher = dispatcherWith(
                    authn, ConfigurableAuthorization.denying("insufficient scope"));

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class,
                    () -> dispatcher.dispatch(new byte[0], headers(), null));

            assertEquals(ItaraRemoteException.ErrorKind.PERMISSION, ex.getErrorKind());
            assertEquals("insufficient scope", ex.getMessage());
            assertEquals(1, authn.authenticateCalls.get(), "authentication ran; only the component invocation was blocked");
        }

        @Test
        @DisplayName("an unexpected exception from authorization surfaces as TRANSPORT, not PERMISSION")
        void unexpectedFailureSurfacesAsTransport() {
            ItaraDispatcher dispatcher = dispatcherWith(
                    ConfigurableAuthentication.accepting(),
                    ConfigurableAuthorization.throwing(new RuntimeException("policy service down")));

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class,
                    () -> dispatcher.dispatch(new byte[0], headers(), null));

            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
        }
    }

    @Nested
    @DisplayName("successful pass-through with both configured")
    class SuccessfulPassThrough {

        @Test
        @DisplayName("the identity authentication produces is exactly what authorization receives")
        void identityFlowsFromAuthenticationToAuthorization() throws Exception {
            ItaraIdentity identity = ItaraIdentity.builder().subject("user-42").build();
            ConfigurableAuthorization authz = ConfigurableAuthorization.permitting();
            ItaraDispatcher dispatcher = dispatcherWith(
                    ConfigurableAuthentication.acceptingWithIdentity(identity), authz);

            byte[] result = dispatcher.dispatch(new byte[0], headers(), null);

            assertEquals("hello", new String(result));
            assertTrue(authz.lastIdentity.isPresent());
            assertSame(identity, authz.lastIdentity.get());
            assertEquals(COMPONENT_ID, authz.lastTarget.getComponent());
            assertEquals("greet", authz.lastTarget.getMethod());
        }
    }
}
