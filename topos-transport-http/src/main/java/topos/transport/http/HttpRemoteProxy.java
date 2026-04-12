package topos.transport.http;

import java.io.*;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * InvocationHandler for remote component calls over HTTP.
 *
 * Serialization: Java object serialization. Simple and dependency-free.
 * Will be made configurable in a future version.
 *
 * Protocol:
 *   POST http://{host}:{port}/topos/{componentId}/{methodName}
 *   Content-Type: application/x-java-serialized-object
 *   Body: serialized Object[] of method arguments
 *
 *   Response body: serialized return value, or serialized Throwable on error
 *   Response status: 200 on success, 500 on component error
 *
 * The remote side (ToposHttpServer) mirrors this protocol exactly.
 */
public class HttpRemoteProxy implements InvocationHandler {

    private final String componentId;
    private final String host;
    private final int port;

    public HttpRemoteProxy(String componentId, String host, int port) {
        this.componentId = componentId;
        this.host = host;
        this.port = port;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Pass through Object methods without going remote
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(this, args);
        }

        String url = String.format("http://%s:%d/topos/%s/%s",
                host, port, componentId, method.getName());

        System.out.println("[Topos/HTTP] -> " + method.getName()
                + " on " + componentId + " at " + host + ":" + port);

        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestProperty("Content-Type",
                "application/x-java-serialized-object");

        // Serialize and send arguments
        Object[] safeArgs = (args == null) ? new Object[0] : args;
        try (ObjectOutputStream oos =
                     new ObjectOutputStream(connection.getOutputStream())) {
            oos.writeObject(safeArgs);
            oos.flush();
        }

        int status = connection.getResponseCode();

        // Deserialize response
        InputStream responseStream = (status == 200)
                ? connection.getInputStream()
                : connection.getErrorStream();

        try (ObjectInputStream ois = new ObjectInputStream(responseStream)) {
            Object response = ois.readObject();
            if (status == 500 && response instanceof Throwable t) {
                throw t;
            }
            return response;
        }
    }
}
