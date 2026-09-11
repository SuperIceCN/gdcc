package gd.script.gdcc.frontend.lowering.cfg;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.frontend.lowering.FrontendCallMutabilitySupport;
import gd.script.gdcc.frontend.lowering.FrontendSubscriptAccessSupport;
import gd.script.gdcc.frontend.lowering.FrontendWritableTypeWritebackSupport;
import gd.script.gdcc.frontend.lowering.cfg.item.AssertItem;
import gd.script.gdcc.frontend.lowering.cfg.item.AssignmentItem;
import gd.script.gdcc.frontend.lowering.cfg.item.AwaitItem;
import gd.script.gdcc.frontend.lowering.cfg.item.BoolConstantItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CallItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CastItem;
import gd.script.gdcc.frontend.lowering.cfg.item.ContainerLiteralItem;
import gd.script.gdcc.frontend.lowering.cfg.item.CompoundAssignmentBinaryOpItem;
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
import gd.script.gdcc.frontend.lowering.cfg.item.FrontendWritableRoutePayload;
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
import gd.script.gdcc.frontend.lowering.cfg.item.ValueOpItem;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendCfgRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendElifRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendForRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendIfRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendMatchRegion;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendMatchSectionAnchors;
import gd.script.gdcc.frontend.lowering.cfg.region.FrontendWhileRegion;
import gd.script.gdcc.frontend.lowering.ForLoweringContractRegistry;
import gd.script.gdcc.frontend.lowering.FrontendForLoweringContract;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.frontend.sema.FrontendCallResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendContainerLiteralPlan;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.FrontendForIterationPlan;
import gd.script.gdcc.frontend.sema.FrontendLambdaCapturePlan;
import gd.script.gdcc.frontend.sema.FrontendMatchPatternPlan;
import gd.script.gdcc.frontend.sema.FrontendMatchPatternRoute;
import gd.script.gdcc.frontend.sema.FrontendMatchPlan;
import gd.script.gdcc.frontend.sema.FrontendMatchSectionPlan;
import gd.script.gdcc.frontend.sema.FrontendMatchSupport;
import gd.script.gdcc.frontend.sema.FrontendMemberResolutionStatus;
import gd.script.gdcc.frontend.sema.FrontendReceiverKind;
import gd.script.gdcc.frontend.sema.FrontendResolvedCall;
import gd.script.gdcc.frontend.sema.FrontendResolvedMember;
import gd.script.gdcc.frontend.sema.analyzer.support.FrontendVariantBoundaryCompatibility;
import gd.script.gdcc.util.StringUtil;
import dev.superice.gdparser.frontend.ast.ArrayExpression;
import dev.superice.gdparser.frontend.ast.AssertStatement;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.AttributeCallStep;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.AttributePropertyStep;
import dev.superice.gdparser.frontend.ast.AttributeStep;
import dev.superice.gdparser.frontend.ast.AttributeSubscriptStep;
import dev.superice.gdparser.frontend.ast.AwaitExpression;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.BreakStatement;
import dev.superice.gdparser.frontend.ast.CallExpression;
import dev.superice.gdparser.frontend.ast.CastExpression;
import dev.superice.gdparser.frontend.ast.CommentStatement;
import dev.superice.gdparser.frontend.ast.ConditionalExpression;
import dev.superice.gdparser.frontend.ast.ContinueStatement;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.DictEntry;
import dev.superice.gdparser.frontend.ast.DictionaryExpression;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.PassStatement;
import dev.superice.gdparser.frontend.ast.ReturnStatement;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.SubscriptExpression;
import dev.superice.gdparser.frontend.ast.TypeTestExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import gd.script.gdcc.type.GdCompilerType;
import gd.script.gdcc.type.GdContainerType;
import gd.script.gdcc.type.GdExtensionTypeEnum;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.scope.PropertyDef;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdObjectType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Frontend CFG builder for one compile-ready executable body.
///
/// The graph stays frontend-only:
/// - `SequenceNode` holds explicit source-level value-op items
/// - `BranchNode` keeps the condition fragment root and published condition value id
/// - `StopNode` marks either a real function return or a synthetic fully-terminated merge anchor
///
/// This builder owns structured executable control flow for the current supported statement surface:
/// - straight-line statements and local `var`
/// - lexical-no-op `CommentStatement`, which stays compile-ready but publishes no CFG item
/// - `if` / `elif` / `else`
/// - `while`
/// - `match` (all six pattern routes, including ARRAY / DICTIONARY destructuring)
/// - loop-local `break` / `continue`
///
/// Short-circuit `and` / `or` now lower through explicit condition/value CFG paths:
/// - condition-context binaries expand into multi-branch condition subgraphs
/// - value-context binaries materialize branch-local `true` / `false` writes into one merged result
///   slot before continuation rejoins
///
/// `ConditionalExpression` reuses the same branch-result merge infrastructure:
/// - value-context ternaries evaluate the condition, then only the selected arm, and merge the arm
///   value into one shared result id
/// - condition-context ternaries expand as pure control flow and never produce a merge value
public final class FrontendCfgGraphBuilder {
    private @Nullable FrontendAnalysisData analysisData;
    private @Nullable LinkedHashMap<String, FrontendCfgGraph.NodeDef> nodes;
    private @Nullable FrontendAstSideTable<FrontendCfgRegion> regions;
    private @Nullable FrontendAstSideTable<FrontendForSourceIteratorSlot> forSourceIteratorSlots;
    private @Nullable FrontendAstSideTable<FrontendForIteratorStateSlot> forIteratorStateSlots;
    private @Nullable FrontendAstSideTable<FrontendMatchBindSlot> matchBindSlots;
    private @Nullable Set<PatternBindingExpression> foldedMatchBindDeclarations;
    private final @NotNull ArrayDeque<LoopFrame> loopStack = new ArrayDeque<>();
    private int nextSequenceIndex;
    private int nextBranchIndex;
    private int nextStopIndex;
    private int nextValueIndex;
    private int nextForIterIndex;

    /// Builds one executable-body frontend CFG graph plus every AST-keyed region published inside it.
    ///
    /// The builder consumes only compile-ready frontend facts. Reaching an unsupported statement or a
    /// missing lowering-ready side-table entry here therefore indicates a pipeline contract violation,
    /// and the builder fails fast instead of publishing a partial graph.
    public @NotNull ExecutableBodyBuild buildExecutableBody(
            @NotNull Block rootBlock,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(rootBlock, "rootBlock must not be null");
        initializeBuildState(analysisData);

        var fallthroughStopId = publishStopNode(FrontendCfgGraph.StopKind.RETURN, null);
        var rootBuild = buildBlock(rootBlock, fallthroughStopId);
        var entryId = rootBuild.entryId();
        if (entryId.equals(fallthroughStopId)) {
            entryId = publishSequenceNode(List.of(), fallthroughStopId);
            requireRegions().put(rootBlock, new FrontendCfgRegion.BlockRegion(entryId));
        } else if (!isNodeReferenced(fallthroughStopId)) {
            requireNodes().remove(fallthroughStopId);
        }

        return finishBuild(entryId);
    }

    /// Builds one expression-rooted frontend CFG graph for a property initializer helper.
    ///
    /// Property initializer contexts do not synthesize a fake `Block` wrapper. They reuse the same
    /// value/short-circuit graph core as executable bodies, then terminate in one synthetic
    /// `RETURN` stop carrying the initializer result value id. No structured region is published
    /// here because `FrontendCfgRegion` currently models block/branch/loop ownership only.
    public @NotNull ExecutableBodyBuild buildPropertyInitializer(
            @NotNull Expression rootExpression,
            @NotNull FrontendAnalysisData analysisData
    ) {
        Objects.requireNonNull(rootExpression, "rootExpression must not be null");
        initializeBuildState(analysisData);

        var rootBuild = buildValue(new BuildCursor(new OpenSequence(nextSequenceId())), rootExpression, null);
        var returnStopId = publishStopNode(FrontendCfgGraph.StopKind.RETURN, rootBuild.resultValueId());
        publishSequenceNode(
                rootBuild.cursor().currentSequence().id(),
                rootBuild.cursor().currentSequence().items(),
                returnStopId
        );
        return finishBuild(rootBuild.cursor().entryId());
    }

    private @NotNull BlockBuild buildBlock(@NotNull Block block, @NotNull String continuationId) {
        var state = new BlockState();
        for (var statement : block.statements()) {
            if (!state.reachable()) {
                break;
            }
            processStatement(state, statement);
        }

        var entryId = finalizeBlockState(state, continuationId);
        requireRegions().put(block, new FrontendCfgRegion.BlockRegion(entryId));
        return new BlockBuild(entryId, state.reachable());
    }

    private void processStatement(@NotNull BlockState state, @NotNull Statement statement) {
        switch (statement) {
            case CommentStatement _ -> {
                // Body comments are lexical trivia for lowering: accepted on compile surface, but
                // intentionally omitted from CFG publication so they do not emit runtime line markers.
            }
            case PassStatement passStatement ->
                    requireCurrentSequence(state).items().add(new SourceAnchorItem(passStatement));
            case ExpressionStatement expressionStatement ->
                    processExpressionStatement(state, expressionStatement.expression());
            case VariableDeclaration variableDeclaration when variableDeclaration.kind() == DeclarationKind.VAR ->
                    processLocalDeclaration(state, variableDeclaration);
            case ReturnStatement returnStatement -> processReturnStatement(state, returnStatement);
            case AssertStatement assertStatement -> processAssertStatement(state, assertStatement);
            case IfStatement ifStatement -> processIfStatement(state, ifStatement);
            case WhileStatement whileStatement -> processWhileStatement(state, whileStatement);
            case ForStatement forStatement -> processForStatement(state, forStatement);
            case MatchStatement matchStatement -> processMatchStatement(state, matchStatement);
            case BreakStatement breakStatement ->
                    processLoopJump(state, breakStatement, requireLoopFrame().breakTargetId());
            case ContinueStatement continueStatement -> processLoopJump(
                    state,
                    continueStatement,
                    requireLoopFrame().continueTargetId()
            );
            default -> throw unsupportedReachableStatement(statement);
        }
    }

    private void processLocalDeclaration(
            @NotNull BlockState state,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        var cursor = new BuildCursor(requireCurrentSequence(state));
        var initializer = variableDeclaration.value();
        ValueBuild initializerBuild = initializer == null
                ? null
                : buildValue(cursor, initializer, nextVariableValueId(variableDeclaration.name()));
        var currentCursor = initializerBuild == null ? cursor : initializerBuild.cursor();
        currentCursor.currentSequence().items().add(new LocalDeclarationItem(
                variableDeclaration,
                initializerBuild == null ? null : initializerBuild.resultValueId()
        ));
        state.setCurrentSequence(currentCursor.currentSequence());
    }

    private void processExpressionStatement(
            @NotNull BlockState state,
            @NotNull Expression expression
    ) {
        requireLoweringReadyExpressionType(expression);
        switch (expression) {
            case AssignmentExpression assignmentExpression -> state.setCurrentSequence(buildAssignmentCommit(
                    new BuildCursor(requireCurrentSequence(state)),
                    assignmentExpression
            ).currentSequence());
            default -> {
                var cursor = new BuildCursor(requireCurrentSequence(state));
                state.setCurrentSequence(
                        isDiscardedResolvedVoidCallExpression(expression)
                                ? buildDiscardedResolvedVoidCall(cursor, expression).currentSequence()
                                : buildValue(cursor, expression, null).cursor().currentSequence()
                );
            }
        }
    }

    /// Statement-position resolved-void calls are the one supported route that intentionally skips
    /// `ValueBuild`: the call still needs receiver/argument sequencing and writable payload
    /// publication, but there is no downstream consumer that should force a dead temp slot into CFG.
    private @NotNull BuildCursor buildDiscardedResolvedVoidCall(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression
    ) {
        return switch (expression) {
            case CallExpression callExpression -> buildDiscardedResolvedVoidBareCall(cursor, callExpression);
            case AttributeExpression attributeExpression -> buildDiscardedResolvedVoidAttributeExpression(
                    cursor,
                    attributeExpression
            );
            default -> throw new IllegalStateException(
                    "Discarded resolved-void call path requires a call-shaped expression, but got "
                            + expression.getClass().getSimpleName()
            );
        };
    }

    private @NotNull BuildCursor buildDiscardedResolvedVoidBareCall(
            @NotNull BuildCursor cursor,
            @NotNull CallExpression callExpression
    ) {
        if (!(callExpression.callee() instanceof IdentifierExpression)) {
            throw new IllegalStateException(
                    "Bare call lowering currently requires an IdentifierExpression callee, but got "
                            + callExpression.callee().getClass().getSimpleName()
            );
        }
        var publishedCall = requireDiscardedResolvedVoidCall(callExpression);
        var argumentsBuild = buildArgumentValues(cursor, callExpression.arguments());
        argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                callExpression,
                publishedCall.callableName(),
                null,
                argumentsBuild.valueIds(),
                null
        ));
        return argumentsBuild.cursor();
    }

    private @NotNull BuildCursor buildDiscardedResolvedVoidAttributeExpression(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression must contain at least one step");
        }
        if (!(attributeExpression.steps().getLast() instanceof AttributeCallStep finalCallStep)) {
            throw new IllegalStateException(
                    "Discarded resolved-void attribute expression must end in AttributeCallStep"
            );
        }
        var publishedCall = requireDiscardedResolvedVoidCall(finalCallStep);

        if (isTypeMetaHeadAttributeExpression(attributeExpression)) {
            if (attributeExpression.steps().size() == 1) {
                return buildDiscardedResolvedVoidTypeMetaHeadCall(cursor, finalCallStep, publishedCall);
            }
            var currentBuild = buildTypeMetaHeadFirstStepValue(
                    cursor,
                    attributeExpression,
                    attributeExpression.steps().getFirst(),
                    null
            );
            for (var stepIndex = 1; stepIndex + 1 < attributeExpression.steps().size(); stepIndex++) {
                currentBuild = applyAttributeStep(currentBuild, attributeExpression.steps().get(stepIndex), null);
            }
            return emitDiscardedResolvedVoidAttributeCall(currentBuild, finalCallStep, publishedCall);
        }

        var currentBuild = buildValue(cursor, attributeExpression.base(), null);
        for (var stepIndex = 0; stepIndex + 1 < attributeExpression.steps().size(); stepIndex++) {
            currentBuild = applyAttributeStep(currentBuild, attributeExpression.steps().get(stepIndex), null);
        }
        return emitDiscardedResolvedVoidAttributeCall(currentBuild, finalCallStep, publishedCall);
    }

    private @NotNull BuildCursor buildDiscardedResolvedVoidTypeMetaHeadCall(
            @NotNull BuildCursor cursor,
            @NotNull AttributeCallStep attributeCallStep,
            @NotNull FrontendResolvedCall publishedCall
    ) {
        var argumentsBuild = buildArgumentValues(cursor, attributeCallStep.arguments());
        argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                attributeCallStep,
                publishedCall.callableName(),
                null,
                argumentsBuild.valueIds(),
                null
        ));
        return argumentsBuild.cursor();
    }

    private @NotNull BuildCursor emitDiscardedResolvedVoidAttributeCall(
            @NotNull ValueBuild receiverBuild,
            @NotNull AttributeCallStep attributeCallStep,
            @NotNull FrontendResolvedCall publishedCall
    ) {
        receiverBuild = maybePublishDirectSlotReceiverAlias(
                receiverBuild,
                publishedCall,
                attributeCallStep.arguments()
        );
        var argumentsBuild = buildArgumentValues(receiverBuild.cursor(), attributeCallStep.arguments());
        var receiverRoute = routePayloadOrValueRoot(receiverBuild);
        argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                attributeCallStep,
                publishedCall.callableName(),
                receiverBuild.resultValueId(),
                argumentsBuild.valueIds(),
                null,
                new FrontendWritableRoutePayload(
                        attributeCallStep,
                        receiverRoute.root(),
                        receiverRoute.leaf(),
                        appendCallReceiverCommitSteps(receiverRoute, publishedCall)
                )
        ));
        return argumentsBuild.cursor();
    }

    private void processReturnStatement(@NotNull BlockState state, @NotNull ReturnStatement returnStatement) {
        var returnValue = returnStatement.value();
        String returnValueId = null;
        if (returnValue != null) {
            var returnBuild = buildValue(new BuildCursor(requireCurrentSequence(state)), returnValue, null);
            state.setCurrentSequence(returnBuild.cursor().currentSequence());
            returnValueId = returnBuild.resultValueId();
        } else {
            requireCurrentSequence(state).items().add(new SourceAnchorItem(returnStatement));
        }
        closeCurrentSequence(state, publishStopNode(FrontendCfgGraph.StopKind.RETURN, returnValueId));
        state.setReachable(false);
    }

    /// `assert` stays an ordinary linear statement on the compile surface: condition and optional
    /// message are materialized as regular values in lexical order, then one `AssertItem` records
    /// the consumption. Truthiness normalization and the final `AssertInsn` belong to body lowering.
    private void processAssertStatement(@NotNull BlockState state, @NotNull AssertStatement assertStatement) {
        var cursor = new BuildCursor(requireCurrentSequence(state));
        var conditionBuild = buildValue(cursor, assertStatement.condition(), null);
        var currentCursor = conditionBuild.cursor();
        String messageValueId = null;
        var message = assertStatement.message();
        if (message != null) {
            var messageBuild = buildValue(currentCursor, message, null);
            currentCursor = messageBuild.cursor();
            messageValueId = messageBuild.resultValueId();
        }
        currentCursor.currentSequence().items().add(new AssertItem(
                assertStatement,
                conditionBuild.resultValueId(),
                messageValueId
        ));
        state.setCurrentSequence(currentCursor.currentSequence());
    }

    private void processLoopJump(@NotNull BlockState state, @NotNull Statement statement, @NotNull String targetId) {
        var sequence = requireCurrentSequence(state);
        sequence.items().add(new SourceAnchorItem(statement));
        closeCurrentSequence(state, targetId);
        state.setReachable(false);
    }

    private void processIfStatement(@NotNull BlockState state, @NotNull IfStatement ifStatement) {
        var mergeSequence = new OpenSequence(nextSequenceId());
        var thenBuild = buildBlock(ifStatement.body(), mergeSequence.id());
        var falseBuild = buildIfFalseChain(ifStatement.elifClauses(), ifStatement.elseBody(), mergeSequence.id());
        var conditionBuild = buildCondition(ifStatement.condition(), thenBuild.entryId(), falseBuild.entryId());
        attachStructuredEntry(state, conditionBuild.entryId());

        var fallsThrough = thenBuild.fallsThrough() || falseBuild.fallsThrough();
        var mergeId = fallsThrough
                ? mergeSequence.id()
                : publishStopNode(FrontendCfgGraph.StopKind.TERMINAL_MERGE, null);
        requireRegions().put(
                ifStatement,
                new FrontendIfRegion(
                        conditionBuild.entryId(),
                        thenBuild.entryId(),
                        falseBuild.entryId(),
                        mergeId
                )
        );

        if (fallsThrough) {
            state.setCurrentSequence(mergeSequence);
            state.setReachable(true);
            return;
        }
        state.setCurrentSequence(null);
        state.setReachable(false);
    }

    private @NotNull ClauseBuild buildIfFalseChain(
            @NotNull List<ElifClause> elifClauses,
            @Nullable Block elseBody,
            @NotNull String mergeTargetId
    ) {
        if (!elifClauses.isEmpty()) {
            return buildElifChain(elifClauses, 0, elseBody, mergeTargetId);
        }
        if (elseBody != null) {
            var elseBuild = buildBlock(elseBody, mergeTargetId);
            return new ClauseBuild(elseBuild.entryId(), elseBuild.fallsThrough());
        }
        return new ClauseBuild(mergeTargetId, true);
    }

    private @NotNull ClauseBuild buildElifChain(
            @NotNull List<ElifClause> elifClauses,
            int clauseIndex,
            @Nullable Block elseBody,
            @NotNull String mergeTargetId
    ) {
        if (clauseIndex >= elifClauses.size()) {
            if (elseBody != null) {
                var elseBuild = buildBlock(elseBody, mergeTargetId);
                return new ClauseBuild(elseBuild.entryId(), elseBuild.fallsThrough());
            }
            return new ClauseBuild(mergeTargetId, true);
        }

        var elifClause = elifClauses.get(clauseIndex);
        var nextClause = buildElifChain(elifClauses, clauseIndex + 1, elseBody, mergeTargetId);
        var bodyBuild = buildBlock(elifClause.body(), mergeTargetId);
        var conditionBuild = buildCondition(elifClause.condition(), bodyBuild.entryId(), nextClause.entryId());
        requireRegions().put(
                elifClause,
                new FrontendElifRegion(
                        conditionBuild.entryId(),
                        bodyBuild.entryId(),
                        nextClause.entryId()
                )
        );
        return new ClauseBuild(conditionBuild.entryId(), bodyBuild.fallsThrough() || nextClause.fallsThrough());
    }

    private void processWhileStatement(@NotNull BlockState state, @NotNull WhileStatement whileStatement) {
        var exitSequence = new OpenSequence(nextSequenceId());
        var conditionCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        loopStack.push(new LoopFrame(conditionCursor.entryId(), exitSequence.id()));
        BlockBuild bodyBuild;
        try {
            bodyBuild = buildBlock(whileStatement.body(), conditionCursor.entryId());
        } finally {
            loopStack.pop();
        }

        var conditionBuild = buildCondition(
                conditionCursor,
                whileStatement.condition(),
                bodyBuild.entryId(),
                exitSequence.id()
        );
        attachStructuredEntry(state, conditionBuild.entryId());
        requireRegions().put(
                whileStatement,
                new FrontendWhileRegion(
                        conditionBuild.entryId(),
                        bodyBuild.entryId(),
                        exitSequence.id()
                )
        );
        state.setCurrentSequence(exitSequence);
        state.setReachable(true);
    }

    /// Materializes one compile-ready `for-in` loop into an explicit frontend CFG.
    ///
    /// The builder consumes only already-published facts: the iteration plan (route, iterator name,
    /// source operands), the final source-facing iterator slot type and the lowering contract queried
    /// from `ForLoweringContractRegistry`. It never re-derives iterable semantics, source iterator name
    /// or source-facing type from AST.
    ///
    /// CFG shape (continue targets the update entry, break targets the exit):
    ///
    /// ```text
    /// initEntry [operands..., ForLoopInitItem] -> conditionEntry [ForLoopShouldContinueItem] -> branch
    ///   branch true  -> bodyEntry [ForLoopGetItem] -> body statements -> updateEntry [ForLoopNextItem] -> conditionEntry
    ///   branch false -> exit
    /// ```
    private void processForStatement(@NotNull BlockState state, @NotNull ForStatement forStatement) {
        var plan = requireForIterationPlan(forStatement);
        var contract = requireForLoweringContract(plan);
        var stateSlot = allocateIteratorStateSlot(forStatement, contract);
        var sourceSlot = allocateSourceIteratorSlot(forStatement, plan);

        var exitSequence = new OpenSequence(nextSequenceId());
        var conditionSequence = new OpenSequence(nextSequenceId());
        var updateSequence = new OpenSequence(nextSequenceId());

        loopStack.push(new LoopFrame(updateSequence.id(), exitSequence.id()));
        BlockBuild bodyBlockBuild;
        try {
            bodyBlockBuild = buildBlock(forStatement.body(), updateSequence.id());
        } finally {
            loopStack.pop();
        }

        var bodyEntryId = publishForBodyGetEntry(forStatement, contract, stateSlot, sourceSlot, bodyBlockBuild.entryId());
        publishForUpdateEntry(forStatement, contract, stateSlot, updateSequence, conditionSequence.id());
        publishForConditionEntry(forStatement, contract, stateSlot, conditionSequence, bodyEntryId, exitSequence.id());
        var initEntryId = publishForInitEntry(forStatement, contract, stateSlot, plan, conditionSequence.id());

        attachStructuredEntry(state, initEntryId);
        requireRegions().put(
                forStatement,
                new FrontendForRegion(
                        initEntryId,
                        conditionSequence.id(),
                        bodyEntryId,
                        updateSequence.id(),
                        exitSequence.id(),
                        sourceSlot.sourceIteratorSlotId(),
                        stateSlot.slotId()
                )
        );
        requireForSourceIteratorSlots().put(forStatement, sourceSlot);
        requireForIteratorStateSlots().put(forStatement, stateSlot);

        state.setCurrentSequence(exitSequence);
        state.setReachable(true);
    }

    /// Materializes one compile-ready `match` into an explicit frontend CFG.
    ///
    /// Subject `buildValue` runs once in the header. Sections are a miss-chained `BranchNode`
    /// spine: a hit enters that section's bind/guard/body path, a miss continues to the next
    /// section, and the last miss plus every body exit join at `mergeId` (or `TERMINAL_MERGE`
    /// when every reachable path terminates). ARRAY / DICTIONARY destructuring lowers through
    /// typeof gate, one container materialization, length gate, then per-element/entry fetch
    /// with recursive sub-pattern tests.
    private void processMatchStatement(@NotNull BlockState state, @NotNull MatchStatement matchStatement) {
        var plan = requireMatchPlan(matchStatement);
        requireMatchRoutesReady(plan);

        var mergeSequence = new OpenSequence(nextSequenceId());
        var sectionBodies = new ArrayList<BlockBuild>();
        for (var sectionPlan : plan.sections()) {
            sectionBodies.add(buildBlock(sectionPlan.section().body(), mergeSequence.id()));
        }

        var headerCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var subjectBuild = buildValue(headerCursor, matchStatement.value(), null);
        var subjectValueId = subjectBuild.resultValueId();
        var subjectType = requirePublishedMatchValueType(matchStatement.value());
        var subjectFamily = FrontendMatchSupport.typeFamilyOrNull(subjectType);
        var workingCursor = subjectBuild.cursor();
        String subjectTypeValueId = null;
        if (needsSubjectTypeId(plan, subjectFamily)) {
            var typeBuild = emitGetVariantType(workingCursor, matchStatement, subjectValueId);
            workingCursor = typeBuild.cursor();
            subjectTypeValueId = typeBuild.resultValueId();
        }

        var sectionAnchors = new FrontendMatchSectionAnchors[plan.sections().size()];
        var nextMissId = mergeSequence.id();
        var anyBodyFallsThrough = false;
        for (var index = plan.sections().size() - 1; index >= 0; index--) {
            var sectionPlan = plan.sections().get(index);
            var bodyBuild = sectionBodies.get(index);
            anyBodyFallsThrough = anyBodyFallsThrough || bodyBuild.fallsThrough();
            var sectionBuild = buildMatchSection(
                    sectionPlan,
                    subjectValueId,
                    subjectFamily,
                    subjectTypeValueId,
                    bodyBuild.entryId(),
                    nextMissId
            );
            sectionAnchors[index] = new FrontendMatchSectionAnchors(
                    sectionBuild.testEntryId(),
                    sectionBuild.bodyEntryId()
            );
            nextMissId = sectionBuild.testEntryId();
        }

        var firstTestEntryId = plan.sections().isEmpty() ? mergeSequence.id() : sectionAnchors[0].testEntryId();
        publishSequenceNode(
                workingCursor.currentSequence().id(),
                workingCursor.currentSequence().items(),
                firstTestEntryId
        );
        var headerEntryId = workingCursor.entryId();

        var missReachable = plan.sections().isEmpty()
                || !isUnconditionalCatchAll(plan.sections().getLast());
        var fallsThrough = anyBodyFallsThrough || missReachable;
        var mergeId = fallsThrough
                ? mergeSequence.id()
                : publishStopNode(FrontendCfgGraph.StopKind.TERMINAL_MERGE, null);

        attachStructuredEntry(state, headerEntryId);
        requireRegions().put(
                matchStatement,
                new FrontendMatchRegion(headerEntryId, List.of(sectionAnchors), mergeId)
        );

        if (fallsThrough) {
            state.setCurrentSequence(mergeSequence);
            state.setReachable(true);
            return;
        }
        state.setCurrentSequence(null);
        state.setReachable(false);
    }

    private @NotNull MatchSectionBuild buildMatchSection(
            @NotNull FrontendMatchSectionPlan sectionPlan,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @NotNull String bodyStatementsEntryId,
            @NotNull String nextMissId
    ) {
        // Every bind slot of the section is allocated up front, mirroring Godot creating bind
        // locals before pattern compilation: a statically folded container pattern keeps its
        // nested slots so the still-built but unreachable body can read them, while only the
        // emitted test fragments commit the matching `MatchBindItem`s.
        var topLevelSlots = new ArrayList<FrontendMatchBindSlot>();
        for (var patternPlan : sectionPlan.patterns()) {
            for (var binding : patternPlan.bindings()) {
                var slot = allocateMatchBindSlot(sectionPlan.section(), binding.declaration());
                if (binding.topLevel()) {
                    topLevelSlots.add(slot);
                }
            }
        }

        var hitTargetId = bodyStatementsEntryId;
        var bodyEntryId = bodyStatementsEntryId;
        if (!topLevelSlots.isEmpty()) {
            var bindSequence = new OpenSequence(nextSequenceId());
            for (var bindSlot : topLevelSlots) {
                bindSequence.items().add(new MatchBindItem(
                        bindSlot.declaration(),
                        subjectValueId,
                        bindSlot.bindSlotId()
                ));
            }
            // Bind commits on the hit path before the guard so `when` can read the new locals.
            var afterBindId = bodyStatementsEntryId;
            if (sectionPlan.hasGuard()) {
                var guard = Objects.requireNonNull(sectionPlan.section().guard(), "guard");
                afterBindId = buildCondition(guard, bodyStatementsEntryId, nextMissId).entryId();
            }
            publishSequenceNode(bindSequence.id(), bindSequence.items(), afterBindId);
            hitTargetId = bindSequence.id();
            bodyEntryId = bindSequence.id();
        } else if (sectionPlan.hasGuard()) {
            var guard = Objects.requireNonNull(sectionPlan.section().guard(), "guard");
            hitTargetId = buildCondition(guard, bodyStatementsEntryId, nextMissId).entryId();
        }

        var nextPatternMissId = nextMissId;
        var testEntryId = hitTargetId;
        var patterns = sectionPlan.patterns();
        for (var index = patterns.size() - 1; index >= 0; index--) {
            var patternPlan = patterns.get(index);
            testEntryId = buildMatchPatternTest(
                    patternPlan,
                    subjectValueId,
                    subjectFamily,
                    subjectTypeValueId,
                    hitTargetId,
                    nextPatternMissId
            );
            nextPatternMissId = testEntryId;
        }
        return new MatchSectionBuild(testEntryId, bodyEntryId);
    }

    private @NotNull String buildMatchPatternTest(
            @NotNull FrontendMatchPatternPlan patternPlan,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        return switch (patternPlan.route()) {
            // Top-level WILDCARD / BINDING need no test; the bind commits at the body entry.
            case WILDCARD, BINDING -> trueTargetId;
            case LITERAL, EXPRESSION -> buildMatchValuePatternTest(
                    patternPlan.patternNode(),
                    patternPlan.route(),
                    subjectValueId,
                    subjectFamily,
                    subjectTypeValueId,
                    trueTargetId,
                    falseTargetId
            );
            case ARRAY -> {
                // A subject whose static family can never be an Array folds to the miss target
                // without a test fragment; the sub-patterns (including possible runtime
                // expressions) would never evaluate behind the failed runtime typeof gate either,
                // so the fold preserves observable behavior.
                if (subjectFamily != null && subjectFamily != GdExtensionTypeEnum.ARRAY) {
                    recordFoldedMatchBindDeclarations(patternPlan);
                    yield falseTargetId;
                }
                yield buildMatchArrayPatternTest(
                        (ArrayExpression) patternPlan.patternNode(),
                        subjectValueId,
                        subjectFamily,
                        subjectTypeValueId,
                        trueTargetId,
                        falseTargetId
                );
            }
            case DICTIONARY -> {
                if (subjectFamily != null && subjectFamily != GdExtensionTypeEnum.DICTIONARY) {
                    recordFoldedMatchBindDeclarations(patternPlan);
                    yield falseTargetId;
                }
                yield buildMatchDictionaryPatternTest(
                        (DictionaryExpression) patternPlan.patternNode(),
                        subjectValueId,
                        subjectFamily,
                        subjectTypeValueId,
                        trueTargetId,
                        falseTargetId
                );
            }
        };
    }

    /// Records the binds of a statically folded container pattern so the artifact validation
    /// accepts their slots without a committed `MatchBindItem`.
    private void recordFoldedMatchBindDeclarations(@NotNull FrontendMatchPatternPlan patternPlan) {
        for (var binding : patternPlan.bindings()) {
            requireFoldedMatchBindDeclarations().add(binding.declaration());
        }
    }

    /// Recursive sub-pattern dispatch inside ARRAY / DICTIONARY destructuring.
    ///
    /// The tested value is always a freshly fetched Variant element temp, so no static family is
    /// known and no shared type temp exists; value tests emit their own local `get_variant_type`
    /// when they need one. A nested BINDING always matches and commits the element into its
    /// pre-allocated bind slot right here in the test fragment, mirroring Godot's
    /// `_parse_match_pattern` PT_BIND assignment under the accumulated short-circuit chain.
    private @NotNull String buildNestedMatchPatternTest(
            @NotNull Expression pattern,
            @NotNull FrontendMatchPatternRoute route,
            @NotNull String elementValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        return switch (route) {
            case WILDCARD -> trueTargetId;
            case BINDING -> {
                var declaration = (PatternBindingExpression) pattern;
                var bindSlot = requireMatchBindSlots().get(declaration);
                if (bindSlot == null) {
                    throw new IllegalStateException(
                            "nested match bind '" + declaration.name() + "' at " + declaration.range()
                                    + " has no pre-allocated bind slot"
                    );
                }
                var bindSequence = new OpenSequence(nextSequenceId());
                bindSequence.items().add(new MatchBindItem(declaration, elementValueId, bindSlot.bindSlotId()));
                publishSequenceNode(bindSequence.id(), bindSequence.items(), trueTargetId);
                yield bindSequence.id();
            }
            case LITERAL, EXPRESSION -> buildMatchValuePatternTest(
                    pattern,
                    route,
                    elementValueId,
                    null,
                    null,
                    trueTargetId,
                    falseTargetId
            );
            case ARRAY -> buildMatchArrayPatternTest(
                    (ArrayExpression) pattern,
                    elementValueId,
                    null,
                    null,
                    trueTargetId,
                    falseTargetId
            );
            case DICTIONARY -> buildMatchDictionaryPatternTest(
                    (DictionaryExpression) pattern,
                    elementValueId,
                    null,
                    null,
                    trueTargetId,
                    falseTargetId
            );
        };
    }

    /// Builds one ARRAY destructuring test chain:
    /// `[typeof gate] -> materialize -> length gate -> per-element fetch + recursive sub-tests`.
    ///
    /// Every gate's miss edge lands on `falseTargetId`, so a failed length or element test falls
    /// to the next section without running later element tests (short-circuit, aligned with Godot).
    /// Statically incompatible subject families fold at the dispatch site instead of here.
    private @NotNull String buildMatchArrayPatternTest(
            @NotNull ArrayExpression pattern,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var containerValueId = nextValueId();
        var elements = pattern.elements();
        var nextId = trueTargetId;
        for (var index = elements.size() - 1; index >= 0; index--) {
            var element = elements.get(index);
            var elementRoute = FrontendMatchSupport.classifyPatternRoute(element);
            if (elementRoute == FrontendMatchPatternRoute.WILDCARD) {
                // `_` matches anything, so it needs neither a fetch nor a test.
                continue;
            }
            nextId = buildMatchArrayElementTest(
                    element,
                    elementRoute,
                    index,
                    containerValueId,
                    nextId,
                    falseTargetId
            );
        }
        nextId = publishMatchLengthGate(
                pattern,
                containerValueId,
                elements.size(),
                pattern.openEnded(),
                nextId,
                falseTargetId
        );
        return publishMatchContainerMaterialize(
                pattern,
                subjectValueId,
                FrontendMatchPatternRoute.ARRAY,
                containerValueId,
                subjectFamily,
                subjectTypeValueId,
                nextId,
                falseTargetId
        );
    }

    /// Builds one DICTIONARY destructuring test chain:
    /// `[typeof gate] -> materialize -> length gate -> per-entry has(key) -> fetch + value test`.
    ///
    /// Entry keys are constants (type-check owns the Godot rule); a `_` value pattern degenerates
    /// to the has-check alone and skips the fetch entirely.
    private @NotNull String buildMatchDictionaryPatternTest(
            @NotNull DictionaryExpression pattern,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var containerValueId = nextValueId();
        var entries = pattern.entries();
        var nextId = trueTargetId;
        for (var index = entries.size() - 1; index >= 0; index--) {
            nextId = buildMatchDictionaryEntryTest(
                    entries.get(index),
                    containerValueId,
                    nextId,
                    falseTargetId
            );
        }
        nextId = publishMatchLengthGate(
                pattern,
                containerValueId,
                entries.size(),
                pattern.openEnded(),
                nextId,
                falseTargetId
        );
        return publishMatchContainerMaterialize(
                pattern,
                subjectValueId,
                FrontendMatchPatternRoute.DICTIONARY,
                containerValueId,
                subjectFamily,
                subjectTypeValueId,
                nextId,
                falseTargetId
        );
    }

    /// Publishes the typeof gate (only for a Variant / statically unknown subject) followed by the
    /// single container materialization sequence that every later gate of this pattern consumes.
    private @NotNull String publishMatchContainerMaterialize(
            @NotNull Expression pattern,
            @NotNull String sourceValueId,
            @NotNull FrontendMatchPatternRoute containerRoute,
            @NotNull String containerValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @NotNull String nextId,
            @NotNull String falseTargetId
    ) {
        var materializeSequence = new OpenSequence(nextSequenceId());
        materializeSequence.items().add(new MatchContainerMaterializeItem(
                pattern,
                sourceValueId,
                containerRoute,
                containerValueId
        ));
        publishSequenceNode(materializeSequence.id(), materializeSequence.items(), nextId);
        if (subjectFamily != null) {
            return materializeSequence.id();
        }
        var expectedFamily = containerRoute == FrontendMatchPatternRoute.ARRAY
                ? GdExtensionTypeEnum.ARRAY
                : GdExtensionTypeEnum.DICTIONARY;
        var gateCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var typeId = requireSubjectTypeValueId(subjectTypeValueId, gateCursor, pattern, sourceValueId);
        return publishConstantTypeGate(
                typeId.cursor(),
                pattern,
                typeId.resultValueId(),
                expectedFamily,
                materializeSequence.id(),
                falseTargetId
        );
    }

    /// Publishes the length gate sequence plus its branch: `size() == count` for a closed pattern,
    /// `size() >= count` for one ending with `..`.
    private @NotNull String publishMatchLengthGate(
            @NotNull Expression pattern,
            @NotNull String containerValueId,
            int expectedCount,
            boolean openEnded,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var cursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new MatchLengthCheckItem(
                pattern,
                containerValueId,
                expectedCount,
                openEnded,
                resultValueId
        ));
        return publishConditionBranch(
                cursor.entryId(),
                cursor.currentSequence(),
                pattern,
                resultValueId,
                trueTargetId,
                falseTargetId
        ).entryId();
    }

    /// Publishes one array element's fetch sequence (`variant_get_indexed` at lowering) chained
    /// into the element's recursive sub-pattern test.
    private @NotNull String buildMatchArrayElementTest(
            @NotNull Expression element,
            @NotNull FrontendMatchPatternRoute elementRoute,
            int index,
            @NotNull String containerValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var cursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var indexBuild = emitIntConstant(cursor, element, index);
        var fetch = emitMatchElementFetch(indexBuild.cursor(), element, containerValueId, indexBuild.resultValueId());
        var subTestEntryId = buildNestedMatchPatternTest(
                element,
                elementRoute,
                fetch.resultValueId(),
                trueTargetId,
                falseTargetId
        );
        publishSequenceNode(
                fetch.cursor().currentSequence().id(),
                fetch.cursor().currentSequence().items(),
                subTestEntryId
        );
        return cursor.entryId();
    }

    /// Publishes one dictionary entry's `has(key)` gate, and behind its true edge the value fetch
    /// plus the value pattern's recursive test. The key constant is materialized once in the gate
    /// sequence and reused by the fetch.
    private @NotNull String buildMatchDictionaryEntryTest(
            @NotNull DictEntry entry,
            @NotNull String containerValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        // The §5.6-4 key-constant rule is owned by type-check; a non-constant key reaching CFG
        // means the analyzer pipeline broke, so fail fast instead of lowering a runtime key read.
        if (!FrontendMatchSupport.isConstantPatternOperand(requireAnalysisData(), entry.key())) {
            throw new IllegalStateException(
                    "dictionary pattern key is not a published constant at " + entry.key().range()
            );
        }
        var gateCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var keyBuild = buildValue(gateCursor, entry.key(), null);
        var hasBuild = emitMatchHasKey(keyBuild.cursor(), entry.key(), containerValueId, keyBuild.resultValueId());
        var valuePattern = entry.value();
        var valueRoute = FrontendMatchSupport.classifyPatternRoute(valuePattern);
        var valueTestEntryId = trueTargetId;
        if (valueRoute != FrontendMatchPatternRoute.WILDCARD) {
            var fetchCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
            var fetch = emitMatchElementFetch(fetchCursor, valuePattern, containerValueId, keyBuild.resultValueId());
            var subTestEntryId = buildNestedMatchPatternTest(
                    valuePattern,
                    valueRoute,
                    fetch.resultValueId(),
                    trueTargetId,
                    falseTargetId
            );
            publishSequenceNode(
                    fetch.cursor().currentSequence().id(),
                    fetch.cursor().currentSequence().items(),
                    subTestEntryId
            );
            valueTestEntryId = fetchCursor.entryId();
        }
        publishConditionBranch(
                gateCursor.entryId(),
                hasBuild.cursor().currentSequence(),
                entry.key(),
                hasBuild.resultValueId(),
                valueTestEntryId,
                falseTargetId
        );
        return gateCursor.entryId();
    }

    private @NotNull ValueBuild emitMatchHasKey(
            @NotNull BuildCursor cursor,
            @NotNull Node anchor,
            @NotNull String dictionaryValueId,
            @NotNull String keyValueId
    ) {
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new MatchHasKeyItem(anchor, dictionaryValueId, keyValueId, resultValueId));
        return new ValueBuild(cursor, anchor, resultValueId, null, null);
    }

    private @NotNull ValueBuild emitMatchElementFetch(
            @NotNull BuildCursor cursor,
            @NotNull Node anchor,
            @NotNull String containerValueId,
            @NotNull String keyValueId
    ) {
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new MatchElementFetchItem(anchor, containerValueId, keyValueId, resultValueId));
        return new ValueBuild(cursor, anchor, resultValueId, null, null);
    }

    private @NotNull String buildMatchValuePatternTest(
            @NotNull Expression pattern,
            @NotNull FrontendMatchPatternRoute route,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        if (route == FrontendMatchPatternRoute.LITERAL && isNullLiteral(pattern)) {
            return buildNullLiteralPatternTest(
                    pattern,
                    subjectValueId,
                    subjectFamily,
                    trueTargetId,
                    falseTargetId
            );
        }
        var constant = route == FrontendMatchPatternRoute.LITERAL
                || FrontendMatchSupport.isConstantPatternOperand(requireAnalysisData(), pattern);
        var patternType = FrontendMatchSupport.publishedTypeOrNull(requireAnalysisData(), pattern);
        var patternFamily = FrontendMatchSupport.typeFamilyOrNull(patternType);
        if (constant) {
            return buildConstantMatchPatternTest(
                    pattern,
                    subjectValueId,
                    subjectFamily,
                    subjectTypeValueId,
                    patternFamily,
                    trueTargetId,
                    falseTargetId
            );
        }
        return buildRuntimeMatchPatternTest(
                pattern,
                subjectValueId,
                subjectFamily,
                subjectTypeValueId,
                patternFamily,
                trueTargetId,
                falseTargetId
        );
    }

    private @NotNull String buildNullLiteralPatternTest(
            @NotNull Expression pattern,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        if (subjectFamily == GdExtensionTypeEnum.NIL) {
            return trueTargetId;
        }
        if (subjectFamily != null) {
            return falseTargetId;
        }
        var cursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var nilBuild = emitVariantIsNil(cursor, pattern, subjectValueId);
        return publishConditionBranch(
                nilBuild.cursor().entryId(),
                nilBuild.cursor().currentSequence(),
                pattern,
                nilBuild.resultValueId(),
                trueTargetId,
                falseTargetId
        ).entryId();
    }

    private @NotNull String buildConstantMatchPatternTest(
            @NotNull Expression pattern,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @Nullable GdExtensionTypeEnum patternFamily,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        if (subjectFamily != null && patternFamily != null
                && !FrontendMatchSupport.familiesCompatibleForMatch(subjectFamily, patternFamily)) {
            // LITERAL / constant-submode fold-to-false may skip operand materialization.
            return falseTargetId;
        }
        var cursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var patternValueBuild = buildValue(cursor, pattern, null);
        var workingCursor = patternValueBuild.cursor();
        if (subjectFamily == null || patternFamily == null) {
            var typeId = requireSubjectTypeValueId(subjectTypeValueId, workingCursor, pattern, subjectValueId);
            workingCursor = typeId.cursor();
            var equalEntryId = nextSequenceId();
            publishConstantTypeGate(
                    workingCursor,
                    pattern,
                    typeId.resultValueId(),
                    patternFamily,
                    equalEntryId,
                    falseTargetId
            );
            workingCursor = new BuildCursor(new OpenSequence(equalEntryId));
        }
        var equalBuild = emitMatchEqual(
                workingCursor,
                pattern,
                subjectValueId,
                patternValueBuild.resultValueId()
        );
        publishConditionBranch(
                equalBuild.cursor().entryId(),
                equalBuild.cursor().currentSequence(),
                pattern,
                equalBuild.resultValueId(),
                trueTargetId,
                falseTargetId
        );
        return cursor.entryId();
    }

    private @NotNull String buildRuntimeMatchPatternTest(
            @NotNull Expression pattern,
            @NotNull String subjectValueId,
            @Nullable GdExtensionTypeEnum subjectFamily,
            @Nullable String subjectTypeValueId,
            @Nullable GdExtensionTypeEnum patternFamily,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var cursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var patternValueBuild = buildValue(cursor, pattern, null);
        var workingCursor = patternValueBuild.cursor();
        if (subjectFamily != null && patternFamily != null
                && !FrontendMatchSupport.familiesCompatibleForMatch(subjectFamily, patternFamily)) {
            var falseBuild = emitBoolConstant(workingCursor, pattern, false);
            return publishConditionBranch(
                    cursor.entryId(),
                    falseBuild.cursor().currentSequence(),
                    pattern,
                    falseBuild.resultValueId(),
                    trueTargetId,
                    falseTargetId
            ).entryId();
        }
        if (subjectFamily != null && patternFamily != null) {
            var equalBuild = emitMatchEqual(
                    workingCursor,
                    pattern,
                    subjectValueId,
                    patternValueBuild.resultValueId()
            );
            return publishConditionBranch(
                    cursor.entryId(),
                    equalBuild.cursor().currentSequence(),
                    pattern,
                    equalBuild.resultValueId(),
                    trueTargetId,
                    falseTargetId
            ).entryId();
        }
        var subjectTypeId = requireSubjectTypeValueId(
                subjectTypeValueId,
                workingCursor,
                pattern,
                subjectValueId
        );
        workingCursor = subjectTypeId.cursor();
        var patternTypeBuild = emitGetVariantType(workingCursor, pattern, patternValueBuild.resultValueId());
        workingCursor = patternTypeBuild.cursor();
        var equalEntryId = nextSequenceId();
        publishRuntimeTypeGate(
                workingCursor,
                pattern,
                subjectTypeId.resultValueId(),
                patternTypeBuild.resultValueId(),
                equalEntryId,
                falseTargetId
        );
        var equalCursor = new BuildCursor(new OpenSequence(equalEntryId));
        var equalBuild = emitMatchEqual(
                equalCursor,
                pattern,
                subjectValueId,
                patternValueBuild.resultValueId()
        );
        publishConditionBranch(
                equalBuild.cursor().entryId(),
                equalBuild.cursor().currentSequence(),
                pattern,
                equalBuild.resultValueId(),
                trueTargetId,
                falseTargetId
        );
        return cursor.entryId();
    }

    private @NotNull String publishConstantTypeGate(
            @NotNull BuildCursor cursor,
            @NotNull Expression pattern,
            @NotNull String subjectTypeValueId,
            @Nullable GdExtensionTypeEnum patternFamily,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        if (patternFamily != null && FrontendMatchSupport.isStringFamily(patternFamily)) {
            return publishStringFamilyTypeGate(cursor, pattern, subjectTypeValueId, trueTargetId, falseTargetId);
        }
        if (patternFamily == null) {
            throw new IllegalStateException("constant match pattern is missing a published type family");
        }
        var expected = emitIntConstant(cursor, pattern, patternFamily.ordinal());
        var equalBuild = emitMatchEqual(
                expected.cursor(),
                pattern,
                subjectTypeValueId,
                expected.resultValueId()
        );
        return publishConditionBranch(
                cursor.entryId(),
                equalBuild.cursor().currentSequence(),
                pattern,
                equalBuild.resultValueId(),
                trueTargetId,
                falseTargetId
        ).entryId();
    }

    private @NotNull String publishStringFamilyTypeGate(
            @NotNull BuildCursor cursor,
            @NotNull Expression pattern,
            @NotNull String subjectTypeValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var stringNameCheckId = nextSequenceId();
        var stringOrd = emitIntConstant(cursor, pattern, GdExtensionTypeEnum.STRING.ordinal());
        var stringEqual = emitMatchEqual(
                stringOrd.cursor(),
                pattern,
                subjectTypeValueId,
                stringOrd.resultValueId()
        );
        publishConditionBranch(
                cursor.entryId(),
                stringEqual.cursor().currentSequence(),
                pattern,
                stringEqual.resultValueId(),
                trueTargetId,
                stringNameCheckId
        );
        var stringNameCursor = new BuildCursor(new OpenSequence(stringNameCheckId));
        var stringNameOrd = emitIntConstant(
                stringNameCursor,
                pattern,
                GdExtensionTypeEnum.STRING_NAME.ordinal()
        );
        var stringNameEqual = emitMatchEqual(
                stringNameOrd.cursor(),
                pattern,
                subjectTypeValueId,
                stringNameOrd.resultValueId()
        );
        publishConditionBranch(
                stringNameCheckId,
                stringNameEqual.cursor().currentSequence(),
                pattern,
                stringNameEqual.resultValueId(),
                trueTargetId,
                falseTargetId
        );
        return cursor.entryId();
    }

    @SuppressWarnings("UnusedReturnValue")
    private @NotNull String publishRuntimeTypeGate(
            @NotNull BuildCursor cursor,
            @NotNull Expression pattern,
            @NotNull String subjectTypeValueId,
            @NotNull String patternTypeValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var crossoverEntryId = nextSequenceId();
        var sameType = emitMatchEqual(cursor, pattern, subjectTypeValueId, patternTypeValueId);
        publishConditionBranch(
                cursor.entryId(),
                sameType.cursor().currentSequence(),
                pattern,
                sameType.resultValueId(),
                trueTargetId,
                crossoverEntryId
        );
        var crossoverCursor = new BuildCursor(new OpenSequence(crossoverEntryId));
        publishBidirectionalStringCrossover(
                crossoverCursor,
                pattern,
                subjectTypeValueId,
                patternTypeValueId,
                trueTargetId,
                falseTargetId
        );
        return cursor.entryId();
    }

    private void publishBidirectionalStringCrossover(
            @NotNull BuildCursor cursor,
            @NotNull Expression pattern,
            @NotNull String subjectTypeValueId,
            @NotNull String patternTypeValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var stringOrd = GdExtensionTypeEnum.STRING.ordinal();
        var stringNameOrd = GdExtensionTypeEnum.STRING_NAME.ordinal();
        var secondDirectionId = nextSequenceId();
        publishStringCrossoverArm(
                cursor,
                pattern,
                subjectTypeValueId,
                patternTypeValueId,
                stringOrd,
                stringNameOrd,
                trueTargetId,
                secondDirectionId
        );
        var secondCursor = new BuildCursor(new OpenSequence(secondDirectionId));
        publishStringCrossoverArm(
                secondCursor,
                pattern,
                subjectTypeValueId,
                patternTypeValueId,
                stringNameOrd,
                stringOrd,
                trueTargetId,
                falseTargetId
        );
    }

    private void publishStringCrossoverArm(
            @NotNull BuildCursor cursor,
            @NotNull Expression pattern,
            @NotNull String subjectTypeValueId,
            @NotNull String patternTypeValueId,
            int subjectOrdinal,
            int patternOrdinal,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var patternCheckId = nextSequenceId();
        var subjectExpected = emitIntConstant(cursor, pattern, subjectOrdinal);
        var subjectEqual = emitMatchEqual(
                subjectExpected.cursor(),
                pattern,
                subjectTypeValueId,
                subjectExpected.resultValueId()
        );
        publishConditionBranch(
                cursor.entryId(),
                subjectEqual.cursor().currentSequence(),
                pattern,
                subjectEqual.resultValueId(),
                patternCheckId,
                falseTargetId
        );
        var patternCursor = new BuildCursor(new OpenSequence(patternCheckId));
        var patternExpected = emitIntConstant(patternCursor, pattern, patternOrdinal);
        var patternEqual = emitMatchEqual(
                patternExpected.cursor(),
                pattern,
                patternTypeValueId,
                patternExpected.resultValueId()
        );
        publishConditionBranch(
                patternCheckId,
                patternEqual.cursor().currentSequence(),
                pattern,
                patternEqual.resultValueId(),
                trueTargetId,
                falseTargetId
        );
    }

    /// Allocates the source-facing bind slot for one `var x` pattern binding.
    ///
    /// The exposed type comes from the `slotTypes()` side table published by
    /// `MATCH_PATTERN_RESOLUTION`: refined subject type for top-level binds, always `Variant` for
    /// nested destructuring binds.
    private @NotNull FrontendMatchBindSlot allocateMatchBindSlot(
            @NotNull MatchSection section,
            @NotNull PatternBindingExpression declaration
    ) {
        var exposedType = requireAnalysisData().slotTypes().get(declaration);
        if (exposedType == null) {
            throw new IllegalStateException(
                    "Missing published slot type for match bind '" + declaration.name() + "' at "
                            + declaration.range()
            );
        }
        if (exposedType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "match bind '" + declaration.name() + "' must not use compiler-only type "
                            + compilerOnlyType.getTypeName()
            );
        }
        var slot = new FrontendMatchBindSlot(declaration, section, declaration.name(), exposedType);
        requireMatchBindSlots().put(declaration, slot);
        return slot;
    }

    /// Bind slots are keyed by source name, so same-name binds of distinct sections (each legal in
    /// its own section scope) — or of different `match` statements in the same callable — share one
    /// function variable; their lifetimes never overlap because only one section executes. When the
    /// exposed types inside such a name group diverge (a top-level bind refined to the subject
    /// static type vs a nested destructuring bind, which is always Variant), every slot of the
    /// group is retyped to Variant so the registry, the declared variable, and every read/write
    /// boundary agree on one storage type.
    private void unifyCollidingMatchBindSlotTypes() {
        var slots = requireMatchBindSlots();
        var typesBySlotId = new LinkedHashMap<String, GdType>();
        var divergentSlotIds = new LinkedHashSet<String>();
        for (var slot : slots.values()) {
            var previous = typesBySlotId.putIfAbsent(slot.bindSlotId(), slot.exposedType());
            if (previous != null && !previous.equals(slot.exposedType())) {
                divergentSlotIds.add(slot.bindSlotId());
            }
        }
        if (divergentSlotIds.isEmpty()) {
            return;
        }
        var retyped = new ArrayList<FrontendMatchBindSlot>();
        for (var slot : slots.values()) {
            if (!divergentSlotIds.contains(slot.bindSlotId())
                    || slot.exposedType() instanceof GdVariantType) {
                continue;
            }
            retyped.add(new FrontendMatchBindSlot(
                    slot.declaration(),
                    slot.section(),
                    slot.bindSlotId(),
                    GdVariantType.VARIANT
            ));
        }
        requireNoTypedLambdaCaptureOnRetypedSlots(retyped);
        for (var slot : retyped) {
            slots.put(slot.declaration(), slot);
        }
    }

    /// Same-match divergence is pre-unified at sema (the whole name group keeps the Variant
    /// baseline, so capture entries freeze Variant too). Cross-match divergence surfaces only here,
    /// after lambda plans froze their capture types — a lambda capturing one of these binds with a
    /// non-Variant entry would collide with the retyped storage at the backend's
    /// `construct_lambda` boundary, so fail fast at the layer that owns the decision.
    private void requireNoTypedLambdaCaptureOnRetypedSlots(@NotNull List<FrontendMatchBindSlot> retyped) {
        if (retyped.isEmpty()) {
            return;
        }
        var retypedDeclarations = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var slot : retyped) {
            retypedDeclarations.add(slot.declaration());
        }
        for (var lambdaPlan : requireAnalysisData().lambdaPlans().values()) {
            for (var capture : lambdaPlan.capturePlan().captures()) {
                if (capture.type() instanceof GdVariantType) {
                    continue;
                }
                if (capture.sourceDeclaration() != null
                        && retypedDeclarations.contains(capture.sourceDeclaration())) {
                    throw new IllegalStateException(
                            "Lambda '" + lambdaPlan.syntheticName() + "' captures match bind '"
                                    + capture.name() + "' with frozen type '" + capture.type().getTypeName()
                                    + "', but same-name binds across match statements share Variant storage; "
                                    + "capturing such a bind is not supported"
                    );
                }
            }
        }
    }

    private @NotNull FrontendMatchPlan requireMatchPlan(@NotNull MatchStatement matchStatement) {
        var plan = requireAnalysisData().matchPlans().get(matchStatement);
        if (plan == null) {
            throw new IllegalStateException(
                    "Missing published match plan for MatchStatement at " + matchStatement.range()
            );
        }
        return plan;
    }

    private static void requireMatchRoutesReady(@NotNull FrontendMatchPlan plan) {
        for (var sectionPlan : plan.sections()) {
            for (var patternPlan : sectionPlan.patterns()) {
                if (!FrontendMatchSupport.isRouteLoweringReady(patternPlan.route())) {
                    throw new IllegalStateException(
                            "match pattern route " + patternPlan.route() + " is not compile-ready"
                    );
                }
            }
        }
    }

    private boolean needsSubjectTypeId(
            @NotNull FrontendMatchPlan plan,
            @Nullable GdExtensionTypeEnum subjectFamily
    ) {
        if (subjectFamily != null) {
            return false;
        }
        for (var sectionPlan : plan.sections()) {
            for (var patternPlan : sectionPlan.patterns()) {
                if (patternPlan.route() == FrontendMatchPatternRoute.LITERAL
                        && isNullLiteral(patternPlan.patternNode())) {
                    continue;
                }
                if (patternPlan.route() == FrontendMatchPatternRoute.LITERAL
                        || patternPlan.route() == FrontendMatchPatternRoute.EXPRESSION
                        || patternPlan.route() == FrontendMatchPatternRoute.ARRAY
                        || patternPlan.route() == FrontendMatchPatternRoute.DICTIONARY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUnconditionalCatchAll(@NotNull FrontendMatchSectionPlan sectionPlan) {
        if (sectionPlan.hasGuard() || sectionPlan.patterns().size() != 1) {
            return false;
        }
        var route = sectionPlan.patterns().getFirst().route();
        return route == FrontendMatchPatternRoute.WILDCARD || route == FrontendMatchPatternRoute.BINDING;
    }

    private static boolean isNullLiteral(@NotNull Expression expression) {
        return expression instanceof LiteralExpression literal && "null".equals(literal.kind());
    }

    private @NotNull GdType requirePublishedMatchValueType(@NotNull Expression expression) {
        var type = FrontendMatchSupport.publishedTypeOrNull(requireAnalysisData(), expression);
        if (type == null) {
            throw new IllegalStateException(
                    "match value is missing a lowering-ready published type at " + expression.range()
            );
        }
        return type;
    }

    private @NotNull ValueBuild requireSubjectTypeValueId(
            @Nullable String subjectTypeValueId,
            @NotNull BuildCursor cursor,
            @NotNull Expression anchor,
            @NotNull String subjectValueId
    ) {
        if (subjectTypeValueId != null) {
            return new ValueBuild(cursor, anchor, subjectTypeValueId, null, null);
        }
        return emitGetVariantType(cursor, anchor, subjectValueId);
    }

    private @NotNull ValueBuild emitBoolConstant(
            @NotNull BuildCursor cursor,
            @NotNull Node anchor,
            boolean value
    ) {
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new BoolConstantItem(anchor, value, resultValueId));
        return new ValueBuild(cursor, anchor, resultValueId, null, null);
    }

    private @NotNull ValueBuild emitIntConstant(
            @NotNull BuildCursor cursor,
            @NotNull Node anchor,
            long value
    ) {
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new IntConstantItem(anchor, value, resultValueId));
        return new ValueBuild(cursor, anchor, resultValueId, null, null);
    }

    private @NotNull ValueBuild emitGetVariantType(
            @NotNull BuildCursor cursor,
            @NotNull Node anchor,
            @NotNull String operandValueId
    ) {
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new GetVariantTypeItem(anchor, operandValueId, resultValueId));
        return new ValueBuild(cursor, anchor, resultValueId, null, null);
    }

    private @NotNull ValueBuild emitMatchEqual(
            @NotNull BuildCursor cursor,
            @NotNull Node anchor,
            @NotNull String leftValueId,
            @NotNull String rightValueId
    ) {
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new MatchEqualItem(anchor, leftValueId, rightValueId, resultValueId));
        return new ValueBuild(cursor, anchor, resultValueId, null, null);
    }

    private @NotNull ValueBuild emitVariantIsNil(
            @NotNull BuildCursor cursor,
            @NotNull Node anchor,
            @NotNull String operandValueId
    ) {
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new VariantIsNilItem(anchor, operandValueId, resultValueId));
        return new ValueBuild(cursor, anchor, resultValueId, null, null);
    }

    /// Publishes the body entry sequence that runs the get operation, committing the source-facing
    /// iterator local before the already-built body statements run.
    private @NotNull String publishForBodyGetEntry(
            @NotNull ForStatement forStatement,
            @NotNull FrontendForLoweringContract contract,
            @NotNull FrontendForIteratorStateSlot stateSlot,
            @NotNull FrontendForSourceIteratorSlot sourceSlot,
            @NotNull String bodyStatementsEntryId
    ) {
        var getSequence = new OpenSequence(nextSequenceId());
        getSequence.items().add(new ForLoopGetItem(
                forStatement,
                contract.get(),
                stateSlot.slotId(),
                nextValueId(),
                sourceSlot.sourceIteratorSlotId()
        ));
        publishSequenceNode(getSequence.id(), getSequence.items(), bodyStatementsEntryId);
        return getSequence.id();
    }

    /// Publishes the update entry sequence that runs the next operation and jumps back to the
    /// condition entry; it is the `continue` target.
    private void publishForUpdateEntry(
            @NotNull ForStatement forStatement,
            @NotNull FrontendForLoweringContract contract,
            @NotNull FrontendForIteratorStateSlot stateSlot,
            @NotNull OpenSequence updateSequence,
            @NotNull String conditionEntryId
    ) {
        updateSequence.items().add(new ForLoopNextItem(
                forStatement,
                contract.next(),
                stateSlot.slotId(),
                stateSlot.nextTempSlotId()
        ));
        publishSequenceNode(updateSequence.id(), updateSequence.items(), conditionEntryId);
    }

    /// Publishes the condition entry sequence that runs the should-continue operation and branches on
    /// its ordinary `bool` result.
    private void publishForConditionEntry(
            @NotNull ForStatement forStatement,
            @NotNull FrontendForLoweringContract contract,
            @NotNull FrontendForIteratorStateSlot stateSlot,
            @NotNull OpenSequence conditionSequence,
            @NotNull String bodyEntryId,
            @NotNull String exitId
    ) {
        var conditionValueId = nextValueId();
        conditionSequence.items().add(new ForLoopShouldContinueItem(
                forStatement,
                contract.shouldContinue(),
                stateSlot.slotId(),
                conditionValueId
        ));
        var branchId = nextBranchId();
        publishSequenceNode(conditionSequence.id(), conditionSequence.items(), branchId);
        requireNodes().put(
                branchId,
                new FrontendCfgGraph.BranchNode(
                        branchId,
                        forStatement.iterable(),
                        conditionValueId,
                        bodyEntryId,
                        exitId
                )
        );
    }

    /// Publishes the init entry subgraph: the source operands are materialized in source order first,
    /// then the init operation writes the hidden iterator state slot.
    private @NotNull String publishForInitEntry(
            @NotNull ForStatement forStatement,
            @NotNull FrontendForLoweringContract contract,
            @NotNull FrontendForIteratorStateSlot stateSlot,
            @NotNull FrontendForIterationPlan plan,
            @NotNull String conditionEntryId
    ) {
        var initCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var operandsBuild = buildArgumentValues(initCursor, plan.sourceOperands());
        operandsBuild.cursor().currentSequence().items().add(new ForLoopInitItem(
                forStatement,
                contract.init(),
                operandsBuild.valueIds(),
                stateSlot.slotId()
        ));
        publishSequenceNode(
                operandsBuild.cursor().currentSequence().id(),
                operandsBuild.cursor().currentSequence().items(),
                conditionEntryId
        );
        return initCursor.entryId();
    }

    private @NotNull FrontendForIterationPlan requireForIterationPlan(@NotNull ForStatement forStatement) {
        var plan = requireAnalysisData().forIterationPlans().get(forStatement);
        if (plan == null) {
            throw new IllegalStateException(
                    "Missing published for-in iteration plan for ForStatement at " + forStatement.range()
            );
        }
        return plan;
    }

    private @NotNull FrontendForLoweringContract requireForLoweringContract(@NotNull FrontendForIterationPlan plan) {
        var contract = ForLoweringContractRegistry.get(plan.route());
        if (contract == null) {
            throw new IllegalStateException(
                    "for-in route " + plan.route() + " is not compile-ready: no lowering contract registered"
            );
        }
        return contract;
    }

    /// Allocates the hidden loop-carried state slot for one for-in loop. The `<n>` index is assigned by
    /// source traversal order within one executable-body build so nested/sibling loops never reuse ids.
    private @NotNull FrontendForIteratorStateSlot allocateIteratorStateSlot(
            @NotNull ForStatement forStatement,
            @NotNull FrontendForLoweringContract contract
    ) {
        var index = nextForIterIndex++;
        return new FrontendForIteratorStateSlot(
                forStatement,
                "cfg_for_iter_" + index,
                "cfg_for_iter_next_" + index,
                contract.iteratorStateType()
        );
    }

    /// Allocates the source-facing iterator slot. The exposed type must come from the final published
    /// `slotTypes()[ForStatement]` and must agree with the plan's exposed iterator type, so the source
    /// slot never diverges from the semantic fact.
    private @NotNull FrontendForSourceIteratorSlot allocateSourceIteratorSlot(
            @NotNull ForStatement forStatement,
            @NotNull FrontendForIterationPlan plan
    ) {
        var exposedType = requireAnalysisData().slotTypes().get(forStatement);
        if (exposedType == null) {
            throw new IllegalStateException(
                    "Missing published slot type for for-in iterator '" + plan.iteratorName() + "' at "
                            + forStatement.range()
            );
        }
        if (exposedType instanceof GdCompilerType compilerOnlyType) {
            throw new IllegalStateException(
                    "for-in iterator '" + plan.iteratorName() + "' must not use compiler-only type "
                            + compilerOnlyType.getTypeName()
            );
        }
        if (!sameExposedType(exposedType, plan.exposedIteratorType())) {
            throw new IllegalStateException(
                    "for-in iterator '" + plan.iteratorName() + "' slot type " + exposedType.getTypeName()
                            + " disagrees with plan exposed type " + plan.exposedIteratorType().getTypeName()
            );
        }
        return new FrontendForSourceIteratorSlot(forStatement, plan.iteratorName(), exposedType);
    }

    /// Source-facing iterator type agreement mirrors the semantic side-table equivalence rule: the final
    /// published slot type and the plan's exposed type must be the same type kind and name.
    private static boolean sameExposedType(@NotNull GdType first, @NotNull GdType second) {
        return first == second
                || (first.getClass() == second.getClass() && first.getTypeName().equals(second.getTypeName()));
    }

    private void attachStructuredEntry(@NotNull BlockState state, @NotNull String structuredEntryId) {
        if (state.currentSequenceOrNull() == null) {
            state.setEntryIdIfMissing(structuredEntryId);
            return;
        }
        closeCurrentSequence(state, structuredEntryId);
    }

    private @NotNull ConditionBuild buildCondition(
            @NotNull Expression condition,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        return buildCondition(new BuildCursor(new OpenSequence(nextSequenceId())), condition, trueTargetId, falseTargetId);
    }

    private @NotNull ConditionBuild buildCondition(
            @NotNull BuildCursor cursor,
            @NotNull Expression condition,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        requireLoweringReadyExpressionType(condition);
        return switch (condition) {
            case UnaryExpression unaryExpression when isLogicalNotExpression(unaryExpression) ->
                    buildCondition(cursor, unaryExpression.operand(), falseTargetId, trueTargetId);
            case BinaryExpression binaryExpression when isShortCircuitBinaryExpression(binaryExpression) ->
                    buildShortCircuitCondition(cursor, binaryExpression, trueTargetId, falseTargetId);
            case ConditionalExpression conditionalExpression ->
                    buildConditionalExpressionCondition(cursor, conditionalExpression, trueTargetId, falseTargetId);
            default -> buildConditionFromValue(cursor, condition, trueTargetId, falseTargetId);
        };
    }

    private @NotNull ConditionBuild buildConditionFromValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression condition,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var conditionValueBuild = buildValue(cursor, condition, null);
        var conditionSequence = conditionValueBuild.cursor().currentSequence();
        return publishConditionBranch(
                conditionValueBuild.cursor().entryId(),
                conditionSequence,
                condition,
                conditionValueBuild.resultValueId(),
                trueTargetId,
                falseTargetId
        );
    }

    /// Publishes one branch that immediately tests the given fragment value.
    ///
    /// The caller must pass the expression root that directly produced `conditionValueId`, not the
    /// outer source-level condition root. This keeps `BranchNode.conditionRoot` aligned with the
    /// concrete value being tested:
    /// - plain condition roots keep themselves
    /// - `not x` strips the wrapper and only swaps true/false targets
    /// - short-circuit branches publish separate roots for `a`, `b`, ... instead of
    ///   repeating the outer `a and b` / `a or b` shell on every split
    private @NotNull ConditionBuild publishConditionBranch(
            @NotNull String entryId,
            @NotNull OpenSequence conditionSequence,
            @NotNull Expression conditionFragmentRoot,
            @NotNull String conditionValueId,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var branchId = nextBranchId();
        publishSequenceNode(conditionSequence.id(), conditionSequence.items(), branchId);
        requireNodes().put(
                branchId,
                new FrontendCfgGraph.BranchNode(
                        branchId,
                        conditionFragmentRoot,
                        conditionValueId,
                        trueTargetId,
                        falseTargetId
                )
        );
        return new ConditionBuild(entryId);
    }

    /// Recursively materializes one lowering-ready value and returns both the current writable
    /// continuation and the published frontend-local value id.
    ///
    /// The builder deliberately special-cases only operations whose semantics later lowering must not
    /// rediscover from raw AST alone. Generic expressions still use `OpaqueExprValueItem`, but only
    /// after all of their lowering-ready children have already published explicit operand ids.
    /// Short-circuit `and` / `or` no longer share the generic eager route; they branch through the
    /// shared condition core and merge one explicit bool result value back to the parent consumer.
    private @NotNull ValueBuild buildValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression,
            @Nullable String preferredResultValueId
    ) {
        requireLoweringReadyExpressionType(expression);
        return switch (expression) {
            case AssignmentExpression _ -> throw new IllegalStateException(
                    "Assignment expressions do not produce a lowering-ready value in the current compile surface"
            );
            case AttributeExpression attributeExpression -> buildAttributeExpressionValue(
                    cursor,
                    attributeExpression,
                    preferredResultValueId
            );
            case CallExpression callExpression -> buildBareCallValue(cursor, callExpression, preferredResultValueId);
            case SubscriptExpression subscriptExpression -> buildPlainSubscriptValue(
                    cursor,
                    subscriptExpression,
                    preferredResultValueId
            );
            case CastExpression castExpression -> buildCastValue(cursor, castExpression, preferredResultValueId);
            case AwaitExpression awaitExpression -> buildAwaitValue(cursor, awaitExpression, preferredResultValueId);
            case ArrayExpression arrayExpression ->
                    buildArrayLiteralValue(cursor, arrayExpression, preferredResultValueId);
            case DictionaryExpression dictionaryExpression ->
                    buildDictionaryLiteralValue(cursor, dictionaryExpression, preferredResultValueId);
            case TypeTestExpression typeTestExpression ->
                    buildTypeTestValue(cursor, typeTestExpression, preferredResultValueId);
            case LambdaExpression lambdaExpression ->
                    buildLambdaConstructValue(cursor, lambdaExpression, preferredResultValueId);
            case ConditionalExpression conditionalExpression -> buildConditionalExpressionValue(
                    cursor,
                    conditionalExpression,
                    preferredResultValueId
            );
            case UnaryExpression unaryExpression -> {
                var operandBuild = buildValue(cursor, unaryExpression.operand(), null);
                yield emitOpaqueValue(
                        operandBuild.cursor(),
                        unaryExpression,
                        List.of(operandBuild.resultValueId()),
                        preferredResultValueId
                );
            }
            case BinaryExpression binaryExpression when isShortCircuitBinaryExpression(binaryExpression) ->
                    buildShortCircuitBinaryValue(cursor, binaryExpression, preferredResultValueId);
            case BinaryExpression binaryExpression -> {
                var leftBuild = buildValue(cursor, binaryExpression.left(), null);
                var rightBuild = buildValue(leftBuild.cursor(), binaryExpression.right(), null);
                yield emitOpaqueValue(
                        rightBuild.cursor(),
                        binaryExpression,
                        List.of(leftBuild.resultValueId(), rightBuild.resultValueId()),
                        preferredResultValueId
                );
            }
            // PreloadExpression and GetNodeExpression join the leaf family: their payloads (the
            // preload path literal / the `$`/`%` source text) are consumed directly by the
            // dedicated opaque processors (no child value ids), mirroring how an identifier leaf
            // carries no operands.
            case IdentifierExpression _, LiteralExpression _, SelfExpression _, PreloadExpression _,
                 GetNodeExpression _ -> emitOpaqueValue(
                    cursor,
                    expression,
                    List.of(),
                    preferredResultValueId
            );
            default -> throw unsupportedReachableExpression(expression);
        };
    }

    /// Builds an outer-body occurrence of a recorded lambda as one `LambdaConstructItem`.
    /// The lambda body itself is lowered separately through its own `LAMBDA_BODY`
    /// context; here only the enclosing-frame capture operands are collected in plan order.
    ///
    /// Operand sources follow the frozen plan entries: named slots for `LOCAL_VAR` / `PARAMETER` /
    /// outer `CAPTURE` sources (slot id == capture entry name, the same names-always-match rule the
    /// body session uses), and the dedicated `SELF_SLOT` descriptor for a leading `self` capture -
    /// never a fabricated `IdentifierExpression` with a SELF binding.
    private @NotNull ValueBuild buildLambdaConstructValue(
            @NotNull BuildCursor cursor,
            @NotNull LambdaExpression lambdaExpression,
            @Nullable String preferredResultValueId
    ) {
        var plan = requireAnalysisData().lambdaPlans().get(lambdaExpression);
        if (plan == null) {
            throw new IllegalStateException(
                    "Frontend CFG builder reached a lowering-ready LambdaExpression without a published lambda plan"
            );
        }
        var capturePlan = plan.capturePlan();
        var captures = capturePlan.captures();
        var captureOperands = new ArrayList<LambdaConstructItem.CaptureOperand>(captures.size());
        for (var index = 0; index < captures.size(); index++) {
            var capture = captures.get(index);
            if (capturePlan.capturesSelf() && index == 0) {
                // The capture planner guarantees a leading SELF_CAPTURE_NAME entry when capturesSelf.
                if (!capture.name().equals(FrontendLambdaCapturePlan.SELF_CAPTURE_NAME)) {
                    throw new IllegalStateException(
                            "Lambda plan for '" + plan.syntheticName()
                                    + "' declares capturesSelf but its leading capture is '" + capture.name() + "'"
                    );
                }
                captureOperands.add(LambdaConstructItem.SelfSlotOperand.SELF_SLOT);
            } else {
                captureOperands.add(new LambdaConstructItem.VariableSlotOperand(capture.name()));
            }
        }
        var resultValueId = chooseResultValueId(preferredResultValueId);
        cursor.currentSequence().items().add(new LambdaConstructItem(
                lambdaExpression,
                plan.syntheticName(),
                captureOperands,
                resultValueId
        ));
        return valueRootBuild(cursor, lambdaExpression, resultValueId);
    }

    /// Attribute chains are expanded step by step so later lowering receives explicit intermediate
    /// value ids instead of having to rerun chain reduction over the full outer expression root.
    private @NotNull ValueBuild buildAttributeExpressionValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @Nullable String preferredResultValueId
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression must contain at least one step");
        }
        if (isTypeMetaHeadAttributeExpression(attributeExpression)) {
            return buildTypeMetaHeadAttributeExpressionValue(cursor, attributeExpression, preferredResultValueId);
        }
        var currentBuild = buildValue(cursor, attributeExpression.base(), null);
        for (var stepIndex = 0; stepIndex < attributeExpression.steps().size(); stepIndex++) {
            var step = attributeExpression.steps().get(stepIndex);
            currentBuild = applyAttributeStep(
                    currentBuild,
                    step,
                    stepIndex + 1 == attributeExpression.steps().size() ? preferredResultValueId : null
            );
        }
        return currentBuild;
    }

    /// Type-meta chain heads such as `Vector3.ZERO`, `Color.RED`, `Node.new()` or `Worker.build(...)`
    /// do not materialize the head identifier as a runtime value.
    ///
    /// The first lowering step must therefore start directly from a published type-meta fact:
    /// - static member loads publish `MemberLoadItem(..., null, ...)`
    /// - static/constructor calls publish `CallItem(..., null, ...)`
    /// - a static-container subscript head (`Worker.values[i]`) first loads the shared container
    ///   through the same `MemberLoadItem(..., null, ...)` shape, then applies an ordinary plain
    ///   subscript on that container value
    ///
    /// Only subsequent steps consume the produced result as an ordinary runtime value.
    private @NotNull ValueBuild buildTypeMetaHeadAttributeExpressionValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @Nullable String preferredResultValueId
    ) {
        var currentBuild = buildTypeMetaHeadFirstStepValue(
                cursor,
                attributeExpression,
                attributeExpression.steps().getFirst(),
                attributeExpression.steps().size() == 1 ? preferredResultValueId : null
        );
        for (var stepIndex = 1; stepIndex < attributeExpression.steps().size(); stepIndex++) {
            var step = attributeExpression.steps().get(stepIndex);
            currentBuild = applyAttributeStep(
                    currentBuild,
                    step,
                    stepIndex + 1 == attributeExpression.steps().size() ? preferredResultValueId : null
            );
        }
        return currentBuild;
    }

    /// Shared first-step dispatch for every type-meta head path (value reads, discarded void calls,
    /// assignment target prefixes). Keeping one dispatch point guarantees all paths accept the same
    /// head-step surface and fail fast with the same message for unsupported shapes.
    private @NotNull ValueBuild buildTypeMetaHeadFirstStepValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @NotNull AttributeStep firstStep,
            @Nullable String preferredResultValueId
    ) {
        return switch (firstStep) {
            case AttributePropertyStep firstPropertyStep -> buildTypeMetaHeadMemberStep(
                    cursor,
                    firstPropertyStep,
                    preferredResultValueId
            );
            case AttributeCallStep firstCallStep -> buildTypeMetaHeadCallStep(
                    cursor,
                    firstCallStep,
                    preferredResultValueId
            );
            case AttributeSubscriptStep firstSubscriptStep -> buildTypeMetaHeadSubscriptStep(
                    cursor,
                    attributeExpression.base(),
                    firstSubscriptStep,
                    preferredResultValueId
            );
            default -> throw new IllegalStateException(
                    "Type-meta attribute head '" + attributeExpression.base()
                            + "' currently requires a property, call, or static-container subscript step to enter lowering"
            );
        };
    }

    /// Type-meta static-container subscript head (`Worker.values[i]`). Chain binding publishes the
    /// RESOLVED static container property on the `AttributeSubscriptStep` itself (the internally
    /// synthesized property step is not an AST node), so both the load item and the writable route
    /// anchor at the subscript step:
    /// - the container enters lowering as a `MemberLoadItem(..., null, ...)` that body lowering turns
    ///   into one `LoadStaticInsn` on the start class (the declaring owner is resolved by the backend
    ///   through the hierarchy, exactly like a plain `ClassName.name` load)
    /// - the subscript itself is a plain `SubscriptLoadItem` (`memberNameOrNull = null`) on that
    ///   container value, so `SubscriptLeaf.baseOrReceiverSlotId` stays backed by a real slot
    /// - the writable route mirrors the bare `values[i]` identifier form: a `STATIC_CONTEXT` root
    ///   plus a terminal `PROPERTY` commit step (the promoted static property), never a named route
    private @NotNull ValueBuild buildTypeMetaHeadSubscriptStep(
            @NotNull BuildCursor cursor,
            @NotNull Expression headBase,
            @NotNull AttributeSubscriptStep attributeSubscriptStep,
            @Nullable String preferredResultValueId
    ) {
        var containerMember = requireTypeMetaStaticContainerMember(attributeSubscriptStep);
        var containerValueId = nextValueId();
        cursor.currentSequence().items().add(new MemberLoadItem(
                attributeSubscriptStep,
                containerMember.memberName(),
                null,
                containerValueId
        ));
        var containerBuild = new ValueBuild(
                cursor,
                attributeSubscriptStep,
                containerValueId,
                new FrontendWritableRoutePayload(
                        attributeSubscriptStep,
                        new FrontendWritableRoutePayload.RootDescriptor(
                                FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT,
                                headBase,
                                null
                        ),
                        new FrontendWritableRoutePayload.LeafDescriptor(
                                FrontendWritableRoutePayload.LeafKind.PROPERTY,
                                attributeSubscriptStep,
                                null,
                                List.of(),
                                containerMember.memberName(),
                                null
                        ),
                        List.of()
                )
        );
        var argumentsBuild = buildArgumentValues(cursor, attributeSubscriptStep.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        argumentsBuild.cursor().currentSequence().items().add(new SubscriptLoadItem(
                attributeSubscriptStep,
                null,
                containerValueId,
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return new ValueBuild(
                argumentsBuild.cursor(),
                attributeSubscriptStep,
                resultValueId,
                appendSubscriptWritableRoute(
                        containerBuild,
                        attributeSubscriptStep,
                        argumentsBuild.valueIds(),
                        determineWritableSubscriptAccessKind(
                                attributeSubscriptStep,
                                requireTypeMetaStaticContainerType(containerMember),
                                requireWritableRouteAnchorType(attributeSubscriptStep.arguments().getFirst())
                        ),
                        null
                )
        );
    }

    /// The type-meta head subscript surface is deliberately scoped to static container properties:
    /// chain binding only re-anchors RESOLVED PROPERTY members onto the subscript step, so a missing
    /// or non-static fact here means the chain deliberately kept the shape unsupported (constant
    /// containers, dynamic members) or publication drifted; both must fail fast instead of lowering
    /// through a guessed route.
    private @NotNull FrontendResolvedMember requireTypeMetaStaticContainerMember(
            @NotNull AttributeSubscriptStep attributeSubscriptStep
    ) {
        var publishedMember = requireAnalysisData().resolvedMembers().get(
                Objects.requireNonNull(attributeSubscriptStep, "attributeSubscriptStep must not be null")
        );
        if (publishedMember == null
                || publishedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                || publishedMember.bindingKind() != FrontendBindingKind.PROPERTY
                || publishedMember.receiverKind() != FrontendReceiverKind.TYPE_META
                || !(publishedMember.declarationSite() instanceof PropertyDef propertyDef)
                || !propertyDef.isStatic()) {
            throw new IllegalStateException(
                    "Type-meta head subscript '"
                            + attributeSubscriptStep.name()
                            + "[...]' requires a RESOLVED static property container member published on the "
                            + "AttributeSubscriptStep, but got "
                            + (publishedMember == null
                            ? "no member fact"
                            : publishedMember.status() + " / " + publishedMember.bindingKind())
                            + "; constant containers and dynamic members stay unsupported"
            );
        }
        return publishedMember;
    }

    private static @NotNull GdType requireTypeMetaStaticContainerType(
            @NotNull FrontendResolvedMember containerMember
    ) {
        return Objects.requireNonNull(
                containerMember.resultType(),
                "RESOLVED static container property member must publish resultType"
        );
    }

    private @NotNull ValueBuild buildTypeMetaHeadMemberStep(
            @NotNull BuildCursor cursor,
            @NotNull AttributePropertyStep attributePropertyStep,
            @Nullable String preferredResultValueId
    ) {
        var publishedMember = requireLoweringReadyMember(attributePropertyStep);
        if (isResolvedStandaloneStaticMethodReference(publishedMember)) {
            var resultValueId = chooseResultValueId(preferredResultValueId);
            var kind = publishedMember.ownerKind() == ScopeOwnerKind.ENGINE
                    ? StandaloneCallableKind.STATIC_ENGINE
                    : StandaloneCallableKind.STATIC_GDCC;
            cursor.currentSequence().items().add(new StandaloneCallableLoadItem(
                    attributePropertyStep,
                    kind,
                    requireStandaloneOwnerName(publishedMember),
                    publishedMember.memberName(),
                    resultValueId
            ));
            return valueRootBuild(cursor, attributePropertyStep, resultValueId);
        }
        if (isResolvedUnsupportedMethodReference(publishedMember)) {
            throw new IllegalStateException(
                    "RESOLVED method-reference '"
                            + publishedMember.memberName()
                            + "' cannot lower as a type-meta member load; builtin type-meta methods stay unsupported"
            );
        }
        var resultValueId = chooseResultValueId(preferredResultValueId);
        cursor.currentSequence().items().add(new MemberLoadItem(
                attributePropertyStep,
                publishedMember.memberName(),
                null,
                resultValueId
        ));
        return valueRootBuild(cursor, attributePropertyStep, resultValueId);
    }

    private @NotNull ValueBuild buildTypeMetaHeadCallStep(
            @NotNull BuildCursor cursor,
            @NotNull AttributeCallStep attributeCallStep,
            @Nullable String preferredResultValueId
    ) {
        var publishedCall = requireLoweringReadyCall(attributeCallStep);
        checkValueProducingCall(publishedCall, attributeCallStep, "type-meta head call step");
        var argumentsBuild = buildArgumentValues(cursor, attributeCallStep.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                attributeCallStep,
                publishedCall.callableName(),
                null,
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return valueRootBuild(argumentsBuild.cursor(), attributeCallStep, resultValueId);
    }

    /// Bare call lowering consumes the published `resolvedCalls()` fact directly.
    ///
    /// The current compile-ready contract permits:
    /// - ordinary bare/global/static calls
    /// - bare builtin direct constructors such as `Vector3i(...)`
    ///
    /// Callable-value invocation stays outside the accepted lowering surface, so a non-identifier callee
    /// is treated as a protocol violation instead of being silently dropped from operand ordering.
    private @NotNull ValueBuild buildBareCallValue(
            @NotNull BuildCursor cursor,
            @NotNull CallExpression callExpression,
            @Nullable String preferredResultValueId
    ) {
        if (!(callExpression.callee() instanceof IdentifierExpression)) {
            throw new IllegalStateException(
                    "Bare call lowering currently requires an IdentifierExpression callee, but got "
                            + callExpression.callee().getClass().getSimpleName()
            );
        }
        var publishedCall = requireLoweringReadyCall(callExpression);
        checkValueProducingCall(publishedCall, callExpression, "bare call");
        var argumentsBuild = buildArgumentValues(cursor, callExpression.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                callExpression,
                publishedCall.callableName(),
                null,
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return valueRootBuild(argumentsBuild.cursor(), callExpression, resultValueId);
    }

    /// Plain subscripts first materialize their base and arguments, then commit one explicit indexed
    /// read item that consumes those operand ids.
    private @NotNull ValueBuild buildPlainSubscriptValue(
            @NotNull BuildCursor cursor,
            @NotNull SubscriptExpression subscriptExpression,
            @Nullable String preferredResultValueId
    ) {
        var baseBuild = buildValue(cursor, subscriptExpression.base(), null);
        var argumentsBuild = buildArgumentValues(baseBuild.cursor(), subscriptExpression.arguments());
        var resultValueId = chooseResultValueId(preferredResultValueId);
        argumentsBuild.cursor().currentSequence().items().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                baseBuild.resultValueId(),
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return new ValueBuild(
                argumentsBuild.cursor(),
                subscriptExpression,
                resultValueId,
                appendSubscriptWritableRoute(
                        baseBuild,
                        subscriptExpression,
                        argumentsBuild.valueIds(),
                        determineWritableSubscriptAccessKind(
                                subscriptExpression,
                                requireWritableRouteAnchorType(subscriptExpression.base()),
                                requireWritableRouteAnchorType(subscriptExpression.arguments().getFirst())
                        )
                )
        );
    }

    /// Cast expressions keep the same “operand first, then one result item” shape as type tests.
    /// Body lowering consumes {@link gd.script.gdcc.frontend.lowering.cfg.item.CastItem} via
    /// {@code ExplicitCastSupport}. Compile-only gate does not intercept {@code CastExpression}.
    private @NotNull ValueBuild buildCastValue(
            @NotNull BuildCursor cursor,
            @NotNull CastExpression castExpression,
            @Nullable String preferredResultValueId
    ) {
        var operandBuild = buildValue(cursor, castExpression.value(), null);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        operandBuild.cursor().currentSequence().items().add(new CastItem(
                castExpression,
                operandBuild.resultValueId(),
                resultValueId
        ));
        return new ValueBuild(operandBuild.cursor(), castExpression, resultValueId, null);
    }

    /// Await expressions keep the same "operand first, then one result item" shape as casts: the
    /// operand (signal read, call, or Variant value) is built as an ordinary value, then one
    /// `AwaitItem` marks the suspension point. No control-flow split happens here — suspension is
    /// a runtime stack switch, so the item stays an ordinary value item (`AwaitInsn` is not a
    /// terminator). The published await result type was already classified by sema; this builder
    /// never re-derives it.
    private @NotNull ValueBuild buildAwaitValue(
            @NotNull BuildCursor cursor,
            @NotNull AwaitExpression awaitExpression,
            @Nullable String preferredResultValueId
    ) {
        var operand = awaitExpression.value();
        var voidCallAnchor = discardedResolvedVoidCallAnchorOrNull(operand);
        if (voidCallAnchor != null && !requireAnalysisData().isPublishedCoroutineCall(voidCallAnchor)) {
            // Redundant await on a resolved-void non-coroutine call: the call still runs for its
            // side effects through the no-result path, but there is no operand value to pass
            // through. Godot resumes such awaits with nil, so the item carries no operand id and
            // body lowering materializes nil into the (always Variant) result slot.
            var voidCursor = buildDiscardedResolvedVoidCall(cursor, operand);
            var voidResultValueId = chooseResultValueId(preferredResultValueId);
            voidCursor.currentSequence().items().add(new AwaitItem(awaitExpression, null, voidResultValueId));
            return new ValueBuild(voidCursor, awaitExpression, voidResultValueId, null);
        }
        var operandBuild = buildValue(cursor, operand, null);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        operandBuild.cursor().currentSequence().items().add(new AwaitItem(
                awaitExpression,
                operandBuild.resultValueId(),
                resultValueId
        ));
        return new ValueBuild(operandBuild.cursor(), awaitExpression, resultValueId, null);
    }

    /// Array literals evaluate elements in source order, then append one dedicated container item.
    ///
    /// CFG never replays the AST for construction: operand value ids are frozen here, and body
    /// lowering later materializes each plan boundary without re-walking children. Plan validation
    /// fails fast if semantic facts are missing, mismatched, or still carry {@code REJECT}.
    private @NotNull ValueBuild buildArrayLiteralValue(
            @NotNull BuildCursor cursor,
            @NotNull ArrayExpression arrayExpression,
            @Nullable String preferredResultValueId
    ) {
        var plan = requireContainerLiteralPlan(arrayExpression);
        var currentCursor = cursor;
        var operandValueIds = new ArrayList<String>(arrayExpression.elements().size());
        for (var element : arrayExpression.elements()) {
            var elementBuild = buildValue(currentCursor, element, null);
            currentCursor = elementBuild.cursor();
            operandValueIds.add(elementBuild.resultValueId());
        }
        validateContainerLiteralPlan(
                arrayExpression,
                plan,
                operandValueIds.size(),
                FrontendContainerLiteralPlan.OperandRole.ARRAY_ELEMENT
        );
        var resultValueId = chooseResultValueId(preferredResultValueId);
        currentCursor.currentSequence().items().add(new ContainerLiteralItem(
                arrayExpression,
                List.copyOf(operandValueIds),
                resultValueId
        ));
        return new ValueBuild(currentCursor, arrayExpression, resultValueId, null);
    }

    /// Dictionary literals freeze key/value evaluation as key0/value0/key1/value1 before construction.
    private @NotNull ValueBuild buildDictionaryLiteralValue(
            @NotNull BuildCursor cursor,
            @NotNull DictionaryExpression dictionaryExpression,
            @Nullable String preferredResultValueId
    ) {
        var plan = requireContainerLiteralPlan(dictionaryExpression);
        var currentCursor = cursor;
        var operandValueIds = new ArrayList<String>(dictionaryExpression.entries().size() * 2);
        for (var entry : dictionaryExpression.entries()) {
            var keyBuild = buildValue(currentCursor, entry.key(), null);
            currentCursor = keyBuild.cursor();
            operandValueIds.add(keyBuild.resultValueId());
            var valueBuild = buildValue(currentCursor, entry.value(), null);
            currentCursor = valueBuild.cursor();
            operandValueIds.add(valueBuild.resultValueId());
        }
        validateContainerLiteralPlan(
                dictionaryExpression,
                plan,
                operandValueIds.size(),
                FrontendContainerLiteralPlan.OperandRole.DICTIONARY_KEY
        );
        var resultValueId = chooseResultValueId(preferredResultValueId);
        currentCursor.currentSequence().items().add(new ContainerLiteralItem(
                dictionaryExpression,
                List.copyOf(operandValueIds),
                resultValueId
        ));
        return new ValueBuild(currentCursor, dictionaryExpression, resultValueId, null);
    }

    private @NotNull FrontendContainerLiteralPlan requireContainerLiteralPlan(@NotNull Expression literal) {
        var plan = requireAnalysisData().containerLiteralPlans().get(literal);
        if (plan == null) {
            throw new IllegalStateException(
                    "containerLiteralPlans() is missing a plan for "
                            + literal.getClass().getSimpleName()
                            + " at "
                            + literal.range()
            );
        }
        return plan;
    }

    /// Validates the published plan against the literal AST and expression type before emitting the item.
    ///
    /// @param expectedFirstRole array builders pass {@code ARRAY_ELEMENT}; dictionary builders pass
    ///                          {@code DICTIONARY_KEY} so the first role is family-checked, then each
    ///                          plan operand is checked for the full role sequence.
    private void validateContainerLiteralPlan(
            @NotNull Expression literal,
            @NotNull FrontendContainerLiteralPlan plan,
            int builtOperandCount,
            @NotNull FrontendContainerLiteralPlan.OperandRole expectedFirstRole
    ) {
        var publishedType = requireAnalysisData().expressionTypes().get(literal);
        if (publishedType == null || publishedType.publishedType() == null) {
            throw new IllegalStateException(
                    "expressionTypes() is missing a published type for container literal "
                            + literal.getClass().getSimpleName()
                            + " at "
                            + literal.range()
            );
        }
        if (!FrontendAnalysisData.sameType(plan.resultType(), publishedType.publishedType())) {
            throw new IllegalStateException(
                    "container literal plan resultType "
                            + plan.resultType().getTypeName()
                            + " does not match expressionTypes() "
                            + publishedType.publishedType().getTypeName()
                            + " at "
                            + literal.range()
            );
        }
        if (plan.operands().size() != builtOperandCount) {
            throw new IllegalStateException(
                    "container literal plan operand count "
                            + plan.operands().size()
                            + " does not match built operand count "
                            + builtOperandCount
                            + " for "
                            + literal.getClass().getSimpleName()
                            + " at "
                            + literal.range()
            );
        }
        validateContainerLiteralOperandRoles(literal, plan, expectedFirstRole);
        for (var operand : plan.operands()) {
            if (operand.decision() == FrontendVariantBoundaryCompatibility.Decision.REJECT) {
                throw new IllegalStateException(
                        "container literal plan still carries REJECT for "
                                + literal.getClass().getSimpleName()
                                + " at "
                                + literal.range()
                                + "; compile-error gate should have blocked lowering"
                );
            }
        }
    }

    private void validateContainerLiteralOperandRoles(
            @NotNull Expression literal,
            @NotNull FrontendContainerLiteralPlan plan,
            @NotNull FrontendContainerLiteralPlan.OperandRole expectedFirstRole
    ) {
        if (plan.operands().isEmpty()) {
            return;
        }
        if (expectedFirstRole == FrontendContainerLiteralPlan.OperandRole.ARRAY_ELEMENT) {
            for (var index = 0; index < plan.operands().size(); index++) {
                var operand = plan.operands().get(index);
                if (operand.role() != FrontendContainerLiteralPlan.OperandRole.ARRAY_ELEMENT) {
                    throw new IllegalStateException(
                            "array literal plan operand[" + index + "] role is " + operand.role()
                                    + " but expected ARRAY_ELEMENT at " + literal.range()
                    );
                }
                if (operand.sourceIndex() != index) {
                    throw new IllegalStateException(
                            "array literal plan operand[" + index + "] sourceIndex is " + operand.sourceIndex()
                                    + " but expected " + index + " at " + literal.range()
                    );
                }
            }
            return;
        }
        if (expectedFirstRole != FrontendContainerLiteralPlan.OperandRole.DICTIONARY_KEY) {
            throw new IllegalStateException(
                    "container literal plan validation expected ARRAY_ELEMENT or DICTIONARY_KEY as family marker, got "
                            + expectedFirstRole
                            + " at "
                            + literal.range()
            );
        }
        // Dictionary: plan order is key0/value0/key1/value1 with sourceIndex = entry index.
        if (plan.operands().size() % 2 != 0) {
            throw new IllegalStateException(
                    "dictionary literal plan operand count must be even (key/value pairs), got "
                            + plan.operands().size()
                            + " at "
                            + literal.range()
            );
        }
        for (var index = 0; index < plan.operands().size(); index++) {
            var operand = plan.operands().get(index);
            var expectedRole = (index % 2 == 0)
                    ? FrontendContainerLiteralPlan.OperandRole.DICTIONARY_KEY
                    : FrontendContainerLiteralPlan.OperandRole.DICTIONARY_VALUE;
            if (operand.role() != expectedRole) {
                throw new IllegalStateException(
                        "dictionary literal plan operand[" + index + "] role is " + operand.role()
                                + " but expected " + expectedRole + " at " + literal.range()
                );
            }
            var expectedSourceIndex = index / 2;
            if (operand.sourceIndex() != expectedSourceIndex) {
                throw new IllegalStateException(
                        "dictionary literal plan operand[" + index + "] sourceIndex is " + operand.sourceIndex()
                                + " but expected " + expectedSourceIndex + " at " + literal.range()
                );
            }
        }
    }

    /// Type-test expressions share the same “child first, then one explicit result item” contract as
    /// casts. Body lowering materializes the item as `is_instance_of` or a folded bool.
    private @NotNull ValueBuild buildTypeTestValue(
            @NotNull BuildCursor cursor,
            @NotNull TypeTestExpression typeTestExpression,
            @Nullable String preferredResultValueId
    ) {
        var operandBuild = buildValue(cursor, typeTestExpression.value(), null);
        var resultValueId = chooseResultValueId(preferredResultValueId);
        operandBuild.cursor().currentSequence().items().add(new TypeTestItem(
                typeTestExpression,
                operandBuild.resultValueId(),
                resultValueId
        ));
        return new ValueBuild(operandBuild.cursor(), typeTestExpression, resultValueId, null);
    }

    /// Value-context `and` / `or` reuse the condition builder so only the necessary operand path is
    /// evaluated. The taken arm then writes an explicit bool constant into a shared merged result id.
    private @NotNull ValueBuild buildShortCircuitBinaryValue(
            @NotNull BuildCursor cursor,
            @NotNull BinaryExpression binaryExpression,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        var mergeSequence = new OpenSequence(nextSequenceId());
        var rightCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var trueWriteSequence = new OpenSequence(nextSequenceId());
        var falseWriteSequence = new OpenSequence(nextSequenceId());

        var shortCircuitOperator = requireShortCircuitBinaryOperator(binaryExpression);
        var entryBuild = switch (shortCircuitOperator) {
            case AND -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        rightCursor.entryId(),
                        falseWriteSequence.id()
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueWriteSequence.id(),
                        falseWriteSequence.id()
                );
                yield leftCondition;
            }
            case OR -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        trueWriteSequence.id(),
                        rightCursor.entryId()
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueWriteSequence.id(),
                        falseWriteSequence.id()
                );
                yield leftCondition;
            }
            default -> throw unsupportedShortCircuitBinary(binaryExpression);
        };

        publishMergedBooleanWriteSequence(
                trueWriteSequence,
                binaryExpression,
                true,
                resultValueId,
                mergeSequence.id()
        );
        publishMergedBooleanWriteSequence(
                falseWriteSequence,
                binaryExpression,
                false,
                resultValueId,
                mergeSequence.id()
        );
        return new ValueBuild(new BuildCursor(entryBuild.entryId(), mergeSequence), binaryExpression, resultValueId, null);
    }

    /// Condition-context `and` / `or` split the left fragment first, then only build the right
    /// fragment on the path where source semantics require it.
    private @NotNull ConditionBuild buildShortCircuitCondition(
            @NotNull BuildCursor cursor,
            @NotNull BinaryExpression binaryExpression,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var shortCircuitOperator = requireShortCircuitBinaryOperator(binaryExpression);
        var rightCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        return switch (shortCircuitOperator) {
            case AND -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        rightCursor.entryId(),
                        falseTargetId
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueTargetId,
                        falseTargetId
                );
                yield leftCondition;
            }
            case OR -> {
                var leftCondition = buildCondition(
                        cursor,
                        binaryExpression.left(),
                        trueTargetId,
                        rightCursor.entryId()
                );
                buildCondition(
                        rightCursor,
                        binaryExpression.right(),
                        trueTargetId,
                        falseTargetId
                );
                yield leftCondition;
            }
            default -> throw unsupportedShortCircuitBinary(binaryExpression);
        };
    }

    /// Value-context ternary `left if condition else right` evaluates the condition first, then only
    /// the selected arm, and merges the arm value into one shared result id.
    ///
    /// The shape mirrors value-context `and` / `or`: one condition subgraph, two arm sequences that
    /// each end in a `MergeValueItem` write, and one merge continuation sequence. Arms build with a
    /// `null` preferred id so each keeps a private temp; the shared id is only written through the
    /// merge items, which keeps the multi-producer single-definition contract merge-only.
    /// Nested ternary or value-context `and` / `or` arms recurse through `buildValue` and return an
    /// unpublished inner merge sequence; the outer merge write is appended to that same sequence, so
    /// the cross-sequence source stays legal under the merge-of-merge contract.
    private @NotNull ValueBuild buildConditionalExpressionValue(
            @NotNull BuildCursor cursor,
            @NotNull ConditionalExpression conditionalExpression,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        var mergeSequence = new OpenSequence(nextSequenceId());
        var trueArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var falseArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));

        var conditionBuild = buildCondition(
                cursor,
                conditionalExpression.condition(),
                trueArmCursor.entryId(),
                falseArmCursor.entryId()
        );

        publishMergedConditionalArmSequence(
                trueArmCursor,
                conditionalExpression,
                conditionalExpression.left(),
                resultValueId,
                mergeSequence.id()
        );
        publishMergedConditionalArmSequence(
                falseArmCursor,
                conditionalExpression,
                conditionalExpression.right(),
                resultValueId,
                mergeSequence.id()
        );
        return new ValueBuild(
                new BuildCursor(conditionBuild.entryId(), mergeSequence),
                conditionalExpression,
                resultValueId,
                null
        );
    }

    /// Builds one ternary arm and publishes its final sequence with the merge write appended.
    ///
    /// `mergeAnchor` is the whole `ConditionalExpression`: the shared merge slot type is keyed by the
    /// anchor's published merged type, so both arms write the same slot regardless of arm types.
    private void publishMergedConditionalArmSequence(
            @NotNull BuildCursor armCursor,
            @NotNull ConditionalExpression mergeAnchor,
            @NotNull Expression armExpression,
            @NotNull String mergedResultValueId,
            @NotNull String nextId
    ) {
        var armBuild = buildValue(armCursor, armExpression, null);
        var armSequence = armBuild.cursor().currentSequence();
        armSequence.items().add(new MergeValueItem(mergeAnchor, armBuild.resultValueId(), mergedResultValueId));
        publishSequenceNode(armSequence.id(), armSequence.items(), nextId);
    }

    /// Condition-context ternary expands as pure control flow and never produces a merge value.
    ///
    /// `if (a if c else b):` first tests `c`, then tests the selected arm's truthiness against the
    /// outer targets. Arms recurse through `buildCondition`, so `not` / `and` / `or` / nested ternary
    /// arms reuse the existing condition machinery and plain arms land in `buildConditionFromValue`
    /// with a temp-slot value. Keeping `conditionValueId` temp-local is required: branch lowering
    /// reads `cfg_tmp_<conditionValueId>` and the merge slot of a value-context build is never a
    /// legal branch condition id.
    private @NotNull ConditionBuild buildConditionalExpressionCondition(
            @NotNull BuildCursor cursor,
            @NotNull ConditionalExpression conditionalExpression,
            @NotNull String trueTargetId,
            @NotNull String falseTargetId
    ) {
        var trueArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var falseArmCursor = new BuildCursor(new OpenSequence(nextSequenceId()));
        var conditionBuild = buildCondition(
                cursor,
                conditionalExpression.condition(),
                trueArmCursor.entryId(),
                falseArmCursor.entryId()
        );
        buildCondition(trueArmCursor, conditionalExpression.left(), trueTargetId, falseTargetId);
        buildCondition(falseArmCursor, conditionalExpression.right(), trueTargetId, falseTargetId);
        return conditionBuild;
    }

    /// Plain assignment preserves target evaluation before RHS evaluation.
    ///
    /// Compound assignment uses a separate read-modify-write path so `AssignmentItem` can stay the
    /// minimal "final store commit" node.
    private @NotNull BuildCursor buildAssignmentCommit(
            @NotNull BuildCursor cursor,
            @NotNull AssignmentExpression assignmentExpression
    ) {
        if (isCompoundAssignmentOperator(assignmentExpression)) {
            return buildCompoundAssignmentCommit(cursor, assignmentExpression);
        }
        var targetOperandsBuild = buildAssignmentTargetOperands(cursor, assignmentExpression.left());
        var rhsBuild = buildValue(targetOperandsBuild.cursor(), assignmentExpression.right(), null);
        rhsBuild.cursor().currentSequence().items().add(new AssignmentItem(
                assignmentExpression,
                targetOperandsBuild.valueIds(),
                rhsBuild.resultValueId(),
                null,
                targetOperandsBuild.writableRoutePayload().withRouteAnchor(assignmentExpression)
        ));
        return rhsBuild.cursor();
    }

    /// Compound assignment must freeze read-modify-write explicitly instead of pretending the target
    /// can be rebuilt from source later.
    ///
    /// The source-order contract is:
    /// 1. freeze target receiver/index operands once
    /// 2. read the current target value from those frozen operands
    /// 3. evaluate the RHS
    /// 4. publish one explicit compound-op value item
    /// 5. commit the computed result through the ordinary assignment store route
    private @NotNull BuildCursor buildCompoundAssignmentCommit(
            @NotNull BuildCursor cursor,
            @NotNull AssignmentExpression assignmentExpression
    ) {
        var binaryOperatorLexeme = requireCompoundBinaryOperatorLexeme(assignmentExpression);
        var targetOperandsBuild = buildAssignmentTargetOperands(cursor, assignmentExpression.left());
        var currentTargetValueBuild = buildCompoundAssignmentCurrentTargetValue(
                targetOperandsBuild.cursor(),
                assignmentExpression.left(),
                targetOperandsBuild.valueIds()
        );
        var rhsBuild = buildValue(currentTargetValueBuild.cursor(), assignmentExpression.right(), null);
        var compoundResultValueId = nextValueId();
        rhsBuild.cursor().currentSequence().items().add(new CompoundAssignmentBinaryOpItem(
                assignmentExpression,
                binaryOperatorLexeme,
                currentTargetValueBuild.resultValueId(),
                rhsBuild.resultValueId(),
                compoundResultValueId
        ));
        rhsBuild.cursor().currentSequence().items().add(new AssignmentItem(
                assignmentExpression,
                targetOperandsBuild.valueIds(),
                compoundResultValueId,
                null,
                targetOperandsBuild.writableRoutePayload().withRouteAnchor(assignmentExpression)
        ));
        return rhsBuild.cursor();
    }

    /// Builds the already-evaluated operands required to commit one assignment target.
    ///
    /// The target AST itself remains on `AssignmentItem`, but any child expressions with real
    /// evaluation order, such as chain prefixes or subscript arguments, are materialized here so
    /// later lowering does not need to recurse back into the target subtree to discover them.
    ///
    /// Every currently supported assignment target must also publish one writable-route payload here.
    /// Body lowering no longer has an AST-replay fallback for final stores.
    private @NotNull AssignmentTargetBuild buildAssignmentTargetOperands(
            @NotNull BuildCursor cursor,
            @NotNull Expression targetExpression
    ) {
        return switch (targetExpression) {
            case IdentifierExpression identifierExpression -> new AssignmentTargetBuild(
                    cursor,
                    List.of(),
                    requireIdentifierWritableRoute(identifierExpression)
            );
            case AttributeExpression attributeExpression -> buildAttributeTargetOperands(cursor, attributeExpression);
            case SubscriptExpression subscriptExpression -> {
                var operands = new ArrayList<String>(1 + subscriptExpression.arguments().size());
                var baseBuild = buildAssignmentTargetValue(cursor, subscriptExpression.base());
                operands.add(baseBuild.resultValueId());
                var argumentsBuild = buildArgumentValues(baseBuild.cursor(), subscriptExpression.arguments());
                operands.addAll(argumentsBuild.valueIds());
                yield new AssignmentTargetBuild(
                        argumentsBuild.cursor(),
                        List.copyOf(operands),
                        appendSubscriptWritableRoute(
                                baseBuild,
                                subscriptExpression,
                                argumentsBuild.valueIds(),
                                determineWritableSubscriptAccessKind(
                                        subscriptExpression,
                                        requireWritableRouteAnchorType(subscriptExpression.base()),
                                        requireWritableRouteAnchorType(subscriptExpression.arguments().getFirst())
                                )
                        ).withRouteAnchor(subscriptExpression)
                );
            }
            default -> throw unsupportedReachableAssignmentTarget(targetExpression);
        };
    }

    /// Compound assignment reuses the already-frozen assignment-target operands to read the current
    /// target value. Rebuilding the original LHS expression here would duplicate side effects such as
    /// prefix calls or subscript indexes.
    private @NotNull ValueBuild buildCompoundAssignmentCurrentTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression targetExpression,
            @NotNull List<String> frozenTargetOperandValueIds
    ) {
        return switch (targetExpression) {
            case IdentifierExpression identifierExpression ->
                    emitOpaqueValue(cursor, identifierExpression, List.of(), null);
            case AttributeExpression attributeExpression -> buildCompoundAssignmentAttributeTargetValue(
                    cursor,
                    attributeExpression,
                    frozenTargetOperandValueIds
            );
            case SubscriptExpression subscriptExpression -> buildCompoundAssignmentSubscriptTargetValue(
                    cursor,
                    subscriptExpression,
                    frozenTargetOperandValueIds
            );
            default -> throw unsupportedReachableAssignmentTarget(targetExpression);
        };
    }

    /// Attribute assignment targets lower every prefix step as an ordinary read/call/subscript chain,
    /// then stop before the final writable slot.
    ///
    /// For example:
    /// - `obj.payload = rhs` publishes only the receiver value for `obj`
    /// - `obj.items[i] = rhs` publishes the receiver value for `obj` plus the index operand ids
    /// - `obj.a().items[i] = rhs` first builds `obj.a()` as explicit prefix value-ops, then exports
    ///   the final target receiver/index operands
    private @NotNull AssignmentTargetBuild buildAttributeTargetOperands(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression assignment target must contain at least one step");
        }

        // Type-meta heads (`Worker.shared = v`, `Worker.values[i] = v`) never materialize the head
        // identifier as a runtime value; the target route must start from the published type-meta
        // fact directly instead of walking an opaque base value.
        ValueBuild currentBuild;
        int firstPrefixStepIndex;
        if (isTypeMetaHeadAttributeExpression(attributeExpression)) {
            var firstStep = attributeExpression.steps().getFirst();
            if (attributeExpression.steps().size() == 1) {
                return buildTypeMetaHeadSingleStepTargetOperands(cursor, attributeExpression, firstStep);
            }
            currentBuild = buildTypeMetaHeadFirstStepValue(cursor, attributeExpression, firstStep, null);
            firstPrefixStepIndex = 1;
        } else {
            currentBuild = buildAssignmentTargetValue(cursor, attributeExpression.base());
            firstPrefixStepIndex = 0;
        }
        for (var stepIndex = firstPrefixStepIndex; stepIndex + 1 < attributeExpression.steps().size(); stepIndex++) {
            currentBuild = applyAttributeStep(currentBuild, attributeExpression.steps().get(stepIndex), null);
        }

        var finalStep = attributeExpression.steps().getLast();
        return switch (finalStep) {
            case AttributePropertyStep attributePropertyStep -> new AssignmentTargetBuild(
                    currentBuild.cursor(),
                    List.of(currentBuild.resultValueId()),
                    appendPropertyWritableRoute(
                            currentBuild,
                            attributePropertyStep,
                            attributePropertyStep.name()
                    ).withRouteAnchor(attributeExpression)
            );
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var operands = new ArrayList<String>(1 + attributeSubscriptStep.arguments().size());
                operands.add(currentBuild.resultValueId());
                var argumentsBuild = buildArgumentValues(currentBuild.cursor(), attributeSubscriptStep.arguments());
                operands.addAll(argumentsBuild.valueIds());
                yield new AssignmentTargetBuild(
                        argumentsBuild.cursor(),
                        List.copyOf(operands),
                        appendSubscriptWritableRoute(
                                currentBuild,
                                attributeSubscriptStep,
                                argumentsBuild.valueIds(),
                                determineWritableSubscriptAccessKind(
                                        attributeSubscriptStep,
                                        requireWritableRouteAnchorType(currentBuild.valueAnchor()),
                                        requireWritableRouteAnchorType(attributeSubscriptStep.arguments().getFirst())
                                )
                        ).withRouteAnchor(attributeExpression)
                );
            }
            default -> throw new IllegalStateException(
                    "Assignment target step '"
                            + finalStep.getClass().getSimpleName()
                            + "' is not supported by the current frontend CFG contract"
            );
        };
    }

    /// Single-step type-meta assignment targets (`Worker.shared = v`, `Worker.values[i] = v`) carry
    /// no runtime receiver operand: the `STATIC_CONTEXT` root plus the published type-meta member
    /// fact already identify the shared static storage, and body lowering resolves the declaring
    /// owner through the hierarchy exactly like a `ClassName.name` load.
    ///
    /// - property form: the `PROPERTY` leaf stays terminal (no commit steps), matching the bare
    ///   `shared = v` identifier route
    /// - subscript form: the container enters as one `MemberLoadItem(..., null, ...)` (lowered to
    ///   `LoadStaticInsn`), the subscript leaf is a plain base[key] route on that container value,
    ///   and the promoted terminal `PROPERTY` commit step anchored at the subscript step writes the
    ///   container back through `StoreStaticInsn` when the carrier requires it
    private @NotNull AssignmentTargetBuild buildTypeMetaHeadSingleStepTargetOperands(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @NotNull AttributeStep firstStep
    ) {
        return switch (firstStep) {
            case AttributePropertyStep attributePropertyStep -> {
                var publishedMember = requireLoweringReadyMember(attributePropertyStep);
                yield new AssignmentTargetBuild(
                        cursor,
                        List.of(),
                        new FrontendWritableRoutePayload(
                                attributeExpression,
                                new FrontendWritableRoutePayload.RootDescriptor(
                                        FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT,
                                        attributeExpression.base(),
                                        null
                                ),
                                new FrontendWritableRoutePayload.LeafDescriptor(
                                        FrontendWritableRoutePayload.LeafKind.PROPERTY,
                                        attributePropertyStep,
                                        null,
                                        List.of(),
                                        publishedMember.memberName(),
                                        null
                                ),
                                List.of()
                        ).withRouteAnchor(attributeExpression)
                );
            }
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var containerMember = requireTypeMetaStaticContainerMember(attributeSubscriptStep);
                var containerValueId = nextValueId();
                cursor.currentSequence().items().add(new MemberLoadItem(
                        attributeSubscriptStep,
                        containerMember.memberName(),
                        null,
                        containerValueId
                ));
                var argumentsBuild = buildArgumentValues(cursor, attributeSubscriptStep.arguments());
                requireSingleWritableRouteKey(attributeSubscriptStep, argumentsBuild.valueIds());
                var operands = new ArrayList<String>(1 + argumentsBuild.valueIds().size());
                operands.add(containerValueId);
                operands.addAll(argumentsBuild.valueIds());
                yield new AssignmentTargetBuild(
                        argumentsBuild.cursor(),
                        List.copyOf(operands),
                        new FrontendWritableRoutePayload(
                                attributeExpression,
                                new FrontendWritableRoutePayload.RootDescriptor(
                                        FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT,
                                        attributeExpression.base(),
                                        null
                                ),
                                new FrontendWritableRoutePayload.LeafDescriptor(
                                        FrontendWritableRoutePayload.LeafKind.SUBSCRIPT,
                                        attributeSubscriptStep,
                                        containerValueId,
                                        List.copyOf(argumentsBuild.valueIds()),
                                        null,
                                        determineWritableSubscriptAccessKind(
                                                attributeSubscriptStep,
                                                requireTypeMetaStaticContainerType(containerMember),
                                                requireWritableRouteAnchorType(attributeSubscriptStep.arguments().getFirst())
                                        )
                                ),
                                List.of(new FrontendWritableRoutePayload.StepDescriptor(
                                        FrontendWritableRoutePayload.StepKind.PROPERTY,
                                        attributeSubscriptStep,
                                        null,
                                        List.of(),
                                        containerMember.memberName(),
                                        null
                                ))
                        ).withRouteAnchor(attributeExpression)
                );
            }
            default -> throw new IllegalStateException(
                    "Type-meta assignment target step '"
                            + firstStep.getClass().getSimpleName()
                            + "' is not supported by the current frontend CFG contract"
            );
        };
    }

    /// Attribute compound-assignment reads reuse the frozen final receiver/index operands instead of
    /// replaying the prefix chain. The final writable step becomes one explicit load item that feeds
    /// the later compound binary op.
    private @NotNull ValueBuild buildCompoundAssignmentAttributeTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression,
            @NotNull List<String> frozenTargetOperandValueIds
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression compound target must contain at least one step");
        }
        var finalStep = attributeExpression.steps().getLast();
        // A single-step type-meta target (`Worker.shared += v` / `Worker.values[i] += v`) freezes no
        // runtime receiver operand: the current-value read must load the shared static storage
        // directly instead of consuming a frozen receiver value id.
        var typeMetaSingleStepTarget = attributeExpression.steps().size() == 1
                && isTypeMetaHeadAttributeExpression(attributeExpression);
        return switch (finalStep) {
            case AttributePropertyStep attributePropertyStep -> {
                requireLoweringReadyCompoundMemberRead(attributePropertyStep);
                var receiverValueId = typeMetaSingleStepTarget
                        ? null
                        : requireFrozenTargetOperandValue(
                        frozenTargetOperandValueIds,
                        attributeExpression,
                        "receiver"
                );
                var resultValueId = nextValueId();
                cursor.currentSequence().items().add(new MemberLoadItem(
                        attributePropertyStep,
                        attributePropertyStep.name(),
                        receiverValueId,
                        resultValueId
                ));
                yield new ValueBuild(cursor, attributePropertyStep, resultValueId, null);
            }
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var receiverValueId = requireFrozenTargetOperandValue(
                        frozenTargetOperandValueIds,
                        attributeExpression,
                        typeMetaSingleStepTarget ? "container" : "receiver"
                );
                var resultValueId = nextValueId();
                cursor.currentSequence().items().add(new SubscriptLoadItem(
                        attributeSubscriptStep,
                        // The type-meta head form loaded the container itself as the frozen first
                        // operand, so the compound read is a plain base[key] subscript on it.
                        typeMetaSingleStepTarget ? null : attributeSubscriptStep.name(),
                        receiverValueId,
                        requireFrozenTargetTrailingOperands(frozenTargetOperandValueIds, attributeExpression),
                        resultValueId
                ));
                yield new ValueBuild(cursor, attributeSubscriptStep, resultValueId, null);
            }
            default -> throw new IllegalStateException(
                    "Compound assignment target step '"
                            + finalStep.getClass().getSimpleName()
                            + "' is not supported by the current frontend CFG contract"
            );
        };
    }

    private @NotNull ValueBuild buildCompoundAssignmentSubscriptTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull SubscriptExpression subscriptExpression,
            @NotNull List<String> frozenTargetOperandValueIds
    ) {
        var receiverValueId = requireFrozenTargetOperandValue(
                frozenTargetOperandValueIds,
                subscriptExpression,
                "base"
        );
        var resultValueId = nextValueId();
        cursor.currentSequence().items().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                receiverValueId,
                requireFrozenTargetTrailingOperands(frozenTargetOperandValueIds, subscriptExpression),
                resultValueId
        ));
        return new ValueBuild(cursor, subscriptExpression, resultValueId, null);
    }

    /// Assignment-target prefixes are not always part of the ordinary published `expressionTypes()`
    /// surface.
    ///
    /// For example, the container base of `items[idx] = rhs` may only be visited through assignment
    /// target analysis, so the builder cannot require a normal expression-type entry before it
    /// materializes the receiver value. This helper therefore falls back to a target-specific value
    /// path for those prefixes while still reusing ordinary `buildValue(...)` whenever a lowering-ready
    /// expression fact actually exists.
    private @NotNull ValueBuild buildAssignmentTargetValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression
    ) {
        if (hasLoweringReadyExpressionType(expression)) {
            return buildValue(cursor, expression, null);
        }
        return switch (expression) {
            case IdentifierExpression _, SelfExpression _ -> emitOpaqueValue(cursor, expression, List.of(), null);
            case AttributeExpression attributeExpression ->
                    buildAssignmentTargetAttributeValue(cursor, attributeExpression);
            case SubscriptExpression subscriptExpression ->
                    buildAssignmentTargetSubscriptValue(cursor, subscriptExpression);
            case CallExpression callExpression -> buildBareCallValue(cursor, callExpression, null);
            default -> throw new IllegalStateException(
                    "Assignment target value "
                            + expression.getClass().getSimpleName()
                            + " is missing a lowering-ready expression fact"
            );
        };
    }

    private @NotNull ValueBuild buildAssignmentTargetAttributeValue(
            @NotNull BuildCursor cursor,
            @NotNull AttributeExpression attributeExpression
    ) {
        if (attributeExpression.steps().isEmpty()) {
            throw new IllegalStateException("AttributeExpression target value must contain at least one step");
        }
        var currentBuild = buildAssignmentTargetValue(cursor, attributeExpression.base());
        for (var step : attributeExpression.steps()) {
            currentBuild = applyAttributeStep(currentBuild, step, null);
        }
        return currentBuild;
    }

    private @NotNull ValueBuild buildAssignmentTargetSubscriptValue(
            @NotNull BuildCursor cursor,
            @NotNull SubscriptExpression subscriptExpression
    ) {
        var baseBuild = buildAssignmentTargetValue(cursor, subscriptExpression.base());
        var argumentsBuild = buildArgumentValues(baseBuild.cursor(), subscriptExpression.arguments());
        var resultValueId = chooseResultValueId(null);
        argumentsBuild.cursor().currentSequence().items().add(new SubscriptLoadItem(
                subscriptExpression,
                null,
                baseBuild.resultValueId(),
                argumentsBuild.valueIds(),
                resultValueId
        ));
        return new ValueBuild(
                argumentsBuild.cursor(),
                subscriptExpression,
                resultValueId,
                appendSubscriptWritableRoute(
                        baseBuild,
                        subscriptExpression,
                        argumentsBuild.valueIds(),
                        determineWritableSubscriptAccessKind(
                                subscriptExpression,
                                requireWritableRouteAnchorType(subscriptExpression.base()),
                                requireWritableRouteAnchorType(subscriptExpression.arguments().getFirst())
                        )
                )
        );
    }

    /// Applies one attribute-chain step to the current receiver value and returns the produced value id.
    ///
    /// The step kind decides which explicit item is emitted, but the overall contract stays uniform:
    /// the receiver value id arrives from the previous chain segment, step-local arguments are built
    /// before the item is appended, and the returned value id becomes the receiver for the next step.
    private @NotNull ValueBuild applyAttributeStep(
            @NotNull ValueBuild receiverBuild,
            @NotNull AttributeStep step,
            @Nullable String preferredResultValueId
    ) {
        return switch (step) {
            case AttributePropertyStep attributePropertyStep -> {
                var publishedMember = requireLoweringReadyMember(attributePropertyStep);
                var resultValueId = chooseResultValueId(preferredResultValueId);
                if (isResolvedSignalMember(publishedMember)) {
                    // RESOLVED SIGNAL reads construct a fresh value. Do not attach a property
                    // writable route; assignment already fails in shared semantic analysis.
                    receiverBuild.cursor().currentSequence().items().add(new SignalLoadItem(
                            attributePropertyStep,
                            publishedMember.memberName(),
                            receiverBuild.resultValueId(),
                            resultValueId
                    ));
                    yield new ValueBuild(
                            receiverBuild.cursor(),
                            attributePropertyStep,
                            resultValueId,
                            null
                    );
                }
                if (isResolvedInstanceMethodReference(publishedMember)) {
                    // RESOLVED Object/self or non-Dictionary builtin METHOD reads construct a
                    // fresh Callable. Do not attach a property writable route.
                    receiverBuild.cursor().currentSequence().items().add(new CallableLoadItem(
                            attributePropertyStep,
                            publishedMember.memberName(),
                            receiverBuild.resultValueId(),
                            resultValueId
                    ));
                    yield new ValueBuild(
                            receiverBuild.cursor(),
                            attributePropertyStep,
                            resultValueId,
                            null
                    );
                }
                if (isResolvedUnsupportedMethodReference(publishedMember)) {
                    throw new IllegalStateException(
                            "RESOLVED method-reference '"
                                    + publishedMember.memberName()
                                    + "' cannot lower as a property; Dictionary keys and type-meta builtin methods stay unsupported"
                    );
                }
                receiverBuild.cursor().currentSequence().items().add(new MemberLoadItem(
                        attributePropertyStep,
                        publishedMember.memberName(),
                        receiverBuild.resultValueId(),
                        resultValueId
                ));
                yield new ValueBuild(
                        receiverBuild.cursor(),
                        attributePropertyStep,
                        resultValueId,
                        appendPropertyWritableRoute(
                                receiverBuild,
                                attributePropertyStep,
                                publishedMember.memberName()
                        )
                );
            }
            case AttributeCallStep attributeCallStep -> {
                var publishedCall = requireLoweringReadyCall(attributeCallStep);
                checkValueProducingCall(publishedCall, attributeCallStep, "attribute call step");
                receiverBuild = maybePublishDirectSlotReceiverAlias(
                        receiverBuild,
                        publishedCall,
                        attributeCallStep.arguments()
                );
                var argumentsBuild = buildArgumentValues(receiverBuild.cursor(), attributeCallStep.arguments());
                var resultValueId = chooseResultValueId(preferredResultValueId);
                var receiverRoute = routePayloadOrValueRoot(receiverBuild);
                argumentsBuild.cursor().currentSequence().items().add(new CallItem(
                        attributeCallStep,
                        publishedCall.callableName(),
                        receiverBuild.resultValueId(),
                        argumentsBuild.valueIds(),
                        resultValueId,
                        // Mutating call receivers reuse the current receiver leaf as the call object and
                        // therefore need the promoted leaf to appear in reverseCommitSteps. Without this,
                        // property/subscript receivers would carry provenance but no actual post-call
                        // writeback plan. Static property receivers stay terminal (see
                        // appendCallReceiverCommitSteps).
                        new FrontendWritableRoutePayload(
                                attributeCallStep,
                                receiverRoute.root(),
                                receiverRoute.leaf(),
                                appendCallReceiverCommitSteps(receiverRoute, publishedCall)
                        )
                ));
                yield valueRootBuild(argumentsBuild.cursor(), attributeCallStep, resultValueId);
            }
            case AttributeSubscriptStep attributeSubscriptStep -> {
                var argumentsBuild = buildArgumentValues(receiverBuild.cursor(), attributeSubscriptStep.arguments());
                var resultValueId = chooseResultValueId(preferredResultValueId);
                argumentsBuild.cursor().currentSequence().items().add(new SubscriptLoadItem(
                        attributeSubscriptStep,
                        attributeSubscriptStep.name(),
                        receiverBuild.resultValueId(),
                        argumentsBuild.valueIds(),
                        resultValueId
                ));
                yield new ValueBuild(
                        argumentsBuild.cursor(),
                        attributeSubscriptStep,
                        resultValueId,
                        appendSubscriptWritableRoute(
                                receiverBuild,
                                attributeSubscriptStep,
                                argumentsBuild.valueIds(),
                                determineWritableSubscriptAccessKind(
                                        attributeSubscriptStep,
                                        requireWritableRouteAnchorType(receiverBuild.valueAnchor()),
                                        requireWritableRouteAnchorType(attributeSubscriptStep.arguments().getFirst())
                                )
                        )
                );
            }
            default -> throw new IllegalStateException(
                    "Unsupported attribute step in frontend CFG builder: " + step.getClass().getSimpleName()
            );
        };
    }

    private @NotNull ValueListBuild buildArgumentValues(
            @NotNull BuildCursor cursor,
            @NotNull List<Expression> arguments
    ) {
        var currentCursor = cursor;
        var valueIds = new ArrayList<String>(arguments.size());
        for (var argument : arguments) {
            var argumentBuild = buildValue(currentCursor, argument, null);
            currentCursor = argumentBuild.cursor();
            valueIds.add(argumentBuild.resultValueId());
        }
        return new ValueListBuild(currentCursor, List.copyOf(valueIds));
    }

    /// Generic opaque items still exist as a bridge for simple expression forms whose exact lowering
    /// will be finalized later, but they no longer hide nested child evaluation order.
    private @NotNull ValueBuild emitOpaqueValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression,
            @NotNull List<String> operandValueIds,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        cursor.currentSequence().items().add(new OpaqueExprValueItem(expression, operandValueIds, resultValueId));
        var route = routePayloadForOpaqueExpression(expression);
        return new ValueBuild(
                cursor,
                expression,
                resultValueId,
                route.writableRoutePayloadOrNull(),
                route.bindingOrNull()
        );
    }

    /// Alias publication stays narrower than generic identifier/self lowering: this item is reserved
    /// for direct-slot mutating receivers whose dedicated value id must stay bound to one trusted
    /// source slot instead of becoming a dead `cfg_tmp_*`.
    private @NotNull ValueBuild emitDirectSlotAliasValue(
            @NotNull BuildCursor cursor,
            @NotNull Expression expression,
            @Nullable String preferredResultValueId
    ) {
        var resultValueId = chooseResultValueId(preferredResultValueId);
        cursor.currentSequence().items().add(new DirectSlotAliasValueItem(expression, resultValueId));
        var route = routePayloadForOpaqueExpression(expression);
        return new ValueBuild(
                cursor,
                expression,
                resultValueId,
                route.writableRoutePayloadOrNull(),
                route.bindingOrNull()
        );
    }

    /// Ordinary value publication and writable-route publication are deliberately kept separate:
    /// CFG items still own evaluation order, while the route payload only freezes how a later
    /// mutation on the published value could be written back into its owner chain.
    /// Returns a non-null carrier so "no writable route" stays distinct from "route analysis did not run".
    ///
    /// `bindingOrNull` is intentionally preserved for identifier/self value roots whose binding affects
    /// later direct-slot alias eligibility, while `writableRoutePayloadOrNull` remains nullable because
    /// constants and non-root opaque expressions are readable values without writable provenance.
    private @NotNull OpaqueExpressionRoute routePayloadForOpaqueExpression(@NotNull Expression expression) {
        return switch (expression) {
            case IdentifierExpression identifierExpression -> buildIdentifierOpaqueRoute(identifierExpression);
            case SelfExpression selfExpression -> {
                var binding = requirePublishedBinding(selfExpression);
                if (binding.kind() != FrontendBindingKind.SELF) {
                    throw new IllegalStateException(
                            "SelfExpression writable-route publication requires binding kind SELF, but got "
                                    + binding.kind()
                    );
                }
                yield new OpaqueExpressionRoute(
                        new FrontendWritableRoutePayload(
                                selfExpression,
                                new FrontendWritableRoutePayload.RootDescriptor(
                                        FrontendWritableRoutePayload.RootKind.DIRECT_SLOT,
                                        selfExpression,
                                        null
                                ),
                                new FrontendWritableRoutePayload.LeafDescriptor(
                                        FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                                        selfExpression,
                                        null,
                                        List.of(),
                                        null,
                                        null
                                ),
                                List.of()
                        ),
                        binding
                );
            }
            default -> OpaqueExpressionRoute.empty();
        };
    }

    private @NotNull FrontendWritableRoutePayload requireIdentifierWritableRoute(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var route = buildIdentifierOpaqueRoute(identifierExpression);
        var payload = route.writableRoutePayloadOrNull();
        if (payload != null) {
            return payload;
        }
        var binding = Objects.requireNonNull(
                route.bindingOrNull(),
                "identifier route must publish bindingOrNull"
        );
        throw new IllegalStateException(
                "Identifier assignment target '"
                        + identifierExpression.name()
                        + "' with binding kind "
                        + binding.kind()
                        + " cannot publish a writable route"
        );
    }

    private @NotNull OpaqueExpressionRoute buildIdentifierOpaqueRoute(
            @NotNull IdentifierExpression identifierExpression
    ) {
        var binding = requirePublishedBinding(identifierExpression);
        var payload = switch (binding.kind()) {
            case LOCAL_VAR, PARAMETER, CAPTURE -> new FrontendWritableRoutePayload(
                    identifierExpression,
                    new FrontendWritableRoutePayload.RootDescriptor(
                            FrontendWritableRoutePayload.RootKind.DIRECT_SLOT,
                            identifierExpression,
                            null
                    ),
                    new FrontendWritableRoutePayload.LeafDescriptor(
                            FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                            identifierExpression,
                            null,
                            List.of(),
                            null,
                            null
                    ),
                    List.of()
            );
            case SELF -> throw new IllegalStateException(
                    "Identifier writable-route publication must use explicit SelfExpression instead of binding kind SELF"
            );
            case PROPERTY -> new FrontendWritableRoutePayload(
                    identifierExpression,
                    new FrontendWritableRoutePayload.RootDescriptor(
                            isStaticPropertyBinding(binding)
                                    ? FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT
                                    : FrontendWritableRoutePayload.RootKind.SELF_CONTEXT,
                            identifierExpression,
                            null
                    ),
                    new FrontendWritableRoutePayload.LeafDescriptor(
                            FrontendWritableRoutePayload.LeafKind.PROPERTY,
                            identifierExpression,
                            null,
                            List.of(),
                            binding.symbolName(),
                            null
                    ),
                    List.of()
            );
            // SUPER is a read-only `self` alias restricted to call receiver position; it never
            // becomes a writable route root (super calls skip reverse-commit writeback).
            case CONSTANT, SINGLETON, SIGNAL, METHOD, STATIC_METHOD, UTILITY_FUNCTION, SUPER -> null;
            default -> throw new IllegalStateException(
                    "Identifier writable-route publication is not supported for binding kind " + binding.kind()
            );
        };
        return new OpaqueExpressionRoute(payload, binding);
    }

    private @NotNull FrontendWritableRoutePayload routePayloadOrValueRoot(@NotNull ValueBuild valueBuild) {
        return valueBuild.writableRoutePayloadOrNull() != null
                ? valueBuild.writableRoutePayloadOrNull()
                : valueRootRoutePayload(valueBuild.valueAnchor(), valueBuild.resultValueId());
    }

    /// Direct-slot alias publication is valid only for the narrow mutating-receiver surface:
    /// - the receiver is already a direct-slot writable root
    /// - the current publication is still the generic opaque temp path
    /// - the receiver belongs to one explicit root category (`SelfExpression`, `LOCAL_VAR`,
    ///   `PARAMETER`) instead of an implicit/self-context fallback
    /// - `CAPTURE` is intentionally excluded until lambda/capture lowering semantics are frozen;
    ///   otherwise alias publication would prematurely promise live-slot behavior for a deferred surface
    /// - for identifier-backed roots, later argument evaluation must stay inside a proven
    ///   no-rebinding subset; otherwise builder deliberately keeps the ordinary temp snapshot
    private @NotNull ValueBuild maybePublishDirectSlotReceiverAlias(
            @NotNull ValueBuild receiverBuild,
            @NotNull FrontendResolvedCall publishedCall,
            @NotNull List<Expression> arguments
    ) {
        if (!FrontendCallMutabilitySupport.mayMutateReceiver(publishedCall)) {
            return receiverBuild;
        }
        var routePayload = receiverBuild.writableRoutePayloadOrNull();
        if (routePayload == null
                || routePayload.root().kind() != FrontendWritableRoutePayload.RootKind.DIRECT_SLOT
                || routePayload.leaf().kind() != FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT) {
            return receiverBuild;
        }
        if (!(receiverBuild.valueAnchor() instanceof IdentifierExpression || receiverBuild.valueAnchor() instanceof SelfExpression)) {
            return receiverBuild;
        }
        var items = receiverBuild.cursor().currentSequence().items();
        if (!(items.getLast() instanceof OpaqueExprValueItem opaqueValueItem)
                || !opaqueValueItem.resultValueId().equals(receiverBuild.resultValueId())
                || opaqueValueItem.expression() != receiverBuild.valueAnchor()) {
            return receiverBuild;
        }
        var aliasRoot = requireDirectSlotAliasRoot(receiverBuild);
        if (!shouldPublishDirectSlotAlias(aliasRoot, arguments)) {
            return receiverBuild;
        }
        items.removeLast();
        return emitDirectSlotAliasValue(
                receiverBuild.cursor(),
                (Expression) receiverBuild.valueAnchor(),
                receiverBuild.resultValueId()
        );
    }

    /// Alias safety is phrased in terms of caller storage semantics, not one hard-coded AST node:
    /// - explicit `self` is always stable because user code cannot rebind the `self` slot
    /// - local/parameter/capture roots only alias when every later argument stays inside a proven
    ///   no-rebinding subset
    /// - anything effect-open or future/unknown falls back to the existing ordinary temp snapshot so
    ///   newly added rebinding forms cannot silently tunnel through alias publication
    private @NotNull DirectSlotAliasRoot requireDirectSlotAliasRoot(@NotNull ValueBuild receiverBuild) {
        var expression = (Expression) receiverBuild.valueAnchor();
        return switch (expression) {
            case SelfExpression selfExpression -> new DirectSlotAliasRoot(
                    selfExpression,
                    DirectSlotAliasRootKind.EXPLICIT_SELF
            );
            case IdentifierExpression identifierExpression -> {
                var binding = receiverBuild.bindingOrNull() != null
                        ? receiverBuild.bindingOrNull()
                        : requirePublishedBinding(identifierExpression);
                yield switch (binding.kind()) {
                    case LOCAL_VAR -> new DirectSlotAliasRoot(identifierExpression, DirectSlotAliasRootKind.LOCAL_VAR);
                    case PARAMETER -> new DirectSlotAliasRoot(identifierExpression, DirectSlotAliasRootKind.PARAMETER);
                    case CAPTURE -> throw new IllegalStateException(
                            "Direct-slot alias publication does not support CAPTURE binding before lambda/capture semantics are implemented"
                    );
                    case SELF -> throw new IllegalStateException(
                            "Direct-slot alias publication must use explicit SelfExpression instead of identifier binding kind SELF"
                    );
                    default -> throw new IllegalStateException(
                            "Direct-slot alias publication requires LOCAL_VAR/PARAMETER binding, but got "
                                    + binding.kind()
                    );
                };
            }
            default -> throw new IllegalStateException(
                    "Direct-slot alias publication requires IdentifierExpression or SelfExpression, but got "
                            + expression.getClass().getSimpleName()
            );
        };
    }

    private boolean shouldPublishDirectSlotAlias(
            @NotNull DirectSlotAliasRoot aliasRoot,
            @NotNull List<Expression> arguments
    ) {
        if (aliasRoot.kind() == DirectSlotAliasRootKind.EXPLICIT_SELF) {
            return true;
        }
        return classifyDirectSlotAliasArguments(arguments) == DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
    }

    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasArguments(
            @NotNull List<Expression> arguments
    ) {
        var safety = DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
        for (var argument : arguments) {
            safety = mergeDirectSlotAliasArgumentSafety(safety, classifyDirectSlotAliasArgument(argument));
            if (safety == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT) {
                return safety;
            }
        }
        return safety;
    }

    /// This classifier is intentionally conservative. Identifier-backed receiver roots stay aliased
    /// across argument evaluation only when the entire later subtree is already known to be
    /// no-rebinding for caller direct-slot storage. Current value-level calls are still treated as
    /// effect-open because future lambda/capture/callable surfaces could otherwise start rebinding the
    /// same root through an ordinary-looking `CallExpression` without touching alias publication code.
    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasArgument(@NotNull Expression expression) {
        return switch (expression) {
            case IdentifierExpression _, LiteralExpression _, SelfExpression _ ->
                    DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
            case UnaryExpression unaryExpression -> classifyDirectSlotAliasArgument(unaryExpression.operand());
            case BinaryExpression binaryExpression -> mergeDirectSlotAliasArgumentSafety(
                    classifyDirectSlotAliasArgument(binaryExpression.left()),
                    classifyDirectSlotAliasArgument(binaryExpression.right())
            );
            case CastExpression castExpression -> classifyDirectSlotAliasArgument(castExpression.value());
            case TypeTestExpression typeTestExpression -> classifyDirectSlotAliasArgument(typeTestExpression.value());
            case ConditionalExpression conditionalExpression -> mergeDirectSlotAliasArgumentSafety(
                    mergeDirectSlotAliasArgumentSafety(
                            classifyDirectSlotAliasArgument(conditionalExpression.condition()),
                            classifyDirectSlotAliasArgument(conditionalExpression.left())
                    ),
                    classifyDirectSlotAliasArgument(conditionalExpression.right())
            );
            case SubscriptExpression subscriptExpression -> mergeDirectSlotAliasArgumentSafety(
                    classifyDirectSlotAliasArgument(subscriptExpression.base()),
                    classifyDirectSlotAliasArguments(subscriptExpression.arguments())
            );
            case AttributeExpression attributeExpression ->
                    classifyDirectSlotAliasAttributeArgument(attributeExpression);
            case AssignmentExpression _, CallExpression _ -> DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT;
            default -> DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT;
        };
    }

    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasAttributeArgument(
            @NotNull AttributeExpression attributeExpression
    ) {
        var safety = classifyDirectSlotAliasArgument(attributeExpression.base());
        for (var step : attributeExpression.steps()) {
            safety = mergeDirectSlotAliasArgumentSafety(safety, classifyDirectSlotAliasAttributeStep(step));
            if (safety == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT) {
                return safety;
            }
        }
        return safety;
    }

    private @NotNull DirectSlotAliasArgumentSafety classifyDirectSlotAliasAttributeStep(
            @NotNull AttributeStep step
    ) {
        return switch (step) {
            case AttributePropertyStep _ -> DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
            case AttributeSubscriptStep attributeSubscriptStep ->
                    classifyDirectSlotAliasArguments(attributeSubscriptStep.arguments());
            default -> DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT;
        };
    }

    private @NotNull DirectSlotAliasArgumentSafety mergeDirectSlotAliasArgumentSafety(
            @NotNull DirectSlotAliasArgumentSafety left,
            @NotNull DirectSlotAliasArgumentSafety right
    ) {
        return left == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT
                || right == DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT
                ? DirectSlotAliasArgumentSafety.REQUIRES_SNAPSHOT
                : DirectSlotAliasArgumentSafety.SAFE_TO_ALIAS;
    }

    private @NotNull ValueBuild valueRootBuild(
            @NotNull BuildCursor cursor,
            @NotNull Node valueAnchor,
            @NotNull String resultValueId
    ) {
        return new ValueBuild(cursor, valueAnchor, resultValueId, valueRootRoutePayload(valueAnchor, resultValueId));
    }

    private @NotNull FrontendWritableRoutePayload valueRootRoutePayload(
            @NotNull Node valueAnchor,
            @NotNull String resultValueId
    ) {
        return new FrontendWritableRoutePayload(
                valueAnchor,
                new FrontendWritableRoutePayload.RootDescriptor(
                        FrontendWritableRoutePayload.RootKind.VALUE_ID,
                        valueAnchor,
                        resultValueId
                ),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT,
                        valueAnchor,
                        null,
                        List.of(),
                        null,
                        null
                ),
                List.of()
        );
    }

    private @NotNull FrontendWritableRoutePayload appendPropertyWritableRoute(
            @NotNull ValueBuild receiverBuild,
            @NotNull Node propertyAnchor,
            @NotNull String propertyName
    ) {
        var baseRoute = routePayloadOrValueRoot(receiverBuild);
        return new FrontendWritableRoutePayload(
                propertyAnchor,
                baseRoute.root(),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.PROPERTY,
                        propertyAnchor,
                        // Ordinary property reads still use `receiverBuild.resultValueId()` through the
                        // published `MemberLoadItem`. When the base is an unwrapped direct-slot root such
                        // as `self` or `box`, reverse commit must *not* freeze that transient read slot as
                        // the owner; otherwise `box.payloads.push_back(seed)` would try to write the
                        // property back into the snapshot temp instead of the real `box` slot.
                        useImplicitRootContainer(baseRoute) ? null : receiverBuild.resultValueId(),
                        List.of(),
                        StringUtil.requireNonBlank(propertyName, "propertyName"),
                        null
                ),
                appendPromotedLeaf(baseRoute)
        );
    }

    private @NotNull FrontendWritableRoutePayload appendSubscriptWritableRoute(
            @NotNull ValueBuild receiverBuild,
            @NotNull Node subscriptAnchor,
            @NotNull List<String> keyValueIds,
            @NotNull FrontendSubscriptAccessSupport.AccessKind accessKind
    ) {
        return appendSubscriptWritableRoute(
                receiverBuild,
                subscriptAnchor,
                keyValueIds,
                accessKind,
                subscriptAnchor instanceof AttributeSubscriptStep attributeSubscriptStep
                        ? attributeSubscriptStep.name()
                        : null
        );
    }

    /// `memberNameOrNull` is explicit because one anchor shape does not imply one route shape:
    /// ordinary attribute-subscripts (`receiver.member[key]`) keep the member name for the named
    /// route, while a type-meta head subscript (`Worker.values[i]`) re-anchors the static container
    /// member on the same step yet must lower the subscript itself as a plain base[key] route.
    private @NotNull FrontendWritableRoutePayload appendSubscriptWritableRoute(
            @NotNull ValueBuild receiverBuild,
            @NotNull Node subscriptAnchor,
            @NotNull List<String> keyValueIds,
            @NotNull FrontendSubscriptAccessSupport.AccessKind accessKind,
            @Nullable String memberNameOrNull
    ) {
        var baseRoute = routePayloadOrValueRoot(receiverBuild);
        requireSingleWritableRouteKey(subscriptAnchor, keyValueIds);
        return new FrontendWritableRoutePayload(
                subscriptAnchor,
                baseRoute.root(),
                new FrontendWritableRoutePayload.LeafDescriptor(
                        FrontendWritableRoutePayload.LeafKind.SUBSCRIPT,
                        subscriptAnchor,
                        useImplicitRootContainer(baseRoute) ? null : receiverBuild.resultValueId(),
                        List.copyOf(keyValueIds),
                        memberNameOrNull,
                        Objects.requireNonNull(accessKind, "accessKind must not be null")
                ),
                appendPromotedLeaf(baseRoute)
        );
    }

    private boolean useImplicitRootContainer(@NotNull FrontendWritableRoutePayload routePayload) {
        return routePayload.reverseCommitSteps().isEmpty()
                && routePayload.leaf().kind() == FrontendWritableRoutePayload.LeafKind.DIRECT_SLOT
                && routePayload.root().kind() != FrontendWritableRoutePayload.RootKind.VALUE_ID;
    }

    private @NotNull List<FrontendWritableRoutePayload.StepDescriptor> appendPromotedLeaf(
            @NotNull FrontendWritableRoutePayload routePayload
    ) {
        var steps = new ArrayList<>(routePayload.reverseCommitSteps());
        var promoted = promoteLeafToCommitStep(routePayload.leaf());
        if (promoted != null) {
            steps.add(promoted);
        }
        return List.copyOf(steps);
    }

    /// Reverse-commit steps for a method call whose receiver is a bare static property. The static
    /// property leaf is already the terminal storage boundary (a static route has no runtime
    /// container slot), so it must never be promoted into a non-terminal commit step whenever the
    /// promoted step could only ever produce a no-op write-back: either the call is provably
    /// const (`mayMutateReceiver == false`, so body lowering skips the reverse commit entirely),
    /// or the receiver is a reference carrier (`Array`/`Dictionary`/objects/primitives) mutated
    /// in place through the loaded value. Mutating calls on value-semantic or unknown (`Variant`)
    /// carriers keep the promotion so the static-terminal contract fails fast instead of
    /// silently dropping a required write-back.
    private @NotNull List<FrontendWritableRoutePayload.StepDescriptor> appendCallReceiverCommitSteps(
            @NotNull FrontendWritableRoutePayload routePayload,
            @NotNull FrontendResolvedCall publishedCall
    ) {
        var receiverType = publishedCall.receiverType();
        var staticBarePropertyReceiver =
                routePayload.root().kind() == FrontendWritableRoutePayload.RootKind.STATIC_CONTEXT
                        && routePayload.leaf().kind() == FrontendWritableRoutePayload.LeafKind.PROPERTY
                        && routePayload.leaf().containerValueIdOrNull() == null;
        if (staticBarePropertyReceiver
                && (!FrontendCallMutabilitySupport.mayMutateReceiver(publishedCall)
                || (receiverType != null
                && !FrontendWritableTypeWritebackSupport.requiresReverseCommitForCarrierType(receiverType)))) {
            return routePayload.reverseCommitSteps();
        }
        return appendPromotedLeaf(routePayload);
    }

    private @Nullable FrontendWritableRoutePayload.StepDescriptor promoteLeafToCommitStep(
            @NotNull FrontendWritableRoutePayload.LeafDescriptor leaf
    ) {
        return switch (leaf.kind()) {
            case DIRECT_SLOT -> null;
            case PROPERTY -> new FrontendWritableRoutePayload.StepDescriptor(
                    FrontendWritableRoutePayload.StepKind.PROPERTY,
                    leaf.anchor(),
                    leaf.containerValueIdOrNull(),
                    List.of(),
                    leaf.memberNameOrNull(),
                    null
            );
            case SUBSCRIPT -> new FrontendWritableRoutePayload.StepDescriptor(
                    FrontendWritableRoutePayload.StepKind.SUBSCRIPT,
                    leaf.anchor(),
                    leaf.containerValueIdOrNull(),
                    leaf.operandValueIds(),
                    leaf.memberNameOrNull(),
                    leaf.subscriptAccessKindOrNull()
            );
        };
    }

    /// Freezes the writable-route subscript access family using the key type that body lowering will
    /// eventually materialize.
    ///
    /// Usage:
    /// - call this while publishing a writable subscript route into `FrontendWritableRoutePayload`
    /// - pass the receiver type and the original key expression type known at CFG build time
    ///
    /// Examples:
    /// - `Array[T]` with a `Variant` key freezes as `INDEXED`, because body lowering will unpack the
    ///   key to the container's `int` index type
    /// - `Dictionary[float, V]` with an `int` key freezes as `KEYED`, because body lowering will cast
    ///   the key to the dictionary's `float` key type
    /// - attribute-subscript steps use a `Variant` effective receiver because the named base is
    ///   materialized from runtime `receiver.member` before the subscript is applied — except
    ///   static container properties, whose named base is loaded from shared static storage, and
    ///   resolved non-static GDCC instance container properties, whose named base is loaded through
    ///   `LoadPropertyInsn`; both keep the published container type (body lowering consumes the same
    ///   provenance through `SubscriptLeaf.containerSourceType` / `staticOwnerNameOrNull` /
    ///   `typedInstanceContainer`)
    private @NotNull FrontendSubscriptAccessSupport.AccessKind determineWritableSubscriptAccessKind(
            @NotNull Node subscriptAnchor,
            @NotNull GdType receiverType,
            @NotNull GdType keyType
    ) {
        var effectiveReceiverType = receiverType;
        if (subscriptAnchor instanceof AttributeSubscriptStep) {
            var containerMember = requireAnalysisData().resolvedMembers().get(subscriptAnchor);
            var staticContainer = containerMember != null
                    && containerMember.status() == FrontendMemberResolutionStatus.RESOLVED
                    && containerMember.bindingKind() == FrontendBindingKind.PROPERTY
                    && containerMember.declarationSite() instanceof PropertyDef propertyDef
                    && propertyDef.isStatic();
            var typedContainerType = containerMember != null
                    && (staticContainer
                    || FrontendSubscriptAccessSupport.isResolvedTypedInstanceContainerMember(containerMember))
                    ? containerMember.resultType()
                    : null;
            effectiveReceiverType = typedContainerType != null ? typedContainerType : GdVariantType.VARIANT;
        }
        var materializedKeyType = effectiveReceiverType instanceof GdContainerType containerType
                ? containerType.getKeyType()
                : keyType;
        return FrontendSubscriptAccessSupport.determineAccessKind(effectiveReceiverType, materializedKeyType);
    }

    private void requireSingleWritableRouteKey(@NotNull Node anchor, @NotNull List<String> keyValueIds) {
        if (keyValueIds.size() != 1) {
            throw new IllegalStateException(
                    "Writable-route publication currently supports exactly one subscript key for "
                            + anchor.getClass().getSimpleName()
            );
        }
    }

    private void publishMergedBooleanWriteSequence(
            @NotNull OpenSequence sequence,
            @NotNull BinaryExpression mergeAnchor,
            boolean constantValue,
            @NotNull String mergedResultValueId,
            @NotNull String nextId
    ) {
        var constantValueId = nextValueId();
        sequence.items().add(new BoolConstantItem(mergeAnchor, constantValue, constantValueId));
        // The outward-facing short-circuit result behaves like a merge slot written on mutually
        // exclusive paths, not like one unique SSA expression definition.
        sequence.items().add(new MergeValueItem(mergeAnchor, constantValueId, mergedResultValueId));
        publishSequenceNode(sequence.id(), sequence.items(), nextId);
    }

    private @NotNull String finalizeBlockState(@NotNull BlockState state, @NotNull String continuationId) {
        if (state.reachable()) {
            closeCurrentSequence(state, continuationId);
            return state.entryIdOrNull() == null ? continuationId : state.entryIdOrNull();
        }
        var entryId = state.entryIdOrNull();
        if (entryId == null) {
            throw new IllegalStateException("Structured block terminated before publishing an entry node");
        }
        return entryId;
    }

    private void closeCurrentSequence(@NotNull BlockState state, @NotNull String nextId) {
        var currentSequence = state.currentSequenceOrNull();
        if (currentSequence == null) {
            return;
        }
        if (!currentSequence.items().isEmpty()) {
            publishSequenceNode(currentSequence.id(), currentSequence.items(), nextId);
        } else if (Objects.equals(state.entryIdOrNull(), currentSequence.id())) {
            state.setEntryId(nextId);
        } else {
            publishSequenceNode(currentSequence.id(), List.of(), nextId);
        }
        state.setCurrentSequence(null);
    }

    private @NotNull OpenSequence requireCurrentSequence(@NotNull BlockState state) {
        var currentSequence = state.currentSequenceOrNull();
        if (currentSequence != null) {
            return currentSequence;
        }
        var created = new OpenSequence(nextSequenceId());
        state.setEntryIdIfMissing(created.id());
        state.setCurrentSequence(created);
        return created;
    }

    private @NotNull LinkedHashMap<String, FrontendCfgGraph.NodeDef> orderNodes(@NotNull String entryId) {
        var ordered = new LinkedHashMap<String, FrontendCfgGraph.NodeDef>();
        var visited = new LinkedHashSet<String>();
        visitNode(entryId, visited, ordered);
        for (var entry : requireNodes().entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return ordered;
    }

    private void visitNode(
            @NotNull String nodeId,
            @NotNull LinkedHashSet<String> visited,
            @NotNull LinkedHashMap<String, FrontendCfgGraph.NodeDef> ordered
    ) {
        if (!visited.add(nodeId)) {
            return;
        }
        var node = requireNodes().get(nodeId);
        if (node == null) {
            throw new IllegalStateException("Frontend CFG node has not been published: " + nodeId);
        }
        ordered.put(nodeId, node);
        switch (node) {
            case FrontendCfgGraph.SequenceNode(_, _, var nextId) -> visitNode(nextId, visited, ordered);
            case FrontendCfgGraph.BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                visitNode(trueTargetId, visited, ordered);
                visitNode(falseTargetId, visited, ordered);
            }
            case FrontendCfgGraph.StopNode _ -> {
            }
        }
    }

    private boolean isNodeReferenced(@NotNull String nodeId) {
        for (var node : requireNodes().values()) {
            switch (node) {
                case FrontendCfgGraph.SequenceNode(_, _, var nextId) -> {
                    if (nextId.equals(nodeId)) {
                        return true;
                    }
                }
                case FrontendCfgGraph.BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                    if (trueTargetId.equals(nodeId) || falseTargetId.equals(nodeId)) {
                        return true;
                    }
                }
                case FrontendCfgGraph.StopNode _ -> {
                }
            }
        }
        return false;
    }

    private @NotNull String publishSequenceNode(@NotNull List<SequenceItem> items, @NotNull String nextId) {
        var sequenceId = nextSequenceId();
        publishSequenceNode(sequenceId, items, nextId);
        return sequenceId;
    }

    private void publishSequenceNode(
            @NotNull String sequenceId,
            @NotNull List<SequenceItem> items,
            @NotNull String nextId
    ) {
        requireNodes().put(sequenceId, new FrontendCfgGraph.SequenceNode(sequenceId, items, nextId));
    }

    private @NotNull String publishStopNode(
            @NotNull FrontendCfgGraph.StopKind kind,
            @Nullable String returnValueIdOrNull
    ) {
        var stopId = nextStopId();
        requireNodes().put(stopId, new FrontendCfgGraph.StopNode(stopId, kind, returnValueIdOrNull));
        return stopId;
    }

    private @NotNull FrontendAstSideTable<FrontendCfgRegion> requireRegions() {
        if (regions == null) {
            throw new IllegalStateException("Frontend CFG regions have not been initialized");
        }
        return regions;
    }

    private @NotNull FrontendAstSideTable<FrontendForSourceIteratorSlot> requireForSourceIteratorSlots() {
        if (forSourceIteratorSlots == null) {
            throw new IllegalStateException("Frontend for-in source iterator slots have not been initialized");
        }
        return forSourceIteratorSlots;
    }

    private @NotNull FrontendAstSideTable<FrontendForIteratorStateSlot> requireForIteratorStateSlots() {
        if (forIteratorStateSlots == null) {
            throw new IllegalStateException("Frontend for-in iterator state slots have not been initialized");
        }
        return forIteratorStateSlots;
    }

    private @NotNull FrontendAstSideTable<FrontendMatchBindSlot> requireMatchBindSlots() {
        if (matchBindSlots == null) {
            throw new IllegalStateException("Frontend match bind slots have not been initialized");
        }
        return matchBindSlots;
    }

    private @NotNull Set<PatternBindingExpression> requireFoldedMatchBindDeclarations() {
        if (foldedMatchBindDeclarations == null) {
            throw new IllegalStateException("Frontend folded match bind declarations have not been initialized");
        }
        return foldedMatchBindDeclarations;
    }

    private @NotNull LinkedHashMap<String, FrontendCfgGraph.NodeDef> requireNodes() {
        if (nodes == null) {
            throw new IllegalStateException("Frontend CFG nodes have not been initialized");
        }
        return nodes;
    }

    private @NotNull FrontendAnalysisData requireAnalysisData() {
        if (analysisData == null) {
            throw new IllegalStateException("Frontend analysis data has not been initialized");
        }
        return analysisData;
    }

    private @NotNull LoopFrame requireLoopFrame() {
        var loopFrame = loopStack.peek();
        if (loopFrame == null) {
            throw new IllegalStateException("Loop control statement requires an active loop frame");
        }
        return loopFrame;
    }

    /// Sequence ids are lexical-order scoped to one build so tests can assert exact graph shape
    /// without leaking counters across functions.
    private @NotNull String nextSequenceId() {
        return "seq_" + nextSequenceIndex++;
    }

    private @NotNull String nextBranchId() {
        return "branch_" + nextBranchIndex++;
    }

    /// Stop ids share the same per-function deterministic contract as sequence ids.
    private @NotNull String nextStopId() {
        return "stop_" + nextStopIndex++;
    }

    /// Value ids name frontend-local temporary results referenced by later CFG nodes.
    private @NotNull String nextValueId() {
        return "v" + nextValueIndex++;
    }

    /// Variable initializer ids keep the declaration name as a stable prefix while still sharing the
    /// same monotonic counter as other frontend-local values.
    private @NotNull String nextVariableValueId(@NotNull String variableName) {
        return FrontendCfgGraph.validateNodeId(variableName, "variableName") + "_" + nextValueIndex++;
    }

    private @NotNull String chooseResultValueId(@Nullable String preferredResultValueId) {
        return preferredResultValueId == null ? nextValueId() : preferredResultValueId;
    }

    private boolean isDiscardedResolvedVoidCallExpression(@NotNull Expression expression) {
        var anchor = discardedResolvedVoidCallAnchorOrNull(expression);
        if (anchor == null) {
            return false;
        }
        // A resolved-void coroutine call still needs a published result value: the backend
        // coroutine ABI requires the `compiler::GdccCoroState` result slot (fire-and-forget then
        // detaches it through an INTERNAL destruct), so statement-position coroutine calls stay on
        // the ordinary value-building path instead of the no-result discarded-call path.
        return !requireAnalysisData().isPublishedCoroutineCall(anchor);
    }

    private @Nullable Node discardedResolvedVoidCallAnchorOrNull(@NotNull Expression expression) {
        return switch (expression) {
            case CallExpression callExpression -> isResolvedVoidCallAnchor(callExpression) ? callExpression : null;
            case AttributeExpression attributeExpression -> attributeExpression.steps().isEmpty()
                    || !(attributeExpression.steps().getLast() instanceof AttributeCallStep attributeCallStep)
                    || !isResolvedVoidCallAnchor(attributeCallStep)
                    ? null
                    : attributeCallStep;
            default -> null;
        };
    }

    private boolean isResolvedVoidCallAnchor(@NotNull Node callAnchor) {
        var publishedCall = requireAnalysisData().resolvedCalls().get(callAnchor);
        return publishedCall != null
                && publishedCall.status() == FrontendCallResolutionStatus.RESOLVED
                && publishedCall.returnType() instanceof GdVoidType;
    }

    private boolean isTypeMetaHeadAttributeExpression(@NotNull AttributeExpression attributeExpression) {
        return attributeExpression.base() instanceof IdentifierExpression identifierExpression
                && requireAnalysisData().symbolBindings().get(identifierExpression) instanceof FrontendBinding binding
                && binding.kind() == FrontendBindingKind.TYPE_META;
    }

    private static boolean isLogicalNotExpression(@NotNull UnaryExpression unaryExpression) {
        return tryResolveUnaryOperator(unaryExpression.operator()) == GodotOperator.NOT;
    }

    private static boolean isShortCircuitBinaryExpression(@NotNull BinaryExpression binaryExpression) {
        var operator = tryResolveBinaryOperator(binaryExpression.operator());
        return operator == GodotOperator.AND || operator == GodotOperator.OR;
    }

    private static boolean isCompoundAssignmentOperator(@NotNull AssignmentExpression assignmentExpression) {
        return !Objects.requireNonNull(assignmentExpression, "assignmentExpression must not be null")
                .operator()
                .equals("=");
    }

    private static @NotNull String requireCompoundBinaryOperatorLexeme(
            @NotNull AssignmentExpression assignmentExpression
    ) {
        var operatorText = Objects.requireNonNull(assignmentExpression, "assignmentExpression must not be null")
                .operator();
        var binaryOperatorLexeme = switch (operatorText) {
            case "+=" -> "+";
            case "-=" -> "-";
            case "*=" -> "*";
            case "/=" -> "/";
            case "%=" -> "%";
            case "**=" -> "**";
            case ">>=" -> ">>";
            case "<<=" -> "<<";
            case "&=" -> "&";
            case "^=" -> "^";
            case "|=" -> "|";
            default -> null;
        };
        if (binaryOperatorLexeme != null) {
            return binaryOperatorLexeme;
        }
        throw new IllegalStateException(
                "Compound assignment operator '"
                        + operatorText
                        + "' is not recognized by the current frontend CFG read-modify-write contract"
        );
    }

    private void requireLoweringReadyCompoundMemberRead(
            @NotNull AttributePropertyStep attributePropertyStep
    ) {
        var contractDetail = "compound attribute-property current-value read";
        var publishedMember = requireAnalysisData().resolvedMembers().get(attributePropertyStep);
        if (publishedMember == null) {
            throw new IllegalStateException(
                    "compound-assignment publication contract is missing a lowering-ready member fact for "
                            + "AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "' during "
                            + contractDetail
            );
        }
        if (publishedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.status() != FrontendMemberResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "compound-assignment member read for AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "' is not lowering-ready during "
                            + contractDetail
                            + ": "
                            + publishedMember.status()
            );
        }
        checkDynamicMemberPublicationContract(
                attributePropertyStep,
                publishedMember,
                "compound-assignment member read during " + contractDetail
        );
    }

    private static @NotNull GodotOperator requireShortCircuitBinaryOperator(
            @NotNull BinaryExpression binaryExpression
    ) {
        var operator = tryResolveBinaryOperator(binaryExpression.operator());
        if (operator == GodotOperator.AND || operator == GodotOperator.OR) {
            return operator;
        }
        throw unsupportedShortCircuitBinary(binaryExpression);
    }

    private static @Nullable GodotOperator tryResolveUnaryOperator(@NotNull String operatorText) {
        try {
            return GodotOperator.fromSourceLexeme(
                    Objects.requireNonNull(operatorText, "operatorText must not be null"),
                    GodotOperator.OperatorArity.UNARY
            );
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static @Nullable GodotOperator tryResolveBinaryOperator(@NotNull String operatorText) {
        try {
            return GodotOperator.fromSourceLexeme(
                    Objects.requireNonNull(operatorText, "operatorText must not be null"),
                    GodotOperator.OperatorArity.BINARY
            );
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static @NotNull String requireFrozenTargetOperandValue(
            @NotNull List<String> frozenTargetOperandValueIds,
            @NotNull Expression targetExpression,
            @NotNull String operandRole
    ) {
        if (!frozenTargetOperandValueIds.isEmpty()) {
            return frozenTargetOperandValueIds.getFirst();
        }
        throw new IllegalStateException(
                "Compound assignment target "
                        + targetExpression.getClass().getSimpleName()
                        + " is missing frozen "
                        + operandRole
                        + " operands in the frontend CFG read-modify-write contract"
        );
    }

    private static @NotNull List<String> requireFrozenTargetTrailingOperands(
            @NotNull List<String> frozenTargetOperandValueIds,
            @NotNull Expression targetExpression
    ) {
        if (frozenTargetOperandValueIds.size() >= 2) {
            return List.copyOf(frozenTargetOperandValueIds.subList(1, frozenTargetOperandValueIds.size()));
        }
        throw new IllegalStateException(
                "Compound assignment target "
                        + targetExpression.getClass().getSimpleName()
                        + " is missing frozen index operands in the frontend CFG read-modify-write contract"
        );
    }

    private @NotNull FrontendBinding requirePublishedBinding(@NotNull Node useSite) {
        var binding = requireAnalysisData().symbolBindings().get(Objects.requireNonNull(useSite, "useSite must not be null"));
        if (binding == null) {
            throw new IllegalStateException("Missing published symbol binding for " + useSite.getClass().getSimpleName());
        }
        return binding;
    }

    private boolean isStaticPropertyBinding(@NotNull FrontendBinding binding) {
        return binding.kind() == FrontendBindingKind.PROPERTY
                && binding.declarationSite() instanceof PropertyDef propertyDef
                && propertyDef.isStatic();
    }

    /// Writable-route publication must resolve access families and payload leaf types from already
    /// published semantic facts instead of re-running chain reduction.
    ///
    /// Some assignment-target prefixes are still analyzed through the shared semantic surface where a
    /// trusted binding-backed leaf such as `items` may expose slot/property metadata without also
    /// publishing an `expressionTypes()` entry for that identifier use site. Writable-route freezing is
    /// still allowed to consume those binding-backed types because the route stays within the same
    /// compile-checked owner chain and does not reopen general expression inference.
    private @NotNull GdType requireWritableRouteAnchorType(@NotNull Node anchor) {
        var publishedType = requireAnalysisData().expressionTypes().get(Objects.requireNonNull(anchor, "anchor must not be null"));
        if (publishedType != null) {
            if (publishedType.publishedType() != null) {
                return publishedType.publishedType();
            }
            throw new IllegalStateException(
                    "Writable-route publication requires a lowering-ready type for "
                            + anchor.getClass().getSimpleName()
                            + ", but got "
                            + publishedType.status()
            );
        }
        var binding = requireAnalysisData().symbolBindings().get(anchor);
        if (binding != null && binding.declarationSite() instanceof Node declarationNode) {
            var slotType = requireAnalysisData().slotTypes().get(declarationNode);
            if (slotType != null) {
                return slotType;
            }
        }
        if (binding != null
                && binding.kind() == FrontendBindingKind.PROPERTY
                && binding.declarationSite() instanceof PropertyDef propertyDef) {
            return propertyDef.getType();
        }
        throw new IllegalStateException(
                "Writable-route publication is missing a published expression type for "
                        + anchor.getClass().getSimpleName()
        );
    }

    /// Compile-ready lowering must only see expressions whose type facts already stabilized to a
    /// lowering-safe state.
    ///
    /// `BLOCKED`, `DEFERRED`, `FAILED`, and `UNSUPPORTED` all indicate the compile gate should have
    /// stopped the pipeline earlier. The builder therefore treats them as protocol violations instead
    /// of trying to recover locally.
    private boolean hasLoweringReadyExpressionType(@NotNull Expression expression) {
        var publishedType = requireAnalysisData().expressionTypes().get(expression);
        return publishedType != null
                && (publishedType.status() == FrontendExpressionTypeStatus.RESOLVED
                || publishedType.status() == FrontendExpressionTypeStatus.DYNAMIC);
    }

    private void requireLoweringReadyExpressionType(@NotNull Expression expression) {
        var publishedType = requireAnalysisData().expressionTypes().get(expression);
        if (publishedType == null) {
            throw new IllegalStateException(
                    "expressionTypes() is missing a lowering-ready fact for "
                            + expression.getClass().getSimpleName()
                            + " at "
                            + expression.range()
            );
        }
        if (publishedType.status() != FrontendExpressionTypeStatus.RESOLVED
                && publishedType.status() != FrontendExpressionTypeStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "Expression "
                            + expression.getClass().getSimpleName()
                            + " is not lowering-ready: "
                            + publishedType.status()
            );
        }
    }

    private @NotNull FrontendResolvedCall requireLoweringReadyCall(@NotNull Node callAnchor) {
        var publishedCall = requireAnalysisData().resolvedCalls().get(callAnchor);
        if (publishedCall == null) {
            throw new IllegalStateException(
                    "resolvedCalls() is missing a lowering-ready fact for " + callAnchor.getClass().getSimpleName()
            );
        }
        if (publishedCall.status() != FrontendCallResolutionStatus.RESOLVED
                && publishedCall.status() != FrontendCallResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "Call anchor "
                            + callAnchor.getClass().getSimpleName()
                            + " is not lowering-ready: "
                            + publishedCall.status()
            );
        }
        return publishedCall;
    }

    private @NotNull FrontendResolvedCall requireDiscardedResolvedVoidCall(@NotNull Node callAnchor) {
        var publishedCall = requireLoweringReadyCall(callAnchor);
        if (publishedCall.status() != FrontendCallResolutionStatus.RESOLVED
                || !(publishedCall.returnType() instanceof GdVoidType)) {
            throw new IllegalStateException(
                    "Discarded call path requires a RESOLVED void call anchor, but got "
                            + publishedCall.status()
                            + " / "
                            + (publishedCall.returnType() == null
                            ? "null"
                            : publishedCall.returnType().getTypeName())
            );
        }
        return publishedCall;
    }

    /// Value-required call paths must never quietly reuse the statement-position resolved-void escape
    /// hatch. If semantic/type-check regressions leak a `void` call here, lowering fails fast instead
    /// of publishing a fake CFG value that later consumers would treat like a real slot-backed result.
    private void checkValueProducingCall(
            @NotNull FrontendResolvedCall publishedCall,
            @NotNull Node callAnchor,
            @NotNull String callSurface
    ) {
        if (publishedCall.status() == FrontendCallResolutionStatus.RESOLVED
                && publishedCall.returnType() instanceof GdVoidType
                && !requireAnalysisData().isPublishedCoroutineCall(callAnchor)) {
            throw new IllegalStateException(
                    "Value-required "
                            + callSurface
                            + " must not lower RESOLVED void call '"
                            + publishedCall.callableName()
                            + "' at "
                            + callAnchor.range()
                            + "; statement-position discarded void calls must use the dedicated no-result path, so this indicates a type-check / compile-gate regression"
            );
        }
    }

    private @NotNull FrontendResolvedMember requireLoweringReadyMember(
            @NotNull AttributePropertyStep attributePropertyStep
    ) {
        var publishedMember = requireAnalysisData().resolvedMembers().get(attributePropertyStep);
        if (publishedMember == null) {
            throw new IllegalStateException(
                    "resolvedMembers() is missing a lowering-ready fact for AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "'"
            );
        }
        if (publishedMember.status() != FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.status() != FrontendMemberResolutionStatus.DYNAMIC) {
            throw new IllegalStateException(
                    "AttributePropertyStep '"
                            + attributePropertyStep.name()
                            + "' is not lowering-ready: "
                            + publishedMember.status()
            );
        }
        checkDynamicMemberPublicationContract(attributePropertyStep, publishedMember, "CFG member lowering");
        return publishedMember;
    }

    /// Only RESOLVED SIGNAL members become `SignalLoadItem`. DYNAMIC stays on the ordinary member
    /// route because the published fact is already runtime-open, not a known signal constructor.
    private static boolean isResolvedSignalMember(@NotNull FrontendResolvedMember publishedMember) {
        return publishedMember.status() == FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.bindingKind() == FrontendBindingKind.SIGNAL;
    }

    /// Object/self instance METHOD members become `CallableLoadItem`.
    private static boolean isResolvedObjectMethodReference(@NotNull FrontendResolvedMember publishedMember) {
        return publishedMember.status() == FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.bindingKind() == FrontendBindingKind.METHOD
                && publishedMember.receiverKind() == FrontendReceiverKind.INSTANCE
                && publishedMember.ownerKind() != ScopeOwnerKind.BUILTIN;
    }

    /// Non-Dictionary builtin instance METHOD members reuse `CallableLoadItem`.
    /// `dict.clear` is a Dictionary key in Godot and must not become a method-reference.
    private static boolean isResolvedBuiltinInstanceMethodReference(@NotNull FrontendResolvedMember publishedMember) {
        return publishedMember.status() == FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.bindingKind() == FrontendBindingKind.METHOD
                && publishedMember.receiverKind() == FrontendReceiverKind.INSTANCE
                && publishedMember.ownerKind() == ScopeOwnerKind.BUILTIN
                && !(publishedMember.receiverType() instanceof GdDictionaryType);
    }

    private static boolean isResolvedInstanceMethodReference(@NotNull FrontendResolvedMember publishedMember) {
        return isResolvedObjectMethodReference(publishedMember)
                || isResolvedBuiltinInstanceMethodReference(publishedMember);
    }

    /// GDCC/engine qualified static method-references become `StandaloneCallableLoadItem`.
    private static boolean isResolvedStandaloneStaticMethodReference(@NotNull FrontendResolvedMember publishedMember) {
        return publishedMember.status() == FrontendMemberResolutionStatus.RESOLVED
                && publishedMember.bindingKind() == FrontendBindingKind.STATIC_METHOD
                && publishedMember.ownerKind() != ScopeOwnerKind.BUILTIN;
    }

    /// Residual RESOLVED METHOD/STATIC_METHOD facts stay off `MemberLoadItem`.
    private static boolean isResolvedUnsupportedMethodReference(@NotNull FrontendResolvedMember publishedMember) {
        return publishedMember.status() == FrontendMemberResolutionStatus.RESOLVED
                && (publishedMember.bindingKind() == FrontendBindingKind.METHOD
                || publishedMember.bindingKind() == FrontendBindingKind.STATIC_METHOD)
                && !isResolvedInstanceMethodReference(publishedMember)
                && !isResolvedStandaloneStaticMethodReference(publishedMember);
    }

    private static @NotNull String requireStandaloneOwnerName(@NotNull FrontendResolvedMember publishedMember) {
        if (publishedMember.receiverType() instanceof GdObjectType(var className)) {
            return className;
        }
        throw new IllegalStateException(
                "standalone static method-reference '" + publishedMember.memberName()
                        + "' is missing an object owner type"
        );
    }

    /// Dynamic members are runtime-open instance routes. A TYPE_META dynamic fact means the static
    /// member resolver published an impossible surface, so CFG must stop before creating fake values.
    private static void checkDynamicMemberPublicationContract(
            @NotNull AttributePropertyStep attributePropertyStep,
            @NotNull FrontendResolvedMember publishedMember,
            @NotNull String context
    ) {
        if (publishedMember.status() != FrontendMemberResolutionStatus.DYNAMIC
                || publishedMember.receiverKind() != FrontendReceiverKind.TYPE_META) {
            return;
        }
        throw new IllegalStateException(
                "Frontend publication contract drift: DYNAMIC member AttributePropertyStep '"
                        + attributePropertyStep.name()
                        + "' cannot use TYPE_META receiver route during "
                        + StringUtil.requireNonBlank(context, "context")
                        + "; static/type-meta members must resolve before CFG lowering"
        );
    }

    private static @NotNull IllegalStateException unsupportedReachableStatement(@NotNull Statement statement) {
        return new IllegalStateException(
                "Frontend CFG builder reached an unsupported reachable statement: "
                        + statement.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedReachableExpression(@NotNull Expression expression) {
        return new IllegalStateException(
                "Frontend CFG builder reached an unsupported lowering-ready expression: "
                        + expression.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedReachableAssignmentTarget(@NotNull Expression targetExpression) {
        return new IllegalStateException(
                "Frontend CFG builder reached an unsupported assignment target expression: "
                        + targetExpression.getClass().getSimpleName()
        );
    }

    private static @NotNull IllegalStateException unsupportedShortCircuitBinary(
            @NotNull BinaryExpression binaryExpression
    ) {
        return new IllegalStateException(
                "Binary operator '"
                        + binaryExpression.operator()
                        + "' must use the dedicated frontend CFG short-circuit path"
        );
    }

    private static @NotNull FrontendAstSideTable<FrontendCfgRegion> copyRegions(
            @NotNull FrontendAstSideTable<FrontendCfgRegion> regions
    ) {
        var copied = new FrontendAstSideTable<FrontendCfgRegion>();
        copied.putAll(regions);
        return copied;
    }

    private static @NotNull FrontendAstSideTable<FrontendForSourceIteratorSlot> copyForSourceIteratorSlots(
            @NotNull FrontendAstSideTable<FrontendForSourceIteratorSlot> slots
    ) {
        var copied = new FrontendAstSideTable<FrontendForSourceIteratorSlot>();
        copied.putAll(slots);
        return copied;
    }

    private static @NotNull FrontendAstSideTable<FrontendForIteratorStateSlot> copyForIteratorStateSlots(
            @NotNull FrontendAstSideTable<FrontendForIteratorStateSlot> slots
    ) {
        var copied = new FrontendAstSideTable<FrontendForIteratorStateSlot>();
        copied.putAll(slots);
        return copied;
    }

    private static @NotNull FrontendAstSideTable<FrontendMatchBindSlot> copyMatchBindSlots(
            @NotNull FrontendAstSideTable<FrontendMatchBindSlot> slots
    ) {
        var copied = new FrontendAstSideTable<FrontendMatchBindSlot>();
        copied.putAll(slots);
        return copied;
    }

    private static @NotNull Set<PatternBindingExpression> copyFoldedMatchBindDeclarations(
            @NotNull Set<PatternBindingExpression> declarations
    ) {
        Set<PatternBindingExpression> copied = Collections.newSetFromMap(new IdentityHashMap<>());
        copied.addAll(declarations);
        return copied;
    }

    /// Cross-table validation for the match build artifact.
    ///
    /// Bind slots are keyed by `PatternBindingExpression` and must not leak into the ordinary
    /// `LocalDeclarationItem` surface. Every published `FrontendMatchRegion` must have a matching
    /// section-count. Top-level bind items live at the section body entry; nested destructuring
    /// bind items live inside the pattern test fragment that fetches their element.
    private static void validateMatchArtifacts(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAstSideTable<FrontendCfgRegion> regions,
            @NotNull FrontendAstSideTable<FrontendMatchBindSlot> bindSlots,
            @NotNull Set<PatternBindingExpression> foldedMatchBindDeclarations
    ) {
        var bindItems = collectMatchBindItems(graph);
        for (var entry : regions.entrySet()) {
            if (!(entry.getKey() instanceof MatchStatement _) || !(entry.getValue() instanceof FrontendMatchRegion(
                    var headerEntryId, var sections, var mergeId
            ))) {
                continue;
            }
            if (sections.isEmpty()) {
                throw new IllegalStateException("match region must publish at least one section");
            }
            graph.requireNode(headerEntryId);
            graph.requireNode(mergeId);
            if (graph.requireNode(mergeId) instanceof FrontendCfgGraph.StopNode stopNode
                    && stopNode.kind() == FrontendCfgGraph.StopKind.TERMINAL_MERGE) {
                for (var node : graph.nodes().values()) {
                    switch (node) {
                        case FrontendCfgGraph.SequenceNode(_, _, var nextId) -> {
                            if (mergeId.equals(nextId)) {
                                throw new IllegalStateException(
                                        "TERMINAL_MERGE must not be a sequence nextId target"
                                );
                            }
                        }
                        case FrontendCfgGraph.BranchNode(_, _, _, var trueTargetId, var falseTargetId) -> {
                            if (mergeId.equals(trueTargetId) || mergeId.equals(falseTargetId)) {
                                throw new IllegalStateException(
                                        "TERMINAL_MERGE must not be a branch target"
                                );
                            }
                        }
                        case FrontendCfgGraph.StopNode _ -> {
                        }
                    }
                }
            }
            for (var sectionAnchors : sections) {
                graph.requireNode(sectionAnchors.testEntryId());
                graph.requireNode(sectionAnchors.bodyEntryId());
            }
        }
        for (var slotEntry : bindSlots.entrySet()) {
            if (!(slotEntry.getKey() instanceof PatternBindingExpression declaration)) {
                throw new IllegalStateException("match bind slot key must be a PatternBindingExpression");
            }
            var slot = slotEntry.getValue();
            if (slot.declaration() != declaration) {
                throw new IllegalStateException("match bind slot key/declaration identity diverged");
            }
            var bindItem = bindItems.get(declaration);
            if (bindItem == null) {
                // A container pattern whose subject family can never match folds to the miss edge
                // without a test fragment, so its nested binds keep their pre-allocated slots (the
                // unreachable body still reads them) but commit no item.
                if (foldedMatchBindDeclarations.contains(declaration)) {
                    continue;
                }
                throw new IllegalStateException(
                        "Missing MatchBindItem for pattern bind '" + slot.bindSlotId() + "'"
                );
            }
            if (!bindItem.bindSlotId().equals(slot.bindSlotId())
                    || !bindItem.declaration().name().equals(slot.bindSlotId())) {
                throw new IllegalStateException(
                        "MatchBindItem slot id diverged from published bind slot '" + slot.bindSlotId() + "'"
                );
            }
        }
        for (var bindItem : bindItems.values()) {
            if (!bindSlots.containsKey(bindItem.declaration())) {
                throw new IllegalStateException(
                        "MatchBindItem has no published bind slot for '" + bindItem.bindSlotId() + "'"
                );
            }
        }
        validateMatchBindsAbsentFromLocalDeclarationSurface(graph, bindSlots);
    }

    private static @NotNull Map<PatternBindingExpression, MatchBindItem> collectMatchBindItems(
            @NotNull FrontendCfgGraph graph
    ) {
        var items = new LinkedHashMap<PatternBindingExpression, MatchBindItem>();
        for (var node : graph.nodes().values()) {
            if (!(node instanceof FrontendCfgGraph.SequenceNode sequenceNode)) {
                continue;
            }
            for (var item : sequenceNode.items()) {
                if (item instanceof MatchBindItem bindItem) {
                    var previous = items.put(bindItem.declaration(), bindItem);
                    if (previous != null) {
                        throw new IllegalStateException(
                                "Duplicate MatchBindItem for '" + bindItem.bindSlotId() + "'"
                        );
                    }
                }
            }
        }
        return items;
    }

    private static void validateMatchBindsAbsentFromLocalDeclarationSurface(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAstSideTable<FrontendMatchBindSlot> bindSlots
    ) {
        for (var node : graph.nodes().values()) {
            if (!(node instanceof FrontendCfgGraph.SequenceNode sequenceNode)) {
                continue;
            }
            for (var item : sequenceNode.items()) {
                if (item instanceof LocalDeclarationItem localDeclarationItem
                        && bindSlots.containsKey(localDeclarationItem.declaration())) {
                    throw new IllegalStateException(
                            "match bind identity leaked onto LocalDeclarationItem surface"
                    );
                }
            }
        }
    }

    /// Cross-table validation for the for-in build artifact.
    ///
    /// The graph alone cannot see the source-slot and hidden-state registries, so the build artifact
    /// validates them together at construction time instead of deferring to lowering processors. This
    /// catches missing/duplicate metadata, slot id reuse across nested/sibling loops, slot ids leaking
    /// into the ordinary value-id surface, and item/region/source-slot identity divergence early.
    private static void validateForLoopArtifacts(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAstSideTable<FrontendCfgRegion> regions,
            @NotNull FrontendAstSideTable<FrontendForSourceIteratorSlot> sourceSlots,
            @NotNull FrontendAstSideTable<FrontendForIteratorStateSlot> stateSlots
    ) {
        var hiddenSlotIds = new LinkedHashSet<String>();
        for (var stateSlot : stateSlots.values()) {
            if (!hiddenSlotIds.add(stateSlot.slotId())) {
                throw new IllegalStateException(
                        "Duplicate hidden for-in iterator state slot id '" + stateSlot.slotId() + "'"
                );
            }
            if (!hiddenSlotIds.add(stateSlot.nextTempSlotId())) {
                throw new IllegalStateException(
                        "Duplicate hidden for-in iterator next temp slot id '" + stateSlot.nextTempSlotId() + "'"
                );
            }
        }

        var forItemsByStatement = collectForLoopItemsByStatement(graph);

        for (var entry : regions.entrySet()) {
            if (!(entry.getKey() instanceof ForStatement forStatement)
                    || !(entry.getValue() instanceof FrontendForRegion region)) {
                continue;
            }
            validateForRegionArtifact(
                    graph,
                    forStatement,
                    region,
                    sourceSlots,
                    stateSlots,
                    hiddenSlotIds,
                    forItemsByStatement
            );
        }

        validateHiddenSlotsAbsentFromValueSurface(graph, hiddenSlotIds);
    }

    private static void validateForRegionArtifact(
            @NotNull FrontendCfgGraph graph,
            @NotNull ForStatement forStatement,
            @NotNull FrontendForRegion region,
            @NotNull FrontendAstSideTable<FrontendForSourceIteratorSlot> sourceSlots,
            @NotNull FrontendAstSideTable<FrontendForIteratorStateSlot> stateSlots,
            @NotNull Set<String> hiddenSlotIds,
            @NotNull Map<ForStatement, ForLoopItems> forItemsByStatement
    ) {
        var sourceSlot = sourceSlots.get(forStatement);
        if (sourceSlot == null) {
            throw new IllegalStateException(
                    "Missing source-facing for-in iterator slot metadata for ForStatement at " + forStatement.range()
            );
        }
        if (sourceSlot.statement() != forStatement) {
            throw new IllegalStateException(
                    "for-in source iterator slot statement identity mismatch at " + forStatement.range()
            );
        }
        if (!sourceSlot.sourceIteratorSlotId().equals(region.sourceIteratorSlotId())) {
            throw new IllegalStateException(
                    "for-in region source iterator slot id '" + region.sourceIteratorSlotId()
                            + "' disagrees with source slot metadata '" + sourceSlot.sourceIteratorSlotId() + "'"
            );
        }

        var stateSlot = stateSlots.get(forStatement);
        if (stateSlot == null) {
            throw new IllegalStateException(
                    "Missing hidden for-in iterator state slot metadata for ForStatement at " + forStatement.range()
            );
        }
        if (stateSlot.statement() != forStatement) {
            throw new IllegalStateException(
                    "for-in hidden state slot statement identity mismatch at " + forStatement.range()
            );
        }
        if (!stateSlot.slotId().equals(region.iteratorStateSlotId())) {
            throw new IllegalStateException(
                    "for-in region hidden state slot id '" + region.iteratorStateSlotId()
                            + "' disagrees with state slot metadata '" + stateSlot.slotId() + "'"
            );
        }
        if (hiddenSlotIds.contains(region.sourceIteratorSlotId())) {
            throw new IllegalStateException(
                    "for-in source iterator slot id '" + region.sourceIteratorSlotId()
                            + "' must not collide with a hidden state slot id"
            );
        }

        var items = forItemsByStatement.get(forStatement);
        if (items == null) {
            throw new IllegalStateException(
                    "Missing for-loop items in graph for ForStatement at " + forStatement.range()
            );
        }
        items.validate(graph, region, stateSlot);
    }

    private static @NotNull Map<ForStatement, ForLoopItems> collectForLoopItemsByStatement(
            @NotNull FrontendCfgGraph graph
    ) {
        var result = new LinkedHashMap<ForStatement, ForLoopItems>();
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                switch (item) {
                    case ForLoopInitItem initItem -> {
                        var collected = result.computeIfAbsent(initItem.statement(), _ -> new ForLoopItems());
                        collected.init = initItem;
                        collected.initNodeId = nodeId;
                    }
                    case ForLoopShouldContinueItem shouldContinueItem -> {
                        var collected = result.computeIfAbsent(shouldContinueItem.statement(), _ -> new ForLoopItems());
                        collected.shouldContinue = shouldContinueItem;
                        collected.shouldContinueNodeId = nodeId;
                    }
                    case ForLoopGetItem getItem -> {
                        var collected = result.computeIfAbsent(getItem.statement(), _ -> new ForLoopItems());
                        collected.get = getItem;
                        collected.getNodeId = nodeId;
                    }
                    case ForLoopNextItem nextItem -> {
                        var collected = result.computeIfAbsent(nextItem.statement(), _ -> new ForLoopItems());
                        collected.next = nextItem;
                        collected.nextNodeId = nodeId;
                    }
                    default -> {
                    }
                }
            }
        }
        return result;
    }

    private static void validateHiddenSlotsAbsentFromValueSurface(
            @NotNull FrontendCfgGraph graph,
            @NotNull Set<String> hiddenSlotIds
    ) {
        if (hiddenSlotIds.isEmpty()) {
            return;
        }
        for (var nodeId : graph.nodeIds()) {
            if (!(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode(_, var items, _))) {
                continue;
            }
            for (var item : items) {
                if (!(item instanceof ValueOpItem valueOpItem)) {
                    continue;
                }
                var resultValueId = valueOpItem.resultValueIdOrNull();
                if (resultValueId != null && hiddenSlotIds.contains(resultValueId)) {
                    throw new IllegalStateException(
                            "Hidden for-in slot id '" + resultValueId + "' must not appear as a CFG value result"
                    );
                }
                for (var operandValueId : valueOpItem.operandValueIds()) {
                    if (hiddenSlotIds.contains(operandValueId)) {
                        throw new IllegalStateException(
                                "Hidden for-in slot id '" + operandValueId
                                        + "' must not appear as an ordinary operand value id"
                        );
                    }
                }
            }
        }
    }

    /// Per-statement collection of the four for-loop items (with their owning node ids) used for
    /// cross-validation against the region, hidden-state metadata and graph topology.
    private static final class ForLoopItems {
        private @Nullable ForLoopInitItem init;
        private @Nullable ForLoopShouldContinueItem shouldContinue;
        private @Nullable ForLoopGetItem get;
        private @Nullable ForLoopNextItem next;
        private @Nullable String initNodeId;
        private @Nullable String shouldContinueNodeId;
        private @Nullable String getNodeId;
        private @Nullable String nextNodeId;

        private void validate(
                @NotNull FrontendCfgGraph graph,
                @NotNull FrontendForRegion region,
                @NotNull FrontendForIteratorStateSlot stateSlot
        ) {
            validateSlotReferences(region, stateSlot);
            validateItemPositions(graph, region);
            validateConditionBranch(graph, region);
        }

        private void validateSlotReferences(
                @NotNull FrontendForRegion region,
                @NotNull FrontendForIteratorStateSlot stateSlot
        ) {
            if (init == null || shouldContinue == null || get == null || next == null) {
                throw new IllegalStateException(
                        "for-in loop must publish exactly one init/should-continue/get/next item per ForStatement at "
                                + region.initEntryId()
                );
            }
            var stateSlotId = region.iteratorStateSlotId();
            if (!init.iteratorStateSlotId().equals(stateSlotId)
                    || !shouldContinue.iteratorStateSlotId().equals(stateSlotId)
                    || !get.iteratorStateSlotId().equals(stateSlotId)
                    || !next.iteratorStateSlotId().equals(stateSlotId)) {
                throw new IllegalStateException(
                        "for-in items must all reference the same hidden state slot '" + stateSlotId + "'"
                );
            }
            if (!get.sourceIteratorSlotId().equals(region.sourceIteratorSlotId())) {
                throw new IllegalStateException(
                        "for-in get item source iterator slot '" + get.sourceIteratorSlotId()
                                + "' disagrees with region source iterator slot '" + region.sourceIteratorSlotId() + "'"
                );
            }
            if (!next.nextTempSlotId().equals(stateSlot.nextTempSlotId())) {
                throw new IllegalStateException(
                        "for-in next item next temp slot '" + next.nextTempSlotId()
                                + "' disagrees with state slot metadata '" + stateSlot.nextTempSlotId() + "'"
                );
            }
        }

        /// Anchors each item to its expected entry: should-continue in the condition entry, get in the
        /// body entry, next in the update entry, and init in a sequence that falls through to the
        /// condition entry. The update entry must also fall through to the condition entry (backedge).
        private void validateItemPositions(@NotNull FrontendCfgGraph graph, @NotNull FrontendForRegion region) {
            if (!region.conditionEntryId().equals(shouldContinueNodeId)) {
                throw new IllegalStateException(
                        "for-in should-continue item must live in the condition entry '" + region.conditionEntryId()
                                + "', but was published in '" + shouldContinueNodeId + "'"
                );
            }
            if (!region.bodyEntryId().equals(getNodeId)) {
                throw new IllegalStateException(
                        "for-in get item must live in the body entry '" + region.bodyEntryId()
                                + "', but was published in '" + getNodeId + "'"
                );
            }
            if (!region.updateEntryId().equals(nextNodeId)) {
                throw new IllegalStateException(
                        "for-in next item must live in the update entry '" + region.updateEntryId()
                                + "', but was published in '" + nextNodeId + "'"
                );
            }
            var initSequence = requireSequence(graph, initNodeId, "init item");
            if (!initSequence.nextId().equals(region.conditionEntryId())) {
                throw new IllegalStateException(
                        "for-in init entry must fall through to the condition entry '" + region.conditionEntryId()
                                + "', but '" + initNodeId + "' continues to '" + initSequence.nextId() + "'"
                );
            }
            var updateSequence = requireSequence(graph, nextNodeId, "next item");
            if (!updateSequence.nextId().equals(region.conditionEntryId())) {
                throw new IllegalStateException(
                        "for-in update entry must fall through to the condition entry '" + region.conditionEntryId()
                                + "', but '" + nextNodeId + "' continues to '" + updateSequence.nextId() + "'"
                );
            }
        }

        /// The condition entry must fall through to a branch that tests the should-continue result and
        /// targets the body entry (true) and the exit (false).
        private void validateConditionBranch(@NotNull FrontendCfgGraph graph, @NotNull FrontendForRegion region) {
            var shouldContinueItem = Objects.requireNonNull(
                    shouldContinue, "shouldContinue must be validated before condition branch"
            );
            var conditionSequence = requireSequence(graph, shouldContinueNodeId, "should-continue item");
            if (!(graph.requireNode(conditionSequence.nextId()) instanceof FrontendCfgGraph.BranchNode branch)) {
                throw new IllegalStateException(
                        "for-in condition entry '" + region.conditionEntryId()
                                + "' must fall through to a condition branch"
                );
            }
            if (!branch.conditionValueId().equals(shouldContinueItem.resultValueId())) {
                throw new IllegalStateException(
                        "for-in condition branch must test the should-continue result '"
                                + shouldContinueItem.resultValueId() + "', but tests '" + branch.conditionValueId() + "'"
                );
            }
            if (!branch.trueTargetId().equals(region.bodyEntryId())
                    || !branch.falseTargetId().equals(region.exitId())) {
                throw new IllegalStateException(
                        "for-in condition branch must target body entry '" + region.bodyEntryId()
                                + "' and exit '" + region.exitId() + "', but targets '" + branch.trueTargetId()
                                + "' / '" + branch.falseTargetId() + "'"
                );
            }
        }

        private static @NotNull FrontendCfgGraph.SequenceNode requireSequence(
                @NotNull FrontendCfgGraph graph,
                @Nullable String nodeId,
                @NotNull String description
        ) {
            if (nodeId == null || !(graph.requireNode(nodeId) instanceof FrontendCfgGraph.SequenceNode sequence)) {
                throw new IllegalStateException("for-in " + description + " must live in a sequence node");
            }
            return sequence;
        }
    }

    private void initializeBuildState(@NotNull FrontendAnalysisData analysisData) {
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        nodes = new LinkedHashMap<>();
        regions = new FrontendAstSideTable<>();
        forSourceIteratorSlots = new FrontendAstSideTable<>();
        forIteratorStateSlots = new FrontendAstSideTable<>();
        matchBindSlots = new FrontendAstSideTable<>();
        foldedMatchBindDeclarations = Collections.newSetFromMap(new IdentityHashMap<>());
        loopStack.clear();
        nextSequenceIndex = 0;
        nextBranchIndex = 0;
        nextStopIndex = 0;
        nextValueIndex = 0;
        nextForIterIndex = 0;
    }

    private @NotNull ExecutableBodyBuild finishBuild(@NotNull String entryId) {
        unifyCollidingMatchBindSlotTypes();
        return new ExecutableBodyBuild(
                new FrontendCfgGraph(entryId, orderNodes(entryId)),
                copyRegions(requireRegions()),
                copyForSourceIteratorSlots(requireForSourceIteratorSlots()),
                copyForIteratorStateSlots(requireForIteratorStateSlots()),
                copyMatchBindSlots(requireMatchBindSlots()),
                copyFoldedMatchBindDeclarations(requireFoldedMatchBindDeclarations())
        );
    }

    public record ExecutableBodyBuild(
            @NotNull FrontendCfgGraph graph,
            @NotNull FrontendAstSideTable<FrontendCfgRegion> regions,
            @NotNull FrontendAstSideTable<FrontendForSourceIteratorSlot> forSourceIteratorSlots,
            @NotNull FrontendAstSideTable<FrontendForIteratorStateSlot> forIteratorStateSlots,
            @NotNull FrontendAstSideTable<FrontendMatchBindSlot> matchBindSlots,
            @NotNull Set<PatternBindingExpression> foldedMatchBindDeclarations
    ) {
        public ExecutableBodyBuild {
            Objects.requireNonNull(graph, "graph must not be null");
            regions = copyRegions(Objects.requireNonNull(regions, "regions must not be null"));
            forSourceIteratorSlots = copyForSourceIteratorSlots(
                    Objects.requireNonNull(forSourceIteratorSlots, "forSourceIteratorSlots must not be null")
            );
            forIteratorStateSlots = copyForIteratorStateSlots(
                    Objects.requireNonNull(forIteratorStateSlots, "forIteratorStateSlots must not be null")
            );
            matchBindSlots = copyMatchBindSlots(
                    Objects.requireNonNull(matchBindSlots, "matchBindSlots must not be null")
            );
            foldedMatchBindDeclarations = copyFoldedMatchBindDeclarations(
                    Objects.requireNonNull(foldedMatchBindDeclarations, "foldedMatchBindDeclarations must not be null")
            );
            validateForLoopArtifacts(graph, regions, forSourceIteratorSlots, forIteratorStateSlots);
            validateMatchArtifacts(graph, regions, matchBindSlots, foldedMatchBindDeclarations);
        }
    }

    /// `entryId` freezes the first node of one expression subgraph while `currentSequence` tracks
    /// the currently writable continuation. Linear expressions keep both on the same sequence;
    /// branchy expressions may move only the continuation to a later merge sequence.
    private record BuildCursor(
            @NotNull String entryId,
            @NotNull OpenSequence currentSequence
    ) {
        private BuildCursor(@NotNull OpenSequence currentSequence) {
            this(currentSequence.id(), currentSequence);
        }

        private BuildCursor {
            entryId = FrontendCfgGraph.validateNodeId(entryId, "entryId");
            Objects.requireNonNull(currentSequence, "currentSequence must not be null");
        }
    }

    private record OpaqueExpressionRoute(
            @Nullable FrontendWritableRoutePayload writableRoutePayloadOrNull,
            @Nullable FrontendBinding bindingOrNull
    ) {
        private static @NotNull OpaqueExpressionRoute empty() {
            return new OpaqueExpressionRoute(null, null);
        }
    }

    private record ValueBuild(
            @NotNull BuildCursor cursor,
            @NotNull Node valueAnchor,
            @NotNull String resultValueId,
            @Nullable FrontendWritableRoutePayload writableRoutePayloadOrNull,
            @Nullable FrontendBinding bindingOrNull
    ) {
        private ValueBuild(
                @NotNull BuildCursor cursor,
                @NotNull Node valueAnchor,
                @NotNull String resultValueId,
                @Nullable FrontendWritableRoutePayload writableRoutePayloadOrNull
        ) {
            this(cursor, valueAnchor, resultValueId, writableRoutePayloadOrNull, null);
        }

        private ValueBuild {
            Objects.requireNonNull(cursor, "cursor must not be null");
            Objects.requireNonNull(valueAnchor, "valueAnchor must not be null");
            resultValueId = FrontendCfgGraph.validateValueId(resultValueId, "resultValueId");
        }
    }

    private record AssignmentTargetBuild(
            @NotNull BuildCursor cursor,
            @NotNull List<String> valueIds,
            @NotNull FrontendWritableRoutePayload writableRoutePayload
    ) {
        private AssignmentTargetBuild {
            Objects.requireNonNull(cursor, "cursor must not be null");
            valueIds = List.copyOf(Objects.requireNonNull(valueIds, "valueIds must not be null"));
            Objects.requireNonNull(
                    writableRoutePayload,
                    "writableRoutePayload must not be null"
            );
        }
    }

    private record ValueListBuild(
            @NotNull BuildCursor cursor,
            @NotNull List<String> valueIds
    ) {
        private ValueListBuild {
            Objects.requireNonNull(cursor, "cursor must not be null");
            valueIds = List.copyOf(Objects.requireNonNull(valueIds, "valueIds must not be null"));
        }
    }

    private record ConditionBuild(
            @NotNull String entryId
    ) {
        private ConditionBuild {
            entryId = FrontendCfgGraph.validateNodeId(entryId, "entryId");
        }
    }

    private record MatchSectionBuild(
            @NotNull String testEntryId,
            @NotNull String bodyEntryId
    ) {
        private MatchSectionBuild {
            testEntryId = FrontendCfgGraph.validateNodeId(testEntryId, "testEntryId");
            bodyEntryId = FrontendCfgGraph.validateNodeId(bodyEntryId, "bodyEntryId");
        }
    }

    private record ClauseBuild(
            @NotNull String entryId,
            boolean fallsThrough
    ) {
        private ClauseBuild {
            Objects.requireNonNull(entryId, "entryId must not be null");
        }
    }

    private record BlockBuild(
            @NotNull String entryId,
            boolean fallsThrough
    ) {
        private BlockBuild {
            Objects.requireNonNull(entryId, "entryId must not be null");
        }
    }

    private record OpenSequence(
            @NotNull String id,
            @NotNull ArrayList<SequenceItem> items
    ) {
        private OpenSequence(@NotNull String id) {
            this(id, new ArrayList<>());
        }

        private OpenSequence {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(items, "items must not be null");
        }
    }

    private record LoopFrame(
            @NotNull String continueTargetId,
            @NotNull String breakTargetId
    ) {
        private LoopFrame {
            Objects.requireNonNull(continueTargetId, "continueTargetId must not be null");
            Objects.requireNonNull(breakTargetId, "breakTargetId must not be null");
        }
    }

    private record DirectSlotAliasRoot(
            @NotNull Expression expression,
            @NotNull DirectSlotAliasRootKind kind
    ) {
        private DirectSlotAliasRoot {
            Objects.requireNonNull(expression, "expression must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    private enum DirectSlotAliasRootKind {
        EXPLICIT_SELF,
        LOCAL_VAR,
        PARAMETER
    }

    private enum DirectSlotAliasArgumentSafety {
        SAFE_TO_ALIAS,
        REQUIRES_SNAPSHOT
    }

    private static final class BlockState {
        private @Nullable String entryId;
        private @Nullable OpenSequence currentSequence;
        private boolean reachable = true;

        private @Nullable String entryIdOrNull() {
            return entryId;
        }

        private void setEntryId(@NotNull String entryId) {
            this.entryId = Objects.requireNonNull(entryId, "entryId must not be null");
        }

        private void setEntryIdIfMissing(@NotNull String entryId) {
            if (this.entryId == null) {
                this.entryId = Objects.requireNonNull(entryId, "entryId must not be null");
            }
        }

        private @Nullable OpenSequence currentSequenceOrNull() {
            return currentSequence;
        }

        private void setCurrentSequence(@Nullable OpenSequence currentSequence) {
            this.currentSequence = currentSequence;
        }

        private boolean reachable() {
            return reachable;
        }

        private void setReachable(boolean reachable) {
            this.reachable = reachable;
        }
    }
}
