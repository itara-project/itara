package dev.itara.examples.authnauthz.gatewayb.api;

import dev.itara.api.ComponentInterface;

/**
 * Externally reachable over Itara's own HTTP transport, mirroring
 * gateway-a exactly. Its connection to backend presents the wrong shared
 * secret — every call through it is rejected at authentication,
 * regardless of which method is invoked, before authorization is ever
 * consulted. See the README.
 */
@ComponentInterface(id = "gateway-b")
public interface GatewayBService {

    String shout(String input);

    String whisper(String input);
}
