package gd.script.gdcc.rpc;

import com.google.gson.JsonParser;
import gd.script.gdcc.api.API;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the HTTP transport contract of `JsonRpcHttpHandler`/`JsonRpcServer` on an ephemeral port
/// with a real JDK `HttpClient` — no engine, no native toolchain. The GDScript client depends on
/// exactly these status codes.
class RpcServerHttpTest {
    private static final String PING = "{\"jsonrpc\": \"2.0\", \"method\": \"server.ping\", \"id\": 1}";

    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void postRoundTripReturnsJsonRpcResult() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var response = post(server, PING, "application/json");

            assertEquals(200, response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals("2.0", envelope.get("jsonrpc").getAsString());
            assertEquals("pong", envelope.get("result").getAsString());
            assertEquals(1, envelope.get("id").getAsInt());
        }
    }

    @Test
    void postAcceptsContentTypeWithCharsetSuffix() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var response = post(server, PING, "application/json; charset=utf-8");

            assertEquals(200, response.statusCode());
        }
    }

    @Test
    void postWithNonJsonMediaTypeIsRejected() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            assertEquals(415, post(server, PING, "text/plain").statusCode());

            // A missing Content-Type header is not a JSON media type either.
            var request = HttpRequest.newBuilder(endpoint(server))
                    .POST(HttpRequest.BodyPublishers.ofString(PING))
                    .build();
            assertEquals(415, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void nonPostMethodIsRejectedWithAllowHeader() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var request = HttpRequest.newBuilder(endpoint(server)).GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(405, response.statusCode());
            assertEquals("POST", response.headers().firstValue("Allow").orElseThrow());
        }
    }

    @Test
    void oversizedBodyIsRejectedWithoutBuffering() throws Exception {
        // A small configured limit plus a body exactly one byte over it: the bounded read must stop
        // at the limit and answer 413 instead of buffering the whole body first.
        var limit = 64;
        try (var server = startServer(limit)) {
            var oversized = PING + " ".repeat(limit + 1 - PING.length());
            assertEquals(limit + 1, oversized.getBytes(StandardCharsets.UTF_8).length);

            assertEquals(413, post(server, oversized, "application/json").statusCode());

            // Exactly at the limit the request still goes through (a notification, so 204).
            var notification = "{\"jsonrpc\": \"2.0\", \"method\": \"server.ping\"}";
            var atLimit = notification.substring(0, notification.length() - 1)
                    + " ".repeat(limit - notification.length())
                    + "}";
            assertEquals(limit, atLimit.getBytes(StandardCharsets.UTF_8).length);
            assertEquals(204, post(server, atLimit, "application/json").statusCode());
        }
    }

    @Test
    void malformedJsonStillAnswers200WithParseError() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var response = post(server, "{not valid json", "application/json");

            assertEquals(200, response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(JsonRpcDispatcher.PARSE_ERROR, envelope.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(envelope.get("id").isJsonNull());
        }
    }

    @Test
    void notificationIsExecutedAndAnswered204() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var notification = "{\"jsonrpc\": \"2.0\", \"method\": \"module.create\", "
                    + "\"params\": {\"moduleId\": \"demo\", \"moduleName\": \"Demo\"}}";
            var response = post(server, notification, "application/json");

            assertEquals(204, response.statusCode());
            assertEquals("", response.body());

            // The side effect proves execution: the module exists now.
            var check = post(server, "{\"jsonrpc\": \"2.0\", \"method\": \"module.get\", "
                    + "\"params\": {\"moduleId\": \"demo\"}, \"id\": 2}", "application/json");
            var envelope = JsonParser.parseString(check.body()).getAsJsonObject();
            assertEquals("demo", envelope.getAsJsonObject("result").get("moduleId").getAsString());
        }
    }

    @Test
    void explicitNullIdGetsErrorResponseNot204() throws Exception {
        // Only a request *without* an `id` member is a notification; an explicit JSON-`null` id is
        // an invalid request and must be answered over the wire.
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var response = post(server, "{\"jsonrpc\": \"2.0\", \"method\": \"server.ping\", \"id\": null}",
                    "application/json");

            assertEquals(200, response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(JsonRpcDispatcher.INVALID_REQUEST,
                    envelope.getAsJsonObject("error").get("code").getAsInt());
            assertTrue(envelope.get("id").isJsonNull());
        }
    }

    @Test
    void blankBodyIsAnswered200WithParseError() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var response = post(server, "   \n  ", "application/json");

            assertEquals(200, response.statusCode());
            var envelope = JsonParser.parseString(response.body()).getAsJsonObject();
            assertEquals(JsonRpcDispatcher.PARSE_ERROR, envelope.getAsJsonObject("error").get("code").getAsInt());
        }
    }

    @Test
    void unknownPathIsNotHandled() throws Exception {
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            assertEquals(404, postTo(server, "/other").statusCode());
        }
    }

    @Test
    void contextPrefixLookalikesAreNotHandled() throws Exception {
        // JDK contexts match by longest prefix, so paths merely starting with `/rpc` must still
        // miss the single endpoint.
        try (var server = startServer(JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            assertEquals(404, postTo(server, "/rpcfoo").statusCode());
            assertEquals(404, postTo(server, "/rpc/extra").statusCode());
        }
    }

    private static JsonRpcServer startServer(int maxRequestBytes) throws IOException {
        return JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0, maxRequestBytes);
    }

    private HttpResponse<String> post(JsonRpcServer server, String body, String contentType)
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(endpoint(server))
                        .header("Content-Type", contentType)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> postTo(JsonRpcServer server, String path)
            throws IOException, InterruptedException {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://" + server.host() + ":" + server.port() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(PING))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static URI endpoint(JsonRpcServer server) {
        return URI.create("http://" + server.host() + ":" + server.port() + "/rpc");
    }
}
