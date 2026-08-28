package dev.itara.examples.authnauthz.backend.impl;

import dev.itara.api.ItaraActivator;

public class BackendActivator implements ItaraActivator {

    @Override
    public Object activate() {
        return new BackendServiceImpl();
    }
}
