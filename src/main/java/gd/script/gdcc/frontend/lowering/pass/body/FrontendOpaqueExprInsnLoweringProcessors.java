package gd.script.gdcc.frontend.lowering.pass.body;

import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.insn.AssignInsn;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.LiteralBoolInsn;
import gd.script.gdcc.lir.insn.LiteralFloatInsn;
import gd.script.gdcc.lir.insn.LiteralIntInsn;
import gd.script.gdcc.lir.insn.LiteralNilInsn;
import gd.script.gdcc.lir.insn.LiteralNodePathInsn;
import gd.script.gdcc.lir.insn.LiteralStringInsn;
import gd.script.gdcc.lir.insn.LiteralStringNameInsn;
import gd.script.gdcc.lir.insn.ConstructCallableInsn;
import gd.script.gdcc.lir.insn.ConstructSignalInsn;
import gd.script.gdcc.lir.insn.ConstructStandaloneCallableInsn;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.lir.insn.LoadPropertyInsn;
import gd.script.gdcc.lir.insn.LoadStaticInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.util.StringUtil;
import gd.script.gdcc.gdextension.ExtensionEnumValue;
import gd.script.gdcc.gdextension.ExtensionGlobalConstant;
import gd.script.gdcc.scope.GdScriptLanguageConstant;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import dev.superice.gdparser.frontend.ast.BinaryExpression;
import dev.superice.gdparser.frontend.ast.Expression;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.LiteralExpression;
import dev.superice.gdparser.frontend.ast.PreloadExpression;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.UnaryExpression;
import gd.script.gdcc.frontend.lowering.cfg.item.OpaqueExprValueItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

final class FrontendOpaqueExprInsnLoweringProcessors {
    private FrontendOpaqueExprInsnLoweringProcessors() {
    }

    static @NotNull FrontendInsnLoweringProcessorRegistry<Expression, FrontendBodyLoweringSession.OpaqueExprLoweringContext>
    createRegistry() {
        return FrontendInsnLoweringProcessorRegistry.of(
                "opaque expression",
                new FrontendIdentifierOpaqueExprInsnLoweringProcessor(),
                new FrontendLiteralOpaqueExprInsnLoweringProcessor(),
                new FrontendSelfOpaqueExprInsnLoweringProcessor(),
                new FrontendUnaryOpaqueExprInsnLoweringProcessor(),
                new FrontendBinaryOpaqueExprInsnLoweringProcessor(),
                new FrontendPreloadOpaqueExprInsnLoweringProcessor(),
                new FrontendGetNodeOpaqueExprInsnLoweringProcessor()
        );
    }

    /// Resolves a bare identifier leaf through the already-published binding table.
    ///
    /// This processor is allowed to choose only among binding-backed runtime load routes
    /// (local/parameter/capture/property/self/singleton) and constant literal materialization;
    /// it must not re-run any scope lookup or member inference.
    ///
    /// The top-binding owner procedure publishes `FrontendBindingKind.SELF` only for explicit
    /// `SelfExpression`. If an `IdentifierExpression` arrives here with binding kind `SELF`, some
    /// earlier publication step violated that contract and body lowering must fail fast instead of
    /// silently recovering to `"self"`.
    private static final class FrontendIdentifierOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<IdentifierExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<IdentifierExpression> nodeType() {
            return IdentifierExpression.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull IdentifierExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            var binding = session.requireBinding(node);
            var resultSlotId = session.resultSlotId(item);
            switch (binding.kind()) {
                case SELF -> throw session.identifierSelfBindingContractViolation(node, "Identifier value lowering");
                // `super` is the same `self` storage; only the enclosing call's target resolution
                // differs, so the leaf materializes exactly like an explicit self read.
                case SUPER ->
                        block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId, "self"));
                case LOCAL_VAR, PARAMETER, CAPTURE ->
                        block.appendNonTerminatorInstruction(new AssignInsn(resultSlotId, binding.symbolName()));
                case PROPERTY -> {
                    if (session.isStaticPropertyBinding(binding)) {
                        block.appendNonTerminatorInstruction(new LoadStaticInsn(
                                resultSlotId,
                                session.currentClassName(),
                                binding.symbolName()
                        ));
                        return block;
                    }
                    session.requireSelfSlot();
                    block.appendNonTerminatorInstruction(new LoadPropertyInsn(resultSlotId, binding.symbolName(), "self"));
                }
                case SIGNAL -> {
                    session.requireSelfSlot();
                    session.emitAssertObjectLiveIfNeeded(block, "self");
                    block.appendNonTerminatorInstruction(new ConstructSignalInsn(
                            resultSlotId,
                            "self",
                            binding.symbolName()
                    ));
                }
                case METHOD -> {
                    session.requireSelfSlot();
                    session.emitAssertObjectLiveIfNeeded(block, "self");
                    block.appendNonTerminatorInstruction(new ConstructCallableInsn(
                            resultSlotId,
                            "self",
                            binding.symbolName()
                    ));
                }
                case STATIC_METHOD -> block.appendNonTerminatorInstruction(new ConstructStandaloneCallableInsn(
                        resultSlotId,
                        StandaloneCallableKind.STATIC_GDCC,
                        session.requireDeclaringStaticOwnerName(session.currentClassName(), binding.symbolName()),
                        binding.symbolName()
                ));
                case UTILITY_FUNCTION -> block.appendNonTerminatorInstruction(new ConstructStandaloneCallableInsn(
                        resultSlotId,
                        StandaloneCallableKind.UTILITY,
                        "",
                        binding.symbolName()
                ));
                case SINGLETON -> {
                    session.checkSingletonBindingType(binding);
                    block.appendNonTerminatorInstruction(new LoadStaticInsn(
                            resultSlotId,
                            "@GlobalScope",
                            binding.symbolName()
                    ));
                }
                case CONSTANT -> {
                    // Global constants materialize as pure literal instructions: int-valued
                    // declarations (JSON global constants, synthesized extreme constants, and
                    // bare global enum members) become `literal_int`, while the synthesized
                    // GDScript language constants (`PI`/`TAU`/`INF`/`NAN`) become `literal_float`.
                    if (binding.declarationSite() instanceof ExtensionGlobalConstant globalConstant) {
                        block.appendNonTerminatorInstruction(new LiteralIntInsn(resultSlotId, globalConstant.value()));
                        return block;
                    }
                    if (binding.declarationSite() instanceof ExtensionEnumValue enumValue) {
                        block.appendNonTerminatorInstruction(new LiteralIntInsn(resultSlotId, enumValue.value()));
                        return block;
                    }
                    if (binding.declarationSite() instanceof GdScriptLanguageConstant languageConstant) {
                        block.appendNonTerminatorInstruction(new LiteralFloatInsn(resultSlotId, languageConstant.value()));
                        return block;
                    }
                    throw session.unsupportedSequenceItem(
                            item,
                            "constant binding is not supported by frontend body lowering: " + binding.symbolName()
                    );
                }
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "identifier binding kind is not supported by frontend body lowering: " + binding.kind()
                );
            }
            return block;
        }
    }

    /// Emits literal materialization instructions from the parser literal kind while preserving the
    /// LIR contract that string-like payloads are already runtime-normalized.
    ///
    /// The processor stays intentionally dumb: all type acceptance already happened upstream, so it
    /// only translates the published literal surface into the matching concrete LIR instruction.
    private static final class FrontendLiteralOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<LiteralExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<LiteralExpression> nodeType() {
            return LiteralExpression.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull LiteralExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            var resultSlotId = session.resultSlotId(item);
            var sourceText = node.sourceText();
            switch (node.kind()) {
                case "integer" -> block.appendNonTerminatorInstruction(new LiteralIntInsn(
                        resultSlotId,
                        Long.parseLong(sourceText)
                ));
                case "number" -> {
                    if (sourceText.contains(".")) {
                        block.appendNonTerminatorInstruction(new LiteralFloatInsn(
                                resultSlotId,
                                Double.parseDouble(sourceText)
                        ));
                        return block;
                    }
                    block.appendNonTerminatorInstruction(new LiteralIntInsn(
                            resultSlotId,
                            Long.parseLong(sourceText)
                    ));
                }
                case "float" -> block.appendNonTerminatorInstruction(new LiteralFloatInsn(
                        resultSlotId,
                        Double.parseDouble(sourceText)
                ));
                case "string" -> block.appendNonTerminatorInstruction(new LiteralStringInsn(
                        resultSlotId,
                        StringUtil.decodeGdStringLexeme(sourceText)
                ));
                case "string_name" -> block.appendNonTerminatorInstruction(new LiteralStringNameInsn(
                        resultSlotId,
                        StringUtil.decodeGdStringLexeme(sourceText)
                ));
                case "node_path" -> block.appendNonTerminatorInstruction(new LiteralNodePathInsn(
                        resultSlotId,
                        StringUtil.decodeNodePathLexeme(sourceText)
                ));
                case "true" -> block.appendNonTerminatorInstruction(new LiteralBoolInsn(resultSlotId, true));
                case "false" -> block.appendNonTerminatorInstruction(new LiteralBoolInsn(resultSlotId, false));
                case "null" -> block.appendNonTerminatorInstruction(new LiteralNilInsn(resultSlotId));
                default -> throw session.unsupportedSequenceItem(
                        item,
                        "literal kind is not supported by frontend body lowering: " + node.kind()
                );
            }
            return block;
        }
    }

    /// Reuses the implicit `self` slot instead of allocating any extra receiver reconstruction path.
    ///
    /// `SelfExpression` has no child operands and no semantic branching once compile gate has
    /// accepted the function shape, so the processor only copies the canonical slot id.
    private static final class FrontendSelfOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<SelfExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<SelfExpression> nodeType() {
            return SelfExpression.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull SelfExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            block.appendNonTerminatorInstruction(new AssignInsn(session.resultSlotId(item), "self"));
            return block;
        }
    }

    /// Finalizes unary opaque expressions from their already-materialized operand slot.
    ///
    /// Child evaluation order is frozen by CFG build, so the processor only validates the operand
    /// count and chooses the unary opcode mapped from the source operator lexeme.
    private static final class FrontendUnaryOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<UnaryExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<UnaryExpression> nodeType() {
            return UnaryExpression.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull UnaryExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            session.requireOpaqueOperandCount(item, 1);
            block.appendNonTerminatorInstruction(new UnaryOpInsn(
                    session.resultSlotId(item),
                    GodotOperator.fromSourceLexeme(node.operator(), GodotOperator.OperatorArity.UNARY),
                    session.slotIdForValue(item.operandValueIds().getFirst())
            ));
            return block;
        }
    }

    /// Finalizes eager binary expressions from the two operand slots already published by CFG build.
    ///
    /// Short-circuit `and/or` never reaches this processor; if they do, that means the opaque-item
    /// classifier or compile gate has been bypassed. Source-level `not in` is lowered as the
    /// composite pair `BinaryOpInsn(IN)` + `UnaryOpInsn(NOT)`, mirroring the sema rule
    /// `not (lhs in rhs)` without any dedicated opcode.
    private static final class FrontendBinaryOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<BinaryExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<BinaryExpression> nodeType() {
            return BinaryExpression.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull BinaryExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            session.requireOpaqueOperandCount(item, 2);
            // `not in` must be intercepted before the generic path: `GodotOperator.fromSourceLexeme`
            // stays fail-closed for it, and the C backend has no `NOT_IN` opcode. The intermediate
            // slot is fixed to `bool` because the runtime `in` result is always a bool (typed and
            // dynamic operands alike — the backend already evaluates `IN` over Variant operands and
            // unpacks the bool result), and backend unary `NOT` only ships the `NOT + bool`
            // specialization.
            if ("not in".equals(node.operator())) {
                var positiveSlotId = session.allocateWritableRouteTemp("not_in_positive", GdBoolType.BOOL);
                block.appendNonTerminatorInstruction(new BinaryOpInsn(
                        positiveSlotId,
                        GodotOperator.IN,
                        session.slotIdForValue(item.operandValueIds().getFirst()),
                        session.slotIdForValue(item.operandValueIds().getLast())
                ));
                block.appendNonTerminatorInstruction(new UnaryOpInsn(
                        session.resultSlotId(item),
                        GodotOperator.NOT,
                        positiveSlotId
                ));
                return block;
            }
            block.appendNonTerminatorInstruction(new BinaryOpInsn(
                    session.resultSlotId(item),
                    GodotOperator.fromSourceLexeme(node.operator(), GodotOperator.OperatorArity.BINARY),
                    session.slotIdForValue(item.operandValueIds().getFirst()),
                    session.slotIdForValue(item.operandValueIds().getLast())
            ));
            return block;
        }
    }

    /// Lowers `preload("literal")` as the `ResourceLoader.load` evaluation-point call:
    /// `load_static "@GlobalScope" "ResourceLoader"` followed by `call_method "load"`. Sema
    /// already rejected non-literal paths and published `RESOLVED(Resource)`; the path string is
    /// passed through verbatim with no compile-time normalization, and the literal is read
    /// straight from the AST (the opaque item carries no child operands by construction).
    private static final class FrontendPreloadOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<PreloadExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<PreloadExpression> nodeType() {
            return PreloadExpression.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull PreloadExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            session.requireOpaqueOperandCount(item, 0);
            if (!(node.path() instanceof LiteralExpression pathLiteral)
                    || !"string".equals(pathLiteral.kind())) {
                // Unreachable when the compile gate ran; hand-built graphs can still violate the
                // contract, so fail fast instead of emitting a call with an invented path.
                throw session.unsupportedSequenceItem(
                        item,
                        "preload path must be a string literal, got: " + node.path().getClass().getSimpleName()
                );
            }
            var pathSlotId = session.allocateGdScriptLanguageFunctionTemp("preload_path", GdStringType.STRING);
            block.appendNonTerminatorInstruction(new LiteralStringInsn(
                    pathSlotId,
                    StringUtil.decodeGdStringLexeme(pathLiteral.sourceText())
            ));
            var loaderSlotId = session.allocateGdScriptLanguageFunctionTemp(
                    "resource_loader",
                    new GdObjectType("ResourceLoader")
            );
            block.appendNonTerminatorInstruction(new LoadStaticInsn(loaderSlotId, "@GlobalScope", "ResourceLoader"));
            block.appendNonTerminatorInstruction(new CallMethodInsn(
                    session.resultSlotId(item),
                    "load",
                    loaderSlotId,
                    List.of(new LirInstruction.VariableOperand(pathSlotId))
            ));
            return block;
        }
    }

    /// Lowers the get-node shorthand (`$Child` / `%Unique`) as Godot's desugar
    /// `self.get_node(NodePath(<decoded path>))`: a `literal_node_path`, an upcast `assign` of
    /// `self` into a fresh temp whose static type is `Node`, then a `call_method "get_node"`.
    ///
    /// The receiver temp must be statically typed `Node` — never the GDCC class type of `self`:
    /// backend method resolution collects candidates starting from the receiver's static type, so
    /// a script-level `get_node(NodePath)` override on a GDCC subclass would otherwise shadow the
    /// engine method. Godot's compiler pins `$` to the native `Node.get_node` bind; typing the
    /// receiver slot `Node` reproduces that pinning through the ordinary ENGINE route. Sema has
    /// already proven the enclosing class is Node-derived, so the upcast assign is always legal.
    private static final class FrontendGetNodeOpaqueExprInsnLoweringProcessor
            implements FrontendInsnLoweringProcessor<GetNodeExpression, FrontendBodyLoweringSession.OpaqueExprLoweringContext> {
        @Override
        public @NotNull Class<GetNodeExpression> nodeType() {
            return GetNodeExpression.class;
        }

        @Override
        public @NotNull LirBasicBlock lower(
                @NotNull FrontendBodyLoweringSession session,
                @NotNull LirBasicBlock block,
                @NotNull GetNodeExpression node,
                @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
        ) {
            var item = requireContext(context);
            session.requireOpaqueOperandCount(item, 0);
            // `self` is the canonical always-live receiver slot, so no AssertObjectLive is needed;
            // `requireSelfSlot` fail-fasts if the current shell never published one (a lambda body
            // only has it when capture planning synthesized a leading `self` capture).
            session.requireSelfSlot();
            var pathSlotId = session.allocateGdScriptLanguageFunctionTemp("get_node_path", GdNodePathType.NODE_PATH);
            block.appendNonTerminatorInstruction(new LiteralNodePathInsn(
                    pathSlotId,
                    StringUtil.decodeGetNodePathLexeme(node.sourceText())
            ));
            var receiverSlotId = session.allocateGdScriptLanguageFunctionTemp(
                    "get_node_receiver",
                    new GdObjectType("Node")
            );
            block.appendNonTerminatorInstruction(new AssignInsn(receiverSlotId, "self"));
            block.appendNonTerminatorInstruction(new CallMethodInsn(
                    session.resultSlotId(item),
                    "get_node",
                    receiverSlotId,
                    List.of(new LirInstruction.VariableOperand(pathSlotId))
            ));
            return block;
        }
    }

    private static @NotNull OpaqueExprValueItem requireContext(
            @Nullable FrontendBodyLoweringSession.OpaqueExprLoweringContext context
    ) {
        return Objects.requireNonNull(context, "context must not be null").item();
    }
}
