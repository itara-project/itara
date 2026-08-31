package dev.itara.agent;

import dev.itara.agent.authentication.NoopAuthentication;
import dev.itara.agent.authorization.NoopAuthorization;
import dev.itara.api.ItaraActivator;
import dev.itara.exceptions.ItaraRemoteException;
import dev.itara.runtime.CallTargetPropagation;
import dev.itara.runtime.ComponentScope;
import dev.itara.runtime.ExchangePattern;
import dev.itara.runtime.ItaraCallTarget;
import dev.itara.runtime.ItaraRegistry;
import dev.itara.runtime.ObservabilityFacade;
import dev.itara.spi.authentication.AuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authorization.AuthorizationConfig;
import dev.itara.spi.authorization.ItaraAuthorization;
import dev.itara.spi.authorization.ItaraAuthorizationConfig;
import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.ItaraSerializerGroupingKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ItaraDispatcher")
class ItaraDispatcherTest {

    @BeforeAll
    static void initObservability() {
        ObservabilityFacade.initialize();
    }

    private ClassLoader originalTccl;

    @BeforeEach
    void setUp() {
        ItaraRegistry.instance().reset();
        originalTccl = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(originalTccl);
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    public interface CaptureContract {
        String captureTccl(String ignored);
    }

    public static class CaptureImpl implements CaptureContract {
        @Override
        public String captureTccl(String ignored) {
            return Thread.currentThread().getContextClassLoader().toString();
        }
    }

    public static class CaptureActivator implements ItaraActivator {
        @Override
        public Object activate() {
            return new CaptureImpl();
        }
    }

    static class TestGroupingKey implements ItaraSerializerGroupingKey {
        @Override
        public boolean equals(Object o) {
            return o instanceof TestGroupingKey;
        }
        @Override
        public int hashCode() {
            return TestGroupingKey.class.hashCode();
        }
    }

    static class TestSerializerConfig implements ItaraSerializerConfig {
        @Override
        public ItaraSerializerGroupingKey groupingKey() {
            return new TestGroupingKey();
        }
    }

    private static final ItaraSerializerConfig TEST_SERIALIZER_CONFIG = new TestSerializerConfig();

    private static final String NODE_ID = "test-node";

    private static final ItaraAuthentication NOOP_AUTHENTICATION = new NoopAuthentication();
    private static final ItaraAuthenticationConfig NOOP_AUTHENTICATION_CONFIG =
            new NoopAuthentication.Factory().parseConfig(AuthenticationConfig.builder().build());

    private static final ItaraAuthorization NOOP_AUTHORIZATION = new NoopAuthorization();
    private static final ItaraAuthorizationConfig NOOP_AUTHORIZATION_CONFIG =
            new NoopAuthorization.Factory().parseConfig(AuthorizationConfig.builder().build());

    /** Headers a caller would have sent — carries the claimed target the dispatcher now requires. */
    private static Map<String, String> headersFor(String componentId, String methodName) {
        return CallTargetPropagation.toHeaders(ItaraCallTarget.of(NODE_ID, componentId, methodName));
    }

    /** Passes byte payloads through untouched — args/results are simple strings in these tests. */
    static class PassthroughSerializer implements ItaraSerializer {
        @Override
        public String type() {
            return "passthrough";
        }

        @Override
        public byte[] serializeArgs(Object[] args, ItaraSerializerConfig config) {
            return new byte[0];
        }

        @Override
        public Object[] deserializeArgs(byte[] bytes, Class<?>[] paramTypes, ItaraSerializerConfig config) {
            // Sized to match the target method's actual parameter count —
            // null is a valid value for the reference-typed params used
            // in these tests (captureTccl ignores its argument; explode
            // takes none).
            return new Object[paramTypes.length];
        }

        @Override
        public byte[] serializeResult(Object result, ItaraSerializerConfig config) {
            return String.valueOf(result).getBytes();
        }

        @Override
        public Object deserializeResult(byte[] bytes, Class<?> returnType, ItaraSerializerConfig config) {
            return new String(bytes);
        }
    }

    private static ClassLoader freshClassLoader() {
        return new URLClassLoader(new URL[0], Thread.currentThread().getContextClassLoader());
    }

    // ── Constructor ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("constructor")
    class Constructor {

        @Test
        @DisplayName("throws immediately when null component scope is provided")
        void throwsWhenComponentClassLoaderNotRegistered() {
            assertThrows(NullPointerException.class, () ->
                    new ItaraDispatcher("conn-unregistered", "unregistered", "test-transport",
                            new PassthroughSerializer(), TEST_SERIALIZER_CONFIG, ItaraRegistry.instance(),
                            ExchangePattern.REQUEST_REPLY,
                            NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                            NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG, null));
        }
    }

    // ── dispatch — TCCL handling ─────────────────────────────────────────

    @Nested
    @DisplayName("dispatch — TCCL handling")
    class DispatchTccl {

        @Test
        @DisplayName("sets TCCL to the component's classloader during invocation, restores it afterward")
        void setsAndRestoresTcclAroundInvocation() throws Exception {
            ClassLoader componentClassLoader = freshClassLoader();
            ClassLoader ambientClassLoader = freshClassLoader();
            assertNotSame(componentClassLoader, ambientClassLoader, "precondition: the two loaders must differ");

            ItaraRegistry.instance().registerActivator("capture", CaptureActivator.class);

            ComponentScope scope = new ComponentScope.Factory()
                    .nodeId(NODE_ID)
                    .componentId("capture")
                    .classLoader(componentClassLoader)
                    .build();

            ItaraDispatcher dispatcher = new ItaraDispatcher(
                    "conn-capture", "capture", "test-transport", new PassthroughSerializer(),
                    TEST_SERIALIZER_CONFIG, ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY,
                    NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                    NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG, scope);

            Thread.currentThread().setContextClassLoader(ambientClassLoader);

            byte[] result = dispatcher.dispatch(new byte[0], headersFor("capture", "captureTccl"), null);

            String observedDuringCall = new String(result);
            assertTrue(observedDuringCall.contains(componentClassLoader.toString()),
                    "TCCL during dispatch must be the component's own classloader");
            assertSame(ambientClassLoader, Thread.currentThread().getContextClassLoader(),
                    "TCCL must be restored to the ambient value after dispatch returns");
        }

        @Test
        @DisplayName("restores TCCL even when the component method throws")
        void restoresTcclEvenWhenComponentThrows() throws Exception {
            ClassLoader componentClassLoader = freshClassLoader();
            ItaraRegistry.instance().registerActivator("throwing", ThrowingActivator.class);

            ComponentScope scope = new ComponentScope.Factory()
                    .nodeId("throwingNode")
                    .componentId("throwing")
                    .classLoader(componentClassLoader)
                    .build();

            ItaraDispatcher dispatcher = new ItaraDispatcher(
                    "conn-throwing", "throwing", "test-transport", new PassthroughSerializer(),
                    TEST_SERIALIZER_CONFIG, ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY,
                    NOOP_AUTHENTICATION, NOOP_AUTHENTICATION_CONFIG,
                    NOOP_AUTHORIZATION, NOOP_AUTHORIZATION_CONFIG, scope);

            ClassLoader ambientClassLoader = Thread.currentThread().getContextClassLoader();

            assertThrows(ItaraRemoteException.class, () ->
                    dispatcher.dispatch(new byte[0], headersFor("throwing", "explode"), null));

            assertSame(ambientClassLoader, Thread.currentThread().getContextClassLoader());
        }
    }

    public interface ThrowingContract {
        void explode();
    }

    public static class ThrowingImpl implements ThrowingContract {
        @Override
        public void explode() {
            throw new RuntimeException("boom");
        }
    }

    public static class ThrowingActivator implements ItaraActivator {
        @Override
        public Object activate() {
            return new ThrowingImpl();
        }
    }
}
