package dev.itara.agent;

import dev.itara.agent.authentication.NoopAuthentication;
import dev.itara.agent.authorization.NoopAuthorization;
import dev.itara.api.ItaraActivator;
import dev.itara.runtime.ComponentLookup;
import dev.itara.runtime.ComponentScope;
import dev.itara.runtime.ComponentScopeHandle;
import dev.itara.runtime.ItaraRegistry;
import dev.itara.runtime.ObservabilityFacade;
import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authorization.AuthorizationConfig;
import dev.itara.spi.authorization.ItaraAuthorization;
import dev.itara.spi.authorization.ItaraAuthorizationConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The failure mode component scope exists to prevent — one component
 * successfully claiming to be, or reaching, another — as something these
 * tests actively attempt and fail to do, not just an absence of
 * complaints on the happy path.
 *
 * Three parties throughout: A (the calling node under test), B (A's real,
 * declared connection — proves the setup is genuine, not a broken no-op
 * that would also "block" everything), and C (colocated, fully activated,
 * completely real — but never declared as a connection from A, anywhere
 * in this test). The guarantee under test is specifically that colocation
 * does not imply reachability.
 */
@DisplayName("ItaraLocalProxyHandler — hostile-actor guarantees")
class ItaraLocalProxyHandlerTest {

    // ── Fixtures ─────────────────────────────────────────────────────────

    public interface Greeter {
        String greet();

        ComponentScope captureCurrentScope();
    }

    static class GreeterImpl implements Greeter {
        private final String name;

        GreeterImpl(String name) {
            this.name = name;
        }

        @Override
        public String greet() {
            return "hello from " + name;
        }

        @Override
        public ComponentScope captureCurrentScope() {
            return ComponentScope.current();
        }
    }

    public static class ComponentBActivator implements ItaraActivator {
        @Override
        public Object activate() {
            return new GreeterImpl("B");
        }
    }

    public static class ComponentCActivator implements ItaraActivator {
        @Override
        public Object activate() {
            return new GreeterImpl("C");
        }
    }

    private static ClassLoader freshClassLoader() {
        return new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
    }

    private ItaraRegistry registry;
    private ComponentScope scopeA; // the calling node under test
    private ComponentScope scopeB; // A's real, declared connection
    private ComponentScope scopeC; // colocated, activated, real — but undeclared from A
    private static final ItaraAuthentication NOOP_AUTHENTICATION = new NoopAuthentication();
    private static final ItaraAuthenticationConfig NOOP_AUTHENTICATION_CONFIG =
            new NoopAuthentication.Factory().parseConfig(AuthenticationConfig.builder().build());
    private static final ItaraAuthorization NOOP_AUTHORIZATION = new NoopAuthorization();
    private static final ItaraAuthorizationConfig NOOP_AUTHORIZATION_CONFIG =
            new NoopAuthorization.Factory().parseConfig(AuthorizationConfig.builder().build());

    @BeforeEach
    void setUp() {
        ObservabilityFacade.initialize();
        registry = ItaraRegistry.instance();
        registry.reset();

        ClassLoader cl = freshClassLoader();
        scopeA = new ComponentScope.Factory().nodeId("nodeA").componentId("component-a").classLoader(cl).build();
        scopeB = new ComponentScope.Factory().nodeId("nodeB").componentId("component-b").classLoader(cl).build();
        scopeC = new ComponentScope.Factory().nodeId("nodeC").componentId("component-c").classLoader(cl).build();

        registry.registerActivator("component-b", ComponentBActivator.class);
        registry.registerActivator("component-c", ComponentCActivator.class);

        // A has a real, declared connection to B — and ONLY to B. No
        // connection from A to C is ever registered, anywhere in this test.
        Greeter proxyToB = (Greeter) Proxy.newProxyInstance(
                cl, new Class<?>[]{ Greeter.class },
                new ItaraLocalProxyHandler("conn-a-to-b", "component-b", registry, scopeB, scopeA,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                        NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG));
        registry.registerConnectionProxy("conn-a-to-b", proxyToB);
        registry.registerOutboundConnection("nodeA", "component-b", "conn-a-to-b");
    }

    @AfterEach
    void tearDown() {
        ComponentScope.resetForTest();
    }

    @Nested
    @DisplayName("colocated reachability")
    class ColocatedReachability {

        @Test
        @DisplayName("A can reach B, its declared connection — proves the setup is real, not a broken no-op")
        void reachesDeclaredConnection() {
            try (ComponentScopeHandle handle = ComponentScopeHandle.open(scopeA)) {
                Greeter b = ComponentLookup.get("component-b", Greeter.class);
                assertEquals("hello from B", b.greet());
            }
        }

        @Test
        @DisplayName("A cannot reach C — colocated, activated, and real, but never declared as a connection")
        void cannotReachUndeclaredTarget() {
            try (ComponentScopeHandle handle = ComponentScopeHandle.open(scopeA)) {
                assertThrows(IllegalStateException.class,
                        () -> ComponentLookup.get("component-c", Greeter.class));
            }
        }

        @Test
        @DisplayName("A cannot reach C even when B has a real, working connection to C — "
                + "colocation never leaks another node's reachability")
        void cannotReachTargetReachableOnlyByAnotherNode() {
            // Give B a real, working connection to C — so C is genuinely
            // reachable in this JVM, just not by A.
            Greeter proxyToC = (Greeter) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[]{ Greeter.class },
                    new ItaraLocalProxyHandler("conn-b-to-c", "component-c", registry, scopeC, scopeB,
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                            NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG));
            registry.registerConnectionProxy("conn-b-to-c", proxyToC);
            registry.registerOutboundConnection("nodeB", "component-c", "conn-b-to-c");

            // Confirm B really can reach C — otherwise this test proves nothing.
            try (ComponentScopeHandle handleB = ComponentScopeHandle.open(scopeB)) {
                Greeter c = ComponentLookup.get("component-c", Greeter.class);
                assertEquals("hello from C", c.greet());
            }

            // A still cannot reach C, even though C is demonstrably reachable
            // in this same JVM — just not by A specifically.
            try (ComponentScopeHandle handleA = ComponentScopeHandle.open(scopeA)) {
                assertThrows(IllegalStateException.class,
                        () -> ComponentLookup.get("component-c", Greeter.class));
            }
        }
    }

    @Nested
    @DisplayName("ambient scope cannot be poisoned")
    class AmbientScopePoisoning {

        @Test
        @DisplayName("the proxy opens its own fixed target scope, never one read from ambient thread-local state")
        void proxyIgnoresPoisonedAmbientScope() {
            ComponentScope poisoned = new ComponentScope.Factory()
                    .nodeId("attackerNode")
                    .componentId("attacker-controlled")
                    .classLoader(freshClassLoader())
                    .build();

            Greeter proxyToB = (Greeter) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[]{ Greeter.class },
                    new ItaraLocalProxyHandler("conn-a-to-b", "component-b", registry, scopeB, scopeA,
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                            NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG));

            // Simulate a hostile or simply broken caller that has poisoned
            // the thread's ambient scope directly, bypassing the normal
            // crossing mechanism entirely. Not something a real proxy would
            // ever do to itself — but exactly the kind of ambient state ADR
            // 0021 says a proxy must never trust to determine identity.
            try (ComponentScopeHandle poisonedHandle = ComponentScopeHandle.open(poisoned)) {
                ComponentScope observed = proxyToB.captureCurrentScope();

                assertSame(scopeB, observed,
                        "the proxy must open its own captured target scope regardless of ambient state");
                assertNotSame(poisoned, observed);
            }
        }

        @Test
        @DisplayName("after the call returns, the ambient scope is restored exactly to what it was before — "
                + "not left as the target's, not cleared")
        void restoresAmbientScopeAfterCall() {
            ComponentScope poisoned = new ComponentScope.Factory()
                    .nodeId("attackerNode")
                    .componentId("attacker-controlled")
                    .classLoader(freshClassLoader())
                    .build();

            Greeter proxyToB = (Greeter) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(), new Class<?>[]{ Greeter.class },
                    new ItaraLocalProxyHandler("conn-a-to-b", "component-b", registry, scopeB, scopeA,
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                            NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG));

            try (ComponentScopeHandle poisonedHandle = ComponentScopeHandle.open(poisoned)) {
                proxyToB.greet(); // same crossing as above, result discarded — only the aftermath matters here

                assertSame(poisoned, ComponentScope.current(),
                        "after the call returns, the ambient scope must be restored to exactly what it was "
                                + "before — not left as the target's scope, not cleared");
            }
        }
    }
}
