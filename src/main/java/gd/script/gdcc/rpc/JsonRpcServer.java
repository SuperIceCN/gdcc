package gd.script.gdcc.rpc;

import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/// Lifecycle owner for the HTTP transport: a JDK `HttpServer` with a virtual-thread executor that
/// is created and closed together with the server. The adapter holds no cross-request mutable
/// state beyond the shared `API` instance inside the dispatcher — per-module serialization stays
/// with the API module gate.
public final class JsonRpcServer implements AutoCloseable {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 6099;
    /// 16 MiB request-body ceiling; enough for large script uploads, small enough to bound memory.
    public static final int DEFAULT_MAX_REQUEST_BYTES = 16 * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonRpcServer.class);
    static final String RPC_CONTEXT = "/rpc";

    private final @NotNull HttpServer server;
    private final @NotNull ExecutorService executor;
    private final @NotNull AtomicBoolean closed = new AtomicBoolean();

    private JsonRpcServer(@NotNull HttpServer server, @NotNull ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /// Binds and starts the server. Port `0` picks an ephemeral port (read it back via `port()`),
    /// which is what tests use; production defaults to `DEFAULT_PORT`.
    public static @NotNull JsonRpcServer start(
            @NotNull JsonRpcDispatcher dispatcher,
            @NotNull String host,
            int port,
            int maxRequestBytes
    ) throws IOException {
        Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        var bindAddress = new InetSocketAddress(host, port);
        if (bindAddress.isUnresolved()) {
            throw new IOException("Cannot resolve RPC bind host: " + host);
        }
        if (!bindAddress.getAddress().isLoopbackAddress()) {
            // v1 ships without authentication, so exposing the service beyond loopback is an
            // explicit operator decision that must be visible in the log.
            LOGGER.warn(
                    "Binding the gdcc JSON-RPC service to non-loopback address {} — "
                            + "this build has no authentication; prefer {}.",
                    host,
                    DEFAULT_HOST
            );
        }
        var httpServer = HttpServer.create(bindAddress, 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            httpServer.setExecutor(executor);
            httpServer.createContext(RPC_CONTEXT, new JsonRpcHttpHandler(dispatcher, maxRequestBytes));
            httpServer.start();
        } catch (RuntimeException exception) {
            executor.close();
            httpServer.stop(0);
            throw exception;
        }
        return new JsonRpcServer(httpServer, executor);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public @NotNull String host() {
        return server.getAddress().getHostString();
    }

    @Override
    public void close() {
        // Idempotent: both the `gdcc serve` shutdown hook and its finally-block (and test
        // try-with-resources) may close the same instance.
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        server.stop(0);
        executor.close();
    }
}
