package io.itara.runtime;

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

    public static void main(String[] args) throws InterruptedException {
        System.out.println("[Itara] Component ready. Waiting for shutdown signal.");
        Thread.currentThread().join();
    }
}
