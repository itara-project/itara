package demo.gateway.springcomponent;

import demo.gateway.api.GatewayService;
import io.itara.runtime.ItaraSpring;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.logging.Logger;

/**
 * Demo entry point.
 *
 * The agent has already wired everything before this runs.
 * All we do is obtain gateway from ItaraSpring and make a few calls.
 *
 * This same main works for both topologies:
 *   - Direct: calculator runs in this JVM, called as a method
 *   - HTTP:   calculator runs in a separate JVM, called over the network
 *
 * The code here does not change. The wiring config changes.
 *
 * Run with:
 *   java  -Ditara.lib.dir=itara-libs \
 *         -Ditara.config=itara-demo/wiring-direct-spring.yaml \
 *         -Ditara.nodes=calculatorNode,gatewayNodeSpring \
 *         -javaagent:itara-agent/target/itara-agent-1.0-SNAPSHOT.jar \
 *         -cp "itara-demo/calculator-component/target/calculator-component-1.0-SNAPSHOT.jar;itara-demo/gateway-springcomponent/target/lib/*;itara-demo/gateway-springcomponent/target/gateway-springcomponent-1.0-SNAPSHOT.jar" \
 *         demo.gateway.springcomponent.DemoMain
 */
@SpringBootApplication
public class DemoMain {

    private static final Logger log = Logger.getLogger(DemoMain.class.getName());


    public static void main(String[] args) throws Exception {
        SpringApplication.run(DemoMain.class, args);
    }

    @Bean
    public CommandLineRunner runner(final GatewayService gateway) {
        return args -> {
            log.info("=".repeat(50));
            log.info("Itara Demo starting...");
            log.info("=".repeat(50));

            // Small pause to let the agent's HTTP server start if needed
            Thread.sleep(500);

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
        };
    }

    @Bean
    public GatewayService gateway() {
        log.info("Making bean from: " + GatewayService.class.getName());
        return ItaraSpring.get(GatewayService.class);
    }
}
