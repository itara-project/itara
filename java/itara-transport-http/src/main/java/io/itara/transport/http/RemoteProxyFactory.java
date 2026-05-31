package io.itara.transport.http;

import io.itara.spi.ItaraSerializer;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.implementation.bind.annotation.AllArguments;
import net.bytebuddy.implementation.bind.annotation.Origin;
import net.bytebuddy.implementation.bind.annotation.RuntimeType;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Generates remote proxy classes using ByteBuddy.
 *
 * Unlike java.lang.reflect.Proxy, ByteBuddy can subclass abstract classes,
 * not just interfaces. This means the contract can be declared as an abstract
 * class (the intended model) rather than being forced into an interface.
 *
 * The generated class is a real subclass of the contract abstract class.
 * The JVM treats it as a normal class — the JIT can optimize it, it
 * participates in normal type hierarchies, and there is no reflective
 * dispatch overhead at call time.
 *
 * The delegation target is the HttpCallInterceptor, which holds the
 * connection details and performs the actual HTTP call.
 *
 * Usage:
 *   PricingService proxy = RemoteProxyFactory.createHttpProxy(
 *       PricingService.class, "pricing-service", "localhost", 8081);
 */
public class RemoteProxyFactory {

    private static final Logger log = Logger.getLogger(RemoteProxyFactory.class.getName());

    /**
     * Creates a ByteBuddy-generated subclass of the contract class.
     * All non-final, non-private methods are intercepted and delegated
     * to the HttpCallInterceptor.
     *
     * @param contractClass  The abstract class annotated with @ComponentInterface.
     *                       Can also be an interface — ByteBuddy handles both.
     * @param componentId    The component id, used in the HTTP endpoint path.
     * @param host           The remote host.
     * @param port           The remote port.
     * @param classLoader    The classloader to define the generated class in.
     * @return               A fully initialized proxy instance.
     */
    @SuppressWarnings("unchecked")
    public static <T> T createHttpProxy(Class<T> contractClass,
                                         String componentId,
                                         String host,
                                         int port,
                                         ClassLoader classLoader,
                                         ItaraSerializer serializer) {
        try {
            HttpCallInterceptor interceptor =
                    new HttpCallInterceptor(componentId, host, port, serializer);

            Class<? extends T> proxyClass = (Class<? extends T>) new ByteBuddy()
                    .subclass(contractClass)
                    // Intercept all declared methods except those from Object
                    .method(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class)))
                    .intercept(MethodDelegation.to(interceptor))
                    .make()
                    .load(classLoader, ClassLoadingStrategy.Default.INJECTION)
                    .getLoaded();

            // Instantiate the generated class.
            // ByteBuddy subclasses require a no-arg constructor to be available
            // in the superclass (abstract class). If the abstract class has no
            // no-arg constructor, use ClassLoadingStrategy.Default.CHILD_FIRST
            // and provide constructor arguments via ByteBuddy's SuperMethodCall.
            // For the PoC, we require abstract contract classes to have a
            // no-arg constructor (protected or public).
            T proxy = proxyClass.getDeclaredConstructor().newInstance();

            log.info("[Itara] Created HTTP proxy for: " + componentId
                    + " (" + contractClass.getSimpleName() + ")"
                    + " -> " + host + ":" + port);

            return proxy;

        } catch (Exception e) {
            throw new RuntimeException(
                    "[Itara] Failed to create HTTP proxy for component '"
                    + componentId + "' (contract: " + contractClass.getName() + "): "
                    + e.getMessage(), e);
        }
    }

    /**
     * The interceptor that ByteBuddy delegates all method calls to.
     *
     * Must be public with a public intercept method for ByteBuddy's
     * MethodDelegation to work correctly.
     */
    public static class HttpCallInterceptor {

        private final HttpRemoteProxy delegate;

        public HttpCallInterceptor(String componentId, String host, int port, ItaraSerializer serializer) {
            this.delegate = new HttpRemoteProxy(componentId, host, port, serializer);
        }

        /**
         * Intercepts every method call on the proxy and routes it through
         * the HTTP transport.
         *
         * @RuntimeType tells ByteBuddy the return type is dynamic (Object).
         * @Origin gives us the Method being called.
         * @AllArguments gives us the method arguments.
         */
        @RuntimeType
        public Object intercept(@Origin Method method,
                                @AllArguments Object[] args) throws Throwable {
            return delegate.invoke(null, method, args);
        }
    }
}
