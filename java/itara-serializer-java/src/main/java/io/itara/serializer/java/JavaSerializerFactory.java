package io.itara.serializer.java;

import io.itara.spi.serializer.ItaraSerializer;
import io.itara.spi.serializer.ItaraSerializerConfig;
import io.itara.spi.serializer.ItaraSerializerFactory;
import io.itara.spi.serializer.SerializerConfig;

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
