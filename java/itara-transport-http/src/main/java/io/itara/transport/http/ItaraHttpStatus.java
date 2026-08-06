package io.itara.transport.http;

import io.itara.exceptions.ItaraRemoteException;

/**
 * HTTP status codes used by the Itara HTTP transport.
 *
 * Package-private — internal contract between HttpTransport and ItaraHttpServer.
 * Not part of the public transport SPI.
 *
 * Itara error kind to HTTP status mapping:
 *   CHECKED   → 422 Unprocessable Entity  — component rejected the request (contract condition)
 *   RUNTIME   → 500 Internal Server Error — component failed unexpectedly
 *   TRANSPORT → 503 Service Unavailable   — Itara infrastructure failed (registry, serialization, etc.)
 */
final class ItaraHttpStatus {

    static final int OK                = 200;
    static final int BAD_REQUEST       = 400;
    static final int METHOD_NOT_FOUND  = 405;
    static final int CHECKED_ERROR     = 422;
    static final int RUNTIME_ERROR     = 500;
    static final int TRANSPORT_ERROR   = 503;
    static final int PERMISSION_ERROR  = 403;

    /**
     * Maps an Itara ErrorKind to the corresponding HTTP status code.
     * Single source of truth for this mapping across HttpTransport and ItaraHttpServer.
     */
    static int forErrorKind(ItaraRemoteException.ErrorKind kind) {
        return switch (kind) {
            case CHECKED   -> CHECKED_ERROR;
            case RUNTIME   -> RUNTIME_ERROR;
            case TRANSPORT -> TRANSPORT_ERROR;
            case PERMISSION -> PERMISSION_ERROR;
        };
    }

    private ItaraHttpStatus() {}
}
