package io.itara.transport.http;

import com.sun.net.httpserver.HttpServer;
import io.itara.runtime.ItaraRegistry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;

/**
 * Minimal HTTP server that exposes locally-running components to remote callers.
 *
 * Started by the agent for any component that has inbound HTTP connections
 * in the wiring config (i.e., another JVM's proxy will be calling this one).
 *
 * Endpoint pattern:
 *   POST /itara/{componentId}/{methodName}
 *
 * The component instance is retrieved from the registry lazily on first call,
 * which triggers the activator if not already initialized.
 *
 * Uses the same Java serialization protocol as HttpRemoteProxy.
 * Uses JDK's built-in HttpServer — no external dependencies.
 */
public class ItaraHttpServer {

    private final HttpServer server;
    private final ItaraRegistry registry;

    public ItaraHttpServer(int port, ItaraRegistry registry) throws IOException {
        this.registry = registry;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);

        // Single catch-all handler for all /itara/* requests
        this.server.createContext("/itara/", this::handle);
    }

    public void start() {
        server.start();
        System.out.println("[Itara/HTTP] Server listening on port "
                + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(1);
    }

    private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Parse /itara/{componentId}/{methodName}
        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        // parts: ["", "itara", componentId, methodName]
        if (parts.length < 4) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }
        String componentId = parts[2];
        String methodName  = parts[3];

        System.out.println("[Itara/HTTP] <- " + methodName + " on " + componentId);

        try {
            // Deserialize arguments
            Object[] args;
            try (ObjectInputStream ois =
                         new ObjectInputStream(exchange.getRequestBody())) {
                args = (Object[]) ois.readObject();
            }

            // Look up the component — triggers activation if needed
            Object instance = registry.get(componentId, Object.class);

            // Find the method by name (PoC: first match, no overload support)
            Method method = findMethod(instance.getClass(), methodName);
            if (method == null) {
                throw new NoSuchMethodException(
                        "No method '" + methodName + "' on " + instance.getClass().getName());
            }

            // Invoke
            Object result = method.invoke(instance, args);

            // Serialize and send response
            sendObject(exchange, 200, result);

        } catch (Throwable t) {
            System.err.println("[Itara/HTTP] Error handling " + methodName
                    + " on " + componentId + ": " + t.getMessage());
            sendObject(exchange, 500, t);
        }
    }

    private void sendObject(com.sun.net.httpserver.HttpExchange exchange,
                            int status, Object obj) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(obj);
        }
        byte[] bytes = baos.toByteArray();
        exchange.getResponseHeaders().set("Content-Type",
                "application/x-java-serialized-object");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }
}
