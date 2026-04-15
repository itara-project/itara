package demo.gateway.component;

import demo.gateway.api.GatewayService;
import io.itara.runtime.ItaraRegistry;

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

    public static void main(String[] args) throws Exception {
        System.out.println("=".repeat(50));
        System.out.println("Itara Demo starting...");
        System.out.println("=".repeat(50));

        // Small pause to let the agent's HTTP server start if needed
        Thread.sleep(500);

        ItaraRegistry registry = ItaraRegistry.instance();
        GatewayService gateway = registry.get("gateway", GatewayService.class);

        System.out.println();
        System.out.println("--- Making calls ---");
        System.out.println();

        System.out.println(gateway.calculate(3, 4));
        System.out.println();
        System.out.println(gateway.calculate(10, 25));
        System.out.println();
        System.out.println(gateway.calculate(100, 200));

        System.out.println();
        System.out.println("=".repeat(50));
        System.out.println("Done.");
        System.out.println("=".repeat(50));
    }
}
