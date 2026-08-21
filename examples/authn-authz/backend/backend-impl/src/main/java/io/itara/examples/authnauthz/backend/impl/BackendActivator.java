package io.itara.examples.authnauthz.backend.impl;

import io.itara.api.ItaraActivator;

public class BackendActivator implements ItaraActivator {

    @Override
    public Object activate() {
        return new BackendServiceImpl();
    }
}
