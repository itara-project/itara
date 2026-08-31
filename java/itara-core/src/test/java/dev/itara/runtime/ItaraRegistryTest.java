package dev.itara.runtime;

import dev.itara.api.ItaraActivator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ItaraRegistry")
class ItaraRegistryTest {

    // Activator instances are created via getDeclaredConstructor().newInstance(),
    // so every fixture activator below is a static, top-level-visible,
    // no-arg-constructible class — never a non-static inner class.

    @BeforeEach
    void reset() {
        ItaraRegistry.instance().reset();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public interface TestContract {
        String hello();
    }

    static class TestContractImpl implements TestContract {
        @Override
        public String hello() {
            return "hello";
        }
    }

    public static class SimpleActivator implements ItaraActivator {
        @Override
        public Object activate() {
            return new TestContractImpl();
        }
    }

    private static ClassLoader freshClassLoader() {
        return new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
    }

    // ── Circular dependency detection ───────────────────────────────────

    public static class SelfReferencingActivator implements ItaraActivator {
        @Override
        public Object activate() {
            // Activating the same component id, recursively, on the same
            // thread — must be caught, not stack-overflow. Uses
            // getRawImplementation() rather than get() deliberately: this
            // test exercises activateRaw()'s own circular-dependency guard
            // directly. get() would now fail for an unrelated reason first
            // (no active ComponentScope, no registered outbound connection)
            // — neither of which is what this test is actually about.
            return ItaraRegistry.instance().getRawImplementation("self", TestContract.class);
        }
    }

    @Nested
    @DisplayName("circular dependency detection")
    class CircularDependency {

        @Test
        @DisplayName("throws IllegalStateException rather than recursing indefinitely")
        void throwsRatherThanRecursingForever() {
            ItaraRegistry.instance().registerActivator("self", SelfReferencingActivator.class);

            assertThrows(IllegalStateException.class,
                    () -> ItaraRegistry.instance().get("self", TestContract.class));
        }
    }

    // ── Topology errors ──────────────────────────────────────────────────

    @Nested
    @DisplayName("topology errors")
    class TopologyErrors {

        @Test
        @DisplayName("getRawImplementation() throws for an unregistered component id")
        void getRawImplementationThrowsForUnregisteredId() {
            assertThrows(IllegalStateException.class,
                    () -> ItaraRegistry.instance().getRawImplementation("does-not-exist", TestContract.class));
        }
    }

    // ── get() — scope and connection resolution ─────────────────────────

    @Nested
    @DisplayName("get()")
    class Get {

        @Test
        @DisplayName("throws when no ComponentScope is active on the calling thread")
        void throwsWhenNoScopeActive() {
            assertThrows(IllegalStateException.class,
                    () -> ItaraRegistry.instance().get("whatever", TestContract.class));
        }

        @Test
        @DisplayName("throws when the calling node has no declared outbound connection to the target")
        void throwsWhenNoConnectionDeclared() {
            ComponentScope callerScope = new ComponentScope.Factory()
                    .nodeId("callerNode")
                    .componentId("caller")
                    .classLoader(freshClassLoader())
                    .build();

            try (ComponentScopeHandle handle = ComponentScopeHandle.open(callerScope)) {
                assertThrows(IllegalStateException.class,
                        () -> ItaraRegistry.instance().get("undeclared-target", TestContract.class));
            }
        }

        @Test
        @DisplayName("returns the registered connection proxy when caller, target, and connection all line up")
        void returnsProxyWhenConnectionDeclared() {
            Object proxy = new Object(); // stand-in — get() never inspects the proxy itself
            ItaraRegistry.instance().registerConnectionProxy("conn-caller-to-target", proxy);
            ItaraRegistry.instance().registerOutboundConnection("callerNode", "target", "conn-caller-to-target");

            ComponentScope callerScope = new ComponentScope.Factory()
                    .nodeId("callerNode")
                    .componentId("caller")
                    .classLoader(freshClassLoader())
                    .build();

            try (ComponentScopeHandle handle = ComponentScopeHandle.open(callerScope)) {
                assertSame(proxy, ItaraRegistry.instance().get("target", Object.class));
            }
        }
    }

    // ── registerOutboundConnection — the outbound-ambiguity guard ───────

    @Nested
    @DisplayName("registerOutboundConnection")
    class RegisterOutboundConnection {

        @Test
        @DisplayName("a first registration for (fromNodeId, targetIdentifier) succeeds")
        void firstRegistrationSucceeds() {
            ItaraRegistry.instance().registerOutboundConnection("callerNode", "target", "conn-a");
            // No exception — the point of this test.
        }

        @Test
        @DisplayName("throws when the same (fromNodeId, targetIdentifier) is registered again with a "
                + "different connectionId — a node cannot have two outbound connections to different "
                + "targets sharing one component id")
        void throwsOnAmbiguousSecondConnection() {
            ItaraRegistry.instance().registerOutboundConnection("callerNode", "target", "conn-a");

            assertThrows(IllegalStateException.class, () ->
                    ItaraRegistry.instance().registerOutboundConnection("callerNode", "target", "conn-b"));
        }

        @Test
        @DisplayName("throws even when re-registering the exact same (fromNodeId, targetIdentifier, "
                + "connectionId) triple — registration is strictly once, not idempotent")
        void throwsOnExactDuplicateToo() {
            ItaraRegistry.instance().registerOutboundConnection("callerNode", "target", "conn-a");

            assertThrows(IllegalStateException.class, () ->
                    ItaraRegistry.instance().registerOutboundConnection("callerNode", "target", "conn-a"));
        }

        @Test
        @DisplayName("different fromNodeIds may each declare their own connection to the same target — "
                + "the guard is scoped per caller, not global")
        void differentFromNodesCanEachConnectToTheSameTarget() {
            ItaraRegistry.instance().registerOutboundConnection("nodeA", "target", "conn-a-to-target");

            // Must not throw — a different caller connecting to the same
            // target is the ordinary fan-out case, not an ambiguity.
            ItaraRegistry.instance().registerOutboundConnection("nodeB", "target", "conn-b-to-target");
        }

        @Test
        @DisplayName("the same fromNodeId may declare connections to different targets without conflict")
        void sameFromNodeCanConnectToDifferentTargets() {
            ItaraRegistry.instance().registerOutboundConnection("callerNode", "target-1", "conn-to-1");

            // Must not throw — different targetIdentifiers from the same
            // caller are entirely unrelated registrations.
            ItaraRegistry.instance().registerOutboundConnection("callerNode", "target-2", "conn-to-2");
        }
    }
}
