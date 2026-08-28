package dev.itara.agent.testsupport;

import dev.itara.runtime.ItaraCallTarget;
import dev.itara.spi.authentication.AuthenticationOutcome;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.identity.ItaraIdentity;
import dev.itara.spi.identity.ItaraTransportCredential;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Configurable ItaraAuthentication test double. Defaults to accepting with
 * no identity until told otherwise. Records call counts and the last-seen
 * arguments so tests can assert on both behavior and what the SPI contract
 * actually received.
 */
public class ConfigurableAuthentication implements ItaraAuthentication {

    private enum Mode { ACCEPT, REJECT, THROW }

    private volatile Mode mode = Mode.ACCEPT;
    private volatile ItaraIdentity identityToProduce;
    private volatile String rejectionReason = "rejected";
    private volatile RuntimeException exceptionToThrow = new RuntimeException("boom");
    private volatile Map<String, String> assertionToProduce = Collections.emptyMap();

    public final AtomicInteger produceAssertionCalls = new AtomicInteger();
    public final AtomicInteger authenticateCalls = new AtomicInteger();

    public volatile ItaraCallTarget lastProduceAssertionTarget;
    public volatile Map<String, String> lastAuthenticateHeaders;
    public volatile ItaraTransportCredential lastTransportCredential;

    public static ConfigurableAuthentication accepting() {
        return new ConfigurableAuthentication();
    }

    public static ConfigurableAuthentication acceptingWithIdentity(ItaraIdentity identity) {
        ConfigurableAuthentication a = new ConfigurableAuthentication();
        a.identityToProduce = identity;
        return a;
    }

    public static ConfigurableAuthentication rejecting(String reason) {
        ConfigurableAuthentication a = new ConfigurableAuthentication();
        a.mode = Mode.REJECT;
        a.rejectionReason = reason;
        return a;
    }

    public static ConfigurableAuthentication throwing(RuntimeException e) {
        ConfigurableAuthentication a = new ConfigurableAuthentication();
        a.mode = Mode.THROW;
        a.exceptionToThrow = e;
        return a;
    }

    public ConfigurableAuthentication withAssertion(Map<String, String> assertion) {
        this.assertionToProduce = assertion;
        return this;
    }

    @Override
    public Map<String, String> produceAssertion(ItaraAuthenticationConfig config, ItaraCallTarget target) throws Exception {
        produceAssertionCalls.incrementAndGet();
        lastProduceAssertionTarget = target;
        if (mode == Mode.THROW) throw exceptionToThrow;
        return assertionToProduce;
    }

    @Override
    public AuthenticationOutcome authenticate(ItaraAuthenticationConfig config, Map<String, String> headers, ItaraTransportCredential transportCredential) throws Exception {
        authenticateCalls.incrementAndGet();
        lastAuthenticateHeaders = headers;
        lastTransportCredential = transportCredential;
        switch (mode) {
            case THROW:
                throw exceptionToThrow;
            case REJECT:
                return AuthenticationOutcome.rejected(rejectionReason);
            default:
                return identityToProduce != null
                        ? AuthenticationOutcome.accepted(identityToProduce)
                        : AuthenticationOutcome.accepted();
        }
    }
}
