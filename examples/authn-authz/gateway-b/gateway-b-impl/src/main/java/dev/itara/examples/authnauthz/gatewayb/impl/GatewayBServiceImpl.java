package dev.itara.examples.authnauthz.gatewayb.impl;

import dev.itara.examples.authnauthz.backend.api.BackendService;
import dev.itara.examples.authnauthz.gatewayb.api.GatewayBService;

/**
 * Identical shape to GatewayAServiceImpl — plain delegation, no
 * auth-related code, no awareness that its connection to backend is
 * misconfigured in this deployment. That misconfiguration lives entirely
 * in wiring.yaml.
 */
public class GatewayBServiceImpl implements GatewayBService {

    private final BackendService backend;

    public GatewayBServiceImpl(BackendService backend) {
        this.backend = backend;
    }

    @Override
    public String shout(String input) {
        return backend.shout(input);
    }

    @Override
    public String whisper(String input) {
        return backend.whisper(input);
    }
}
