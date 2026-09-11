package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.resolver.ScopeMethodResolver;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit-level coverage for the fail-closed super-target rules that source fixtures cannot express
/// (GDCC abstract targets, unregistered superclass, missing superclass) plus message anchoring for
/// the common failure paths.
class FrontendSuperCallSupportTest {
    /// Godot super semantics (`gdscript_analyzer.cpp`): lookup walks from the direct superclass up
    /// and stops at the FIRST class declaring the method name; argument matching happens only within
    /// that owner. An argument-incompatible nearer declaration must fail instead of being skipped
    /// for an argument-compatible farther one.
    @Test
    void superCallStopsAtNearestDeclaringOwnerEvenWhenArgsMismatch() {
        var grandBase = newClass("GrandBase");
        grandBase.addFunction(newMethod("GrandBase", "m", GdIntType.INT, false, GdIntType.INT));
        var base = newClass("Base", "GrandBase");
        base.addFunction(newMethod("Base", "m", GdIntType.INT, false, GdStringType.STRING));
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(grandBase, base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "m",
                List.of(GdIntType.INT),
                assignableRank(registry)
        );

        assertFalse(
                resolution.isResolved(),
                "super.m(1) must not skip Base.m(String) to reach GrandBase.m(int)"
        );
        assertNotNull(resolution.detailReason());
        assertTrue(
                resolution.detailReason().contains("Base"),
                () -> "failure must name the nearest declaring owner Base: " + resolution.detailReason()
        );
    }

    /// Same stop-at-first-declarer rule for the instance-only guard: a static nearer declaration
    /// must surface the static rejection even when an ancestor has an applicable instance method.
    @Test
    void superCallStopsAtNearestDeclaringOwnerForStaticGuard() {
        var grandBase = newClass("GrandBase");
        grandBase.addFunction(newMethod("GrandBase", "make", GdIntType.INT, false, GdIntType.INT));
        var base = newClass("Base", "GrandBase");
        base.addFunction(newMethod("Base", "make", GdIntType.INT, true, GdStringType.STRING));
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(grandBase, base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "make",
                List.of(GdIntType.INT),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
    }

    @Test
    void resolvesNearestAncestorImplementation() {
        var grandBase = newClass("GrandBase");
        grandBase.addFunction(newMethod("GrandBase", "deep", GdIntType.INT, false));
        var base = newClass("Base", "GrandBase");
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(grandBase, base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "deep",
                List.of(),
                assignableRank(registry)
        );

        assertTrue(resolution.isResolved(), () -> String.valueOf(resolution.detailReason()));
        assertNotNull(resolution.method());
        assertEquals("GrandBase", resolution.method().ownerClass().getName());
    }

    @Test
    void missingSuperclassFailsClosed() {
        var orphan = newClass("Orphan");
        var registry = newRegistry(List.of(), List.of(orphan));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Orphan"),
                "anything",
                List.of(),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
        assertTrue(resolution.detailReason().contains("has no superclass"), resolution.detailReason());
    }

    @Test
    void unregisteredSuperclassFailsClosed() {
        var child = newClass("Child", "Ghost");
        var registry = newRegistry(List.of(), List.of(child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "foo",
                List.of(),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
        assertTrue(resolution.detailReason().contains("not registered"), resolution.detailReason());
    }

    @Test
    void initTargetIsRejected() {
        var base = newClass("Base");
        base.addFunction(newMethod("Base", "_init", GdIntType.INT, false));
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "_init",
                List.of(),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
        assertTrue(resolution.detailReason().contains("automatically"), resolution.detailReason());
    }

    @Test
    void staticTargetIsRejected() {
        var base = newClass("Base");
        base.addFunction(newMethod("Base", "make", GdIntType.INT, true));
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "make",
                List.of(),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
        assertTrue(resolution.detailReason().contains("instance-only"), resolution.detailReason());
    }

    @Test
    void gdccAbstractTargetIsRejected() {
        var base = newClass("Base");
        var abstractMethod = newMethod("Base", "hook", GdIntType.INT, false);
        abstractMethod.setAbstract(true);
        base.addFunction(abstractMethod);
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "hook",
                List.of(),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
        assertTrue(resolution.detailReason().contains("abstract"), resolution.detailReason());
    }

    /// Engine "abstract" entries are virtual hooks whose default no-op implementation stays
    /// callable through super (e.g. `super._ready()`), unlike GDCC abstract methods.
    @Test
    void engineVirtualHookTargetIsAllowed() {
        var engineBase = new ExtensionGdClass(
                "EngineBase",
                false,
                true,
                "",
                "core",
                List.of(),
                List.of(new ExtensionGdClass.ClassMethod(
                        "_ready",
                        false,
                        false,
                        false,
                        true,
                        0,
                        List.of(),
                        null,
                        List.of()
                )),
                List.of(),
                List.of(),
                List.of()
        );
        var child = newClass("Child", "EngineBase");
        var registry = newRegistry(List.of(engineBase), List.of(child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "_ready",
                List.of(),
                assignableRank(registry)
        );

        assertTrue(resolution.isResolved(), () -> String.valueOf(resolution.detailReason()));
    }

    @Test
    void missingMethodReportsReadableMessage() {
        var base = newClass("Base");
        base.addFunction(newMethod("Base", "known", GdIntType.INT, false));
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "missing",
                List.of(),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
        assertTrue(
                resolution.detailReason().contains("'missing' was not found on the superclass chain of 'Child'"),
                resolution.detailReason()
        );
    }

    @Test
    void argumentMismatchCarriesResolverDetailMessage() {
        var base = newClass("Base");
        base.addFunction(newMethod("Base", "greet", GdIntType.INT, false, GdStringType.STRING));
        var child = newClass("Child", "Base");
        var registry = newRegistry(List.of(), List.of(base, child));

        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                registry,
                new GdObjectType("Child"),
                "greet",
                List.of(),
                assignableRank(registry)
        );

        assertFalse(resolution.isResolved());
        assertNotNull(resolution.detailReason());
        // The resolver's Failed.message() carries the arity/type detail; regressions that collapse
        // it back to the raw FailureKind enum name fail this assertion.
        assertTrue(
                resolution.detailReason().contains("No applicable overload")
                        && !resolution.detailReason().endsWith("NO_APPLICABLE_OVERLOAD"),
                resolution.detailReason()
        );
    }

    private static @NotNull ScopeMethodResolver.ParameterCompatibilityRank assignableRank(@NotNull ClassRegistry registry) {
        return (_index, sourceType, targetType) -> registry.checkAssignable(sourceType, targetType) ? 1 : 0;
    }

    private static @NotNull LirClassDef newClass(@NotNull String name) {
        return new LirClassDef(name, "", false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private static @NotNull LirClassDef newClass(@NotNull String name, @NotNull String superName) {
        return new LirClassDef(name, superName, false, false, Map.of(), List.of(), List.of(), List.of());
    }

    /// Instance-method fixtures follow the shared resolver's `self`-first parameter contract.
    private static @NotNull LirFunctionDef newMethod(
            @NotNull String ownerName,
            @NotNull String name,
            @NotNull GdType returnType,
            boolean isStatic,
            @NotNull GdType... parameterTypes
    ) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        function.setStatic(isStatic);
        if (!isStatic) {
            function.addParameter(new LirParameterDef("self", new GdObjectType(ownerName), null, function));
        }
        for (var index = 0; index < parameterTypes.length; index++) {
            function.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, function));
        }
        return function;
    }

    private static @NotNull ClassRegistry newRegistry(
            @NotNull List<ExtensionGdClass> engineClasses,
            @NotNull List<LirClassDef> gdccClasses
    ) {
        var registry = new ClassRegistry(new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                engineClasses,
                List.of(),
                List.of()
        ));
        for (var gdccClass : gdccClasses) {
            registry.addGdccClass(gdccClass);
        }
        return registry;
    }
}
