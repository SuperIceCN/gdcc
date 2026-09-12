package gd.script.gdcc.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import gd.script.gdcc.api.API;
import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileTaskSnapshot;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.ApiModuleNotFoundException;
import gd.script.gdcc.exception.CodegenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the JSON-RPC envelope rules and the exception-to-error-code mapping against a real `API`
/// instance (constructed plainly, without the api-package test support). Covers both directions:
/// well-formed flows produce result envelopes, and every documented failure mode produces its
/// pinned error code.
class JsonRpcDispatcherTest {
    private final API api = new API();
    private final JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(api);

    // ------------------------------------------------------------------ envelope rules

    @Test
    void successEnvelopeCarriesResultEchoesNumericIdVerbatimAndOmitsError() {
        var response = dispatcher.dispatch(request("server.ping", null, new JsonPrimitive(1)));

        assertEquals("2.0", response.get("jsonrpc").getAsString());
        assertEquals("pong", response.get("result").getAsString());
        assertFalse(response.has("error"));
        // Verbatim echo: the integer id must not come back as `1.0`.
        assertEquals("1", response.get("id").toString());
    }

    @Test
    void successEnvelopeEchoesStringIdVerbatim() {
        var response = dispatcher.dispatch(request("server.ping", null, new JsonPrimitive("req-abc")));

        assertEquals(new JsonPrimitive("req-abc"), response.get("id"));
    }

    @Test
    void errorEnvelopeCarriesErrorEchoesIdAndOmitsResult() {
        var response = dispatcher.dispatch(request("no.such.method", null, new JsonPrimitive(9)));

        assertFalse(response.has("result"));
        var error = response.getAsJsonObject("error");
        assertEquals(JsonRpcDispatcher.METHOD_NOT_FOUND, error.get("code").getAsInt());
        assertEquals(new JsonPrimitive(9), response.get("id"));
    }

    @Test
    void parseErrorRespondsWithNullId() {
        var response = dispatcher.dispatch("{not valid json");

        var error = response.getAsJsonObject("error");
        assertEquals(JsonRpcDispatcher.PARSE_ERROR, error.get("code").getAsInt());
        assertEquals(JsonNull.INSTANCE, response.get("id"));
        // Parse failures have an underlying exception, so `data` carries its simple class name.
        assertTrue(error.getAsJsonObject("data").get("exception").getAsString().contains("JsonSyntaxException"));
    }

    @Test
    void malformedJsonVariantsAreParseErrors() {
        // Empty and whitespace-only bodies are parse failures, not envelope failures.
        assertParseError("");
        assertParseError("   \n  ");
        // Lenient JSON dialects that Gson would otherwise accept must stay rejected.
        assertParseError("{'jsonrpc': '2.0', 'method': 'server.ping', 'id': 1}");
        assertParseError("{jsonrpc: \"2.0\", method: \"server.ping\", id: 1}");
        assertParseError("{\"jsonrpc\": \"2.0\", /* comment */ \"method\": \"server.ping\", \"id\": 1}");
        assertParseError("{\"jsonrpc\": \"2.0\", \"method\": \"server.ping\", \"id\": 1} trailing");
    }

    @Test
    void explicitNullIdIsRejectedAndNotTreatedAsNotification() {
        // A notification is a request *without* an `id` member; an explicit JSON-`null` id
        // violates the string|number id contract and must be answered, never silently executed.
        var response = dispatcher.dispatch("{\"jsonrpc\": \"2.0\", \"method\": \"module.create\", "
                + "\"params\": {\"moduleId\": \"demo\", \"moduleName\": \"Demo\"}, \"id\": null}");

        assertEquals(JsonRpcDispatcher.INVALID_REQUEST, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(JsonNull.INSTANCE, response.get("id"));
        assertThrows(
                ApiModuleNotFoundException.class,
                () -> api.getModule("demo"),
                "explicit null id must not execute as a notification"
        );
    }

    @Test
    void batchRequestIsRejectedAsInvalidRequest() {
        var response = dispatcher.dispatch("[{\"jsonrpc\": \"2.0\", \"method\": \"server.ping\", \"id\": 1}]");

        assertEquals(JsonRpcDispatcher.INVALID_REQUEST, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(JsonNull.INSTANCE, response.get("id"));
    }

    @Test
    void invalidEnvelopeVariantsAreRejectedAsInvalidRequest() {
        // Non-object body.
        assertInvalidRequest("42");
        // Missing / wrong / non-string `jsonrpc` member.
        assertInvalidRequest("{\"method\": \"server.ping\", \"id\": 1}");
        assertInvalidRequest("{\"jsonrpc\": \"1.0\", \"method\": \"server.ping\", \"id\": 1}");
        assertInvalidRequest("{\"jsonrpc\": 2.0, \"method\": \"server.ping\", \"id\": 1}");
        // Missing / non-string `method` member.
        assertInvalidRequest("{\"jsonrpc\": \"2.0\", \"id\": 1}");
        assertInvalidRequest("{\"jsonrpc\": \"2.0\", \"method\": 42, \"id\": 1}");
        // Unusable id type (object/array/boolean are not string|number).
        assertInvalidRequest("{\"jsonrpc\": \"2.0\", \"method\": \"server.ping\", \"id\": {}}");
        assertInvalidRequest("{\"jsonrpc\": \"2.0\", \"method\": \"server.ping\", \"id\": true}");
    }

    @Test
    void byPositionParamsAreRejectedAsInvalidParams() {
        var response = dispatcher.dispatch(
                "{\"jsonrpc\": \"2.0\", \"method\": \"module.get\", \"params\": [\"demo\"], \"id\": 3}"
        );

        assertEquals(JsonRpcDispatcher.INVALID_PARAMS, response.getAsJsonObject("error").get("code").getAsInt());
        assertEquals(new JsonPrimitive(3), response.get("id"));
    }

    @Test
    void scalarParamsAreRejectedAsInvalidParams() {
        var response = dispatcher.dispatch(
                "{\"jsonrpc\": \"2.0\", \"method\": \"module.get\", \"params\": \"demo\", \"id\": 3}"
        );

        assertEquals(JsonRpcDispatcher.INVALID_PARAMS, response.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void notificationExecutesAndProducesNoResponse() {
        // Without an id the request is a notification: it executes and returns no envelope.
        var notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", "module.create");
        notification.add("params", params("moduleId", "demo", "moduleName", "Demo"));

        assertNull(dispatcher.dispatch(notification));

        // The side effect proves execution: the module now exists.
        var check = dispatcher.dispatch(request("module.get", params("moduleId", "demo"), new JsonPrimitive(1)));
        assertEquals("demo", check.getAsJsonObject("result").get("moduleId").getAsString());
    }

    @Test
    void notificationFailuresProduceNoResponse() {
        // Unknown method, invalid params, and execution failures of notifications are all dropped.
        var unknownMethod = new JsonObject();
        unknownMethod.addProperty("jsonrpc", "2.0");
        unknownMethod.addProperty("method", "no.such.method");
        assertNull(dispatcher.dispatch(unknownMethod));

        var invalidParams = new JsonObject();
        invalidParams.addProperty("jsonrpc", "2.0");
        invalidParams.addProperty("method", "module.get");
        invalidParams.add("params", new JsonObject());
        assertNull(dispatcher.dispatch(invalidParams));

        var executionFailure = new JsonObject();
        executionFailure.addProperty("jsonrpc", "2.0");
        executionFailure.addProperty("method", "module.get");
        executionFailure.add("params", params("moduleId", "missing"));
        assertNull(dispatcher.dispatch(executionFailure));
    }

    // ------------------------------------------------------------------ param binding

    @Test
    void missingRequiredRecursiveParamFailsAsInvalidParams() {
        var response = dispatcher.dispatch(request(
                "vfs.deletePath",
                params("moduleId", "demo", "path", "/src"),
                new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(JsonRpcDispatcher.INVALID_PARAMS, error.get("code").getAsInt());
        assertEquals("NullPointerException", error.getAsJsonObject("data").get("exception").getAsString());
        assertEquals("recursive must not be null", error.getAsJsonObject("data").get("message").getAsString());
    }

    @Test
    void missingModuleIdFailsAsInvalidParamsWithNpeData() {
        // The API's argument checks throw `NullPointerException`; that must map to -32602, never
        // to -32603.
        var response = dispatcher.dispatch(request("module.get", new JsonObject(), new JsonPrimitive(1)));

        var error = response.getAsJsonObject("error");
        assertEquals(JsonRpcDispatcher.INVALID_PARAMS, error.get("code").getAsInt());
        assertEquals("NullPointerException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void apiArgumentCheckFailuresMapToInvalidParams() {
        // `taskId <= 0` passes the boxed param record but fails the API's own range check (IAE).
        var response = dispatcher.dispatch(request(
                "compile.getTask",
                params("taskId", -5),
                new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(JsonRpcDispatcher.INVALID_PARAMS, error.get("code").getAsInt());
        assertEquals("IllegalArgumentException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    // ------------------------------------------------------------------ error code mapping

    @Test
    void mapsModuleNotFoundToError() {
        var response = dispatcher.dispatch(request("module.get", params("moduleId", "missing"), new JsonPrimitive(1)));

        var error = response.getAsJsonObject("error");
        assertEquals(-32000, error.get("code").getAsInt());
        assertEquals("ApiModuleNotFoundException", error.getAsJsonObject("data").get("exception").getAsString());
        assertEquals(error.get("message").getAsString(), error.getAsJsonObject("data").get("message").getAsString());
    }

    @Test
    void mapsModuleAlreadyExistsToError() {
        api.createModule("demo", "Demo");

        var response = dispatcher.dispatch(request(
                "module.create", params("moduleId", "demo", "moduleName", "Again"), new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(-32001, error.get("code").getAsInt());
        assertEquals("ApiModuleAlreadyExistsException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsModuleBusyAndCompileAlreadyRunningToErrors(@TempDir Path tempDir) {
        api.createModule("demo", "Demo");
        api.setCompileOptions("demo", compileOptions(tempDir.resolve("busy-project")));
        // A multi-file workload keeps the task in active stages long enough for the assertions
        // below; the queued/active slot is held until the runner finishes.
        for (var index = 0; index < 40; index++) {
            api.putFile("demo", "/src/file" + index + ".gd", "class_name BusyDemo" + index + "\nextends RefCounted\n");
        }
        var taskId = api.compile("demo");
        awaitTaskState(taskId, CompileTaskSnapshot.State.RUNNING);
        try {
            var duplicateCompile = dispatcher.dispatch(request(
                    "compile.start", params("moduleId", "demo"), new JsonPrimitive(1)
            ));
            var duplicateError = duplicateCompile.getAsJsonObject("error");
            assertEquals(-32003, duplicateError.get("code").getAsInt());
            assertEquals(
                    "ApiCompileAlreadyRunningException",
                    duplicateError.getAsJsonObject("data").get("exception").getAsString()
            );

            var delete = dispatcher.dispatch(request("module.delete", params("moduleId", "demo"), new JsonPrimitive(2)));
            var deleteError = delete.getAsJsonObject("error");
            assertEquals(-32002, deleteError.get("code").getAsInt());
            assertEquals(
                    "ApiModuleBusyException",
                    deleteError.getAsJsonObject("data").get("exception").getAsString()
            );
        } finally {
            // Bound the test runtime regardless of whether a native toolchain is present.
            api.cancelCompileTask(taskId);
            awaitTaskTerminal(taskId);
        }
    }

    @Test
    void mapsCompileTaskNotFoundToError() {
        var response = dispatcher.dispatch(request(
                "compile.getTask", params("taskId", 999999999), new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(-32004, error.get("code").getAsInt());
        assertEquals("ApiCompileTaskNotFoundException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsPathNotFoundToError() {
        api.createModule("demo", "Demo");

        var response = dispatcher.dispatch(request(
                "vfs.readFile", params("moduleId", "demo", "path", "/missing.gd"), new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(-32005, error.get("code").getAsInt());
        assertEquals("ApiPathNotFoundException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsEntryTypeMismatchToError() {
        api.createModule("demo", "Demo");
        api.createDirectory("demo", "/docs");

        var response = dispatcher.dispatch(request(
                "vfs.readFile", params("moduleId", "demo", "path", "/docs"), new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(-32006, error.get("code").getAsInt());
        assertEquals("ApiEntryTypeMismatchException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsDirectoryNotEmptyToError() {
        api.createModule("demo", "Demo");
        api.putFile("demo", "/docs/note.txt", "hello");

        var response = dispatcher.dispatch(request(
                "vfs.deletePath",
                params("moduleId", "demo", "path", "/docs", "recursive", false),
                new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(-32007, error.get("code").getAsInt());
        assertEquals("ApiDirectoryNotEmptyException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsBrokenLinkToError() {
        api.createModule("demo", "Demo");
        api.createLink("demo", "/dangling", gd.script.gdcc.api.VfsEntrySnapshot.LinkKind.VIRTUAL, "/missing");

        var response = dispatcher.dispatch(request(
                "vfs.readFile", params("moduleId", "demo", "path", "/dangling"), new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(-32008, error.get("code").getAsInt());
        assertEquals("ApiBrokenLinkException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsLinkCycleToError() {
        api.createModule("demo", "Demo");
        api.createLink("demo", "/a", gd.script.gdcc.api.VfsEntrySnapshot.LinkKind.VIRTUAL, "/b");
        api.createLink("demo", "/b", gd.script.gdcc.api.VfsEntrySnapshot.LinkKind.VIRTUAL, "/a");

        var response = dispatcher.dispatch(request(
                "vfs.readFile", params("moduleId", "demo", "path", "/a"), new JsonPrimitive(1)
        ));

        var error = response.getAsJsonObject("error");
        assertEquals(-32009, error.get("code").getAsInt());
        assertEquals("ApiLinkCycleException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsUnexpectedRuntimeFailureToInternalError() {
        var failing = new JsonRpcMethodRegistry(Map.of(
                "test.boom",
                (api, codec, params) -> {
                    throw new IllegalStateException("boom");
                }
        ));
        var failingDispatcher = new JsonRpcDispatcher(api, new RpcJsonCodec(), failing);

        var response = failingDispatcher.dispatch(request("test.boom", null, new JsonPrimitive(1)));

        var error = response.getAsJsonObject("error");
        assertEquals(JsonRpcDispatcher.INTERNAL_ERROR, error.get("code").getAsInt());
        assertEquals("IllegalStateException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    @Test
    void mapsUnmappedDomainExceptionToInternalError() {
        // Domain exceptions outside the pinned -3200x table are internal errors, not client faults.
        var failing = new JsonRpcMethodRegistry(Map.of(
                "test.domain",
                (api, codec, params) -> {
                    throw new CodegenException("domain boom");
                }
        ));
        var failingDispatcher = new JsonRpcDispatcher(api, new RpcJsonCodec(), failing);

        var response = failingDispatcher.dispatch(request("test.domain", null, new JsonPrimitive(1)));

        var error = response.getAsJsonObject("error");
        assertEquals(JsonRpcDispatcher.INTERNAL_ERROR, error.get("code").getAsInt());
        assertEquals("CodegenException", error.getAsJsonObject("data").get("exception").getAsString());
    }

    // ------------------------------------------------------------------ task/event surface

    @Test
    void serverInfoReturnsVersionAndPageSizeShape() {
        var response = dispatcher.dispatch(request("server.info", null, new JsonPrimitive(1)));

        var info = response.getAsJsonObject("result");
        assertTrue(info.get("version").isJsonPrimitive());
        assertTrue(info.get("branch").isJsonPrimitive());
        assertTrue(info.get("commit").isJsonPrimitive());
        assertEquals(API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE, info.get("maxCompileTaskEventPageSize").getAsInt());
    }

    @Test
    void compileStartReturnsTaskIdObject() {
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "class_name StartShapeDemo\nextends RefCounted\n");

        var response = dispatcher.dispatch(request("compile.start", params("moduleId", "demo"), new JsonPrimitive(1)));

        var result = response.getAsJsonObject("result");
        assertFalse(response.has("error"));
        assertTrue(result.get("taskId").getAsLong() > 0);
        awaitTaskTerminal(result.get("taskId").getAsLong());
    }

    @Test
    void clearEventsReturnsNullResult() {
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "class_name ClearEventsDemo\nextends RefCounted\n");
        var taskId = api.compile("demo");
        awaitTaskTerminal(taskId);

        var response = dispatcher.dispatch(request(
                "compile.clearEvents", params("taskId", taskId), new JsonPrimitive(1)
        ));

        assertFalse(response.has("error"));
        assertEquals(JsonNull.INSTANCE, response.get("result"));
    }

    @Test
    void latestEventReturnsNullResultForEmptyEventLog() {
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "class_name EmptyLogDemo\nextends RefCounted\n");
        // No projectPath: the compile fails fast with CONFIGURATION_FAILED and records no events.
        var taskId = api.compile("demo");
        awaitTaskTerminal(taskId);

        var response = dispatcher.dispatch(request(
                "compile.getLatestEvent", params("taskId", taskId), new JsonPrimitive(1)
        ));

        assertEquals(JsonNull.INSTANCE, response.get("result"));
    }

    @Test
    void listEventsDefaultsMaxCountToFullPageAndRejectsZero() {
        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "class_name PageDefaultDemo\nextends RefCounted\n");
        var taskId = api.compile("demo");
        awaitTaskTerminal(taskId);

        // Omitted `maxCount` must bind to the full page size (a zero-default would fail the API's
        // range check instead of succeeding).
        var defaulted = dispatcher.dispatch(request(
                "compile.listEvents", params("taskId", taskId), new JsonPrimitive(1)
        ));
        assertTrue(defaulted.get("result").isJsonArray());

        var zero = dispatcher.dispatch(request(
                "compile.listEvents", params("taskId", taskId, "maxCount", 0), new JsonPrimitive(2)
        ));
        assertEquals(JsonRpcDispatcher.INVALID_PARAMS, zero.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void allDocumentedMethodsAreRouted() {
        assertEquals(25, JsonRpcMethodRegistry.create().methodCount());

        api.createModule("demo", "Demo");
        api.putFile("demo", "/src/main.gd", "class_name RoutingDemo\nextends Node\n");
        api.createDirectory("demo", "/docs");
        var taskId = api.compile("demo");
        awaitTaskTerminal(taskId);

        // Every method must resolve to a handler; domain failures are fine, -32601 is not.
        var calls = Map.ofEntries(
                Map.entry("server.ping", new JsonObject()),
                Map.entry("server.info", new JsonObject()),
                Map.entry("module.create", params("moduleId", "second", "moduleName", "Second")),
                Map.entry("module.get", params("moduleId", "demo")),
                Map.entry("module.list", new JsonObject()),
                Map.entry("module.delete", params("moduleId", "second")),
                Map.entry("vfs.createDirectory", params("moduleId", "demo", "path", "/docs2")),
                Map.entry("vfs.putFile", params("moduleId", "demo", "path", "/src/b.gd", "content", "extends Node\n")),
                Map.entry("vfs.readFile", params("moduleId", "demo", "path", "/src/main.gd")),
                Map.entry("vfs.deletePath", params("moduleId", "demo", "path", "/docs2", "recursive", false)),
                Map.entry("vfs.listDirectory", params("moduleId", "demo", "path", "/src")),
                Map.entry("vfs.readEntry", params("moduleId", "demo", "path", "/src/main.gd")),
                Map.entry("vfs.createLink", params("moduleId", "demo", "path", "/out", "linkKind", "LOCAL", "target", "/tmp/out")),
                Map.entry("options.get", params("moduleId", "demo")),
                Map.entry("options.set", optionsSetParams()),
                Map.entry("classMap.get", params("moduleId", "demo")),
                Map.entry("classMap.set", classMapSetParams()),
                Map.entry("compile.start", params("moduleId", "demo")),
                Map.entry("compile.getTask", params("taskId", taskId)),
                Map.entry("compile.cancel", params("taskId", taskId)),
                Map.entry("compile.getLastResult", params("moduleId", "demo")),
                Map.entry("compile.listEvents", params("taskId", taskId)),
                Map.entry("compile.getLatestEvent", params("taskId", taskId)),
                Map.entry("compile.clearEvents", params("taskId", taskId)),
                Map.entry("analyze.run", params("moduleId", "demo"))
        );
        assertEquals(25, calls.size());

        long startedTask = 0;
        for (var entry : calls.entrySet()) {
            var response = dispatcher.dispatch(request(entry.getKey(), entry.getValue(), new JsonPrimitive(1)));
            if ("compile.start".equals(entry.getKey()) && response.has("result")) {
                startedTask = response.getAsJsonObject("result").get("taskId").getAsLong();
            }
            if (response.has("error")) {
                assertNotEquals(
                        JsonRpcDispatcher.METHOD_NOT_FOUND,
                        response.getAsJsonObject("error").get("code").getAsInt(),
                        "Method must be routed: " + entry.getKey()
                );
            }
        }
        if (startedTask != 0) {
            awaitTaskTerminal(startedTask);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void assertInvalidRequest(String body) {
        var response = dispatcher.dispatch(body);
        assertEquals(
                JsonRpcDispatcher.INVALID_REQUEST,
                response.getAsJsonObject("error").get("code").getAsInt(),
                "Body must be rejected as Invalid Request: " + body
        );
    }

    private void assertParseError(String body) {
        var response = dispatcher.dispatch(body);
        assertEquals(
                JsonRpcDispatcher.PARSE_ERROR,
                response.getAsJsonObject("error").get("code").getAsInt(),
                "Body must be rejected as Parse error: '" + body + "'"
        );
        assertEquals(JsonNull.INSTANCE, response.get("id"));
    }

    private static JsonObject request(String method, JsonObject params, JsonElement id) {
        var request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("method", method);
        if (params != null) {
            request.add("params", params);
        }
        if (id != null) {
            request.add("id", id);
        }
        return request;
    }

    private static JsonObject params(Object... keyValues) {
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

    private static JsonObject optionsSetParams() {
        var options = new JsonObject();
        options.addProperty("godotVersion", "V451");
        options.add("projectPath", JsonNull.INSTANCE);
        options.addProperty("optimizationLevel", "DEBUG");
        options.addProperty("targetPlatform", "LINUX_X86_64");
        options.addProperty("strictMode", false);
        options.addProperty("outputMountRoot", "/__build__");
        return params("moduleId", "demo", "compileOptions", options);
    }

    private static JsonObject classMapSetParams() {
        var map = new JsonObject();
        map.addProperty("Player", "game.Player");
        return params("moduleId", "demo", "topLevelCanonicalNameMap", map);
    }

    private static CompileOptions compileOptions(Path projectPath) {
        return new CompileOptions(
                GodotVersion.V451,
                projectPath,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform(),
                false,
                CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT
        );
    }

    private void awaitTaskState(long taskId, CompileTaskSnapshot.State expected) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            var snapshot = api.getCompileTask(taskId);
            if (snapshot.state() == expected) {
                return;
            }
            if (snapshot.completed()) {
                throw new AssertionError(
                        "Task " + taskId + " completed before reaching " + expected + ": " + snapshot.state()
                );
            }
            sleepBriefly();
        }
        throw new AssertionError("Task " + taskId + " did not reach " + expected + " within the deadline");
    }

    private void awaitTaskTerminal(long taskId) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (api.getCompileTask(taskId).completed()) {
                return;
            }
            sleepBriefly();
        }
        throw new AssertionError("Task " + taskId + " did not complete within the deadline");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while polling compile task", exception);
        }
    }
}
