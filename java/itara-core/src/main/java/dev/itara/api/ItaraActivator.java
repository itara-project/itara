package dev.itara.api;

/**
 * Implemented by the developer in the component jar.
 *
 * <p>The activator is responsible for constructing the component's internal
 * object graph and returning the root implementation instance.
 *
 * <p>The activator may call {@link dev.itara.runtime.ComponentLookup#get}
 * to obtain proxies for its dependencies.
 *
 * <p>One activator per component jar. Discovered by the agent via
 * {@code META-INF/itara/activator} in the component jar.
 *
 * <p>Example:
 * <pre>{@code
 * public class PricingActivator implements ItaraActivator {
 *     public Object activate() {
 *         InventoryService inventory =
 *                 ComponentLookup.get("inventory", InventoryService.class);
 *         return new PricingServiceImpl(inventory);
 *     }
 * }
 * }</pre>
 */
public interface ItaraActivator {

    /**
     * Constructs and returns this component's root implementation instance.
     *
     * <p>Called at most once per component per JVM, the first time this
     * component is actually needed — which may be eager, at boot (see
     * {@link dev.itara.runtime.ItaraMain}), or lazy, on first real use.
     * Wiring is always fully processed by the time this runs. This is
     * <em>not</em> a guarantee that the host application's own startup has
     * finished — a lazy activation can happen mid-startup, e.g. if the
     * host application's own wiring needs this component to construct one
     * of its beans.
     *
     * @return the activated instance; must not be {@code null}
     * @throws Exception if construction fails — the registry wraps this
     *         in a {@code RuntimeException} before propagating it
     */
    Object activate() throws Exception;
}
