package dev.itara.serializer.java;

import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.ItaraSerializerGroupingKey;

/**
 * The parsed config for the Java serializer — currently carries nothing,
 * because the Java serializer has no per-connection configuration.
 *
 * A genuine singleton: every connection declaring id "java" shares this
 * one instance and its equal-to-itself grouping key, since there is
 * nothing in the params map that could ever justify a separate instance.
 * Unlike the JSON serializer, real per-connection configuration here is
 * considered unlikely — Java object serialization has essentially no
 * dials to turn — so this stays a straightforward singleton rather than
 * being parsed from params.
 */
final class JavaSerializerConfig implements ItaraSerializerConfig, ItaraSerializerGroupingKey {

    static final JavaSerializerConfig INSTANCE = new JavaSerializerConfig();

    public static JavaSerializerConfig getINSTANCE() {
        return INSTANCE;
    }

    private JavaSerializerConfig() {
    }

    @Override
    public ItaraSerializerGroupingKey groupingKey() {
        return this;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JavaSerializerConfig;
    }

    @Override
    public int hashCode() {
        return JavaSerializerConfig.class.hashCode();
    }
}
