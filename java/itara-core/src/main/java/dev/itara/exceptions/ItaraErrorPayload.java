package dev.itara.exceptions;

import java.io.Serializable;

/**
 * Wire-safe error payload exchanged between Itara proxy and dispatcher.
 *
 * <p>Carries exactly the information needed to reconstruct an ItaraRemoteException
 * on the caller side. Nothing more — no stack traces, no cause chains, no
 * implementation-specific detail that would create a security or coupling concern.
 *
 * <p>This is the type the serializer sees at the error boundary. The serializer
 * has no knowledge of ItaraRemoteException — it serializes and deserializes
 * this DTO as a plain object.
 *
 * <p>Specified as part of the Itara error handling contract. Every language
 * implementation produces and consumes this structure at the error boundary.
 */
public class ItaraErrorPayload implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** The failure category. */
    private ItaraRemoteException.ErrorKind errorKind;
    /** Fully qualified class name of the original exception. */
    private String remoteExceptionClass;
    /** Message from the original exception. */
    private String message;

    /** Required for deserialization. */
    public ItaraErrorPayload() {}

    /**
     * Constructs a fully-populated error payload.
     *
     * @param errorKind            classifies the failure category
     * @param remoteExceptionClass fully qualified class name of the original exception
     * @param message              message from the original exception
     */
    public ItaraErrorPayload(ItaraRemoteException.ErrorKind errorKind,
                             String remoteExceptionClass,
                             String message) {
        this.errorKind = errorKind;
        this.remoteExceptionClass = remoteExceptionClass;
        this.message = message;
    }

    /**
     * Returns the failure category.
     *
     * @return the failure category
     */
    public ItaraRemoteException.ErrorKind getErrorKind() { return errorKind; }
    /**
     * Returns fully qualified class name of the original exception.
     *
     * @return fully qualified class name of the original exception
     */
    public String getRemoteExceptionClass() { return remoteExceptionClass; }
    /**
     * Returns message from the original exception.
     *
     * @return message from the original exception
     */
    public String getMessage() { return message; }

    /**
     * Sets classifies the failure category.
     *
     * @param errorKind classifies the failure category
     */
    public void setErrorKind(ItaraRemoteException.ErrorKind errorKind) { this.errorKind = errorKind; }
    /**
     * Sets fully qualified class name of the original exception.
     *
     * @param remoteExceptionClass fully qualified class name of the original exception
     */
    public void setRemoteExceptionClass(String remoteExceptionClass) { this.remoteExceptionClass = remoteExceptionClass; }
    /**
     * Sets message from the original exception.
     *
     * @param message message from the original exception
     */
    public void setMessage(String message) { this.message = message; }
}
