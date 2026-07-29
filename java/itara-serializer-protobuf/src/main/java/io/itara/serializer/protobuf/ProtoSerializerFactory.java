package io.itara.serializer.protobuf;

import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.ItaraSerializerFactory;
import io.itara.spi.serializer.SerializerConfig;

/**
 * Factory for {@link ProtoItaraSerializer}.
 *
 * Discovered by the agent via META-INF/itara/serializer, which names this
 * factory rather than ProtoItaraSerializer directly.
 *
 * The proto serializer has no per-connection configuration today — every
 * connection declaring serializer id "protobuf" parses to the same
 * constant config (see {@link ProtoSerializerConfig}) and therefore
 * shares a single ProtoItaraSerializer instance, regardless of what — if
 * anything — is present in that connection's serializer params map.
 */
public class ProtoSerializerFactory implements ItaraSerializerFactory {

    @Override
    public String id() {
        return "protobuf";
    }

    @Override
    public ItaraSerializerConfig parseConfig(SerializerConfig config) {
        return ProtoSerializerConfig.INSTANCE;
    }

    @Override
    public ItaraSerializer create(ItaraSerializerConfig config) {
        return new ProtoItaraSerializer();
    }
}
