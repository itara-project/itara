package io.itara.agent.testsupport;

import io.itara.spi.authorization.ItaraAuthorizationConfig;
import io.itara.spi.authorization.ItaraAuthorizationGroupingKey;

public class TestAuthorizationConfig implements ItaraAuthorizationConfig, ItaraAuthorizationGroupingKey {

    public static final TestAuthorizationConfig INSTANCE = new TestAuthorizationConfig();

    @Override
    public ItaraAuthorizationGroupingKey groupingKey() {
        return this;
    }
}
