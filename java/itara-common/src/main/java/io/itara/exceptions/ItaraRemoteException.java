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
        TRANSPORT,

        /**
         * Authentication rejected the call, or authorization denied it
         * (spec §15.6, §16.5; ADR 0026). Both share this single kind — the
         * caller does not need to distinguish "who are you" failures from
         * "you're not allowed to do that" failures, and neither gets a
         * dedicated exception type.
         */
        PERMISSION
    }

    private final ErrorKind errorKind;
    private final String remoteExceptionClass;

    /**
     * Pre-serialized error payload for transport-level error propagation.
     * Set by the dispatcher via withSerializedPayload() before throwing,
     * so the transport server can write the bytes back to the caller
     * without touching the serializer. Null for locally-originated failures.
     */
    private byte[] serializedPayload;

    public ItaraRemoteException(byte[] responseBytes) {
        this.errorKind = ErrorKind.TRANSPORT;
        this.remoteExceptionClass = RuntimeException.class.getSimpleName();
        this.serializedPayload = responseBytes;
    }

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

    // ── Payload conversion ────────────────────────────────────────────────

    /**
     * Produces a wire-safe ItaraErrorPayload from this exception.
     * Called by the dispatcher before serializing the error — the payload
     * is what crosses the wire, not the exception itself.
     */
    public ItaraErrorPayload toPayload() {
        return new ItaraErrorPayload(errorKind, remoteExceptionClass, getMessage());
    }

    /**
     * Reconstructs an ItaraRemoteException from a deserialized payload.
     * Called by the proxy after deserializing the error response.
     */
    public static ItaraRemoteException from(ItaraErrorPayload payload) {
        return new ItaraRemoteException(payload.getErrorKind(),
                payload.getRemoteExceptionClass(),
                payload.getMessage());
    }

    // ── Getters ───────────────────────────────────────────────────────────

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

    /**
     * Returns the pre-serialized error payload, or null if not set.
     * The transport server writes these bytes back to the caller as-is.
     */
    public byte[] getSerializedPayload() {
        return serializedPayload;
    }

    /**
     * Attaches a pre-serialized error payload to this exception.
     * Called by the dispatcher after serializing the error, before throwing.
     * Returns this for fluent use: throw ex.withSerializedPayload(bytes).
     */
    public ItaraRemoteException withSerializedPayload(byte[] payload) {
        this.serializedPayload = payload;
        return this;
    }

    @Override
    public String toString() {
        return "ItaraRemoteException[" + errorKind + ", " + remoteExceptionClass + "]: " + getMessage();
    }
}
