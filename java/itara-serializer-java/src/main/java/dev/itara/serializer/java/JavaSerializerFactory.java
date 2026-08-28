package dev.itara.serializer.java;

import dev.itara.spi.serializer.ItaraSerializer;
import dev.itara.spi.serializer.ItaraSerializerConfig;
import dev.itara.spi.serializer.ItaraSerializerFactory;
import dev.itara.spi.serializer.SerializerConfig;

public class JavaSerializerFactory implements ItaraSerializerFactory {

    @Override
    public String id() {
        return "java";
    }

    @Override
    public ItaraSerializerConfig parseConfig(SerializerConfig config) {
        return JavaSerializerConfig.getINSTANCE();
    }

    @Override
    public ItaraSerializer create(ItaraSerializerConfig config) {
        return new JavaItaraSerializer();
    }
}
