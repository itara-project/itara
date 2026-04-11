package topos.api;

import topos.runtime.ToposRegistry;

/**
 * Implemented by the developer in the component jar.
 *
 * The activator is responsible for constructing the component's internal
 * object graph and returning the root implementation instance.
 *
 * The activator may call registry.get() to pull in dependencies. If the
 * dependency is remote, the registry returns the agent's HTTP proxy. If
 * it is local (direct connection), the registry triggers that component's
 * activator recursively. The developer does not need to know which is which.
 *
 * One activator per component jar. Discovered by the agent via
 * META-INF/topos/activator in the component jar.
 *
 * Example:
 *   public class PricingActivator implements ToposActivator<PricingService> {
 *       public PricingService activate(ToposRegistry registry) {
 *           return new PricingServiceImpl();
 *       }
 *   }
 */
public interface ToposActivator<T> {
    T activate(ToposRegistry registry) throws Exception;
}
