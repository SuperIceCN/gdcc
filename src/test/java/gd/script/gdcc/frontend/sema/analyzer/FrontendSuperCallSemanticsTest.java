package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Semantic-level coverage for the `super` call route
/// (`doc/module_impl/frontend/frontend_super_call_implementation.md`).
class FrontendSuperCallSemanticsTest {
    @Test
    void superChainCallResolvesToParentImplementation() throws Exception {
        var analyzed = analyze(
                "super_chain.gd",
                """
                        class_name SuperChain
                        extends Node

                        class Base:
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            func greet() -> int:
                                return 2
                            func probe() -> int:
                                return super.greet()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributeCallStep.class, step -> step.name().equals("greet"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        var receiverType = resolvedCall.receiverType();
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind()),
                () -> assertEquals(ScopeOwnerKind.GDCC, resolvedCall.ownerKind()),
                () -> assertEquals("greet", resolvedCall.callableName()),
                () -> assertEquals("int", resolvedCall.returnType() == null ? null : resolvedCall.returnType().getTypeName()),
                // The receiver stays the lexical current class so the backend `CALL_SUPER_METHOD`
                // self invariant (`receiver type == bodyBuilder.clazz()`) holds.
                () -> assertEquals("SuperChain__sub__Child", receiverType == null ? null : receiverType.getTypeName()),
                () -> assertNotNull(resolvedCall.exactCallableBoundary()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.binding").isEmpty())
        );
    }

    @Test
    void bareSuperCallUsesEnclosingFunctionName() throws Exception {
        var analyzed = analyze(
                "super_bare.gd",
                """
                        class_name SuperBare
                        extends Node

                        class Base:
                            func greet(name: String) -> int:
                                return 1

                        class Child extends Base:
                            func greet(name: String) -> int:
                                return super(name)
                        """
        );

        var superCall = findNode(analyzed.unit().ast(), CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression callee && callee.name().equals("super"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superCall);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals("greet", resolvedCall.callableName()),
                () -> assertEquals("int", resolvedCall.returnType() == null ? null : resolvedCall.returnType().getTypeName()),
                () -> assertNotNull(resolvedCall.exactCallableBoundary()),
                () -> assertEquals(1, resolvedCall.exactCallableBoundary().fixedParameterTypes().size()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superCallResolvesNearestAncestorBeyondDirectParent() throws Exception {
        var analyzed = analyze(
                "super_grandparent.gd",
                """
                        class_name SuperGrandparent
                        extends Node

                        class GrandBase:
                            func deep() -> int:
                                return 7

                        class Base extends GrandBase:
                            func other():
                                pass

                        class Child extends Base:
                            func probe() -> int:
                                return super.deep()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributeCallStep.class, step -> step.name().equals("deep"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals("int", resolvedCall.returnType() == null ? null : resolvedCall.returnType().getTypeName()),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superCallFailsWhenParentChainLacksMethod() throws Exception {
        var analyzed = analyze(
                "super_missing.gd",
                """
                        class_name SuperMissing
                        extends Node

                        class Base:
                            func known():
                                pass

                        class Child extends Base:
                            func probe():
                                super.missing()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributeCallStep.class, step -> step.name().equals("missing"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertFalse(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superPropertyAccessFails() throws Exception {
        var analyzed = analyze(
                "super_property.gd",
                """
                        class_name SuperProperty
                        extends Node

                        class Base:
                            var payload: int = 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.payload
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var superStep = findNode(probe, AttributePropertyStep.class, step -> step.name().equals("payload"));
        var resolvedMember = analyzed.analysisData().resolvedMembers().get(superStep);
        assertNotNull(resolvedMember);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendMemberResolutionStatus.FAILED, resolvedMember.status()),
                () -> assertFalse(diagnosticsByCategory(analyzed.analysisData(), "sema.member_resolution").isEmpty())
        );
    }

    @Test
    void bareSuperValueIsRejectedByCompileGate() throws Exception {
        var analyzed = analyze(
                "super_value.gd",
                """
                        class_name SuperValue
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            func probe():
                                var x = super
                                return x
                        """
        );

        var compileDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.compile_check");
        assertTrue(
                compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("'super' must be followed by a method call")),
                () -> "Expected a compile-gate diagnostic for value-position super, got: " + compileDiagnostics
        );
    }

    @Test
    void superCallInStaticFunctionFails() throws Exception {
        var analyzed = analyze(
                "super_static.gd",
                """
                        class_name SuperStatic
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            static func probe():
                                super.greet()
                        """
        );

        var bindingDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.binding");
        assertTrue(
                bindingDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("'super' is not available in static context")),
                () -> "Expected a static-context binding diagnostic, got: " + bindingDiagnostics
        );
    }

    @Test
    void superInitCallIsRejected() throws Exception {
        var analyzed = analyze(
                "super_init.gd",
                """
                        class_name SuperInit
                        extends Node

                        class Base:
                            func _init():
                                pass

                        class Child extends Base:
                            func _init():
                                super()
                        """
        );

        var superCall = findNode(analyzed.unit().ast(), CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression callee && callee.name().equals("super"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superCall);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("automatically"),
                        String.valueOf(resolvedCall.detailReason()))
        );
    }

    @Test
    void bareSuperCallInsideLambdaFailsClosed() throws Exception {
        var analyzed = analyze(
                "super_lambda_bare.gd",
                """
                        class_name SuperLambdaBare
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            func greet():
                                var callback = func():
                                    super()
                                return callback
                        """
        );

        var superCall = findNode(analyzed.unit().ast(), CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression callee && callee.name().equals("super"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superCall);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("enclosing named function"),
                        String.valueOf(resolvedCall.detailReason()))
        );
    }

    @Test
    void superCallInsideLambdaCapturesSelf() throws Exception {
        var analyzed = analyze(
                "super_lambda_capture.gd",
                """
                        class_name SuperLambdaCapture
                        extends Node

                        class Base:
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            func probe():
                                var callback = func():
                                    return super.greet()
                                return callback.call()
                        """
        );

        var probe = findFunction(analyzed.unit().ast(), "probe");
        var lambda = findNode(probe, LambdaExpression.class, _ -> true);
        var plan = analyzed.analysisData().lambdaPlans().get(lambda);
        assertNotNull(plan);
        assertTrue(plan.capturesSelf(), "super call inside a lambda must capture the enclosing self");
        var superStep = findNode(lambda, AttributeCallStep.class, step -> step.name().equals("greet"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                String.valueOf(resolvedCall.detailReason()));
    }

    @Test
    void superCallTargetsEngineSuperclassMethod() throws Exception {
        var analyzed = analyze(
                "super_engine.gd",
                """
                        class_name SuperEngine
                        extends Node

                        func _ready():
                            super._ready()
                        """
        );

        var ready = findFunction(analyzed.unit().ast(), "_ready");
        var superStep = findNode(ready, AttributeCallStep.class, step -> step.name().equals("_ready"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status(),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, resolvedCall.callKind()),
                () -> assertEquals(ScopeOwnerKind.ENGINE, resolvedCall.ownerKind())
        );
    }

    @Test
    void superCallResultContinuesAsOrdinaryChain() throws Exception {
        var analyzed = analyze(
                "super_chain_suffix.gd",
                """
                        class_name SuperChainSuffix
                        extends Node

                        class Base:
                            func make() -> Base:
                                return self
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.make().greet()
                        """
        );

        // Step 0 (`super.make()`) is the super route; step 1 (`greet`) reduces ordinarily against
        // the parent method's return type (`Base`), not the lexical current class.
        var superStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("make"));
        var suffixStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("greet"));
        var superCall = analyzed.analysisData().resolvedCalls().get(superStep);
        var suffixCall = analyzed.analysisData().resolvedCalls().get(suffixStep);
        assertNotNull(superCall);
        assertNotNull(suffixCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, superCall.status(),
                        String.valueOf(superCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.SUPER_METHOD, superCall.callKind()),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, suffixCall.status(),
                        String.valueOf(suffixCall.detailReason())),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, suffixCall.callKind()),
                () -> assertEquals(
                        "SuperChainSuffix__sub__Base",
                        suffixCall.receiverType() == null ? null : suffixCall.receiverType().getTypeName()
                ),
                () -> assertTrue(diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution").isEmpty())
        );
    }

    @Test
    void superAttributeSubscriptStepFails() throws Exception {
        // `super.payload[0]` chains a subscript step onto the super head: the step-0 interception
        // fail-closes with the subscript-specific message (not the generic compile position gate).
        var analyzed = analyze(
                "super_subscript_step.gd",
                """
                        class_name SuperSubscriptStep
                        extends Node

                        class Base:
                            var payload: Array[int] = [1]

                        class Child extends Base:
                            func probe() -> int:
                                return super.payload[0]
                        """
        );

        var callDiagnostics = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.severity().name().equals("ERROR"))
                .toList();
        assertFalse(callDiagnostics.isEmpty(), "super subscript step must produce an error diagnostic");
        assertTrue(
                callDiagnostics.stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("'super' only supports method calls; subscript access is not allowed")),
                () -> "expected the subscript-specific super message, got: " + callDiagnostics
        );
    }

    @Test
    void bareSuperSubscriptIsRejectedByCompileGate() throws Exception {
        // `super[0]` is a SubscriptExpression whose base sits in value position: no chain step is
        // involved, so the compile position gate owns the diagnostic.
        var analyzed = analyze(
                "super_bare_subscript.gd",
                """
                        class_name SuperBareSubscript
                        extends Node

                        class Base:
                            var payload: Array[int] = [1]

                        class Child extends Base:
                            func probe() -> int:
                                return super[0]
                        """
        );

        var compileDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.compile_check");
        assertTrue(
                compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("'super' must be followed by a method call")),
                () -> "Expected a compile-gate diagnostic for subscript-position super, got: " + compileDiagnostics
        );
    }

    @Test
    void superAsCallArgumentIsRejectedByCompileGate() throws Exception {
        var analyzed = analyze(
                "super_argument.gd",
                """
                        class_name SuperArgument
                        extends Node

                        class Base:
                            func greet():
                                pass

                        class Child extends Base:
                            func take(value):
                                pass
                            func probe():
                                take(super)
                        """
        );

        var compileDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.compile_check");
        assertTrue(
                compileDiagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("'super' must be followed by a method call")),
                () -> "Expected a compile-gate diagnostic for argument-position super, got: " + compileDiagnostics
        );
    }

    @Test
    void superCallToStaticTargetFails() throws Exception {
        var analyzed = analyze(
                "super_static_target.gd",
                """
                        class_name SuperStaticTarget
                        extends Node

                        class Base:
                            static func make() -> int:
                                return 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.make()
                        """
        );

        var superStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("make"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("instance-only"),
                        String.valueOf(resolvedCall.detailReason()))
        );
    }

    @Test
    void superCallWithArgumentMismatchCarriesReadableDetail() throws Exception {
        var analyzed = analyze(
                "super_arity.gd",
                """
                        class_name SuperArity
                        extends Node

                        class Base:
                            func greet(name: String) -> int:
                                return 1

                        class Child extends Base:
                            func probe() -> int:
                                return super.greet()
                        """
        );

        var superStep = findNode(analyzed.unit().ast(), AttributeCallStep.class, step -> step.name().equals("greet"));
        var resolvedCall = analyzed.analysisData().resolvedCalls().get(superStep);
        assertNotNull(resolvedCall);
        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(FrontendCallResolutionStatus.FAILED, resolvedCall.status()),
                () -> assertTrue(String.valueOf(resolvedCall.detailReason()).contains("No applicable overload"),
                        String.valueOf(resolvedCall.detailReason())),
                () -> assertFalse(String.valueOf(resolvedCall.detailReason()).endsWith("NO_APPLICABLE_OVERLOAD"),
                        "failure must carry the resolver detail message, not the raw enum name")
        );
    }

    @Test
    void superCallInPropertyInitializerIsRejected() throws Exception {
        var analyzed = analyze(
                "super_property_init.gd",
                """
                        class_name SuperPropertyInit
                        extends Node

                        class Base:
                            func greet() -> int:
                                return 1

                        class Child extends Base:
                            var payload: int = super.greet()
                        """
        );

        var unsupported = diagnosticsByCategory(analyzed.analysisData(), "sema.unsupported_binding_subtree");
        assertTrue(
                unsupported.stream().anyMatch(diagnostic -> diagnostic.message().contains("'super'")),
                () -> "Expected a property-initializer boundary diagnostic, got: "
                        + analyzed.analysisData().diagnostics().asList()
        );
    }

    @Test
    void superCallReportsReadableMessageWhenMethodMissing() throws Exception {
        var analyzed = analyze(
                "super_missing_message.gd",
                """
                        class_name SuperMissingMessage
                        extends Node

                        class Base:
                            func known():
                                pass

                        class Child extends Base:
                            func probe():
                                super.missing()
                        """
        );

        var callDiagnostics = diagnosticsByCategory(analyzed.analysisData(), "sema.call_resolution");
        assertTrue(
                callDiagnostics.stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("'missing' was not found on the superclass chain of")),
                () -> "Expected a readable missing-method message, got: " + callDiagnostics
        );
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        // The compile-gate path is required: the super position gate lives in the compile-check
        // analyzer, which the plain inspection-oriented analyze() entrypoint never runs.
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit), Map.of()),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnostics
        );
        return new AnalyzedScript(unit, analysisData);
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNodes(root, FunctionDeclaration.class, function -> function.name().equals(name))
                .stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Function not found: " + name));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError("Node not found: " + nodeType.getSimpleName()));
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectMatchingNodes(root, nodeType, predicate, matches);
        return List.copyOf(matches);
    }

    private static <T extends Node> void collectMatchingNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(node)) {
            var candidate = nodeType.cast(node);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : node.getChildren()) {
            collectMatchingNodes(child, nodeType, predicate, matches);
        }
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull String category
    ) {
        return analysisData.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private record AnalyzedScript(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData
    ) {
    }
}
