package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.DiagnosticSnapshot;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnostic;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.FrontendSourceUnit;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendClassSkeletonBuilder;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendForIterationRoute;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendTypeCheckAnalyzerTest {
    @Test
    void analyzeRejectsMissingModuleSkeletonBoundary() throws Exception {
        var analyzer = new FrontendTypeCheckAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(classRegistry, analysisData, new DiagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("moduleSkeleton"));
    }

    @Test
    void analyzeRejectsMissingDiagnosticsBoundary() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_diagnostics.gd", """
                class_name MissingTypeCheckDiagnostics
                extends Node
                
                func ping() -> int:
                    return 1
                """);
        var analyzer = new FrontendTypeCheckAnalyzer();
        var analysisData = FrontendAnalysisData.bootstrap();
        analysisData.updateModuleSkeleton(preparedInput.analysisData().moduleSkeleton());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(preparedInput.classRegistry(), analysisData, preparedInput.diagnosticManager())
        );

        assertTrue(thrown.getMessage().contains("diagnostics"));
    }

    @Test
    void analyzeRejectsMissingPublishedSourceScope() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_scope.gd", """
                class_name MissingTypeCheckScope
                extends Node
                
                func ping() -> int:
                    return 1
                """);
        var analyzer = new FrontendTypeCheckAnalyzer();
        preparedInput.analysisData().scopesByAst().remove(preparedInput.unit().ast());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> analyzer.analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains(preparedInput.unit().path().toString()));
    }

    @Test
    void analyzeRejectsMissingPublishedLocalInitializerExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_local_initializer_type.gd", """
                class_name MissingTypeCheckLocalInitializerType
                extends RefCounted
                
                func ping():
                    var local: int = 1
                """);
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var localDeclaration = findVariable(pingFunction.body().statements(), "local");
        preparedInput.analysisData().expressionTypes().remove(localDeclaration.value());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("Local initializer for 'local'"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeRejectsMissingPublishedPropertyInitializerExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_property_initializer_type.gd", """
                class_name MissingTypeCheckPropertyInitializerType
                extends RefCounted
                
                var field: int = 1
                """);
        var fieldDeclaration = findVariable(preparedInput.unit().ast(), "field");
        preparedInput.analysisData().expressionTypes().remove(fieldDeclaration.value());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("Property initializer for 'field'"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeRejectsMissingPublishedReturnExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_return_value_type.gd", """
                class_name MissingTypeCheckReturnValueType
                extends RefCounted
                
                func ping() -> int:
                    return 1
                """);
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var returnStatement = findNode(
                pingFunction,
                dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                ignored -> true
        );
        preparedInput.analysisData().expressionTypes().remove(returnStatement.value());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("Return value for Callable on class 'MissingTypeCheckReturnValueType'"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeRejectsMissingPublishedConditionExpressionType() throws Exception {
        var preparedInput = prepareTypeCheckInput("missing_type_check_condition_type.gd", """
                class_name MissingTypeCheckConditionType
                extends RefCounted
                
                func ping():
                    if true:
                        pass
                """);
        var ifStatement = findNode(
                preparedInput.unit().ast(),
                dev.superice.gdparser.frontend.ast.IfStatement.class,
                ignored -> true
        );
        preparedInput.analysisData().expressionTypes().remove(ifStatement.condition());

        var thrown = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(thrown.getMessage().contains("IfStatement condition"));
        assertTrue(thrown.getMessage().contains("expression type has not been published"));
    }

    @Test
    void analyzeWalksTypedRootsWithExpectedContextWithoutMutatingPublishedFacts() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_context_probe.gd", """
                class_name TypeCheckContextProbe
                extends RefCounted
                
                var instance_field: int = 1
                static var static_field: int = 2
                
                class Inner:
                    static var inner_field: int = 3
                
                func _init():
                    return
                
                static func read(flag) -> int:
                    var local: int = 1
                    assert(flag, "still typed as a regular expression")
                    if flag:
                        return local
                    while flag:
                        return 2
                    return 3
                """);
        var analyzer = new RecordingTypeCheckAnalyzer();
        var symbolBindings = preparedInput.analysisData().symbolBindings();
        var resolvedMembers = preparedInput.analysisData().resolvedMembers();
        var resolvedCalls = preparedInput.analysisData().resolvedCalls();
        var expressionTypes = preparedInput.analysisData().expressionTypes();
        var symbolBindingCount = symbolBindings.size();
        var resolvedMemberCount = resolvedMembers.size();
        var resolvedCallCount = resolvedCalls.size();
        var expressionTypeCount = expressionTypes.size();
        var diagnosticsBefore = preparedInput.diagnosticManager().snapshot();

        analyzer.analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertSame(symbolBindings, preparedInput.analysisData().symbolBindings());
        assertSame(resolvedMembers, preparedInput.analysisData().resolvedMembers());
        assertSame(resolvedCalls, preparedInput.analysisData().resolvedCalls());
        assertSame(expressionTypes, preparedInput.analysisData().expressionTypes());
        assertEquals(symbolBindingCount, preparedInput.analysisData().symbolBindings().size());
        assertEquals(resolvedMemberCount, preparedInput.analysisData().resolvedMembers().size());
        assertEquals(resolvedCallCount, preparedInput.analysisData().resolvedCalls().size());
        assertEquals(expressionTypeCount, preparedInput.analysisData().expressionTypes().size());
        assertEquals(diagnosticsBefore, preparedInput.diagnosticManager().snapshot());

        assertEquals(11, analyzer.events().size());

        assertEvent(
                analyzer.events().get(0),
                "property",
                "instance_field",
                "TypeCheckContextProbe",
                null,
                ResolveRestriction.instanceContext(),
                false,
                0,
                "instance_field"
        );
        assertEvent(
                analyzer.events().get(1),
                "property",
                "static_field",
                "TypeCheckContextProbe",
                null,
                ResolveRestriction.staticContext(),
                true,
                0,
                "static_field"
        );
        assertEvent(
                analyzer.events().get(2),
                "property",
                "inner_field",
                "TypeCheckContextProbe__sub__Inner",
                null,
                ResolveRestriction.staticContext(),
                true,
                0,
                "inner_field"
        );
        assertEvent(
                analyzer.events().get(3),
                "return",
                "bare",
                "TypeCheckContextProbe",
                "void",
                ResolveRestriction.instanceContext(),
                false,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(4),
                "local",
                "local",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(5),
                "condition",
                "AssertStatement",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(6),
                "condition",
                "IfStatement",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(7),
                "return",
                "valued",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                2,
                null
        );
        assertEvent(
                analyzer.events().get(8),
                "condition",
                "WhileStatement",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
        assertEvent(
                analyzer.events().get(9),
                "return",
                "valued",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                2,
                null
        );
        assertEvent(
                analyzer.events().get(10),
                "return",
                "valued",
                "TypeCheckContextProbe",
                "int",
                ResolveRestriction.staticContext(),
                true,
                1,
                null
        );
    }

    @Test
    void analyzeReportsParameterizedGdccConstructorDeclarationAsSemanticError() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_parameterized_gdcc_constructor.gd", """
                class_name TypeCheckParameterizedCtor
                extends RefCounted
                
                class Worker:
                    func _init(value: int):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );

        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("supports only zero parameters"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("Worker._init(...)"));
        assertEquals(
                FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_parameterized_gdcc_constructor.gd")),
                typeCheckDiagnostics.getFirst().sourcePath()
        );
        assertNotNull(typeCheckDiagnostics.getFirst().range());
    }

    @Test
    void analyzeDoesNotReportZeroArgGdccConstructorDeclaration() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_zero_arg_gdcc_constructor.gd", """
                class_name TypeCheckZeroArgCtor
                extends RefCounted
                
                class Worker:
                    func _init():
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        ).isEmpty());
    }

    @Test
    void analyzeChecksOnlyExplicitOrdinaryLocalDeclaredSlotsAndSkipsUnstableInitializerFacts() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_local_compatibility.gd", """
                class_name TypeCheckLocalCompatibility
                extends RefCounted
                
                class Worker:
                    pass
                
                var payload: int = 1
                
                static func ping(worker):
                    var accepts_variant: Variant = 1
                    var exact_variant_source: int = accepts_variant
                    var accepted_float: float = 1
                    var strict_int: int = 1.0
                    var dynamic_variant: Variant = worker.ping().length
                    var dynamic_int: int = worker.ping().length
                    var inferred := 1
                    var skipped_blocked: int = self.payload
                    var skipped_deferred: int = 1 + 2
                    var skipped_failed: int = Worker
                    var skipped_unsupported: int = Worker.VALUE
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "exact_variant_source", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "accepted_float", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                requireInitializerType(pingFunction.body().statements(), "dynamic_variant", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                requireInitializerType(pingFunction.body().statements(), "dynamic_int", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.BLOCKED,
                requireInitializerType(pingFunction.body().statements(), "skipped_blocked", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "skipped_deferred", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                requireInitializerType(pingFunction.body().statements(), "skipped_failed", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.UNSUPPORTED,
                requireInitializerType(pingFunction.body().statements(), "skipped_unsupported", preparedInput).status()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size());
        var typeCheckDiagnostic = typeCheckDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, typeCheckDiagnostic.severity());
        assertTrue(typeCheckDiagnostic.message().contains("strict_int"));
        assertTrue(typeCheckDiagnostic.message().contains("float"));
        assertTrue(typeCheckDiagnostic.message().contains("int"));
        assertEquals(
                FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_local_compatibility.gd")),
                typeCheckDiagnostic.sourcePath()
        );
        assertNotNull(typeCheckDiagnostic.range());

        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_hint"
        ).isEmpty());
    }

    @Test
    void analyzeChecksLambdaInitializersAgainstDeclaredSlots() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_lambda_assignment.gd", """
                class_name TypeCheckLambdaAssignment
                extends RefCounted
                
                func ping():
                    var cb: Callable = func(): pass
                    var bad: int = func(): pass
                    var inferred := func(): pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var callableInitializer = requireInitializerType(pingFunction.body().statements(), "cb", preparedInput);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, callableInitializer.status());
        assertEquals("Callable", callableInitializer.publishedType().getTypeName());
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "bad", preparedInput).status()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        // The `Callable` slot accepts the lambda through the ordinary boundary and the `:=` slot
        // packs into Variant, so only the `: int` slot reports the existing mismatch diagnostic.
        assertEquals(1, typeCheckDiagnostics.size());
        var mismatch = typeCheckDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, mismatch.severity());
        assertTrue(mismatch.message().contains("bad"));
        assertTrue(mismatch.message().contains("Callable"));
        assertTrue(mismatch.message().contains("int"));
    }

    @Test
    void analyzeWalksRecordedLambdaBodiesWithInheritedCallableContext() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_lambda_body_walk.gd", """
                class_name TypeCheckLambdaBodyWalk
                extends RefCounted
                
                func ping():
                    var cb := func():
                        var bad_local: int = "text"
                        var nested := func():
                            var inner_bad: int = 1.5
                            return inner_bad
                        return nested
                    assert(true, func():
                        var assert_bad: int = "msg"
                        return "")
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        // Both the outer and the nested lambda body are walked as independent callable islands,
        // including the lambda carried by the assert-message position. The lambda root itself is
        // `Callable`, which violates the current String-assignable assert-message contract and reports
        // its own diagnostic on top of the three lambda-body mismatches.
        assertEquals(4, typeCheckDiagnostics.size(), typeCheckDiagnostics::toString);
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("bad_local") && diagnostic.message().contains("String")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("inner_bad") && diagnostic.message().contains("float")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("assert_bad") && diagnostic.message().contains("String")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Assert message type 'Callable' is not assignable to 'String'")
        ));
    }

    @Test
    void analyzeChecksLambdaReturnAgainstPublishedDeclaredReturnType() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_lambda_return_slot.gd", """
                class_name TypeCheckLambdaReturnSlot
                extends RefCounted
                
                func ping():
                    var cb := func() -> int:
                        return "text"
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        // The lambda return slot is the plan-published declared type, so a mismatching
        // return value gets the ordinary return diagnostic instead of a Variant pass-through.
        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size(), typeCheckDiagnostics::toString);
        assertTrue(typeCheckDiagnostics.getFirst().message()
                .contains("Return value type 'String' is not assignable to callable return slot type 'int'"));
    }

    @Test
    void analyzeRejectsBareReturnInLambdaWithDeclaredReturnType() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_lambda_bare_return.gd", """
                class_name TypeCheckLambdaBareReturn
                extends RefCounted
                
                func ping():
                    var cb := func() -> int:
                        return
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size(), typeCheckDiagnostics::toString);
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("Bare 'return' is only allowed"));
    }

    @Test
    void analyzeAcceptsLambdaReturnMatchingDeclaredReturnType() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_lambda_return_ok.gd", """
                class_name TypeCheckLambdaReturnOk
                extends RefCounted
                
                func ping():
                    var cb := func() -> int:
                        return 1
                    var plain := func():
                        return
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        ).isEmpty());
    }

    @Test
    void analyzeKeepsUnrecordedPropertyInitializerLambdaBodiesOutsideTypeCheckSurface() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_lambda_unwalked.gd", """
                class_name TypeCheckLambdaUnwalked
                extends RefCounted
                
                var prop = func():
                    var hidden_bad: int = "text"
                
                func ping():
                    pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        // Property-initializer lambdas stay unrecorded, so their bodies have no published facts
        // and type-check must not descend into them. Match-section lambdas are now recorded.
        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        ).isEmpty());
    }

    @Test
    void analyzeWalksRecordedMatchSectionLambdaBodies() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_lambda_in_match.gd", """
                class_name TypeCheckLambdaInMatch
                extends RefCounted
                
                func ping(choice):
                    match choice:
                        0:
                            var in_match = func():
                                var match_bad: int = "text"
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertEquals(1, diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        ).size());
    }

    @Test
    void analyzeReportsHardInvalidCastInMatchSubjectAndExpressionPattern() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_match_cast.gd", """
                class_name TypeCheckMatchCast
                extends RefCounted
                
                func ping(value: int, other: int):
                    match value as Node:
                        1:
                            pass
                    match other:
                        other as Node:
                            pass
                        2:
                            var later := other
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(2, diagnostics.size(), diagnostics::toString);
        assertTrue(diagnostics.stream().allMatch(diagnostic -> diagnostic.message().contains("Invalid cast")));
        var later = findNode(
                findFunction(preparedInput.unit().ast(), "ping"),
                VariableDeclaration.class,
                declaration -> declaration.name().equals("later")
        );
        assertNotNull(preparedInput.analysisData().slotTypes().get(later));
    }

    @Test
    void analyzeChecksPropertyInitializersAgainstPublishedSkeletonSlotsAndWarnsForMissingExplicitTypes()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_property_compatibility.gd", """
                class_name TypeCheckPropertyCompatibility
                extends RefCounted
                
                class Worker:
                    static func make():
                        return "value"
                
                    static func make_count():
                        return 1
                
                var accepts_variant: Variant = 1
                var accepts_variant_source: int = Worker.make_count()
                var wrong_type: int = "x"
                var inferred_int := 1
                var missing_type = 1
                var inferred_dynamic := Worker.make().length
                var skipped_blocked := self.payload
                var skipped_deferred: int = 1 + 2
                var skipped_failed: int = Worker
                var skipped_failed_hint = Worker
                var skipped_deferred_hint := 1 + 2
                var skipped_unsupported: int = Worker.VALUE
                static var blocked_field: int = self.payload
                var payload: int = 1
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "accepts_variant_source", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.BLOCKED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_blocked", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_deferred", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_failed", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_failed_hint", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_deferred_hint", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.UNSUPPORTED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_unsupported", preparedInput).status()
        );

        var diagnostics = preparedInput.diagnosticManager().snapshot();
        var typeCheckDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_check");
        assertEquals(1, typeCheckDiagnostics.size());
        var typeCheckDiagnostic = typeCheckDiagnostics.getFirst();
        assertEquals(FrontendDiagnosticSeverity.ERROR, typeCheckDiagnostic.severity());
        assertTrue(typeCheckDiagnostic.message().contains("wrong_type"));
        assertTrue(typeCheckDiagnostic.message().contains("String"));
        assertTrue(typeCheckDiagnostic.message().contains("int"));
        assertEquals(
                FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_property_compatibility.gd")),
                typeCheckDiagnostic.sourcePath()
        );
        assertNotNull(typeCheckDiagnostic.range());

        var typeHintDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_hint");
        assertEquals(4, typeHintDiagnostics.size());
        assertTrue(typeHintDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.WARNING
                        && Objects.equals(
                        FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_property_compatibility.gd")),
                        diagnostic.sourcePath()
                )
                        && diagnostic.range() != null
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("inferred_int")
                        && diagnostic.message().contains("':='")
                        && diagnostic.message().contains(": int")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("missing_type")
                        && diagnostic.message().contains("no explicit type")
                        && diagnostic.message().contains(": int")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("inferred_dynamic")
                        && diagnostic.message().contains("':='")
                        && diagnostic.message().contains(": Variant")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("skipped_deferred_hint")
                        && diagnostic.message().contains("':='")
                        && diagnostic.message().contains(": int")
        ));

        var topLevelClass = preparedInput.analysisData().moduleSkeleton().sourceClassRelations().getFirst().classDef();
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "inferred_int").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "missing_type").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "inferred_dynamic").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "skipped_blocked").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "skipped_failed_hint").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "skipped_deferred_hint").getType());
    }

    /// Static `:=` follows the instance-property contract exactly: no inference, `Variant`
    /// metadata, and a `sema.type_hint` warning asking for an explicit type. An explicitly typed
    /// static property produces no hint.
    @Test
    void analyzeWarnsForStaticPropertyInferredTypeHintsWithoutRewritingMetadata() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_static_property_hint.gd", """
                class_name TypeCheckStaticPropertyHint
                extends RefCounted
                
                static var inferred_static := 1
                static var missing_static = 2
                static var typed_static: int = 3
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = preparedInput.diagnosticManager().snapshot();
        var typeHintDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_hint");
        assertEquals(2, typeHintDiagnostics.size());
        assertTrue(typeHintDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.WARNING && diagnostic.range() != null
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("inferred_static")
                        && diagnostic.message().contains("':='")
                        && diagnostic.message().contains(": int")
        ));
        assertTrue(typeHintDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("missing_static")
                        && diagnostic.message().contains("no explicit type")
                        && diagnostic.message().contains(": int")
        ));
        assertTrue(typeHintDiagnostics.stream().noneMatch(diagnostic ->
                diagnostic.message().contains("typed_static")
        ));

        var topLevelClass = preparedInput.analysisData().moduleSkeleton().sourceClassRelations().getFirst().classDef();
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "inferred_static").getType());
        assertEquals(GdVariantType.VARIANT, findPropertyDef(topLevelClass, "missing_static").getType());
        assertTrue(findPropertyDef(topLevelClass, "inferred_static").isStatic());
        assertTrue(findPropertyDef(topLevelClass, "missing_static").isStatic());
    }

    @Test
    void analyzeAcceptsOnlySameDimensionVectoriToVectorInitializerBoundaries() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_vector_initializer_compatibility.gd", """
                class_name TypeCheckVectorInitializerCompatibility
                extends RefCounted
                
                var accepted_property: Vector3 = Vector3i(1, 2, 3)
                var rejected_property_reverse: Vector3i = Vector3(1.0, 2.0, 3.0)
                var rejected_property_dimension: Vector2 = Vector3i(1, 2, 3)
                
                func ping() -> void:
                    var accepted_local: Vector3 = Vector3i(4, 5, 6)
                    var rejected_local_reverse: Vector3i = Vector3(4.0, 5.0, 6.0)
                    var rejected_local_dimension: Vector2 = Vector3i(4, 5, 6)
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "accepted_property", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "accepted_local", preparedInput).status()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(4, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && Objects.equals(
                        FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_vector_initializer_compatibility.gd")),
                        diagnostic.sourcePath()
                )
                        && diagnostic.range() != null
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("rejected_property_reverse")
                        && diagnostic.message().contains("Vector3")
                        && diagnostic.message().contains("Vector3i")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("rejected_property_dimension")
                        && diagnostic.message().contains("Vector3i")
                        && diagnostic.message().contains("Vector2")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("rejected_local_reverse")
                        && diagnostic.message().contains("Vector3")
                        && diagnostic.message().contains("Vector3i")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("rejected_local_dimension")
                        && diagnostic.message().contains("Vector3i")
                        && diagnostic.message().contains("Vector2")
        ));
    }

    @Test
    void analyzeAcceptsStringFamilyBoundariesAtOrdinaryTypeCheckSlots() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_string_family_boundaries.gd", """
                class_name TypeCheckStringFamilyBoundaries
                extends RefCounted
                
                var accepted_property_name: StringName = ""
                var accepted_property_text: String = &"property_text"
                var rejected_property: int = &"not_an_int"
                
                func ping(text: String, name: StringName) -> void:
                    var accepted_local_name: StringName = text
                    var accepted_local_text: String = name
                    var rejected_local: int = text
                    name = text
                    text = name
                
                func return_name(text: String) -> StringName:
                    return text
                
                func return_text(name: StringName) -> String:
                    return name
                
                func reject_return(text: String) -> int:
                    return text
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "accepted_property_name", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "accepted_property_text", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "accepted_local_name", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(pingFunction.body().statements(), "accepted_local_text", preparedInput).status()
        );
        var assignments = findNodes(pingFunction, AssignmentExpression.class, _ -> true);
        for (var assignment : assignments) {
            assertEquals(
                    FrontendExpressionTypeStatus.RESOLVED,
                    Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(assignment)).status()
            );
        }

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(3, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && Objects.equals(
                        FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_string_family_boundaries.gd")),
                        diagnostic.sourcePath()
                )
                        && diagnostic.range() != null
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("rejected_property")
                        && diagnostic.message().contains("StringName")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("rejected_local")
                        && diagnostic.message().contains("String")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'String'")
                        && diagnostic.message().contains("int")
        ));
    }

    @Test
    void analyzeSkipsInheritedPropertyInitializerBoundaryDiagnosticsOwnedByUpstreamPhases()
            throws Exception {
        var preparedInput = prepareTypeCheckInput(
                "type_check_inherited_property_initializer_boundary.gd",
                """
                        class_name TypeCheckInheritedPropertyInitializerBoundary
                        extends PropertyInitializerBase
                        
                        var skipped_blocked: int = payload
                        static var skipped_unsupported: int = PropertyInitializerBase.read()
                        static var allowed_helper: int = PropertyInitializerBase.helper()
                        """,
                FrontendAnalyzerTestRegistrySupport.registryWithInheritedPropertyInitializerBase()
        );

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertEquals(
                FrontendExpressionTypeStatus.BLOCKED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_blocked", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.UNSUPPORTED,
                requireInitializerType(preparedInput.unit().ast(), "skipped_unsupported", preparedInput).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                requireInitializerType(preparedInput.unit().ast(), "allowed_helper", preparedInput).status()
        );
        assertEquals(
                "int",
                Objects.requireNonNull(
                                requireInitializerType(preparedInput.unit().ast(), "allowed_helper", preparedInput)
                                        .publishedType()
                        )
                        .getTypeName()
        );
        assertTrue(diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        ).isEmpty());
    }

    @Test
    void analyzeChecksReturnCompatibilityAgainstPublishedCallableSlotsAndSkipsUnstableReturnValues()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_return_compatibility.gd", """
                class_name TypeCheckReturnCompatibility
                extends RefCounted
                
                class Worker:
                    pass
                
                func accepts_variant_expr() -> Variant:
                    return 1
                
                func accepts_variant_bare() -> Variant:
                    return
                
                func accepts_weak_bare():
                    return
                
                func accepts_exact_variant_source(value: Variant) -> int:
                    return value
                
                func accepts_dynamic_variant_source(worker) -> int:
                    return worker.ping().length
                
                func accepts_primitive_float_boundary() -> float:
                    return 1
                
                func rejects_bare() -> int:
                    return
                
                func rejects_object_bare() -> Object:
                    return
                
                func rejects_type() -> int:
                    return "x"
                
                func rejects_primitive_narrowing() -> int:
                    return 1.0
                
                func skips_failed() -> int:
                    return Worker
                
                func skips_deferred() -> int:
                    return 1 + 2
                
                func _init():
                    return 1
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = preparedInput.diagnosticManager().snapshot();
        var typeCheckDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_check");
        assertEquals(5, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && Objects.equals(
                        FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_return_compatibility.gd")),
                        diagnostic.sourcePath()
                )
                        && diagnostic.range() != null
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Bare 'return'")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Bare 'return'")
                        && diagnostic.message().contains("Object")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'String'")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'float'")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("returns 'void'")
                        && diagnostic.message().contains("return expr")
        ));
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "accepts_exact_variant_source"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "accepts_dynamic_variant_source"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "accepts_primitive_float_boundary"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );

        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "skips_failed"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "skips_deferred"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        ignored -> true).value()
                        )
                ).status()
        );
    }

    @Test
    void analyzeAcceptsOnlySameDimensionVectoriToVectorReturnBoundaries() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_vector_return_compatibility.gd", """
                class_name TypeCheckVectorReturnCompatibility
                extends RefCounted
                
                func accepts_vector_widening(value: Vector3i) -> Vector3:
                    return value
                
                func rejects_reverse(value: Vector3) -> Vector3i:
                    return value
                
                func rejects_wrong_dimension(value: Vector2i) -> Vector3:
                    return value
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(
                                findNode(findFunction(preparedInput.unit().ast(), "accepts_vector_widening"),
                                        dev.superice.gdparser.frontend.ast.ReturnStatement.class,
                                        _ -> true).value()
                        )
                ).status()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(2, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.stream().allMatch(diagnostic ->
                diagnostic.severity() == FrontendDiagnosticSeverity.ERROR
                        && Objects.equals(
                        FrontendDiagnostic.sourcePathText(Path.of("tmp", "type_check_vector_return_compatibility.gd")),
                        diagnostic.sourcePath()
                )
                        && diagnostic.range() != null
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'Vector3'")
                        && diagnostic.message().contains("Vector3i")
        ));
        assertTrue(typeCheckDiagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'Vector2i'")
                        && diagnostic.message().contains("Vector3")
        ));
    }

    @Test
    void analyzeAcceptsNullAtObjectInitializerAndReturnBoundariesButKeepsNilToScalarRejected()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_null_object_boundaries.gd", """
                class_name TypeCheckNullObjectBoundaries
                extends RefCounted
                
                var accepted_obj: Object = null
                var rejected_int: int = null
                
                func ping() -> void:
                    var local_obj: Object = null
                    var local_i: int = null
                
                func ret_obj() -> Object:
                    return null
                
                func ret_int() -> int:
                    return null
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(3, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Property 'rejected_int'")
                        && diagnostic.message().contains("Nil")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Local variable 'local_i'")
                        && diagnostic.message().contains("Nil")
                        && diagnostic.message().contains("int")
        ));
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.message().contains("Return value type 'Nil'")
                        && diagnostic.message().contains("int")
        ));
    }

    @Test
    void analyzeReportsTypeMismatchWhenVoidUtilityFeedsTypedInitializerInsteadOfCrashing()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_void_utility_initializer.gd", """
                class_name TypeCheckVoidUtilityInitializer
                extends RefCounted
                
                func ping(value):
                    var strict_value: int = print(value)
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var strictValueType = requireInitializerType(
                pingFunction.body().statements(),
                "strict_value",
                preparedInput
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, strictValueType.status());
        assertEquals(GdVoidType.VOID, strictValueType.publishedType());

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("strict_value"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("void"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("int"));
    }

    @Test
    void analyzeReportsTypeMismatchWhenVoidSignalEmitFeedsTypedInitializer()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_void_signal_emit_initializer.gd", """
                class_name TypeCheckVoidSignalEmitInitializer
                extends Node
                
                func ping(sig: Signal):
                    var unused: int = sig.emit()
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var unusedType = requireInitializerType(
                pingFunction.body().statements(),
                "unused",
                preparedInput
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, unusedType.status());
        assertEquals(GdVoidType.VOID, unusedType.publishedType());

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("unused"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("void"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("int"));
    }

    @Test
    void analyzeRequiresStableConditionFactsButDoesNotEnforceStrictBoolConditionSlots()
            throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_condition_contract.gd", """
                class_name TypeCheckConditionContract
                extends RefCounted
                
                class Worker:
                    pass
                
                func ping(payload):
                    assert(payload, "variant condition remains source-valid")
                    if 1:
                        pass
                    elif payload:
                        pass
                    while payload:
                        pass
                    if 1 + 2:
                        pass
                    if payload and 1:
                        pass
                    if payload or 0:
                        pass
                    if !true:
                        pass
                    if not payload:
                        pass
                    if Worker:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = preparedInput.diagnosticManager().snapshot();
        var typeCheckDiagnostics = diagnosticsByCategory(diagnostics, "sema.type_check");
        assertTrue(typeCheckDiagnostics.isEmpty());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var ifStatements = findNodes(
                pingFunction,
                dev.superice.gdparser.frontend.ast.IfStatement.class,
                ignored -> true
        );
        var whileStatement = findNode(
                pingFunction,
                dev.superice.gdparser.frontend.ast.WhileStatement.class,
                ignored -> true
        );
        var firstIf = ifStatements.getFirst();
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(firstIf.condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(
                        preparedInput.analysisData().expressionTypes().get(firstIf.elifClauses().getFirst().condition())
                ).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(whileStatement.condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(1).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(2).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(3).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(4).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.DYNAMIC,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(5).condition())).status()
        );
        assertEquals(
                FrontendExpressionTypeStatus.FAILED,
                Objects.requireNonNull(preparedInput.analysisData().expressionTypes().get(ifStatements.get(6).condition())).status()
        );
    }

    @Test
    void analyzeFailsFastWhenConditionFactLeaksCompilerOnlyType() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_compiler_only_condition.gd", """
                class_name TypeCheckCompilerOnlyCondition
                extends RefCounted
                
                func ping():
                    if 1:
                        pass
                """);
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var ifStatement = findNode(
                pingFunction,
                dev.superice.gdparser.frontend.ast.IfStatement.class,
                ignored -> true
        );
        preparedInput.analysisData().expressionTypes().put(
                ifStatement.condition(),
                FrontendExpressionType.resolved(GdccForRangeIterType.FOR_RANGE_ITER)
        );

        var failure = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(failure.getMessage().contains("compiler-only type leaked into frontend condition fact"));
    }

    @Test
    void analyzeFailsFastWhenForIterationPlanIsNotPublished() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_missing_iteration_plan.gd", """
                class_name ForMissingIterationPlan
                extends RefCounted
                
                func ping():
                    for i in range(3):
                        pass
                """);
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var forStatement = findNode(pingFunction, ForStatement.class, ignored -> true);
        preparedInput.analysisData().forIterationPlans().remove(forStatement);

        var failure = assertThrows(
                IllegalStateException.class,
                () -> new FrontendTypeCheckAnalyzer().analyze(
                        preparedInput.classRegistry(),
                        preparedInput.analysisData(),
                        preparedInput.diagnosticManager()
                )
        );

        assertTrue(failure.getMessage().contains("for-in iteration plan has not been published"));
    }

    @Test
    void analyzeReportsRangeArityDiagnosticsForEmptyAndOverflowingRange() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_range_arity.gd", """
                class_name ForRangeArity
                extends RefCounted
                
                func ping():
                    for i in range():
                        pass
                    for j in range(1, 2, 3, 4):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.get(0).message().contains("range(...) expects between 1 and 3 arguments but got 0"));
        assertTrue(diagnostics.get(1).message().contains("range(...) expects between 1 and 3 arguments but got 4"));
    }

    @Test
    void analyzeAcceptsValidRangeArityAndZeroStepWithoutDiagnostic() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_range_valid_arity.gd", """
                class_name ForRangeValidArity
                extends RefCounted
                
                func ping():
                    for a in range(3):
                        pass
                    for b in range(1, 3):
                        pass
                    for c in range(2, 8, 2):
                        pass
                    for d in range(1, 2, 0):
                        pass
                    for e in range(8, 2, -2):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check").isEmpty());
    }

    @Test
    void analyzeAcceptsDynamicIntegerRangeBoundaries() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_range_dynamic_bounds.gd", """
                class_name ForRangeDynamicBounds
                extends RefCounted
                
                func ping():
                    var start: int = 1
                    var end: int = 5
                    var step: int = 2
                    for a in range(start, end):
                        pass
                    for b in range(1, end):
                        pass
                    for c in range(start, end, step):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check").isEmpty());
    }

    @Test
    void analyzeReportsRangeArgumentThatCannotEnterIntSlotAtArgumentPosition() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_range_bad_argument.gd", """
                class_name ForRangeBadArgument
                extends RefCounted
                
                func ping():
                    var end: String = "x"
                    var step: String = "y"
                    for a in range("literal"):
                        pass
                    for b in range(1, end):
                        pass
                    for c in range(1, 5, step):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(3, diagnostics.size());
        assertTrue(diagnostics.get(0).message().contains("range(...) argument #1 type 'String' is not assignable to 'int'"));
        assertTrue(diagnostics.get(1).message().contains("range(...) argument #2 type 'String' is not assignable to 'int'"));
        assertTrue(diagnostics.get(2).message().contains("range(...) argument #3 type 'String' is not assignable to 'int'"));
    }

    @Test
    void analyzeDoesNotReportUnsupportedForGenericVariantRoute() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_generic_route.gd", """
                class_name ForGenericRoute
                extends RefCounted
                
                func ping(values):
                    for item in values:
                        pass
                    for f in 2.2:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check").isEmpty());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var forStatements = findNodes(pingFunction, ForStatement.class, ignored -> true);
        assertEquals(2, forStatements.size());
        var firstPlan = Objects.requireNonNull(
                preparedInput.analysisData().forIterationPlans().get(forStatements.getFirst())
        );
        var secondPlan = Objects.requireNonNull(
                preparedInput.analysisData().forIterationPlans().get(forStatements.get(1))
        );
        assertEquals(FrontendForIterationRoute.GENERIC_VARIANT, firstPlan.route());
        assertEquals(FrontendForIterationRoute.FLOAT_SHORTHAND, secondPlan.route());
        assertSame(GdVariantType.VARIANT, firstPlan.exposedIteratorType());
        assertEquals("float", secondPlan.semanticElementType().getTypeName());
        assertEquals("float", secondPlan.exposedIteratorType().getTypeName());
    }

    @Test
    void analyzeAcceptsCompatibleExplicitIteratorTypeUsingSemanticElementType() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_iterator_compatible.gd", """
                class_name ForIteratorCompatible
                extends RefCounted
                
                func ping():
                    for i: float in range(3):
                        pass
                    for j: int in range(3):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check").isEmpty());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var forStatements = findNodes(pingFunction, ForStatement.class, ignored -> true);
        var floatPlan = Objects.requireNonNull(
                preparedInput.analysisData().forIterationPlans().get(forStatements.getFirst())
        );
        assertEquals("float", floatPlan.exposedIteratorType().getTypeName());
        assertEquals("int", floatPlan.semanticElementType().getTypeName());
        var intPlan = Objects.requireNonNull(
                preparedInput.analysisData().forIterationPlans().get(forStatements.get(1))
        );
        assertEquals("int", intPlan.exposedIteratorType().getTypeName());
    }

    @Test
    void analyzeRejectsExplicitIteratorTypeThatCannotReceiveElement() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_iterator_incompatible.gd", """
                class_name ForIteratorIncompatible
                extends RefCounted
                
                func ping():
                    for i: String in range(3):
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message()
                .contains("for-in iterator declared type 'String' cannot receive iterated element type 'int'"));
    }

    @Test
    void analyzeReportsNonIterableHardTypesAtTypeCheckBoundary() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_non_iterable_hard_types.gd", """
                class_name ForNonIterableHardTypes
                extends RefCounted
                
                func ping(callable: Callable, signal_value: Signal, rid: RID, string_name: StringName,
                        node_path: NodePath, vector4: Vector4, rect2: Rect2):
                    for item in true:
                        pass
                    for item in callable:
                        pass
                    for item in signal_value:
                        pass
                    for item in rid:
                        pass
                    for item in string_name:
                        pass
                    for item in node_path:
                        pass
                    for item in vector4:
                        pass
                    for item in rect2:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(8, diagnostics.size());
        for (var typeName : List.of("bool", "Callable", "Signal", "RID", "StringName", "NodePath", "Vector4", "Rect2")) {
            assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message()
                    .equals("Unable to iterate on value of type \"" + typeName + "\"")));
        }
    }

    @Test
    void analyzeKeepsVariantAndUnresolvedIterableDiagnosticsWithTheirUpstreamOwners() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_dynamic_or_unresolved.gd", """
                class_name ForDynamicOrUnresolved
                extends RefCounted
                
                func ping(values: Variant):
                    for value in values:
                        pass
                    for missing_value in missing_iterable:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check").isEmpty());
        assertTrue(preparedInput.diagnosticManager().snapshot().asList().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains("missing_iterable")));
    }

    @Test
    void analyzeStillTraversesBodyAfterNonIterableDiagnostic() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_non_iterable_body.gd", """
                class_name ForNonIterableBody
                extends RefCounted
                
                func ping():
                    for item in true:
                        var invalid: int = "bad"
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message()
                .equals("Unable to iterate on value of type \"bool\"")));
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("Local variable 'invalid'")));
    }

    @Test
    void analyzeExplicitIteratorTypeUsesTypedContainerSemanticElement() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_typed_container_iterator.gd", """
                class_name ForTypedContainerIterator
                extends RefCounted
                
                func ping(values: Array[int]):
                    for item: String in values:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message()
                .contains("for-in iterator declared type 'String' cannot receive iterated element type 'int'"));
    }

    @Test
    void analyzeRejectsExplicitIteratorTypeAgainstStringSemanticElement() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_string_iterator_type.gd", """
                class_name ForStringIteratorType
                extends RefCounted
                
                func ping():
                    for item: float in "abc":
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message()
                .contains("for-in iterator declared type 'float' cannot receive iterated element type 'String'"));
    }

    @Test
    void analyzeNonIterableExplicitIteratorDoesNotAddFallbackConversionDiagnostic() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_non_iterable_explicit_iterator.gd", """
                class_name ForNonIterableExplicitIterator
                extends RefCounted
                
                func ping():
                    for item: String in true:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(1, diagnostics.size());
        assertEquals("Unable to iterate on value of type \"bool\"", diagnostics.getFirst().message());
    }

    @Test
    void analyzeExplicitVariantIteratorKeepsVariantSlotAcrossRoutes() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_explicit_variant_iterator.gd", """
                class_name ForExplicitVariantIterator
                extends RefCounted
                
                func ping(values: Variant):
                    var limit := 3
                    for range_item: Variant in range(3):
                        pass
                    for shorthand_item: Variant in limit:
                        pass
                    for dynamic_item: Variant in values:
                        pass
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check").isEmpty());
        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var forStatements = findNodes(pingFunction, ForStatement.class, _ -> true);
        assertEquals(3, forStatements.size());
        for (var forStatement : forStatements) {
            var plan = Objects.requireNonNull(preparedInput.analysisData().forIterationPlans().get(forStatement));
            assertSame(GdVariantType.VARIANT, plan.exposedIteratorType());
            assertSame(GdVariantType.VARIANT, preparedInput.analysisData().slotTypes().get(forStatement));
        }
    }

    @Test
    void analyzeTypeChecksForBodyOrdinaryLocalInitializer() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_body_local_initializer.gd", """
                class_name ForBodyLocalInitializer
                extends RefCounted
                
                func ping(values):
                    for item in values:
                        var value: int = "invalid"
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message().contains("Local variable 'value'"));
        assertTrue(diagnostics.getFirst().message().contains("not assignable to declared slot type 'int'"));
    }

    @Test
    void analyzeConsumesStringFormatTypedFactsAcrossInitializerConditionAndReturn() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_string_format.gd", """
                class_name TypeCheckStringFormat
                extends RefCounted
                
                func render(value: Variant) -> String:
                    var text: String = "value=%s" % value
                    if "%s" % value:
                        return text
                    return "%d" % 1
                
                func reject(value: Variant) -> void:
                    var wrong: int = "%s" % value
                """);

        // The `%` initializer publishes a resolved String fact that downstream consumers use.
        var renderFunction = findFunction(preparedInput.unit().ast(), "render");
        var textInit = requireInitializerType(renderFunction.body().statements(), "text", preparedInput);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, textInit.status());
        assertEquals("String", textInit.publishedType().getTypeName());

        // The condition root is the `%` expression itself, so type-check consumes its String fact.
        var condition = findNodes(renderFunction, IfStatement.class, _ -> true).getFirst().condition();
        var conditionFact = preparedInput.analysisData().expressionTypes().get(condition);
        assertNotNull(conditionFact);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, conditionFact.status());
        assertEquals("String", conditionFact.publishedType().getTypeName());

        // Initializer/condition/return consumption of the String result raises no findings in the
        // legal function; assigning the String result to an int slot is rejected exactly once.
        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var typeCheckDiagnostics = diagnosticsByCategory(
                preparedInput.diagnosticManager().snapshot(),
                "sema.type_check"
        );
        assertEquals(1, typeCheckDiagnostics.size());
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("Local variable 'wrong'"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("not assignable to declared slot type 'int'"));
        assertTrue(typeCheckDiagnostics.getFirst().message().contains("String"));
    }

    @Test
    void analyzeTypeChecksNestedForBodiesWithoutMaskingInnerErrors() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_nested_body.gd", """
                class_name ForNestedBody
                extends RefCounted
                
                func ping(values):
                    for i in values:
                        var outer_bad: int = "outer"
                        for j in values:
                            var inner_bad: int = "inner"
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(2, diagnostics.size());
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("outer_bad")));
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("inner_bad")));
    }

    @Test
    void analyzeTypeChecksForBodyReturnStatement() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_body_return.gd", """
                class_name ForBodyReturn
                extends RefCounted
                
                func ping() -> int:
                    for i in range(3):
                        return "not int"
                    return 0
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var diagnostics = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check");
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.getFirst().message().contains("Return value type"));
    }

    @Test
    void analyzeRangeAndIntShorthandRoutesRefineIteratorToInt() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_route_refinement.gd", """
                class_name ForRouteRefinement
                extends RefCounted
                
                func ping():
                    var limit := 3
                    for i in range(3):
                        var x := i + 1
                    for k in limit:
                        var y := k + 1
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        assertTrue(diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.type_check").isEmpty());

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var forStatements = findNodes(pingFunction, ForStatement.class, ignored -> true);
        assertEquals(2, forStatements.size());
        var rangePlan = Objects.requireNonNull(
                preparedInput.analysisData().forIterationPlans().get(forStatements.getFirst())
        );
        var shorthandPlan = Objects.requireNonNull(
                preparedInput.analysisData().forIterationPlans().get(forStatements.get(1))
        );
        assertEquals(FrontendForIterationRoute.RANGE_CALL, rangePlan.route());
        assertEquals(FrontendForIterationRoute.INT_SHORTHAND, shorthandPlan.route());
        assertSame(GdIntType.INT, preparedInput.analysisData().slotTypes().get(forStatements.getFirst()));
        assertSame(GdIntType.INT, preparedInput.analysisData().slotTypes().get(forStatements.get(1)));
    }

    @Test
    void analyzeKeepsUpstreamAssignmentAndCallBoundaryDiagnosticsInBareRangeForBody() throws Exception {
        var preparedInput = prepareTypeCheckInput("for_body_upstream_boundary.gd", """
                class_name ForBodyUpstreamBoundary
                extends RefCounted
                
                func ping():
                    for i in range(3):
                        undeclared_target = 1
                        some_undefined_callable()
                """);

        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                preparedInput.diagnosticManager()
        );

        var bindingErrors = diagnosticsByCategory(preparedInput.diagnosticManager().snapshot(), "sema.binding");
        assertTrue(
                bindingErrors.stream().anyMatch(diagnostic -> diagnostic.message().contains("undeclared_target")),
                "upstream assignment boundary diagnostic expected in bare-range for body"
        );
        assertTrue(
                bindingErrors.stream().anyMatch(diagnostic -> diagnostic.message().contains("some_undefined_callable")),
                "upstream call boundary diagnostic expected in bare-range for body"
        );
    }

    private static void assertEvent(
            @NotNull ProbeEvent event,
            @NotNull String expectedKind,
            @NotNull String expectedName,
            @NotNull String expectedCurrentClassName,
            @Nullable String expectedReturnTypeName,
            @NotNull ResolveRestriction expectedRestriction,
            boolean expectedStaticContext,
            int expectedDepth,
            @Nullable String expectedPropertyInitializerName
    ) {
        assertEquals(expectedKind, event.kind());
        assertEquals(expectedName, event.name());
        assertEquals(expectedCurrentClassName, event.currentClassName());
        assertEquals(expectedReturnTypeName, event.currentReturnTypeName());
        assertEquals(expectedRestriction, event.restriction());
        assertEquals(expectedStaticContext, event.staticContext());
        assertEquals(expectedDepth, event.executableBodyDepth());
        assertEquals(expectedPropertyInitializerName, event.propertyInitializerName());
    }

    @Test
    void analyzeReportsContainerLiteralElementRejectAndDuplicateKeyFromPlan() throws Exception {
        var preparedInput = prepareTypeCheckInput("type_check_container_literal_plan.gd", """
                class_name TypeCheckContainerLiteralPlan
                extends RefCounted
                
                func ping():
                    var bad: Array[String] = [1]
                    var dup: Dictionary = {"x": 1, "x": 2}
                    var ok: Array[int] = [1, 2]
                """);

        // Shared semantic already ran inside prepareTypeCheckInput; re-run only type-check for isolation.
        var typeCheckOnly = new DiagnosticManager();
        new FrontendTypeCheckAnalyzer().analyze(
                preparedInput.classRegistry(),
                preparedInput.analysisData(),
                typeCheckOnly
        );

        var typeCheckDiagnostics = diagnosticsByCategory(typeCheckOnly.snapshot(), "sema.type_check");
        assertEquals(
                1,
                typeCheckDiagnostics.stream()
                        .filter(d -> d.message().contains("Cannot have an element of type")
                                && d.message().contains("int")
                                && d.message().contains("Array[String]"))
                        .count()
        );
        assertEquals(
                1,
                typeCheckDiagnostics.stream()
                        .filter(d -> d.message().contains("was already used in this dictionary"))
                        .count()
        );
        assertTrue(typeCheckDiagnostics.stream().noneMatch(d -> d.message().contains("ok")));

        var pingFunction = findFunction(preparedInput.unit().ast(), "ping");
        var badInit = findVariable(pingFunction.body().statements(), "bad").value();
        var badPlan = preparedInput.analysisData().containerLiteralPlans().get(badInit);
        assertNotNull(badPlan);
        assertEquals("Array[String]", badPlan.resultType().getTypeName());
        assertEquals(
                FrontendExpressionTypeStatus.RESOLVED,
                preparedInput.analysisData().expressionTypes().get(badInit).status()
        );
    }

    private static @NotNull PreparedTypeCheckInput prepareTypeCheckInput(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return prepareTypeCheckInput(fileName, source, new ClassRegistry(ExtensionApiLoader.loadDefault()));
    }

    private static @NotNull PreparedTypeCheckInput prepareTypeCheckInput(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull ClassRegistry classRegistry
    ) {
        var parserService = new GdScriptParserService();
        var diagnosticManager = new DiagnosticManager();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnosticManager);
        assertTrue(diagnosticManager.isEmpty(), () -> "Unexpected parse diagnostics: " + diagnosticManager.snapshot());

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
        return new PreparedTypeCheckInput(unit, analysisData, diagnosticManager, classRegistry);
    }

    private static @NotNull List<FrontendDiagnostic> diagnosticsByCategory(
            @NotNull DiagnosticSnapshot diagnostics,
            @NotNull String category
    ) {
        return diagnostics.asList().stream()
                .filter(diagnostic -> diagnostic.category().equals(category))
                .toList();
    }

    private static @NotNull FrontendExpressionType requireInitializerType(
            @NotNull List<Statement> statements,
            @NotNull String variableName,
            @NotNull PreparedTypeCheckInput preparedInput
    ) {
        return requireInitializerType(findVariable(statements, variableName), preparedInput);
    }

    private static @NotNull FrontendExpressionType requireInitializerType(
            @NotNull Node root,
            @NotNull String variableName,
            @NotNull PreparedTypeCheckInput preparedInput
    ) {
        return requireInitializerType(findVariable(root, variableName), preparedInput);
    }

    private static @NotNull FrontendExpressionType requireInitializerType(
            @NotNull VariableDeclaration variableDeclaration,
            @NotNull PreparedTypeCheckInput preparedInput
    ) {
        var initializer = Objects.requireNonNull(variableDeclaration.value(), "initializer must not be null");
        var publishedType = preparedInput.analysisData().expressionTypes().get(initializer);
        return Objects.requireNonNull(
                publishedType,
                () -> "Initializer type not published for variable '" + variableDeclaration.name() + "'"
        );
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String functionName) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(functionName));
    }

    private static @NotNull VariableDeclaration findVariable(
            @NotNull List<Statement> statements,
            @NotNull String variableName
    ) {
        return statements.stream()
                .filter(VariableDeclaration.class::isInstance)
                .map(VariableDeclaration.class::cast)
                .filter(variable -> variable.name().equals(variableName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Variable not found: " + variableName));
    }

    private static @NotNull VariableDeclaration findVariable(@NotNull Node root, @NotNull String variableName) {
        return findNode(root, VariableDeclaration.class, variable -> variable.name().equals(variableName));
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
                // Continue searching remaining subtrees.
            }
        }
        throw new AssertionError("Node not found: " + nodeType.getSimpleName());
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectNodes(root, nodeType, predicate, matches);
        if (matches.isEmpty()) {
            throw new AssertionError("Node not found: " + nodeType.getSimpleName());
        }
        return List.copyOf(matches);
    }

    private static <T extends Node> void collectNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(root)) {
            var candidate = nodeType.cast(root);
            if (predicate.test(candidate)) {
                matches.add(candidate);
            }
        }
        for (var child : root.getChildren()) {
            collectNodes(child, nodeType, predicate, matches);
        }
    }

    private static @NotNull PropertyDef findPropertyDef(
            @NotNull ClassDef classDef,
            @NotNull String propertyName
    ) {
        return classDef.getProperties().stream()
                .filter(property -> property.getName().equals(propertyName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Property not found: " + propertyName));
    }

    private record PreparedTypeCheckInput(
            @NotNull FrontendSourceUnit unit,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull ClassRegistry classRegistry
    ) {
    }

    private record ProbeEvent(
            @NotNull String kind,
            @NotNull String name,
            @Nullable String currentClassName,
            @Nullable String currentReturnTypeName,
            @NotNull ResolveRestriction restriction,
            boolean staticContext,
            int executableBodyDepth,
            @Nullable String propertyInitializerName
    ) {
        private ProbeEvent {
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(restriction, "restriction must not be null");
        }
    }

    private static final class RecordingTypeCheckAnalyzer extends FrontendTypeCheckAnalyzer {
        private final List<ProbeEvent> events = new ArrayList<>();

        @Override
        protected void visitOrdinaryLocalInitializer(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.VariableDeclaration variableDeclaration
        ) {
            events.add(toEvent("local", variableDeclaration.name(), access));
        }

        @Override
        protected void visitPropertyInitializer(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.VariableDeclaration variableDeclaration
        ) {
            events.add(toEvent("property", variableDeclaration.name(), access));
        }

        @Override
        protected void visitReturnStatement(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.ReturnStatement returnStatement
        ) {
            events.add(toEvent("return", returnStatement.value() == null ? "bare" : "valued", access));
        }

        @Override
        protected void visitConditionExpression(
                @NotNull TypeCheckAccess access,
                @NotNull dev.superice.gdparser.frontend.ast.Expression condition,
                @NotNull dev.superice.gdparser.frontend.ast.Node owner
        ) {
            events.add(toEvent("condition", owner.getClass().getSimpleName(), access));
        }

        private @NotNull ProbeEvent toEvent(
                @NotNull String kind,
                @NotNull String name,
                @NotNull TypeCheckAccess access
        ) {
            var context = access.context();
            var propertyInitializerContext = context.currentPropertyInitializerContext();
            return new ProbeEvent(
                    kind,
                    name,
                    context.currentClass() != null ? context.currentClass().getName() : null,
                    context.currentCallableReturnSlot() != null ? context.currentCallableReturnSlot().getTypeName() : null,
                    context.currentRestriction(),
                    context.currentStaticContext(),
                    context.executableBodyDepth(),
                    propertyInitializerContext != null ? propertyInitializerContext.declaration().name() : null
            );
        }

        private @NotNull List<ProbeEvent> events() {
            return List.copyOf(events);
        }
    }
}
