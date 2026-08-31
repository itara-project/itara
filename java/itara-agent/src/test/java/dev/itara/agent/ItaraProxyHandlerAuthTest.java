package dev.itara.agent;

import dev.itara.agent.testsupport.ConfigurableAuthentication;
import dev.itara.agent.testsupport.TestAuthenticationConfig;
import dev.itara.exceptions.ItaraRemoteException;
import dev.itara.runtime.ComponentScope;
import dev.itara.runtime.DispatchHandler;
import dev.itara.runtime.ExchangePattern;
import dev.itara.runtime.ItaraCallTarget;
import dev.itara.runtime.ObservabilityFacade;
import dev.itara.spi.failuresemantics.ItaraFailureSemantics;
import dev.itara.spi.failuresemantics.TransportCall;
import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.ItaraSerializerGroupingKey;
import dev.itara.spi.transport.ItaraTransport;
import dev.itara.spi.transport.ItaraTransportConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ItaraProxyHandler — authentication")
class ItaraProxyHandlerAuthTest {

    private static final String COMPONENT_ID = "greeter";
    private static final String NODE_ID = "greeterNode";

    private static final ComponentScope FROM_SCOPE = new ComponentScope.Factory()
            .nodeId("callerNode")
            .componentId("caller")
            .classLoader(ItaraProxyHandlerAuthTest.class.getClassLoader())
            .build();

    @BeforeAll
    static void initObservability() {
        ObservabilityFacade.initialize();
    }

    public interface GreeterContract {
        String greet();
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

    /** Fails the first failuresBeforeSuccess attempts with a retriable TRANSPORT error, then succeeds. */
    static class FlakyTransport implements ItaraTransport {
        private final int failuresBeforeSuccess;
        final AtomicInteger callCount = new AtomicInteger();
        final List<Map<String, String>> receivedHeaders = new ArrayList<>();

        FlakyTransport(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public byte[] send(ItaraCallTarget target, byte[] payload, Map<String, String> headers,
                           ItaraTransportConfig config, Duration timeout) throws Exception {
            receivedHeaders.add(Map.copyOf(headers));
            int attempt = callCount.incrementAndGet();
            if (attempt <= failuresBeforeSuccess) {
                throw new ItaraRemoteException(ItaraRemoteException.ErrorKind.TRANSPORT,
                        "java.net.ConnectException", "simulated failure");
            }
            return "hello".getBytes();
        }

        @Override
        public void registerListener(ItaraTransportConfig config, DispatchHandler dispatcher) {}
        @Override public void start() {}
        @Override public void stop() {}
    }

    /** Retries up to maxAttempts times on TRANSPORT failure, otherwise rethrows immediately. */
    static class RetryingFailureSemantics implements ItaraFailureSemantics {
        private final int maxAttempts;
        RetryingFailureSemantics(int maxAttempts) { this.maxAttempts = maxAttempts; }

        @Override
        public byte[] execute(TransportCall work, boolean idempotent) throws ItaraRemoteException {
            ItaraRemoteException last = null;
            for (int i = 0; i < maxAttempts; i++) {
                try {
                    return work.call(null);
                } catch (ItaraRemoteException e) {
                    if (e.getErrorKind() != ItaraRemoteException.ErrorKind.TRANSPORT) throw e;
                    last = e;
                }
            }
            throw last;
        }
    }

    private GreeterContract proxyWith(ConfigurableAuthentication authn, ItaraTransport transport, ItaraFailureSemantics fs) {
        return (GreeterContract) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{ GreeterContract.class },
                new ItaraProxyHandler("conn-greeter", COMPONENT_ID, NODE_ID, new PassthroughSerializer(),
                        TEST_SERIALIZER_CONFIG, transport, "test-transport", null, ExchangePattern.REQUEST_REPLY,
                        fs, authn, TestAuthenticationConfig.INSTANCE, null, null, FROM_SCOPE));
    }

    @Nested
    @DisplayName("retry-reuse of the identity assertion")
    class RetryReuse {

        @Test
        @DisplayName("produceAssertion() is called exactly once across multiple retries")
        void producesAssertionOnceAcrossRetries() {
            ConfigurableAuthentication authn = ConfigurableAuthentication.accepting()
                    .withAssertion(Map.of("x-test-assertion", "token-123"));
            FlakyTransport transport = new FlakyTransport(2); // fails twice, succeeds on the 3rd
            GreeterContract proxy = proxyWith(authn, transport, new RetryingFailureSemantics(3));

            String result = proxy.greet();

            assertEquals("hello", result);
            assertEquals(3, transport.callCount.get(), "precondition: this test needs multiple actual attempts");
            assertEquals(1, authn.produceAssertionCalls.get(),
                    "produceAssertion() must be called once per call, not once per attempt");
        }

        @Test
        @DisplayName("the assertion is present in headers on every attempt, not just the first")
        void assertionPresentOnEveryAttempt() {
            ConfigurableAuthentication authn = ConfigurableAuthentication.accepting()
                    .withAssertion(Map.of("x-test-assertion", "token-123"));
            FlakyTransport transport = new FlakyTransport(2);
            proxyWith(authn, transport, new RetryingFailureSemantics(3)).greet();

            assertEquals(3, transport.receivedHeaders.size());
            for (Map<String, String> headers : transport.receivedHeaders) {
                assertEquals("token-123", headers.get("x-test-assertion"),
                        "the same assertion must ride along on every attempt, reused not regenerated");
            }
        }
    }

    @Nested
    @DisplayName("authentication failure")
    class AuthenticationFailure {

        @Test
        @DisplayName("produceAssertion() throwing surfaces as TRANSPORT, and the transport is never called")
        void producingAssertionFailureSurfacesAsTransport() {
            ConfigurableAuthentication authn = ConfigurableAuthentication.throwing(new RuntimeException("keystore locked"));
            FlakyTransport transport = new FlakyTransport(0);
            GreeterContract proxy = proxyWith(authn, transport, new RetryingFailureSemantics(1));

            ItaraRemoteException ex = assertThrows(ItaraRemoteException.class, proxy::greet);

            assertEquals(ItaraRemoteException.ErrorKind.TRANSPORT, ex.getErrorKind());
            assertEquals(0, transport.callCount.get(), "transport must never be reached if the assertion couldn't be produced");
        }
    }
}
