package io.itara.exceptions;

/**
 * Marker interface for checked exceptions that declare support for
 * reconstruction across a topology boundary.
 *
 * When a CHECKED error crosses a remote boundary, the proxy attempts to
 * reconstruct the original exception type before surfacing it to the
 * caller. Reconstruction only occurs if the exception class implements
 * this interface AND an {@link ItaraReconstructibleExceptionFactory} is registered for
 * the component's contract on the caller's classpath.
 *
 * Implementing this interface is a declaration by the exception author
 * that the exception is safe and meaningful to reconstruct on the caller
 * side — that its type and message are sufficient to represent the
 * failure, and that it carries no callee-runtime-specific state.
 *
 * Specified in §6.6.6 of the Itara specification.
 */
public interface ItaraReconstructibleException {
}
