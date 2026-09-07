package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendModuleSkeleton;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.scope.*;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.resolver.ScopeTypeTextSupport;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.UnknownExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/// Shared local expression-semantics helper used by both body-phase analyzers.
///
/// The helper stays analyzer-neutral:
/// - it returns pure semantic results
/// - it never publishes side tables
/// - it never emits diagnostics
/// - it delegates nested expression resolution back to the caller
///
/// `rootOwnsOutcome` tells the caller whether the returned non-success status belongs to the current
/// root expression itself, or is only propagated from an upstream dependency. The expression
/// analyzer uses this to decide whether it owns a frontend diagnostic at the root.
public final class FrontendExpressionSemanticSupport {
    @FunctionalInterface
    public interface NestedExpressionResolver {
        @NotNull FrontendExpressionType resolve(@NotNull Expression expression, boolean finalizeWindow);
    }

    /// Expected-type-aware nested resolver for container literals and other contextual roots.
    /// Call sites must pass a real implementation (e.g. `this::resolveExpressionTypeExpected`);
    /// do not wrap `NestedExpressionResolver` via a silent default that drops expected types.
    @FunctionalInterface
    public interface ContextualNestedExpressionResolver {
        @NotNull FrontendExpressionType resolve(
                @NotNull Expression expression,
                boolean finalizeWindow,
                @Nullable GdType expectedType
        );
    }

    private enum MatchPreference {
        BETTER,
        WORSE,
        EQUAL,
        INCOMPARABLE
    }

    public record ExpressionSemanticResult(
            @NotNull FrontendExpressionType expressionType,
            boolean rootOwnsOutcome,
            @Nullable FrontendResolvedCall publishedCallOrNull,
            @Nullable FrontendTypeTestTarget publishedTypeTestTargetOrNull,
            @Nullable FrontendContainerLiteralPlan publishedContainerLiteralPlanOrNull
    ) {
        public ExpressionSemanticResult {
            Objects.requireNonNull(expressionType, "expressionType must not be null");
        }

        public ExpressionSemanticResult(
                @NotNull FrontendExpressionType expressionType,
                boolean rootOwnsOutcome,
                @Nullable FrontendResolvedCall publishedCallOrNull,
                @Nullable FrontendTypeTestTarget publishedTypeTestTargetOrNull
        ) {
            this(expressionType, rootOwnsOutcome, publishedCallOrNull, publishedTypeTestTargetOrNull, null);
        }

        public ExpressionSemanticResult(
                @NotNull FrontendExpressionType expressionType,
                boolean rootOwnsOutcome,
                @Nullable FrontendResolvedCall publishedCallOrNull
        ) {
            this(expressionType, rootOwnsOutcome, publishedCallOrNull, null, null);
        }
    }

    /// Await classification routes per `frontend_await_implementation.md` §8.
    public enum AwaitRoute {
        /// No classification happened: the outcome was propagated from the operand or produced by
        /// the fail-closed context boundary / unsupported-operand rule.
        NONE,
        /// Operand is a statically known `Signal` value; result type follows the signal parameters.
        SIGNAL,
        /// Operand is runtime-dynamic (`DYNAMIC` status or statically `Variant`); result is Variant.
        DYNAMIC,
        /// Operand is an exact call; whether the callee is a coroutine is decided by the post-suite
        /// await coroutine pass, which consumes the carried `operandCallOrNull`.
        CALL
    }

    /// Dedicated await classification payload: the type to publish for the await node plus the
    /// route metadata the owner analyzer needs for coroutine marking / pending recording.
    public record AwaitSemanticResult(
            @NotNull FrontendExpressionType expressionType,
            boolean rootOwnsOutcome,
            @NotNull AwaitRoute route,
            @Nullable FrontendResolvedCall operandCallOrNull
    ) {
        public AwaitSemanticResult {
            Objects.requireNonNull(expressionType, "expressionType must not be null");
            Objects.requireNonNull(route, "route must not be null");
        }
    }

    private final @NotNull Function<IdentifierExpression, FrontendBinding> bindingLookup;
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull Supplier<ResolveRestriction> restrictionSupplier;
    private final @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext>
            propertyInitializerContextSupplier;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier;
    private final @NotNull Supplier<Map<String, String>> topLevelCanonicalNameMapSupplier;
    private final @NotNull FrontendSubscriptSemanticSupport subscriptSemanticSupport;

    public FrontendExpressionSemanticSupport(
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier
    ) {
        this(
                symbolBindings::get,
                scopesByAst,
                restrictionSupplier,
                () -> null,
                classRegistry,
                headReceiverSupportSupplier,
                Map::of
        );
    }

    public FrontendExpressionSemanticSupport(
            @NotNull FrontendAstSideTable<FrontendBinding> symbolBindings,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext> propertyInitializerContextSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier
    ) {
        this(
                symbolBindings::get,
                scopesByAst,
                restrictionSupplier,
                propertyInitializerContextSupplier,
                classRegistry,
                headReceiverSupportSupplier,
                Map::of
        );
    }

    public FrontendExpressionSemanticSupport(
            @NotNull Function<IdentifierExpression, FrontendBinding> bindingLookup,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext> propertyInitializerContextSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier
    ) {
        this(
                bindingLookup,
                scopesByAst,
                restrictionSupplier,
                propertyInitializerContextSupplier,
                classRegistry,
                headReceiverSupportSupplier,
                Map::of
        );
    }

    public FrontendExpressionSemanticSupport(
            @NotNull Function<IdentifierExpression, FrontendBinding> bindingLookup,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext> propertyInitializerContextSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull Supplier<FrontendChainHeadReceiverSupport> headReceiverSupportSupplier,
            @NotNull Supplier<Map<String, String>> topLevelCanonicalNameMapSupplier
    ) {
        this.bindingLookup = Objects.requireNonNull(bindingLookup, "bindingLookup must not be null");
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.restrictionSupplier = Objects.requireNonNull(restrictionSupplier, "restrictionSupplier must not be null");
        this.propertyInitializerContextSupplier = Objects.requireNonNull(
                propertyInitializerContextSupplier,
                "propertyInitializerContextSupplier must not be null"
        );
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.headReceiverSupportSupplier = Objects.requireNonNull(
                headReceiverSupportSupplier,
                "headReceiverSupportSupplier must not be null"
        );
        this.topLevelCanonicalNameMapSupplier = Objects.requireNonNull(
                topLevelCanonicalNameMapSupplier,
                "topLevelCanonicalNameMapSupplier must not be null"
        );
        subscriptSemanticSupport = new FrontendSubscriptSemanticSupport(this.classRegistry);
    }

    public @NotNull ExpressionSemanticResult resolveLiteralExpressionType(
            @NotNull LiteralExpression literalExpression
    ) {
        var literalType = headReceiverSupportSupplier.get().resolveLiteralType(literalExpression);
        if (literalType != null) {
            return propagated(FrontendExpressionType.resolved(literalType));
        }
        return propagated(FrontendExpressionType.failed(
                "Literal kind '" + literalExpression.kind() + "' does not yet have a local type rule"
        ));
    }

    public @NotNull ExpressionSemanticResult resolveSelfExpressionType(@NotNull Node selfNode) {
        var boundaryDetail = FrontendPropertyInitializerSupport.detailForBindingBoundary(
                currentPropertyInitializerContext(),
                gd.script.gdcc.frontend.sema.FrontendBindingKind.SELF,
                "self"
        );
        if (boundaryDetail != null) {
            return propagated(FrontendExpressionType.blocked(null, boundaryDetail));
        }
        return propagated(FrontendChainStatusBridge.toPublishedExpressionType(
                headReceiverSupportSupplier.get().resolveSelfReceiver(selfNode)
        ));
    }

    public @NotNull ExpressionSemanticResult resolveIdentifierExpressionType(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var binding = bindingFor(identifierExpression);
        if (binding == null) {
            return propagated(FrontendExpressionType.failed(
                    "No published binding fact is available for identifier '" + identifierExpression.name() + "'"
            ));
        }
        var boundaryDetail = FrontendPropertyInitializerSupport.detailForBindingBoundary(
                currentPropertyInitializerContext(),
                binding.kind(),
                identifierExpression.name()
        );
        if (boundaryDetail != null) {
            return propagated(FrontendExpressionType.blocked(null, boundaryDetail));
        }
        return propagated(switch (binding.kind()) {
            case SELF -> resolveSelfExpressionType(identifierExpression).expressionType();
            case PARAMETER, LOCAL_VAR, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON, GLOBAL_ENUM ->
                    resolveValueIdentifierExpressionType(identifierExpression, binding);
            case TYPE_META -> FrontendExpressionType.failed(
                    "Type-meta identifier '" + identifierExpression.name()
                            + "' cannot be consumed as an ordinary value; use a static route such as '"
                            + identifierExpression.name() + ".build(...)', '" + identifierExpression.name()
                            + ".new()', or a static constant access"
            );
            case METHOD, STATIC_METHOD -> resolveCallableIdentifierExpressionType(identifierExpression);
            case UTILITY_FUNCTION -> resolveUtilityIdentifierExpressionType(identifierExpression);
            case UNKNOWN, LITERAL -> FrontendExpressionType.failed(
                    "Identifier '" + identifierExpression.name() + "' does not resolve to a typed value"
            );
        });
    }

    /// Shared bare-call / direct-callable semantics.
    ///
    /// `resolveArgumentsWhenCalleeUnresolved` preserves the current analyzer-specific traversal
    /// contract:
    /// - expression typing still publishes argument expression facts even when a non-bare callee is
    ///   already non-resolved
    /// - chain-local dependency typing may stay conservative and stop early
    public @NotNull ExpressionSemanticResult resolveCallExpressionType(
            @NotNull CallExpression callExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveArgumentsWhenCalleeUnresolved,
            boolean finalizeWindow
    ) {
        return resolveCallExpressionType(
                callExpression,
                (expression, finalize, _) -> nestedResolver.resolve(expression, finalize),
                resolveArgumentsWhenCalleeUnresolved,
                finalizeWindow
        );
    }

    /// Expected-aware bare-call path: container-literal arguments are previewed per candidate, then
    /// finalized with the selected fixed parameter types so published plans stay contextual.
    public @NotNull ExpressionSemanticResult resolveCallExpressionType(
            @NotNull CallExpression callExpression,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean resolveArgumentsWhenCalleeUnresolved,
            boolean finalizeWindow
    ) {
        if (callExpression.callee() instanceof IdentifierExpression bareCallee) {
            var bareBinding = bindingFor(bareCallee);
            if (bareBinding != null && bareBinding.kind() == FrontendBindingKind.TYPE_META) {
                // Preliminary generic snapshot for selection; selected fixed params rewrite
                // argumentTypes and drive contextual finalize of container-literal args.
                var preliminary = resolveCallArgumentTypes(
                        callExpression.arguments(),
                        nestedResolver,
                        false,
                        null
                );
                if (preliminary.issue() != null) {
                    if (finalizeWindow) {
                        resolveCallArgumentTypes(callExpression.arguments(), nestedResolver, true, null);
                    }
                    return propagated(preliminary.issue());
                }
                return resolveBareTypeMetaConstructorCallExpression(
                        bareCallee,
                        callExpression.arguments(),
                        preliminary.argumentTypes(),
                        nestedResolver,
                        finalizeWindow
                );
            }
            if (bareBinding != null
                    && bareBinding.kind() == FrontendBindingKind.UTILITY_FUNCTION
                    && classRegistry.isGdScriptLanguageFunction(bareCallee.name())) {
                // Synthetic GDScript language functions are call-only: value-position resolution
                // rejects them (first-class reference ban, see resolveIdentifierExpressionType),
                // so the callee slot must skip value-resolution entirely, mirroring the TYPE_META
                // constructor branch above. The call itself resolves through the shared bare-call
                // path and publishes its own resolved-call fact.
                return resolveBareIdentifierCallWithLiteralContext(
                        bareCallee,
                        callExpression.arguments(),
                        nestedResolver,
                        finalizeWindow
                );
            }
            var calleeType = nestedResolver.resolve(bareCallee, finalizeWindow, null);
            if (calleeType.status() != FrontendExpressionTypeStatus.RESOLVED
                    && !shouldContinueBlockedBareCallResolution(bareCallee, calleeType)) {
                return propagated(calleeType);
            }
            return resolveBareIdentifierCallWithLiteralContext(
                    bareCallee,
                    callExpression.arguments(),
                    nestedResolver,
                    finalizeWindow
            );
        }

        var calleeType = nestedResolver.resolve(callExpression.callee(), finalizeWindow, null);
        if (!resolveArgumentsWhenCalleeUnresolved
                && calleeType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return propagated(calleeType);
        }

        var argumentResolution = resolveCallArgumentTypes(callExpression.arguments(), nestedResolver, finalizeWindow);
        if (argumentResolution.issue() != null) {
            return propagated(argumentResolution.issue());
        }
        if (calleeType.status() != FrontendExpressionTypeStatus.RESOLVED) {
            return propagated(calleeType);
        }
        return rootOutcome(calleeType.publishedType() instanceof GdCallableType
                ? FrontendExpressionType.unsupported(
                "Direct invocation of callable values is not implemented yet unless the callee is a bare identifier"
        )
                : FrontendExpressionType.failed("Call target does not resolve to a callable value"));
    }

    /// Bare builtin direct constructors are the one intentional exception to the ordinary
    /// "type-meta is not a value" rule used by identifier typing.
    ///
    /// This path keeps object construction strict:
    /// - builtin types may use `Vector3i(...)`, `Array(...)`, ...
    /// - object types must continue to use `TypeName.new(...)`
    private @NotNull ExpressionSemanticResult resolveBareTypeMetaConstructorCallExpression(
            @NotNull IdentifierExpression bareCallee,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var receiverState = headReceiverSupportSupplier.get().resolveTypeMetaReceiver(bareCallee);
        if (receiverState.status() != FrontendChainReductionHelper.Status.RESOLVED) {
            if (finalizeWindow) {
                resolveCallArgumentTypes(argumentExpressions, nestedResolver, true, null);
            }
            return rootOutcome(switch (receiverState.status()) {
                case UNSUPPORTED -> FrontendExpressionType.unsupported(
                        Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
                );
                case FAILED, BLOCKED, DEFERRED, DYNAMIC -> FrontendExpressionType.failed(
                        Objects.requireNonNull(receiverState.detailReason(), "detailReason must not be null")
                );
                case RESOLVED ->
                        throw new IllegalStateException("resolved receiver state unexpectedly missing type-meta metadata");
            });
        }
        var receiverTypeMeta = Objects.requireNonNull(
                receiverState.receiverTypeMeta(),
                "resolved type-meta constructor call must carry receiverTypeMeta"
        );
        if (receiverTypeMeta.kind() != ScopeTypeMetaKind.BUILTIN) {
            if (finalizeWindow) {
                resolveCallArgumentTypes(argumentExpressions, nestedResolver, true, null);
            }
            return rootOutcome(FrontendExpressionType.failed(
                    "Type-meta bare call '" + bareCallee.name()
                            + "(...)' is not a builtin direct constructor; use '" + bareCallee.name()
                            + ".new(...)' or a static route instead"
            ));
        }

        var childSourceResolver = FrontendCallableLiteralArgumentSupport.fromContextualResolver(nestedResolver);
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                classRegistry,
                receiverTypeMeta,
                argumentExpressions,
                preliminaryArgumentTypes,
                childSourceResolver
        );
        return switch (resolution.status()) {
            case RESOLVED -> {
                var boundary = constructorBoundaryOrNull(resolution.declarationSite());
                var selectedParameterTypes = boundary == null ? null : boundary.fixedParameterTypes();
                var finalized = resolveCallArgumentTypes(
                        argumentExpressions,
                        nestedResolver,
                        finalizeWindow,
                        selectedParameterTypes
                );
                if (finalized.issue() != null) {
                    yield propagated(finalized.issue());
                }
                var publishedArgumentTypes = FrontendCallableLiteralArgumentSupport.rewriteArgumentTypes(
                        argumentExpressions,
                        finalized.argumentTypes(),
                        selectedParameterTypes
                );
                yield rootOutcome(
                        FrontendExpressionType.resolved(receiverTypeMeta.instanceType()),
                        resolvedBareConstructorCall(
                                bareCallee,
                                receiverTypeMeta,
                                publishedArgumentTypes,
                                resolution,
                                boundary
                        )
                );
            }
            case FAILED -> {
                var finalized = resolveCallArgumentTypes(argumentExpressions, nestedResolver, finalizeWindow, null);
                if (finalized.issue() != null) {
                    yield propagated(finalized.issue());
                }
                yield rootOutcome(
                        FrontendExpressionType.failed(
                                Objects.requireNonNull(resolution.detailReason(), "detailReason must not be null")
                        ),
                        failedBareConstructorCall(bareCallee, receiverTypeMeta, finalized.argumentTypes(), resolution)
                );
            }
            default ->
                    throw new IllegalStateException("unexpected bare constructor resolution status: " + resolution.status());
        };
    }

    private static @Nullable FrontendResolvedCall.ExactCallableBoundary constructorBoundaryOrNull(
            @Nullable Object declarationSite
    ) {
        if (declarationSite instanceof FunctionDef functionDef) {
            return FrontendResolvedCall.ExactCallableBoundary.fromFunctionDef(functionDef);
        }
        return null;
    }

    /// A blocked bare identifier callee may still be a valid bare-call winner:
    /// `FOUND_BLOCKED` preserves the shadowing overload set, so we should still run overload
    /// selection and keep the blocked call's real return type when the published binding is
    /// function-like. Other blocked callable values continue to short-circuit as propagated
    /// dependencies because they are not part of the bare-call overload route.
    private boolean shouldContinueBlockedBareCallResolution(
            @NotNull IdentifierExpression bareCallee,
            @NotNull FrontendExpressionType calleeType
    ) {
        if (calleeType.status() != FrontendExpressionTypeStatus.BLOCKED
                || !(calleeType.publishedType() instanceof GdCallableType)) {
            return false;
        }
        var binding = bindingFor(Objects.requireNonNull(bareCallee, "bareCallee must not be null"));
        if (binding == null) {
            return false;
        }
        return switch (binding.kind()) {
            case METHOD, STATIC_METHOD, UTILITY_FUNCTION -> true;
            case SELF, LITERAL, LOCAL_VAR, PARAMETER, CAPTURE, PROPERTY, SIGNAL, CONSTANT, SINGLETON,
                 GLOBAL_ENUM, TYPE_META, UNKNOWN -> false;
        };
    }

    public @NotNull ExpressionSemanticResult resolveSubscriptExpressionType(
            @NotNull SubscriptExpression subscriptExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var baseType = nestedResolver.resolve(subscriptExpression.base(), finalizeWindow);
        var dependencyIssue = firstNonResolvedDependency(baseType);
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        var argumentResolution = resolveCallArgumentTypes(
                subscriptExpression.arguments(),
                nestedResolver,
                finalizeWindow
        );
        if (argumentResolution.issue() != null) {
            return propagated(argumentResolution.issue());
        }
        return rootOutcome(subscriptSemanticSupport.resolveSubscriptType(
                Objects.requireNonNull(baseType.publishedType(), "publishedType must not be null"),
                argumentResolution.argumentTypes(),
                "subscript expression"
        ));
    }

    /// Lambda typing contract: a recorded lambda — one the interface phase registered
    /// as a callable owner and whose body already resolved through its nested suite — publishes the
    /// unparameterized `Callable` type, aligned with method-as-value / `construct_callable` facts.
    /// Lambdas outside supported executable bodies (property initializers, parameter defaults,
    /// skipped subtrees) stay fail-closed with the unsupported route. The nested-resolve trigger
    /// itself lives in the top-binding owner (`tryResolveRecordedLambda`), not here.
    public @NotNull ExpressionSemanticResult resolveLambdaExpressionType(
            @NotNull LambdaExpression lambdaExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            boolean finalizeWindow,
            boolean recordedLambda
    ) {
        var dependencyIssue = resolveNestedChildren
                ? firstNestedDependencyIssue(lambdaExpression, nestedResolver, finalizeWindow)
                : null;
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        if (recordedLambda) {
            return rootOutcome(FrontendExpressionType.resolved(new GdCallableType()));
        }
        return rootOutcome(FrontendExpressionType.unsupported(
                "Lambda expression typing is only supported inside a supported executable body"
        ));
    }

    /// Unary operators are now part of the ordinary local expression contract:
    /// - unstable operand outcomes still propagate from upstream
    /// - exact `Variant` operands stay runtime-dynamic
    /// - exact resolved non-Variant operands use builtin operator metadata
    public @NotNull ExpressionSemanticResult resolveUnaryExpressionType(
            @NotNull UnaryExpression unaryExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var operandType = nestedResolver.resolve(unaryExpression.operand(), finalizeWindow);
        var dependencyIssue = firstNonResolvedDependency(operandType);
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        var publishedOperandType = Objects.requireNonNull(
                operandType.publishedType(),
                "publishedType must not be null for stable unary operand"
        );
        if (operandType.status() == FrontendExpressionTypeStatus.DYNAMIC
                || publishedOperandType instanceof GdVariantType) {
            return rootOutcome(FrontendExpressionType.dynamic(
                    "Variant operand routes unary operator '" + unaryExpression.operator()
                            + "' through runtime-dynamic semantics"
            ));
        }

        final GodotOperator operator;
        try {
            operator = GodotOperator.fromSourceLexeme(
                    unaryExpression.operator(),
                    GodotOperator.OperatorArity.UNARY
            );
        } catch (IllegalArgumentException ex) {
            return rootOutcome(FrontendExpressionType.failed(ex.getMessage()));
        }

        var returnType = resolveUnaryExactReturnType(classRegistry, operator, publishedOperandType);
        if (returnType != null) {
            return rootOutcome(FrontendExpressionType.resolved(returnType));
        }
        return rootOutcome(FrontendExpressionType.failed(
                "Unary operator '" + unaryExpression.operator()
                        + "' is not defined for operand type '" + publishedOperandType.getTypeName() + "'"
        ));
    }

    /// Binary operators split into two layers:
    /// - source-level special rules (`and/or`, object/nil equality, object identity equality,
    ///   typed `Array[T] + Array[T]`) plus the source-level composite rule (`not in`)
    /// - ordinary builtin metadata lookup for the remaining exact pairs
    public @NotNull ExpressionSemanticResult resolveBinaryExpressionType(
            @NotNull BinaryExpression binaryExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        var leftOperandType = nestedResolver.resolve(binaryExpression.left(), finalizeWindow);
        var leftDependencyIssue = firstNonResolvedDependency(leftOperandType);
        if (leftDependencyIssue != null) {
            return propagated(leftDependencyIssue);
        }

        var rightOperandType = nestedResolver.resolve(binaryExpression.right(), finalizeWindow);
        var rightDependencyIssue = firstNonResolvedDependency(rightOperandType);
        if (rightDependencyIssue != null) {
            return propagated(rightDependencyIssue);
        }

        return rootOutcome(resolveBinaryOperatorResultType(
                classRegistry,
                binaryExpression.operator(),
                leftOperandType,
                rightOperandType
        ));
    }

    /// Shared binary-operator typing entry used by ordinary `BinaryExpression` roots and compound
    /// assignment semantic checks.
    ///
    /// Callers must pass only stable operands (`RESOLVED` / `DYNAMIC`) whose published type is already
    /// available. Dependency propagation stays outside this helper so the owner can keep precise
    /// root-vs-propagated diagnostic ownership.
    public static @NotNull FrontendExpressionType resolveBinaryOperatorResultType(
            @NotNull ClassRegistry classRegistry,
            @NotNull String operatorText,
            @NotNull FrontendExpressionType leftOperandType,
            @NotNull FrontendExpressionType rightOperandType
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        var actualOperatorText = Objects.requireNonNull(operatorText, "operatorText must not be null");
        var publishedLeftType = requireStableOperatorOperandType("leftOperandType", leftOperandType);
        var publishedRightType = requireStableOperatorOperandType("rightOperandType", rightOperandType);

        // `not in` is intercepted before the enum factory on purpose: `GodotOperator` stays
        // fail-closed for it, and its typing follows the composite rule `not (lhs in rhs)`.
        if ("not in".equals(actualOperatorText)) {
            return resolveNotInOperatorResultType(classRegistry, leftOperandType, rightOperandType);
        }

        final GodotOperator operator;
        try {
            operator = GodotOperator.fromSourceLexeme(actualOperatorText, GodotOperator.OperatorArity.BINARY);
        } catch (IllegalArgumentException ex) {
            return FrontendExpressionType.failed(ex.getMessage());
        }

        var specialReturnType = resolveBinarySpecialReturnType(operator, publishedLeftType, publishedRightType);
        if (specialReturnType != null) {
            return FrontendExpressionType.resolved(specialReturnType);
        }

        // String `%` formatting with a runtime-open right operand still always yields String:
        // Godot's `do_mod` for a String left operand and every `String % T` metadata entry
        // return String. This rule must sit directly in front of the generic runtime-open
        // branch, and it must require a runtime-open right operand so the exact-match stage
        // below stays fail-closed for named object subclasses and `null`.
        if (operator == GodotOperator.MODULE
                && publishedLeftType instanceof GdStringType
                && isRuntimeOpenOperatorOperand(rightOperandType, publishedRightType)) {
            return FrontendExpressionType.resolved(GdStringType.STRING);
        }

        if (isRuntimeOpenOperatorOperand(leftOperandType, publishedLeftType)
                || isRuntimeOpenOperatorOperand(rightOperandType, publishedRightType)) {
            return FrontendExpressionType.dynamic(
                    "Runtime-open operand routes binary operator '" + actualOperatorText
                            + "' through Variant semantics"
            );
        }

        // Godot metadata exposes mixed int/float operator entries, but this frontend boundary
        // intentionally does not turn ordinary typed-boundary widening into operator promotion.
        if (isMixedIntFloatScalarPair(publishedLeftType, publishedRightType)) {
            return FrontendExpressionType.failed(
                    "Binary operator '" + actualOperatorText
                            + "' is not defined for operand types '" + publishedLeftType.getTypeName()
                            + "' and '" + publishedRightType.getTypeName() + "'"
            );
        }

        var exactReturnType = resolveBinaryExactReturnType(
                classRegistry,
                operator,
                publishedLeftType,
                publishedRightType
        );
        if (exactReturnType != null) {
            return FrontendExpressionType.resolved(exactReturnType);
        }
        return FrontendExpressionType.failed(
                "Binary operator '" + actualOperatorText
                        + "' is not defined for operand types '" + publishedLeftType.getTypeName()
                        + "' and '" + publishedRightType.getTypeName() + "'"
        );
    }

    /// Composite typing rule for source-level `a not in b`, typed exactly as `not (a in b)`.
    ///
    /// The inner `in` pairing reuses the ordinary binary pipeline so diagnostics keep anchoring the
    /// `in` operand pair (same message style as Godot). A stable inner outcome always publishes
    /// `bool`: the runtime `in` result is always a `bool`, even for runtime-open operands — those
    /// only mean "not judged illegal at compile time", they do not change the result type.
    /// Publishing `DYNAMIC(Variant)` here is forbidden because the C backend only ships the
    /// `NOT + bool` specialization for unary `NOT` and has no Variant evaluator path for it.
    private static @NotNull FrontendExpressionType resolveNotInOperatorResultType(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendExpressionType leftOperandType,
            @NotNull FrontendExpressionType rightOperandType
    ) {
        var innerResult = resolveBinaryOperatorResultType(classRegistry, "in", leftOperandType, rightOperandType);
        // Inner recursion always passes literal "in", so it can never re-enter this branch; with
        // stable operands it can only produce RESOLVED / DYNAMIC / FAILED. Anything other than
        // RESOLVED / DYNAMIC (FAILED in practice) propagates unchanged to keep diagnostics anchored.
        if (innerResult.status() != FrontendExpressionTypeStatus.RESOLVED
                && innerResult.status() != FrontendExpressionTypeStatus.DYNAMIC) {
            return innerResult;
        }
        // Defensive metadata probe: the unary `NOT + bool` entry always exists in extension
        // metadata, so the published type is `bool` in practice; only a broken registry misses.
        var notReturnType = resolveUnaryExactReturnType(classRegistry, GodotOperator.NOT, GdBoolType.BOOL);
        if (notReturnType == null) {
            return FrontendExpressionType.failed(
                    "Unary operator 'not' is not defined for operand type 'bool'"
            );
        }
        return FrontendExpressionType.resolved(notReturnType);
    }

    /// Resolves `left if condition else right`.
    ///
    /// Shared semantic contract:
    /// - the condition operand is resolved first without an expected type (pure control-flow
    ///   context); a non-bool condition is accepted here and normalized during lowering
    /// - both value arms are resolved through the contextual resolver with the outer expected type
    ///   so nested container literals construct contextually (same pass-through as casts)
    /// - once all three operands are stable the two arm types are merged via
    ///   `resolveConditionalMergedType`
    /// - this helper never emits diagnostics and never writes side tables
    public @NotNull ExpressionSemanticResult resolveConditionalExpressionType(
            @NotNull ConditionalExpression conditionalExpression,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow,
            @Nullable GdType expectedType
    ) {
        Objects.requireNonNull(conditionalExpression, "conditionalExpression must not be null");
        Objects.requireNonNull(nestedResolver, "nestedResolver must not be null");

        var conditionType = nestedResolver.resolve(conditionalExpression.condition(), finalizeWindow, null);
        var conditionIssue = firstNonResolvedDependency(conditionType);
        if (conditionIssue != null) {
            return propagated(conditionIssue);
        }

        var leftArmType = nestedResolver.resolve(conditionalExpression.left(), finalizeWindow, expectedType);
        var leftIssue = firstNonResolvedDependency(leftArmType);
        if (leftIssue != null) {
            return propagated(leftIssue);
        }

        var rightArmType = nestedResolver.resolve(conditionalExpression.right(), finalizeWindow, expectedType);
        var rightIssue = firstNonResolvedDependency(rightArmType);
        if (rightIssue != null) {
            return propagated(rightIssue);
        }

        return rootOutcome(resolveConditionalMergedType(classRegistry, leftArmType, rightArmType));
    }

    /// Merges the two stable arm types of a conditional expression into the outward-facing type.
    ///
    /// The rule mirrors Godot's bidirectional `is_type_compatible` ternary reduction but reuses the
    /// GDCC ordinary typed-boundary matrix as the single source of truth:
    /// - a runtime-open arm (`DYNAMIC` or exact `Variant`) keeps the result runtime-open; this also
    ///   covers `void` paired with `Variant`, because the void arm evaluates to Variant `nil` at
    ///   runtime and the Variant merge slot holds it
    /// - a void arm paired with a non-Variant arm is an explicit unsupported boundary, because a
    ///   concrete merge slot cannot materialize the void arm's `nil`
    /// - otherwise the first boundary direction that allows conversion wins, so widening pairs such
    ///   as `int`/`float` merge to `float` regardless of arm order
    /// - mutually incompatible arms fall back to `RESOLVED(Variant)` without a diagnostic
    ///   (Godot-compatible; no `INCOMPATIBLE_TERNARY` in the MVP)
    private static @NotNull FrontendExpressionType resolveConditionalMergedType(
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendExpressionType leftArmType,
            @NotNull FrontendExpressionType rightArmType
    ) {
        Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        var publishedLeft = Objects.requireNonNull(
                leftArmType.publishedType(),
                "publishedType must not be null for a stable conditional left arm"
        );
        var publishedRight = Objects.requireNonNull(
                rightArmType.publishedType(),
                "publishedType must not be null for a stable conditional right arm"
        );

        if (isRuntimeOpenOperatorOperand(leftArmType, publishedLeft)
                || isRuntimeOpenOperatorOperand(rightArmType, publishedRight)) {
            return FrontendExpressionType.dynamic(
                    "Runtime-open ternary arm routes the conditional expression through Variant semantics"
            );
        }
        if (publishedLeft instanceof GdVoidType || publishedRight instanceof GdVoidType) {
            return FrontendExpressionType.unsupported(
                    "Conditional expression arms must produce materializable values; a void arm cannot merge"
            );
        }
        if (FrontendVariantBoundaryCompatibility
                .determineFrontendBoundaryDecision(classRegistry, publishedRight, publishedLeft)
                .allows()) {
            return FrontendExpressionType.resolved(publishedLeft);
        }
        if (FrontendVariantBoundaryCompatibility
                .determineFrontendBoundaryDecision(classRegistry, publishedLeft, publishedRight)
                .allows()) {
            return FrontendExpressionType.resolved(publishedRight);
        }
        return FrontendExpressionType.resolved(GdVariantType.VARIANT);
    }

    /// Exhaustive routing for the remaining explicitly deferred expression kinds.
    ///
    /// The analyzers intentionally keep dedicated entry points for the green paths such as
    /// identifiers, calls, subscript, assignment, lambda, unary operators, and binary operators.
    /// Everything still
    /// outside that set is enumerated here so we no longer hide unsupported/deferred domains behind
    /// a generic fallback bucket.
    public @NotNull ExpressionSemanticResult resolveRemainingExplicitExpressionType(
            @NotNull Expression expression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            boolean finalizeWindow
    ) {
        return switch (Objects.requireNonNull(expression, "expression must not be null")) {
            case ArrayExpression arrayExpression -> resolveArrayExpressionType(
                    arrayExpression,
                    nestedResolver,
                    finalizeWindow
            );
            case DictionaryExpression dictionaryExpression -> resolveDictionaryExpressionType(
                    dictionaryExpression,
                    nestedResolver,
                    finalizeWindow
            );
            case PreloadExpression preloadExpression -> resolvePreloadExpressionType(
                    preloadExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    finalizeWindow
            );
            case CastExpression castExpression -> resolveCastExpressionType(
                    castExpression,
                    nestedResolver,
                    finalizeWindow
            );
            case TypeTestExpression typeTestExpression -> resolveTypeTestExpressionType(
                    typeTestExpression,
                    nestedResolver,
                    finalizeWindow
            );
            case PatternBindingExpression patternBindingExpression -> resolveExplicitDeferredExpressionType(
                    patternBindingExpression,
                    nestedResolver,
                    resolveNestedChildren,
                    "Pattern binding expression typing is deferred by the current frontend expression-typing contract",
                    finalizeWindow
            );
            case UnknownExpression unknownExpression -> rootOutcome(FrontendExpressionType.unsupported(
                    "Parser recovery expression '" + unknownExpression.nodeType()
                            + "' cannot participate in expression typing"
            ));
            case LiteralExpression _,
                 SelfExpression _,
                 IdentifierExpression _,
                 AttributeExpression _,
                 AssignmentExpression _,
                 AwaitExpression _,
                 GetNodeExpression _,
                 CallExpression _,
                 SubscriptExpression _,
                 LambdaExpression _,
                 UnaryExpression _,
                 BinaryExpression _,
                 ConditionalExpression _ -> throw new IllegalArgumentException(
                    "Expression kind '" + expression.getClass().getSimpleName()
                            + "' must use its dedicated semantic resolver"
            );
        };
    }

    /// Shared explicit-deferred path for recognized-but-not-yet-typed expressions.
    public @NotNull ExpressionSemanticResult resolveExplicitDeferredExpressionType(
            @NotNull Expression expression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            @NotNull String detailReason,
            boolean finalizeWindow
    ) {
        var dependencyIssue = resolveNestedChildren
                ? firstNestedDependencyIssue(expression, nestedResolver, finalizeWindow)
                : null;
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        return rootOutcome(FrontendExpressionType.deferred(detailReason));
    }

    /// Dedicated `preload(path)` semantics: the path must be a string literal and is passed
    /// through to `ResourceLoader.load` verbatim at the evaluation point (no compile-time
    /// relative-path normalization — an intentional difference from Godot's compile-time preload).
    /// Success publishes `RESOLVED(Resource)`; no `FrontendResolvedCall` is published because
    /// lowering rewrites the opaque item into the singleton call pair directly, keeping the
    /// resolved-call key space frozen to `CallExpression`/`AttributeCallStep`.
    private @NotNull ExpressionSemanticResult resolvePreloadExpressionType(
            @NotNull PreloadExpression preloadExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean resolveNestedChildren,
            boolean finalizeWindow
    ) {
        if (resolveNestedChildren) {
            var dependencyIssue = firstNestedDependencyIssue(preloadExpression, nestedResolver, finalizeWindow);
            if (dependencyIssue != null) {
                return propagated(dependencyIssue);
            }
        }
        if (!(preloadExpression.path() instanceof LiteralExpression pathLiteral)
                || !"string".equals(pathLiteral.kind())) {
            return rootOutcome(FrontendExpressionType.failed(
                    "preload(...) path must be a string literal; dynamic paths must use load(...) instead"
            ));
        }
        return rootOutcome(FrontendExpressionType.resolved(new GdObjectType("Resource")));
    }

    /// Dedicated await classifier (`frontend_await_implementation.md` §8). The operand is resolved
    /// through the nested resolver first and its non-stable outcome propagates unchanged.
    /// `failClosedBoundaryReason` is non-null when the await sits in a property initializer:
    /// that context stays fail-closed after the operand itself was resolved (lambda bodies accept
    /// await and route through the normal owner marking).
    ///
    /// Exact calls to a GDCC function classify as `CALL` with a provisional callee return type
    /// (`Variant` for void callees); whether the callee is a coroutine is decided by the post-suite
    /// fixed-point pass. Exact external calls cannot become GDCC coroutines, so Signal/Variant
    /// returns immediately reuse the ordinary signal/dynamic await routes.
    /// A statically Variant operand takes the dynamic route, mirroring the unary/binary rule that
    /// exact `Variant` values stay runtime-dynamic.
    public @NotNull AwaitSemanticResult resolveAwaitExpressionType(
            @NotNull AwaitExpression awaitExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            @NotNull Function<Expression, FrontendResolvedCall> operandCallLookup,
            @Nullable String failClosedBoundaryReason,
            boolean finalizeWindow
    ) {
        Objects.requireNonNull(awaitExpression, "awaitExpression must not be null");
        Objects.requireNonNull(operandCallLookup, "operandCallLookup must not be null");
        var operandType = nestedResolver.resolve(awaitExpression.value(), finalizeWindow);
        var dependencyIssue = firstNonResolvedDependency(operandType);
        if (dependencyIssue != null) {
            return new AwaitSemanticResult(dependencyIssue, false, AwaitRoute.NONE, null);
        }
        if (failClosedBoundaryReason != null) {
            return new AwaitSemanticResult(
                    FrontendExpressionType.unsupported(failClosedBoundaryReason),
                    true,
                    AwaitRoute.NONE,
                    null
            );
        }
        var publishedOperandType = Objects.requireNonNull(
                operandType.publishedType(),
                "stable await operand type must publish a type"
        );
        if (operandType.status() == FrontendExpressionTypeStatus.DYNAMIC) {
            return new AwaitSemanticResult(
                    FrontendExpressionType.resolved(GdVariantType.VARIANT),
                    true,
                    AwaitRoute.DYNAMIC,
                    null
            );
        }
        // A GDCC exact call keeps its own route while its callee's coroutine status is pending.
        // External call sites cannot become GDCC coroutines, so their awaitable return type is
        // already sufficient to select Signal/Variant runtime dispatch.
        var operandCall = operandCallLookup.apply(awaitExpression.value());
        if (operandCall != null && operandCall.status() == FrontendCallResolutionStatus.RESOLVED) {
            var returnType = operandCall.returnType();
            var resultType = returnType == null || returnType instanceof GdVoidType
                    ? GdVariantType.VARIANT
                    : returnType;
            if (!(operandCall.declarationSite() instanceof LirFunctionDef)) {
                if (returnType instanceof GdSignalType signalType) {
                    return new AwaitSemanticResult(
                            FrontendExpressionType.resolved(signalAwaitResultType(signalType)),
                            true,
                            AwaitRoute.SIGNAL,
                            null
                    );
                }
                if (returnType instanceof GdVariantType) {
                    return new AwaitSemanticResult(
                            FrontendExpressionType.resolved(GdVariantType.VARIANT),
                            true,
                            AwaitRoute.DYNAMIC,
                            null
                    );
                }
            }
            return new AwaitSemanticResult(
                    FrontendExpressionType.resolved(resultType),
                    true,
                    AwaitRoute.CALL,
                    operandCall
            );
        }
        if (publishedOperandType instanceof GdSignalType signalType) {
            return new AwaitSemanticResult(
                    FrontendExpressionType.resolved(signalAwaitResultType(signalType)),
                    true,
                    AwaitRoute.SIGNAL,
                    null
            );
        }
        if (publishedOperandType instanceof GdVariantType) {
            return new AwaitSemanticResult(
                    FrontendExpressionType.resolved(GdVariantType.VARIANT),
                    true,
                    AwaitRoute.DYNAMIC,
                    null
            );
        }
        return new AwaitSemanticResult(
                FrontendExpressionType.unsupported(
                        "Await operand of type '" + publishedOperandType.getTypeName()
                                + "' is not a Signal or a call to a coroutine function"
                ),
                true,
                AwaitRoute.NONE,
                null
        );
    }

    /// Resume-value rule for signal awaits: no parameters resume with nil (`Variant`), a single
    /// parameter resumes with its declared type, and multiple parameters resume as `Array[Variant]`.
    public static @NotNull GdType signalAwaitResultType(@NotNull GdSignalType signalType) {
        var parameterTypes = signalType.parameterTypes();
        return switch (parameterTypes.size()) {
            case 0 -> GdVariantType.VARIANT;
            case 1 -> parameterTypes.getFirst();
            default -> new GdArrayType(GdVariantType.VARIANT);
        };
    }

    /// Resolves an array literal (generic or contextual) through `FrontendContainerLiteralSemanticSupport`.
    public @NotNull ExpressionSemanticResult resolveArrayExpressionType(
            @NotNull ArrayExpression arrayExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        return resolveArrayExpressionType(arrayExpression, nestedResolver, finalizeWindow, null);
    }

    public @NotNull ExpressionSemanticResult resolveArrayExpressionType(
            @NotNull ArrayExpression arrayExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow,
            @Nullable GdType expectedType
    ) {
        return resolveArrayExpressionType(
                arrayExpression,
                (expression, finalize, _) -> nestedResolver.resolve(expression, finalize),
                finalizeWindow,
                expectedType
        );
    }

    public @NotNull ExpressionSemanticResult resolveArrayExpressionType(
            @NotNull ArrayExpression arrayExpression,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow,
            @Nullable GdType expectedType
    ) {
        var resolution = FrontendContainerLiteralSemanticSupport.resolveArrayExpressionType(
                classRegistry,
                arrayExpression,
                nestedResolver,
                finalizeWindow,
                expectedType
        );
        return toContainerLiteralResult(resolution);
    }

    /// Resolves a dictionary literal (generic or contextual) through `FrontendContainerLiteralSemanticSupport`.
    public @NotNull ExpressionSemanticResult resolveDictionaryExpressionType(
            @NotNull DictionaryExpression dictionaryExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        return resolveDictionaryExpressionType(dictionaryExpression, nestedResolver, finalizeWindow, null);
    }

    public @NotNull ExpressionSemanticResult resolveDictionaryExpressionType(
            @NotNull DictionaryExpression dictionaryExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow,
            @Nullable GdType expectedType
    ) {
        return resolveDictionaryExpressionType(
                dictionaryExpression,
                (expression, finalize, _) -> nestedResolver.resolve(expression, finalize),
                finalizeWindow,
                expectedType
        );
    }

    public @NotNull ExpressionSemanticResult resolveDictionaryExpressionType(
            @NotNull DictionaryExpression dictionaryExpression,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow,
            @Nullable GdType expectedType
    ) {
        var resolution = FrontendContainerLiteralSemanticSupport.resolveDictionaryExpressionType(
                classRegistry,
                dictionaryExpression,
                nestedResolver,
                finalizeWindow,
                expectedType
        );
        return toContainerLiteralResult(resolution);
    }

    private static @NotNull ExpressionSemanticResult toContainerLiteralResult(
            @NotNull FrontendContainerLiteralSemanticSupport.Resolution resolution
    ) {
        return new ExpressionSemanticResult(
                resolution.expressionType(),
                resolution.rootOwnsOutcome(),
                null,
                null,
                resolution.planOrNull()
        );
    }

    /// Resolves `value as T` (CastExpression).
    ///
    /// Shared semantic contract:
    /// - result type is hard target type when value is typing-stable and target text resolves
    /// - no side-table: result type itself carries the target (`RESOLVED(targetType)`)
    /// - unknown bare identifiers, `null`, `void`, empty, and malformed structured targets are
    ///   root-owned `FAILED` (stricter than type-test's unresolved-object degrade)
    /// - static hard-pair validity is not decided here; type-check owns `sema.type_check` via
    ///   `ExplicitCastSupport`
    /// - this helper never emits diagnostics and never writes side tables
    public @NotNull ExpressionSemanticResult resolveCastExpressionType(
            @NotNull CastExpression castExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        return resolveCastExpressionType(
                castExpression,
                (expression, finalize, _) -> nestedResolver.resolve(expression, finalize),
                finalizeWindow
        );
    }

    /// Resolves `value as T`. Target is resolved first so container-literal operands receive
    /// the cast target as expected type.
    public @NotNull ExpressionSemanticResult resolveCastExpressionType(
            @NotNull CastExpression castExpression,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        Objects.requireNonNull(castExpression, "castExpression must not be null");
        Objects.requireNonNull(nestedResolver, "nestedResolver must not be null");

        var typeText = castExpression.targetType().sourceText().trim();
        switch (typeText) {
            case "" -> {
                return rootOutcome(FrontendExpressionType.failed("Cast target type is empty"));
            }
            // `null` / `void` are not valid source-facing cast targets.
            case "null" -> {
                return rootOutcome(FrontendExpressionType.failed(
                        "Cast target type 'null' is not a valid type name"
                ));
            }
            case "void" -> {
                return rootOutcome(FrontendExpressionType.failed(
                        "Cast target type 'void' is not a valid type name"
                ));
            }
        }

        var scope = scopesByAst.get(castExpression);
        if (scope == null) {
            scope = scopesByAst.get(castExpression.value());
        }
        if (scope == null) {
            scope = classRegistry;
        }

        // Same declared-type path as type annotations / type-test known targets: lexical first,
        // then top-level canonical remap-on-miss. Cast miss policy is strict: any miss is FAILED.
        var resolvedType = FrontendModuleSkeleton.tryResolveSourceFacingDeclaredType(
                scope,
                typeText,
                currentTopLevelCanonicalNameMap()
        );
        if (resolvedType == null) {
            if (ScopeTypeTextSupport.looksStructuredTypeText(typeText)) {
                return rootOutcome(FrontendExpressionType.failed(
                        "Cast target type '" + typeText + "' is not a supported declared type"
                ));
            }
            return rootOutcome(FrontendExpressionType.failed(
                    "Cast target type '" + typeText + "' cannot be resolved in the current scope"
            ));
        }

        // Value operand is typed after the target so nested container literals can construct contextually.
        var valueType = nestedResolver.resolve(castExpression.value(), finalizeWindow, resolvedType);
        var dependencyIssue = firstNonResolvedDependency(valueType);
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }
        return rootOutcome(FrontendExpressionType.resolved(resolvedType));
    }

    /// Resolves `value is T` / `value is not T`.
    ///
    /// Shared semantic contract:
    /// - result is always `bool` when the value operand is typing-stable and the RHS is acceptable
    /// - target type is returned via `publishedTypeTestTargetOrNull` for side-table publication
    /// - legal bare object class names that miss `ScopeTypeResolver` degrade to
    ///   `TargetUnresolvedObject` (lint warning is owned by the publisher, not this helper)
    /// - builtin / structured container targets that fail resolution stay `FAILED`
    /// - this helper never emits diagnostics and never writes side tables
    public @NotNull ExpressionSemanticResult resolveTypeTestExpressionType(
            @NotNull TypeTestExpression typeTestExpression,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        Objects.requireNonNull(typeTestExpression, "typeTestExpression must not be null");
        Objects.requireNonNull(nestedResolver, "nestedResolver must not be null");

        // TypeRef is not an Expression; only the value operand participates in nested typing.
        var valueType = nestedResolver.resolve(typeTestExpression.value(), finalizeWindow);
        var dependencyIssue = firstNonResolvedDependency(valueType);
        if (dependencyIssue != null) {
            return propagated(dependencyIssue);
        }

        var typeText = typeTestExpression.targetType().sourceText().trim();
        if (typeText.isEmpty()) {
            return rootOutcome(FrontendExpressionType.failed("Type-test target type is empty"));
        }
        // `null` is a value, not a type-test target in Godot / gdcc.
        if (typeText.equals("null")) {
            return rootOutcome(FrontendExpressionType.failed(
                    "Type-test target type 'null' is not a valid type name"
            ));
        }

        var scope = scopesByAst.get(typeTestExpression);
        if (scope == null) {
            // Synthetic unit-test nodes and some recovery paths may lack a root scope fact; fall back
            // to the value operand scope, then the class registry root.
            scope = scopesByAst.get(typeTestExpression.value());
        }
        if (scope == null) {
            scope = classRegistry;
        }

        // Same declared-type path as variable annotations: lexical lookup first, then top-level
        // canonical remap-on-miss. No UnresolvedTypeMapper inventing GdObjectType here.
        var resolvedType = FrontendModuleSkeleton.tryResolveSourceFacingDeclaredType(
                scope,
                typeText,
                currentTopLevelCanonicalNameMap()
        );
        if (resolvedType != null) {
            return rootOutcome(
                    FrontendExpressionType.resolved(GdBoolType.BOOL),
                    null,
                    new FrontendTypeTestTarget.TargetKnown(resolvedType)
            );
        }

        // Nested structured containers and malformed Array/Dictionary texts are rejected by the
        // strict declared-type resolver and must not degrade to unresolved object.
        if (ScopeTypeTextSupport.looksStructuredTypeText(typeText)) {
            return rootOutcome(FrontendExpressionType.failed(
                    "Type-test target type '" + typeText + "' is not a supported declared type"
            ));
        }

        // Bare legal identifier miss → Object-family runtime degrade (UNRESOLVED_OBJECT).
        // Known builtins / bare Array·Dictionary always resolve when present in the registry, so a
        // miss here cannot be a compile-time-known non-object type family.
        if (ClassRegistry.isLegalGodotIdentifier(typeText)) {
            return rootOutcome(
                    FrontendExpressionType.resolved(GdBoolType.BOOL),
                    null,
                    new FrontendTypeTestTarget.TargetUnresolvedObject(typeText)
            );
        }

        return rootOutcome(FrontendExpressionType.failed(
                "Type-test target type '" + typeText + "' is not a valid type name"
        ));
    }

    private @NotNull FrontendExpressionType resolveValueIdentifierExpressionType(
            @NotNull IdentifierExpression identifierExpression,
            @NotNull FrontendBinding binding
    ) {
        var currentScope = scopesByAst.get(identifierExpression);
        if (currentScope == null) {
            return FrontendExpressionType.unsupported(
                    "Identifier '" + identifierExpression.name() + "' is inside a skipped subtree"
            );
        }
        var resolvedValue = binding.resolvedValue();
        if (resolvedValue == null) {
            return FrontendExpressionType.failed(
                    "Published value binding '" + identifierExpression.name()
                            + "' is missing its top-binding resolved value payload"
            );
        }
        return switch (Objects.requireNonNull(
                binding.valueAccessStatus(),
                "valueAccessStatus must not be null when resolvedValue is present"
        )) {
            case FOUND_ALLOWED -> FrontendExpressionType.resolved(resolvedValue.type());
            case FOUND_BLOCKED -> FrontendExpressionType.blocked(
                    resolvedValue.type(),
                    "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
            );
            case NOT_FOUND -> FrontendExpressionType.failed(
                    "Published value binding '" + identifierExpression.name()
                            + "' carries an invalid not-found resolved value status"
            );
        };
    }

    /// Value-position references to utility identifiers. Synthetic GDScript language functions
    /// (`len`/`char`/`ord`, ...) are rejected here as the first line of defense: they carry no
    /// engine utility hash, so no standalone callable can be built for `var f = len`. The backend
    /// `construct_standalone_callable` lookup (`findUtilityFunction`, which excludes synthetic
    /// entries) stays fail-fast as the second line of defense.
    ///
    /// Note this only sees value positions: bare-call callees are resolved through
    /// `resolveBareIdentifierCallWithLiteralContext`, so direct calls remain unaffected.
    private @NotNull FrontendExpressionType resolveUtilityIdentifierExpressionType(
            @NotNull IdentifierExpression identifierExpression
    ) {
        if (classRegistry.isGdScriptLanguageFunction(identifierExpression.name())) {
            return FrontendExpressionType.failed(
                    "GDScript language function '" + identifierExpression.name()
                            + "' cannot be referenced as a first-class value; call it directly instead"
            );
        }
        return resolveCallableIdentifierExpressionType(identifierExpression);
    }

    private @NotNull FrontendExpressionType resolveCallableIdentifierExpressionType(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var currentScope = scopesByAst.get(identifierExpression);
        if (currentScope == null) {
            return FrontendExpressionType.unsupported(
                    "Callable expression '" + identifierExpression.name() + "' is inside a skipped subtree"
            );
        }
        var functionResult = currentScope.resolveFunctions(identifierExpression.name(), currentRestriction());
        var callableType = new GdCallableType();
        if (functionResult.isAllowed()) {
            return FrontendExpressionType.resolved(callableType);
        }
        if (functionResult.isBlocked()) {
            return FrontendExpressionType.blocked(
                    callableType,
                    "Binding '" + identifierExpression.name() + "' is not accessible in the current context"
            );
        }
        return FrontendExpressionType.failed(
                "Published callable binding '" + identifierExpression.name() + "' is no longer visible"
        );
    }

    private @NotNull CallArgumentResolution resolveCallArgumentTypes(
            @NotNull List<? extends Expression> arguments,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        return resolveCallArgumentTypes(
                arguments,
                (expression, finalize, _) -> nestedResolver.resolve(expression, finalize),
                finalizeWindow
        );
    }

    private @NotNull CallArgumentResolution resolveCallArgumentTypes(
            @NotNull List<? extends Expression> arguments,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        return resolveCallArgumentTypes(arguments, nestedResolver, finalizeWindow, null);
    }

    /// When `expectedParameterTypes` is non-null, container-literal arguments finalize with the
    /// matching fixed parameter type; other arguments still resolve without expected type.
    private @NotNull CallArgumentResolution resolveCallArgumentTypes(
            @NotNull List<? extends Expression> arguments,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow,
            @Nullable List<GdType> expectedParameterTypes
    ) {
        var argumentTypes = new ArrayList<GdType>(arguments.size());
        for (var i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            var expected = expectedParameterTypes != null && i < expectedParameterTypes.size()
                    ? expectedParameterTypes.get(i)
                    : null;
            // Variant parameters do not provide typed-container construction context.
            if (expected instanceof GdVariantType) {
                expected = null;
            }
            var argumentType = nestedResolver.resolve(argument, finalizeWindow, expected);
            switch (argumentType.status()) {
                case RESOLVED, DYNAMIC -> argumentTypes.add(
                        Objects.requireNonNull(argumentType.publishedType(), "publishedType must not be null")
                );
                case BLOCKED, DEFERRED, FAILED, UNSUPPORTED -> {
                    return new CallArgumentResolution(List.of(), argumentType);
                }
            }
        }
        return new CallArgumentResolution(List.copyOf(argumentTypes), null);
    }

    private @NotNull ExpressionSemanticResult resolveBareIdentifierCallWithLiteralContext(
            @NotNull IdentifierExpression bareCallee,
            @NotNull List<? extends Expression> arguments,
            @NotNull ContextualNestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        // Preliminary generic snapshot for non-literal args and for candidate ranking sources.
        // Preview uses finalizeWindow=false so owner-local caches stay non-final; any unstable
        // argument must still be finalized below so root publication can export expression facts.
        var preliminary = resolveCallArgumentTypes(arguments, nestedResolver, false, null);
        if (preliminary.issue() != null) {
            if (finalizeWindow) {
                resolveCallArgumentTypes(arguments, nestedResolver, true, null);
            }
            return propagated(preliminary.issue());
        }
        var currentScope = scopesByAst.get(bareCallee);
        if (currentScope == null) {
            return rootOutcome(FrontendExpressionType.unsupported(
                    "Bare call '" + bareCallee.name() + "(...)' is inside a skipped subtree"
            ));
        }
        var bareCallRoute = bareCallRoute(bareCallee);
        var functionResult = currentScope.resolveFunctions(bareCallee.name(), currentRestriction());
        if (functionResult.isAllowed()) {
            var overloadSelection = selectCallableOverload(
                    functionResult.requireValue(),
                    arguments,
                    preliminary.argumentTypes(),
                    nestedResolver
            );
            if (overloadSelection.selected() != null) {
                var selected = overloadSelection.selected();
                // Synthetic `range` is vararg with zero fixed parameters (matching Godot's
                // MethodInfo), so generic vararg matching would accept any argument count.
                // Gate the Godot 1..3 arity here instead of leaking `range()` to runtime.
                if (isSyntheticRangeCall(bareCallee) && (arguments.isEmpty() || arguments.size() > 3)) {
                    var arityReason = "GDScript language function 'range' expects 1 to 3 argument(s), got "
                            + arguments.size();
                    // Finalize arguments with generic expected so their expression facts publish.
                    var finalized = resolveCallArgumentTypes(arguments, nestedResolver, finalizeWindow, null);
                    if (finalized.issue() != null) {
                        return propagated(finalized.issue());
                    }
                    return rootOutcome(
                            FrontendExpressionType.failed(arityReason),
                            failedBareCall(bareCallee, bareCallRoute, finalized.argumentTypes(), arityReason)
                    );
                }
                var selectedParameterTypes = fixedParameterTypes(selected);
                var finalized = resolveCallArgumentTypes(
                        arguments,
                        nestedResolver,
                        finalizeWindow,
                        selectedParameterTypes
                );
                if (finalized.issue() != null) {
                    return propagated(finalized.issue());
                }
                return rootOutcome(
                        FrontendExpressionType.resolved(selected.getReturnType()),
                        resolvedBareCall(bareCallee, bareCallRoute, selected, finalized.argumentTypes())
                );
            }
            var detailReason = Objects.requireNonNull(overloadSelection.detailReason(), "detailReason must not be null");
            // Finalize with generic expected so argument expression facts still publish.
            var finalized = resolveCallArgumentTypes(arguments, nestedResolver, finalizeWindow, null);
            if (finalized.issue() != null) {
                return propagated(finalized.issue());
            }
            return rootOutcome(
                    FrontendExpressionType.failed(detailReason),
                    failedBareCall(bareCallee, bareCallRoute, finalized.argumentTypes(), detailReason)
            );
        }
        if (functionResult.isBlocked()) {
            var overloadSelection = selectCallableOverload(
                    functionResult.requireValue(),
                    arguments,
                    preliminary.argumentTypes(),
                    nestedResolver
            );
            var selected = overloadSelection.selected();
            var blockedReturnType = selected != null ? selected.getReturnType() : null;
            var detailReason = "Binding '" + bareCallee.name() + "' is not accessible in the current context";
            final List<GdType> publishedArgTypes;
            if (selected != null) {
                var finalized = resolveCallArgumentTypes(
                        arguments,
                        nestedResolver,
                        finalizeWindow,
                        fixedParameterTypes(selected)
                );
                if (finalized.issue() != null) {
                    return propagated(finalized.issue());
                }
                publishedArgTypes = finalized.argumentTypes();
            } else {
                var finalized = resolveCallArgumentTypes(arguments, nestedResolver, finalizeWindow, null);
                if (finalized.issue() != null) {
                    return propagated(finalized.issue());
                }
                publishedArgTypes = finalized.argumentTypes();
            }
            return rootOutcome(
                    FrontendExpressionType.blocked(blockedReturnType, detailReason),
                    selected != null
                            ? blockedBareCall(bareCallee, bareCallRoute, selected, publishedArgTypes, detailReason)
                            : null
            );
        }
        var detailReason = "Published bare callee binding '" + bareCallee.name() + "' is no longer visible";
        var finalized = resolveCallArgumentTypes(arguments, nestedResolver, finalizeWindow, null);
        if (finalized.issue() != null) {
            return propagated(finalized.issue());
        }
        return rootOutcome(
                FrontendExpressionType.failed(detailReason),
                failedBareCall(bareCallee, bareCallRoute, finalized.argumentTypes(), detailReason)
        );
    }

    /// True when the bare callee resolves to the synthetic GDScript language function `range`
    /// (not a user-defined shadow): the published binding must be the utility-function kind, which
    /// user function bindings never carry.
    private boolean isSyntheticRangeCall(@NotNull IdentifierExpression bareCallee) {
        if (!"range".equals(bareCallee.name())) {
            return false;
        }
        var binding = bindingFor(bareCallee);
        return binding != null && binding.kind() == FrontendBindingKind.UTILITY_FUNCTION;
    }

    /// Generic-snapshot overload selection (no container-literal expression AST).
    @NotNull CallableOverloadSelection selectCallableOverload(
            @NotNull List<? extends FunctionDef> overloadSet,
            @NotNull List<GdType> argumentTypes
    ) {
        return selectCallableOverload(overloadSet, List.of(), argumentTypes, null);
    }

    /// When `argumentExpressions` is non-empty, container-literal arguments are ranked against
    /// each candidate parameter type (preview only) instead of using only the generic snapshot.
    @NotNull CallableOverloadSelection selectCallableOverload(
            @NotNull List<? extends FunctionDef> overloadSet,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @Nullable ContextualNestedExpressionResolver nestedResolverOrNull
    ) {
        var applicable = overloadSet.stream()
                .filter(callable -> matchesCallableArgumentsWithLiterals(
                        callable,
                        argumentExpressions,
                        preliminaryArgumentTypes,
                        nestedResolverOrNull
                ))
                .toList();
        if (applicable.size() == 1) {
            return new CallableOverloadSelection(applicable.getFirst(), null);
        }
        if (applicable.size() > 1) {
            var mostSpecific = FrontendCallableOverloadRankingSupport.selectMostSpecificApplicable(
                    applicable,
                    (candidate, baseline) -> isStrictlyMoreSpecificWithLiterals(
                            candidate,
                            baseline,
                            argumentExpressions,
                            preliminaryArgumentTypes,
                            nestedResolverOrNull
                    )
            );
            if (mostSpecific != null) {
                return new CallableOverloadSelection(mostSpecific, null);
            }
            return new CallableOverloadSelection(
                    null,
                    "Ambiguous bare call overload: " + renderCallableSignatures(applicable)
            );
        }
        var detailReason = overloadSet.isEmpty()
                ? "Bare call resolves to an empty overload set"
                : "No applicable overload for bare call: "
                  + buildCallableMismatchReason(overloadSet.getFirst(), preliminaryArgumentTypes)
                  + ". candidates: " + renderCallableSignatures(overloadSet);
        return new CallableOverloadSelection(null, detailReason);
    }

    private boolean isStrictlyMoreSpecific(
            @NotNull FunctionDef candidate,
            @NotNull FunctionDef baseline,
            @NotNull List<GdType> argumentTypes
    ) {
        var strictlyBetter = false;
        for (var index = 0; index < argumentTypes.size(); index++) {
            var preference = compareArgumentSpecificity(
                    argumentTypes.get(index),
                    parameterTypeAt(candidate, index),
                    candidate.isVararg(),
                    parameterTypeAt(baseline, index),
                    baseline.isVararg()
            );
            switch (preference) {
                case WORSE, INCOMPARABLE -> {
                    return false;
                }
                case BETTER -> strictlyBetter = true;
                case EQUAL -> {
                }
            }
        }

        var omittedByCandidate = omittedTrailingParameterCount(candidate, argumentTypes.size());
        var omittedByBaseline = omittedTrailingParameterCount(baseline, argumentTypes.size());
        if (omittedByCandidate > omittedByBaseline) {
            return false;
        }
        if (omittedByCandidate < omittedByBaseline) {
            strictlyBetter = true;
        }

        if (candidate.isVararg() && !baseline.isVararg()) {
            return false;
        }
        if (!candidate.isVararg() && baseline.isVararg()) {
            strictlyBetter = true;
        }
        return strictlyBetter;
    }

    private @NotNull MatchPreference compareArgumentSpecificity(
            @NotNull GdType sourceType,
            @Nullable GdType candidateTarget,
            boolean candidateVararg,
            @Nullable GdType baselineTarget,
            boolean baselineVararg
    ) {
        if (candidateTarget == null && baselineTarget == null) {
            return MatchPreference.EQUAL;
        }
        if (candidateTarget == null) {
            return candidateVararg ? MatchPreference.WORSE : MatchPreference.INCOMPARABLE;
        }
        if (baselineTarget == null) {
            return baselineVararg ? MatchPreference.BETTER : MatchPreference.INCOMPARABLE;
        }

        var candidateRank = FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                classRegistry,
                sourceType,
                candidateTarget
        );
        var baselineRank = FrontendVariantBoundaryCompatibility.frontendBoundarySpecificityRank(
                classRegistry,
                sourceType,
                baselineTarget
        );
        if (candidateRank > baselineRank) {
            return MatchPreference.BETTER;
        }
        if (candidateRank < baselineRank) {
            return MatchPreference.WORSE;
        }
        return MatchPreference.EQUAL;
    }

    private @NotNull BareCallRoute bareCallRoute(@NotNull IdentifierExpression bareCallee) {
        var binding = bindingFor(Objects.requireNonNull(bareCallee, "bareCallee must not be null"));
        var receiverType = currentClassReceiverType(bareCallee);
        if (binding == null) {
            return new BareCallRoute(FrontendCallResolutionKind.UNKNOWN, FrontendReceiverKind.UNKNOWN, receiverType);
        }
        return switch (binding.kind()) {
            case METHOD -> new BareCallRoute(
                    FrontendCallResolutionKind.INSTANCE_METHOD,
                    FrontendReceiverKind.INSTANCE,
                    receiverType
            );
            case STATIC_METHOD -> new BareCallRoute(
                    FrontendCallResolutionKind.STATIC_METHOD,
                    FrontendReceiverKind.TYPE_META,
                    receiverType
            );
            case UTILITY_FUNCTION -> new BareCallRoute(
                    FrontendCallResolutionKind.STATIC_METHOD,
                    FrontendReceiverKind.TYPE_META,
                    null
            );
            default ->
                    new BareCallRoute(FrontendCallResolutionKind.UNKNOWN, FrontendReceiverKind.UNKNOWN, receiverType);
        };
    }

    private @NotNull FrontendResolvedCall resolvedBareCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull BareCallRoute bareCallRoute,
            @NotNull FunctionDef selected,
            @NotNull List<GdType> argumentTypes
    ) {
        return FrontendResolvedCall.resolved(
                bareCallee.name(),
                bareCallRoute.callKind(),
                bareCallRoute.receiverKind(),
                ownerKind(selected),
                bareCallRoute.receiverType(),
                selected.getReturnType(),
                argumentTypes,
                selected
        );
    }

    private @NotNull FrontendResolvedCall blockedBareCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull BareCallRoute bareCallRoute,
            @NotNull FunctionDef selected,
            @NotNull List<GdType> argumentTypes,
            @NotNull String detailReason
    ) {
        return FrontendResolvedCall.blocked(
                bareCallee.name(),
                bareCallRoute.callKind(),
                bareCallRoute.receiverKind(),
                ownerKind(selected),
                bareCallRoute.receiverType(),
                selected.getReturnType(),
                argumentTypes,
                selected,
                detailReason
        );
    }

    private @NotNull FrontendResolvedCall failedBareCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull BareCallRoute bareCallRoute,
            @NotNull List<GdType> argumentTypes,
            @NotNull String detailReason
    ) {
        return FrontendResolvedCall.failed(
                bareCallee.name(),
                bareCallRoute.callKind(),
                bareCallRoute.receiverKind(),
                null,
                bareCallRoute.receiverType(),
                argumentTypes,
                null,
                detailReason
        );
    }

    private @NotNull FrontendResolvedCall resolvedBareConstructorCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull List<GdType> argumentTypes,
            @NotNull FrontendConstructorResolutionSupport.Resolution resolution,
            @Nullable FrontendResolvedCall.ExactCallableBoundary exactCallableBoundary
    ) {
        return FrontendResolvedCall.resolved(
                bareCallee.name(),
                FrontendCallResolutionKind.CONSTRUCTOR,
                FrontendReceiverKind.TYPE_META,
                resolution.ownerKind(),
                receiverTypeMeta.instanceType(),
                receiverTypeMeta.instanceType(),
                argumentTypes,
                resolution.declarationSite(),
                exactCallableBoundary
        );
    }

    private @NotNull FrontendResolvedCall failedBareConstructorCall(
            @NotNull IdentifierExpression bareCallee,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull List<GdType> argumentTypes,
            @NotNull FrontendConstructorResolutionSupport.Resolution resolution
    ) {
        return FrontendResolvedCall.failed(
                bareCallee.name(),
                FrontendCallResolutionKind.CONSTRUCTOR,
                FrontendReceiverKind.TYPE_META,
                resolution.ownerKind(),
                receiverTypeMeta.instanceType(),
                argumentTypes,
                resolution.declarationSite(),
                Objects.requireNonNull(resolution.detailReason(), "detailReason must not be null")
        );
    }

    private @Nullable ScopeOwnerKind ownerKind(@Nullable FunctionDef functionDef) {
        if (functionDef == null) {
            return null;
        }
        return switch (functionDef) {
            case LirFunctionDef _ -> ScopeOwnerKind.GDCC;
            case ExtensionBuiltinClass.ClassMethod _, ExtensionBuiltinClass.ConstructorInfo _ -> ScopeOwnerKind.BUILTIN;
            case ExtensionGdClass.ClassMethod _, ExtensionUtilityFunction _ -> ScopeOwnerKind.ENGINE;
            default -> null;
        };
    }

    private @Nullable GdType currentClassReceiverType(@NotNull IdentifierExpression anchor) {
        var currentScope = scopesByAst.get(Objects.requireNonNull(anchor, "anchor must not be null"));
        if (currentScope == null) {
            return null;
        }
        var owningClass = currentScope.owningClassOrNull();
        return owningClass == null ? null : new GdObjectType(owningClass.getName());
    }

    /// Fixed-argument call compatibility is another direct consumer of the typed-boundary matrix in
    /// `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`.
    /// Call resolution must reuse the shared frontend boundary helper instead of carrying a
    /// call-specific handwritten conversion table. The corresponding ordinary `(un)pack`
    /// materialization contract is documented in
    /// `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`.
    private boolean matchesCallableArgumentsWithLiterals(
            @NotNull FunctionDef callable,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @Nullable ContextualNestedExpressionResolver nestedResolverOrNull
    ) {
        if (argumentExpressions.isEmpty() || nestedResolverOrNull == null) {
            return matchesCallableArguments(callable, preliminaryArgumentTypes);
        }
        var childSourceResolver = FrontendCallableLiteralArgumentSupport.fromContextualResolver(nestedResolverOrNull);
        var parameters = List.copyOf(callable.getParameters());
        var fixedCount = parameters.size();
        var providedCount = preliminaryArgumentTypes.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(parameters, providedCount)) {
            return false;
        }
        if (!callable.isVararg() && providedCount > fixedCount) {
            return false;
        }
        // Fixed prefix only: vararg tails are Variant-packed and never supply typed-container context (§4.2).
        var fixedPrefixCount = Math.min(providedCount, fixedCount);
        for (var index = 0; index < fixedPrefixCount; index++) {
            var parameterType = parameters.get(index).getType();
            var argument = index < argumentExpressions.size() ? argumentExpressions.get(index) : null;
            if (argument instanceof ArrayExpression || argument instanceof DictionaryExpression) {
                var compatible = FrontendCallableLiteralArgumentSupport.literalArgumentCompatibleOrNull(
                        classRegistry,
                        argument,
                        childSourceResolver,
                        parameterType
                );
                if (compatible == null || !compatible) {
                    return false;
                }
                continue;
            }
            if (!FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(
                    classRegistry,
                    preliminaryArgumentTypes.get(index),
                    parameterType
            )) {
                return false;
            }
        }
        return true;
    }

    private boolean isStrictlyMoreSpecificWithLiterals(
            @NotNull FunctionDef candidate,
            @NotNull FunctionDef baseline,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes,
            @Nullable ContextualNestedExpressionResolver nestedResolverOrNull
    ) {
        if (argumentExpressions.isEmpty() || nestedResolverOrNull == null) {
            return isStrictlyMoreSpecific(candidate, baseline, preliminaryArgumentTypes);
        }
        var childSourceResolver = FrontendCallableLiteralArgumentSupport.fromContextualResolver(nestedResolverOrNull);
        return FrontendCallableLiteralArgumentSupport.isStrictlyMoreSpecificByLiteralAggregate(
                classRegistry,
                fixedParameterTypes(candidate),
                fixedParameterTypes(baseline),
                argumentExpressions,
                preliminaryArgumentTypes,
                childSourceResolver,
                () -> isStrictlyMoreSpecific(candidate, baseline, preliminaryArgumentTypes)
        );
    }

    private static @NotNull List<GdType> fixedParameterTypes(@NotNull FunctionDef callable) {
        return FrontendCallableLiteralArgumentSupport.fixedParameterTypes(callable);
    }

    private boolean matchesCallableArguments(
            @NotNull FunctionDef callable,
            @NotNull List<GdType> argumentTypes
    ) {
        var parameters = List.copyOf(callable.getParameters());
        var fixedCount = parameters.size();
        var providedCount = argumentTypes.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(parameters, providedCount)) {
            return false;
        }
        if (!callable.isVararg() && providedCount > fixedCount) {
            return false;
        }
        var fixedPrefixCount = Math.min(providedCount, fixedCount);
        for (var index = 0; index < fixedPrefixCount; index++) {
            if (!FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(
                    classRegistry,
                    argumentTypes.get(index),
                    parameters.get(index).getType()
            )) {
                return false;
            }
        }
        if (!callable.isVararg()) {
            return true;
        }
        // GDScript vararg tails are packaged as Variant at the call boundary, so any already-typed
        // runtime value may flow into the tail without proving a strict `T -> Variant` conversion.
        return true;
    }

    private @NotNull String buildCallableMismatchReason(
            @NotNull FunctionDef callable,
            @NotNull List<GdType> argumentTypes
    ) {
        var parameters = List.copyOf(callable.getParameters());
        var fixedCount = parameters.size();
        var providedCount = argumentTypes.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(parameters, providedCount)) {
            var missingParameterIndex = firstMissingRequiredParameter(parameters, providedCount);
            return "missing required parameter #" + (missingParameterIndex + 1) + " ('"
                    + parameters.get(missingParameterIndex).getName() + "')";
        }
        if (!callable.isVararg() && providedCount > fixedCount) {
            return "expected " + fixedCount + " arguments, got " + providedCount;
        }
        var fixedPrefixCount = Math.min(providedCount, fixedCount);
        for (var index = 0; index < fixedPrefixCount; index++) {
            var argumentType = argumentTypes.get(index);
            var parameter = parameters.get(index);
            if (!FrontendVariantBoundaryCompatibility.isFrontendBoundaryCompatible(
                    classRegistry,
                    argumentType,
                    parameter.getType()
            )) {
                return "argument #" + (index + 1) + " of type '" + argumentType.getTypeName()
                        + "' is not assignable to parameter '" + parameter.getName()
                        + "' of type '" + parameter.getType().getTypeName() + "'";
            }
        }
        return "no compatible signature found";
    }

    private @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext currentPropertyInitializerContext() {
        return propertyInitializerContextSupplier.get();
    }

    private @Nullable FrontendBinding bindingFor(@NotNull IdentifierExpression identifierExpression) {
        return bindingLookup.apply(Objects.requireNonNull(identifierExpression, "identifierExpression must not be null"));
    }

    /// Typed containers keep richer source-level names such as `Array[int]`, but operator metadata
    /// is still owned by the raw builtin classes and uses raw operand names for matching.
    private static @Nullable GdType resolveUnaryExactReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GodotOperator operator,
            @NotNull GdType operandType
    ) {
        var builtinClass = findOperatorOwnerClass(classRegistry, operandType);
        if (builtinClass == null) {
            return null;
        }
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null || classOperator.operator() != operator) {
                continue;
            }
            if (!StringUtil.trimToEmpty(classOperator.rightType()).isEmpty()) {
                continue;
            }
            var returnType = parseOperatorReturnType(classRegistry, classOperator);
            if (returnType != null) {
                return returnType;
            }
        }
        return null;
    }

    private static @NotNull GdType requireStableOperatorOperandType(
            @NotNull String operandName,
            @NotNull FrontendExpressionType operandType
    ) {
        var actualOperandType = Objects.requireNonNull(operandType, "operandType must not be null");
        if (actualOperandType.status() != FrontendExpressionTypeStatus.RESOLVED
                && actualOperandType.status() != FrontendExpressionTypeStatus.DYNAMIC) {
            throw new IllegalStateException(
                    Objects.requireNonNull(operandName, "operandName must not be null")
                            + " must be RESOLVED or DYNAMIC before binary operator typing"
            );
        }
        return Objects.requireNonNull(
                actualOperandType.publishedType(),
                operandName + ".publishedType() must not be null for stable operator typing"
        );
    }

    private static @Nullable GdType resolveBinarySpecialReturnType(
            @NotNull GodotOperator operator,
            @NotNull GdType publishedLeftType,
            @NotNull GdType publishedRightType
    ) {
        if (operator == GodotOperator.AND || operator == GodotOperator.OR) {
            return GdBoolType.BOOL;
        }
        if ((operator == GodotOperator.EQUAL || operator == GodotOperator.NOT_EQUAL)
                && isObjectNilEqualityPair(publishedLeftType, publishedRightType)) {
            return GdBoolType.BOOL;
        }
        if ((operator == GodotOperator.EQUAL || operator == GodotOperator.NOT_EQUAL)
                && isObjectObjectEqualityPair(publishedLeftType, publishedRightType)) {
            return GdBoolType.BOOL;
        }
        if (operator == GodotOperator.ADD
                && publishedLeftType instanceof GdArrayType leftArrayType
                && publishedRightType instanceof GdArrayType rightArrayType
                && !(leftArrayType.getValueType() instanceof GdVariantType)
                && leftArrayType.getValueType().equals(rightArrayType.getValueType())) {
            return leftArrayType;
        }
        return null;
    }

    private static boolean isObjectNilEqualityPair(@NotNull GdType leftType, @NotNull GdType rightType) {
        return leftType instanceof GdNilType && rightType instanceof GdNilType
                || leftType instanceof GdObjectType && rightType instanceof GdNilType
                || leftType instanceof GdNilType && rightType instanceof GdObjectType;
    }

    /// Identity equality gate for two static object types.
    ///
    /// Godot Variant `OBJECT/OBJECT` `==` / `!=` compares identity, not assignability.
    /// Any `GdObjectType` pair is accepted; `GdVariantType` is excluded by `instanceof`.
    private static boolean isObjectObjectEqualityPair(@NotNull GdType leftType, @NotNull GdType rightType) {
        return leftType instanceof GdObjectType && rightType instanceof GdObjectType;
    }

    private static boolean isRuntimeOpenOperatorOperand(
            @NotNull FrontendExpressionType operandType,
            @NotNull GdType publishedOperandType
    ) {
        return operandType.status() == FrontendExpressionTypeStatus.DYNAMIC
                || publishedOperandType instanceof GdVariantType;
    }

    private static boolean isMixedIntFloatScalarPair(@NotNull GdType leftType, @NotNull GdType rightType) {
        return leftType instanceof GdIntType && rightType instanceof GdFloatType
                || leftType instanceof GdFloatType && rightType instanceof GdIntType;
    }

    private static @Nullable GdType resolveBinaryExactReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull GodotOperator operator,
            @NotNull GdType leftType,
            @NotNull GdType rightType
    ) {
        var builtinClass = findOperatorOwnerClass(classRegistry, leftType);
        if (builtinClass == null) {
            return null;
        }
        var normalizedRightType = StringUtil.trimToEmpty(operatorOperandTypeName(rightType));
        for (var classOperator : builtinClass.operators()) {
            if (classOperator == null || classOperator.operator() != operator) {
                continue;
            }
            var metadataRightType = StringUtil.trimToEmpty(classOperator.rightType());
            if (metadataRightType.isEmpty() || !metadataRightType.equals(normalizedRightType)) {
                continue;
            }
            var returnType = parseOperatorReturnType(classRegistry, classOperator);
            if (returnType != null) {
                return returnType;
            }
        }
        return null;
    }

    private static @Nullable ExtensionBuiltinClass findOperatorOwnerClass(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType operandType
    ) {
        return Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                .findBuiltinClass(operatorOperandTypeName(operandType));
    }

    private static @NotNull String operatorOperandTypeName(@NotNull GdType operandType) {
        if (operandType instanceof GdArrayType) {
            return "Array";
        }
        if (operandType instanceof GdDictionaryType) {
            return "Dictionary";
        }
        return operandType.getTypeName();
    }

    private static @Nullable GdType parseOperatorReturnType(
            @NotNull ClassRegistry classRegistry,
            @NotNull ExtensionBuiltinClass.ClassOperator classOperator
    ) {
        var returnTypeName = StringUtil.trimToEmpty(classOperator.returnType());
        if (returnTypeName.isEmpty()) {
            return null;
        }
        return Objects.requireNonNull(classRegistry, "classRegistry must not be null")
                .tryResolveDeclaredType(returnTypeName);
    }

    private boolean canOmitTrailingParameters(
            @NotNull List<? extends ParameterDef> parameters,
            int providedCount
    ) {
        for (var index = providedCount; index < parameters.size(); index++) {
            if (parameters.get(index).getDefaultValueFunc() == null) {
                return false;
            }
        }
        return true;
    }

    private @Nullable GdType parameterTypeAt(@NotNull FunctionDef callable, int index) {
        var parameters = callable.getParameters();
        if (index < parameters.size()) {
            return parameters.get(index).getType();
        }
        return null;
    }

    private int omittedTrailingParameterCount(@NotNull FunctionDef callable, int providedCount) {
        var fixedCount = callable.getParameters().size();
        return Math.max(0, fixedCount - Math.min(providedCount, fixedCount));
    }

    private int firstMissingRequiredParameter(
            @NotNull List<? extends ParameterDef> parameters,
            int providedCount
    ) {
        for (var index = providedCount; index < parameters.size(); index++) {
            if (parameters.get(index).getDefaultValueFunc() == null) {
                return index;
            }
        }
        return providedCount;
    }

    private @NotNull String renderCallableSignatures(@NotNull List<? extends FunctionDef> callables) {
        var signatures = new ArrayList<String>(callables.size());
        for (var callable : callables) {
            var args = new ArrayList<String>();
            for (var parameter : callable.getParameters()) {
                args.add(parameter.getType().getTypeName());
            }
            if (callable.isVararg()) {
                args.add("...");
            }
            signatures.add(callable.getName() + "(" + String.join(", ", args) + ")");
        }
        return String.join("; ", signatures);
    }

    private @Nullable FrontendExpressionType firstNestedDependencyIssue(
            @NotNull Node node,
            @NotNull NestedExpressionResolver nestedResolver,
            boolean finalizeWindow
    ) {
        for (var child : node.getChildren()) {
            if (child instanceof Expression childExpression) {
                var dependencyIssue = firstNonResolvedDependency(nestedResolver.resolve(childExpression, finalizeWindow));
                if (dependencyIssue != null) {
                    return dependencyIssue;
                }
                continue;
            }
            var nestedIssue = firstNestedDependencyIssue(child, nestedResolver, finalizeWindow);
            if (nestedIssue != null) {
                return nestedIssue;
            }
        }
        return null;
    }

    private @Nullable FrontendExpressionType firstNonResolvedDependency(
            @Nullable FrontendExpressionType... dependencies
    ) {
        for (var dependency : dependencies) {
            if (dependency == null
                    || dependency.status() == FrontendExpressionTypeStatus.RESOLVED
                    || dependency.status() == FrontendExpressionTypeStatus.DYNAMIC) {
                continue;
            }
            return dependency;
        }
        return null;
    }

    private @NotNull ResolveRestriction currentRestriction() {
        return Objects.requireNonNull(restrictionSupplier.get(), "currentRestriction must not be null");
    }

    private @NotNull Map<String, String> currentTopLevelCanonicalNameMap() {
        return Objects.requireNonNull(
                topLevelCanonicalNameMapSupplier.get(),
                "topLevelCanonicalNameMap must not be null"
        );
    }

    private static @NotNull ExpressionSemanticResult propagated(@NotNull FrontendExpressionType expressionType) {
        return new ExpressionSemanticResult(expressionType, false, null, null, null);
    }

    private static @NotNull ExpressionSemanticResult rootOutcome(@NotNull FrontendExpressionType expressionType) {
        return rootOutcome(expressionType, null, null);
    }

    private static @NotNull ExpressionSemanticResult rootOutcome(
            @NotNull FrontendExpressionType expressionType,
            @Nullable FrontendResolvedCall publishedCallOrNull
    ) {
        return rootOutcome(expressionType, publishedCallOrNull, null);
    }

    private static @NotNull ExpressionSemanticResult rootOutcome(
            @NotNull FrontendExpressionType expressionType,
            @Nullable FrontendResolvedCall publishedCallOrNull,
            @Nullable FrontendTypeTestTarget publishedTypeTestTargetOrNull
    ) {
        return new ExpressionSemanticResult(
                expressionType,
                true,
                publishedCallOrNull,
                publishedTypeTestTargetOrNull,
                null
        );
    }

    private record CallArgumentResolution(
            @NotNull List<GdType> argumentTypes,
            @Nullable FrontendExpressionType issue
    ) {
        private CallArgumentResolution {
            argumentTypes = List.copyOf(argumentTypes);
        }
    }

    record CallableOverloadSelection(
            @Nullable FunctionDef selected,
            @Nullable String detailReason
    ) {
    }

    private record BareCallRoute(
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable GdType receiverType
    ) {
        private BareCallRoute {
            Objects.requireNonNull(callKind, "callKind must not be null");
            Objects.requireNonNull(receiverKind, "receiverKind must not be null");
        }
    }
}
