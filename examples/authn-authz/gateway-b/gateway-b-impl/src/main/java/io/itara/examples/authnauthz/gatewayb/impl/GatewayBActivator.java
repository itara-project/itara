package io.itara.examples.authnauthz.gatewayb.impl;

import io.itara.api.ItaraActivator;
import io.itara.examples.authnauthz.backend.api.BackendService;
import io.itara.runtime.ComponentLookup;

public class GatewayBActivator implements ItaraActivator {

    @Override
    public Object activate() {
        BackendService backend = ComponentLookup.get("backend", BackendService.class);
        return new GatewayBServiceImpl(backend);
    }
}
