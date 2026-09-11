package gd.script.gdcc.frontend.sema.analyzer;

import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
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
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAwaitCallPending;
import gd.script.gdcc.frontend.sema.FrontendAwaitCoroutineOwner;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendBodySemanticSupportPolicy;
import gd.script.gdcc.frontend.sema.FrontendBodyDeclarationIndex;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendForIterationPlan;
import gd.script.gdcc.frontend.sema.FrontendForLoopSupport;
import gd.script.gdcc.frontend.sema.FrontendMatchBindingPlan;
import gd.script.gdcc.frontend.sema.FrontendMatchPlan;
import gd.script.gdcc.frontend.sema.FrontendMatchSupport;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendSemanticStage;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendAssignmentSemanticSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionFacade;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainReductionHelper;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendChainStatusBridge;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendDualRoleTypeMetaRouteSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendExpressionSemanticSupport;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendPropertyInitializerSupport;
import gd.script.gdcc.frontend.sema.patch.FrontendLocalSlotTypeUpdate;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolution;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueResolver;
import gd.script.gdcc.frontend.sema.resolver.FrontendVisibleValueStatus;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeLookupStatus;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/// Statement-local owner procedures used by the body SuiteResolver path.
///
/// This class is intentionally root-bounded: it may walk the current statement/header expression, but
/// it must never start from a `SourceFile` or invoke a whole-module analyzer entrypoint. Facts
/// are written only through `FrontendTypedLexicalEnvironment`, so pending/current-suite visibility and
/// ordered per-owner export stay centralized in one place.
public final class FrontendBodyOwnerProcedures implements FrontendStatementResolver.OwnerProcedures {
    /// Shared category for a callable-local slot that cannot become lowering-ready.
    public static final @NotNull String VARIABLE_SLOT_PUBLICATION_CATEGORY = "sema.variable_slot_publication";
    private static final @NotNull String BINDING_CATEGORY = "sema.binding";
    private static final @NotNull String MEMBER_RESOLUTION_CATEGORY = "sema.member_resolution";
    private static final @NotNull String CALL_RESOLUTION_CATEGORY = "sema.call_resolution";
    private static final @NotNull String EXPRESSION_RESOLUTION_CATEGORY = "sema.expression_resolution";
    private static final @NotNull String DISCARDED_EXPRESSION_CATEGORY = "sema.discarded_expression";
    private static final @NotNull String UNSAFE_CALL_ARGUMENT_CATEGORY = "sema.unsafe_call_argument";
    /// Shared expr-publication warning for `Variant` / `DYNAMIC` source explicit casts to a hard target.
    private static final @NotNull String UNSAFE_CAST_CATEGORY = "sema.unsafe_cast";
    private static final @NotNull String DEFERRED_CHAIN_RESOLUTION_CATEGORY = "sema.deferred_chain_resolution";
    private static final @NotNull String DEFERRED_EXPRESSION_RESOLUTION_CATEGORY =
            "sema.deferred_expression_resolution";
    private static final @NotNull String TYPE_TEST_UNRESOLVED_OBJECT_CATEGORY =
            "sema.type_test_unresolved_object";
    private static final @NotNull String TYPE_CHECK_CATEGORY = "sema.type_check";
    private static final @NotNull String UNSUPPORTED_BINDING_SUBTREE_CATEGORY =
            "sema.unsupported_binding_subtree";
    private static final @NotNull String UNSUPPORTED_CHAIN_ROUTE_CATEGORY = "sema.unsupported_chain_route";
    private static final @NotNull String UNSUPPORTED_EXPRESSION_ROUTE_CATEGORY = "sema.unsupported_expression_route";
    /// Warning owner for `await` on a call whose callee is a statically known non-coroutine
    /// (`frontend_await_implementation.md` §8; aligns Godot `REDUNDANT_AWAIT`).
    public static final @NotNull String REDUNDANT_AWAIT_CATEGORY = "sema.redundant_await";

    private FrontendAnalysisData cachedAnalysisData;
    private FrontendBodyDeclarationIndex cachedBodyDeclarationIndex;
    private FrontendVisibleValueResolver cachedVisibleValueResolver;

    @Override
    public void runTopBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        forEachExpression(root, expression -> {
            if (expression instanceof AttributeExpression attributeExpression) {
                tryApplyAttributeChainHeadTypeMetaBias(context, attributeExpression);
            } else if (expression instanceof IdentifierExpression identifierExpression) {
                bindIdentifier(context, identifierExpression);
            } else if (expression instanceof LiteralExpression literalExpression) {
                bindLiteral(context, literalExpression);
            } else if (expression instanceof SelfExpression selfExpression) {
                bindSelf(context, selfExpression);
            } else if (expression instanceof LambdaExpression lambdaExpression) {
                if (!tryResolveRecordedLambda(context, lambdaExpression)) {
                    reportUnsupportedBinding(context, lambdaExpression, "lambda subtree");
                }
            }
        });
    }

    /// Recorded lambdas resolve through the nested suite trigger instead of producing unsupported
    /// binding/chain diagnostics. The trigger is idempotent per lambda node: the
    /// published plan doubles as the resolved marker. Unrecorded lambdas (property initializers,
    /// parameter defaults, skipped subtrees) stay fail-closed. `walkRootBounded` keeps pruning at
    /// the lambda node, so the enclosing owner never walks the body as an ordinary expression tree.
    private static boolean tryResolveRecordedLambda(
            @NotNull FrontendSuiteContext context,
            @NotNull LambdaExpression lambdaExpression
    ) {
        if (!context.interfaceSurface().suiteEntryRoots().containsCallableOwner(lambdaExpression)) {
            return false;
        }
        if (context.analysisData().lambdaPlans().containsKey(lambdaExpression)) {
            return true;
        }
        var nestedResolver = Objects.requireNonNull(
                context.nestedLambdaResolver(),
                "Recorded lambda has no nested resolve route"
        );
        nestedResolver.resolveNestedLambda(context, lambdaExpression);
        return true;
    }

    private void tryApplyAttributeChainHeadTypeMetaBias(
            @NotNull FrontendSuiteContext context,
            @NotNull AttributeExpression attributeExpression
    ) {
        if (!(attributeExpression.base() instanceof IdentifierExpression identifierExpression)) {
            return;
        }
        if (context.typedEnvironment().symbolBinding(identifierExpression) != null) {
            return;
        }
        var currentScope = currentScopeFor(context, identifierExpression);
        if (currentScope == null) {
            return;
        }
        var valueResolution = resolveVisibleValue(context, identifierExpression);
        var biasedTypeMeta = FrontendDualRoleTypeMetaRouteSupport.resolveBiasedTypeMeta(
                attributeExpression,
                valueResolution,
                currentScope,
                context.restriction(),
                context.analysisData().moduleSkeleton(),
                context.classRegistry()
        );
        if (biasedTypeMeta != null) {
            publishTypeMetaBinding(context, identifierExpression, biasedTypeMeta);
            return;
        }

        var typeMetaResult = context.analysisData().moduleSkeleton().resolveSourceFacingTypeMeta(
                currentScope,
                identifierExpression.name(),
                context.restriction()
        );
        if (FrontendDualRoleTypeMetaRouteSupport.shouldPreferGlobalEnumTypeMeta(valueResolution, typeMetaResult)) {
            publishTypeMetaBinding(context, identifierExpression, typeMetaResult.requireValue());
        }
    }

    private static void publishTypeMetaBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression,
            @NotNull ScopeTypeMeta typeMeta
    ) {
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(
                        identifierExpression.name(),
                        FrontendBindingKind.TYPE_META,
                        typeMeta.declaration()
                )
        );
    }

    @Override
    public void runLocalTypeStabilization(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        if (!(root instanceof VariableDeclaration variableDeclaration)) {
            return;
        }
        var blockScope = eligibleInferredLocalScope(context, variableDeclaration);
        if (blockScope == null || variableDeclaration.value() == null) {
            return;
        }
        var initializer = variableDeclaration.value();
        if (initializer instanceof LambdaExpression) {
            // Silent stabilization must not resolve lambda initializers: the slot
            // keeps its inventory Variant, and any `:=` refinement to `Callable` may only come
            // from a non-silent write-back after nested resolve completed — never from resolving
            // the lambda expression on this silent path.
            return;
        }
        var guardedFailure = typeMetaOrdinaryValueInitializerFailure(context, initializer);
        if (guardedFailure == null) {
            guardedFailure = assignmentOrdinaryValueInitializerFailure(initializer);
        }
        var initializerType = guardedFailure != null
                ? guardedFailure
                : new BodyExpressionResolver(context).resolveExpressionType(initializer, false);
        var stableType = stableLocalTypeOrNull(initializerType);
        if (stableType == null) {
            return;
        }
        context.typedEnvironment().addLocalSlotTypeUpdate(
                FrontendSemanticStage.LOCAL_TYPE_STABILIZATION,
                new FrontendLocalSlotTypeUpdate(
                        blockScope,
                        variableDeclaration.name().trim(),
                        variableDeclaration,
                        stableType
                )
        );
    }

    @Override
    public void runChainBinding(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        var resolver = new BodyExpressionResolver(context);
        forEachExpression(root, expression -> {
            if (expression instanceof AttributeExpression attributeExpression) {
                var reduced = resolver.reduceAttributeExpression(attributeExpression);
                if (reduced != null) {
                    publishReduction(context, reduced);
                }
            } else if (expression instanceof LambdaExpression lambdaExpression) {
                // Recorded lambdas were already resolved by the top-binding trigger of this same
                // statement; only unrecorded ones stay fail-closed here.
                if (!context.interfaceSurface().suiteEntryRoots().containsCallableOwner(lambdaExpression)) {
                    reportUnsupportedChain(context, lambdaExpression, "lambda subtree");
                }
            }
        });
    }

    @Override
    public void runExprType(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        var resolver = new BodyExpressionResolver(context);
        publishRootExpressionTypes(context, resolver, root);
        if (root instanceof ExpressionStatement expressionStatement) {
            reportDiscardedExpressionWarning(
                    context,
                    expressionStatement.expression(),
                    context.typedEnvironment().expressionType(expressionStatement.expression())
            );
        }
        if (root instanceof VariableDeclaration variableDeclaration && variableDeclaration.value() != null) {
            var declarationScope = context.analysisData().scopesByAst().get(variableDeclaration);
            checkInferredLocalTypeConsistency(
                    variableDeclaration,
                    declarationScope instanceof BlockScope blockScope ? blockScope : null,
                    context.typedEnvironment().expressionType(variableDeclaration.value())
            );
        }
    }

    /// Parameter-default island root typing: identical to the bare-expression branch of
    /// `runExprType`, but the parameter slot type flows in as the expected type so typed container
    /// literals (e.g. `Array[int] = [1, 2]`) keep their declared element types. `Variant`/untyped
    /// parameters drop the context through `contextualExpectedOrNull` like every other root.
    @Override
    public void runParameterDefaultExprType(
            @NotNull FrontendSuiteContext context,
            @NotNull Expression defaultRoot,
            @Nullable GdType expectedType
    ) {
        var resolver = new BodyExpressionResolver(context);
        publishExpressionType(context, resolver, defaultRoot, false, contextualExpectedOrNull(expectedType));
    }

    private static void reportDiscardedExpressionWarning(
            @NotNull FrontendSuiteContext context,
            @NotNull Expression expression,
            @Nullable FrontendExpressionType expressionType
    ) {
        if (expressionType == null
                || expressionType.status() != FrontendExpressionTypeStatus.RESOLVED
                || expressionType.publishedType() == null
                || expressionType.publishedType() instanceof GdVoidType) {
            return;
        }
        context.diagnosticManager().warning(
                DISCARDED_EXPRESSION_CATEGORY,
                "Discarded expression result of type '" + expressionType.publishedType().getTypeName() + "'",
                context.sourcePath(),
                FrontendRange.fromAstRange(expression.range())
        );
    }

    /// Verifies the local-stabilization result without creating a second slot-mutation owner.
    static void checkInferredLocalTypeConsistency(
            @NotNull VariableDeclaration variableDeclaration,
            @Nullable BlockScope blockScope,
            @Nullable FrontendExpressionType initializerType
    ) {
        if (!FrontendDeclaredTypeSupport.isInferredTypeRef(variableDeclaration.type())
                || variableDeclaration.value() == null
                || blockScope == null
                || initializerType == null) {
            return;
        }
        var existingLocal = blockScope.resolveValueHere(variableDeclaration.name().trim());
        if (existingLocal == null || existingLocal.declaration() != variableDeclaration) {
            return;
        }
        var resolvedInitializerType = switch (initializerType.status()) {
            case RESOLVED -> initializerType.publishedType();
            case DYNAMIC, BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> null;
        };
        if (resolvedInitializerType == null || resolvedInitializerType instanceof GdVoidType) {
            return;
        }
        if (resolvedInitializerType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend local consistency guard: "
                            + compilerOnlyType.getTypeName()
            );
        }
        if (!(existingLocal.type() instanceof GdVariantType)
                && !existingLocal.type().getTypeName().equals(resolvedInitializerType.getTypeName())) {
            throw new IllegalStateException(
                    "Inferred local slot type changed after stabilization for '"
                            + variableDeclaration.name().trim()
                            + "': existing="
                            + existingLocal.type().getTypeName()
                            + ", initializer="
                            + resolvedInitializerType.getTypeName()
            );
        }
    }

    @Override
    public void runVarTypePost(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        if (root instanceof ForStatement forStatement) {
            publishForIteratorSlotType(context, forStatement);
            return;
        }
        if (root instanceof MatchStatement matchStatement) {
            publishMatchBindSlotTypes(context, matchStatement);
            return;
        }
        if (!(root instanceof VariableDeclaration variableDeclaration)
                || variableDeclaration.kind() != DeclarationKind.VAR) {
            return;
        }
        var declarationScope = context.analysisData().scopesByAst().get(variableDeclaration);
        if (!(declarationScope instanceof BlockScope blockScope)) {
            return;
        }
        var localName = variableDeclaration.name().trim();
        var slot = blockScope.resolveValueHere(localName);
        if (slot == null || slot.declaration() != variableDeclaration) {
            reportRejectedLocalSlotPublication(context, blockScope, variableDeclaration, slot);
            return;
        }
        var effectiveSlot = context.typedEnvironment().effectiveScopeValue(slot, blockScope);
        context.typedEnvironment().putSlotType(
                FrontendSemanticStage.VAR_TYPE_POST,
                variableDeclaration,
                effectiveSlot.type()
        );
    }

    @Override
    public void runForIterationResolution(@NotNull FrontendSuiteContext context, @NotNull ForStatement forStatement) {
        var declaredIteratorType = resolveDeclaredIteratorType(context, forStatement);
        var iterableType = resolveIterableType(context, forStatement);
        var plan = FrontendForLoopSupport.buildPlan(forStatement, declaredIteratorType, iterableType);
        context.typedEnvironment().putForIterationPlan(
                FrontendSemanticStage.FOR_ITERATION_RESOLUTION,
                forStatement,
                plan
        );
        refineIteratorSlot(context, forStatement, plan);
    }

    @Override
    public void runMatchPatternResolution(
            @NotNull FrontendSuiteContext context,
            @NotNull MatchStatement matchStatement
    ) {
        var plan = FrontendMatchSupport.buildPlan(matchStatement);
        context.typedEnvironment().putMatchPlan(
                FrontendSemanticStage.MATCH_PATTERN_RESOLUTION,
                matchStatement,
                plan
        );
        refineMatchBindSlots(context, matchStatement, plan);
    }

    private @Nullable GdType resolveDeclaredIteratorType(
            @NotNull FrontendSuiteContext context,
            @NotNull ForStatement forStatement
    ) {
        if (forStatement.iteratorType() == null
                || FrontendDeclaredTypeSupport.isInferredTypeRef(forStatement.iteratorType())) {
            return null;
        }
        var forBodyScope = context.analysisData().scopesByAst().get(forStatement.body());
        if (!(forBodyScope instanceof BlockScope blockScope)) {
            return null;
        }
        var iteratorSlot = blockScope.resolveValueHere(forStatement.iterator());
        if (iteratorSlot == null || iteratorSlot.declaration() != forStatement) {
            return null;
        }
        return iteratorSlot.type();
    }

    private @Nullable GdType resolveIterableType(
            @NotNull FrontendSuiteContext context,
            @NotNull ForStatement forStatement
    ) {
        if (FrontendForLoopSupport.isBareRangeCall(forStatement.iterable())) {
            return null;
        }
        var expressionType = context.typedEnvironment().expressionType(forStatement.iterable());
        if (expressionType == null || expressionType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return null;
        }
        return expressionType.publishedType();
    }

    /// Refines the iterator local via `FOR_ITERATION_RESOLUTION` against **FOR_BODY** scope identity.
    /// Must use `scopesByAst[forStatement.body()]`, not `scopesByAst[ForStatement]` (header outer),
    /// so `effectiveBinding` / `findLocalSlotTypeUpdate` can match with `scope ==`.
    private void refineIteratorSlot(
            @NotNull FrontendSuiteContext context,
            @NotNull ForStatement forStatement,
            @NotNull FrontendForIterationPlan plan
    ) {
        var forBodyScope = context.analysisData().scopesByAst().get(forStatement.body());
        if (!(forBodyScope instanceof BlockScope blockScope)) {
            return;
        }
        var iteratorSlot = blockScope.resolveValueHere(plan.iteratorName());
        if (iteratorSlot == null || iteratorSlot.declaration() != forStatement) {
            return;
        }
        var effectiveSlot = context.typedEnvironment().effectiveScopeValue(iteratorSlot, blockScope);
        if (!(effectiveSlot.type() instanceof GdVariantType)) {
            return;
        }
        if (plan.exposedIteratorType() instanceof GdVariantType) {
            return;
        }
        context.typedEnvironment().addLocalSlotTypeUpdate(
                FrontendSemanticStage.FOR_ITERATION_RESOLUTION,
                new FrontendLocalSlotTypeUpdate(
                        blockScope,
                        plan.iteratorName(),
                        forStatement,
                        plan.exposedIteratorType()
                )
        );
    }

    /// Publishes source-facing `slotTypes()[ForStatement]` from the effective type on **FOR_BODY**.
    private void publishForIteratorSlotType(@NotNull FrontendSuiteContext context, @NotNull ForStatement forStatement) {
        var forBodyScope = context.analysisData().scopesByAst().get(forStatement.body());
        if (!(forBodyScope instanceof BlockScope blockScope)) {
            return;
        }
        var iteratorName = forStatement.iterator();
        var slot = blockScope.resolveValueHere(iteratorName);
        if (slot == null || slot.declaration() != forStatement) {
            return;
        }
        var effectiveSlot = context.typedEnvironment().effectiveScopeValue(slot, blockScope);
        context.typedEnvironment().putSlotType(
                FrontendSemanticStage.VAR_TYPE_POST,
                forStatement,
                effectiveSlot.type()
        );
    }

    /// Refines top-level binds via `MATCH_PATTERN_RESOLUTION` against the section
    /// `MATCH_SECTION_BODY` identity. Nested binds stay `Variant`. Subject type must already be a
    /// stable non-Variant fact; otherwise the bind keeps its inventory baseline.
    private void refineMatchBindSlots(
            @NotNull FrontendSuiteContext context,
            @NotNull MatchStatement matchStatement,
            @NotNull FrontendMatchPlan plan
    ) {
        var subjectType = resolveMatchSubjectType(context, matchStatement);
        if (subjectType == null || subjectType instanceof GdVariantType) {
            return;
        }
        if (subjectType instanceof GdVoidType || subjectType instanceof GdCompilerType) {
            return;
        }
        var divergentNames = collectDivergentMatchBindNames(plan);
        for (var sectionPlan : plan.sections()) {
            var sectionScope = context.analysisData().scopesByAst().get(sectionPlan.section());
            if (!(sectionScope instanceof BlockScope blockScope)) {
                continue;
            }
            for (var patternPlan : sectionPlan.patterns()) {
                for (var bindingPlan : patternPlan.bindings()) {
                    if (!bindingPlan.topLevel()) {
                        continue;
                    }
                    if (divergentNames.contains(bindingPlan.name())) {
                        continue;
                    }
                    refineMatchBindSlot(context, blockScope, bindingPlan, subjectType);
                }
            }
        }
    }

    /// Same-name binds of distinct sections share one name-keyed function variable at lowering, so
    /// a name carried by both a top-level bind (refined to the subject type) and a nested
    /// destructuring bind (always Variant) would publish divergent types for one storage slot.
    /// Godot match binds are untyped at runtime, and the frozen capture entry / scope binding /
    /// slot type must agree with the shared storage, so such a name group keeps the Variant
    /// inventory baseline for every member. Cross-match divergence cannot be seen from one plan and
    /// is still unified at CFG build time.
    private static @NotNull Set<String> collectDivergentMatchBindNames(@NotNull FrontendMatchPlan plan) {
        var topLevelNames = new HashSet<String>();
        var nestedNames = new HashSet<String>();
        for (var sectionPlan : plan.sections()) {
            for (var patternPlan : sectionPlan.patterns()) {
                for (var bindingPlan : patternPlan.bindings()) {
                    (bindingPlan.topLevel() ? topLevelNames : nestedNames).add(bindingPlan.name());
                }
            }
        }
        topLevelNames.retainAll(nestedNames);
        return topLevelNames;
    }

    private void refineMatchBindSlot(
            @NotNull FrontendSuiteContext context,
            @NotNull BlockScope blockScope,
            @NotNull FrontendMatchBindingPlan bindingPlan,
            @NotNull GdType subjectType
    ) {
        var bindSlot = blockScope.resolveValueHere(bindingPlan.name());
        if (bindSlot == null || bindSlot.declaration() != bindingPlan.declaration()) {
            return;
        }
        var effectiveSlot = context.typedEnvironment().effectiveScopeValue(bindSlot, blockScope);
        if (!(effectiveSlot.type() instanceof GdVariantType)) {
            return;
        }
        context.typedEnvironment().addLocalSlotTypeUpdate(
                FrontendSemanticStage.MATCH_PATTERN_RESOLUTION,
                new FrontendLocalSlotTypeUpdate(
                        blockScope,
                        bindingPlan.name(),
                        bindingPlan.declaration(),
                        subjectType
                )
        );
    }

    private @Nullable GdType resolveMatchSubjectType(
            @NotNull FrontendSuiteContext context,
            @NotNull MatchStatement matchStatement
    ) {
        var expressionType = context.typedEnvironment().expressionType(matchStatement.value());
        if (expressionType == null || expressionType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return null;
        }
        return expressionType.publishedType();
    }

    /// Publishes source-facing `slotTypes()[PatternBindingExpression]` from the effective type on
    /// the section `MATCH_SECTION_BODY`. Rejected binds (duplicate/shadowing) emit the ordinary
    /// `sema.variable_slot_publication` warning so compile-gate hole scanning can upgrade them.
    private void publishMatchBindSlotTypes(
            @NotNull FrontendSuiteContext context,
            @NotNull MatchStatement matchStatement
    ) {
        var plan = context.typedEnvironment().matchPlan(matchStatement);
        if (plan == null) {
            plan = context.analysisData().matchPlans().get(matchStatement);
        }
        if (plan == null) {
            return;
        }
        for (var sectionPlan : plan.sections()) {
            var sectionScope = context.analysisData().scopesByAst().get(sectionPlan.section());
            if (!(sectionScope instanceof BlockScope blockScope)) {
                continue;
            }
            for (var patternPlan : sectionPlan.patterns()) {
                for (var bindingPlan : patternPlan.bindings()) {
                    publishMatchBindSlotType(context, blockScope, bindingPlan.declaration());
                }
            }
        }
    }

    private void publishMatchBindSlotType(
            @NotNull FrontendSuiteContext context,
            @NotNull BlockScope blockScope,
            @NotNull PatternBindingExpression patternBinding
    ) {
        var slot = blockScope.resolveValueHere(patternBinding.name());
        if (slot == null || slot.declaration() != patternBinding) {
            reportRejectedPatternBindSlotPublication(context, blockScope, patternBinding, slot);
            return;
        }
        var effectiveSlot = context.typedEnvironment().effectiveScopeValue(slot, blockScope);
        context.typedEnvironment().putSlotType(
                FrontendSemanticStage.VAR_TYPE_POST,
                patternBinding,
                effectiveSlot.type()
        );
    }

    private void reportRejectedPatternBindSlotPublication(
            @NotNull FrontendSuiteContext context,
            @NotNull BlockScope blockScope,
            @NotNull PatternBindingExpression patternBinding,
            @Nullable ScopeValue currentLayerSlot
    ) {
        var survivingSlot = findSurvivingCallableLocalBinding(
                blockScope,
                patternBinding.name(),
                currentLayerSlot
        );
        var message = new StringBuilder()
                .append("Pattern binding '")
                .append(patternBinding.name())
                .append("' in ")
                .append(describeLocalContext(blockScope, context.callableOwner()))
                .append(" has no lowering-ready published slot type at ")
                .append(formatRange(patternBinding))
                .append(" in ")
                .append(context.sourcePath())
                .append("; the declaration was not accepted into callable-local inventory");
        if (survivingSlot != null && survivingSlot.declaration() instanceof Node survivingDeclaration) {
            message.append("; surviving slot currently resolves to ")
                    .append(describeSurvivingDeclaration(survivingSlot))
                    .append(" at ")
                    .append(formatRange(survivingDeclaration));
        } else {
            message.append("; this usually means earlier variable analysis rejected the declaration as duplicate or shadowing");
        }
        context.diagnosticManager().warning(
                VARIABLE_SLOT_PUBLICATION_CATEGORY,
                message.toString(),
                context.sourcePath(),
                FrontendRange.fromAstRange(patternBinding.range())
        );
    }

    private void reportRejectedLocalSlotPublication(
            @NotNull FrontendSuiteContext context,
            @NotNull BlockScope blockScope,
            @NotNull VariableDeclaration variableDeclaration,
            @Nullable ScopeValue currentLayerSlot
    ) {
        var survivingSlot = findSurvivingCallableLocalBinding(blockScope, variableDeclaration.name().trim(), currentLayerSlot);
        var message = new StringBuilder()
                .append("Local variable '")
                .append(variableDeclaration.name().trim())
                .append("' in ")
                .append(describeLocalContext(blockScope, context.callableOwner()))
                .append(" has no lowering-ready published slot type at ")
                .append(formatRange(variableDeclaration))
                .append(" in ")
                .append(context.sourcePath())
                .append("; the declaration was not accepted into callable-local inventory");
        if (survivingSlot != null && survivingSlot.declaration() instanceof Node survivingDeclaration) {
            message.append("; surviving slot currently resolves to ")
                    .append(describeSurvivingDeclaration(survivingSlot))
                    .append(" at ")
                    .append(formatRange(survivingDeclaration));
        } else {
            message.append("; this usually means earlier variable analysis rejected the declaration as duplicate or shadowing");
        }
        context.diagnosticManager().warning(
                VARIABLE_SLOT_PUBLICATION_CATEGORY,
                message.toString(),
                context.sourcePath(),
                FrontendRange.fromAstRange(variableDeclaration.range())
        );
    }

    private @Nullable ScopeValue findSurvivingCallableLocalBinding(
            @NotNull BlockScope declarationScope,
            @NotNull String variableName,
            @Nullable ScopeValue currentLayerSlot
    ) {
        if (currentLayerSlot != null) {
            return currentLayerSlot;
        }
        return findCallableLocalBindingUpScopes(declarationScope.getParentScope(), variableName);
    }

    /// Walks outer scopes from [startScope] up to the enclosing callable boundary, returning the
    /// first binding named [variableName] found in an intermediate block scope or the callable scope
    /// itself. Returns `null` when no such binding exists before the callable boundary.
    static @Nullable ScopeValue findCallableLocalBindingUpScopes(
            @Nullable Scope startScope,
            @NotNull String variableName
    ) {
        Scope currentScope = startScope;
        while (currentScope != null) {
            if (currentScope instanceof BlockScope outerBlockScope) {
                var outerLocal = outerBlockScope.resolveValueHere(variableName);
                if (outerLocal != null) {
                    return outerLocal;
                }
                currentScope = outerBlockScope.getParentScope();
                continue;
            }
            if (currentScope instanceof CallableScope callableScope) {
                return callableScope.resolveValueHere(variableName);
            }
            return null;
        }
        return null;
    }

    private static @NotNull String describeLocalContext(
            @NotNull BlockScope blockScope,
            @NotNull Node callableOwner
    ) {
        return switch (blockScope.kind()) {
            case FUNCTION_BODY, CONSTRUCTOR_BODY -> describeCallableContext(callableOwner);
            case BLOCK_STATEMENT -> "block statement of " + describeCallableContext(callableOwner);
            case IF_BODY -> "if-body of " + describeCallableContext(callableOwner);
            case ELIF_BODY -> "elif-body of " + describeCallableContext(callableOwner);
            case ELSE_BODY -> "else-body of " + describeCallableContext(callableOwner);
            case WHILE_BODY -> "while-body of " + describeCallableContext(callableOwner);
            case LAMBDA_BODY -> "lambda-body of " + describeCallableContext(callableOwner);
            case FOR_BODY -> "`for` body of " + describeCallableContext(callableOwner);
            case MATCH_SECTION_BODY -> "`match` section of " + describeCallableContext(callableOwner);
        };
    }

    private static @NotNull String describeCallableContext(@NotNull Node callableOwner) {
        return switch (callableOwner) {
            case FunctionDeclaration functionDeclaration -> "function '" + functionDeclaration.name().trim() + "'";
            case ConstructorDeclaration _ -> "constructor '_init'";
            default -> callableOwner.getClass().getSimpleName();
        };
    }

    private static @NotNull String describeSurvivingDeclaration(@NotNull ScopeValue survivingSlot) {
        return switch (survivingSlot.kind()) {
            case LOCAL -> "another accepted local declaration";
            case PARAMETER -> "the parameter declaration";
            case CAPTURE -> "the capture declaration";
            case CONSTANT -> "the constant declaration";
            default -> "an accepted callable-local binding";
        };
    }

    private static @NotNull String formatRange(@NotNull Node node) {
        var range = FrontendRange.fromAstRange(node.range());
        if (range == null) {
            return "<unknown-range>";
        }
        return "%d:%d-%d:%d".formatted(
                range.start().line(),
                range.start().column(),
                range.end().line(),
                range.end().column()
        );
    }

    @Override
    public void runUnsupported(@NotNull FrontendSuiteContext context, @NotNull Node root) {
        switch (root) {
            case VariableDeclaration variableDeclaration when variableDeclaration.value() != null -> {
                reportUnsupportedBinding(context, variableDeclaration.value(), "block-local const initializer");
                reportUnsupportedChain(context, variableDeclaration.value(), "block-local const initializer");
            }
            case FunctionDeclaration functionDeclaration ->
                    reportStandaloneLambdaStatement(context, functionDeclaration);
            case ConstructorDeclaration constructorDeclaration ->
                    reportStandaloneLambdaStatement(context, constructorDeclaration);
            default -> {
            }
        }
    }

    /// A statement-position `func ...` never becomes a recorded callable owner (Godot rejects the
    /// same shape as an inaccessible standalone lambda), so the enclosing suite meets it here as
    /// an unsupported root; top binding owns the single boundary diagnostic, anchored at the
    /// skipped subtree root.
    private static void reportStandaloneLambdaStatement(
            @NotNull FrontendSuiteContext context,
            @NotNull Node declaration
    ) {
        if (context.interfaceSurface().suiteEntryRoots().containsCallableOwner(declaration)) {
            return;
        }
        reportUnsupportedBindingMessage(
                context,
                declaration,
                "Standalone lambda statement cannot be accessed; "
                        + "consider assigning it to a variable or passing it as an argument"
        );
    }

    private void bindIdentifier(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        if (context.typedEnvironment().symbolBinding(identifierExpression) != null) {
            return;
        }
        // `super` is a keyword position marker, not a value symbol; without the dedicated binding
        // the generic path would misreport it as an unresolvable identifier.
        if ("super".equals(identifierExpression.name())) {
            bindSuper(context, identifierExpression);
            return;
        }
        var valueResolution = resolveVisibleValue(context, identifierExpression);
        if (valueResolution.status() == FrontendVisibleValueStatus.FOUND_ALLOWED
                || valueResolution.status() == FrontendVisibleValueStatus.FOUND_BLOCKED) {
            publishScopeValueBinding(context, identifierExpression, valueResolution);
            if (valueResolution.status() == FrontendVisibleValueStatus.FOUND_BLOCKED
                    && !context.isParameterDefaultIsland()) {
                reportBindingError(
                        context,
                        identifierExpression,
                        "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
                );
            }
            return;
        }
        if (valueResolution.status() == FrontendVisibleValueStatus.DEFERRED_UNSUPPORTED) {
            return;
        }
        // Parameter-default island: a bare identifier that misses every visible layer but names a
        // callable local is a visibility violation, not an unknown name. Publish it as blocked so
        // the island root fails silently and the metadata owner anchors the single diagnostic.
        if (context.isParameterDefaultIsland()) {
            var callableLocal = parameterDefaultIslandLocalHit(context, identifierExpression.name());
            if (callableLocal != null) {
                context.typedEnvironment().putSymbolBinding(
                        FrontendSemanticStage.TOP_BINDING,
                        identifierExpression,
                        new FrontendBinding(
                                identifierExpression.name(),
                                toBindingKind(callableLocal.kind()),
                                callableLocal.declaration(),
                                callableLocal,
                                ScopeLookupStatus.FOUND_BLOCKED
                        )
                );
                return;
            }
        }
        if (tryPublishFunctionBinding(context, identifierExpression)) {
            return;
        }
        if (tryPublishTypeMetaBinding(context, identifierExpression)) {
            return;
        }
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(identifierExpression.name(), FrontendBindingKind.UNKNOWN, null)
        );
        reportBindingError(
                context,
                identifierExpression,
                "Unable to resolve value binding '" + identifierExpression.name() + "'"
        );
    }

    /// `super` publishes only the keyword binding; availability rules (property-initializer
    /// boundary, static context) intentionally mirror `bindSelf`.
    private void bindSuper(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        if (context.typedEnvironment().symbolBinding(identifierExpression) == null) {
            context.typedEnvironment().putSymbolBinding(
                    FrontendSemanticStage.TOP_BINDING,
                    identifierExpression,
                    new FrontendBinding("super", FrontendBindingKind.SUPER, null)
            );
        }
        if (context.propertyInitializerContext() != null) {
            reportUnsupportedBindingMessage(
                    context,
                    identifierExpression,
                    FrontendPropertyInitializerSupport.superBoundaryDetail()
            );
            return;
        }
        // Static parameter-default islands reject `super` through the metadata owner's single
        // anchored diagnostic, mirroring the `self` rule in `bindSelf`.
        if (context.isParameterDefaultIsland() && context.staticContext()) {
            return;
        }
        if (context.staticContext()) {
            reportBindingError(context, identifierExpression, "Keyword 'super' is not available in static context");
        }
    }

    private void bindSelf(
            @NotNull FrontendSuiteContext context,
            @NotNull SelfExpression selfExpression
    ) {
        if (context.typedEnvironment().symbolBinding(selfExpression) == null) {
            context.typedEnvironment().putSymbolBinding(
                    FrontendSemanticStage.TOP_BINDING,
                    selfExpression,
                    new FrontendBinding("self", FrontendBindingKind.SELF, null)
            );
        }
        if (context.propertyInitializerContext() != null) {
            reportUnsupportedBindingMessage(
                    context,
                    selfExpression,
                    FrontendPropertyInitializerSupport.unsupportedSelfMessage()
            );
            return;
        }
        // Static parameter-default islands reject `self` through the metadata owner's single
        // anchored diagnostic; the blocked self type still publishes so the island root fails.
        if (context.isParameterDefaultIsland() && context.staticContext()) {
            return;
        }
        if (context.staticContext()) {
            reportBindingError(context, selfExpression, "Keyword 'self' is not available in static context");
        }
    }

    /// Probes the island's enclosing callable body for a top-level local with the given name.
    /// Default expressions live in the `CallableScope`, so body locals can never be hit by the
    /// ordinary layer walk; this probe distinguishes "references a local" (visibility violation,
    /// silent blocked) from a genuinely unknown name (ordinary `sema.binding` error). Locals in
    /// deeper nested blocks stay on the ordinary unknown-name path.
    private static @Nullable ScopeValue parameterDefaultIslandLocalHit(
            @NotNull FrontendSuiteContext context,
            @NotNull String name
    ) {
        if (!(context.callableOwner() instanceof FunctionDeclaration functionDeclaration)) {
            return null;
        }
        if (!(context.analysisData().scopesByAst().get(functionDeclaration.body()) instanceof BlockScope bodyScope)) {
            return null;
        }
        var hit = bodyScope.resolveValueHere(name);
        return hit != null && hit.kind() == ScopeValueKind.LOCAL ? hit : null;
    }

    private void bindLiteral(
            @NotNull FrontendSuiteContext context,
            @NotNull LiteralExpression literalExpression
    ) {
        if (context.typedEnvironment().symbolBinding(literalExpression) != null) {
            return;
        }
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                literalExpression,
                new FrontendBinding(literalExpression.sourceText(), FrontendBindingKind.LITERAL, null)
        );
    }

    private @NotNull FrontendVisibleValueResolution resolveVisibleValue(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        if (context.propertyInitializerContext() != null) {
            var result = context.currentScope().resolveValue(identifierExpression.name(), context.restriction());
            return switch (result.status()) {
                case FOUND_ALLOWED -> FrontendVisibleValueResolution.foundAllowed(result.requireValue(), List.of());
                case FOUND_BLOCKED -> FrontendVisibleValueResolution.foundBlocked(result.requireValue(), List.of());
                case NOT_FOUND -> FrontendVisibleValueResolution.notFound(List.of());
            };
        }
        return visibleValueResolver(context).resolve(
                context.visibleValueResolveRequest(identifierExpression.name(), identifierExpression),
                context.typedEnvironment()
        );
    }

    private void publishScopeValueBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression,
            @NotNull FrontendVisibleValueResolution resolution
    ) {
        var resolvedValue = Objects.requireNonNull(resolution.visibleValue(), "visibleValue must not be null");
        var accessStatus = resolution.status() == FrontendVisibleValueStatus.FOUND_ALLOWED
                ? ScopeLookupStatus.FOUND_ALLOWED
                : ScopeLookupStatus.FOUND_BLOCKED;
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(
                        identifierExpression.name(),
                        toBindingKind(resolvedValue.kind()),
                        resolvedValue.declaration(),
                        resolvedValue,
                        accessStatus
                )
        );
    }

    private boolean tryPublishFunctionBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = currentScopeFor(context, identifierExpression);
        if (currentScope == null) {
            return false;
        }
        var functionResult = currentScope.resolveFunctions(identifierExpression.name(), context.restriction());
        if (!functionResult.isAllowed() && !functionResult.isBlocked()) {
            return false;
        }
        var bindingKind = classifyFunctionBinding(functionResult.requireValue());
        if (bindingKind == null) {
            return false;
        }
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(
                        identifierExpression.name(),
                        bindingKind,
                        List.copyOf(functionResult.requireValue())
                )
        );
        return true;
    }

    private boolean tryPublishTypeMetaBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = currentScopeFor(context, identifierExpression);
        if (currentScope == null) {
            return false;
        }
        var typeMetaResult = context.analysisData().moduleSkeleton().resolveSourceFacingTypeMeta(
                currentScope,
                identifierExpression.name(),
                context.restriction()
        );
        if (!typeMetaResult.isAllowed()) {
            return false;
        }
        context.typedEnvironment().putSymbolBinding(
                FrontendSemanticStage.TOP_BINDING,
                identifierExpression,
                new FrontendBinding(
                        identifierExpression.name(),
                        FrontendBindingKind.TYPE_META,
                        typeMetaResult.requireValue().declaration()
                )
        );
        return true;
    }

    private @Nullable BlockScope eligibleInferredLocalScope(
            @NotNull FrontendSuiteContext context,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        if (variableDeclaration.kind() != DeclarationKind.VAR
                || variableDeclaration.value() == null
                || !FrontendDeclaredTypeSupport.isInferredTypeRef(variableDeclaration.type())) {
            return null;
        }
        var declarationScope = context.analysisData().scopesByAst().get(variableDeclaration);
        if (!(declarationScope instanceof BlockScope blockScope)
                || !FrontendBodySemanticSupportPolicy.forBlockScopeKind(
                blockScope.kind()
        ).publishesLexicalInventory()) {
            return null;
        }
        var survivingLocal = blockScope.resolveValueHere(variableDeclaration.name().trim());
        if (survivingLocal == null || survivingLocal.declaration() != variableDeclaration) {
            return null;
        }
        return blockScope;
    }

    private @Nullable FrontendExpressionType typeMetaOrdinaryValueInitializerFailure(
            @NotNull FrontendSuiteContext context,
            @NotNull Expression initializer
    ) {
        if (!(initializer instanceof IdentifierExpression identifierExpression)) {
            return null;
        }
        var binding = context.typedEnvironment().symbolBinding(identifierExpression);
        if (binding == null || binding.kind() != FrontendBindingKind.TYPE_META) {
            return null;
        }
        return FrontendExpressionType.failed(
                "Type-meta initializer '" + identifierExpression.name()
                        + "' cannot stabilize an inferred local because it is not an ordinary value"
        );
    }

    private static @Nullable FrontendExpressionType assignmentOrdinaryValueInitializerFailure(
            @NotNull Expression initializer
    ) {
        if (!(initializer instanceof AssignmentExpression)) {
            return null;
        }
        return FrontendExpressionType.failed(
                "Assignment initializer cannot stabilize an inferred local because it is not an ordinary value"
        );
    }

    private static @Nullable GdType stableLocalTypeOrNull(@NotNull FrontendExpressionType initializerType) {
        if (initializerType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return null;
        }
        var publishedType = initializerType.publishedType();
        if (publishedType instanceof GdVoidType) {
            return null;
        }
        if (publishedType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend local stabilization: "
                            + compilerOnlyType.getTypeName()
            );
        }
        return publishedType;
    }

    private static void publishReduction(
            @NotNull FrontendSuiteContext context,
            @NotNull FrontendChainReductionHelper.ReductionResult result
    ) {
        for (var trace : result.stepTraces()) {
            if (trace.suggestedMember() != null) {
                context.typedEnvironment().putResolvedMember(
                        FrontendSemanticStage.CHAIN_BINDING,
                        trace.step(),
                        trace.suggestedMember()
                );
                reportMemberTrace(context, trace);
            }
            if (trace.suggestedCall() != null) {
                context.typedEnvironment().putResolvedCall(
                        FrontendSemanticStage.CHAIN_BINDING,
                        trace.step(),
                        trace.suggestedCall()
                );
                reportCallTrace(context, trace);
            }
        }
        reportRecoveryBoundary(context, result);
        for (var note : result.notes()) {
            context.diagnosticManager().warning(
                    note.category(),
                    note.message(),
                    context.sourcePath(),
                    FrontendRange.fromAstRange(note.anchor().range())
            );
        }
    }

    private static void reportMemberTrace(
            @NotNull FrontendSuiteContext context,
            @NotNull FrontendChainReductionHelper.StepTrace trace
    ) {
        if (trace.status() != FrontendChainReductionHelper.Status.BLOCKED
                && trace.status() != FrontendChainReductionHelper.Status.FAILED) {
            return;
        }
        // Parameter-default island: restriction-blocked traces (e.g. `self`/instance members under
        // a static function, parameter/local heads) stay silent so the metadata owner anchors the
        // single default diagnostic. Genuine resolution failures (FAILED) keep reporting.
        if (context.isParameterDefaultIsland() && trace.status() == FrontendChainReductionHelper.Status.BLOCKED) {
            return;
        }
        context.diagnosticManager().error(
                MEMBER_RESOLUTION_CATEGORY,
                Objects.requireNonNull(trace.detailReason(), "detailReason must not be null"),
                context.sourcePath(),
                FrontendRange.fromAstRange(trace.step().range())
        );
    }

    private static void reportCallTrace(
            @NotNull FrontendSuiteContext context,
            @NotNull FrontendChainReductionHelper.StepTrace trace
    ) {
        if (trace.status() != FrontendChainReductionHelper.Status.BLOCKED
                && trace.status() != FrontendChainReductionHelper.Status.FAILED) {
            return;
        }
        // Same island rule as `reportMemberTrace`: BLOCKED stays owner-reported, FAILED reports.
        if (context.isParameterDefaultIsland() && trace.status() == FrontendChainReductionHelper.Status.BLOCKED) {
            return;
        }
        context.diagnosticManager().error(
                CALL_RESOLUTION_CATEGORY,
                Objects.requireNonNull(trace.detailReason(), "detailReason must not be null"),
                context.sourcePath(),
                FrontendRange.fromAstRange(trace.step().range())
        );
    }

    private static void reportRecoveryBoundary(
            @NotNull FrontendSuiteContext context,
            @NotNull FrontendChainReductionHelper.ReductionResult result
    ) {
        var recoveryRoot = result.recoveryRoot();
        if (recoveryRoot == null || result.stepTraces().isEmpty()) {
            return;
        }
        var firstNonResolved = result.stepTraces().stream()
                .filter(trace -> trace.status() != FrontendChainReductionHelper.Status.RESOLVED)
                .findFirst()
                .orElse(null);
        if (firstNonResolved == null) {
            return;
        }
        if (firstNonResolved.status() == FrontendChainReductionHelper.Status.DEFERRED) {
            context.diagnosticManager().warning(
                    DEFERRED_CHAIN_RESOLUTION_CATEGORY,
                    Objects.requireNonNull(firstNonResolved.detailReason(), "detailReason must not be null"),
                    context.sourcePath(),
                    FrontendRange.fromAstRange(recoveryRoot.range())
            );
            return;
        }
        if (firstNonResolved.status() == FrontendChainReductionHelper.Status.UNSUPPORTED) {
            context.diagnosticManager().error(
                    UNSUPPORTED_CHAIN_ROUTE_CATEGORY,
                    Objects.requireNonNull(firstNonResolved.detailReason(), "detailReason must not be null"),
                    context.sourcePath(),
                    FrontendRange.fromAstRange(recoveryRoot.range())
            );
        }
    }

    private static void publishRootExpressionTypes(
            @NotNull FrontendSuiteContext context,
            @NotNull BodyExpressionResolver resolver,
            @NotNull Node root
    ) {
        switch (root) {
            case VariableDeclaration variableDeclaration when variableDeclaration.value() != null ->
                    publishExpressionType(
                            context,
                            resolver,
                            variableDeclaration.value(),
                            false,
                            declaredInitializerExpectedType(context, variableDeclaration)
                    );
            case ExpressionStatement expressionStatement ->
                    publishExpressionType(context, resolver, expressionStatement.expression(), true, null);
            case ReturnStatement returnStatement when returnStatement.value() != null -> publishExpressionType(
                    context,
                    resolver,
                    returnStatement.value(),
                    false,
                    // Variant/void return slots do not provide typed-container context.
                    contextualExpectedOrNull(context.currentCallableReturnType())
            );
            case AssertStatement assertStatement -> {
                publishExpressionType(context, resolver, assertStatement.condition(), false, null);
                if (assertStatement.message() != null) {
                    publishExpressionType(context, resolver, assertStatement.message(), false, null);
                }
            }
            case Expression expression -> publishExpressionType(context, resolver, expression, false, null);
            default ->
                    forEachExpression(root, expression -> publishExpressionType(context, resolver, expression, false, null));
        }
    }

    /// Explicit typed local/property slots supply expected type; inferred (`:=`) stays generic.
    private static @Nullable GdType declaredInitializerExpectedType(
            @NotNull FrontendSuiteContext context,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        if (FrontendDeclaredTypeSupport.isInferredTypeRef(variableDeclaration.type())) {
            return null;
        }
        var declarationScope = context.analysisData().scopesByAst().get(variableDeclaration);
        if (declarationScope instanceof BlockScope blockScope) {
            var slot = blockScope.resolveValueHere(variableDeclaration.name().trim());
            if (slot != null && slot.declaration() == variableDeclaration) {
                return contextualExpectedOrNull(slot.type());
            }
        }
        if (context.propertyInitializerContext() != null) {
            var propertySlot = context.propertyInitializerContext()
                    .declaringClassScope()
                    .resolveValueHere(variableDeclaration.name().trim());
            if (propertySlot != null) {
                return contextualExpectedOrNull(propertySlot.type());
            }
        }
        return null;
    }

    /// `Variant` is an ordinary outer boundary target, not a container construction context.
    private static @Nullable GdType contextualExpectedOrNull(@Nullable GdType expectedType) {
        if (expectedType == null || expectedType instanceof GdVariantType || expectedType instanceof GdVoidType) {
            return null;
        }
        return expectedType;
    }

    private static void publishExpressionType(
            @NotNull FrontendSuiteContext context,
            @NotNull BodyExpressionResolver resolver,
            @NotNull Expression expression,
            boolean allowStatementResult,
            @Nullable GdType expectedType
    ) {
        resolver.populateRootExpressionTransientCaches(expression, allowStatementResult, expectedType);
        for (var entry : resolver.finalizedExpressionTypes().entrySet()) {
            if (resolver.isRouteHeadOnlyTypeMeta(entry.getKey())) {
                continue;
            }
            if (!resolver.isAssignmentTargetPrefixExpression(entry.getKey())
                    && resolver.rootOwnsExpressionDiagnostic(entry.getKey())) {
                reportExpressionDiagnostic(context, resolver, entry.getKey(), entry.getValue());
            }
            context.typedEnvironment().putExpressionType(
                    FrontendSemanticStage.EXPR_TYPE,
                    entry.getKey(),
                    entry.getValue()
            );
            if (entry.getKey() instanceof CastExpression castExpression) {
                reportUnsafeCastWarning(context, resolver, castExpression, entry.getValue());
            }
            if (entry.getKey() instanceof AttributeExpression attributeExpression) {
                publishAttributeStepExpressionTypes(context, resolver.reduceAttributeExpression(attributeExpression));
            }
        }
        if (expression instanceof AssignmentExpression assignmentExpression) {
            publishAssignmentTargetStepExpressionTypes(context, resolver, assignmentExpression.left());
        }
        for (var entry : resolver.resolvedCalls().entrySet()) {
            context.typedEnvironment().putResolvedCall(
                    FrontendSemanticStage.EXPR_TYPE,
                    entry.getKey(),
                    entry.getValue()
            );
            reportUnsafeCallArgumentWarning(context, entry.getKey(), entry.getValue());
        }
        for (var entry : resolver.typeTestTargets().entrySet()) {
            context.typedEnvironment().putTypeTestTarget(
                    FrontendSemanticStage.EXPR_TYPE,
                    entry.getKey(),
                    entry.getValue()
            );
            reportUnresolvedTypeTestTargetWarning(context, entry.getKey(), entry.getValue());
            reportHardTypedIncompatibilityWarning(context, entry.getKey(), entry.getValue());
        }
        for (var entry : resolver.containerLiteralPlans().entrySet()) {
            context.typedEnvironment().putContainerLiteralPlan(
                    FrontendSemanticStage.EXPR_TYPE,
                    entry.getKey(),
                    entry.getValue()
            );
        }
    }

    /// Shared `sema.redundant_await` reporter: used by the EXPR_TYPE owner for call sites that can
    /// never be coroutines (non-`FunctionDef` declaration anchors) and by the post-suite await
    /// coroutine pass once the fixed point proves the callee stays unmarked.
    public static void reportRedundantAwait(
            @NotNull DiagnosticManager diagnosticManager,
            @NotNull java.nio.file.Path sourcePath,
            @NotNull AwaitExpression awaitExpression,
            @NotNull String callableName
    ) {
        diagnosticManager.warning(
                REDUNDANT_AWAIT_CATEGORY,
                "The called function '" + callableName
                        + "' is not a coroutine, so 'await' returns the call result immediately without suspending",
                sourcePath,
                FrontendRange.fromAstRange(awaitExpression.range())
        );
    }

    private static void reportUnresolvedTypeTestTargetWarning(
            @NotNull FrontendSuiteContext context,
            @NotNull TypeTestExpression typeTestExpression,
            @NotNull FrontendTypeTestTarget typeTestTarget
    ) {
        if (!(typeTestTarget instanceof FrontendTypeTestTarget.TargetUnresolvedObject(var typeName))) {
            return;
        }
        context.diagnosticManager().warning(
                TYPE_TEST_UNRESOLVED_OBJECT_CATEGORY,
                "type name '" + typeName
                        + "' not found in scope, will be checked at runtime",
                context.sourcePath(),
                FrontendRange.fromAstRange(typeTestExpression.targetType().range())
        );
    }

    /// Emits a `sema.type_check` warning when the operand's static type is provably incompatible
    /// with the type-test target (neither direction is assignable). Mirrors Godot's analyzer
    /// diagnostic: `Expression is of type "X" so it can't be of type "Y".`
    ///
    /// Skipped for Variant / Nil operands and unresolved object targets (runtime-open).
    private static void reportHardTypedIncompatibilityWarning(
            @NotNull FrontendSuiteContext context,
            @NotNull TypeTestExpression typeTestExpression,
            @NotNull FrontendTypeTestTarget typeTestTarget
    ) {
        if (!(typeTestTarget instanceof FrontendTypeTestTarget.TargetKnown(var targetType))) {
            return;
        }
        var valueExprType = context.typedEnvironment().expressionType(typeTestExpression.value());
        if (valueExprType == null || valueExprType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return;
        }
        var valueType = valueExprType.publishedType();
        if (valueType instanceof GdVariantType || valueType instanceof GdNilType
                || targetType instanceof GdVariantType) {
            return;
        }
        var registry = context.classRegistry();
        if (valueType != null && (registry.checkAssignable(valueType, targetType)
                || registry.checkAssignable(targetType, valueType))) {
            return;
        }
        if (valueType != null) {
            context.diagnosticManager().warning(
                    TYPE_CHECK_CATEGORY,
                    "Expression is of type \"" + valueType.getTypeName()
                            + "\" so it can't be of type \"" + targetType.getTypeName() + "\".",
                    context.sourcePath(),
                    FrontendRange.fromAstRange(typeTestExpression.value().range())
            );
        } else {
            context.diagnosticManager().warning(
                    TYPE_CHECK_CATEGORY,
                    "Expression is of unknown type so it can't be of type \"" + targetType.getTypeName() + "\".",
                    context.sourcePath(),
                    FrontendRange.fromAstRange(typeTestExpression.value().range())
            );
        }
    }

    private static void publishAssignmentTargetStepExpressionTypes(
            @NotNull FrontendSuiteContext context,
            @NotNull BodyExpressionResolver resolver,
            @NotNull Expression targetExpression
    ) {
        if (targetExpression instanceof AttributeExpression attributeExpression) {
            publishAttributeStepExpressionTypes(context, resolver.assignmentTargetReduction(attributeExpression));
        }
    }

    private static void reportUnsafeCallArgumentWarning(
            @NotNull FrontendSuiteContext context,
            @NotNull CallExpression callExpression,
            @NotNull FrontendResolvedCall publishedCall
    ) {
        if (!isUnsafeBuiltinVariantConstructorRoute(publishedCall)) {
            return;
        }
        var sourceType = publishedCall.argumentTypes().getFirst();
        var targetType = Objects.requireNonNull(publishedCall.returnType(), "returnType must not be null");
        context.diagnosticManager().warning(
                UNSAFE_CALL_ARGUMENT_CATEGORY,
                "Unsafe call argument for builtin constructor '" + publishedCall.callableName()
                        + "(...)': static argument type '" + sourceType.getTypeName()
                        + "' requires runtime conversion to '" + targetType.getTypeName() + "'",
                context.sourcePath(),
                FrontendRange.fromAstRange(callExpression.range())
        );
    }

    /// Emits `sema.unsafe_cast` when the cast source is runtime-open (`Variant` or `DYNAMIC`) and
    /// the target is not `Variant`. Coexists with `RESOLVED(targetType)`; does not block compile.
    ///
    /// Value-operand type is read from the resolver transient cache first so publication order of
    /// `finalizedExpressionTypes` cannot hide the source fact.
    private static void reportUnsafeCastWarning(
            @NotNull FrontendSuiteContext context,
            @NotNull BodyExpressionResolver resolver,
            @NotNull CastExpression castExpression,
            @NotNull FrontendExpressionType publishedCastType
    ) {
        if (publishedCastType.status() != FrontendExpressionTypeStatus.RESOLVED
                && publishedCastType.status() != FrontendExpressionTypeStatus.DYNAMIC) {
            return;
        }
        var targetType = publishedCastType.publishedType();
        if (targetType == null || targetType instanceof GdVariantType) {
            return;
        }
        var valueType = resolver.finalizedExpressionTypes().get(castExpression.value());
        if (valueType == null) {
            valueType = context.typedEnvironment().expressionType(castExpression.value());
        }
        if (valueType == null) {
            return;
        }
        var runtimeOpen = valueType.status() == FrontendExpressionTypeStatus.DYNAMIC
                || (valueType.status() == FrontendExpressionTypeStatus.RESOLVED
                && valueType.publishedType() instanceof GdVariantType);
        if (!runtimeOpen) {
            return;
        }
        context.diagnosticManager().warning(
                UNSAFE_CAST_CATEGORY,
                "Casting \"Variant\" to \"" + targetType.getTypeName() + "\" is unsafe.",
                context.sourcePath(),
                FrontendRange.fromAstRange(castExpression.range())
        );
    }

    private static boolean isUnsafeBuiltinVariantConstructorRoute(@NotNull FrontendResolvedCall publishedCall) {
        return publishedCall.status() == FrontendCallResolutionStatus.RESOLVED
                && publishedCall.callKind() == FrontendCallResolutionKind.CONSTRUCTOR
                && publishedCall.receiverKind() == FrontendReceiverKind.TYPE_META
                && publishedCall.ownerKind() == ScopeOwnerKind.BUILTIN
                && publishedCall.argumentTypes().size() == 1
                && publishedCall.argumentTypes().getFirst() instanceof GdVariantType
                && publishedCall.declarationSite() instanceof ExtensionBuiltinClass;
    }

    private static void reportUnsupportedBinding(
            @NotNull FrontendSuiteContext context,
            @NotNull Node anchor,
            @NotNull String domain
    ) {
        context.diagnosticManager().error(
                UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                "Binding analysis is not supported in " + domain,
                context.sourcePath(),
                FrontendRange.fromAstRange(anchor.range())
        );
    }

    private static void reportBindingError(
            @NotNull FrontendSuiteContext context,
            @NotNull Node anchor,
            @NotNull String message
    ) {
        context.diagnosticManager().error(
                BINDING_CATEGORY,
                message,
                context.sourcePath(),
                FrontendRange.fromAstRange(anchor.range())
        );
    }

    private static void reportUnsupportedBindingMessage(
            @NotNull FrontendSuiteContext context,
            @NotNull Node anchor,
            @NotNull String message
    ) {
        context.diagnosticManager().error(
                UNSUPPORTED_BINDING_SUBTREE_CATEGORY,
                message,
                context.sourcePath(),
                FrontendRange.fromAstRange(anchor.range())
        );
    }

    private static void reportUnsupportedChain(
            @NotNull FrontendSuiteContext context,
            @NotNull Node anchor,
            @NotNull String domain
    ) {
        context.diagnosticManager().error(
                UNSUPPORTED_CHAIN_ROUTE_CATEGORY,
                "Chain binding analysis is not supported in " + domain,
                context.sourcePath(),
                FrontendRange.fromAstRange(anchor.range())
        );
    }

    private static void reportUnsupportedExpression(
            @NotNull FrontendSuiteContext context,
            @NotNull Node anchor,
            @NotNull String detailReason
    ) {
        context.diagnosticManager().error(
                UNSUPPORTED_EXPRESSION_ROUTE_CATEGORY,
                detailReason,
                context.sourcePath(),
                FrontendRange.fromAstRange(anchor.range())
        );
    }

    private static void reportExpressionDiagnostic(
            @NotNull FrontendSuiteContext context,
            @NotNull BodyExpressionResolver resolver,
            @NotNull Expression expression,
            @NotNull FrontendExpressionType expressionType
    ) {
        if (!resolver.markExpressionDiagnosticReported(expression)) {
            return;
        }
        switch (expressionType.status()) {
            case FAILED -> context.diagnosticManager().error(
                    EXPRESSION_RESOLUTION_CATEGORY,
                    Objects.requireNonNull(expressionType.detailReason(), "detailReason must not be null"),
                    context.sourcePath(),
                    FrontendRange.fromAstRange(expression.range())
            );
            case DEFERRED -> context.diagnosticManager().warning(
                    DEFERRED_EXPRESSION_RESOLUTION_CATEGORY,
                    Objects.requireNonNull(expressionType.detailReason(), "detailReason must not be null"),
                    context.sourcePath(),
                    FrontendRange.fromAstRange(expression.range())
            );
            case UNSUPPORTED -> reportUnsupportedExpression(
                    context,
                    expression,
                    Objects.requireNonNull(expressionType.detailReason(), "detailReason must not be null")
            );
            default -> {
            }
        }
    }

    private static void publishAttributeStepExpressionTypes(
            @NotNull FrontendSuiteContext context,
            @Nullable FrontendChainReductionHelper.ReductionResult reduction
    ) {
        if (reduction == null) {
            return;
        }
        for (var trace : reduction.stepTraces()) {
            context.typedEnvironment().putExpressionType(
                    FrontendSemanticStage.EXPR_TYPE,
                    trace.step(),
                    resolvePublishedAttributeStepType(trace)
            );
        }
    }

    private static @NotNull FrontendExpressionType resolvePublishedAttributeStepType(
            @NotNull FrontendChainReductionHelper.StepTrace trace
    ) {
        // A SUBSCRIPT step may carry a suggestedMember as static container provenance
        // (`obj.values[i]` with static `values`, published for body lowering only). The step's
        // value type is still the subscript element type from the outgoing receiver, never the
        // container property type.
        if (trace.suggestedMember() != null
                && trace.stepKind() != FrontendChainReductionHelper.StepKind.SUBSCRIPT) {
            return FrontendChainStatusBridge.toPublishedExpressionType(trace.suggestedMember());
        }
        if (trace.suggestedCall() != null) {
            return FrontendChainStatusBridge.toPublishedExpressionType(trace.suggestedCall());
        }
        if (trace.status() == FrontendChainReductionHelper.Status.BLOCKED
                && trace.routeKind() == FrontendChainReductionHelper.RouteKind.UPSTREAM_BLOCKED) {
            return FrontendExpressionType.blocked(
                    null,
                    Objects.requireNonNull(trace.detailReason(), "detailReason must not be null")
            );
        }
        return FrontendChainStatusBridge.toPublishedExpressionType(trace.outgoingReceiver());
    }

    private @NotNull FrontendVisibleValueResolver visibleValueResolver(@NotNull FrontendSuiteContext context) {
        var bodyDeclarationIndex = context.interfaceSurface().bodyDeclarationIndex();
        if (cachedAnalysisData != context.analysisData()
                || cachedBodyDeclarationIndex != bodyDeclarationIndex
                || cachedVisibleValueResolver == null) {
            cachedAnalysisData = context.analysisData();
            cachedBodyDeclarationIndex = bodyDeclarationIndex;
            cachedVisibleValueResolver = new FrontendVisibleValueResolver(
                    context.analysisData(),
                    bodyDeclarationIndex
            );
        }
        return cachedVisibleValueResolver;
    }

    private static @Nullable Scope currentScopeFor(
            @NotNull FrontendSuiteContext context,
            @NotNull Node node
    ) {
        return context.analysisData().scopesByAst().get(node);
    }

    private static @Nullable FrontendBindingKind classifyFunctionBinding(@NotNull List<FunctionDef> overloadSet) {
        var overloads = List.copyOf(Objects.requireNonNull(overloadSet, "overloadSet must not be null"));
        if (overloads.isEmpty()) {
            return null;
        }
        if (overloads.stream().allMatch(ExtensionUtilityFunction.class::isInstance)) {
            return FrontendBindingKind.UTILITY_FUNCTION;
        }
        if (overloads.stream().anyMatch(ExtensionUtilityFunction.class::isInstance)) {
            return null;
        }
        if (overloads.stream().allMatch(FunctionDef::isStatic)) {
            return FrontendBindingKind.STATIC_METHOD;
        }
        if (overloads.stream().anyMatch(FunctionDef::isStatic)) {
            return null;
        }
        return FrontendBindingKind.METHOD;
    }

    private static @NotNull FrontendBindingKind toBindingKind(@NotNull ScopeValueKind scopeValueKind) {
        return switch (Objects.requireNonNull(scopeValueKind, "scopeValueKind must not be null")) {
            case LOCAL -> FrontendBindingKind.LOCAL_VAR;
            case PARAMETER -> FrontendBindingKind.PARAMETER;
            case CAPTURE -> FrontendBindingKind.CAPTURE;
            case PROPERTY -> FrontendBindingKind.PROPERTY;
            case SIGNAL -> FrontendBindingKind.SIGNAL;
            case CONSTANT -> FrontendBindingKind.CONSTANT;
            case SINGLETON -> FrontendBindingKind.SINGLETON;
            case GLOBAL_ENUM -> FrontendBindingKind.GLOBAL_ENUM;
            case TYPE_META -> FrontendBindingKind.TYPE_META;
        };
    }

    private static void forEachExpression(@NotNull Node root, @NotNull Consumer<Expression> consumer) {
        walkRootBounded(root, node -> {
            if (node instanceof Expression expression) {
                consumer.accept(expression);
            }
        });
    }

    private static void walkRootBounded(@NotNull Node node, @NotNull Consumer<Node> consumer) {
        consumer.accept(node);
        if (node instanceof LambdaExpression) {
            return;
        }
        for (var child : node.getChildren()) {
            walkRootBounded(child, consumer);
        }
    }

    private static final class BodyExpressionResolver {
        private final @NotNull FrontendSuiteContext context;
        // Procedure-local transient caches back bounded retry without joining the typed overlay.
        // Only explicit owner publication below can move final facts into pending/committed state.
        private final @NotNull IdentityHashMap<Expression, FrontendExpressionType> expressionTypes =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Expression, FrontendExpressionType> finalizedExpressionTypes =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Expression, Boolean> assignmentTargetPrefixExpressions =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<AttributeExpression, FrontendChainReductionHelper.ReductionResult>
                assignmentTargetReductions = new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Expression, Boolean> routeHeadOnlyTypeMetaExpressions =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<CallExpression, FrontendResolvedCall> resolvedCalls =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<TypeTestExpression, FrontendTypeTestTarget> typeTestTargets =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Expression, FrontendContainerLiteralPlan> containerLiteralPlans =
                new IdentityHashMap<>();
        /// Expected type used when the expression was finalized (null expected stored as absent value
        /// via a sentinel-free Optional map). Conflicts fail-fast instead of silently rewriting.
        private final @NotNull IdentityHashMap<Expression, java.util.Optional<GdType>> finalExpectedTypes =
                new IdentityHashMap<>();
        /// Explicit false means the non-success status was propagated from a dependency; the root
        /// must not re-emit the same diagnostic. Missing keys default to root-owned (true).
        private final @NotNull IdentityHashMap<Expression, Boolean> rootOwnsExpressionDiagnostics =
                new IdentityHashMap<>();
        private final @NotNull IdentityHashMap<Expression, Boolean> reportedExpressionDiagnostics =
                new IdentityHashMap<>();
        private final @NotNull FrontendChainReductionFacade chainReduction;
        private final @NotNull FrontendAssignmentSemanticSupport.Context assignmentSemanticContext;
        private final @NotNull FrontendExpressionSemanticSupport expressionSemanticSupport;
        private final @NotNull IdentityHashMap<AssignmentExpression, FrontendAssignmentSemanticSupport.AssignmentUsage>
                assignmentUsages = new IdentityHashMap<>();

        private BodyExpressionResolver(@NotNull FrontendSuiteContext context) {
            this.context = Objects.requireNonNull(context, "context must not be null");
            chainReduction = new FrontendChainReductionFacade(
                    context.analysisData(),
                    context.analysisData().scopesByAst(),
                    context::restriction,
                    context::staticContext,
                    context::propertyInitializerContext,
                    context.classRegistry(),
                    this::resolveExpressionDependency,
                    identifier -> context.typedEnvironment().symbolBinding(identifier)
            );
            assignmentSemanticContext = FrontendAssignmentSemanticSupport.createContext(
                    context.analysisData().symbolBindings(),
                    identifier -> context.typedEnvironment().symbolBinding(identifier),
                    context.analysisData().scopesByAst(),
                    context.analysisData().moduleSkeleton(),
                    context::restriction,
                    context.classRegistry(),
                    chainReduction
            );
            expressionSemanticSupport = new FrontendExpressionSemanticSupport(
                    identifier -> context.typedEnvironment().symbolBinding(identifier),
                    context.analysisData().scopesByAst(),
                    context::restriction,
                    context::propertyInitializerContext,
                    context.classRegistry(),
                    chainReduction::headReceiverSupport,
                    () -> context.analysisData().moduleSkeleton().topLevelCanonicalNameMap()
            );
        }

        private @NotNull FrontendExpressionType resolveExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return resolveExpressionTypeExpected(expression, finalizeWindow, null);
        }

        /// Expected-type-aware entry used by container-literal contexts, typed roots, and selected calls.
        private @NotNull FrontendExpressionType resolveExpressionTypeExpected(
                @NotNull Expression expression,
                boolean finalizeWindow,
                @Nullable GdType expectedType
        ) {
            var published = context.typedEnvironment().expressionType(expression);
            if (published != null) {
                checkExpectedTypeMatchesFinal(expression, expectedType);
                return published;
            }
            if (finalizeWindow) {
                checkExpectedTypeMatchesFinal(expression, expectedType);
                var finalized = finalizedExpressionTypes.get(expression);
                if (finalized != null) {
                    return finalized;
                }
            } else if (expectedType == null) {
                var cached = expressionTypes.get(expression);
                if (cached != null) {
                    return cached;
                }
            }
            // Non-null expected + !finalizeWindow: recompute without identity cache (overload preview).
            var computed = computeExpressionType(expression, finalizeWindow, expectedType);
            if (finalizeWindow) {
                finalExpectedTypes.put(expression, java.util.Optional.ofNullable(expectedType));
                finalizedExpressionTypes.put(expression, computed);
                expressionTypes.put(expression, computed);
            } else if (expectedType == null) {
                expressionTypes.put(expression, computed);
            }
            return computed;
        }

        private void checkExpectedTypeMatchesFinal(
                @NotNull Expression expression,
                @Nullable GdType expectedType
        ) {
            var finalizedExpected = finalExpectedTypes.get(expression);
            if (finalizedExpected == null) {
                return;
            }
            var previous = finalizedExpected.orElse(null);
            if (!FrontendAnalysisData.sameType(previous, expectedType)) {
                throw new IllegalStateException(
                        "Expression finalized with expected type "
                                + typeNameOrNull(previous)
                                + " cannot be re-requested with expected type "
                                + typeNameOrNull(expectedType)
                );
            }
        }

        private static @NotNull String typeNameOrNull(@Nullable GdType type) {
            return type == null ? "<null>" : type.getTypeName();
        }

        /// Computes the root expression for side effects only: it records statement-vs-value
        /// assignment usage and fills owner-local transient caches that the caller publishes in bulk.
        private void populateRootExpressionTransientCaches(
                @NotNull Expression expression,
                boolean allowStatementResult,
                @Nullable GdType expectedType
        ) {
            if (expression instanceof AssignmentExpression assignmentExpression) {
                assignmentUsages.put(
                        assignmentExpression,
                        allowStatementResult
                                ? FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT
                                : FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED
                );
            }
            resolveExpressionTypeExpected(expression, true, expectedType);
        }

        private @NotNull FrontendExpressionType computeExpressionType(
                @NotNull Expression expression,
                boolean finalizeWindow,
                @Nullable GdType expectedType
        ) {
            return switch (expression) {
                case LiteralExpression literalExpression -> expressionSemanticSupport
                        .resolveLiteralExpressionType(literalExpression)
                        .expressionType();
                case SelfExpression selfExpression -> expressionSemanticSupport
                        .resolveSelfExpressionType(selfExpression)
                        .expressionType();
                case IdentifierExpression identifierExpression -> expressionSemanticSupport
                        .resolveIdentifierExpressionType(identifierExpression)
                        .expressionType();
                case AttributeExpression attributeExpression ->
                        resolveAttributeExpressionType(attributeExpression, finalizeWindow);
                case AssignmentExpression assignmentExpression -> resolveAssignmentExpressionType(
                        assignmentExpression,
                        finalizeWindow
                );
                case CallExpression callExpression -> resolveCallExpressionType(callExpression, finalizeWindow);
                case SubscriptExpression subscriptExpression -> expressionSemanticSupport
                        .resolveSubscriptExpressionType(
                                subscriptExpression,
                                this::resolveExpressionType,
                                finalizeWindow
                        )
                        .expressionType();
                case LambdaExpression lambdaExpression -> expressionSemanticSupport
                        .resolveLambdaExpressionType(
                                lambdaExpression,
                                this::resolveExpressionType,
                                false,
                                finalizeWindow,
                                context.interfaceSurface()
                                        .suiteEntryRoots()
                                        .containsCallableOwner(lambdaExpression)
                        )
                        .expressionType();
                case UnaryExpression unaryExpression -> expressionSemanticSupport
                        .resolveUnaryExpressionType(
                                unaryExpression,
                                this::resolveExpressionType,
                                finalizeWindow
                        )
                        .expressionType();
                case BinaryExpression binaryExpression -> expressionSemanticSupport
                        .resolveBinaryExpressionType(
                                binaryExpression,
                                this::resolveExpressionType,
                                finalizeWindow
                        )
                        .expressionType();
                case TypeTestExpression typeTestExpression ->
                        resolveTypeTestExpressionType(typeTestExpression, finalizeWindow);
                case CastExpression castExpression -> resolveCastExpressionType(castExpression, finalizeWindow);
                case ConditionalExpression conditionalExpression ->
                        resolveConditionalExpressionType(conditionalExpression, finalizeWindow, expectedType);
                case ArrayExpression arrayExpression ->
                        resolveArrayExpressionType(arrayExpression, finalizeWindow, expectedType);
                case DictionaryExpression dictionaryExpression ->
                        resolveDictionaryExpressionType(dictionaryExpression, finalizeWindow, expectedType);
                case AwaitExpression awaitExpression -> resolveAwaitExpressionType(awaitExpression, finalizeWindow);
                case GetNodeExpression _ -> resolveGetNodeExpressionType();
                default -> expressionSemanticSupport
                        .resolveRemainingExplicitExpressionType(
                                expression,
                                this::resolveExpressionType,
                                true,
                                finalizeWindow
                        )
                        .expressionType();
            };
        }

        private @NotNull FrontendExpressionType resolveTypeTestExpressionType(
                @NotNull TypeTestExpression typeTestExpression,
                boolean finalizeWindow
        ) {
            var result = expressionSemanticSupport.resolveTypeTestExpressionType(
                    typeTestExpression,
                    this::resolveExpressionType,
                    finalizeWindow
            );
            rootOwnsExpressionDiagnostics.put(typeTestExpression, result.rootOwnsOutcome());
            if (result.publishedTypeTestTargetOrNull() != null) {
                typeTestTargets.put(typeTestExpression, result.publishedTypeTestTargetOrNull());
            }
            return result.expressionType();
        }

        private @NotNull FrontendExpressionType resolveCastExpressionType(
                @NotNull CastExpression castExpression,
                boolean finalizeWindow
        ) {
            var result = expressionSemanticSupport.resolveCastExpressionType(
                    castExpression,
                    this::resolveExpressionTypeExpected,
                    finalizeWindow
            );
            // Propagated value-operand failures keep the upstream diagnostic owner.
            rootOwnsExpressionDiagnostics.put(castExpression, result.rootOwnsOutcome());
            return result.expressionType();
        }

        private @NotNull FrontendExpressionType resolveConditionalExpressionType(
                @NotNull ConditionalExpression conditionalExpression,
                boolean finalizeWindow,
                @Nullable GdType expectedType
        ) {
            // Binary-style root re-ownership: no rootOwnsExpressionDiagnostics entry is recorded, so
            // the conditional root stays root-owned (default true) and re-emits propagated outcomes.
            return expressionSemanticSupport.resolveConditionalExpressionType(
                    conditionalExpression,
                    this::resolveExpressionTypeExpected,
                    finalizeWindow,
                    expectedType
            ).expressionType();
        }

        private @NotNull FrontendExpressionType resolveArrayExpressionType(
                @NotNull ArrayExpression arrayExpression,
                boolean finalizeWindow,
                @Nullable GdType expectedType
        ) {
            var result = expressionSemanticSupport.resolveArrayExpressionType(
                    arrayExpression,
                    this::resolveExpressionTypeExpected,
                    finalizeWindow,
                    expectedType
            );
            rootOwnsExpressionDiagnostics.put(arrayExpression, result.rootOwnsOutcome());
            // Only finalized resolutions publish plans; speculative previews must not enter this map.
            if (finalizeWindow && result.publishedContainerLiteralPlanOrNull() != null) {
                containerLiteralPlans.put(arrayExpression, result.publishedContainerLiteralPlanOrNull());
            }
            return result.expressionType();
        }

        private @NotNull FrontendExpressionType resolveDictionaryExpressionType(
                @NotNull DictionaryExpression dictionaryExpression,
                boolean finalizeWindow,
                @Nullable GdType expectedType
        ) {
            var result = expressionSemanticSupport.resolveDictionaryExpressionType(
                    dictionaryExpression,
                    this::resolveExpressionTypeExpected,
                    finalizeWindow,
                    expectedType
            );
            rootOwnsExpressionDiagnostics.put(dictionaryExpression, result.rootOwnsOutcome());
            if (finalizeWindow && result.publishedContainerLiteralPlanOrNull() != null) {
                containerLiteralPlans.put(dictionaryExpression, result.publishedContainerLiteralPlanOrNull());
            }
            return result.expressionType();
        }

        /// Await owner hook: the pure classification lives
        /// in `FrontendExpressionSemanticSupport`; this wrapper owns the side effects — coroutine
        /// marking for signal/dynamic routes and pending recording for exact-call operands. The
        /// call-operand coroutine decision never happens here: the callee's own body may resolve
        /// after this owner, so all call pendings are consumed by the post-suite fixed-point pass.
        private @NotNull FrontendExpressionType resolveAwaitExpressionType(
                @NotNull AwaitExpression awaitExpression,
                boolean finalizeWindow
        ) {
            var boundaryReason = awaitFailClosedBoundaryReason();
            var result = expressionSemanticSupport.resolveAwaitExpressionType(
                    awaitExpression,
                    this::resolveExpressionType,
                    this::findAwaitOperandCallOrNull,
                    boundaryReason,
                    finalizeWindow
            );
            rootOwnsExpressionDiagnostics.put(awaitExpression, result.rootOwnsOutcome());
            // Parameter-default island: convert the fail-closed boundary into a silent blocked root
            // so the metadata owner anchors the single default diagnostic; no coroutine marking or
            // pending recording ever starts there.
            if (boundaryReason != null && context.isParameterDefaultIsland()) {
                return FrontendExpressionType.blocked(null, boundaryReason);
            }
            switch (result.route()) {
                case SIGNAL, DYNAMIC -> context.analysisData()
                        .markCoroutineOwner(requireAwaitCoroutineOwner());
                case CALL -> recordAwaitCallPending(
                        awaitExpression,
                        Objects.requireNonNull(
                                result.operandCallOrNull(),
                                "CALL await route must carry the operand call"
                        )
                );
                case NONE -> {
                }
            }
            return result.expressionType();
        }

        /// Fail-closed boundary: property initializers and parameter defaults reject await. Lambda
        /// bodies accept await — their owners are marked through the lambda owner set and bridged to
        /// the synthesized shell during lowering preparation.
        private @Nullable String awaitFailClosedBoundaryReason() {
            if (context.propertyInitializerContext() != null) {
                return "Await expressions are not supported inside property initializers";
            }
            if (context.isParameterDefaultIsland()) {
                return "Await expressions are not supported inside parameter defaults";
            }
            return null;
        }

        /// Get-node shorthand (`$` / `%`) owner hook. The boundary classification needs owner
        /// context (property-initializer island, lambda body, static restriction, class
        /// hierarchy), so it lives here instead of the shared support. Get-node is a leaf
        /// expression with no nested children and never publishes a resolved call, so the hook
        /// returns the outcome directly; the diagnostic anchor is the expression identity itself.
        /// Successful resolution fixes the static type at native `Node`, mirroring Godot's
        /// `reduce_get_node`: no scene-file-based concrete type inference is attempted.
        private @NotNull FrontendExpressionType resolveGetNodeExpressionType() {
            if (context.propertyInitializerContext() != null) {
                // Deferred rather than failed: this is a frontend capability boundary (the
                // initializer runs before the node enters the scene tree), not a source error.
                // The compile gate escalates the deferred warning into a compile-mode error.
                return FrontendExpressionType.deferred(
                        "Get-node expression is not supported inside property initializers"
                );
            }
            if (context.isParameterDefaultIsland()) {
                // Blocked rather than deferred/failed: the island stays silent here so the
                // metadata owner anchors the single parameter-default diagnostic.
                return FrontendExpressionType.blocked(
                        null,
                        "Get-node expression is not supported inside parameter defaults"
                );
            }
            var nodeType = new GdObjectType("Node");
            if (context.callableOwner() instanceof LambdaExpression lambdaExpression) {
                // The capture plan is the single source of truth for whether the enclosing
                // instance receiver is reachable (static / owning-class decisions are already
                // sealed into `buildSelfCaptureEntry`); re-deriving them here would create two
                // truths that can drift. A missing plan violates the publish-before-body
                // contract, so it fails fast instead of becoming a source diagnostic.
                var plan = Objects.requireNonNull(
                        context.typedEnvironment().lambdaPlan(lambdaExpression),
                        "lambda plan must be published before body expression typing of a get-node expression"
                );
                if (plan.capturesSelf()
                        && context.classRegistry().checkAssignable(plan.captures().getFirst().type(), nodeType)) {
                    return FrontendExpressionType.resolved(nodeType);
                }
                // Reuse the function-body message families instead of a lambda-specific wording:
                // the failure reason is the enclosing self-source, not the lambda itself.
                if (plan.enclosingCallable() instanceof FunctionDeclaration enclosingFunction
                        && enclosingFunction.isStatic()) {
                    return FrontendExpressionType.failed(
                            "Get-node expression cannot be used in a static function"
                    );
                }
                return FrontendExpressionType.failed(
                        "Get-node expression can only be used in a class that inherits from Node"
                );
            }
            if (context.staticContext()) {
                return FrontendExpressionType.failed(
                        "Get-node expression cannot be used in a static function"
                );
            }
            var owningClass = Objects.requireNonNull(
                    context.currentScope().owningClassOrNull(),
                    "owning class must be published before body expression typing of a get-node expression"
            );
            if (!context.classRegistry().checkAssignable(new GdObjectType(owningClass.getName()), nodeType)) {
                return FrontendExpressionType.failed(
                        "Get-node expression can only be used in a class that inherits from Node"
                );
            }
            return FrontendExpressionType.resolved(nodeType);
        }

        /// The operand's exact call fact: bare calls publish into the owner-local transient map,
        /// chain calls are anchored on their last `AttributeCallStep` by chain reduction. Anything
        /// else (property reads, plain values, non-exact routes) has no exact call to classify.
        private @Nullable FrontendResolvedCall findAwaitOperandCallOrNull(@NotNull Expression operand) {
            if (operand instanceof CallExpression) {
                return resolvedCalls.get(operand);
            }
            if (operand instanceof AttributeExpression attributeExpression) {
                var steps = attributeExpression.steps();
                if (!steps.isEmpty() && steps.getLast() instanceof AttributeCallStep callStep) {
                    return context.typedEnvironment().resolvedCall(callStep);
                }
            }
            return null;
        }

        private void recordAwaitCallPending(
                @NotNull AwaitExpression awaitExpression,
                @NotNull FrontendResolvedCall operandCall
        ) {
            if (!(operandCall.declarationSite() instanceof FunctionDef calleeFunction)) {
                // Exact calls anchored on a non-FunctionDef site (e.g. builtin constructor owner
                // anchors) are statically known non-coroutines; warn immediately instead of
                // recording a pending the fixed-point pass could never classify.
                reportRedundantAwait(
                        context.diagnosticManager(),
                        context.sourcePath(),
                        awaitExpression,
                        operandCall.callableName()
                );
                return;
            }
            context.analysisData().addAwaitCallPending(new FrontendAwaitCallPending(
                    awaitExpression,
                    requireAwaitCoroutineOwner(),
                    calleeFunction,
                    operandCall.callKind(),
                    context.sourcePath()
            ));
        }

        /// Resolves the coroutine-marking handle of the enclosing callable owner. Lambda owners
        /// (including nested lambdas, which carry their own identity as `context.callableOwner()`)
        /// are keyed by AST identity — never by `FrontendLambdaPlan.enclosingCallable()`, which
        /// would misattribute the marking to the nearest non-lambda callable; named/constructor
        /// owners map to their skeleton `LirFunctionDef`.
        private @NotNull FrontendAwaitCoroutineOwner requireAwaitCoroutineOwner() {
            if (context.callableOwner() instanceof LambdaExpression lambdaExpression) {
                return new FrontendAwaitCoroutineOwner.Lambda(lambdaExpression);
            }
            return new FrontendAwaitCoroutineOwner.NamedFunction(requireEnclosingCallableFunction());
        }

        /// Maps the enclosing callable AST owner to its skeleton `LirFunctionDef` by the same
        /// name/static/arity triple the skeleton builder used when creating it. Only named
        /// function/constructor owners have a skeleton at sema time; lambda owners never reach
        /// here — `requireAwaitCoroutineOwner()` handles them by AST identity first.
        private @NotNull LirFunctionDef requireEnclosingCallableFunction() {
            var callableOwner = context.callableOwner();
            var owningClass = context.currentScope().owningClassOrNull();
            final String functionName;
            final boolean staticFunction;
            final int parameterCount;
            switch (callableOwner) {
                case FunctionDeclaration functionDeclaration -> {
                    functionName = functionDeclaration.name().trim();
                    staticFunction = functionDeclaration.isStatic();
                    parameterCount = functionDeclaration.parameters().size();
                }
                case ConstructorDeclaration constructorDeclaration -> {
                    functionName = "_init";
                    staticFunction = false;
                    parameterCount = constructorDeclaration.parameters().size();
                }
                default -> throw new IllegalStateException(
                        "Await coroutine marking requires a function/constructor callable owner, got "
                                + callableOwner.getClass().getSimpleName()
                );
            }
            var owningClassDef = Objects.requireNonNull(
                    owningClass,
                    "owning class must be published for await coroutine marking of '" + functionName + "'"
            );
            return owningClassDef.getFunctions().stream()
                    .filter(function -> function instanceof LirFunctionDef)
                    .map(LirFunctionDef.class::cast)
                    .filter(function -> function.getName().equals(functionName))
                    .filter(function -> function.isStatic() == staticFunction)
                    .filter(function -> function.getParameterCount() == parameterCount)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Function skeleton has not been published for await coroutine marking: "
                                    + owningClassDef.getName() + "." + functionName
                    ));
        }

        private @NotNull FrontendExpressionType resolveAssignmentExpressionType(
                @NotNull AssignmentExpression assignmentExpression,
                boolean finalizeWindow
        ) {
            var result = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                    assignmentSemanticContext,
                    assignmentExpression,
                    assignmentUsages.getOrDefault(
                            assignmentExpression,
                            FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED
                    ),
                    this::resolveExpressionTypeExpected,
                    finalizeWindow
            ).expressionType();
            if (finalizeWindow) {
                finalizeAssignmentTargetExpressionTypes(assignmentExpression.left());
            }
            return result;
        }

        private void finalizeAssignmentTargetExpressionTypes(@NotNull Expression targetExpression) {
            switch (targetExpression) {
                case AttributeExpression attributeExpression ->
                        finalizeAttributeAssignmentTargetExpressionTypes(attributeExpression, false);
                case SubscriptExpression subscriptExpression ->
                        finalizeSubscriptAssignmentTargetExpressionTypes(subscriptExpression, false);
                default -> {
                }
            }
        }

        private void finalizeAssignmentTargetValueExpression(@NotNull Expression expression) {
            switch (expression) {
                case AttributeExpression attributeExpression ->
                        finalizeAttributeAssignmentTargetExpressionTypes(attributeExpression, true);
                case SubscriptExpression subscriptExpression ->
                        finalizeSubscriptAssignmentTargetExpressionTypes(subscriptExpression, true);
                default -> markAndResolveAssignmentTargetPrefixExpression(expression);
            }
        }

        private void finalizeAttributeAssignmentTargetExpressionTypes(
                @NotNull AttributeExpression attributeExpression,
                boolean publishRootExpression
        ) {
            // Mirror `resolveAttributeExpressionType`: a type-meta chain head (`Worker.values[i] = v`,
            // `Worker.shared = v`) is never consumed as an ordinary value, so its failed ordinary-value
            // type must stay unpublished exactly like on the read path.
            if (isTypeMetaRouteHead(attributeExpression.base())) {
                routeHeadOnlyTypeMetaExpressions.put(attributeExpression.base(), Boolean.TRUE);
            }
            finalizeAssignmentTargetValueExpression(attributeExpression.base());
            for (var step : attributeExpression.steps()) {
                if (step instanceof AttributeCallStep attributeCallStep) {
                    resolveAttributeCallArguments(attributeCallStep, true);
                } else if (step instanceof AttributeSubscriptStep attributeSubscriptStep) {
                    for (var argument : attributeSubscriptStep.arguments()) {
                        resolveExpressionType(argument, true);
                    }
                }
            }
            var reduction = reduceAttributeExpression(attributeExpression);
            if (reduction != null) {
                assignmentTargetReductions.put(attributeExpression, reduction);
            }
            if (publishRootExpression) {
                markAndResolveAssignmentTargetPrefixExpression(attributeExpression);
            }
        }

        private void finalizeSubscriptAssignmentTargetExpressionTypes(
                @NotNull SubscriptExpression subscriptExpression,
                boolean publishRootExpression
        ) {
            finalizeAssignmentTargetValueExpression(subscriptExpression.base());
            for (var argument : subscriptExpression.arguments()) {
                resolveExpressionType(argument, true);
            }
            if (publishRootExpression) {
                markAndResolveAssignmentTargetPrefixExpression(subscriptExpression);
            }
        }

        private void markAndResolveAssignmentTargetPrefixExpression(@NotNull Expression expression) {
            // Lowering materializes assignment receivers, but binding/chain/assignment-root owners
            // still own their diagnostics. Publish the type fact without creating duplicate expr errors.
            assignmentTargetPrefixExpressions.put(expression, Boolean.TRUE);
            resolveExpressionType(expression, true);
        }

        private @NotNull FrontendExpressionType resolveCallExpressionType(
                @NotNull CallExpression callExpression,
                boolean finalizeWindow
        ) {
            // Bare `super(...)` borrows the enclosing callable's own method name; it must not flow
            // into the ordinary bare-call path, which would look up a function literally named
            // "super" and misreport the failure.
            if (callExpression.callee() instanceof IdentifierExpression bareCallee) {
                var bareBinding = context.typedEnvironment().symbolBinding(bareCallee);
                if (bareBinding != null && bareBinding.kind() == FrontendBindingKind.SUPER) {
                    var superResult = expressionSemanticSupport.resolveBareSuperCallExpression(
                            bareCallee,
                            superImplicitMethodNameOrNull(),
                            context.staticContext(),
                            callExpression.arguments(),
                            this::resolveExpressionTypeExpected,
                            finalizeWindow
                    );
                    if (superResult.publishedCallOrNull() != null) {
                        resolvedCalls.put(callExpression, superResult.publishedCallOrNull());
                    }
                    return superResult.expressionType();
                }
            }
            var result = expressionSemanticSupport.resolveCallExpressionType(
                    callExpression,
                    this::resolveExpressionTypeExpected,
                    true,
                    finalizeWindow
            );
            if (result.publishedCallOrNull() != null) {
                resolvedCalls.put(callExpression, result.publishedCallOrNull());
            }
            return result.expressionType();
        }

        /// Bare `super(...)` infers the target name from the enclosing callable owner. Lambda owners
        /// have no stable method name, so they return null and the super route fails closed with a
        /// dedicated reason instead of guessing a name.
        private @Nullable String superImplicitMethodNameOrNull() {
            return switch (context.callableOwner()) {
                case FunctionDeclaration functionDeclaration -> functionDeclaration.name().trim();
                case ConstructorDeclaration _ -> "_init";
                default -> null;
            };
        }

        private @NotNull FrontendExpressionType resolveAttributeExpressionType(
                @NotNull AttributeExpression attributeExpression,
                boolean finalizeWindow
        ) {
            if (isTypeMetaRouteHead(attributeExpression.base())) {
                routeHeadOnlyTypeMetaExpressions.put(attributeExpression.base(), Boolean.TRUE);
            }
            resolveExpressionType(attributeExpression.base(), finalizeWindow);
            for (var step : attributeExpression.steps()) {
                if (step instanceof AttributeCallStep attributeCallStep) {
                    resolveAttributeCallArguments(attributeCallStep, finalizeWindow);
                    continue;
                }
                if (step instanceof AttributeSubscriptStep attributeSubscriptStep) {
                    for (var argument : attributeSubscriptStep.arguments()) {
                        resolveExpressionType(argument, finalizeWindow);
                    }
                }
            }
            var reduced = reduceAttributeExpression(attributeExpression);
            if (reduced == null) {
                return FrontendExpressionType.unsupported(
                        "Nested chain expression is inside an unsupported or skipped subtree"
                );
            }
            return FrontendChainStatusBridge.toPublishedExpressionType(reduced);
        }

        /// Exact selected calls finalize container-literal arguments with fixed parameter types.
        private void resolveAttributeCallArguments(
                @NotNull AttributeCallStep attributeCallStep,
                boolean finalizeWindow
        ) {
            var selectedCall = context.typedEnvironment().resolvedCall(attributeCallStep);
            var fixedParameters = selectedCall != null
                    && selectedCall.exactCallableBoundary() != null
                    ? selectedCall.exactCallableBoundary().fixedParameterTypes()
                    : null;
            var arguments = attributeCallStep.arguments();
            for (var i = 0; i < arguments.size(); i++) {
                var expected = fixedParameters != null && i < fixedParameters.size()
                        ? contextualExpectedOrNull(fixedParameters.get(i))
                        : null;
                resolveExpressionTypeExpected(arguments.get(i), finalizeWindow, expected);
            }
        }

        private @Nullable FrontendChainReductionHelper.ReductionResult reduceAttributeExpression(
                @NotNull AttributeExpression attributeExpression
        ) {
            return chainReduction.reduce(attributeExpression).result();
        }

        private @Nullable FrontendChainReductionHelper.ReductionResult assignmentTargetReduction(
                @NotNull AttributeExpression attributeExpression
        ) {
            return assignmentTargetReductions.get(attributeExpression);
        }

        private @NotNull IdentityHashMap<Expression, FrontendExpressionType> finalizedExpressionTypes() {
            return finalizedExpressionTypes;
        }

        private boolean isAssignmentTargetPrefixExpression(@NotNull Expression expression) {
            return assignmentTargetPrefixExpressions.containsKey(expression);
        }

        private boolean isRouteHeadOnlyTypeMeta(@NotNull Expression expression) {
            return routeHeadOnlyTypeMetaExpressions.containsKey(expression);
        }

        /// Defaults to true for expression kinds that do not record ownership explicitly.
        private boolean rootOwnsExpressionDiagnostic(@NotNull Expression expression) {
            return rootOwnsExpressionDiagnostics.getOrDefault(expression, Boolean.TRUE);
        }

        private boolean markExpressionDiagnosticReported(@NotNull Expression expression) {
            return reportedExpressionDiagnostics.putIfAbsent(expression, Boolean.TRUE) == null;
        }

        private boolean isTypeMetaRouteHead(@NotNull Expression expression) {
            if (!(expression instanceof IdentifierExpression identifierExpression)) {
                return false;
            }
            var binding = context.typedEnvironment().symbolBinding(identifierExpression);
            return binding != null && binding.kind() == FrontendBindingKind.TYPE_META;
        }

        private @NotNull IdentityHashMap<CallExpression, FrontendResolvedCall> resolvedCalls() {
            return resolvedCalls;
        }

        private @NotNull IdentityHashMap<TypeTestExpression, FrontendTypeTestTarget> typeTestTargets() {
            return typeTestTargets;
        }

        private @NotNull IdentityHashMap<Expression, FrontendContainerLiteralPlan> containerLiteralPlans() {
            return containerLiteralPlans;
        }

        private @NotNull FrontendChainReductionHelper.ExpressionTypeResult resolveExpressionDependency(
                @NotNull Expression expression,
                boolean finalizeWindow
        ) {
            return FrontendChainStatusBridge.toExpressionTypeResult(resolveExpressionType(expression, finalizeWindow));
        }
    }
}
