package io.itara.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares behavioral properties of a contract method.
 *
 * These are intrinsic to the method — true regardless of topology.
 * Place this on abstract method declarations in the API jar.
 *
 * Properties here inform the controller's decisions in future versions
 * but are not enforced by the runtime in v1.
 *
 * Example:
 *   @ContractMethod(idempotent = true)
 *   public abstract double calculate(double amount);
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ContractMethod {
    boolean idempotent() default false;
    boolean orderSensitive() default false;
}
