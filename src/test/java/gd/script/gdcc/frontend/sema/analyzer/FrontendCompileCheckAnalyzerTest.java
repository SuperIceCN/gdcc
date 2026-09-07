package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendCompileCheckAnalyzerTest {
    @Test
    void analyzeRejectsMissingModuleSkeletonBoundary() {
        var analyzer = new FrontendCompileCheckAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(analysisData, new DiagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("moduleSkeleton"));
    }

    @Test
    void analyzeRejectsMissingDiagnosticsBoundary() throws Exception {
        var preparedInput = prepareCompileCheckInput("missing_compile_check_diagnostics.gd", """
                class_name MissingCompileCheckDiagnostics
                extends Node
                
                func ping():
                    pass
                """);
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(preparedInput.analysisData().moduleSkeleton());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCompileCheckAnalyzer().analyze(analysisData, preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("diagnostics"));
    }

    @Test
    void analyzeForCompileReleasesCompileReadyExpressionMixWhileAnalyzeLeavesSharedDiagnosticsUntouched() throws Exception {
        var source = """
                class_name CompileCheckReadyExpressionMix
                extends Node
                
                var property_array = [1]
                var property_preload = preload("res://icon.svg")
                
                func ping(value):
                    assert(value, "compile-only gate")
                    1 if value else 0
                    {"hp": 1}
                    $Camera3D
                    value as String
                    value is String
                """;

        var sharedAnalyzed = analyzeShared("compile_check_ready_expression_mix.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.type_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.unsupported_expression_route").isEmpty());

        // No explicit expression intercepts remain: assert, ternary, array/dictionary literals,
        // preload, get-node, cast, and type-test are all compile-ready, and the supported property
        // initializer island (property_preload) stays clean as well.
        var compiled = analyzeForCompile("compile_check_ready_expression_mix.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileAllowsArrayAndDictionaryLiterals() throws Exception {
        var source = """
                class_name CompileCheckContainerLiterals
                extends RefCounted
                
                var scores: Array[int] = [1, 2]
                var labels: Dictionary[String, int] = {"a": 1}
                
                func probe(value: int) -> Array:
                    var mixed = [value, "x", true]
                    var nested = [[1], {"k": 2}]
                    var packed: Variant = [value]
                    return [mixed.size(), nested.size(), packed]
                """;

        var sharedAnalyzed = analyzeShared("compile_check_container_literals.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_container_literals.gd", source);
        assertFalse(
                compiled.diagnostics().hasErrors(),
                () -> "Unexpected compile diagnostics: " + compiled.diagnostics().asList()
        );
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileAllowsCastExpression() throws Exception {
        var source = """
                class_name CompileCheckCastExpression
                extends RefCounted
                
                func probe(value: int) -> float:
                    return value as float
                """;

        var sharedAnalyzed = analyzeShared("compile_check_cast_expression.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_cast_expression.gd", source);
        assertFalse(
                compiled.diagnostics().hasErrors(),
                () -> "Unexpected compile diagnostics: " + compiled.diagnostics().asList()
        );
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileDoesNotDuplicatePropagatedCastOperandFailure() throws Exception {
        var compiled = analyzeForCompile("compile_check_cast_propagated_operand.gd", """
                class_name CompileCheckCastPropagatedOperand
                extends RefCounted
                
                func probe() -> int:
                    var y = missing as int
                    return y
                """);
        var probeFunction = findFunction(compiled.unit().ast().statements(), "probe");
        var missingIdentifier = findNode(
                probeFunction,
                IdentifierExpression.class,
                identifier -> "missing".equals(identifier.name())
        );

        var expressionDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, expressionDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(missingIdentifier.range()), expressionDiagnostics.getFirst().range());
        assertTrue(
                compileDiagnostics.isEmpty(),
                () -> "propagated cast operand failure must not add cast-root compile_check: " + compileDiagnostics
        );
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileDoesNotDuplicatePropagatedTypeTestOperandFailure() throws Exception {
        var compiled = analyzeForCompile("compile_check_type_test_propagated_operand.gd", """
                class_name CompileCheckTypeTestPropagatedOperand
                extends RefCounted
                
                func probe() -> bool:
                    var y = missing is int
                    return y
                """);
        var probeFunction = findFunction(compiled.unit().ast().statements(), "probe");
        var missingIdentifier = findNode(
                probeFunction,
                IdentifierExpression.class,
                identifier -> "missing".equals(identifier.name())
        );

        var expressionDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, expressionDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(missingIdentifier.range()), expressionDiagnostics.getFirst().range());
        assertTrue(
                compileDiagnostics.isEmpty(),
                () -> "propagated type-test operand failure must not add type-test-root compile_check: "
                        + compileDiagnostics
        );
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileDoesNotDuplicateChainedPropagatedCastFailure() throws Exception {
        var compiled = analyzeForCompile("compile_check_chained_cast_propagated_operand.gd", """
                class_name CompileCheckChainedCastPropagatedOperand
                extends RefCounted
                
                func probe() -> float:
                    var y = (missing as int) as float
                    return y
                """);
        var probeFunction = findFunction(compiled.unit().ast().statements(), "probe");
        var missingIdentifier = findNode(
                probeFunction,
                IdentifierExpression.class,
                identifier -> "missing".equals(identifier.name())
        );

        var expressionDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, expressionDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(missingIdentifier.range()), expressionDiagnostics.getFirst().range());
        assertTrue(
                compileDiagnostics.isEmpty(),
                () -> "chained propagated cast failures must not add cast-root compile_check: " + compileDiagnostics
        );
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileKeepsRootOwnedCastTargetFailureOwnedByExpressionResolution() throws Exception {
        var compiled = analyzeForCompile("compile_check_cast_unknown_target.gd", """
                class_name CompileCheckCastUnknownTarget
                extends RefCounted
                
                func probe(value: int):
                    var y = value as MissingType
                """);
        var probeFunction = findFunction(compiled.unit().ast().statements(), "probe");
        var castExpression = findNode(probeFunction, CastExpression.class, _ -> true);

        var expressionDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertFalse(expressionDiagnostics.isEmpty());
        assertEquals(FrontendRange.fromAstRange(castExpression.range()), expressionDiagnostics.getFirst().range());
        assertTrue(
                compileDiagnostics.isEmpty(),
                () -> "root-owned cast target failure should stay owned by expression_resolution, not compile_check: "
                        + compileDiagnostics
        );
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileKeepsRootOwnedTypeTestTargetFailureOwnedByExpressionResolution() throws Exception {
        var compiled = analyzeForCompile("compile_check_type_test_invalid_target.gd", """
                class_name CompileCheckTypeTestInvalidTarget
                extends RefCounted
                
                func probe(value: int) -> bool:
                    return value is null
                """);
        var probeFunction = findFunction(compiled.unit().ast().statements(), "probe");
        var typeTestExpression = findNode(probeFunction, TypeTestExpression.class, _ -> true);

        var expressionDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertFalse(expressionDiagnostics.isEmpty());
        assertEquals(FrontendRange.fromAstRange(typeTestExpression.range()), expressionDiagnostics.getFirst().range());
        assertTrue(
                expressionDiagnostics.getFirst().message().contains("null"),
                () -> "expected invalid type-test target diagnostic: " + expressionDiagnostics
        );
        assertTrue(
                compileDiagnostics.isEmpty(),
                () -> "root-owned type-test target failure should stay owned by expression_resolution, not compile_check: "
                        + compileDiagnostics
        );
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileAcceptsAssertWithoutReclassifyingSharedConditionContract() throws Exception {
        var source = """
                class_name CompileCheckAssertContract
                extends RefCounted
                
                func ping():
                    assert(1, "frontend still accepts truthy source conditions")
                """;

        var sharedAnalyzed = analyzeShared("compile_check_assert_contract.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.type_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_assert_contract.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        // Assert is compile-ready: the gate no longer blocks it, and the Godot-compatible
        // truthiness condition contract stays unreinterpreted at source level.
        assertTrue(compileDiagnostics.isEmpty(), () -> compiled.diagnostics().asList().toString());
        assertFalse(compiled.diagnostics().hasErrors(), () -> compiled.diagnostics().asList().toString());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.type_check").isEmpty());
    }

    @Test
    void analyzeForCompileKeepsEngineVirtualOverrideDiagnosticsAsFrontendErrorsWithoutSynthesizingCompileCheckDuplicates()
            throws Exception {
        var source = """
                class_name CompileCheckVirtualOverride
                extends Node
                
                func _process(delta) -> void:
                    pass
                """;

        var sharedAnalyzed = analyzeShared("compile_check_virtual_override.gd", source);
        var sharedOverrideDiagnostics = diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.virtual_override");

        assertTrue(sharedAnalyzed.diagnostics().hasErrors());
        assertEquals(1, sharedOverrideDiagnostics.size());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(sharedOverrideDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.range() != null
        ));
        assertTrue(sharedOverrideDiagnostics.getFirst().message().contains("parameter #1 'delta'"));

        var compiled = analyzeForCompile("compile_check_virtual_override.gd", source);
        var compiledOverrideDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.virtual_override");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, compiledOverrideDiagnostics.size());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(compiledOverrideDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && diagnostic.range() != null
        ));
        assertEquals(sharedOverrideDiagnostics.getFirst().message(), compiledOverrideDiagnostics.getFirst().message());
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileKeepsStaticSelfAssignmentTargetDiagnosticAtSelfAnchor() throws Exception {
        var compiled = analyzeForCompile("compile_check_static_self_assignment_target.gd", """
                class_name CompileCheckStaticSelfAssignmentTarget
                extends RefCounted
                
                var hp: int = 0
                
                static func ping_static() -> void:
                    self.hp = 1
                """);
        var pingFunction = findFunction(compiled.unit().ast().statements(), "ping_static");
        var explicitSelf = findNode(pingFunction, SelfExpression.class, _ -> true);

        var bindingDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.binding");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, bindingDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(explicitSelf.range()), bindingDiagnostics.getFirst().range());
        assertTrue(bindingDiagnostics.getFirst().message().contains("static context"));
        assertTrue(
                compileDiagnostics.isEmpty(),
                () -> "static self assignment target must not add assignment-root compile_check diagnostics: "
                        + compileDiagnostics
        );
        assertEquals(compiled.diagnostics(), compiled.diagnosticManager().snapshot());
    }

    @Test
    void analyzeForCompileTreatsVariableInventoryErrorsAsCompileBlockingWithoutSynthesizingCompileCheckDuplicates()
            throws Exception {
        var compiled = analyzeForCompile("compile_check_duplicate_local.gd", """
                class_name CompileCheckDuplicateLocal
                extends RefCounted
                
                func ping():
                    var value := 1
                    var value := 2
                    return value
                """);

        var variableDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.variable_binding");
        var slotPublicationWarnings = diagnosticsByCategory(
                compiled.diagnostics(),
                FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY
        );
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, variableDiagnostics.size());
        assertTrue(variableDiagnostics.getFirst().message().contains("Duplicate local variable 'value'"));
        assertTrue(variableDiagnostics.getFirst().message().contains(Path.of("tmp", "compile_check_duplicate_local.gd").toString()));
        assertEquals(1, slotPublicationWarnings.size());
        assertTrue(slotPublicationWarnings.getFirst().message().contains("has no lowering-ready published slot type"));
        assertEquals(1, compileDiagnostics.size());
        assertTrue(compileDiagnostics.getFirst().message().contains("missing a lowering-ready published slot type"));
    }

    @Test
    void analyzeForCompileEscalatesShadowingLocalSlotTypeWarningIntoCompileBlock() throws Exception {
        var compiled = analyzeForCompile("compile_check_shadowing_local.gd", """
                class_name CompileCheckShadowingLocal
                extends RefCounted
                
                func ping(seed: int):
                    var value := seed
                    if seed > 0:
                        var value := 1
                    return value
                """);

        var slotPublicationWarnings = diagnosticsByCategory(
                compiled.diagnostics(),
                FrontendBodyOwnerProcedures.VARIABLE_SLOT_PUBLICATION_CATEGORY
        );
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, slotPublicationWarnings.size());
        assertTrue(slotPublicationWarnings.getFirst().message().contains("if-body of function 'ping'"));
        assertEquals(1, compileDiagnostics.size());
        assertTrue(compileDiagnostics.getFirst().message().contains("Local variable 'value'"));
        assertTrue(compileDiagnostics.getFirst().message().contains("missing a lowering-ready published slot type"));
    }

    @Test
    void analyzeForCompileAcceptsStaticPropertyDeclarationsWithInitializer() throws Exception {
        var source = """
                class_name CompileCheckStaticPropertyDeclaration
                extends RefCounted
                
                static var shared: int = 1
                
                static func build() -> int:
                    return shared
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_property_declaration.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        // Static var declarations are ordinary compile-surface members: the compile pass must not
        // emit any diagnostic for the declaration or the static function reading it.
        var compiled = analyzeForCompile("compile_check_static_property_declaration.gd", source);
        assertFalse(compiled.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileAcceptsStaticPropertyDeclarationsWithoutInitializer() throws Exception {
        var source = """
                class_name CompileCheckStaticPropertyWithoutInitializer
                extends RefCounted
                
                static var shared: int
                
                static func set_shared(value: int):
                    shared = value
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_property_without_initializer.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        // A static var without a source initializer compiles cleanly; its type default is
        // materialized by the backend static lifecycle instead of a property-init helper.
        var compiled = analyzeForCompile("compile_check_static_property_without_initializer.gd", source);
        assertFalse(compiled.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileRecursesIntoStaticPropertyInitializerSubtrees() throws Exception {
        var source = """
                class_name CompileCheckStaticPropertyInitializerSubtree
                extends Node
                
                static var shared = [1]
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_property_initializer_subtree.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        // The initializer subtree now participates in the compile walk; supported constructs
        // (container literals) stay diagnostic-free instead of being shielded by a
        // declaration-level short-circuit.
        var compiled = analyzeForCompile("compile_check_static_property_initializer_subtree.gd", source);
        assertFalse(compiled.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileStillReportsNestedBlockersInsideStaticPropertyInitializers() throws Exception {
        var source = """
                class_name CompileCheckStaticPropertyNestedBlocker
                extends Node
                
                static var shared = $Camera3D
                """;

        // Negative path: removing the static-property declaration gate must not shield
        // still-unsupported constructs nested inside the initializer. The compile walk reaches
        // the get-node expression, whose property-initializer DEFERRED fact is escalated by the
        // generic published-fact scan, proving the subtree is really traversed.
        var compiled = analyzeForCompile("compile_check_static_property_nested_blocker.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, compileDiagnostics.size());
        assertTrue(compileDiagnostics.getFirst().message().contains("remains deferred"));
        assertTrue(compileDiagnostics.getFirst().message().contains(
                "Get-node expression is not supported inside property initializers"
        ));
        assertFalse(compileDiagnostics.getFirst().message().contains("Static property"));
        // The blocker must anchor inside the initializer subtree (the get-node expression),
        // never on the declaration itself.
        assertNotEquals(
                FrontendRange.fromAstRange(findVariable(compiled.unit().ast().statements(), "shared").range()),
                compileDiagnostics.getFirst().range()
        );
    }

    @Test
    void analyzeForCompileLeavesResolvedUnaryAndEagerBinaryExpressionsOutOfCompileBlocks() throws Exception {
        var source = """
                class_name CompileCheckUnaryBinaryResolved
                extends RefCounted
                
                func ping(
                    items_a: Array[int],
                    items_b: Array[int],
                    typed_variant: Variant
                ):
                    var negated: int = -1
                    var logical_not: bool = !true
                    var dynamic_not := not typed_variant
                    var sum: int = 1 + 2
                    var typed_merge := items_a + items_b
                    var dynamic_sum := typed_variant + 1
                """;

        var sharedAnalyzed = analyzeShared("compile_check_unary_binary_resolved.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.deferred_chain_resolution").isEmpty());

        var compiled = analyzeForCompile("compile_check_unary_binary_resolved.gd", source);
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_chain_resolution").isEmpty());
    }

    @Test
    void analyzeForCompileLeavesTypedObjectNilEqualityOutOfCompileBlocks() throws Exception {
        var source = """
                class_name CompileCheckTypedObjectNilEquality
                extends RefCounted
                
                class Point extends RefCounted:
                    var next: Point = null
                
                func has_next(point: Point) -> bool:
                    return point.next != null
                
                func count(point: Point) -> int:
                    var total := 0
                    var current: Point = point
                    while current != null:
                        total += 1
                        current = current.next
                    return total
                """;

        var sharedAnalyzed = analyzeShared("compile_check_typed_object_nil_equality.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors(), () -> "Unexpected shared diagnostics: "
                + sharedAnalyzed.diagnostics().asList());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_typed_object_nil_equality.gd", source);
        assertFalse(compiled.diagnostics().hasErrors(), () -> "Unexpected compile diagnostics: "
                + compiled.diagnostics().asList());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileKeepsNonEqualityObjectNilComparisonBlocked() throws Exception {
        var source = """
                class_name CompileCheckObjectNilOrdering
                extends RefCounted
                
                class Point extends RefCounted:
                    pass
                
                func ping(point: Point):
                    return point < null
                """;

        var sharedAnalyzed = analyzeShared("compile_check_object_nil_ordering.gd", source);
        var sharedExpressionDiagnostics = diagnosticsByCategory(
                sharedAnalyzed.diagnostics(),
                "sema.expression_resolution"
        );

        assertTrue(sharedAnalyzed.diagnostics().hasErrors());
        assertEquals(1, sharedExpressionDiagnostics.size());
        assertTrue(sharedExpressionDiagnostics.getFirst().message().contains("not defined for operand types"));
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_object_nil_ordering.gd", source);
        var compiledExpressionDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.expression_resolution"
        );
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertEquals(1, compiledExpressionDiagnostics.size());
        assertTrue(compileDiagnostics.isEmpty());
    }

    @Test
    void analyzeForCompileLeavesTypedObjectIdentityEqualityOutOfCompileBlocks() throws Exception {
        var source = """
                class_name CompileCheckTypedObjectIdentityEquality
                extends Node
                
                func same_node(left: Node, right: Node) -> bool:
                    return left == right
                
                func object_vs_node(obj: Object, node: Node) -> bool:
                    return obj != node
                """;

        var sharedAnalyzed = analyzeShared("compile_check_typed_object_identity_equality.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors(), () -> "Unexpected shared diagnostics: "
                + sharedAnalyzed.diagnostics().asList());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_typed_object_identity_equality.gd", source);
        assertFalse(compiled.diagnostics().hasErrors(), () -> "Unexpected compile diagnostics: "
                + compiled.diagnostics().asList());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileKeepsTypedObjectIdentityOrderingBlocked() throws Exception {
        var source = """
                class_name CompileCheckObjectIdentityOrdering
                extends Node
                
                func ping(left: Node, right: Node):
                    return left < right
                """;

        var sharedAnalyzed = analyzeShared("compile_check_object_identity_ordering.gd", source);
        var sharedExpressionDiagnostics = diagnosticsByCategory(
                sharedAnalyzed.diagnostics(),
                "sema.expression_resolution"
        );

        assertTrue(sharedAnalyzed.diagnostics().hasErrors());
        assertEquals(1, sharedExpressionDiagnostics.size());
        assertTrue(sharedExpressionDiagnostics.getFirst().message().contains("not defined for operand types"));
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_object_identity_ordering.gd", source);
        var compiledExpressionDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.expression_resolution"
        );
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertEquals(1, compiledExpressionDiagnostics.size());
        assertTrue(compileDiagnostics.isEmpty());
    }

    @Test
    void analyzeForCompileLeavesShortCircuitBinaryExpressionsOnCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckShortCircuitBinary
                extends RefCounted
                
                func helper(value):
                    return value
                
                func ping(left, right):
                    var both := left and helper(right)
                    return left or right
                """;

        var sharedAnalyzed = analyzeShared("compile_check_short_circuit_binary.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_short_circuit_binary.gd", source);
        assertFalse(compiled.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_expression_resolution").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_chain_resolution").isEmpty());
    }

    @Test
    void analyzeForCompileAllowsCompoundAssignmentOnceBodyLoweringContractLands() throws Exception {
        var source = """
                class_name CompileCheckCompoundAssignment
                extends RefCounted
                
                var hp: int = 0
                
                func ping():
                    hp += 1
                """;

        var sharedAnalyzed = analyzeShared("compile_check_compound_assignment.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.unsupported_expression_route").isEmpty());

        var compiled = analyzeForCompile("compile_check_compound_assignment.gd", source);
        assertFalse(compiled.diagnostics().hasErrors(), () -> "Unexpected compile diagnostics: " + compiled.diagnostics().asList());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.unsupported_expression_route").isEmpty());
    }

    @Test
    void analyzeForCompileLeavesStaticMethodRoutesOutOfStaticPropertyCompileBlocks() throws Exception {
        var source = """
                class_name CompileCheckStaticMethodRoute
                extends RefCounted
                
                class Worker:
                    static func build() -> Worker:
                        return Worker.new()
                
                var worker := Worker.build()
                """;

        var sharedAnalyzed = analyzeShared("compile_check_static_method_route.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_static_method_route.gd", source);
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").toString()
        );
    }

    @Test
    void analyzeForCompileLeavesMappedTopLevelStaticMethodRoutesOutOfCompileBlocks() throws Exception {
        var source = """
                class_name MappedWorker
                extends RefCounted
                
                static func build() -> MappedWorker:
                    return MappedWorker.new()
                
                var worker := MappedWorker.build()
                """;

        var sharedAnalyzed = analyzeShared(
                "compile_check_mapped_static_method_route.gd",
                source,
                Map.of("MappedWorker", "RuntimeWorker")
        );
        assertFalse(sharedAnalyzed.diagnostics().hasErrors());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile(
                "compile_check_mapped_static_method_route.gd",
                source,
                Map.of("MappedWorker", "RuntimeWorker")
        );
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").toString()
        );
    }

    @Test
    void analyzeForCompileBlocksParameterizedGdccConstructorRoutes() throws Exception {
        var compiled = analyzeForCompile("compile_check_parameterized_gdcc_constructor.gd", """
                class_name CompileCheckParameterizedCtor
                extends RefCounted
                
                class Worker:
                    func _init(value: int):
                        pass
                
                func build(seed):
                    return Worker.new(seed)
                """);

        var callDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.call_resolution");
        var typeCheckDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.type_check");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(compiled.diagnostics().hasErrors());
        assertEquals(1, callDiagnostics.size());
        assertTrue(callDiagnostics.getFirst().message().contains("does not support arguments"));
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("supports only zero parameters"));
        assertEquals(1, compileDiagnostics.size());
        assertTrue(
                compileDiagnostics.getFirst().message().contains("supports only zero-argument custom object construction"),
                compileDiagnostics.getFirst().message()
        );
    }

    @Test
    void analyzeForCompileKeepsDedicatedGuardForResolvedParameterizedGdccConstructorRegression() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_parameterized_gdcc_constructor_regression.gd", """
                        class_name CompileCheckParameterizedCtorRegression
                        extends RefCounted
                
                        class Worker:
                            func _init(value: int):
                                pass
                
                        func build(seed):
                            return Worker.new(seed)
                """);
        var buildFunction = findFunction(preparedInput.unit().ast().statements(), "build");
        var newStep = findNode(buildFunction, AttributeCallStep.class, step -> step.name().equals("new"));
        preparedInput.analysisData().expressionTypes().clear();
        preparedInput.analysisData().resolvedCalls().clear();
        preparedInput.analysisData().resolvedCalls().put(
                newStep,
                FrontendResolvedCall.resolved(
                        "new",
                        FrontendCallResolutionKind.CONSTRUCTOR,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("CompileCheckParameterizedCtorRegression__sub__Worker"),
                        new GdObjectType("CompileCheckParameterizedCtorRegression__sub__Worker"),
                        List.of(GdVariantType.VARIANT),
                        new Object()
                )
        );
        preparedInput.analysisData().updateDiagnostics(new DiagnosticSnapshot(List.of()));
        var cleanDiagnosticManager = new DiagnosticManager();

        runCompileCheck(new PreparedCompileCheckInput(
                preparedInput.unit(),
                preparedInput.analysisData(),
                cleanDiagnosticManager
        ));

        var compileDiagnostics = diagnosticsByCategory(
                preparedInput.analysisData().diagnostics(),
                "sema.compile_check"
        );
        assertEquals(1, compileDiagnostics.size());
        assertTrue(
                compileDiagnostics.getFirst().message().contains("supports only zero-argument custom object construction"),
                compileDiagnostics.getFirst().message()
        );
    }

    @Test
    void matchArrayRouteReleasesBodyWhileUnsupportedBindingSubtreesStayClosed() throws Exception {
        var source = """
                class_name CompileCheckSkippedSurface
                extends Node
                
                func helper():
                    pass
                
                func ping(seed = [1]):
                    var body_local = 0
                    var f = func():
                        pass
                    const answer = [body_local]
                    match body_local:
                        [1, var bound]:
                            [bound]
                            preload("res://icon.svg")
                            $Camera3D
                            bound as int
                            bound is int
                            assert(bound)
                    return body_local
                """;

        var preparedInput = prepareCompileCheckInput("compile_check_skipped_surface.gd", source);
        runCompileCheck(preparedInput);
        var compiled = analyzeForCompile("compile_check_skipped_surface.gd", source);

        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var getNode = findNode(preparedInput.unit().ast(), GetNodeExpression.class, ignored -> true);
        // ARRAY is compile-ready: the match body joins the compile surface, and the nested
        // get-node is published RESOLVED(Node) — proof the body was actually scanned, since the
        // fact only exists for expressions the walker released. The match root itself, the nested
        // preload, and the assert stay clean.
        var getNodeType = preparedInput.analysisData().expressionTypes().get(getNode);
        assertNotNull(getNodeType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, getNodeType.status());
        assertEquals(new GdObjectType("Node"), getNodeType.publishedType());
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        var unsupportedBindingDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_binding_subtree"
        );
        // Block-local const stays fail-closed; the parameter default is now analyzed and accepted
        // by the parameter-default island, and the ready match route contributes nothing.
        assertEquals(1, unsupportedBindingDiagnostics.size(), unsupportedBindingDiagnostics::toString);
    }

    @Test
    void matchReadyRoutesReleaseBodyOntoCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckMatchReadySurface
                extends Node
                
                func ping(value: int):
                    match value:
                        var bound when bound > 0:
                            $Camera3D
                """;

        var preparedInput = prepareCompileCheckInput("compile_check_match_ready_surface.gd", source);
        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        // The ready match route releases its body onto the compile surface; the get-node inside
        // carries a published RESOLVED(Node) fact, which proves the body was actually scanned and
        // is lowering-ready.
        var getNode = findNode(preparedInput.unit().ast(), GetNodeExpression.class, ignored -> true);
        var getNodeType = preparedInput.analysisData().expressionTypes().get(getNode);
        assertNotNull(getNodeType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, getNodeType.status());
        assertEquals(new GdObjectType("Node"), getNodeType.publishedType());
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void forGenericVariantRouteReleasesBodyOntoCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckForBridge
                extends Node
                
                func ping(values):
                    for item in values:
                        var copy := item
                        $Camera3D
                """;

        var shared = analyzeShared("compile_check_for_bridge.gd", source);
        var preparedInput = prepareCompileCheckInput("compile_check_for_bridge.gd", source);
        runCompileCheck(preparedInput);

        assertTrue(shared.diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.unsupported_variable_inventory_subtree")
                        || diagnostic.category().equals("sema.unsupported_binding_subtree")
                        || diagnostic.category().equals("sema.compile_check")
        ));
        // The generic Variant for route releases its body onto the compile surface; the get-node
        // inside carries a published RESOLVED(Node) fact, which proves the body was actually
        // scanned and is lowering-ready.
        var getNode = findNode(preparedInput.unit().ast(), GetNodeExpression.class, ignored -> true);
        var getNodeType = preparedInput.analysisData().expressionTypes().get(getNode);
        assertNotNull(getNodeType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, getNodeType.status());
        assertEquals(new GdObjectType("Node"), getNodeType.publishedType());
        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileReleasesRangeCallRouteOntoCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckForRangeReleased
                extends Node
                
                func ping():
                    for i in range(3):
                        var copy := i
                """;

        var compiled = analyzeForCompile("compile_check_for_range_released.gd", source);

        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(compiled.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR));
    }

    @Test
    void analyzeForCompileReleasesIntShorthandRouteOntoCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckForIntShorthandReleased
                extends Node
                
                func ping():
                    for i in 5:
                        var copy := i
                """;

        var compiled = analyzeForCompile("compile_check_for_int_shorthand_released.gd", source);

        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
        assertTrue(compiled.diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.severity() == FrontendDiagnosticSeverity.ERROR));
    }

    @Test
    void analyzeForCompileScansReleasedRangeLoopBody() throws Exception {
        var source = """
                class_name CompileCheckForRangeBodyScanned
                extends Node
                
                func ping():
                    for i in range(3):
                        $Camera3D
                """;

        var preparedInput = prepareCompileCheckInput("compile_check_for_range_body_scanned.gd", source);
        runCompileCheck(preparedInput);

        // The released range loop body is scanned like any other compile surface; the get-node
        // inside carries a published RESOLVED(Node) fact, which proves the scan reached it and
        // that the fact is lowering-ready.
        var getNode = findNode(preparedInput.unit().ast(), GetNodeExpression.class, ignored -> true);
        var getNodeType = preparedInput.analysisData().expressionTypes().get(getNode);
        assertNotNull(getNodeType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, getNodeType.status());
        assertEquals(new GdObjectType("Node"), getNodeType.publishedType());
        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileDoesNotReWrapUpstreamErrorOnForRouteNotReady() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_for_upstream_error.gd", """
                class_name CompileCheckForUpstreamError
                extends Node
                
                func ping(values):
                    for item in values:
                        var copy := item
                """);
        var forStatement = findNode(preparedInput.unit().ast(), ForStatement.class, ignored -> true);
        preparedInput.diagnosticManager().error(
                "sema.synthetic",
                "synthetic upstream error owning the for statement anchor",
                preparedInput.unit().path(),
                FrontendRange.fromAstRange(forStatement.range())
        );
        preparedInput.analysisData().updateDiagnostics(preparedInput.diagnosticManager().snapshot());

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.compile_check").isEmpty());
        assertEquals(1, diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.synthetic").size());
    }

    @Test
    void analyzeForCompileFailsFastWhenForIterationPlanIsNotPublished() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_for_missing_plan.gd", """
                class_name CompileCheckForMissingPlan
                extends Node
                
                func ping():
                    for i in range(3):
                        var copy := i
                """);
        var forStatement = findNode(preparedInput.unit().ast(), ForStatement.class, ignored -> true);
        preparedInput.analysisData().forIterationPlans().remove(forStatement);

        var failure = assertThrows(
                IllegalStateException.class,
                () -> new FrontendCompileCheckAnalyzer().analyze(
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );
        assertTrue(failure.getMessage().contains("for-in iteration plan has not been published"));
    }

    @Test
    void analyzeSkipsCompileCheckWhenAnchorAlreadyHasPublishedError() throws Exception {
        // Anchor on a still-blocked form so compile-check dedup is not vacuous: a get-node inside
        // a property initializer stays DEFERRED on the shared path and would be escalated by the
        // generic published-fact scan if the synthetic upstream error did not own the anchor.
        var preparedInput = prepareCompileCheckInput("compile_check_existing_error.gd", """
                class_name CompileCheckExistingError
                extends Node
                
                var camera = $Camera3D
                """);
        var getNodeExpression = findNode(preparedInput.unit().ast(), GetNodeExpression.class, ignored -> true);
        preparedInput.diagnosticManager().error(
                "sema.synthetic",
                "synthetic upstream error",
                preparedInput.unit().path(),
                FrontendRange.fromAstRange(getNodeExpression.range())
        );
        preparedInput.analysisData().updateDiagnostics(preparedInput.diagnosticManager().snapshot());

        new FrontendCompileCheckAnalyzer().analyze(
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.compile_check").isEmpty());
        assertEquals(1, diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.synthetic").size());
    }

    @Test
    void analyzeDeduplicatesAgainstLiveManagerSnapshotWhenAnalysisDataSnapshotIsStale() throws Exception {
        // Anchor on a still-blocked form so compile-check dedup is not vacuous (property
        // initializer get-node stays DEFERRED on the shared path; see the sibling test above).
        var preparedInput = prepareCompileCheckInput("compile_check_live_manager_upstream.gd", """
                class_name CompileCheckLiveManagerUpstream
                extends Node
                
                var camera = $Camera3D
                """);
        var getNodeExpression = findNode(preparedInput.unit().ast(), GetNodeExpression.class, _ -> true);
        preparedInput.diagnosticManager().error(
                "sema.synthetic",
                "synthetic upstream error not yet copied to analysisData",
                preparedInput.unit().path(),
                FrontendRange.fromAstRange(getNodeExpression.range())
        );
        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.synthetic").isEmpty());

        new FrontendCompileCheckAnalyzer().analyze(
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var finalSnapshot = preparedInput.diagnosticManager().snapshot();
        assertTrue(diagnosticsByCategory(finalSnapshot, "sema.compile_check").isEmpty());
        assertEquals(1, diagnosticsByCategory(finalSnapshot, "sema.synthetic").size());
    }

    @Test
    void analyzeForCompileReleasesSupportedConditionalExpressionsWithoutCompileCheck() throws Exception {
        var compiled = analyzeForCompile("compile_check_conditional_ready.gd", """
                class_name CompileCheckConditionalReady
                extends Node
                
                func ping(flag: bool, seed: int) -> int:
                    var chosen = seed if flag else 0
                    return chosen
                """);

        assertAll(
                () -> assertFalse(compiled.diagnostics().hasErrors()),
                () -> assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty()),
                () -> assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution").isEmpty()),
                () -> assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.deferred_expression_resolution").isEmpty())
        );
    }

    @Test
    void analyzeForCompileDeduplicatesFailedConditionalArmAgainstRepublishedRootDiagnostic() throws Exception {
        var compiled = analyzeForCompile("compile_check_conditional_failed_arm.gd", """
                class_name CompileCheckConditionalFailedArm
                extends Node
                
                func ping(flag: bool):
                    var failed_arm = missing_identifier if flag else 0
                """);
        var conditional = findNode(compiled.unit().ast(), ConditionalExpression.class, ignored -> true);
        var missingArm = conditional.left();
        var expressionDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");

        // Binary-style root re-ownership: one diagnostic at the unbound identifier arm plus one
        // re-emitted at the conditional root, each anchored at its exact range. The gate's
        // exact-range conflict dedup then suppresses the generic compile_check for both anchors.
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors()),
                () -> assertEquals(2, expressionDiagnostics.size()),
                () -> assertTrue(expressionDiagnostics.stream().anyMatch(diagnostic ->
                        FrontendRange.fromAstRange(missingArm.range()).equals(diagnostic.range())
                )),
                () -> assertTrue(expressionDiagnostics.stream().anyMatch(diagnostic ->
                        FrontendRange.fromAstRange(conditional.range()).equals(diagnostic.range())
                )),
                () -> assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty())
        );
    }

    @Test
    void analyzeForCompileKeepsUnsupportedConditionalRouteWithoutCompileCheck() throws Exception {
        var compiled = analyzeForCompile("compile_check_conditional_void_arm.gd", """
                class_name CompileCheckConditionalVoidArm
                extends Node
                
                func void_helper() -> void:
                    pass
                
                func ping(flag: bool):
                    var void_arm = void_helper() if flag else void_helper()
                """);
        var conditional = findNode(compiled.unit().ast(), ConditionalExpression.class, ignored -> true);
        var unsupportedDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.unsupported_expression_route");

        // The void arm itself is RESOLVED(void) without diagnostics; only the conditional root owns
        // a single unsupported route, and its upstream error suppresses the generic compile_check.
        assertAll(
                () -> assertTrue(compiled.diagnostics().hasErrors()),
                () -> assertEquals(1, unsupportedDiagnostics.size()),
                () -> assertEquals(
                        FrontendRange.fromAstRange(conditional.range()),
                        unsupportedDiagnostics.getFirst().range()
                ),
                () -> assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty())
        );
    }

    @Test
    void analyzeReportsGenericCompileBlocksForPublishedCompileSurfaceFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_published_facts.gd", """
                class_name CompileCheckPublishedFacts
                extends RefCounted
                
                class Worker:
                    var payload: int = 1
                
                    func read() -> int:
                        return 1
                
                func ping(worker: Worker):
                    var copy = worker
                    var payload_copy = worker.payload
                    var read_value = worker.read()
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var copyDeclaration = findVariable(pingFunction.body().statements(), "copy");
        var payloadCopyDeclaration = findVariable(pingFunction.body().statements(), "payload_copy");
        var readValueDeclaration = findVariable(pingFunction.body().statements(), "read_value");
        var copyIdentifier = assertInstanceOf(dev.superice.gdparser.frontend.ast.IdentifierExpression.class, copyDeclaration.value());
        var payloadStep = findNode(
                Objects.requireNonNull(payloadCopyDeclaration.value()),
                AttributePropertyStep.class,
                step -> step.name().equals("payload")
        );
        var readStep = findNode(
                Objects.requireNonNull(readValueDeclaration.value()),
                AttributeCallStep.class,
                step -> step.name().equals("read")
        );

        preparedInput.analysisData().expressionTypes().put(
                copyIdentifier,
                FrontendExpressionType.deferred("synthetic deferred expression")
        );
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(payloadStep));
        preparedInput.analysisData().resolvedMembers().put(
                payloadStep,
                FrontendResolvedMember.failed(
                        originalMember.memberName(),
                        originalMember.bindingKind(),
                        originalMember.receiverKind(),
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic failed member"
                )
        );
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.unsupported(
                        originalCall.callableName(),
                        originalCall.callKind(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic unsupported call"
                )
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(3, compileDiagnostics.size());
        assertTrue(compileDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
        ));
        assertEquals(
                Set.of(
                        FrontendRange.fromAstRange(copyIdentifier.range()),
                        FrontendRange.fromAstRange(payloadStep.range()),
                        FrontendRange.fromAstRange(readStep.range())
                ),
                compileDiagnostics.stream().map(FrontendDiagnostic::range).collect(java.util.stream.Collectors.toSet())
        );
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic deferred expression")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic failed member")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic unsupported call")
        ));
    }

    @Test
    void analyzeDeduplicatesGenericCompileBlocksAtSharedAttributeFinalStepAnchor() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_shared_anchor.gd", """
                class_name CompileCheckSharedAnchor
                extends RefCounted
                
                class Worker:
                    func read() -> int:
                        return 1
                
                func ping(worker: Worker):
                    var value = worker.read()
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var valueDeclaration = findVariable(pingFunction.body().statements(), "value");
        var attributeExpression = assertInstanceOf(AttributeExpression.class, valueDeclaration.value());
        var readStep = findNode(attributeExpression, AttributeCallStep.class, step -> step.name().equals("read"));
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));

        preparedInput.analysisData().expressionTypes().put(
                attributeExpression,
                FrontendExpressionType.failed("synthetic failed attribute expression")
        );
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.failed(
                        originalCall.callableName(),
                        originalCall.callKind(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic failed call step"
                )
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(1, compileDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(readStep.range()), compileDiagnostics.getFirst().range());
        assertTrue(compileDiagnostics.getFirst().message().contains("synthetic failed attribute expression"));
    }

    @Test
    void analyzeReportsCompileBlocksForPublishedBareCallFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_bare_call_fact.gd", """
                class_name CompileCheckBareCallFact
                extends RefCounted
                
                func helper(value: int) -> int:
                    return value
                
                static func ping_static(value: int):
                    helper(value)
                """);
        var pingStaticFunction = findFunction(preparedInput.unit().ast().statements(), "ping_static");
        var bareCall = findNode(
                assertInstanceOf(ExpressionStatement.class, pingStaticFunction.body().statements().getFirst()),
                CallExpression.class,
                ignored -> true
        );
        var publishedBareCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(bareCall));

        assertEquals(FrontendCallResolutionStatus.BLOCKED, publishedBareCall.status());
        assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, publishedBareCall.callKind());
        assertEquals(FrontendReceiverKind.INSTANCE, publishedBareCall.receiverKind());
        preparedInput.analysisData().expressionTypes().put(
                bareCall,
                FrontendExpressionType.resolved(Objects.requireNonNull(publishedBareCall.returnType()))
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        var bareCallDiagnostics = compileDiagnostics.stream()
                .filter(diagnostic -> FrontendRange.fromAstRange(bareCall.range()).equals(diagnostic.range()))
                .toList();
        assertEquals(1, bareCallDiagnostics.size());
        assertTrue(bareCallDiagnostics.getFirst().message().contains("Call expression 'helper(...)'"));
        assertTrue(bareCallDiagnostics.getFirst().message().contains("not accessible in the current context"));
    }

    @Test
    void analyzeUsesCompileGateForUnsupportedPublishedAttributeSubscriptStepFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_attribute_subscript_step.gd", """
                class_name CompileCheckAttributeSubscriptStep
                extends RefCounted
                
                class Worker:
                    var payloads: Dictionary[int, int]
                
                func ping(worker: Worker, seed: int):
                    var value = worker.payloads[seed]
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var valueDeclaration = findVariable(pingFunction.body().statements(), "value");
        var payloadsStep = findNode(
                Objects.requireNonNull(valueDeclaration.value()),
                AttributeSubscriptStep.class,
                step -> step.name().equals("payloads")
        );

        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.type_check").isEmpty());
        preparedInput.analysisData().expressionTypes().put(
                payloadsStep,
                FrontendExpressionType.unsupported("synthetic unsupported attribute subscript step")
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(1, compileDiagnostics.size());
        assertEquals(FrontendRange.fromAstRange(payloadsStep.range()), compileDiagnostics.getFirst().range());
        assertTrue(compileDiagnostics.getFirst().message().contains("Subscript step 'payloads[...]'"));
        assertTrue(compileDiagnostics.getFirst().message().contains("synthetic unsupported attribute subscript step"));
    }

    @Test
    void analyzeReportsGenericCompileBlocksForPublishedPropertyInitializerFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_property_initializer_facts.gd", """
                class_name CompileCheckPropertyInitializerFacts
                extends RefCounted
                
                class Handle:
                    func read() -> int:
                        return 1
                
                class Worker:
                    var handle: Handle = Handle.new()
                
                    static func build() -> Worker:
                        return Worker.new()
                
                var expr_value: int = 1
                var member_value := Worker.build().handle
                var call_value := Worker.build().handle.read()
                """);
        var exprValueDeclaration = findVariable(preparedInput.unit().ast().statements(), "expr_value");
        var memberValueDeclaration = findVariable(preparedInput.unit().ast().statements(), "member_value");
        var callValueDeclaration = findVariable(preparedInput.unit().ast().statements(), "call_value");
        var exprLiteral = assertInstanceOf(LiteralExpression.class, exprValueDeclaration.value());
        var handleStep = findNode(
                Objects.requireNonNull(memberValueDeclaration.value()),
                AttributePropertyStep.class,
                step -> step.name().equals("handle")
        );
        var readStep = findNode(
                Objects.requireNonNull(callValueDeclaration.value()),
                AttributeCallStep.class,
                step -> step.name().equals("read")
        );

        preparedInput.analysisData().expressionTypes().put(
                exprLiteral,
                FrontendExpressionType.failed("synthetic property initializer expression")
        );
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(handleStep));
        preparedInput.analysisData().resolvedMembers().put(
                handleStep,
                FrontendResolvedMember.failed(
                        originalMember.memberName(),
                        originalMember.bindingKind(),
                        originalMember.receiverKind(),
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic property initializer member"
                )
        );
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.unsupported(
                        originalCall.callableName(),
                        originalCall.callKind(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic property initializer call"
                )
        );

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(3, compileDiagnostics.size());
        assertEquals(
                Set.of(
                        FrontendRange.fromAstRange(exprLiteral.range()),
                        FrontendRange.fromAstRange(handleStep.range()),
                        FrontendRange.fromAstRange(readStep.range())
                ),
                compileDiagnostics.stream().map(FrontendDiagnostic::range).collect(java.util.stream.Collectors.toSet())
        );
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic property initializer expression")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic property initializer member")
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("synthetic property initializer call")
        ));
    }

    @Test
    void analyzeScansAcceptedParameterDefaultRootFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_param_default_surface.gd", """
                class_name CompileCheckParamDefaultSurface
                extends Node
                
                func ping(seed = 1):
                    var callback = func():
                        return 2
                    pass
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var defaultLiteral = assertInstanceOf(LiteralExpression.class, pingFunction.parameters().getFirst().defaultValue());

        preparedInput.analysisData().expressionTypes().put(
                defaultLiteral,
                FrontendExpressionType.deferred("synthetic default-value deferred expression")
        );
        assertTrue(preparedInput.analysisData().expressionTypes().containsKey(defaultLiteral));

        runCompileCheck(preparedInput);

        // Accepted parameter default roots belong to the compile surface even without call facts:
        // the corrupted not-ready literal fact is scanned and blocked exactly once at the literal
        // anchor. The recorded lambda in the body stays clean and contributes nothing.
        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertEquals(1, compileDiagnostics.size(), compileDiagnostics::toString);
        assertEquals(
                FrontendRange.fromAstRange(defaultLiteral.range()),
                compileDiagnostics.getFirst().range(),
                compileDiagnostics::toString
        );
        assertTrue(
                compileDiagnostics.getFirst().message().contains("synthetic default-value deferred expression"),
                compileDiagnostics::toString
        );
    }

    @Test
    void analyzeSkipsDynamicPublishedFactsInsideCompileSurface() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_dynamic_surface.gd", """
                class_name CompileCheckDynamicSurface
                extends RefCounted
                
                class Worker:
                    var payload: int = 1
                
                    func read() -> int:
                        return 1
                
                func ping(worker: Worker):
                    var copy = worker
                    var payload_copy = worker.payload
                    var read_value = worker.read()
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var copyDeclaration = findVariable(pingFunction.body().statements(), "copy");
        var payloadCopyDeclaration = findVariable(pingFunction.body().statements(), "payload_copy");
        var readValueDeclaration = findVariable(pingFunction.body().statements(), "read_value");
        var copyIdentifier = assertInstanceOf(dev.superice.gdparser.frontend.ast.IdentifierExpression.class, copyDeclaration.value());
        var payloadStep = findNode(
                Objects.requireNonNull(payloadCopyDeclaration.value()),
                AttributePropertyStep.class,
                step -> step.name().equals("payload")
        );
        var readStep = findNode(
                Objects.requireNonNull(readValueDeclaration.value()),
                AttributeCallStep.class,
                step -> step.name().equals("read")
        );
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(payloadStep));
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(readStep));

        preparedInput.analysisData().expressionTypes().put(
                copyIdentifier,
                FrontendExpressionType.dynamic("synthetic dynamic expression")
        );
        preparedInput.analysisData().resolvedMembers().put(
                payloadStep,
                FrontendResolvedMember.dynamic(
                        originalMember.memberName(),
                        originalMember.bindingKind(),
                        originalMember.receiverKind(),
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic dynamic member"
                )
        );
        preparedInput.analysisData().resolvedCalls().put(
                readStep,
                FrontendResolvedCall.dynamic(
                        originalCall.callableName(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        originalCall.receiverType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic dynamic call"
                )
        );

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeKeepsDynamicMemberPublicationDriftOutOfCompileGate() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_dynamic_member_publication_drift.gd", """
                class_name CompileCheckDynamicMemberPublicationDrift
                extends RefCounted
                
                func zero() -> Vector3:
                    return Vector3.ZERO
                """);
        var zeroFunction = findFunction(preparedInput.unit().ast().statements(), "zero");
        var returnStatement = assertInstanceOf(dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                zeroFunction.body().statements().getFirst());
        var expression = assertInstanceOf(AttributeExpression.class, returnStatement.value());
        var zeroStep = assertInstanceOf(AttributePropertyStep.class, expression.steps().getFirst());
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(zeroStep));
        preparedInput.analysisData().resolvedMembers().put(
                zeroStep,
                FrontendResolvedMember.dynamic(
                        originalMember.memberName(),
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.TYPE_META,
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic type-meta dynamic publication drift"
                )
        );

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileUpgradesDeferredWarningsIntoCompileBlockingErrors() throws Exception {
        // A get-node inside a property initializer is the remaining DEFERRED producer on the
        // shared analyze path (warning only); compile mode escalates it via the generic
        // published-fact scan.
        var source = """
                class_name DeferredCompileCheck
                extends Node
                
                var camera = $Camera3D
                """;

        var shared = analyzeShared("deferred_compile_check.gd", source);
        assertFalse(shared.diagnostics().hasErrors());
        var deferredWarnings = diagnosticsByCategory(shared.diagnostics(), "sema.deferred_expression_resolution");
        assertEquals(1, deferredWarnings.size());
        assertTrue(deferredWarnings.getFirst().message().contains("Get-node expression"));
        assertTrue(diagnosticsByCategory(shared.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("deferred_compile_check.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertEquals(1, compileDiagnostics.size(), () -> compiled.diagnostics().asList().toString());
        assertTrue(compiled.diagnostics().hasErrors());
        assertTrue(compileDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
        ));
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("remains deferred")
                        && diagnostic.message().contains("Get-node expression")
        ));
    }

    @Test
    void analyzeForCompileKeepsStaticGetNodeFailureOnUpstreamErrorWithoutDuplicateCompileCheck() throws Exception {
        var source = """
                class_name CompileCheckStaticGetNode
                extends Node
                
                static func ping():
                    $Camera3D
                """;

        var compiled = analyzeForCompile("compile_check_static_get_node.gd", source);

        // The static-function get-node failure is owned upstream by `sema.expression_resolution`;
        // the generic published-fact scan sees the same FAILED fact but must not wrap a second
        // `sema.compile_check` on the already-reported anchor.
        assertTrue(compiled.diagnostics().hasErrors());
        var expressionErrors = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        assertEquals(1, expressionErrors.size(), () -> compiled.diagnostics().asList().toString());
        assertTrue(expressionErrors.getFirst().message().contains("static function"));
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> compiled.diagnostics().asList().toString()
        );
    }

    @Test
    void analyzeForCompileKeepsStringFormatVariantRightOperandWithoutCompileCheckDiagnostic() throws Exception {
        var source = """
                class_name CompileCheckStringFormatVariant
                extends RefCounted
                
                func render(value: Variant) -> String:
                    return "value=%s" % value
                """;

        var compiled = analyzeForCompile("compile_check_string_format_variant.gd", source);

        // `String % Variant` publishes a resolved String fact, so neither the upstream resolution
        // nor the compile-check scan has anything to report.
        assertFalse(compiled.diagnostics().hasErrors(), () -> compiled.diagnostics().asList().toString());
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> compiled.diagnostics().asList().toString()
        );
    }

    @Test
    void analyzeForCompileKeepsStringFormatNilFailureOnUpstreamErrorWithoutDuplicateCompileCheck() throws Exception {
        var source = """
                class_name CompileCheckStringFormatNil
                extends RefCounted
                
                func render() -> String:
                    return "value=%s" % null
                """;

        var compiled = analyzeForCompile("compile_check_string_format_nil.gd", source);

        // The `String % null` failure is owned upstream by `sema.expression_resolution`; the
        // compile-check scan must not wrap a second `sema.compile_check` on the same anchor.
        assertTrue(compiled.diagnostics().hasErrors());
        var expressionErrors = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        assertEquals(1, expressionErrors.size(), () -> compiled.diagnostics().asList().toString());
        assertTrue(expressionErrors.getFirst().message().contains(
                "Binary operator '%' is not defined for operand types 'String' and 'Nil'"
        ));
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> compiled.diagnostics().asList().toString()
        );
    }

    @Test
    void analyzeForCompileKeepsNonNodeGetNodeFailureOnUpstreamErrorWithoutDuplicateCompileCheck() throws Exception {
        var source = """
                class_name CompileCheckNonNodeGetNode
                extends RefCounted
                
                func ping():
                    $Camera3D
                """;

        var compiled = analyzeForCompile("compile_check_non_node_get_node.gd", source);

        // Same upstream-owner rule for the non-Node-derived boundary: exactly one
        // `sema.expression_resolution` error, no duplicate `sema.compile_check`.
        assertTrue(compiled.diagnostics().hasErrors());
        var expressionErrors = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        assertEquals(1, expressionErrors.size(), () -> compiled.diagnostics().asList().toString());
        assertTrue(expressionErrors.getFirst().message().contains("inherits from Node"));
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> compiled.diagnostics().asList().toString()
        );
    }

    @Test
    void analyzeForCompileKeepsStaticLambdaGetNodeFailureOnUpstreamErrorWithoutDuplicateCompileCheck() throws Exception {
        var source = """
                class_name CompileCheckStaticLambdaGetNode
                extends Node
                
                static func ping():
                    var f = func():
                        return $Camera3D
                """;

        var compiled = analyzeForCompile("compile_check_static_lambda_get_node.gd", source);

        // A static enclosing callable synthesizes no self capture, so the lambda get-node failure
        // stays on the upstream `sema.expression_resolution` owner; the generic published-fact
        // scan sees the FAILED fact but must not wrap a second `sema.compile_check`.
        assertTrue(compiled.diagnostics().hasErrors());
        var expressionErrors = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        assertEquals(1, expressionErrors.size(), () -> compiled.diagnostics().asList().toString());
        assertTrue(expressionErrors.getFirst().message().contains("static function"));
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> compiled.diagnostics().asList().toString()
        );
    }

    @Test
    void analyzeForCompileKeepsNonNodeLambdaGetNodeFailureOnUpstreamErrorWithoutDuplicateCompileCheck() throws Exception {
        var source = """
                class_name CompileCheckNonNodeLambdaGetNode
                extends RefCounted
                
                func ping():
                    var f = func():
                        return $Camera3D
                """;

        var compiled = analyzeForCompile("compile_check_non_node_lambda_get_node.gd", source);

        // The leading self capture exists (instance function) but is not Node-assignable: the
        // lambda get-node failure keeps the class-hierarchy wording on the upstream owner with
        // no duplicate `sema.compile_check`.
        assertTrue(compiled.diagnostics().hasErrors());
        var expressionErrors = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        assertEquals(1, expressionErrors.size(), () -> compiled.diagnostics().asList().toString());
        assertTrue(expressionErrors.getFirst().message().contains("inherits from Node"));
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                () -> compiled.diagnostics().asList().toString()
        );
    }

    @Test
    void analyzeForCompileReleasesBareSignalValueReadWhileAnalyzeLeavesSharedFactsUntouched() throws Exception {
        var source = """
                class_name CompileCheckBareSignalValue
                extends Node
                
                signal pinged
                
                func ping():
                    var copied = pinged
                """;

        var sharedAnalyzed = analyzeShared("compile_check_bare_signal_value.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors(), () -> sharedAnalyzed.diagnostics().asList().toString());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_bare_signal_value.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics().asList()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileReleasesReceiverQualifiedSignalReads() throws Exception {
        var source = """
                class_name CompileCheckReceiverSignalValue
                extends Node
                
                signal pinged
                
                func ping(other: CompileCheckReceiverSignalValue):
                    var from_other = other.pinged
                    var from_self = self.pinged
                """;

        var compiled = analyzeForCompile("compile_check_receiver_signal_value.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics().asList()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileStillBlocksStaticContextSignalReads() throws Exception {
        var source = """
                class_name CompileCheckStaticSignalValue
                extends Node
                
                signal pinged
                
                static func copy_signal() -> Signal:
                    return pinged
                """;

        var compiled = analyzeForCompile("compile_check_static_signal_value.gd", source);

        assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics().asList()::toString);
        assertTrue(compiled.diagnostics().asList().stream().anyMatch(diagnostic ->
                diagnostic.message().contains("pinged")
        ), compiled.diagnostics().asList()::toString);
    }

    @Test
    void analyzeForCompileReportsBareSignalAssignmentAsReadOnlyWithoutCompileCheck() throws Exception {
        var source = """
                class_name CompileCheckBareSignalAssignment
                extends Node
                
                signal pinged
                
                func bad():
                    pinged = null
                """;

        var sharedAnalyzed = analyzeShared("compile_check_bare_signal_assignment.gd", source);
        var compiled = analyzeForCompile("compile_check_bare_signal_assignment.gd", source);
        var badFunction = findFunction(compiled.unit().ast().statements(), "bad");
        var assignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, badFunction.body().statements().getFirst()).expression()
        );
        assertInstanceOf(IdentifierExpression.class, assignment.left());

        var sharedExpressionDiagnostics = diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.expression_resolution");
        var compiledExpressionDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.expression_resolution");
        var compiledCompileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertTrue(sharedAnalyzed.diagnostics().hasErrors(), sharedAnalyzed.diagnostics().asList()::toString);
        assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics().asList()::toString);
        assertEquals(1, sharedExpressionDiagnostics.size(), sharedExpressionDiagnostics::toString);
        assertTrue(
                sharedExpressionDiagnostics.getFirst().message().contains("read-only"),
                sharedExpressionDiagnostics::toString
        );
        assertEquals(1, compiledExpressionDiagnostics.size(), compiledExpressionDiagnostics::toString);
        assertTrue(
                compiledExpressionDiagnostics.getFirst().message().contains("read-only"),
                compiledExpressionDiagnostics::toString
        );
        assertTrue(compiledCompileDiagnostics.isEmpty(), compiledCompileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileReleasesSignalConnectDisconnectAndBareMethodReference() throws Exception {
        var source = """
                class_name CompileCheckSignalMethods
                extends Node
                
                signal pinged
                
                func _handler():
                    pass
                
                func ping(sig: Signal, other: CompileCheckSignalMethods):
                    sig.emit()
                    sig.connect(_handler)
                    sig.disconnect(_handler)
                    var err = sig.connect(other._handler, Object.CONNECT_DEFERRED)
                    var copied = _handler
                    return err
                """;

        var compiled = analyzeForCompile("compile_check_signal_methods.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileReleasesSignalEmitArityAndHeterogeneousArgs() throws Exception {
        var source = """
                class_name CompileCheckSignalEmitReleased
                extends Node
                
                signal pinged(count: int)
                
                func ping(sig: Signal, label: String, vec: Vector3):
                    sig.emit()
                    sig.emit(1)
                    sig.emit(1, label, vec)
                    pinged.emit("not-an-int")
                """;

        var compiled = analyzeForCompile("compile_check_signal_emit_released.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileReleasesBareStaticAndUtilityValueReads() throws Exception {
        var source = """
                class_name CompileCheckBareValueReferences
                extends RefCounted
                
                func helper(value):
                    return value
                
                static func make_static():
                    return 1
                
                func ping():
                    var method_ref = helper
                    var static_ref = make_static
                    var utility_ref = print
                """;

        var compiled = analyzeForCompile("compile_check_bare_value_references.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    /// Bare `lerp` is a value-ref, while `lerp(...)` stays a legal utility call.
    @Test
    void analyzeForCompileReleasesLerpUtilityValueRead() throws Exception {
        var source = """
                class_name CompileCheckLerpValueReference
                extends Node
                
                func ping():
                    var utility_ref = lerp
                    lerp(0.0, 1.0, 0.5)
                """;

        var compiled = analyzeForCompile("compile_check_lerp_value_reference.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    /// Constructor-as-value stays failed; compile gate must not treat it as a released Callable.
    @Test
    void analyzeForCompileBlocksConstructorValueReference() throws Exception {
        var source = """
                class_name CompileCheckConstructorValueReference
                extends Node
                
                func ping():
                    var ctor_ref = Node.new
                """;

        var compiled = analyzeForCompile("compile_check_constructor_value_reference.gd", source);
        var ctorStep = findNamedPropertyStep(findFunction(compiled.unit().ast().statements(), "ping"), "ctor_ref", "new");
        var memberDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.member_resolution");

        assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(
                memberDiagnostics.stream().anyMatch(diagnostic ->
                        diagnostic.range().equals(FrontendRange.fromAstRange(ctorStep.range()))
                                && diagnostic.message().contains("new")
                ),
                compiled.diagnostics().asList()::toString
        );
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty()
                        || compiled.diagnostics().asList().stream().anyMatch(diagnostic ->
                        diagnostic.message().contains("new") || diagnostic.message().contains("Node.new")),
                compiled.diagnostics().asList()::toString);
    }

    /// Classified signal await is legal on the compile surface.
    @Test
    void analyzeForCompileAllowsAwaitSignal() throws Exception {
        var source = """
                class_name CompileCheckAwaitSignal
                extends Node
                
                signal pinged
                
                func ping():
                    await pinged
                """;

        var compiled = analyzeForCompile("compile_check_await_signal.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");

        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileAllowsDynamicAndCoroutineCallAwaits() throws Exception {
        var source = """
                class_name CompileCheckAwaitRoutes
                extends Node
                
                signal pinged
                
                func inner() -> int:
                    await pinged
                    return 7
                
                func await_call() -> int:
                    return await inner()
                
                func await_dynamic(value: Variant) -> Variant:
                    return await value
                """;

        var compiled = analyzeForCompile("compile_check_await_routes.gd", source);
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileReleasesBuiltinAndStaticMethodReferences() throws Exception {
        var source = """
                class_name CompileCheckUnsupportedMethodReferences
                extends Node
                
                static func make_static():
                    return 1
                
                func ping(vec: Vector2, dict: Dictionary):
                    var builtin_ref = vec.abs
                    var static_ref = CompileCheckUnsupportedMethodReferences.make_static
                    var engine_ref = JSON.parse_string
                    var dict_ref = dict.clear
                    var type_meta_ref = Vector2.from_angle
                    var type_meta_abs = Vector2.abs
                    dict.clear()
                    var lambda_cb = func():
                        pass
                    pinged.connect(lambda_cb)
                
                signal pinged
                """;

        var compiled = analyzeForCompile("compile_check_unsupported_method_references.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var pingFunction = findFunction(compiled.unit().ast().statements(), "ping");
        var dictStep = findNamedPropertyStep(pingFunction, "dict_ref", "clear");

        assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.stream().noneMatch(diagnostic ->
                diagnostic.message().contains("Qualified method-reference 'abs'")
        ), compileDiagnostics::toString);
        assertTrue(compileDiagnostics.stream().noneMatch(diagnostic ->
                diagnostic.message().contains("Qualified static-method 'make_static'")
        ), compileDiagnostics::toString);
        assertTrue(compileDiagnostics.stream().noneMatch(diagnostic ->
                diagnostic.message().contains("parse_string")
        ), compileDiagnostics::toString);
        assertTrue(compileDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.range().equals(FrontendRange.fromAstRange(dictStep.range()))
                        && diagnostic.message().contains("Qualified method-reference 'clear'")
        ), compileDiagnostics::toString);
        assertTrue(compileDiagnostics.stream().noneMatch(diagnostic ->
                diagnostic.message().contains("Qualified method-reference 'clear'")
                        && !diagnostic.range().equals(FrontendRange.fromAstRange(dictStep.range()))
        ), compileDiagnostics::toString);
        // The recorded lambda no longer reports unsupported binding/chain subtrees.
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.unsupported_binding_subtree").isEmpty());
        // The recorded lambda is released onto the compile surface, so it contributes no
        // form-level lambda compile block either.
        assertTrue(compileDiagnostics.stream().noneMatch(diagnostic ->
                diagnostic.message().contains("Lambda expression")
        ), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileReleasesDirectLambdaConnectArgument() throws Exception {
        var source = """
                class_name CompileCheckDirectLambdaConnect
                extends Node
                
                signal pinged
                
                func ping(sig: Signal):
                    sig.connect(func():
                        pass
                    )
                """;

        var compiled = analyzeForCompile("compile_check_direct_lambda_connect.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var unsupportedExpressionDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_expression_route"
        );
        var unsupportedBindingDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_binding_subtree"
        );

        // The lambda is recorded inside a supported executable body, so the gate releases
        // it onto the compile surface and the connect argument compiles cleanly — no form-level
        // lambda blocker, no unsupported expression route, no unsupported binding subtree.
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        assertTrue(unsupportedExpressionDiagnostics.isEmpty(), unsupportedExpressionDiagnostics::toString);
        assertTrue(unsupportedBindingDiagnostics.isEmpty(), unsupportedBindingDiagnostics::toString);
    }

    @Test
    void analyzeForCompileAllowsAwaitInsideRecordedLambdaBody() throws Exception {
        var source = """
                class_name CompileCheckLambdaAwait
                extends Node
                
                signal pinged
                
                func watch():
                    var prefix = "seen"
                    var callback = func():
                        await pinged
                        var _echo = prefix
                    callback.call()
                """;

        var compiled = analyzeForCompile("compile_check_lambda_await.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var unsupportedExpressionDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_expression_route"
        );

        // A recorded lambda body accepts await (with captures) — the gate recurses
        // into the body with the ordinary published-fact scan and no await-specific blocker fires;
        // the enclosing named function `watch` stays non-coroutine (the suspend point belongs to
        // the lambda's own synthesized shell).
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        assertTrue(unsupportedExpressionDiagnostics.isEmpty(), unsupportedExpressionDiagnostics::toString);
    }

    @Test
    void analyzeForCompileScansRecordedLambdaBodyPublishedFacts() throws Exception {
        var source = """
                class_name CompileCheckLambdaBodyScan
                extends Node
                
                func ping():
                    var body_local = 0
                    var f = func():
                        preload("res://icon.svg")
                        $Camera3D
                        assert(body_local)
                """;

        var shared = analyzeShared("compile_check_lambda_body_scan.gd", source);
        var preparedInput = prepareCompileCheckInput("compile_check_lambda_body_scan.gd", source);
        runCompileCheck(preparedInput);

        // The gate recurses into the recorded lambda body: preload, get-node and assert are all
        // compile-ready there, so neither the shared path nor the generic published-fact scan
        // reports any deferred/compile-check outcome.
        assertTrue(shared.diagnostics().asList().stream().noneMatch(diagnostic ->
                diagnostic.category().equals("sema.deferred_expression_resolution")
                        || diagnostic.category().equals("sema.compile_check")
        ), shared.diagnostics()::toString);
        // The lambda body's get-node publishes RESOLVED(Node) — the leading self capture supplies
        // the receiver — which proves the body was actually scanned and is lowering-ready.
        var getNode = findNode(preparedInput.unit().ast(), GetNodeExpression.class, ignored -> true);
        var getNodeType = preparedInput.analysisData().expressionTypes().get(getNode);
        assertNotNull(getNodeType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, getNodeType.status());
        assertEquals(new GdObjectType("Node"), getNodeType.publishedType());
        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileKeepsUnrecordedPropertyInitializerLambdaBlocked() throws Exception {
        var source = """
                class_name CompileCheckPropertyInitLambda
                extends Node
                
                var cb = func():
                    pass
                """;

        var compiled = analyzeForCompile("compile_check_property_init_lambda.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var unsupportedExpressionDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_expression_route"
        );
        var lambda = findNode(compiled.unit().ast(), LambdaExpression.class, ignored -> true);
        var lambdaRange = FrontendRange.fromAstRange(lambda.range());

        // A property-initializer lambda publishes no plan, so the gate must not release it onto the
        // compile surface. Upstream resolution owns the failure through the unsupported
        // lambda-subtree diagnostics; the compile gate adds no release and no duplicate blocker.
        assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        assertTrue(unsupportedExpressionDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.range().equals(lambdaRange)
                        && diagnostic.message().contains("Lambda expression")
        ), unsupportedExpressionDiagnostics::toString);
    }

    @Test
    void analyzeForCompileKeepsGetNodeInsideUnrecordedPropertyInitializerLambdaSilent() throws Exception {
        var source = """
                class_name CompileCheckPropertyInitLambdaGetNode
                extends Node
                
                var cb = func():
                    return $Camera3D
                """;

        var preparedInput = prepareCompileCheckInput("compile_check_property_init_lambda_get_node.gd", source);
        runCompileCheck(preparedInput);

        var lambda = findNode(preparedInput.unit().ast(), LambdaExpression.class, ignored -> true);
        var getNode = findNode(preparedInput.unit().ast(), GetNodeExpression.class, ignored -> true);
        var lambdaRange = FrontendRange.fromAstRange(lambda.range());
        var getNodeRange = FrontendRange.fromAstRange(getNode.range());

        // The property-initializer lambda is an unrecorded subtree: no plan is published and body
        // traversal prunes before entering the lambda body, so the nested get-node publishes no
        // expression type and draws no nested diagnostic of its own — the unrecorded-lambda root
        // diagnostic holds the failure, and the gate adds no compile_check.
        assertFalse(preparedInput.analysisData().lambdaPlans().containsKey(lambda));
        assertNull(preparedInput.analysisData().expressionTypes().get(getNode));
        var diagnostics = preparedInput.analysisData().diagnostics();
        assertTrue(diagnostics.hasErrors(), diagnostics::toString);
        assertTrue(diagnostics.asList().stream().noneMatch(diagnostic ->
                diagnostic.range().equals(getNodeRange)
        ), diagnostics::toString);
        assertTrue(diagnosticsByCategory(diagnostics, "sema.compile_check").isEmpty(), diagnostics::toString);
        assertTrue(diagnosticsByCategory(diagnostics, "sema.unsupported_expression_route").stream()
                        .anyMatch(diagnostic -> diagnostic.range().equals(lambdaRange)
                                && diagnostic.message().contains("Lambda expression")),
                diagnostics::toString);
    }

    @Test
    void analyzeForCompileKeepsUnrecordedParameterDefaultLambdaBlocked() throws Exception {
        var source = """
                class_name CompileCheckParamDefaultLambda
                extends Node
                
                func ping(cb = func():
                    $Camera3D
                ):
                    pass
                """;

        var compiled = analyzeForCompile("compile_check_param_default_lambda.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var unsupportedDefaultDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_binding_subtree"
        );
        var lambda = findNode(compiled.unit().ast(), LambdaExpression.class, ignored -> true);
        var lambdaRange = FrontendRange.fromAstRange(lambda.range());

        // The gate now considers the accepted-default surface, but this root was rejected by the
        // parameter-default island through the unrecorded-lambda fail-closed path: the single
        // `sema.unsupported_binding_subtree` already owns the lambda (default root) range, so the
        // root-level conflict check skips the walk entirely. The nested get-node is never scanned.
        assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        assertTrue(unsupportedDefaultDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.range().equals(lambdaRange)
                        && diagnostic.message().contains("lambda subtree")
        ), unsupportedDefaultDiagnostics::toString);
    }

    @Test
    void analyzeForCompileAcceptsSupportedParameterDefaults() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_defaults_accepted.gd", """
                class_name CompileCheckParamDefaultsAccepted
                extends Node
                
                var count = 3
                
                func make_default():
                    return 7
                
                func ping(seed = 1, at = Vector2(1, 2), copy = count, made = self.make_default()):
                    pass
                
                static func pick(tag = "x"):
                    return tag
                """);

        // Accepted defaults (literal, builtin constructor, instance member, self method call, and
        // a static-function literal) publish lowering-ready facts, so the gate's default-root walk
        // finds nothing to block.
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileBlocksValuePositionCoroutineCallInParameterDefault() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_default_coroutine.gd", """
                class_name CompileCheckParamDefaultCoroutine
                extends Node
                
                signal pinged
                
                func make_value():
                    await pinged
                    return 1
                
                func ping(seed = make_value()):
                    pass
                """);

        // The sweep accepts `make_value()` (the call itself resolves), but a coroutine call in a
        // default can never be awaited, so the value-position rule blocks it exactly once at the
        // call — a blocker only the compile gate can raise, with no upstream diagnostic involved.
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertTrue(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
        assertEquals(1, compileDiagnostics.size(), compileDiagnostics::toString);
        var defaultCall = findNode(
                compiled.unit().ast(),
                CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression callee && callee.name().equals("make_value")
        );
        assertEquals(
                FrontendRange.fromAstRange(defaultCall.range()),
                compileDiagnostics.getFirst().range(),
                compileDiagnostics::toString
        );
        assertTrue(compileDiagnostics.getFirst().message().contains("make_value"), compileDiagnostics::toString);
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.unsupported_parameter_default_expression").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileKeepsBlockedParameterReferenceDefaultUpstreamOwned() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_default_blocked_param.gd", """
                class_name CompileCheckParamDefaultBlockedParam
                extends RefCounted
                
                func ping(count, alias = count + 1):
                    return alias
                """);

        // The island stays silent on the inner BLOCKED identifier (`count`) and the owner anchors
        // the single rejection at the default root, so the gate must not walk the subtree and
        // re-report the silently blocked inner facts at ranges no upstream diagnostic owns.
        var ownerDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_parameter_default_expression"
        );
        assertEquals(1, ownerDiagnostics.size(), compiled.diagnostics()::toString);
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileKeepsStaticSelfDefaultUpstreamOwned() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_default_static_self.gd", """
                class_name CompileCheckParamDefaultStaticSelf
                extends RefCounted
                
                var hp = 1
                
                static func ping(copy = self.hp):
                    return copy
                """);

        // Same silent-BLOCKED shape through an attribute root: `self` is invisible in a static
        // function default and the only diagnostic is the owner's root-anchored rejection; the
        // gate must not surface inner `self`/step facts as extra compile_check diagnostics.
        var ownerDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_parameter_default_expression"
        );
        assertEquals(1, ownerDiagnostics.size(), compiled.diagnostics()::toString);
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

        @Test
    void analyzeForCompileKeepsAwaitWrappedFailureDefaultUpstreamOwned() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_default_await_wrap.gd", """
                class_name CompileCheckParamDefaultAwaitWrap
                extends Node
                
                func ping(value = await self.missing_member):
                    return value
                """);

        // The island rewrites the await root to BLOCKED while the genuine member failure owns the
        // inner step; the owner stays silent because an island error already exists. The gate must
        // recognize the subtree as upstream-owned and add no compile_check of its own.
        assertEquals(
                1,
                diagnosticsByCategory(compiled.diagnostics(), "sema.member_resolution").size(),
                compiled.diagnostics()::toString
        );
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileKeepsContainerWrappedFailureDefaultUpstreamOwned() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_default_array_wrap.gd", """
                class_name CompileCheckParamDefaultArrayWrap
                extends RefCounted
                
                func ping(values = [self.missing_member]):
                    return values
                """);

        // Same wrapper shape through an array literal: the element's FAILED member fact owns the
        // inner step, the propagated FAILED root carries no diagnostic of its own, and the gate
        // must not add a second blocker at the array root range.
        assertEquals(
                1,
                diagnosticsByCategory(compiled.diagnostics(), "sema.member_resolution").size(),
                compiled.diagnostics()::toString
        );
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileKeepsRejectedDefaultFailureFactsUpstreamOwned() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_default_rejected.gd", """
                class_name CompileCheckParamDefaultRejected
                extends RefCounted
                
                func ping(copy = self.missing_member):
                    return copy
                """);

        // The rejected default retains its FAILED facts, but the member-step failure already owns
        // a range inside the default subtree, so the gate treats the whole island as
        // upstream-owned and skips the walk instead of adding a second blocker.
        var memberDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.member_resolution");
        assertEquals(1, memberDiagnostics.size(), compiled.diagnostics()::toString);
        assertTrue(
                diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty(),
                compiled.diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileBlocksParameterizedConstructorDefaultViaDedicatedGuard() throws Exception {
        var compiled = analyzeForCompile("compile_check_param_default_ctor.gd", """
                class_name CompileCheckParamDefaultCtor
                extends RefCounted
                
                class Worker:
                    func _init(value: int):
                        pass
                
                func build(worker = Worker.new(1)):
                    pass
                """);

        // A parameterized GDCC constructor default fails upstream resolution and stays
        // fail-closed; the dedicated constructor regression guard (surface-independent, dedup
        // skipped) still raises its single compile_check at the call step, matching the
        // executable-body route behavior.
        var callDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.call_resolution");
        var typeCheckDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.type_check");
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertEquals(1, callDiagnostics.size(), compiled.diagnostics()::toString);
        assertEquals(1, typeCheckDiagnostics.size(), compiled.diagnostics()::toString);
        assertEquals(1, compileDiagnostics.size(), compiled.diagnostics()::toString);
        assertTrue(
                compileDiagnostics.getFirst().message().contains("supports only zero-argument custom object construction"),
                compileDiagnostics::toString
        );
    }

    @Test
    void analyzeForCompileSkipsConstructorParameterDefaults() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_init_default.gd", """
                class_name CompileCheckInitDefault
                extends RefCounted
                
                func _init(seed = 1):
                    pass
                """);
        var initConstructor = findNode(
                preparedInput.unit().ast(),
                ConstructorDeclaration.class,
                ignored -> true
        );
        var defaultLiteral = Objects.requireNonNull(initConstructor.parameters().getFirst().defaultValue());

        // `_init` defaults stay permanently unsupported: the sweep never analyzes them, so no
        // expression facts exist for the default root and the gate's accepted-default walk skips
        // it structurally; the existing rejection chain owns the diagnostics.
        assertNull(preparedInput.analysisData().expressionTypes().get(defaultLiteral));

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check");
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        assertFalse(
                diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.type_check").isEmpty(),
                preparedInput.analysisData().diagnostics()::toString
        );
    }

    @Test
    void analyzeForCompileLambdaBodyMatchFailsWithoutCompileCheckWrap() throws Exception {
        var source = """
                class_name CompileCheckLambdaBodyMatch
                extends Node
                
                func ping():
                    var x = 0
                    var f = func():
                        match x:
                            1:
                                pass
                """;

        var compiled = analyzeForCompile("compile_check_lambda_body_match.gd", source);
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        var unsupportedBindingDiagnostics = diagnosticsByCategory(
                compiled.diagnostics(),
                "sema.unsupported_binding_subtree"
        );

        // LITERAL is compile-ready, so a recorded-lambda body match is released.
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
        assertTrue(unsupportedBindingDiagnostics.isEmpty(), unsupportedBindingDiagnostics::toString);
        assertFalse(compiled.diagnostics().hasErrors(), compiled.diagnostics()::toString);
    }

    @Test
    void analyzeForCompileDoesNotReWrapUpstreamErrorOnMatchRouteNotReady() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_match_upstream_error.gd", """
                class_name CompileCheckMatchUpstreamError
                extends Node
                
                func ping(value):
                    match value:
                        [1]:
                            pass
                """);
        var matchStatement = findNode(preparedInput.unit().ast(), MatchStatement.class, ignored -> true);
        preparedInput.diagnosticManager().error(
                "sema.synthetic",
                "synthetic upstream error owning the match statement anchor",
                preparedInput.unit().path(),
                FrontendRange.fromAstRange(matchStatement.range())
        );
        preparedInput.analysisData().updateDiagnostics(preparedInput.diagnosticManager().snapshot());

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.compile_check").isEmpty());
        assertEquals(1, diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.synthetic").size());
    }

    @Test
    void analyzeForCompileKeepsMatchWithoutPublishedPlanFailClosedWithoutCompileCheck() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_match_missing_plan.gd", """
                class_name CompileCheckMatchMissingPlan
                extends Node
                
                func ping(value):
                    match value:
                        1:
                            pass
                """);
        var matchStatement = findNode(preparedInput.unit().ast(), MatchStatement.class, ignored -> true);
        preparedInput.analysisData().matchPlans().remove(matchStatement);

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileUpgradesMissingBindSlotTypeWhenPublicationWarningExists() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_match_bind_slot_hole.gd", """
                class_name CompileCheckMatchBindSlotHole
                extends Node
                
                func ping(value: int):
                    match value:
                        var bound:
                            pass
                """);
        var bind = findNode(preparedInput.unit().ast(), PatternBindingExpression.class, ignored -> true);
        preparedInput.analysisData().slotTypes().remove(bind);
        preparedInput.diagnosticManager().warning(
                "sema.variable_slot_publication",
                "synthetic bind slot publication warning",
                preparedInput.unit().path(),
                FrontendRange.fromAstRange(bind.range())
        );
        preparedInput.analysisData().updateDiagnostics(preparedInput.diagnosticManager().snapshot());

        runCompileCheck(preparedInput);

        var compileDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.compile_check"
        );
        assertEquals(1, compileDiagnostics.size(), compileDiagnostics::toString);
        assertEquals(FrontendRange.fromAstRange(bind.range()), compileDiagnostics.getFirst().range());
    }

    @Test
    void analyzeForCompileDoesNotUpgradeMissingBindSlotTypeWithoutPublicationWarning() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_match_bind_slot_protocol.gd", """
                class_name CompileCheckMatchBindSlotProtocol
                extends Node
                
                func ping(value: int):
                    match value:
                        var bound:
                            pass
                """);
        var bind = findNode(preparedInput.unit().ast(), PatternBindingExpression.class, ignored -> true);
        preparedInput.analysisData().slotTypes().remove(bind);

        runCompileCheck(preparedInput);

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileLeavesLegalBareMethodAndUtilityCallsOutOfSignalValueBlockers() throws Exception {
        var source = """
                class_name CompileCheckSignalCalleeExclusion
                extends RefCounted
                
                func helper(value):
                    return value
                
                static func make_static(value):
                    return value
                
                func ping(left, right):
                    var both := left and helper(right)
                    make_static(right)
                    print(right)
                    return left or right
                """;

        var sharedAnalyzed = analyzeShared("compile_check_signal_callee_exclusion.gd", source);
        assertFalse(sharedAnalyzed.diagnostics().hasErrors(), () -> sharedAnalyzed.diagnostics().asList().toString());
        assertTrue(diagnosticsByCategory(sharedAnalyzed.diagnostics(), "sema.compile_check").isEmpty());

        var compiled = analyzeForCompile("compile_check_signal_callee_exclusion.gd", source);
        assertFalse(compiled.diagnostics().hasErrors(), () -> compiled.diagnostics().asList().toString());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileDoesNotGuessSignalOrCallableLocalsByType() throws Exception {
        var source = """
                class_name CompileCheckSignalTypeGuessing
                extends RefCounted
                
                func ping(sig: Signal, cb: Callable):
                    var copied_signal: Signal = sig
                    var copied_callable: Callable = cb
                    return copied_callable.is_null()
                """;

        var compiled = analyzeForCompile("compile_check_signal_type_guessing.gd", source);
        assertFalse(compiled.diagnostics().hasErrors(), () -> compiled.diagnostics().asList().toString());
        assertTrue(diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeForCompileSkipsBareSignalValueReferencesOutsideCompileSurface() throws Exception {
        var source = """
                class_name CompileCheckSignalSkippedSurface
                extends Node
                
                signal pinged
                
                func _handler():
                    pass
                
                func ping(seed = pinged, handler = _handler):
                    var f = func():
                        var hidden_signal = pinged
                        var hidden_method = _handler
                    match 0:
                        [1]:
                            var hidden_match_signal = pinged
                """;

        var compiled = analyzeForCompile("compile_check_signal_skipped_surface.gd", source);
        // The recorded lambda body is released onto the compile surface, and its bare signal /
        // self-method value reads are already compile-ready. The ARRAY match is compile-ready too,
        // so its rescanned body adds no blocker either.
        var compileDiagnostics = diagnosticsByCategory(compiled.diagnostics(), "sema.compile_check");
        assertEquals(0, compileDiagnostics.size(), () -> compiled.diagnostics().asList().toString());
    }

    @Test
    void analyzeSkipsDynamicSignalMemberAndSignalMethodFacts() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_dynamic_signal_facts.gd", """
                class_name CompileCheckDynamicSignalFacts
                extends Node
                
                signal pinged
                
                func ping(other: CompileCheckDynamicSignalFacts):
                    var receiver_signal = other.pinged
                    other.pinged.emit()
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var pingedStep = findNamedPropertyStep(pingFunction, "receiver_signal", "pinged");
        var emitStep = findNamedCallStep(pingFunction, "emit");
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(pingedStep));
        var originalCall = Objects.requireNonNull(preparedInput.analysisData().resolvedCalls().get(emitStep));
        var dynamicSignalMember = FrontendResolvedMember.dynamic(
                originalMember.memberName(),
                FrontendBindingKind.SIGNAL,
                originalMember.receiverKind(),
                originalMember.ownerKind(),
                originalMember.receiverType(),
                originalMember.declarationSite(),
                "synthetic dynamic signal member"
        );

        for (var entry : List.copyOf(preparedInput.analysisData().resolvedMembers().entrySet())) {
            if (entry.getValue().bindingKind() == FrontendBindingKind.SIGNAL) {
                preparedInput.analysisData().resolvedMembers().put(entry.getKey(), dynamicSignalMember);
            }
        }
        preparedInput.analysisData().resolvedCalls().put(
                emitStep,
                FrontendResolvedCall.dynamic(
                        originalCall.callableName(),
                        originalCall.receiverKind(),
                        originalCall.ownerKind(),
                        new GdSignalType(),
                        originalCall.argumentTypes(),
                        originalCall.declarationSite(),
                        "synthetic dynamic signal call"
                )
        );
        preparedInput.analysisData().updateDiagnostics(new DiagnosticSnapshot(List.of()));
        var cleanDiagnosticManager = new DiagnosticManager();

        runCompileCheck(new PreparedCompileCheckInput(
                preparedInput.unit(),
                preparedInput.analysisData(),
                cleanDiagnosticManager
        ));

        assertTrue(diagnosticsByCategory(preparedInput.analysisData().diagnostics(), "sema.compile_check").isEmpty());
    }

    @Test
    void analyzeFailsFastWhenSubscriptStepMemberFactIsNotResolvedProperty() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_subscript_member_fact_guard.gd", """
                class_name CompileCheckSubscriptMemberFactGuard
                extends RefCounted
                
                var items: Array[int] = []
                
                func ping() -> void:
                    self.items[0] = 1
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var subscriptStep = findNode(pingFunction, AttributeSubscriptStep.class, step -> step.name().equals("items"));
        var originalMember = Objects.requireNonNull(preparedInput.analysisData().resolvedMembers().get(subscriptStep));
        preparedInput.analysisData().resolvedMembers().put(
                subscriptStep,
                FrontendResolvedMember.dynamic(
                        originalMember.memberName(),
                        originalMember.bindingKind(),
                        originalMember.receiverKind(),
                        originalMember.ownerKind(),
                        originalMember.receiverType(),
                        originalMember.declarationSite(),
                        "synthetic dynamic container member"
                )
        );

        var exception = assertThrows(
                IllegalStateException.class,
                () -> runCompileCheck(new PreparedCompileCheckInput(
                        preparedInput.unit(),
                        preparedInput.analysisData(),
                        new DiagnosticManager()
                ))
        );

        assertTrue(exception.getMessage().contains("RESOLVED container property provenance"));
    }

    @Test
    void analyzeReleasesResolvedSignalConnectCallRegression() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_signal_resolved_regression.gd", """
                class_name CompileCheckSignalResolvedRegression
                extends Node
                
                signal pinged
                
                func ping(other: CompileCheckSignalResolvedRegression, handler: Callable):
                    var receiver_signal = other.pinged
                    other.pinged.connect(handler)
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var pingedStep = findNamedPropertyStep(pingFunction, "receiver_signal", "pinged");
        var connectStep = findNamedCallStep(pingFunction, "connect");
        preparedInput.analysisData().expressionTypes().clear();
        preparedInput.analysisData().resolvedMembers().clear();
        preparedInput.analysisData().resolvedCalls().clear();
        preparedInput.analysisData().symbolBindings().clear();
        preparedInput.analysisData().resolvedMembers().put(
                pingedStep,
                FrontendResolvedMember.resolved(
                        "pinged",
                        FrontendBindingKind.SIGNAL,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.GDCC,
                        new GdObjectType("CompileCheckSignalResolvedRegression"),
                        new GdSignalType(),
                        new Object()
                )
        );
        preparedInput.analysisData().resolvedCalls().put(
                connectStep,
                FrontendResolvedCall.resolved(
                        "connect",
                        FrontendCallResolutionKind.INSTANCE_METHOD,
                        FrontendReceiverKind.INSTANCE,
                        ScopeOwnerKind.BUILTIN,
                        new GdSignalType(),
                        GdVoidType.VOID,
                        List.of(),
                        new Object()
                )
        );
        preparedInput.analysisData().updateDiagnostics(new DiagnosticSnapshot(List.of()));
        var cleanDiagnosticManager = new DiagnosticManager();

        runCompileCheck(new PreparedCompileCheckInput(
                preparedInput.unit(),
                preparedInput.analysisData(),
                cleanDiagnosticManager
        ));

        var compileDiagnostics = diagnosticsByCategory(
                preparedInput.analysisData().diagnostics(),
                "sema.compile_check"
        );
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeReleasesBareSignalValueBindingRegression() throws Exception {
        var preparedInput = prepareCompileCheckInput("compile_check_bare_signal_binding_regression.gd", """
                class_name CompileCheckBareSignalBindingRegression
                extends Node
                
                signal pinged
                
                func ping():
                    var copied = pinged
                """);
        var pingFunction = findFunction(preparedInput.unit().ast().statements(), "ping");
        var pingedIdentifier = findIdentifier(pingFunction, "copied", "pinged");
        preparedInput.analysisData().expressionTypes().clear();
        preparedInput.analysisData().resolvedMembers().clear();
        preparedInput.analysisData().resolvedCalls().clear();
        preparedInput.analysisData().symbolBindings().clear();
        preparedInput.analysisData().symbolBindings().put(
                pingedIdentifier,
                new FrontendBinding("pinged", FrontendBindingKind.SIGNAL, new Object())
        );
        preparedInput.analysisData().updateDiagnostics(new DiagnosticSnapshot(List.of()));
        var cleanDiagnosticManager = new DiagnosticManager();

        runCompileCheck(new PreparedCompileCheckInput(
                preparedInput.unit(),
                preparedInput.analysisData(),
                cleanDiagnosticManager
        ));

        var compileDiagnostics = diagnosticsByCategory(
                preparedInput.analysisData().diagnostics(),
                "sema.compile_check"
        );
        assertTrue(compileDiagnostics.isEmpty(), compileDiagnostics::toString);
    }

    @Test
    void analyzeForCompileKeepsUnresolvedBareIdentifierAsUpstreamBindingErrorBlockingLowering() throws Exception {
        var analyzed = analyzeForCompile(
                "compile_check_unknown_bare_identifier.gd",
                """
                        class_name CompileCheckUnknownBareIdentifier
                        extends RefCounted
                        
                        func ping() -> int:
                            return TYPE_WHATEVER
                        """
        );

        var bindingErrors = diagnosticsByCategory(analyzed.diagnostics(), "sema.binding");
        var compileErrors = diagnosticsByCategory(analyzed.diagnostics(), "sema.compile_check");
        assertAll(
                () -> assertEquals(1, bindingErrors.size()),
                () -> assertTrue(bindingErrors.getFirst().message().contains("Unable to resolve value binding 'TYPE_WHATEVER'")),
                // The upstream binding error already owns the identifier range, so the gate must
                // not add a second compile_check for the same root cause.
                () -> assertTrue(compileErrors.isEmpty(), compileErrors::toString),
                // The compile-only lowering precondition is `analyzeForCompile(...)` plus no
                // errors; the binding error therefore keeps this module out of lowering.
                () -> assertTrue(analyzed.diagnosticManager().hasErrors())
        );
    }

    private static @NotNull AnalyzedScript analyzeShared(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyzeShared(fileName, source, Map.of());
    }

    private static @NotNull AnalyzedScript analyzeShared(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnosticManager = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnosticManager
        );
        return new AnalyzedScript(unit, analysisData.diagnostics(), diagnosticManager);
    }

    private static @NotNull AnalyzedScript analyzeForCompile(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyzeForCompile(fileName, source, Map.of());
    }

    private static @NotNull AnalyzedScript analyzeForCompile(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull Map<String, String> topLevelCanonicalNameMap
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var parseDiagnostics = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, parseDiagnostics);
        assertTrue(parseDiagnostics.isEmpty(), () -> "Unexpected parse diagnostics: " + parseDiagnostics.snapshot());

        var diagnosticManager = new DiagnosticManager();
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit), topLevelCanonicalNameMap),
                new ClassRegistry(ExtensionApiLoader.loadDefault()),
                diagnosticManager
        );
        return new AnalyzedScript(unit, analysisData.diagnostics(), diagnosticManager);
    }

    private static @NotNull PreparedCompileCheckInput prepareCompileCheckInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = FrontendAnalysisData.bootstrap();
        var moduleSkeleton = new FrontendClassSkeletonBuilder().build(
                new FrontendModule("test_module", List.of(unit)),
                classRegistry,
                diagnosticManager,
                analysisData
        );
        analysisData.updateModuleSkeleton(moduleSkeleton);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendScopeAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendVariableAnalyzer().analyze(analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        FrontendSuiteResolverStageTestSupport.resolveAllOwners(classRegistry, analysisData, diagnosticManager);
        new FrontendAnnotationUsageAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        new FrontendTypeCheckAnalyzer().analyze(classRegistry, analysisData, diagnosticManager);
        analysisData.updateDiagnostics(diagnosticManager.snapshot());
        return new PreparedCompileCheckInput(unit, analysisData, diagnosticManager);
    }

    private static void runCompileCheck(@NotNull PreparedCompileCheckInput preparedInput) {
        Objects.requireNonNull(preparedInput, "preparedInput must not be null");
        new FrontendCompileCheckAnalyzer().analyze(
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );
        preparedInput.analysisData().updateDiagnostics(preparedInput.diagnosticManager().snapshot());
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull FunctionDeclaration findFunction(
            @NotNull List<Statement> statements,
            @NotNull String name
    ) {
        for (var statement : Objects.requireNonNull(statements, "statements must not be null")) {
            if (statement instanceof FunctionDeclaration functionDeclaration
                    && functionDeclaration.name().equals(Objects.requireNonNull(name, "name must not be null"))) {
                return functionDeclaration;
            }
        }
        throw new AssertionError("Function not found: " + name);
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<Statement> statements,
            @NotNull String name
    ) {
        for (var statement : Objects.requireNonNull(statements, "statements must not be null")) {
            if (statement instanceof VariableDeclaration variableDeclaration
                    && variableDeclaration.name().equals(Objects.requireNonNull(name, "name must not be null"))) {
                return variableDeclaration;
            }
        }
        throw new AssertionError("Variable not found: " + name);
    }

    private static @NotNull IdentifierExpression findIdentifier(
            @NotNull FunctionDeclaration function,
            @NotNull String variableName,
            @NotNull String identifierName
    ) {
        var declaration = findVariable(function.body().statements(), variableName);
        var identifier = assertInstanceOf(
                IdentifierExpression.class,
                Objects.requireNonNull(declaration.value(), "variable value must not be null")
        );
        assertEquals(identifierName, identifier.name());
        return identifier;
    }

    private static @NotNull AttributePropertyStep findNamedPropertyStep(
            @NotNull FunctionDeclaration function,
            @NotNull String variableName,
            @NotNull String memberName
    ) {
        var declaration = findVariable(function.body().statements(), variableName);
        return findNode(
                Objects.requireNonNull(declaration.value(), "variable value must not be null"),
                AttributePropertyStep.class,
                step -> step.name().equals(memberName)
        );
    }

    private static @NotNull AttributeCallStep findNamedCallStep(
            @NotNull FunctionDeclaration function,
            @NotNull String methodName
    ) {
        return findNode(function, AttributeCallStep.class, step -> step.name().equals(methodName));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                return candidate;
            }
        }
        for (var child : root.getChildren()) {
            try {
                return findNode(child, nodeType, predicate);
            } catch (AssertionError ignored) {
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private record AnalyzedScript(
            @NotNull FrontendSourceUnit unit,
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        private AnalyzedScript {
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(diagnostics, "diagnostics must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        }
    }

    private record PreparedCompileCheckInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        private PreparedCompileCheckInput {
            Objects.requireNonNull(unit, "unit must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");
        }
    }
}
