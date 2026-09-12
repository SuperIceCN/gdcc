package gd.script.gdcc.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import gd.script.gdcc.api.API;
import gd.script.gdcc.exception.ApiBrokenLinkException;
import gd.script.gdcc.exception.ApiCompileAlreadyRunningException;
import gd.script.gdcc.exception.ApiCompileTaskNotFoundException;
import gd.script.gdcc.exception.ApiDirectoryNotEmptyException;
import gd.script.gdcc.exception.ApiEntryTypeMismatchException;
import gd.script.gdcc.exception.ApiLinkCycleException;
import gd.script.gdcc.exception.ApiModuleAlreadyExistsException;
import gd.script.gdcc.exception.ApiModuleBusyException;
import gd.script.gdcc.exception.ApiModuleNotFoundException;
import gd.script.gdcc.exception.ApiPathNotFoundException;
import gd.script.gdcc.exception.GdccException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import java.util.Objects;

/// JSON-RPC 2.0 dispatcher in front of the in-process `API` facade: parses the request envelope,
/// routes to the method registry, and encodes the result or mapped error.
///
/// Envelope construction is fully manual (`JsonObject` member by member) and never passes through
/// the null-serializing DTO codec, so a success envelope never carries an `error` member and an
/// error envelope never carries a `result` member, as JSON-RPC 2.0 requires. The request `id` is
/// captured as the raw `JsonElement` and echoed verbatim — no `Object`/`Double` round-trip, so an
/// integer id never comes back as `1.0`.
///
/// Notifications (requests without an `id` member) are executed but never produce a response
/// envelope; their failures are logged and dropped. An envelope that cannot be understood at all
/// (parse error, non-object, batch array, bad `jsonrpc`/`method`, unusable or JSON-`null` `id`)
/// is not a valid request — and therefore not a notification either — so it is answered with an
/// error and `id: null`.
public final class JsonRpcDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonRpcDispatcher.class);

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    /// API domain exceptions keep their descriptive exception message as the error message; the
    /// client branches on the code plus `data.exception`, never on message text.
    private static final Map<Class<? extends GdccException>, Integer> API_EXCEPTION_CODES = Map.ofEntries(
            Map.entry(ApiModuleNotFoundException.class, -32000),
            Map.entry(ApiModuleAlreadyExistsException.class, -32001),
            Map.entry(ApiModuleBusyException.class, -32002),
            Map.entry(ApiCompileAlreadyRunningException.class, -32003),
            Map.entry(ApiCompileTaskNotFoundException.class, -32004),
            Map.entry(ApiPathNotFoundException.class, -32005),
            Map.entry(ApiEntryTypeMismatchException.class, -32006),
            Map.entry(ApiDirectoryNotEmptyException.class, -32007),
            Map.entry(ApiBrokenLinkException.class, -32008),
            Map.entry(ApiLinkCycleException.class, -32009)
    );

    private final @NotNull API api;
    private final @NotNull RpcJsonCodec codec;
    private final @NotNull JsonRpcMethodRegistry registry;

    public JsonRpcDispatcher(@NotNull API api) {
        this(api, new RpcJsonCodec(), JsonRpcMethodRegistry.create());
    }

    JsonRpcDispatcher(@NotNull API api, @NotNull RpcJsonCodec codec, @NotNull JsonRpcMethodRegistry registry) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /// Dispatches one raw request body. Returns the response envelope, or `null` when the request
    /// is a notification (the transport layer then answers `204` with an empty body).
    ///
    /// Parsing is strict JSON (no lenient single quotes/unquoted names/comments) and must consume
    /// the whole body; blank or malformed bodies are parse errors (`-32700`), never envelope
    /// errors.
    public @Nullable JsonObject dispatch(@NotNull String requestBody) {
        if (requestBody.isBlank()) {
            return errorEnvelope(JsonNull.INSTANCE, PARSE_ERROR, "Parse error", null);
        }
        JsonElement request;
        try {
            var reader = new JsonReader(new StringReader(requestBody));
            reader.setStrictness(Strictness.STRICT);
            request = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new JsonSyntaxException("Trailing content after the JSON value");
            }
        } catch (JsonParseException | IllegalStateException | IOException exception) {
            return errorEnvelope(JsonNull.INSTANCE, PARSE_ERROR, "Parse error", exception);
        }
        return dispatch(request);
    }

    public @Nullable JsonObject dispatch(@NotNull JsonElement request) {
        if (request.isJsonArray()) {
            return errorEnvelope(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid Request", null);
        }
        if (!request.isJsonObject()) {
            return errorEnvelope(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid Request", null);
        }
        var envelope = request.getAsJsonObject();

        // The id must be a string or number. An explicit JSON-`null` id is neither a usable id
        // nor "no id member" — the narrow envelope contract rejects it as an invalid request.
        var id = envelope.get("id");
        if (id != null && (id.isJsonNull() || !isUsableId(id))) {
            return errorEnvelope(JsonNull.INSTANCE, INVALID_REQUEST, "Invalid Request", null);
        }
        var notification = id == null;

        var jsonrpc = envelope.get("jsonrpc");
        if (jsonrpc == null
                || !jsonrpc.isJsonPrimitive()
                || !"2.0".equals(stringValueOrNull(jsonrpc))) {
            return errorEnvelope(responseId(id), INVALID_REQUEST, "Invalid Request", null);
        }
        var methodElement = envelope.get("method");
        var method = methodElement == null ? null : stringValueOrNull(methodElement);
        if (method == null) {
            return errorEnvelope(responseId(id), INVALID_REQUEST, "Invalid Request", null);
        }
        var handler = registry.find(method);
        if (handler == null) {
            if (notification) {
                LOGGER.warn("Dropping notification for unknown method '{}'", method);
                return null;
            }
            return errorEnvelope(responseId(id), METHOD_NOT_FOUND, "Method not found", null);
        }

        var paramsElement = envelope.get("params");
        if (paramsElement != null && !paramsElement.isJsonNull() && !paramsElement.isJsonObject()) {
            // Both by-position arrays and scalar params violate the by-name params contract.
            if (notification) {
                LOGGER.warn("Dropping notification for method '{}' with non-object params", method);
                return null;
            }
            return errorEnvelope(responseId(id), INVALID_PARAMS, "Invalid params", null);
        }
        var params = paramsElement == null || paramsElement.isJsonNull()
                ? new JsonObject()
                : paramsElement.getAsJsonObject();

        if (notification) {
            try {
                handler.invoke(api, codec, params);
            } catch (RuntimeException exception) {
                LOGGER.warn("Notification for method '{}' failed and is dropped", method, exception);
            }
            return null;
        }

        try {
            var result = handler.invoke(api, codec, params);
            return resultEnvelope(id, result);
        } catch (JsonParseException | IllegalArgumentException | NullPointerException exception) {
            // Covers param-record binding/validation failures and the API layer's own argument
            // checks alike — both are caller input problems, never internal errors.
            return errorEnvelope(id, INVALID_PARAMS, "Invalid params", exception);
        } catch (GdccException exception) {
            var code = API_EXCEPTION_CODES.get(exception.getClass());
            if (code == null) {
                LOGGER.error("Unhandled domain exception from method '{}'", method, exception);
                return errorEnvelope(id, INTERNAL_ERROR, "Internal error", exception);
            }
            return errorEnvelope(id, code, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            LOGGER.error("Unexpected internal error from method '{}'", method, exception);
            return errorEnvelope(id, INTERNAL_ERROR, "Internal error", exception);
        }
    }

    private static boolean isUsableId(@NotNull JsonElement id) {
        if (!id.isJsonPrimitive()) {
            return false;
        }
        var primitive = id.getAsJsonPrimitive();
        return primitive.isString() || primitive.isNumber();
    }

    /// Echoes a usable request id, falling back to JSON `null` when the request had none (or an
    /// unusable one); parse-level failures always answer with `id: null`.
    private static @NotNull JsonElement responseId(@Nullable JsonElement id) {
        return id == null ? JsonNull.INSTANCE : id;
    }

    private static @Nullable String stringValueOrNull(@NotNull JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private @NotNull JsonObject resultEnvelope(@NotNull JsonElement id, @Nullable Object result) {
        var envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.add("result", codec.toJsonTree(result));
        envelope.add("id", id);
        return envelope;
    }

    private static @NotNull JsonObject errorEnvelope(
            @NotNull JsonElement id,
            int code,
            @NotNull String message,
            @Nullable Throwable cause
    ) {
        var envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        var error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        if (cause != null) {
            // `data` lets the client branch on the exception type without parsing message text.
            var data = new JsonObject();
            data.addProperty("exception", cause.getClass().getSimpleName());
            data.addProperty("message", cause.getMessage());
            error.add("data", data);
        }
        envelope.add("error", error);
        envelope.add("id", id);
        return envelope;
    }
}
