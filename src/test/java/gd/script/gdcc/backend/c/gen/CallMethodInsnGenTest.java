package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.enums.LifecycleProvenance;
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
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdPackedVectorArrayType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallMethodInsnGenTest {
    @Test
    @DisplayName("CALL_METHOD should emit builtin static dispatch for Vector3.rotated")
    void callMethodBuiltinShouldEmitStaticDispatch() {
        var clazz = newClass("Worker");
        var func = newFunction("call_rotated");
        func.createAndAddVariable("vector", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("axis", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("angle", GdFloatType.FLOAT);
        func.createAndAddVariable("ret", GdFloatVectorType.VECTOR3);

        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "rotated",
                "vector",
                List.of(
                        new LirInstruction.VariableOperand("axis"),
                        new LirInstruction.VariableOperand("angle")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(vector3Builtin()), List.of()), List.of(clazz));
        assertTrue(body.contains("$ret = godot_Vector3_rotated(&$vector, &$axis, $angle);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit builtin Signal.emit with packed argv/argc")
    void callMethodBuiltinSignalEmitShouldRenderVarargArgv() {
        var clazz = newClass("Worker");
        var func = newFunction("emit_signal");
        func.createAndAddVariable("sig", new GdSignalType());
        func.createAndAddVariable("arg0", GdVariantType.VARIANT);
        func.createAndAddVariable("arg1", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "emit",
                "sig",
                List.of(
                        new LirInstruction.VariableOperand("arg0"),
                        new LirInstruction.VariableOperand("arg1")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(signalBuiltin()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Signal_emit(&$sig, __gdcc_tmp_argv_"), body);
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_"), body);
        assertTrue(body.contains(", (godot_int)2);"), body);
        assertFalse(body.contains("godot_Signal_emit(&$sig);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit builtin Signal.emit() as NULL, 0")
    void callMethodBuiltinSignalEmitZeroArgsShouldRenderNullArgc() {
        var clazz = newClass("Worker");
        var func = newFunction("emit_empty");
        func.createAndAddVariable("sig", new GdSignalType());
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "emit",
                "sig",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(signalBuiltin()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Signal_emit(&$sig, NULL, (godot_int)0);"), body);
        assertFalse(body.contains("const godot_Variant* __gdcc_tmp_argv_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should reject a non-Variant Signal.emit tail argument")
    void callMethodBuiltinSignalEmitShouldRejectNonVariantVararg() {
        var clazz = newClass("Worker");
        var func = newFunction("emit_bad_tail");
        func.createAndAddVariable("sig", new GdSignalType());
        func.createAndAddVariable("count", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "emit",
                "sig",
                List.of(new LirInstruction.VariableOperand("count"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(signalBuiltin()), List.of()), List.of(clazz))
        );
        assertTrue(ex.getMessage().contains("Vararg argument #1"), ex.getMessage());
        assertTrue(ex.getMessage().contains("must be Variant"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should emit exact engine helper dispatch for Node.queue_free")
    void callMethodEngineShouldEmitStaticDispatch() {
        var clazz = newClass("Worker");
        var func = newFunction("call_queue_free");
        func.createAndAddVariable("node", new GdObjectType("Node"));

        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "queue_free",
                "node",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz));
        assertTrue(body.contains("gdcc_engine_call_node_queue_free_P_RV($node);"), body);
        assertFalse(body.contains("godot_Node_queue_free("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should convert GDCC self pointer when static dispatch resolves to engine owner")
    void callMethodGdccSelfToEngineOwnerShouldConvertReceiverPointer() {
        var clazz = newClass("GDMyNode", "Node");
        var func = newFunction("dispose_self");
        func.addParameter(new LirParameterDef("self", new GdObjectType("GDMyNode"), null, func));

        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "queue_free",
                "self",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz));
        // The helper accepts owner fat self; GDCC→engine uses the generated upcast, not raw object_ptr.
        assertTrue(
                body.contains("gdcc_engine_call_node_queue_free_P_RV(gdcc_GDMyNode_fat_ptr_upcast_to_Node($self));"),
                body
        );
        assertFalse(body.contains("gdcc_object_to_godot_object_ptr($self, GDMyNode_object_ptr)"), body);
        assertFalse(body.contains("gdcc_engine_call_node_queue_free_P_RV((godot_Node*)$self);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should dispatch ResourceLoader.load with singleton receiver and default completion")
    void callMethodResourceLoaderLoadShouldMaterializeDefaultsAndOwnResult() throws java.io.IOException {
        // This is the instruction pair the frontend `load`/`preload` rewrite emits:
        // `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load" $loader $path`.
        // The real API dump supplies the singleton registration and the exact method metadata
        // (`type_hint=""`, `cache_mode=1` defaults; hash 3358495409).
        var clazz = newClass("Worker");
        var func = newFunction("probe_load");
        func.createAndAddVariable("loader", new GdObjectType("ResourceLoader"));
        func.createAndAddVariable("path", GdStringType.STRING);
        func.createAndAddVariable("resource", new GdObjectType("Resource"));

        entry(func).appendInstruction(new LoadStaticInsn("loader", "@GlobalScope", "ResourceLoader"));
        entry(func).appendInstruction(new CallMethodInsn(
                "resource",
                "load",
                "loader",
                List.of(new LirInstruction.VariableOperand("path"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, ExtensionApiLoader.loadDefault(), List.of(clazz));
        // Singleton receiver materializes through the module-local singleton getter binding.
        assertTrue(body.contains("godot_ResourceLoader_singleton()"), body);
        // The OWNED Resource result is consumed by the declared slot directly (no extra retain:
        // the engine call result is already caller-owned).
        assertTrue(
                body.contains("$resource = gdcc_engine_call_resourceloader_load_"),
                body
        );
        assertFalse(
                body.contains("own_object(gdcc_Resource_fat_ptr_live_object($resource)"),
                "OWNED call result must not be retained again: " + body
        );
        // ENGINE exact dispatch for the instance method (no public godot_* wrapper).
        assertFalse(body.contains("godot_ResourceLoader_load("), body);
        // The two omitted defaults are materialized with their actual values: `type_hint=""`
        // (String temp) and `cache_mode=1` (int temp).
        assertOrdered(
                body,
                "godot_String __gdcc_tmp_default_arg_2_",
                "= godot_new_String_with_String(GD_STATIC_S(u8\"\"));"
        );
        assertOrdered(
                body,
                "godot_int __gdcc_tmp_default_arg_3_",
                "= 1;"
        );
    }

    @Test
    @DisplayName("CALL_METHOD discard of ResourceLoader.load result should release the OWNED resource")
    void callMethodResourceLoaderLoadDiscardShouldReleaseOwnedResult() throws java.io.IOException {
        // Fire-and-forget `load(path)`: the RefCounted return is caller-OWNED, so discarding it
        // must emit the balancing release instead of leaking the strong reference.
        var clazz = newClass("Worker");
        var func = newFunction("probe_load_discard");
        func.createAndAddVariable("loader", new GdObjectType("ResourceLoader"));
        func.createAndAddVariable("path", GdStringType.STRING);

        entry(func).appendInstruction(new LoadStaticInsn("loader", "@GlobalScope", "ResourceLoader"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "load",
                "loader",
                List.of(new LirInstruction.VariableOperand("path"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, ExtensionApiLoader.loadDefault(), List.of(clazz));
        // The discarded result is captured into a discard temp and immediately released: the
        // balancing release for the caller-OWNED RefCounted return.
        assertTrue(
                body.contains("gdcc_Resource_fat_ptr __gdcc_tmp_discard_"),
                body
        );
        assertOrdered(
                body,
                "= gdcc_engine_call_resourceloader_load_",
                "release_object(gdcc_Resource_fat_ptr_live_object(__gdcc_tmp_discard_"
        );
    }

    @Test
    @DisplayName("CALL_METHOD should emit exact engine helper dispatch for object-parameter methods")
    void callMethodEngineObjectParamShouldEmitHelperRoute() {
        var clazz = newClass("Worker");
        var func = newFunction("call_add_child");
        func.createAndAddVariable("holder", new GdObjectType("Node"));
        func.createAndAddVariable("child", new GdObjectType("Node"));

        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "add_child",
                "holder",
                List.of(new LirInstruction.VariableOperand("child"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithAddChild())), List.of(clazz));
        assertTrue(body.contains("gdcc_engine_call_node_add_child_PL4Node_ZI_RV($holder, $child,"), body);
        assertTrue(body.contains("__gdcc_tmp_default_arg_2_"), body);
        assertTrue(body.contains("__gdcc_tmp_default_arg_3_"), body);
        assertFalse(body.contains("godot_Node_add_child("), body);
        assertFalse(body.contains("(const godot_Node_InternalMode *)&"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit GDCC static dispatch between known GDCC types")
    void callMethodGdccShouldEmitStaticDispatch() {
        var workerClass = newClass("Worker");
        var pingFunc = newFunction("ping");
        pingFunc.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, pingFunc));
        entry(pingFunc).appendInstruction(new gd.script.gdcc.lir.insn.ReturnInsn(null));
        workerClass.addFunction(pingFunc);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_ping($worker);"), body);
        assertFalse(body.contains("godot_Object_call("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should upcast GDCC receiver to parent owner via _super chain without bare cast")
    void callMethodGdccReceiverToParentOwnerShouldUseSuperChainUpcast() {
        var baseClass = newClass("BaseWorker");
        var basePing = newFunction("base_ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("BaseWorker"), null, basePing));
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var childClass = newClass("ChildWorker", "BaseWorker");
        var childOnly = newFunction("child_only");
        childOnly.addParameter(new LirParameterDef("self", new GdObjectType("ChildWorker"), null, childOnly));
        entry(childOnly).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childOnly);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("child", new GdObjectType("ChildWorker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "base_ping",
                "child",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, baseClass));
        assertTrue(body.contains("BaseWorker_base_ping(gdcc_ChildWorker_fat_ptr_upcast_to_BaseWorker($child));"), body);
        assertFalse(body.contains("&($child->_super)"), body);
        assertFalse(body.contains("BaseWorker_base_ping((BaseWorker*)$child);"), body);
        assertFalse(body.contains("godot_Object_call("), body);
    }

    // ==== Vtable-indirect dispatch (virtual_override_vtable_implementation.md §2.6, R4) ====

    @Test
    @DisplayName("CALL_METHOD on a polymorphic GDCC method should dispatch indirectly through the introducer vtable")
    void callMethodPolymorphicShouldEmitVtableIndirectDispatch() {
        var parentClass = newClass("VtBase");
        var parentFoo = newFunction("foo");
        parentFoo.addParameter(new LirParameterDef("self", new GdObjectType("VtBase"), null, parentFoo));
        entry(parentFoo).appendInstruction(new ReturnInsn(null));
        parentClass.addFunction(parentFoo);

        var childClass = newClass("VtDerived", "VtBase");
        var childFoo = newFunction("foo");
        childFoo.addParameter(new LirParameterDef("self", new GdObjectType("VtDerived"), null, childFoo));
        entry(childFoo).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childFoo);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("parent", new GdObjectType("VtBase"));
        entry(caller).appendInstruction(new CallMethodInsn(null, "foo", "parent", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, parentClass));
        // Receiver materialized once as the introducer fat self; callee and first arg both use the temp.
        assertTrue(body.contains("gdcc_VtBase_fat_ptr __gdcc_tmp_vt_recv_0 = $parent;"), body);
        assertTrue(body.contains("VtBase_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_foo(__gdcc_tmp_vt_recv_0);"), body);
        assertFalse(body.contains("VtBase_foo("), body);
        assertFalse(body.contains("VtDerived_foo("), body);
        assertFalse(body.contains("godot_Object_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD three-level chain should materialize a mid-typed receiver as the slot introducer fat self")
    void callMethodPolymorphicThreeLevelShouldMaterializeReceiverAsIntroducerFatSelf() {
        // Owner != introducer (§2.6): receiver static type VtMid (nearest owner), slot introducer VtRoot.
        var parentClass = newClass("VtRoot");
        var parentFoo = newFunction("foo");
        parentFoo.addParameter(new LirParameterDef("self", new GdObjectType("VtRoot"), null, parentFoo));
        entry(parentFoo).appendInstruction(new ReturnInsn(null));
        parentClass.addFunction(parentFoo);

        var childClass = newClass("VtMid", "VtRoot");
        var childFoo = newFunction("foo");
        childFoo.addParameter(new LirParameterDef("self", new GdObjectType("VtMid"), null, childFoo));
        entry(childFoo).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childFoo);

        var grandchildClass = newClass("VtLeaf", "VtMid");
        var grandchildFoo = newFunction("foo");
        grandchildFoo.addParameter(new LirParameterDef("self", new GdObjectType("VtLeaf"), null, grandchildFoo));
        entry(grandchildFoo).appendInstruction(new ReturnInsn(null));
        grandchildClass.addFunction(grandchildFoo);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("child", new GdObjectType("VtMid"));
        entry(caller).appendInstruction(new CallMethodInsn(null, "foo", "child", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, grandchildClass, childClass, parentClass));
        // The VtMid receiver is upcast to the INTRODUCER (VtRoot) fat type before entering the vtable.
        assertTrue(body.contains("gdcc_VtRoot_fat_ptr __gdcc_tmp_vt_recv_0 = gdcc_VtMid_fat_ptr_upcast_to_VtRoot($child);"), body);
        assertTrue(body.contains("VtRoot_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_foo(__gdcc_tmp_vt_recv_0);"), body);
        assertFalse(body.contains("VtMid_foo("), body);
        assertFalse(body.contains("VtRoot_foo("), body);
        assertFalse(body.contains("VtLeaf_foo("), body);
    }

    @Test
    @DisplayName("CALL_METHOD on a final overrider receiver should stay a direct call (devirtualization)")
    void callMethodFinalOverriderShouldKeepDirectDispatch() {
        // Two-level chain with no deeper override: isPolymorphicCall(VtFinal, foo) is false (§2.1),
        // so the call site must not touch the vtable even though the slot exists.
        var parentClass = newClass("VtOrigin");
        var parentFoo = newFunction("foo");
        parentFoo.addParameter(new LirParameterDef("self", new GdObjectType("VtOrigin"), null, parentFoo));
        entry(parentFoo).appendInstruction(new ReturnInsn(null));
        parentClass.addFunction(parentFoo);

        var childClass = newClass("VtFinal", "VtOrigin");
        var childFoo = newFunction("foo");
        childFoo.addParameter(new LirParameterDef("self", new GdObjectType("VtFinal"), null, childFoo));
        entry(childFoo).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childFoo);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("child", new GdObjectType("VtFinal"));
        entry(caller).appendInstruction(new CallMethodInsn(null, "foo", "child", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, parentClass));
        assertTrue(body.contains("VtFinal_foo($child);"), body);
        assertFalse(body.contains("_class_vtable("), body);
        assertFalse(body.contains("vt_recv"), body);
    }

    @Test
    @DisplayName("CALL_METHOD resolving to a GDCC static method should warn and never query the vtable")
    void callMethodStaticViaInstanceSyntaxShouldNotQueryVtable() {
        // Static declarations are excluded from slots and break override chains (§2.1); the gate
        // additionally skips static resolutions outright, so a descendant instance same-name
        // method must not pull this call site into vtable dispatch.
        var parentClass = newClass("VtStaticBase");
        var make = newFunction("make");
        make.setStatic(true);
        parentClass.addFunction(make);

        var childClass = newClass("VtStaticChild", "VtStaticBase");
        var childMake = newFunction("make");
        childMake.addParameter(new LirParameterDef("self", new GdObjectType("VtStaticChild"), null, childMake));
        entry(childMake).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childMake);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("parent", new GdObjectType("VtStaticBase"));
        entry(caller).appendInstruction(new CallMethodInsn(null, "make", "parent", List.of()));
        hostClass.addFunction(caller);

        var outputBuffer = new ByteArrayOutputStream();
        var capture = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
        var originalOut = System.out;
        String body;
        try {
            System.setOut(capture);
            body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, parentClass));
        } finally {
            System.setOut(originalOut);
            capture.close();
        }

        var output = outputBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("resolved static method 'VtStaticBase.make'"), output);
        assertTrue(body.contains("VtStaticBase_make();"), body);
        assertFalse(body.contains("_class_vtable("), body);
        assertFalse(body.contains("vt_recv"), body);
    }

    @Test
    @DisplayName("CALL_METHOD polymorphic with an object return should keep the internal FAT_PTR result path")
    void callMethodPolymorphicObjectReturnShouldKeepFatPtrResultPath() {
        // §2.6: vtable slots point at internal GDCC functions, so an object return must be
        // consumed as an internal fat-pointer product — never via the `godot_*` raw-producer
        // `_from_raw` capture path.
        var parentClass = newClass("ObjRoot");
        var who = newFunction("who");
        who.setReturnType(new GdObjectType("ObjRoot"));
        who.addParameter(new LirParameterDef("self", new GdObjectType("ObjRoot"), null, who));
        entry(who).appendInstruction(new ReturnInsn("self"));
        parentClass.addFunction(who);

        var childClass = newClass("ObjChild", "ObjRoot");
        var childWho = newFunction("who");
        childWho.setReturnType(new GdObjectType("ObjRoot"));
        childWho.addParameter(new LirParameterDef("self", new GdObjectType("ObjChild"), null, childWho));
        entry(childWho).appendInstruction(new ReturnInsn("self"));
        childClass.addFunction(childWho);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("parent", new GdObjectType("ObjRoot"));
        caller.createAndAddVariable("result", new GdObjectType("ObjRoot"));
        entry(caller).appendInstruction(new CallMethodInsn("result", "who", "parent", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, parentClass));
        assertTrue(body.contains("ObjRoot_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_who(__gdcc_tmp_vt_recv_0)"), body);
        assertFalse(body.contains("_from_raw"), body);
        assertFalse(body.contains("ObjRoot_who("), body);
    }

    @Test
    @DisplayName("CALL_METHOD polymorphic with default_value_func should feed the original receiver to the default and vt_recv to the slot")
    void callMethodPolymorphicDefaultValueFuncShouldKeepOwnerReceiverForDefault() {
        // §2.6: defaults belong to the statically resolved owner signature — the instance
        // default_value_func still receives the ORIGINAL receiver rendered to the owner type,
        // while the vtable call's first argument is the materialized introducer temp.
        var parentClass = newClass("DefRoot");
        var foo = newFunction("foo");
        foo.setReturnType(GdIntType.INT);
        foo.addParameter(new LirParameterDef("self", new GdObjectType("DefRoot"), null, foo));
        foo.addParameter(new LirParameterDef("value", GdIntType.INT, "default_value", foo));
        entry(foo).appendInstruction(new ReturnInsn("value"));
        parentClass.addFunction(foo);
        var defaultValue = newFunction("default_value");
        defaultValue.setReturnType(GdIntType.INT);
        defaultValue.addParameter(new LirParameterDef("self", new GdObjectType("DefRoot"), null, defaultValue));
        defaultValue.createAndAddVariable("result", GdIntType.INT);
        entry(defaultValue).appendInstruction(new LiteralIntInsn("result", 7));
        entry(defaultValue).appendInstruction(new ReturnInsn("result"));
        parentClass.addFunction(defaultValue);

        var childClass = newClass("DefChild", "DefRoot");
        var childFoo = newFunction("foo");
        childFoo.setReturnType(GdIntType.INT);
        childFoo.addParameter(new LirParameterDef("self", new GdObjectType("DefChild"), null, childFoo));
        childFoo.addParameter(new LirParameterDef("value", GdIntType.INT, null, childFoo));
        entry(childFoo).appendInstruction(new ReturnInsn("value"));
        childClass.addFunction(childFoo);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("parent", new GdObjectType("DefRoot"));
        caller.createAndAddVariable("result", GdIntType.INT);
        entry(caller).appendInstruction(new CallMethodInsn("result", "foo", "parent", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, parentClass));
        assertTrue(body.contains("DefRoot_default_value($parent);"), body);
        assertTrue(body.contains(
                "DefRoot_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_foo(__gdcc_tmp_vt_recv_0, __gdcc_tmp_default_arg_1_"), body);
        assertFalse(body.contains("DefRoot_default_value(__gdcc_tmp_vt_recv_0"), body);
        assertFalse(body.contains("DefRoot_foo("), body);
        // Default materialization precedes the indirect call; the receiver temp is declared first.
        assertOrdered(body,
                "gdcc_DefRoot_fat_ptr __gdcc_tmp_vt_recv_0 = $parent;",
                "DefRoot_default_value($parent);",
                "DefRoot_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_foo(");
    }

    @Test
    @DisplayName("CALL_METHOD should fallback to OBJECT_DYNAMIC for known engine type with unknown method")
    void callMethodKnownEngineTypeUnknownMethodShouldFallbackToObjectDynamic() {
        var clazz = newClass("Worker");
        var func = newFunction("call_unknown");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn(null, "missing_method", "node", List.of()));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz));
        assertTrue(body.contains("godot_Object_call(gdcc_Node_fat_ptr_live_object($node), GD_STATIC_SN(u8\"missing_method\")"), body);
        assertFalse(body.contains("godot_Node_missing_method("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should fallback to OBJECT_DYNAMIC for known GDCC type with unknown method")
    void callMethodKnownGdccTypeUnknownMethodShouldFallbackToObjectDynamic() {
        var workerClass = newClass("Worker");
        var pingFunc = newFunction("ping");
        pingFunc.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, pingFunc));
        entry(pingFunc).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(pingFunc);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "missing_method",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("godot_Object_call(gdcc_Worker_fat_ptr_live_object($worker), GD_STATIC_SN(u8\"missing_method\")"), body);
        assertFalse(body.contains("Worker_missing_method("), body);
        assertFalse(body.contains("gdcc_object_to_godot_object_ptr($worker"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit OBJECT_DYNAMIC for GDCC base-typed receiver with pointer conversion")
    void callMethodGdccBaseTypedReceiverShouldFallbackToObjectDynamicWithPointerConversion() {
        var baseClass = newClass("BaseWorker");
        var basePing = newFunction("ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("BaseWorker"), null, basePing));
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var childClass = newClass("ChildWorker", "BaseWorker");
        var childOnly = newFunction("child_only_echo");
        childOnly.setReturnType(GdIntType.INT);
        childOnly.addParameter(new LirParameterDef("self", new GdObjectType("ChildWorker"), null, childOnly));
        childOnly.addParameter(new LirParameterDef("value", GdIntType.INT, null, childOnly));
        entry(childOnly).appendInstruction(new ReturnInsn("value"));
        childClass.addFunction(childOnly);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("baseRef", new GdObjectType("BaseWorker"));
        caller.createAndAddVariable("value", GdIntType.INT);
        caller.createAndAddVariable("ret", GdIntType.INT);
        entry(caller).appendInstruction(new CallMethodInsn(
                "ret",
                "child_only_echo",
                "baseRef",
                List.of(new LirInstruction.VariableOperand("value"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, baseClass, childClass));
        assertTrue(body.contains("godot_Object_call(gdcc_BaseWorker_fat_ptr_live_object($baseRef), GD_STATIC_SN(u8\"child_only_echo\")"), body);
        assertFalse(body.contains("ChildWorker_child_only_echo("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
        assertFalse(body.contains("gdcc_object_to_godot_object_ptr($baseRef"), body);
    }

    @Test
    @DisplayName("CALL_METHOD OBJECT_DYNAMIC should pack GDCC object args with helper and keep pointer conversion on receiver")
    void callMethodObjectDynamicShouldPackGdccArgsWithHelperAndPointerConversion() {
        var baseClass = newClass("BaseWorker");
        var basePing = newFunction("ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("BaseWorker"), null, basePing));
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var childClass = newClass("ChildWorker", "BaseWorker");
        var childOnly = newFunction("child_only_consume_peer");
        childOnly.addParameter(new LirParameterDef("self", new GdObjectType("ChildWorker"), null, childOnly));
        childOnly.addParameter(new LirParameterDef("peer", new GdObjectType("PeerWorker"), null, childOnly));
        entry(childOnly).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childOnly);

        var peerClass = newClass("PeerWorker");
        var peerPing = newFunction("ping");
        peerPing.addParameter(new LirParameterDef("self", new GdObjectType("PeerWorker"), null, peerPing));
        entry(peerPing).appendInstruction(new ReturnInsn(null));
        peerClass.addFunction(peerPing);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("baseRef", new GdObjectType("BaseWorker"));
        caller.createAndAddVariable("peer", new GdObjectType("PeerWorker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "child_only_consume_peer",
                "baseRef",
                List.of(new LirInstruction.VariableOperand("peer"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(
                hostClass,
                caller,
                newApi(List.of(), List.of()),
                List.of(hostClass, baseClass, childClass, peerClass)
        );
        assertTrue(body.contains("godot_Object_call(gdcc_BaseWorker_fat_ptr_live_object($baseRef), GD_STATIC_SN(u8\"child_only_consume_peer\")"), body);
        assertTrue(body.contains("gdcc_PeerWorker_fat_ptr_to_variant($peer)"), body);
        assertFalse(body.contains("gdcc_new_Variant_with_gdcc_Object("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
        assertFalse(body.contains("gdcc_object_to_godot_object_ptr($baseRef"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should allow static method call and print warning")
    void callMethodStaticShouldEmitWarningAndGenerateCall() {
        var clazz = newClass("Worker");
        var func = newFunction("call_static");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("ret", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn("ret", "make", "node", List.of()));
        clazz.addFunction(func);

        var outputBuffer = new ByteArrayOutputStream();
        var capture = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
        var originalOut = System.out;
        String body;
        try {
            System.setOut(capture);
            body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithStaticFactory())), List.of(clazz));
        } finally {
            System.setOut(originalOut);
            capture.close();
        }

        var output = outputBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("gdcc_engine_call_static_node_make_P_RL4Node_()"), body);
        assertFalse(body.contains("godot_Node_make()"), body);
        assertTrue(output.contains("call_method on receiver"), output);
        assertTrue(output.contains("resolved static method 'Node.make'"), output);
    }

    @Test
    @DisplayName("CALL_METHOD should emit exact engine callv helper route and keep extra tail on caller side")
    void callMethodExactEngineVarargShouldEmitHelperRouteAndKeepExtraTailOnCallerSide() {
        var clazz = newClass("Worker");
        var func = newFunction("call_vararg_exact");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("label", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("tail", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "spread",
                "node",
                List.of(
                        new LirInstruction.VariableOperand("label"),
                        new LirInstruction.VariableOperand("tail")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithVarargSpread(93L))), List.of(clazz));
        assertTrue(body.contains("gdcc_engine_callv_node_spread_PS_RV_Xv($node, &$label, __gdcc_tmp_argv_"), body);
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_"), body);
        assertFalse(body.contains("godot_Node_spread("), body);
        assertFalse(body.contains("godot_new_Variant_with_StringName($label)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit exact static engine callv helper route without receiver argument")
    void callMethodExactStaticEngineVarargShouldEmitStaticHelperRouteWithoutReceiverArgument() {
        var clazz = newClass("Worker");
        var func = newFunction("call_static_vararg");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("prefix", GdIntType.INT);
        func.createAndAddVariable("tail", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "broadcast",
                "node",
                List.of(
                        new LirInstruction.VariableOperand("prefix"),
                        new LirInstruction.VariableOperand("tail")
                )
        ));
        clazz.addFunction(func);

        var outputBuffer = new ByteArrayOutputStream();
        var capture = new PrintStream(outputBuffer, true, StandardCharsets.UTF_8);
        var originalOut = System.out;
        String body;
        try {
            System.setOut(capture);
            body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithStaticVarargBroadcast(94L))), List.of(clazz));
        } finally {
            System.setOut(originalOut);
            capture.close();
        }

        var output = outputBuffer.toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("gdcc_engine_callv_static_node_broadcast_PI_RV_Xv($prefix, __gdcc_tmp_argv_"), body);
        assertFalse(body.contains("gdcc_engine_callv_static_node_broadcast_PI_RV_Xv($node"), body);
        assertFalse(body.contains("godot_Node_broadcast("), body);
        assertTrue(output.contains("resolved static method 'Node.broadcast'"), output);
    }

    @Test
    @DisplayName("CALL_METHOD should pick nearest owner overload on inheritance chain")
    void callMethodShouldPickNearestOwnerOverload() {
        var baseClass = newClass("Base", "RefCounted");
        var basePing = newFunction("ping");
        basePing.addParameter(new LirParameterDef("self", new GdObjectType("Base"), null, basePing));
        entry(basePing).appendInstruction(new ReturnInsn(null));
        baseClass.addFunction(basePing);

        var subClass = newClass("Sub", "Base");
        var subPing = newFunction("ping");
        subPing.addParameter(new LirParameterDef("self", new GdObjectType("Sub"), null, subPing));
        entry(subPing).appendInstruction(new ReturnInsn(null));
        subClass.addFunction(subPing);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("target", new GdObjectType("Sub"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "target",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, subClass, baseClass));
        assertTrue(body.contains("Sub_ping($target);"), body);
        assertFalse(body.contains("Base_ping($target);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should prefer fixed overload over vararg overload")
    void callMethodShouldPreferFixedOverVararg() {
        var workerClass = newClass("Worker");

        var fixed = newFunction("echo");
        fixed.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fixed));
        fixed.addParameter(new LirParameterDef("text", GdStringType.STRING, null, fixed));
        fixed.addParameter(new LirParameterDef("count", GdIntType.INT, null, fixed));
        entry(fixed).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fixed);

        var vararg = newFunction("echo");
        vararg.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, vararg));
        vararg.addParameter(new LirParameterDef("text", GdStringType.STRING, null, vararg));
        vararg.setVararg(true);
        entry(vararg).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(vararg);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("text", GdStringType.STRING);
        caller.createAndAddVariable("count", GdIntType.INT);
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "echo",
                "worker",
                List.of(
                        new LirInstruction.VariableOperand("text"),
                        new LirInstruction.VariableOperand("count")
                )
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_echo($worker, &$text, $count);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should fallback to OBJECT_DYNAMIC when overload resolution is ambiguous")
    void callMethodShouldFallbackToObjectDynamicOnAmbiguousOverload() {
        var clazz = newClass("Worker");
        var func = newFunction("call_ambiguous");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("arg", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "mix",
                "node",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithAmbiguousOverloads())), List.of(clazz));
        assertTrue(body.contains("godot_Object_call(gdcc_Node_fat_ptr_live_object($node), GD_STATIC_SN(u8\"mix\")"), body);
        assertFalse(body.contains("godot_Node_mix("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should resolve typed Array receiver to Array builtin metadata")
    void callMethodTypedArrayReceiverShouldResolveBuiltinMetadata() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typed_array_size");
        func.createAndAddVariable("values", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "size",
                "values",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithSize()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_size(&$values)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should reject incompatible fixed argument type")
    void callMethodShouldRejectArgumentTypeMismatch() {
        var clazz = newClass("Worker");
        var func = newFunction("call_rotated_bad_arg");
        func.createAndAddVariable("vector", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("axis", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("angle", GdStringType.STRING);
        func.createAndAddVariable("ret", GdFloatVectorType.VECTOR3);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "rotated",
                "vector",
                List.of(
                        new LirInstruction.VariableOperand("axis"),
                        new LirInstruction.VariableOperand("angle")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(vector3Builtin()), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Cannot assign value of type 'String'"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject resultId for void method")
    void callMethodShouldRejectResultForVoidMethod() {
        var clazz = newClass("Worker");
        var func = newFunction("call_queue_free_with_result");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("ret", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn("ret", "queue_free", "node", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("has no return value"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject exact engine route when metadata hash is missing")
    void callMethodShouldRejectExactEngineRouteWhenMetadataHashIsMissing() {
        var clazz = newClass("Worker");
        var func = newFunction("call_queue_free_missing_hash");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn(null, "queue_free", "node", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree(0L))), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Exact engine method 'Node.queue_free' is missing method-bind hash"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject missing argument variable")
    void callMethodShouldRejectMissingArgumentVariable() {
        var clazz = newClass("Worker");
        var func = newFunction("call_rotated_missing_arg");
        func.createAndAddVariable("vector", GdFloatVectorType.VECTOR3);
        func.createAndAddVariable("ret", GdFloatVectorType.VECTOR3);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "rotated",
                "vector",
                List.of(
                        new LirInstruction.VariableOperand("axis"),
                        new LirInstruction.VariableOperand("angle")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(vector3Builtin()), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Argument variable ID 'axis' not found"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should materialize extension literal defaults")
    void callMethodShouldMaterializeExtensionLiteralDefault() {
        var clazz = newClass("Worker");
        var func = newFunction("call_substr_default");
        func.createAndAddVariable("text", GdStringType.STRING);
        func.createAndAddVariable("from", GdIntType.INT);
        func.createAndAddVariable("ret", GdStringType.STRING);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "substr",
                "text",
                List.of(new LirInstruction.VariableOperand("from"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(stringBuiltinWithSubstrDefault()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_String_substr(&$text, $from, __gdcc_tmp_default_arg_2_"), body);
        assertTrue(body.contains("__gdcc_tmp_default_arg_2_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should pass provided bitfield arguments through normalized engine helper surface")
    void callMethodShouldPassProvidedBitfieldArgThroughNormalizedHelperSurface() {
        var clazz = newClass("Worker");
        var func = newFunction("call_set_process_thread_messages");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("flags", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "set_process_thread_messages",
                "node",
                List.of(new LirInstruction.VariableOperand("flags"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithBitfieldDefaultParam())), List.of(clazz));
        assertTrue(body.contains("gdcc_engine_call_node_set_process_thread_messages_PI_RV($node, $flags);"), body);
        assertFalse(body.contains("__gdcc_tmp_bitfield_arg_1_"), body);
        assertFalse(body.contains("(const godot_Node_ProcessThreadMessages *)&"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should materialize bitfield defaults through normalized engine helper surface")
    void callMethodShouldMaterializeBitfieldDefaultThroughNormalizedHelperSurface() {
        var clazz = newClass("Worker");
        var func = newFunction("call_set_process_thread_messages_default");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "set_process_thread_messages",
                "node",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithBitfieldDefaultParam())), List.of(clazz));
        assertTrue(body.contains("godot_int __gdcc_tmp_default_arg_1_"), body);
        assertTrue(body.contains("gdcc_engine_call_node_set_process_thread_messages_PI_RV($node, __gdcc_tmp_default_arg_1_"), body);
        assertFalse(body.contains("(const godot_Node_ProcessThreadMessages *)&"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should materialize GDCC instance default_value_func")
    void callMethodShouldMaterializeGdccInstanceDefaultFunction() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("default_count");
        defaultCount.setReturnType(GdIntType.INT);
        defaultCount.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, defaultCount));
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "default_count", ping));
        ping.setReturnType(GdVoidType.VOID);
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_default_count($worker)"), body);
        assertTrue(body.contains("Worker_ping($worker, __gdcc_tmp_default_arg_1_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should materialize GDCC static default_value_func without receiver")
    void callMethodShouldMaterializeGdccStaticDefaultFunction() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("default_count_static");
        defaultCount.setStatic(true);
        defaultCount.setReturnType(GdIntType.INT);
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "default_count_static", ping));
        ping.setReturnType(GdVoidType.VOID);
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("Worker_default_count_static()"), body);
        assertFalse(body.contains("Worker_default_count_static($worker)"), body);
        assertTrue(body.contains("Worker_ping($worker, __gdcc_tmp_default_arg_1_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should materialize multiple source defaults in declaration order")
    void callMethodShouldMaterializeMultipleSourceDefaultsInDeclarationOrder() {
        var workerClass = newClass("Worker");

        var defaultCount = newFunction("_default_ping$count");
        defaultCount.setReturnType(GdIntType.INT);
        defaultCount.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, defaultCount));
        workerClass.addFunction(defaultCount);

        var defaultLabel = newFunction("_default_ping$label");
        defaultLabel.setReturnType(GdStringType.STRING);
        defaultLabel.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, defaultLabel));
        workerClass.addFunction(defaultLabel);

        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("a", GdIntType.INT, null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_ping$count", ping));
        ping.addParameter(new LirParameterDef("label", GdStringType.STRING, "_default_ping$label", ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("a", GdIntType.INT);
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of(new LirInstruction.VariableOperand("a"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        // Each omitted trailing argument re-invokes its own shell at the call site, in declaration
        // order, before the completed call (per-call re-evaluation, no shared caching).
        assertOrdered(
                body,
                "Worker__default_ping$count($worker)",
                "Worker__default_ping$label($worker)",
                "Worker_ping($worker, $a, __gdcc_tmp_default_arg_2_"
        );
        assertTrue(body.contains("__gdcc_tmp_default_arg_3_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should reject too-few arguments below the required prefix of a source default method")
    void callMethodShouldRejectTooFewBelowRequiredPrefixOfSourceDefaultMethod() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("_default_ping$count");
        defaultCount.setReturnType(GdIntType.INT);
        defaultCount.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, defaultCount));
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("a", GdIntType.INT, null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_ping$count", ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("Too few arguments"), ex.getMessage());
        assertTrue(ex.getMessage().contains("missing required parameter #1"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject too-many arguments on a source default method")
    void callMethodShouldRejectTooManyOnSourceDefaultMethod() {
        var workerClass = newClass("Worker");
        var defaultCount = newFunction("_default_ping$count");
        defaultCount.setReturnType(GdIntType.INT);
        defaultCount.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, defaultCount));
        workerClass.addFunction(defaultCount);

        var ping = newFunction("ping");
        ping.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, ping));
        ping.addParameter(new LirParameterDef("a", GdIntType.INT, null, ping));
        ping.addParameter(new LirParameterDef("count", GdIntType.INT, "_default_ping$count", ping));
        entry(ping).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(ping);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("a", GdIntType.INT);
        caller.createAndAddVariable("b", GdIntType.INT);
        caller.createAndAddVariable("c", GdIntType.INT);
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "ping",
                "worker",
                List.of(
                        new LirInstruction.VariableOperand("a"),
                        new LirInstruction.VariableOperand("b"),
                        new LirInstruction.VariableOperand("c")
                )
        ));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("Too many arguments"), ex.getMessage());
        assertTrue(ex.getMessage().contains("expected 2, got 3"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject non-Variant vararg arguments")
    void callMethodShouldRejectNonVariantVarargArgument() {
        var clazz = newClass("Worker");
        var func = newFunction("call_vararg_bad_extra");
        func.createAndAddVariable("node", new GdObjectType("Node"));
        func.createAndAddVariable("name", GdStringNameType.STRING_NAME);
        func.createAndAddVariable("extra", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "spread",
                "node",
                List.of(
                        new LirInstruction.VariableOperand("name"),
                        new LirInstruction.VariableOperand("extra")
                )
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithVarargSpread())), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Vararg argument #2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("must be Variant"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should normalize typedarray PackedByteArray parameter to packed array type")
    void callMethodShouldNormalizeTypedarrayPackedByteArrayParameter() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typedarray_packed_param");
        func.createAndAddVariable("array", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("bytes", GdPackedNumericArrayType.PACKED_BYTE_ARRAY);
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "accept_packed",
                "array",
                List.of(new LirInstruction.VariableOperand("bytes"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithTypedarrayPackedByteArrayParam()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_accept_packed(&$array, &$bytes)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should normalize typedarray StringName parameter to Array[StringName]")
    void callMethodShouldNormalizeTypedarrayStringNameParameter() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typedarray_string_name_param");
        func.createAndAddVariable("array", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("names", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "accept_names",
                "array",
                List.of(new LirInstruction.VariableOperand("names"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithTypedarrayStringNameParam()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_accept_names(&$array, &$names)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should normalize typedarray PackedVector3Array return type")
    void callMethodShouldNormalizeTypedarrayPackedVector3ArrayReturn() {
        var clazz = newClass("Worker");
        var func = newFunction("call_typedarray_packed_return");
        func.createAndAddVariable("array", new GdArrayType(GdStringType.STRING));
        func.createAndAddVariable("ret", GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "fetch_packed_vectors",
                "array",
                List.of()
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(arrayBuiltinWithTypedarrayPackedVector3ArrayReturn()), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Array_fetch_packed_vectors(&$array)"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit OBJECT_DYNAMIC dispatch with argument pack and return unpack")
    void callMethodObjectDynamicShouldPackAndUnpack() {
        var clazz = newClass("Worker");
        var func = newFunction("call_object_dynamic");
        // Unknown object types fail-fast; use a known engine type for this dynamic-call fixture.
        func.createAndAddVariable("obj", new GdObjectType("Node"));
        func.createAndAddVariable("value", GdIntType.INT);
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "compute",
                "obj",
                List.of(new LirInstruction.VariableOperand("value"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of(nodeClassWithQueueFree())), List.of(clazz));
        assertTrue(body.contains("godot_Object_call(gdcc_Node_fat_ptr_live_object($obj), GD_STATIC_SN(u8\"compute\")"), body);
        assertTrue(body.contains("godot_new_Variant_with_int($value)"), body);
        assertTrue(body.contains("godot_new_int_with_Variant("), body);
        assertFalse(body.contains("godot_Variant_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should emit VARIANT_DYNAMIC dispatch and keep Variant result")
    void callMethodVariantDynamicShouldEmitCall() {
        var clazz = newClass("Worker");
        clazz.setSourceFile("worker_dynamic.gd");
        var func = newFunction("call_variant_dynamic");
        func.createAndAddVariable("recv", GdVariantType.VARIANT);
        func.createAndAddVariable("arg", GdIntType.INT);
        func.createAndAddVariable("ret", GdVariantType.VARIANT);
        entry(func).appendInstruction(new LineNumberInsn(11));
        entry(func).appendInstruction(new LineNumberInsn(22));
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "mystery",
                "recv",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        entry(func).appendInstruction(new LineNumberInsn(99));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Variant_call(&$recv, GD_STATIC_SN(u8\"mystery\")"), body);
        assertTrue(body.contains("\"worker_dynamic.gd\", 22"), body);
        assertFalse(body.contains("\"worker_dynamic.gd\", 99"), body);
        assertTrue(body.contains("godot_new_Variant_with_int($arg)"), body);
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_"), body);
        assertFalse(body.contains("variant_call_argv"), body);
        assertFalse(body.contains("godot_Object_call("), body);
    }

    @Test
    @DisplayName("CALL_METHOD should unpack VARIANT_DYNAMIC return into non-Variant target with assemble fallback location")
    void callMethodVariantDynamicShouldUnpackReturn() {
        var clazz = newClass("Worker");
        clazz.setSourceFile("worker_dynamic.gd");
        var func = newFunction("call_variant_dynamic_unpack");
        func.createAndAddVariable("recv", GdVariantType.VARIANT);
        func.createAndAddVariable("arg", GdIntType.INT);
        func.createAndAddVariable("ret", GdIntType.INT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "mystery",
                "recv",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Variant_call(&$recv, GD_STATIC_SN(u8\"mystery\")"), body);
        assertTrue(body.contains("\"worker_dynamic.gd(assemble)\", 0"), body);
        assertTrue(body.contains("godot_new_int_with_Variant("), body);
        assertTrue(body.contains("const godot_Variant* __gdcc_tmp_argv_"), body);
    }

    @Test
    @DisplayName("CALL_METHOD should fallback to class name when sourceFile is missing in VARIANT_DYNAMIC location")
    void callMethodVariantDynamicShouldUseClassNameWhenSourceFileMissing() {
        var clazz = newClass("WorkerNoSource");
        var func = newFunction("call_variant_dynamic_no_source_file");
        func.createAndAddVariable("recv", GdVariantType.VARIANT);
        func.createAndAddVariable("arg", GdIntType.INT);
        func.createAndAddVariable("ret", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "mystery",
                "recv",
                List.of(new LirInstruction.VariableOperand("arg"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz));
        assertTrue(body.contains("godot_Variant_call(&$recv, GD_STATIC_SN(u8\"mystery\")"), body);
        assertTrue(body.contains("\"WorkerNoSource(assemble)\", 0"), body);
    }

    @Test
    @DisplayName("CALL_METHOD dynamic paths should reject ref result variable")
    void callMethodDynamicShouldRejectRefResult() {
        var clazz = newClass("Worker");
        var func = newFunction("call_object_dynamic_ref_result");
        func.createAndAddVariable("obj", new GdObjectType("MysteryObject"));
        func.createAndAddRefVariable("ret", GdVariantType.VARIANT);
        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "compute",
                "obj",
                List.of()
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("cannot be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject compiler-only dynamic argument before Variant pack")
    void callMethodShouldRejectCompilerOnlyDynamicArgument() {
        var clazz = newClass("Worker");
        var func = newFunction("call_dynamic_with_compiler_only_arg");
        func.createAndAddVariable("obj", new GdObjectType("MysteryObject"));
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);

        entry(func).appendInstruction(new CallMethodInsn(
                null,
                "compute",
                "obj",
                List.of(new LirInstruction.VariableOperand("iter"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("compiler-only type leaked into call_method argument #1"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD should reject compiler-only dynamic result target before Variant unpack")
    void callMethodShouldRejectCompilerOnlyDynamicResultTarget() {
        var clazz = newClass("Worker");
        var func = newFunction("call_dynamic_into_compiler_only_result");
        func.createAndAddVariable("obj", new GdObjectType("MysteryObject"));
        func.createAndAddVariable("ret", GdccForRangeIterType.FOR_RANGE_ITER);

        entry(func).appendInstruction(new CallMethodInsn(
                "ret",
                "compute",
                "obj",
                List.of()
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, newApi(List.of(), List.of()), List.of(clazz))
        );
        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("compiler-only type leaked into dynamic call result target variable 'ret'"), ex.getMessage());
    }

    // ==== Coroutine internal ABI (frontend_await_implementation.md §10) ====
    // A call on an `isCoroutine` GDCC callee targets the coroutine-start thunk and publishes
    // the OWNED state object reference as a compiler::GdccCoroState result.

    @Test
    @DisplayName("CALL_METHOD on a GDCC coroutine should call the start thunk into a GdccCoroState result")
    void callMethodGdccCoroutineShouldCallStartThunk() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setCoroutine(true);
        fire.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fire));
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallMethodInsn(
                "state",
                "fire",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        // Overwrite discipline: the previously held state reference (or NULL) is released first.
        assertTrue(body.contains("gdcc_coro_state_slot_destroy(&$state);"), body);
        assertTrue(body.contains("$state = Worker_fire__coro_start($worker);"), body);
        assertFalse(body.contains("Worker_fire($worker)"), body);
        // Slot-write order: release the old reference BEFORE the new OWNED reference lands.
        assertOrdered(body, "gdcc_coro_state_slot_destroy(&$state);", "$state = Worker_fire__coro_start($worker);");
    }

    @Test
    @DisplayName("CALL_METHOD on a GDCC coroutine should forward fixed arguments to the start thunk")
    void callMethodGdccCoroutineShouldForwardArguments() {
        var workerClass = newClass("Worker");
        var sumTo = newFunction("sum_to");
        sumTo.setCoroutine(true);
        sumTo.setReturnType(GdIntType.INT);
        sumTo.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, sumTo));
        sumTo.addParameter(new LirParameterDef("count", GdIntType.INT, null, sumTo));
        entry(sumTo).appendInstruction(new ReturnInsn("count"));
        workerClass.addFunction(sumTo);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("count", GdIntType.INT);
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallMethodInsn(
                "state",
                "sum_to",
                "worker",
                List.of(new LirInstruction.VariableOperand("count"))
        ));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("$state = Worker_sum_to__coro_start($worker, $count);"), body);
    }

    @Test
    @DisplayName("CALL_METHOD on a polymorphic GDCC coroutine should call the start thunk through the vtable")
    void callMethodPolymorphicCoroutineShouldCallStartThunkIndirectly() {
        // Coroutine slots store start thunks (§2.2); the indirect path replaces only the callee,
        // so the result keeps the compiler::GdccCoroState slot-write discipline (§2.6).
        var parentClass = newClass("CoroRoot");
        var fire = newFunction("fire");
        fire.setCoroutine(true);
        fire.addParameter(new LirParameterDef("self", new GdObjectType("CoroRoot"), null, fire));
        entry(fire).appendInstruction(new ReturnInsn(null));
        parentClass.addFunction(fire);

        var childClass = newClass("CoroDerived", "CoroRoot");
        var childFire = newFunction("fire");
        childFire.setCoroutine(true);
        childFire.addParameter(new LirParameterDef("self", new GdObjectType("CoroDerived"), null, childFire));
        entry(childFire).appendInstruction(new ReturnInsn(null));
        childClass.addFunction(childFire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("CoroRoot"));
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallMethodInsn("state", "fire", "worker", List.of()));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, childClass, parentClass));
        assertTrue(body.contains("$state = CoroRoot_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_fire(__gdcc_tmp_vt_recv_0);"), body);
        assertFalse(body.contains("CoroRoot_fire__coro_start("), body);
        assertFalse(body.contains("CoroRoot_fire("), body);
        // Slot-write order unchanged: release the old state reference BEFORE the new one lands.
        assertOrdered(
                body,
                "gdcc_coro_state_slot_destroy(&$state);",
                "$state = CoroRoot_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_fire(__gdcc_tmp_vt_recv_0);"
        );
    }

    @Test
    @DisplayName("CALL_METHOD statement-position coroutine call should detach via INTERNAL destruct")
    void callMethodGdccCoroutineStatementShouldDetachViaInternalDestruct() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setCoroutine(true);
        fire.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fire));
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("__coro_state_7", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallMethodInsn(
                "__coro_state_7",
                "fire",
                "worker",
                List.of()
        ));
        // Fire-and-forget: releasing the last call-site reference detaches the coroutine,
        // which stays alive through its own wait edges (statement-root position).
        entry(caller).appendInstruction(new DestructInsn("__coro_state_7", LifecycleProvenance.INTERNAL));
        hostClass.addFunction(caller);

        var body = generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass));
        assertTrue(body.contains("$__coro_state_7 = Worker_fire__coro_start($worker);"), body);
        assertTrue(body.contains("gdcc_coro_state_slot_destroy(&$__coro_state_7);"), body);
        // The state reference must be acquired before the detach release consumes it.
        assertOrdered(
                body,
                "$__coro_state_7 = Worker_fire__coro_start($worker);",
                "gdcc_coro_state_slot_destroy(&$__coro_state_7);"
        );
    }

    @Test
    @DisplayName("CALL_METHOD on a GDCC coroutine without a result should fail fast")
    void callMethodGdccCoroutineWithoutResultShouldFailFast() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setCoroutine(true);
        fire.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fire));
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        entry(caller).appendInstruction(new CallMethodInsn(
                null,
                "fire",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("must declare a compiler::GdccCoroState result variable"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD on a GDCC coroutine with a non-GdccCoroState result should fail fast")
    void callMethodGdccCoroutineWithWrongResultTypeShouldFailFast() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setCoroutine(true);
        fire.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fire));
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("state", GdVariantType.VARIANT);
        entry(caller).appendInstruction(new CallMethodInsn(
                "state",
                "fire",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("must be declared compiler::GdccCoroState"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD on a static GDCC coroutine should fail fast (Post-MVP)")
    void callMethodGdccStaticCoroutineShouldFailFast() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setCoroutine(true);
        fire.setStatic(true);
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallMethodInsn(
                "state",
                "fire",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("static coroutine calls are not supported"), ex.getMessage());
    }

    @Test
    @DisplayName("CALL_METHOD on a vararg GDCC coroutine should fail fast (fixed-parameter start thunk)")
    void callMethodGdccVarargCoroutineShouldFailFast() {
        var workerClass = newClass("Worker");
        var fire = newFunction("fire");
        fire.setCoroutine(true);
        fire.setVararg(true);
        fire.addParameter(new LirParameterDef("self", new GdObjectType("Worker"), null, fire));
        entry(fire).appendInstruction(new ReturnInsn(null));
        workerClass.addFunction(fire);

        var hostClass = newClass("Host");
        var caller = newFunction("run");
        caller.createAndAddVariable("worker", new GdObjectType("Worker"));
        caller.createAndAddVariable("state", GdccCoroStateType.CORO_STATE);
        entry(caller).appendInstruction(new CallMethodInsn(
                "state",
                "fire",
                "worker",
                List.of()
        ));
        hostClass.addFunction(caller);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(hostClass, caller, newApi(List.of(), List.of()), List.of(hostClass, workerClass))
        );
        assertTrue(ex.getMessage().contains("vararg"), ex.getMessage());
    }

    /// Asserts each fragment exists and appears strictly after the previous one: used where the
    /// lifecycle contract is an ordering (release-before-overwrite, acquire-before-detach), not
    /// mere presence. Each fragment is searched after the previous match, so repeated fragments
    /// (e.g. a slot destroy that is also the detach statement) are matched as distinct occurrences.
    private static void assertOrdered(String text, String... fragmentsInOrder) {
        var searchFromIndex = 0;
        for (var fragment : fragmentsInOrder) {
            var index = text.indexOf(fragment, searchFromIndex);
            assertTrue(index >= 0, () -> "Missing fragment: " + fragment + "\n" + text);
            searchFromIndex = index + fragment.length();
        }
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

    private ExtensionBuiltinClass signalBuiltin() {
        var emit = new ExtensionBuiltinClass.ClassMethod(
                "emit",
                "void",
                true,
                true,
                false,
                false,
                3286317445L,
                List.of(),
                List.of(),
                null
        );
        return new ExtensionBuiltinClass(
                "Signal",
                false,
                List.of(),
                List.of(emit),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass vector3Builtin() {
        var rotated = new ExtensionBuiltinClass.ClassMethod(
                "rotated",
                "Vector3",
                false,
                true,
                false,
                false,
                0L,
                List.of(
                        new ExtensionFunctionArgument("axis", "Vector3", null, null),
                        new ExtensionFunctionArgument("angle", "float", null, null)
                ),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("Vector3")
        );
        return new ExtensionBuiltinClass(
                "Vector3",
                false,
                List.of(),
                List.of(rotated),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithQueueFree() {
        return nodeClassWithQueueFree(77L);
    }

    private ExtensionGdClass nodeClassWithQueueFree(long hash) {
        var queueFree = new ExtensionGdClass.ClassMethod(
                "queue_free",
                false,
                false,
                false,
                false,
                hash,
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

    private ExtensionGdClass nodeClassWithBitfieldDefaultParam() {
        return nodeClassWithBitfieldDefaultParam(67L);
    }

    private ExtensionGdClass nodeClassWithBitfieldDefaultParam(long hash) {
        var setProcessThreadMessages = new ExtensionGdClass.ClassMethod(
                "set_process_thread_messages",
                false,
                false,
                false,
                false,
                hash,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("flags", "bitfield::Node.ProcessThreadMessages", "0", null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(setProcessThreadMessages),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithStaticFactory() {
        return nodeClassWithStaticFactory(88L);
    }

    private ExtensionGdClass nodeClassWithStaticFactory(long hash) {
        var make = new ExtensionGdClass.ClassMethod(
                "make",
                false,
                false,
                true,
                false,
                hash,
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
                List.of(make),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithAddChild() {
        var addChild = new ExtensionGdClass.ClassMethod(
                "add_child",
                false,
                false,
                false,
                false,
                79L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(
                        new ExtensionFunctionArgument("node", "Node", null, null),
                        new ExtensionFunctionArgument("legible_unique_name", "bool", "false", null),
                        new ExtensionFunctionArgument("internal", "int", "0", null)
                )
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(addChild),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithAmbiguousOverloads() {
        var mixNode = new ExtensionGdClass.ClassMethod(
                "mix",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("value", "Node", null, null))
        );
        var mixObject = new ExtensionGdClass.ClassMethod(
                "mix",
                false,
                false,
                false,
                false,
                0L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("value", "Object", null, null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(mixNode, mixObject),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass arrayBuiltinWithSize() {
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

    private ExtensionBuiltinClass stringBuiltinWithSubstrDefault() {
        var substr = new ExtensionBuiltinClass.ClassMethod(
                "substr",
                "String",
                false,
                true,
                false,
                false,
                0L,
                List.of(
                        new ExtensionFunctionArgument("from", "int", null, null),
                        new ExtensionFunctionArgument("len", "int", "-1", null)
                ),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("String")
        );
        return new ExtensionBuiltinClass(
                "String",
                false,
                List.of(),
                List.of(substr),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithVarargSpread() {
        return nodeClassWithVarargSpread(93L);
    }

    private ExtensionGdClass nodeClassWithVarargSpread(long hash) {
        var spread = new ExtensionGdClass.ClassMethod(
                "spread",
                false,
                true,
                false,
                false,
                hash,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("name", "StringName", null, null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(spread),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionGdClass nodeClassWithStaticVarargBroadcast(long hash) {
        var broadcast = new ExtensionGdClass.ClassMethod(
                "broadcast",
                false,
                true,
                true,
                false,
                hash,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("void"),
                List.of(new ExtensionFunctionArgument("prefix", "int", null, null))
        );
        return new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(broadcast),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass arrayBuiltinWithTypedarrayPackedByteArrayParam() {
        var method = new ExtensionBuiltinClass.ClassMethod(
                "accept_packed",
                "int",
                false,
                true,
                false,
                false,
                0L,
                List.of(new ExtensionFunctionArgument("bytes", "typedarray::PackedByteArray", null, null)),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass arrayBuiltinWithTypedarrayStringNameParam() {
        var method = new ExtensionBuiltinClass.ClassMethod(
                "accept_names",
                "int",
                false,
                true,
                false,
                false,
                0L,
                List.of(new ExtensionFunctionArgument("names", "typedarray::StringName", null, null)),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("int")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass arrayBuiltinWithTypedarrayPackedVector3ArrayReturn() {
        var method = new ExtensionBuiltinClass.ClassMethod(
                "fetch_packed_vectors",
                "typedarray::PackedVector3Array",
                false,
                true,
                false,
                false,
                0L,
                List.of(),
                List.of(),
                new ExtensionBuiltinClass.ClassMethod.ReturnValue("typedarray::PackedVector3Array")
        );
        return new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(),
                List.of(method),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }
}
