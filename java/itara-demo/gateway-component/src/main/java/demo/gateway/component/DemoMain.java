package demo.gateway.component;

import demo.gateway.api.GatewayService;
import io.itara.runtime.ComponentLookup;

import java.util.logging.Logger;

/**
 * Demo entry point.
 *
 * The agent has already wired everything before this runs.
 * All we do is pull the gateway from the registry and make a few calls.
 *
 * This same main works for both topologies:
 *   - Direct: calculator runs in this JVM, called as a method
 *   - HTTP:   calculator runs in a separate JVM, called over the network
 *
 * The code here does not change. The wiring config changes.
 *
 * Run with:
 *   java -javaagent:itara-agent.jar
 *        -Ditara.config=wiring-direct.yaml
 *        -cp itara-common.jar;calculator-api.jar;calculator-component.jar;gateway-api.jar;gateway-component.jar
 *        demo.gateway.component.DemoMain
 */
public class DemoMain {

    private static final Logger log = Logger.getLogger(DemoMain.class.getName());

    public static void main(String[] args) throws Exception {
        log.info("=".repeat(50));
        log.info("Itara Demo starting...");
        log.info("=".repeat(50));

        // Small pause to let the agent's HTTP server start if needed
        Thread.sleep(500);

        GatewayService gateway = ComponentLookup.getSelf("gateway", GatewayService.class, true);

        log.info("");
        log.info("--- Making calls ---");
        log.info("");

        log.info(gateway.calculate(3, 4));
        log.info("");
        log.info(gateway.calculate(10, 25));
        log.info("");
        log.info(gateway.calculate(100, 200));

        log.info("");
        log.info("=".repeat(50));
        log.info("Done.");
        log.info("=".repeat(50));
    }
}
