package gd.script.gdcc.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/// HTTP transport for the JSON-RPC dispatcher on the single `POST /rpc` endpoint.
///
/// Status-code contract (pinned by `RpcServerHttpTest`; the GDScript client depends on it):
/// - any well-formed POST gets `200` with the JSON-RPC response object — including JSON-RPC parse
///   errors and method errors, which are protocol answers, not transport failures;
/// - a notification (request without `id`) is executed and answered `204` with an empty body;
/// - a body beyond the configured size limit is answered `413`; the body is read through a
///   counting stream that stops at the limit instead of buffering it first (`HttpServer` has no
///   max-body setting, so a naive `readAllBytes()` would defeat the limit entirely);
/// - a non-JSON media type is answered `415`, a non-POST method `405` with `Allow: POST`.
public final class JsonRpcHttpHandler implements HttpHandler {
    static final String APPLICATION_JSON = "application/json";

    private final @NotNull JsonRpcDispatcher dispatcher;
    private final int maxRequestBytes;

    public JsonRpcHttpHandler(@NotNull JsonRpcDispatcher dispatcher, int maxRequestBytes) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher must not be null");
        if (maxRequestBytes <= 0) {
            throw new IllegalArgumentException("maxRequestBytes must be positive");
        }
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    public void handle(@NotNull HttpExchange exchange) throws IOException {
        try (exchange) {
            // `HttpServer` contexts match by longest prefix, so `/rpcfoo` and `/rpc/extra` would
            // otherwise reach this handler too; the endpoint contract is the exact path only.
            if (!JsonRpcServer.RPC_CONTEXT.equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            var contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !isJsonMediaType(contentType)) {
                exchange.sendResponseHeaders(415, -1);
                return;
            }
            var body = readBodyBounded(exchange.getRequestBody(), maxRequestBytes);
            if (body == null) {
                exchange.sendResponseHeaders(413, -1);
                return;
            }
            var response = dispatcher.dispatch(new String(body, StandardCharsets.UTF_8));
            if (response == null) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            var responseBytes = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", APPLICATION_JSON);
            exchange.sendResponseHeaders(200, responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
        }
    }

    /// Accepts `application/json` with an optional `; charset=...` (or any other parameter)
    /// suffix; the body is always decoded as UTF-8 regardless of the declared parameter.
    private static boolean isJsonMediaType(@NotNull String contentType) {
        var mediaType = contentType.split(";", 2)[0].trim();
        return APPLICATION_JSON.equals(mediaType.toLowerCase(Locale.ROOT));
    }

    /// Reads at most `maxBytes + 1` bytes so an oversized body is detected the moment the limit is
    /// crossed — the read stops there and the caller answers `413` without draining the rest.
    /// Arithmetic is widened to `long` so `Integer.MAX_VALUE` limits cannot overflow the bound.
    private static byte @Nullable [] readBodyBounded(@NotNull InputStream in, int maxBytes) throws IOException {
        var buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        var chunk = new byte[8192];
        long total = 0;
        while (total <= maxBytes) {
            var read = in.read(chunk, 0, (int) Math.min(chunk.length, (long) maxBytes + 1 - total));
            if (read < 0) {
                return buffer.toByteArray();
            }
            buffer.write(chunk, 0, read);
            total += read;
        }
        return null;
    }
}
