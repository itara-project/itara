package dev.itara.runtime;

import dev.itara.exceptions.ItaraReconstructibleExceptionFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Registry of available {@link ItaraReconstructibleExceptionFactory} instances,
 * keyed by contract ID.
 *
 * <p>Populated by the agent at startup via the ExceptionFactoryLoader, which
 * discovers factories from META-INF/itara/exception-factory descriptors on
 * the classpath. One factory per API artifact is registered.
 *
 * <p>At call time, the proxy looks up the factory by contract ID and delegates
 * to it for reconstruction. If no factory is registered for a contract, the
 * proxy falls back to ItaraRemoteException — no error, no startup failure.
 * Registration is optional; absence means reconstruction is not supported
 * for that contract.
 */
public class ReconstructibleExceptionRegistry {

    private static final Logger log = Logger.getLogger(ReconstructibleExceptionRegistry.class.getName());

    private static final ReconstructibleExceptionRegistry INSTANCE = new ReconstructibleExceptionRegistry();

    private final Map<String, ItaraReconstructibleExceptionFactory> factories = new ConcurrentHashMap<>();

    private ReconstructibleExceptionRegistry() {}

    /** @return the singleton registry instance */
    public static ReconstructibleExceptionRegistry instance() {
        return INSTANCE;
    }

    /**
     * Registers a factory for the contract ID it declares.
     * Called by the agent at startup before any connections are processed.
     * A second registration for the same contract ID replaces the first
     * and logs a warning — duplicate factories in a deployment are
     * likely a configuration mistake.
     */
    public void register(ItaraReconstructibleExceptionFactory factory) {
        String contractId = factory.contractId();
        if (factories.containsKey(contractId)) {
            log.warning("[Itara] duplicate exception factory contract=" + contractId
                    + " replacing=" + factories.get(contractId).getClass().getName()
                    + " with=" + factory.getClass().getName());
        }
        factories.put(contractId, factory);
        log.fine("[Itara] registered exception factory contract=" + contractId
                + " class=" + factory.getClass().getName());
    }

    /**
     * Returns the factory registered for the given contract ID, or empty
     * if no factory has been registered for that contract.
     */
    public Optional<ItaraReconstructibleExceptionFactory> get(String contractId) {
        return Optional.ofNullable(factories.get(contractId));
    }
}
