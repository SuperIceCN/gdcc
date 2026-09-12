package gd.script.gdcc.rpc;

import gd.script.gdcc.api.API;
import gd.script.gdcc.logger.GdccLogger;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/// `gdcc serve` entry point: starts the JSON-RPC service and blocks until SIGINT, then stops the
/// server gracefully. Owns the whole service lifecycle (API instance, dispatcher, HTTP server);
/// the CLI compile flow stays untouched in `gd.script.gdcc.cli`.
@Command(
        name = "serve",
        mixinStandardHelpOptions = true,
        description = "Start the gdcc JSON-RPC service (default " + JsonRpcServer.DEFAULT_HOST + ":"
                + JsonRpcServer.DEFAULT_PORT + ")."
)
public final class RpcServeCommand implements Callable<Integer> {
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcServeCommand.class);

    @Spec
    private CommandSpec spec;

    // Field initializers carry the defaults so the same constants are never duplicated in
    // annotation strings. Fields stay package-visible for parse-level tests, matching the CLI
    // command's test convention.
    @Option(names = "--host", description = "Bind host (default: ${DEFAULT-VALUE}). Non-loopback "
            + "binds log a warning because v1 has no authentication.")
    @NotNull String host = JsonRpcServer.DEFAULT_HOST;

    @Option(names = "--port", description = "Bind port (default: ${DEFAULT-VALUE}; 0 picks an "
            + "ephemeral port).")
    int port = JsonRpcServer.DEFAULT_PORT;

    @Option(names = "--max-request-bytes", description = "Request body size limit in bytes "
            + "(default: ${DEFAULT-VALUE}).")
    int maxRequestBytes = JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES;

    public static int execute(@NotNull String[] args) {
        var previousPlainOutput = GdccLogger.isPlainOutput();
        GdccLogger.setPlainOutput(true);
        try {
            return new RpcServeCommand().commandLine().execute(args);
        } finally {
            GdccLogger.setPlainOutput(previousPlainOutput);
        }
    }

    public @NotNull CommandLine commandLine() {
        return new CommandLine(this);
    }

    @Override
    public @NotNull Integer call() throws Exception {
        validateOptions();
        // try-with-resources covers startup failures (e.g. bind errors); the shutdown hook covers
        // SIGINT; all closes are idempotent so paths may overlap.
        try (var api = new API()) {
            try (var server = JsonRpcServer.start(new JsonRpcDispatcher(api), host, port, maxRequestBytes)) {
                // The JVM waits for shutdown hooks before halting, so closing inside the hook is
                // what makes the SIGINT stop graceful: first stop accepting requests, then let
                // the API cancel unfinished compile tasks and reap their runner threads.
                Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdown(server, api), "gdcc-rpc-shutdown"));
                LOGGER.info("gdcc JSON-RPC service listening on {}:{}", server.host(), server.port());
                new CountDownLatch(1).await();
                return 0;
            }
        }
    }

    private static void shutdown(@NotNull JsonRpcServer server, @NotNull API api) {
        server.close();
        api.close();
    }

    private void validateOptions() {
        if (host.isBlank()) {
            throw new ParameterException(spec.commandLine(), "--host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new ParameterException(spec.commandLine(), "--port must be between 0 and 65535");
        }
        if (maxRequestBytes <= 0) {
            throw new ParameterException(spec.commandLine(), "--max-request-bytes must be positive");
        }
    }
}
