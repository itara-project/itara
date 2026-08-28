package dev.itara.examples.authnauthz.authn;

import dev.itara.runtime.ItaraCallTarget;
import dev.itara.spi.authentication.AuthenticationOutcome;
import dev.itara.spi.authentication.ItaraAuthentication;
import dev.itara.spi.authentication.ItaraAuthenticationConfig;
import dev.itara.spi.identity.ItaraIdentity;
import dev.itara.spi.identity.ItaraTransportCredential;

import java.util.HashMap;
import java.util.Map;

/**
 * Illustrative shared-secret authentication for this example only — not
 * part of Itara's core distribution, and not something to copy into a
 * real deployment as-is (no key rotation, no hashing, etc.).
 *
 * Demonstrates the shape every credential-based authentication
 * implementation follows, and specifically the one point this example
 * exists to make: a caller presents a credential and proves possession
 * of it — it never gets to declare its own identity. The identity a
 * successful authentication produces (subject) comes entirely from the
 * callee's own configuration for this connection, never from anything
 * the caller sent. Two differently-configured connections presenting
 * the same credential to the same callee would be authenticated as two
 * different identities, decided solely by what the callee's own wiring
 * declares for each.
 */
public class SharedSecretAuthentication implements ItaraAuthentication {

    static final String ASSERTION_KEY = "x-example-shared-secret";

    @Override
    public Map<String, String> produceAssertion(ItaraAuthenticationConfig config, ItaraCallTarget target) {
        SharedSecretAuthenticationConfig cfg = (SharedSecretAuthenticationConfig) config;
        Map<String, String> assertion = new HashMap<>();
        assertion.put(ASSERTION_KEY, cfg.getSecret());
        return assertion;
    }

    @Override
    public AuthenticationOutcome authenticate(ItaraAuthenticationConfig config, Map<String, String> headers, ItaraTransportCredential transportCredential) {
        SharedSecretAuthenticationConfig cfg = (SharedSecretAuthenticationConfig) config;
        String presented = headers.get(ASSERTION_KEY);

        if (presented == null || !presented.equals(cfg.getSecret())) {
            return AuthenticationOutcome.rejected(
                    "shared secret did not match this connection's configured secret");
        }

        return AuthenticationOutcome.accepted(
                ItaraIdentity.builder()
                        .subject(cfg.getSubject())
                        .issuer("shared-secret-example")
                        .trustMechanism("shared-secret")
                        .build());
    }
}
