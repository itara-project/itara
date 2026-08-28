package dev.itara.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares properties of a contract interface method.
 *
 * <p>Currently just idempotency, but this is meant to grow — more
 * properties (e.g. whether a method streams, ordering requirements, or
 * whatever else surfaces later) are expected to be added here over time.
 * These are intrinsic to the method — true regardless of topology. Place
 * this on methods in a component's API interface.
 *
 * <p>Not read by the runtime — the artifact's {@code .itara} metadata
 * file is authoritative for every property of a method. This annotation
 * exists for tooling: it is the basis future tooling will use to
 * generate and keep that metadata file's method properties up to date
 * automatically, rather than requiring it to be hand-maintained.
 *
 * <p>Example:
 * <pre>{@code
 * public interface PricingService {
 *     @ContractMethod(idempotent = true)
 *     double calculate(double amount);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ContractMethod {
    boolean idempotent() default false;
}
