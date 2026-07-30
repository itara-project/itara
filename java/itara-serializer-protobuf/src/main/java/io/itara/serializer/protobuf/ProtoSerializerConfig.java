package io.itara.serializer.protobuf;

import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.ItaraSerializerGroupingKey;

/**
 * The parsed config for the proto serializer — currently carries nothing,
 * because the proto serializer has no per-connection configuration.
 *
 * A genuine singleton, same call as JavaSerializerConfig and for the same
 * reason: everything the proto serializer needs (the target class for a
 * given call) is derived from the contract method's own declared types at
 * call time via reflection, not from anything a connection could
 * configure. Unlike the JSON serializer, there is no obvious knob
 * (encoding options, module registration, etc.) that would need
 * per-connection variation here. If that changes — e.g. a future
 * extension-registry or unknown-fields-handling option — this stops
 * being a singleton and groupingKey() should be derived from whatever
 * that configuration turns out to be, mirroring how JsonSerializerConfig
 * is built.
 */
final class ProtoSerializerConfig implements ItaraSerializerConfig, ItaraSerializerGroupingKey {

    static final ProtoSerializerConfig INSTANCE = new ProtoSerializerConfig();

    private ProtoSerializerConfig() {
    }

    @Override
    public ItaraSerializerGroupingKey groupingKey() {
        return this;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProtoSerializerConfig;
    }

    @Override
    public int hashCode() {
        return ProtoSerializerConfig.class.hashCode();
    }
}
