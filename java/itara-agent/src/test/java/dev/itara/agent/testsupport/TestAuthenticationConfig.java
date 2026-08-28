package dev.itara.agent.testsupport;

import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.authentication.ItaraAuthenticationGroupingKey;

/**
 * Minimal ItaraAuthenticationConfig test double — a single shared instance
 * is enough for tests that only care about authenticate()/produceAssertion()
 * behavior, not grouping or per-connection parameters.
 */
public class TestAuthenticationConfig implements ItaraAuthenticationConfig, ItaraAuthenticationGroupingKey {

    public static final TestAuthenticationConfig INSTANCE = new TestAuthenticationConfig();

    @Override
    public ItaraAuthenticationGroupingKey groupingKey() {
        return this;
    }
}
