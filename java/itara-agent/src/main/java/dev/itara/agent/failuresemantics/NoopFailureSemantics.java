package dev.itara.agent.failuresemantics;

import dev.itara.exceptions.ItaraRemoteException;
import dev.itara.spi.failuresemantics.FailureSemanticsConfig;
import dev.itara.spi.failuresemantics.ItaraFailureSemantics;
import dev.itara.spi.failuresemantics.ItaraFailureSemanticsFactory;
import dev.itara.spi.failuresemantics.TransportCall;

/**
 * No-op failure semantics implementation.
 *
 * <p>Executes the transport call exactly once and surfaces any error
 * immediately to the caller without retry, timeout enforcement, or
 * circuit breaking. This is the default behaviour when no
 * failureSemantics block is declared on a connection (§14.1).
 *
 * <p>Registered directly by the agent at startup — not discovered via
 * META-INF/itara/failure-semantics.
 */
public class NoopFailureSemantics implements ItaraFailureSemantics {

    /**
     * The idempotency flag has no effect in this implementation —
     * the call is never retried regardless.
     */
    @Override
    public byte[] execute(TransportCall work, boolean idempotent) throws ItaraRemoteException {
        return work.call(null);
    }

    /**
     * Factory for the noop implementation.
     *
     * Config is accepted but ignored — the noop strategy has no
     * configurable behaviour.
     */
    public static final class Factory implements ItaraFailureSemanticsFactory {

        @Override
        public String type() {
            return "noop";
        }

        @Override
        public ItaraFailureSemantics create(FailureSemanticsConfig config) {
            return new NoopFailureSemantics();
        }
    }
}
