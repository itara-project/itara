package io.itara.examples.authnauthz.gatewaya.impl;

import io.itara.api.ItaraActivator;
import io.itara.examples.authnauthz.backend.api.BackendService;
import io.itara.runtime.ComponentLookup;

public class GatewayAActivator implements ItaraActivator {

    @Override
    public Object activate() {
        BackendService backend = ComponentLookup.get("backend", BackendService.class);
        return new GatewayAServiceImpl(backend);
    }
}
