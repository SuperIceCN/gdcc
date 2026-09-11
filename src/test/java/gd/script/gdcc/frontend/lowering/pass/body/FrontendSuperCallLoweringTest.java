package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.lowering.FrontendLoweringContext;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBodyInsnPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringBuildCfgPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringClassSkeletonPass;
import gd.script.gdcc.frontend.lowering.pass.FrontendLoweringFunctionPreparationPass;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallSuperMethodInsn;
import gd.script.gdcc.scope.ClassRegistry;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Lowering-level coverage for the `super` call route: published `SUPER_METHOD` facts must
/// materialize as `CALL_SUPER_METHOD` with the current-class `self` receiver, never as an ordinary
/// vtable-dispatched `call_method`.
class FrontendSuperCallLoweringTest {
    @Test
    void superChainCallLowersToCallSuperMethodInsn() throws Exception {
        var lowered = lowerFunction(
                """
                        class_name SuperLow
                        extends Node

                        class Base:
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            func greet() -> int:
                                return 2
                            func probe() -> int:
                                return super.greet()
                        """,
                "probe"
        );

        var superCalls = allInstructions(lowered, CallSuperMethodInsn.class);
        assertEquals(1, superCalls.size(), () -> "expected exactly one call_super_method in " + allInstructions(lowered, LirInstruction.class));
        var superCall = superCalls.getFirst();
        assertEquals("greet", superCall.methodName());
        // Chain form: receiver is the materialized `super` leaf, i.e. a temp aliasing `self`.
        var selfAlias = allInstructions(lowered, AssignInsn.class).stream()
                .filter(assign -> assign.sourceId().equals("self"))
                .filter(assign -> assign.resultId().equals(superCall.objectId()))
                .findFirst()
                .orElse(null);
        assertNotNull(selfAlias, () -> "super receiver must be an alias of self: " + allInstructions(lowered, LirInstruction.class));
        assertTrue(
                allInstructions(lowered, CallMethodInsn.class).stream()
                        .noneMatch(call -> call.methodName().equals("greet")),
                "super call must not lower to ordinary call_method"
        );
        // The receiver slot keeps the lexical current-class type so the backend lexical-self
        // invariant (`receiver type == bodyBuilder.clazz()`) holds.
        assertEquals(
                "SuperLow__sub__Child",
                lowered.getVariableById(superCall.objectId()).type().getTypeName()
        );
        assertNotNull(superCall.resultId(), "non-void super call must publish a result slot");
    }

    @Test
    void bareSuperCallLowersToCallSuperMethodInsn() throws Exception {
        var lowered = lowerFunction(
                """
                        class_name SuperLowBare
                        extends Node

                        class Base:
                            func greet(name: String) -> int:
                                return 1

                        class Child extends Base:
                            func greet(name: String) -> int:
                                return super(name)
                        """,
                "greet"
        );

        var superCalls = allInstructions(lowered, CallSuperMethodInsn.class);
        assertEquals(1, superCalls.size(), () -> "expected exactly one call_super_method in " + allInstructions(lowered, LirInstruction.class));
        var superCall = superCalls.getFirst();
        assertEquals("greet", superCall.methodName());
        // Bare form: the implicit receiver is the canonical self slot directly.
        assertEquals("self", superCall.objectId());
        assertEquals(1, superCall.args().size());
        assertTrue(
                allInstructions(lowered, CallMethodInsn.class).stream()
                        .noneMatch(call -> call.methodName().equals("greet")),
                "super call must not lower to ordinary call_method"
        );
    }

    @Test
    void superCallToEngineSuperclassMethodLowersToCallSuperMethodInsn() throws Exception {
        var lowered = lowerFunction(
                """
                        class_name SuperLowEngine
                        extends Node

                        func _ready() -> void:
                            super._ready()
                        """,
                "_ready"
        );

        var superCalls = allInstructions(lowered, CallSuperMethodInsn.class);
        assertEquals(1, superCalls.size(), () -> "expected exactly one call_super_method in " + allInstructions(lowered, LirInstruction.class));
        var superCall = superCalls.getFirst();
        assertEquals("_ready", superCall.methodName());
        // Chain form (`super._ready()`): receiver materializes as a self-alias temp.
        var selfAlias = allInstructions(lowered, AssignInsn.class).stream()
                .filter(assign -> assign.sourceId().equals("self"))
                .filter(assign -> assign.resultId().equals(superCall.objectId()))
                .findFirst()
                .orElse(null);
        assertNotNull(selfAlias, () -> "super receiver must be an alias of self: " + allInstructions(lowered, LirInstruction.class));
    }

    private static @NotNull LirFunctionDef lowerFunction(
            @NotNull String source,
            @NotNull String functionName
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var unit = new GdScriptParserService().parseUnit(
                Path.of("tmp", "super_call_lowering.gd"),
                source,
                diagnostics
        );
        assertTrue(diagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnostics.snapshot());
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var module = new FrontendModule("test_module", List.of(unit), Map.of());
        // The compile gate path mirrors the production lowering precondition: the super position
        // gate and compile blockers run before any CFG/body pass consumes the published facts.
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(module, classRegistry, diagnostics);
        assertFalse(
                diagnostics.hasErrors(),
                () -> "Unexpected semantic errors before body lowering: " + diagnostics.snapshot()
        );

        var context = new FrontendLoweringContext(module, classRegistry, diagnostics);
        context.publishAnalysisData(analysisData);
        new FrontendLoweringClassSkeletonPass().run(context);
        new FrontendLoweringFunctionPreparationPass().run(context);
        new FrontendLoweringBuildCfgPass().run(context);
        new FrontendLoweringBodyInsnPass().run(context);

        return context.requireFunctionLoweringContexts().stream()
                .filter(candidate -> candidate.kind() == FunctionLoweringContext.Kind.EXECUTABLE_BODY)
                .filter(candidate -> candidate.targetFunction().getName().equals(functionName))
                // Inner-class fixtures share method names across Base/Child; the super call probe
                // lives in the class that declares the override (or the only declaration).
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("Missing executable body context for " + functionName))
                .targetFunction();
    }

    private static <T extends LirInstruction> @NotNull List<T> allInstructions(
            @NotNull LirFunctionDef function,
            @NotNull Class<T> type
    ) {
        var matches = new ArrayList<T>();
        for (var block : function) {
            for (var instruction : block.getInstructions()) {
                if (type.isInstance(instruction)) {
                    matches.add(type.cast(instruction));
                }
            }
        }
        return List.copyOf(matches);
    }
}
