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
import dev.itara.spi.authorization.AuthorizationConfig;
import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.ItaraSerializerGroupingKey;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression coverage for a bug found while building the authn/authz
 * example: a component method that itself made an outbound call to
 * another component, didn't catch that call's failure, and let it
 * propagate uncaught — the classic "chained call" case.
 * ItaraDispatcher's InvocationTargetException handling used to
 * reclassify any RuntimeException-derived cause as ErrorKind.RUNTIME,
 * which silently discarded the original ItaraRemoteException's real
 * kind (PERMISSION, in the case that surfaced this) and its
 * remoteExceptionClass, keeping only the message text.
 */
class ItaraDispatcherExceptionPropagationTest {

    private static final String NODE_ID = "test-node";
    private static final String COMPONENT_ID = "chain";

    @BeforeAll
    static void initObservability() {
        ObservabilityFacade.initialize();
    }

    @BeforeEach
    void setUp() {
        ItaraRegistry.instance().reset();
    }

    public interface ChainContract {
        String call();
    }

    /** Simulates a component whose own outbound call failed and propagated uncaught. */
    public static class PermissionThrowingImpl implements ChainContract {
        @Override
        public String call() {
            throw new ItaraRemoteException(
                    ItaraRemoteException.ErrorKind.PERMISSION,
                    "AuthorizationDenied",
                    "denied by a downstream connection");
        }
    }

    public static class PermissionThrowingActivator implements ItaraActivator {
        @Override
        public Object activate() { return new PermissionThrowingImpl(); }
    }

    /** Ordinary, unrelated business exception — must still classify as RUNTIME. */
    public static class PlainRuntimeThrowingImpl implements ChainContract {
        @Override
        public String call() {
            throw new IllegalStateException("ordinary business failure");
        }
    }

    public static class PlainRuntimeThrowingActivator implements ItaraActivator {
        @Override
        public Object activate() { return new PlainRuntimeThrowingImpl(); }
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
        return CallTargetPropagation.toHeaders(ItaraCallTarget.of(NODE_ID, COMPONENT_ID, "call"));
    }

    private ItaraDispatcher dispatcherFor(Class<? extends ItaraActivator> activatorClass) throws Exception {
        ItaraRegistry.instance().registerActivator(COMPONENT_ID, activatorClass);
        ComponentScope scope = new ComponentScope.Factory()
                .nodeId(NODE_ID)
                .componentId(COMPONENT_ID)
                .classLoader(Thread.currentThread().getContextClassLoader())
                .build();
        return new ItaraDispatcher(
                "conn-chain", COMPONENT_ID, "test-transport", new PassthroughSerializer(),
                TEST_SERIALIZER_CONFIG, ItaraRegistry.instance(), ExchangePattern.REQUEST_REPLY,
                new NoopAuthentication(), new NoopAuthentication.Factory().parseConfig(AuthenticationConfig.builder().build()),
                new NoopAuthorization(), new NoopAuthorization.Factory().parseConfig(AuthorizationConfig.builder().build()),
                scope);
    }

    @Test
    @DisplayName("an ItaraRemoteException propagating uncaught from a component method keeps its own kind and remoteExceptionClass")
    void chainedItaraRemoteExceptionKeepsOwnKind() throws Exception {
        ItaraDispatcher dispatcher = dispatcherFor(PermissionThrowingActivator.class);

        ItaraRemoteException ex = assertThrows(ItaraRemoteException.class,
                () -> dispatcher.dispatch(new byte[0], headers(), null));

        assertEquals(ItaraRemoteException.ErrorKind.PERMISSION, ex.getErrorKind());
        assertEquals("AuthorizationDenied", ex.getRemoteExceptionClass());
        assertEquals("denied by a downstream connection", ex.getMessage());
    }

    @Test
    @DisplayName("an ordinary RuntimeException from business logic is still classified as RUNTIME")
    void ordinaryRuntimeExceptionStillClassifiedAsRuntime() throws Exception {
        ItaraDispatcher dispatcher = dispatcherFor(PlainRuntimeThrowingActivator.class);

        ItaraRemoteException ex = assertThrows(ItaraRemoteException.class,
                () -> dispatcher.dispatch(new byte[0], headers(), null));

        assertEquals(ItaraRemoteException.ErrorKind.RUNTIME, ex.getErrorKind());
        assertEquals(IllegalStateException.class.getName(), ex.getRemoteExceptionClass());
        assertEquals("ordinary business failure", ex.getMessage());
    }
}
