package io.itara.agent.testsupport;

import io.itara.runtime.ItaraCallTarget;
import io.itara.spi.authorization.AuthorizationDecision;
import io.itara.spi.authorization.ItaraAuthorization;
import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.identity.ItaraIdentity;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public class ConfigurableAuthorization implements ItaraAuthorization {

    private enum Mode { PERMIT, DENY, THROW }

    private volatile Mode mode = Mode.PERMIT;
    private volatile String denialReason = "denied";
    private volatile RuntimeException exceptionToThrow = new RuntimeException("boom");

    public final AtomicInteger authorizeCalls = new AtomicInteger();
    public volatile Optional<ItaraIdentity> lastIdentity;
    public volatile ItaraCallTarget lastTarget;
    public volatile Map<String, String> lastHeaders;

    public static ConfigurableAuthorization permitting() {
        return new ConfigurableAuthorization();
    }

    public static ConfigurableAuthorization denying(String reason) {
        ConfigurableAuthorization a = new ConfigurableAuthorization();
        a.mode = Mode.DENY;
        a.denialReason = reason;
        return a;
    }

    public static ConfigurableAuthorization throwing(RuntimeException e) {
        ConfigurableAuthorization a = new ConfigurableAuthorization();
        a.mode = Mode.THROW;
        a.exceptionToThrow = e;
        return a;
    }

    @Override
    public AuthorizationDecision authorize(ItaraAuthorizationConfig config, Optional<ItaraIdentity> identity, ItaraCallTarget target, Map<String, String> headers) throws Exception {
        authorizeCalls.incrementAndGet();
        lastIdentity = identity;
        lastTarget = target;
        lastHeaders = headers;
        switch (mode) {
            case THROW:
                throw exceptionToThrow;
            case DENY:
                return AuthorizationDecision.deny(denialReason);
            default:
                return AuthorizationDecision.permit();
        }
    }
}
