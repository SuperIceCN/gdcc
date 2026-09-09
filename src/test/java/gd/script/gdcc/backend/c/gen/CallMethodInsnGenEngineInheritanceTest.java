package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.CProjectBuilder;
import gd.script.gdcc.backend.c.build.CProjectInfo;
import gd.script.gdcc.backend.c.build.GodotGdextensionTestRunner;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallMethodInsnGenEngineInheritanceTest {

    @Test
    @DisplayName("CALL_METHOD inheritance should preserve safe GDCC conversion and variant packing in real engine")
    void callMethodInheritanceShouldUseSafeConversionsAndRunInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_method_engine_inheritance");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_method_engine_inheritance",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var hostClass = newInheritanceHostClass();
        var baseClass = newInheritanceBaseClass();
        var childClass = newInheritanceChildClass();
        var peerClass = newInheritancePeerClass();

        var module = new LirModule(
                "call_method_engine_inheritance_module",
                List.of(hostClass, baseClass, childClass, peerClass)
        );
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());
        assertFalse(buildResult.artifacts().isEmpty(), "Compilation should produce extension artifacts.");

        var entrySource = Files.readString(tempDir.resolve("entry.c"));
        assertTrue(
                entrySource.contains("GDInheritanceBaseWorker_base_value(gdcc_GDInheritanceChildWorker_fat_ptr_upcast_to_GDInheritanceBaseWorker($child));"),
                "Child receiver should upcast via fat pointer helper for parent static dispatch."
        );
        assertTrue(
                entrySource.contains("godot_Object_call(gdcc_GDInheritanceBaseWorker_fat_ptr_live_object($baseRef), GD_STATIC_SN(u8\"child_only_consume_peer\")"),
                "Base-typed GDCC receiver should use OBJECT_DYNAMIC dispatch with fat live_object conversion."
        );
        assertTrue(
                entrySource.contains("gdcc_GDInheritancePeerWorker_fat_ptr_to_variant($peer)"),
                "OBJECT_DYNAMIC arg packing should use fat to_variant.\n" + entrySource
        );
        assertFalse(
                entrySource.contains("gdcc_new_Variant_with_gdcc_Object("),
                "Removed helper gdcc_new_Variant_with_gdcc_Object must not appear; pack via fat to_variant."
        );
        assertFalse(
                entrySource.contains("gdcc_object_to_godot_object_ptr("),
                "OBJECT_DYNAMIC path must not emit wrapper-boundary gdcc_object_to_godot_object_ptr."
        );

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "CallMethodInheritanceNode",
                        hostClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(inheritanceEngineTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("gdcc inheritance call_method check passed."), "Inheritance path should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    private static boolean hasZig() {
        return ZigUtil.findZig() != null;
    }

    @Test
    @DisplayName("CALL_METHOD polymorphic dispatch should route through the vtable and devirtualize correctly in real Godot")
    void callMethodPolymorphicVtableDispatchShouldWorkInRealGodot() throws IOException, InterruptedException {
        if (!hasZig()) {
            Assumptions.abort("Zig not found; skipping integration test");
            return;
        }

        var tempDir = Path.of("tmp/test/call_method_vtable_dispatch");
        Files.createDirectories(tempDir);

        var projectInfo = new CProjectInfo(
                "call_method_vtable_dispatch",
                GodotVersion.V451,
                tempDir,
                COptimizationLevel.DEBUG,
                TargetPlatform.getNativePlatform()
        );
        var builder = new CProjectBuilder();
        builder.initProject(projectInfo);

        var hostClass = newVtableDispatchHostClass();
        var module = new LirModule(
                "call_method_vtable_dispatch_module",
                List.of(hostClass,
                        newConstFooClass("GDVtableRootWorker", "RefCounted", "foo", 1),
                        newConstFooClass("GDVtableMidWorker", "GDVtableRootWorker", "foo", 2),
                        newConstFooClass("GDVtableLeafWorker", "GDVtableMidWorker", "foo", 3),
                        newConstFooClass("GDPassAWorker", "RefCounted", "bar", 10),
                        newWorkerClass("GDPassBWorker", "GDPassAWorker"),
                        newConstFooClass("GDPassCWorker", "GDPassBWorker", "bar", 30),
                        newConstFooClass("GDSibBaseWorker", "RefCounted", "baz", 100),
                        newConstFooClass("GDSibOverrideWorker", "GDSibBaseWorker", "baz", 101),
                        newWorkerClass("GDSibPlainWorker", "GDSibBaseWorker"))
        );
        var api = ExtensionApiLoader.loadVersion(GodotVersion.V451);
        var codegen = new CCodegen();
        codegen.prepare(new CodegenContext(projectInfo, new ClassRegistry(api)), module);

        var buildResult = builder.buildProject(projectInfo, codegen);
        assertTrue(buildResult.success(), "Compilation should succeed. Build log:\n" + buildResult.buildLog());

        var entrySource = Files.readString(tempDir.resolve("entry.c"));

        // Polymorphic call sites (§2.6): indirect through the INTRODUCER accessor; the mid-typed
        // receiver is upcast to the root fat type at materialization (owner != introducer).
        var midCallBody = resolveFunctionBody(entrySource, "GDVtableCallHostNode_call_foo_as_mid(");
        assertTrue(midCallBody.contains(
                        "gdcc_GDVtableRootWorker_fat_ptr __gdcc_tmp_vt_recv_0 = gdcc_GDVtableMidWorker_fat_ptr_upcast_to_GDVtableRootWorker($mid);"),
                midCallBody);
        assertTrue(midCallBody.contains(
                        "GDVtableRootWorker_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_foo(__gdcc_tmp_vt_recv_0)"),
                midCallBody);
        assertFalse(midCallBody.contains("GDVtableMidWorker_foo("), midCallBody);

        // Devirtualization anchors: final-overrider and sibling call sites stay direct despite
        // the slot existing (R4 bracket clause / sibling rule in §2.1).
        var leafCallBody = resolveFunctionBody(entrySource, "GDVtableCallHostNode_call_foo_as_leaf(");
        assertTrue(leafCallBody.contains("GDVtableLeafWorker_foo($leaf)"), leafCallBody);
        assertFalse(leafCallBody.contains("_class_vtable("), leafCallBody);
        var plainCallBody = resolveFunctionBody(entrySource, "GDVtableCallHostNode_call_baz_as_plain(");
        assertTrue(plainCallBody.contains(
                        "GDSibBaseWorker_baz(gdcc_GDSibPlainWorker_fat_ptr_upcast_to_GDSibBaseWorker($plain))"),
                plainCallBody);
        assertFalse(plainCallBody.contains("_class_vtable("), plainCallBody);

        // Pass-through chain: the A-typed call site reads A's accessor; pass-through B owns no
        // vtable symbol of any kind (its instances share the ancestor table at runtime).
        var barCallBody = resolveFunctionBody(entrySource, "GDVtableCallHostNode_call_bar_as_a(");
        assertTrue(barCallBody.contains(
                        "GDPassAWorker_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_bar(__gdcc_tmp_vt_recv_0)"),
                barCallBody);
        assertFalse(entrySource.contains("GDPassBWorker_class_vtable"), entrySource);

        var runner = new GodotGdextensionTestRunner(Path.of("test_project"));
        runner.prepareProject(new GodotGdextensionTestRunner.ProjectSetup(
                buildResult.artifacts(),
                List.of(new GodotGdextensionTestRunner.SceneNodeSpec(
                        "VtableDispatchNode",
                        hostClass.getName(),
                        ".",
                        Map.of()
                )),
                new GodotGdextensionTestRunner.TestScriptSpec(vtableDispatchEngineTestScript())
        ));

        var runResult = runner.run(true);
        var combinedOutput = runResult.combinedOutput();

        assertTrue(runResult.stopSignalSeen(), "Godot run should emit stop signal.\nOutput:\n" + combinedOutput);
        assertTrue(combinedOutput.contains("gdcc vtable dispatch check passed."),
                "All vtable dispatch checks should pass.\nOutput:\n" + combinedOutput);
        assertFalse(combinedOutput.contains("dispatch check failed"), "No check should fail.\nOutput:\n" + combinedOutput);
    }

    /// Extracts the body between the braces following a function-definition prefix.
    /// Relies on the entry.c emission contract that user method functions are DEFINED without
    /// preceding in-file prototypes (prototypes live in entry.h), so the first prefix match is
    /// always the definition.
    private static String resolveFunctionBody(String code, String signaturePrefix) {
        var signatureIndex = code.indexOf(signaturePrefix);
        assertTrue(signatureIndex >= 0, () -> "Missing prefix: " + signaturePrefix + "\n" + code);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, () -> "Missing opening brace for " + signaturePrefix);
        var depth = 0;
        for (var index = openBraceIndex; index < code.length(); index++) {
            var ch = code.charAt(index);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return code.substring(openBraceIndex + 1, index);
                }
            }
        }
        throw new AssertionError("Missing closing brace for " + signaturePrefix);
    }

    private static LirClassDef newVtableDispatchHostClass() {
        var clazz = new LirClassDef("GDVtableCallHostNode", "Node");
        clazz.setSourceFile("call_method_vtable_dispatch_host.gd");
        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newCallTargetMethod(selfType, "call_foo_as_root", "root", "GDVtableRootWorker", "foo"));
        clazz.addFunction(newCallTargetMethod(selfType, "call_foo_as_mid", "mid", "GDVtableMidWorker", "foo"));
        clazz.addFunction(newCallTargetMethod(selfType, "call_foo_as_leaf", "leaf", "GDVtableLeafWorker", "foo"));
        clazz.addFunction(newCallTargetMethod(selfType, "call_bar_as_a", "a", "GDPassAWorker", "bar"));
        clazz.addFunction(newCallTargetMethod(selfType, "call_baz_as_plain", "plain", "GDSibPlainWorker", "baz"));
        return clazz;
    }

    /// `name(param: ParamType) -> int`: `result = param.<targetMethod>()`. The receiver's static
    /// type decides direct vs vtable-indirect dispatch (§2.6), which is what each scenario pins.
    private static LirFunctionDef newCallTargetMethod(GdObjectType selfType, String name,
                                                      String paramName, String paramTypeName, String targetMethod) {
        var func = newMethod(name, GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef(paramName, new GdObjectType(paramTypeName), null, func));
        func.createAndAddVariable("result", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn("result", targetMethod, paramName, List.of()));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    /// Worker class declaring one constant-returning int method (the override chain signature
    /// must match exactly, so every link uses the same shape).
    private static LirClassDef newConstFooClass(String name, String superName, String methodName, int value) {
        var clazz = newWorkerClass(name, superName);
        var selfType = new GdObjectType(name);
        var func = newMethod(methodName, GdIntType.INT, selfType);
        func.createAndAddVariable("result", GdIntType.INT);
        entry(func).appendInstruction(new LiteralIntInsn("result", value));
        entry(func).appendInstruction(new ReturnInsn("result"));
        clazz.addFunction(func);
        return clazz;
    }

    /// Worker class with no methods (pass-through / plain-sibling links).
    private static LirClassDef newWorkerClass(String name, String superName) {
        var clazz = new LirClassDef(name, superName);
        clazz.setSourceFile("call_method_vtable_dispatch_" + name + ".gd");
        return clazz;
    }

    private static String vtableDispatchEngineTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "VtableDispatchNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var root = GDVtableRootWorker.new()
                    var mid = GDVtableMidWorker.new()
                    var leaf = GDVtableLeafWorker.new()
                
                    if int(target.call("call_foo_as_root", leaf)) != 3:
                        push_error("vtable dispatch check failed: root-typed ref to leaf should reach leaf impl (3).")
                        return
                    if int(target.call("call_foo_as_root", mid)) != 2:
                        push_error("vtable dispatch check failed: root-typed ref to mid should reach mid impl (2).")
                        return
                    if int(target.call("call_foo_as_root", root)) != 1:
                        push_error("vtable dispatch check failed: root-typed ref to root should reach root impl (1).")
                        return
                    if int(target.call("call_foo_as_mid", leaf)) != 3:
                        push_error("vtable dispatch check failed: mid-typed ref to leaf should reach leaf impl (3).")
                        return
                    if int(target.call("call_foo_as_leaf", leaf)) != 3:
                        push_error("direct dispatch check failed: leaf-typed ref should stay direct and return 3.")
                        return
                
                    var pass_b = GDPassBWorker.new()
                    var pass_c = GDPassCWorker.new()
                    if int(target.call("call_bar_as_a", pass_c)) != 30:
                        push_error("vtable dispatch check failed: a-typed ref to C should reach C impl (30).")
                        return
                    if int(target.call("call_bar_as_a", pass_b)) != 10:
                        push_error("vtable dispatch check failed: a-typed ref to pass-through B should share A's table and reach A impl (10).")
                        return
                
                    var plain = GDSibPlainWorker.new()
                    if int(target.call("call_baz_as_plain", plain)) != 100:
                        push_error("sibling dispatch check failed: plain sibling should reach base impl (100).")
                        return
                
                    print("gdcc vtable dispatch check passed.")
                """;
    }

    private static LirClassDef newInheritanceHostClass() {
        var clazz = new LirClassDef("GDCallMethodInheritanceNode", "Node");
        clazz.setSourceFile("call_method_engine_inheritance_host.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newCallBaseValueFromChild(selfType));
        clazz.addFunction(newCallChildOnlyFromBaseWithPeer(selfType));
        return clazz;
    }

    private static LirClassDef newInheritanceBaseClass() {
        var clazz = new LirClassDef("GDInheritanceBaseWorker", "RefCounted");
        clazz.setSourceFile("call_method_engine_inheritance_base.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newBaseValueFunction(selfType));
        return clazz;
    }

    private static LirClassDef newInheritanceChildClass() {
        var clazz = new LirClassDef("GDInheritanceChildWorker", "GDInheritanceBaseWorker");
        clazz.setSourceFile("call_method_engine_inheritance_child.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newChildOnlyConsumePeerFunction(selfType));
        return clazz;
    }

    private static LirClassDef newInheritancePeerClass() {
        var clazz = new LirClassDef("GDInheritancePeerWorker", "RefCounted");
        clazz.setSourceFile("call_method_engine_inheritance_peer.gd");

        var selfType = new GdObjectType(clazz.getName());
        clazz.addFunction(newPeerEchoFunction(selfType));
        return clazz;
    }

    private static LirFunctionDef newCallBaseValueFromChild(GdObjectType selfType) {
        var func = newMethod("call_base_value_from_child", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("child", new GdObjectType("GDInheritanceChildWorker"), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "base_value",
                "child",
                List.of()
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newCallChildOnlyFromBaseWithPeer(GdObjectType selfType) {
        var func = newMethod("call_child_only_from_base_with_peer", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("baseRef", new GdObjectType("GDInheritanceBaseWorker"), null, func));
        func.addParameter(new LirParameterDef("peer", new GdObjectType("GDInheritancePeerWorker"), null, func));
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "child_only_consume_peer",
                "baseRef",
                List.of(varRef("peer"))
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newBaseValueFunction(GdObjectType selfType) {
        var func = newMethod("base_value", GdIntType.INT, selfType);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new LiteralIntInsn("result", 41));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newChildOnlyConsumePeerFunction(GdObjectType selfType) {
        var func = newMethod("child_only_consume_peer", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("peer", new GdObjectType("GDInheritancePeerWorker"), null, func));
        func.createAndAddVariable("seed", GdIntType.INT);
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new LiteralIntInsn("seed", 101));
        entry(func).appendInstruction(new CallMethodInsn(
                "result",
                "echo_value",
                "peer",
                List.of(varRef("seed"))
        ));
        entry(func).appendInstruction(new ReturnInsn("result"));
        return func;
    }

    private static LirFunctionDef newPeerEchoFunction(GdObjectType selfType) {
        var func = newMethod("echo_value", GdIntType.INT, selfType);
        func.addParameter(new LirParameterDef("value", GdIntType.INT, null, func));
        entry(func).appendInstruction(new ReturnInsn("value"));
        return func;
    }

    private static LirFunctionDef newMethod(String name, GdType returnType, GdObjectType selfType) {
        var func = new LirFunctionDef(name);
        func.setReturnType(returnType);
        func.addParameter(new LirParameterDef("self", selfType, null, func));
        func.addBasicBlock(new LirBasicBlock("entry"));
        func.setEntryBlockId("entry");
        return func;
    }

    private static LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private static LirInstruction.VariableOperand varRef(String id) {
        return new LirInstruction.VariableOperand(id);
    }

    private static String inheritanceEngineTestScript() {
        return """
                extends Node
                
                const TARGET_NODE_NAME = "CallMethodInheritanceNode"
                
                func _ready() -> void:
                    var target = get_parent().get_node_or_null(TARGET_NODE_NAME)
                    if target == null:
                        push_error("Target node missing.")
                        return
                
                    var child = GDInheritanceChildWorker.new()
                    var peer = GDInheritancePeerWorker.new()
                
                    var parent_value = int(target.call("call_base_value_from_child", child))
                    if parent_value != 41:
                        push_error("gdcc inheritance call_method check failed.")
                        return
                
                    var dynamic_value = int(target.call("call_child_only_from_base_with_peer", child, peer))
                    if dynamic_value != 101:
                        push_error("gdcc inheritance call_method check failed.")
                        return
                
                    var is_parent_ok = child is GDInheritanceBaseWorker
                    var classdb_parent_ok = ClassDB.is_parent_class("GDInheritanceChildWorker", "GDInheritanceBaseWorker")
                    if is_parent_ok and classdb_parent_ok:
                        print("gdcc inheritance call_method check passed.")
                    else:
                        push_error("gdcc inheritance call_method check failed.")
                """;
    }
}
