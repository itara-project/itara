package dev.itara.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an Itara component contract.
 *
 * <p>Place this on the interface in the API jar. Discovered by the agent
 * via {@code META-INF/itara/contract} in that jar.
 *
 * <p>Example:
 * <pre>{@code
 * @ComponentInterface(id = "pricing-service")
 * public interface PricingService {
 *     double calculate(double amount);
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentInterface {

    /**
     * This component's id. Must be unique across the topology and match
     * the component id used in the wiring config.
     */
    String id();
}
