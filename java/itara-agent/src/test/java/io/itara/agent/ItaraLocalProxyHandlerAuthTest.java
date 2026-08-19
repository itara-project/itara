package io.itara.agent;

import io.itara.agent.testsupport.ConfigurableAuthentication;
import io.itara.agent.testsupport.ConfigurableAuthorization;
import io.itara.agent.testsupport.TestAuthenticationConfig;
import io.itara.agent.testsupport.TestAuthorizationConfig;
import io.itara.api.ItaraActivator;
import io.itara.exceptions.ItaraRemoteException;
import io.itara.runtime.ComponentScope;
import io.itara.runtime.ItaraRegistry;
import io.itara.runtime.ObservabilityFacade;
import io.itara.spi.identity.ItaraIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ItaraLocalProxyHandler — authentication and authorization")
class ItaraLocalProxyHandlerAuthTest {

    @BeforeAll
    static void initObservability() {
        ObservabilityFacade.initialize();
    }

    public interface Greeter {
        String greet();
    }

    static class GreeterImpl implements Greeter {
        @Override
        public String greet() { return "hello from B"; }
    }

    public static class GreeterActivator implements ItaraActivator {
        @Override
        public Object activate() { return new GreeterImpl(); }
    }

    private static ClassLoader freshClassLoader() {
        return new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
    }

    private ItaraRegistry registry;
    private ComponentScope scopeA; // caller
    private ComponentScope scopeB; // callee

    @BeforeEach
    void setUp() {
        registry = ItaraRegistry.instance();
        registry.reset();

        ClassLoader cl = freshClassLoader();
        scopeA = new ComponentScope.Factory().nodeId("nodeA").componentId("component-a").classLoader(cl).build();
        scopeB = new ComponentScope.Factory().nodeId("nodeB").componentId("component-b").classLoader(cl).build();

        registry.registerActivator("component-b", GreeterActivator.class);
    }

    @AfterEach
    void tearDown() {
        ComponentScope.resetForTest();
    }

    private Greeter proxyWith(ConfigurableAuthentication authn, ConfigurableAuthorization authz) {
        return (Greeter) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ Greeter.class },
                new ItaraLocalProxyHandler("conn-a-to-b", "component-b", registry, scopeB, scopeA,
                        authn, TestAuthenticationConfig.INSTANCE, authn, TestAuthenticationConfig.INSTANCE,
                        authz, TestAuthorizationConfig.INSTANCE));
    }

    @Nested
    @DisplayName("noop pass-through")
    class NoopPassThrough {

        @Test
        @DisplayName("call succeeds with no identity when both accept/permit with nothing configured")
        void succeedsWithNoIdentity() {
            Greeter proxy = proxyWith(ConfigurableAuthentication.accepting(), ConfigurableAuthorization.permitting());

            assertEquals("hello from B", proxy.greet());
        }
    }

    @Nested
    @DisplayName("rejection at authentication")
    class AuthenticationRejection {

        @Test
        @DisplayName("throws PERMISSION with the rejection reason, and never calls authorization or the delegate")
        void rejectsBeforeAuthorizationAndDelegate() {
            ConfigurableAuthorization authz = ConfigurableAuthorization.permitting();
            Greeter proxy = proxyWith(ConfigurableAuthentication.rejecting("invalid token"), authz);

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class, proxy::greet);

            assertEquals(ItaraRemoteException.ErrorKind.PERMISSION, ex.getErrorKind());
            assertEquals("invalid token", ex.getMessage());
            assertEquals(0, authz.authorizeCalls.get(), "authorization must never run after a rejection");
        }

        @Test
        @DisplayName("an unexpected exception from authentication surfaces as TRANSPORT, not PERMISSION")
        void unexpectedFailureSurfacesAsTransport() {
            Greeter proxy = proxyWith(
                    ConfigurableAuthentication.throwing(new RuntimeException("keystore locked")),
                    ConfigurableAuthorization.permitting());

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class, proxy::greet);

            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
        }
    }

    @Nested
    @DisplayName("rejection at authorization")
    class AuthorizationRejection {

        @Test
        @DisplayName("throws PERMISSION with the denial reason; authentication ran but the delegate was never invoked")
        void deniesBeforeDelegateInvocation() {
            ConfigurableAuthentication authn = ConfigurableAuthentication.accepting();
            Greeter proxy = proxyWith(authn, ConfigurableAuthorization.denying("insufficient scope"));

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class, proxy::greet);

            assertEquals(ItaraRemoteException.ErrorKind.PERMISSION, ex.getErrorKind());
            assertEquals("insufficient scope", ex.getMessage());
            assertEquals(1, authn.authenticateCalls.get(), "authentication ran; only the delegate invocation was blocked");
        }

        @Test
        @DisplayName("an unexpected exception from authorization surfaces as TRANSPORT, not PERMISSION")
        void unexpectedFailureSurfacesAsTransport() {
            Greeter proxy = proxyWith(
                    ConfigurableAuthentication.accepting(),
                    ConfigurableAuthorization.throwing(new RuntimeException("policy service down")));

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class, proxy::greet);

            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
        }
    }

    @Nested
    @DisplayName("successful pass-through with both configured")
    class SuccessfulPassThrough {

        @Test
        @DisplayName("the identity authentication produces is exactly what authorization receives")
        void identityFlowsFromAuthenticationToAuthorization() {
            ItaraIdentity identity = ItaraIdentity.builder().subject("user-42").build();
            ConfigurableAuthorization authz = ConfigurableAuthorization.permitting();
            Greeter proxy = proxyWith(ConfigurableAuthentication.acceptingWithIdentity(identity), authz);

            assertEquals("hello from B", proxy.greet());

            assertTrue(authz.lastIdentity.isPresent());
            assertSame(identity, authz.lastIdentity.get());
            assertEquals("component-b", authz.lastTarget.getComponent());
            assertEquals("greet", authz.lastTarget.getMethod());
            assertEquals("nodeB", authz.lastTarget.getNode());
        }
    }

    @Nested
    @DisplayName("in-memory assertion carrier")
    class InMemoryAssertionCarrier {

        @Test
        @DisplayName("authenticate() receives the exact same Map instance produceAssertion() returned — "
                + "no serialization round-trip on the direct path")
        void assertionIsPassedByReferenceNotSerialized() {
            Map<String, String> assertion = Map.of("x-test-assertion", "token-123");
            ConfigurableAuthentication authn = ConfigurableAuthentication.accepting().withAssertion(assertion);
            Greeter proxy = proxyWith(authn, ConfigurableAuthorization.permitting());

            proxy.greet();

            assertSame(assertion, authn.lastAuthenticateHeaders,
                    "the direct path must hand the assertion straight through as the same object reference — "
                            + "if this ever fails, it's a sign the carrier stopped being an in-memory reference "
                            + "(see the note to revisit this before closing the issue)");
        }
    }
}
