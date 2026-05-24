package io.itara.runtime;

/**
 * Minimal Spring integration helper for Itara.
 *
 * <p>Purpose:
 * Provides explicit access to Itara-managed component proxies so application
 * developers can wire them into their own Spring configuration.
 *
 * <p>This is intentionally simple and explicit:
 * <ul>
 *   <li>No Spring auto-configuration</li>
 *   <li>No automatic bean injection magic</li>
 *   <li>No dependency on Spring inside itara-common or itara-agent</li>
 * </ul>
 *
 * <p>Typical usage:
 *
 * <pre>{@code
 * @Configuration
 * public class AppConfig {
 *
 *     @Bean
 *     public UserClient userClient() {
 *         return ItaraSpring.get(UserClient.class);
 *     }
 * }
 * }</pre>
 *
 * <p>The returned instance is the Itara-wired proxy that was pre-registered
 * by the agent during startup.
 */
public final class ItaraSpring {

    private ItaraSpring() {
    }

    /**
     * Retrieve a component by explicit component id and contract type.
     *
     * <p>Usually used internally or in advanced wiring scenarios where
     * multiple implementations may exist.
     *
     * @param id component id from the topology configuration
     * @param type expected contract/interface type
     * @return the Itara-managed component instance or proxy
     */
    public static <T> T get(String id, Class<T> type) {
        ItaraRegistry registry = ItaraRegistry.instance();
        return registry.get(id, type);
    }

    /**
     * Retrieve a component by contract/interface type.
     *
     * <p>Designed for Spring {@code @Bean} methods where the developer
     * explicitly chooses which Itara component to expose as a Spring bean.
     *
     * <p>The registry must contain exactly one component matching the type.
     * If multiple components match, an exception is thrown to avoid ambiguous
     * wiring.
     *
     * @param type contract/interface type
     * @return the pre-registered Itara proxy or activated component
     */
    public static <T> T get(Class<T> type) {
        ItaraRegistry registry = ItaraRegistry.instance();
        return registry.get(type);
    }
}