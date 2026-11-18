package io.github.oryxlabs.oryxos.boot.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** Loopback-only external service fixture; never accepts an arbitrary target. */
public final class LocalHttpFixture implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    public LocalHttpFixture(String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
    }
    public URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"); }
    public int calls() { return calls.get(); }
    public void close() { server.stop(0); }
}
