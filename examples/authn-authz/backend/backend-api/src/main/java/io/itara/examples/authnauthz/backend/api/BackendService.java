package io.itara.examples.authnauthz.backend.api;

import io.itara.api.ComponentInterface;

/**
 * Plain contract, no message format, no auth-awareness of any kind —
 * authentication and authorization are entirely connection-level
 * concerns, invisible to this interface and to whatever implements it.
 *
 * Two methods exist specifically so gateway-a's connection to backend
 * can demonstrate an allow rule and a deny rule side by side, on the
 * same connection, with the same authenticated identity — see the
 * example's README.
 */
@ComponentInterface(id = "backend")
public interface BackendService {

    /** Allowed for gateway-a's connection; not for gateway-b's (rejected earlier, at authentication). */
    String shout(String input);

    /** Denied for gateway-a's connection specifically, by its authorization rule table. */
    String whisper(String input);
}
