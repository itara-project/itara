package dev.itara.runtime;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in main class for components that only receive calls.
 *
 * <p>Components that don't initiate work — pure service components — don't
 * need to write a main method. Point the JVM at this class instead:
 *
 * <pre>{@code
 * java -javaagent:itara-agent.jar \
 *      -Ditara.config=/path/to/wiring.yaml \
 *      -Ditara.nodes=node1,node2 \
 *      -cp itara-core.jar:... \
 *      dev.itara.runtime.ItaraMain
 * }</pre>
 *
 * <p>The agent has already started every registered transport and populated
 * the registry before this main is called. This method simply keeps the JVM
 * alive until an external signal (SIGTERM, SIGINT) triggers the shutdown
 * hook, which the agent registered to stop every registered transport
 * gracefully.
 *
 * <p>For components that initiate work (batch jobs, schedulers, CLI tools),
 * write your own main and use {@link ComponentLookup} to retrieve your
 * wired dependencies — which specific method applies depends on the kind
 * of application; see its own javadoc.
 */
public class ItaraMain {

    private static final Logger log = Logger.getLogger(ItaraMain.class.getName());

    /** Not instantiated — used only via {@link #main}. */
    public ItaraMain() {}

    /**
     * Keeps the JVM alive to receive calls, until an external signal
     * (SIGTERM, SIGINT) triggers the shutdown hook the agent registered.
     * See this class's own javadoc for the full picture.
     *
     * @param args unused
     * @throws InterruptedException if interrupted while waiting
     */
    public static void main(String[] args) throws InterruptedException {
        ComponentLookup.disable();

        // TODO(good-first-issue): replacement for the old activateAllLocal()
        // eager-activation call below. Maintain a list of every registered
        // proxy and dispatcher, and eagerly trigger/cache each one's
        // delegate here (where caching makes sense — see ItaraRegistry),
        // so an activation failure surfaces at boot again instead of only
        // on the first real call that happens to reach it.
        //
        try {
            ItaraRegistry.instance().activateAllLocal();
        } catch (Exception e) {
            log.log(Level.SEVERE,
                    "[Itara] FATAL: one or more local components failed to activate. "
                            + "Refusing to start.", e);
            System.exit(1);
        }

        log.info("[Itara] component ready");
        Thread.currentThread().join();
    }
}
