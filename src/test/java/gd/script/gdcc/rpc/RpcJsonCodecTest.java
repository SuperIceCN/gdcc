package gd.script.gdcc.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalysisResult;
import gd.script.gdcc.api.AnalyzeOptions;
import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.api.CompileTaskEvent;
import gd.script.gdcc.api.CompileTaskSnapshot;
import gd.script.gdcc.api.ModuleSnapshot;
import gd.script.gdcc.api.VfsEntrySnapshot;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendPoint;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the RPC DTO wire format (`RpcJsonCodec`) and the param-record binding rules: record
/// component names, `serializeNulls` shape stability, enum `name()` values, Path/Instant encodings,
/// the custom `VfsEntrySnapshot` shapes, and the boxed required/optional param component rules.
class RpcJsonCodecTest {
    private static final Instant UPDATED_AT = Instant.parse("2026-09-11T08:30:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-09-11T08:00:00Z");

    private final RpcJsonCodec codec = new RpcJsonCodec();

    @Test
    void moduleSnapshotSerializesWithCanonicalComponentNames() {
        var snapshot = new ModuleSnapshot(
                "demo",
                "Demo Module",
                options(null),
                Map.of("Player", "game.Player"),
                true,
                2
        );

        assertJson("""
                {
                  "moduleId": "demo",
                  "moduleName": "Demo Module",
                  "compileOptions": {
                    "godotVersion": "V451",
                    "projectPath": null,
                    "optimizationLevel": "DEBUG",
                    "targetPlatform": "LINUX_X86_64",
                    "strictMode": false,
                    "outputMountRoot": "/__build__"
                  },
                  "topLevelCanonicalNameMap": {"Player": "game.Player"},
                  "hasLastCompileResult": true,
                  "rootEntryCount": 2
                }
                """, codec.toJsonTree(snapshot));
    }

    @Test
    void compileOptionsSerializeNullProjectPathAndRoundTrip() {
        var withNullPath = options(null);
        var withHostPath = options(Path.of("/tmp/gdcc-project"));

        // serializeNulls keeps the component present so GDScript Dictionary consumers see a
        // stable shape; Path crosses the wire as plain host text.
        assertJson("""
                {
                  "godotVersion": "V451",
                  "projectPath": null,
                  "optimizationLevel": "DEBUG",
                  "targetPlatform": "LINUX_X86_64",
                  "strictMode": false,
                  "outputMountRoot": "/__build__"
                }
                """, codec.toJsonTree(withNullPath));
        assertEquals(
                "/tmp/gdcc-project",
                codec.toJsonTree(withHostPath).getAsJsonObject().get("projectPath").getAsString()
        );
        assertEquals(withNullPath, codec.bindParams(codec.toJsonTree(withNullPath), CompileOptions.class));
        assertEquals(withHostPath, codec.bindParams(codec.toJsonTree(withHostPath), CompileOptions.class));
    }

    @Test
    void compileResultSerializesAllComponentsWithoutDerivedMethods() {
        var result = new CompileResult(
                CompileResult.Outcome.SUCCESS,
                options(Path.of("/tmp/gdcc-project")),
                Map.of("Player", "game.Player"),
                List.of("/src/main.gd"),
                new DiagnosticSnapshot(List.of()),
                null,
                "",
                List.of(Path.of("/tmp/gdcc-project/entry.c")),
                List.of(Path.of("/tmp/gdcc-project/libdemo.so")),
                List.of(new VfsEntrySnapshot.LinkEntrySnapshot(
                        "/__build__/libdemo.so", "libdemo.so",
                        VfsEntrySnapshot.LinkKind.LOCAL, "/tmp/gdcc-project/libdemo.so", null
                ))
        );

        var json = codec.toJsonTree(result).getAsJsonObject();

        assertJson("""
                {
                  "outcome": "SUCCESS",
                  "compileOptions": {
                    "godotVersion": "V451",
                    "projectPath": "/tmp/gdcc-project",
                    "optimizationLevel": "DEBUG",
                    "targetPlatform": "LINUX_X86_64",
                    "strictMode": false,
                    "outputMountRoot": "/__build__"
                  },
                  "topLevelCanonicalNameMap": {"Player": "game.Player"},
                  "sourcePaths": ["/src/main.gd"],
                  "diagnostics": {"diagnostics": []},
                  "failureMessage": null,
                  "buildLog": "",
                  "generatedFiles": ["/tmp/gdcc-project/entry.c"],
                  "artifacts": ["/tmp/gdcc-project/libdemo.so"],
                  "outputLinks": [{
                    "kind": "LINK",
                    "path": "/__build__/libdemo.so",
                    "virtualPath": "/__build__/libdemo.so",
                    "name": "libdemo.so",
                    "linkKind": "LOCAL",
                    "target": "/tmp/gdcc-project/libdemo.so",
                    "brokenReason": null
                  }]
                }
                """, json);
        // Derived record methods are not components and must never leak onto the wire.
        assertFalse(json.has("success"));
    }

    @Test
    void compileTaskSnapshotSerializesNullCompletionMembers() {
        var snapshot = new CompileTaskSnapshot(
                7,
                "demo",
                CompileTaskSnapshot.State.RUNNING,
                CompileTaskSnapshot.Stage.PARSING,
                "Parsing main.gd",
                1,
                3,
                "/src/main.gd",
                2,
                CREATED_AT,
                null,
                null
        );

        assertJson("""
                {
                  "taskId": 7,
                  "moduleId": "demo",
                  "state": "RUNNING",
                  "stage": "PARSING",
                  "stageMessage": "Parsing main.gd",
                  "completedUnits": 1,
                  "totalUnits": 3,
                  "currentSourcePath": "/src/main.gd",
                  "revision": 2,
                  "createdAt": "2026-09-11T08:00:00Z",
                  "completedAt": null,
                  "result": null
                }
                """, codec.toJsonTree(snapshot));
    }

    @Test
    void analysisResultSerializesAllComponentsWithoutDerivedMethods() {
        var result = new AnalysisResult(
                AnalysisResult.Outcome.COMPLETED,
                new AnalyzeOptions(false),
                GodotVersion.V451,
                Map.of(),
                List.of("/src/main.gd"),
                new DiagnosticSnapshot(List.of()),
                null,
                AnalysisResult.LoweringStatus.NOT_REQUESTED
        );

        var json = codec.toJsonTree(result).getAsJsonObject();

        assertJson("""
                {
                  "outcome": "COMPLETED",
                  "analyzeOptions": {"includeLowering": false},
                  "godotVersion": "V451",
                  "topLevelCanonicalNameMap": {},
                  "sourcePaths": ["/src/main.gd"],
                  "diagnostics": {"diagnostics": []},
                  "failureMessage": null,
                  "loweringStatus": "NOT_REQUESTED"
                }
                """, json);
        assertFalse(json.has("completed"));
        assertFalse(json.has("hasErrors"));
    }

    @Test
    void diagnosticSnapshotSerializesWithAndWithoutRange() {
        var withRange = new DiagnosticSnapshot(List.of(new FrontendDiagnostic(
                FrontendDiagnosticSeverity.ERROR,
                "sema.class_skeleton",
                "Duplicate top-level class source name 'A'",
                "/src/a.gd",
                new FrontendRange(0, 12, new FrontendPoint(1, 1), new FrontendPoint(1, 13))
        )));
        var withoutRange = new DiagnosticSnapshot(List.of(new FrontendDiagnostic(
                FrontendDiagnosticSeverity.WARNING,
                "sema.redundant_await",
                "redundant await",
                null,
                null
        )));

        assertJson("""
                {"diagnostics": [{
                  "severity": "ERROR",
                  "category": "sema.class_skeleton",
                  "message": "Duplicate top-level class source name 'A'",
                  "sourcePath": "/src/a.gd",
                  "range": {
                    "startByte": 0,
                    "endByte": 12,
                    "start": {"line": 1, "column": 1},
                    "end": {"line": 1, "column": 13}
                  }
                }]}
                """, codec.toJsonTree(withRange));
        assertJson("""
                {"diagnostics": [{
                  "severity": "WARNING",
                  "category": "sema.redundant_await",
                  "message": "redundant await",
                  "sourcePath": null,
                  "range": null
                }]}
                """, codec.toJsonTree(withoutRange));
    }

    @Test
    void directoryEntrySnapshotSerializesFullShape() {
        var directory = new VfsEntrySnapshot.DirectoryEntrySnapshot("/src", "src", 3);

        assertJson("""
                {
                  "kind": "DIRECTORY",
                  "path": "/src",
                  "virtualPath": "/src",
                  "name": "src",
                  "childCount": 3
                }
                """, codec.toJsonTree(directory));
    }

    @Test
    void fileEntrySnapshotSerializesFullShapeWithDisplayPathAsPath() {
        var file = new VfsEntrySnapshot.FileEntrySnapshot(
                "/src/main.gd", "res://main.gd", "main.gd", 12, UPDATED_AT
        );

        // `path` is the display path for files, never the plain virtual path.
        assertJson("""
                {
                  "kind": "FILE",
                  "path": "res://main.gd",
                  "virtualPath": "/src/main.gd",
                  "name": "main.gd",
                  "displayPath": "res://main.gd",
                  "byteCount": 12,
                  "updatedAt": "2026-09-11T08:30:00Z"
                }
                """, codec.toJsonTree(file));
    }

    @Test
    void linkEntrySnapshotSerializesFullShapeForIntactAndBrokenLinks() {
        var intact = new VfsEntrySnapshot.LinkEntrySnapshot(
                "/out", "out", VfsEntrySnapshot.LinkKind.VIRTUAL, "/__build__/out", null
        );
        var broken = new VfsEntrySnapshot.LinkEntrySnapshot(
                "/dangling", "dangling", VfsEntrySnapshot.LinkKind.VIRTUAL,
                "/missing", VfsEntrySnapshot.BrokenReason.MISSING_TARGET
        );

        assertJson("""
                {
                  "kind": "LINK",
                  "path": "/out",
                  "virtualPath": "/out",
                  "name": "out",
                  "linkKind": "VIRTUAL",
                  "target": "/__build__/out",
                  "brokenReason": null
                }
                """, codec.toJsonTree(intact));
        assertJson("""
                {
                  "kind": "LINK",
                  "path": "/dangling",
                  "virtualPath": "/dangling",
                  "name": "dangling",
                  "linkKind": "VIRTUAL",
                  "target": "/missing",
                  "brokenReason": "MISSING_TARGET"
                }
                """, codec.toJsonTree(broken));
        // `broken()` is a derived method and must not appear on the wire.
        assertFalse(codec.toJsonTree(intact).getAsJsonObject().has("broken"));
    }

    @Test
    void compileTaskEventIndexedSerializesExactShape() {
        var indexed = new CompileTaskEvent.Indexed(5, new CompileTaskEvent("stage", "Parsing main.gd"));

        assertJson("""
                {
                  "index": 5,
                  "event": {"category": "stage", "detail": "Parsing main.gd"}
                }
                """, codec.toJsonTree(indexed));
    }

    @Test
    void instantSerializesAsIso8601UtcString() {
        assertEquals(
                "\"2026-09-11T08:30:00Z\"",
                codec.toJsonTree(UPDATED_AT).toString()
        );
    }

    @Test
    void nullResultSerializesAsJsonNull() {
        // `compile.clearEvents` and an empty `compile.getLatestEvent` both return a `null` result.
        assertInstanceOf(JsonNull.class, codec.toJsonTree(null));
    }

    @Test
    void enumsSerializeWithPinnedWireNames() {
        // Wire values are the Java `name()`s, deliberately unlike the CLI argument spellings.
        assertEnumWireValues(
                GodotVersion.class,
                Map.of(GodotVersion.V451, "V451")
        );
        assertEnumWireValues(
                COptimizationLevel.class,
                Map.of(COptimizationLevel.DEBUG, "DEBUG", COptimizationLevel.RELEASE, "RELEASE")
        );
        assertEnumWireValues(
                TargetPlatform.class,
                Map.of(
                        TargetPlatform.WINDOWS_X86_64, "WINDOWS_X86_64",
                        TargetPlatform.WINDOWS_AARCH64, "WINDOWS_AARCH64",
                        TargetPlatform.LINUX_X86_64, "LINUX_X86_64",
                        TargetPlatform.LINUX_AARCH64, "LINUX_AARCH64",
                        TargetPlatform.LINUX_RISCV64, "LINUX_RISCV64",
                        TargetPlatform.ANDROID_X86_64, "ANDROID_X86_64",
                        TargetPlatform.ANDROID_AARCH64, "ANDROID_AARCH64",
                        TargetPlatform.WEB_WASM32, "WEB_WASM32"
                )
        );
        assertEnumWireValues(
                VfsEntrySnapshot.Kind.class,
                Map.of(
                        VfsEntrySnapshot.Kind.DIRECTORY, "DIRECTORY",
                        VfsEntrySnapshot.Kind.FILE, "FILE",
                        VfsEntrySnapshot.Kind.LINK, "LINK"
                )
        );
        assertEnumWireValues(
                VfsEntrySnapshot.LinkKind.class,
                Map.of(
                        VfsEntrySnapshot.LinkKind.VIRTUAL, "VIRTUAL",
                        VfsEntrySnapshot.LinkKind.LOCAL, "LOCAL"
                )
        );
        assertEnumWireValues(
                VfsEntrySnapshot.BrokenReason.class,
                Map.of(
                        VfsEntrySnapshot.BrokenReason.MISSING_TARGET, "MISSING_TARGET",
                        VfsEntrySnapshot.BrokenReason.CYCLE, "CYCLE"
                )
        );
        assertEnumWireValues(
                CompileResult.Outcome.class,
                Map.of(
                        CompileResult.Outcome.SUCCESS, "SUCCESS",
                        CompileResult.Outcome.CANCELED, "CANCELED",
                        CompileResult.Outcome.SOURCE_COLLECTION_FAILED, "SOURCE_COLLECTION_FAILED",
                        CompileResult.Outcome.CONFIGURATION_FAILED, "CONFIGURATION_FAILED",
                        CompileResult.Outcome.FRONTEND_FAILED, "FRONTEND_FAILED",
                        CompileResult.Outcome.BUILD_FAILED, "BUILD_FAILED"
                )
        );
        assertEnumWireValues(
                CompileTaskSnapshot.State.class,
                Map.of(
                        CompileTaskSnapshot.State.QUEUED, "QUEUED",
                        CompileTaskSnapshot.State.RUNNING, "RUNNING",
                        CompileTaskSnapshot.State.SUCCEEDED, "SUCCEEDED",
                        CompileTaskSnapshot.State.FAILED, "FAILED",
                        CompileTaskSnapshot.State.CANCELED, "CANCELED"
                )
        );
        assertEnumWireValues(
                CompileTaskSnapshot.Stage.class,
                Map.of(
                        CompileTaskSnapshot.Stage.QUEUED, "QUEUED",
                        CompileTaskSnapshot.Stage.FREEZING_INPUTS, "FREEZING_INPUTS",
                        CompileTaskSnapshot.Stage.COLLECTING_SOURCES, "COLLECTING_SOURCES",
                        CompileTaskSnapshot.Stage.PARSING, "PARSING",
                        CompileTaskSnapshot.Stage.LOWERING, "LOWERING",
                        CompileTaskSnapshot.Stage.CODEGEN_PREPARE, "CODEGEN_PREPARE",
                        CompileTaskSnapshot.Stage.BUILDING_NATIVE, "BUILDING_NATIVE",
                        CompileTaskSnapshot.Stage.FINISHED, "FINISHED"
                )
        );
        assertEnumWireValues(
                AnalysisResult.Outcome.class,
                Map.of(
                        AnalysisResult.Outcome.COMPLETED, "COMPLETED",
                        AnalysisResult.Outcome.SOURCE_COLLECTION_FAILED, "SOURCE_COLLECTION_FAILED",
                        AnalysisResult.Outcome.INTERNAL_FAILED, "INTERNAL_FAILED"
                )
        );
        assertEnumWireValues(
                AnalysisResult.LoweringStatus.class,
                Map.of(
                        AnalysisResult.LoweringStatus.NOT_REQUESTED, "NOT_REQUESTED",
                        AnalysisResult.LoweringStatus.SUCCEEDED, "SUCCEEDED",
                        AnalysisResult.LoweringStatus.FAILED, "FAILED"
                )
        );
        assertEnumWireValues(
                FrontendDiagnosticSeverity.class,
                Map.of(
                        FrontendDiagnosticSeverity.WARNING, "WARNING",
                        FrontendDiagnosticSeverity.ERROR, "ERROR"
                )
        );
    }

    @Test
    void bindParamsRejectsMissingRequiredComponents() {
        // Every required component is boxed, so a missing member binds as `null` and the compact
        // constructor rejects it (Gson would otherwise silently zero-fill a primitive).
        var missingModuleId = assertThrows(NullPointerException.class, () ->
                codec.bindParams(parse("{}"), RpcParams.VfsDeletePathParams.class));
        assertEquals("moduleId must not be null", missingModuleId.getMessage());

        var missingRecursive = assertThrows(NullPointerException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"path\": \"/src\"}"),
                        RpcParams.VfsDeletePathParams.class
                ));
        assertEquals("recursive must not be null", missingRecursive.getMessage());

        var missingTaskId = assertThrows(NullPointerException.class, () ->
                codec.bindParams(parse("{}"), RpcParams.CompileGetTaskParams.class));
        assertEquals("taskId must not be null", missingTaskId.getMessage());
    }

    @Test
    void bindParamsRejectsJsonNullRequiredComponents() {
        var exception = assertThrows(NullPointerException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": null, \"path\": \"/src\", \"recursive\": false}"),
                        RpcParams.VfsDeletePathParams.class
                ));
        assertEquals("moduleId must not be null", exception.getMessage());
    }

    @Test
    void bindParamsRejectsBlankRequiredStrings() {
        var exception = assertThrows(IllegalArgumentException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"   \"}"),
                        RpcParams.ModuleGetParams.class
                ));
        assertEquals("moduleId must not be blank", exception.getMessage());
    }

    @Test
    void bindParamsRejectsTypeMismatchedComponents() {
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(parse("{\"taskId\": \"abc\"}"), RpcParams.CompileGetTaskParams.class));
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"compileOptions\": \"not-an-object\"}"),
                        RpcParams.OptionsSetParams.class
                ));
    }

    @Test
    void bindParamsRejectsCoercibleButTypeWrongComponents() {
        // Gson's stock scalar adapters would silently coerce every one of these; the strict
        // adapters reject them all so callers get `-32602` instead of corrupted input.
        // JSON number → String component.
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(parse("{\"moduleId\": 123}"), RpcParams.ModuleGetParams.class));
        // Quoted number → Long component.
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(parse("{\"taskId\": \"7\"}"), RpcParams.CompileGetTaskParams.class));
        // String → Boolean component (`"true"` would coerce to true, `"garbage"` to false).
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"path\": \"/src\", \"recursive\": \"true\"}"),
                        RpcParams.VfsDeletePathParams.class
                ));
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"path\": \"/src\", \"recursive\": \"garbage\"}"),
                        RpcParams.VfsDeletePathParams.class
                ));
        // JSON number → Path component nested in CompileOptions.
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"compileOptions\": {"
                                + "\"godotVersion\": \"V451\", "
                                + "\"projectPath\": 123, "
                                + "\"optimizationLevel\": \"DEBUG\", "
                                + "\"targetPlatform\": \"LINUX_X86_64\", "
                                + "\"strictMode\": false, "
                                + "\"outputMountRoot\": \"/__build__\"}}"),
                        RpcParams.OptionsSetParams.class
                ));
        // JSON number → class map value.
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"topLevelCanonicalNameMap\": {\"Player\": 1}}"),
                        RpcParams.ClassMapSetParams.TYPE_TOKEN
                ));
    }

    @Test
    void bindParamsRejectsUnknownEnumNames() {
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"path\": \"/out\", "
                                + "\"linkKind\": \"SIDEWAYS\", \"target\": \"/build\"}"),
                        RpcParams.VfsCreateLinkParams.class
                ));
    }

    @Test
    void bindParamsTreatsJsonNullParamsAsEmptyObject() {
        // A JSON-`null` `params` member binds like `{}`, so required-component validation fires
        // instead of the whole record silently becoming `null`.
        var exception = assertThrows(NullPointerException.class, () ->
                codec.bindParams(JsonNull.INSTANCE, RpcParams.ModuleGetParams.class));
        assertEquals("moduleId must not be null", exception.getMessage());
    }

    @Test
    void bindParamsAppliesOptionalDefaults() {
        var listEvents = codec.bindParams(
                parse("{\"taskId\": 7}"),
                RpcParams.CompileListEventsParams.class
        );

        assertEquals(0, listEvents.startIndexOrDefault());
        assertEquals(API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE, listEvents.maxCountOrDefault());
        assertNull(listEvents.category());

        var analyze = codec.bindParams(
                parse("{\"moduleId\": \"demo\"}"),
                RpcParams.AnalyzeRunParams.class
        );

        assertFalse(analyze.includeLoweringOrDefault());

        var putFileWithoutDisplayPath = codec.bindParams(
                parse("{\"moduleId\": \"demo\", \"path\": \"/src/main.gd\", \"content\": \"extends Node\\n\"}"),
                RpcParams.VfsPutFileParams.class
        );

        assertNull(putFileWithoutDisplayPath.displayPath());
    }

    @Test
    void bindParamsBindsCompleteObjects() {
        var putFile = codec.bindParams(
                parse("{\"moduleId\": \"demo\", \"path\": \"/src/main.gd\", "
                        + "\"content\": \"extends Node\\n\", \"displayPath\": \"res://main.gd\"}"),
                RpcParams.VfsPutFileParams.class
        );

        assertEquals("demo", putFile.moduleId());
        assertEquals("/src/main.gd", putFile.path());
        assertEquals("extends Node\n", putFile.content());
        assertEquals("res://main.gd", putFile.displayPath());

        var classMap = codec.bindParams(
                parse("{\"moduleId\": \"demo\", \"topLevelCanonicalNameMap\": {\"Player\": \"game.Player\"}}"),
                RpcParams.ClassMapSetParams.TYPE_TOKEN
        );

        assertEquals(Map.of("Player", "game.Player"), classMap.topLevelCanonicalNameMap());

        var optionsSet = codec.bindParams(
                parse("{\"moduleId\": \"demo\", \"compileOptions\": " + codec.toJsonTree(options(null)) + "}"),
                RpcParams.OptionsSetParams.class
        );

        assertEquals(options(null), optionsSet.compileOptions());
    }

    @Test
    void bindParamsRejectsUnknownEnumInNestedCompileOptions() {
        assertThrows(JsonSyntaxException.class, () ->
                codec.bindParams(
                        parse("{\"moduleId\": \"demo\", \"compileOptions\": {"
                                + "\"godotVersion\": \"V460\", "
                                + "\"projectPath\": null, "
                                + "\"optimizationLevel\": \"DEBUG\", "
                                + "\"targetPlatform\": \"LINUX_X86_64\", "
                                + "\"strictMode\": false, "
                                + "\"outputMountRoot\": \"/__build__\"}}"),
                        RpcParams.OptionsSetParams.class
                ));
    }

    private static CompileOptions options(Path projectPath) {
        return new CompileOptions(
                GodotVersion.V451,
                projectPath,
                COptimizationLevel.DEBUG,
                TargetPlatform.LINUX_X86_64,
                false,
                "/__build__"
        );
    }

    private <E extends Enum<E>> void assertEnumWireValues(Class<E> enumType, Map<E, String> expected) {
        // The table must be complete: an enum constant missing from the expectation fails here.
        assertEquals(Set.copyOf(expected.keySet()), Set.of(enumType.getEnumConstants()));
        for (var entry : expected.entrySet()) {
            assertEquals(
                    "\"" + entry.getValue() + "\"",
                    codec.toJsonTree(entry.getKey()).toString()
            );
        }
    }

    private static void assertJson(String expected, JsonElement actual) {
        assertEquals(JsonParser.parseString(expected), actual);
    }

    private static com.google.gson.JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
