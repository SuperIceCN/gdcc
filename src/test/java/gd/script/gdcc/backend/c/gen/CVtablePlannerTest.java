package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.CodegenException;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Behavior anchors for the module vtable planner: slot allocation, prefix compatibility,
/// introducer/final-overrider tracking, role predicates, the two full-role symbol queries, and
/// the fail-fast rules (incompatible overrides, coroutine-marker mismatches, unresolved
/// abstract slots). Fixtures register their classes into the registry unless they exercise the
/// incomplete-registry tolerance rule explicitly.
class CVtablePlannerTest {
    private ClassRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
    }

    private CVtablePlanner planRegistered(LirClassDef... classDefs) {
        for (var classDef : classDefs) {
            registry.addGdccClass(classDef);
        }
        return new CVtablePlanner(List.of(classDefs), registry);
    }

    private CVtablePlanner planUnregistered(LirClassDef... classDefs) {
        return new CVtablePlanner(List.of(classDefs), registry);
    }

    private static LirClassDef newClass(String name, String superName, LirFunctionDef... functions) {
        var classDef = new LirClassDef(name, superName);
        for (var function : functions) {
            classDef.addFunction(function);
        }
        return classDef;
    }

    /// Instance method fixture carrying the backend's synthetic leading `self` parameter, like
    /// frontend lowering produces; `ownerClass` must match the class the function is added to.
    private static LirFunctionDef newInstanceMethod(
            String ownerClass,
            String name,
            GdType returnType,
            List<GdType> paramTypes
    ) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        function.addParameter(new LirParameterDef("self", new GdObjectType(ownerClass), null, function));
        for (var index = 0; index < paramTypes.size(); index++) {
            function.addParameter(new LirParameterDef("p" + index, paramTypes.get(index), null, function));
        }
        return function;
    }

    private static LirFunctionDef newInstanceMethod(String ownerClass, String name) {
        return newInstanceMethod(ownerClass, name, GdVoidType.VOID, List.of());
    }

    private static LirFunctionDef newStaticMethod(String name, GdType returnType, List<GdType> paramTypes) {
        var function = new LirFunctionDef(name);
        function.setStatic(true);
        function.setReturnType(returnType);
        for (var index = 0; index < paramTypes.size(); index++) {
            function.addParameter(new LirParameterDef("p" + index, paramTypes.get(index), null, function));
        }
        return function;
    }

    private static CVtablePlanner.VtableSlotEntry requireSlot(
            CVtablePlanner planner,
            String className,
            String methodName
    ) {
        var entry = planner.findVtableSlot(className, methodName);
        assertTrue(entry.isPresent(), () -> "slot '" + methodName + "' should exist on class '" + className + "'");
        return entry.get();
    }

    @Test
    @DisplayName("hierarchy without any override is entirely slotless")
    void hierarchyWithoutOverridesIsSlotless() {
        var base = newClass("PlainBase", "Node", newInstanceMethod("PlainBase", "foo"));
        var middle = newClass("PlainMiddle", "PlainBase", newInstanceMethod("PlainMiddle", "bar"));
        var leaf = newClass("PlainLeaf", "PlainMiddle");

        var planner = planRegistered(base, middle, leaf);

        for (var className : List.of("PlainBase", "PlainMiddle", "PlainLeaf")) {
            assertFalse(planner.slotted(className), className + " should be slotless");
            assertFalse(planner.introducesSlot(className));
            assertFalse(planner.passThrough(className));
        }
    }

    @Test
    @DisplayName("query sentinels distinguish slotless hierarchy (empty) from side branch (NULL)")
    void querySentinelsDistinguishSlotlessHierarchyAndSideBranch() {
        // Root hierarchy carries one slot (A.foo, overridden by AChild); B is the side branch.
        var root = newClass("SentRoot", "Node");
        var introducer = newClass("SentA", "SentRoot", newInstanceMethod("SentA", "foo"));
        var overrider = newClass("SentAChild", "SentA", newInstanceMethod("SentAChild", "foo"));
        var sideBranch = newClass("SentB", "SentRoot");
        // Independent second hierarchy with no slots at all.
        var lonely = newClass("SentLonely", "Node", newInstanceMethod("SentLonely", "foo"));

        var planner = planRegistered(root, introducer, overrider, sideBranch, lonely);

        assertTrue(planner.vtableInstanceType("SentLonely").isEmpty());
        assertTrue(planner.resolvedVtableSymbol("SentLonely").isEmpty(),
                "slotless hierarchy must resolve to empty, not NULL");
        assertTrue(planner.vtableInstanceType("SentB").isEmpty());
        assertEquals(CVtablePlanner.VTABLE_NULL_SYMBOL, planner.resolvedVtableSymbol("SentB").orElseThrow());
        // The hierarchy root itself is slotless but lives in a slotted hierarchy, so it also
        // plans the NULL expression (its `_vtable` field exists but is never read for it).
        assertTrue(planner.vtableInstanceType("SentRoot").isEmpty());
        assertEquals(CVtablePlanner.VTABLE_NULL_SYMBOL, planner.resolvedVtableSymbol("SentRoot").orElseThrow());
        assertEquals("gdcc_SentA_vtable", planner.vtableInstanceType("SentA").orElseThrow());
        assertEquals("gdcc_SentA_vtable_inst", planner.resolvedVtableSymbol("SentA").orElseThrow());
    }

    @Test
    @DisplayName("two- and three-segment override chains reuse slots and track introducer/final overrider")
    void overrideChainsReuseSlotsAndTrackOverriders() {
        var base = newClass("ChainA", "Node", newInstanceMethod("ChainA", "foo", GdIntType.INT, List.of()));
        var middle = newClass("ChainB", "ChainA", newInstanceMethod("ChainB", "foo", GdIntType.INT, List.of()));
        // Leaf overrides foo again and additionally introduces bar (overridden by GrandLeaf).
        var leaf = newClass("ChainC", "ChainB",
                newInstanceMethod("ChainC", "foo", GdIntType.INT, List.of()),
                newInstanceMethod("ChainC", "bar", GdIntType.INT, List.of()));
        var grandLeaf = newClass("ChainD", "ChainC", newInstanceMethod("ChainD", "bar", GdIntType.INT, List.of()));

        var planner = planRegistered(base, middle, leaf, grandLeaf);

        assertEquals(1, planner.slots("ChainA").size());
        assertEquals(1, planner.slots("ChainB").size());
        assertEquals(2, planner.slots("ChainC").size());
        assertEquals(2, planner.slots("ChainD").size());

        // Slot identity and prefix order: foo always comes from ChainA, bar is appended by ChainC.
        var fooOnBase = requireSlot(planner, "ChainA", "foo");
        assertEquals("ChainA", fooOnBase.slot().introducerClassName());
        assertEquals("ChainA", fooOnBase.finalOverriderClassName());
        assertEquals(fooOnBase.slot(), requireSlot(planner, "ChainB", "foo").slot(), "override must reuse the inherited slot");
        assertEquals("ChainB", requireSlot(planner, "ChainB", "foo").finalOverriderClassName());
        var leafSlots = planner.slots("ChainC");
        assertEquals("foo", leafSlots.get(0).slot().methodName());
        assertEquals("bar", leafSlots.get(1).slot().methodName());
        assertEquals("ChainC", requireSlot(planner, "ChainC", "foo").finalOverriderClassName());
        assertEquals("ChainC", leafSlots.get(1).slot().introducerClassName());
        assertEquals("ChainD", requireSlot(planner, "ChainD", "bar").finalOverriderClassName());
        assertEquals("ChainC", requireSlot(planner, "ChainD", "foo").finalOverriderClassName(),
                "ChainD does not override foo, so ChainC remains its final overrider");

        assertTrue(planner.introducesSlot("ChainA"));
        assertFalse(planner.introducesSlot("ChainB"));
        assertTrue(planner.overridesInheritedSlot("ChainB"));
        assertTrue(planner.introducesSlot("ChainC"));
        assertTrue(planner.overridesInheritedSlot("ChainC"));
        assertFalse(planner.introducesSlot("ChainD"));
        assertFalse(planner.passThrough("ChainD"), "an overriding class is never pass-through");
    }

    @Test
    @DisplayName("pass-through middle class inherits slots and resolves the ancestor's table value")
    void passThroughMiddleClassSharesAncestorTable() {
        // A introduces foo (overridden by C); B between them declares nothing; C introduces bar.
        var base = newClass("PassA", "Node", newInstanceMethod("PassA", "foo"));
        var middle = newClass("PassB", "PassA");
        var leaf = newClass("PassC", "PassB",
                newInstanceMethod("PassC", "foo"),
                newInstanceMethod("PassC", "bar"));
        var grandLeaf = newClass("PassD", "PassC", newInstanceMethod("PassD", "bar"));

        var planner = planRegistered(base, middle, leaf, grandLeaf);

        var middleSlots = planner.slots("PassB");
        assertEquals(1, middleSlots.size());
        assertEquals("PassA", middleSlots.getFirst().slot().introducerClassName());
        assertTrue(planner.passThrough("PassB"));
        // C's slot prefix attaches to A directly, skipping the pass-through B.
        var leafSlots = planner.slots("PassC");
        assertEquals("PassA", leafSlots.get(0).slot().introducerClassName());
        assertEquals("PassC", leafSlots.get(1).slot().introducerClassName());
        assertTrue(planner.introducesSlot("PassC"));

        assertEquals("gdcc_PassA_vtable_inst", planner.resolvedVtableSymbol("PassB").orElseThrow(),
                "pass-through reuses the nearest non-pass-through ancestor's table value");
        assertEquals("gdcc_PassA_vtable", planner.vtableInstanceType("PassB").orElseThrow());
        assertEquals("gdcc_PassC_vtable_inst", planner.resolvedVtableSymbol("PassC").orElseThrow());
        assertEquals("gdcc_PassC_vtable", planner.vtableInstanceType("PassC").orElseThrow());

        // Dispatch through a pass-through receiver type still needs the indirect path when a
        // deeper class overrides the method; dispatch typed at the final overrider stays direct.
        assertTrue(planner.isPolymorphicCall("PassB", "foo"),
                "PassC overrides foo below the pass-through PassB");
        assertTrue(planner.isPolymorphicCall("PassA", "foo"));
        assertFalse(planner.isPolymorphicCall("PassC", "foo"),
                "no descendant of PassC re-overrides foo");
        assertTrue(planner.isPolymorphicCall("PassC", "bar"));
    }

    @Test
    @DisplayName("four-level mixed chain plans two introducers and a final override-only class")
    void fourLevelMixedChainPlansIntroducersAndOverridersIndependently() {
        // A(root, no methods) -> B(introduces m1) -> C(introduces m2) -> D(overrides m1 and m2).
        var root = newClass("MixA", "Node");
        var firstIntroducer = newClass("MixB", "MixA", newInstanceMethod("MixB", "m1"));
        var secondIntroducer = newClass("MixC", "MixB", newInstanceMethod("MixC", "m2"));
        var overrideOnlyLeaf = newClass("MixD", "MixC",
                newInstanceMethod("MixD", "m1"),
                newInstanceMethod("MixD", "m2"));

        var planner = planRegistered(root, firstIntroducer, secondIntroducer, overrideOnlyLeaf);

        assertEquals(1, planner.slots("MixB").size());
        assertEquals(2, planner.slots("MixC").size());
        var mixedLeafSlots = planner.slots("MixC");
        assertEquals("MixB", mixedLeafSlots.get(0).slot().introducerClassName());
        assertEquals("MixC", mixedLeafSlots.get(1).slot().introducerClassName());
        assertEquals("MixB", requireSlot(planner, "MixC", "m1").finalOverriderClassName(),
                "MixC does not override m1, so MixB remains its final overrider");

        assertTrue(planner.vtableInstanceType("MixA").isEmpty());
        assertEquals(CVtablePlanner.VTABLE_NULL_SYMBOL, planner.resolvedVtableSymbol("MixA").orElseThrow());
        assertEquals("gdcc_MixB_vtable", planner.vtableInstanceType("MixB").orElseThrow());
        assertEquals("gdcc_MixC_vtable", planner.vtableInstanceType("MixC").orElseThrow());
        assertFalse(planner.introducesSlot("MixD"));
        assertEquals("gdcc_MixC_vtable", planner.vtableInstanceType("MixD").orElseThrow());
        assertEquals("gdcc_MixD_vtable_inst", planner.resolvedVtableSymbol("MixD").orElseThrow());

        assertTrue(planner.isPolymorphicCall("MixB", "m1"));
        assertTrue(planner.isPolymorphicCall("MixC", "m1"),
                "MixD overrides m1 below MixC even though MixC never declares m1 itself");
        assertTrue(planner.isPolymorphicCall("MixC", "m2"));
        assertFalse(planner.isPolymorphicCall("MixD", "m1"));
        assertFalse(planner.isPolymorphicCall("MixD", "m2"));
    }

    @Test
    @DisplayName("override-only class keeps the ancestor typedef but resolves its own instance symbol")
    void overrideOnlyClassKeepsAncestorTypedefButOwnInstance() {
        var base = newClass("OnlyA", "Node", newInstanceMethod("OnlyA", "foo"));
        var leaf = newClass("OnlyB", "OnlyA", newInstanceMethod("OnlyB", "foo"));

        var planner = planRegistered(base, leaf);

        assertFalse(planner.introducesSlot("OnlyB"));
        assertFalse(planner.passThrough("OnlyB"));
        assertEquals("gdcc_OnlyA_vtable", planner.vtableInstanceType("OnlyB").orElseThrow());
        assertEquals("gdcc_OnlyB_vtable_inst", planner.resolvedVtableSymbol("OnlyB").orElseThrow(),
                "override-only classes still get their own vtable instance");
    }

    @Test
    @DisplayName("typedef chain (nearest introducer) and table-value chain (nearest non-pass-through) resolve independently")
    void typedefChainAndValueChainResolveIndependently() {
        // A introduces foo, B only overrides foo, C is pass-through below B.
        var base = newClass("DualA", "Node", newInstanceMethod("DualA", "foo"));
        var overrideOnly = newClass("DualB", "DualA", newInstanceMethod("DualB", "foo"));
        var passThrough = newClass("DualC", "DualB");

        var planner = planRegistered(base, overrideOnly, passThrough);

        assertTrue(planner.passThrough("DualC"));
        assertEquals("gdcc_DualB_vtable_inst", planner.resolvedVtableSymbol("DualC").orElseThrow(),
                "the table value follows the nearest non-pass-through ancestor (B), not the introducer (A)");
        assertEquals("gdcc_DualA_vtable", planner.vtableInstanceType("DualB").orElseThrow());
        assertEquals("gdcc_DualA_vtable", planner.vtableInstanceType("DualC").orElseThrow(),
                "the typedef follows the nearest introducer (A) for both B and C");
    }

    @Test
    @DisplayName("excluded method pairs (_init, static, hidden same-name) never raise conflicts")
    void excludedMethodPairsDoNotRaiseConflict() {
        var base = newClass("ExclBase", "Node",
                newInstanceMethod("ExclBase", "_init", GdVoidType.VOID, List.of(GdIntType.INT)),
                newInstanceMethod("ExclBase", "foo", GdIntType.INT, List.of(GdIntType.INT)));
        // Parent/child `_init` signatures routinely differ and must not conflict; the static
        // same-named declaration is excluded as well and breaks the override chain below it.
        var child = newClass("ExclChild", "ExclBase",
                newInstanceMethod("ExclChild", "_init"),
                newStaticMethod("foo", GdVoidType.VOID, List.of()));
        var grandChild = newClass("ExclGrand", "ExclChild",
                newInstanceMethod("ExclGrand", "foo", GdStringType.STRING, List.of(GdStringType.STRING)));

        // A hidden declaration breaks the chain the same way: the incompatible instance method
        // below it is fresh, not an override of the ancestor's signature.
        var hiddenBase = newClass("ExclHiddenBase", "Node",
                newInstanceMethod("ExclHiddenBase", "foo", GdIntType.INT, List.of(GdIntType.INT)));
        var hiddenFoo = newInstanceMethod("ExclHiddenChild", "foo");
        hiddenFoo.setHidden(true);
        var hiddenChild = newClass("ExclHiddenChild", "ExclHiddenBase", hiddenFoo);
        var hiddenGrandChild = newClass("ExclHiddenGrand", "ExclHiddenChild",
                newInstanceMethod("ExclHiddenGrand", "foo", GdStringType.STRING, List.of(GdStringType.STRING)));

        var planner = planRegistered(base, child, grandChild, hiddenBase, hiddenChild, hiddenGrandChild);

        for (var className : List.of(
                "ExclBase", "ExclChild", "ExclGrand", "ExclHiddenBase", "ExclHiddenChild", "ExclHiddenGrand")) {
            assertFalse(planner.slotted(className), className + " should be slotless");
        }
    }

    @Test
    @DisplayName("introducing a slot whose name survives behind an excluded declaration fails fast")
    void excludedChainBreakWithInheritedSameNameSlotFailsFast() {
        var base = newClass("ColA", "Node", newInstanceMethod("ColA", "foo"));
        // ColSide makes ColA.foo polymorphic, so the slot exists on the whole hierarchy.
        var branchOverrider = newClass("ColSide", "ColA", newInstanceMethod("ColSide", "foo"));
        // The static foo in ColMid breaks the override chain for deeper classes...
        var mid = newClass("ColMid", "ColA", newStaticMethod("foo", GdVoidType.VOID, List.of()));
        // ...but ColDeep.foo is polymorphic (ColDeepChild overrides it) and would introduce a
        // second same-named slot, colliding with the inherited one in the C struct.
        var deep = newClass("ColDeep", "ColMid", newInstanceMethod("ColDeep", "foo"));
        var deepChild = newClass("ColDeepChild", "ColDeep", newInstanceMethod("ColDeepChild", "foo"));

        var exception = assertThrows(CodegenException.class,
                () -> planRegistered(base, branchOverrider, mid, deep, deepChild));
        assertTrue(exception.getMessage().contains("foo"), exception.getMessage());
        assertTrue(exception.getMessage().contains("ColDeep"), exception.getMessage());
    }

    @Test
    @DisplayName("sibling branch inherits the slot but its call sites stay direct (slot presence is not the dispatch gate)")
    void siblingBranchInheritsSlotWithoutBecomingPolymorphicCall() {
        var base = newClass("SibB", "Node", newInstanceMethod("SibB", "foo"));
        var overridingChild = newClass("SibChild1", "SibB", newInstanceMethod("SibChild1", "foo"));
        var siblingBranch = newClass("SibChild2", "SibB");

        var planner = planRegistered(base, overridingChild, siblingBranch);

        var inheritedSlot = requireSlot(planner, "SibChild2", "foo");
        assertEquals("SibB", inheritedSlot.slot().introducerClassName());
        assertEquals("SibB", inheritedSlot.finalOverriderClassName());
        assertTrue(planner.passThrough("SibChild2"));
        assertFalse(planner.isPolymorphicCall("SibChild2", "foo"),
                "no proper descendant of SibChild2 overrides foo, so its calls stay direct "
                        + "even though findVtableSlot hits");
        assertTrue(planner.isPolymorphicCall("SibB", "foo"));
    }

    @Test
    @DisplayName("overriding an engine virtual without GDCC re-overriders creates no slot")
    void engineVirtualOverrideWithoutGdccOverriderCreatesNoSlot() {
        var base = newClass("EvBase", "Node", newInstanceMethod("EvBase", "_ready"));
        var leaf = newClass("EvLeaf", "EvBase");

        var planner = planRegistered(base, leaf);

        assertFalse(planner.slotted("EvBase"));
        assertFalse(planner.slotted("EvLeaf"));
        assertFalse(planner.isPolymorphicCall("EvBase", "_ready"));
    }

    @Test
    @DisplayName("final overrider keeps the inherited slot but calls on its static type stay direct")
    void finalOverriderKeepsInheritedSlotButIsNotPolymorphicCall() {
        var base = newClass("FinA", "Node", newInstanceMethod("FinA", "foo"));
        var finalOverrider = newClass("FinB", "FinA", newInstanceMethod("FinB", "foo"));

        var planner = planRegistered(base, finalOverrider);

        var inheritedSlot = requireSlot(planner, "FinB", "foo");
        assertEquals("FinB", inheritedSlot.finalOverriderClassName());
        assertFalse(planner.isPolymorphicCall("FinB", "foo"),
                "no proper descendant re-overrides foo, so calls typed FinB dispatch directly");
        assertTrue(planner.isPolymorphicCall("FinA", "foo"));
    }

    @Test
    @DisplayName("descendant method with incompatible signature fails fast naming both classes and the method")
    void incompatibleDescendantMethodFailsFast() {
        var base = newClass("BadBase", "Node",
                newInstanceMethod("BadBase", "foo", GdIntType.INT, List.of(GdIntType.INT)));
        var mismatchedParam = newClass("BadChild", "BadBase",
                newInstanceMethod("BadChild", "foo", GdIntType.INT, List.of(GdStringType.STRING)));

        var exception = assertThrows(CodegenException.class, () -> planRegistered(base, mismatchedParam));
        assertTrue(exception.getMessage().contains("foo"), exception.getMessage());
        assertTrue(exception.getMessage().contains("BadBase"), exception.getMessage());
        assertTrue(exception.getMessage().contains("BadChild"), exception.getMessage());

        var mismatchedReturn = newClass("BadReturnChild", "BadBase",
                newInstanceMethod("BadReturnChild", "foo", GdStringType.STRING, List.of(GdIntType.INT)));
        assertThrows(CodegenException.class, () -> planRegistered(base, mismatchedReturn));

        var mismatchedArity = newClass("BadArityChild", "BadBase",
                newInstanceMethod("BadArityChild", "foo", GdIntType.INT, List.of(GdIntType.INT, GdIntType.INT)));
        assertThrows(CodegenException.class, () -> planRegistered(base, mismatchedArity));
    }

    @Test
    @DisplayName("coroutine marker mismatch across an override fails fast in both directions")
    void coroutineFlagMismatchAcrossOverrideFailsFast() {
        var coroutineFoo = newInstanceMethod("CoroBase", "foo", GdIntType.INT, List.of());
        coroutineFoo.setCoroutine(true);
        var coroutineBase = newClass("CoroBase", "Node", coroutineFoo);
        var plainChildFoo = newInstanceMethod("CoroChild", "foo", GdIntType.INT, List.of());
        var plainChild = newClass("CoroChild", "CoroBase", plainChildFoo);
        assertThrows(CodegenException.class, () -> planRegistered(coroutineBase, plainChild),
                "coroutine overridden by a plain method must be rejected");

        var plainBase = newClass("PlainCoroBase", "Node",
                newInstanceMethod("PlainCoroBase", "foo", GdIntType.INT, List.of()));
        var coroutineChildFoo = newInstanceMethod("PlainCoroChild", "foo", GdIntType.INT, List.of());
        coroutineChildFoo.setCoroutine(true);
        var coroutineChild = newClass("PlainCoroChild", "PlainCoroBase", coroutineChildFoo);
        assertThrows(CodegenException.class, () -> planRegistered(plainBase, coroutineChild),
                "plain method overridden by a coroutine must be rejected");
    }

    @Test
    @DisplayName("abstract-introduced slot plans a NULL entry for the abstract class itself")
    void abstractIntroducedSlotPlansNullEntryForAbstractClass() {
        var abstractFoo = newInstanceMethod("AbsA", "foo");
        abstractFoo.setAbstract(true);
        var abstractBase = newClass("AbsA", "Node", abstractFoo);
        abstractBase.setAbstract(true);
        var concreteLeaf = newClass("AbsC", "AbsA", newInstanceMethod("AbsC", "foo"));

        var planner = planRegistered(abstractBase, concreteLeaf);

        var baseEntry = requireSlot(planner, "AbsA", "foo");
        assertTrue(baseEntry.abstractHole(), "abstract declaration resolves to a NULL entry");
        assertEquals("AbsA", baseEntry.finalOverriderClassName());
        var leafEntry = requireSlot(planner, "AbsC", "foo");
        assertFalse(leafEntry.abstractHole());
        assertEquals("AbsC", leafEntry.finalOverriderClassName());
        assertEquals("gdcc_AbsA_vtable_inst", planner.resolvedVtableSymbol("AbsA").orElseThrow());
    }

    @Test
    @DisplayName("concrete class inheriting an unresolved abstract slot fails fast")
    void concreteClassWithUnresolvedAbstractSlotFailsFast() {
        var abstractFoo = newInstanceMethod("UnresA", "foo");
        abstractFoo.setAbstract(true);
        var abstractBase = newClass("UnresA", "Node", abstractFoo);
        abstractBase.setAbstract(true);
        // Sibling C implements foo, which makes A.foo polymorphic and introduces the slot;
        // concrete B provides no implementation and must be rejected.
        var concreteHole = newClass("UnresB", "UnresA");
        var implementingSibling = newClass("UnresC", "UnresA", newInstanceMethod("UnresC", "foo"));

        var exception = assertThrows(CodegenException.class,
                () -> planRegistered(abstractBase, concreteHole, implementingSibling));
        assertTrue(exception.getMessage().contains("UnresB"), exception.getMessage());
        assertTrue(exception.getMessage().contains("foo"), exception.getMessage());
    }

    @Test
    @DisplayName("coroutine override chain plans a coroutine slot (start-thunk entries)")
    void coroutineOverrideChainPlansCoroutineSlot() {
        var baseFoo = newInstanceMethod("ThunkA", "foo", GdIntType.INT, List.of());
        baseFoo.setCoroutine(true);
        var base = newClass("ThunkA", "Node", baseFoo);
        var leafFoo = newInstanceMethod("ThunkB", "foo", GdIntType.INT, List.of());
        leafFoo.setCoroutine(true);
        var leaf = newClass("ThunkB", "ThunkA", leafFoo);

        var planner = planRegistered(base, leaf);

        var slot = requireSlot(planner, "ThunkA", "foo").slot();
        assertTrue(slot.coroutine(), "coroutine chains plan coroutine slots whose entries point at start thunks");
        assertTrue(requireSlot(planner, "ThunkB", "foo").slot().coroutine());
        assertEquals("ThunkB", requireSlot(planner, "ThunkB", "foo").finalOverriderClassName());
    }

    @Test
    @DisplayName("static, hidden, lambda and _init declarations never occupy slots even with same-named descendants")
    void excludedMethodKindsNeverOccupySlots() {
        var base = newClass("KindBase", "Node",
                newStaticMethod("make", GdIntType.INT, List.of()),
                newInstanceMethod("KindBase", "secret"),
                newInstanceMethod("KindBase", "lambda_1"),
                newInstanceMethod("KindBase", "_init"));
        base.getFunctions().get(1).setHidden(true);
        base.getFunctions().get(2).setLambda(true);
        var leaf = newClass("KindLeaf", "KindBase",
                newStaticMethod("make", GdStringType.STRING, List.of(GdIntType.INT)),
                newInstanceMethod("KindLeaf", "secret"),
                newInstanceMethod("KindLeaf", "lambda_1"),
                newInstanceMethod("KindLeaf", "_init", GdVoidType.VOID, List.of(GdIntType.INT)));
        leaf.getFunctions().get(1).setHidden(true);
        leaf.getFunctions().get(2).setLambda(true);

        var planner = planRegistered(base, leaf);

        assertFalse(planner.slotted("KindBase"));
        assertFalse(planner.slotted("KindLeaf"));
        assertFalse(planner.isPolymorphicCall("KindBase", "secret"));
        assertFalse(planner.isPolymorphicCall("KindBase", "lambda_1"));
    }

    @Test
    @DisplayName("unregistered GDCC parents are planned as hierarchy roots without failing")
    void unregisteredGdccParentPlansAsHierarchyRoot() {
        // Both classes are in the module list but neither is registered: the edge is invisible.
        var base = newClass("TolBase", "Node", newInstanceMethod("TolBase", "foo"));
        var leaf = newClass("TolLeaf", "TolBase", newInstanceMethod("TolLeaf", "foo"));

        var planner = planUnregistered(base, leaf);

        assertFalse(planner.slotted("TolBase"));
        assertFalse(planner.slotted("TolLeaf"));
        assertFalse(planner.isPolymorphicCall("TolBase", "foo"));
        assertTrue(planner.findVtableSlot("TolLeaf", "foo").isEmpty());

        // A dangling superclass name (absent from both module list and registry) is treated
        // like an engine/native parent: the class is a hierarchy root.
        var dangling = newClass("TolDangling", "MissingParent", newInstanceMethod("TolDangling", "foo"));
        var danglingPlanner = planUnregistered(dangling);
        assertFalse(danglingPlanner.slotted("TolDangling"));
        assertTrue(danglingPlanner.resolvedVtableSymbol("TolDangling").isEmpty());
    }

    @Test
    @DisplayName("GDCC inheritance cycles fail fast during planning")
    void gdccInheritanceCycleFailsFast() {
        var classA = newClass("CycA", "CycB");
        var classB = newClass("CycB", "CycA");
        registry.addGdccClass(classA);
        registry.addGdccClass(classB);

        var exception = assertThrows(IllegalStateException.class,
                () -> new CVtablePlanner(List.of(classA, classB), registry));
        assertTrue(exception.getMessage().contains("inheritance cycle"), exception.getMessage());
    }

    @Test
    @DisplayName("queries for unknown classes are safely empty or false")
    void queriesForUnknownClassesAreSafelyEmpty() {        var base = newClass("KnownBase", "Node", newInstanceMethod("KnownBase", "foo"));
        var leaf = newClass("KnownLeaf", "KnownBase", newInstanceMethod("KnownLeaf", "foo"));

        var planner = planRegistered(base, leaf);

        assertTrue(planner.slots("Ghost").isEmpty());
        assertFalse(planner.slotted("Ghost"));
        assertFalse(planner.introducesSlot("Ghost"));
        assertFalse(planner.overridesInheritedSlot("Ghost"));
        assertFalse(planner.passThrough("Ghost"));
        assertFalse(planner.isPolymorphicCall("Ghost", "foo"));
        assertTrue(planner.findVtableSlot("Ghost", "foo").isEmpty());
        assertTrue(planner.vtableInstanceType("Ghost").isEmpty());
        assertTrue(planner.resolvedVtableSymbol("Ghost").isEmpty());
    }

    @Test
    @DisplayName("inner-class canonical names spill raw (with __sub__) into vtable symbols")
    void innerClassCanonicalNamesSpillRawIntoVtableSymbols() {
        var base = newClass("Outer__sub__Base", "Node", newInstanceMethod("Outer__sub__Base", "foo"));
        var leaf = newClass("Outer__sub__Leaf", "Outer__sub__Base", newInstanceMethod("Outer__sub__Leaf", "foo"));

        var planner = planRegistered(base, leaf);

        assertEquals("gdcc_Outer__sub__Base_vtable", planner.vtableInstanceType("Outer__sub__Base").orElseThrow());
        assertEquals("gdcc_Outer__sub__Base_vtable_inst", planner.resolvedVtableSymbol("Outer__sub__Base").orElseThrow());
        assertEquals("gdcc_Outer__sub__Leaf_vtable_inst", planner.resolvedVtableSymbol("Outer__sub__Leaf").orElseThrow());
    }

    @Test
    @DisplayName("CGenHelper exposes the computed vtable plan")
    void helperExposesComputedVtablePlan() {
        var projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var base = newClass("MountBase", "Node", newInstanceMethod("MountBase", "foo"));
        var leaf = newClass("MountLeaf", "MountBase", newInstanceMethod("MountLeaf", "foo"));
        registry.addGdccClass(base);
        registry.addGdccClass(leaf);

        var helper = new CGenHelper(new CodegenContext(projectInfo, registry), List.of(base, leaf));

        assertNotNull(helper.vtablePlanner());
        assertTrue(helper.vtablePlanner().findVtableSlot("MountBase", "foo").isPresent());
        assertTrue(helper.vtablePlanner().isPolymorphicCall("MountBase", "foo"));
        assertFalse(helper.vtablePlanner().isPolymorphicCall("MountLeaf", "foo"));
    }

    @Test
    @DisplayName("GDCC override signature check skips the leading self parameter on both sides independently")
    void gdccOverrideSignatureSkipsLeadingSelfOnBothSides() {
        var baseWithSelf = newInstanceMethod("SigBase", "foo", GdIntType.INT, List.of(GdIntType.INT));
        var overrideWithSelf = newInstanceMethod("SigChild", "foo", GdIntType.INT, List.of(GdIntType.INT));
        assertTrue(CVtablePlanner.checkGdccOverrideSignature(baseWithSelf, overrideWithSelf));

        // A frontend-style declaration without self still matches a backend-style override and
        // vice versa: each side's leading self is skipped independently.
        var baseWithoutSelf = new LirFunctionDef("foo");
        baseWithoutSelf.setReturnType(GdIntType.INT);
        baseWithoutSelf.addParameter(new LirParameterDef("value", GdIntType.INT, null, baseWithoutSelf));
        assertTrue(CVtablePlanner.checkGdccOverrideSignature(baseWithoutSelf, overrideWithSelf));
        assertTrue(CVtablePlanner.checkGdccOverrideSignature(baseWithSelf, baseWithoutSelf));
    }

    @Test
    @DisplayName("GDCC override signature check rejects every incompatible shape")
    void gdccOverrideSignatureRejectsIncompatibleShapes() {
        var base = newInstanceMethod("ShapeBase", "foo", GdIntType.INT, List.of(GdIntType.INT));

        var varargMismatch = newInstanceMethod("ShapeChild", "foo", GdIntType.INT, List.of(GdIntType.INT));
        varargMismatch.setVararg(true);
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, varargMismatch));

        var coroutineMismatch = newInstanceMethod("ShapeChild", "foo", GdIntType.INT, List.of(GdIntType.INT));
        coroutineMismatch.setCoroutine(true);
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, coroutineMismatch));

        var staticMismatch = newStaticMethod("foo", GdIntType.INT, List.of(GdIntType.INT));
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, staticMismatch));

        var hiddenMismatch = newInstanceMethod("ShapeChild", "foo", GdIntType.INT, List.of(GdIntType.INT));
        hiddenMismatch.setHidden(true);
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, hiddenMismatch));

        var lambdaMismatch = newInstanceMethod("ShapeChild", "foo", GdIntType.INT, List.of(GdIntType.INT));
        lambdaMismatch.setLambda(true);
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, lambdaMismatch));

        var paramTypeMismatch = newInstanceMethod("ShapeChild", "foo", GdIntType.INT, List.of(GdStringType.STRING));
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, paramTypeMismatch));

        var returnTypeMismatch = newInstanceMethod("ShapeChild", "foo", GdStringType.STRING, List.of(GdIntType.INT));
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, returnTypeMismatch));

        var arityMismatch = newInstanceMethod("ShapeChild", "foo", GdIntType.INT, List.of());
        assertFalse(CVtablePlanner.checkGdccOverrideSignature(base, arityMismatch));
    }
}
