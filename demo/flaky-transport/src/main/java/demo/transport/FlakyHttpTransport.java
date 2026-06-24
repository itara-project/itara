package demo.transport;

import io.itara.runtime.DispatchHandler;
import io.itara.transport.http.HttpTransport;

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

    public FlakyHttpTransport() {
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
    public String type() {
        return "flaky-http";
    }

    @Override
    public byte[] send(String componentId,
                       String methodName,
                       byte[] payload,
                       Map<String, String> headers,
                       Map<String, String> properties,
                       Duration timeout) throws Exception {
        if (random.nextDouble() < failRate) {
            log.info("[Itara/FlakyHTTP] Simulating transient failure for "
                    + methodName + " on " + componentId
                    + " (rate=" + (int)(failRate * 100) + "%)");
            throw new RuntimeException("[Itara/FlakyHTTP] Simulated transient failure");
        }
        return super.send(componentId, methodName, payload, headers, properties, timeout);
    }

    @Override
    public void startListener(String componentId,
                              Map<String, String> properties,
                              DispatchHandler dispatcher) {
        super.startListener(componentId, properties, dispatcher);
    }

    @Override
    public void stopListener() {
        super.stopListener();
    }
}
