package gd.script.gdcc.frontend.lowering.pass.body;

import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import gd.script.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import gd.script.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import gd.script.gdcc.frontend.lowering.FunctionLoweringContext;
import gd.script.gdcc.frontend.lowering.cfg.FrontendCfgGraph;
import gd.script.gdcc.frontend.lowering.cfg.FrontendForSourceIteratorSlot;
import gd.script.gdcc.frontend.lowering.cfg.FrontendMatchBindSlot;
import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.AwaitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CastItem;
import gd.script.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
import gd.script.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionKind;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendVariantBoundaryCompatibility;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.AssertObjectLiveInsn;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.BuiltinCastInsn;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.ObjectCastInsn;
import gd.script.gdcc.lir.insn.PackVariantInsn;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ParameterDef;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.GdContainerType;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdIntVectorType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.util.StringUtil;
import gd.script.gdcc.util.type.ExplicitCastSupport;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

/// Stateful carrier for one function-body lowering run.
///
/// The session owns the shared lowering facts for a single function:
/// - published semantic tables already accepted by compile gate
/// - declared temp/local/merge slots
/// - block materialization order
/// - processor registries for CFG nodes, sequence items, and opaque expressions, including
///   continuation-block threading when writable-route lowering splices synthetic blocks
/// - one dedicated writable-route entry for published assignment targets
///
/// Processors may query this session for already-frozen information, but they must not rebuild
/// semantic facts or invent alternate evaluation order.
public final class FrontendBodyLoweringSession {
    private final @NotNull FunctionLoweringContext functionContext;
    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull FrontendCfgGraph graph;
    private final @NotNull LirFunctionDef function;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull SequencedMap<String, FrontendBodyLoweringSupport.CfgValueMaterialization> valueMaterializations;
    /// Value ids consumed as await operands. A coroutine call whose result is in this set is
    /// awaited (ownership moves into the await); any other coroutine call result is a discarded
    /// statement-position value that must be detached through an INTERNAL destruct right after
    /// the call (`frontend_await_implementation.md` §7 fire-and-forget).
    private final @NotNull Set<String> awaitOperandValueIds;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<FrontendCfgGraph.NodeDef, Void> cfgNodeProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<SequenceItem, Void> sequenceItemProcessors;
    private final @NotNull FrontendInsnLoweringProcessorRegistry<Expression, OpaqueExprLoweringContext> opaqueExprProcessors;
    private int boundaryMaterializationCounter;
    private int writableRouteMaterializationCounter;
    private int writableRouteBlockCounter;
    private int forLoopConstantCounter;
    private int matchHelperCounter;
    private int returnNilCounter;
    private int languageFunctionTempCounter;

    public FrontendBodyLoweringSession(
            @NotNull FunctionLoweringContext functionContext,
            @NotNull ClassRegistry classRegistry
    ) {
        this.functionContext = Objects.requireNonNull(functionContext, "functionContext must not be null");
        this.analysisData = functionContext.analysisData();
        this.graph = functionContext.requireFrontendCfgGraph();
        this.function = functionContext.targetFunction();
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.valueMaterializations = FrontendBodyLoweringSupport.collectCfgValueMaterializations(
                graph,
                analysisData,
                this.classRegistry,
                functionContext.matchBindSlots()
        );
        this.awaitOperandValueIds = collectAwaitOperandValueIds(graph);
        this.cfgNodeProcessors = FrontendCfgNodeInsnLoweringProcessors.createRegistry();
        this.sequenceItemProcessors = FrontendSequenceItemInsnLoweringProcessors.createRegistry();
        this.opaqueExprProcessors = FrontendOpaqueExprInsnLoweringProcessors.createRegistry();
    }

    public void run() {
        requireShellOnlyTarget();
        declareSelfSlotIfNeeded();
        declareSourceLocalSlots();
        declareCfgValueSlots();
        declareForLoopSlots();
        declareMatchBindSlots();
        createBlocks();
        lowerBlocks();
    }

    @NotNull LirBasicBlock lowerSequenceItem(@NotNull LirBasicBlock block, @NotNull SequenceItem item) {
        return sequenceItemProcessors.lower(this, block, item, null);
    }

    @NotNull LirBasicBlock lowerOpaqueExpression(@NotNull LirBasicBlock block, @NotNull OpaqueExprValueItem item) {
        return opaqueExprProcessors.lower(this, block, item.expression(), new OpaqueExprLoweringContext(item));
    }

    @NotNull LirBasicBlock lowerAssignmentTarget(
            @NotNull LirBasicBlock block,
            @NotNull AssignmentItem item,
            @NotNull String rhsSlotId
    ) {
        return FrontendAssignmentTargetInsnLoweringProcessors.lowerPublishedWritableRoute(this, block, item, rhsSlotId);
    }

    @NotNull FrontendBinding requireBinding(@NotNull Node useSite) {
        var binding = analysisData.symbolBindings().get(Objects.requireNonNull(useSite, "useSite must not be null"));
        if (binding == null) {
            throw new IllegalStateException("Missing published symbol binding for " + useSite.getClass().getSimpleName());
        }
        return binding;
    }

    /// Reads the published type-test RHS target fact for one `TypeTestExpression` anchor.
    ///
    /// Shared semantic owns publication; body lowering only consumes the frozen side-table entry.
    @NotNull FrontendTypeTestTarget requireTypeTestTarget(@NotNull Node typeTestAnchor) {
        var target = analysisData.typeTestTargets().get(
                Objects.requireNonNull(typeTestAnchor, "typeTestAnchor must not be null")
        );
        if (target == null) {
            throw new IllegalStateException(
                    "Missing published type-test target for " + typeTestAnchor.getClass().getSimpleName()
            );
        }
        return target;
    }

    /// Reads the frozen array/dictionary construction plan for one literal root.
    ///
    /// CFG build already required the plan; body lowering only materializes operands and emits
    /// `construct_container_literal`. Missing plan is a protocol violation, not a source diagnostic.
    @NotNull FrontendContainerLiteralPlan requireContainerLiteralPlan(@NotNull Node literalAnchor) {
        var plan = analysisData.containerLiteralPlans().get(
                Objects.requireNonNull(literalAnchor, "literalAnchor must not be null")
        );
        if (plan == null) {
            throw new IllegalStateException(
                    "Missing published container literal plan for " + literalAnchor.getClass().getSimpleName()
            );
        }
        return plan;
    }

    @NotNull ClassRegistry classRegistry() {
        return classRegistry;
    }

    /// Sequence-item processors occasionally need the enclosing lowering context, e.g. resolving
    /// a synthesized lambda shell on the owning class for the phase-F consistency check.
    @NotNull FunctionLoweringContext functionContext() {
        return functionContext;
    }

    /// The top-binding owner procedure only publishes binding kind `SELF` for explicit
    /// `SelfExpression`. Any identifier node that still carries `SELF` means some earlier
    /// publication step leaked an impossible surface into lowering, so all body-lowering entry
    /// points must reject it consistently instead of silently rewriting the identifier to `self`.
    @NotNull IllegalStateException identifierSelfBindingContractViolation(
            @NotNull IdentifierExpression identifierExpression,
            @NotNull String contractDetail
    ) {
        Objects.requireNonNull(identifierExpression, "identifierExpression must not be null");
        return new IllegalStateException(
                StringUtil.requireNonBlank(contractDetail, "contractDetail")
                        + " must use explicit SelfExpression instead of identifier binding kind SELF"
        );
    }

    /// Nullable sibling of `requireResolvedCall` for dispatch sites where a missing fact selects a
    /// different route instead of being an invariant violation (e.g. await operand classification:
    /// only call-shaped operands ever carry a call fact).
    @Nullable FrontendResolvedCall findResolvedCallOrNull(@NotNull Node callAnchor) {
        return analysisData.resolvedCalls().get(Objects.requireNonNull(callAnchor, "callAnchor must not be null"));
    }

    /// Consumes one lowering-ready published call fact.
    ///
    /// Compile gate and CFG publication already accept both exact `RESOLVED` routes and runtime-open
    /// `DYNAMIC` routes. Body lowering therefore reads the same frozen contract here instead of
    /// silently narrowing the accepted surface back to resolved-only.
    @NotNull FrontendResolvedCall requireResolvedCall(@NotNull Node callAnchor) {
        var resolvedCall = analysisData.resolvedCalls().get(Objects.requireNonNull(callAnchor, "callAnchor must not be null"));
        if (resolvedCall == null) {
            throw new IllegalStateException(
                    "Missing published resolved call for " + callAnchor.getClass().getSimpleName()
            );
        }
        if (resolvedCall.status() != FrontendCallResolutionStatus.RESOLVED
                && resolvedCall.status() != FrontendCallResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "Call anchor " + callAnchor.getClass().getSimpleName() + " is not lowering-ready: " + resolvedCall.status()
            );
        }
        return resolvedCall;
    }

    /// Consumes one lowering-ready published member fact.
    ///
    /// CFG publication already admits exact `RESOLVED` routes and runtime-open `DYNAMIC`
    /// member routes. `DYNAMIC` keeps route/provenance only; its value type is published
    /// separately through `expressionTypes()`, so this gate must not narrow the surface back
    /// to resolved-only.
    @NotNull FrontendResolvedMember requireResolvedMember(@NotNull Node memberAnchor) {
        var resolvedMember = analysisData.resolvedMembers().get(
                Objects.requireNonNull(memberAnchor, "memberAnchor must not be null")
        );
        if (resolvedMember == null) {
            throw new IllegalStateException(
                    "Missing published resolved member for " + memberAnchor.getClass().getSimpleName()
            );
        }
        if (resolvedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                && resolvedMember.status() != FrontendMemberResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "Member anchor " + memberAnchor.getClass().getSimpleName()
                            + " is not lowering-ready: "
                            + resolvedMember.status()
            );
        }
        return resolvedMember;
    }

    void requireOpaqueOperandCount(@NotNull OpaqueExprValueItem item, int expectedCount) {
        if (item.operandValueIds().size() != expectedCount) {
            throw unsupportedSequenceItem(
                    item,
                    "expected " + expectedCount + " operand value ids, but got " + item.operandValueIds().size()
            );
        }
    }

    void requireSingleSubscriptArgument(@NotNull Node anchor, @NotNull List<?> arguments) {
        if (arguments.size() != 1) {
            throw new IllegalStateException(
                    "Subscript lowering currently supports exactly one key operand for "
                            + anchor.getClass().getSimpleName()
            );
        }
    }

    @NotNull String resolveInstanceCallReceiver(@NotNull CallItem item) {
        var receiverValueId = item.receiverValueIdOrNull();
        if (receiverValueId != null) {
            return slotIdForValue(receiverValueId);
        }
        requireSelfSlot();
        return "self";
    }

    /// Materializes the current call-receiver leaf through the shared writable-route support.
    ///
    /// When the CFG has already published one concrete receiver value id, that slot is the direct
    /// receiver leaf and must be reused as-is. The writable-route payload then exists only to carry
    /// post-call reverse-commit provenance. The old direct-slot repair logic now lives entirely in
    /// publication/materialization: if some payload-backed call needs a real source slot here, its
    /// dedicated `receiverValueIdOrNull` must already be an alias-backed value id.
    /// Body lowering therefore never re-reads a payload-backed leaf on demand.
    @NotNull String materializeCallReceiverLeaf(@NotNull LirBasicBlock block, @NotNull CallItem item) {
        Objects.requireNonNull(block, "block must not be null");
        var actualItem = Objects.requireNonNull(item, "item must not be null");
        if (actualItem.writableRoutePayloadOrNull() != null) {
            if (actualItem.receiverValueIdOrNull() != null) {
                return slotIdForValue(actualItem.receiverValueIdOrNull());
            }
            throw new IllegalStateException(
                    "Payload-backed call '"
                            + actualItem.callableName()
                            + "' must publish dedicated receiverValueIdOrNull before body lowering"
            );
        }
        if (actualItem.receiverValueIdOrNull() != null) {
            return slotIdForValue(actualItem.receiverValueIdOrNull());
        }
        var receiverSlotId = resolveInstanceCallReceiver(actualItem);
        var receiverType = requireFunctionVariableType(receiverSlotId);
        var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                actualItem.anchor(),
                new FrontendWritableRouteSupport.FrontendWritableRoot("call receiver", receiverSlotId, receiverType),
                new FrontendWritableRouteSupport.DirectSlotLeaf(receiverSlotId, receiverType),
                List.of()
        );
        return FrontendWritableRouteSupport.materializeLeafRead(this, block, chain, "call_receiver");
    }

    @NotNull FrontendWritableRouteSupport.FrontendWritableAccessChain requireWritableAccessChain(
            @NotNull FrontendWritableRoutePayload payload
    ) {
        var actualPayload = Objects.requireNonNull(payload, "payload must not be null");
        return new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                actualPayload.routeAnchor(),
                materializeWritableRouteRoot(actualPayload.root()),
                materializeWritableLeaf(actualPayload),
                truncateAtStaticStorageBoundary(actualPayload)
        );
    }

    /// Materializes reverse-commit steps and truncates everything outside the innermost static
    /// storage boundary (`holder.child.values[i] = v` with static `values` still carries an
    /// instance `child` step from route promotion; `static_holder.values[i].x = v` with static
    /// `static_holder` carries an outer static property step).
    ///
    /// The innermost static commit fully terminates the mutation chain: static storage is not
    /// nested inside any owner, so outer steps only re-store unchanged carriers (statics only
    /// exist on object classes, and an outer static property's stored reference never changes when
    /// an inner element/container is mutated). Dropping them is semantically neutral (receiver/key
    /// expressions were still evaluated in CFG order for side effects) and keeps the remaining
    /// static step terminal, as `StaticPropertyCommitStep` and `StaticContainerSubscriptCommitStep`
    /// require.
    private @NotNull List<FrontendWritableRouteSupport.FrontendWritableCommitStep> truncateAtStaticStorageBoundary(
            @NotNull FrontendWritableRoutePayload payload
    ) {
        var commitSteps = payload.reverseCommitSteps().stream()
                .map(step -> materializeWritableCommitStep(payload.root(), step))
                .toList();
        // Steps are ordered outermost-first; the innermost static boundary is the real storage
        // terminal, so everything before it is dropped.
        var staticBoundaryIndex = -1;
        for (var index = 0; index < commitSteps.size(); index++) {
            if (commitSteps.get(index) instanceof FrontendWritableRouteSupport.StaticPropertyCommitStep
                    || commitSteps.get(index) instanceof FrontendWritableRouteSupport.StaticContainerSubscriptCommitStep) {
                staticBoundaryIndex = index;
            }
        }
        if (staticBoundaryIndex <= 0) {
            return commitSteps;
        }
        return commitSteps.subList(staticBoundaryIndex, commitSteps.size());
    }

    private @NotNull FrontendWritableRouteSupport.FrontendWritableRoot materializeWritableRouteRoot(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root
    ) {
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> {
                var slotId = resolveDirectWritableRootSlot(root.anchor());
                yield new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "direct slot route",
                        slotId,
                        requireFunctionVariableType(slotId)
                );
            }
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "implicit self route",
                        "self",
                        requireFunctionVariableType("self")
                );
            }
            case STATIC_CONTEXT -> new FrontendWritableRouteSupport.FrontendWritableRoot(
                    "static route",
                    null,
                    new GdObjectType(currentClassName())
            );
            case VALUE_ID -> {
                var valueId = Objects.requireNonNull(root.valueIdOrNull(), "VALUE_ID root must publish valueIdOrNull");
                yield new FrontendWritableRouteSupport.FrontendWritableRoot(
                        "value route",
                        slotIdForValue(valueId),
                        requireValueType(valueId)
                );
            }
        };
    }

    private @NotNull FrontendWritableRouteSupport.FrontendWritableLeaf materializeWritableLeaf(
            @NotNull FrontendWritableRoutePayload payload
    ) {
        var root = payload.root();
        var leaf = payload.leaf();
        var leafType = requireWritableLeafType(payload);
        return switch (leaf.kind()) {
            case DIRECT_SLOT -> new FrontendWritableRouteSupport.DirectSlotLeaf(
                    resolveDirectWritableLeafSlot(root),
                    leafType
            );
            case PROPERTY -> {
                var propertyName = Objects.requireNonNull(leaf.memberNameOrNull(), "PROPERTY leaf must publish memberNameOrNull");
                var dynamicMember = dynamicWritableMemberOrNull(leaf.anchor(), "property leaf");
                if (dynamicMember != null) {
                    yield new FrontendWritableRouteSupport.DynamicPropertyLeaf(
                            resolveWritableContainerSlot(root, leaf.containerValueIdOrNull()),
                            requireWritableContainerType(root, leaf.containerValueIdOrNull()),
                            dynamicMember.memberName(),
                            leafType
                    );
                }
                if (isStaticWritablePropertyRoute(root, leaf.anchor())) {
                    yield new FrontendWritableRouteSupport.StaticPropertyLeaf(
                            requireStaticWritableReceiverName(root, leaf.anchor()),
                            propertyName,
                            leafType
                    );
                }
                yield new FrontendWritableRouteSupport.InstancePropertyLeaf(
                        resolveWritableContainerSlot(root, leaf.containerValueIdOrNull()),
                        propertyName,
                        leafType
                );
            }
            case SUBSCRIPT -> {
                var keyValueId = leaf.operandValueIds().getFirst();
                var receiverType = requireWritableContainerType(root, leaf.containerValueIdOrNull());
                // Container provenance lives on the leaf anchor (the subscript step), not the
                // route anchor: assignment payloads re-anchor the route to the whole assignment
                // expression via `withRouteAnchor`.
                var containerFacts = resolveSubscriptContainerFacts(
                        leaf.memberNameOrNull(),
                        receiverType,
                        leaf.anchor()
                );
                yield new FrontendWritableRouteSupport.SubscriptLeaf(
                        resolveWritableContainerSlot(root, leaf.containerValueIdOrNull()),
                        receiverType,
                        leaf.memberNameOrNull(),
                        slotIdForValue(keyValueId),
                        requireValueType(keyValueId),
                        leafType,
                        containerFacts.containerSourceType(),
                        containerFacts.staticOwnerNameOrNull(),
                        containerFacts.typedInstanceContainer()
                );
            }
        };
    }

    private @NotNull FrontendWritableRouteSupport.FrontendWritableCommitStep materializeWritableCommitStep(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull FrontendWritableRoutePayload.StepDescriptor step
    ) {
        return switch (Objects.requireNonNull(step, "step must not be null").kind()) {
            case PROPERTY -> {
                var propertyName = Objects.requireNonNull(step.memberNameOrNull(), "PROPERTY step must publish memberNameOrNull");
                var dynamicMember = dynamicWritableMemberOrNull(step.anchor(), "reverse-commit property step");
                if (dynamicMember != null) {
                    yield new FrontendWritableRouteSupport.DynamicPropertyCommitStep(
                            resolveWritableContainerSlot(root, step.containerValueIdOrNull()),
                            requireWritableContainerType(root, step.containerValueIdOrNull()),
                            dynamicMember.memberName()
                    );
                }
                if (isStaticWritablePropertyRoute(root, step.anchor())) {
                    yield new FrontendWritableRouteSupport.StaticPropertyCommitStep(
                            requireStaticWritableReceiverName(root, step.anchor()),
                            propertyName
                    );
                }
                yield new FrontendWritableRouteSupport.InstancePropertyCommitStep(
                        resolveWritableContainerSlot(root, step.containerValueIdOrNull()),
                        propertyName
                );
            }
            case SUBSCRIPT -> {
                var keyValueId = step.operandValueIds().getFirst();
                // A named subscript step whose container is a static property (`obj.values[i]`
                // with static `values`) commits back to shared class storage, not the instance
                // receiver; chain binding publishes the container member on the subscript anchor.
                var containerMember = step.memberNameOrNull() != null
                        ? findSubscriptContainerMemberOrNull(step.anchor())
                        : null;
                if (containerMember != null && isStaticResolvedPropertyMember(containerMember)) {
                    yield new FrontendWritableRouteSupport.StaticContainerSubscriptCommitStep(
                            requireStaticReceiverName(containerMember.receiverType()),
                            Objects.requireNonNull(step.memberNameOrNull(), "memberNameOrNull must not be null"),
                            Objects.requireNonNull(
                                    containerMember.resultType(),
                                    "RESOLVED container property member must publish resultType"
                            ),
                            slotIdForValue(keyValueId),
                            requireValueType(keyValueId)
                    );
                }
                // Resolved non-static GDCC instance container (`obj.items[i]` with typed `items`)
                // commits back through `StorePropertyInsn` with the published container type,
                // mirroring the bare `items[i]` route; engine/dynamic containers keep the Variant
                // named route below.
                if (containerMember != null
                        && FrontendSubscriptAccessSupport.isResolvedTypedInstanceContainerMember(containerMember)) {
                    yield new FrontendWritableRouteSupport.InstanceContainerSubscriptCommitStep(
                            resolveWritableContainerSlot(root, step.containerValueIdOrNull()),
                            Objects.requireNonNull(step.memberNameOrNull(), "memberNameOrNull must not be null"),
                            Objects.requireNonNull(
                                    containerMember.resultType(),
                                    "RESOLVED container property member must publish resultType"
                            ),
                            slotIdForValue(keyValueId),
                            requireValueType(keyValueId)
                    );
                }
                yield new FrontendWritableRouteSupport.SubscriptCommitStep(
                        resolveWritableContainerSlot(root, step.containerValueIdOrNull()),
                        requireWritableContainerType(root, step.containerValueIdOrNull()),
                        step.memberNameOrNull(),
                        slotIdForValue(keyValueId),
                        requireValueType(keyValueId)
                );
            }
        };
    }

    private @NotNull String resolveDirectWritableLeafSlot(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root
    ) {
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> resolveDirectWritableRootSlot(root.anchor());
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield "self";
            }
            case VALUE_ID ->
                    slotIdForValue(Objects.requireNonNull(root.valueIdOrNull(), "VALUE_ID root must publish valueIdOrNull"));
            case STATIC_CONTEXT ->
                    throw new IllegalStateException("STATIC_CONTEXT root cannot materialize a direct-slot leaf");
        };
    }

    private @NotNull String resolveWritableContainerSlot(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @Nullable String containerValueIdOrNull
    ) {
        if (containerValueIdOrNull != null) {
            return slotIdForValue(containerValueIdOrNull);
        }
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> resolveDirectWritableRootSlot(root.anchor());
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield "self";
            }
            case VALUE_ID ->
                    slotIdForValue(Objects.requireNonNull(root.valueIdOrNull(), "VALUE_ID root must publish valueIdOrNull"));
            case STATIC_CONTEXT -> throw new IllegalStateException(
                    "STATIC_CONTEXT root requires an explicit container value id for non-property writable routes"
            );
        };
    }

    private @NotNull String resolveDirectWritableRootSlot(@NotNull Node rootAnchor) {
        return switch (Objects.requireNonNull(rootAnchor, "rootAnchor must not be null")) {
            case SelfExpression _ -> {
                requireSelfSlot();
                yield "self";
            }
            case IdentifierExpression identifierExpression -> {
                var binding = requireBinding(identifierExpression);
                yield switch (binding.kind()) {
                    case LOCAL_VAR, PARAMETER, CAPTURE -> binding.symbolName();
                    case SELF -> throw identifierSelfBindingContractViolation(
                            identifierExpression,
                            "DIRECT_SLOT writable root"
                    );
                    default -> throw new IllegalStateException(
                            "DIRECT_SLOT writable root requires a storage-backed binding, but got " + binding.kind()
                    );
                };
            }
            default -> throw new IllegalStateException(
                    "DIRECT_SLOT writable root requires IdentifierExpression or SelfExpression, but got "
                            + rootAnchor.getClass().getSimpleName()
            );
        };
    }

    private boolean isStaticWritablePropertyRoute(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull Node propertyAnchor
    ) {
        return switch (Objects.requireNonNull(propertyAnchor, "propertyAnchor must not be null")) {
            case IdentifierExpression _ -> {
                var binding = requireBinding(propertyAnchor);
                yield isStaticPropertyBinding(binding);
            }
            case AttributePropertyStep _ -> {
                var resolvedMember = requireResolvedMember(propertyAnchor);
                // `ClassName.x` arrives with a TYPE_META receiver; instance syntax (`obj.x` /
                // `self.x`, already warned via sema.static_access_via_instance) keeps an INSTANCE
                // receiver but must still target the declaring class's shared static storage.
                yield resolvedMember.receiverKind() == FrontendReceiverKind.TYPE_META
                        || isStaticResolvedPropertyMember(resolvedMember);
            }
            // Type-meta head subscript routes (`ClassName.values[i]`) encode the static container
            // property as a PROPERTY leaf/commit step anchored at the subscript step; chain binding
            // publishes the container member on that anchor (receiverKind TYPE_META).
            case AttributeSubscriptStep _ -> {
                var containerMember = findSubscriptContainerMemberOrNull(propertyAnchor);
                yield containerMember != null
                        && containerMember.receiverKind() == FrontendReceiverKind.TYPE_META
                        && isStaticResolvedPropertyMember(containerMember);
            }
            default -> root.kind() == FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT
                    && root.anchor() == propertyAnchor;
        };
    }

    private boolean isStaticResolvedPropertyMember(@NotNull FrontendResolvedMember resolvedMember) {
        return resolvedMember.bindingKind() == FrontendBindingKind.PROPERTY
                && resolvedMember.declarationSite() instanceof PropertyDef propertyDef
                && propertyDef.isStatic();
    }

    /// Returns the RESOLVED container property member published on an attribute-subscript
    /// anchor (`receiver.member[key]`). Chain binding re-anchors the internally synthesized
    /// property resolution (instance or static) on the real subscript step; a null result means
    /// the container member is runtime-dynamic and the named base operates as Variant.
    @Nullable FrontendResolvedMember findSubscriptContainerMemberOrNull(
            @NotNull Node subscriptAnchor
    ) {
        var resolvedMember = analysisData.resolvedMembers().get(
                Objects.requireNonNull(subscriptAnchor, "subscriptAnchor must not be null")
        );
        if (resolvedMember == null
                || resolvedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                || resolvedMember.bindingKind() != FrontendBindingKind.PROPERTY) {
            return null;
        }
        return resolvedMember;
    }

    /// Frozen container facts for `SubscriptLeaf` construction. `containerSourceType` always
    /// records the compile-time-known container type: the base type for plain subscripts, the
    /// published container property type for resolved named containers (instance or static), or
    /// Variant for runtime-dynamic named containers. `staticOwnerNameOrNull` is set only when the
    /// named container is a static property, redirecting the named-base scratch/writeback to the
    /// start class's shared static storage. `typedInstanceContainer` is set only when the named
    /// container is a resolved non-static GDCC instance property with a concrete declared type,
    /// selecting the typed `LoadPropertyInsn`/`StorePropertyInsn` named route instead of the
    /// Variant named route (engine properties and dynamic members stay Variant).
    record SubscriptContainerFacts(
            @NotNull GdType containerSourceType,
            @Nullable String staticOwnerNameOrNull,
            boolean typedInstanceContainer
    ) {
    }

    @NotNull SubscriptContainerFacts resolveSubscriptContainerFacts(
            @Nullable String memberNameOrNull,
            @NotNull GdType receiverType,
            @NotNull Node subscriptAnchor
    ) {
        Objects.requireNonNull(receiverType, "receiverType must not be null");
        if (memberNameOrNull == null) {
            return new SubscriptContainerFacts(receiverType, null, false);
        }
        var containerMember = findSubscriptContainerMemberOrNull(subscriptAnchor);
        if (containerMember == null) {
            return new SubscriptContainerFacts(GdVariantType.VARIANT, null, false);
        }
        return new SubscriptContainerFacts(
                Objects.requireNonNull(
                        containerMember.resultType(),
                        "RESOLVED container property member must publish resultType"
                ),
                isStaticResolvedPropertyMember(containerMember)
                        ? requireStaticReceiverName(containerMember.receiverType())
                        : null,
                FrontendSubscriptAccessSupport.isResolvedTypedInstanceContainerMember(containerMember)
        );
    }

    private @NotNull String requireStaticWritableReceiverName(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull Node propertyAnchor
    ) {
        if (root.kind() == FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT && root.anchor() == propertyAnchor) {
            return currentClassName();
        }
        return switch (Objects.requireNonNull(propertyAnchor, "propertyAnchor must not be null")) {
            case AttributePropertyStep _ -> requireStaticReceiverName(
                    requireResolvedMember(propertyAnchor).receiverType()
            );
            // `ClassName.values[i]` anchor: the start class comes from the published container
            // member's receiver type (the type-meta class), never from `currentClassName()`.
            case AttributeSubscriptStep _ -> requireStaticReceiverName(
                    requireResolvedMember(propertyAnchor).receiverType()
            );
            case IdentifierExpression _ -> currentClassName();
            default -> throw new IllegalStateException(
                    "Static writable property route requires a type-meta or static-binding anchor"
            );
        };
    }

    private @Nullable FrontendResolvedMember dynamicWritableMemberOrNull(
            @NotNull Node propertyAnchor,
            @NotNull String routeDescription
    ) {
        if (!(Objects.requireNonNull(propertyAnchor, "propertyAnchor must not be null")
                instanceof AttributePropertyStep)) {
            return null;
        }
        var resolvedMember = requireResolvedMember(propertyAnchor);
        if (resolvedMember.status() != FrontendMemberResolutionStatus.DYNAMIC) {
            return null;
        }
        // Dynamic member writes are selected only by the frozen member status. Once selected, only
        // instance receivers may enter the Variant named route; type-meta dynamic publication is drift.
        if (resolvedMember.receiverKind() != FrontendReceiverKind.INSTANCE) {
            throw new IllegalStateException(
                    "Dynamic writable "
                            + StringUtil.requireNonBlank(routeDescription, "routeDescription")
                            + " requires an instance receiver route, but got "
                            + resolvedMember.receiverKind()
            );
        }
        return resolvedMember;
    }

    private @NotNull GdType requireWritableLeafType(@NotNull FrontendWritableRoutePayload payload) {
        var published = analysisData.expressionTypes().get(payload.leaf().anchor());
        if (published != null && published.publishedType() != null) {
            return published.publishedType();
        }
        if (payload.leaf().kind() == FrontendWritableRoutePayload.LeafKind.PROPERTY
                && dynamicWritableMemberOrNull(payload.leaf().anchor(), "property leaf") != null) {
            // Assignment targets do not always publish the final left-value property as an
            // expression value. Once the frozen member fact says DYNAMIC, the writable surface is
            // the same runtime-open Variant member slot used by assignment semantic checking.
            return GdVariantType.VARIANT;
        }
        return switch (payload.leaf().kind()) {
            case DIRECT_SLOT -> requireWritableDirectLeafType(payload.root());
            case PROPERTY -> requireWritablePropertyLeafType(payload.leaf().anchor());
            case SUBSCRIPT -> requireWritableSubscriptLeafType(payload.root(), payload.leaf());
        };
    }

    private @NotNull GdType requireWritableDirectLeafType(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root
    ) {
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> requireFunctionVariableType(resolveDirectWritableRootSlot(root.anchor()));
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield requireFunctionVariableType("self");
            }
            case VALUE_ID -> requireValueType(Objects.requireNonNull(
                    root.valueIdOrNull(),
                    "VALUE_ID root must publish valueIdOrNull"
            ));
            case STATIC_CONTEXT -> throw new IllegalStateException(
                    "STATIC_CONTEXT root cannot materialize a direct-slot leaf type"
            );
        };
    }

    private @NotNull GdType requireWritablePropertyLeafType(@NotNull Node propertyAnchor) {
        return switch (Objects.requireNonNull(propertyAnchor, "propertyAnchor must not be null")) {
            case IdentifierExpression _ -> requireWritableBindingStorageType(requireBinding(propertyAnchor));
            case AttributePropertyStep _ -> {
                var resolvedMember = requireResolvedMember(propertyAnchor);
                if (resolvedMember.status() == FrontendMemberResolutionStatus.DYNAMIC) {
                    throw new IllegalStateException(
                            "Dynamic writable property leaf type must come from expressionTypes(), not resolvedMembers()"
                    );
                }
                yield Objects.requireNonNull(
                        resolvedMember.resultType(),
                        "Resolved property leaf must publish resultType"
                );
            }
            default -> throw new IllegalStateException(
                    "Writable property leaf type requires IdentifierExpression or AttributePropertyStep anchor"
            );
        };
    }

    private @NotNull GdType requireWritableSubscriptLeafType(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @NotNull FrontendWritableRoutePayload.LeafDescriptor leaf
    ) {
        var containerType = requireWritableContainerType(root, leaf.containerValueIdOrNull());
        return switch (containerType) {
            case GdVariantType _ -> GdVariantType.VARIANT;
            case GdContainerType container -> container.getValueType();
            default -> throw new IllegalStateException(
                    "Writable subscript leaf requires container-family or Variant carrier, but got "
                            + containerType.getTypeName()
            );
        };
    }

    private @NotNull GdType requireWritableContainerType(
            @NotNull FrontendWritableRoutePayload.RootDescriptor root,
            @Nullable String containerValueIdOrNull
    ) {
        if (containerValueIdOrNull != null) {
            return requireValueType(containerValueIdOrNull);
        }
        return switch (Objects.requireNonNull(root, "root must not be null").kind()) {
            case DIRECT_SLOT -> requireFunctionVariableType(resolveDirectWritableRootSlot(root.anchor()));
            case SELF_CONTEXT -> {
                requireSelfSlot();
                yield requireFunctionVariableType("self");
            }
            case STATIC_CONTEXT -> throw new IllegalStateException(
                    "STATIC_CONTEXT root requires an explicit container value id for non-property writable routes"
            );
            case VALUE_ID -> requireValueType(Objects.requireNonNull(
                    root.valueIdOrNull(),
                    "VALUE_ID root must publish valueIdOrNull"
            ));
        };
    }

    private @NotNull GdType requireWritableBindingStorageType(@NotNull FrontendBinding binding) {
        return switch (Objects.requireNonNull(binding, "binding must not be null").kind()) {
            case LOCAL_VAR, PARAMETER, CAPTURE -> requireFunctionVariableType(binding.symbolName());
            case SELF -> throw new IllegalStateException(
                    "Writable binding storage lookup must use explicit SelfExpression instead of binding kind SELF"
            );
            case PROPERTY -> switch (Objects.requireNonNull(
                    binding.declarationSite(),
                    "Property binding must carry declaration metadata"
            )) {
                case PropertyDef propertyDef -> propertyDef.getType();
                default -> throw new IllegalStateException(
                        "Property binding '" + binding.symbolName() + "' does not carry property metadata"
                );
            };
            default -> throw new IllegalStateException(
                    "Binding '" + binding.symbolName() + "' is not backed by writable storage"
            );
        };
    }

    void requireSelfSlot() {
        if (function.getVariableById("self") == null) {
            throw new IllegalStateException(
                    describeContext() + " requires an implicit self receiver slot"
            );
        }
    }

    /// Signal/callable construction must keep the canonical `self` slot when the CFG receiver
    /// is a `SelfExpression`. Opaque self reads copy into a temp, but that receiver is still
    /// treated as always-live `self` and must not emit `AssertObjectLiveInsn`.
    @NotNull String requireLiveObjectReceiverSlotId(@NotNull String receiverValueId) {
        if (isCanonicalSelfValue(receiverValueId)) {
            requireSelfSlot();
            return "self";
        }
        return slotIdForValue(receiverValueId);
    }

    void emitAssertObjectLiveIfNeeded(@NotNull LirBasicBlock block, @NotNull String receiverSlotId) {
        if (receiverSlotId.equals("self")) {
            return;
        }
        if (requireFunctionVariableType(receiverSlotId) instanceof GdObjectType objectType
                && classRegistry.getRefCountedStatus(objectType) != RefCountedStatus.YES) {
            block.appendNonTerminatorInstruction(new AssertObjectLiveInsn(receiverSlotId));
        }
    }

    /// Declares one function variable slot or verifies an existing declaration's type.
    ///
    /// Public so the shared truthiness helper in `FrontendBodyLoweringSupport` (parent package)
    /// can declare its `cfg_cond_*` conversion slots; body processors inside this package use the
    /// same entry point instead of touching the function variable table directly.
    public void ensureVariable(@NotNull String variableId, @NotNull GdType expectedType) {
        var existing = function.getVariableById(variableId);
        if (existing == null) {
            function.createAndAddVariable(variableId, expectedType);
            return;
        }
        if (!existing.type().equals(expectedType)) {
            throw new IllegalStateException(
                    "Variable '" + variableId + "' already exists with type "
                            + existing.type().getTypeName()
                            + ", expected "
                            + expectedType.getTypeName()
            );
        }
    }

    @NotNull LirBasicBlock requireBlock(@NotNull String blockId) {
        var block = function.getBasicBlock(blockId);
        if (block == null) {
            throw new IllegalStateException("LIR basic block has not been materialized: " + blockId);
        }
        return block;
    }

    @NotNull GdType requireValueType(@NotNull String valueId) {
        var materialization = requireValueMaterialization(valueId);
        return materialization.type();
    }

    private boolean isCanonicalSelfValue(@NotNull String valueId) {
        for (var node : graph.nodes().values()) {
            if (!(node instanceof FrontendCfgGraph.SequenceNode sequenceNode)) {
                continue;
            }
            for (var item : sequenceNode.items()) {
                if (item instanceof OpaqueExprValueItem opaque
                        && valueId.equals(opaque.resultValueId())
                        && opaque.expression() instanceof SelfExpression) {
                    return true;
                }
            }
        }
        return false;
    }

    /// Resolves the slot that actually backs one published value id, honoring the published
    /// materialization kind (`cfg_tmp_*` temp, `cfg_merge_*` merge, coroutine state, or direct
    /// source-slot alias).
    ///
    /// Public so the shared truthiness helper in `FrontendBodyLoweringSupport` (parent package)
    /// can read merge-backed assert conditions (`assert(a and b)`, ternary results) from their
    /// real `cfg_merge_*` slot instead of a never-written `cfg_tmp_*`. Branch conditions stay
    /// TEMP_SLOT by the condition-context contract, so this resolves to their `cfg_tmp_*` id.
    public @NotNull String slotIdForValue(@NotNull String valueId) {
        var materialization = requireValueMaterialization(valueId);
        return switch (materialization.kind()) {
            case TEMP_SLOT -> FrontendBodyLoweringSupport.cfgTempSlotId(valueId);
            case MERGE_SLOT -> FrontendBodyLoweringSupport.mergeSlotId(valueId);
            case CORO_STATE_SLOT -> FrontendBodyLoweringSupport.coroStateSlotId(valueId);
            case SOURCE_SLOT_ALIAS -> resolveDirectWritableRootSlot(Objects.requireNonNull(
                    materialization.aliasSourceAnchorOrNull(),
                    "SOURCE_SLOT_ALIAS materialization must carry aliasSourceAnchorOrNull"
            ));
        };
    }

    /// Whether the given value id is consumed as an await operand anywhere in this graph.
    boolean isAwaitOperandValue(@NotNull String valueId) {
        return awaitOperandValueIds.contains(valueId);
    }

    /// Whether the published exact call at `callAnchor` targets a sema-marked coroutine. Pure
    /// consumption of the frozen side table; lowering never re-derives coroutine-ness.
    boolean isCoroutineCall(@NotNull Node callAnchor) {
        return analysisData.isPublishedCoroutineCall(callAnchor);
    }

    /// Whether the lowering target function itself is sema-marked as a coroutine. Await
    /// instructions are only legal inside such functions (the backend independently enforces this).
    boolean isTargetFunctionCoroutine() {
        return analysisData.coroutineFunctions().contains(function);
    }

    private static @NotNull Set<String> collectAwaitOperandValueIds(@NotNull FrontendCfgGraph graph) {
        var valueIds = new HashSet<String>();
        for (var node : graph.nodes().values()) {
            if (!(node instanceof FrontendCfgGraph.SequenceNode sequenceNode)) {
                continue;
            }
            for (var item : sequenceNode.items()) {
                if (item instanceof AwaitItem awaitItem && awaitItem.operandValueIdOrNull() != null) {
                    valueIds.add(awaitItem.operandValueIdOrNull());
                }
            }
        }
        return valueIds;
    }

    @NotNull String resultSlotId(@NotNull OpaqueExprValueItem item) {
        return FrontendBodyLoweringSupport.cfgTempSlotId(item.resultValueId());
    }

    @NotNull GdType requireSourceLocalSlotType(@NotNull VariableDeclaration declaration) {
        return FrontendBodyLoweringSupport.requireSourceLocalSlotType(analysisData, declaration);
    }

    /// Returns the validated source-facing iterator slot for one compile-ready `for-in` loop.
    ///
    /// The get processor consumes this to learn the final exposed iterator type. A missing artifact
    /// means CFG build never published the source slot, so the lookup fails fast instead of letting a
    /// processor invent a slot id or type.
    @NotNull FrontendMatchBindSlot requireMatchBindSlot(@NotNull PatternBindingExpression declaration) {
        var bindSlot = functionContext.matchBindSlotOrNull(
                Objects.requireNonNull(declaration, "declaration must not be null")
        );
        if (bindSlot == null) {
            throw new IllegalStateException(
                    "Missing published match bind slot for PatternBindingExpression at " + declaration.range()
            );
        }
        return bindSlot;
    }

    /// Allocates one match-lowering helper temp (`cfg_match_<purpose>_<n>`).
    @NotNull String allocateMatchHelperTemp(@NotNull String purpose, @NotNull GdType type) {
        var slotId = "cfg_match_"
                + StringUtil.requireNonBlank(purpose, "purpose")
                + "_"
                + matchHelperCounter++;
        ensureVariable(slotId, Objects.requireNonNull(type, "type must not be null"));
        return slotId;
    }

    /// Allocates one Variant nil temp (`cfg_return_nil_<n>`) for a bare return stop of a
    /// Variant-returning callable (implicit fallthrough or explicit `return` without a value).
    @NotNull String allocateReturnNilTemp() {
        var slotId = "cfg_return_nil_" + returnNilCounter++;
        ensureVariable(slotId, GdVariantType.VARIANT);
        return slotId;
    }

    /// Returns the already-Variant slot, or packs the source into a match helper temp.
    @NotNull String requireVariantSlot(
            @NotNull LirBasicBlock block,
            @NotNull String valueId,
            @NotNull String purpose
    ) {
        var sourceSlotId = slotIdForValue(valueId);
        var sourceType = requireValueType(valueId);
        if (sourceType instanceof GdVariantType) {
            return sourceSlotId;
        }
        var variantSlotId = allocateMatchHelperTemp(purpose, GdVariantType.VARIANT);
        block.appendNonTerminatorInstruction(new PackVariantInsn(variantSlotId, sourceSlotId));
        return variantSlotId;
    }

    @NotNull FrontendForSourceIteratorSlot requireForSourceIteratorSlot(@NotNull ForStatement statement) {
        var sourceSlot = functionContext.forSourceIteratorSlotOrNull(
                Objects.requireNonNull(statement, "statement must not be null")
        );
        if (sourceSlot == null) {
            throw new IllegalStateException(
                    "Missing published for-in source iterator slot for ForStatement at " + statement.range()
            );
        }
        return sourceSlot;
    }

    @NotNull GdType requireFunctionVariableType(@NotNull String variableId) {
        var variable = function.getVariableById(StringUtil.requireNonBlank(variableId, "variableId"));
        if (variable == null) {
            throw new IllegalStateException("Missing lowered function variable '" + variableId + "'");
        }
        return variable.type();
    }

    @NotNull LirFunctionDef targetFunction() {
        return function;
    }

    /// Materializes one ordinary frontend typed boundary by re-deriving the matrix decision.
    ///
    /// Prefer the overload that accepts a frozen
    /// [FrontendVariantBoundaryCompatibility.Decision] when the consumer already published that
    /// decision (e.g. container-literal plan operands). The long-form contract lives in
    /// `doc/module_impl/frontend/frontend_lowering_(un)pack_implementation.md`.
    @NotNull String materializeFrontendBoundaryValue(
            @NotNull LirBasicBlock block,
            @NotNull String sourceSlotId,
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull String boundaryUse
    ) {
        Objects.requireNonNull(block, "block must not be null");
        var source = Objects.requireNonNull(sourceType, "sourceType must not be null");
        var target = Objects.requireNonNull(targetType, "targetType must not be null");
        return materializeFrontendBoundaryValue(
                block,
                sourceSlotId,
                source,
                target,
                FrontendVariantBoundaryCompatibility.determineFrontendBoundaryDecision(
                        classRegistry,
                        source,
                        target
                ),
                boundaryUse
        );
    }

    /// Materializes one ordinary boundary using a **frozen** compatibility decision.
    ///
    /// Used when semantic/CFG already published the decision (container-literal plan, future call
    /// argument plans). Lowering must not re-query the matrix so LIR stays isomorphic to the plan.
    @NotNull String materializeFrontendBoundaryValue(
            @NotNull LirBasicBlock block,
            @NotNull String sourceSlotId,
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull FrontendVariantBoundaryCompatibility.Decision decision,
            @NotNull String boundaryUse
    ) {
        Objects.requireNonNull(block, "block must not be null");
        var sourceSlot = StringUtil.requireNonBlank(sourceSlotId, "sourceSlotId");
        var source = Objects.requireNonNull(sourceType, "sourceType must not be null");
        var target = Objects.requireNonNull(targetType, "targetType must not be null");
        var frozenDecision = Objects.requireNonNull(decision, "decision must not be null");
        var use = StringUtil.requireNonBlank(boundaryUse, "boundaryUse");
        if (source instanceof GdVoidType) {
            throw new IllegalStateException(
                    "Frontend boundary '"
                            + use
                            + "' must not materialize source type void; statement-position RESOLVED(void) calls must omit result slots, and value-required void calls should have failed before body lowering"
            );
        }
        if (target instanceof GdVoidType) {
            throw new IllegalStateException(
                    "Frontend boundary '"
                            + use
                            + "' must not materialize into target type void; value-required lowering sites must not request a concrete slot for void"
            );
        }
        if (source instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend boundary source '"
                            + use
                            + "': "
                            + compilerOnlyType.getTypeName()
            );
        }
        if (target instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "compiler-only type leaked into frontend boundary target '"
                            + use
                            + "': "
                            + compilerOnlyType.getTypeName()
            );
        }
        return switch (frozenDecision) {
            case ALLOW_DIRECT -> sourceSlot;
            case ALLOW_WITH_PACK -> {
                var packedSlotId = nextBoundaryMaterializationSlotId(use, "pack");
                ensureVariable(packedSlotId, GdVariantType.VARIANT);
                block.appendNonTerminatorInstruction(new PackVariantInsn(packedSlotId, sourceSlot));
                yield packedSlotId;
            }
            case ALLOW_WITH_UNPACK -> {
                var unpackedSlotId = nextBoundaryMaterializationSlotId(use, "unpack");
                ensureVariable(unpackedSlotId, target);
                block.appendNonTerminatorInstruction(new UnpackVariantInsn(unpackedSlotId, sourceSlot));
                yield unpackedSlotId;
            }
            case ALLOW_WITH_LITERAL_NULL -> {
                var nullSlotId = nextBoundaryMaterializationSlotId(use, "null_object");
                ensureVariable(nullSlotId, target);
                block.appendNonTerminatorInstruction(new LiteralNullInsn(nullSlotId));
                yield nullSlotId;
            }
            case ALLOW_WITH_INTRINSIC_CAST -> materializeIntrinsicCast(block, sourceSlot, source, target, use);
            case ALLOW_WITH_BUILTIN_CONSTRUCTOR ->
                    materializeBuiltinConstructorBoundary(block, sourceSlot, target, use);
            case REJECT -> throw new IllegalStateException(
                    "Frontend boundary '"
                            + use
                            + "' rejects source type '"
                            + source.getTypeName()
                            + "' for target type '"
                            + target.getTypeName()
                            + "'"
            );
        };
    }

    /// Materializes a frontend-owned builtin-constructor boundary into a target-typed temp.
    ///
    /// This route is for ordinary typed-boundary conversions that already have exact Godot builtin
    /// constructor metadata, such as `String <-> StringName`. The backend constructor matcher stays
    /// exact; lowering makes the conversion explicit here instead of teaching each consumer or the
    /// backend to infer a widening rule.
    private @NotNull String materializeBuiltinConstructorBoundary(
            @NotNull LirBasicBlock block,
            @NotNull String sourceSlotId,
            @NotNull GdType targetType,
            @NotNull String boundaryUse
    ) {
        var constructedSlotId = nextBoundaryMaterializationSlotId(boundaryUse, "builtin_constructor");
        ensureVariable(constructedSlotId, targetType);
        block.appendNonTerminatorInstruction(new ConstructBuiltinInsn(
                constructedSlotId,
                List.of(new LirInstruction.VariableOperand(sourceSlotId))
        ));
        return constructedSlotId;
    }

    /// Materializes an intrinsic-cast ordinary boundary into a backend-owned intrinsic call.
    ///
    /// Usage:
    /// - only call this from `materializeFrontendBoundaryValue(...)` after the shared frontend
    ///   boundary decision has returned `ALLOW_WITH_INTRINSIC_CAST`
    /// - pass the already-lowered source slot and the published source/target types for that
    ///   boundary
    ///
    /// Supported routes:
    /// - `int -> float` becomes `call_intrinsic "c_int_to_float" $seed`
    /// - `Vector3i -> Vector3` becomes `call_intrinsic "c_vector3i_to_vector3" $seed`
    ///
    /// This helper deliberately fail-fast guards the lowered pairs so later intrinsic casts cannot
    /// silently reuse an existing route.
    private @NotNull String materializeIntrinsicCast(
            @NotNull LirBasicBlock block,
            @NotNull String sourceSlotId,
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull String boundaryUse
    ) {
        var intrinsicName = requireIntrinsicCastName(sourceType, targetType, boundaryUse);
        var castedSlotId = nextBoundaryMaterializationSlotId(boundaryUse, "intrinsic_cast");
        ensureVariable(castedSlotId, targetType);
        block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                castedSlotId,
                intrinsicName,
                List.of(new LirInstruction.VariableOperand(sourceSlotId))
        ));
        return castedSlotId;
    }

    private static @NotNull String requireIntrinsicCastName(
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull String boundaryUse
    ) {
        if (sourceType instanceof GdIntType && targetType instanceof GdFloatType) {
            return "c_int_to_float";
        }
        if (
                sourceType instanceof GdIntVectorType sourceVector
                        && targetType instanceof GdFloatVectorType targetVector
                        && sourceVector.getDimension() == targetVector.getDimension()
        ) {
            return switch (sourceVector.getDimension()) {
                case 2 -> "c_vector2i_to_vector2";
                case 3 -> "c_vector3i_to_vector3";
                case 4 -> "c_vector4i_to_vector4";
                default -> throw unsupportedIntrinsicCast(sourceType, targetType, boundaryUse);
            };
        }
        throw unsupportedIntrinsicCast(sourceType, targetType, boundaryUse);
    }

    private static @NotNull IllegalStateException unsupportedIntrinsicCast(
            @NotNull GdType sourceType,
            @NotNull GdType targetType,
            @NotNull String boundaryUse
    ) {
        return new IllegalStateException(
                "Frontend boundary '"
                        + boundaryUse
                        + "' requested unsupported intrinsic cast from '"
                        + sourceType.getTypeName()
                        + "' to '"
                        + targetType.getTypeName()
                        + "'"
        );
    }

    /// Materializes a subscript key/index before body lowering chooses the final access instruction.
    ///
    /// Usage:
    /// - `FrontendWritableRouteSupport` calls this for subscript reads, direct writes, and reverse
    ///   commits
    /// - callers pass the evaluated key slot plus the static receiver/key types published by sema
    /// - the returned slot/access kind must be consumed together; recomputing access kind from the
    ///   original source key type would reintroduce stale `GENERIC` routes
    ///
    /// Examples:
    /// - `Dictionary[float, V]` with an `int` key returns a fresh `float` key slot and `KEYED`
    /// - `Array[T]` with a `Variant` index returns an unpacked `int` index slot and `INDEXED`
    /// - `Dictionary[Variant, V]` with a `String` key returns a packed `Variant` key slot and
    ///   `GENERIC`
    @NotNull MaterializedSubscriptKey materializeSubscriptKey(
            @NotNull LirBasicBlock block,
            @NotNull String sourceKeySlotId,
            @NotNull GdType sourceKeyType,
            @NotNull GdType receiverType,
            @Nullable String memberNameOrNull,
            @NotNull String boundaryUse
    ) {
        var effectiveReceiverType = memberNameOrNull == null ? receiverType : GdVariantType.VARIANT;
        if (effectiveReceiverType instanceof GdContainerType containerType) {
            var materializedSlotId = materializeFrontendBoundaryValue(
                    block,
                    sourceKeySlotId,
                    sourceKeyType,
                    containerType.getKeyType(),
                    boundaryUse
            );
            return new MaterializedSubscriptKey(
                    materializedSlotId,
                    containerType.getKeyType(),
                    FrontendSubscriptAccessSupport.determineAccessKind(effectiveReceiverType, containerType.getKeyType())
            );
        }
        return new MaterializedSubscriptKey(
                sourceKeySlotId,
                sourceKeyType,
                FrontendSubscriptAccessSupport.determineAccessKind(effectiveReceiverType, sourceKeyType)
        );
    }

    /// Materializes call operands against the already-published route contract.
    ///
    /// Exact member-call routes prefer the already-published normalized callable boundary so lowering
    /// does not rebuild parameter types from raw metadata. This keeps the selected callable on a
    /// single publication/single consumption path across sema and body lowering. Routes without an
    /// exact published boundary, such as bare-call fallback and constructor-specific paths, keep
    /// using callable signature metadata until they publish the same boundary plan shape. `DYNAMIC`
    /// calls intentionally bypass any exact signature lookup and forward their already-evaluated
    /// operand slots unchanged.
    @NotNull List<LirInstruction.Operand> materializeCallArguments(
            @NotNull LirBasicBlock block,
            @NotNull CallItem item,
            @NotNull FrontendResolvedCall resolvedCall
    ) {
        Objects.requireNonNull(block, "block must not be null");
        Objects.requireNonNull(item, "item must not be null");
        var argumentValueIds = item.argumentValueIds();
        if (argumentValueIds.isEmpty()) {
            return List.of();
        }
        if (resolvedCall.status() == FrontendCallResolutionStatus.DYNAMIC) {
            return argumentValueIds.stream()
                    .map(this::slotIdForValue)
                    .<LirInstruction.Operand>map(LirInstruction.VariableOperand::new)
                    .toList();
        }
        var boundaryPlan = requireCallArgumentBoundaryPlan(item, resolvedCall);
        if (!boundaryPlan.isVararg() && argumentValueIds.size() > boundaryPlan.fixedParameterTypes().size()) {
            throw new IllegalStateException(
                    "Resolved call '" + resolvedCall.callableName() + "' provides "
                            + argumentValueIds.size()
                            + " arguments for a non-vararg signature with "
                            + boundaryPlan.fixedParameterTypes().size()
                            + " fixed parameters"
            );
        }

        var operands = new ArrayList<LirInstruction.Operand>(argumentValueIds.size());
        var fixedPrefixCount = Math.min(argumentValueIds.size(), boundaryPlan.fixedParameterTypes().size());
        for (var index = 0; index < fixedPrefixCount; index++) {
            var argumentValueId = argumentValueIds.get(index);
            var materializedSlotId = materializeFrontendBoundaryValue(
                    block,
                    slotIdForValue(argumentValueId),
                    requireValueType(argumentValueId),
                    boundaryPlan.fixedParameterTypes().get(index),
                    "call_fixed_" + index
            );
            operands.add(new LirInstruction.VariableOperand(materializedSlotId));
        }
        for (var index = fixedPrefixCount; index < argumentValueIds.size(); index++) {
            var argumentValueId = argumentValueIds.get(index);
            var materializedSlotId = materializeFrontendBoundaryValue(
                    block,
                    slotIdForValue(argumentValueId),
                    requireValueType(argumentValueId),
                    GdVariantType.VARIANT,
                    "call_vararg_" + index
            );
            operands.add(new LirInstruction.VariableOperand(materializedSlotId));
        }
        return List.copyOf(operands);
    }

    /// Property bindings always carry the skeleton-produced `PropertyDef` as declaration site
    /// (never the AST `VariableDeclaration`), so staticness must be read from the property model.
    boolean isStaticPropertyBinding(@NotNull FrontendBinding binding) {
        return binding.kind() == FrontendBindingKind.PROPERTY
                && binding.declarationSite() instanceof PropertyDef propertyDef
                && propertyDef.isStatic();
    }

    @NotNull String currentClassName() {
        return functionContext.owningClass().getName();
    }

    /// Resolves the class that actually declared `functionName`, starting at `startClassName`.
    @NotNull String requireDeclaringStaticOwnerName(@NotNull String startClassName, @NotNull String functionName) {
        var lookup = classRegistry.findStaticFunctionInHierarchy(startClassName, functionName);
        if (lookup == null) {
            throw new IllegalStateException(
                    "standalone static method-reference '" + startClassName + "." + functionName
                            + "' is not a generated static function"
            );
        }
        return lookup.ownerClass().getName();
    }

    @NotNull String requireClassName(@Nullable GdType receiverType) {
        return switch (Objects.requireNonNull(receiverType, "receiverType must not be null")) {
            case GdObjectType(var className) -> className;
            default -> throw new IllegalStateException(
                    "Static receiver type must be an object/class type, but was " + receiverType.getTypeName()
            );
        };
    }

    @NotNull String requireStaticReceiverName(@Nullable GdType receiverType) {
        Objects.requireNonNull(receiverType, "receiverType must not be null");
        if (receiverType instanceof GdObjectType(var className)) {
            return className;
        }
        var builtinTypeName = receiverType.getTypeName();
        if (classRegistry.findBuiltinClass(builtinTypeName) != null) {
            return builtinTypeName;
        }
        throw new IllegalStateException(
                "Static receiver type must be an engine/script class or builtin type, but was "
                        + receiverType.getTypeName()
        );
    }

    void checkSingletonBindingType(@NotNull FrontendBinding binding) {
        var singletonType = classRegistry.findSingletonType(binding.symbolName());
        if (singletonType == null) {
            throw new IllegalStateException(
                    "Published singleton binding '" + binding.symbolName()
                            + "' is missing registry-validated object metadata"
            );
        }
    }

    /// Allocates one body-local temp owned by the synthetic GDScript language function lowering
    /// (the `load`/`preload` rewrite to the `ResourceLoader` singleton call pair). Kept separate
    /// from published CFG value ids and from writable-route scratch slots.
    ///
    /// The `cfg_lang_fn_*` prefix is a legal GDScript identifier, so a source-level variable could
    /// occupy the same name (source locals keep their declared name verbatim); the counter loop
    /// skips occupied ids instead of reusing them via `ensureVariable`, which would otherwise
    /// either crash on a type mismatch or silently let the rewrite overwrite a user variable.
    @NotNull String allocateGdScriptLanguageFunctionTemp(@NotNull String purpose, @NotNull GdType type) {
        var tempType = Objects.requireNonNull(type, "type must not be null");
        var prefix = "cfg_lang_fn_" + StringUtil.requireNonBlank(purpose, "purpose") + "_";
        while (true) {
            var slotId = prefix + languageFunctionTempCounter++;
            if (function.getVariableById(slotId) != null) {
                continue;
            }
            function.createAndAddVariable(slotId, tempType);
            return slotId;
        }
    }

    /// Allocates one body-local helper temp owned by writable-route lowering.
    ///
    /// Ordinary CFG value slots continue to use `cfgTempSlotId(...)` / `mergeSlotId(...)`. This
    /// helper is reserved for support-local scratch values such as named-member intermediates and
    /// leaf-read temps so those scratch slots stay clearly separated from published CFG value ids.
    @NotNull String allocateWritableRouteTemp(
            @NotNull String purpose,
            @NotNull GdType type
    ) {
        var slotId = "cfg_writable_"
                + StringUtil.requireNonBlank(purpose, "purpose")
                + "_"
                + writableRouteMaterializationCounter++;
        ensureVariable(slotId, Objects.requireNonNull(type, "type must not be null"));
        return slotId;
    }

    /// Materializes one lowering-owned integer constant into a fresh temp slot.
    ///
    /// The range init intrinsic takes its `(start, end, step)` bounds as ordinary int locals, so the
    /// implicit `0` start and `1` step used by the single-operand forms (INT_SHORTHAND stop and
    /// `range(stop)`) must be realized as real LIR variables here instead of being passed as raw
    /// literals, which intrinsic argument positions do not accept.
    @NotNull String materializeForLoopIntConstant(@NotNull LirBasicBlock block, long value) {
        var slotId = "cfg_for_range_const_" + forLoopConstantCounter++;
        ensureVariable(slotId, GdIntType.INT);
        block.appendNonTerminatorInstruction(new LiteralIntInsn(slotId, value));
        return slotId;
    }

    /// Allocates one synthetic basic block owned by writable-route lowering.
    ///
    /// The writable-route runtime gate path may need to split the current lexical block into
    /// `apply` / `skip` / `continue` regions. Those blocks are not frontend CFG nodes; they are
    /// body-lowering artifacts that must still live in the target LIR function so later validation
    /// and codegen can see the real branch structure.
    @NotNull LirBasicBlock createWritableRouteBlock(@NotNull String purpose) {
        var blockId = "cfg_writable_bb_"
                + StringUtil.requireNonBlank(purpose, "purpose")
                + "_"
                + writableRouteBlockCounter++;
        var block = new LirBasicBlock(blockId);
        function.addBasicBlock(block);
        return block;
    }

    @NotNull IllegalStateException unsupportedSequenceItem(@NotNull SequenceItem item, @NotNull String detail) {
        return new IllegalStateException(
                item.getClass().getSimpleName()
                        + " is not supported by frontend body lowering yet: "
                        + detail
        );
    }

    /// Emits the single LIR instruction for one explicit `as` cast.
    ///
    /// Decision source is only {@link ExplicitCastSupport}; ordinary implicit-boundary helpers are not
    /// consulted. Target type text for {@link BuiltinCastInsn}/{@link ObjectCastInsn} comes from the
    /// already-published {@link GdType}, never from a re-parse of the AST {@code TypeRef}.
    void emitExplicitCast(
            @NotNull LirBasicBlock block,
            @NotNull CastItem item,
            @NotNull String resultSlotId,
            @NotNull String sourceSlotId,
            @NotNull GdType sourceType,
            @NotNull GdType targetType
    ) {
        var decision = ExplicitCastSupport.classify(classRegistry, sourceType, targetType);
        switch (decision) {
            case IDENTITY, OBJECT_UPCAST -> block.appendNonTerminatorInstruction(
                    new AssignInsn(resultSlotId, sourceSlotId)
            );
            case PACK_TO_VARIANT -> block.appendNonTerminatorInstruction(
                    new PackVariantInsn(resultSlotId, sourceSlotId)
            );
            case BUILTIN_RUNTIME_CAST -> block.appendNonTerminatorInstruction(
                    new BuiltinCastInsn(resultSlotId, targetType.getTypeName(), sourceSlotId)
            );
            case OBJECT_RUNTIME_CAST -> {
                // Nil/Variant/downcast: keep one ObjectCastInsn so failure always uses the shared
                // canonical-null contract (no literal-null bypass).
                if (!(targetType instanceof GdObjectType)) {
                    throw unsupportedSequenceItem(
                            item,
                            "OBJECT_RUNTIME_CAST requires object target type, but was "
                                    + targetType.getTypeName()
                    );
                }
                block.appendNonTerminatorInstruction(new ObjectCastInsn(
                        resultSlotId,
                        requireClassName(targetType),
                        sourceSlotId
                ));
            }
            case INVALID -> throw unsupportedSequenceItem(
                    item,
                    "explicit cast is statically invalid from "
                            + sourceType.getTypeName()
                            + " to "
                            + targetType.getTypeName()
            );
        }
    }

    private void requireShellOnlyTarget() {
        if (function.getBasicBlockCount() != 0 || !function.getEntryBlockId().isEmpty()) {
            throw new IllegalStateException(describeContext() + " must remain shell-only before body lowering");
        }
    }

    private @NotNull String describeContext() {
        return "Function lowering context "
                + functionContext.kind()
                + " "
                + functionContext.owningClass().getName()
                + "."
                + function.getName();
    }

    private void declareSelfSlotIfNeeded() {
        if (function.isStatic()) {
            return;
        }
        ensureVariable("self", new GdObjectType(currentClassName()));
    }

    private void declareSourceLocalSlots() {
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (!(item instanceof LocalDeclarationItem localDeclarationItem)) {
                    continue;
                }
                var declaration = localDeclarationItem.declaration();
                var slotType = FrontendBodyLoweringSupport.requireSourceLocalSlotType(analysisData, declaration);
                ensureVariable(FrontendBodyLoweringSupport.sourceLocalSlotId(declaration), slotType);
            }
        }
    }

    private void declareCfgValueSlots() {
        for (var entry : valueMaterializations.entrySet()) {
            var valueId = entry.getKey();
            var materialization = entry.getValue();
            switch (materialization.kind()) {
                case TEMP_SLOT ->
                        ensureVariable(FrontendBodyLoweringSupport.cfgTempSlotId(valueId), materialization.type());
                case MERGE_SLOT ->
                        ensureVariable(FrontendBodyLoweringSupport.mergeSlotId(valueId), materialization.type());
                case CORO_STATE_SLOT ->
                        ensureVariable(FrontendBodyLoweringSupport.coroStateSlotId(valueId), materialization.type());
                case SOURCE_SLOT_ALIAS -> {
                    // Alias-backed values intentionally reuse an existing trusted source slot instead of
                    // declaring a second `cfg_tmp_*` variable that call lowering would never truly consume.
                }
            }
        }
    }

    /// Predeclares the for-in lowering-owned locals before any block is materialized.
    ///
    /// Each compile-ready `for-in` loop carries two registries published alongside the CFG graph: the
    /// hidden iterator state slot (plus its distinct next-commit temp) typed as the route's
    /// compiler-only state type, and the source-facing iterator local typed as the final exposed slot
    /// type. Declaring them here, instead of lazily inside the item processors, keeps the
    /// owner/type/uniqueness contract validated up front and guarantees the get/next processors only
    /// ever consume already-frozen slot ids.
    private void declareForLoopSlots() {
        for (var stateSlot : functionContext.forIteratorStateSlots().values()) {
            ensureVariable(stateSlot.slotId(), stateSlot.stateType());
            ensureVariable(stateSlot.nextTempSlotId(), stateSlot.stateType());
        }
        for (var sourceSlot : functionContext.forSourceIteratorSlots().values()) {
            ensureVariable(sourceSlot.sourceIteratorSlotId(), sourceSlot.exposedType());
        }
    }

    /// Predeclares source-facing match bind locals before any block is materialized.
    ///
    /// Bind slots are keyed by source name, so same-name binds of distinct sections (each legal in
    /// its own section scope) share one function variable. The CFG builder has already
    /// Variant-unified the exposed types of every divergent name group, so `ensureVariable`'s
    /// consistency check doubles as the fail-fast guard for that contract.
    private void declareMatchBindSlots() {
        for (var bindSlot : functionContext.matchBindSlots().values()) {
            ensureVariable(bindSlot.bindSlotId(), bindSlot.exposedType());
        }
    }

    private void createBlocks() {
        for (var nodeId : graph.nodeIds()) {
            if (graph.requireNode(nodeId) instanceof FrontendCfgGraph.StopNode stopNode
                    && stopNode.kind() == FrontendCfgGraph.StopKind.TERMINAL_MERGE) {
                continue;
            }
            function.addBasicBlock(new LirBasicBlock(nodeId));
        }
        function.setEntryBlockId(graph.entryNodeId());
    }

    private void lowerBlocks() {
        for (var nodeId : graph.nodeIds()) {
            if (graph.requireNode(nodeId) instanceof FrontendCfgGraph.StopNode stopNode
                    && stopNode.kind() == FrontendCfgGraph.StopKind.TERMINAL_MERGE) {
                continue;
            }
            var block = requireBlock(nodeId);
            cfgNodeProcessors.lower(this, block, graph.requireNode(nodeId), null);
        }
    }

    private @NotNull String nextBoundaryMaterializationSlotId(
            @NotNull String boundaryUse,
            @NotNull String operation
    ) {
        return "cfg_boundary_"
                + StringUtil.requireNonBlank(boundaryUse, "boundaryUse")
                + "_"
                + StringUtil.requireNonBlank(operation, "operation")
                + "_"
                + boundaryMaterializationCounter++;
    }

    /// Legacy fallback for routes that still lower through callable declaration metadata.
    ///
    /// Constructor routes such as `Node.new()` or unary builtin-from-`Variant` special cases may
    /// intentionally publish owner metadata instead of a synthetic callable. Some bare-call exact
    /// routes also still rely on declaration metadata rather than `FrontendResolvedCall`'s published
    /// `ExactCallableBoundary`, so this helper remains the narrow fallback for those routes.
    private @NotNull FunctionDef requireBoundaryCallableSignature(@NotNull FrontendResolvedCall resolvedCall) {
        var declarationSite = Objects.requireNonNull(
                Objects.requireNonNull(resolvedCall, "resolvedCall must not be null").declarationSite(),
                "Resolved call must carry declaration metadata"
        );
        return switch (declarationSite) {
            case FunctionDef functionDef -> functionDef;
            default -> throw new IllegalStateException(
                    "Resolved call '" + resolvedCall.callableName()
                            + "' does not carry callable signature metadata required for argument materialization"
            );
        };
    }

    private @NotNull CallArgumentBoundaryPlan requireCallArgumentBoundaryPlan(
            @NotNull CallItem item,
            @NotNull FrontendResolvedCall resolvedCall
    ) {
        var publishedBoundary = resolvedCall.exactCallableBoundary();
        if (publishedBoundary != null) {
            return new CallArgumentBoundaryPlan(
                    publishedBoundary.fixedParameterTypes(),
                    publishedBoundary.isVararg()
            );
        }
        if (requiresPublishedExactCallableBoundary(item, resolvedCall)) {
            throw new IllegalStateException(
                    "Exact call '" + resolvedCall.callableName()
                            + "' is missing published callable boundary metadata required for argument materialization"
            );
        }
        var callable = requireBoundaryCallableSignature(resolvedCall);
        return new CallArgumentBoundaryPlan(
                callBoundaryParameterTypes(callable, resolvedCall.callKind()),
                callable.isVararg()
        );
    }

    /// Exact instance-call CFG items already publish a concrete receiver value and therefore should
    /// also carry the shared resolver's fixed-parameter boundary. Losing that boundary is a frontend
    /// invariant violation, not a reason to fall back to raw `FunctionDef` metadata.
    private boolean requiresPublishedExactCallableBoundary(
            @NotNull CallItem item,
            @NotNull FrontendResolvedCall resolvedCall
    ) {
        return resolvedCall.status() == FrontendCallResolutionStatus.RESOLVED
                && (resolvedCall.callKind() == FrontendCallResolutionKind.INSTANCE_METHOD
                || resolvedCall.callKind() == FrontendCallResolutionKind.SUPER_METHOD)
                && item.receiverValueIdOrNull() != null;
    }

    /// Call instructions materialize the receiver separately, so any legacy frontend-facing
    /// signature metadata that still exposes an implicit `self` parameter must be normalized away.
    private @NotNull List<GdType> callBoundaryParameterTypes(
            @NotNull FunctionDef callable,
            @NotNull FrontendCallResolutionKind callKind
    ) {
        var parameters = List.copyOf(callable.getParameters());
        if ((callKind == FrontendCallResolutionKind.INSTANCE_METHOD
                || callKind == FrontendCallResolutionKind.SUPER_METHOD)
                && !callable.isStatic()
                && !parameters.isEmpty()
                && parameters.getFirst().getName().equals("self")) {
            return parameters.stream()
                    .skip(1)
                    .map(ParameterDef::getType)
                    .toList();
        }
        return parameters.stream()
                .map(ParameterDef::getType)
                .toList();
    }

    private record CallArgumentBoundaryPlan(
            @NotNull List<GdType> fixedParameterTypes,
            boolean isVararg
    ) {
        private CallArgumentBoundaryPlan {
            fixedParameterTypes = List.copyOf(Objects.requireNonNull(
                    fixedParameterTypes,
                    "fixedParameterTypes must not be null"
            ));
        }
    }

    private @NotNull FrontendBodyLoweringSupport.CfgValueMaterialization requireValueMaterialization(@NotNull String valueId) {
        var materialization = valueMaterializations.get(Objects.requireNonNull(valueId, "valueId must not be null"));
        if (materialization == null) {
            throw new IllegalStateException("Missing published materialization for frontend CFG value id '" + valueId + "'");
        }
        return materialization;
    }

    record OpaqueExprLoweringContext(@NotNull OpaqueExprValueItem item) {
        OpaqueExprLoweringContext {
            Objects.requireNonNull(item, "item must not be null");
        }
    }

}
