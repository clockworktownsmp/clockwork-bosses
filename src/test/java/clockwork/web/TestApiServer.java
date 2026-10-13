package clockwork.web;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

/** Loopback API harness for transport-independent security/file regressions.
 * Test sources only: no listener or website is included in the companion jar. */
final class TestApiServer implements AutoCloseable {
    static final String ID = "0".repeat(64);
    static final String COOKIE = "clockwork_workshop_" + ID + "=";
    private final HttpServer server;
    private final TemplateApi api;
    private final ExecutorService workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), task -> { var thread = new Thread(task, "clockwork-test-api"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    TestApiServer(String address, int port, TemplateApi api) throws IOException {
        if (!address.equals("127.0.0.1")) throw new IOException("Test API must bind literal loopback");
        this.api = api;
        server = HttpServer.create(new InetSocketAddress(address, port), 16);
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Connection", "close");
                if (exchange.getRequestURI().getRawPath().startsWith("/api/")) api.handle(exchange);
                else exchange.sendResponseHeaders(404, -1);
            }
        });
        server.start();
    }
    @Override public void close() {
        api.close(); server.stop(0); workers.shutdownNow();
        try { workers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
}
