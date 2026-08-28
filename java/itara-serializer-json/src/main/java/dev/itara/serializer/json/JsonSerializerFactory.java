package dev.itara.serializer.json;

import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.ItaraSerializerFactory;
import dev.itara.spi.serializer.SerializerConfig;

/**
 * Factory for {@link JsonItaraSerializer}.
 *
 * Discovered by the agent via META-INF/itara/serializer, which now names
 * this factory rather than JsonItaraSerializer directly.
 *
 * Connections sharing equal serializer params (including two connections
 * that both declare none) share a single JsonItaraSerializer instance —
 * see {@link JsonSerializerConfig} for the grouping rationale.
 */
public class JsonSerializerFactory implements ItaraSerializerFactory {

    @Override
    public String id() {
        return "json";
    }

    @Override
    public ItaraSerializerConfig parseConfig(SerializerConfig config) {
        return new JsonSerializerConfig(config.getParams());
    }

    @Override
    public ItaraSerializer create(ItaraSerializerConfig config) {
        return new JsonItaraSerializer((JsonSerializerConfig) config);
    }
}
