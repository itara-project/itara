package io.itara.exceptions;

import java.util.Optional;

/**
 * Reconstructs checked exceptions from a remote error payload on the
 * caller side.
 *
 * One factory per API artifact. The factory is discovered at agent startup
 * from a descriptor at META-INF/itara/exception-factory in the API artifact
 * jar, registered against the contract ID returned by {@link #contractId()},
 * and called by the proxy whenever a CHECKED error arrives for that component.
 *
 * The factory receives the error type identifier (fully qualified class name)
 * and message from the wire payload. It returns the reconstructed exception
 * if it handles that type, or an empty Optional to signal that this error
 * type should fall back to {@link ItaraRemoteException}.
 *
 * Implementations MUST:
 * - Return a present Optional only for types whose class implements
 *   {@link ItaraReconstructibleException}
 * - Never throw — any internal failure must be caught and represented
 *   as an empty Optional, letting the proxy fall back safely
 * - Be stateless and thread-safe — one instance is shared across all
 *   calls for the lifetime of the agent
 *
 * Example:
 *   public class CalculatorExceptionFactory implements ItaraReconstructibleExceptionFactory {
 *
 *       public String contractId() { return "calculator"; }
 *
 *       public Optional<ItaraReconstructibleException> reconstruct(
 *               String errorTypeId, String message) {
 *           if ("com.example.DivisionByZeroException".equals(errorTypeId)) {
 *               return Optional.of(new DivisionByZeroException(message));
 *           }
 *           return Optional.empty();
 *       }
 *   }
 *
 * Specified in §6.6.6 of the Itara specification.
 */
public interface ItaraReconstructibleExceptionFactory {

    /**
     * The API contract ID this factory handles.
     * Must match the component id declared in the API artifact's .itara
     * metadata file. Used by the agent to register and look up the factory.
     *
     * @return the contract ID, never null
     */
    String contractId();

    /**
     * Attempts to reconstruct a checked exception from the wire payload.
     *
     * @param errorTypeId  fully qualified class name of the original exception
     * @param message      message from the original exception
     * @return             the reconstructed exception, or empty to fall back
     *                     to {@link ItaraRemoteException}
     */
    Optional<ItaraReconstructibleException> reconstruct(String errorTypeId, String message);
}
