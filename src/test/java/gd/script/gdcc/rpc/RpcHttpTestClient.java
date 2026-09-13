package gd.script.gdcc.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;

/// Minimal JSON-RPC-over-HTTP driver for the integration tests: sequential protocol ids, envelope
/// parsing, and a result-returning variant that fails the test on any error envelope. Kept tiny on
/// purpose — it mirrors exactly what the GDScript client does (POST one object, read one object).
final class RpcHttpTestClient {
    private final HttpClient client = HttpClient.newHttpClient();
    private final URI endpoint;
    private long nextId = 1;

    RpcHttpTestClient(JsonRpcServer server) {
        endpoint = URI.create("http://" + server.host() + ":" + server.port() + "/rpc");
    }

    /// Posts one request and returns the raw response envelope for explicit result/error asserts.
    JsonObject call(String method, JsonObject params) throws IOException, InterruptedException {
        var request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("method", method);
        request.add("params", params == null ? new JsonObject() : params);
        request.addProperty("id", nextId++);
        var response = client.send(
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            throw new AssertionError("Expected HTTP 200 from " + method + " but got " + response.statusCode()
                    + ": " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    /// Calls a method and returns its `result`, failing the test when the envelope carries an
    /// error instead.
    JsonElement callForResult(String method, JsonObject params) throws IOException, InterruptedException {
        var envelope = call(method, params);
        if (envelope.has("error")) {
            throw new AssertionError("Expected result from " + method + " but got error: "
                    + envelope.getAsJsonObject("error"));
        }
        assertFalse(envelope.has("error"));
        return envelope.get("result");
    }

    /// Small by-name params builder alternating keys and values (String/Number/Boolean/JsonElement).
    static JsonObject params(Object... keyValues) {
        var params = new JsonObject();
        for (var index = 0; index < keyValues.length; index += 2) {
            var key = (String) keyValues[index];
            var value = keyValues[index + 1];
            switch (value) {
                case String text -> params.addProperty(key, text);
                case Number number -> params.addProperty(key, number);
                case Boolean flag -> params.addProperty(key, flag);
                case JsonElement element -> params.add(key, element);
                default -> throw new IllegalArgumentException("Unsupported param value: " + value);
            }
        }
        return params;
    }
}
