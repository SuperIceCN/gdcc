package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageBuffer;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageSession;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.lir.insn.VariantGetInsn;
import gd.script.gdcc.lir.insn.VariantSetInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdccCoroStateType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdRect2Type;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CCodegenTest {
    @Test
    public void variantGetOpcodeIsRegisteredAndGeneratesBody() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("index_load_codegen");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("self", GdVariantType.VARIANT);
        func.createAndAddVariable("key", GdVariantType.VARIANT);
        func.createAndAddVariable("result", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new VariantGetInsn("result", "self", "key"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("index_load_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_variant_get(&$self, &$key"), body);
        assertTrue(body.contains("$result = godot_new_Variant_with_Variant"), body);
    }

    @Test
    public void variantSetOpcodeIsRegisteredAndGeneratesBody() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("index_store_codegen");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("self", GdVariantType.VARIANT);
        func.createAndAddVariable("key", GdVariantType.VARIANT);
        func.createAndAddVariable("value", GdVariantType.VARIANT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new VariantSetInsn("self", "key", "value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("index_store_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var body = codegen.generateFuncBody(workerClass, func);
        assertTrue(body.contains("godot_variant_set(&$self, &$key, &$value"), body);
    }

    @Test
    public void binaryOperatorOpcodeIsRegisteredAndFailFastIsControlled() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("operator_fail_fast");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("left", GdIntType.INT);
        func.createAndAddVariable("right", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("operator_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, func));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Operator path is not implemented"));
    }

    @Test
    public void generatesEntryFiles() throws Exception {
        // build a simple LirModule
        var rotatingCameraClass = new LirClassDef("GDRotatingCamera3D", "Camera3D");
        rotatingCameraClass.addProperty(new LirPropertyDef("pitch_degree", GdFloatType.FLOAT));
        var module = new LirModule("my_module", List.of(rotatingCameraClass));

        // load extension API and class registry
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);

        // tiny ProjectInfo implementation for test
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        List<GeneratedFile> files = codegen.generate();

        assertEquals(4, files.size(), "Should produce four files");
        assertEquals(List.of("entry.c", "engine_method_binds.h", "object_fat_ptr_types.h", "entry.h"), files.stream().map(GeneratedFile::filePath).toList());

        var cCode = generatedFileText(files, "entry.c");
        var bindHeaderCode = generatedFileText(files, "engine_method_binds.h");
        var hCode = generatedFileText(files, "entry.h");
        assertTrue(cCode.contains("Loading my_module"));
        assertTrue(bindHeaderCode.contains("GDEXTENSION_MY_MODULE_ENGINE_METHOD_BINDS_H"));
        assertTrue(bindHeaderCode.contains("No module-local Godot wrappers were collected for this module."), bindHeaderCode);
        assertTrue(hCode.contains("GDEXTENSION_MY_MODULE_ENTRY_H"));
        assertTrue(hCode.contains("#include \"engine_method_binds.h\""));
    }

    @Test
    public void generatesValidHeaderGuardsForModuleNamesWithSpaces() throws Exception {
        // Display-oriented module names may contain spaces/punctuation; the include guards must be
        // sanitized C identifiers, otherwise every generated header shares the same first #ifndef
        // identifier and later headers are silently skipped by the preprocessor.
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var module = new LirModule("Compile Demo", List.of(workerClass));

        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        List<GeneratedFile> files = codegen.generate();

        var bindHeaderCode = generatedFileText(files, "engine_method_binds.h");
        var fatPtrHeaderCode = generatedFileText(files, "object_fat_ptr_types.h");
        var hCode = generatedFileText(files, "entry.h");
        assertTrue(bindHeaderCode.contains("#ifndef GDEXTENSION_COMPILE_DEMO_ENGINE_METHOD_BINDS_H"));
        assertTrue(fatPtrHeaderCode.contains("#ifndef GDEXTENSION_COMPILE_DEMO_OBJECT_FAT_PTR_TYPES_H"));
        assertTrue(hCode.contains("#ifndef GDEXTENSION_COMPILE_DEMO_ENTRY_H"));
        // The spaced raw form must not survive anywhere in the guards.
        assertFalse(hCode.contains("#ifndef GDEXTENSION_COMPILE DEMO_ENTRY_H"));
    }

    @Test
    public void generateShouldRejectCompilerOnlyTypeOnHiddenFunctionReturnBeforeBackendSynthesis() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var helper = new LirFunctionDef("helper");
        helper.setHidden(true);
        helper.setReturnType(GdccForRangeIterType.FOR_RANGE_ITER);
        var entry = new LirBasicBlock("entry");
        entry.setTerminator(new ReturnInsn(null));
        helper.addBasicBlock(entry);
        helper.setEntryBlockId("entry");
        workerClass.addFunction(helper);

        var module = new LirModule("compiler_only_hidden_return", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalArgumentException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("compiler-only type leaked into function return"), exception.getMessage());
        assertFalse(exception.getMessage().contains("dedicated prepare initialization"), exception.getMessage());
        assertFalse(exception.getMessage().contains("property initializer"), exception.getMessage());
        assertFalse(exception.getMessage().contains("outward GDExtension metadata"), exception.getMessage());
    }

    @Test
    public void generateShouldRejectCompilerOnlyPropertyBeforeDefaultInitSynthesis() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(new LirPropertyDef("iter", GdccForRangeIterType.FOR_RANGE_ITER));

        var module = new LirModule("compiler_only_property", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalArgumentException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("compiler-only type leaked into property"), exception.getMessage());
        assertFalse(exception.getMessage().contains("property initializer"), exception.getMessage());
        assertFalse(exception.getMessage().contains("outward GDExtension metadata"), exception.getMessage());
    }

    @Test
    public void generateShouldEmitCompilerOnlyPrepareInitCallForLocalVariables() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("prepare_compiler_only_local");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);
        var entryBlock = new LirBasicBlock("entry");
        entryBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entryBlock);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("compiler_only_prepare", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");

        assertTrue(cCode.contains("gdcc_for_range_iter $iter;"), cCode);
        assertTrue(cCode.contains("$iter = gdcc_for_range_iter_init();"), cCode);
        assertFalse(cCode.contains("godot_new_GdccForRangeIter"), cCode);
    }

    @Test
    public void generateShouldEmitCoroStatePrepareInitCallForLocalVariables() {
        // Coroutine state locals use the godot_Object* storage with the call-and-assign slot
        // init helper; no fake godot_* constructor must appear.
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("prepare_coro_state_local");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        var entryBlock = new LirBasicBlock("entry");
        entryBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entryBlock);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("coro_state_prepare", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");

        assertTrue(cCode.contains("godot_Object* $state;"), cCode);
        assertTrue(cCode.contains("$state = gdcc_coro_state_slot_init();"), cCode);
        assertFalse(cCode.contains("godot_new_GdccCoroState"), cCode);
    }

    @Test
    public void generateShouldKeepCompilerOnlyPrepareInitOnExplicitHelperPath() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("prepare_compiler_only_local_again");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);
        var entryBlock = new LirBasicBlock("entry");
        entryBlock.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entryBlock);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("compiler_only_prepare_again", List.of(workerClass));
        var classRegistry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        ));
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");

        assertTrue(cCode.contains("$iter = gdcc_for_range_iter_init();"), cCode);
        assertFalse(cCode.contains("ConstructBuiltinInsn"), cCode);
        assertFalse(cCode.contains("godot_new_GdccForRangeIter"), cCode);
    }

    @Test
    public void entryTemplateShouldSetMinimumInitializationLevelAndGuardLifecycleLevels() throws Exception {
        var workerClass = new LirClassDef("GDEntryWorker", "RefCounted");
        var module = new LirModule("entry_contract_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        var entryBody = resolveFunctionBodyByPrefix(cCode, "GDE_EXPORT GDExtensionBool gdextension_entry(");
        assertContainsAll(
                entryBody,
                "if (!godot_initialize_interface(p_get_proc_address)) {",
                "return false;",
                "r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE;",
                "r_initialization->userdata = NULL;",
                "r_initialization->initialize = &initialize;",
                "r_initialization->deinitialize = &deinitialize;"
        );
        assertTrue(
                entryBody.indexOf("godot_initialize_interface(p_get_proc_address)") <
                        entryBody.indexOf("class_library = p_library;"),
                entryBody
        );
        assertTrue(hCode.contains("#include <godot_binding.h>"), hCode);

        var initializeBody = resolveFunctionBodyByPrefix(cCode, "void initialize(void* userdata");
        assertContainsAll(
                initializeBody,
                "(void)userdata;",
                "if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {",
                "return;",
                "gdcc_init();"
        );
        assertTrue(
                initializeBody.indexOf("if (p_level != GDEXTENSION_INITIALIZATION_SCENE)") <
                        initializeBody.indexOf("gdcc_init();"),
                initializeBody
        );

        var deinitializeBody = resolveFunctionBodyByPrefix(cCode, "void deinitialize(void* userdata");
        assertContainsAll(
                deinitializeBody,
                "(void)userdata;",
                "if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {",
                "return;",
                "gdcc_sn_registry_destroy_all();",
                "gdcc_s_registry_destroy_all();",
                "gdcc_standalone_callable_registry_destroy_all();"
        );
        assertTrue(
                deinitializeBody.indexOf("if (p_level != GDEXTENSION_INITIALIZATION_SCENE)") <
                        deinitializeBody.indexOf("gdcc_sn_registry_destroy_all();"),
                deinitializeBody
        );
        assertTrue(
                deinitializeBody.indexOf("gdcc_s_registry_destroy_all();") <
                        deinitializeBody.indexOf("gdcc_standalone_callable_registry_destroy_all();"),
                deinitializeBody
        );
    }

    @Test
    public void generateShouldUseGeneratedFilePathsAndCollectExactEngineHelpersOncePerSession() {
        var workerClass = new LirClassDef("EngineUsageWorker", "RefCounted");
        var peerClass = new LirClassDef("EngineUsagePeer", "RefCounted");

        var ping = newFunction("ping", GdVoidType.VOID);
        ping.addParameter(new LirParameterDef("self", new GdObjectType("EngineUsagePeer"), null, ping));
        entry(ping).setTerminator(new ReturnInsn(null));
        peerClass.addFunction(ping);

        var queueFreeA = newFunction("call_queue_free_a", GdVoidType.VOID);
        queueFreeA.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, queueFreeA));
        entry(queueFreeA).appendInstruction(new CallMethodInsn(null, "queue_free", "node", List.of()));
        entry(queueFreeA).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(queueFreeA);

        var queueFreeB = newFunction("call_queue_free_b", GdVoidType.VOID);
        queueFreeB.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, queueFreeB));
        entry(queueFreeB).appendInstruction(new CallMethodInsn(null, "queue_free", "node", List.of()));
        entry(queueFreeB).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(queueFreeB);

        var staticMake = newFunction("call_static_make", GdVoidType.VOID);
        staticMake.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, staticMake));
        staticMake.createAndAddVariable("made", new GdObjectType("Node"));
        entry(staticMake).appendInstruction(new CallMethodInsn("made", "make", "node", List.of()));
        entry(staticMake).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(staticMake);

        var varargCall = newFunction("call_vararg", GdVoidType.VOID);
        varargCall.addParameter(new LirParameterDef("obj", new GdObjectType("Object"), null, varargCall));
        varargCall.createAndAddVariable("dispatch", GdStringNameType.STRING_NAME);
        varargCall.createAndAddVariable("tail", GdVariantType.VARIANT);
        varargCall.createAndAddVariable("result", GdVariantType.VARIANT);
        entry(varargCall).appendInstruction(new CallMethodInsn(
                "result",
                "call",
                "obj",
                List.of(varOperand("dispatch"), varOperand("tail"))
        ));
        entry(varargCall).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(varargCall);

        var builtinCall = newFunction("call_builtin", GdVoidType.VOID);
        builtinCall.createAndAddVariable("array", new GdArrayType(GdVariantType.VARIANT));
        builtinCall.createAndAddVariable("size", GdIntType.INT);
        entry(builtinCall).appendInstruction(new CallMethodInsn("size", "size", "array", List.of()));
        entry(builtinCall).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(builtinCall);

        var gdccCall = newFunction("call_gdcc", GdVoidType.VOID);
        gdccCall.addParameter(new LirParameterDef("peer", new GdObjectType("EngineUsagePeer"), null, gdccCall));
        entry(gdccCall).appendInstruction(new CallMethodInsn(null, "ping", "peer", List.of()));
        entry(gdccCall).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(gdccCall);

        var module = new LirModule("engine_helper_generation_module", List.of(workerClass, peerClass));
        var api = engineHelperApi();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        // The public single-function renderer is intentionally side-effect free for module helper usage.
        var queueFreePreview = codegen.generateFuncBody(workerClass, queueFreeA);
        assertTrue(queueFreePreview.contains("gdcc_engine_call_node_queue_free_P_RV"), queueFreePreview);

        var files = codegen.generate();
        var filePaths = files.stream().map(GeneratedFile::filePath).toList();
        var entrySource = generatedFileText(files, "entry.c");
        var bindHeaderCode = generatedFileText(files, "engine_method_binds.h");
        var hCode = generatedFileText(files, "entry.h");

        assertEquals(List.of("entry.c", "engine_method_binds.h", "object_fat_ptr_types.h", "entry.h"), filePaths);
        assertTrue(hCode.contains("#include \"engine_method_binds.h\""), hCode);
        assertContainsAll(
                bindHeaderCode,
                "gdcc_engine_method_bind_node_queue_free_P_RV(",
                "gdcc_engine_call_node_queue_free_P_RV(",
                "gdcc_engine_method_bind_static_node_make_P_RL4Node_(",
                "gdcc_engine_call_static_node_make_P_RL4Node_(",
                "gdcc_engine_method_bind_object_call_PS_RR_Xv(",
                "gdcc_engine_callv_object_call_PS_RR_Xv("
        );
        assertContainsAll(
                entrySource,
                "gdcc_engine_call_node_queue_free_P_RV(",
                "gdcc_engine_call_static_node_make_P_RL4Node_()",
                "gdcc_engine_callv_object_call_PS_RR_Xv("
        );
        assertFalse(bindHeaderCode.contains("gdcc_engine_method_bind_array_size_"), bindHeaderCode);
        assertFalse(bindHeaderCode.contains("gdcc_engine_method_bind_engineusagepeer_ping_"), bindHeaderCode);
        assertFalse(bindHeaderCode.contains("gdcc_engine_method_bind_variant_callv_"), bindHeaderCode);
        assertEquals(
                1,
                countOccurrences(bindHeaderCode, "gdcc_engine_method_bind_node_queue_free_P_RV,"),
                bindHeaderCode
        );
        assertEquals(
                1,
                countOccurrences(bindHeaderCode, "static inline void gdcc_engine_call_node_queue_free_P_RV("),
                bindHeaderCode
        );
        assertEquals(
                1,
                countOccurrences(bindHeaderCode, "gdcc_engine_method_bind_object_call_PS_RR_Xv,"),
                bindHeaderCode
        );
        assertTrue(bindHeaderCode.contains("GDCC_DEFINE_ENGINE_METHOD_BIND_ACCESSOR("), bindHeaderCode);
    }

    @Test
    public void generateShouldEmitOnDemandEngineConstructorWrappersInBindHeader() throws Exception {
        var workerClass = new LirClassDef("ConstructorUsageWorker", "RefCounted");
        var constructNode = newFunction("construct_node", GdVoidType.VOID);
        constructNode.createAndAddVariable("node", new GdObjectType("Node"));
        entry(constructNode).appendInstruction(new ConstructObjectInsn("node", "Node"));
        entry(constructNode).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(constructNode);

        var module = new LirModule("engine_constructor_usage_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var files = codegen.generate();
        var entrySource = generatedFileText(files, "entry.c");
        var bindHeaderCode = generatedFileText(files, "engine_method_binds.h");

        assertEquals(List.of("entry.c", "engine_method_binds.h", "object_fat_ptr_types.h", "entry.h"), files.stream().map(GeneratedFile::filePath).toList());
        assertTrue(
                entrySource.contains("$node = gdcc_Node_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_new_Node()));"),
                entrySource
        );
        assertContainsAll(
                bindHeaderCode,
                "static inline godot_Node *godot_new_Node(void)",
                "godot_classdb_construct_object(GD_STATIC_SN(u8\"Node\"))",
                "gdcc_binding_lookup_context context = { 0 };",
                "context.kind = \"engine_constructor\";",
                "context.function_name = \"godot_new_Node\";",
                "return (godot_Node *)object;",
                "No exact engine method binds were collected for this module."
        );
        assertFalse(bindHeaderCode.contains("gdcc_binding_lookup_fail(&(gdcc_binding_lookup_context){"), bindHeaderCode);
        assertFalse(bindHeaderCode.contains("\n                .kind = \"engine_constructor\""), bindHeaderCode);
        assertFalse(bindHeaderCode.contains("godot_new_RefCounted(void)"), bindHeaderCode);
        assertFalse(bindHeaderCode.contains("godot_classdb_construct_object2"), bindHeaderCode);
        assertFalse(bindHeaderCode.contains("godot_new_StringName_with_latin1_chars"), bindHeaderCode);
        assertFalse(bindHeaderCode.contains("godot_StringName_destroy"), bindHeaderCode);
    }

    @Test
    public void generateShouldOnlyRegisterStrictEngineVirtualOverridesInVirtualDispatchTables() throws Exception {
        var validClass = new LirClassDef("ValidVirtualDispatch", "Node");
        var ready = newFunction("_ready", GdVoidType.VOID);
        entry(ready).setTerminator(new ReturnInsn(null));
        validClass.addFunction(ready);
        var process = newFunction("_process", GdVoidType.VOID);
        process.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, process));
        entry(process).setTerminator(new ReturnInsn(null));
        validClass.addFunction(process);

        var invalidClass = new LirClassDef("InvalidVirtualDispatch", "Node");
        var invalidReady = newFunction("_ready", GdVoidType.VOID);
        invalidReady.setStatic(true);
        entry(invalidReady).setTerminator(new ReturnInsn(null));
        invalidClass.addFunction(invalidReady);
        var invalidProcess = newFunction("_process", GdVoidType.VOID);
        entry(invalidProcess).setTerminator(new ReturnInsn(null));
        invalidClass.addFunction(invalidProcess);

        var module = new LirModule("virtual_dispatch_module", List.of(validClass, invalidClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var files = codegen.generate();
        var entrySource = generatedFileText(files, "entry.c");

        var validLookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* ValidVirtualDispatch_class_get_virtual_with_data("
        );
        assertContainsAll(
                validLookupBody,
                "(void)p_class_userdata;",
                "(void)p_hash;",
                "return (void*)ValidVirtualDispatch__ready;",
                "return (void*)ValidVirtualDispatch__process;"
        );
        var validDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void ValidVirtualDispatch_class_call_virtual_with_data("
        );
        assertContainsAll(
                validDispatchBody,
                "&ValidVirtualDispatch__ready",
                "&ValidVirtualDispatch__process"
        );

        var invalidLookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* InvalidVirtualDispatch_class_get_virtual_with_data("
        );
        assertFalse(invalidLookupBody.contains("InvalidVirtualDispatch__ready"), invalidLookupBody);
        assertFalse(invalidLookupBody.contains("InvalidVirtualDispatch__process"), invalidLookupBody);
        var invalidDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void InvalidVirtualDispatch_class_call_virtual_with_data("
        );
        assertFalse(invalidDispatchBody.contains("InvalidVirtualDispatch__ready"), invalidDispatchBody);
        assertFalse(invalidDispatchBody.contains("InvalidVirtualDispatch__process"), invalidDispatchBody);
    }

    /// GDExtension has no tool-script registration flag, so non-tool classes gate the frame-loop
    /// virtuals (`_process` / `_physics_process`) behind `gdcc_is_editor_hint()` inside their
    /// userdata-matched dispatch branches; other virtuals (`_ready`) stay ungated.
    @Test
    public void generateShouldGateFrameLoopVirtualsForNonToolClassInEditor() throws Exception {
        var workerClass = new LirClassDef("FrameLoopGateWorker", "Node");
        var process = newFunction("_process", GdVoidType.VOID);
        process.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, process));
        entry(process).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(process);
        var physicsProcess = newFunction("_physics_process", GdVoidType.VOID);
        physicsProcess.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, physicsProcess));
        entry(physicsProcess).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(physicsProcess);
        var ready = newFunction("_ready", GdVoidType.VOID);
        entry(ready).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(ready);

        var module = new LirModule("frame_loop_gate_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var entrySource = generatedFileText(codegen.generate(), "entry.c");

        var dispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void FrameLoopGateWorker_class_call_virtual_with_data("
        );
        // The generated C passes through CCodeFormatter, so the gate block is asserted as ordered
        // fragments rather than whitespace-exact text.
        var processBranch = resolveVirtualDispatchBranch(dispatchBody, "FrameLoopGateWorker__process");
        assertOrdered(processBranch, "if (gdcc_is_editor_hint()) {", "return;", "}", "ptrcall");
        var physicsBranch = resolveVirtualDispatchBranch(dispatchBody, "FrameLoopGateWorker__physics_process");
        assertOrdered(physicsBranch, "if (gdcc_is_editor_hint()) {", "return;", "}", "ptrcall");
        var readyBranch = resolveVirtualDispatchBranch(dispatchBody, "FrameLoopGateWorker__ready");
        assertContainsAll(readyBranch, "ptrcall");
        assertFalse(readyBranch.contains("gdcc_is_editor_hint"), readyBranch);

        // The lookup side never gates: it only resolves userdata for the engine.
        var lookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* FrameLoopGateWorker_class_get_virtual_with_data("
        );
        assertFalse(lookupBody.contains("gdcc_is_editor_hint"), lookupBody);
    }

    /// The frame-loop gate also applies when the override carries default slots: the gate lives
    /// inside the default-userdata-matched branch, before the defslot ptrcall flavor.
    @Test
    public void generateShouldGateFrameLoopVirtualWithDefaultUserdataInEditor() throws Exception {
        var workerClass = new LirClassDef("FrameLoopGateDefault", "Node");

        var shell = new LirFunctionDef("_default__process$delta");
        shell.setHidden(true);
        shell.setReturnType(GdFloatType.FLOAT);
        shell.addParameter(new LirParameterDef("self", new GdObjectType("FrameLoopGateDefault"), null, shell));
        var shellResult = shell.createAndAddTmpVariable(GdFloatType.FLOAT);
        var shellEntry = new LirBasicBlock("entry");
        shellEntry.appendInstruction(new LiteralFloatInsn(shellResult.id(), 0.0));
        shellEntry.setTerminator(new ReturnInsn(shellResult.id()));
        shell.addBasicBlock(shellEntry);
        shell.setEntryBlockId("entry");
        workerClass.addFunction(shell);

        var process = new LirFunctionDef("_process");
        process.setReturnType(GdVoidType.VOID);
        process.addParameter(new LirParameterDef("self", new GdObjectType("FrameLoopGateDefault"), null, process));
        process.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, "_default__process$delta", process));
        var processEntry = new LirBasicBlock("entry");
        processEntry.setTerminator(new ReturnInsn(null));
        process.addBasicBlock(processEntry);
        process.setEntryBlockId("entry");
        workerClass.addFunction(process);

        var module = new LirModule("frame_loop_gate_default_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var entrySource = generatedFileText(codegen.generate(), "entry.c");

        var dispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void FrameLoopGateDefault_class_call_virtual_with_data("
        );
        var processBranch = resolveVirtualDispatchBranch(
                dispatchBody,
                "FrameLoopGateDefault__process$default_ud"
        );
        assertOrdered(
                processBranch,
                "if (gdcc_is_editor_hint()) {",
                "return;",
                "}",
                "ptrcall_FrameLoopGateDefault_1_arg_float_no_ret_1_defslot("
        );
    }

    /// Tool classes (including inner classes of a `@tool` script, which the frontend marks with
    /// the same propagated flag) emit no editor gate at all: frame-loop virtuals run in the
    /// editor, matching Godot's `@tool` semantics.
    @Test
    public void generateShouldNotGateFrameLoopVirtualsForToolClass() throws Exception {
        var toolClass = new LirClassDef("ToolFrameLoopWorker", "Node");
        toolClass.setTool(true);
        var process = newFunction("_process", GdVoidType.VOID);
        process.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, process));
        entry(process).setTerminator(new ReturnInsn(null));
        toolClass.addFunction(process);

        var toolInnerClass = new LirClassDef("ToolFrameLoopWorker__sub__Inner", "Node");
        toolInnerClass.setTool(true);
        var innerPhysics = newFunction("_physics_process", GdVoidType.VOID);
        innerPhysics.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, innerPhysics));
        entry(innerPhysics).setTerminator(new ReturnInsn(null));
        toolInnerClass.addFunction(innerPhysics);

        var module = new LirModule("tool_frame_loop_module", List.of(toolClass, toolInnerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var entrySource = generatedFileText(codegen.generate(), "entry.c");

        var toolDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void ToolFrameLoopWorker_class_call_virtual_with_data("
        );
        assertContainsAll(toolDispatchBody, "ptrcall");
        assertFalse(toolDispatchBody.contains("gdcc_is_editor_hint"), toolDispatchBody);
        var innerDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void ToolFrameLoopWorker__sub__Inner_class_call_virtual_with_data("
        );
        assertContainsAll(innerDispatchBody, "ptrcall");
        assertFalse(innerDispatchBody.contains("gdcc_is_editor_hint"), innerDispatchBody);
    }

    /// A subclass with a GDCC parent must fall through to the parent callbacks when
    /// no own override matches — the engine only ever invokes the most-derived instance's
    /// registered callbacks and never walks extension parents itself. The parent (engine super)
    /// keeps the plain `return NULL;` tail, and the child forwarding trails every own branch so
    /// editor gates remain inside their own-hit branches.
    @Test
    public void generateShouldForwardVirtualCallbacksToGdccParent() throws Exception {
        var parentClass = new LirClassDef("VirtualFwdParent", "Node");
        var parentReady = newFunction("_ready", GdVoidType.VOID);
        entry(parentReady).setTerminator(new ReturnInsn(null));
        parentClass.addFunction(parentReady);

        var childClass = new LirClassDef("VirtualFwdChild", "VirtualFwdParent");
        var childProcess = newFunction("_process", GdVoidType.VOID);
        childProcess.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, null, childProcess));
        entry(childProcess).setTerminator(new ReturnInsn(null));
        childClass.addFunction(childProcess);

        var module = new LirModule("virtual_forward_module", List.of(parentClass, childClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var entrySource = generatedFileText(codegen.generate(), "entry.c");

        var childLookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* VirtualFwdChild_class_get_virtual_with_data("
        );
        assertOrdered(
                childLookupBody,
                "return (void*)VirtualFwdChild__process;",
                "return VirtualFwdParent_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);"
        );
        assertFalse(childLookupBody.contains("return NULL;"), childLookupBody);

        var childDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void VirtualFwdChild_class_call_virtual_with_data("
        );
        var processBranch = resolveVirtualDispatchBranch(childDispatchBody, "VirtualFwdChild__process");
        assertOrdered(processBranch, "if (gdcc_is_editor_hint()) {", "return;", "}", "ptrcall");
        assertOrdered(
                childDispatchBody,
                "ptrcall",
                "VirtualFwdParent_class_call_virtual_with_data(p_instance, p_name, p_virtual_call_userdata, p_args, r_ret);"
        );

        // Engine-parent negative anchor: the parent itself keeps the NULL tail and emits no hop.
        var parentLookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* VirtualFwdParent_class_get_virtual_with_data("
        );
        assertContainsAll(parentLookupBody, "return (void*)VirtualFwdParent__ready;", "return NULL;");
        assertFalse(parentLookupBody.contains("class_get_virtual_with_data("), parentLookupBody);
        var parentDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void VirtualFwdParent_class_call_virtual_with_data("
        );
        assertFalse(parentDispatchBody.contains("class_call_virtual_with_data("), parentDispatchBody);
    }

    /// A subclass without any own virtual override emits empty own-branch sections but
    /// still forwards both callbacks to its GDCC parent — otherwise the engine would silently
    /// drop the parent's virtual implementations for instances of the subclass.
    @Test
    public void generateShouldForwardVirtualCallbacksForSubclassWithoutOwnOverrides() throws Exception {
        var parentClass = new LirClassDef("VirtualEmptyParent", "Node");
        var parentReady = newFunction("_ready", GdVoidType.VOID);
        entry(parentReady).setTerminator(new ReturnInsn(null));
        parentClass.addFunction(parentReady);

        var childClass = new LirClassDef("VirtualEmptyChild", "VirtualEmptyParent");

        var module = new LirModule("virtual_empty_forward_module", List.of(parentClass, childClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var entrySource = generatedFileText(codegen.generate(), "entry.c");

        var childLookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* VirtualEmptyChild_class_get_virtual_with_data("
        );
        assertFalse(childLookupBody.contains("godot_StringName_op_equal_StringName"), childLookupBody);
        assertContainsAll(
                childLookupBody,
                "return VirtualEmptyParent_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);"
        );

        var childDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void VirtualEmptyChild_class_call_virtual_with_data("
        );
        assertFalse(childDispatchBody.contains("p_virtual_call_userdata =="), childDispatchBody);
        assertContainsAll(
                childDispatchBody,
                "VirtualEmptyParent_class_call_virtual_with_data(p_instance, p_name, p_virtual_call_userdata, p_args, r_ret);"
        );
    }

    /// Forwarding composes level by level — a grandchild with no own overrides must hop
    /// through the intermediate GDCC class (not directly to the root), so each level's own
    /// overrides and editor gates keep their chance to match.
    @Test
    public void generateShouldChainVirtualForwardingAcrossMultipleGdccLevels() throws Exception {
        var rootClass = new LirClassDef("VirtualChainRoot", "Node");
        var rootReady = newFunction("_ready", GdVoidType.VOID);
        entry(rootReady).setTerminator(new ReturnInsn(null));
        rootClass.addFunction(rootReady);

        var midClass = new LirClassDef("VirtualChainMid", "VirtualChainRoot");
        var leafClass = new LirClassDef("VirtualChainLeaf", "VirtualChainMid");

        var module = new LirModule("virtual_chain_forward_module", List.of(rootClass, midClass, leafClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var entrySource = generatedFileText(codegen.generate(), "entry.c");

        var leafLookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* VirtualChainLeaf_class_get_virtual_with_data("
        );
        assertContainsAll(
                leafLookupBody,
                "return VirtualChainMid_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);"
        );
        assertFalse(leafLookupBody.contains("VirtualChainRoot_class_get_virtual_with_data("), leafLookupBody);
        var leafDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void VirtualChainLeaf_class_call_virtual_with_data("
        );
        assertContainsAll(
                leafDispatchBody,
                "VirtualChainMid_class_call_virtual_with_data(p_instance, p_name, p_virtual_call_userdata, p_args, r_ret);"
        );
        assertFalse(leafDispatchBody.contains("VirtualChainRoot_class_call_virtual_with_data("), leafDispatchBody);

        var midLookupBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void* VirtualChainMid_class_get_virtual_with_data("
        );
        assertContainsAll(
                midLookupBody,
                "return VirtualChainRoot_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);"
        );
        var midDispatchBody = resolveFunctionBodyByPrefix(
                entrySource,
                "void VirtualChainMid_class_call_virtual_with_data("
        );
        assertContainsAll(
                midDispatchBody,
                "VirtualChainRoot_class_call_virtual_with_data(p_instance, p_name, p_virtual_call_userdata, p_args, r_ret);"
        );
    }

    @Test
    public void generateShouldUseSessionBoundBodyRendererInsteadOfPublicGenerateFuncBody() {
        var workerClass = new LirClassDef("EngineUsageWorker", "RefCounted");
        var queueFree = newFunction("call_queue_free", GdVoidType.VOID);
        queueFree.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, queueFree));
        entry(queueFree).appendInstruction(new CallMethodInsn(null, "queue_free", "node", List.of()));
        entry(queueFree).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(queueFree);

        var module = new LirModule("engine_usage_render_dispatch_module", List.of(workerClass));
        var api = engineHelperApi();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var publicBodyCalled = new boolean[]{false};
        var sessionBodyCalled = new boolean[]{false};
        var codegen = new CCodegen() {
            @Override
            public @NotNull String generateFuncBody(@NotNull LirClassDef clazz, @NotNull LirFunctionDef func) {
                publicBodyCalled[0] = true;
                return super.generateFuncBody(clazz, func);
            }

            @Override
            @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                             @NotNull LirFunctionDef func,
                                             @NotNull GodotBindingUsageBuffer usageBuffer) {
                sessionBodyCalled[0] = true;
                return super.generateFuncBody(clazz, func, usageBuffer);
            }
        };
        codegen.prepare(ctx, module);

        var files = codegen.generate();
        var bindHeaderCode = generatedFileText(files, "engine_method_binds.h");

        assertTrue(sessionBodyCalled[0], "generate() should render bodies through the session-bound helper path.");
        assertFalse(publicBodyCalled[0], "generate() should not route through the public no-op usage renderer.");
        assertTrue(bindHeaderCode.contains("gdcc_engine_method_bind_node_queue_free_P_RV("), bindHeaderCode);
    }

    @Test
    public void generateShouldKeepCoreFilesWhenModuleLocalGodotWrappersAreCollected() throws Exception {
        var workerClass = new LirClassDef("ModuleLocalWrapperWorker", "RefCounted");
        var useWrapper = newFunction("use_module_local_wrapper", GdVoidType.VOID);
        entry(useWrapper).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(useWrapper);

        var module = new LirModule("module_local_wrapper_generation_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen() {
            @Override
            @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                             @NotNull LirFunctionDef func,
                                             @NotNull GodotBindingUsageBuffer usageBuffer) {
                if (!func.getName().equals("use_module_local_wrapper")) {
                    return super.generateFuncBody(clazz, func, usageBuffer);
                }
                usageBuffer.recordModuleLocalGodotBinding(moduleLocalConstantBinding());
                return """
                        goto entry;
                        entry: // entry
                        godot_Probe_READY();
                        goto __finally__;
                        __finally__: // __finally__
                        return;
                        """;
            }
        };
        codegen.prepare(ctx, module);

        var files = codegen.generate();
        var entrySource = generatedFileText(files, "entry.c");
        var bindHeaderCode = generatedFileText(files, "engine_method_binds.h");

        assertEquals(List.of("entry.c", "engine_method_binds.h", "object_fat_ptr_types.h", "entry.h"), files.stream().map(GeneratedFile::filePath).toList());
        assertTrue(entrySource.contains("godot_Probe_READY();"), entrySource);
        assertTrue(bindHeaderCode.contains("static inline godot_int godot_Probe_READY(void)"), bindHeaderCode);
        assertTrue(bindHeaderCode.contains("return (godot_int)13;"), bindHeaderCode);
    }

    @Test
    public void failedSessionBodyRenderShouldNotLeakModuleLocalGodotBindingUsageIntoTheModuleSession() throws Exception {
        var workerClass = new LirClassDef("ModuleLocalWrapperWorker", "RefCounted");
        var invalid = newFunction("record_module_local_then_fail", GdVoidType.VOID);
        entry(invalid).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(invalid);
        var valid = newFunction("record_module_local_valid", GdVoidType.VOID);
        entry(valid).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(valid);

        var module = new LirModule("module_local_wrapper_failed_render_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen() {
            @Override
            @NotNull String generateFuncBody(@NotNull LirClassDef clazz,
                                             @NotNull LirFunctionDef func,
                                             @NotNull GodotBindingUsageBuffer usageBuffer) {
                usageBuffer.recordModuleLocalGodotBinding(moduleLocalConstantBinding());
                if (func.getName().equals("record_module_local_then_fail")) {
                    throw new InvalidInsnException("forced module-local render failure");
                }
                return """
                        goto entry;
                        entry: // entry
                        godot_Probe_READY();
                        goto __finally__;
                        __finally__: // __finally__
                        return;
                        """;
            }
        };
        codegen.prepare(ctx, module);

        var usageSession = new GodotBindingUsageSession(Set.of());
        assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, invalid, usageSession));
        assertTrue(usageSession.moduleLocalBindings().isEmpty(), "Failed function renders must not commit module-local usage.");

        var validBody = codegen.generateFuncBody(workerClass, valid, usageSession);
        var snapshot = usageSession.moduleLocalBindings();

        assertTrue(validBody.contains("godot_Probe_READY();"), validBody);
        assertEquals(1, snapshot.size(), snapshot.toString());
        assertEquals("godot_Probe_READY", snapshot.getFirst().symbol().cFunctionName());
    }

    @Test
    public void failedSessionBodyRenderShouldNotLeakEngineMethodUsageIntoTheModuleSession() {
        var workerClass = new LirClassDef("EngineUsageWorker", "RefCounted");

        var valid = newFunction("call_queue_free_valid", GdVoidType.VOID);
        valid.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, valid));
        entry(valid).appendInstruction(new CallMethodInsn(null, "queue_free", "node", List.of()));
        entry(valid).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(valid);

        var invalid = newFunction("call_queue_free_invalid", GdVoidType.VOID);
        invalid.addParameter(new LirParameterDef("node", new GdObjectType("Node"), null, invalid));
        invalid.createAndAddVariable("left", GdIntType.INT);
        invalid.createAndAddVariable("right", GdIntType.INT);
        invalid.createAndAddVariable("sum", GdIntType.INT);
        entry(invalid).appendInstruction(new CallMethodInsn(null, "queue_free", "node", List.of()));
        entry(invalid).appendInstruction(new BinaryOpInsn("sum", GodotOperator.ADD, "left", "right"));
        entry(invalid).setTerminator(new ReturnInsn(null));
        workerClass.addFunction(invalid);

        var module = new LirModule("engine_usage_failed_render_module", List.of(workerClass));
        var api = engineHelperApi();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var usageSession = new GodotBindingUsageSession(Set.of());
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(workerClass, invalid, usageSession));
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(usageSession.engineMethods().isEmpty(), "Failed function renders must not commit helper usage.");

        var validBody = codegen.generateFuncBody(workerClass, valid, usageSession);
        var snapshot = usageSession.engineMethods();

        assertTrue(validBody.contains("gdcc_engine_call_node_queue_free_P_RV"), validBody);
        assertEquals(1, snapshot.size(), snapshot.toString());
        assertEquals("queue_free", snapshot.getFirst().methodName());
        assertEquals("Node", snapshot.getFirst().ownerClassName());
    }

    @Test
    public void generatesVariantMethodBindingMetadataAndKeepsNonVariantGate() throws Exception {
        var workerClass = new LirClassDef("VariantAbiWorker", "Node");

        var acceptVariant = new LirFunctionDef("accept_variant");
        acceptVariant.setReturnType(GdIntType.INT);
        acceptVariant.addParameter(new LirParameterDef("self", new GdObjectType("VariantAbiWorker"), null, acceptVariant));
        acceptVariant.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, acceptVariant));
        var acceptResult = acceptVariant.createAndAddTmpVariable(GdIntType.INT);
        var acceptEntry = new LirBasicBlock("entry");
        acceptEntry.appendInstruction(new LiteralIntInsn(acceptResult.id(), 1));
        acceptEntry.setTerminator(new ReturnInsn(acceptResult.id()));
        acceptVariant.addBasicBlock(acceptEntry);
        acceptVariant.setEntryBlockId("entry");
        workerClass.addFunction(acceptVariant);

        var echoVariant = new LirFunctionDef("echo_variant");
        echoVariant.setReturnType(GdVariantType.VARIANT);
        echoVariant.addParameter(new LirParameterDef("self", new GdObjectType("VariantAbiWorker"), null, echoVariant));
        echoVariant.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, echoVariant));
        var echoEntry = new LirBasicBlock("entry");
        echoEntry.setTerminator(new ReturnInsn("value"));
        echoVariant.addBasicBlock(echoEntry);
        echoVariant.setEntryBlockId("entry");
        workerClass.addFunction(echoVariant);

        var acceptInt = new LirFunctionDef("accept_int");
        acceptInt.setReturnType(GdIntType.INT);
        acceptInt.addParameter(new LirParameterDef("self", new GdObjectType("VariantAbiWorker"), null, acceptInt));
        acceptInt.addParameter(new LirParameterDef("value", GdIntType.INT, null, acceptInt));
        var acceptIntEntry = new LirBasicBlock("entry");
        acceptIntEntry.setTerminator(new ReturnInsn("value"));
        acceptInt.addBasicBlock(acceptIntEntry);
        acceptInt.setEntryBlockId("entry");
        workerClass.addFunction(acceptInt);

        var module = new LirModule("variant_method_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var hCode = generatedFileText(files, "entry.h");

        var acceptVariantBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Variant_ret_int");
        var echoVariantBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Variant_ret_Variant");
        var acceptVariantCallBody = resolveCallWrapperBody(hCode, "_1_arg_Variant_ret_int");
        var acceptIntCallBody = resolveCallWrapperBody(hCode, "_1_arg_int_ret_int");

        assertContainsAll(
                acceptVariantBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT"
        );
        assertContainsAll(
                echoVariantBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(",
                "GDEXTENSION_VARIANT_TYPE_NIL",
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT"
        );
        assertFalse(acceptVariantCallBody.contains("expected = GDEXTENSION_VARIANT_TYPE_NIL;"), acceptVariantCallBody);
        assertContainsAll(acceptIntCallBody, "expected = GDEXTENSION_VARIANT_TYPE_INT;");
    }

    @Test
    public void generatesCallWrapperInboundIntToFloatCompatibilityWithoutWeakeningOtherParams() throws Exception {
        var workerClass = new LirClassDef("InboundPrimitiveCallWorker", "Node");

        var takeFloat = new LirFunctionDef("take_float");
        takeFloat.setReturnType(GdFloatType.FLOAT);
        takeFloat.addParameter(new LirParameterDef("self", new GdObjectType("InboundPrimitiveCallWorker"), null, takeFloat));
        takeFloat.addParameter(new LirParameterDef("value", GdFloatType.FLOAT, null, takeFloat));
        var takeFloatEntry = new LirBasicBlock("entry");
        takeFloatEntry.setTerminator(new ReturnInsn("value"));
        takeFloat.addBasicBlock(takeFloatEntry);
        takeFloat.setEntryBlockId("entry");
        workerClass.addFunction(takeFloat);

        var takeInt = new LirFunctionDef("take_int");
        takeInt.setReturnType(GdIntType.INT);
        takeInt.addParameter(new LirParameterDef("self", new GdObjectType("InboundPrimitiveCallWorker"), null, takeInt));
        takeInt.addParameter(new LirParameterDef("value", GdIntType.INT, null, takeInt));
        var takeIntEntry = new LirBasicBlock("entry");
        takeIntEntry.setTerminator(new ReturnInsn("value"));
        takeInt.addBasicBlock(takeIntEntry);
        takeInt.setEntryBlockId("entry");
        workerClass.addFunction(takeInt);

        var echoVariant = new LirFunctionDef("echo_variant");
        echoVariant.setReturnType(GdVariantType.VARIANT);
        echoVariant.addParameter(new LirParameterDef("self", new GdObjectType("InboundPrimitiveCallWorker"), null, echoVariant));
        echoVariant.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, echoVariant));
        var echoVariantEntry = new LirBasicBlock("entry");
        echoVariantEntry.setTerminator(new ReturnInsn("value"));
        echoVariant.addBasicBlock(echoVariantEntry);
        echoVariant.setEntryBlockId("entry");
        workerClass.addFunction(echoVariant);

        var module = new LirModule("inbound_primitive_call_module", List.of(workerClass));
        var hCode = generateHeader(module);

        var takeFloatCallBody = resolveCallWrapperBody(hCode, "_1_arg_float_ret_float");
        var takeIntCallBody = resolveCallWrapperBody(hCode, "_1_arg_int_ret_int");
        var echoVariantCallBody = resolveCallWrapperBody(hCode, "_1_arg_Variant_ret_Variant");

        assertContainsAll(
                takeFloatCallBody,
                "const GDExtensionVariantType arg0_type = godot_variant_get_type(p_args[0]);",
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_FLOAT || arg0_type == GDEXTENSION_VARIANT_TYPE_INT))",
                "expected = GDEXTENSION_VARIANT_TYPE_FLOAT;",
                "const godot_float arg0 = arg0_type == GDEXTENSION_VARIANT_TYPE_INT ? (godot_float)godot_new_int_with_Variant((GDExtensionVariantPtr)p_args[0]) : godot_new_float_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertFalse(takeFloatCallBody.contains("GDEXTENSION_VARIANT_TYPE_BOOL"), takeFloatCallBody);
        assertFalse(
                takeFloatCallBody.contains("godot_variant_get_type((GDExtensionVariantPtr)p_args[0])"),
                takeFloatCallBody
        );
        assertEquals(1, countOccurrences(takeFloatCallBody, "godot_variant_get_type(p_args[0])"), takeFloatCallBody);

        assertContainsAll(
                takeIntCallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_INT))",
                "expected = GDEXTENSION_VARIANT_TYPE_INT;",
                "const godot_int arg0 = godot_new_int_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertFalse(takeIntCallBody.contains("GDEXTENSION_VARIANT_TYPE_FLOAT ||"), takeIntCallBody);
        assertFalse(takeIntCallBody.contains("godot_new_float_with_Variant"), takeIntCallBody);

        assertFalse(echoVariantCallBody.contains("expected = GDEXTENSION_VARIANT_TYPE_NIL;"), echoVariantCallBody);
        assertTrue(echoVariantCallBody.contains("godot_Variant arg0 = godot_new_Variant_with_Variant((GDExtensionVariantPtr)p_args[0]);"), echoVariantCallBody);
        assertTrue(echoVariantCallBody.contains("godot_Variant_destroy(&arg0);"), echoVariantCallBody);
    }

    @Test
    public void generatesCallWrapperInboundVectorIToVectorCompatibilityWithoutWeakeningOtherParams() throws Exception {
        var workerClass = new LirClassDef("InboundVectorCallWorker", "Node");
        addSingleParamReturnFunction(workerClass, "InboundVectorCallWorker", "take_vector2", GdFloatVectorType.VECTOR2);
        addSingleParamReturnFunction(workerClass, "InboundVectorCallWorker", "take_vector3", GdFloatVectorType.VECTOR3);
        addSingleParamReturnFunction(workerClass, "InboundVectorCallWorker", "take_vector4", GdFloatVectorType.VECTOR4);
        addSingleParamReturnFunction(workerClass, "InboundVectorCallWorker", "take_vector3i", GdIntVectorType.VECTOR3I);
        addSingleParamReturnFunction(workerClass, "InboundVectorCallWorker", "take_rect2", GdRect2Type.RECT2);

        var module = new LirModule("inbound_vector_call_module", List.of(workerClass));
        var hCode = generateHeader(module);

        var takeVector2CallBody = resolveCallWrapperBody(hCode, "_1_arg_Vector2_ret_Vector2");
        var takeVector3CallBody = resolveCallWrapperBody(hCode, "_1_arg_Vector3_ret_Vector3");
        var takeVector4CallBody = resolveCallWrapperBody(hCode, "_1_arg_Vector4_ret_Vector4");
        var takeVector3iCallBody = resolveCallWrapperBody(hCode, "_1_arg_Vector3i_ret_Vector3i");
        var takeRect2CallBody = resolveCallWrapperBody(hCode, "_1_arg_Rect2_ret_Rect2");

        assertContainsAll(
                takeVector2CallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_VECTOR2 || arg0_type == GDEXTENSION_VARIANT_TYPE_VECTOR2I))",
                "expected = GDEXTENSION_VARIANT_TYPE_VECTOR2;",
                "const godot_Vector2 arg0 = gdcc_new_Vector2_from_call_arg_variant((GDExtensionVariantPtr)p_args[0], arg0_type);"
        );
        assertContainsAll(
                takeVector3CallBody,
                "const GDExtensionVariantType arg0_type = godot_variant_get_type(p_args[0]);",
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_VECTOR3 || arg0_type == GDEXTENSION_VARIANT_TYPE_VECTOR3I))",
                "expected = GDEXTENSION_VARIANT_TYPE_VECTOR3;",
                "const godot_Vector3 arg0 = gdcc_new_Vector3_from_call_arg_variant((GDExtensionVariantPtr)p_args[0], arg0_type);"
        );
        assertContainsAll(
                takeVector4CallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_VECTOR4 || arg0_type == GDEXTENSION_VARIANT_TYPE_VECTOR4I))",
                "expected = GDEXTENSION_VARIANT_TYPE_VECTOR4;",
                "const godot_Vector4 arg0 = gdcc_new_Vector4_from_call_arg_variant((GDExtensionVariantPtr)p_args[0], arg0_type);"
        );
        assertEquals(1, countOccurrences(takeVector3CallBody, "godot_variant_get_type(p_args[0])"), takeVector3CallBody);

        assertContainsAll(
                takeVector3iCallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_VECTOR3I))",
                "expected = GDEXTENSION_VARIANT_TYPE_VECTOR3I;",
                "const godot_Vector3i arg0 = godot_new_Vector3i_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertFalse(takeVector3iCallBody.contains("GDEXTENSION_VARIANT_TYPE_VECTOR3 ||"), takeVector3iCallBody);
        assertFalse(takeVector3iCallBody.contains("gdcc_new_Vector3_from_call_arg_variant"), takeVector3iCallBody);

        assertContainsAll(
                takeRect2CallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_RECT2))",
                "expected = GDEXTENSION_VARIANT_TYPE_RECT2;",
                "godot_Rect2 arg0 = godot_new_Rect2_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertFalse(takeRect2CallBody.contains("GDEXTENSION_VARIANT_TYPE_RECT2I"), takeRect2CallBody);
        assertFalse(takeRect2CallBody.contains("gdcc_new_Rect2_from_call_arg_variant"), takeRect2CallBody);
    }

    @Test
    public void generatesCallWrapperInboundStringFamilyCompatibilityWithoutWeakeningOtherParams() throws Exception {
        var workerClass = new LirClassDef("InboundStringCallWorker", "Node");
        addSingleParamReturnFunction(workerClass, "InboundStringCallWorker", "take_string_name", GdStringNameType.STRING_NAME);
        addSingleParamReturnFunction(workerClass, "InboundStringCallWorker", "take_string", GdStringType.STRING);
        addSingleParamReturnFunction(workerClass, "InboundStringCallWorker", "take_node_path", GdNodePathType.NODE_PATH);

        var module = new LirModule("inbound_string_call_module", List.of(workerClass));
        var hCode = generateHeader(module);

        var takeStringNameCallBody = resolveCallWrapperBody(hCode, "_1_arg_StringName_ret_StringName");
        var takeStringCallBody = resolveCallWrapperBody(hCode, "_1_arg_String_ret_String");
        var takeNodePathCallBody = resolveCallWrapperBody(hCode, "_1_arg_NodePath_ret_NodePath");

        assertContainsAll(
                takeStringNameCallBody,
                "const GDExtensionVariantType arg0_type = godot_variant_get_type(p_args[0]);",
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_STRING_NAME || arg0_type == GDEXTENSION_VARIANT_TYPE_STRING))",
                "expected = GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "godot_StringName arg0 = gdcc_new_StringName_from_call_arg_variant((GDExtensionVariantPtr)p_args[0], arg0_type);"
        );
        assertEquals(1, countOccurrences(takeStringNameCallBody, "godot_variant_get_type(p_args[0])"), takeStringNameCallBody);
        assertFalse(
                takeStringNameCallBody.contains("godot_new_StringName_with_Variant((GDExtensionVariantPtr)p_args[0])"),
                takeStringNameCallBody
        );

        assertContainsAll(
                takeStringCallBody,
                "const GDExtensionVariantType arg0_type = godot_variant_get_type(p_args[0]);",
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_STRING || arg0_type == GDEXTENSION_VARIANT_TYPE_STRING_NAME))",
                "expected = GDEXTENSION_VARIANT_TYPE_STRING;",
                "godot_String arg0 = gdcc_new_String_from_call_arg_variant((GDExtensionVariantPtr)p_args[0], arg0_type);"
        );
        assertEquals(1, countOccurrences(takeStringCallBody, "godot_variant_get_type(p_args[0])"), takeStringCallBody);
        assertFalse(
                takeStringCallBody.contains("godot_new_String_with_Variant((GDExtensionVariantPtr)p_args[0])"),
                takeStringCallBody
        );

        assertContainsAll(
                takeNodePathCallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_NODE_PATH))",
                "expected = GDEXTENSION_VARIANT_TYPE_NODE_PATH;",
                "godot_NodePath arg0 = godot_new_NodePath_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertFalse(takeNodePathCallBody.contains("GDEXTENSION_VARIANT_TYPE_STRING"), takeNodePathCallBody);
        assertFalse(takeNodePathCallBody.contains("from_call_arg_variant"), takeNodePathCallBody);
    }

    /// Export-variant properties bind with the hint/hint_string driven by the annotation value;
    /// the class_name slot stays empty for non-Object property types.
    @Test
    public void generatesExportVariantPropertyBindingMetadataFromAnnotationValue() throws Exception {
        var workerClass = new LirClassDef("ExportVariantPropertyOwner", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "speed",
                GdFloatType.FLOAT,
                false,
                null,
                null,
                null,
                Map.of("export_range", "0,20,0.5")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "title",
                GdStringType.STRING,
                false,
                null,
                null,
                null,
                Map.of("export_multiline", "")
        ));

        var module = new LirModule("export_variant_property_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var cCode = generatedFileText(codegen.generate(), "entry.c");

        var speedBind = resolvePropertyBindCall(cCode, "speed");
        assertContainsAll(
                speedBind,
                "GDEXTENSION_VARIANT_TYPE_FLOAT",
                "godot_PROPERTY_HINT_RANGE",
                "GD_STATIC_S(u8\"0,20,0.5\")",
                "GD_STATIC_SN(u8\"\")",
                "godot_PROPERTY_USAGE_DEFAULT"
        );
        var titleBind = resolvePropertyBindCall(cCode, "title");
        assertContainsAll(
                titleBind,
                "GDEXTENSION_VARIANT_TYPE_STRING",
                "godot_PROPERTY_HINT_MULTILINE_TEXT",
                "godot_PROPERTY_USAGE_DEFAULT"
        );
    }

    /// Object exports bind with the property type class in both the hint_string and the
    /// class_name slot (never the owner class name), driving RESOURCE_TYPE / NODE_TYPE hints.
    @Test
    public void generatesObjectExportBindingWithPropertyTypeClassName() throws Exception {
        var workerClass = new LirClassDef("ObjectExportOwner", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "texture",
                new GdObjectType("Texture2D"),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "target",
                new GdObjectType("Node2D"),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));

        var module = new LirModule("object_export_property_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var cCode = generatedFileText(codegen.generate(), "entry.c");

        var textureBind = resolvePropertyBindCall(cCode, "texture");
        assertContainsAll(
                textureBind,
                "GDEXTENSION_VARIANT_TYPE_OBJECT",
                "godot_PROPERTY_HINT_RESOURCE_TYPE",
                "GD_STATIC_S(u8\"Texture2D\")",
                "GD_STATIC_SN(u8\"Texture2D\")",
                "godot_PROPERTY_USAGE_DEFAULT"
        );
        assertFalse(textureBind.contains("GD_STATIC_SN(u8\"ObjectExportOwner\")"), textureBind);
        var targetBind = resolvePropertyBindCall(cCode, "target");
        assertContainsAll(
                targetBind,
                "godot_PROPERTY_HINT_NODE_TYPE",
                "GD_STATIC_S(u8\"Node2D\")",
                "GD_STATIC_SN(u8\"Node2D\")"
        );
        assertFalse(targetBind.contains("GD_STATIC_SN(u8\"ObjectExportOwner\")"), targetBind);
    }

    @Test
    public void generatesVariantPropertyBindingMetadataAndKeepsNonVariantPropertyShape() throws Exception {
        var workerClass = new LirClassDef("VariantPropertyOwner", "Node");
        workerClass.addProperty(new LirPropertyDef("hidden_payload", GdVariantType.VARIANT));
        workerClass.addProperty(new LirPropertyDef("visible_payload", GdVariantType.VARIANT, false, null, null, null, Map.of("export", "")));
        workerClass.addProperty(new LirPropertyDef("hidden_score", GdIntType.INT));
        workerClass.addProperty(new LirPropertyDef("visible_score", GdIntType.INT, false, null, null, null, Map.of("export", "")));

        var module = new LirModule("variant_property_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var hiddenPayloadBind = resolvePropertyBindCall(cCode, "hidden_payload");
        var visiblePayloadBind = resolvePropertyBindCall(cCode, "visible_payload");
        var hiddenScoreBind = resolvePropertyBindCall(cCode, "hidden_score");
        var visibleScoreBind = resolvePropertyBindCall(cCode, "visible_score");

        assertEquals(4, countOccurrences(cCode, "gdcc_bind_property_full("), cCode);
        assertFalse(cCode.contains("gdcc_bind_property(class_name,"), cCode);
        assertContainsAll(
                hiddenPayloadBind,
                "GDEXTENSION_VARIANT_TYPE_NIL",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_NO_EDITOR | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                "_field_getter_hidden_payload",
                "_field_setter_hidden_payload"
        );
        assertContainsAll(
                visiblePayloadBind,
                "GDEXTENSION_VARIANT_TYPE_NIL",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT",
                "_field_getter_visible_payload",
                "_field_setter_visible_payload"
        );
        assertContainsAll(
                hiddenScoreBind,
                "GDEXTENSION_VARIANT_TYPE_INT",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_NO_EDITOR",
                "_field_getter_hidden_score",
                "_field_setter_hidden_score"
        );
        assertContainsAll(
                visibleScoreBind,
                "GDEXTENSION_VARIANT_TYPE_INT",
                "godot_PROPERTY_HINT_NONE",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_visible_score",
                "_field_setter_visible_score"
        );
    }

    @Test
    public void generatesTypedDictionaryMethodBindingMetadataAndKeepsGenericDictionaryPlain() throws Exception {
        var workerClass = new LirClassDef("TypedDictionaryAbiWorker", "Node");
        var typedDictionaryType = new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node"));
        var mixedDictionaryType = new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT);
        var genericDictionaryType = new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryAbiWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef("payload", typedDictionaryType, null, acceptTypedPayload));
        var acceptTypedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var acceptTypedEntry = new LirBasicBlock("entry");
        acceptTypedEntry.appendInstruction(new LiteralIntInsn(acceptTypedResult.id(), 1));
        acceptTypedEntry.setTerminator(new ReturnInsn(acceptTypedResult.id()));
        acceptTypedPayload.addBasicBlock(acceptTypedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var echoMixedPayload = new LirFunctionDef("echo_mixed_payload");
        echoMixedPayload.setReturnType(mixedDictionaryType);
        echoMixedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryAbiWorker"), null, echoMixedPayload));
        echoMixedPayload.addParameter(new LirParameterDef("payload", mixedDictionaryType, null, echoMixedPayload));
        var echoMixedEntry = new LirBasicBlock("entry");
        echoMixedEntry.setTerminator(new ReturnInsn("payload"));
        echoMixedPayload.addBasicBlock(echoMixedEntry);
        echoMixedPayload.setEntryBlockId("entry");
        workerClass.addFunction(echoMixedPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdBoolType.BOOL);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryAbiWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef("payload", genericDictionaryType, null, acceptGenericPayload));
        var acceptGenericResult = acceptGenericPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var acceptGenericEntry = new LirBasicBlock("entry");
        acceptGenericEntry.appendInstruction(new LiteralBoolInsn(acceptGenericResult.id(), true));
        acceptGenericEntry.setTerminator(new ReturnInsn(acceptGenericResult.id()));
        acceptGenericPayload.addBasicBlock(acceptGenericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_dictionary_method_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        // Method registration splits the outward contract across entry.c and entry.h:
        // entry.c passes the base variant type, while entry.h fixes hint/hint_string/class_name/usage.
        var typedBindCall = resolveMethodBindCall(cCode, "accept_typed_payload");
        var typedBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Dictionary_ret_int");
        var mixedBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Dictionary_ret_Dictionary");
        var genericBindCall = resolveMethodBindCall(cCode, "accept_generic_payload");
        var genericBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Dictionary_ret_bool");

        assertContainsAll(
                typedBindCall,
                "GD_STATIC_SN(u8\"accept_typed_payload\")",
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY"
        );
        assertContainsAll(
                typedBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Node\")",
                "godot_PROPERTY_USAGE_DEFAULT"
        );
        assertContainsAll(
                mixedBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(",
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Variant\")"
        );
        assertContainsAll(
                genericBindCall,
                "GD_STATIC_SN(u8\"accept_generic_payload\")",
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY"
        );
        assertContainsAll(genericBindBody, "gdcc_make_property_full(arg0_type, arg0_name", "godot_PROPERTY_HINT_NONE");
        assertFalse(genericBindBody.contains("godot_PROPERTY_HINT_DICTIONARY_TYPE"), genericBindBody);
        assertFalse(genericBindBody.contains("GD_STATIC_S(u8\"StringName;"), genericBindBody);
    }

    @Test
    public void generatesTypedDictionaryPropertyBindingMetadataAndKeepsGenericDictionaryPlain() throws Exception {
        var workerClass = new LirClassDef("TypedDictionaryPropertyOwner", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "typed_payload",
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node")),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "mixed_payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "generic_payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));

        var module = new LirModule("typed_dictionary_property_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var typedBind = resolvePropertyBindCall(cCode, "typed_payload");
        var mixedBind = resolvePropertyBindCall(cCode, "mixed_payload");
        var genericBind = resolvePropertyBindCall(cCode, "generic_payload");

        assertContainsAll(
                typedBind,
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"StringName;Node\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_typed_payload",
                "_field_setter_typed_payload"
        );
        assertContainsAll(
                mixedBind,
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_DICTIONARY_TYPE",
                "GD_STATIC_S(u8\"Variant;PackedInt32Array\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_mixed_payload",
                "_field_setter_mixed_payload"
        );
        assertContainsAll(
                genericBind,
                "GDEXTENSION_VARIANT_TYPE_DICTIONARY",
                "godot_PROPERTY_HINT_NONE",
                "GD_STATIC_S(u8\"\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_generic_payload",
                "_field_setter_generic_payload"
        );
        assertFalse(genericBind.contains("godot_PROPERTY_HINT_DICTIONARY_TYPE"), genericBind);
    }

    @Test
    public void generatesTypedArrayMethodBindingMetadataAndKeepsGenericArrayPlain() throws Exception {
        var workerClass = new LirClassDef("TypedArrayAbiWorker", "Node");
        var typedStringArray = new GdArrayType(GdStringNameType.STRING_NAME);
        var typedPlainArray = new GdArrayType(new GdArrayType(GdVariantType.VARIANT));
        var genericArray = new GdArrayType(GdVariantType.VARIANT);

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayAbiWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef("payload", typedStringArray, null, acceptTypedPayload));
        var acceptTypedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var acceptTypedEntry = new LirBasicBlock("entry");
        acceptTypedEntry.appendInstruction(new LiteralIntInsn(acceptTypedResult.id(), 1));
        acceptTypedEntry.setTerminator(new ReturnInsn(acceptTypedResult.id()));
        acceptTypedPayload.addBasicBlock(acceptTypedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var echoPlainPayload = new LirFunctionDef("echo_plain_payload");
        echoPlainPayload.setReturnType(typedPlainArray);
        echoPlainPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayAbiWorker"), null, echoPlainPayload));
        echoPlainPayload.addParameter(new LirParameterDef("payload", typedPlainArray, null, echoPlainPayload));
        var echoPlainEntry = new LirBasicBlock("entry");
        echoPlainEntry.setTerminator(new ReturnInsn("payload"));
        echoPlainPayload.addBasicBlock(echoPlainEntry);
        echoPlainPayload.setEntryBlockId("entry");
        workerClass.addFunction(echoPlainPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdBoolType.BOOL);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayAbiWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef("payload", genericArray, null, acceptGenericPayload));
        var acceptGenericResult = acceptGenericPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var acceptGenericEntry = new LirBasicBlock("entry");
        acceptGenericEntry.appendInstruction(new LiteralBoolInsn(acceptGenericResult.id(), true));
        acceptGenericEntry.setTerminator(new ReturnInsn(acceptGenericResult.id()));
        acceptGenericPayload.addBasicBlock(acceptGenericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_array_method_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        var typedBindCall = resolveMethodBindCall(cCode, "accept_typed_payload");
        var typedBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Array_ret_int");
        var plainBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Array_ret_Array");
        var genericBindCall = resolveMethodBindCall(cCode, "accept_generic_payload");
        var genericBindBody = resolveMethodBindHelperBody(hCode, "_1_arg_Array_ret_bool");

        assertContainsAll(
                typedBindCall,
                "GD_STATIC_SN(u8\"accept_typed_payload\")",
                "GDEXTENSION_VARIANT_TYPE_ARRAY"
        );
        assertContainsAll(
                typedBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"StringName\")",
                "godot_PROPERTY_USAGE_DEFAULT"
        );
        assertContainsAll(
                plainBindBody,
                "gdcc_make_property_full(arg0_type, arg0_name",
                "GDExtensionPropertyInfo return_info = gdcc_make_property_full(",
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"Array\")"
        );
        assertContainsAll(
                genericBindCall,
                "GD_STATIC_SN(u8\"accept_generic_payload\")",
                "GDEXTENSION_VARIANT_TYPE_ARRAY"
        );
        assertContainsAll(genericBindBody, "gdcc_make_property_full(arg0_type, arg0_name", "godot_PROPERTY_HINT_NONE");
        assertFalse(genericBindBody.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), genericBindBody);
        assertFalse(genericBindBody.contains("GD_STATIC_S(u8\"StringName\")"), genericBindBody);
    }

    @Test
    public void generatesTypedArrayPropertyBindingMetadataAndKeepsGenericArrayPlain() throws Exception {
        var workerClass = new LirClassDef("TypedArrayPropertyOwner", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "typed_payload",
                new GdArrayType(GdStringNameType.STRING_NAME),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "plain_nested_payload",
                new GdArrayType(new GdArrayType(GdVariantType.VARIANT)),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));
        workerClass.addProperty(new LirPropertyDef(
                "generic_payload",
                new GdArrayType(GdVariantType.VARIANT),
                false,
                null,
                null,
                null,
                Map.of("export", "")
        ));

        var module = new LirModule("typed_array_property_bind_metadata_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var typedBind = resolvePropertyBindCall(cCode, "typed_payload");
        var plainBind = resolvePropertyBindCall(cCode, "plain_nested_payload");
        var genericBind = resolvePropertyBindCall(cCode, "generic_payload");

        assertContainsAll(
                typedBind,
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"StringName\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_typed_payload",
                "_field_setter_typed_payload"
        );
        assertContainsAll(
                plainBind,
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_ARRAY_TYPE",
                "GD_STATIC_S(u8\"Array\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_plain_nested_payload",
                "_field_setter_plain_nested_payload"
        );
        assertContainsAll(
                genericBind,
                "GDEXTENSION_VARIANT_TYPE_ARRAY",
                "godot_PROPERTY_HINT_NONE",
                "GD_STATIC_S(u8\"\")",
                "godot_PROPERTY_USAGE_DEFAULT",
                "_field_getter_generic_payload",
                "_field_setter_generic_payload"
        );
        assertFalse(genericBind.contains("godot_PROPERTY_HINT_ARRAY_TYPE"), genericBind);
    }

    @Test
    public void generatesTypedArrayCallWrapperPreflightAndKeepsGenericArrayOnBaseGate() throws Exception {
        var workerClass = new LirClassDef("TypedArrayCallGuardWorker", "Node");

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayCallGuardWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdArrayType(new GdObjectType("Node")),
                null,
                acceptTypedPayload
        ));
        var typedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var typedEntry = new LirBasicBlock("entry");
        typedEntry.appendInstruction(new LiteralIntInsn(typedResult.id(), 1));
        typedEntry.setTerminator(new ReturnInsn(typedResult.id()));
        acceptTypedPayload.addBasicBlock(typedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var acceptPackedPayload = new LirFunctionDef("accept_packed_payload");
        acceptPackedPayload.setReturnType(GdBoolType.BOOL);
        acceptPackedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayCallGuardWorker"), null, acceptPackedPayload));
        acceptPackedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdArrayType(GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                null,
                acceptPackedPayload
        ));
        var packedResult = acceptPackedPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var packedEntry = new LirBasicBlock("entry");
        packedEntry.appendInstruction(new LiteralBoolInsn(packedResult.id(), true));
        packedEntry.setTerminator(new ReturnInsn(packedResult.id()));
        acceptPackedPayload.addBasicBlock(packedEntry);
        acceptPackedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptPackedPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdFloatType.FLOAT);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedArrayCallGuardWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef(
                "payload",
                new GdArrayType(GdVariantType.VARIANT),
                null,
                acceptGenericPayload
        ));
        var genericResult = acceptGenericPayload.createAndAddTmpVariable(GdFloatType.FLOAT);
        var genericEntry = new LirBasicBlock("entry");
        genericEntry.appendInstruction(new LiteralFloatInsn(genericResult.id(), 1.5));
        genericEntry.setTerminator(new ReturnInsn(genericResult.id()));
        acceptGenericPayload.addBasicBlock(genericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_array_call_guard_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var hCode = generatedFileText(files, "entry.h");

        var typedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Array_ret_int");
        var packedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Array_ret_bool");
        var genericCallBody = resolveCallWrapperBody(hCode, "_1_arg_Array_ret_float");

        assertContainsAll(
                typedCallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_ARRAY))",
                "godot_Array probe0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_StringName probe0_class_name = godot_Array_get_typed_class_name(&probe0);",
                "godot_Variant probe0_script = godot_Array_get_typed_script(&probe0);",
                "godot_Variant probe0_script_is_null_result;",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_script, &probe0_script_nil, (GDExtensionUninitializedVariantPtr)&probe0_script_is_null_result, &probe0_script_is_null_valid);",
                "const godot_bool probe0_script_is_null = probe0_script_is_null_valid && godot_new_bool_with_Variant(&probe0_script_is_null_result);",
                "typed_mismatch = !godot_StringName_op_equal_StringName(&probe0_class_name, GD_STATIC_SN(u8\"Node\")) || !probe0_script_is_null;",
                "if (probe0_script_is_null_valid) {",
                "godot_Variant_destroy(&probe0_script_is_null_result);",
                "godot_Variant_destroy(&probe0_script_nil);",
                "godot_Variant_destroy(&probe0_script);",
                "godot_StringName_destroy(&probe0_class_name);",
                "godot_Array_destroy(&probe0);",
                "expected = GDEXTENSION_VARIANT_TYPE_ARRAY;",
                "argument = 0;"
        );
        var typedProbeIndex = typedCallBody.indexOf("godot_Array probe0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        var typedArgIndex = typedCallBody.indexOf("arg0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        assertTrue(typedProbeIndex >= 0, typedCallBody);
        assertTrue(typedArgIndex > typedProbeIndex, typedCallBody);
        assertFalse(typedCallBody.contains("probe0_script_is_null_result = godot_new_Variant_nil();"), typedCallBody);
        assertFalse(typedCallBody.contains("godot_Array_is_same_typed"), typedCallBody);

        assertContainsAll(
                packedCallBody,
                "godot_Array probe0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "godot_bool typed_mismatch = godot_Array_get_typed_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY;",
                "godot_Array_destroy(&probe0);",
                "expected = GDEXTENSION_VARIANT_TYPE_ARRAY;"
        );
        assertFalse(packedCallBody.contains("probe0_class_name"), packedCallBody);
        assertFalse(packedCallBody.contains("probe0_script"), packedCallBody);
        assertFalse(packedCallBody.contains("godot_Array_is_same_typed"), packedCallBody);

        assertContainsAll(
                genericCallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_ARRAY))",
                "godot_Array arg0 = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertEquals(
                1,
                countOccurrences(genericCallBody, "godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[0])"),
                genericCallBody
        );
        assertFalse(genericCallBody.contains("typed_mismatch"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Array_get_typed_"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Array_is_same_typed"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Array probe0 ="), genericCallBody);
    }

    @Test
    public void generatesTypedDictionaryCallWrapperPreflightAndKeepsGenericDictionaryOnBaseGate() throws Exception {
        var workerClass = new LirClassDef("TypedDictionaryCallGuardWorker", "Node");

        var acceptTypedPayload = new LirFunctionDef("accept_typed_payload");
        acceptTypedPayload.setReturnType(GdIntType.INT);
        acceptTypedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryCallGuardWorker"), null, acceptTypedPayload));
        acceptTypedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdDictionaryType(GdStringNameType.STRING_NAME, new GdObjectType("Node")),
                null,
                acceptTypedPayload
        ));
        var typedResult = acceptTypedPayload.createAndAddTmpVariable(GdIntType.INT);
        var typedEntry = new LirBasicBlock("entry");
        typedEntry.appendInstruction(new LiteralIntInsn(typedResult.id(), 1));
        typedEntry.setTerminator(new ReturnInsn(typedResult.id()));
        acceptTypedPayload.addBasicBlock(typedEntry);
        acceptTypedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptTypedPayload);

        var acceptMixedPayload = new LirFunctionDef("accept_mixed_payload");
        acceptMixedPayload.setReturnType(GdBoolType.BOOL);
        acceptMixedPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryCallGuardWorker"), null, acceptMixedPayload));
        acceptMixedPayload.addParameter(new LirParameterDef(
                "payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                null,
                acceptMixedPayload
        ));
        var mixedResult = acceptMixedPayload.createAndAddTmpVariable(GdBoolType.BOOL);
        var mixedEntry = new LirBasicBlock("entry");
        mixedEntry.appendInstruction(new LiteralBoolInsn(mixedResult.id(), true));
        mixedEntry.setTerminator(new ReturnInsn(mixedResult.id()));
        acceptMixedPayload.addBasicBlock(mixedEntry);
        acceptMixedPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptMixedPayload);

        var acceptGenericPayload = new LirFunctionDef("accept_generic_payload");
        acceptGenericPayload.setReturnType(GdFloatType.FLOAT);
        acceptGenericPayload.addParameter(new LirParameterDef("self", new GdObjectType("TypedDictionaryCallGuardWorker"), null, acceptGenericPayload));
        acceptGenericPayload.addParameter(new LirParameterDef(
                "payload",
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                null,
                acceptGenericPayload
        ));
        var genericResult = acceptGenericPayload.createAndAddTmpVariable(GdFloatType.FLOAT);
        var genericEntry = new LirBasicBlock("entry");
        genericEntry.appendInstruction(new LiteralFloatInsn(genericResult.id(), 1.5));
        genericEntry.setTerminator(new ReturnInsn(genericResult.id()));
        acceptGenericPayload.addBasicBlock(genericEntry);
        acceptGenericPayload.setEntryBlockId("entry");
        workerClass.addFunction(acceptGenericPayload);

        var module = new LirModule("typed_dictionary_call_guard_module", List.of(workerClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var hCode = generatedFileText(files, "entry.h");

        var typedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Dictionary_ret_int");
        var mixedCallBody = resolveCallWrapperBody(hCode, "_1_arg_Dictionary_ret_bool");
        var genericCallBody = resolveCallWrapperBody(hCode, "_1_arg_Dictionary_ret_float");

        assertContainsAll(
                typedCallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_DICTIONARY))",
                "godot_Dictionary probe0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "godot_bool typed_mismatch = false;",
                "typed_mismatch = godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_STRING_NAME;",
                "typed_mismatch = godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_OBJECT;",
                "godot_StringName probe0_value_class_name = godot_Dictionary_get_typed_value_class_name(&probe0);",
                "godot_Variant probe0_value_script = godot_Dictionary_get_typed_value_script(&probe0);",
                "godot_Variant probe0_value_script_nil = godot_new_Variant_nil();",
                "godot_Variant probe0_value_script_is_null_result;",
                "godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &probe0_value_script, &probe0_value_script_nil, (GDExtensionUninitializedVariantPtr)&probe0_value_script_is_null_result, &probe0_value_script_is_null_valid);",
                "const godot_bool probe0_value_script_is_null = probe0_value_script_is_null_valid && godot_new_bool_with_Variant(&probe0_value_script_is_null_result);",
                "typed_mismatch = !godot_StringName_op_equal_StringName(&probe0_value_class_name, GD_STATIC_SN(u8\"Node\")) || !probe0_value_script_is_null;",
                "if (probe0_value_script_is_null_valid) {",
                "godot_Variant_destroy(&probe0_value_script_is_null_result);",
                "godot_Variant_destroy(&probe0_value_script_nil);",
                "godot_Variant_destroy(&probe0_value_script);",
                "godot_StringName_destroy(&probe0_value_class_name);",
                "godot_Dictionary_destroy(&probe0);",
                "expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY;",
                "argument = 0;"
        );
        var typedProbeIndex = typedCallBody.indexOf("godot_Dictionary probe0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        var typedArgIndex = typedCallBody.indexOf("arg0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);");
        assertTrue(typedProbeIndex >= 0, typedCallBody);
        assertTrue(typedArgIndex > typedProbeIndex, typedCallBody);
        assertFalse(typedCallBody.contains("probe0_value_script_is_null_result = godot_new_Variant_nil();"), typedCallBody);
        assertFalse(typedCallBody.contains("goto "), typedCallBody);

        assertContainsAll(
                mixedCallBody,
                "godot_Dictionary probe0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);",
                "typed_mismatch = godot_Dictionary_get_typed_key_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_NIL;",
                "typed_mismatch = godot_Dictionary_get_typed_value_builtin(&probe0) != (godot_int)GDEXTENSION_VARIANT_TYPE_PACKED_INT32_ARRAY;",
                "godot_Dictionary_destroy(&probe0);"
        );
        assertFalse(mixedCallBody.contains("probe0_key_class_name"), mixedCallBody);
        assertFalse(mixedCallBody.contains("probe0_value_class_name"), mixedCallBody);
        assertFalse(mixedCallBody.contains("probe0_value_script"), mixedCallBody);
        assertFalse(mixedCallBody.contains("godot_Dictionary_is_same_typed"), mixedCallBody);

        assertContainsAll(
                genericCallBody,
                "if (!(arg0_type == GDEXTENSION_VARIANT_TYPE_DICTIONARY))",
                "godot_Dictionary arg0 = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0]);"
        );
        assertEquals(
                1,
                countOccurrences(genericCallBody, "godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[0])"),
                genericCallBody
        );
        assertFalse(genericCallBody.contains("typed_mismatch"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Dictionary_is_same_typed"), genericCallBody);
        assertFalse(genericCallBody.contains("expectedBase0"), genericCallBody);
        assertFalse(genericCallBody.contains("godot_Dictionary probe0 ="), genericCallBody);
    }

    @Test
    public void generatesCallFuncCleanupForDestroyableWrapperLocals() throws Exception {
        var workerClass = new LirClassDef("CallWrapperCleanupWorker", "Node");

        var echoString = new LirFunctionDef("echo_string");
        echoString.setReturnType(GdStringType.STRING);
        echoString.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, echoString));
        echoString.addParameter(new LirParameterDef("value", GdStringType.STRING, null, echoString));
        var echoStringEntry = new LirBasicBlock("entry");
        echoStringEntry.setTerminator(new ReturnInsn("value"));
        echoString.addBasicBlock(echoStringEntry);
        echoString.setEntryBlockId("entry");
        workerClass.addFunction(echoString);

        var arrayToBool = new LirFunctionDef("array_to_bool");
        arrayToBool.setReturnType(GdBoolType.BOOL);
        arrayToBool.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, arrayToBool));
        arrayToBool.addParameter(new LirParameterDef("value", new GdArrayType(GdVariantType.VARIANT), null, arrayToBool));
        var boolResult = arrayToBool.createAndAddTmpVariable(GdBoolType.BOOL);
        var arrayToBoolEntry = new LirBasicBlock("entry");
        arrayToBoolEntry.appendInstruction(new LiteralBoolInsn(boolResult.id(), true));
        arrayToBoolEntry.setTerminator(new ReturnInsn(boolResult.id()));
        arrayToBool.addBasicBlock(arrayToBoolEntry);
        arrayToBool.setEntryBlockId("entry");
        workerClass.addFunction(arrayToBool);

        var echoVariant = new LirFunctionDef("echo_variant");
        echoVariant.setReturnType(GdVariantType.VARIANT);
        echoVariant.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, echoVariant));
        echoVariant.addParameter(new LirParameterDef("value", GdVariantType.VARIANT, null, echoVariant));
        var echoVariantEntry = new LirBasicBlock("entry");
        echoVariantEntry.setTerminator(new ReturnInsn("value"));
        echoVariant.addBasicBlock(echoVariantEntry);
        echoVariant.setEntryBlockId("entry");
        workerClass.addFunction(echoVariant);

        var consumeString = new LirFunctionDef("consume_string");
        consumeString.setReturnType(GdVoidType.VOID);
        consumeString.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupWorker"), null, consumeString));
        consumeString.addParameter(new LirParameterDef("value", GdStringType.STRING, null, consumeString));
        var consumeStringEntry = new LirBasicBlock("entry");
        consumeStringEntry.setTerminator(new ReturnInsn(null));
        consumeString.addBasicBlock(consumeStringEntry);
        consumeString.setEntryBlockId("entry");
        workerClass.addFunction(consumeString);

        var module = new LirModule("call_wrapper_cleanup_module", List.of(workerClass));
        var hCode = generateHeader(module);
        var echoStringWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_String_ret_String");
        var consumeStringWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_String_no_ret");

        assertEquals(2, countOccurrences(hCode, "godot_String_destroy(&arg0);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_String_destroy(&r);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_Array_destroy(&arg0);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_Variant_destroy(&arg0);"), hCode);
        assertEquals(1, countOccurrences(hCode, "godot_Variant_destroy(&r);"), hCode);
        assertEquals(3, countOccurrences(hCode, "godot_Variant_destroy(&ret);"), hCode);
        assertFalse(hCode.contains("godot_bool_destroy(&r);"), hCode);
        assertTrue(consumeStringWrapperBody.contains("godot_String_destroy(&arg0);"), consumeStringWrapperBody);
        assertFalse(consumeStringWrapperBody.contains("godot_Variant_destroy(&ret);"), consumeStringWrapperBody);

        var copyIndex = echoStringWrapperBody.indexOf("godot_variant_new_copy(r_return, &ret);");
        var retDestroyIndex = echoStringWrapperBody.indexOf("godot_Variant_destroy(&ret);", copyIndex);
        var returnDestroyIndex = echoStringWrapperBody.indexOf("godot_String_destroy(&r);", retDestroyIndex);
        var argDestroyIndex = echoStringWrapperBody.indexOf("godot_String_destroy(&arg0);", returnDestroyIndex);
        assertTrue(copyIndex >= 0, echoStringWrapperBody);
        assertTrue(retDestroyIndex > copyIndex, echoStringWrapperBody);
        assertTrue(returnDestroyIndex > retDestroyIndex, echoStringWrapperBody);
        assertTrue(argDestroyIndex > returnDestroyIndex, echoStringWrapperBody);
    }

    @Test
    public void generatesCallFuncCleanupWithoutDestroyingObjectsOrPrimitives() throws Exception {
        var workerClass = new LirClassDef("CallWrapperCleanupNegativeWorker", "Node");

        var echoNode = new LirFunctionDef("echo_node");
        echoNode.setReturnType(new GdObjectType("Node"));
        echoNode.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupNegativeWorker"), null, echoNode));
        echoNode.addParameter(new LirParameterDef("value", new GdObjectType("Node"), null, echoNode));
        var echoNodeEntry = new LirBasicBlock("entry");
        echoNodeEntry.setTerminator(new ReturnInsn("value"));
        echoNode.addBasicBlock(echoNodeEntry);
        echoNode.setEntryBlockId("entry");
        workerClass.addFunction(echoNode);

        var echoInt = new LirFunctionDef("echo_int");
        echoInt.setReturnType(GdIntType.INT);
        echoInt.addParameter(new LirParameterDef("self", new GdObjectType("CallWrapperCleanupNegativeWorker"), null, echoInt));
        echoInt.addParameter(new LirParameterDef("value", GdIntType.INT, null, echoInt));
        var echoIntEntry = new LirBasicBlock("entry");
        echoIntEntry.setTerminator(new ReturnInsn("value"));
        echoInt.addBasicBlock(echoIntEntry);
        echoInt.setEntryBlockId("entry");
        workerClass.addFunction(echoInt);

        var module = new LirModule("call_wrapper_cleanup_negative_module", List.of(workerClass));
        var hCode = generateHeader(module);
        var echoNodeWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_Node_ret_Node");
        var echoIntWrapperBody = resolveCallWrapperBody(hCode, "_1_arg_int_ret_int");

        assertEquals(2, countOccurrences(hCode, "godot_Variant_destroy(&ret);"), hCode);
        assertTrue(echoNodeWrapperBody.contains("godot_Variant_destroy(&ret);"), echoNodeWrapperBody);
        assertTrue(echoIntWrapperBody.contains("godot_Variant_destroy(&ret);"), echoIntWrapperBody);
        assertFalse(echoNodeWrapperBody.contains("godot_object_destroy(&arg0);"), echoNodeWrapperBody);
        assertFalse(echoNodeWrapperBody.contains("godot_object_destroy(&r);"), echoNodeWrapperBody);
        assertFalse(echoIntWrapperBody.contains("godot_int_destroy(&arg0);"), echoIntWrapperBody);
        assertFalse(echoIntWrapperBody.contains("godot_int_destroy(&r);"), echoIntWrapperBody);
    }

    @Test
    public void generateCreatesDefaultPropertyInitHelperWhenInitFuncIsUnset() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var property = new LirPropertyDef("ready_value", GdIntType.INT);
        workerClass.addProperty(property);
        var module = new LirModule("property_init_default_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        assertEquals("_field_init_ready_value", property.getInitFunc());
        var initFunc = assertInstanceOf(
                LirFunctionDef.class,
                workerClass.getFunctions().stream()
                        .filter(function -> function.getName().equals("_field_init_ready_value"))
                        .findFirst()
                        .orElseThrow()
        );
        assertTrue(initFunc.isHidden());
        assertTrue(initFunc.hasBasicBlock("entry"));
        assertEquals("__prepare__", initFunc.getEntryBlockId());
        assertTrue(initFunc.hasBasicBlock("__prepare__"));
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        assertContainsAll(constructorBody, "GDWorkerNode_class_apply_property_init_ready_value(self);");
        assertContainsAll(applyHelperBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(gdcc_GDWorkerNode_fat_ptr_from_raw(GDWorkerNode_object_ptr(self)))");
        assertFalse(applyHelperBody.contains("_field_setter_"), applyHelperBody);
        assertFalse(cCode.contains("GD_STATIC_SN(u8\"_field_init_ready_value\")"), cCode);
    }

    @Test
    public void generateAcceptsPropertyInitFunctionWithExecutableBody() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var property = new LirPropertyDef("ready_value", GdIntType.INT, false, "_field_init_ready_value", null, null, Map.of());
        workerClass.addProperty(property);
        var initFunction = new LirFunctionDef("_field_init_ready_value");
        initFunction.setHidden(true);
        initFunction.setReturnType(GdIntType.INT);
        initFunction.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, initFunction));
        var result = initFunction.createAndAddTmpVariable(GdIntType.INT);
        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new LiteralIntInsn(result.id(), 7));
        entry.setTerminator(new ReturnInsn(result.id()));
        initFunction.addBasicBlock(entry);
        initFunction.setEntryBlockId("entry");
        workerClass.addFunction(initFunction);
        var module = new LirModule("property_init_lowered_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");
        var initHelperBody = resolveFunctionBodyByPrefix(cCode, "godot_int GDWorkerNode__field_init_ready_value");

        assertTrue(cCode.contains("godot_int GDWorkerNode__field_init_ready_value("), cCode);
        assertTrue(cCode.contains("gdcc_GDWorkerNode_fat_ptr $self"), cCode);
        assertContainsAll(initHelperBody, "$0 = 7;");
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        assertContainsAll(constructorBody, "GDWorkerNode_class_apply_property_init_ready_value(self);");
        assertContainsAll(applyHelperBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(gdcc_GDWorkerNode_fat_ptr_from_raw(GDWorkerNode_object_ptr(self)))");
        assertFalse(applyHelperBody.contains("_field_setter_"), applyHelperBody);
    }

    @Test
    void generateUsesDedicatedDirectFieldApplyHelpersForObjectAndScalarPropertyInit() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef("ready_value", GdIntType.INT));
        workerClass.addProperty(new LirPropertyDef("ready_node", new GdObjectType("Node")));
        var module = new LirModule("property_init_apply_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");

        assertContainsAll(
                constructorBody,
                "GDWorkerNode_class_apply_property_init_ready_value(self);",
                "GDWorkerNode_class_apply_property_init_ready_node(self);"
        );

        var intApplyBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        var objectApplyBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_node");
        assertContainsAll(intApplyBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(gdcc_GDWorkerNode_fat_ptr_from_raw(GDWorkerNode_object_ptr(self)))");
        assertContainsAll(objectApplyBody, "self->ready_node =", "GDWorkerNode__field_init_ready_node(gdcc_GDWorkerNode_fat_ptr_from_raw(GDWorkerNode_object_ptr(self)))");
        assertFalse(intApplyBody.contains("_field_setter_"), intApplyBody);
        assertFalse(objectApplyBody.contains("_field_setter_"), objectApplyBody);
        assertFalse(constructorBody.contains("self->ready_value ="), constructorBody);
        assertFalse(constructorBody.contains("self->ready_node ="), constructorBody);
    }

    @Test
    void generatePropertyInitApplyHelperConsumesFreshRefCountedResultWithoutExtraOwn() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef("ready_ref", new GdObjectType("RefCounted")));
        var module = new LirModule("property_init_refcounted_apply_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_ref");
        assertContainsAll(applyHelperBody, "self->ready_ref =", "GDWorkerNode__field_init_ready_ref(gdcc_GDWorkerNode_fat_ptr_from_raw(GDWorkerNode_object_ptr(self)))");
        assertFalse(applyHelperBody.contains("own_object(self->ready_ref);"), applyHelperBody);
        assertFalse(applyHelperBody.contains("try_own_object(self->ready_ref);"), applyHelperBody);
        assertFalse(applyHelperBody.contains("release_object("), applyHelperBody);
        assertFalse(applyHelperBody.contains("try_release_object("), applyHelperBody);
    }

    @Test
    void generatePropertyInitApplyHelperDoesNotReuseExplicitSetterRoute() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var property = new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                "_field_getter_ready_value",
                "custom_ready_value_setter",
                Map.of()
        );
        workerClass.addProperty(property);

        var initFunction = new LirFunctionDef("_field_init_ready_value");
        initFunction.setHidden(true);
        initFunction.setReturnType(GdIntType.INT);
        initFunction.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, initFunction));
        var result = initFunction.createAndAddTmpVariable(GdIntType.INT);
        var initEntry = new LirBasicBlock("entry");
        initEntry.appendInstruction(new LiteralIntInsn(result.id(), 7));
        initEntry.setTerminator(new ReturnInsn(result.id()));
        initFunction.addBasicBlock(initEntry);
        initFunction.setEntryBlockId("entry");
        workerClass.addFunction(initFunction);

        var setter = new LirFunctionDef("custom_ready_value_setter");
        setter.setReturnType(GdVoidType.VOID);
        setter.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, setter));
        setter.addParameter(new LirParameterDef("value", GdIntType.INT, null, setter));
        var setterEntry = new LirBasicBlock("entry");
        setterEntry.setTerminator(new ReturnInsn(null));
        setter.addBasicBlock(setterEntry);
        setter.setEntryBlockId("entry");
        workerClass.addFunction(setter);

        var module = new LirModule("property_init_apply_helper_setter_boundary_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());
        var applyHelperBody = resolvePropertyInitApplyHelperBody(cCode, "GDWorkerNode", "ready_value");
        var constructorBody = resolveClassConstructorBody(cCode, "GDWorkerNode");

        assertContainsAll(applyHelperBody, "self->ready_value =", "GDWorkerNode__field_init_ready_value(gdcc_GDWorkerNode_fat_ptr_from_raw(GDWorkerNode_object_ptr(self)))");
        assertFalse(applyHelperBody.contains("custom_ready_value_setter"), applyHelperBody);
        assertFalse(constructorBody.contains("custom_ready_value_setter"), constructorBody);
        assertTrue(cCode.contains("GD_STATIC_SN(u8\"custom_ready_value_setter\")"), cCode);
    }

    @Test
    public void generateFailsFastWhenPropertyInitFunctionIsMissing() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                null,
                null,
                Map.of()
        ));
        var module = new LirModule("property_init_missing_helper_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("GDWorkerNode._field_init_ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("does not exist"), exception.getMessage());
    }

    @Test
    public void generateFailsFastWhenPropertyInitFunctionRemainsShellOnly() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                null,
                null,
                Map.of()
        ));
        var shellOnlyInit = new LirFunctionDef("_field_init_ready_value");
        shellOnlyInit.setHidden(true);
        shellOnlyInit.setReturnType(GdIntType.INT);
        shellOnlyInit.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, shellOnlyInit));
        workerClass.addFunction(shellOnlyInit);
        var module = new LirModule("property_init_shell_only_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("GDWorkerNode.ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("_field_init_ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("shell-only"), exception.getMessage());
    }

    @Test
    public void generateFailsFastWhenPropertyInitFunctionSignatureIsNotInternalHelperShape() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        workerClass.addProperty(new LirPropertyDef(
                "ready_value",
                GdIntType.INT,
                false,
                "_field_init_ready_value",
                null,
                null,
                Map.of()
        ));
        var invalidInit = new LirFunctionDef("_field_init_ready_value");
        invalidInit.setHidden(true);
        invalidInit.setReturnType(GdFloatType.FLOAT);
        invalidInit.addParameter(new LirParameterDef("value", GdIntType.INT, null, invalidInit));
        var entry = new LirBasicBlock("entry");
        var result = invalidInit.createAndAddTmpVariable(GdFloatType.FLOAT);
        entry.appendInstruction(new LiteralFloatInsn(result.id(), 1.0));
        entry.setTerminator(new ReturnInsn(result.id()));
        invalidInit.addBasicBlock(entry);
        invalidInit.setEntryBlockId("entry");
        workerClass.addFunction(invalidInit);
        var module = new LirModule("property_init_invalid_signature_module", List.of(workerClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var exception = assertThrows(IllegalStateException.class, codegen::generate);

        assertTrue(exception.getMessage().contains("GDWorkerNode.ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("_field_init_ready_value"), exception.getMessage());
        assertTrue(exception.getMessage().contains("mismatched return type"), exception.getMessage());
    }

    @Test
    public void generatesMappedCanonicalClassNamesVerbatimInArtifacts() throws Exception {
        var runtimeOuterClass = new LirClassDef("RuntimeOuter", "Node");
        var module = new LirModule("mapped_runtime_module", List.of(runtimeOuterClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();

        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        assertTrue(hCode.contains("struct RuntimeOuter {"), hCode);
        assertTrue(hCode.contains("RuntimeOuter_class_create_instance"), hCode);
        assertTrue(cCode.contains("RuntimeOuter_class_create_instance"), cCode);
        assertTrue(cCode.contains("godot_classdb_construct_object2(GD_STATIC_SN(u8\"Node\"))"), cCode);
        assertFalse(hCode.contains("MappedOuter"), hCode);
        assertFalse(cCode.contains("MappedOuter"), cCode);
    }

    @Test
    public void keepsInnerCanonicalIdentityConsistentAcrossRegistrationBindAndAttachSurfaces() throws Exception {
        var sharedClass = new LirClassDef("RuntimeOuter__sub__Shared", "RefCounted");
        var sharedRead = new LirFunctionDef("read");
        sharedRead.setReturnType(GdIntType.INT);
        sharedRead.addParameter(new LirParameterDef("self", new GdObjectType("RuntimeOuter__sub__Shared"), null, sharedRead));
        var sharedEntry = new LirBasicBlock("entry");
        var sharedResult = sharedRead.createAndAddTmpVariable(GdIntType.INT);
        sharedEntry.appendInstruction(new LiteralIntInsn(sharedResult.id(), 7));
        sharedEntry.setTerminator(new ReturnInsn(sharedResult.id()));
        sharedRead.addBasicBlock(sharedEntry);
        sharedRead.setEntryBlockId("entry");
        sharedClass.addFunction(sharedRead);

        var leafClass = new LirClassDef("RuntimeOuter__sub__Leaf", "RuntimeOuter__sub__Shared");
        var leafPing = new LirFunctionDef("ping");
        leafPing.setReturnType(GdVoidType.VOID);
        leafPing.addParameter(new LirParameterDef("self", new GdObjectType("RuntimeOuter__sub__Leaf"), null, leafPing));
        var leafEntry = new LirBasicBlock("entry");
        leafEntry.setTerminator(new ReturnInsn(null));
        leafPing.addBasicBlock(leafEntry);
        leafPing.setEntryBlockId("entry");
        leafClass.addFunction(leafPing);

        var module = new LirModule("inner_registration_surface_module", List.of(sharedClass, leafClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();

        var cCode = generatedFileText(files, "entry.c");
        var sharedBindBody = resolveFunctionBodyByPrefix(cCode, "void RuntimeOuter__sub__Shared_class_bind_methods");
        var leafBindBody = resolveFunctionBodyByPrefix(cCode, "void RuntimeOuter__sub__Leaf_class_bind_methods");
        var sharedCreateInstanceBody = resolveCreateInstanceBody(cCode, "RuntimeOuter__sub__Shared");
        var leafCreateInstanceBody = resolveCreateInstanceBody(cCode, "RuntimeOuter__sub__Leaf");

        var sharedRegisterPattern = Pattern.compile(
                "godot_classdb_register_extension_class5\\(class_library,\\s*" +
                        "GD_STATIC_SN\\(u8\"RuntimeOuter__sub__Shared\"\\), GD_STATIC_SN\\(u8\"RefCounted\"\\),\\s*&creation_info\\);",
                Pattern.DOTALL
        );
        var leafRegisterPattern = Pattern.compile(
                "godot_classdb_register_extension_class5\\(class_library,\\s*" +
                        "GD_STATIC_SN\\(u8\"RuntimeOuter__sub__Leaf\"\\), GD_STATIC_SN\\(u8\"RuntimeOuter__sub__Shared\"\\),\\s*&creation_info\\);",
                Pattern.DOTALL
        );
        assertTrue(sharedRegisterPattern.matcher(cCode).find(), cCode);
        assertTrue(leafRegisterPattern.matcher(cCode).find(), cCode);

        assertContainsAll(
                sharedBindBody,
                "godot_StringName* class_name = GD_STATIC_SN(u8\"RuntimeOuter__sub__Shared\");",
                "RuntimeOuter__sub__Shared_read"
        );
        assertContainsAll(
                leafBindBody,
                "godot_StringName* class_name = GD_STATIC_SN(u8\"RuntimeOuter__sub__Leaf\");",
                "RuntimeOuter__sub__Leaf_ping"
        );

        assertContainsAll(
                sharedCreateInstanceBody,
                "godot_object_set_instance(obj, GD_STATIC_SN(u8\"RuntimeOuter__sub__Shared\"), self);"
        );
        assertContainsAll(
                leafCreateInstanceBody,
                "godot_object_set_instance(obj, GD_STATIC_SN(u8\"RuntimeOuter__sub__Leaf\"), self);"
        );
        assertFalse(sharedCreateInstanceBody.contains("GD_STATIC_SN(u8\"Shared\")"), sharedCreateInstanceBody);
        assertFalse(leafCreateInstanceBody.contains("GD_STATIC_SN(u8\"Leaf\")"), leafCreateInstanceBody);

        // Engine-facing native construction still follows the nearest native ancestor rather than the canonical leaf name.
        assertEquals("RefCounted", resolveConstructTarget(cCode, "RuntimeOuter__sub__Shared"));
        assertEquals("RefCounted", resolveConstructTarget(cCode, "RuntimeOuter__sub__Leaf"));
    }

    @Test
    public void rendersOperatorEvaluatorHelpersAndUsesHelperCallsInFunctionBody() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("operator_eval");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("left", GdIntType.INT);
        func.createAndAddVariable("right", GdIntType.INT);
        func.createAndAddVariable("tmp", GdBoolType.BOOL);
        func.createAndAddVariable("result", GdBoolType.BOOL);
        func.createAndAddVariable("left_string", GdStringType.STRING);
        func.createAndAddVariable("right_string", GdStringType.STRING);
        func.createAndAddVariable("string_result", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BinaryOpInsn("tmp", GodotOperator.IN, "left", "right"));
        entry.appendInstruction(new UnaryOpInsn("result", GodotOperator.NOT, "tmp"));
        entry.appendInstruction(new BinaryOpInsn("string_result", GodotOperator.ADD, "left_string", "right_string"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("operator_eval_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(evaluatorIntApi());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();

        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        assertTrue(hCode.contains("static inline godot_bool gdcc_eval_binary_in_int_int_to_bool("), hCode);
        assertTrue(hCode.contains("static inline godot_bool gdcc_eval_unary_not_bool_to_bool("), hCode);
        assertTrue(hCode.contains("static inline godot_String gdcc_eval_binary_add_string_string_to_string("), hCode);
        assertTrue(hCode.contains("GDEXTENSION_VARIANT_OP_IN"), hCode);
        assertTrue(hCode.contains("GDEXTENSION_VARIANT_OP_NOT"), hCode);
        assertTrue(hCode.contains("GDEXTENSION_VARIANT_OP_ADD"), hCode);
        assertTrue(hCode.contains("GDCC_PRINT_RUNTIME_ERROR(\"operator evaluator is unavailable"), hCode);
        assertTrue(hCode.contains("return false;"), hCode);
        var stringHelperBody = resolveFunctionBodyByPrefix(
                hCode,
                "static inline godot_String gdcc_eval_binary_add_string_string_to_string("
        );
        assertContainsAll(
                stringHelperBody,
                "// Operator evaluators assign into an existing carrier; destroyable returns must start initialized.",
                "godot_String result = { 0 };",
                "return result;"
        );
        assertFalse(stringHelperBody.contains("godot_String result;\n"), stringHelperBody);
        assertTrue(cCode.contains("$tmp = gdcc_eval_binary_in_int_int_to_bool($left, $right);"), cCode);
        assertTrue(cCode.contains("$result = gdcc_eval_unary_not_bool_to_bool($tmp);"), cCode);
        assertTrue(cCode.contains("$string_result = gdcc_eval_binary_add_string_string_to_string("), cCode);
    }

    @Test
    public void rendersStringFormatEvaluatorHelpersForUntypedAndTypedArrayRightOperands() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("string_format_eval");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("fmt", GdStringType.STRING);
        func.createAndAddVariable("args", new GdArrayType(GdVariantType.VARIANT));
        func.createAndAddVariable("typed_args", new GdArrayType(GdIntType.INT));
        func.createAndAddVariable("plain_result", GdStringType.STRING);
        func.createAndAddVariable("typed_result", GdStringType.STRING);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BinaryOpInsn("plain_result", GodotOperator.MODULE, "fmt", "args"));
        entry.appendInstruction(new BinaryOpInsn("typed_result", GodotOperator.MODULE, "fmt", "typed_args"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("string_format_eval_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(stringFormatApi());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();

        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        // Both helper specs (untyped and typed-array signatures) are collected and rendered;
        // the typed variant keeps its sanitized name while hitting the plain Array metadata entry.
        assertTrue(hCode.contains("static inline godot_String gdcc_eval_binary_module_string_array_to_string("), hCode);
        assertTrue(hCode.contains("gdcc_eval_binary_module_string_array_int_to_string("), hCode);
        assertTrue(hCode.contains("GDEXTENSION_VARIANT_OP_MODULE"), hCode);
        assertTrue(cCode.contains("$plain_result = gdcc_eval_binary_module_string_array_to_string(&$fmt, &$args);"), cCode);
        assertTrue(cCode.contains("$typed_result = gdcc_eval_binary_module_string_array_int_to_string(&$fmt, &$typed_args);"), cCode);
    }

    @Test
    public void codegenShouldFailWhenOnlySwappedMetadataExists() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = new LirFunctionDef("operator_eval_swap");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("left", GdStringType.STRING);
        func.createAndAddVariable("right", GdIntType.INT);
        func.createAndAddVariable("result", GdBoolType.BOOL);

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"));
        entry.appendInstruction(new ReturnInsn(null));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var module = new LirModule("operator_eval_swap_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(evaluatorSwapFallbackApi());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var ex = assertThrows(RuntimeException.class, codegen::generate);
        var rootCause = findRootCause(ex);
        assertTrue(
                rootCause.getMessage().contains("Binary operator metadata is missing for signature (String, GREATER, int)"),
                rootCause.getMessage()
        );
    }

    @Test
    public void generatesExplicitGdccInheritanceLayoutAndObjectPtrHelpers() throws Exception {
        var parentClass = new LirClassDef("GDParentNode", "Node");
        parentClass.addProperty(new LirPropertyDef("speed", GdFloatType.FLOAT));

        var childClass = new LirClassDef("GDChildNode", "GDParentNode");
        childClass.addProperty(new LirPropertyDef("peer", new GdObjectType("GDParentNode")));

        var module = new LirModule("inheritance_layout_module", List.of(parentClass, childClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        List<GeneratedFile> files = codegen.generate();

        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");
        var childObjectPtrHelperBody = resolveFunctionBodyByPrefix(
                cCode,
                "static inline GDExtensionObjectPtr GDChildNode_object_ptr"
        );
        var childCreateInstanceBody = resolveCreateInstanceBody(cCode, "GDChildNode");
        var childConstructorBody = resolveClassConstructorBody(cCode, "GDChildNode");
        var childDestructorBody = resolveClassDestructorBody(cCode, "GDChildNode");

        assertContainsAll(
                hCode,
                "struct GDParentNode {",
                "GDExtensionObjectPtr _object;",
                "struct GDChildNode {",
                "GDParentNode _super;",
                "GDParentNode_object_ptr(",
                "GDChildNode_object_ptr(",
                "GDChildNode_set_object_ptr("
        );
        assertContainsAll(childObjectPtrHelperBody, "return GDParentNode_object_ptr(&self->_super);");
        assertContainsAll(
                childCreateInstanceBody,
                "GDChildNode_set_object_ptr(self, obj);"
        );
        assertContainsAll(childConstructorBody, "GDParentNode_class_constructor(&self->_super);");
        assertContainsAll(childDestructorBody, "GDParentNode_class_destructor(&self->_super);");
        assertContainsAll(cCode, "try_release_object(gdcc_GDParentNode_fat_ptr_live_object(self->peer), self->peer.instance_id);");

        assertEquals("Node", resolveConstructTarget(cCode, "GDParentNode"));
        assertEquals("Node", resolveConstructTarget(cCode, "GDChildNode"));
        var directParentConstructPattern = Pattern.compile(
                "GDExtensionObjectPtr\\s+GDChildNode_class_create_instance\\([^)]*\\)\\s*\\{\\s*GDExtensionObjectPtr obj = godot_classdb_construct_object2\\(GD_STATIC_SN\\(u8\"GDParentNode\"\\)\\);",
                Pattern.DOTALL);
        assertFalse(directParentConstructPattern.matcher(cCode).find());
    }

    @Test
    public void createInstanceUsesSingleBindingAndNearestNativeAncestorForDeepGdccInheritance() throws Exception {
        var rootClass = new LirClassDef("GDRootNode", "Node");
        var midClass = new LirClassDef("GDMidNode", "GDRootNode");
        var leafClass = new LirClassDef("GDLeafNode", "GDMidNode");
        var module = new LirModule("deep_inheritance_module", List.of(rootClass, midClass, leafClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        assertEquals("Node", resolveConstructTarget(cCode, "GDRootNode"));
        assertEquals("Node", resolveConstructTarget(cCode, "GDMidNode"));
        assertEquals("Node", resolveConstructTarget(cCode, "GDLeafNode"));

        var leafCreateInstanceBody = resolveCreateInstanceBody(cCode, "GDLeafNode");
        assertEquals(1, countOccurrences(leafCreateInstanceBody, "godot_object_set_instance("));
        assertEquals(1, countOccurrences(leafCreateInstanceBody, "godot_object_set_instance_binding("));
    }

    @Test
    public void createInstanceKeepsRawNativeConstructionForBothRefCountedAndPlainGdccClasses() throws Exception {
        var countedClass = new LirClassDef("GDCountedWorker", "RefCounted");
        var plainClass = new LirClassDef("GDPlainObject", "Object");
        var module = new LirModule("ref_counted_create_instance_module", List.of(countedClass, plainClass));

        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        var countedBody = resolveCreateInstanceBody(cCode, "GDCountedWorker");
        var plainBody = resolveCreateInstanceBody(cCode, "GDPlainObject");

        assertTrue(
                countedBody.contains("godot_classdb_construct_object2(GD_STATIC_SN(u8\"RefCounted\"))"),
                countedBody
        );
        assertTrue(
                plainBody.contains("godot_classdb_construct_object2(GD_STATIC_SN(u8\"Object\"))"),
                plainBody
        );
        assertFalse(countedBody.contains("gdcc_ref_counted_init_raw("), countedBody);
        assertFalse(plainBody.contains("gdcc_ref_counted_init_raw("), plainBody);
    }

    @Test
    void classConstructorShouldOnlyAutoInvokeZeroArgInit() throws Exception {
        var workerClass = new LirClassDef("GDWorkerNode", "Node");
        var init = new LirFunctionDef("_init");
        init.setReturnType(GdVoidType.VOID);
        init.addParameter(new LirParameterDef("self", new GdObjectType("GDWorkerNode"), null, init));
        init.addParameter(new LirParameterDef("value", GdIntType.INT, null, init));
        var initEntry = new LirBasicBlock("entry");
        init.addBasicBlock(initEntry);
        initEntry.setTerminator(new ReturnInsn(null));
        init.setEntryBlockId("entry");
        workerClass.addFunction(init);

        var zeroArgClass = new LirClassDef("GDZeroArgNode", "Node");
        var zeroArgInit = new LirFunctionDef("_init");
        zeroArgInit.setReturnType(GdVoidType.VOID);
        zeroArgInit.addParameter(new LirParameterDef("self", new GdObjectType("GDZeroArgNode"), null, zeroArgInit));
        var zeroArgEntry = new LirBasicBlock("entry");
        zeroArgInit.addBasicBlock(zeroArgEntry);
        zeroArgEntry.setTerminator(new ReturnInsn(null));
        zeroArgInit.setEntryBlockId("entry");
        zeroArgClass.addFunction(zeroArgInit);

        var module = new LirModule("constructor_init_codegen_module", List.of(workerClass, zeroArgClass));
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);

        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        var cCode = new String(files.getFirst().contentWriter());

        assertFalse(cCode.contains("GDWorkerNode__init(self);"), cCode);
        assertTrue(
                cCode.contains("GDZeroArgNode__init(gdcc_GDZeroArgNode_fat_ptr_from_raw(GDZeroArgNode_object_ptr(self)));"),
                cCode
        );
    }

    private static String resolveConstructTarget(String cCode, String className) {
        var functionPrefix = "GDExtensionObjectPtr\\s+" + Pattern.quote(className) + "_class_create_instance";
        var pattern = Pattern.compile(functionPrefix +
                        "\\([^)]*\\)\\s*\\{\\s*GDExtensionObjectPtr obj = godot_classdb_construct_object2\\(GD_STATIC_SN\\(u8\"([^\"]+)\"\\)\\);",
                Pattern.DOTALL);
        var matcher = pattern.matcher(cCode);
        assertTrue(matcher.find(), "Missing create_instance construct target for class " + className);
        return matcher.group(1);
    }

    private static String resolveCreateInstanceBody(String cCode, String className) {
        return resolveFunctionBodyByPrefix(cCode, "GDExtensionObjectPtr " + className + "_class_create_instance");
    }

    private static String resolveClassConstructorBody(String cCode, String className) {
        return resolveFunctionBodyByPrefix(cCode, "void " + className + "_class_constructor");
    }

    private static String resolveClassDestructorBody(String cCode, String className) {
        return resolveFunctionBodyByPrefix(cCode, "void " + className + "_class_destructor");
    }

    private static String resolvePropertyInitApplyHelperBody(String cCode, String className, String propertyName) {
        return resolveFunctionBodyByPrefix(
                cCode,
                "static inline void " + className + "_class_apply_property_init_" + propertyName
        );
    }

    private static String resolveCallWrapperBody(String hCode, String bindName) {
        // Instance wrappers are named call_<Owner><shape>; static wrappers remain call<shape>.
        return resolveFunctionBodyByPrefix(hCode, resolveOwnedWrapperPrefix(hCode, "static void call", bindName));
    }

    private static String resolveMethodBindHelperBody(String hCode, String bindName) {
        return resolveFunctionBodyByPrefix(hCode, resolveOwnedWrapperPrefix(hCode, "static void gdcc_bind_method", bindName));
    }

    private static String resolveOwnedWrapperPrefix(String hCode, String staticPrefix, String bindName) {
        var exact = staticPrefix + bindName;
        if (hCode.contains(exact + "(") || hCode.contains(exact + "\n") || hCode.contains(exact + " ")) {
            // Prefer exact match when present (static methods / already ownerless shapes).
            var idx = hCode.indexOf(exact);
            if (idx >= 0) {
                var paren = hCode.indexOf('(', idx);
                if (paren > idx && hCode.substring(idx, paren).equals(exact)) {
                    return exact;
                }
            }
        }
        var needle = staticPrefix + "_";
        var from = 0;
        while (true) {
            var idx = hCode.indexOf(needle, from);
            if (idx < 0) {
                break;
            }
            var paren = hCode.indexOf('(', idx);
            if (paren > idx) {
                var full = hCode.substring(idx, paren);
                if (full.endsWith(bindName)) {
                    return full;
                }
            }
            from = idx + needle.length();
        }
        return exact;
    }

    private static String resolveMethodBindCall(String cCode, String methodName) {
        var methodAnchor = "GD_STATIC_SN(u8\"" + methodName + "\")";
        var methodIndex = cCode.indexOf(methodAnchor);
        assertTrue(methodIndex >= 0, "Missing method binding anchor for " + methodName);
        var callStart = cCode.lastIndexOf("gdcc_bind_method", methodIndex);
        assertTrue(callStart >= 0, "Missing method binding call for " + methodName);
        var callEnd = cCode.indexOf(");", methodIndex);
        assertTrue(callEnd >= 0, "Missing end of method binding call for " + methodName);
        return cCode.substring(callStart, callEnd + 2);
    }

    private static String resolvePropertyBindCall(String cCode, String propertyName) {
        var propertyAnchor = "GD_STATIC_SN(u8\"" + propertyName + "\")";
        var propertyIndex = cCode.indexOf(propertyAnchor);
        assertTrue(propertyIndex >= 0, "Missing property binding anchor for " + propertyName);
        var callStart = cCode.lastIndexOf("gdcc_bind_property_full(", propertyIndex);
        assertTrue(callStart >= 0, "Missing full property binding call for " + propertyName);
        var callEnd = cCode.indexOf(");", propertyIndex);
        assertTrue(callEnd >= 0, "Missing end of property binding call for " + propertyName);
        return cCode.substring(callStart, callEnd + 2);
    }

    private static String resolveFunctionBodyByPrefix(String code, String signaturePrefix) {
        var signatureIndex = code.indexOf(signaturePrefix);
        assertTrue(signatureIndex >= 0, "Missing function prefix: " + signaturePrefix);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, "Missing opening brace for " + signaturePrefix);
        var closeBraceIndex = findMatchingBrace(code, openBraceIndex);
        return code.substring(openBraceIndex + 1, closeBraceIndex);
    }

    /// Extracts one userdata-matched branch body out of a generated `*_class_call_virtual_with_data`
    /// dispatch body so assertions can target exactly one virtual's gate/call sequence.
    private static String resolveVirtualDispatchBranch(String dispatchBody, String userdataSymbol) {
        var branchAnchor = "if (p_virtual_call_userdata == &" + userdataSymbol + ")";
        var branchStart = dispatchBody.indexOf(branchAnchor);
        assertTrue(branchStart >= 0, "Missing dispatch branch for " + userdataSymbol + "\n" + dispatchBody);
        var openBraceIndex = dispatchBody.indexOf('{', branchStart);
        var closeBraceIndex = findMatchingBrace(dispatchBody, openBraceIndex);
        return dispatchBody.substring(openBraceIndex + 1, closeBraceIndex);
    }

    private static void addSingleParamReturnFunction(@NotNull LirClassDef clazz,
                                                     @NotNull String className,
                                                     @NotNull String functionName,
                                                     @NotNull GdType type) {
        var func = new LirFunctionDef(functionName);
        func.setReturnType(type);
        func.addParameter(new LirParameterDef("self", new GdObjectType(className), null, func));
        func.addParameter(new LirParameterDef("value", type, null, func));
        var entry = new LirBasicBlock("entry");
        entry.setTerminator(new ReturnInsn("value"));
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        clazz.addFunction(func);
    }

    private static int findMatchingBrace(String text, int openBraceIndex) {
        var depth = 0;
        for (var i = openBraceIndex; i < text.length(); i++) {
            var ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("Missing closing brace for function body");
    }

    private static void assertContainsAll(String text, String... needles) {
        for (var needle : needles) {
            assertTrue(
                    text.contains(needle),
                    () -> "Missing fragment `" + needle + "` in:\n" + text
            );
        }
    }

    private static void assertOrdered(String text, String... fragmentsInOrder) {
        var searchFromIndex = 0;
        for (var fragment : fragmentsInOrder) {
            var index = text.indexOf(fragment, searchFromIndex);
            assertTrue(index >= 0, () -> "Missing fragment: " + fragment + "\n" + text);
            searchFromIndex = index + fragment.length();
        }
    }

    // ==== Static var C backend ====

    private static @NotNull CodegenContext newStaticTestContext() {
        var projectInfo = new ProjectInfo("static_codegen_test", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, new ClassRegistry(new ExtensionAPI(
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        )));
    }

    private static @NotNull LirPropertyDef staticProperty(@NotNull String name, @NotNull GdType type) {
        return new LirPropertyDef(name, type, true, null, null, null, Map.of());
    }

    /// Builds a valid hidden static zero-parameter `_field_init_<name>` helper returning `type`.
    private static @NotNull LirFunctionDef staticInitFunction(@NotNull String name, @NotNull GdType type) {
        var func = new LirFunctionDef(name);
        func.setHidden(true);
        func.setStatic(true);
        func.setReturnType(type);
        var tmpVar = func.createAndAddTmpVariable(type);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        if (type instanceof GdIntType) {
            entry.appendNonTerminatorInstruction(new LiteralIntInsn(tmpVar.id(), 1));
        } else if (type instanceof GdStringType) {
            entry.appendNonTerminatorInstruction(new LiteralStringInsn(tmpVar.id(), "init"));
        } else {
            throw new IllegalArgumentException("unsupported fixture type: " + type.getTypeName());
        }
        entry.setTerminator(new ReturnInsn(tmpVar.id()));
        func.setEntryBlockId("entry");
        return func;
    }

    private static @NotNull String extractSection(@NotNull String cCode, @NotNull String sectionHeader) {
        // Match the function definition (header + line break + brace), never its file-top
        // prototype; the formatter emits CRLF, so the line break is matched with `\R`.
        var matcher = Pattern.compile(Pattern.quote(sectionHeader) + "\\R\\{").matcher(cCode);
        assertTrue(matcher.find(), () -> "Missing section: " + sectionHeader);
        var start = matcher.start();
        var end = cCode.indexOf("\n}", start);
        assertTrue(end > start, () -> "Unterminated section: " + sectionHeader);
        return cCode.substring(start, end);
    }

    @Test
    void generateSkipsInstanceSynthesisForStaticProperties() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(staticProperty("count", GdIntType.INT));
        workerClass.addProperty(new LirPropertyDef("speed", GdFloatType.FLOAT));
        var module = new LirModule("static_skip_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        // Backing storage is defined once at the top of entry.c (single translation unit);
        // instance member synthesis covers only `speed`.
        assertTrue(cCode.contains("godot_int gdcc_static_Worker_count;"), cCode);
        assertFalse(hCode.contains("gdcc_static_Worker_count"), hCode);
        assertTrue(cCode.contains("Worker_class_apply_property_init_speed"), cCode);
        assertFalse(cCode.contains("_field_getter_count"), cCode);
        assertFalse(cCode.contains("_field_setter_count"), cCode);
        assertFalse(cCode.contains("Worker_class_apply_property_init_count"), cCode);
        // No instance-field access path may reference the static name.
        assertFalse(cCode.contains("self->count"), cCode);
        assertFalse(hCode.contains("self->count"), hCode);
        // initFunc freeze: backend must not synthesize a default `_field_init_` helper for statics.
        var countProperty = workerClass.getProperties().stream()
                .filter(property -> property.getName().equals("count"))
                .findFirst()
                .orElseThrow();
        assertNull(countProperty.getInitFunc());
        assertNull(countProperty.getGetterFunc());
        assertNull(countProperty.getSetterFunc());
    }

    @Test
    void generateEmitsTwoPhaseStaticInitInBaseBeforeDerivedOrder() {
        // Module order is deliberately derived-first to prove the inheritance topology reorder.
        var subClass = new LirClassDef("Sub", "Base");
        subClass.addProperty(staticProperty("sub_title", GdStringType.STRING));
        var baseClass = new LirClassDef("Base", "RefCounted");
        baseClass.addProperty(staticProperty("base_title", GdStringType.STRING));
        var module = new LirModule("static_order_module", List.of(subClass, baseClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var cCode = generatedFileText(codegen.generate(), "entry.c");

        // Phase 1 (defaults) fully precedes phase 2 (initializers); both are base-before-derived.
        var defaultsBase = cCode.indexOf("gdcc_static_defaults_Base();");
        var defaultsSub = cCode.indexOf("gdcc_static_defaults_Sub();");
        var initializersBase = cCode.indexOf("gdcc_static_initializers_Base();");
        var initializersSub = cCode.indexOf("gdcc_static_initializers_Sub();");
        assertTrue(defaultsBase >= 0 && initializersSub >= 0, cCode);
        assertTrue(defaultsBase < defaultsSub, cCode);
        assertTrue(defaultsSub < initializersBase, cCode);
        assertTrue(initializersBase < initializersSub, cCode);

        // deinitialize() destroys in reverse initialization order, before the runtime registries.
        var subDestroy = cCode.indexOf("godot_String_destroy(&(gdcc_static_Sub_sub_title))");
        var baseDestroy = cCode.indexOf("godot_String_destroy(&(gdcc_static_Base_base_title))");
        var registryDestroy = cCode.indexOf("gdcc_sn_registry_destroy_all()");
        assertTrue(subDestroy >= 0 && baseDestroy > subDestroy, cCode);
        assertTrue(registryDestroy > baseDestroy, cCode);

        // Defaults sections first-write zero-initialized storage: no destroy of the backing there.
        var defaultsSection = extractSection(cCode, "static void gdcc_static_defaults_Base(void)");
        assertTrue(defaultsSection.contains("gdcc_static_Base_base_title ="), defaultsSection);
        assertFalse(defaultsSection.contains("godot_String_destroy"), defaultsSection);
    }

    @Test
    void generateEmitsStructDefinitionsAndClassRegistrationInBaseBeforeDerivedOrder() {
        // Module order is deliberately derived-first to prove the inheritance topology reorder;
        // the unrelated solo class pins that classes ready in the same pass keep their
        // module-relative order (Solo precedes Root in module order and neither blocks the other).
        var leafClass = new LirClassDef("GdTopoLeaf", "GdTopoMid");
        var midClass = new LirClassDef("GdTopoMid", "GdTopoRoot");
        var soloClass = new LirClassDef("GdTopoSolo", "RefCounted");
        var rootClass = new LirClassDef("GdTopoRoot", "RefCounted");
        var module = new LirModule("topo_order_module", List.of(leafClass, midClass, soloClass, rootClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        // A non-root wrapper embeds its parent BY VALUE as the first field, so the parent struct
        // definition must be complete before the child one (C has no by-value incomplete fields).
        assertOrdered(hCode,
                "struct GdTopoSolo {",
                "struct GdTopoRoot {",
                "struct GdTopoMid {",
                "struct GdTopoLeaf {");
        assertOrdered(hCode,
                "struct GdTopoRoot {",
                "GdTopoRoot _super;",
                "GdTopoMid _super;");

        // Godot rejects registering an extension class whose parent extension class is not
        // registered yet, so ClassDB registration must follow the same base-before-derived order.
        // The `();` anchor matches only the call sites inside initialize(), never the later
        // `void <Class>_class_bind_methods() {` definitions.
        assertOrdered(cCode,
                "GdTopoSolo_class_bind_methods();",
                "GdTopoRoot_class_bind_methods();",
                "GdTopoMid_class_bind_methods();",
                "GdTopoLeaf_class_bind_methods();");
    }

    @Test
    void generateEmitsStaticInitAsFilteredInheritanceOrder() {
        // Locks the filtered-order semantics of the shared topology: transitive static
        // dependencies order through non-static intermediates (GdStaticD before GdStaticA via
        // GdNonStaticB), and a class blocked behind its chain may land after unrelated static
        // classes that follow it in module order (GdStaticC before GdStaticA). Only
        // base-before-derived is contractual; the exact sequence pins determinism.
        var classA = new LirClassDef("GdStaticA", "GdNonStaticB");
        classA.addProperty(staticProperty("a_title", GdStringType.STRING));
        var classC = new LirClassDef("GdStaticC", "RefCounted");
        classC.addProperty(staticProperty("c_title", GdStringType.STRING));
        var classB = new LirClassDef("GdNonStaticB", "GdStaticD");
        var classD = new LirClassDef("GdStaticD", "RefCounted");
        classD.addProperty(staticProperty("d_title", GdStringType.STRING));
        var module = new LirModule("static_filtered_order_module", List.of(classA, classC, classB, classD));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var cCode = generatedFileText(codegen.generate(), "entry.c");

        assertOrdered(cCode,
                "gdcc_static_defaults_GdStaticC();",
                "gdcc_static_defaults_GdStaticD();",
                "gdcc_static_defaults_GdStaticA();");
        assertOrdered(cCode,
                "gdcc_static_initializers_GdStaticC();",
                "gdcc_static_initializers_GdStaticD();",
                "gdcc_static_initializers_GdStaticA();");
    }

    @Test
    void generateFailsFastOnModuleInheritanceCycle() {
        // The frontend rejects inheritance cycles before lowering; the backend still fails fast
        // on hand-built cyclic modules instead of emitting silently unordered C. The vtable
        // planner mounted on the helper chain now detects the GDCC cycle already at prepare(),
        // earlier than generate()'s own topology guard, so the assertion covers the whole
        // prepare/generate sequence.
        var classA = new LirClassDef("GdCycleA", "GdCycleB");
        var classB = new LirClassDef("GdCycleB", "GdCycleA");
        var module = new LirModule("topo_cycle_module", List.of(classA, classB));
        var codegen = new CCodegen();

        var exception = assertThrows(IllegalStateException.class, () -> {
            codegen.prepare(newStaticTestContext(), module);
            codegen.generate();
        });
        assertTrue(
                exception.getMessage().contains("inheritance cycle") || exception.getMessage().contains("Inheritance cycle"),
                exception.getMessage()
        );
    }

    @Test
    void generateRoutesStaticInitializerThroughOverwriteSemantics() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var titleProperty = new LirPropertyDef("title", GdStringType.STRING, true, "_field_init_title", null, null, Map.of());
        workerClass.addProperty(titleProperty);
        workerClass.addFunction(staticInitFunction("_field_init_title", GdStringType.STRING));
        var module = new LirModule("static_init_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var cCode = generatedFileText(codegen.generate(), "entry.c");

        // The initializers section calls the hidden static helper into a carrier temp, destroys
        // the already-materialized default, then MOVES the carrier in by plain struct assignment
        // (destroy-then-move): no copy construction, and the moved-from carrier is never
        // destroyed.
        var initializersSection = extractSection(cCode, "static void gdcc_static_initializers_Worker(void)");
        var callIndex = initializersSection.indexOf("= Worker__field_init_title();");
        var destroyIndex = initializersSection.indexOf("godot_String_destroy(&gdcc_static_Worker_title)");
        var moveMatcher = Pattern.compile("gdcc_static_Worker_title = __gdcc_tmp_owned_move_\\d+;")
                .matcher(initializersSection);
        assertTrue(callIndex >= 0, initializersSection);
        assertTrue(destroyIndex > callIndex, initializersSection);
        assertTrue(moveMatcher.find(destroyIndex), initializersSection);
        assertFalse(initializersSection.contains("godot_new_String_with_String"), initializersSection);
        assertFalse(initializersSection.contains("godot_String_destroy(&__gdcc_tmp"), initializersSection);
    }

    @Test
    void generateRunsStaticInitAfterCoroutineStateClassRegistration() {
        // Both static init phases must run only after ALL classes — including hidden
        // coroutine state classes — are registered, since an initializer may start a coroutine.
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(staticProperty("count", GdIntType.INT));
        var coroutineFunc = new LirFunctionDef("work");
        coroutineFunc.setReturnType(GdVoidType.VOID);
        coroutineFunc.setStatic(true);
        coroutineFunc.setHidden(true);
        coroutineFunc.setCoroutine(true);
        var entry = new LirBasicBlock("entry");
        entry.setTerminator(new ReturnInsn(null));
        coroutineFunc.addBasicBlock(entry);
        coroutineFunc.setEntryBlockId("entry");
        workerClass.addFunction(coroutineFunc);
        var module = new LirModule("static_coroutine_order_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var cCode = generatedFileText(codegen.generate(), "entry.c");

        // First occurrence of the state-class factory is its registration inside initialize().
        var stateRegistration = cCode.indexOf("_gdcc_coro_state_Worker__coro__work_class_create_instance");
        var defaultsCall = cCode.indexOf("gdcc_static_defaults_Worker();");
        var initializersCall = cCode.indexOf("gdcc_static_initializers_Worker();");
        assertTrue(stateRegistration >= 0, cCode);
        assertTrue(defaultsCall > stateRegistration, cCode);
        assertTrue(initializersCall > stateRegistration, cCode);
        assertTrue(defaultsCall < initializersCall, cCode);
    }

    @Test
    void generateSeedsFixedModuleSymbolsIntoConflictCheck() {
        // Class `gdextension` + function `entry` would spell the exported `gdextension_entry`.
        var clashClass = new LirClassDef("gdextension", "RefCounted");
        var entryFunc = new LirFunctionDef("entry");
        entryFunc.setReturnType(GdVoidType.VOID);
        clashClass.addFunction(entryFunc);
        var module = new LirModule("fixed_symbol_clash_module", List.of(clashClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var ex = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(ex.getMessage().contains("C file-scope symbol conflict: 'gdextension_entry'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("GDExtension entry point"), ex.getMessage());
    }

    @Test
    void generateOmitsStaticSectionsWhenNoStaticProperties() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(new LirPropertyDef("speed", GdFloatType.FLOAT));
        var module = new LirModule("no_static_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");
        var hCode = generatedFileText(files, "entry.h");

        // Modules without static properties keep byte-stable output: no backing storage,
        // no lifecycle entries, no two-phase calls.
        assertFalse(cCode.contains("gdcc_static_"), cCode);
        assertFalse(hCode.contains("gdcc_static_"), hCode);
    }

    @Test
    void generateEmitsObjectStaticLifecycleThreeStateForms() throws Exception {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        // YES (RefCounted-derived GDCC) with an object-producing initializer.
        workerClass.addProperty(new LirPropertyDef("peer", new GdObjectType("Worker"), true, "_field_init_peer", null, null, Map.of()));
        // UNKNOWN (`Object` root) and NO (non-RefCounted engine class) runtime statics.
        workerClass.addProperty(staticProperty("target", new GdObjectType("Object")));
        workerClass.addProperty(staticProperty("node", new GdObjectType("Node")));
        var initFunc = new LirFunctionDef("_field_init_peer");
        initFunc.setHidden(true);
        initFunc.setStatic(true);
        initFunc.setReturnType(new GdObjectType("Worker"));
        var tmpVar = initFunc.createAndAddTmpVariable(new GdObjectType("Worker"));
        var entry = new LirBasicBlock("entry");
        initFunc.addBasicBlock(entry);
        entry.appendNonTerminatorInstruction(new ConstructObjectInsn(tmpVar.id(), "Worker"));
        entry.setTerminator(new ReturnInsn(tmpVar.id()));
        initFunc.setEntryBlockId("entry");
        workerClass.addFunction(initFunc);
        var module = new LirModule("static_object_module", List.of(workerClass));
        var projectInfo = new ProjectInfo("static_object_test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, new ClassRegistry(ExtensionApiLoader.loadDefault()));
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);

        var cCode = generatedFileText(codegen.generate(), "entry.c");

        // Initializer overwrite (OWNED object): the call result is materialized FIRST (before the
        // old slot is captured, so re-entrant writes from the initializer cannot leak), then
        // capture old -> assign carrier -> release old, NO re-own, carrier never destroyed.
        var initializersSection = extractSection(cCode, "static void gdcc_static_initializers_Worker(void)");
        var callIndex = initializersSection.indexOf("= Worker__field_init_peer();");
        var captureIndex = initializersSection.indexOf("= gdcc_static_Worker_peer;");
        var moveMatcher = Pattern.compile("gdcc_static_Worker_peer = __gdcc_tmp_owned_move_\\d+;")
                .matcher(initializersSection);
        assertTrue(callIndex >= 0, initializersSection);
        assertTrue(captureIndex > callIndex, initializersSection);
        assertTrue(moveMatcher.find(captureIndex), initializersSection);
        assertFalse(initializersSection.contains("own_object"), initializersSection);
        assertTrue(initializersSection.indexOf("release_object(", moveMatcher.end()) > moveMatcher.end(), initializersSection);

        // deinitialize(): YES -> release_object, UNKNOWN -> try_release_object with cached id,
        // NO -> no cleanup statement at all.
        var deinitializeSection = extractSection(cCode, "void deinitialize(void* userdata, GDExtensionInitializationLevel p_level)");
        assertTrue(deinitializeSection.contains("release_object(gdcc_Worker_fat_ptr_live_object(gdcc_static_Worker_peer));"), deinitializeSection);
        assertTrue(deinitializeSection.contains("try_release_object(gdcc_Object_fat_ptr_live_object(gdcc_static_Worker_target), gdcc_static_Worker_target.instance_id);"), deinitializeSection);
        assertFalse(deinitializeSection.contains("gdcc_static_Worker_node"), deinitializeSection);
    }

    @Test
    void generateFailsFastOnFileScopeSymbolConflictBetweenFunctions() {
        // `A` + function `B_c` and class `A_B` + function `c` both spell `A_B_c`.
        var plainClass = new LirClassDef("A", "RefCounted");
        var clashFunc = new LirFunctionDef("B_c");
        clashFunc.setReturnType(GdVoidType.VOID);
        plainClass.addFunction(clashFunc);
        var compoundClass = new LirClassDef("A_B", "RefCounted");
        var otherFunc = new LirFunctionDef("c");
        otherFunc.setReturnType(GdVoidType.VOID);
        compoundClass.addFunction(otherFunc);
        var module = new LirModule("symbol_clash_module", List.of(plainClass, compoundClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var ex = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(ex.getMessage().contains("C file-scope symbol conflict: 'A_B_c'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("A.B_c"), ex.getMessage());
        assertTrue(ex.getMessage().contains("A_B.c"), ex.getMessage());
    }

    @Test
    void generateFailsFastOnStaticBackingSymbolConflict() {
        // `A.b_c` and `A_b.c` both spell `gdcc_static_A_b_c`.
        var plainClass = new LirClassDef("A", "RefCounted");
        plainClass.addProperty(staticProperty("b_c", GdIntType.INT));
        var compoundClass = new LirClassDef("A_b", "RefCounted");
        compoundClass.addProperty(staticProperty("c", GdIntType.INT));
        var module = new LirModule("backing_clash_module", List.of(plainClass, compoundClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var ex = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(ex.getMessage().contains("C file-scope symbol conflict: 'gdcc_static_A_b_c'"), ex.getMessage());
    }

    @Test
    void generateRejectsStaticInitFunctionWithParameters() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(new LirPropertyDef("count", GdIntType.INT, true, "_field_init_count", null, null, Map.of()));
        var badInit = staticInitFunction("_field_init_count", GdIntType.INT);
        badInit.addParameter(new LirParameterDef("unexpected", GdIntType.INT, null, badInit));
        workerClass.addFunction(badInit);
        var module = new LirModule("static_init_param_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var ex = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(ex.getMessage().contains("expected zero parameters"), ex.getMessage());
    }

    @Test
    void generateRejectsNonStaticInitFunctionForStaticProperty() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(new LirPropertyDef("count", GdIntType.INT, true, "_field_init_count", null, null, Map.of()));
        var badInit = staticInitFunction("_field_init_count", GdIntType.INT);
        badInit.setStatic(false);
        workerClass.addFunction(badInit);
        var module = new LirModule("static_init_flag_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var ex = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(ex.getMessage().contains("must be static"), ex.getMessage());
    }

    @Test
    void generateRejectsMissingStaticInitFunction() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(new LirPropertyDef("count", GdIntType.INT, true, "_field_init_count", null, null, Map.of()));
        var module = new LirModule("static_init_missing_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var ex = assertThrows(IllegalStateException.class, codegen::generate);
        assertTrue(ex.getMessage().contains("does not exist"), ex.getMessage());
    }

    @Test
    void generateEmitsTypedArrayStaticDefaultThroughConstructor() {
        var workerClass = new LirClassDef("Worker", "RefCounted");
        workerClass.addProperty(staticProperty("items", new GdArrayType(GdIntType.INT)));
        var module = new LirModule("static_typed_default_module", List.of(workerClass));
        var codegen = new CCodegen();
        codegen.prepare(newStaticTestContext(), module);

        var cCode = generatedFileText(codegen.generate(), "entry.c");

        // Typed containers materialize via the builtin constructor (element-type metadata kept),
        // then move into the backing slot — never a bare untyped `godot_new_Array()` assignment.
        var defaultsSection = extractSection(cCode, "static void gdcc_static_defaults_Worker(void)");
        assertTrue(defaultsSection.contains("gdcc_static_Worker_items ="), defaultsSection);
        assertTrue(defaultsSection.contains("GDEXTENSION_VARIANT_TYPE_INT"), defaultsSection);
        assertFalse(defaultsSection.contains("gdcc_static_Worker_items = godot_new_Array();"), defaultsSection);
    }

    private static String generatedFileText(List<GeneratedFile> files, String filePath) {
        for (var file : files) {
            if (file.filePath().equals(filePath)) {
                return new String(file.contentWriter());
            }
        }
        throw new AssertionError("Missing generated file: " + filePath);
    }

    private static String generateHeader(LirModule module) throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var classRegistry = new ClassRegistry(api);
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        var files = codegen.generate();
        return generatedFileText(files, "entry.h");
    }

    private static int countOccurrences(String text, String needle) {
        var count = 0;
        var fromIndex = 0;
        while (true) {
            var index = text.indexOf(needle, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + needle.length();
        }
    }

    private static Throwable findRootCause(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static ExtensionAPI evaluatorIntApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var boolBuiltin = new ExtensionBuiltinClass(
                "bool",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("not", "", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var stringBuiltin = new ExtensionBuiltinClass(
                "String",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("+", "String", "String")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin, boolBuiltin, stringBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionAPI stringFormatApi() {
        var stringBuiltin = new ExtensionBuiltinClass(
                "String",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("%", "Array", "String")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(stringBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionAPI evaluatorSwapFallbackApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("<", "String", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    @Test
    public void sourceParameterDefaultsKeepBindRegistrationChannelEmpty() throws Exception {
        var workerClass = new LirClassDef("DefaultBindWorker", "RefCounted");

        // Hidden synthetic default shell: excluded from binding but prototyped/defined normally.
        var shell = new LirFunctionDef("_default_ping$count");
        shell.setHidden(true);
        shell.setReturnType(GdIntType.INT);
        shell.addParameter(new LirParameterDef("self", new GdObjectType("DefaultBindWorker"), null, shell));
        var shellResult = shell.createAndAddTmpVariable(GdIntType.INT);
        var shellEntry = new LirBasicBlock("entry");
        shellEntry.appendInstruction(new LiteralIntInsn(shellResult.id(), 40));
        shellEntry.setTerminator(new ReturnInsn(shellResult.id()));
        shell.addBasicBlock(shellEntry);
        shell.setEntryBlockId("entry");
        workerClass.addFunction(shell);

        var ping = new LirFunctionDef("ping");
        ping.setReturnType(GdIntType.INT);
        ping.addParameter(new LirParameterDef("self", new GdObjectType("DefaultBindWorker"), null, ping));
        ping.addParameter(new LirParameterDef("base", GdIntType.INT, null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_ping$count", ping));
        var pingEntry = new LirBasicBlock("entry");
        pingEntry.setTerminator(new ReturnInsn("base"));
        ping.addBasicBlock(pingEntry);
        ping.setEntryBlockId("entry");
        workerClass.addFunction(ping);

        var module = new LirModule("default_bind_isolation_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var files = codegen.generate();
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        // The bind helper must not grow default_N_value formals and must not register
        // method_info.default_arguments for GDCC source defaults; the default-slot count feeds
        // the wrapper shape name instead.
        var bindHelperBody = resolveMethodBindHelperBody(hCode, "_2_arg_int_int_ret_int_1_defslot");
        assertFalse(bindHelperBody.contains("default_"), bindHelperBody);
        assertFalse(bindHelperBody.contains("default_argument_count"), bindHelperBody);

        // The registration call hands over the per-method default userdata (not the raw impl
        // pointer) plus name/type metadata, so the helper signature and call site stay linkable.
        var bindCall = resolveMethodBindCall(cCode, "ping");
        assertTrue(bindCall.contains("&DefaultBindWorker_ping$default_ud"), bindCall);
        assertFalse(bindCall.contains("DefaultBindWorker_ping,"), bindCall);
        assertTrue(bindCall.contains("_1_defslot"), bindCall);
        assertFalse(bindCall.contains("_default_ping"), bindCall);

        // The hidden shell keeps its raw `$` C symbol at both prototype (entry.h) and definition
        // (entry.c) sites — the spelling shared by definition, call site and conflict check.
        assertTrue(hCode.contains("DefaultBindWorker__default_ping$count"), hCode);
        assertTrue(cCode.contains("DefaultBindWorker__default_ping$count"), cCode);
    }

    @Test
    public void sourceParameterDefaultsGenerateArgcAwareWrapperAndExclusiveUserdata() throws Exception {
        var workerClass = new LirClassDef("DefaultWrapperWorker", "RefCounted");

        // Hidden instance shells declare the owner-typed self as first parameter.
        var shellCount = new LirFunctionDef("_default_describe$count");
        shellCount.setHidden(true);
        shellCount.setReturnType(GdIntType.INT);
        shellCount.addParameter(new LirParameterDef("self", new GdObjectType("DefaultWrapperWorker"), null, shellCount));
        var shellCountResult = shellCount.createAndAddTmpVariable(GdIntType.INT);
        var shellCountEntry = new LirBasicBlock("entry");
        shellCountEntry.appendInstruction(new LiteralIntInsn(shellCountResult.id(), 40));
        shellCountEntry.setTerminator(new ReturnInsn(shellCountResult.id()));
        shellCount.addBasicBlock(shellCountEntry);
        shellCount.setEntryBlockId("entry");
        workerClass.addFunction(shellCount);

        var shellLabel = new LirFunctionDef("_default_describe$label");
        shellLabel.setHidden(true);
        shellLabel.setReturnType(GdStringType.STRING);
        shellLabel.addParameter(new LirParameterDef("self", new GdObjectType("DefaultWrapperWorker"), null, shellLabel));
        var shellLabelResult = shellLabel.createAndAddTmpVariable(GdStringType.STRING);
        var shellLabelEntry = new LirBasicBlock("entry");
        shellLabelEntry.appendInstruction(new LiteralStringInsn(shellLabelResult.id(), "fallback"));
        shellLabelEntry.setTerminator(new ReturnInsn(shellLabelResult.id()));
        shellLabel.addBasicBlock(shellLabelEntry);
        shellLabel.setEntryBlockId("entry");
        workerClass.addFunction(shellLabel);

        // Hidden static shell (no parameters).
        var shellStatic = new LirFunctionDef("_default_s_static_ping$count");
        shellStatic.setHidden(true);
        shellStatic.setStatic(true);
        shellStatic.setReturnType(GdIntType.INT);
        var shellStaticResult = shellStatic.createAndAddTmpVariable(GdIntType.INT);
        var shellStaticEntry = new LirBasicBlock("entry");
        shellStaticEntry.appendInstruction(new LiteralIntInsn(shellStaticResult.id(), 7));
        shellStaticEntry.setTerminator(new ReturnInsn(shellStaticResult.id()));
        shellStatic.addBasicBlock(shellStaticEntry);
        shellStatic.setEntryBlockId("entry");
        workerClass.addFunction(shellStatic);

        var describe = new LirFunctionDef("describe");
        describe.setReturnType(GdIntType.INT);
        describe.addParameter(new LirParameterDef("self", new GdObjectType("DefaultWrapperWorker"), null, describe));
        describe.addParameter(new LirParameterDef("base", GdIntType.INT, null, describe));
        describe.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_describe$count", describe));
        describe.addParameter(new LirParameterDef("label", GdStringType.STRING, "_default_describe$label", describe));
        var describeEntry = new LirBasicBlock("entry");
        describeEntry.setTerminator(new ReturnInsn("base"));
        describe.addBasicBlock(describeEntry);
        describe.setEntryBlockId("entry");
        workerClass.addFunction(describe);

        var staticPing = new LirFunctionDef("static_ping");
        staticPing.setStatic(true);
        staticPing.setReturnType(GdIntType.INT);
        staticPing.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_s_static_ping$count", staticPing));
        var staticPingEntry = new LirBasicBlock("entry");
        staticPingEntry.setTerminator(new ReturnInsn("count"));
        staticPing.addBasicBlock(staticPingEntry);
        staticPing.setEntryBlockId("entry");
        workerClass.addFunction(staticPing);

        // Same-arity method without defaults: must keep the zero-default flavor untouched.
        var ping = new LirFunctionDef("ping");
        ping.setReturnType(GdIntType.INT);
        ping.addParameter(new LirParameterDef("self", new GdObjectType("DefaultWrapperWorker"), null, ping));
        ping.addParameter(new LirParameterDef("base", GdIntType.INT, null, ping));
        var pingEntry = new LirBasicBlock("entry");
        pingEntry.setTerminator(new ReturnInsn("base"));
        ping.addBasicBlock(pingEntry);
        ping.setEntryBlockId("entry");
        workerClass.addFunction(ping);

        var module = new LirModule("default_wrapper_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var files = codegen.generate();
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        // Per-shape userdata typedef with per-slot typed default function pointers
        // (heterogeneous int + String), instance flavor taking owner fat self. impl keeps the
        // exact function-pointer type (no function-pointer <-> void* round-trip).
        assertContainsAll(
                hCode,
                "godot_int (*impl)(gdcc_DefaultWrapperWorker_fat_ptr, godot_int, godot_int, godot_String*);",
                "godot_int (*def0)(gdcc_DefaultWrapperWorker_fat_ptr);",
                "godot_String (*def1)(gdcc_DefaultWrapperWorker_fat_ptr);",
                "} gdcc_default_ud_DefaultWrapperWorker_3_arg_int_int_String_ret_int_2_defslot;",
                // Static flavor: receiver-less slot signature.
                "godot_int (*impl)(godot_int);",
                "godot_int (*def0)();",
                "} gdcc_default_ud_1_arg_int_ret_int_1_defslot_static;"
        );

        // Call wrapper: argc guard with required-count expected, gates/probes conditional
        // on supplied arguments, per-slot unpack-or-default, self_fat before any fill.
        var callBody = resolveCallWrapperBody(hCode, "_3_arg_int_int_String_ret_int_2_defslot");
        assertContainsAll(
                callBody,
                "if (p_argument_count < 1)",
                "r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;",
                "r_error->expected = 1;",
                "if (p_argument_count > 3)",
                "r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;",
                "r_error->expected = 3;",
                "godot_int arg1;",
                "godot_String arg2;",
                "arg1 = godot_new_int_with_Variant((GDExtensionVariantPtr)p_args[1]);",
                "arg1 = ud->def0(self_fat);",
                "arg2 = ud->def1(self_fat);",
                "= ud->impl;",
                // Default-filled destroyable values join the same reverse cleanup epilogue.
                "godot_String_destroy(&arg2);"
        );
        // self_fat materialization strictly precedes userdata unwrap and every default fill.
        assertOrdered(
                callBody,
                "self_fat = ",
                "* ud = method_userdata;",
                "ud->def0(self_fat)",
                "ud->def1(self_fat)"
        );
        // Required slot 0 keeps the unconditional gate; default slots are guarded by argc.
        assertFalse(callBody.contains("if (p_argument_count > 0)"), callBody);
        assertTrue(callBody.contains("if (p_argument_count > 1)"), callBody);
        assertTrue(callBody.contains("if (p_argument_count > 2)"), callBody);

        // Static flavor: receiver-less default invocation.
        var staticCallBody = resolveCallWrapperBody(hCode, "_1_arg_int_ret_int_1_defslot_static");
        assertTrue(staticCallBody.contains("arg0 = ud->def0();"), staticCallBody);
        assertFalse(staticCallBody.contains("self_fat"), staticCallBody);

        // ptrcall keeps the full-argument ABI but must unwrap impl from the same userdata.
        var ptrcallBody = resolveFunctionBodyByPrefix(
                hCode,
                resolveOwnedWrapperPrefix(hCode, "static void ptrcall", "_3_arg_int_int_String_ret_int_2_defslot")
        );
        assertTrue(ptrcallBody.contains("= ud->impl;"), ptrcallBody);
        assertFalse(ptrcallBody.contains("p_argument_count"), ptrcallBody);
        assertFalse(ptrcallBody.contains("ud->def"), ptrcallBody);

        // The registration site owns a per-method exclusive static userdata instance, filled
        // in declaration order, passed through the existing void* function formal.
        assertOrdered(
                cCode,
                "static gdcc_default_ud_DefaultWrapperWorker_3_arg_int_int_String_ret_int_2_defslot DefaultWrapperWorker_describe$default_ud = {",
                "DefaultWrapperWorker_describe,",
                "DefaultWrapperWorker__default_describe$count,",
                "DefaultWrapperWorker__default_describe$label,"
        );
        var describeBindCall = resolveMethodBindCall(cCode, "describe");
        assertTrue(describeBindCall.contains("&DefaultWrapperWorker_describe$default_ud"), describeBindCall);
        assertTrue(cCode.contains(
                "static gdcc_default_ud_1_arg_int_ret_int_1_defslot_static DefaultWrapperWorker_static_ping$default_ud = {"
        ), cCode);
        assertTrue(cCode.contains("DefaultWrapperWorker__default_s_static_ping$count,"), cCode);

        // Zero-default regression: impl pointer still flows directly as method_userdata.
        var pingBindCall = resolveMethodBindCall(cCode, "ping");
        assertTrue(pingBindCall.contains("DefaultWrapperWorker_ping,"), pingBindCall);
        assertFalse(cCode.contains("DefaultWrapperWorker_ping$default_ud"), cCode);
        var pingCallBody = resolveCallWrapperBody(hCode, "_1_arg_int_ret_int");
        assertTrue(pingCallBody.contains("= method_userdata;"), pingCallBody);
        assertFalse(pingCallBody.contains("ud->"), pingCallBody);

        // Bind-time isolation still holds under the defslot flavor: no bind-time default
        // Variant channel anywhere in the generated registration surface.
        assertFalse(hCode.contains("default_argument_count"), hCode);
        assertFalse(hCode.contains("default_0_value"), hCode);
    }

    @Test
    public void virtualOverrideWithDefaultsSharesDefaultUserdataBetweenClassDBAndVirtualDispatch() throws Exception {
        var workerClass = new LirClassDef("VirtualDefaultDispatch", "Node");

        // Hidden instance shell for `_process`'s delta default.
        var shell = new LirFunctionDef("_default__process$delta");
        shell.setHidden(true);
        shell.setReturnType(GdFloatType.FLOAT);
        shell.addParameter(new LirParameterDef("self", new GdObjectType("VirtualDefaultDispatch"), null, shell));
        var shellResult = shell.createAndAddTmpVariable(GdFloatType.FLOAT);
        var shellEntry = new LirBasicBlock("entry");
        shellEntry.appendInstruction(new LiteralFloatInsn(shellResult.id(), 0.0));
        shellEntry.setTerminator(new ReturnInsn(shellResult.id()));
        shell.addBasicBlock(shellEntry);
        shell.setEntryBlockId("entry");
        workerClass.addFunction(shell);

        var process = new LirFunctionDef("_process");
        process.setReturnType(GdVoidType.VOID);
        process.addParameter(new LirParameterDef("self", new GdObjectType("VirtualDefaultDispatch"), null, process));
        process.addParameter(new LirParameterDef("delta", GdFloatType.FLOAT, "_default__process$delta", process));
        var processEntry = new LirBasicBlock("entry");
        processEntry.setTerminator(new ReturnInsn(null));
        process.addBasicBlock(processEntry);
        process.setEntryBlockId("entry");
        workerClass.addFunction(process);

        var module = new LirModule("virtual_default_dispatch_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");

        // The engine's virtual callback reaches the defslot ptrcall flavor through
        // p_virtual_call_userdata; it must be the same per-method userdata instance the ClassDB
        // registration handed over, never the raw impl address (which the wrapper would
        // dereference as a struct).
        assertOrdered(
                cCode,
                "static gdcc_default_ud_VirtualDefaultDispatch_1_arg_float_no_ret_1_defslot VirtualDefaultDispatch__process$default_ud = {",
                "VirtualDefaultDispatch__process,",
                "VirtualDefaultDispatch__default__process$delta,"
        );
        var lookupBody = resolveFunctionBodyByPrefix(
                cCode,
                "void* VirtualDefaultDispatch_class_get_virtual_with_data("
        );
        assertTrue(lookupBody.contains("return (void*)&VirtualDefaultDispatch__process$default_ud;"), lookupBody);
        assertFalse(lookupBody.contains("return (void*)VirtualDefaultDispatch__process;"), lookupBody);
        var dispatchBody = resolveFunctionBodyByPrefix(
                cCode,
                "void VirtualDefaultDispatch_class_call_virtual_with_data("
        );
        assertTrue(dispatchBody.contains("p_virtual_call_userdata == &VirtualDefaultDispatch__process$default_ud"), dispatchBody);
        assertTrue(dispatchBody.contains("ptrcall_VirtualDefaultDispatch_1_arg_float_no_ret_1_defslot("), dispatchBody);
    }

    @Test
    public void defaultUserdataInstanceNameCannotCollideWithUserFunction() throws Exception {
        var workerClass = new LirClassDef("CollisionWorker", "RefCounted");

        var shell = new LirFunctionDef("_default_foo$a");
        shell.setHidden(true);
        shell.setReturnType(GdIntType.INT);
        shell.addParameter(new LirParameterDef("self", new GdObjectType("CollisionWorker"), null, shell));
        var shellResult = shell.createAndAddTmpVariable(GdIntType.INT);
        var shellEntry = new LirBasicBlock("entry");
        shellEntry.appendInstruction(new LiteralIntInsn(shellResult.id(), 1));
        shellEntry.setTerminator(new ReturnInsn(shellResult.id()));
        shell.addBasicBlock(shellEntry);
        shell.setEntryBlockId("entry");
        workerClass.addFunction(shell);

        var foo = new LirFunctionDef("foo");
        foo.setReturnType(GdVoidType.VOID);
        foo.addParameter(new LirParameterDef("self", new GdObjectType("CollisionWorker"), null, foo));
        foo.addParameter(new LirParameterDef("a", GdIntType.INT, "_default_foo$a", foo));
        var fooEntry = new LirBasicBlock("entry");
        fooEntry.setTerminator(new ReturnInsn(null));
        foo.addBasicBlock(fooEntry);
        foo.setEntryBlockId("entry");
        workerClass.addFunction(foo);

        // A legal user function whose C symbol would collide with an underscore-joined
        // `<method>_default_ud` userdata instance name.
        var userFunction = new LirFunctionDef("foo_default_ud");
        userFunction.setReturnType(GdVoidType.VOID);
        userFunction.addParameter(new LirParameterDef("self", new GdObjectType("CollisionWorker"), null, userFunction));
        var userEntry = new LirBasicBlock("entry");
        userEntry.setTerminator(new ReturnInsn(null));
        userFunction.addBasicBlock(userEntry);
        userFunction.setEntryBlockId("entry");
        workerClass.addFunction(userFunction);

        var module = new LirModule("default_userdata_collision_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);

        // Must not throw: the `$`-separated instance name is unreachable from user identifiers.
        var files = codegen.generate();
        var cCode = generatedFileText(files, "entry.c");
        assertTrue(cCode.contains("CollisionWorker_foo$default_ud = {"), cCode);
        assertTrue(cCode.contains("CollisionWorker_foo_default_ud("), cCode);
    }

    @Test
    public void objectDefaultFillIsOwnedAndReleasedOnlyOnTheDefaultBranch() throws Exception {
        var workerClass = new LirClassDef("ObjectDefaultWorker", "RefCounted");

        var shell = new LirFunctionDef("_default_take$target");
        shell.setHidden(true);
        shell.setReturnType(new GdObjectType("RefCounted"));
        shell.addParameter(new LirParameterDef("self", new GdObjectType("ObjectDefaultWorker"), null, shell));
        var shellResult = shell.createAndAddVariable("target", new GdObjectType("RefCounted"));
        var shellEntry = new LirBasicBlock("entry");
        shellEntry.appendInstruction(new ConstructObjectInsn(shellResult.id(), "RefCounted"));
        shellEntry.setTerminator(new ReturnInsn(shellResult.id()));
        shell.addBasicBlock(shellEntry);
        shell.setEntryBlockId("entry");
        workerClass.addFunction(shell);

        var take = new LirFunctionDef("take");
        take.setReturnType(GdVoidType.VOID);
        take.addParameter(new LirParameterDef("self", new GdObjectType("ObjectDefaultWorker"), null, take));
        take.addParameter(new LirParameterDef("target", new GdObjectType("RefCounted"), "_default_take$target", take));
        var takeEntry = new LirBasicBlock("entry");
        takeEntry.setTerminator(new ReturnInsn(null));
        take.addBasicBlock(takeEntry);
        take.setEntryBlockId("entry");
        workerClass.addFunction(take);

        var module = new LirModule("object_default_fill_module", List.of(workerClass));
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        ProjectInfo projectInfo = new ProjectInfo("test", GodotVersion.V451, Path.of(".")) {
        };
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, classRegistry), module);
        var files = codegen.generate();
        var hCode = generatedFileText(files, "entry.h");

        var callBody = resolveCallWrapperBody(hCode, "_1_arg_RefCounted_no_ret_1_defslot");
        // Shell-produced objects are OWNED: the default branch flags them, and the epilogue
        // releases exactly those; the Variant-unpacked (BORROWED) branch stays untouched.
        assertContainsAll(
                callBody,
                "godot_bool arg0_from_default = false;",
                "arg0 = gdcc_RefCounted_fat_ptr_from_variant((GDExtensionVariantPtr)p_args[0]);",
                "arg0 = ud->def0(self_fat);",
                "arg0_from_default = true;",
                "if (arg0_from_default) {",
                "release_object(gdcc_RefCounted_fat_ptr_live_object(arg0));"
        );
        // The release is only reachable behind the from_default guard.
        assertOrdered(
                callBody,
                "arg0_from_default = true;",
                "if (arg0_from_default) {",
                "release_object(gdcc_RefCounted_fat_ptr_live_object(arg0));"
        );
    }

    private static LirFunctionDef newFunction(String name, GdType returnType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static LirBasicBlock entry(LirFunctionDef func) {
        return func.getBasicBlock("entry");
    }

    private static LirInstruction.VariableOperand varOperand(String id) {
        return new LirInstruction.VariableOperand(id);
    }

    private static ModuleLocalGodotBinding moduleLocalConstantBinding() {
        return ModuleLocalGodotBinding.classConstant("Probe", "READY", "13");
    }

    private static ExtensionAPI engineHelperApi() {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(arrayBuiltinWithSizeBuiltin()),
                List.of(
                        nodeClassWithQueueFreeAndStaticFactory(77L, 88L),
                        objectClassWithVarargCall(93L)
                ),
                List.of(),
                List.of()
        );
    }

    private static ExtensionBuiltinClass arrayBuiltinWithSizeBuiltin() {
        var size = new ExtensionBuiltinClass.ClassMethod(
                "size",
                "int",
                false,
                true,
                false,
                false,
                0L,
                List.of(),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(size),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionGdClass nodeClassWithQueueFreeAndStaticFactory(long queueFreeHash, long makeHash) {
        var queueFree = new ExtensionGdClass.ClassMethod(
                "queue_free",
                false,
                false,
                false,
                false,
                queueFreeHash,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of()
        );
        var make = new ExtensionGdClass.ClassMethod(
                "make",
                false,
                false,
                true,
                false,
                makeHash,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("Node"),
                List.of()
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(queueFree, make),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static ExtensionGdClass objectClassWithVarargCall(long hash) {
        var call = new ExtensionGdClass.ClassMethod(
                "call",
                false,
                true,
                false,
                false,
                hash,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("Variant"),
                List.of(new ExtensionFunctionArgument("method", "StringName", null, null))
        );
        return new ExtensionGdClass(
                "Object",
                false,
                true,
                "",
                "core",
                List.of(),
                List.of(call),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
