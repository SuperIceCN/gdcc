package gd.script.gdcc.rpc;

import gd.script.gdcc.api.API;
import gd.script.gdcc.backend.c.build.ZigUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static gd.script.gdcc.rpc.RpcHttpTestClient.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Compile-over-HTTP integration test: the round-trip workflow plus a real native build through
/// `compile.start` and task polling, gated on zig availability. Proves the adapter layer carries
/// the full editor-plugin compile loop (`options.set` with a build directory → start → poll →
/// result) without any in-process shortcuts.
class RpcCompileHttpIntegrationTest {
    private static final Set<String> TERMINAL_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELED");

    @Test
    void compileWorkflowOverHttp(@TempDir Path tempDir) throws Exception {
        if (ZigUtil.findZig() == null) {
            Assumptions.abort("Zig not found; skipping compile-over-HTTP integration test");
            return;
        }
        try (var server = JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0,
                JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var rpc = new RpcHttpTestClient(server);

            rpc.callForResult("module.create", params("moduleId", "demo", "moduleName", "Compile Demo"));
            rpc.callForResult("vfs.putFile", params(
                    "moduleId", "demo", "path", "/src/main.gd",
                    "content", "class_name RpcCompileMain\nextends RefCounted\n\nfunc value() -> int:\n    return 1\n"
            ));
            rpc.callForResult("vfs.putFile", params(
                    "moduleId", "demo", "path", "/src/helper.gd",
                    "content", "class_name RpcCompileHelper\nextends RefCounted\n"
            ));

            // options.set replaces the whole snapshot, so the build directory is set on top of the
            // exact `options.get` shape — never a hand-written partial object.
            var options = rpc.callForResult("options.get", params("moduleId", "demo")).getAsJsonObject();
            options.addProperty("projectPath", tempDir.resolve("compile-project").toString());
            var replaced = rpc.callForResult("options.set", params(
                    "moduleId", "demo", "compileOptions", options
            )).getAsJsonObject();
            assertEquals(tempDir.resolve("compile-project").toString(), replaced.get("projectPath").getAsString());

            var started = rpc.callForResult("compile.start", params("moduleId", "demo")).getAsJsonObject();
            var taskId = started.get("taskId").getAsLong();

            // Wall-clock deadline polling on the task table — the only progress channel that does
            // not enter the module gate.
            var task = pollTaskUntilTerminal(rpc, taskId);
            assertEquals("SUCCEEDED", task.get("state").getAsString(),
                    "compile task must succeed with a real native build: " + task);
            assertEquals("SUCCESS", task.getAsJsonObject("result").get("outcome").getAsString());
            assertTrue(task.get("completedAt").isJsonPrimitive());

            // The last-result slot mirrors the completed task's compile result.
            var lastResult = rpc.callForResult("compile.getLastResult", params("moduleId", "demo"))
                    .getAsJsonObject();
            assertEquals("SUCCESS", lastResult.get("outcome").getAsString());
            assertTrue(lastResult.getAsJsonArray("generatedFiles").size() > 0);
            assertTrue(lastResult.getAsJsonArray("artifacts").size() > 0);

            // Event paging crosses HTTP with explicit bounds (the production pipeline records no
            // custom events, so the page itself is empty).
            var page = rpc.callForResult("compile.listEvents", params(
                    "taskId", taskId, "startIndex", 0, "maxCount", 10
            ));
            assertTrue(page.isJsonArray());
            assertTrue(rpc.callForResult("compile.getLatestEvent", params("taskId", taskId)).isJsonNull());

            // Cancelling a completed task is an idempotent no-op that echoes the current snapshot.
            var canceled = rpc.callForResult("compile.cancel", params("taskId", taskId)).getAsJsonObject();
            assertEquals("SUCCEEDED", canceled.get("state").getAsString());

            // Clearing events on a completed task succeeds with a null result.
            assertTrue(rpc.call("compile.clearEvents", params("taskId", taskId)).get("result").isJsonNull());
        }
    }

    private static com.google.gson.JsonObject pollTaskUntilTerminal(RpcHttpTestClient rpc, long taskId)
            throws Exception {
        // Native builds are slow on cold caches; the budget stays far above the engine-test frame
        // budgets used elsewhere in the repository.
        var deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        while (System.nanoTime() < deadline) {
            var task = rpc.callForResult("compile.getTask", params("taskId", taskId)).getAsJsonObject();
            if (TERMINAL_STATES.contains(task.get("state").getAsString())) {
                return task;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Compile task " + taskId + " did not complete within the deadline");
    }
}
