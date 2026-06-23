package io.itara.failuresemantics.builtin;

import io.itara.spi.failuresemantics.FailureSemanticsConfig;
import io.itara.spi.failuresemantics.ItaraFailureSemantics;
import io.itara.spi.failuresemantics.ItaraFailureSemanticsFactory;
import io.itara.util.DurationParser;

import java.time.Duration;
import java.util.Map;

/**
 * Factory for the built-in Resilience4j-backed failure semantics.
 *
 * Discovered via META-INF/itara/failure-semantics. Registered by the
 * agent alongside the noop factory at startup.
 *
 * Supported params (all optional):
 *   waitDuration       — fixed wait between attempts, ISO-8601 (default: 500ms)
 *   retryNonIdempotent — "true" to allow retrying non-idempotent methods (default: false)
 */
public class BuiltInFailureSemanticsFactory implements ItaraFailureSemanticsFactory {

    @Override
    public String type() {
        return "built-in";
    }

    @Override
    public ItaraFailureSemantics create(FailureSemanticsConfig config) throws Exception {
        Map<String, String> params = config.getParams();

        int maxAttempts = config.getMaxAttempts() != null
                ? config.getMaxAttempts()
                : BuiltInConfig.DEFAULT_MAX_ATTEMPTS;

        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "[Itara/built-in] maxAttempts must be >= 1, got: " + maxAttempts);
        }

        Duration waitDuration = params.containsKey("waitDuration")
                ? DurationParser.parse(params.get("waitDuration"))
                : BuiltInConfig.DEFAULT_WAIT_DURATION;

        boolean retryNonIdempotent = Boolean.parseBoolean(
                params.getOrDefault("retryNonIdempotent", "false"));

        BuiltInConfig builtInConfig = new BuiltInConfig(
                maxAttempts,
                waitDuration,
                config.getTimeout(),
                config.isHandleTimeout(),
                config.getAbsoluteTimeout(),
                retryNonIdempotent
        );

        return new BuiltInFailureSemantics(builtInConfig);
    }
}
