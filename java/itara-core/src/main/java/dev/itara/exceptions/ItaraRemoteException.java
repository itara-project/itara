package dev.itara.exceptions;

/**
 * Thrown on the caller side when a remote component invocation fails and
 * no more specific reconstruction applies.
 *
 * <p>Itara distinguishes four failure categories, reflected in the ErrorKind
 * of this exception:
 *
 * <ul>
 * <li><b>CHECKED</b> — the remote component threw a checked exception. This
 * is a declared contract condition the caller is expected to handle. The
 * component executed, validated its input, and rejected it through its
 * normal error path.</li>
 * <li><b>RUNTIME</b> — the remote component threw an unexpected runtime
 * exception or error. The component executed but failed in an uncontrolled
 * way.</li>
 * <li><b>TRANSPORT</b> — Itara itself failed. The component may or may not
 * have been invoked. Registry lookup, activation, serialization, or the
 * network layer failed. The caller should treat this as infrastructure
 * failure, not a business error.</li>
 * <li><b>PERMISSION</b> — the caller was not permitted to make this call:
 * authentication failed or authorization denied it.</li>
 * </ul>
 *
 * <p>For CHECKED errors only, the proxy may reconstruct the original
 * exception's type — so the caller can catch the exact declared exception
 * its own contract dependency throws, rather than needing to know
 * anything about Itara — if that exception class implements {@link
 * ItaraReconstructibleException} and a matching {@link
 * ItaraReconstructibleExceptionFactory} is registered on the caller's
 * classpath. Even then, this is not a full reconstruction: stack trace
 * and cause chain are deliberately dropped for security reasons, so only
 * the type and message cross the boundary. If reconstruction isn't
 * available or fails, a CHECKED error falls back to this class, exactly
 * like RUNTIME, TRANSPORT, and PERMISSION always do — none of those three
 * are declared on any contract, so there is no specific type for the
 * caller to want to catch in the first place.
 *
 * <p>Callers that need to handle specific remote failure conditions should
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

    /**
     * Constructs a placeholder exception carrying an undecoded, serialized
     * error response.
     *
     * <p>For {@link dev.itara.spi.transport.ItaraTransport} implementations:
     * when a call returns a non-success status, throw this with the raw
     * response body bytes, without attempting to decode them yourself. The
     * proxy unwraps {@link #getSerializedPayload()} through the
     * connection's own serializer to recover the real {@link ErrorKind}
     * and rethrows the result of {@link #from} in its place — a transport
     * only needs to know that a call failed and hand back the bytes that
     * came with it, never the wire format of the error payload itself.
     *
     * <p>Always classified as {@code TRANSPORT} with a generic
     * remoteExceptionClass until the proxy decodes it — both are
     * placeholders here, replaced once decoding succeeds.
     *
     * @param responseBytes the raw, not-yet-decoded response body
     */
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
     * <p>Used when the failure originated in the transport layer itself rather
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
     * <p>Use this to decide how to handle the failure:
     * <ul>
     * <li><b>CHECKED</b> — handle as a business error, do not retry blindly</li>
     * <li><b>RUNTIME</b> — log and potentially retry, root cause is in the component</li>
     * <li><b>TRANSPORT</b> — treat as transient infrastructure failure, retry with backoff</li>
     * <li><b>PERMISSION</b> — the caller was not permitted to make this call;
     * treat as an access-control failure, not an infrastructure problem</li>
     * </ul>
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
