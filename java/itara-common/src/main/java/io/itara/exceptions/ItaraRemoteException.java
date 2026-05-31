package io.itara.exceptions;

/**
 * Thrown on the caller side when a remote component invocation fails.
 *
 * Itara transports distinguish three failure categories, reflected in the
 * HTTP status code and the ErrorKind of this exception:
 *
 *   CHECKED   — the remote component threw a checked exception. This is a
 *               declared contract condition the caller is expected to handle.
 *               The component executed, validated its input, and rejected it
 *               through its normal error path. Equivalent to HTTP 422.
 *
 *   RUNTIME   — the remote component threw an unexpected runtime exception
 *               or error. The component executed but failed in an uncontrolled
 *               way. Equivalent to HTTP 500.
 *
 *   TRANSPORT — Itara itself failed. The component may or may not have been
 *               invoked. Registry lookup, activation, serialization, or the
 *               network layer failed. The caller should treat this as
 *               infrastructure failure, not a business error. Equivalent
 *               to HTTP 503 or a connection-level failure.
 *
 * Full exception reconstruction — reinstating the original exception type —
 * is intentionally out of scope. Reconstructing the original type would
 * require the exception class to be present on the caller's classpath,
 * which cannot be guaranteed in a topology where components are developed
 * and deployed independently.
 *
 * Callers that need to handle specific remote failure conditions should
 * inspect getErrorKind(), getRemoteExceptionClass(), and getMessage().
 */
public class ItaraRemoteException extends RuntimeException {

    /**
     * Classifies the failure that caused this exception.
     *
     * Callers can use this to decide whether to retry, surface the error
     * to the user, or treat the failure as an infrastructure problem.
     */
    public enum ErrorKind {

        /**
         * The remote component threw a checked exception.
         * This is a declared contract condition — the component executed
         * and rejected the request through its normal error path.
         * The caller should handle this as a business-level failure.
         */
        CHECKED,

        /**
         * The remote component threw an unexpected runtime exception or error.
         * The component executed but failed in an uncontrolled way.
         * The caller may retry, but the root cause is in the component.
         */
        RUNTIME,

        /**
         * Itara infrastructure failed. The component may or may not have
         * been invoked. Serialization, registry lookup, activation, or the
         * transport layer itself failed. The caller should treat this as
         * a transient infrastructure failure and may retry with backoff.
         */
        TRANSPORT
    }

    private final ErrorKind errorKind;
    private final String remoteExceptionClass;

    /**
     * Constructs an ItaraRemoteException from a structured error payload.
     *
     * @param errorKind            classifies the failure category
     * @param remoteExceptionClass fully qualified class name of the original exception
     * @param remoteMessage        message from the original exception
     */
    public ItaraRemoteException(ErrorKind errorKind,
                                String remoteExceptionClass,
                                String remoteMessage) {
        super(remoteMessage);
        this.errorKind = errorKind;
        this.remoteExceptionClass = remoteExceptionClass;
    }

    /**
     * Constructs an ItaraRemoteException preserving a transport-level cause.
     *
     * Used when the failure originated in the transport layer itself rather
     * than in the remote component, and the causing exception is available
     * locally.
     *
     * @param errorKind            classifies the failure category
     * @param remoteExceptionClass fully qualified class name of the original exception,
     *                             or an Itara infrastructure class name for TRANSPORT failures
     * @param remoteMessage        message describing the failure
     * @param cause                the local transport-level exception that triggered this failure
     */
    public ItaraRemoteException(ErrorKind errorKind,
                                String remoteExceptionClass,
                                String remoteMessage,
                                Throwable cause) {
        super(remoteMessage, cause);
        this.errorKind = errorKind;
        this.remoteExceptionClass = remoteExceptionClass;
    }

    /**
     * Returns the failure category.
     *
     * Use this to decide how to handle the failure:
     *   CHECKED   — handle as a business error, do not retry blindly
     *   RUNTIME   — log and potentially retry, root cause is in the component
     *   TRANSPORT — treat as transient infrastructure failure, retry with backoff
     *
     * @return the error kind, never null
     */
    public ErrorKind getErrorKind() {
        return errorKind;
    }

    /**
     * Returns the fully qualified class name of the original exception.
     *
     * For TRANSPORT failures originating locally, this will be an Itara
     * infrastructure class name rather than a remote exception class.
     *
     * @return the remote exception class name, never null
     */
    public String getRemoteExceptionClass() {
        return remoteExceptionClass;
    }

    @Override
    public String toString() {
        return "ItaraRemoteException[" + errorKind + ", " + remoteExceptionClass + "]: " + getMessage();
    }
}
