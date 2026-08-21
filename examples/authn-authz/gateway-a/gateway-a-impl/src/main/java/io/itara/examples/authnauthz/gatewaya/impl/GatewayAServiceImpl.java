package io.itara.examples.authnauthz.gatewaya.impl;

import io.itara.examples.authnauthz.backend.api.BackendService;
import io.itara.examples.authnauthz.gatewaya.api.GatewayAService;

/**
 * Delegates each method straight through to the matching method on
 * backend. There is nothing auth-related in this class — the connection
 * to backend already has its own authentication and authorization
 * configured entirely in wiring.yaml. This class calls backend exactly
 * the way it would call any other dependency, with no idea any of that
 * machinery exists.
 */
public class GatewayAServiceImpl implements GatewayAService {

    private final BackendService backend;

    public GatewayAServiceImpl(BackendService backend) {
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
