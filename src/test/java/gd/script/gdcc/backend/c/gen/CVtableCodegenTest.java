package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.GeneratedFile;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Golden anchoring for Step 2 vtable layout emission (virtual_override_vtable_implementation.md
/// §2.2/§2.3/§2.7): entry.h typedefs, the root `_vtable` field, accessors, trampolines, vtable
/// instances and the four create_instance initialization branches. Positive cases pin the exact
/// symbol shapes and value/typedef-chain separation; negative cases pin the absence of symbols
/// for slotless/pass-through classes and the conditional accessor conflict registration.
public class CVtableCodegenTest {

    @Test
    @DisplayName("A(introduces foo)→B(pass-through)→C(introduces bar)→D(overrides both): full vtable layout")
    void introducerPassThroughChainGeneratesFullVtableLayout() throws Exception {
        var classA = newClass("VtA", "Node", newEchoIntMethod("VtA", "foo"));
        var classB = newClass("VtB", "VtA");
        var classC = newClass("VtC", "VtB", newVoidInstanceMethod("VtC", "bar"));
        var classD = newClass("VtD", "VtC", newEchoIntMethod("VtD", "foo"), newVoidInstanceMethod("VtD", "bar"));

        var files = generate(classA, classB, classC, classD);
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        // Root wrapper: `_vtable` sits directly after `_object` (the hierarchy carries slots).
        assertOrdered(resolveBlockBody(hCode, "struct VtA {"),
                "GDExtensionObjectPtr _object;", "const void* _vtable;");

        // Typedefs: A's own segment; C embeds the NEAREST INTRODUCER ancestor A (skipping the
        // pass-through B) and appends its own slot after the prefix.
        assertContainsAll(resolveBlockBody(hCode, "typedef struct gdcc_VtA_vtable {"),
                "godot_int (*m_foo)(gdcc_VtA_fat_ptr $self, godot_int $value);");
        var cTypedef = resolveBlockBody(hCode, "typedef struct gdcc_VtC_vtable {");
        assertOrdered(cTypedef,
                "gdcc_VtA_vtable _super;",
                "void (*m_bar)(gdcc_VtC_fat_ptr $self);");
        // Pass-through B owns no vtable symbol of any kind.
        assertFalse(hCode.contains("gdcc_VtB_vtable"), hCode);
        assertFalse(hCode.contains("VtB_class_vtable"), hCode);
        // Accessor declarations exist for introducers only.
        assertContainsAll(hCode,
                "static inline const gdcc_VtA_vtable* VtA_class_vtable(VtA* self);",
                "static inline const gdcc_VtC_vtable* VtC_class_vtable(VtC* self);");

        // Accessor definitions walk the WRAPPER chain straight to the root field — never a
        // parent accessor (B is pass-through and has none to recurse into).
        assertContainsAll(resolveFunctionBodyByPrefix(cCode, "static inline const gdcc_VtA_vtable* VtA_class_vtable"),
                "return (const gdcc_VtA_vtable*)self->_vtable;");
        var cAccessorBody = resolveFunctionBodyByPrefix(cCode, "static inline const gdcc_VtC_vtable* VtC_class_vtable");
        assertContainsAll(cAccessorBody, "return (const gdcc_VtC_vtable*)self->_super._super._vtable;");
        assertFalse(cAccessorBody.contains("_class_vtable("), cAccessorBody);

        // Instances: every entry is the slot's final overrider for that class — A and C fill
        // their own implementations (C does not override foo, so A's impl survives the prefix);
        // D overrides both and fills trampolines.
        assertContainsAll(cCode,
                "static const gdcc_VtA_vtable gdcc_VtA_vtable_inst = { .m_foo = VtA_foo };",
                "static const gdcc_VtC_vtable gdcc_VtC_vtable_inst = { ._super = { .m_foo = VtA_foo }, .m_bar = VtC_bar };",
                "static const gdcc_VtC_vtable gdcc_VtD_vtable_inst = { ._super = { .m_foo = gdcc_VtD_vslot_foo }, .m_bar = gdcc_VtD_vslot_bar };");

        // Trampolines: introducer-typed signature, sanctioned downcast compound literal, impl
        // call — value-returning foo forwards args with `return`, void bar does not.
        assertContainsAll(cCode,
                "static godot_int gdcc_VtD_vslot_foo(gdcc_VtA_fat_ptr $self, godot_int $value)",
                "static void gdcc_VtD_vslot_bar(gdcc_VtC_fat_ptr $self)");
        var fooTrampolineBody = resolveFunctionBodyByPrefix(cCode, "static godot_int gdcc_VtD_vslot_foo");
        assertContainsAll(fooTrampolineBody,
                "gdcc_VtD_fat_ptr s = { (VtD*)$self.ptr, $self.instance_id };",
                "return VtD_foo(s, $value);");
        var barTrampolineBody = resolveFunctionBodyByPrefix(cCode, "static void gdcc_VtD_vslot_bar");
        assertContainsAll(barTrampolineBody, "VtD_bar(s);");
        assertFalse(barTrampolineBody.contains("return VtD_bar"), barTrampolineBody);

        // create_instance: branch 3 for introducer/override-only classes (own instance), branch
        // 4 for the pass-through B (nearest non-pass-through ancestor's value, never NULL); the
        // write always precedes the POSTINITIALIZE notification.
        assertCreateInstanceWrites(cCode, "VtA", "self->_vtable = &gdcc_VtA_vtable_inst;");
        assertCreateInstanceWrites(cCode, "VtB", "self->_super._vtable = &gdcc_VtA_vtable_inst;");
        assertCreateInstanceWrites(cCode, "VtC", "self->_super._super._vtable = &gdcc_VtC_vtable_inst;");
        assertCreateInstanceWrites(cCode, "VtD", "self->_super._super._super._vtable = &gdcc_VtD_vtable_inst;");
        assertFalse(resolveCreateInstanceBody(cCode, "VtB").contains("NULL"),
                "pass-through classes must never initialize _vtable with NULL");
    }

    @Test
    @DisplayName("side branches write NULL (not no-op) while the slotted branch writes its own instance")
    void sideBranchHierarchyInitializesNullWithoutCollapsingToNoAssignment() throws Exception {
        var root = newClass("SbRoot", "Node");
        var classA = newClass("SbA", "SbRoot", newVoidInstanceMethod("SbA", "foo"));
        var classAChild = newClass("SbAChild", "SbA", newVoidInstanceMethod("SbAChild", "foo"));
        var classB = newClass("SbB", "SbRoot");

        var files = generate(root, classA, classAChild, classB);
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        // The hierarchy carries slots through SbA, so the root segment owns the field even
        // though the root itself is slotless; slotless classes own no vtable symbols.
        assertContainsAll(resolveBlockBody(hCode, "struct SbRoot {"), "const void* _vtable;");
        assertFalse(hCode.contains("gdcc_SbRoot_vtable"), hCode);
        assertFalse(hCode.contains("gdcc_SbB_vtable"), hCode);

        // Branch 2: side branches (root and SbB) write NULL — an explicit assignment, not the
        // branch-1 no-op, because their instances still carry the root field.
        assertCreateInstanceWrites(cCode, "SbRoot", "self->_vtable = NULL;");
        assertCreateInstanceWrites(cCode, "SbB", "self->_super._vtable = NULL;");
        // Branch 3 on the slotted branch: introducer and override-only child write own instances.
        assertCreateInstanceWrites(cCode, "SbA", "self->_super._vtable = &gdcc_SbA_vtable_inst;");
        assertCreateInstanceWrites(cCode, "SbAChild", "self->_super._super._vtable = &gdcc_SbAChild_vtable_inst;");
        assertContainsAll(cCode,
                "static const gdcc_SbA_vtable gdcc_SbAChild_vtable_inst = { .m_foo = gdcc_SbAChild_vslot_foo };");
    }

    @Test
    @DisplayName("A(introduces foo)→B(override-only)→C(pass-through): typedef chain follows A, value chain follows B")
    void overrideOnlyAndPassThroughSeparateTypedefChainFromValueChain() throws Exception {
        var classA = newClass("ChA", "Node", newVoidInstanceMethod("ChA", "foo"));
        var classB = newClass("ChB", "ChA", newVoidInstanceMethod("ChB", "foo"));
        var classC = newClass("ChC", "ChB");

        var files = generate(classA, classB, classC);
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        // Typedef chain: only the introducer A owns a typedef/accessor; the override-only B and
        // the pass-through C own none (no pure-alias types).
        assertContainsAll(hCode, "typedef struct gdcc_ChA_vtable");
        assertFalse(hCode.contains("gdcc_ChB_vtable "), hCode);
        assertFalse(hCode.contains("typedef struct gdcc_ChB_vtable"), hCode);
        assertFalse(hCode.contains("ChB_class_vtable"), hCode);
        assertFalse(hCode.contains("gdcc_ChC_vtable"), hCode);
        assertFalse(hCode.contains("ChC_class_vtable"), hCode);

        // B's instance is typed with A's typedef but carries B's trampoline — pointer identity
        // is B's own, not the ancestor's.
        assertContainsAll(cCode,
                "static const gdcc_ChA_vtable gdcc_ChB_vtable_inst = { .m_foo = gdcc_ChB_vslot_foo };");
        assertCreateInstanceWrites(cCode, "ChB", "self->_super._vtable = &gdcc_ChB_vtable_inst;");
        // Value chain: the pass-through C resolves to B's instance (the nearest non-pass-through
        // ancestor), NOT to the typedef owner A.
        var createC = resolveCreateInstanceBody(cCode, "ChC");
        assertContainsAll(createC, "self->_super._super._vtable = &gdcc_ChB_vtable_inst;");
        assertFalse(createC.contains("gdcc_ChA_vtable_inst"), createC);
    }

    @Test
    @DisplayName("derived-first module order: ancestor trampolines are still defined before referencing instances")
    void derivedFirstModuleOrderStillDefinesTrampolinesBeforeReferencingInstances() throws Exception {
        // C introduces m2 without overriding m1, so C's instance keeps B as m1's final
        // overrider and takes the address of B's trampoline. Static trampolines have no header
        // prototype, so emission must follow the base-before-derived order even when the module
        // lists classes derived-first — otherwise the generated C does not compile.
        var classA = newClass("DfA", "Node", newVoidInstanceMethod("DfA", "m1"));
        var classB = newClass("DfB", "DfA", newVoidInstanceMethod("DfB", "m1"));
        var classC = newClass("DfC", "DfB", newVoidInstanceMethod("DfC", "m2"));
        var classD = newClass("DfD", "DfC", newVoidInstanceMethod("DfD", "m2"));

        var files = generate(classD, classC, classB, classA);
        var cCode = generatedFileText(files, "entry.c");

        assertContainsAll(cCode,
                "static void gdcc_DfB_vslot_m1(",
                "static const gdcc_DfC_vtable gdcc_DfC_vtable_inst = { ._super = { .m_m1 = gdcc_DfB_vslot_m1 }, .m_m2 = DfC_m2 };");
        // Definition-before-use: every trampoline precedes the (derived) instance referencing it.
        assertOrdered(cCode,
                "static void gdcc_DfB_vslot_m1(",
                "static const gdcc_DfC_vtable gdcc_DfC_vtable_inst =");
    }

    @Test
    @DisplayName("A(root, no methods)→B(introduces m1)→C(introduces m2)→D(overrides both): consecutive introducers never skip")
    void consecutiveIntroducersChainTypedefsAndInstances() throws Exception {
        // §2.2 four-level mixed chain: consecutive introducers must embed the ADJACENT
        // introducer's typedef (C embeds gdcc_B_vtable, never the more distant A).
        var classA = newClass("MxA", "Node");
        var classB = newClass("MxB", "MxA", newVoidInstanceMethod("MxB", "m1"));
        var classC = newClass("MxC", "MxB", newVoidInstanceMethod("MxC", "m2"));
        var classD = newClass("MxD", "MxC", newVoidInstanceMethod("MxD", "m1"), newVoidInstanceMethod("MxD", "m2"));

        var files = generate(classA, classB, classC, classD);
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        var bTypedef = resolveBlockBody(hCode, "typedef struct gdcc_MxB_vtable {");
        assertContainsAll(bTypedef, "void (*m_m1)(gdcc_MxB_fat_ptr $self);");
        assertFalse(bTypedef.contains("_super"), bTypedef);
        var cTypedef = resolveBlockBody(hCode, "typedef struct gdcc_MxC_vtable {");
        assertOrdered(cTypedef, "gdcc_MxB_vtable _super;", "void (*m_m2)(gdcc_MxC_fat_ptr $self);");

        // Mid-chain introduction (§2.2): the slotless root still owns the field and writes
        // NULL; D (override-only) reuses C's typedef with its own trampolines.
        assertCreateInstanceWrites(cCode, "MxA", "self->_vtable = NULL;");
        assertCreateInstanceWrites(cCode, "MxB", "self->_super._vtable = &gdcc_MxB_vtable_inst;");
        assertCreateInstanceWrites(cCode, "MxC", "self->_super._super._vtable = &gdcc_MxC_vtable_inst;");
        assertContainsAll(cCode,
                "static const gdcc_MxB_vtable gdcc_MxB_vtable_inst = { .m_m1 = MxB_m1 };",
                "static const gdcc_MxC_vtable gdcc_MxC_vtable_inst = { ._super = { .m_m1 = MxB_m1 }, .m_m2 = MxC_m2 };",
                "static const gdcc_MxC_vtable gdcc_MxD_vtable_inst = { ._super = { .m_m1 = gdcc_MxD_vslot_m1 }, .m_m2 = gdcc_MxD_vslot_m2 };");
    }

    @Test
    @DisplayName("hierarchy without polymorphic methods emits no vtable symbol anywhere")
    void slotlessHierarchyEmitsNoVtableSymbols() throws Exception {
        var classA = newClass("SlA", "Node", newVoidInstanceMethod("SlA", "foo"));
        var classB = newClass("SlB", "SlA", newVoidInstanceMethod("SlB", "bar"));

        var files = generate(classA, classB);
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        // Neither file may mention vtable machinery at all (`get_virtual_with_data` only
        // contains "virtual", never "vtable").
        assertFalse(hCode.contains("vtable"), hCode);
        assertFalse(cCode.contains("vtable"), cCode);
        assertFalse(resolveCreateInstanceBody(cCode, "SlA").contains("_vtable ="), "no assignment for slotless hierarchies");
        assertFalse(resolveCreateInstanceBody(cCode, "SlB").contains("_vtable ="), "no assignment for slotless hierarchies");
    }

    @Test
    @DisplayName("inner-class hierarchy: vtable symbols keep the raw canonical name, fat types stay normalized")
    void innerClassHierarchyUsesRawCanonicalVtableSymbols() throws Exception {
        var base = newClass("Outer__sub__Base", "Node", newVoidInstanceMethod("Outer__sub__Base", "foo"));
        var leaf = newClass("Outer__sub__Leaf", "Outer__sub__Base", newVoidInstanceMethod("Outer__sub__Leaf", "foo"));

        var files = generate(base, leaf);
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        // Identity/wrapper layer keeps raw `__sub__`; only the fat-pointer type normalizes.
        assertContainsAll(hCode,
                "typedef struct gdcc_Outer__sub__Base_vtable",
                "void (*m_foo)(gdcc_Outer_sub_Base_fat_ptr $self);",
                "static inline const gdcc_Outer__sub__Base_vtable* Outer__sub__Base_class_vtable(Outer__sub__Base* self);");
        assertContainsAll(cCode,
                "static const gdcc_Outer__sub__Base_vtable gdcc_Outer__sub__Leaf_vtable_inst = { .m_foo = gdcc_Outer__sub__Leaf_vslot_foo };",
                "gdcc_Outer_sub_Leaf_fat_ptr s = { (Outer__sub__Leaf*)$self.ptr, $self.instance_id };");
        assertCreateInstanceWrites(cCode, "Outer__sub__Leaf", "self->_super._vtable = &gdcc_Outer__sub__Leaf_vtable_inst;");
    }

    @Test
    @DisplayName("user method `class_vtable` conflicts only when the class actually emits the accessor")
    void classVtableUserMethodConflictsOnlyWhenAccessorEmitted() throws Exception {
        // Positive: the introducer emits CcA_class_vtable as its accessor AND as the user
        // function — the conflict validator must fail fast with the colliding symbol.
        var clashBase = newClass("CcA", "Node",
                newVoidInstanceMethod("CcA", "foo"), newVoidInstanceMethod("CcA", "class_vtable"));
        var clashChild = newClass("CcChild", "CcA", newVoidInstanceMethod("CcChild", "foo"));
        var clashModule = new LirModule("accessor_clash_module", List.of(clashBase, clashChild));
        var clashCodegen = new CCodegen();
        clashCodegen.prepare(newTestContext(), clashModule);
        var exception = assertThrows(IllegalStateException.class, clashCodegen::generate);
        assertTrue(exception.getMessage().contains("CcA_class_vtable"), exception.getMessage());

        // Negative: without a polymorphic method the class emits no accessor, so the same user
        // method name stays legal (the accessor symbol is registered conditionally).
        var fineBase = newClass("NcA", "Node",
                newVoidInstanceMethod("NcA", "foo"), newVoidInstanceMethod("NcA", "class_vtable"));
        var fineChild = newClass("NcChild", "NcA", newVoidInstanceMethod("NcChild", "bar"));
        var files = generate(fineBase, fineChild);
        assertContainsAll(generatedFileText(files, "entry.h"), "NcA_class_vtable(");
    }

    @Test
    @DisplayName("coroutine slot: signature, entries and trampoline all use the start thunk")
    void coroutineSlotUsesStartThunkSignatureAndEntries() throws Exception {
        var classA = newClass("CoA", "Node", newCoroutineInstanceMethod("CoA", "foo"));
        var classD = newClass("CoD", "CoA", newCoroutineInstanceMethod("CoD", "foo"));

        var files = generate(classA, classD);
        var hCode = generatedFileText(files, "entry.h");
        var cCode = generatedFileText(files, "entry.c");

        assertContainsAll(resolveBlockBody(hCode, "typedef struct gdcc_CoA_vtable {"),
                "godot_Object* (*m_foo)(gdcc_CoA_fat_ptr $self);");
        assertContainsAll(cCode,
                "static const gdcc_CoA_vtable gdcc_CoA_vtable_inst = { .m_foo = CoA_foo__coro_start };",
                "static const gdcc_CoA_vtable gdcc_CoD_vtable_inst = { .m_foo = gdcc_CoD_vslot_foo };");
        assertContainsAll(resolveFunctionBodyByPrefix(cCode, "static godot_Object* gdcc_CoD_vslot_foo"),
                "return CoD_foo__coro_start(s);");
    }

    @Test
    @DisplayName("abstract-introduced slot fills a NULL entry in the abstract class's own instance")
    void abstractSlotHoleFillsNullEntry() throws Exception {
        // The abstract declaration carries a minimal body so the plain-function template loop
        // stays satisfied; the planner only reads the abstract marker when planning the hole.
        var abstractFoo = newVoidInstanceMethod("AbA", "foo");
        abstractFoo.setAbstract(true);
        var classA = newClass("AbA", "Node", abstractFoo);
        classA.setAbstract(true);
        var classC = newClass("AbC", "AbA", newVoidInstanceMethod("AbC", "foo"));

        var files = generate(classA, classC);
        var cCode = generatedFileText(files, "entry.c");

        assertContainsAll(cCode,
                "static const gdcc_AbA_vtable gdcc_AbA_vtable_inst = { .m_foo = NULL };",
                "static const gdcc_AbA_vtable gdcc_AbC_vtable_inst = { .m_foo = gdcc_AbC_vslot_foo };");
    }

    // ==== Fixture helpers ====

    @Test
    @DisplayName("three-level chain with a mid-layer call site: end-to-end vtable dispatch (Step 4, §2.6)")
    void threeLevelChainWithMidLayerCallSiteGeneratesVtableDispatchEndToEnd() throws Exception {
        var classA = newClass("GeA", "Node", newEchoIntMethod("GeA", "foo"));
        var classB = newClass("GeB", "GeA", newEchoIntMethod("GeB", "foo"));
        var classC = newClass("GeC", "GeB", newEchoIntMethod("GeC", "foo"));
        var hostClass = newClass("GeHost", "Node", newCallFooOnMidMethod());

        var files = generate(classA, classB, classC, hostClass);
        var cCode = generatedFileText(files, "entry.c");
        var fatPtrHeader = generatedFileText(files, "object_fat_ptr_types.h");

        // Call site (owner GeB != introducer GeA): the receiver is materialized once as the
        // introducer fat self, then callee + first arg both read the temp (§2.6).
        var callSiteBody = resolveFunctionBodyByPrefix(cCode, "GeHost_call_foo_on_mid(");
        assertOrdered(callSiteBody,
                "gdcc_GeA_fat_ptr __gdcc_tmp_vt_recv_0 = gdcc_GeB_fat_ptr_upcast_to_GeA($child);",
                "GeA_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_foo(__gdcc_tmp_vt_recv_0, $value)");
        assertFalse(callSiteBody.contains("GeB_foo("), callSiteBody);
        assertFalse(callSiteBody.contains("GeA_foo("), callSiteBody);

        // The slot member read at the call site is backed by real per-class tables (Step 2
        // integration): B and C override foo through trampolines in their own instances.
        assertContainsAll(cCode,
                "static const gdcc_GeA_vtable gdcc_GeA_vtable_inst = { .m_foo = GeA_foo };",
                "static const gdcc_GeA_vtable gdcc_GeB_vtable_inst = { .m_foo = gdcc_GeB_vslot_foo };",
                "static const gdcc_GeA_vtable gdcc_GeC_vtable_inst = { .m_foo = gdcc_GeC_vslot_foo };");

        // The receiver→introducer upcast helper flows through the regular collector channel
        // (§2.6 item 5): no call-site-specific collection mechanism is needed.
        assertTrue(fatPtrHeader.contains("gdcc_GeB_fat_ptr_upcast_to_GeA("), fatPtrHeader);
    }

    /// `call_foo_on_mid(child: GeB, value: int) -> int`: mid-typed receiver polymorphic call site —
    /// resolved owner is GeB while the slot introducer is GeA.
    private static @NotNull LirFunctionDef newCallFooOnMidMethod() {
        var function = newInstanceMethodSkeleton("GeHost", "call_foo_on_mid", GdIntType.INT);
        function.addParameter(new LirParameterDef("child", new GdObjectType("GeB"), null, function));
        function.addParameter(new LirParameterDef("value", GdIntType.INT, null, function));
        function.createAndAddVariable("result", GdIntType.INT);
        entryOf(function).appendInstruction(new CallMethodInsn(
                "result",
                "foo",
                "child",
                List.of(new LirInstruction.VariableOperand("value"))
        ));
        entryOf(function).setTerminator(new ReturnInsn("result"));
        return function;
    }

    private static @NotNull LirClassDef newClass(@NotNull String name, @NotNull String superName,
                                                 @NotNull LirFunctionDef... functions) {
        var classDef = new LirClassDef(name, superName);
        for (var function : functions) {
            classDef.addFunction(function);
        }
        return classDef;
    }

    /// Void instance method with only the synthetic leading `self` parameter and an empty body.
    private static @NotNull LirFunctionDef newVoidInstanceMethod(@NotNull String owner, @NotNull String name) {
        var function = newInstanceMethodSkeleton(owner, name, GdVoidType.VOID);
        entryOf(function).setTerminator(new ReturnInsn(null));
        return function;
    }

    /// `(self, value: int) -> int` returning the parameter, covering the value-returning
    /// trampoline path (overrides must share the exact signature, so both sides use this shape).
    private static @NotNull LirFunctionDef newEchoIntMethod(@NotNull String owner, @NotNull String name) {
        var function = newInstanceMethodSkeleton(owner, name, GdIntType.INT);
        function.addParameter(new LirParameterDef("value", GdIntType.INT, null, function));
        entryOf(function).setTerminator(new ReturnInsn("value"));
        return function;
    }

    private static @NotNull LirFunctionDef newCoroutineInstanceMethod(@NotNull String owner, @NotNull String name) {
        var function = newInstanceMethodSkeleton(owner, name, GdVoidType.VOID);
        function.setCoroutine(true);
        entryOf(function).setTerminator(new ReturnInsn(null));
        return function;
    }

    private static @NotNull LirFunctionDef newInstanceMethodSkeleton(@NotNull String owner, @NotNull String name,
                                                                     @NotNull GdType returnType) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        function.addParameter(new LirParameterDef("self", new GdObjectType(owner), null, function));
        function.addBasicBlock(new LirBasicBlock("entry"));
        function.setEntryBlockId("entry");
        return function;
    }

    private static @NotNull LirBasicBlock entryOf(@NotNull LirFunctionDef function) {
        return function.getBasicBlock("entry");
    }

    private static @NotNull CodegenContext newTestContext() throws Exception {
        // Names deliberately avoid the "vtable" substring so the slotless negative case can
        // assert its total absence from the generated sources.
        var projectInfo = new ProjectInfo("layout_codegen_test", GodotVersion.V451, Path.of(".")) {
        };
        return new CodegenContext(projectInfo, new ClassRegistry(ExtensionApiLoader.loadDefault()));
    }

    private static @NotNull List<GeneratedFile> generate(@NotNull LirClassDef... classDefs) throws Exception {
        var module = new LirModule("layout_codegen_module", List.of(classDefs));
        var codegen = new CCodegen();
        codegen.prepare(newTestContext(), module);
        return codegen.generate();
    }

    private static @NotNull String generatedFileText(@NotNull List<GeneratedFile> files, @NotNull String fileName) {
        for (var file : files) {
            if (file.filePath().endsWith(fileName)) {
                return new String(file.contentWriter());
            }
        }
        throw new AssertionError("Missing generated file: " + fileName);
    }

    /// Asserts the exact `_vtable` write inside one create_instance body and its placement
    /// before the POSTINITIALIZE notification (so `_init` dispatches through a valid table).
    private static void assertCreateInstanceWrites(@NotNull String cCode, @NotNull String className,
                                                   @NotNull String assignment) {
        var createBody = resolveCreateInstanceBody(cCode, className);
        assertOrdered(createBody, assignment, "godot_Object_notification");
    }

    private static @NotNull String resolveCreateInstanceBody(@NotNull String cCode, @NotNull String className) {
        return resolveFunctionBodyByPrefix(cCode, "GDExtensionObjectPtr " + className + "_class_create_instance");
    }

    /// Extracts the body between the braces following a prefix (function definitions, struct
    /// blocks, typedef blocks alike). Function prefixes rely on the entry.c emission contract
    /// that user method functions are DEFINED without preceding in-file prototypes (prototypes
    /// live in entry.h), so the first prefix match is always the definition.
    private static @NotNull String resolveFunctionBodyByPrefix(@NotNull String code, @NotNull String signaturePrefix) {
        return resolveBlockBody(code, signaturePrefix);
    }

    private static @NotNull String resolveBlockBody(@NotNull String code, @NotNull String prefix) {
        var signatureIndex = code.indexOf(prefix);
        assertTrue(signatureIndex >= 0, () -> "Missing prefix: " + prefix + "\n" + code);
        var openBraceIndex = code.indexOf('{', signatureIndex);
        assertTrue(openBraceIndex >= 0, () -> "Missing opening brace for " + prefix);
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
        throw new AssertionError("Missing closing brace for " + prefix);
    }

    private static void assertContainsAll(@NotNull String text, @NotNull String... needles) {
        for (var needle : needles) {
            assertTrue(text.contains(needle), () -> "Missing fragment `" + needle + "` in:\n" + text);
        }
    }

    private static void assertOrdered(@NotNull String text, @NotNull String... fragmentsInOrder) {
        var searchFromIndex = 0;
        for (var fragment : fragmentsInOrder) {
            var index = text.indexOf(fragment, searchFromIndex);
            assertTrue(index >= 0, () -> "Missing fragment: " + fragment + "\n" + text);
            searchFromIndex = index + fragment.length();
        }
    }
}
