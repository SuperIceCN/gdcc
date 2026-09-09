package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.CallSuperMethodInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Golden tests for `CALL_SUPER_METHOD` (vtable plan §2.5 / Step 5). A super call always names the
/// FIXED nearest-ancestor implementation found by lexical resolution from the containing class:
/// direct `<Owner>_<method>` for GDCC owners (never the vtable-indirect form, even for polymorphic
/// slots), the exact engine helper for engine owners, and the start thunk for GDCC coroutine
/// owners. Statically unresolvable chains are compile-time errors (D2).
class CallSuperMethodInsnGenTest {

    @Test
    @DisplayName("CALL_SUPER_METHOD on GDCC parent emits the direct owner call and bypasses the vtable")
    void superGdccParentShouldEmitDirectCallAndBypassVtable() {
        // Root introduces `greet` (vtable slot), Mid overrides it, Leaf calls super.greet():
        // the nearest ancestor implementation is Mid's, invoked directly — no vtable accessor.
        var rootClass = newClass("VtRoot");
        rootClass.addFunction(newGdccMethod("VtRoot", "greet"));
        var midClass = newClass("VtMid", "VtRoot");
        midClass.addFunction(newGdccMethod("VtMid", "greet"));
        var leafClass = newClass("VtLeaf", "VtMid");

        var run = newSuperCallFunction("VtLeaf", "greet", null);
        leafClass.addFunction(run);

        var body = generateBody(leafClass, run, newApi(List.of(), List.of()), List.of(rootClass, midClass, leafClass));
        assertTrue(body.contains("VtMid_greet(gdcc_VtLeaf_fat_ptr_upcast_to_VtMid($self));"), body);
        assertFalse(body.contains("VtRoot_greet("), body);
        assertFalse(body.contains("class_vtable"), body);
        assertFalse(body.contains("vt_recv"), body);
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD skips a parent that does not declare the method and resolves the grandparent owner")
    void superParentMissingShouldResolveGrandparentOwner() {
        var grandClass = newClass("VtGrand");
        grandClass.addFunction(newGdccMethod("VtGrand", "greet"));
        var midClass = newClass("VtMid", "VtGrand");
        var leafClass = newClass("VtLeaf", "VtMid");

        var run = newSuperCallFunction("VtLeaf", "greet", null);
        leafClass.addFunction(run);

        var body = generateBody(leafClass, run, newApi(List.of(), List.of()), List.of(grandClass, midClass, leafClass));
        assertTrue(body.contains("VtGrand_greet(gdcc_VtLeaf_fat_ptr_upcast_to_VtGrand($self));"), body);
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD on an engine super method emits the exact engine helper")
    void superEngineMethodShouldEmitExactHelper() {
        var childClass = newClass("VtChild", "Node");
        var run = newSuperCallFunction("VtChild", "queue_free", null);
        childClass.addFunction(run);

        var body = generateBody(childClass, run, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(childClass));
        assertTrue(
                body.contains("gdcc_engine_call_node_queue_free_P_RV(gdcc_VtChild_fat_ptr_upcast_to_Node($self));"),
                body
        );
        assertFalse(body.contains("godot_Node_queue_free("), body);
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD on a GDCC coroutine super method calls the start thunk directly")
    void superCoroutineShouldCallStartThunkDirectly() {
        var baseClass = newClass("VtBase");
        var fire = newGdccMethod("VtBase", "fire");
        fire.setCoroutine(true);
        baseClass.addFunction(fire);
        var childClass = newClass("VtChild", "VtBase");

        var run = newSuperCallFunction("VtChild", "fire", "state");
        run.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        childClass.addFunction(run);

        var body = generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass));
        // Overwrite discipline and start-thunk ABI mirror CALL_METHOD's coroutine route.
        assertTrue(body.contains("gdcc_coro_state_slot_destroy(&$state);"), body);
        assertTrue(body.contains("$state = VtBase_fire__coro_start(gdcc_VtChild_fat_ptr_upcast_to_VtBase($self));"), body);
        assertFalse(body.contains("VtBase_fire("), body);
        assertFalse(body.contains("class_vtable"), body);
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD writes a non-void super result into the result variable")
    void superResultShouldBeWrittenToResultVariable() {
        var baseClass = newClass("VtBase");
        var answer = newGdccMethod("VtBase", "answer");
        answer.setReturnType(GdIntType.INT);
        baseClass.addFunction(answer);
        var childClass = newClass("VtChild", "VtBase");

        var run = newSuperCallFunction("VtChild", "answer", "res");
        run.createAndAddVariable("res", GdIntType.INT);
        childClass.addFunction(run);

        var body = generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass));
        assertTrue(body.contains("$res = VtBase_answer(gdcc_VtChild_fat_ptr_upcast_to_VtBase($self));"), body);
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD completes omitted arguments through the owner default_value_func")
    void superShouldCompleteDefaultsThroughOwnerDefaultFunction() {
        var baseClass = newClass("VtBase");
        var defaultCount = newGdccMethod("VtBase", "default_count");
        defaultCount.setReturnType(GdIntType.INT);
        baseClass.addFunction(defaultCount);
        var ping = newGdccMethod("VtBase", "ping");
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "default_count", ping));
        baseClass.addFunction(ping);
        var childClass = newClass("VtChild", "VtBase");

        var run = newSuperCallFunction("VtChild", "ping", null);
        childClass.addFunction(run);

        var body = generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass));
        // The instance default_value_func receives the receiver rendered to the OWNER type, and the
        // completed call passes the materialized default temp — same rules as CALL_METHOD.
        assertTrue(body.contains("VtBase_default_count(gdcc_VtChild_fat_ptr_upcast_to_VtBase($self))"), body);
        assertTrue(body.contains("VtBase_ping(gdcc_VtChild_fat_ptr_upcast_to_VtBase($self), __gdcc_tmp_default_arg_1_"), body);
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD forwards the vararg tail of a vararg GDCC super method")
    void superVarargShouldForwardPackedTail() {
        var baseClass = newClass("VtBase");
        var echo = newGdccMethod("VtBase", "echo");
        echo.addParameter(new LirParameterDef("text", GdStringType.STRING, null, echo));
        echo.setVararg(true);
        baseClass.addFunction(echo);
        var childClass = newClass("VtChild", "VtBase");

        var run = newSuperCallFunction("VtChild", "echo", null,
                List.of(new LirInstruction.VariableOperand("text"), new LirInstruction.VariableOperand("extra")));
        run.createAndAddVariable("text", GdStringType.STRING);
        run.createAndAddVariable("extra", GdVariantType.VARIANT);
        childClass.addFunction(run);

        var body = generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass));
        assertTrue(body.contains("VtBase_echo(gdcc_VtChild_fat_ptr_upcast_to_VtBase($self), &$text, __gdcc_tmp_argv_"), body);
        assertTrue(body.contains(", (godot_int)1);"), body);
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD rejects a receiver whose static type is not the containing class (lexical super invariant)")
    void superShouldRejectNonLexicalSelfReceiver() {
        var baseClass = newClass("VtBase");
        baseClass.addFunction(newGdccMethod("VtBase", "greet"));
        var childClass = newClass("VtChild", "VtBase");

        var run = newFunction("run");
        run.createAndAddVariable("other", new GdObjectType("VtBase"));
        entry(run).appendInstruction(new CallSuperMethodInsn(null, "greet", "other", List.of()));
        childClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass))
        );
        assertTrue(ex.getMessage().contains("lexical self"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD on a class without a super class fails at compile time (D2)")
    void superWithoutSuperClassShouldFailFast() {
        var rootlessClass = newClass("VtRootless", "");
        var run = newSuperCallFunction("VtRootless", "greet", null);
        rootlessClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(rootlessClass, run, newApi(List.of(), List.of()), List.of(rootlessClass))
        );
        assertTrue(ex.getMessage().contains("no super class"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD with an unresolvable super chain fails at compile time (D2)")
    void superUnresolvableChainShouldFailFast() {
        var childClass = newClass("VtChild", "Ghost");
        var run = newSuperCallFunction("VtChild", "greet", null);
        childClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(childClass, run, newApi(List.of(), List.of()), List.of(childClass))
        );
        assertTrue(ex.getMessage().contains("RECEIVER_METADATA_UNKNOWN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("never fall back to dynamic dispatch"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD for a method missing on a known GDCC parent fails at compile time (D2)")
    void superMethodMissingOnKnownParentShouldFailFast() {
        var baseClass = newClass("VtBase");
        baseClass.addFunction(newGdccMethod("VtBase", "greet"));
        var childClass = newClass("VtChild", "VtBase");
        var run = newSuperCallFunction("VtChild", "missing_method", null);
        childClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass))
        );
        assertTrue(ex.getMessage().contains("METHOD_MISSING"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD resolves an inner-class parent by its canonical name")
    void superInnerClassParentShouldResolveByCanonicalName() {
        // Canonical inner names keep raw `__sub__` in the C function symbol but collapse to single
        // underscores in fat-pointer helper identifiers — both naming layers are pinned here.
        var parentClass = newClass("Outer__sub__Inner", "RefCounted");
        parentClass.addFunction(newGdccMethod("Outer__sub__Inner", "greet"));
        var childClass = newClass("VtChild", "Outer__sub__Inner");

        var run = newSuperCallFunction("VtChild", "greet", null);
        childClass.addFunction(run);

        var body = generateBody(childClass, run, newApi(List.of(), List.of()), List.of(parentClass, childClass));
        assertTrue(
                body.contains("Outer__sub__Inner_greet(gdcc_VtChild_fat_ptr_upcast_to_Outer_sub_Inner($self));"),
                body
        );
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD resolving to a static parent method fails fast (super is instance-only)")
    void superStaticMethodShouldFailFast() {
        var baseClass = newClass("VtBase");
        var make = newFunction("make");
        make.setStatic(true);
        entry(make).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(make);
        var childClass = newClass("VtChild", "VtBase");
        var run = newSuperCallFunction("VtChild", "make", null);
        childClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass))
        );
        assertTrue(ex.getMessage().contains("instance-only"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD rejects super._init (constructor route is not an ordinary method lookup)")
    void superInitShouldFailFast() {
        var baseClass = newClass("VtBase");
        var childClass = newClass("VtChild", "VtBase");
        var run = newSuperCallFunction("VtChild", "_init", null);
        childClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass))
        );
        assertTrue(ex.getMessage().contains("_init"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD rejects a result variable on a void super method")
    void superVoidMethodShouldRejectResultId() {
        var baseClass = newClass("VtBase");
        baseClass.addFunction(newGdccMethod("VtBase", "greet"));
        var childClass = newClass("VtChild", "VtBase");

        var run = newSuperCallFunction("VtChild", "greet", "res");
        run.createAndAddVariable("res", GdVariantType.VARIANT);
        childClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass))
        );
        assertTrue(ex.getMessage().contains("has no return value"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_SUPER_METHOD on a coroutine super method requires a compiler::GdccCoroState result")
    void superCoroutineShouldRequireCoroStateResult() {
        var baseClass = newClass("VtBase");
        var fire = newGdccMethod("VtBase", "fire");
        fire.setCoroutine(true);
        baseClass.addFunction(fire);
        var childClass = newClass("VtChild", "VtBase");

        var run = newSuperCallFunction("VtChild", "fire", null);
        childClass.addFunction(run);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(childClass, run, newApi(List.of(), List.of()), List.of(baseClass, childClass))
        );
        assertTrue(ex.getMessage().contains("compiler::GdccCoroState"), ex.getMessage());
    }

    /// Declares a void non-static instance method on `ownerClass` with just the `self` parameter.
    private LirFunctionDef newGdccMethod(String ownerClass, String name) {
        var method = newFunction(name);
        method.addParameter(new LirParameterDef("self", new GdObjectType(ownerClass), null, method));
        entry(method).appendInstruction(new ReturnInsn(null));
        return method;
    }

    /// Builds the containing-class `run` function holding one `call_super_method` on its `self`.
    private LirFunctionDef newSuperCallFunction(String className, String methodName, String resultId) {
        return newSuperCallFunction(className, methodName, resultId, List.of());
    }

    private LirFunctionDef newSuperCallFunction(String className,
                                                String methodName,
                                                String resultId,
                                                List<LirInstruction.Operand> args) {
        var run = newFunction("run");
        run.addParameter(new LirParameterDef("self", new GdObjectType(className), null, run));
        entry(run).appendInstruction(new CallSuperMethodInsn(resultId, methodName, "self", args));
        return run;
    }

    private LirClassDef newClass(String name) {
        return newClass(name, "RefCounted");
    }

    private LirClassDef newClass(String name, String superName) {
        return new LirClassDef(name, superName, false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private LirFunctionDef newFunction(String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private String generateBody(LirClassDef clazz,
                                LirFunctionDef func,
                                ExtensionAPI api,
                                List<LirClassDef> gdccClasses) {
        var module = new LirModule("test_module", gdccClasses);
        var codegen = newCodegen(module, api, gdccClasses);
        return codegen.generateFuncBody(clazz, func);
    }

    private CCodegen newCodegen(LirModule module, ExtensionAPI api, List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private ExtensionAPI newApi(List<ExtensionBuiltinClass> builtinClasses, List<ExtensionGdClass> gdClasses) {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                builtinClasses,
                gdClasses,
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithQueueFree() {
        var queueFree = new ExtensionGdClass.ClassMethod(
                "queue_free",
                false,
                false,
                false,
                false,
                77L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of()
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(queueFree),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
