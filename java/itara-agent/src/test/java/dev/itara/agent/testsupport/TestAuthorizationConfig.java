package dev.itara.agent.testsupport;

import dev.itara.spi.authorization.ItaraAuthorizationConfig;
import dev.itara.spi.authorization.ItaraAuthorizationGroupingKey;

public class TestAuthorizationConfig implements ItaraAuthorizationConfig, ItaraAuthorizationGroupingKey {

    public static final TestAuthorizationConfig INSTANCE = new TestAuthorizationConfig();

    @Override
    public ItaraAuthorizationGroupingKey groupingKey() {
        return this;
    }
}
