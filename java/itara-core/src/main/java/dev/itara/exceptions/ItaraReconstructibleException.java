package dev.itara.exceptions;

/**
 * Marker interface for checked exceptions that declare support for
 * reconstruction across a topology boundary.
 *
 * <p>Implementing classes MUST also extend {@code Throwable} (in practice, a
 * checked {@code Exception}). This interface intentionally does not
 * extend {@code Throwable} itself, so it can be implemented alongside any
 * exception hierarchy without running into Java's single-class-inheritance
 * limits. A class that implements this interface without also being a
 * {@code Throwable} is invalid and will not work: it cannot actually be
 * thrown, so {@link ItaraReconstructibleExceptionFactory#reconstruct} has
 * nothing valid to hand back, and the proxy checks for this and falls
 * back to {@link ItaraRemoteException}.
 *
 * <p>When a CHECKED error crosses a remote boundary, the proxy attempts to
 * reconstruct the original exception type before surfacing it to the
 * caller. Reconstruction only occurs if the exception class implements
 * this interface AND an {@link ItaraReconstructibleExceptionFactory} is registered for
 * the component's contract on the caller's classpath.
 *
 * <p>Implementing this interface is a declaration by the exception author
 * that the exception is safe and meaningful to reconstruct on the caller
 * side — that its type and message are sufficient to represent the
 * failure, and that it carries no callee-runtime-specific state.
 *
 * <p>Specified in §6.6.6 of the Itara specification.
 */
public interface ItaraReconstructibleException {
}
