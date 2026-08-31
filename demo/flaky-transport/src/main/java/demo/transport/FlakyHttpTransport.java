package demo.transport;

import dev.itara.transport.http.HttpTransport;
import dev.itara.transport.http.HttpTransportConfig;
import dev.itara.runtime.ItaraCallTarget;
import dev.itara.spi.transport.ItaraTransportConfig;

import java.time.Duration;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Demo-only HTTP transport with N probability that a call fails.
 *
 * N is read from the ITARA_FLAKY_FAIL_RATE environment variable at
 * construction time. Defaults to 0.4 if not set.
 *
 * Used exclusively in the failure-semantics demo scenario to simulate
 * transient transport failures without touching business code.
 * Not suitable for production use.
 *
 * Discovered via META-INF/itara/transport — type id is "flaky-http".
 * After the transport SPI lifecycle rework, failCount will become a
 * wiring config property instead of an environment variable.
 */
public class FlakyHttpTransport extends HttpTransport {

    private static final Logger log = Logger.getLogger(FlakyHttpTransport.class.getName());

    private static final String ENV_FAIL_RATE    = "ITARA_FLAKY_FAIL_RATE";
    private static final double DEFAULT_FAIL_RATE = 0.4;

    private final double failRate;
    private final Random random = new Random();

    public FlakyHttpTransport(HttpTransportConfig config) {
        super(config);
        String env = System.getenv(ENV_FAIL_RATE);
        double rate = DEFAULT_FAIL_RATE;
        if (env != null && !env.isBlank()) {
            try {
                rate = Double.parseDouble(env.trim());
                if (rate < 0.0 || rate > 1.0) {
                    log.warning("[Itara/FlakyHTTP] " + ENV_FAIL_RATE
                            + " must be between 0.0 and 1.0, got '" + env
                            + "' — using default " + DEFAULT_FAIL_RATE);
                    rate = DEFAULT_FAIL_RATE;
                }
            } catch (NumberFormatException e) {
                log.warning("[Itara/FlakyHTTP] Invalid " + ENV_FAIL_RATE
                        + " value '" + env + "' — using default " + DEFAULT_FAIL_RATE);
            }
        }
        this.failRate = rate;
        log.info("[Itara/FlakyHTTP] Initialized — failure rate: "
                + (int)(failRate * 100) + "%");
    }

    @Override
    public byte[] send(ItaraCallTarget target,
                       byte[] payload,
                       Map<String, String> headers,
                       ItaraTransportConfig config,
                       Duration timeout) throws Exception {
        if (random.nextDouble() < failRate) {
            log.info("[Itara/FlakyHTTP] Simulating transient failure for "
                    + target.getMethod() + " on " + target.getComponent()
                    + " (rate=" + (int)(failRate * 100) + "%)");
            throw new RuntimeException("[Itara/FlakyHTTP] Simulated transient failure");
        }
        return super.send(target, payload, headers, config, timeout);
    }
}
