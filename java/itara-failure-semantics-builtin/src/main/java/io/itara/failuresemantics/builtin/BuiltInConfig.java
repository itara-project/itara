package io.itara.failuresemantics.builtin;

import java.time.Duration;

/**
 * Parsed, validated configuration for the built-in failure semantics.
 *
 * Constructed once by BuiltInFailureSemanticsFactory at startup from
 * FailureSemanticsConfig. All fields are typed and validated — if
 * construction succeeds, the configuration is guaranteed usable.
 */
final class BuiltInConfig {

    static final int      DEFAULT_MAX_ATTEMPTS      = 3;
    static final Duration DEFAULT_WAIT_DURATION     = Duration.ofMillis(500);

    final int      maxAttempts;
    final Duration waitDuration;
    final Duration timeout;
    final boolean  handleTimeout;
    final Duration absoluteTimeout;
    final boolean  retryNonIdempotent;

    BuiltInConfig(int maxAttempts,
                  Duration waitDuration,
                  Duration timeout,
                  boolean handleTimeout,
                  Duration absoluteTimeout,
                  boolean retryNonIdempotent) {
        this.maxAttempts        = maxAttempts;
        this.waitDuration       = waitDuration;
        this.timeout            = timeout;
        this.handleTimeout      = handleTimeout;
        this.absoluteTimeout    = absoluteTimeout;
        this.retryNonIdempotent = retryNonIdempotent;
    }
}
