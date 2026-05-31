package io.itara.exceptions;

import java.io.Serializable;

/**
 * Wire-safe error payload exchanged between Itara proxy and dispatcher.
 *
 * Carries exactly the information needed to reconstruct an ItaraRemoteException
 * on the caller side. Nothing more — no stack traces, no cause chains, no
 * implementation-specific detail that would create a security or coupling concern.
 *
 * This is the type the serializer sees at the error boundary. The serializer
 * has no knowledge of ItaraRemoteException — it serializes and deserializes
 * this DTO as a plain object.
 *
 * Specified as part of the Itara error handling contract. Every language
 * implementation produces and consumes this structure at the error boundary.
 */
public class ItaraErrorPayload implements Serializable {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private ItaraRemoteException.ErrorKind errorKind;
    private String remoteExceptionClass;
    private String message;

    /** Required for deserialization. */
    public ItaraErrorPayload() {}

    public ItaraErrorPayload(ItaraRemoteException.ErrorKind errorKind,
                             String remoteExceptionClass,
                             String message) {
        this.errorKind = errorKind;
        this.remoteExceptionClass = remoteExceptionClass;
        this.message = message;
    }

    public ItaraRemoteException.ErrorKind getErrorKind() { return errorKind; }
    public String getRemoteExceptionClass() { return remoteExceptionClass; }
    public String getMessage() { return message; }

    public void setErrorKind(ItaraRemoteException.ErrorKind errorKind) { this.errorKind = errorKind; }
    public void setRemoteExceptionClass(String remoteExceptionClass) { this.remoteExceptionClass = remoteExceptionClass; }
    public void setMessage(String message) { this.message = message; }
}
