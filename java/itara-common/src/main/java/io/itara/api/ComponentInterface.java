package io.itara.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as an Itara component contract.
 *
 * Place this on the interface in the API jar.
 * The id must be unique across the topology and must match
 * the component id used in the wiring config.
 *
 * Example:
 *   @ComponentInterface(id = "pricing-service")
 *   public interface PricingService { ... }
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentInterface {
    String id();
}
