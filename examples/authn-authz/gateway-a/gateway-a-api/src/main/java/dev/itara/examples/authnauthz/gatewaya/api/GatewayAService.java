package dev.itara.examples.authnauthz.gatewaya.api;

import dev.itara.api.ComponentInterface;

/**
 * Externally reachable over Itara's own HTTP transport — this is the
 * curl-able entry point for this example (see the README), not a
 * hand-rolled server. Mirrors backend's two methods 1:1, purely so each
 * call's outcome depends only on which method was invoked, nothing else.
 */
@ComponentInterface(id = "gateway-a")
public interface GatewayAService {

    String shout(String input);

    String whisper(String input);
}
