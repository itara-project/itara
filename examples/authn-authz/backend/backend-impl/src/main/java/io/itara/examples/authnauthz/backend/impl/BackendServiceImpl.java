package io.itara.examples.authnauthz.backend.impl;

import io.itara.examples.authnauthz.backend.api.BackendService;

/**
 * Deliberately trivial business logic — the point of this example is
 * everything happening around this call, not this call itself. No
 * auth-related code here at all: authentication and authorization
 * already ran, and either allowed this method to be reached or didn't,
 * before this class is ever touched.
 */
public class BackendServiceImpl implements BackendService {

    @Override
    public String shout(String input) {
        return input.toUpperCase() + "!";
    }

    @Override
    public String whisper(String input) {
        return input.toLowerCase() + "...";
    }
}
