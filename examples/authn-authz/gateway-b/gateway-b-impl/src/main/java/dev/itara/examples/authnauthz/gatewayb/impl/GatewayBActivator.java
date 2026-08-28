package dev.itara.examples.authnauthz.gatewayb.impl;

import dev.itara.api.ItaraActivator;
import dev.itara.examples.authnauthz.backend.api.BackendService;
import dev.itara.runtime.ComponentLookup;

public class GatewayBActivator implements ItaraActivator {

    @Override
    public Object activate() {
        BackendService backend = ComponentLookup.get("backend", BackendService.class);
        return new GatewayBServiceImpl(backend);
    }
}
