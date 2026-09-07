package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.BlockScopeKind;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendDiagnosticSeverity;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import gd.script.gdcc.frontend.sema.FrontendTypedLexicalEnvironment;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport.NestedExpressionResolver;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirParameterDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPackedStringArrayType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Point;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.Range;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeRef;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import gd.script.gdcc.type.GdType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendExpressionSemanticSupportTest {
    private static final @NotNull Range TINY = new Range(0, 1, new Point(0, 0), new Point(0, 1));

    @Test
    void resolveIdentifierExpressionTypeMaterializesCallableValuesAndRejectsBareTypeMeta() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_identifiers.gd",
                """
                        class_name ExpressionSemanticSupportIdentifiers
                        extends RefCounted
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func helper() -> int:
                            return 1
                        
                        func ping(seed):
                            seed
                            helper
                            Worker
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var seed = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var helper = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var worker = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );

        var seedResult = support.resolveIdentifierExpressionType(seed);
        assertFalse(seedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, seedResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, seedResult.expressionType().publishedType());

        var helperResult = support.resolveIdentifierExpressionType(helper);
        assertFalse(helperResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, helperResult.expressionType().status());
        assertInstanceOf(GdCallableType.class, helperResult.expressionType().publishedType());

        var workerResult = support.resolveIdentifierExpressionType(worker);
        assertFalse(workerResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, workerResult.expressionType().status());
        assertTrue(workerResult.expressionType().detailReason().contains("static route"));
    }

    @Test
    void resolveIdentifierExpressionTypeFailsWhenValueBindingMissesResolvedValuePayload() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_missing_resolved_value.gd",
                """
                        class_name ExpressionSemanticSupportMissingResolvedValue
                        extends RefCounted
                        
                        func ping(seed):
                            seed
                        """
        );
        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var seed = assertInstanceOf(
                IdentifierExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        analyzed.analysisData().symbolBindings().put(
                seed,
                new FrontendBinding("seed", FrontendBindingKind.PARAMETER, seed)
        );

        var seedResult = support.resolveIdentifierExpressionType(seed);

        assertFalse(seedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, seedResult.expressionType().status());
        assertTrue(seedResult.expressionType().detailReason().contains("missing its top-binding resolved value payload"));
    }

    @Test
    void resolveIdentifierExpressionTypeUsesInjectedOverlayBindingBeforeStableVariantPayload() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var registry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var classScope = new ClassScope(registry, registry, new LirClassDef("OverlayHost", "RefCounted"));
        var callableScope = new CallableScope(classScope, CallableScopeKind.FUNCTION_DECLARATION);
        var bodyScope = new BlockScope(callableScope, BlockScopeKind.FUNCTION_BODY);
        var seed = identifier("seed");
        bodyScope.defineLocal("seed", GdVariantType.VARIANT, seed);
        var stableValue = bodyScope.resolveValueHere("seed");
        assertNotNull(stableValue);
        analysisData.scopesByAst().put(seed, bodyScope);
        analysisData.symbolBindings().put(
                seed,
                new FrontendBinding("seed", FrontendBindingKind.LOCAL_VAR, seed, stableValue, ScopeLookupStatus.FOUND_ALLOWED)
        );
        var environment = new FrontendTypedLexicalEnvironment(bodyScope, analysisData);
        environment.addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(bodyScope, "seed", seed, GdIntType.INT)
        );
        var support = new FrontendExpressionSemanticSupport(
                environment::symbolBinding,
                analysisData.scopesByAst(),
                ResolveRestriction::instanceContext,
                () -> null,
                registry,
                () -> new FrontendChainHeadReceiverSupport(
                        analysisData,
                        analysisData.scopesByAst(),
                        environment::symbolBinding,
                        ResolveRestriction.instanceContext(),
                        false,
                        null,
                        _ -> null,
                        _ -> null
                )
        );

        var result = support.resolveIdentifierExpressionType(seed);

        assertEquals(GdVariantType.VARIANT, stableValue.type());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
        assertEquals(GdIntType.INT, result.expressionType().publishedType());
    }

    @Test
    void resolveCallExpressionTypeDistinguishesResolvedBlockedAndUnsupportedCalls() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_calls.gd",
                """
                        class_name ExpressionSemanticSupportCalls
                        extends RefCounted
                        
                        func helper(value: int) -> int:
                            return value
                        
                        func make_cb() -> Callable:
                            return helper
                        
                        static func ping_static(value: int):
                            helper(value)
                        
                        func ping():
                            helper(1)
                            self.make_cb()()
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var pingStaticFunction = findFunction(analyzed.ast(), "ping_static");
        var resolvedBareCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var unsupportedDirectCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var blockedBareCall = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingStaticFunction.body().statements().getFirst()).expression()
        );

        var unrestrictedSupport = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var staticSupport = createSupport(analyzed, ResolveRestriction.staticContext(), true);
        var publishedResolver = publishedExpressionResolver(analyzed);

        var resolvedResult = unrestrictedSupport.resolveCallExpressionType(
                resolvedBareCall,
                publishedResolver,
                true,
                false
        );
        var resolvedCall = resolvedResult.publishedCallOrNull();
        assertAll(
                () -> assertTrue(resolvedResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedResult.expressionType().status()),
                () -> assertEquals("int", resolvedResult.expressionType().publishedType().getTypeName()),
                () -> assertNotNull(resolvedCall),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, resolvedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, resolvedCall.receiverKind()),
                () -> assertEquals(List.of("int"), resolvedCall.argumentTypes().stream().map(GdType::getTypeName).toList()),
                () -> assertEquals("int", resolvedCall.returnType().getTypeName()),
                () -> assertNotNull(resolvedCall.declarationSite())
        );

        var blockedResult = staticSupport.resolveCallExpressionType(
                blockedBareCall,
                publishedResolver,
                true,
                false
        );
        var blockedCall = blockedResult.publishedCallOrNull();
        assertAll(
                () -> assertTrue(blockedResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.BLOCKED, blockedResult.expressionType().status()),
                () -> assertNotNull(blockedResult.expressionType().publishedType()),
                () -> assertEquals("int", blockedResult.expressionType().publishedType().getTypeName()),
                () -> assertNotNull(blockedCall),
                () -> assertEquals(FrontendCallResolutionStatus.BLOCKED, blockedCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, blockedCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, blockedCall.receiverKind()),
                () -> assertEquals(List.of("int"), blockedCall.argumentTypes().stream().map(GdType::getTypeName).toList()),
                () -> assertEquals("int", blockedCall.returnType().getTypeName()),
                () -> assertNotNull(blockedCall.declarationSite())
        );

        var unsupportedResult = unrestrictedSupport.resolveCallExpressionType(
                unsupportedDirectCall,
                publishedResolver,
                true,
                false
        );
        assertAll(
                () -> assertTrue(unsupportedResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, unsupportedResult.expressionType().status()),
                () -> assertTrue(unsupportedResult.expressionType().detailReason().contains("Direct invocation of callable values")),
                () -> assertNull(unsupportedResult.publishedCallOrNull())
        );
    }

    @Test
    void resolveCallExpressionTypePublishesBuiltinDirectConstructorsAndRejectsBareObjectConstructors()
            throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_direct_constructors.gd",
                """
                        class_name ExpressionSemanticSupportDirectConstructors
                        extends RefCounted
                        
                        func ping() -> void:
                            Array()
                            Vector3i(1, 2, 3)
                            Node()
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var arrayCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Array")
        );
        var vectorCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Vector3i")
        );
        var nodeCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Node")
        );

        var arrayResult = support.resolveCallExpressionType(arrayCall, publishedResolver, true, false);
        var vectorResult = support.resolveCallExpressionType(vectorCall, publishedResolver, true, false);
        var nodeResult = support.resolveCallExpressionType(nodeCall, publishedResolver, true, false);

        assertAll(
                () -> assertTrue(arrayResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, arrayResult.expressionType().status()),
                () -> assertEquals("Array", arrayResult.expressionType().publishedType().getTypeName()),
                () -> assertNotNull(arrayResult.publishedCallOrNull()),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, arrayResult.publishedCallOrNull().status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, arrayResult.publishedCallOrNull().callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, arrayResult.publishedCallOrNull().receiverKind()),
                () -> assertEquals(List.of(), arrayResult.publishedCallOrNull().argumentTypes()),
                () -> assertNotNull(arrayResult.publishedCallOrNull().declarationSite()),
                () -> assertTrue(vectorResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, vectorResult.expressionType().status()),
                () -> assertEquals("Vector3i", vectorResult.expressionType().publishedType().getTypeName()),
                () -> assertNotNull(vectorResult.publishedCallOrNull()),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, vectorResult.publishedCallOrNull().status()),
                () -> assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, vectorResult.publishedCallOrNull().callKind()),
                () -> assertEquals(FrontendReceiverKind.TYPE_META, vectorResult.publishedCallOrNull().receiverKind()),
                () -> assertEquals(
                        List.of("int", "int", "int"),
                        vectorResult.publishedCallOrNull().argumentTypes().stream().map(GdType::getTypeName).toList()
                ),
                () -> assertNotNull(vectorResult.publishedCallOrNull().declarationSite()),
                () -> assertTrue(nodeResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.FAILED, nodeResult.expressionType().status()),
                () -> assertTrue(nodeResult.expressionType().detailReason().contains("Node.new(...)")),
                () -> assertNull(nodeResult.publishedCallOrNull())
        );
    }

    @Test
    void resolveCallExpressionTypeTargetsSingleArgVariantBuiltinConstructorsWithoutHijackingObjectOrCastRoutes()
            throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_variant_builtin_constructors.gd",
                """
                        class_name ExpressionSemanticSupportVariantBuiltinConstructors
                        extends RefCounted
                        
                        func ping(plain: Array, seed: Variant) -> void:
                            int(plain[0])
                            String(plain[0])
                            StringName(plain[0])
                            Array(seed)
                            Dictionary(seed)
                            Node(seed)
                            seed as int
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var intCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("int")
        );
        var stringCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("String")
        );
        var stringNameCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("StringName")
        );
        var arrayCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Array")
        );
        var dictionaryCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Dictionary")
        );
        var nodeCall = findNode(
                pingFunction,
                CallExpression.class,
                candidate -> candidate.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals("Node")
        );
        var castExpression = findNode(pingFunction, CastExpression.class, _ -> true);

        var intResult = support.resolveCallExpressionType(intCall, publishedResolver, true, false);
        var stringResult = support.resolveCallExpressionType(stringCall, publishedResolver, true, false);
        var stringNameResult = support.resolveCallExpressionType(stringNameCall, publishedResolver, true, false);
        var arrayResult = support.resolveCallExpressionType(arrayCall, publishedResolver, true, false);
        var dictionaryResult = support.resolveCallExpressionType(dictionaryCall, publishedResolver, true, false);
        var nodeResult = support.resolveCallExpressionType(nodeCall, publishedResolver, true, false);
        var castType = analyzed.analysisData().expressionTypes().get(castExpression);

        assertAll(
                () -> {
                    assertTrue(intResult.rootOwnsOutcome());
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, intResult.expressionType().status());
                    var publishedIntType = intResult.expressionType().publishedType();
                    assertNotNull(publishedIntType);
                    assertEquals("int", publishedIntType.getTypeName());
                    var resolvedIntCall = intResult.publishedCallOrNull();
                    assertNotNull(resolvedIntCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedIntCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedIntCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, resolvedIntCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), resolvedIntCall.argumentTypes());
                },
                () -> {
                    assertTrue(stringResult.rootOwnsOutcome());
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, stringResult.expressionType().status());
                    var publishedStringType = stringResult.expressionType().publishedType();
                    assertNotNull(publishedStringType);
                    assertEquals("String", publishedStringType.getTypeName());
                    var resolvedStringCall = stringResult.publishedCallOrNull();
                    assertNotNull(resolvedStringCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedStringCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedStringCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, resolvedStringCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), resolvedStringCall.argumentTypes());
                },
                () -> {
                    assertTrue(stringNameResult.rootOwnsOutcome());
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, stringNameResult.expressionType().status());
                    var publishedStringNameType = stringNameResult.expressionType().publishedType();
                    assertNotNull(publishedStringNameType);
                    assertEquals("StringName", publishedStringNameType.getTypeName());
                    var resolvedStringNameCall = stringNameResult.publishedCallOrNull();
                    assertNotNull(resolvedStringNameCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedStringNameCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedStringNameCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, resolvedStringNameCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), resolvedStringNameCall.argumentTypes());
                },
                () -> {
                    assertTrue(arrayResult.rootOwnsOutcome());
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, arrayResult.expressionType().status());
                    var publishedArrayType = arrayResult.expressionType().publishedType();
                    assertNotNull(publishedArrayType);
                    assertEquals("Array", publishedArrayType.getTypeName());
                    var resolvedArrayCall = arrayResult.publishedCallOrNull();
                    assertNotNull(resolvedArrayCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedArrayCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedArrayCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, resolvedArrayCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), resolvedArrayCall.argumentTypes());
                },
                () -> {
                    assertTrue(dictionaryResult.rootOwnsOutcome());
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, dictionaryResult.expressionType().status());
                    var publishedDictionaryType = dictionaryResult.expressionType().publishedType();
                    assertNotNull(publishedDictionaryType);
                    assertEquals("Dictionary", publishedDictionaryType.getTypeName());
                    var resolvedDictionaryCall = dictionaryResult.publishedCallOrNull();
                    assertNotNull(resolvedDictionaryCall);
                    assertEquals(FrontendCallResolutionStatus.RESOLVED, resolvedDictionaryCall.status());
                    assertEquals(FrontendCallResolutionKind.CONSTRUCTOR, resolvedDictionaryCall.callKind());
                    assertEquals(FrontendReceiverKind.TYPE_META, resolvedDictionaryCall.receiverKind());
                    assertEquals(List.of(GdVariantType.VARIANT), resolvedDictionaryCall.argumentTypes());
                },
                () -> {
                    assertTrue(nodeResult.rootOwnsOutcome());
                    assertEquals(FrontendExpressionTypeStatus.FAILED, nodeResult.expressionType().status());
                    var detailReason = nodeResult.expressionType().detailReason();
                    assertNotNull(detailReason);
                    assertTrue(detailReason.contains("Node.new(...)"));
                    assertNull(nodeResult.publishedCallOrNull());
                },
                () -> {
                    // Cast is no longer deferred: Variant source + hard target publishes RESOLVED(target).
                    var publishedCastType = castType;
                    assertNotNull(publishedCastType);
                    assertEquals(FrontendExpressionTypeStatus.RESOLVED, publishedCastType.status());
                    assertEquals("int", publishedCastType.publishedType().getTypeName());
                }
        );
    }

    @Test
    void resolveCallExpressionTypeAcceptsStableVariantSourcesAtFixedParameterBoundaries() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_variant_calls.gd",
                """
                        class_name ExpressionSemanticSupportVariantCalls
                        extends RefCounted
                        
                        func take_i(value: int) -> int:
                            return value
                        
                        func take_any(value) -> int:
                            return 1
                        
                        func ping(any_value: Variant, worker):
                            take_i(any_value)
                            take_i(worker.ping().length)
                            take_any(1)
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var calls = findNodes(findFunction(analyzed.ast(), "ping"), CallExpression.class, _ -> true);

        var exactVariantCall = support.resolveCallExpressionType(calls.get(0), publishedResolver, true, false);
        var dynamicVariantCall = support.resolveCallExpressionType(calls.get(1), publishedResolver, true, false);
        var packToVariantCall = support.resolveCallExpressionType(calls.get(2), publishedResolver, true, false);

        for (var result : List.of(exactVariantCall, dynamicVariantCall, packToVariantCall)) {
            assertTrue(result.rootOwnsOutcome());
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertNotNull(result.publishedCallOrNull());
        }

        assertEquals(List.of(GdVariantType.VARIANT), exactVariantCall.publishedCallOrNull().argumentTypes());
        assertEquals(List.of(GdVariantType.VARIANT), dynamicVariantCall.publishedCallOrNull().argumentTypes());
        assertEquals(List.of(GdIntType.INT), packToVariantCall.publishedCallOrNull().argumentTypes());
    }

    @Test
    void resolveCallExpressionTypeUsesPrimitiveCastBoundaryWithoutWeakeningOverloadSpecificity()
            throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_primitive_cast_calls.gd",
                """
                        class_name ExpressionSemanticSupportPrimitiveCastCalls
                        extends RefCounted
                        
                        func ping() -> void:
                            take_float(1)
                            pick_exact(1)
                            pick_float(1)
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var calls = findNodes(pingFunction, CallExpression.class, _ -> true);
        publishSyntheticIntBareCallOverloads(
                analyzed,
                calls.get(0),
                newCallable("take_float", GdFloatType.FLOAT, GdFloatType.FLOAT)
        );
        publishSyntheticIntBareCallOverloads(
                analyzed,
                calls.get(1),
                newCallable("pick_exact", GdIntType.INT, GdIntType.INT),
                newCallable("pick_exact", GdFloatType.FLOAT, GdFloatType.FLOAT)
        );
        publishSyntheticIntBareCallOverloads(
                analyzed,
                calls.get(2),
                newCallable("pick_float", GdFloatType.FLOAT, GdFloatType.FLOAT),
                newCallable("pick_float", GdVariantType.VARIANT, GdVariantType.VARIANT)
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var primitiveCastCall = support.resolveCallExpressionType(calls.get(0), publishedResolver, true, false);
        var exactPreferredCall = support.resolveCallExpressionType(calls.get(1), publishedResolver, true, false);
        var primitivePreferredCall = support.resolveCallExpressionType(calls.get(2), publishedResolver, true, false);

        assertAll(
                () -> assertTrue(primitiveCastCall.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, primitiveCastCall.expressionType().status()),
                () -> assertEquals(GdFloatType.FLOAT, primitiveCastCall.expressionType().publishedType()),
                () -> assertNotNull(primitiveCastCall.publishedCallOrNull()),
                () -> assertEquals(GdFloatType.FLOAT, primitiveCastCall.publishedCallOrNull().returnType()),
                () -> assertEquals(List.of(GdIntType.INT), primitiveCastCall.publishedCallOrNull().argumentTypes()),
                () -> assertTrue(exactPreferredCall.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, exactPreferredCall.expressionType().status()),
                () -> assertEquals(GdIntType.INT, exactPreferredCall.publishedCallOrNull().returnType()),
                () -> assertEquals(List.of(GdIntType.INT), exactPreferredCall.publishedCallOrNull().argumentTypes()),
                () -> assertTrue(primitivePreferredCall.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, primitivePreferredCall.expressionType().status()),
                () -> assertEquals(GdFloatType.FLOAT, primitivePreferredCall.publishedCallOrNull().returnType()),
                () -> assertEquals(List.of(GdIntType.INT), primitivePreferredCall.publishedCallOrNull().argumentTypes())
        );
    }

    @Test
    void resolveCallExpressionTypeUsesVectorIntrinsicBoundaryWithoutWeakeningOverloadSpecificity()
            throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_vector_cast_calls.gd",
                """
                        class_name ExpressionSemanticSupportVectorCastCalls
                        extends RefCounted
                        
                        func ping() -> void:
                            take_vector(Vector3i(1, 2, 3))
                            pick_exact(Vector3i(1, 2, 3))
                            pick_vector(Vector3i(1, 2, 3))
                            reject_reverse(Vector3(1.0, 2.0, 3.0))
                        """
        );

        var pingFunction = findFunction(analyzed.ast(), "ping");
        var takeVectorCall = findBareCall(pingFunction, "take_vector");
        var pickExactCall = findBareCall(pingFunction, "pick_exact");
        var pickVectorCall = findBareCall(pingFunction, "pick_vector");
        var rejectReverseCall = findBareCall(pingFunction, "reject_reverse");
        publishSyntheticBareCallOverloads(
                analyzed,
                takeVectorCall,
                GdIntVectorType.VECTOR3I,
                newCallable("take_vector", GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3)
        );
        publishSyntheticBareCallOverloads(
                analyzed,
                pickExactCall,
                GdIntVectorType.VECTOR3I,
                newCallable("pick_exact", GdIntVectorType.VECTOR3I, GdIntVectorType.VECTOR3I),
                newCallable("pick_exact", GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3)
        );
        publishSyntheticBareCallOverloads(
                analyzed,
                pickVectorCall,
                GdIntVectorType.VECTOR3I,
                newCallable("pick_vector", GdFloatVectorType.VECTOR3, GdFloatVectorType.VECTOR3),
                newCallable("pick_vector", GdVariantType.VARIANT, GdVariantType.VARIANT)
        );
        publishSyntheticBareCallOverloads(
                analyzed,
                rejectReverseCall,
                GdFloatVectorType.VECTOR3,
                newCallable("reject_reverse", GdIntVectorType.VECTOR3I, GdIntVectorType.VECTOR3I)
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var vectorCastCall = support.resolveCallExpressionType(takeVectorCall, publishedResolver, true, false);
        var exactPreferredCall = support.resolveCallExpressionType(pickExactCall, publishedResolver, true, false);
        var vectorPreferredCall = support.resolveCallExpressionType(pickVectorCall, publishedResolver, true, false);
        var reverseRejectedCall = support.resolveCallExpressionType(rejectReverseCall, publishedResolver, true, false);

        assertAll(
                () -> assertTrue(vectorCastCall.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, vectorCastCall.expressionType().status()),
                () -> assertEquals(GdFloatVectorType.VECTOR3, vectorCastCall.publishedCallOrNull().returnType()),
                () -> assertEquals(List.of(GdIntVectorType.VECTOR3I), vectorCastCall.publishedCallOrNull().argumentTypes()),
                () -> assertTrue(exactPreferredCall.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, exactPreferredCall.expressionType().status()),
                () -> assertEquals(GdIntVectorType.VECTOR3I, exactPreferredCall.publishedCallOrNull().returnType()),
                () -> assertEquals(List.of(GdIntVectorType.VECTOR3I), exactPreferredCall.publishedCallOrNull().argumentTypes()),
                () -> assertTrue(vectorPreferredCall.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, vectorPreferredCall.expressionType().status()),
                () -> assertEquals(GdFloatVectorType.VECTOR3, vectorPreferredCall.publishedCallOrNull().returnType()),
                () -> assertEquals(List.of(GdIntVectorType.VECTOR3I), vectorPreferredCall.publishedCallOrNull().argumentTypes()),
                () -> assertTrue(reverseRejectedCall.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.FAILED, reverseRejectedCall.expressionType().status()),
                () -> assertTrue(reverseRejectedCall.expressionType().detailReason().contains("No applicable overload"))
        );
    }

    @Test
    void resolveCallExpressionTypeAcceptsNullSourcesAtObjectParameterBoundariesButRejectsScalarTargets()
            throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_null_object_calls.gd",
                """
                        class_name ExpressionSemanticSupportNullObjectCalls
                        extends RefCounted
                        
                        func take_obj(value: Object) -> int:
                            return 1
                        
                        func take_i(value: int) -> int:
                            return value
                        
                        func ping() -> void:
                            take_obj(null)
                            take_i(null)
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var calls = findNodes(findFunction(analyzed.ast(), "ping"), CallExpression.class, _ -> true);

        var objectCall = support.resolveCallExpressionType(calls.get(0), publishedResolver, true, false);
        var scalarCall = support.resolveCallExpressionType(calls.get(1), publishedResolver, true, false);

        assertTrue(objectCall.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, objectCall.expressionType().status());
        assertNotNull(objectCall.publishedCallOrNull());
        assertEquals(List.of(GdNilType.NIL), objectCall.publishedCallOrNull().argumentTypes());

        assertTrue(scalarCall.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, scalarCall.expressionType().status());
        assertTrue(scalarCall.expressionType().detailReason().contains("Nil"));
        assertTrue(scalarCall.expressionType().detailReason().contains("int"));
    }

    @Test
    void resolveCallExpressionTypeAcceptsInheritedBareVoidEngineCallsWithoutReturnValueMetadata() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_inherited_void_engine_calls.gd",
                """
                        class_name ExpressionSemanticSupportInheritedVoidEngineCalls
                        extends Node
                        
                        func ping() -> void:
                            add_to_group(&"alpha")
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var bareCallExpression = assertInstanceOf(
                CallExpression.class,
                assertInstanceOf(ExpressionStatement.class, findFunction(analyzed.ast(), "ping").body().statements().getFirst())
                        .expression()
        );

        var bareResult = support.resolveCallExpressionType(bareCallExpression, publishedResolver, true, false);
        var bareCall = bareResult.publishedCallOrNull();

        assertAll(
                () -> assertTrue(bareResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, bareResult.expressionType().status()),
                () -> assertEquals(GdVoidType.VOID, bareResult.expressionType().publishedType()),
                () -> assertNotNull(bareCall),
                () -> assertEquals(FrontendCallResolutionStatus.RESOLVED, bareCall.status()),
                () -> assertEquals(FrontendCallResolutionKind.INSTANCE_METHOD, bareCall.callKind()),
                () -> assertEquals(FrontendReceiverKind.INSTANCE, bareCall.receiverKind()),
                () -> assertEquals(GdVoidType.VOID, bareCall.returnType()),
                () -> assertNotNull(bareCall.declarationSite())
        );
    }

    @Test
    void resolveSubscriptExpressionTypePublishesResolvedAndDynamicOutcomes() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_subscript.gd",
                """
                        class_name ExpressionSemanticSupportSubscript
                        extends RefCounted
                        
                        func ping(items: Array[int], lookup: Dictionary[String, int], packed: PackedInt32Array, dynamic_value):
                            items[0]
                            lookup["hp"]
                            packed[0]
                            dynamic_value[0]
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var itemsSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getFirst()).expression()
        );
        var lookupSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var packedSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );
        var dynamicSubscript = assertInstanceOf(
                SubscriptExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
        );

        var itemsResult = support.resolveSubscriptExpressionType(itemsSubscript, publishedResolver, false);
        assertTrue(itemsResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, itemsResult.expressionType().status());
        assertEquals("int", itemsResult.expressionType().publishedType().getTypeName());

        var lookupResult = support.resolveSubscriptExpressionType(lookupSubscript, publishedResolver, false);
        assertTrue(lookupResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, lookupResult.expressionType().status());
        assertEquals("int", lookupResult.expressionType().publishedType().getTypeName());

        var packedResult = support.resolveSubscriptExpressionType(packedSubscript, publishedResolver, false);
        assertTrue(packedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, packedResult.expressionType().status());
        assertEquals("int", packedResult.expressionType().publishedType().getTypeName());

        var dynamicResult = support.resolveSubscriptExpressionType(dynamicSubscript, publishedResolver, false);
        assertTrue(dynamicResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, dynamicResult.expressionType().publishedType());
    }

    @Test
    void resolveUnaryExpressionTypePublishesResolvedDynamicAndFailedOutcomes() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_unary.gd",
                """
                        class_name ExpressionSemanticSupportUnary
                        extends RefCounted
                        
                        func ping(items: Array[int], dynamic_value, typed_variant: Variant):
                            -1
                            +1
                            ~1
                            !true
                            not true
                            not items
                            -dynamic_value
                            not typed_variant
                            ~"hello"
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var expressions = pingFunction.body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(UnaryExpression.class::cast)
                .toList();

        var negateResult = support.resolveUnaryExpressionType(expressions.get(0), publishedResolver, false);
        assertTrue(negateResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, negateResult.expressionType().status());
        assertEquals("int", negateResult.expressionType().publishedType().getTypeName());

        var positiveResult = support.resolveUnaryExpressionType(expressions.get(1), publishedResolver, false);
        assertTrue(positiveResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, positiveResult.expressionType().status());
        assertEquals("int", positiveResult.expressionType().publishedType().getTypeName());

        var bitNotResult = support.resolveUnaryExpressionType(expressions.get(2), publishedResolver, false);
        assertTrue(bitNotResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bitNotResult.expressionType().status());
        assertEquals("int", bitNotResult.expressionType().publishedType().getTypeName());

        var bangResult = support.resolveUnaryExpressionType(expressions.get(3), publishedResolver, false);
        assertTrue(bangResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bangResult.expressionType().status());
        assertEquals("bool", bangResult.expressionType().publishedType().getTypeName());

        var notResult = support.resolveUnaryExpressionType(expressions.get(4), publishedResolver, false);
        assertTrue(notResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, notResult.expressionType().status());
        assertEquals("bool", notResult.expressionType().publishedType().getTypeName());

        var typedArrayNotResult = support.resolveUnaryExpressionType(expressions.get(5), publishedResolver, false);
        assertTrue(typedArrayNotResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedArrayNotResult.expressionType().status());
        assertEquals("bool", typedArrayNotResult.expressionType().publishedType().getTypeName());

        var dynamicResult = support.resolveUnaryExpressionType(expressions.get(6), publishedResolver, false);
        assertTrue(dynamicResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, dynamicResult.expressionType().publishedType());

        var resolvedVariantResult = support.resolveUnaryExpressionType(expressions.get(7), publishedResolver, false);
        assertTrue(resolvedVariantResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, resolvedVariantResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, resolvedVariantResult.expressionType().publishedType());

        var invalidResult = support.resolveUnaryExpressionType(expressions.get(8), publishedResolver, false);
        assertTrue(invalidResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, invalidResult.expressionType().status());
        assertTrue(invalidResult.expressionType().detailReason().contains("not defined for operand type 'String'"));
    }

    @Test
    void resolveUnaryExpressionTypeRejectsUnknownOperatorsAndPropagatesDependencyFailures() throws Exception {
        var support = newBareSupport();
        var unknownOperatorResult = support.resolveUnaryExpressionType(
                new UnaryExpression("??", integerLiteral("1"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(unknownOperatorResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, unknownOperatorResult.expressionType().status());
        assertTrue(unknownOperatorResult.expressionType().detailReason().contains("Unknown unary source operator"));

        var analyzed = analyze(
                "expression_semantic_support_unary_dependency.gd",
                """
                        class_name ExpressionSemanticSupportUnaryDependency
                        extends RefCounted
                        
                        func ping():
                            -missing.payload
                        """
        );
        var dependencySupport = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var unaryExpression = assertInstanceOf(
                UnaryExpression.class,
                assertInstanceOf(
                        ExpressionStatement.class,
                        findFunction(analyzed.ast(), "ping").body().statements().getFirst()
                ).expression()
        );

        var propagatedResult = dependencySupport.resolveUnaryExpressionType(unaryExpression, publishedResolver, false);
        assertFalse(propagatedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, propagatedResult.expressionType().status());
        assertTrue(propagatedResult.expressionType().detailReason().contains("chain head"));
    }

    @Test
    void resolveBinaryExpressionTypePublishesMetadataDynamicSpecialAndNotInOutcomes() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_binary.gd",
                """
                        class_name ExpressionSemanticSupportBinary
                        extends RefCounted
                        
                        func ping(
                            ints_a: Array[int],
                            ints_b: Array[int],
                            names: Array[String],
                            raw_array: Array,
                            dynamic_value,
                            typed_variant: Variant
                        ):
                            1 + 2
                            1 - 2
                            1 * 2
                            1 == 2
                            1 < 2
                            1 & 2
                            1 in ints_a
                            dynamic_value + 1
                            typed_variant + 1
                            1 and 2
                            dynamic_value or 0
                            ints_a + ints_b
                            ints_a + names
                            ints_a + raw_array
                            1 not in ints_a
                            "hello" & 1
                            dynamic_value not in ints_a
                            typed_variant not in ints_a
                            "hello" not in 1
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var expressions = findFunction(analyzed.ast(), "ping").body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(BinaryExpression.class::cast)
                .toList();

        for (var index : List.of(0, 1, 2)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertTrue(result.rootOwnsOutcome());
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals("int", result.expressionType().publishedType().getTypeName());
        }
        var bitAndResult = support.resolveBinaryExpressionType(expressions.get(5), publishedResolver, false);
        assertTrue(bitAndResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bitAndResult.expressionType().status());
        assertEquals("int", bitAndResult.expressionType().publishedType().getTypeName());

        for (var index : List.of(3, 4, 6, 9, 10)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertTrue(result.rootOwnsOutcome());
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals("bool", result.expressionType().publishedType().getTypeName());
        }

        var dynamicAddResult = support.resolveBinaryExpressionType(expressions.get(7), publishedResolver, false);
        assertTrue(dynamicAddResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicAddResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, dynamicAddResult.expressionType().publishedType());

        var variantAddResult = support.resolveBinaryExpressionType(expressions.get(8), publishedResolver, false);
        assertTrue(variantAddResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, variantAddResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, variantAddResult.expressionType().publishedType());

        var typedArrayPreserveResult = support.resolveBinaryExpressionType(expressions.get(11), publishedResolver, false);
        assertTrue(typedArrayPreserveResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedArrayPreserveResult.expressionType().status());
        assertEquals("Array[int]", typedArrayPreserveResult.expressionType().publishedType().getTypeName());

        var mismatchedTypedArrayResult = support.resolveBinaryExpressionType(expressions.get(12), publishedResolver, false);
        assertTrue(mismatchedTypedArrayResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, mismatchedTypedArrayResult.expressionType().status());
        assertEquals("Array", mismatchedTypedArrayResult.expressionType().publishedType().getTypeName());

        var typedUntypedArrayResult = support.resolveBinaryExpressionType(expressions.get(13), publishedResolver, false);
        assertTrue(typedUntypedArrayResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedUntypedArrayResult.expressionType().status());
        assertEquals("Array", typedUntypedArrayResult.expressionType().publishedType().getTypeName());

        // Composite rule: a stable inner `in` pairing always publishes `bool`, even when the
        // operands are runtime-open (dynamic / Variant), because the runtime `in` result is
        // always a bool and only the legality check is deferred to runtime.
        for (var index : List.of(14, 16, 17)) {
            var notInResult = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertTrue(notInResult.rootOwnsOutcome());
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, notInResult.expressionType().status());
            assertEquals("bool", notInResult.expressionType().publishedType().getTypeName());
        }

        var invalidResult = support.resolveBinaryExpressionType(expressions.get(15), publishedResolver, false);
        assertTrue(invalidResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, invalidResult.expressionType().status());
        assertTrue(invalidResult.expressionType().detailReason().contains("not defined for operand types 'String' and 'int'"));

        // An illegal `not in` pairing fails with the diagnostic anchored on the inner `in` pair.
        var invalidNotInResult = support.resolveBinaryExpressionType(expressions.get(18), publishedResolver, false);
        assertTrue(invalidNotInResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, invalidNotInResult.expressionType().status());
        assertTrue(invalidNotInResult.expressionType().detailReason().contains(
                "Binary operator 'in' is not defined for operand types 'String' and 'int'"
        ));
    }

    @Test
    void resolveBinaryExpressionTypeAcceptsOnlyNarrowObjectNilEqualitySpecialRule() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_object_nil_binary.gd",
                """
                        class_name ExpressionSemanticSupportObjectNilBinary
                        extends RefCounted
                        
                        class Point extends RefCounted:
                            var next: Point = null
                        
                        func ping(point: Point, typed_variant: Variant, dynamic_value):
                            point != null
                            point == null
                            null != point
                            null == point
                            null == null
                            null != null
                            typed_variant == null
                            null == typed_variant
                            dynamic_value == null
                            null == dynamic_value
                            point < null
                            null < point
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var expressions = findFunction(analyzed.ast(), "ping").body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(BinaryExpression.class::cast)
                .toList();
        var pointType = publishedResolver.resolve(((BinaryExpression) expressions.getFirst()).left(), false);
        assertEquals(
                new GdObjectType("ExpressionSemanticSupportObjectNilBinary__sub__Point"),
                pointType.publishedType()
        );

        // These are the only source-level nil equality pairs that the current helper publishes as bool.
        for (var index : List.of(0, 1, 2, 3, 4, 5)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertAll(
                    () -> assertTrue(result.rootOwnsOutcome()),
                    () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status()),
                    () -> assertEquals("bool", result.expressionType().publishedType().getTypeName())
            );
        }

        for (var index : List.of(6, 7, 8, 9)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertAll(
                    () -> assertTrue(result.rootOwnsOutcome()),
                    () -> assertEquals(FrontendExpressionTypeStatus.DYNAMIC, result.expressionType().status()),
                    () -> assertEquals(GdVariantType.VARIANT, result.expressionType().publishedType())
            );
        }

        for (var index : List.of(10, 11)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertAll(
                    () -> assertTrue(result.rootOwnsOutcome()),
                    () -> assertEquals(FrontendExpressionTypeStatus.FAILED, result.expressionType().status()),
                    () -> assertTrue(result.expressionType().detailReason().contains("not defined for operand types"))
            );
        }
    }

    @Test
    void resolveBinaryExpressionTypeAcceptsObjectIdentityEqualitySpecialRule() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_object_identity_binary.gd",
                """
                        class_name ExpressionSemanticSupportObjectIdentityBinary
                        extends Node
                        
                        func ping(left: Node, right: Node, obj: Object, sprite: Sprite2D, label: Label, typed_variant: Variant, dynamic_value):
                            left == right
                            left != right
                            obj == obj
                            obj != left
                            left == obj
                            left < right
                            left <= obj
                            typed_variant == left
                            dynamic_value == left
                            self == left
                            sprite == label
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var expressions = findFunction(analyzed.ast(), "ping").body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(BinaryExpression.class::cast)
                .toList();
        var leftType = publishedResolver.resolve(expressions.getFirst().left(), false);
        var selfType = publishedResolver.resolve(expressions.get(9).left(), false);
        var spriteType = publishedResolver.resolve(expressions.get(10).left(), false);
        var labelType = publishedResolver.resolve(expressions.get(10).right(), false);
        assertEquals(new GdObjectType("Node"), leftType.publishedType());
        assertEquals(new GdObjectType("ExpressionSemanticSupportObjectIdentityBinary"), selfType.publishedType());
        assertEquals(new GdObjectType("Sprite2D"), spriteType.publishedType());
        assertEquals(new GdObjectType("Label"), labelType.publishedType());

        // Same-class, inheritance-related, unrelated, and GDCC/engine mixed pairs all resolve as identity bool.
        for (var index : List.of(0, 1, 2, 3, 4, 9, 10)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertAll(
                    () -> assertTrue(result.rootOwnsOutcome()),
                    () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status()),
                    () -> assertEquals("bool", result.expressionType().publishedType().getTypeName())
            );
        }

        for (var index : List.of(5, 6)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertAll(
                    () -> assertTrue(result.rootOwnsOutcome()),
                    () -> assertEquals(FrontendExpressionTypeStatus.FAILED, result.expressionType().status()),
                    () -> assertTrue(result.expressionType().detailReason().contains("not defined for operand types"))
            );
        }

        for (var index : List.of(7, 8)) {
            var result = support.resolveBinaryExpressionType(expressions.get(index), publishedResolver, false);
            assertAll(
                    () -> assertTrue(result.rootOwnsOutcome()),
                    () -> assertEquals(FrontendExpressionTypeStatus.DYNAMIC, result.expressionType().status()),
                    () -> assertEquals(GdVariantType.VARIANT, result.expressionType().publishedType())
            );
        }
    }

    @Test
    void resolveBinaryExpressionTypePreservesOperandOrderAndDependencyProvenance() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_binary_order.gd",
                """
                        class_name ExpressionSemanticSupportBinaryOrder
                        extends RefCounted
                        
                        func ping(items: Array[int]):
                            1 in items
                            items in 1
                            1 + missing.payload
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var expressions = findFunction(analyzed.ast(), "ping").body().statements().stream()
                .map(ExpressionStatement.class::cast)
                .map(ExpressionStatement::expression)
                .map(BinaryExpression.class::cast)
                .toList();

        var resolvedInResult = support.resolveBinaryExpressionType(expressions.get(0), publishedResolver, false);
        assertTrue(resolvedInResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, resolvedInResult.expressionType().status());
        assertEquals("bool", resolvedInResult.expressionType().publishedType().getTypeName());

        var reversedInResult = support.resolveBinaryExpressionType(expressions.get(1), publishedResolver, false);
        assertTrue(reversedInResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, reversedInResult.expressionType().status());
        assertTrue(reversedInResult.expressionType().detailReason().contains("operand types 'Array[int]' and 'int'"));

        var propagatedResult = support.resolveBinaryExpressionType(expressions.get(2), publishedResolver, false);
        assertFalse(propagatedResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, propagatedResult.expressionType().status());
        assertTrue(propagatedResult.expressionType().detailReason().contains("chain head"));

        var unknownOperatorResult = newBareSupport().resolveBinaryExpressionType(
                new BinaryExpression("??", integerLiteral("1"), integerLiteral("2"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(unknownOperatorResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, unknownOperatorResult.expressionType().status());
        assertTrue(unknownOperatorResult.expressionType().detailReason().contains("Unknown binary source operator"));
    }

    @Test
    void resolveBinaryExpressionTypeAcceptsLogicalSourceAliases() throws Exception {
        var support = newBareSupport();

        var logicalAndResult = support.resolveBinaryExpressionType(
                new BinaryExpression("&&", integerLiteral("1"), integerLiteral("2"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(logicalAndResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, logicalAndResult.expressionType().status());
        assertEquals("bool", logicalAndResult.expressionType().publishedType().getTypeName());

        var logicalOrResult = support.resolveBinaryExpressionType(
                new BinaryExpression("||", identifier("payload"), integerLiteral("0"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression
                        ? FrontendExpressionType.dynamic("synthetic runtime-open payload")
                        : FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(logicalOrResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, logicalOrResult.expressionType().status());
        assertEquals("bool", logicalOrResult.expressionType().publishedType().getTypeName());
    }

    @Test
    void resolveBinaryExpressionTypeStringFormatPublishesStringForRuntimeOpenRightOperand() throws Exception {
        var support = newBareSupport();

        // Happy path: a typed Variant right operand keeps static String precision.
        var variantRightResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("fmt"), identifier("payload"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("fmt")
                        ? FrontendExpressionType.resolved(GdStringType.STRING)
                        : FrontendExpressionType.resolved(GdVariantType.VARIANT),
                false
        );
        assertTrue(variantRightResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, variantRightResult.expressionType().status());
        assertEquals(GdStringType.STRING, variantRightResult.expressionType().publishedType());

        // Happy path: an untyped (dynamic) right operand keeps static String precision.
        var dynamicRightResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("fmt"), identifier("payload"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("fmt")
                        ? FrontendExpressionType.resolved(GdStringType.STRING)
                        : FrontendExpressionType.dynamic("synthetic runtime-open payload"),
                false
        );
        assertTrue(dynamicRightResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, dynamicRightResult.expressionType().status());
        assertEquals(GdStringType.STRING, dynamicRightResult.expressionType().publishedType());
    }

    @Test
    void resolveBinaryExpressionTypeStringFormatKeepsBoundariesAndInvariants() throws Exception {
        var support = newBareSupport();

        // Invariant: exact static right operands still resolve through metadata unchanged.
        var exactIntResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("fmt"), identifier("value"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("fmt")
                        ? FrontendExpressionType.resolved(GdStringType.STRING)
                        : FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(exactIntResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, exactIntResult.expressionType().status());
        assertEquals(GdStringType.STRING, exactIntResult.expressionType().publishedType());

        // Negative: a named object subclass right operand stays fail-closed (exact-name matching
        // has no entry for "Node"), and the FAILED detail wording stays unchanged.
        var nodeRightResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("fmt"), identifier("node"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("fmt")
                        ? FrontendExpressionType.resolved(GdStringType.STRING)
                        : FrontendExpressionType.resolved(new GdObjectType("Node")),
                false
        );
        assertTrue(nodeRightResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, nodeRightResult.expressionType().status());
        assertTrue(nodeRightResult.expressionType().detailReason().contains(
                "Binary operator '%' is not defined for operand types 'String' and 'Node'"
        ));

        // Negative: a `null` right operand stays fail-closed and reports the Nil type name.
        var nilRightResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("fmt"), identifier("payload"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("fmt")
                        ? FrontendExpressionType.resolved(GdStringType.STRING)
                        : FrontendExpressionType.resolved(GdNilType.NIL),
                false
        );
        assertTrue(nilRightResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, nilRightResult.expressionType().status());
        assertTrue(nilRightResult.expressionType().detailReason().contains(
                "Binary operator '%' is not defined for operand types 'String' and 'Nil'"
        ));

        // Negative: MODULE with a non-String left operand is unchanged numeric-modulo semantics.
        var intLeftResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("value"), identifier("fmt"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("fmt")
                        ? FrontendExpressionType.resolved(GdStringType.STRING)
                        : FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(intLeftResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, intLeftResult.expressionType().status());
        assertTrue(intLeftResult.expressionType().detailReason().contains(
                "Binary operator '%' is not defined for operand types 'int' and 'String'"
        ));

        // Invariant: a runtime-open left operand still routes through Variant semantics.
        var variantLeftResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("payload"), identifier("value"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("payload")
                        ? FrontendExpressionType.resolved(GdVariantType.VARIANT)
                        : FrontendExpressionType.resolved(GdIntType.INT),
                false
        );
        assertTrue(variantLeftResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, variantLeftResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, variantLeftResult.expressionType().publishedType());

        // Invariant: a StringName left operand is out of scope for the String-format rule.
        var stringNameLeftResult = support.resolveBinaryExpressionType(
                new BinaryExpression("%", identifier("fmt"), identifier("payload"), TINY),
                (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                        && identifier.name().equals("fmt")
                        ? FrontendExpressionType.resolved(GdStringNameType.STRING_NAME)
                        : FrontendExpressionType.resolved(GdVariantType.VARIANT),
                false
        );
        assertTrue(stringNameLeftResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, stringNameLeftResult.expressionType().status());
        assertEquals(GdVariantType.VARIANT, stringNameLeftResult.expressionType().publishedType());
    }

    @Test
    void resolveBinaryExpressionTypeStringFormatResolvesMetadataOperandMatrix() throws Exception {
        var support = newBareSupport();

        // Representative sample of the `String % T -> String` metadata matrix, including the
        // container normalization entries (Array[T] -> "Array", Dictionary[K,V] -> "Dictionary")
        // and the exact-name "Object" entry. The full matrix is inherited from the Godot
        // evaluator and is not enumerated exhaustively here.
        var sampledRightTypes = List.<GdType>of(
                GdIntType.INT,
                GdFloatType.FLOAT,
                GdBoolType.BOOL,
                GdStringType.STRING,
                new GdArrayType(GdVariantType.VARIANT),
                new GdArrayType(GdIntType.INT),
                new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT),
                new GdDictionaryType(GdStringType.STRING, GdIntType.INT),
                GdPackedStringArrayType.PACKED_STRING_ARRAY,
                GdObjectType.OBJECT
        );
        for (var rightType : sampledRightTypes) {
            var result = support.resolveBinaryExpressionType(
                    new BinaryExpression("%", identifier("fmt"), identifier("payload"), TINY),
                    (expression, finalizeWindow) -> expression instanceof IdentifierExpression identifier
                            && identifier.name().equals("fmt")
                            ? FrontendExpressionType.resolved(GdStringType.STRING)
                            : FrontendExpressionType.resolved(rightType),
                    false
            );
            assertTrue(result.rootOwnsOutcome(), rightType.getTypeName());
            assertEquals(
                    FrontendExpressionTypeStatus.RESOLVED,
                    result.expressionType().status(),
                    rightType.getTypeName()
            );
            assertEquals(GdStringType.STRING, result.expressionType().publishedType(), rightType.getTypeName());
        }
    }

    @Test
    void resolveConditionalExpressionMergesArmTypesAcrossBoundaryMatrix() throws Exception {
        var support = newBareSupport();
        var stringType = GdStringType.STRING;
        var stringNameType = GdStringNameType.STRING_NAME;

        // Same-type arms stay unchanged.
        assertEquals("String", mergeConditionalArms(support,
                FrontendExpressionType.resolved(stringType),
                FrontendExpressionType.resolved(stringType)).publishedType().getTypeName());

        // int/float widens to float regardless of arm order.
        assertEquals("float", mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdIntType.INT),
                FrontendExpressionType.resolved(GdFloatType.FLOAT)).publishedType().getTypeName());
        assertEquals("float", mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdFloatType.FLOAT),
                FrontendExpressionType.resolved(GdIntType.INT)).publishedType().getTypeName());

        // Object sub/super merges to the super class regardless of arm order.
        var subClass = new GdObjectType("Node2D");
        var superClass = new GdObjectType("Node");
        assertEquals("Node", mergeConditionalArms(support,
                FrontendExpressionType.resolved(subClass),
                FrontendExpressionType.resolved(superClass)).publishedType().getTypeName());
        assertEquals("Node", mergeConditionalArms(support,
                FrontendExpressionType.resolved(superClass),
                FrontendExpressionType.resolved(subClass)).publishedType().getTypeName());

        // null/object merges to object regardless of arm order.
        assertEquals("Object", mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdNilType.NIL),
                FrontendExpressionType.resolved(GdObjectType.OBJECT)).publishedType().getTypeName());
        assertEquals("Object", mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdObjectType.OBJECT),
                FrontendExpressionType.resolved(GdNilType.NIL)).publishedType().getTypeName());

        // Both-nil arms stay Nil instead of degrading to Variant.
        var nilMerged = mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdNilType.NIL),
                FrontendExpressionType.resolved(GdNilType.NIL));
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, nilMerged.status());
        assertEquals(GdNilType.NIL, nilMerged.publishedType());

        // Mutually incompatible scalar arms fall back to Variant without a diagnostic.
        var incompatible = mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdIntType.INT),
                FrontendExpressionType.resolved(stringType));
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, incompatible.status());
        assertEquals(GdVariantType.VARIANT, incompatible.publishedType());

        // Typed containers do not widen element scalars: Array[int]/Array[float] -> Variant.
        var containerMismatch = mergeConditionalArms(support,
                FrontendExpressionType.resolved(new GdArrayType(GdIntType.INT)),
                FrontendExpressionType.resolved(new GdArrayType(GdFloatType.FLOAT)));
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, containerMismatch.status());
        assertEquals(GdVariantType.VARIANT, containerMismatch.publishedType());

        // String/StringName are mutually constructible; the first matching direction wins (left-biased).
        assertEquals("String", mergeConditionalArms(support,
                FrontendExpressionType.resolved(stringType),
                FrontendExpressionType.resolved(stringNameType)).publishedType().getTypeName());
        assertEquals("StringName", mergeConditionalArms(support,
                FrontendExpressionType.resolved(stringNameType),
                FrontendExpressionType.resolved(stringType)).publishedType().getTypeName());
    }

    @Test
    void resolveConditionalExpressionPublishesDynamicForRuntimeOpenArmsAndUnsupportedForVoidArms() throws Exception {
        var support = newBareSupport();

        // A Variant arm keeps the merge runtime-open regardless of position.
        var variantLeft = mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdVariantType.VARIANT),
                FrontendExpressionType.resolved(GdIntType.INT));
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, variantLeft.status());
        assertEquals(GdVariantType.VARIANT, variantLeft.publishedType());
        var variantRight = mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdIntType.INT),
                FrontendExpressionType.resolved(GdVariantType.VARIANT));
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, variantRight.status());

        // A DYNAMIC-status arm also keeps the merge runtime-open.
        var dynamicArm = mergeConditionalArms(support,
                FrontendExpressionType.dynamic("synthetic runtime-open arm"),
                FrontendExpressionType.resolved(GdIntType.INT));
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, dynamicArm.status());

        // A void arm is an explicit unsupported boundary regardless of position.
        var voidLeft = mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdVoidType.VOID),
                FrontendExpressionType.resolved(GdIntType.INT));
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, voidLeft.status());
        var voidRight = mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdIntType.INT),
                FrontendExpressionType.resolved(GdVoidType.VOID));
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, voidRight.status());

        // Ordering lock: a runtime-open arm is checked before the void boundary, so void+Variant
        // stays DYNAMIC. This is intentional: at runtime the void arm evaluates to Variant nil,
        // which the Variant merge slot holds, so the pair is runtime-open rather than unsupported.
        var voidVariant = mergeConditionalArms(support,
                FrontendExpressionType.resolved(GdVoidType.VOID),
                FrontendExpressionType.resolved(GdVariantType.VARIANT));
        assertEquals(FrontendExpressionTypeStatus.DYNAMIC, voidVariant.status());
    }

    @Test
    void resolveConditionalExpressionDoesNotResolveArmsWhenConditionIsUnstable() throws Exception {
        var support = newBareSupport();
        var resolvedArms = new ArrayList<String>();
        var condition = identifier("condition_operand");
        var left = identifier("left_arm");
        var right = identifier("right_arm");
        var conditional = new ConditionalExpression(condition, left, right, TINY);
        FrontendExpressionSemanticSupport.ContextualNestedExpressionResolver resolver =
                (expression, finalizeWindow, expectedType) -> {
                    if (expression == condition) {
                        return FrontendExpressionType.failed("synthetic condition failure");
                    }
                    if (expression == left) {
                        resolvedArms.add("left");
                        return FrontendExpressionType.resolved(GdIntType.INT);
                    }
                    resolvedArms.add("right");
                    return FrontendExpressionType.resolved(GdIntType.INT);
                };

        var result = support.resolveConditionalExpressionType(conditional, resolver, false, null);
        assertFalse(result.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, result.expressionType().status());
        assertTrue(resolvedArms.isEmpty(), "arms must not be resolved once the condition is unstable");
    }

    @Test
    void resolveConditionalExpressionPropagatesUnstableOperandsWithoutRootOwnership() throws Exception {
        var support = newBareSupport();
        var failedArm = FrontendExpressionType.failed("synthetic arm failure");
        var stableInt = FrontendExpressionType.resolved(GdIntType.INT);

        // An unstable condition propagates before either arm is consulted.
        var conditionFailed = resolveConditionalWithArmTypes(support, failedArm, stableInt, stableInt);
        assertFalse(conditionFailed.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, conditionFailed.expressionType().status());

        // An unstable left arm propagates.
        var leftFailed = resolveConditionalWithArmTypes(support, stableInt, failedArm, stableInt);
        assertFalse(leftFailed.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, leftFailed.expressionType().status());

        // An unstable right arm propagates.
        var rightFailed = resolveConditionalWithArmTypes(support, stableInt, stableInt, failedArm);
        assertFalse(rightFailed.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, rightFailed.expressionType().status());
    }

    @Test
    void resolveConditionalExpressionPassesExpectedTypeToBothArmsButNotToCondition() throws Exception {
        var support = newBareSupport();
        var expected = new GdArrayType(GdIntType.INT);
        var receivedByLeft = new ArrayList<GdType>();
        var receivedByRight = new ArrayList<GdType>();
        var condition = identifier("condition_operand");
        var left = identifier("left_arm");
        var right = identifier("right_arm");
        var conditional = new ConditionalExpression(condition, left, right, TINY);
        FrontendExpressionSemanticSupport.ContextualNestedExpressionResolver resolver =
                (expression, finalizeWindow, expectedType) -> {
                    if (expression == condition) {
                        assertNull(expectedType, "condition must resolve in a pure control-flow context");
                        return FrontendExpressionType.resolved(GdIntType.INT);
                    }
                    if (expression == left) {
                        receivedByLeft.add(expectedType);
                        return FrontendExpressionType.resolved(expected);
                    }
                    receivedByRight.add(expectedType);
                    return FrontendExpressionType.resolved(expected);
                };

        var result = support.resolveConditionalExpressionType(conditional, resolver, false, expected);
        assertTrue(result.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
        assertEquals(expected, result.expressionType().publishedType());
        assertEquals(List.of(expected), receivedByLeft);
        assertEquals(List.of(expected), receivedByRight);
    }

    private static @NotNull FrontendExpressionType mergeConditionalArms(
            @NotNull FrontendExpressionSemanticSupport support,
            @NotNull FrontendExpressionType leftArm,
            @NotNull FrontendExpressionType rightArm
    ) {
        var result = resolveConditionalWithArmTypes(
                support,
                FrontendExpressionType.resolved(GdIntType.INT),
                leftArm,
                rightArm
        );
        assertTrue(result.rootOwnsOutcome());
        return result.expressionType();
    }

    private static @NotNull FrontendExpressionSemanticSupport.ExpressionSemanticResult resolveConditionalWithArmTypes(
            @NotNull FrontendExpressionSemanticSupport support,
            @NotNull FrontendExpressionType conditionType,
            @NotNull FrontendExpressionType leftArm,
            @NotNull FrontendExpressionType rightArm
    ) {
        var condition = identifier("condition_operand");
        var left = identifier("left_arm");
        var right = identifier("right_arm");
        var conditional = new ConditionalExpression(condition, left, right, TINY);
        FrontendExpressionSemanticSupport.ContextualNestedExpressionResolver resolver =
                (expression, finalizeWindow, expectedType) -> {
                    if (expression == condition) {
                        return conditionType;
                    }
                    if (expression == left) {
                        return leftArm;
                    }
                    if (expression == right) {
                        return rightArm;
                    }
                    throw new IllegalStateException("unexpected nested expression " + expression);
                };
        return support.resolveConditionalExpressionType(conditional, resolver, false, null);
    }

    @Test
    void resolveRemainingExplicitExpressionRoutesEnumerateRemainingDeferredKindsAndRejectParserRecoveryNodes()
            throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();
        var typeRef = new TypeRef("String", TINY);
        var literal = integerLiteral("1");
        var cases = List.of(
                new RemainingExpressionCase(
                        new PatternBindingExpression("captured", TINY),
                        FrontendExpressionTypeStatus.DEFERRED,
                        "Pattern binding expression typing is deferred"
                ),
                new RemainingExpressionCase(
                        new UnknownExpression("recovery_node", "??", TINY),
                        FrontendExpressionTypeStatus.UNSUPPORTED,
                        "Parser recovery expression 'recovery_node'"
                )
        );

        for (var testCase : cases) {
            var result = support.resolveRemainingExplicitExpressionType(
                    testCase.expression(),
                    nestedResolver,
                    true,
                    false
            );
            assertTrue(result.rootOwnsOutcome(), () -> "expected root-owned outcome for " + testCase.expression());
            assertEquals(testCase.status(), result.expressionType().status());
            assertTrue(
                    result.expressionType().detailReason().contains(testCase.reasonFragment()),
                    () -> "expected detail reason to contain '" + testCase.reasonFragment() + "' but got '"
                            + result.expressionType().detailReason() + "'"
            );
        }

        // Type-test is no longer deferred: remaining-route entry resolves to RESOLVED(bool).
        var typeTest = new TypeTestExpression(identifier("value"), typeRef, false, TINY);
        var typeTestResult = support.resolveRemainingExplicitExpressionType(
                typeTest,
                nestedResolver,
                true,
                false
        );
        assertTrue(typeTestResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typeTestResult.expressionType().status());
        assertEquals("bool", typeTestResult.expressionType().publishedType().getTypeName());
        assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                typeTestResult.publishedTypeTestTargetOrNull()
        );

        // Cast is no longer deferred: remaining-route entry resolves to RESOLVED(target).
        var castExpression = new CastExpression(identifier("value"), typeRef, TINY);
        var castResult = support.resolveRemainingExplicitExpressionType(
                castExpression,
                nestedResolver,
                true,
                false
        );
        assertTrue(castResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, castResult.expressionType().status());
        assertEquals("String", castResult.expressionType().publishedType().getTypeName());
        assertNull(castResult.publishedTypeTestTargetOrNull());

        // Container literals are no longer deferred: remaining-route publishes generic Array/Dictionary.
        var arrayExpression = new ArrayExpression(List.of(integerLiteral("1")), false, TINY);
        var arrayResult = support.resolveRemainingExplicitExpressionType(
                arrayExpression,
                nestedResolver,
                true,
                false
        );
        assertTrue(arrayResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, arrayResult.expressionType().status());
        assertEquals("Array", arrayResult.expressionType().publishedType().getTypeName());
        assertNotNull(arrayResult.publishedContainerLiteralPlanOrNull());

        var dictionaryExpression = new DictionaryExpression(
                List.of(new DictEntry(stringLiteral("\"hp\""), integerLiteral("1"), TINY)),
                false,
                TINY
        );
        var dictionaryResult = support.resolveRemainingExplicitExpressionType(
                dictionaryExpression,
                nestedResolver,
                true,
                false
        );
        assertTrue(dictionaryResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, dictionaryResult.expressionType().status());
        assertEquals("Dictionary", dictionaryResult.expressionType().publishedType().getTypeName());
        assertNotNull(dictionaryResult.publishedContainerLiteralPlanOrNull());

        // Preload resolves at sema: a string-literal path resolves to RESOLVED(Resource) without
        // publishing a resolved call; a non-literal path fails with the dedicated literal
        // requirement.
        var preloadResult = support.resolveRemainingExplicitExpressionType(
                new PreloadExpression(stringLiteral("\"res://icon.svg\""), TINY),
                nestedResolver,
                true,
                false
        );
        assertTrue(preloadResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, preloadResult.expressionType().status());
        assertEquals("Resource", preloadResult.expressionType().publishedType().getTypeName());
        assertNull(preloadResult.publishedCallOrNull());

        var dynamicPreloadResult = support.resolveRemainingExplicitExpressionType(
                new PreloadExpression(identifier("dynamic_path"), TINY),
                nestedResolver,
                true,
                false
        );
        assertTrue(dynamicPreloadResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, dynamicPreloadResult.expressionType().status());
        assertTrue(
                dynamicPreloadResult.expressionType().detailReason().contains("string literal"),
                () -> "unexpected detail reason: " + dynamicPreloadResult.expressionType().detailReason()
        );

        // Await is not a deferred remaining route: it owns a dedicated classifier
        // (`frontend_await_implementation.md` §8) and the generic bucket must reject it.
        var awaitExpression = new AwaitExpression(identifier("signal_name"), TINY);
        assertThrows(
                IllegalArgumentException.class,
                () -> support.resolveRemainingExplicitExpressionType(
                        awaitExpression,
                        nestedResolver,
                        true,
                        false
                )
        );

        // Get-node is not a deferred remaining route either: the body-owner hook classifies it
        // (RESOLVED(Node) inside Node-derived instance functions, fail-closed boundaries
        // otherwise), so the generic bucket must reject it.
        var getNodeExpression = new GetNodeExpression("$Camera3D", TINY);
        assertThrows(
                IllegalArgumentException.class,
                () -> support.resolveRemainingExplicitExpressionType(
                        getNodeExpression,
                        nestedResolver,
                        true,
                        false
                )
        );
    }

    @Test
    void analyzeResolvesGetNodeExpressionToNodeInsideNodeDerivedInstanceFunction() throws Exception {
        var analyzed = analyze(
                "get_node_sema_positive.gd",
                """
                        class_name GetNodeSemaPositive
                        extends Node
                        
                        func probe():
                            var plain = $Camera3D
                            var unique = %Foo
                        """
        );

        var getNodeExpressions = findNodes(analyzed.ast(), GetNodeExpression.class, ignored -> true);
        assertEquals(2, getNodeExpressions.size());
        for (var getNodeExpression : getNodeExpressions) {
            var published = analyzed.analysisData().expressionTypes().get(getNodeExpression);
            assertNotNull(published);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, published.status());
            assertEquals(new GdObjectType("Node"), published.publishedType());
        }
        assertFalse(analyzed.analysisData().diagnostics().hasErrors());
        assertTrue(analyzed.analysisData().diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals("sema.deferred_expression_resolution")));
        // Get-node never publishes into the resolved-call key space (preload precedent): lowering
        // desugars to `self.get_node(NodePath)` without sema-level call facts.
        assertTrue(analyzed.analysisData().resolvedCalls().isEmpty());
    }

    @Test
    void analyzeFailsGetNodeExpressionInsideStaticFunction() throws Exception {
        var analyzed = analyze(
                "get_node_sema_static.gd",
                """
                        class_name GetNodeSemaStatic
                        extends Node
                        
                        static func probe():
                            var child = $Camera3D
                        """
        );

        var getNodeExpression = findNode(analyzed.ast(), GetNodeExpression.class, ignored -> true);
        var published = analyzed.analysisData().expressionTypes().get(getNodeExpression);
        assertNotNull(published);
        assertEquals(FrontendExpressionTypeStatus.FAILED, published.status());
        var expressionErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .toList();
        assertEquals(1, expressionErrors.size());
        assertTrue(expressionErrors.getFirst().message().contains("static function"));
    }

    @Test
    void analyzeFailsGetNodeExpressionOutsideNodeDerivedClass() throws Exception {
        var analyzed = analyze(
                "get_node_sema_non_node.gd",
                """
                        class_name GetNodeSemaNonNode
                        extends RefCounted
                        
                        func probe():
                            var child = $Camera3D
                        """
        );

        var getNodeExpression = findNode(analyzed.ast(), GetNodeExpression.class, ignored -> true);
        var published = analyzed.analysisData().expressionTypes().get(getNodeExpression);
        assertNotNull(published);
        assertEquals(FrontendExpressionTypeStatus.FAILED, published.status());
        var expressionErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .toList();
        assertEquals(1, expressionErrors.size());
        assertTrue(expressionErrors.getFirst().message().contains("inherits from Node"));
    }

    @Test
    void analyzeDefersGetNodeExpressionInsidePropertyInitializer() throws Exception {
        var analyzed = analyze(
                "get_node_sema_property_init.gd",
                """
                        class_name GetNodeSemaPropertyInit
                        extends Node
                        
                        var camera = $Camera3D
                        """
        );

        var getNodeExpression = findNode(analyzed.ast(), GetNodeExpression.class, ignored -> true);
        var published = analyzed.analysisData().expressionTypes().get(getNodeExpression);
        assertNotNull(published);
        assertEquals(FrontendExpressionTypeStatus.DEFERRED, published.status());
        assertTrue(published.detailReason().startsWith("Get-node expression"));
        // Deferred stays a warning-only outcome on the shared analyze path; the compile gate owns
        // the escalation into a compile-blocking error.
        assertFalse(analyzed.analysisData().diagnostics().hasErrors());
        var deferredWarnings = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.deferred_expression_resolution"))
                .toList();
        assertEquals(1, deferredWarnings.size());
        assertTrue(deferredWarnings.getFirst().message().contains("property initializers"));
    }

    @Test
    void analyzeResolvesGetNodeExpressionInsideNodeDerivedInstanceLambda() throws Exception {
        var analyzed = analyze(
                "get_node_sema_lambda_instance.gd",
                """
                        class_name GetNodeSemaLambdaInstance
                        extends Node
                        
                        func probe():
                            var callback = func():
                                var child = $Camera3D
                                return $Camera3D.name
                        """
        );

        // Both the statement-position and the chain-head get-node publish RESOLVED(Node) with the
        // same rules as a plain function body; the receiver comes from the leading self capture.
        var getNodeExpressions = findNodes(analyzed.ast(), GetNodeExpression.class, ignored -> true);
        assertEquals(2, getNodeExpressions.size());
        for (var getNodeExpression : getNodeExpressions) {
            var published = analyzed.analysisData().expressionTypes().get(getNodeExpression);
            assertNotNull(published);
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, published.status());
            assertEquals(new GdObjectType("Node"), published.publishedType());
        }
        assertFalse(analyzed.analysisData().diagnostics().hasErrors());
        assertTrue(analyzed.analysisData().diagnostics().asList().stream()
                .noneMatch(diagnostic -> diagnostic.category().equals("sema.deferred_expression_resolution")));
        // Get-node never publishes into the resolved-call key space (preload precedent), inside
        // lambda bodies either.
        assertTrue(analyzed.analysisData().resolvedCalls().isEmpty());
    }

    @Test
    void analyzeFailsGetNodeExpressionInsideStaticFunctionLambda() throws Exception {
        var analyzed = analyze(
                "get_node_sema_lambda_static.gd",
                """
                        class_name GetNodeSemaLambdaStatic
                        extends Node
                        
                        static func probe():
                            var callback = func():
                                return $Camera3D
                        """
        );

        // Static enclosing callable: capture planning synthesizes no self capture, and the sema
        // hook reuses the function-body static wording rather than a lambda-specific one.
        var getNodeExpression = findNode(analyzed.ast(), GetNodeExpression.class, ignored -> true);
        var published = analyzed.analysisData().expressionTypes().get(getNodeExpression);
        assertNotNull(published);
        assertEquals(FrontendExpressionTypeStatus.FAILED, published.status());
        var expressionErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .toList();
        assertEquals(1, expressionErrors.size());
        assertTrue(expressionErrors.getFirst().message().contains("static function"));
    }

    @Test
    void analyzeFailsGetNodeExpressionInsideNonNodeClassLambda() throws Exception {
        var analyzed = analyze(
                "get_node_sema_lambda_non_node.gd",
                """
                        class_name GetNodeSemaLambdaNonNode
                        extends RefCounted
                        
                        func probe():
                            var callback = func():
                                return $Camera3D
                        """
        );

        // The leading self capture exists (instance function) but its type is not
        // Node-assignable, so the class-hierarchy wording applies.
        var getNodeExpression = findNode(analyzed.ast(), GetNodeExpression.class, ignored -> true);
        var published = analyzed.analysisData().expressionTypes().get(getNodeExpression);
        assertNotNull(published);
        assertEquals(FrontendExpressionTypeStatus.FAILED, published.status());
        var expressionErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .toList();
        assertEquals(1, expressionErrors.size());
        assertTrue(expressionErrors.getFirst().message().contains("inherits from Node"));
    }

    @Test
    void resolveAwaitExpressionClassifiesSignalOperandsByParameterCount() throws Exception {
        var support = newBareSupport();

        var noParamSignal = new AwaitExpression(identifier("pinged"), TINY);
        var noParamResult = support.resolveAwaitExpressionType(
                noParamSignal,
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(new GdSignalType()),
                _ -> null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.SIGNAL, noParamResult.route()),
                () -> assertTrue(noParamResult.rootOwnsOutcome()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, noParamResult.expressionType().status()),
                () -> assertEquals("Variant", noParamResult.expressionType().publishedType().getTypeName())
        );

        var oneParamSignal = new AwaitExpression(identifier("pinged"), TINY);
        var oneParamResult = support.resolveAwaitExpressionType(
                oneParamSignal,
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(
                        new GdSignalType(List.of(GdIntType.INT))
                ),
                _ -> null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.SIGNAL, oneParamResult.route()),
                () -> assertEquals("int", oneParamResult.expressionType().publishedType().getTypeName())
        );

        var multiParamSignal = new AwaitExpression(identifier("pinged"), TINY);
        var multiParamResult = support.resolveAwaitExpressionType(
                multiParamSignal,
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(
                        new GdSignalType(List.of(GdIntType.INT, GdStringType.STRING))
                ),
                _ -> null,
                null,
                false
        );
        var multiParamType = assertInstanceOf(
                GdArrayType.class,
                multiParamResult.expressionType().publishedType()
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.SIGNAL, multiParamResult.route()),
                // `Array[Variant]` is the generic array in this type system (Variant element).
                () -> assertTrue(multiParamType.isGenericArray()),
                () -> assertInstanceOf(GdVariantType.class, multiParamType.getValueType())
        );
    }

    @Test
    void resolveAwaitExpressionClassifiesDynamicAndVariantOperandsAsVariant() throws Exception {
        var support = newBareSupport();

        var dynamicAwait = new AwaitExpression(identifier("target"), TINY);
        var dynamicResult = support.resolveAwaitExpressionType(
                dynamicAwait,
                (expression, finalizeWindow) -> FrontendExpressionType.dynamic("runtime-dynamic operand"),
                _ -> null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.DYNAMIC, dynamicResult.route()),
                () -> assertEquals(FrontendExpressionTypeStatus.RESOLVED, dynamicResult.expressionType().status()),
                () -> assertEquals("Variant", dynamicResult.expressionType().publishedType().getTypeName())
        );

        // A statically Variant-typed operand takes the same runtime-dynamic route, mirroring the
        // unary/binary rule that exact Variant values stay runtime-dynamic.
        var variantAwait = new AwaitExpression(identifier("target"), TINY);
        var variantResult = support.resolveAwaitExpressionType(
                variantAwait,
                resolvedVariantResolver(),
                _ -> null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.DYNAMIC, variantResult.route()),
                () -> assertEquals("Variant", variantResult.expressionType().publishedType().getTypeName())
        );
    }

    @Test
    void resolveAwaitExpressionClassifiesExactCallOperandsAsPendingCall() throws Exception {
        var support = newBareSupport();
        var callee = new LirFunctionDef("worker");
        callee.setReturnType(GdIntType.INT);
        var operandCall = FrontendResolvedCall.resolved(
                "worker",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                null,
                null,
                GdIntType.INT,
                List.of(),
                callee
        );
        var callOperand = new CallExpression(identifier("worker"), List.of(), TINY);

        var result = support.resolveAwaitExpressionType(
                new AwaitExpression(callOperand, TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                expression -> expression == callOperand ? operandCall : null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.CALL, result.route()),
                () -> assertSame(operandCall, result.operandCallOrNull()),
                () -> assertEquals("int", result.expressionType().publishedType().getTypeName())
        );

        // Void callees publish Variant: the resume channel yields nil either way.
        var voidCall = FrontendResolvedCall.resolved(
                "worker",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                null,
                null,
                GdVoidType.VOID,
                List.of(),
                callee
        );
        var voidResult = support.resolveAwaitExpressionType(
                new AwaitExpression(callOperand, TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdVoidType.VOID),
                expression -> expression == callOperand ? voidCall : null,
                null,
                false
        );
        assertEquals("Variant", voidResult.expressionType().publishedType().getTypeName());
    }

    /// Unannotated callees resolve to a `Variant` return, and that combination must still take the
    /// call route: redundant-await detection and coroutine marking depend on the pending call.
    @Test
    void resolveAwaitExpressionKeepsCallRouteWhenCalleeReturnsVariant() throws Exception {
        var support = newBareSupport();
        var callee = new LirFunctionDef("worker");
        var variantCall = FrontendResolvedCall.resolved(
                "worker",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                null,
                null,
                GdVariantType.VARIANT,
                List.of(),
                callee
        );
        var callOperand = new CallExpression(identifier("worker"), List.of(), TINY);

        var result = support.resolveAwaitExpressionType(
                new AwaitExpression(callOperand, TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdVariantType.VARIANT),
                expression -> expression == callOperand ? variantCall : null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.CALL, result.route()),
                () -> assertSame(variantCall, result.operandCallOrNull()),
                () -> assertEquals("Variant", result.expressionType().publishedType().getTypeName())
        );
    }

    @Test
    void resolveAwaitExpressionUsesAwaitableReturnRoutesForExternalCalls() throws Exception {
        var support = newBareSupport();
        var callOperand = new CallExpression(identifier("external"), List.of(), TINY);
        var signalCall = FrontendResolvedCall.resolved(
                "external_signal",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                null,
                null,
                new GdSignalType(List.of(GdIntType.INT)),
                List.of(),
                new Object()
        );
        var signalResult = support.resolveAwaitExpressionType(
                new AwaitExpression(callOperand, TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(signalCall.returnType()),
                expression -> expression == callOperand ? signalCall : null,
                null,
                false
        );

        var variantCall = FrontendResolvedCall.resolved(
                "external_variant",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                null,
                null,
                GdVariantType.VARIANT,
                List.of(),
                new Object()
        );
        var variantResult = support.resolveAwaitExpressionType(
                new AwaitExpression(callOperand, TINY),
                resolvedVariantResolver(),
                expression -> expression == callOperand ? variantCall : null,
                null,
                false
        );

        var voidCall = FrontendResolvedCall.resolved(
                "external_void",
                FrontendCallResolutionKind.INSTANCE_METHOD,
                FrontendReceiverKind.INSTANCE,
                null,
                null,
                GdVoidType.VOID,
                List.of(),
                new Object()
        );
        var voidResult = support.resolveAwaitExpressionType(
                new AwaitExpression(callOperand, TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdVoidType.VOID),
                expression -> expression == callOperand ? voidCall : null,
                null,
                false
        );

        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.SIGNAL, signalResult.route()),
                () -> assertEquals("int", signalResult.expressionType().publishedType().getTypeName()),
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.DYNAMIC, variantResult.route()),
                () -> assertEquals("Variant", variantResult.expressionType().publishedType().getTypeName()),
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.CALL, voidResult.route()),
                () -> assertEquals("Variant", voidResult.expressionType().publishedType().getTypeName())
        );
    }

    @Test
    void resolveAwaitExpressionRejectsPlainValuesAndFailClosedBoundaries() throws Exception {
        var support = newBareSupport();

        var plainValueResult = support.resolveAwaitExpressionType(
                new AwaitExpression(identifier("counter"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdIntType.INT),
                _ -> null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.NONE, plainValueResult.route()),
                () -> assertTrue(plainValueResult.rootOwnsOutcome()),
                () -> assertEquals(
                        FrontendExpressionTypeStatus.UNSUPPORTED,
                        plainValueResult.expressionType().status()
                ),
                () -> assertTrue(plainValueResult.expressionType().detailReason().contains("not a Signal"))
        );

        var boundaryResult = support.resolveAwaitExpressionType(
                new AwaitExpression(identifier("pinged"), TINY),
                (expression, finalizeWindow) -> FrontendExpressionType.resolved(new GdSignalType()),
                _ -> null,
                // The lambda boundary accepts await; the property-initializer
                // boundary stays fail-closed and anchors the classifier's reason pass-through.
                "Await expressions are not supported inside property initializers",
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.NONE, boundaryResult.route()),
                () -> assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, boundaryResult.expressionType().status()),
                () -> assertTrue(boundaryResult.expressionType().detailReason().contains("property initializers"))
        );
    }

    @Test
    void resolveAwaitExpressionPropagatesOperandDependencyIssues() throws Exception {
        var support = newBareSupport();
        var dependencyIssue = FrontendExpressionType.blocked(GdIntType.INT, "operand still blocked");
        var result = support.resolveAwaitExpressionType(
                new AwaitExpression(identifier("blocked_operand"), TINY),
                (expression, finalizeWindow) -> dependencyIssue,
                _ -> null,
                null,
                false
        );
        assertAll(
                () -> assertEquals(FrontendExpressionSemanticSupport.AwaitRoute.NONE, result.route()),
                // Propagated outcomes stay owned by the upstream dependency, not by the await root.
                () -> assertFalse(result.rootOwnsOutcome()),
                () -> assertSame(dependencyIssue, result.expressionType())
        );
    }

    @Test
    void resolveTypeTestExpressionPublishesResolvedBoolForKnownBuiltinAndObjectTargets() throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();

        var intTest = new TypeTestExpression(identifier("value"), new TypeRef("int", TINY), false, TINY);
        var intResult = support.resolveTypeTestExpressionType(intTest, nestedResolver, false);
        assertTrue(intResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, intResult.expressionType().status());
        assertEquals("bool", intResult.expressionType().publishedType().getTypeName());
        var knownInt = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                intResult.publishedTypeTestTargetOrNull()
        );
        assertEquals("int", knownInt.type().getTypeName());

        var isNotTest = new TypeTestExpression(identifier("value"), new TypeRef("int", TINY), true, TINY);
        var isNotResult = support.resolveTypeTestExpressionType(isNotTest, nestedResolver, false);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, isNotResult.expressionType().status());
        assertEquals("bool", isNotResult.expressionType().publishedType().getTypeName());
        // negated is AST-only at this layer; the semantic result type is bool either way.
        assertTrue(isNotTest.negated());

        var nodeTest = new TypeTestExpression(identifier("value"), new TypeRef("Node", TINY), false, TINY);
        var nodeResult = support.resolveTypeTestExpressionType(nodeTest, nestedResolver, false);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, nodeResult.expressionType().status());
        var knownNode = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                nodeResult.publishedTypeTestTargetOrNull()
        );
        assertEquals("Node", knownNode.type().getTypeName());

        var arrayTest = new TypeTestExpression(
                identifier("value"),
                new TypeRef("Array[int]", TINY),
                false,
                TINY
        );
        var arrayResult = support.resolveTypeTestExpressionType(arrayTest, nestedResolver, false);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, arrayResult.expressionType().status());
        var knownArray = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                arrayResult.publishedTypeTestTargetOrNull()
        );
        assertEquals("Array[int]", knownArray.type().getTypeName());

        var bareArrayResult = support.resolveTypeTestExpressionType(
                new TypeTestExpression(identifier("value"), new TypeRef("Array", TINY), false, TINY),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bareArrayResult.expressionType().status());
        var knownBareArray = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                bareArrayResult.publishedTypeTestTargetOrNull()
        );
        assertEquals("Array", knownBareArray.type().getTypeName());

        var bareDictResult = support.resolveTypeTestExpressionType(
                new TypeTestExpression(identifier("value"), new TypeRef("Dictionary", TINY), false, TINY),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, bareDictResult.expressionType().status());
        var knownBareDict = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                bareDictResult.publishedTypeTestTargetOrNull()
        );
        assertEquals("Dictionary", knownBareDict.type().getTypeName());

        var typedDictResult = support.resolveTypeTestExpressionType(
                new TypeTestExpression(
                        identifier("value"),
                        new TypeRef("Dictionary[String, int]", TINY),
                        false,
                        TINY
                ),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, typedDictResult.expressionType().status());
        var knownTypedDict = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                typedDictResult.publishedTypeTestTargetOrNull()
        );
        assertEquals("Dictionary[String, int]", knownTypedDict.type().getTypeName());

        var packedResult = support.resolveTypeTestExpressionType(
                new TypeTestExpression(
                        identifier("value"),
                        new TypeRef("PackedInt32Array", TINY),
                        false,
                        TINY
                ),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, packedResult.expressionType().status());
        var knownPacked = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                packedResult.publishedTypeTestTargetOrNull()
        );
        assertEquals("PackedInt32Array", knownPacked.type().getTypeName());
    }

    @Test
    void resolveTypeTestExpressionDegradesUnknownObjectIdentifierToUnresolvedTarget() throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();
        var typeTest = new TypeTestExpression(
                identifier("value"),
                new TypeRef("FutureEnemy", TINY),
                false,
                TINY
        );

        var result = support.resolveTypeTestExpressionType(typeTest, nestedResolver, false);

        assertTrue(result.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
        assertEquals("bool", result.expressionType().publishedType().getTypeName());
        var unresolved = assertInstanceOf(
                FrontendTypeTestTarget.TargetUnresolvedObject.class,
                result.publishedTypeTestTargetOrNull()
        );
        assertEquals("FutureEnemy", unresolved.typeName());
    }

    @Test
    void resolveTypeTestExpressionRejectsNullNestedContainerAndIllegalTypeText() throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();

        var nullResult = support.resolveTypeTestExpressionType(
                new TypeTestExpression(identifier("value"), new TypeRef("null", TINY), false, TINY),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, nullResult.expressionType().status());
        assertTrue(nullResult.expressionType().detailReason().contains("null"));
        assertNull(nullResult.publishedTypeTestTargetOrNull());

        var nestedResult = support.resolveTypeTestExpressionType(
                new TypeTestExpression(
                        identifier("value"),
                        new TypeRef("Array[Array[int]]", TINY),
                        false,
                        TINY
                ),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, nestedResult.expressionType().status());
        assertTrue(nestedResult.expressionType().detailReason().contains("Array[Array[int]]"));
        assertNull(nestedResult.publishedTypeTestTargetOrNull());

        var illegalResult = support.resolveTypeTestExpressionType(
                new TypeTestExpression(
                        identifier("value"),
                        new TypeRef("123Bad", TINY),
                        false,
                        TINY
                ),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, illegalResult.expressionType().status());
        assertNull(illegalResult.publishedTypeTestTargetOrNull());
    }

    @Test
    void resolveTypeTestExpressionPropagatesValueOperandDependencyFailures() throws Exception {
        var support = newBareSupport();
        NestedExpressionResolver failingResolver = (_, _) -> FrontendExpressionType.failed("value operand failed");

        var result = support.resolveTypeTestExpressionType(
                new TypeTestExpression(identifier("value"), new TypeRef("int", TINY), false, TINY),
                failingResolver,
                false
        );

        assertFalse(result.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, result.expressionType().status());
        assertTrue(result.expressionType().detailReason().contains("value operand failed"));
        assertNull(result.publishedTypeTestTargetOrNull());
    }

    @Test
    void endToEndTypeTestPublishesExpressionTypeTargetAndUnresolvedLint() throws Exception {
        var analyzed = analyze(
                "type_test_expression_semantic.gd",
                """
                        class_name TypeTestExpressionSemantic
                        extends RefCounted
                        
                        func probe(value):
                            var known = value is int
                            var is_not = value is not Node
                            var unknown = value is FutureEnemy
                            var nested = value is Array[Array[int]]
                        """
        );
        var function = findFunction(analyzed.ast(), "probe");
        var typeTests = findNodes(function, TypeTestExpression.class, _ -> true);
        assertEquals(4, typeTests.size());

        var known = typeTests.stream()
                .filter(typeTest -> typeTest.targetType().sourceText().equals("int"))
                .findFirst()
                .orElseThrow();
        var isNot = typeTests.stream()
                .filter(TypeTestExpression::negated)
                .findFirst()
                .orElseThrow();
        var unknown = typeTests.stream()
                .filter(typeTest -> typeTest.targetType().sourceText().equals("FutureEnemy"))
                .findFirst()
                .orElseThrow();
        var nested = typeTests.stream()
                .filter(typeTest -> typeTest.targetType().sourceText().equals("Array[Array[int]]"))
                .findFirst()
                .orElseThrow();

        var knownType = analyzed.analysisData().expressionTypes().get(known);
        assertNotNull(knownType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, knownType.status());
        assertEquals("bool", knownType.publishedType().getTypeName());
        var knownTarget = assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                analyzed.analysisData().typeTestTargets().get(known)
        );
        assertEquals("int", knownTarget.type().getTypeName());

        var isNotType = analyzed.analysisData().expressionTypes().get(isNot);
        assertNotNull(isNotType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, isNotType.status());
        assertEquals("bool", isNotType.publishedType().getTypeName());
        assertTrue(isNot.negated());
        assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                analyzed.analysisData().typeTestTargets().get(isNot)
        );

        var unknownType = analyzed.analysisData().expressionTypes().get(unknown);
        assertNotNull(unknownType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, unknownType.status());
        var unresolvedTarget = assertInstanceOf(
                FrontendTypeTestTarget.TargetUnresolvedObject.class,
                analyzed.analysisData().typeTestTargets().get(unknown)
        );
        assertEquals("FutureEnemy", unresolvedTarget.typeName());

        var nestedType = analyzed.analysisData().expressionTypes().get(nested);
        assertNotNull(nestedType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, nestedType.status());
        assertNull(analyzed.analysisData().typeTestTargets().get(nested));

        var unresolvedDiagnostics = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.type_test_unresolved_object"))
                .toList();
        assertEquals(1, unresolvedDiagnostics.size());
        assertEquals(FrontendDiagnosticSeverity.WARNING, unresolvedDiagnostics.getFirst().severity());
        assertTrue(unresolvedDiagnostics.getFirst().message().contains("FutureEnemy"));
        assertTrue(unresolvedDiagnostics.getFirst().message().contains("will be checked at runtime"));

        var expressionErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .filter(diagnostic -> diagnostic.message().contains("Array[Array[int]]"))
                .toList();
        assertEquals(1, expressionErrors.size());
        assertEquals(FrontendDiagnosticSeverity.ERROR, expressionErrors.getFirst().severity());
    }

    @Test
    void endToEndTypeTestPassesCompileGate() throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(
                Path.of("tmp", "type_test_compile_gate.gd"),
                """
                        class_name TypeTestCompileGate
                        extends RefCounted
                        
                        func probe(value):
                            var flag = value is Node
                        """,
                diagnostics
        );
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit)),
                classRegistry,
                diagnostics
        );
        var compileBlocks = analysisData.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .filter(diagnostic -> diagnostic.message().toLowerCase().contains("type-test"))
                .toList();
        assertTrue(compileBlocks.isEmpty(), () -> "TypeTest should pass compile gate, got: "
                + analysisData.diagnostics());
        var typeTest = findNode(unit.ast(), TypeTestExpression.class, _ -> true);
        var expressionType = analysisData.expressionTypes().get(typeTest);
        assertNotNull(expressionType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
        assertEquals("bool", expressionType.publishedType().getTypeName());
        assertInstanceOf(
                FrontendTypeTestTarget.TargetKnown.class,
                analysisData.typeTestTargets().get(typeTest)
        );
    }

    @Test
    void hardTypedIncompatibilityEmitsWarning() throws Exception {
        var analyzed = analyze(
                "type_test_hard_typed_warning.gd",
                """
                        class_name TypeTestHardTypedWarning
                        extends RefCounted
                        
                        func incompatible_int_is_object() -> bool:
                            var x: int = 5
                            return x is Node
                        
                        func incompatible_object_is_builtin() -> bool:
                            var n: Node = null
                            return n is int
                        
                        func variant_operand_no_warning(v) -> bool:
                            return v is Node
                        
                        func compatible_upcast_no_warning() -> bool:
                            var child := Node2D.new()
                            var result := child is Node
                            child.free()
                            return result
                        
                        func compatible_downcast_no_warning() -> bool:
                            var n := Node2D.new()
                            var result := n is Node2D
                            n.free()
                            return result
                        
                        func unresolved_target_no_warning() -> bool:
                            var x: int = 5
                            return x is FutureEnemy
                        
                        func variant_target_no_warning() -> bool:
                            var x: int = 5
                            return x is Variant
                        """
        );
        var typeCheckWarnings = analyzed.analysisData().diagnostics().asList().stream()
                .filter(d -> d.category().equals("sema.type_check"))
                .toList();
        assertEquals(2, typeCheckWarnings.size(),
                () -> "Expected exactly 2 hard-typed warnings, got: " + typeCheckWarnings);
        assertTrue(typeCheckWarnings.getFirst().message().contains("int"));
        assertTrue(typeCheckWarnings.getFirst().message().contains("Node"));
        assertTrue(typeCheckWarnings.get(1).message().contains("Node"));
        assertTrue(typeCheckWarnings.get(1).message().contains("int"));
        for (var warning : typeCheckWarnings) {
            assertEquals(FrontendDiagnosticSeverity.WARNING, warning.severity());
            assertTrue(warning.message().contains("can't be of type"));
        }
    }

    @Test
    void resolveCastExpressionPublishesResolvedTargetForKnownBuiltinAndObjectTargets() throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();

        var intCast = new CastExpression(identifier("value"), new TypeRef("int", TINY), TINY);
        var intResult = support.resolveCastExpressionType(intCast, nestedResolver, false);
        assertTrue(intResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, intResult.expressionType().status());
        assertEquals("int", intResult.expressionType().publishedType().getTypeName());
        assertNull(intResult.publishedTypeTestTargetOrNull());

        var nodeCast = new CastExpression(identifier("value"), new TypeRef("Node", TINY), TINY);
        var nodeResult = support.resolveCastExpressionType(nodeCast, nestedResolver, false);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, nodeResult.expressionType().status());
        assertEquals("Node", nodeResult.expressionType().publishedType().getTypeName());

        var arrayCast = new CastExpression(identifier("value"), new TypeRef("Array[int]", TINY), TINY);
        var arrayResult = support.resolveCastExpressionType(arrayCast, nestedResolver, false);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, arrayResult.expressionType().status());
        assertEquals("Array[int]", arrayResult.expressionType().publishedType().getTypeName());

        var variantCast = new CastExpression(identifier("value"), new TypeRef("Variant", TINY), TINY);
        var variantResult = support.resolveCastExpressionType(variantCast, nestedResolver, false);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, variantResult.expressionType().status());
        assertEquals("Variant", variantResult.expressionType().publishedType().getTypeName());
    }

    @Test
    void resolveCastExpressionFailsUnknownNullVoidAndMalformedTargets() throws Exception {
        var support = newBareSupport();
        var nestedResolver = resolvedVariantResolver();

        var unknownResult = support.resolveCastExpressionType(
                new CastExpression(identifier("value"), new TypeRef("FutureEnemy", TINY), TINY),
                nestedResolver,
                false
        );
        assertTrue(unknownResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, unknownResult.expressionType().status());
        assertTrue(unknownResult.expressionType().detailReason().contains("FutureEnemy"));
        assertTrue(unknownResult.expressionType().detailReason().contains("cannot be resolved"));

        var nullResult = support.resolveCastExpressionType(
                new CastExpression(identifier("value"), new TypeRef("null", TINY), TINY),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, nullResult.expressionType().status());
        assertTrue(nullResult.expressionType().detailReason().contains("null"));

        var voidResult = support.resolveCastExpressionType(
                new CastExpression(identifier("value"), new TypeRef("void", TINY), TINY),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, voidResult.expressionType().status());
        assertTrue(voidResult.expressionType().detailReason().contains("void"));

        var nestedResult = support.resolveCastExpressionType(
                new CastExpression(identifier("value"), new TypeRef("Array[Array[int]]", TINY), TINY),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, nestedResult.expressionType().status());
        assertTrue(nestedResult.expressionType().detailReason().contains("Array[Array[int]]"));

        var emptyResult = support.resolveCastExpressionType(
                new CastExpression(identifier("value"), new TypeRef("   ", TINY), TINY),
                nestedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, emptyResult.expressionType().status());
        assertTrue(emptyResult.expressionType().detailReason().contains("empty"));
    }

    @Test
    void resolveCastExpressionPropagatesValueOperandDependencyFailures() throws Exception {
        var support = newBareSupport();
        NestedExpressionResolver failingResolver = (_, _) -> FrontendExpressionType.failed("value operand failed");

        var result = support.resolveCastExpressionType(
                new CastExpression(identifier("value"), new TypeRef("int", TINY), TINY),
                failingResolver,
                false
        );

        assertFalse(result.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, result.expressionType().status());
        assertTrue(result.expressionType().detailReason().contains("value operand failed"));
    }

    @Test
    void endToEndCastPublishesTargetTypeUnsafeWarningAndResolutionErrors() throws Exception {
        var analyzed = analyze(
                "cast_expression_semantic.gd",
                """
                        class_name CastExpressionSemantic
                        extends RefCounted
                        
                        func probe(value: Variant, hard_int: int) -> void:
                            var as_int = value as int
                            var as_variant = hard_int as Variant
                            var as_float = hard_int as float
                            var unknown = value as FutureEnemy
                            var as_void = value as void
                            var nested = value as Array[Array[int]]
                        """
        );
        var function = findFunction(analyzed.ast(), "probe");
        var casts = findNodes(function, CastExpression.class, _ -> true);
        assertEquals(6, casts.size());

        var asInt = casts.stream()
                .filter(cast -> cast.targetType().sourceText().equals("int"))
                .findFirst()
                .orElseThrow();
        var asVariant = casts.stream()
                .filter(cast -> cast.targetType().sourceText().equals("Variant"))
                .findFirst()
                .orElseThrow();
        var asFloat = casts.stream()
                .filter(cast -> cast.targetType().sourceText().equals("float"))
                .findFirst()
                .orElseThrow();
        var unknown = casts.stream()
                .filter(cast -> cast.targetType().sourceText().equals("FutureEnemy"))
                .findFirst()
                .orElseThrow();
        var asVoid = casts.stream()
                .filter(cast -> cast.targetType().sourceText().equals("void"))
                .findFirst()
                .orElseThrow();
        var nested = casts.stream()
                .filter(cast -> cast.targetType().sourceText().equals("Array[Array[int]]"))
                .findFirst()
                .orElseThrow();

        var asIntType = analyzed.analysisData().expressionTypes().get(asInt);
        assertNotNull(asIntType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, asIntType.status());
        assertEquals("int", asIntType.publishedType().getTypeName());

        var asVariantType = analyzed.analysisData().expressionTypes().get(asVariant);
        assertNotNull(asVariantType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, asVariantType.status());
        assertEquals("Variant", asVariantType.publishedType().getTypeName());

        var asFloatType = analyzed.analysisData().expressionTypes().get(asFloat);
        assertNotNull(asFloatType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, asFloatType.status());
        assertEquals("float", asFloatType.publishedType().getTypeName());

        var unknownType = analyzed.analysisData().expressionTypes().get(unknown);
        assertNotNull(unknownType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, unknownType.status());

        var voidType = analyzed.analysisData().expressionTypes().get(asVoid);
        assertNotNull(voidType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, voidType.status());

        var nestedType = analyzed.analysisData().expressionTypes().get(nested);
        assertNotNull(nestedType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, nestedType.status());

        var unsafeWarnings = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.unsafe_cast"))
                .toList();
        assertEquals(1, unsafeWarnings.size(), () -> "Expected one unsafe_cast warning, got: " + unsafeWarnings);
        assertEquals(FrontendDiagnosticSeverity.WARNING, unsafeWarnings.getFirst().severity());
        assertTrue(unsafeWarnings.getFirst().message().contains("Variant"));
        assertTrue(unsafeWarnings.getFirst().message().contains("int"));

        // value as Variant must not warn; hard int as float must not warn.
        assertTrue(unsafeWarnings.stream().noneMatch(d -> d.message().contains("float")));

        var expressionErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .toList();
        assertEquals(3, expressionErrors.size(), () -> "Expected three resolution errors, got: " + expressionErrors);
        assertTrue(expressionErrors.stream().anyMatch(d -> d.message().contains("FutureEnemy")));
        assertTrue(expressionErrors.stream().anyMatch(d -> d.message().contains("void")));
        assertTrue(expressionErrors.stream().anyMatch(d -> d.message().contains("Array[Array[int]]")));
    }

    @Test
    void endToEndHardInvalidCastEmitsTypeCheckError() throws Exception {
        var analyzed = analyze(
                "cast_expression_hard_invalid.gd",
                """
                        class_name CastExpressionHardInvalid
                        extends RefCounted
                        
                        func hard_invalid() -> void:
                            var x: int = 1
                            var y = x as Node
                        
                        func related_object_ok() -> void:
                            var n: Node = Node.new()
                            var n2d = n as Node2D
                            n.free()
                        """
        );

        var typeCheckErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(d -> d.category().equals("sema.type_check"))
                .filter(d -> d.message().contains("Invalid cast"))
                .toList();
        assertEquals(1, typeCheckErrors.size(), () -> "Expected one invalid cast error, got: " + typeCheckErrors);
        assertEquals(FrontendDiagnosticSeverity.ERROR, typeCheckErrors.getFirst().severity());
        assertTrue(typeCheckErrors.getFirst().message().contains("int"));
        assertTrue(typeCheckErrors.getFirst().message().contains("Node"));

        var function = findFunction(analyzed.ast(), "related_object_ok");
        var objectCast = findNode(function, CastExpression.class, _ -> true);
        var objectCastType = analyzed.analysisData().expressionTypes().get(objectCast);
        assertNotNull(objectCastType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, objectCastType.status());
        assertEquals("Node2D", objectCastType.publishedType().getTypeName());
    }

    @Test
    void endToEndDynamicSourceCastEmitsUnsafeWarning() throws Exception {
        // Attribute on Variant widens to DYNAMIC; cast target stays hard and must warn once.
        var analyzed = analyze(
                "cast_expression_dynamic_source.gd",
                """
                        class_name CastExpressionDynamicSource
                        extends RefCounted
                        
                        func probe(value: Variant) -> void:
                            var as_int = value.foo as int
                        """
        );
        var function = findFunction(analyzed.ast(), "probe");
        var cast = findNode(function, CastExpression.class, _ -> true);
        var castType = analyzed.analysisData().expressionTypes().get(cast);
        assertNotNull(castType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, castType.status());
        assertEquals("int", castType.publishedType().getTypeName());

        var unsafeWarnings = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.unsafe_cast"))
                .toList();
        assertEquals(1, unsafeWarnings.size(), () -> "Expected one unsafe_cast for DYNAMIC source, got: "
                + analyzed.analysisData().diagnostics());
    }

    @Test
    void endToEndCastResultFeedsChainAndTypedConsumerWithoutRecheck() throws Exception {
        var analyzed = analyze(
                "cast_expression_chain_consumer.gd",
                """
                        class_name CastExpressionChainConsumer
                        extends RefCounted
                        
                        func probe(value: Variant) -> String:
                            var name = (value as Node).name
                            return name
                        """
        );
        var function = findFunction(analyzed.ast(), "probe");
        var cast = findNode(function, CastExpression.class, _ -> true);
        var castType = analyzed.analysisData().expressionTypes().get(cast);
        assertNotNull(castType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, castType.status());
        assertEquals("Node", castType.publishedType().getTypeName());

        // Result is consumed as Node-typed chain head; no hard type-check invalid-cast error.
        var invalidCastErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(d -> d.category().equals("sema.type_check"))
                .filter(d -> d.message().contains("Invalid cast"))
                .toList();
        assertTrue(invalidCastErrors.isEmpty(), () -> "Cast result consumers must not re-run cast pair check: "
                + invalidCastErrors);

        var unsafeWarnings = analyzed.analysisData().diagnostics().asList().stream()
                .filter(d -> d.category().equals("sema.unsafe_cast"))
                .toList();
        assertEquals(1, unsafeWarnings.size());
    }

    @Test
    void endToEndCastPassesCompileGateWithResolvedTarget() throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(
                Path.of("tmp", "cast_compile_gate.gd"),
                """
                        class_name CastCompileGate
                        extends RefCounted
                        
                        func probe(value: Variant) -> int:
                            return value as int
                        """,
                diagnostics
        );
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyzeForCompile(
                new FrontendModule("test_module", List.of(unit)),
                classRegistry,
                diagnostics
        );
        var cast = findNode(unit.ast(), CastExpression.class, _ -> true);
        var expressionType = analysisData.expressionTypes().get(cast);
        assertNotNull(expressionType);
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, expressionType.status());
        assertEquals("int", expressionType.publishedType().getTypeName());

        var compileBlocks = analysisData.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.compile_check"))
                .filter(diagnostic -> diagnostic.message().contains("Cast expression"))
                .toList();
        assertTrue(
                compileBlocks.isEmpty(),
                () -> "CastExpression should pass compile gate, got: " + analysisData.diagnostics()
        );
        // Unsafe source still produces a shared warning; it must not become a compile-check rewrap.
        var unsafeWarnings = analysisData.diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.unsafe_cast"))
                .toList();
        assertEquals(1, unsafeWarnings.size());
        assertFalse(
                analysisData.diagnostics().hasErrors(),
                () -> "Supported cast must not error at compile gate, got: " + analysisData.diagnostics()
        );
    }

    @Test
    void endToEndCastDoesNotDuplicateUpstreamOperandErrors() throws Exception {
        var analyzed = analyze(
                "cast_expression_upstream_failure.gd",
                """
                        class_name CastExpressionUpstreamFailure
                        extends RefCounted
                        
                        func probe() -> void:
                            var y = missing as int
                        """
        );
        var function = findFunction(analyzed.ast(), "probe");
        var cast = findNode(function, CastExpression.class, _ -> true);
        var castType = analyzed.analysisData().expressionTypes().get(cast);
        assertNotNull(castType);
        assertEquals(FrontendExpressionTypeStatus.FAILED, castType.status());

        // Only the identifier failure should own the expression_resolution error; cast root does not
        // re-emit a second root-owned cast-target failure for a known target.
        var expressionErrors = analyzed.analysisData().diagnostics().asList().stream()
                .filter(diagnostic -> diagnostic.category().equals("sema.expression_resolution"))
                .toList();
        assertEquals(1, expressionErrors.size(), () -> "Expected single upstream error, got: " + expressionErrors);
        assertTrue(expressionErrors.getFirst().message().contains("missing")
                || expressionErrors.getFirst().message().toLowerCase().contains("identifier"));
    }

    @Test
    void resolveDeferredRoutesKeepDependencyProvenance() throws Exception {
        var analyzed = analyze(
                "expression_semantic_support_deferred.gd",
                """
                        class_name ExpressionSemanticSupportDeferred
                        extends RefCounted
                        
                        func ping(items):
                            items = 1
                            1 + missing.payload
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var genericDeferred = assertInstanceOf(
                Expression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().getLast()).expression()
        );

        var genericResult = support.resolveBinaryExpressionType(
                assertInstanceOf(BinaryExpression.class, genericDeferred),
                publishedResolver,
                false
        );
        assertFalse(genericResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, genericResult.expressionType().status());
        assertTrue(genericResult.expressionType().detailReason().contains("chain head"));
    }

    @Test
    void selectCallableOverloadRanksFrontendBoundarySpecificityBeforeReportingAmbiguity() throws Exception {
        var support = newBareSupport();
        var directInt = newCallable("helper", GdIntType.INT, GdIntType.INT);
        var primitiveFloat = newCallable("helper", GdFloatType.FLOAT, GdFloatType.FLOAT);
        var variantPack = newCallable("helper", GdVariantType.VARIANT, GdVariantType.VARIANT);
        var directString = newCallable("helper", GdStringType.STRING, GdStringType.STRING);
        var stringNameBoundary = newCallable("helper", GdStringNameType.STRING_NAME, GdStringNameType.STRING_NAME);
        var stringVariantPack = newCallable("helper", GdVariantType.VARIANT, GdVariantType.VARIANT);

        var directSelection = support.selectCallableOverload(
                List.of(directInt, primitiveFloat),
                List.of(GdIntType.INT)
        );
        assertSame(directInt, directSelection.selected());
        assertNull(directSelection.detailReason());

        var primitiveSelection = support.selectCallableOverload(
                List.of(primitiveFloat, variantPack),
                List.of(GdIntType.INT)
        );
        assertSame(primitiveFloat, primitiveSelection.selected());
        assertNull(primitiveSelection.detailReason());

        var stringExactSelection = support.selectCallableOverload(
                List.of(directString, stringNameBoundary),
                List.of(GdStringType.STRING)
        );
        assertSame(directString, stringExactSelection.selected());
        assertNull(stringExactSelection.detailReason());

        var stringNameExactSelection = support.selectCallableOverload(
                List.of(directString, stringNameBoundary),
                List.of(GdStringNameType.STRING_NAME)
        );
        assertSame(stringNameBoundary, stringNameExactSelection.selected());
        assertNull(stringNameExactSelection.detailReason());

        var stringConstructorSelection = support.selectCallableOverload(
                List.of(stringNameBoundary, stringVariantPack),
                List.of(GdStringType.STRING)
        );
        assertSame(stringNameBoundary, stringConstructorSelection.selected());
        assertNull(stringConstructorSelection.detailReason());

        // Each candidate is more specific for a different parameter; rank sums must not decide it.
        var crossParameterAmbiguousSelection = support.selectCallableOverload(
                List.of(
                        newCallable("helper", GdVoidType.VOID, GdFloatType.FLOAT, GdStringType.STRING),
                        newCallable("helper", GdVoidType.VOID, GdIntType.INT, GdStringNameType.STRING_NAME)
                ),
                List.of(GdIntType.INT, GdStringType.STRING)
        );
        assertTrue(crossParameterAmbiguousSelection.selected() == null);
        assertTrue(crossParameterAmbiguousSelection.detailReason().contains("Ambiguous bare call overload"));

        var stringFamilyVariantAmbiguousSelection = support.selectCallableOverload(
                List.of(directString, stringNameBoundary),
                List.of(GdVariantType.VARIANT)
        );
        assertTrue(stringFamilyVariantAmbiguousSelection.selected() == null);
        assertTrue(stringFamilyVariantAmbiguousSelection.detailReason().contains("Ambiguous bare call overload"));

        var variantAmbiguous = List.of(
                newCallable("helper", GdIntType.INT, GdIntType.INT),
                newCallable("helper", GdIntType.INT, GdStringType.STRING)
        );
        var variantAmbiguousSelection = support.selectCallableOverload(
                variantAmbiguous,
                List.of(GdVariantType.VARIANT)
        );
        assertTrue(variantAmbiguousSelection.selected() == null);
        assertTrue(variantAmbiguousSelection.detailReason().contains("Ambiguous bare call overload"));

        var emptySelection = support.selectCallableOverload(List.of(), List.of(GdIntType.INT));
        assertTrue(emptySelection.selected() == null);
        assertEquals("Bare call resolves to an empty overload set", emptySelection.detailReason());
    }

    private @NotNull FrontendExpressionSemanticSupport createSupport(
            @NotNull AnalyzedScript analyzed,
            @NotNull ResolveRestriction restriction,
            boolean staticContext
    ) {
        return new FrontendExpressionSemanticSupport(
                analyzed.analysisData().symbolBindings(),
                analyzed.analysisData().scopesByAst(),
                () -> restriction,
                () -> null,
                analyzed.classRegistry(),
                () -> new FrontendChainHeadReceiverSupport(
                        analyzed.analysisData(),
                        analyzed.analysisData().scopesByAst(),
                        restriction,
                        staticContext,
                        null,
                        _ -> null,
                        _ -> null
                )
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport newBareSupport() throws Exception {
        var analysisData = FrontendAnalysisData.bootstrap();
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        return new FrontendExpressionSemanticSupport(
                analysisData.symbolBindings(),
                analysisData.scopesByAst(),
                ResolveRestriction::instanceContext,
                () -> null,
                classRegistry,
                () -> new FrontendChainHeadReceiverSupport(
                        analysisData,
                        analysisData.scopesByAst(),
                        ResolveRestriction.instanceContext(),
                        false,
                        null,
                        _ -> null,
                        _ -> null
                )
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver publishedExpressionResolver(
            @NotNull AnalyzedScript analyzed
    ) {
        return (expression, finalizeWindow) -> {
            var published = analyzed.analysisData().expressionTypes().get(expression);
            return Objects.requireNonNull(
                    published,
                    "Expected published expression type for " + expression.getClass().getSimpleName()
            );
        };
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver resolvedVariantResolver() {
        return (expression, finalizeWindow) -> FrontendExpressionType.resolved(GdVariantType.VARIANT);
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var classRegistry = new ClassRegistry(ExtensionApiLoader.loadDefault());
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                classRegistry,
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData, classRegistry);
    }

    private static @NotNull IdentifierExpression identifier(@NotNull String name) {
        return new IdentifierExpression(name, TINY);
    }

    private static @NotNull dev.superice.gdparser.frontend.ast.LiteralExpression integerLiteral(
            @NotNull String sourceText
    ) {
        return new dev.superice.gdparser.frontend.ast.LiteralExpression("integer", sourceText, TINY);
    }

    private static @NotNull dev.superice.gdparser.frontend.ast.LiteralExpression stringLiteral(
            @NotNull String sourceText
    ) {
        return new dev.superice.gdparser.frontend.ast.LiteralExpression("string", sourceText, TINY);
    }

    private static @NotNull LirFunctionDef newCallable(
            @NotNull String name,
            @NotNull GdType returnType,
            @NotNull GdType... parameterTypes
    ) {
        var function = new LirFunctionDef(name);
        function.setReturnType(returnType);
        for (var index = 0; index < parameterTypes.length; index++) {
            function.addParameter(new LirParameterDef("arg" + index, parameterTypes[index], null, function));
        }
        return function;
    }

    private static void publishSyntheticIntBareCallOverloads(
            @NotNull AnalyzedScript analyzed,
            @NotNull CallExpression call,
            @NotNull LirFunctionDef... overloads
    ) {
        publishSyntheticBareCallOverloads(analyzed, call, GdIntType.INT, overloads);
    }

    private static void publishSyntheticBareCallOverloads(
            @NotNull AnalyzedScript analyzed,
            @NotNull CallExpression call,
            @NotNull GdType argumentType,
            @NotNull LirFunctionDef... overloads
    ) {
        var bareCallee = assertInstanceOf(IdentifierExpression.class, call.callee());
        var owner = new LirClassDef("Synthetic" + bareCallee.name(), "RefCounted");
        var scope = new ClassScope(analyzed.classRegistry(), analyzed.classRegistry(), owner);
        for (var overload : overloads) {
            scope.defineFunction(overload);
        }
        analyzed.analysisData().scopesByAst().put(bareCallee, scope);
        analyzed.analysisData().symbolBindings().put(
                bareCallee,
                new FrontendBinding(bareCallee.name(), FrontendBindingKind.METHOD, overloads[0])
        );
        analyzed.analysisData().expressionTypes().put(bareCallee, FrontendExpressionType.resolved(new GdCallableType()));
        for (var argument : call.arguments()) {
            analyzed.analysisData().expressionTypes().put(argument, FrontendExpressionType.resolved(argumentType));
        }
    }

    private static @NotNull CallExpression findBareCall(@NotNull Node root, @NotNull String calleeName) {
        return findNode(
                root,
                CallExpression.class,
                call -> call.callee() instanceof IdentifierExpression identifier
                        && identifier.name().equals(calleeName)
        );
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(name));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).getFirst();
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

    private record AnalyzedScript(
            @NotNull Node ast,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
    }

    private record RemainingExpressionCase(
            @NotNull Expression expression,
            @NotNull FrontendExpressionTypeStatus status,
            @NotNull String reasonFragment
    ) {
    }
}
