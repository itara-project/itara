package io.itara.runtime;

import io.itara.api.ItaraActivator;

/**
 * Factory called by the registry when a component needs to be activated.
 *
 * Registered by the agent at startup. Allows the agent to wrap the
 * activated instance in an observability decorator before it is stored
 * in the registry.
 *
 * If no factory is registered, the registry falls back to calling
 * activate() directly on the activator — useful for testing without
 * the agent.
 */
public interface ComponentFactory {

    /**
     * Creates and returns a component instance for the given activator.
     * The returned instance is what the registry will store and serve
     * to callers — typically the real instance wrapped in a decorator.
     *
     * @param activatorClass  the activator class to instantiate
     * @param componentId     the component identifier from the wiring config
     * @param contractClass   the @ComponentInterface interface
     * @return                the instance to store in the registry
     */
    Object create(Class<? extends ItaraActivator<?>> activatorClass,
                  String componentId,
                  Class<?> contractClass);
}
