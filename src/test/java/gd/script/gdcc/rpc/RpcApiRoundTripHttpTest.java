package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import gd.script.gdcc.api.API;
import org.junit.jupiter.api.Test;

import static gd.script.gdcc.rpc.RpcHttpTestClient.params;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Full API workflow over real HTTP on an ephemeral-port server: module lifecycle, VFS writes,
/// options and class-map symmetry, and analysis — the exact call sequence the editor plugin
/// drives. This path never configures a build directory, so no native build is started.
class RpcApiRoundTripHttpTest {
    @Test
    void apiWorkflowRoundTripsOverHttp() throws Exception {
        try (var server = JsonRpcServer.start(new JsonRpcDispatcher(new API()), "127.0.0.1", 0,
                JsonRpcServer.DEFAULT_MAX_REQUEST_BYTES)) {
            var rpc = new RpcHttpTestClient(server);

            // module.create → snapshot with the module's initial state.
            var created = rpc.callForResult("module.create", params("moduleId", "demo", "moduleName", "Demo"))
                    .getAsJsonObject();
            assertEquals("demo", created.get("moduleId").getAsString());
            assertEquals("Demo", created.get("moduleName").getAsString());
            assertFalse(created.get("hasLastCompileResult").getAsBoolean());
            assertEquals(0, created.get("rootEntryCount").getAsInt());

            // vfs.putFile with an explicit display path round-trips the file-entry wire shape.
            var mainSource = "class_name RoundTripMain\nextends Node\n";
            var mainEntry = rpc.callForResult("vfs.putFile", params(
                    "moduleId", "demo", "path", "/src/main.gd",
                    "content", mainSource, "displayPath", "res://main.gd"
            )).getAsJsonObject();
            assertEquals("FILE", mainEntry.get("kind").getAsString());
            assertEquals("res://main.gd", mainEntry.get("path").getAsString());
            assertEquals(mainSource.length(), mainEntry.get("byteCount").getAsLong());

            var helperSource = "class_name RoundTripHelper\nextends RefCounted\n";
            rpc.callForResult("vfs.putFile", params(
                    "moduleId", "demo", "path", "/src/helper.gd", "content", helperSource
            ));

            // Content reads and listings cross HTTP unchanged.
            assertEquals(mainSource, rpc.callForResult("vfs.readFile", params(
                    "moduleId", "demo", "path", "/src/main.gd"
            )).getAsString());
            assertEquals(2, rpc.callForResult("vfs.listDirectory", params(
                    "moduleId", "demo", "path", "/src"
            )).getAsJsonArray().size());

            // options.get exposes the defaults; a missing projectPath is an explicit JSON null.
            var options = rpc.callForResult("options.get", params("moduleId", "demo")).getAsJsonObject();
            assertEquals("V451", options.get("godotVersion").getAsString());
            assertTrue(options.has("projectPath"));
            assertTrue(options.get("projectPath").isJsonNull());
            assertFalse(options.get("strictMode").getAsBoolean());

            // options.set takes the exact `options.get` shape back (symmetric wire format) and
            // echoes the replaced snapshot.
            options.addProperty("strictMode", true);
            var replaced = rpc.callForResult("options.set", params(
                    "moduleId", "demo", "compileOptions", options
            )).getAsJsonObject();
            assertTrue(replaced.get("strictMode").getAsBoolean());
            assertTrue(rpc.callForResult("options.get", params("moduleId", "demo"))
                    .getAsJsonObject().get("strictMode").getAsBoolean());

            // classMap.set/get round-trip the same map under the Java component name.
            var classMap = new JsonObject();
            classMap.addProperty("RoundTripMain", "game.roundtrip.RoundTripMain");
            var echoed = rpc.callForResult("classMap.set", params(
                    "moduleId", "demo", "topLevelCanonicalNameMap", classMap
            )).getAsJsonObject();
            assertEquals("game.roundtrip.RoundTripMain", echoed.get("RoundTripMain").getAsString());
            var fetched = rpc.callForResult("classMap.get", params("moduleId", "demo")).getAsJsonObject();
            assertEquals("game.roundtrip.RoundTripMain", fetched.get("RoundTripMain").getAsString());

            // analyze.run on valid sources completes with empty diagnostics.
            var analysis = rpc.callForResult("analyze.run", params("moduleId", "demo")).getAsJsonObject();
            assertEquals("COMPLETED", analysis.get("outcome").getAsString());
            assertEquals(0, analysis.getAsJsonObject("diagnostics").getAsJsonArray("diagnostics").size());
            assertEquals("NOT_REQUESTED", analysis.get("loweringStatus").getAsString());
            // sourcePaths carry display paths: helper.gd falls back to its virtual path, main.gd
            // uses the explicit res:// display path set above.
            var sourcePaths = analysis.getAsJsonArray("sourcePaths");
            assertEquals(2, sourcePaths.size());
            assertEquals("/src/helper.gd", sourcePaths.get(0).getAsString());
            assertEquals("res://main.gd", sourcePaths.get(1).getAsString());

            // includeLowering=true flips the lowering status to SUCCEEDED.
            var lowered = rpc.callForResult("analyze.run", params(
                    "moduleId", "demo", "includeLowering", true
            )).getAsJsonObject();
            assertEquals("SUCCEEDED", lowered.get("loweringStatus").getAsString());

            // A broken source still completes the pipeline but reports ERROR diagnostics —
            // ordinary analysis failures are payload, not JSON-RPC errors.
            rpc.callForResult("vfs.putFile", params(
                    "moduleId", "demo", "path", "/src/main.gd",
                    "content", "class_name AnalyzeBroken\nextends Node\n\nfunc _ready(\n    pass\n"
            ));
            var broken = rpc.callForResult("analyze.run", params("moduleId", "demo")).getAsJsonObject();
            assertEquals("COMPLETED", broken.get("outcome").getAsString());
            var diagnostics = broken.getAsJsonObject("diagnostics").getAsJsonArray("diagnostics");
            assertTrue(diagnostics.size() > 0);
            var sawError = false;
            for (var diagnostic : diagnostics) {
                if ("ERROR".equals(diagnostic.getAsJsonObject().get("severity").getAsString())) {
                    sawError = true;
                    break;
                }
            }
            assertTrue(sawError, "broken source must produce at least one ERROR diagnostic");

            // module.delete returns the final snapshot; the module is gone afterwards.
            var deleted = rpc.callForResult("module.delete", params("moduleId", "demo")).getAsJsonObject();
            assertEquals("demo", deleted.get("moduleId").getAsString());
            var gone = rpc.call("module.get", params("moduleId", "demo"));
            assertEquals(-32000, gone.getAsJsonObject("error").get("code").getAsInt());
        }
    }
}
