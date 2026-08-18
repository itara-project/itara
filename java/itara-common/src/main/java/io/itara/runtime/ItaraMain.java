package io.itara.runtime;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Built-in main class for components that only receive calls.
 *
 * Components that don't initiate work — pure service components — don't
 * need to write a main method. Point the JVM at this class instead:
 *
 *   java -javaagent:Itara-agent.jar \
 *        -DItara.config=/path/to/wiring.yaml \
 *        -cp Itara-common.jar:... \
 *        Itara.runtime.ItaraMain
 *
 * The agent has already started the HTTP server and populated the registry
 * before this main is called. This method simply keeps the JVM alive until
 * an external signal (SIGTERM, SIGINT) triggers the shutdown hook, which
 * the agent registered to stop the HTTP server gracefully.
 *
 * For components that initiate work (batch jobs, schedulers, CLI tools),
 * write your own main and call ItaraRegistry.instance().get() to retrieve
 * your wired dependencies.
 */
public class ItaraMain {

    private static final Logger log = Logger.getLogger(ItaraMain.class.getName());

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
