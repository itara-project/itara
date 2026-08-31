package dev.itara.examples.authnauthz.gatewaya.impl;

import dev.itara.api.ItaraActivator;
import dev.itara.examples.authnauthz.backend.api.BackendService;
import dev.itara.runtime.ComponentLookup;

public class GatewayAActivator implements ItaraActivator {

    @Override
    public Object activate() {
        BackendService backend = ComponentLookup.get("backend", BackendService.class);
        return new GatewayAServiceImpl(backend);
    }
}
