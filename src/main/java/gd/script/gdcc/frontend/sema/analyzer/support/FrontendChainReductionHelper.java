package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendExpressionType;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.scope.resolver.ScopeMethodParameter;
import gd.script.gdcc.scope.resolver.ScopeMethodResolver;
import gd.script.gdcc.scope.resolver.ScopePropertyResolver;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.scope.resolver.ScopeResolvedProperty;
import gd.script.gdcc.scope.resolver.ScopeResolvedSignal;
import gd.script.gdcc.scope.resolver.ScopeSignalResolver;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.UnknownAttributeStep;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.regex.Pattern;

/// Local left-to-right chain reduction helper used before the published chain analyzer exists.
///
/// The helper is frontend-specific because it translates shared resolver output into frontend
/// statuses, trace entries, and suggested `FrontendResolvedMember` / `FrontendResolvedCall` facts.
public final class FrontendChainReductionHelper {
    private static final @NotNull Pattern INTEGER_LITERAL_PATTERN = Pattern.compile("[+-]?\\d+");
    /// Diagnostic category for the non-blocking warning emitted when a static property is read or
    /// written through an instance receiver (`obj.x` / `self.x`). Owner: chain binding.
    public static final @NotNull String STATIC_ACCESS_VIA_INSTANCE_CATEGORY = "sema.static_access_via_instance";

    private FrontendChainReductionHelper() {
    }

    public enum Status {
        RESOLVED,
        BLOCKED,
        DEFERRED,
        DYNAMIC,
        FAILED,
        UNSUPPORTED
    }

    public enum StepKind {
        PROPERTY,
        CALL,
        SUBSCRIPT,
        UNKNOWN
    }

    public enum RouteKind {
        INSTANCE_PROPERTY,
        INSTANCE_SIGNAL,
        INSTANCE_METHOD,
        SUPER_METHOD,
        STATIC_METHOD,
        STATIC_LOAD,
        CONSTRUCTOR,
        SUBSCRIPT,
        UPSTREAM_BLOCKED,
        UPSTREAM_DEFERRED,
        UPSTREAM_DYNAMIC,
        UPSTREAM_FAILED,
        UPSTREAM_UNSUPPORTED
    }

    @FunctionalInterface
    public interface ExpressionTypeResolver {
        @NotNull ExpressionTypeResult resolve(@NotNull Expression expression, boolean finalizeWindow);
    }

    @FunctionalInterface
    public interface NoteSink {
        void accept(@NotNull ReductionNote note);
    }

    /// Non-blocking warning emitted while reducing a chain. `category` routes the diagnostic to
    /// the owning category; historical notes all belonged to call resolution, which remains the
    /// default via [ReductionNote#DEFAULT_CATEGORY].
    public record ReductionNote(
            @NotNull Node anchor,
            @NotNull String category,
            @NotNull String message
    ) {
        /// Default category for notes that predate per-category routing (instance-style static
        /// method call resolution warnings).
        public static final @NotNull String DEFAULT_CATEGORY = "sema.call_resolution";

        public ReductionNote {
            Objects.requireNonNull(anchor, "anchor must not be null");
            category = StringUtil.requireNonBlank(category, "category");
            message = StringUtil.requireNonBlank(message, "message");
        }
    }

    public record ExpressionTypeResult(
            @NotNull Status status,
            @Nullable GdType type,
            @Nullable String detailReason
    ) {
        public ExpressionTypeResult {
            Objects.requireNonNull(status, "status must not be null");
            if (status == Status.RESOLVED) {
                Objects.requireNonNull(type, "type must not be null for resolved expression type result");
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved expression type result");
                }
            } else {
                if (status == Status.DYNAMIC) {
                    Objects.requireNonNull(type, "type must not be null for dynamic expression type result");
                    if (!(type instanceof GdVariantType)) {
                        throw new IllegalArgumentException("dynamic expression type result must publish Variant");
                    }
                } else if (status != Status.BLOCKED && type != null) {
                    throw new IllegalArgumentException("type must be null for non-resolved/non-blocked expression type result");
                }
                StringUtil.requireNonBlank(detailReason, "detailReason");
            }
        }

        public static @NotNull ExpressionTypeResult resolved(@NotNull GdType type) {
            return new ExpressionTypeResult(Status.RESOLVED, Objects.requireNonNull(type, "type must not be null"), null);
        }

        public static @NotNull ExpressionTypeResult deferred(@NotNull String detailReason) {
            return new ExpressionTypeResult(Status.DEFERRED, null, detailReason);
        }

        public static @NotNull ExpressionTypeResult blocked(
                @Nullable GdType type,
                @NotNull String detailReason
        ) {
            return new ExpressionTypeResult(Status.BLOCKED, type, detailReason);
        }

        public static @NotNull ExpressionTypeResult dynamic(@NotNull String detailReason) {
            return new ExpressionTypeResult(Status.DYNAMIC, GdVariantType.VARIANT, detailReason);
        }

        public static @NotNull ExpressionTypeResult failed(@NotNull String detailReason) {
            return new ExpressionTypeResult(Status.FAILED, null, detailReason);
        }

        public static @NotNull ExpressionTypeResult unsupported(@NotNull String detailReason) {
            return new ExpressionTypeResult(Status.UNSUPPORTED, null, detailReason);
        }

        public static @NotNull ExpressionTypeResult fromPublished(@NotNull FrontendExpressionType expressionType) {
            return FrontendChainStatusBridge.toExpressionTypeResult(expressionType);
        }
    }

    public record ReceiverState(
            @NotNull Status status,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable GdType receiverType,
            @Nullable ScopeTypeMeta receiverTypeMeta,
            @Nullable String detailReason
    ) {
        public ReceiverState {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(receiverKind, "receiverKind must not be null");
            if (status == Status.RESOLVED) {
                if (receiverKind != FrontendReceiverKind.INSTANCE && receiverKind != FrontendReceiverKind.TYPE_META) {
                    throw new IllegalArgumentException("resolved receiver state must be INSTANCE or TYPE_META");
                }
                Objects.requireNonNull(receiverType, "receiverType must not be null for resolved receiver state");
                if (receiverKind == FrontendReceiverKind.TYPE_META) {
                    Objects.requireNonNull(receiverTypeMeta, "receiverTypeMeta must not be null for type-meta receiver state");
                } else if (receiverTypeMeta != null) {
                    throw new IllegalArgumentException("receiverTypeMeta must be null for instance receiver state");
                }
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved receiver state");
                }
            } else {
                StringUtil.requireNonBlank(detailReason, "detailReason");
                if (status == Status.DYNAMIC && receiverKind != FrontendReceiverKind.DYNAMIC) {
                    throw new IllegalArgumentException("dynamic receiver state must use DYNAMIC receiver kind");
                }
            }
        }

        public static @NotNull ReceiverState resolvedInstance(@NotNull GdType receiverType) {
            return new ReceiverState(
                    Status.RESOLVED,
                    FrontendReceiverKind.INSTANCE,
                    Objects.requireNonNull(receiverType, "receiverType must not be null"),
                    null,
                    null
            );
        }

        public static @NotNull ReceiverState resolvedTypeMeta(@NotNull ScopeTypeMeta receiverTypeMeta) {
            var typeMeta = Objects.requireNonNull(receiverTypeMeta, "receiverTypeMeta must not be null");
            return new ReceiverState(
                    Status.RESOLVED,
                    FrontendReceiverKind.TYPE_META,
                    typeMeta.instanceType(),
                    typeMeta,
                    null
            );
        }

        public static @NotNull ReceiverState blockedFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.BLOCKED, detailReason);
        }

        public static @NotNull ReceiverState deferredFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.DEFERRED, detailReason);
        }

        public static @NotNull ReceiverState failedFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.FAILED, detailReason);
        }

        public static @NotNull ReceiverState unsupportedFrom(@NotNull ReceiverState baseState, @NotNull String detailReason) {
            return copyWithStatus(baseState, Status.UNSUPPORTED, detailReason);
        }

        public static @NotNull ReceiverState dynamic(@NotNull String detailReason) {
            return new ReceiverState(Status.DYNAMIC, FrontendReceiverKind.DYNAMIC, GdVariantType.VARIANT, null, detailReason);
        }

        private static @NotNull ReceiverState copyWithStatus(
                @NotNull ReceiverState baseState,
                @NotNull Status status,
                @NotNull String detailReason
        ) {
            var state = Objects.requireNonNull(baseState, "baseState must not be null");
            return new ReceiverState(
                    Objects.requireNonNull(status, "status must not be null"),
                    state.receiverKind(),
                    state.receiverType(),
                    state.receiverTypeMeta(),
                    detailReason
            );
        }
    }

    public record UpstreamCause(
            @NotNull OptionalInt sourceStepIndex,
            @NotNull Status status,
            @NotNull String detailReason
    ) {
        public UpstreamCause {
            Objects.requireNonNull(sourceStepIndex, "sourceStepIndex must not be null");
            Objects.requireNonNull(status, "status must not be null");
            if (status == Status.RESOLVED) {
                throw new IllegalArgumentException("upstream cause cannot be RESOLVED");
            }
            detailReason = StringUtil.requireNonBlank(detailReason, "detailReason");
        }
    }

    public record StepTrace(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull StepKind stepKind,
            @NotNull RouteKind routeKind,
            @NotNull ReceiverState incomingReceiver,
            @NotNull Status status,
            @NotNull ReceiverState outgoingReceiver,
            @Nullable UpstreamCause upstreamCause,
            @Nullable FrontendResolvedMember suggestedMember,
            @Nullable FrontendResolvedCall suggestedCall,
            boolean finalizeRetryUsed,
            @Nullable String detailReason
    ) {
        public StepTrace {
            if (stepIndex < 0) {
                throw new IllegalArgumentException("stepIndex must be >= 0");
            }
            Objects.requireNonNull(step, "step must not be null");
            Objects.requireNonNull(stepKind, "stepKind must not be null");
            Objects.requireNonNull(routeKind, "routeKind must not be null");
            Objects.requireNonNull(incomingReceiver, "incomingReceiver must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(outgoingReceiver, "outgoingReceiver must not be null");
            if (suggestedMember != null && suggestedCall != null) {
                throw new IllegalArgumentException("a step trace may suggest either a member or a call, not both");
            }
            if (status == Status.RESOLVED) {
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved step trace");
                }
                if (stepKind != StepKind.SUBSCRIPT && suggestedMember == null && suggestedCall == null) {
                    throw new IllegalArgumentException("resolved step trace must publish a member or call suggestion");
                }
            } else {
                StringUtil.requireNonBlank(detailReason, "detailReason");
            }
        }
    }

    /// @param bindingLookup Pending-aware binding view (the same one the facade's head-receiver resolution uses).
    ///                      Chain binding runs before the top-binding flush, so the stable
    ///                      `analysisData.symbolBindings()` table may not see the current suite's SUPER binding
    ///                       yet; the super-head detection must go through this view.
    public record ReductionRequest(
            @NotNull AttributeExpression chainExpression,
            @NotNull ReceiverState headReceiver,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry,
            @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext propertyInitializerContext,
            @NotNull ExpressionTypeResolver expressionTypeResolver,
            @NotNull NoteSink noteSink,
            @NotNull Function<IdentifierExpression, FrontendBinding> bindingLookup
    ) {
        public ReductionRequest(
                @NotNull AttributeExpression chainExpression,
                @NotNull ReceiverState headReceiver,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull ClassRegistry classRegistry,
                @NotNull ExpressionTypeResolver expressionTypeResolver,
                @NotNull NoteSink noteSink
        ) {
            this(
                    chainExpression,
                    headReceiver,
                    analysisData,
                    classRegistry,
                    null,
                    expressionTypeResolver,
                    noteSink
            );
        }

        public ReductionRequest(
                @NotNull AttributeExpression chainExpression,
                @NotNull ReceiverState headReceiver,
                @NotNull FrontendAnalysisData analysisData,
                @NotNull ClassRegistry classRegistry,
                @Nullable FrontendPropertyInitializerSupport.PropertyInitializerContext propertyInitializerContext,
                @NotNull ExpressionTypeResolver expressionTypeResolver,
                @NotNull NoteSink noteSink
        ) {
            this(
                    chainExpression,
                    headReceiver,
                    analysisData,
                    classRegistry,
                    propertyInitializerContext,
                    expressionTypeResolver,
                    noteSink,
                    analysisData.symbolBindings()::get
            );
        }

        public ReductionRequest {
            Objects.requireNonNull(chainExpression, "chainExpression must not be null");
            Objects.requireNonNull(headReceiver, "headReceiver must not be null");
            Objects.requireNonNull(analysisData, "analysisData must not be null");
            Objects.requireNonNull(classRegistry, "classRegistry must not be null");
            Objects.requireNonNull(expressionTypeResolver, "expressionTypeResolver must not be null");
            Objects.requireNonNull(noteSink, "noteSink must not be null");
            Objects.requireNonNull(bindingLookup, "bindingLookup must not be null");
        }
    }

    public record ReductionResult(
            @NotNull List<StepTrace> stepTraces,
            @NotNull ReceiverState finalReceiver,
            @Nullable Node recoveryRoot,
            @NotNull List<ReductionNote> notes
    ) {
        public ReductionResult {
            stepTraces = List.copyOf(stepTraces);
            Objects.requireNonNull(finalReceiver, "finalReceiver must not be null");
            notes = List.copyOf(notes);
        }
    }

    public static @NotNull ReductionResult reduce(@NotNull ReductionRequest request) {
        var input = Objects.requireNonNull(request, "request must not be null");
        var traces = new ArrayList<StepTrace>();
        var notes = new ArrayList<ReductionNote>();
        var currentReceiver = input.headReceiver();
        Node recoveryRoot = currentReceiver.status() == Status.RESOLVED ? null : input.chainExpression().base();
        UpstreamCause upstreamCause = currentReceiver.status() == Status.RESOLVED
                ? null
                : new UpstreamCause(
                OptionalInt.empty(),
                currentReceiver.status(),
                StringUtil.requireNonBlank(currentReceiver.detailReason(), "currentReceiver.detailReason")
        );

        for (var stepIndex = 0; stepIndex < input.chainExpression().steps().size(); stepIndex++) {
            var step = input.chainExpression().steps().get(stepIndex);
            StepTrace trace;
            if (currentReceiver.status() == Status.RESOLVED) {
                trace = reduceStep(stepIndex, step, currentReceiver, input, notes);
            } else if (shouldResolveFirstBlockedStepExactly(
                    stepIndex,
                    currentReceiver,
                    Objects.requireNonNull(upstreamCause)
            )) {
                trace = reduceFirstBlockedStepExactly(
                        stepIndex,
                        step,
                        currentReceiver,
                        input,
                        notes,
                        upstreamCause
                );
            } else {
                trace = propagateStep(stepIndex, step, currentReceiver, Objects.requireNonNull(upstreamCause));
            }
            traces.add(trace);
            currentReceiver = trace.outgoingReceiver();
            if (recoveryRoot == null && trace.status() != Status.RESOLVED) {
                recoveryRoot = step;
            }
            if (trace.status() != Status.RESOLVED) {
                upstreamCause = new UpstreamCause(
                        OptionalInt.of(stepIndex),
                        trace.status(),
                        Objects.requireNonNull(trace.detailReason())
                );
            }
        }

        return new ReductionResult(traces, currentReceiver, recoveryRoot, notes);
    }

    /// A blocked chain head may still carry enough concrete receiver metadata to resolve the first
    /// member/call exactly and publish it as `BLOCKED`. This is required for contracts such as
    /// static property initializers, where `self.payload` must not degrade into an empty side-table
    /// publication just because `self` itself is blocked in static context.
    private static boolean shouldResolveFirstBlockedStepExactly(
            int stepIndex,
            @NotNull ReceiverState currentReceiver,
            @NotNull UpstreamCause upstreamCause
    ) {
        return stepIndex == 0
                && currentReceiver.status() == Status.BLOCKED
                && upstreamCause.status() == Status.BLOCKED
                && upstreamCause.sourceStepIndex().isEmpty()
                && currentReceiver.receiverKind() != FrontendReceiverKind.UNKNOWN
                && currentReceiver.receiverKind() != FrontendReceiverKind.DYNAMIC
                && currentReceiver.receiverType() != null;
    }

    private static @NotNull StepTrace reduceFirstBlockedStepExactly(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull ReceiverState blockedReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<ReductionNote> notes,
            @NotNull UpstreamCause upstreamCause
    ) {
        var exactIncomingReceiver = new ReceiverState(
                Status.RESOLVED,
                blockedReceiver.receiverKind(),
                blockedReceiver.receiverType(),
                blockedReceiver.receiverTypeMeta(),
                null
        );
        var exactTrace = reduceStep(stepIndex, step, exactIncomingReceiver, request, notes);
        if (exactTrace.status() != Status.RESOLVED) {
            return propagateStep(stepIndex, step, blockedReceiver, upstreamCause);
        }

        var detailReason = renderUpstreamReason(upstreamCause);
        return new StepTrace(
                stepIndex,
                step,
                exactTrace.stepKind(),
                exactTrace.routeKind(),
                blockedReceiver,
                Status.BLOCKED,
                ReceiverState.blockedFrom(exactTrace.outgoingReceiver(), detailReason),
                upstreamCause,
                toBlockedSuggestedMember(exactTrace.suggestedMember(), detailReason),
                toBlockedSuggestedCall(exactTrace.suggestedCall(), detailReason),
                exactTrace.finalizeRetryUsed(),
                detailReason
        );
    }

    private static @NotNull StepTrace reduceStep(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<ReductionNote> notes
    ) {
        // A `super` chain head only licenses a single leading call step; the super route owns step 0
        // and any later suffix reduces against the call result like an ordinary chain.
        if (stepIndex == 0 && isSuperChainHead(request)) {
            return switch (step) {
                case AttributeCallStep callStep ->
                        reduceSuperCallStep(stepIndex, callStep, incomingReceiver, request);
                case AttributePropertyStep propertyStep -> {
                    var detailReason = "'super' only supports method calls; property access '"
                            + propertyStep.name() + "' is not allowed";
                    yield failedSuperHeadStep(
                            stepIndex,
                            propertyStep,
                            incomingReceiver,
                            FrontendResolvedMember.failed(
                                    propertyStep.name(),
                                    FrontendBindingKind.PROPERTY,
                                    FrontendReceiverKind.INSTANCE,
                                    null,
                                    incomingReceiver.receiverType(),
                                    null,
                                    detailReason
                            ),
                            detailReason
                    );
                }
                case AttributeSubscriptStep subscriptStep -> failedSuperHeadStep(
                        stepIndex,
                        subscriptStep,
                        incomingReceiver,
                        null,
                        "'super' only supports method calls; subscript access is not allowed"
                );
                case UnknownAttributeStep unknownStep -> unsupportedUnknownStep(stepIndex, unknownStep, incomingReceiver);
            };
        }
        return switch (step) {
            case AttributePropertyStep propertyStep ->
                    reducePropertyStep(stepIndex, propertyStep, incomingReceiver, request, notes);
            case AttributeCallStep callStep -> reduceCallStep(stepIndex, callStep, incomingReceiver, request, notes);
            case AttributeSubscriptStep subscriptStep ->
                    reduceSubscriptStep(stepIndex, subscriptStep, incomingReceiver, request, notes);
            case UnknownAttributeStep unknownStep -> unsupportedUnknownStep(stepIndex, unknownStep, incomingReceiver);
        };
    }

    /// The chain head is `super` exactly when top binding published the SUPER keyword binding for
    /// the base identifier; the published fact is the single source of truth, not the spelled name.
    private static boolean isSuperChainHead(@NotNull ReductionRequest request) {
        if (!(request.chainExpression().base() instanceof IdentifierExpression baseIdentifier)) {
            return false;
        }
        var binding = request.bindingLookup().apply(baseIdentifier);
        return binding != null && binding.kind() == FrontendBindingKind.SUPER;
    }

    /// `super.m(...)` resolves from the lexical superclass of the enclosing class (never the
    /// receiver's dynamic type) and publishes a `SUPER_METHOD` route so lowering emits
    /// `CALL_SUPER_METHOD` instead of the vtable-dispatched ordinary call. Unlike the ordinary
    /// instance route there is no warning-level outcome to record in `notes`: every divergent
    /// super form (static target, missing parent chain method, subscript/property steps) is a
    /// fail-closed `FAILED` fact, mirroring the backend's `invalidInsn` strictness.
    private static @NotNull StepTrace reduceSuperCallStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request
    ) {
        var argumentResolution = resolveArgumentTypes(step.arguments(), request);
        if (argumentResolution.status() != Status.RESOLVED) {
            return unresolvedCallDependencyTrace(
                    stepIndex,
                    step,
                    incomingReceiver,
                    argumentResolution,
                    RouteKind.SUPER_METHOD,
                    FrontendCallResolutionKind.SUPER_METHOD
            );
        }
        var resolution = FrontendSuperCallSupport.resolveSuperInstanceMethod(
                request.classRegistry(),
                Objects.requireNonNull(incomingReceiver.receiverType(), "receiverType must not be null"),
                step.name(),
                argumentResolution.argumentTypes(),
                literalAwareParameterRank(request, step.arguments())
        );
        var resolvedMethod = resolution.method();
        if (resolvedMethod == null) {
            var detailReason = Objects.requireNonNull(resolution.detailReason(), "detailReason must not be null");
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.CALL,
                    RouteKind.SUPER_METHOD,
                    incomingReceiver,
                    Status.FAILED,
                    ReceiverState.failedFrom(incomingReceiver, detailReason),
                    null,
                    null,
                    FrontendResolvedCall.failed(
                            step.name(),
                            FrontendCallResolutionKind.SUPER_METHOD,
                            FrontendReceiverKind.INSTANCE,
                            null,
                            incomingReceiver.receiverType(),
                            argumentResolution.argumentTypes(),
                            null,
                            detailReason
                    ),
                    argumentResolution.retryUsed(),
                    detailReason
            );
        }
        return resolvedCallTrace(
                stepIndex,
                step,
                incomingReceiver,
                RouteKind.SUPER_METHOD,
                FrontendReceiverKind.INSTANCE,
                FrontendCallResolutionKind.SUPER_METHOD,
                resolvedMethod,
                argumentResolution.argumentTypes(),
                argumentResolution.retryUsed()
        );
    }

    private static @NotNull StepTrace failedSuperHeadStep(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull ReceiverState incomingReceiver,
            @Nullable FrontendResolvedMember failedMember,
            @NotNull String detailReason
    ) {
        return new StepTrace(
                stepIndex,
                step,
                failedMember != null ? StepKind.PROPERTY : StepKind.UNKNOWN,
                RouteKind.SUPER_METHOD,
                incomingReceiver,
                Status.FAILED,
                ReceiverState.failedFrom(incomingReceiver, detailReason),
                null,
                failedMember,
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace reducePropertyStep(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<ReductionNote> notes
    ) {
        if (incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META) {
            return reduceStaticLoadStep(stepIndex, step, incomingReceiver, request);
        }

        var receiverType = Objects.requireNonNull(incomingReceiver.receiverType(), "receiverType must not be null");
        if (receiverType instanceof GdVariantType) {
            var detailReason = "Variant receiver routes property access '" + step.name() + "' through runtime-dynamic semantics";
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.PROPERTY,
                    RouteKind.INSTANCE_PROPERTY,
                    incomingReceiver,
                    Status.DYNAMIC,
                    ReceiverState.dynamic(detailReason),
                    null,
                    FrontendResolvedMember.dynamic(
                            step.name(),
                            FrontendBindingKind.UNKNOWN,
                            FrontendReceiverKind.INSTANCE,
                            null,
                            receiverType,
                            null,
                            detailReason
                    ),
                    null,
                    false,
                    detailReason
            );
        }

        if (!(receiverType instanceof GdObjectType objectType)) {
            return reduceBuiltinPropertyStep(stepIndex, step, incomingReceiver, request.classRegistry(), receiverType);
        }

        var propertyResult = ScopePropertyResolver.resolveObjectProperty(request.classRegistry(), objectType, step.name());
        if (propertyResult instanceof ScopePropertyResolver.Resolved(var property)) {
            var boundaryDetail = FrontendPropertyInitializerSupport.detailForResolvedPropertyBoundary(
                    request.propertyInitializerContext(),
                    property
            );
            if (boundaryDetail != null) {
                return unsupportedResolvedPropertyTrace(stepIndex, step, incomingReceiver, property, boundaryDetail);
            }
            if (property.property().isStatic()) {
                // Godot silently allows instance access to a static var and operates on the
                // declaring class's shared storage; gdcc keeps the behavior but additionally
                // warns, mirroring the STATIC_CALLED_ON_INSTANCE precedent for static methods.
                emitNote(
                        step,
                        STATIC_ACCESS_VIA_INSTANCE_CATEGORY,
                        "The property '" + step.name() + "' is static but was accessed from an instance. "
                                + "Instead, it should be accessed from the type: '"
                                + property.ownerClass().getName() + "." + step.name() + "'.",
                        notes,
                        request.noteSink()
                );
                return resolvedInstanceStaticPropertyTrace(stepIndex, step, incomingReceiver, property);
            }
            return resolvedPropertyTrace(stepIndex, step, incomingReceiver, property);
        }

        var signalResult = ScopeSignalResolver.resolveInstanceSignal(request.classRegistry(), receiverType, step.name());
        if (signalResult instanceof ScopeSignalResolver.Resolved(var signal)) {
            var boundaryDetail = FrontendPropertyInitializerSupport.detailForResolvedSignalBoundary(
                    request.propertyInitializerContext(),
                    signal
            );
            if (boundaryDetail != null) {
                return unsupportedResolvedSignalTrace(stepIndex, step, incomingReceiver, signal, boundaryDetail);
            }
            return resolvedSignalTrace(stepIndex, step, incomingReceiver, signal);
        }

        if (propertyResult instanceof ScopePropertyResolver.MetadataUnknown
                || signalResult instanceof ScopeSignalResolver.MetadataUnknown) {
            var detailReason = "Receiver metadata for object type '" + receiverType.getTypeName()
                    + "' is not available yet while resolving member '" + step.name() + "'";
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.PROPERTY,
                    RouteKind.INSTANCE_PROPERTY,
                    incomingReceiver,
                    Status.DYNAMIC,
                    ReceiverState.dynamic(detailReason),
                    null,
                    FrontendResolvedMember.dynamic(
                            step.name(),
                            FrontendBindingKind.UNKNOWN,
                            FrontendReceiverKind.INSTANCE,
                            null,
                            receiverType,
                            null,
                            detailReason
                    ),
                    null,
                    false,
                    detailReason
            );
        }

        var propertyFailed = assertPropertyFailure(propertyResult);
        var signalFailed = assertSignalFailure(signalResult);
        if (propertyFailed.kind() == ScopePropertyResolver.FailureKind.PROPERTY_MISSING
                && signalFailed.kind() == ScopeSignalResolver.FailureKind.SIGNAL_MISSING) {
            var methodReference = resolveInstanceMethodReference(request.classRegistry(), receiverType, step.name());
            if (methodReference != null) {
                var boundaryDetail = FrontendPropertyInitializerSupport.detailForMethodReferenceBoundary(
                        request.propertyInitializerContext(),
                        methodReference.ownerClass(),
                        methodReference.declarationSite().getFirst()
                );
                if (boundaryDetail != null) {
                    return unsupportedMethodReferenceTrace(
                            stepIndex,
                            step,
                            incomingReceiver,
                            methodReference,
                            boundaryDetail
                    );
                }
                return resolvedMethodReferenceTrace(stepIndex, step, incomingReceiver, methodReference);
            }
        }
        var detailReason = propertyFailed.kind() == ScopePropertyResolver.FailureKind.PROPERTY_MISSING
                && signalFailed.kind() == ScopeSignalResolver.FailureKind.SIGNAL_MISSING
                ? "Member '" + step.name() + "' was not found as a property or signal on type '" + receiverType.getTypeName() + "'"
                : renderMemberFailure(propertyFailed, signalFailed);
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                propertyFailed.kind() == ScopePropertyResolver.FailureKind.PROPERTY_MISSING
                        ? RouteKind.INSTANCE_SIGNAL
                        : RouteKind.INSTANCE_PROPERTY,
                incomingReceiver,
                Status.FAILED,
                ReceiverState.failedFrom(incomingReceiver, detailReason),
                null,
                FrontendResolvedMember.failed(
                        step.name(),
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        null,
                        receiverType,
                        null,
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    /// Builtin instance member access stays on the ordinary property route.
    ///
    /// The metadata normalization layer already maps Godot builtin JSON `members` into the shared
    /// property-like surface consumed by `ScopePropertyResolver`, so this helper must not add a
    /// second schema fallback or silently widen the route into builtin keyed access.
    private static @NotNull StepTrace reduceBuiltinPropertyStep(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType receiverType
    ) {
        var propertyResult = ScopePropertyResolver.resolveBuiltinProperty(classRegistry, receiverType, step.name());
        if (propertyResult instanceof ScopePropertyResolver.Resolved(var property)) {
            return resolvedPropertyTrace(stepIndex, step, incomingReceiver, property);
        }
        var failed = assertPropertyFailure(propertyResult);
        if (failed.kind() == ScopePropertyResolver.FailureKind.BUILTIN_PROPERTY_MISSING) {
            var methodReference = resolveInstanceMethodReference(classRegistry, receiverType, step.name());
            if (methodReference != null) {
                return resolvedMethodReferenceTrace(stepIndex, step, incomingReceiver, methodReference);
            }
        }
        var detailReason = "Builtin member lookup for '" + step.name() + "' failed on type '"
                + receiverType.getTypeName() + "': " + failed.kind();
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_PROPERTY,
                incomingReceiver,
                Status.FAILED,
                ReceiverState.failedFrom(incomingReceiver, detailReason),
                null,
                FrontendResolvedMember.failed(
                        step.name(),
                        FrontendBindingKind.UNKNOWN,
                        FrontendReceiverKind.INSTANCE,
                        null,
                        receiverType,
                        null,
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace reduceStaticLoadStep(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request
    ) {
        var receiverTypeMeta = Objects.requireNonNull(incomingReceiver.receiverTypeMeta(), "receiverTypeMeta must not be null");
        var propertyInitializerContext = request.propertyInitializerContext();
        if (FrontendPropertyInitializerSupport.isCurrentInstanceHierarchyTypeMeta(propertyInitializerContext, receiverTypeMeta)) {
            var currentInstanceValueKind = FrontendPropertyInitializerSupport.currentInstanceHierarchyNonStaticValueKind(
                    propertyInitializerContext,
                    step.name()
            );
            if (currentInstanceValueKind != null) {
                return unsupportedPropertyInitializerStaticLoadTrace(
                        stepIndex,
                        step,
                        incomingReceiver,
                        receiverTypeMeta,
                        currentInstanceValueKind
                );
            }
        }
        return switch (receiverTypeMeta.kind()) {
            case GLOBAL_ENUM -> reduceGlobalEnumStaticLoad(stepIndex, step, incomingReceiver, receiverTypeMeta);
            case BUILTIN ->
                    reduceBuiltinStaticLoad(stepIndex, step, incomingReceiver, request.classRegistry(), receiverTypeMeta);
            case ENGINE_CLASS ->
                    reduceEngineStaticLoad(stepIndex, step, incomingReceiver, request.classRegistry(), receiverTypeMeta);
            case GDCC_CLASS ->
                    reduceGdccStaticLoad(stepIndex, step, incomingReceiver, request.classRegistry(), receiverTypeMeta);
        };
    }

    private static @NotNull StepTrace reduceGlobalEnumStaticLoad(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeTypeMeta receiverTypeMeta
    ) {
        if (!(receiverTypeMeta.declaration() instanceof ExtensionGlobalEnum globalEnum)) {
            var detailReason = "Global enum static load receiver '" + receiverTypeMeta.displayName()
                    + "' has malformed declaration metadata";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, null, receiverTypeMeta, detailReason);
        }
        var enumValue = globalEnum.values().stream()
                .filter(value -> step.name().equals(value.name()))
                .findFirst()
                .orElse(null);
        if (enumValue == null) {
            var detailReason = "Enum value '" + step.name() + "' not found in global enum '" + globalEnum.name() + "'";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, null, receiverTypeMeta, detailReason);
        }
        return resolvedStaticLoadTrace(
                stepIndex,
                step,
                incomingReceiver,
                FrontendBindingKind.CONSTANT,
                null,
                GdIntType.INT,
                enumValue
        );
    }

    private static @NotNull StepTrace reduceBuiltinStaticLoad(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta receiverTypeMeta
    ) {
        var builtinClass = resolveBuiltinStaticOwner(classRegistry, receiverTypeMeta);
        if (builtinClass == null) {
            var detailReason = "Builtin static receiver '" + receiverTypeMeta.displayName()
                    + "' is not backed by builtin class metadata";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, ScopeOwnerKind.BUILTIN, receiverTypeMeta, detailReason);
        }
        var constantLookup = classRegistry.findBuiltinClassConstantInHierarchy(builtinClass.name(), step.name());
        if (constantLookup == null) {
            var methodReference = resolveStaticMethodReference(classRegistry, receiverTypeMeta, step.name());
            if (methodReference != null) {
                return resolvedMethodReferenceTrace(stepIndex, step, incomingReceiver, methodReference);
            }
            var enumValueLookup = classRegistry.findBuiltinClassEnumValueInHierarchy(builtinClass.name(), step.name());
            if (enumValueLookup != null) {
                return resolvedStaticLoadTrace(
                        stepIndex,
                        step,
                        incomingReceiver,
                        FrontendBindingKind.CONSTANT,
                        ScopeOwnerKind.BUILTIN,
                        GdIntType.INT,
                        enumValueLookup.enumValue()
                );
            }
            var detailReason = "Builtin constant or enum value '" + step.name()
                    + "' not found in class '" + builtinClass.name() + "'";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, ScopeOwnerKind.BUILTIN, receiverTypeMeta, detailReason);
        }
        var constant = constantLookup.constant();
        if (constant.type() == null || constant.type().isBlank()) {
            var detailReason = "Builtin constant '" + step.name() + "' in class '" + constantLookup.ownerClass().name()
                    + "' has no declared type";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, ScopeOwnerKind.BUILTIN, constant, detailReason);
        }
        var constantType = classRegistry.tryResolveDeclaredType(constant.type());
        if (constantType == null) {
            var detailReason = "Builtin constant '" + step.name() + "' in class '" + constantLookup.ownerClass().name()
                    + "' has unsupported declared type '" + constant.type() + "'";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, ScopeOwnerKind.BUILTIN, constant, detailReason);
        }
        return resolvedStaticLoadTrace(
                stepIndex,
                step,
                incomingReceiver,
                FrontendBindingKind.CONSTANT,
                ScopeOwnerKind.BUILTIN,
                constantType,
                constant
        );
    }

    private static @NotNull StepTrace reduceEngineStaticLoad(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta receiverTypeMeta
    ) {
        var engineClass = resolveEngineStaticOwner(classRegistry, receiverTypeMeta);
        if (engineClass == null) {
            var detailReason = "Engine static receiver '" + receiverTypeMeta.displayName()
                    + "' is not backed by engine class metadata";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, ScopeOwnerKind.ENGINE, receiverTypeMeta, detailReason);
        }
        var constantLookup = classRegistry.findEngineClassConstantInHierarchy(engineClass.getName(), step.name());
        if (constantLookup == null) {
            var methodReference = resolveStaticMethodReference(classRegistry, receiverTypeMeta, step.name());
            if (methodReference != null) {
                return resolvedMethodReferenceTrace(stepIndex, step, incomingReceiver, methodReference);
            }
            var enumValueLookup = classRegistry.findEngineClassEnumValueInHierarchy(engineClass.getName(), step.name());
            if (enumValueLookup != null) {
                return resolvedStaticLoadTrace(
                        stepIndex,
                        step,
                        incomingReceiver,
                        FrontendBindingKind.CONSTANT,
                        ScopeOwnerKind.ENGINE,
                        GdIntType.INT,
                        enumValueLookup.enumValue()
                );
            }
            var detailReason = "Engine class constant or enum value '" + step.name()
                    + "' not found in class '" + engineClass.name() + "' or its superclasses";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, ScopeOwnerKind.ENGINE, receiverTypeMeta, detailReason);
        }
        var constant = constantLookup.constant();
        var literal = constant.value() == null ? "" : constant.value().trim();
        if (!INTEGER_LITERAL_PATTERN.matcher(literal).matches()) {
            var detailReason = "Engine class constant '" + step.name() + "' in class '" + constantLookup.ownerClass().name()
                    + "' is not an integer literal";
            return failedStaticLoadTrace(stepIndex, step, incomingReceiver, ScopeOwnerKind.ENGINE, constant, detailReason);
        }
        return resolvedStaticLoadTrace(
                stepIndex,
                step,
                incomingReceiver,
                FrontendBindingKind.CONSTANT,
                ScopeOwnerKind.ENGINE,
                GdIntType.INT,
                constant
        );
    }

    private static @NotNull StepTrace reduceGdccStaticLoad(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta receiverTypeMeta
    ) {
        var methodReference = resolveStaticMethodReference(classRegistry, receiverTypeMeta, step.name());
        if (methodReference != null) {
            return resolvedMethodReferenceTrace(stepIndex, step, incomingReceiver, methodReference);
        }
        var propertyLookup = classRegistry.findStaticPropertyInHierarchy(receiverTypeMeta.canonicalName(), step.name());
        if (propertyLookup != null) {
            return resolvedStaticLoadTrace(
                    stepIndex,
                    step,
                    incomingReceiver,
                    FrontendBindingKind.PROPERTY,
                    ScopeOwnerKind.GDCC,
                    propertyLookup.property().getType(),
                    propertyLookup.property()
            );
        }
        var detailReason = "Static load route on GDCC class '" + receiverTypeMeta.displayName()
                + "' resolved to neither a static method reference nor a static property; it is outside the current support boundary";
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.STATIC_LOAD,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                null,
                FrontendResolvedMember.unsupported(
                        step.name(),
                        FrontendBindingKind.CONSTANT,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        incomingReceiver.receiverType(),
                        receiverTypeMeta.declaration(),
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace resolvedStaticLoadTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull FrontendBindingKind bindingKind,
            @Nullable ScopeOwnerKind ownerKind,
            @NotNull GdType resultType,
            @Nullable Object declarationSite
    ) {
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.STATIC_LOAD,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(resultType),
                null,
                FrontendResolvedMember.resolved(
                        step.name(),
                        bindingKind,
                        FrontendReceiverKind.TYPE_META,
                        ownerKind,
                        incomingReceiver.receiverType(),
                        resultType,
                        declarationSite
                ),
                null,
                false,
                null
        );
    }

    private static @NotNull StepTrace unsupportedPropertyInitializerStaticLoadTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull ScopeValueKind valueKind
    ) {
        var detailReason = FrontendPropertyInitializerSupport.unsupportedTypeMetaValueMessage(
                receiverTypeMeta.displayName(),
                step.name(),
                valueKind
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.STATIC_LOAD,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                null,
                FrontendResolvedMember.unsupported(
                        step.name(),
                        valueKind == ScopeValueKind.SIGNAL ? FrontendBindingKind.SIGNAL : FrontendBindingKind.PROPERTY,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        incomingReceiver.receiverType(),
                        receiverTypeMeta.declaration(),
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace failedStaticLoadTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.STATIC_LOAD,
                incomingReceiver,
                Status.FAILED,
                ReceiverState.failedFrom(incomingReceiver, detailReason),
                null,
                FrontendResolvedMember.failed(
                        step.name(),
                        FrontendBindingKind.CONSTANT,
                        FrontendReceiverKind.TYPE_META,
                        ownerKind,
                        incomingReceiver.receiverType(),
                        declarationSite,
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace resolvedMethodReferenceTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull MethodReferenceResolution methodReference
    ) {
        var callableType = new GdCallableType();
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                methodReference.routeKind(),
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(callableType),
                null,
                FrontendResolvedMember.resolved(
                        methodReference.memberName(),
                        methodReference.bindingKind(),
                        incomingReceiver.receiverKind(),
                        methodReference.ownerKind(),
                        incomingReceiver.receiverType(),
                        callableType,
                        methodReference.declarationSite()
                ),
                null,
                false,
                null
        );
    }

    private static @NotNull StepTrace unsupportedMethodReferenceTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull MethodReferenceResolution methodReference,
            @NotNull String detailReason
    ) {
        var callableType = new GdCallableType();
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                methodReference.routeKind(),
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(ReceiverState.resolvedInstance(callableType), detailReason),
                null,
                FrontendResolvedMember.unsupported(
                        methodReference.memberName(),
                        methodReference.bindingKind(),
                        incomingReceiver.receiverKind(),
                        methodReference.ownerKind(),
                        incomingReceiver.receiverType(),
                        methodReference.declarationSite(),
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    /// Resolved route for a static property reached through an instance receiver (`obj.x`).
    ///
    /// The route kind is [RouteKind.STATIC_LOAD] because storage lives on the declaring class,
    /// while `receiverKind` stays `INSTANCE` to preserve the source syntax for downstream
    /// lowering (the receiver expression must still be evaluated for side effects) and for the
    /// `sema.static_access_via_instance` warning anchored on this step.
    private static @NotNull StepTrace resolvedInstanceStaticPropertyTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeResolvedProperty property
    ) {
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.STATIC_LOAD,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(property.property().getType()),
                null,
                FrontendResolvedMember.resolved(
                        step.name(),
                        FrontendBindingKind.PROPERTY,
                        FrontendReceiverKind.INSTANCE,
                        property.ownerKind(),
                        incomingReceiver.receiverType(),
                        property.property().getType(),
                        property.property()
                ),
                null,
                false,
                null
        );
    }

    private static @NotNull StepTrace resolvedPropertyTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeResolvedProperty property
    ) {
        var member = FrontendResolvedMember.resolved(
                step.name(),
                FrontendBindingKind.PROPERTY,
                FrontendReceiverKind.INSTANCE,
                property.ownerKind(),
                incomingReceiver.receiverType(),
                property.property().getType(),
                property.property()
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_PROPERTY,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(property.property().getType()),
                null,
                member,
                null,
                false,
                null
        );
    }

    private static @NotNull StepTrace unsupportedResolvedPropertyTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeResolvedProperty property,
            @NotNull String detailReason
    ) {
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_PROPERTY,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(
                        ReceiverState.resolvedInstance(property.property().getType()),
                        detailReason
                ),
                null,
                FrontendResolvedMember.unsupported(
                        step.name(),
                        FrontendBindingKind.PROPERTY,
                        FrontendReceiverKind.INSTANCE,
                        property.ownerKind(),
                        incomingReceiver.receiverType(),
                        property.property(),
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace resolvedSignalTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeResolvedSignal signal
    ) {
        var signalType = GdSignalType.from(signal.signal());
        var member = FrontendResolvedMember.resolved(
                step.name(),
                FrontendBindingKind.SIGNAL,
                FrontendReceiverKind.INSTANCE,
                signal.ownerKind(),
                incomingReceiver.receiverType(),
                signalType,
                signal.signal()
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_SIGNAL,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(signalType),
                null,
                member,
                null,
                false,
                null
        );
    }

    private static @NotNull StepTrace unsupportedResolvedSignalTrace(
            int stepIndex,
            @NotNull AttributePropertyStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ScopeResolvedSignal signal,
            @NotNull String detailReason
    ) {
        var signalType = GdSignalType.from(signal.signal());
        return new StepTrace(
                stepIndex,
                step,
                StepKind.PROPERTY,
                RouteKind.INSTANCE_SIGNAL,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(ReceiverState.resolvedInstance(signalType), detailReason),
                null,
                FrontendResolvedMember.unsupported(
                        step.name(),
                        FrontendBindingKind.SIGNAL,
                        FrontendReceiverKind.INSTANCE,
                        signal.ownerKind(),
                        incomingReceiver.receiverType(),
                        signal.signal(),
                        detailReason
                ),
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace reduceCallStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<ReductionNote> notes
    ) {
        var argumentResolution = resolveArgumentTypes(step.arguments(), request);
        if (argumentResolution.status() != Status.RESOLVED) {
            var routeKind = incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META && step.name().equals("new")
                    ? RouteKind.CONSTRUCTOR
                    : incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META
                      ? RouteKind.STATIC_METHOD
                      : RouteKind.INSTANCE_METHOD;
            var callKind = incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META && step.name().equals("new")
                    ? FrontendCallResolutionKind.CONSTRUCTOR
                    : incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META
                      ? FrontendCallResolutionKind.STATIC_METHOD
                      : FrontendCallResolutionKind.INSTANCE_METHOD;
            return unresolvedCallDependencyTrace(
                    stepIndex,
                    step,
                    incomingReceiver,
                    argumentResolution,
                    routeKind,
                    callKind
            );
        }

        if (incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META && step.name().equals("new")) {
            return reduceConstructorStep(
                    stepIndex,
                    step,
                    incomingReceiver,
                    request,
                    argumentResolution.argumentTypes(),
                    argumentResolution.retryUsed()
            );
        }

        if (incomingReceiver.receiverKind() == FrontendReceiverKind.TYPE_META) {
            return reduceStaticMethodStep(
                    stepIndex,
                    step,
                    incomingReceiver,
                    request,
                    argumentResolution.argumentTypes()
            );
        }
        return reduceInstanceMethodStep(
                stepIndex,
                step,
                incomingReceiver,
                request,
                argumentResolution.argumentTypes(),
                argumentResolution.retryUsed(),
                notes
        );
    }

    private static @NotNull StepTrace reduceStaticMethodStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<GdType> argumentTypes
    ) {
        var receiverTypeMeta = Objects.requireNonNull(incomingReceiver.receiverTypeMeta(), "receiverTypeMeta must not be null");
        var propertyInitializerContext = request.propertyInitializerContext();
        if (FrontendPropertyInitializerSupport.isCurrentInstanceHierarchyTypeMeta(propertyInitializerContext, receiverTypeMeta)
                && FrontendPropertyInitializerSupport.hasCurrentInstanceHierarchyNonStaticFunction(
                propertyInitializerContext,
                step.name()
        )) {
            return unsupportedPropertyInitializerStaticMethodTrace(
                    stepIndex,
                    step,
                    incomingReceiver,
                    argumentTypes,
                    receiverTypeMeta
            );
        }
        var result = ScopeMethodResolver.resolveStaticMethodWithParameterRank(
                request.classRegistry(),
                receiverTypeMeta,
                step.name(),
                argumentTypes,
                literalAwareParameterRank(request, step.arguments()),
                literalAwareCandidateSpecificity(request, step.arguments(), argumentTypes)
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> resolvedCallTrace(
                    stepIndex,
                    step,
                    incomingReceiver,
                    RouteKind.STATIC_METHOD,
                    FrontendReceiverKind.TYPE_META,
                    FrontendCallResolutionKind.STATIC_METHOD,
                    resolved.method(),
                    argumentTypes,
                    false
            );
            case ScopeMethodResolver.DynamicFallback fallback -> {
                var detailReason = "Static method lookup for '" + step.name() + "' unexpectedly returned dynamic fallback: " + fallback.reason();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.STATIC_METHOD,
                        incomingReceiver,
                        Status.DYNAMIC,
                        ReceiverState.dynamic(detailReason),
                        null,
                        null,
                        FrontendResolvedCall.dynamic(
                                step.name(),
                                FrontendReceiverKind.TYPE_META,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                receiverTypeMeta,
                                detailReason
                        ),
                        false,
                        detailReason
                );
            }
            case ScopeMethodResolver.Failed failed -> {
                var detailReason = "Static method lookup for '" + step.name() + "' on type '"
                        + receiverTypeMeta.displayName() + "' failed: " + failed.kind();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.STATIC_METHOD,
                        incomingReceiver,
                        Status.FAILED,
                        ReceiverState.failedFrom(incomingReceiver, detailReason),
                        null,
                        null,
                        FrontendResolvedCall.failed(
                                step.name(),
                                FrontendCallResolutionKind.STATIC_METHOD,
                                FrontendReceiverKind.TYPE_META,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                receiverTypeMeta,
                                detailReason
                        ),
                        false,
                        detailReason
                );
            }
        };
    }

    private static @NotNull StepTrace unsupportedPropertyInitializerStaticMethodTrace(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull List<GdType> argumentTypes,
            @NotNull ScopeTypeMeta receiverTypeMeta
    ) {
        var detailReason = FrontendPropertyInitializerSupport.unsupportedTypeMetaMethodMessage(
                receiverTypeMeta.displayName(),
                step.name()
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.CALL,
                RouteKind.STATIC_METHOD,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                null,
                null,
                FrontendResolvedCall.unsupported(
                        step.name(),
                        FrontendCallResolutionKind.STATIC_METHOD,
                        FrontendReceiverKind.TYPE_META,
                        ScopeOwnerKind.GDCC,
                        incomingReceiver.receiverType(),
                        argumentTypes,
                        receiverTypeMeta.declaration(),
                        detailReason
                ),
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace reduceConstructorStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<GdType> argumentTypes,
            boolean finalizeRetryUsed
    ) {
        var receiverTypeMeta = Objects.requireNonNull(incomingReceiver.receiverTypeMeta(), "receiverTypeMeta must not be null");
        var childSourceResolver = FrontendCallableLiteralArgumentSupport.fromChainExpressionTypeResolver(
                (expression, finalizeWindow) -> lookupExpressionType(request, expression, finalizeWindow)
        );
        var resolution = FrontendConstructorResolutionSupport.resolveConstructor(
                request.classRegistry(),
                receiverTypeMeta,
                step.arguments(),
                argumentTypes,
                childSourceResolver
        );
        return switch (resolution.status()) {
            case RESOLVED -> {
                var boundary = constructorBoundaryOrNull(resolution.declarationSite());
                var publishedArgumentTypes = FrontendCallableLiteralArgumentSupport.rewriteArgumentTypes(
                        step.arguments(),
                        argumentTypes,
                        boundary == null ? null : boundary.fixedParameterTypes()
                );
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.CONSTRUCTOR,
                        incomingReceiver,
                        Status.RESOLVED,
                        ReceiverState.resolvedInstance(receiverTypeMeta.instanceType()),
                        null,
                        null,
                        FrontendResolvedCall.resolved(
                                step.name(),
                                FrontendCallResolutionKind.CONSTRUCTOR,
                                FrontendReceiverKind.TYPE_META,
                                resolution.ownerKind(),
                                incomingReceiver.receiverType(),
                                receiverTypeMeta.instanceType(),
                                publishedArgumentTypes,
                                resolution.declarationSite(),
                                boundary
                        ),
                        finalizeRetryUsed,
                        null
                );
            }
            case FAILED -> new StepTrace(
                    stepIndex,
                    step,
                    StepKind.CALL,
                    RouteKind.CONSTRUCTOR,
                    incomingReceiver,
                    Status.FAILED,
                    ReceiverState.failedFrom(incomingReceiver, Objects.requireNonNull(resolution.detailReason())),
                    null,
                    null,
                    FrontendResolvedCall.failed(
                            step.name(),
                            FrontendCallResolutionKind.CONSTRUCTOR,
                            FrontendReceiverKind.TYPE_META,
                            resolution.ownerKind(),
                            incomingReceiver.receiverType(),
                            argumentTypes,
                            resolution.declarationSite(),
                            Objects.requireNonNull(resolution.detailReason())
                    ),
                    finalizeRetryUsed,
                    Objects.requireNonNull(resolution.detailReason())
            );
            default -> throw new IllegalStateException("unexpected constructor status: " + resolution.status());
        };
    }

    private static @NotNull StepTrace reduceInstanceMethodStep(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<GdType> argumentTypes,
            boolean finalizeRetryUsed,
            @NotNull List<ReductionNote> notes
    ) {
        var propertyInitializerContext = request.propertyInitializerContext();
        var classRegistry = request.classRegistry();
        var noteSink = request.noteSink();
        var result = ScopeMethodResolver.resolveInstanceMethodWithParameterRank(
                classRegistry,
                Objects.requireNonNull(incomingReceiver.receiverType(), "receiverType must not be null"),
                step.name(),
                argumentTypes,
                literalAwareParameterRank(request, step.arguments()),
                literalAwareCandidateSpecificity(request, step.arguments(), argumentTypes)
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> {
                var resolvedMethod = resolved.method();
                var boundaryDetail = FrontendPropertyInitializerSupport.detailForResolvedMethodBoundary(
                        propertyInitializerContext,
                        resolvedMethod
                );
                var routeKind = resolvedMethod.isStatic() ? RouteKind.STATIC_METHOD : RouteKind.INSTANCE_METHOD;
                var callKind = resolvedMethod.isStatic()
                        ? FrontendCallResolutionKind.STATIC_METHOD
                        : FrontendCallResolutionKind.INSTANCE_METHOD;
                if (boundaryDetail != null) {
                    yield unsupportedResolvedCallTrace(
                            stepIndex,
                            step,
                            incomingReceiver,
                            routeKind,
                            FrontendReceiverKind.INSTANCE,
                            callKind,
                            resolvedMethod,
                            argumentTypes,
                            finalizeRetryUsed,
                            boundaryDetail
                    );
                }
                if (resolvedMethod.isStatic()) {
                    emitNote(
                            step,
                            "Instance-style syntax resolved to static method '" + resolvedMethod.ownerClass().getName()
                                    + "." + resolvedMethod.methodName() + "'",
                            notes,
                            noteSink
                    );
                }
                yield resolvedCallTrace(
                        stepIndex,
                        step,
                        incomingReceiver,
                        routeKind,
                        FrontendReceiverKind.INSTANCE,
                        callKind,
                        resolvedMethod,
                        argumentTypes,
                        finalizeRetryUsed
                );
            }
            case ScopeMethodResolver.DynamicFallback fallback -> {
                var detailReason = "Method lookup for '" + step.name() + "' chose runtime-dynamic fallback: " + fallback.reason();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.INSTANCE_METHOD,
                        incomingReceiver,
                        Status.DYNAMIC,
                        ReceiverState.dynamic(detailReason),
                        null,
                        null,
                        FrontendResolvedCall.dynamic(
                                step.name(),
                                FrontendReceiverKind.INSTANCE,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                null,
                                detailReason
                        ),
                        finalizeRetryUsed,
                        detailReason
                );
            }
            case ScopeMethodResolver.Failed failed -> {
                var detailReason = "Method lookup for '" + step.name() + "' on type '"
                        + incomingReceiver.receiverType().getTypeName() + "' failed: " + failed.kind();
                yield new StepTrace(
                        stepIndex,
                        step,
                        StepKind.CALL,
                        RouteKind.INSTANCE_METHOD,
                        incomingReceiver,
                        Status.FAILED,
                        ReceiverState.failedFrom(incomingReceiver, detailReason),
                        null,
                        null,
                        FrontendResolvedCall.failed(
                                step.name(),
                                FrontendCallResolutionKind.INSTANCE_METHOD,
                                FrontendReceiverKind.INSTANCE,
                                null,
                                incomingReceiver.receiverType(),
                                argumentTypes,
                                null,
                                detailReason
                        ),
                        finalizeRetryUsed,
                        detailReason
                );
            }
        };
    }

    private static @NotNull StepTrace unsupportedResolvedCallTrace(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull RouteKind routeKind,
            @NotNull FrontendReceiverKind receiverKind,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull ScopeResolvedMethod resolvedMethod,
            @NotNull List<GdType> argumentTypes,
            boolean finalizeRetryUsed,
            @NotNull String detailReason
    ) {
        return new StepTrace(
                stepIndex,
                step,
                StepKind.CALL,
                routeKind,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(
                        ReceiverState.resolvedInstance(resolvedMethod.returnType()),
                        detailReason
                ),
                null,
                null,
                FrontendResolvedCall.unsupported(
                        step.name(),
                        callKind,
                        receiverKind,
                        resolvedMethod.ownerKind(),
                        incomingReceiver.receiverType(),
                        argumentTypes,
                        resolvedMethod.function(),
                        detailReason
                ),
                finalizeRetryUsed,
                detailReason
        );
    }

    private static @NotNull StepTrace resolvedCallTrace(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull RouteKind routeKind,
            @NotNull FrontendReceiverKind receiverKind,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull ScopeResolvedMethod resolvedMethod,
            @NotNull List<GdType> argumentTypes,
            boolean finalizeRetryUsed
    ) {
        // Publish the shared resolver's normalized callable boundary once so downstream exact-call
        // consumers can reuse it without touching raw `FunctionDef` parameter metadata again.
        // Rewrite container-literal argument snapshots to the selected contextual construction type
        // so CHAIN_BINDING facts already match EXPR_TYPE finalization.
        var boundary = FrontendResolvedCall.ExactCallableBoundary.fromResolvedMethod(resolvedMethod);
        var publishedArgumentTypes = FrontendCallableLiteralArgumentSupport.rewriteArgumentTypes(
                step.arguments(),
                argumentTypes,
                boundary.fixedParameterTypes()
        );
        var call = FrontendResolvedCall.resolved(
                step.name(),
                callKind,
                receiverKind,
                resolvedMethod.ownerKind(),
                incomingReceiver.receiverType(),
                resolvedMethod.returnType(),
                publishedArgumentTypes,
                resolvedMethod.function(),
                boundary
        );
        return new StepTrace(
                stepIndex,
                step,
                StepKind.CALL,
                routeKind,
                incomingReceiver,
                Status.RESOLVED,
                ReceiverState.resolvedInstance(resolvedMethod.returnType()),
                null,
                null,
                call,
                finalizeRetryUsed,
                null
        );
    }

    private static @NotNull StepTrace unresolvedCallDependencyTrace(
            int stepIndex,
            @NotNull AttributeCallStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ArgumentResolution argumentResolution,
            @NotNull RouteKind routeKind,
            @NotNull FrontendCallResolutionKind callKind
    ) {
        var detailReason = Objects.requireNonNull(argumentResolution.detailReason(), "detailReason must not be null");
        var status = argumentResolution.status();
        var outgoingReceiver = switch (status) {
            case BLOCKED -> new ReceiverState(Status.BLOCKED, FrontendReceiverKind.UNKNOWN, null, null, detailReason);
            case DEFERRED -> ReceiverState.deferredFrom(incomingReceiver, detailReason);
            case FAILED -> ReceiverState.failedFrom(incomingReceiver, detailReason);
            case UNSUPPORTED -> ReceiverState.unsupportedFrom(incomingReceiver, detailReason);
            default -> throw new IllegalStateException("unexpected argument status: " + status);
        };
        var call = switch (status) {
            case BLOCKED -> null;
            case DEFERRED -> FrontendResolvedCall.deferred(
                    step.name(),
                    callKind,
                    incomingReceiver.receiverKind(),
                    null,
                    incomingReceiver.receiverType(),
                    argumentResolution.argumentTypes(),
                    null,
                    detailReason
            );
            case FAILED -> FrontendResolvedCall.failed(
                    step.name(),
                    callKind,
                    incomingReceiver.receiverKind(),
                    null,
                    incomingReceiver.receiverType(),
                    argumentResolution.argumentTypes(),
                    null,
                    detailReason
            );
            case UNSUPPORTED -> FrontendResolvedCall.unsupported(
                    step.name(),
                    callKind,
                    incomingReceiver.receiverKind(),
                    null,
                    incomingReceiver.receiverType(),
                    argumentResolution.argumentTypes(),
                    null,
                    detailReason
            );
            default -> throw new IllegalStateException("unexpected argument status: " + status);
        };
        return new StepTrace(
                stepIndex,
                step,
                StepKind.CALL,
                routeKind,
                incomingReceiver,
                status,
                outgoingReceiver,
                null,
                null,
                call,
                argumentResolution.retryUsed(),
                detailReason
        );
    }

    private static @NotNull StepTrace reduceSubscriptStep(
            int stepIndex,
            @NotNull AttributeSubscriptStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ReductionRequest request,
            @NotNull List<ReductionNote> notes
    ) {
        var memberResolution = reducePropertyStep(
                stepIndex,
                new AttributePropertyStep(step.name(), step.range()),
                incomingReceiver,
                request,
                notes
        );
        if (memberResolution.status() != Status.RESOLVED && memberResolution.status() != Status.DYNAMIC) {
            return new StepTrace(
                    stepIndex,
                    step,
                    StepKind.SUBSCRIPT,
                    RouteKind.SUBSCRIPT,
                    incomingReceiver,
                    memberResolution.status(),
                    memberResolution.outgoingReceiver(),
                    null,
                    null,
                    null,
                    false,
                    Objects.requireNonNull(memberResolution.detailReason(), "detailReason must not be null")
            );
        }

        var argumentResolution = resolveArgumentTypes(step.arguments(), request);
        if (argumentResolution.status() != Status.RESOLVED) {
            return unresolvedSubscriptDependencyTrace(stepIndex, step, incomingReceiver, argumentResolution);
        }

        var receiverType = Objects.requireNonNull(
                memberResolution.outgoingReceiver().receiverType(),
                "receiverType must not be null"
        );
        var semanticResult = new FrontendSubscriptSemanticSupport(request.classRegistry()).resolveSubscriptType(
                receiverType,
                argumentResolution.argumentTypes(),
                "attribute subscript step '" + step.name() + "'"
        );
        var subscriptTypeResult = FrontendChainStatusBridge.toExpressionTypeResult(semanticResult);
        var outgoingReceiver = FrontendChainStatusBridge.toReceiverState(subscriptTypeResult);
        var detailReason = semanticResult.detailReason();
        // Attribute-subscript steps re-anchor the internally synthesized container property
        // resolution (instance or static) on the real subscript step: the synthesized property
        // step is detached from the AST, so the side table cannot retrieve it otherwise. Body
        // lowering consumes the fact as container provenance (`receiver.member[key]`): the
        // published container type feeds `SubscriptLeaf.containerSourceType`, and static
        // containers additionally redirect the named-base scratch/writeback from the Variant
        // named route to `LoadStaticInsn`/`StoreStaticInsn`. Dynamic containers (Variant
        // receivers) stay unpublished and keep the Variant named route.
        var containerMember = memberResolution.suggestedMember();
        var publishableContainerMember = containerMember != null
                && containerMember.status() == FrontendMemberResolutionStatus.RESOLVED
                && containerMember.bindingKind() == FrontendBindingKind.PROPERTY
                ? containerMember
                : null;
        return new StepTrace(
                stepIndex,
                step,
                StepKind.SUBSCRIPT,
                RouteKind.SUBSCRIPT,
                incomingReceiver,
                outgoingReceiver.status(),
                outgoingReceiver,
                null,
                publishableContainerMember,
                null,
                argumentResolution.retryUsed(),
                detailReason
        );
    }

    private static @NotNull StepTrace unresolvedSubscriptDependencyTrace(
            int stepIndex,
            @NotNull AttributeSubscriptStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull ArgumentResolution argumentResolution
    ) {
        var detailReason = Objects.requireNonNull(argumentResolution.detailReason(), "detailReason must not be null");
        var status = argumentResolution.status();
        var outgoingReceiver = switch (status) {
            case BLOCKED -> new ReceiverState(Status.BLOCKED, FrontendReceiverKind.UNKNOWN, null, null, detailReason);
            case DEFERRED -> ReceiverState.deferredFrom(incomingReceiver, detailReason);
            case FAILED -> ReceiverState.failedFrom(incomingReceiver, detailReason);
            case UNSUPPORTED -> ReceiverState.unsupportedFrom(incomingReceiver, detailReason);
            default -> throw new IllegalStateException("unexpected argument status: " + status);
        };
        return new StepTrace(
                stepIndex,
                step,
                StepKind.SUBSCRIPT,
                RouteKind.SUBSCRIPT,
                incomingReceiver,
                status,
                outgoingReceiver,
                null,
                null,
                null,
                argumentResolution.retryUsed(),
                detailReason
        );
    }

    private static @NotNull StepTrace unsupportedUnknownStep(
            int stepIndex,
            @NotNull UnknownAttributeStep step,
            @NotNull ReceiverState incomingReceiver
    ) {
        var detailReason = "Unknown attribute step '" + step.nodeType() + "' cannot enter exact chain reduction";
        return new StepTrace(
                stepIndex,
                step,
                StepKind.UNKNOWN,
                RouteKind.SUBSCRIPT,
                incomingReceiver,
                Status.UNSUPPORTED,
                ReceiverState.unsupportedFrom(incomingReceiver, detailReason),
                null,
                null,
                null,
                false,
                detailReason
        );
    }

    private static @NotNull StepTrace propagateStep(
            int stepIndex,
            @NotNull AttributeStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull UpstreamCause upstreamCause
    ) {
        var detailReason = renderUpstreamReason(upstreamCause);
        var routeKind = switch (upstreamCause.status()) {
            case BLOCKED -> RouteKind.UPSTREAM_BLOCKED;
            case DEFERRED -> RouteKind.UPSTREAM_DEFERRED;
            case DYNAMIC -> RouteKind.UPSTREAM_DYNAMIC;
            case FAILED -> RouteKind.UPSTREAM_FAILED;
            case UNSUPPORTED -> RouteKind.UPSTREAM_UNSUPPORTED;
            case RESOLVED -> throw new IllegalStateException("upstream cause cannot be resolved");
        };
        var outgoingReceiver = switch (upstreamCause.status()) {
            case BLOCKED -> ReceiverState.blockedFrom(incomingReceiver, detailReason);
            case DEFERRED -> ReceiverState.deferredFrom(incomingReceiver, detailReason);
            case DYNAMIC -> ReceiverState.dynamic(detailReason);
            case FAILED -> ReceiverState.failedFrom(incomingReceiver, detailReason);
            case UNSUPPORTED -> ReceiverState.unsupportedFrom(incomingReceiver, detailReason);
            case RESOLVED -> throw new IllegalStateException("upstream cause cannot be resolved");
        };
        FrontendResolvedMember suggestedMember = null;
        if (upstreamCause.status() == Status.FAILED && upstreamCause.sourceStepIndex().isEmpty()) {
            suggestedMember = headFailureSuggestedMember(step, incomingReceiver, detailReason);
        } else if (upstreamCause.status() == Status.DYNAMIC && step instanceof AttributePropertyStep propertyStep) {
            suggestedMember = FrontendResolvedMember.dynamic(
                    propertyStep.name(),
                    FrontendBindingKind.UNKNOWN,
                    FrontendReceiverKind.INSTANCE,
                    null,
                    GdVariantType.VARIANT,
                    null,
                    detailReason
            );
        }
        var suggestedCall = upstreamCause.status() == Status.FAILED && upstreamCause.sourceStepIndex().isEmpty()
                ? headFailureSuggestedCall(step, incomingReceiver, detailReason)
                : null;
        return new StepTrace(
                stepIndex,
                step,
                classifyStepKind(step),
                routeKind,
                incomingReceiver,
                outgoingReceiver.status(),
                outgoingReceiver,
                upstreamCause,
                suggestedMember,
                suggestedCall,
                false,
                detailReason
        );
    }

    private static @Nullable FrontendResolvedMember toBlockedSuggestedMember(
            @Nullable FrontendResolvedMember resolvedMember,
            @NotNull String detailReason
    ) {
        if (resolvedMember == null) {
            return null;
        }
        return FrontendResolvedMember.blocked(
                resolvedMember.memberName(),
                resolvedMember.bindingKind(),
                resolvedMember.receiverKind(),
                resolvedMember.ownerKind(),
                resolvedMember.receiverType(),
                Objects.requireNonNull(resolvedMember.resultType(), "resultType must not be null"),
                resolvedMember.declarationSite(),
                detailReason
        );
    }

    private static @Nullable FrontendResolvedCall toBlockedSuggestedCall(
            @Nullable FrontendResolvedCall resolvedCall,
            @NotNull String detailReason
    ) {
        if (resolvedCall == null) {
            return null;
        }
        // Once an exact callable boundary has been published, blocked propagation must keep that same
        // normalized fact instead of dropping it and forcing later consumers back to raw metadata.
        return FrontendResolvedCall.blocked(
                resolvedCall.callableName(),
                resolvedCall.callKind(),
                resolvedCall.receiverKind(),
                resolvedCall.ownerKind(),
                resolvedCall.receiverType(),
                Objects.requireNonNull(resolvedCall.returnType(), "returnType must not be null"),
                resolvedCall.argumentTypes(),
                resolvedCall.declarationSite(),
                detailReason,
                resolvedCall.exactCallableBoundary()
        );
    }

    /// A failed chain head should still materialize one concrete failed first-step publication so
    /// analyzer layers can issue a stable error diagnostic instead of observing an empty reduction.
    private static @Nullable FrontendResolvedMember headFailureSuggestedMember(
            @NotNull AttributeStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull String detailReason
    ) {
        if (!(step instanceof AttributePropertyStep propertyStep)) {
            return null;
        }
        return FrontendResolvedMember.failed(
                propertyStep.name(),
                FrontendBindingKind.UNKNOWN,
                incomingReceiver.receiverKind(),
                null,
                incomingReceiver.receiverType(),
                null,
                detailReason
        );
    }

    private static @Nullable FrontendResolvedCall headFailureSuggestedCall(
            @NotNull AttributeStep step,
            @NotNull ReceiverState incomingReceiver,
            @NotNull String detailReason
    ) {
        if (!(step instanceof AttributeCallStep callStep)) {
            return null;
        }
        return FrontendResolvedCall.failed(
                callStep.name(),
                FrontendCallResolutionKind.UNKNOWN,
                incomingReceiver.receiverKind(),
                null,
                incomingReceiver.receiverType(),
                List.copyOf(Collections.nCopies(callStep.arguments().size(), GdVariantType.VARIANT)),
                null,
                detailReason
        );
    }

    /// Call/subscript steps are the only places where B-phase local retry is needed right now.
    /// We keep the retry window bounded to two passes:
    /// - normal lookup from published side tables / current callback facts
    /// - one finalize-window retry for the same argument list
    /// Index-aware applicability rank for chain instance/static method selection. Container-literal
    /// arguments use element-boundary preview ranks (`0` rejects); ordinary arguments keep matrix
    /// ranks. Specificity among applicable candidates uses
    /// [literalAwareCandidateSpecificity] so chain shares bare/ctor `literalAggregateRank`.
    private static @NotNull ScopeMethodResolver.ParameterCompatibilityRank literalAwareParameterRank(
            @NotNull ReductionRequest request,
            @NotNull List<? extends Expression> argumentExpressions
    ) {
        var childSourceResolver = chainChildSourceResolver(request);
        return (argumentIndex, sourceType, targetType) -> FrontendCallableLiteralArgumentSupport.parameterCompatibilityRank(
                request.classRegistry(),
                argumentExpressions,
                childSourceResolver,
                argumentIndex,
                sourceType,
                targetType
        );
    }

    /// Candidate-level specificity for chain instance/static selection.
    ///
    /// Closes over call-site expressions without pushing AST into the scope package. Uses the same
    /// `literalAggregateRank` + `compareCandidateRanks` path as bare/constructor overload
    /// selection; aggregates over `ScopeResolvedMethod.parameters()` (synthetic `self` already
    /// stripped). When aggregates tie, falls back to matrix Pareto on user-visible fixed parameters.
    private static @NotNull ScopeMethodResolver.CandidateSpecificity literalAwareCandidateSpecificity(
            @NotNull ReductionRequest request,
            @NotNull List<? extends Expression> argumentExpressions,
            @NotNull List<GdType> preliminaryArgumentTypes
    ) {
        var childSourceResolver = chainChildSourceResolver(request);
        var classRegistry = request.classRegistry();
        return (candidate, baseline) -> {
            if (argumentExpressions.isEmpty()) {
                return isStrictlyMoreSpecificByBoundaryRanks(
                        classRegistry,
                        candidate,
                        baseline,
                        preliminaryArgumentTypes
                );
            }
            return FrontendCallableLiteralArgumentSupport.isStrictlyMoreSpecificByLiteralAggregate(
                    classRegistry,
                    fixedParameterTypes(candidate),
                    fixedParameterTypes(baseline),
                    argumentExpressions,
                    preliminaryArgumentTypes,
                    childSourceResolver,
                    () -> isStrictlyMoreSpecificByBoundaryRanks(
                            classRegistry,
                            candidate,
                            baseline,
                            preliminaryArgumentTypes
                    )
            );
        };
    }

    private static @NotNull FrontendCallableLiteralArgumentSupport.ChildSourceResolver chainChildSourceResolver(
            @NotNull ReductionRequest request
    ) {
        return FrontendCallableLiteralArgumentSupport.fromChainExpressionTypeResolver(
                (expression, finalizeWindow) -> lookupExpressionType(request, expression, finalizeWindow)
        );
    }

    private static @NotNull List<GdType> fixedParameterTypes(@NotNull ScopeResolvedMethod method) {
        return method.parameters().stream()
                .map(ScopeMethodParameter::type)
                .toList();
    }

    /// Classic per-argument matrix Pareto on user-visible fixed parameters (chain fallback when
    /// `literalAggregateRank` ties, and when there are no argument expressions).
    ///
    /// Aligned with bare/constructor `isStrictlyMoreSpecific`: supplied-arg ranks, then fewer
    /// omitted trailing defaults preferred, then non-vararg over vararg.
    private static boolean isStrictlyMoreSpecificByBoundaryRanks(
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeResolvedMethod candidate,
            @NotNull ScopeResolvedMethod baseline,
            @NotNull List<GdType> argumentTypes
    ) {
        var strictlyBetter = false;
        for (var index = 0; index < argumentTypes.size(); index++) {
            var sourceType = argumentTypes.get(index);
            var candidateTarget = index < candidate.parameters().size()
                    ? candidate.parameters().get(index).type()
                    : null;
            var baselineTarget = index < baseline.parameters().size()
                    ? baseline.parameters().get(index).type()
                    : null;
            if (candidateTarget == null && baselineTarget == null) {
                continue;
            }
            if (candidateTarget == null) {
                if (candidate.isVararg()) {
                    return false;
                }
                continue;
            }
            if (baselineTarget == null) {
                if (baseline.isVararg()) {
                    strictlyBetter = true;
                }
                continue;
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
            if (candidateRank < baselineRank) {
                return false;
            }
            if (candidateRank > baselineRank) {
                strictlyBetter = true;
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

    private static int omittedTrailingParameterCount(
            @NotNull ScopeResolvedMethod method,
            int providedCount
    ) {
        var parameters = method.parameters();
        if (providedCount >= parameters.size()) {
            return 0;
        }
        return parameters.size() - providedCount;
    }

    /// Constructor declaration sites are either a selected `FunctionDef` or an owner-level anchor
    /// (Variant-driven unary route / zero-arg engine constructor). Only `FunctionDef` publishes a
    /// fixed-parameter boundary for contextual literal finalization.
    private static @Nullable FrontendResolvedCall.ExactCallableBoundary constructorBoundaryOrNull(
            @Nullable Object declarationSite
    ) {
        if (declarationSite instanceof FunctionDef functionDef) {
            return FrontendResolvedCall.ExactCallableBoundary.fromFunctionDef(functionDef);
        }
        return null;
    }

    private static @NotNull ArgumentResolution resolveArgumentTypes(
            @NotNull List<? extends Expression> arguments,
            @NotNull ReductionRequest request
    ) {
        var resolvedTypes = new ArrayList<GdType>(arguments.size());
        var deferredIndexes = new ArrayList<Integer>();
        for (var index = 0; index < arguments.size(); index++) {
            var expression = arguments.get(index);
            var result = lookupExpressionType(request, expression, false);
            switch (result.status()) {
                case RESOLVED -> resolvedTypes.add(Objects.requireNonNull(result.type()));
                case DYNAMIC -> resolvedTypes.add(Objects.requireNonNull(result.type()));
                case BLOCKED -> {
                    if (result.type() != null) {
                        resolvedTypes.add(result.type());
                    }
                    return new ArgumentResolution(
                            Status.BLOCKED,
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (index + 1) + " is blocked: " + result.detailReason(),
                            false
                    );
                }
                case DEFERRED -> {
                    resolvedTypes.add(null);
                    deferredIndexes.add(index);
                }
                case FAILED, UNSUPPORTED -> {
                    return new ArgumentResolution(
                            result.status(),
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (index + 1) + " type is unavailable: " + result.detailReason(),
                            false
                    );
                }
            }
        }

        if (deferredIndexes.isEmpty()) {
            return new ArgumentResolution(Status.RESOLVED, List.copyOf(resolvedTypes), null, false);
        }

        for (var deferredIndex : deferredIndexes) {
            var expression = arguments.get(deferredIndex);
            var result = lookupExpressionType(request, expression, true);
            switch (result.status()) {
                case RESOLVED -> resolvedTypes.set(deferredIndex, Objects.requireNonNull(result.type()));
                case DYNAMIC -> resolvedTypes.set(deferredIndex, Objects.requireNonNull(result.type()));
                case BLOCKED -> {
                    if (result.type() != null) {
                        resolvedTypes.set(deferredIndex, result.type());
                    }
                    return new ArgumentResolution(
                            Status.BLOCKED,
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (deferredIndex + 1) + " is blocked during finalize-window retry: "
                                    + result.detailReason(),
                            true
                    );
                }
                case DEFERRED -> {
                    return new ArgumentResolution(
                            Status.DEFERRED,
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (deferredIndex + 1) + " type is still deferred after finalize-window retry: "
                                    + result.detailReason(),
                            true
                    );
                }
                case FAILED, UNSUPPORTED -> {
                    return new ArgumentResolution(
                            result.status(),
                            resolvedArgumentPrefix(resolvedTypes),
                            "Argument #" + (deferredIndex + 1) + " type became unavailable during finalize-window retry: "
                                    + result.detailReason(),
                            true
                    );
                }
            }
        }
        return new ArgumentResolution(Status.RESOLVED, List.copyOf(resolvedTypes), null, true);
    }

    private static @NotNull List<GdType> resolvedArgumentPrefix(@NotNull List<GdType> resolvedTypes) {
        var prefix = new ArrayList<GdType>(resolvedTypes.size());
        for (var resolvedType : resolvedTypes) {
            if (resolvedType == null) {
                break;
            }
            prefix.add(resolvedType);
        }
        return List.copyOf(prefix);
    }

    private static @NotNull ExpressionTypeResult lookupExpressionType(
            @NotNull ReductionRequest request,
            @NotNull Expression expression,
            boolean finalizeWindow
    ) {
        // The injected resolver owns dependency lookup ordering. Body resolvers must see their
        // procedure-local transient caches and overlay facts before stable side tables, so this
        // helper must not short-circuit through `analysisData.expressionTypes()` first.
        return request.expressionTypeResolver().resolve(expression, finalizeWindow);
    }

    private static @Nullable ExtensionBuiltinClass resolveBuiltinStaticOwner(
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta receiverTypeMeta
    ) {
        if (receiverTypeMeta.declaration() instanceof ExtensionBuiltinClass builtinClass) {
            return builtinClass;
        }
        return classRegistry.findBuiltinClass(receiverTypeMeta.canonicalName());
    }

    private static @Nullable ExtensionGdClass resolveEngineStaticOwner(
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta receiverTypeMeta
    ) {
        if (receiverTypeMeta.declaration() instanceof ExtensionGdClass engineClass) {
            return engineClass;
        }
        if (receiverTypeMeta.instanceType() instanceof GdObjectType objectType
                && classRegistry.getClassDef(objectType) instanceof ExtensionGdClass engineClass) {
            return engineClass;
        }
        return null;
    }

    private static @Nullable MethodReferenceResolution resolveInstanceMethodReference(
            @NotNull ClassRegistry classRegistry,
            @NotNull GdType receiverType,
            @NotNull String memberName
    ) {
        if (receiverType instanceof GdObjectType objectType) {
            var classDef = classRegistry.getClassDef(objectType);
            return classDef == null ? null : resolveMethodReference(classRegistry, classDef, memberName, false);
        }
        var builtinClass = classRegistry.findBuiltinClass(receiverType.getTypeName());
        return builtinClass == null ? null : resolveMethodReference(classRegistry, builtinClass, memberName, false);
    }

    private static @Nullable MethodReferenceResolution resolveStaticMethodReference(
            @NotNull ClassRegistry classRegistry,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull String memberName
    ) {
        return switch (receiverTypeMeta.kind()) {
            case GLOBAL_ENUM -> null;
            case BUILTIN -> {
                var builtinClass = resolveBuiltinStaticOwner(classRegistry, receiverTypeMeta);
                yield builtinClass == null ? null : resolveMethodReference(classRegistry, builtinClass, memberName, true);
            }
            case ENGINE_CLASS, GDCC_CLASS -> {
                var classDef = classRegistry.resolveClassDefFromTypeMeta(receiverTypeMeta);
                yield classDef == null ? null : resolveMethodReference(classRegistry, classDef, memberName, true);
            }
        };
    }

    private static @Nullable MethodReferenceResolution resolveMethodReference(
            @NotNull ClassRegistry classRegistry,
            @NotNull ClassDef startClass,
            @NotNull String memberName,
            boolean staticOnly
    ) {
        ClassDef current = startClass;
        var visited = new HashSet<String>();
        while (current != null && visited.add(current.getName())) {
            var ownerMethods = current.getFunctions().stream()
                    .filter(function -> function.getName().equals(memberName))
                    .filter(function -> function.isStatic() == staticOnly)
                    .toList();
            if (!ownerMethods.isEmpty()) {
                var ownerKind = resolveMethodOwnerKind(classRegistry, current);
                if (ownerKind == null) {
                    return null;
                }
                return new MethodReferenceResolution(
                        memberName,
                        staticOnly ? FrontendBindingKind.STATIC_METHOD : FrontendBindingKind.METHOD,
                        staticOnly ? RouteKind.STATIC_METHOD : RouteKind.INSTANCE_METHOD,
                        current,
                        ownerKind,
                        List.copyOf(ownerMethods)
                );
            }
            current = classRegistry.resolveSuperclass(current);
        }
        return null;
    }

    private static @Nullable ScopeOwnerKind resolveMethodOwnerKind(
            @NotNull ClassRegistry classRegistry,
            @NotNull ClassDef ownerClass
    ) {
        if (ownerClass instanceof ExtensionBuiltinClass) {
            return ScopeOwnerKind.BUILTIN;
        }
        if (ownerClass instanceof ExtensionGdClass) {
            return ScopeOwnerKind.ENGINE;
        }
        if (ownerClass.isGdccClass() || classRegistry.isGdccClass(ownerClass.getName())) {
            return ScopeOwnerKind.GDCC;
        }
        if (classRegistry.isGdClass(ownerClass.getName())) {
            return ScopeOwnerKind.ENGINE;
        }
        if (classRegistry.isBuiltinClass(ownerClass.getName())) {
            return ScopeOwnerKind.BUILTIN;
        }
        return null;
    }

    private record MethodReferenceResolution(
            @NotNull String memberName,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull RouteKind routeKind,
            @NotNull ClassDef ownerClass,
            @NotNull ScopeOwnerKind ownerKind,
            @NotNull List<? extends FunctionDef> declarationSite
    ) {
        private MethodReferenceResolution {
            Objects.requireNonNull(memberName, "memberName must not be null");
            Objects.requireNonNull(bindingKind, "bindingKind must not be null");
            Objects.requireNonNull(routeKind, "routeKind must not be null");
            Objects.requireNonNull(ownerClass, "ownerClass must not be null");
            Objects.requireNonNull(ownerKind, "ownerKind must not be null");
            declarationSite = List.copyOf(declarationSite);
        }
    }

    private static @NotNull StepKind classifyStepKind(@NotNull AttributeStep step) {
        return switch (step) {
            case AttributePropertyStep _ -> StepKind.PROPERTY;
            case AttributeCallStep _ -> StepKind.CALL;
            case AttributeSubscriptStep _ -> StepKind.SUBSCRIPT;
            case UnknownAttributeStep _ -> StepKind.UNKNOWN;
        };
    }

    private static void emitNote(
            @NotNull Node anchor,
            @NotNull String message,
            @NotNull List<ReductionNote> notes,
            @NotNull NoteSink noteSink
    ) {
        emitNote(anchor, ReductionNote.DEFAULT_CATEGORY, message, notes, noteSink);
    }

    private static void emitNote(
            @NotNull Node anchor,
            @NotNull String category,
            @NotNull String message,
            @NotNull List<ReductionNote> notes,
            @NotNull NoteSink noteSink
    ) {
        var note = new ReductionNote(anchor, category, message);
        notes.add(note);
        noteSink.accept(note);
    }

    private static @NotNull ScopePropertyResolver.Failed assertPropertyFailure(@NotNull Object result) {
        return switch (result) {
            case ScopePropertyResolver.Failed failed -> failed;
            default -> throw new IllegalStateException("expected property failure, got " + result);
        };
    }

    private static @NotNull ScopeSignalResolver.Failed assertSignalFailure(@NotNull Object result) {
        return switch (result) {
            case ScopeSignalResolver.Failed failed -> failed;
            default -> throw new IllegalStateException("expected signal failure, got " + result);
        };
    }

    private static @NotNull String renderMemberFailure(
            @NotNull ScopePropertyResolver.Failed propertyFailed,
            @NotNull ScopeSignalResolver.Failed signalFailed
    ) {
        if (propertyFailed.kind() != ScopePropertyResolver.FailureKind.PROPERTY_MISSING) {
            return "Property resolution failed for '" + propertyFailed.propertyName() + "': " + propertyFailed.kind();
        }
        if (signalFailed.kind() != ScopeSignalResolver.FailureKind.SIGNAL_MISSING) {
            return "Signal resolution failed for '" + signalFailed.signalName() + "': " + signalFailed.kind();
        }
        return "Member '" + propertyFailed.propertyName() + "' was not found as a property or signal";
    }

    private static @NotNull String renderUpstreamReason(@NotNull UpstreamCause upstreamCause) {
        var anchor = upstreamCause.sourceStepIndex().isEmpty()
                ? "chain head"
                : "upstream step #" + (upstreamCause.sourceStepIndex().getAsInt() + 1);
        return switch (upstreamCause.status()) {
            case BLOCKED -> "Exact suffix resolution is blocked by " + anchor + ": " + upstreamCause.detailReason();
            case DEFERRED ->
                    "Exact suffix resolution stays deferred because of " + anchor + ": " + upstreamCause.detailReason();
            case DYNAMIC ->
                    "Exact suffix resolution stops after runtime-dynamic route at " + anchor + ": " + upstreamCause.detailReason();
            case FAILED ->
                    "Exact suffix resolution stops after failed step at " + anchor + ": " + upstreamCause.detailReason();
            case UNSUPPORTED ->
                    "Exact suffix resolution is sealed by unsupported " + anchor + ": " + upstreamCause.detailReason();
            case RESOLVED -> throw new IllegalStateException("upstream cause cannot be resolved");
        };
    }

    private record ArgumentResolution(
            @NotNull Status status,
            @NotNull List<GdType> argumentTypes,
            @Nullable String detailReason,
            boolean retryUsed
    ) {
        private ArgumentResolution {
            Objects.requireNonNull(status, "status must not be null");
            argumentTypes = List.copyOf(argumentTypes);
            if (status == Status.RESOLVED) {
                if (detailReason != null) {
                    throw new IllegalArgumentException("detailReason must be null for resolved argument resolution");
                }
            } else {
                StringUtil.requireNonBlank(detailReason, "detailReason");
            }
        }
    }

}
