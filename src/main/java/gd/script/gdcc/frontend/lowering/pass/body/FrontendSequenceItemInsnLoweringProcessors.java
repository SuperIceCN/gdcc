package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.enums.LifecycleProvenance;
import gd.script.gdcc.frontend.lowering.FrontendBodyLoweringSupport;
import gd.script.gdcc.frontend.lowering.FrontendCallMutabilitySupport;
import gd.script.gdcc.frontend.lowering.cfg.item.AssertItem;
import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.AwaitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CastItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ContainerLiteralItem;
import gd.script.gdcc.frontend.lowering.cfg.item.DirectSlotAliasValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopGetItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopInitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopNextItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ForLoopShouldContinueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.GetVariantTypeItem;
import gd.script.gdcc.frontend.lowering.cfg.item.IntConstantItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchBindItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchContainerMaterializeItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchElementFetchItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchEqualItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchHasKeyItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MatchLengthCheckItem;
import gd.script.gdcc.frontend.lowering.cfg.item.VariantIsNilItem;
import gd.script.gdcc.frontend.lowering.cfg.item.LambdaConstructItem;
import gd.script.gdcc.frontend.lowering.cfg.item.LocalDeclarationItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallableLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MemberLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SignalLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.StandaloneCallableLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.MergeValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SequenceItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SourceAnchorItem;
import gd.script.gdcc.frontend.lowering.cfg.item.SubscriptLoadItem;
import gd.script.gdcc.frontend.lowering.cfg.item.TypeTestItem;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.AssertInsn;
import gd.script.gdcc.lir.insn.AwaitInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.GetVariantTypeInsn;
import gd.script.gdcc.lir.insn.CallGlobalInsn;
import gd.script.gdcc.lir.insn.CallIntrinsicInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.CallStaticMethodInsn;
import gd.script.gdcc.lir.insn.CallSuperMethodInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructContainerLiteralInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.ConstructCallableInsn;
import gd.script.gdcc.lir.insn.ConstructLambdaInsn;
import gd.script.gdcc.lir.insn.ConstructSignalInsn;
import gd.script.gdcc.lir.insn.ConstructStandaloneCallableInsn;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendVariantBoundaryCompatibility;
import gd.script.gdcc.lir.insn.IsInstanceOfInsn;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralNullInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.frontend.sema.FrontendMatchPatternRoute;
import gd.script.gdcc.frontend.sema.FrontendTypeTestTarget;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdccCoroStateType;
import gd.script.gdcc.util.type.ExplicitCastSupport;
import gd.script.gdcc.util.type.TypeTestFoldResult;
import gd.script.gdcc.util.type.TypeTestFoldUtil;
import gd.script.gdcc.lir.insn.UnpackVariantInsn;
import gd.script.gdcc.lir.insn.VariantGetIndexedInsn;
import gd.script.gdcc.lir.insn.VariantGetKeyedInsn;
import gd.script.gdcc.lir.insn.VariantIsNilInsn;
import gd.script.gdcc.lir.insn.VariantGetNamedInsn;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class FrontendSequenceItemInsnLoweringProcessors {
    private FrontendSequenceItemInsnLoweringProcessors() {
    }

    /// Emits the runtime-open writeback predicate for one current carrier slot.
    /// The helper name and positive `requires_writeback` polarity are part of the frozen
    /// frontend/backend contract and must stay aligned with `gdcc_helper.h`.
    static @NotNull String emitVariantRequiresWritebackCondition(
            @NotNull FrontendBodyLoweringSession session,
            @NotNull LirBasicBlock block,
            @NotNull String currentCarrierSlotId
    ) {
        var gateSlotId = session.allocateWritableRouteTemp("variant_requires_writeback", GdBoolType.BOOL);
        block.appendNonTerminatorInstruction(new CallGlobalInsn(
                gateSlotId,
                "gdcc_variant_requires_writeback",
                List.of(new LirInstruction.VariableOperand(currentCarrierSlotId))
        ));
        return gateSlotId;
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<SequenceItem, Void> createRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "sequence item",
                new FrontendSourceAnchorInsnLoweringProcessor(),
                new FrontendAssertInsnLoweringProcessor(),
                new FrontendLocalDeclarationInsnLoweringProcessor(),
                new FrontendBoolConstantInsnLoweringProcessor(),
                new FrontendIntConstantInsnLoweringProcessor(),
                new FrontendGetVariantTypeInsnLoweringProcessor(),
                new FrontendMatchEqualInsnLoweringProcessor(),
                new FrontendVariantIsNilInsnLoweringProcessor(),
                new FrontendMatchBindInsnLoweringProcessor(),
                new FrontendMatchContainerMaterializeInsnLoweringProcessor(),
                new FrontendMatchLengthCheckInsnLoweringProcessor(),
                new FrontendMatchHasKeyInsnLoweringProcessor(),
                new FrontendMatchElementFetchInsnLoweringProcessor(),
                new FrontendMergeValueInsnLoweringProcessor(),
                new FrontendOpaqueExprValueInsnLoweringProcessor(),
                new FrontendDirectSlotAliasInsnLoweringProcessor(),
                new FrontendCallInsnLoweringProcessor(),
                new FrontendMemberLoadInsnLoweringProcessor(),
                new FrontendSignalLoadInsnLoweringProcessor(),
                new FrontendCallableLoadInsnLoweringProcessor(),
                new FrontendStandaloneCallableLoadInsnLoweringProcessor(),
                new FrontendLambdaConstructInsnLoweringProcessor(),
                new FrontendSubscriptLoadInsnLoweringProcessor(),
                new FrontendCompoundAssignmentBinaryInsnLoweringProcessor(),
                new FrontendAssignmentInsnLoweringProcessor(),
                new FrontendCastInsnLoweringProcessor(),
                new FrontendAwaitInsnLoweringProcessor(),
                new FrontendContainerLiteralInsnLoweringProcessor(),
                new FrontendTypeTestInsnLoweringProcessor(),
                new FrontendForLoopInitInsnLoweringProcessor(),
                new FrontendForLoopShouldContinueInsnLoweringProcessor(),
                new FrontendForLoopGetInsnLoweringProcessor(),
                new FrontendForLoopNextInsnLoweringProcessor()
        );
    }

    /// Emits source line markers without touching value flow or runtime state.
    ///
    /// Source anchors exist only to preserve debug location fidelity for later passes and tooling,
    /// so the processor appends `LineNumberInsn` only when the statement still carries a concrete
    /// source range.
    private static final class FrontendSourceAnchorInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SourceAnchorItem, Void> {
        @Override
        public @NotNull Class<SourceAnchorItem> nodeType() {
            return SourceAnchorItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SourceAnchorItem node,
                @Nullable Void context
        ) {
            var lineNumber = sourceLine(node.statement());
            if (lineNumber > 0) {
                block.appendNonTerminatorInstruction(new LineNumberInsn(lineNumber));
            }
            return block;
        }

        private int sourceLine(@NotNull Statement statement) {
            var range = statement.range();
            return range == null ? -1 : range.startPoint().row() + 1;
        }
    }

    /// Lowers one `assert` statement into the result-less `AssertInsn`.
    ///
    /// The condition value is normalized into a bool slot through the shared truthiness helper
    /// (the same one branch lowering uses), so `assert(x)` accepts any Godot-truthy source type.
    /// The optional message value is consumed from its materialized slot as-is: sema type-check
    /// already guaranteed it is directly `String`-assignable, and the backend re-validates the
    /// slot type before emitting the failure path. Being a non-terminator, the block continues
    /// with the next sequence item after the guard.
    private static final class FrontendAssertInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<AssertItem, Void> {
        @Override
        public @NotNull Class<AssertItem> nodeType() {
            return AssertItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AssertItem node,
                @Nullable Void context
        ) {
            var conditionValueId = node.conditionValueId();
            var boolSlotId = FrontendBodyLoweringSupport.materializeTruthinessToBool(
                    session,
                    block,
                    conditionValueId,
                    session.requireValueType(conditionValueId)
            );
            var messageValueId = node.messageValueIdOrNull();
            var messageSlotId = messageValueId == null ? null : session.slotIdForValue(messageValueId);
            block.appendNonTerminatorInstruction(new AssertInsn(boolSlotId, messageSlotId));
            return block;
        }
    }

    /// Commits a published local initializer into the stable source-local slot.
    ///
    /// Slot declaration itself already happened before instruction lowering. The remaining job here
    /// is to materialize any ordinary `Variant` boundary required by the published local slot type
    /// and then commit the final value into the stable source-local storage.
    private static final class FrontendLocalDeclarationInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<LocalDeclarationItem, Void> {
        @Override
        public @NotNull Class<LocalDeclarationItem> nodeType() {
            return LocalDeclarationItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull LocalDeclarationItem node,
                @Nullable Void context
        ) {
            var initializerValueId = node.initializerValueIdOrNull();
            if (initializerValueId == null) {
                return block;
            }
            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    session.slotIdForValue(initializerValueId),
                    session.requireValueType(initializerValueId),
                    session.requireSourceLocalSlotType(node.declaration()),
                    "local_init"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.sourceLocalSlotId(node.declaration()),
                    materializedSlotId
            ));
            return block;
        }
    }

    /// Materializes one frontend bool constant item into a dedicated temp slot.
    ///
    /// Bool constants intentionally keep their own CFG item instead of flowing through the generic
    /// literal route when short-circuit lowering needs path-local truth values.
    private static final class FrontendBoolConstantInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<BoolConstantItem, Void> {
        @Override
        public @NotNull Class<BoolConstantItem> nodeType() {
            return BoolConstantItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull BoolConstantItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new LiteralBoolInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    node.value()
            ));
            return block;
        }
    }

    private static final class FrontendIntConstantInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<IntConstantItem, Void> {
        @Override
        public @NotNull Class<IntConstantItem> nodeType() {
            return IntConstantItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull IntConstantItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new LiteralIntInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    node.value()
            ));
            return block;
        }
    }

    /// Packs a non-Variant operand then emits `get_variant_type`.
    private static final class FrontendGetVariantTypeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<GetVariantTypeItem, Void> {
        @Override
        public @NotNull Class<GetVariantTypeItem> nodeType() {
            return GetVariantTypeItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull GetVariantTypeItem node,
                @Nullable Void context
        ) {
            var variantSlotId = session.requireVariantSlot(block, node.operandValueId(), "variant_type");
            block.appendNonTerminatorInstruction(new GetVariantTypeInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    variantSlotId
            ));
            return block;
        }
    }

    private static final class FrontendMatchEqualInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MatchEqualItem, Void> {
        @Override
        public @NotNull Class<MatchEqualItem> nodeType() {
            return MatchEqualItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MatchEqualItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new BinaryOpInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    GodotOperator.EQUAL,
                    session.slotIdForValue(node.leftValueId()),
                    session.slotIdForValue(node.rightValueId())
            ));
            return block;
        }
    }

    private static final class FrontendVariantIsNilInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<VariantIsNilItem, Void> {
        @Override
        public @NotNull Class<VariantIsNilItem> nodeType() {
            return VariantIsNilItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull VariantIsNilItem node,
                @Nullable Void context
        ) {
            var variantSlotId = session.requireVariantSlot(block, node.operandValueId(), "is_nil");
            block.appendNonTerminatorInstruction(new VariantIsNilInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    variantSlotId
            ));
            return block;
        }
    }

    /// Materializes the match subject into the predeclared bind slot.
    private static final class FrontendMatchBindInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MatchBindItem, Void> {
        @Override
        public @NotNull Class<MatchBindItem> nodeType() {
            return MatchBindItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MatchBindItem node,
                @Nullable Void context
        ) {
            var _ = session.requireMatchBindSlot(node.declaration());
            // Same-name binds of distinct sections share one function variable whose declared type
            // may be widened to Variant (see declareMatchBindSlots), so materialize into the
            // declared slot type instead of this declaration's own exposed type.
            var declaredType = session.requireFunctionVariableType(node.bindSlotId());
            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    session.slotIdForValue(node.subjectValueId()),
                    session.requireValueType(node.subjectValueId()),
                    declaredType,
                    "match_bind"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(node.bindSlotId(), materializedSlotId));
            return block;
        }
    }

    /// Materializes the destructuring subject into one static container slot.
    ///
    /// Runs once per container pattern right behind its typeof gate: a Variant source unpacks into
    /// the untyped `Array` / `Dictionary` surface, while an already-static container source keeps
    /// its published type (typed containers keep their element/value parameters).
    private static final class FrontendMatchContainerMaterializeInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MatchContainerMaterializeItem, Void> {
        @Override
        public @NotNull Class<MatchContainerMaterializeItem> nodeType() {
            return MatchContainerMaterializeItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MatchContainerMaterializeItem node,
                @Nullable Void context
        ) {
            var sourceType = session.requireValueType(node.sourceValueId());
            var targetType = switch (node.containerRoute()) {
                case FrontendMatchPatternRoute.ARRAY -> sourceType instanceof GdArrayType
                        ? sourceType
                        : new GdArrayType(GdVariantType.VARIANT);
                case FrontendMatchPatternRoute.DICTIONARY -> sourceType instanceof GdDictionaryType
                        ? sourceType
                        : new GdDictionaryType(GdVariantType.VARIANT, GdVariantType.VARIANT);
                default -> throw session.unsupportedSequenceItem(
                        node,
                        "container materialize route must be ARRAY or DICTIONARY, but got " + node.containerRoute()
                );
            };
            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    session.slotIdForValue(node.sourceValueId()),
                    sourceType,
                    targetType,
                    "match_container"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    materializedSlotId
            ));
            return block;
        }
    }

    /// Lowers the destructuring length gate: builtin `size()` plus `==` / `>=` against the pattern
    /// element count (`>=` when the pattern ends with `..`).
    private static final class FrontendMatchLengthCheckInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MatchLengthCheckItem, Void> {
        @Override
        public @NotNull Class<MatchLengthCheckItem> nodeType() {
            return MatchLengthCheckItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MatchLengthCheckItem node,
                @Nullable Void context
        ) {
            var containerSlotId = session.slotIdForValue(node.containerValueId());
            var sizeSlotId = session.allocateMatchHelperTemp("size", GdIntType.INT);
            block.appendNonTerminatorInstruction(new CallMethodInsn(sizeSlotId, "size", containerSlotId, List.of()));
            var countSlotId = session.allocateMatchHelperTemp("length", GdIntType.INT);
            block.appendNonTerminatorInstruction(new LiteralIntInsn(countSlotId, node.expectedCount()));
            block.appendNonTerminatorInstruction(new BinaryOpInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    node.openEnded() ? GodotOperator.GREATER_EQUAL : GodotOperator.EQUAL,
                    sizeSlotId,
                    countSlotId
            ));
            return block;
        }
    }

    /// Lowers one dictionary entry's key-existence gate: key packed to Variant, builtin `has(key)`.
    private static final class FrontendMatchHasKeyInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MatchHasKeyItem, Void> {
        @Override
        public @NotNull Class<MatchHasKeyItem> nodeType() {
            return MatchHasKeyItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MatchHasKeyItem node,
                @Nullable Void context
        ) {
            var dictionarySlotId = session.slotIdForValue(node.dictionaryValueId());
            var keySlotId = session.requireVariantSlot(block, node.keyValueId(), "match_has_key");
            block.appendNonTerminatorInstruction(new CallMethodInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    "has",
                    dictionarySlotId,
                    List.of(new LirInstruction.VariableOperand(keySlotId))
            ));
            return block;
        }
    }

    /// Lowers one destructuring element fetch: `variant_get_indexed` for a static `Array` slot with
    /// an int index, `variant_get_keyed` for a static `Dictionary` slot with a Variant key. The
    /// result temp is always Variant; the nested sub-pattern test or bind consumes it from there.
    private static final class FrontendMatchElementFetchInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MatchElementFetchItem, Void> {
        @Override
        public @NotNull Class<MatchElementFetchItem> nodeType() {
            return MatchElementFetchItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MatchElementFetchItem node,
                @Nullable Void context
        ) {
            var containerType = session.requireValueType(node.containerValueId());
            var containerSlotId = session.slotIdForValue(node.containerValueId());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            switch (containerType) {
                case GdArrayType _ -> block.appendNonTerminatorInstruction(new VariantGetIndexedInsn(
                        resultSlotId,
                        containerSlotId,
                        session.slotIdForValue(node.keyValueId())
                ));
                case GdDictionaryType _ -> {
                    var keySlotId = session.requireVariantSlot(block, node.keyValueId(), "match_dict_get");
                    block.appendNonTerminatorInstruction(new VariantGetKeyedInsn(
                            resultSlotId,
                            containerSlotId,
                            keySlotId
                    ));
                }
                default -> throw session.unsupportedSequenceItem(
                        node,
                        "element fetch container must be a static Array/Dictionary slot, but got "
                                + containerType.getTypeName()
                );
            }
            return block;
        }
    }

    /// Moves one mutually-exclusive merge source into the shared merge slot.
    ///
    /// Merge items are the only legal multi-producer value ids in frontend CFG, so the processor
    /// always writes into `cfg_merge_<valueId>` rather than pretending the value still has a unique
    /// SSA-like temp slot. Branch results may be heterogeneously typed: the shared slot is typed by
    /// the shared expression anchor, so each arm is converted through the single
    /// `materializeFrontendBoundaryValue(..., "merge_write")` consumer before the final copy.
    /// That consumer is intentionally re-derive (it re-queries the ordinary typed-boundary matrix at
    /// lowering time) rather than frozen-decision, matching `assignment` / `return` / `local-init`.
    private static final class FrontendMergeValueInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MergeValueItem, Void> {
        @Override
        public @NotNull Class<MergeValueItem> nodeType() {
            return MergeValueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MergeValueItem node,
                @Nullable Void context
        ) {
            var sourceType = session.requireValueType(node.sourceValueId());
            var mergeType = session.requireValueType(node.resultValueId());
            var sourceSlotId = session.slotIdForValue(node.sourceValueId());
            var materializedSourceSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    sourceSlotId,
                    sourceType,
                    mergeType,
                    "merge_write"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(
                    FrontendBodyLoweringSupport.mergeSlotId(node.resultValueId()),
                    materializedSourceSlotId
            ));
            return block;
        }
    }

    /// Gates the generic opaque-expression route before handing off to expression-root processors.
    ///
    /// Only leaf values plus eager unary/binary operators are allowed through this surface. Any
    /// short-circuit, deferred, or special-form expression must already have been published as a
    /// dedicated CFG item before body lowering starts.
    private static final class FrontendOpaqueExprValueInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<OpaqueExprValueItem, Void> {
        @Override
        public @NotNull Class<OpaqueExprValueItem> nodeType() {
            return OpaqueExprValueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull OpaqueExprValueItem node,
                @Nullable Void context
        ) {
            var policy = classifyOpaqueExpression(node.expression());
            if (policy.handling() != OpaqueExprHandling.HANDLE_NOW) {
                throw session.unsupportedSequenceItem(node, policy.detail());
            }
            return session.lowerOpaqueExpression(block, node);
        }

        private @NotNull OpaqueExprPolicy classifyOpaqueExpression(@NotNull Expression expression) {
            return switch (expression) {
                case IdentifierExpression _, LiteralExpression _, SelfExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "leaf values stay on the OpaqueExprValueItem route"
                );
                case UnaryExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "eager unary expressions still lower from OpaqueExprValueItem"
                );
                case BinaryExpression binaryExpression when isShortCircuitBinary(binaryExpression) ->
                        new OpaqueExprPolicy(
                                OpaqueExprHandling.REJECT,
                                "short-circuit and/or must lower through branchy control flow, not BinaryOpInsn"
                        );
                case BinaryExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "eager binary operators keep using OpaqueExprValueItem"
                );
                case ConditionalExpression _, CastExpression _, TypeTestExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.DEFER,
                        "this expression root needs a dedicated lowering route before body pass consumes it"
                );
                case ArrayExpression _, DictionaryExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.REJECT,
                        "array/dictionary literals must lower through ContainerLiteralItem, not OpaqueExprValueItem"
                );
                case PreloadExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "preload lowers through the dedicated opaque processor as a ResourceLoader "
                                + "singleton call pair"
                );
                case GetNodeExpression _ -> new OpaqueExprPolicy(
                        OpaqueExprHandling.HANDLE_NOW,
                        "get-node lowers through the dedicated opaque processor as a "
                                + "literal_node_path + self-upcast + Node.get_node call triple"
                );
                case AssignmentExpression _, AttributeExpression _, CallExpression _, SubscriptExpression _ ->
                        new OpaqueExprPolicy(
                                OpaqueExprHandling.REJECT,
                                "this expression must not re-enter the generic opaque fallback path"
                        );
                default -> new OpaqueExprPolicy(
                        OpaqueExprHandling.REJECT,
                        "unsupported opaque expression root: " + expression.getClass().getSimpleName()
                );
            };
        }

        private boolean isShortCircuitBinary(@NotNull BinaryExpression binaryExpression) {
            return switch (binaryExpression.operator()) {
                case "and", "or" -> true;
                default -> false;
            };
        }

        private enum OpaqueExprHandling {
            HANDLE_NOW,
            DEFER,
            REJECT
        }

        private record OpaqueExprPolicy(
                @NotNull OpaqueExprHandling handling,
                @NotNull String detail
        ) {
            private OpaqueExprPolicy {
                Objects.requireNonNull(handling, "handling must not be null");
                detail = Objects.requireNonNull(detail, "detail must not be null");
            }
        }
    }

    /// Direct-slot alias values are publication-only. They intentionally reuse one trusted source slot
    /// and therefore emit no standalone instruction or temp declaration during body lowering.
    private static final class FrontendDirectSlotAliasInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<DirectSlotAliasValueItem, Void> {
        @Override
        public @NotNull Class<DirectSlotAliasValueItem> nodeType() {
            return DirectSlotAliasValueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull DirectSlotAliasValueItem node,
                @Nullable Void context
        ) {
            session.slotIdForValue(node.resultValueId());
            return block;
        }
    }

    /// Emits call instructions strictly from the published call route.
    ///
    /// The processor is not allowed to repair missing receiver information, redo overload choice,
    /// or guess between global/static/instance call families once compile-ready facts exist. It
    /// only materializes the already-approved argument-side `Variant` boundaries required by the
    /// selected callable signature for exact routes. Runtime-open `DYNAMIC_FALLBACK` instance calls
    /// still reuse the ordinary `CallMethodInsn` surface, but the receiver side may now attach
    /// conservative runtime-gated reverse commit when a writable payload is published. The
    /// processor must therefore treat receiver writeback as one parallel contract around the same
    /// call instruction instead of re-splitting the receiver chain into ad-hoc step items here.
    /// The returned block is the active continuation block after any post-call writeback, because a
    /// dynamic receiver gate may splice synthetic `apply/skip/continue` blocks and later sequence
    /// items must keep lowering into that returned continuation.
    private static final class FrontendCallInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CallItem, Void> {
        @Override
        public @NotNull Class<CallItem> nodeType() {
            return CallItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @Nullable Void context
        ) {
            var resolvedCall = session.requireResolvedCall(node.anchor());
            return switch (resolvedCall.callKind()) {
                case INSTANCE_METHOD -> lowerExactInstanceCall(session, block, node, resolvedCall);
                case SUPER_METHOD -> lowerSuperMethodCall(session, block, node, resolvedCall);
                case STATIC_METHOD -> lowerStaticMethodCall(session, block, node, resolvedCall);
                case CONSTRUCTOR -> lowerConstructorCall(
                        session,
                        block,
                        node,
                        resolvedCall,
                        requireMaterializedResultSlotId(session, node, "constructor call")
                );
                case DYNAMIC_FALLBACK -> lowerDynamicInstanceCall(
                        session,
                        block,
                        node,
                        resolvedCall,
                        requireMaterializedResultSlotId(session, node, "dynamic call")
                );
                case UNKNOWN -> throw session.unsupportedSequenceItem(
                        node,
                        "call route is not lowering-ready: " + resolvedCall.callKind()
                );
            };
        }

        private @NotNull LirBasicBlock lowerExactInstanceCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            var mutatingReceiverRoute = mutatingReceiverRouteOrNull(session, node, resolvedCall);
            var receiverSlotId = session.materializeCallReceiverLeaf(block, node);
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            session.emitAssertObjectLiveIfNeeded(block, receiverSlotId);
            block.appendNonTerminatorInstruction(new CallMethodInsn(
                    emittedExactResultSlotIdOrNull(session, node, resolvedCall),
                    resolvedCall.callableName(),
                    receiverSlotId,
                    arguments
            ));
            var continuation = continueAfterReceiverWriteback(session, block, mutatingReceiverRoute, receiverSlotId);
            return emitCoroutineDetachIfNeeded(session, continuation, node);
        }

        /// `super` calls share the exact-instance receiver/argument materialization, but emit
        /// `CALL_SUPER_METHOD` so the backend resolves the nearest ancestor implementation from the
        /// lexical superclass and bypasses vtable dispatch
        /// (`virtual_override_vtable_implementation.md` §2.5). The receiver is always the
        /// current-class `self` alias, so no reverse-commit writeback route applies.
        private @NotNull LirBasicBlock lowerSuperMethodCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            var receiverSlotId = session.materializeCallReceiverLeaf(block, node);
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            session.emitAssertObjectLiveIfNeeded(block, receiverSlotId);
            block.appendNonTerminatorInstruction(new CallSuperMethodInsn(
                    emittedExactResultSlotIdOrNull(session, node, resolvedCall),
                    resolvedCall.callableName(),
                    receiverSlotId,
                    arguments
            ));
            return emitCoroutineDetachIfNeeded(session, block, node);
        }

        /// Fire-and-forget contract (`frontend_await_implementation.md` §7): a coroutine call whose
        /// result is not consumed by an await releases the call-site OWNED state reference right
        /// after the call; the coroutine frame stays alive through its own wait edges. Awaited
        /// results skip the destruct because the await moves the reference out of the slot.
        private @NotNull LirBasicBlock emitCoroutineDetachIfNeeded(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock continuation,
                @NotNull CallItem node
        ) {
            var resultValueId = node.resultValueIdOrNull();
            if (resultValueId == null
                    || !session.isCoroutineCall(node.anchor())
                    || session.isAwaitOperandValue(resultValueId)) {
                return continuation;
            }
            continuation.appendNonTerminatorInstruction(new DestructInsn(
                    session.slotIdForValue(resultValueId),
                    LifecycleProvenance.INTERNAL
            ));
            return continuation;
        }

        private @NotNull LirBasicBlock lowerStaticMethodCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            // The receiverType == null branch is the utility route (`CallGlobalInsn`); same-class
            // `worker(1)` calls go through `CallStaticMethodInsn`. Utility callees are never
            // coroutines, so the detach on that branch is a no-op — hooking both branches keeps
            // static fire-and-forget on the same discipline as instance without special-casing.
            if (resolvedCall.receiverType() == null) {
                // Synthetic `load` never reaches the backend as `call_global`: rewrite it to the
                // ResourceLoader singleton instance call pair so the ordinary engine dispatch
                // (default-argument completion included) handles it. The declaration-site check
                // keeps user-defined `load` shadows on their own static/instance route.
                if (isSyntheticLoadCall(session, resolvedCall)) {
                    var loaderSlotId = session.allocateGdScriptLanguageFunctionTemp(
                            "resource_loader",
                            new GdObjectType("ResourceLoader")
                    );
                    block.appendNonTerminatorInstruction(new LoadStaticInsn(
                            loaderSlotId,
                            "@GlobalScope",
                            "ResourceLoader"
                    ));
                    block.appendNonTerminatorInstruction(new CallMethodInsn(
                            emittedExactResultSlotIdOrNull(session, node, resolvedCall),
                            "load",
                            loaderSlotId,
                            arguments
                    ));
                    return emitCoroutineDetachIfNeeded(session, block, node);
                }
                block.appendNonTerminatorInstruction(new CallGlobalInsn(
                        emittedExactResultSlotIdOrNull(session, node, resolvedCall),
                        resolvedCall.callableName(),
                        arguments
                ));
                return emitCoroutineDetachIfNeeded(session, block, node);
            }
            block.appendNonTerminatorInstruction(new CallStaticMethodInsn(
                    emittedExactResultSlotIdOrNull(session, node, resolvedCall),
                    session.requireStaticReceiverName(resolvedCall.receiverType()),
                    resolvedCall.callableName(),
                    arguments
            ));
            return emitCoroutineDetachIfNeeded(session, block, node);
        }

        /// True only when the resolved bare call is the synthetic GDScript language function
        /// `load`: the declaration site must be the registered synthetic `ExtensionUtilityFunction`
        /// record, which user-defined same-name functions (static or instance) never carry.
        private boolean isSyntheticLoadCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            return "load".equals(resolvedCall.callableName())
                    && resolvedCall.declarationSite() instanceof ExtensionUtilityFunction declaration
                    && session.classRegistry().isGdScriptLanguageFunction(declaration.getName());
        }

        private @NotNull LirBasicBlock lowerConstructorCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall,
                @NotNull String resultSlotId
        ) {
            var constructorResultType = Objects.requireNonNull(
                    resolvedCall.returnType(),
                    "resolved constructor call must carry a result type"
            );
            // Unary builtin-from-Variant construction is published as a dedicated constructor route, but
            // sema intentionally anchors its declaration site to the builtin owner instead of one concrete
            // overload. Lowering therefore must reuse the already-evaluated Variant slot directly rather
            // than asking the ordinary callable-signature materializer for a synthetic constructor shape.
            if (resolvedCall.ownerKind() == ScopeOwnerKind.BUILTIN
                    && resolvedCall.argumentTypes().size() == 1
                    && resolvedCall.argumentTypes().getFirst() instanceof GdVariantType) {
                if (node.argumentValueIds().size() != 1) {
                    throw new IllegalStateException(
                            "Builtin Variant constructor unpack route must publish exactly one argument value id"
                    );
                }
                var argumentValueId = node.argumentValueIds().getFirst();
                var argumentType = session.requireValueType(argumentValueId);
                if (!(argumentType instanceof GdVariantType)) {
                    throw new IllegalStateException(
                            "Builtin Variant constructor unpack route requires Variant argument slot, but got "
                                    + argumentType.getTypeName()
                    );
                }
                block.appendNonTerminatorInstruction(new UnpackVariantInsn(
                        resultSlotId,
                        session.slotIdForValue(argumentValueId)
                ));
                return block;
            }
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            switch (constructorResultType) {
                // Builtin/container constructors materialize directly from the published call route.
                case GdObjectType _ -> block.appendNonTerminatorInstruction(new ConstructObjectInsn(
                        resultSlotId,
                        session.requireClassName(constructorResultType)
                ));
                default -> block.appendNonTerminatorInstruction(new ConstructBuiltinInsn(resultSlotId, arguments));
            }
            return block;
        }

        private @NotNull LirBasicBlock lowerDynamicInstanceCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall,
                @NotNull String resultSlotId
        ) {
            if (resolvedCall.receiverKind() != FrontendReceiverKind.INSTANCE) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic call lowering requires an instance receiver route, but got "
                                + resolvedCall.receiverKind()
                );
            }
            var mutatingReceiverRoute = mutatingReceiverRouteOrNull(session, node, resolvedCall);
            var receiverSlotId = session.materializeCallReceiverLeaf(block, node);
            var arguments = session.materializeCallArguments(block, node, resolvedCall);
            session.emitAssertObjectLiveIfNeeded(block, receiverSlotId);
            block.appendNonTerminatorInstruction(new CallMethodInsn(
                    resultSlotId,
                    resolvedCall.callableName(),
                    receiverSlotId,
                    arguments
            ));
            return continueAfterDynamicReceiverWriteback(session, block, mutatingReceiverRoute, receiverSlotId);
        }

        /// Exact call routes may legally omit a result slot only for resolved-void statement calls.
        /// Coroutine callees are the one exception: the internal coroutine ABI always yields the
        /// OWNED state object reference, so even a void coroutine call requires its
        /// `compiler::GdccCoroState` result slot. Any other non-void exact route that reaches body
        /// lowering without a published result id is still an invariant violation instead of a
        /// signal to silently drop the value.
        private @Nullable String emittedExactResultSlotIdOrNull(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            if (session.isCoroutineCall(node.anchor())) {
                return requireMaterializedResultSlotId(
                        session,
                        node,
                        "coroutine call '" + resolvedCall.callableName() + "'"
                );
            }
            if (resolvedCall.returnType() instanceof GdVoidType) {
                return null;
            }
            return requireMaterializedResultSlotId(session, node, "non-void exact call '" + resolvedCall.callableName() + "'");
        }

        private @NotNull String requireMaterializedResultSlotId(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull CallItem node,
                @NotNull String contractDetail
        ) {
            var resultValueId = node.resultValueIdOrNull();
            if (resultValueId == null) {
                throw new IllegalStateException(contractDetail + " must publish resultValueIdOrNull before body lowering");
            }
            return session.slotIdForValue(resultValueId);
        }

        /// Returns the block that later lowering must keep appending to after post-call receiver
        /// writeback. Exact routes use the shared static gate and therefore stay in-place.
        private @NotNull LirBasicBlock continueAfterReceiverWriteback(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @Nullable FrontendWritableRouteSupport.FrontendWritableAccessChain mutatingReceiverRoute,
                @NotNull String receiverSlotId
        ) {
            if (mutatingReceiverRoute == null) {
                return block;
            }
            FrontendWritableRouteSupport.reverseCommit(
                    session,
                    block,
                    mutatingReceiverRoute,
                    receiverSlotId,
                    FrontendWritableRouteSupport.createStaticCarrierWritebackGate(session)
            );
            return block;
        }

        /// Dynamic instance calls cannot answer receiver writeback from static constness alone, so
        /// lowering reuses the shared runtime gate helper and keeps lowering on the returned
        /// continuation block. The runtime helper only answers the `Variant` branch; concrete carrier
        /// families still keep using the shared static fast path inside writable-route support.
        private @NotNull LirBasicBlock continueAfterDynamicReceiverWriteback(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @Nullable FrontendWritableRouteSupport.FrontendWritableAccessChain mutatingReceiverRoute,
                @NotNull String receiverSlotId
        ) {
            if (mutatingReceiverRoute == null) {
                return block;
            }
            return FrontendWritableRouteSupport.reverseCommitWithRuntimeGate(
                    session,
                    block,
                    mutatingReceiverRoute,
                    receiverSlotId,
                    (_, gateBlock, _, currentCarrierSlotId) -> emitVariantRequiresWritebackCondition(
                            session,
                            gateBlock,
                            currentCarrierSlotId
                    )
            );
        }

        /// Receiver writeback is enabled only for already-published writable receiver routes whose
        /// call route is conservative may-mutate. Exact routes use declaration constness when
        /// available; dynamic instance routes count as may-mutate because runtime-open dispatch has
        /// no reliable constness fact and must not let a potentially mutating receiver collapse back
        /// to plain snapshot semantics.
        private @Nullable FrontendWritableRouteSupport.FrontendWritableAccessChain mutatingReceiverRouteOrNull(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull CallItem node,
                @NotNull FrontendResolvedCall resolvedCall
        ) {
            if (!FrontendCallMutabilitySupport.mayMutateReceiver(resolvedCall)
                    || node.writableRoutePayloadOrNull() == null) {
                return null;
            }
            return session.requireWritableAccessChain(node.writableRoutePayloadOrNull());
        }

    }

    /// Emits one property/static-member read from the published member-resolution result.
    ///
    /// Member receiver kind is already frozen by semantic analysis, so the processor only maps it
    /// to the concrete load instruction family and never re-inspects the original chain. Builtin
    /// instance fields such as `vector.x` therefore lower through the same `LoadPropertyInsn`
    /// contract as engine/GDCC ordinary property reads.
    private static final class FrontendMemberLoadInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<MemberLoadItem, Void> {
        @Override
        public @NotNull Class<MemberLoadItem> nodeType() {
            return MemberLoadItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MemberLoadItem node,
                @Nullable Void context
        ) {
            var resolvedMember = session.requireResolvedMember(node.anchor());
            if (resolvedMember.status() == FrontendMemberResolutionStatus.RESOLVED
                    && resolvedMember.bindingKind() == FrontendBindingKind.SIGNAL) {
                throw session.unsupportedSequenceItem(
                        node,
                        "RESOLVED SIGNAL members must lower through SignalLoadItem, not MemberLoadItem"
                );
            }
            if (resolvedMember.status() == FrontendMemberResolutionStatus.RESOLVED
                    && (resolvedMember.bindingKind() == FrontendBindingKind.METHOD
                    || resolvedMember.bindingKind() == FrontendBindingKind.STATIC_METHOD)) {
                throw session.unsupportedSequenceItem(
                        node,
                        "RESOLVED METHOD/STATIC_METHOD members must lower through CallableLoadItem or StandaloneCallableLoadItem, not MemberLoadItem"
                );
            }
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            if (resolvedMember.status() == FrontendMemberResolutionStatus.DYNAMIC) {
                lowerDynamicMemberLoad(session, block, node, resolvedMember, resultSlotId);
                return block;
            }
            switch (resolvedMember.receiverKind()) {
                case INSTANCE -> {
                    if (node.baseValueIdOrNull() == null) {
                        throw session.unsupportedSequenceItem(
                                node,
                                "instance member load is missing a receiver value id"
                        );
                    }
                    if (resolvedMember.bindingKind() == FrontendBindingKind.PROPERTY
                            && resolvedMember.declarationSite() instanceof PropertyDef propertyDef
                            && propertyDef.isStatic()) {
                        // Instance-syntax access to a static property: the receiver value was
                        // already materialized in CFG order (side effects preserved), but the
                        // load must read the declaring class's shared static storage, not an
                        // instance field.
                        block.appendNonTerminatorInstruction(new LoadStaticInsn(
                                resultSlotId,
                                session.requireStaticReceiverName(resolvedMember.receiverType()),
                                node.memberName()
                        ));
                        return block;
                    }
                    var receiverSlotId = session.slotIdForValue(node.baseValueIdOrNull());
                    var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                            node.anchor(),
                            new FrontendWritableRouteSupport.FrontendWritableRoot(
                                    "member receiver",
                                    receiverSlotId,
                                    session.requireValueType(node.baseValueIdOrNull())
                            ),
                            new FrontendWritableRouteSupport.InstancePropertyLeaf(
                                    receiverSlotId,
                                    node.memberName(),
                                    session.requireValueType(node.resultValueId())
                            ),
                            List.of()
                    );
                    FrontendWritableRouteSupport.materializeLeafReadInto(
                            session,
                            block,
                            chain,
                            resultSlotId
                    );
                }
                case TYPE_META -> {
                    if (node.baseValueIdOrNull() != null) {
                        throw session.unsupportedSequenceItem(
                                node,
                                "type-meta static member load must not carry a receiver value id"
                        );
                    }
                    block.appendNonTerminatorInstruction(new LoadStaticInsn(
                            resultSlotId,
                            session.requireStaticReceiverName(resolvedMember.receiverType()),
                            node.memberName()
                    ));
                }
                default -> throw session.unsupportedSequenceItem(
                        node,
                        "member receiver kind is not lowering-ready: " + resolvedMember.receiverKind()
                );
            }
            return block;
        }

        private void lowerDynamicMemberLoad(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull MemberLoadItem node,
                @NotNull FrontendResolvedMember resolvedMember,
                @NotNull String resultSlotId
        ) {
            // Dynamic member reads are selected by the published status, not by receiver family.
            // Object-family receivers are only packed after the DYNAMIC route fact is already frozen.
            if (resolvedMember.receiverKind() != FrontendReceiverKind.INSTANCE) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic member load requires an instance receiver route, but got "
                                + resolvedMember.receiverKind()
                );
            }
            if (node.baseValueIdOrNull() == null) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic member load is missing a receiver value id"
                );
            }

            var receiverValueId = node.baseValueIdOrNull();
            var receiverSlotId = session.slotIdForValue(receiverValueId);
            var receiverType = session.requireValueType(receiverValueId);
            if (!(receiverType instanceof GdVariantType) && !(receiverType instanceof GdObjectType)) {
                throw session.unsupportedSequenceItem(
                        node,
                        "dynamic member load requires Variant or Object-family receiver, but got "
                                + receiverType.getTypeName()
                );
            }

            var receiverVariantSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    receiverSlotId,
                    receiverType,
                    GdVariantType.VARIANT,
                    "dynamic_member_read_receiver"
            );
            var nameSlotId = session.allocateWritableRouteTemp(
                    "dynamic_member_read_name",
                    GdStringNameType.STRING_NAME
            );
            block.appendNonTerminatorInstruction(new LiteralStringNameInsn(nameSlotId, node.memberName()));
            block.appendNonTerminatorInstruction(new VariantGetNamedInsn(
                    resultSlotId,
                    receiverVariantSlotId,
                    nameSlotId
            ));
        }
    }

    /// Materializes a published RESOLVED SIGNAL member into `construct_signal`.
    ///
    /// The processor consumes only the published member fact and the CFG receiver value id.
    /// It never invents a property writable route and never guesses a signal name from AST.
    private static final class FrontendSignalLoadInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SignalLoadItem, Void> {
        @Override
        public @NotNull Class<SignalLoadItem> nodeType() {
            return SignalLoadItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SignalLoadItem node,
                @Nullable Void context
        ) {
            var resolvedMember = session.requireResolvedMember(node.anchor());
            if (resolvedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                    || resolvedMember.bindingKind() != FrontendBindingKind.SIGNAL) {
                throw session.unsupportedSequenceItem(
                        node,
                        "signal load requires a RESOLVED SIGNAL member, but got "
                                + resolvedMember.status()
                                + "/"
                                + resolvedMember.bindingKind()
                );
            }
            if (resolvedMember.receiverKind() != FrontendReceiverKind.INSTANCE) {
                throw session.unsupportedSequenceItem(
                        node,
                        "signal load requires an instance receiver, but got "
                                + resolvedMember.receiverKind()
                );
            }
            if (node.receiverValueId() == null) {
                throw session.unsupportedSequenceItem(node, "signal load is missing a receiver value id");
            }
            var receiverSlotId = session.requireLiveObjectReceiverSlotId(node.receiverValueId());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            session.emitAssertObjectLiveIfNeeded(block, receiverSlotId);
            block.appendNonTerminatorInstruction(new ConstructSignalInsn(
                    resultSlotId,
                    receiverSlotId,
                    node.signalName()
            ));
            return block;
        }
    }

    /// Materializes a published RESOLVED instance METHOD member into `construct_callable`.
    ///
    /// Object receivers keep Object liveness. Builtin receivers skip the Object guard.
    private static final class FrontendCallableLoadInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CallableLoadItem, Void> {
        @Override
        public @NotNull Class<CallableLoadItem> nodeType() {
            return CallableLoadItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CallableLoadItem node,
                @Nullable Void context
        ) {
            var resolvedMember = session.requireResolvedMember(node.anchor());
            if (resolvedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                    || resolvedMember.bindingKind() != FrontendBindingKind.METHOD
                    || resolvedMember.receiverKind() != FrontendReceiverKind.INSTANCE) {
                throw session.unsupportedSequenceItem(
                        node,
                        "callable load requires a RESOLVED instance METHOD member, but got "
                                + resolvedMember.status()
                                + "/"
                                + resolvedMember.bindingKind()
                                + "/"
                                + resolvedMember.receiverKind()
                );
            }
            if (node.receiverValueId() == null) {
                throw session.unsupportedSequenceItem(node, "callable load is missing a receiver value id");
            }
            var receiverIsObject = resolvedMember.ownerKind() != ScopeOwnerKind.BUILTIN;
            var receiverSlotId = receiverIsObject
                    ? session.requireLiveObjectReceiverSlotId(node.receiverValueId())
                    : session.slotIdForValue(node.receiverValueId());
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            if (receiverIsObject) {
                session.emitAssertObjectLiveIfNeeded(block, receiverSlotId);
            }
            block.appendNonTerminatorInstruction(new ConstructCallableInsn(
                    resultSlotId,
                    receiverSlotId,
                    node.methodName()
            ));
            return block;
        }
    }

    /// Materializes a published RESOLVED GDCC/engine STATIC_METHOD into `construct_standalone_callable`.
    private static final class FrontendStandaloneCallableLoadInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<StandaloneCallableLoadItem, Void> {
        @Override
        public @NotNull Class<StandaloneCallableLoadItem> nodeType() {
            return StandaloneCallableLoadItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull StandaloneCallableLoadItem node,
                @Nullable Void context
        ) {
            var resolvedMember = session.requireResolvedMember(node.anchor());
            if (resolvedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                    || resolvedMember.bindingKind() != FrontendBindingKind.STATIC_METHOD
                    || resolvedMember.ownerKind() == ScopeOwnerKind.BUILTIN) {
                throw session.unsupportedSequenceItem(
                        node,
                        "standalone callable load requires a RESOLVED GDCC/engine STATIC_METHOD, but got "
                                + resolvedMember.status()
                                + "/"
                                + resolvedMember.bindingKind()
                                + "/"
                                + resolvedMember.ownerKind()
                );
            }
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            block.appendNonTerminatorInstruction(new ConstructStandaloneCallableInsn(
                    resultSlotId,
                    node.kind(),
                    session.requireDeclaringStaticOwnerName(node.ownerName(), node.callableName()),
                    node.callableName()
            ));
            return block;
        }
    }

    /// Emits `construct_lambda` for an outer-body lambda occurrence.
    ///
    /// The item carries only the synthetic name plus ordered enclosing-frame capture slot reads;
    /// the processor re-validates the synthesized shell on the owning class (existence, capture
    /// count, and name order) so a drifted plan/shell pair fails fast here instead of silently
    /// emitting a mismatched instruction. Capture operand slot ids equal the capture entry names
    /// by construction (the SELF_SLOT descriptor resolves to `SELF_CAPTURE_NAME`), which is what
    /// makes the name-order cross-check meaningful.
    private static final class FrontendLambdaConstructInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<LambdaConstructItem, Void> {
        @Override
        public @NotNull Class<LambdaConstructItem> nodeType() {
            return LambdaConstructItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull LambdaConstructItem node,
                @Nullable Void context
        ) {
            var lambdaFunction = session.functionContext().owningClass().getFunctions().stream()
                    .filter(function -> function.getName().equals(node.lambdaName()))
                    .findFirst()
                    .orElse(null);
            if (lambdaFunction == null || !lambdaFunction.isLambda()) {
                throw session.unsupportedSequenceItem(
                        node,
                        "lambda construct requires a synthesized lambda shell named '"
                                + node.lambdaName() + "' on the owning class"
                );
            }
            var shellCaptures = lambdaFunction.getCaptureList();
            if (shellCaptures.size() != node.captureOperands().size()) {
                throw session.unsupportedSequenceItem(
                        node,
                        "lambda construct capture count mismatch: item carries "
                                + node.captureOperands().size() + " operand(s) but shell '"
                                + node.lambdaName() + "' declares " + shellCaptures.size() + " capture(s)"
                );
            }
            for (var index = 0; index < shellCaptures.size(); index++) {
                if (!shellCaptures.get(index).getName().equals(node.captureOperands().get(index).slotId())) {
                    throw session.unsupportedSequenceItem(
                            node,
                            "lambda construct capture order mismatch at index " + index + ": item operand '"
                                    + node.captureOperands().get(index).slotId() + "' but shell '"
                                    + node.lambdaName() + "' declares capture '"
                                    + shellCaptures.get(index).getName() + "'"
                    );
                }
            }
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            block.appendNonTerminatorInstruction(new ConstructLambdaInsn(
                    resultSlotId,
                    node.lambdaName(),
                    node.captureOperands().stream()
                            .<LirInstruction.Operand>map(operand ->
                                    new LirInstruction.VariableOperand(operand.slotId()))
                            .toList()
            ));
            return block;
        }
    }

    /// Lowers a published subscript read, including attribute-step intermediate named loads.
    ///
    /// The processor relies only on published receiver/key types to pick the final instruction
    /// family; it must not reopen subscript route inference during body materialization.
    private static final class FrontendSubscriptLoadInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SubscriptLoadItem, Void> {
        @Override
        public @NotNull Class<SubscriptLoadItem> nodeType() {
            return SubscriptLoadItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SubscriptLoadItem node,
                @Nullable Void context
        ) {
            session.requireSingleSubscriptArgument(node.anchor(), node.argumentValueIds());
            var baseSlotId = session.slotIdForValue(node.baseValueId());
            var keyValueId = node.argumentValueIds().getFirst();
            var keySlotId = session.slotIdForValue(keyValueId);
            var keyType = session.requireValueType(keyValueId);
            var receiverType = session.requireValueType(node.baseValueId());
            var containerFacts = session.resolveSubscriptContainerFacts(
                    node.memberNameOrNull(),
                    receiverType,
                    node.anchor()
            );
            var chain = new FrontendWritableRouteSupport.FrontendWritableAccessChain(
                    node.anchor(),
                    new FrontendWritableRouteSupport.FrontendWritableRoot(
                            node.memberNameOrNull() == null ? "subscript base" : "attribute-subscript receiver",
                            baseSlotId,
                            receiverType
                    ),
                    new FrontendWritableRouteSupport.SubscriptLeaf(
                            baseSlotId,
                            receiverType,
                            node.memberNameOrNull(),
                            keySlotId,
                            keyType,
                            session.requireValueType(node.resultValueId()),
                            containerFacts.containerSourceType(),
                            containerFacts.staticOwnerNameOrNull(),
                            containerFacts.typedInstanceContainer()
                    ),
                    List.of()
            );
            FrontendWritableRouteSupport.materializeLeafReadInto(
                    session,
                    block,
                    chain,
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId())
            );
            return block;
        }
    }

    /// Materializes the already-frozen read-modify-write computation for one compound assignment.
    ///
    /// CFG build already guaranteed evaluation order and single-evaluation target operands. This
    /// processor therefore only converts the published binary lexeme plus the two operand value ids
    /// into one `BinaryOpInsn`; it does not insert any extra assignment-boundary `(un)pack` logic.
    private static final class FrontendCompoundAssignmentBinaryInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CompoundAssignmentBinaryOpItem, Void> {
        @Override
        public @NotNull Class<CompoundAssignmentBinaryOpItem> nodeType() {
            return CompoundAssignmentBinaryOpItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CompoundAssignmentBinaryOpItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new BinaryOpInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    requireCompoundOperator(session, node),
                    session.slotIdForValue(node.currentTargetValueId()),
                    session.slotIdForValue(node.rhsValueId())
            ));
            return block;
        }

        private @NotNull GodotOperator requireCompoundOperator(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull CompoundAssignmentBinaryOpItem node
        ) {
            try {
                return GodotOperator.fromSourceLexeme(
                        node.binaryOperatorLexeme(),
                        GodotOperator.OperatorArity.BINARY
                );
            } catch (IllegalArgumentException ex) {
                throw session.unsupportedSequenceItem(
                        node,
                        "compound-assignment body-lowering contract published unsupported binary operator '"
                                + node.binaryOperatorLexeme()
                                + "'"
                );
            }
        }
    }

    /// Commits one published assignment target store and optionally republishes assignment-as-value.
    ///
    /// The processor never re-evaluates target children; it delegates only on the already-frozen
    /// target shape while keeping operand ordering and RHS materialization from CFG build intact.
    private static final class FrontendAssignmentInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<AssignmentItem, Void> {
        @Override
        public @NotNull Class<AssignmentItem> nodeType() {
            return AssignmentItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AssignmentItem node,
                @Nullable Void context
        ) {
            var rhsSlotId = session.slotIdForValue(node.rhsValueId());
            var currentBlock = session.lowerAssignmentTarget(block, node, rhsSlotId);
            if (node.resultValueIdOrNull() != null) {
                currentBlock.appendNonTerminatorInstruction(new AssignInsn(
                        FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueIdOrNull()),
                        rhsSlotId
                ));
            }
            return currentBlock;
        }
    }

    /// Lowers `value as T` using the shared {@link ExplicitCastSupport} decision matrix.
    ///
    /// Fixed contracts:
    /// - target type is the published cast expression type (result materialization), never a re-parse
    ///   of {@code TypeRef.sourceText()}
    /// - source type comes from the already-lowered operand value id
    /// - does not reuse ordinary implicit-boundary materialization
    /// - INVALID decisions are fail-fast guard rails (type-check owns user diagnostics)
    private static final class FrontendCastInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<CastItem, Void> {
        @Override
        public @NotNull Class<CastItem> nodeType() {
            return CastItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull CastItem node,
                @Nullable Void context
        ) {
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            var sourceSlotId = session.slotIdForValue(node.operandValueId());
            var sourceType = session.requireValueType(node.operandValueId());
            // CastItem TEMP_SLOT is already typed by expressionTypes()[CastExpression] = target type.
            var targetType = session.requireValueType(node.resultValueId());
            session.emitExplicitCast(block, node, resultSlotId, sourceSlotId, sourceType, targetType);
            return block;
        }
    }

    /// Lowers one await suspension point strictly from frozen facts
    /// (`frontend_await_implementation.md` §9).
    ///
    /// Dispatch follows the materialized operand type. Signal/Variant-returning non-coroutine calls
    /// remain suspension-capable (Godot dynamically awaits their returned value), while a RESOLVED
    /// non-coroutine call with any other static return type is the true redundant passthrough shape.
    /// The supported shapes are:
    /// - `compiler::GdccCoroState` operand (produced only by a coroutine call) → state-channel
    ///   `AwaitInsn`; the enclosing function is necessarily sema-marked as a coroutine.
    /// - `Signal` operand → signal-channel `AwaitInsn`.
    /// - `Variant` operand → runtime-dynamic `AwaitInsn`.
    /// A missing operand value id means the redundant-void-call shape (result resumes as nil).
    /// Anything outside these shapes is a sema/lowering protocol violation and fails fast.
    private static final class FrontendAwaitInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<AwaitItem, Void> {
        @Override
        public @NotNull Class<AwaitItem> nodeType() {
            return AwaitItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AwaitItem node,
                @Nullable Void context
        ) {
            var resultType = session.requireValueType(node.resultValueId());
            var resultSlotId = session.slotIdForValue(node.resultValueId());
            var operandValueId = node.operandValueIdOrNull();
            if (operandValueId == null) {
                return lowerVoidRedundantAwait(session, block, node, resultType, resultSlotId);
            }
            var operandType = session.requireValueType(operandValueId);
            var operandSlotId = session.slotIdForValue(operandValueId);
            if (operandType instanceof GdSignalType || operandType instanceof GdVariantType) {
                return emitAwaitInsn(session, block, node, resultSlotId, operandSlotId, operandType);
            }
            if (isRedundantAwaitOperandCall(session, node)) {
                return lowerRedundantAwaitPassthrough(
                        session, block, resultSlotId, operandSlotId, operandType, resultType
                );
            }
            if (operandType instanceof GdccCoroStateType) {
                return emitAwaitInsn(session, block, node, resultSlotId, operandSlotId, operandType);
            }
            throw session.unsupportedSequenceItem(
                    node,
                    "await operand of type " + operandType.getTypeName()
                            + " has no published signal/coroutine/dynamic route; sema should have rejected it"
            );
        }

        /// Shared suspend-instruction emission with the coroutine-context invariant: sema marks
        /// every function containing a signal/dynamic/coroutine-call await, so an unmarked target
        /// means the skeleton pass or the fixed-point pass dropped the fact.
        private @NotNull LirBasicBlock emitAwaitInsn(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AwaitItem node,
                @NotNull String resultSlotId,
                @NotNull String operandSlotId,
                @NotNull GdType operandType
        ) {
            if (!session.isTargetFunctionCoroutine()) {
                throw session.unsupportedSequenceItem(
                        node,
                        "await on " + operandType.getTypeName()
                                + " operand requires the enclosing function to be marked as a coroutine"
                );
            }
            block.appendNonTerminatorInstruction(new AwaitInsn(resultSlotId, operandSlotId));
            return block;
        }

        /// Redundant await on a statically known non-coroutine call: pure pass-through of the call
        /// result into the await result slot (both are typed by the same callee return type, so the
        /// boundary materialization is a direct assign in practice). The route itself was decided
        /// by the caller from the published call fact, so the item node is not needed here.
        private @NotNull LirBasicBlock lowerRedundantAwaitPassthrough(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull String resultSlotId,
                @NotNull String operandSlotId,
                @NotNull GdType operandType,
                @NotNull GdType resultType
        ) {
            var materializedOperandSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    operandSlotId,
                    operandType,
                    resultType,
                    "await_redundant_passthrough"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId, materializedOperandSlotId));
            return block;
        }

        /// Void-callee redundant await: the call already ran for side effects on the no-result
        /// path; the resume value is nil (Godot `REDUNDANT_AWAIT` on void calls).
        private @NotNull LirBasicBlock lowerVoidRedundantAwait(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull AwaitItem node,
                @NotNull GdType resultType,
                @NotNull String resultSlotId
        ) {
            var operandCall = awaitOperandResolvedCallOrNull(session, node);
            if (operandCall == null
                    || !(operandCall.returnType() instanceof GdVoidType)
                    || !(resultType instanceof GdVariantType)) {
                throw session.unsupportedSequenceItem(
                        node,
                        "await without an operand value requires a resolved-void non-coroutine call operand"
                                + " and a Variant result"
                );
            }
            block.appendNonTerminatorInstruction(new LiteralNullInsn(resultSlotId));
            return block;
        }

        /// An await operand counts as a redundant-await call when it is call-shaped, has a RESOLVED
        /// exact call fact, and its callee is not a sema-marked coroutine. DYNAMIC fallback routes
        /// are deliberately excluded: they keep the runtime-dynamic await path.
        private boolean isRedundantAwaitOperandCall(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull AwaitItem node
        ) {
            var operandCall = awaitOperandResolvedCallOrNull(session, node);
            return operandCall != null
                    && operandCall.status() == FrontendCallResolutionStatus.RESOLVED
                    && !session.isCoroutineCall(requireAwaitOperandCallAnchor(node));
        }

        private @Nullable FrontendResolvedCall awaitOperandResolvedCallOrNull(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull AwaitItem node
        ) {
            var anchor = awaitOperandCallAnchorOrNull(node);
            return anchor == null ? null : session.findResolvedCallOrNull(anchor);
        }

        private @NotNull Node requireAwaitOperandCallAnchor(@NotNull AwaitItem node) {
            return Objects.requireNonNull(
                    awaitOperandCallAnchorOrNull(node),
                    "redundant await passthrough requires a call-shaped operand anchor"
            );
        }

        /// Mirrors the sema-side await operand anchoring: bare calls key on the `CallExpression`,
        /// chain calls on their last `AttributeCallStep`.
        private @Nullable Node awaitOperandCallAnchorOrNull(@NotNull AwaitItem node) {
            return switch (node.expression().value()) {
                case CallExpression callExpression -> callExpression;
                case AttributeExpression attributeExpression
                        when !attributeExpression.steps().isEmpty()
                        && attributeExpression.steps().getLast() instanceof AttributeCallStep callStep -> callStep;
                default -> null;
            };
        }
    }

    /// Lowers array/dictionary literals via plan-driven boundary materialization + one construct insn.
    ///
    /// Contract (plan §6.3 / §7.2):
    /// - result slot is the pre-declared `cfg_tmp_*` TEMP_SLOT for the item
    /// - each operand uses frozen plan `sourceType`/`targetType` through `materializeFrontendBoundaryValue`
    /// - no re-walk of AST children, no re-decide of compatibility, no opaque fallback
    private static final class FrontendContainerLiteralInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ContainerLiteralItem, Void> {
        @Override
        public @NotNull Class<ContainerLiteralItem> nodeType() {
            return ContainerLiteralItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ContainerLiteralItem node,
                @Nullable Void context
        ) {
            var plan = session.requireContainerLiteralPlan(node.expression());
            var operandValueIds = node.operandValueIds();
            if (operandValueIds.size() != plan.operands().size()) {
                throw new IllegalStateException(
                        "ContainerLiteralItem operand count "
                                + operandValueIds.size()
                                + " does not match published plan operand count "
                                + plan.operands().size()
                                + " at "
                                + node.expression().range()
                );
            }
            for (var planOperand : plan.operands()) {
                if (planOperand.decision() == FrontendVariantBoundaryCompatibility.Decision.REJECT) {
                    throw new IllegalStateException(
                            "ContainerLiteralItem plan still carries REJECT decision for operand sourceIndex="
                                    + planOperand.sourceIndex()
                                    + " role="
                                    + planOperand.role()
                                    + " at "
                                    + node.expression().range()
                    );
                }
            }

            var materializedOperands = new ArrayList<LirInstruction.Operand>(operandValueIds.size());
            for (var index = 0; index < operandValueIds.size(); index++) {
                var operandValueId = operandValueIds.get(index);
                var planOperand = plan.operands().get(index);
                var boundaryUse = "container_literal_" + planOperand.role().name().toLowerCase() + "_" + index;
                // Consume frozen plan decision; do not re-query the conversion matrix.
                var materializedSlotId = session.materializeFrontendBoundaryValue(
                        block,
                        session.slotIdForValue(operandValueId),
                        planOperand.sourceType(),
                        planOperand.targetType(),
                        planOperand.decision(),
                        boundaryUse
                );
                materializedOperands.add(new LirInstruction.VariableOperand(materializedSlotId));
            }

            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            block.appendNonTerminatorInstruction(new ConstructContainerLiteralInsn(
                    resultSlotId,
                    List.copyOf(materializedOperands)
            ));
            return block;
        }
    }

    /// Lowers `value is T` / `value is not T` to a single `is_instance_of` (or a folded bool constant).
    ///
    /// Current type-test lowering contract:
    /// - RHS target comes from the published `typeTestTargets` side-table (never re-resolved here)
    /// - `UNRESOLVED_OBJECT` always emits runtime `is_instance_of` and never folds
    /// - known targets may fold to `true`/`false` when the operand static type decides the outcome
    /// - `Variant` target is the top type and always folds (`true` / `is not` → `false`)
    /// - `negated` is applied either by folding the constant or by wrapping with `unary_op NOT`
    /// - never expands into `get_variant_type` / multi-intrinsic LIR recipes
    private static final class FrontendTypeTestInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<TypeTestItem, Void> {
        @Override
        public @NotNull Class<TypeTestItem> nodeType() {
            return TypeTestItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull TypeTestItem node,
                @Nullable Void context
        ) {
            var expression = node.expression();
            var resultSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            var valueSlotId = session.slotIdForValue(node.operandValueId());
            var valueType = session.requireValueType(node.operandValueId());
            var target = session.requireTypeTestTarget(expression);

            return switch (target) {
                case FrontendTypeTestTarget.TargetUnresolvedObject(var unresolvedTypeName) ->
                    // Source identifier is intentionally not remapped; backend must force runtime.
                        emitIsInstanceOfWithOptionalNot(
                                session,
                                block,
                                resultSlotId,
                                unresolvedTypeName,
                                valueSlotId,
                                expression.negated()
                        );
                case FrontendTypeTestTarget.TargetKnown(var knownTargetType) -> {
                    var folded = TypeTestFoldUtil.fold(
                            session.classRegistry(),
                            valueType,
                            knownTargetType
                    );
                    if (folded != TypeTestFoldResult.RUNTIME_OPEN) {
                        var constant = expression.negated() != (folded == TypeTestFoldResult.TRUE);
                        block.appendNonTerminatorInstruction(new LiteralBoolInsn(resultSlotId, constant));
                        yield block;
                    }
                    yield emitIsInstanceOfWithOptionalNot(
                            session,
                            block,
                            resultSlotId,
                            knownTargetType.getTypeName(),
                            valueSlotId,
                            expression.negated()
                    );
                }
            };
        }

        private static @NotNull LirBasicBlock emitIsInstanceOfWithOptionalNot(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull String resultSlotId,
                @NotNull String typeName,
                @NotNull String valueSlotId,
                boolean negated
        ) {
            if (!negated) {
                block.appendNonTerminatorInstruction(new IsInstanceOfInsn(resultSlotId, typeName, valueSlotId));
                return block;
            }
            // Keep the intermediate positive test in a dedicated temp so the published result slot
            // only ever holds the final (possibly negated) bool.
            var positiveSlotId = session.allocateWritableRouteTemp("type_test_positive", GdBoolType.BOOL);
            block.appendNonTerminatorInstruction(new IsInstanceOfInsn(positiveSlotId, typeName, valueSlotId));
            block.appendNonTerminatorInstruction(new UnaryOpInsn(resultSlotId, GodotOperator.NOT, positiveSlotId));
            return block;
        }

    }

    /// Initializes the hidden loop-carried iterator state by calling the route's init intrinsic.
    ///
    /// The source operands are already materialized earlier in the init entry sequence; this processor
    /// only normalizes them into the intrinsic's `(start, end, step)` triple and writes the result into
    /// the predeclared hidden state slot. It publishes no ordinary value: the state slot is
    /// lowering-owned mutable storage, never a CFG value id.
    private static final class FrontendForLoopInitInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopInitItem, Void> {
        @Override
        public @NotNull Class<ForLoopInitItem> nodeType() {
            return ForLoopInitItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopInitItem node,
                @Nullable Void context
        ) {
            var argSlots = resolveInitArgumentSlots(session, block, node);
            var args = argSlots.stream()
                    .<LirInstruction.Operand>map(LirInstruction.VariableOperand::new)
                    .toList();
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    node.iteratorStateSlotId(),
                    node.initOperation().intrinsicName(),
                    args
            ));
            return block;
        }

        /// Resolves the init intrinsic argument slots. When the operand count already matches the
        /// contract's expected argument count (e.g. generic Variant route with a single source operand),
        /// operands pass through directly. Otherwise, range normalization pads 1..3 operands into the
        /// `(start, end, step)` triple by materializing implicit `0`/`1` int constants.
        private @NotNull List<String> resolveInitArgumentSlots(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopInitItem node
        ) {
            var operandSlots = node.operandValueIds().stream()
                    .map(session::slotIdForValue)
                    .toList();
            var expectedArgCount = node.initOperation().argumentTypes().size();
            if (operandSlots.size() == expectedArgCount) {
                return operandSlots;
            }
            return switch (operandSlots.size()) {
                case 1 -> List.of(
                        session.materializeForLoopIntConstant(block, 0L),
                        operandSlots.getFirst(),
                        session.materializeForLoopIntConstant(block, 1L)
                );
                case 2 -> List.of(
                        operandSlots.get(0),
                        operandSlots.get(1),
                        session.materializeForLoopIntConstant(block, 1L)
                );
                default -> throw new IllegalStateException(
                        "for-in init expects " + expectedArgCount + " contract arguments, but got "
                                + operandSlots.size() + " source operands"
                );
            };
        }
    }

    /// Publishes the ordinary `bool` loop condition by calling the route's should-continue intrinsic.
    ///
    /// The result lands in the predeclared `cfg_tmp_*` slot that the loop condition branch consumes
    /// directly; because the intrinsic already returns `bool`, no compiler-only condition normalization
    /// is triggered. The hidden state slot is read by reference and never appears as an ordinary operand.
    private static final class FrontendForLoopShouldContinueInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopShouldContinueItem, Void> {
        @Override
        public @NotNull Class<ForLoopShouldContinueItem> nodeType() {
            return ForLoopShouldContinueItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopShouldContinueItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId()),
                    node.shouldContinueOperation().intrinsicName(),
                    List.of(new LirInstruction.VariableOperand(node.iteratorStateSlotId()))
            ));
            return block;
        }
    }

    /// Reads the raw element from the hidden iterator state and commits the source-facing iterator local.
    ///
    /// The get intrinsic writes the raw element (typed as the contract's get result type) into a
    /// standalone `cfg_tmp_*` slot that intentionally does not alias the source local. The raw value is
    /// then converted, when required, into the final exposed iterator type through the shared frontend
    /// boundary helper and assigned into the predeclared source-facing slot before the body runs.
    private static final class FrontendForLoopGetInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopGetItem, Void> {
        @Override
        public @NotNull Class<ForLoopGetItem> nodeType() {
            return ForLoopGetItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopGetItem node,
                @Nullable Void context
        ) {
            var rawSlotId = FrontendBodyLoweringSupport.cfgTempSlotId(node.resultValueId());
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    rawSlotId,
                    node.getOperation().intrinsicName(),
                    List.of(new LirInstruction.VariableOperand(node.iteratorStateSlotId()))
            ));
            var sourceSlot = session.requireForSourceIteratorSlot(node.statement());
            var materializedSlotId = session.materializeFrontendBoundaryValue(
                    block,
                    rawSlotId,
                    node.getOperation().resultType(),
                    sourceSlot.exposedType(),
                    "for_in_get"
            );
            block.appendNonTerminatorInstruction(new AssignInsn(node.sourceIteratorSlotId(), materializedSlotId));
            return block;
        }
    }

    /// Advances the hidden iterator state by calling the route's next intrinsic.
    ///
    /// The next operation returns a new state value rather than mutating in place, so the result is
    /// written into the distinct predeclared next temp first and only then committed back into the state
    /// slot via an assign. This preserves a correct lifecycle order for future destroyable generic
    /// states and keeps the intrinsic result target distinct from the state argument slot.
    private static final class FrontendForLoopNextInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<ForLoopNextItem, Void> {
        @Override
        public @NotNull Class<ForLoopNextItem> nodeType() {
            return ForLoopNextItem.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull ForLoopNextItem node,
                @Nullable Void context
        ) {
            block.appendNonTerminatorInstruction(new CallIntrinsicInsn(
                    node.nextTempSlotId(),
                    node.nextOperation().intrinsicName(),
                    List.of(new LirInstruction.VariableOperand(node.iteratorStateSlotId()))
            ));
            block.appendNonTerminatorInstruction(new AssignInsn(node.iteratorStateSlotId(), node.nextTempSlotId()));
            return block;
        }
    }
}
