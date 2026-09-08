package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.c.gen.binding.BindingData;
import gd.script.gdcc.backend.c.gen.binding.BoundMetadata;
import gd.script.gdcc.backend.c.gen.binding.EngineMethodHelperParam;
import gd.script.gdcc.backend.c.gen.binding.EngineMethodSymbolKey;
import gd.script.gdcc.backend.c.gen.binding.GodotBindingSupport;
import gd.script.gdcc.backend.c.gen.fatptr.ObjectFatPtrSpec;
import gd.script.gdcc.backend.c.gen.fatptr.ObjectFatPtrUpcastSpec;
import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import gd.script.gdcc.backend.c.gen.insn.OperatorResolver;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.scope.*;
import gd.script.gdcc.scope.resolver.ScopeTypeParsers;
import gd.script.gdcc.type.*;
import gd.script.gdcc.util.type.TypeCheckUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static gd.script.gdcc.util.StringUtil.escapeStringLiteral;

public final class CGenHelper {
    private static final String GODOT_UTILITY_PREFIX = "godot_";
    private static final String VARIANT_WRITEBACK_HELPER_NAME = "gdcc_variant_requires_writeback";
    /// Static route table from synthetic GDScript language function names to their `gdcc_*`
    /// runtime helper. `load` is deliberately absent: frontend lowering rewrites it into a
    /// ResourceLoader singleton call, so it must never reach `call_global` routing.
    private static final Map<String, String> GDSCRIPT_LANGUAGE_FUNCTION_C_NAMES = Map.of(
            "len", "gdcc_len",
            "char", "gdcc_char",
            "ord", "gdcc_ord",
            "range", "gdcc_range",
            // Named `*_global` to keep the hard boundary against the `x is T` expression
            // helper family `gdcc_is_instance_of_object_*`.
            "is_instance_of", "gdcc_is_instance_of_global"
    );
    private static final FunctionSignature VARIANT_WRITEBACK_HELPER_SIGNATURE = new FunctionSignature(
            VARIANT_WRITEBACK_HELPER_NAME,
            List.of(new FunctionSignature.Parameter("value", GdVariantType.VARIANT, null)),
            false,
            GdBoolType.BOOL
    );

    private final @NotNull CodegenContext context;
    private final @NotNull CBuiltinBuilder builtinBuilder;
    private final @NotNull CIntrinsicManager intrinsicManager;
    private final @NotNull OperatorResolver operatorResolver = new OperatorResolver();
    private final @NotNull Set<BindingData> bindingDataSet = new HashSet<>();
    private final boolean hasCoroutineFunctions;
    /// Module-wide GDCC vtable plan, computed eagerly so inheritance/signature conflicts fail
    /// fast at helper construction. Production registers the whole module before this point;
    /// directly constructed fixtures with incomplete registries fall back to slotless planning
    /// under the planner's tolerance rule.
    private final @NotNull CVtablePlanner vtablePlanner;

    public CGenHelper(@NotNull CodegenContext context, @NotNull List<? extends ClassDef> classDefs) {
        this.context = context;
        this.builtinBuilder = new CBuiltinBuilder(this);
        this.intrinsicManager = new CIntrinsicManager();
        this.collectBindingData(classDefs);
        this.hasCoroutineFunctions = classDefs.stream()
                .flatMap(classDef -> classDef.getFunctions().stream())
                .anyMatch(function -> function instanceof LirFunctionDef lirFunction && lirFunction.isCoroutine());
        this.vtablePlanner = new CVtablePlanner(classDefs, context.classRegistry());
    }

    public record OperatorEvaluatorHelperSpec(
            @NotNull String functionName,
            boolean unary,
            @NotNull String operatorEnumLiteral,
            @NotNull GdType leftType,
            @Nullable GdType rightType,
            @NotNull GdType returnType,
            @NotNull String leftVariantTypeEnumLiteral,
            @Nullable String rightVariantTypeEnumLiteral
    ) {
        public OperatorEvaluatorHelperSpec {
            Objects.requireNonNull(functionName);
            Objects.requireNonNull(operatorEnumLiteral);
            Objects.requireNonNull(leftType);
            Objects.requireNonNull(returnType);
            Objects.requireNonNull(leftVariantTypeEnumLiteral);
            if (unary && rightType != null) {
                throw new IllegalArgumentException("Unary evaluator helper must not have rightType");
            }
            if (unary && rightVariantTypeEnumLiteral != null) {
                throw new IllegalArgumentException("Unary evaluator helper must not have rightVariantTypeEnumLiteral");
            }
            if (!unary && rightType == null) {
                throw new IllegalArgumentException("Binary evaluator helper must have rightType");
            }
            if (!unary && rightVariantTypeEnumLiteral == null) {
                throw new IllegalArgumentException("Binary evaluator helper must have rightVariantTypeEnumLiteral");
            }
        }
    }

    private record TypedContainerRuntimeLeaf(
            @NotNull String typeIntLiteral,
            @NotNull String classNameExpr,
            boolean objectLeaf
    ) {
    }

    public @NotNull List<BindingData> getBindingDataList() {
        return List.copyOf(bindingDataSet);
    }

    public @NotNull CIntrinsicManager intrinsicManager() {
        return intrinsicManager;
    }

    public @NotNull List<OperatorEvaluatorHelperSpec> collectOperatorEvaluatorHelperSpecs(@NotNull LirModule module) {
        var specsByName = new LinkedHashMap<String, OperatorEvaluatorHelperSpec>();
        for (var classDef : module.getClassDefs()) {
            collectClassOperatorEvaluatorHelperSpecs(specsByName, classDef);
        }
        return List.copyOf(specsByName.values());
    }

    /// C parameter type of a generated `gdcc_eval_*` helper.
    /// Object operands are internal fat pointers (by value); non-objects keep their usual ref shape.
    public @NotNull String renderOperatorEvaluatorHelperTypeInC(@NotNull GdType type) {
        return renderGdTypeRefInC(type);
    }

    /// C return type of a generated `gdcc_eval_*` helper (same as internal storage).
    public @NotNull String renderOperatorEvaluatorHelperReturnTypeInC(@NotNull GdType type) {
        return renderGdTypeInC(type);
    }

    /// Declares a local raw object slot when a fat-pointer operand must be lowered to Godot's
    /// `GDExtensionPtrOperatorEvaluator` ABI (`void*` of a live raw object pointer). Empty for non-objects.
    public @NotNull String renderOperatorEvaluatorObjectRawSlotDecl(@NotNull GdType type, @NotNull String argName) {
        if (!(type instanceof GdObjectType objectType)) {
            return "";
        }
        var fatType = renderObjectFatPtrStorageType(objectType);
        return "GDExtensionObjectPtr " + argName + "_raw = " + fatType + "_live_object(" + argName + ");\n";
    }

    /// Argument expression passed to `GDExtensionPtrOperatorEvaluator`.
    /// Object operands pass the address of the temporary raw slot materialised above.
    public @NotNull String renderOperatorEvaluatorArgExpr(@NotNull GdType type, @NotNull String argName) {
        if (type instanceof GdObjectType) {
            return "&" + argName + "_raw";
        }
        if (type instanceof GdPrimitiveType) {
            return "&" + argName;
        }
        return argName;
    }

    /// Local result carrier type for the evaluator out-parameter.
    /// Godot writes a raw object pointer; the helper then captures it into a fat pointer return.
    ///
    /// Defensive: Godot 4.5.1 `extension_api` has no builtin operator with `return_type: Object`
    /// (Object appears only as a right operand; returns are bool/String). Object/object `==`/`!=` also
    /// bypass evaluators via `OBJECT_COMPARISON`. This branch stays so a future metadata surface
    /// keeps the same raw-carrier → ownership-neutral `_from_raw` shape as other raw producers.
    public @NotNull String renderOperatorEvaluatorResultCarrierTypeInC(@NotNull GdType type) {
        if (type instanceof GdObjectType) {
            return "GDExtensionObjectPtr";
        }
        return renderGdTypeInC(type);
    }

    /// Converts the evaluator result carrier into the helper return expression.
    /// See {@link #renderOperatorEvaluatorResultCarrierTypeInC} for why the object branch is kept.
    public @NotNull String renderOperatorEvaluatorReturnExpr(@NotNull GdType type, @NotNull String resultName) {
        if (type instanceof GdObjectType objectType) {
            return renderFatPtrFromRawExpr(resultName, objectType);
        }
        return resultName;
    }

    private @NotNull String renderFatPtrFromRawExpr(@NotNull String rawCode, @NotNull GdObjectType objectType) {
        return renderObjectFatPtrStorageType(objectType) + "_from_raw((GDExtensionObjectPtr)(" + rawCode + "))";
    }

    /// Context-aware default/zero expression for hard-fail returns and null slots.
    /// Object values use the per-type fat pointer zero compound literal; other types keep the
    /// legacy non-object defaults from `CBodyBuilder.renderDefaultValueExpr`.
    public @NotNull String renderDefaultValueExprInC(@NotNull GdType type) {
        if (type instanceof GdObjectType objectType) {
            return "(" + renderObjectFatPtrStorageType(objectType) + "){ 0 }";
        }
        return CBodyBuilder.renderDefaultValueExpr(type);
    }

    private void collectClassOperatorEvaluatorHelperSpecs(@NotNull Map<String, OperatorEvaluatorHelperSpec> specsByName,
                                                          @NotNull LirClassDef classDef) {
        for (var func : classDef.getFunctions()) {
            var bodyBuilder = new CBodyBuilder(this, classDef, func);
            for (var block : func) {
                var instructions = block.getInstructions();
                for (var i = 0; i < instructions.size(); i++) {
                    var instruction = instructions.get(i);
                    bodyBuilder.setCurrentPosition(block, i, instruction);
                    switch (instruction) {
                        case UnaryOpInsn unaryOpInsn -> collectUnaryEvaluatorHelperSpec(
                                specsByName, bodyBuilder, func, unaryOpInsn
                        );
                        case BinaryOpInsn binaryOpInsn -> collectBinaryEvaluatorHelperSpec(
                                specsByName, bodyBuilder, func, binaryOpInsn
                        );
                        default -> {
                        }
                    }
                }
            }
        }
    }

    private void collectUnaryEvaluatorHelperSpec(@NotNull Map<String, OperatorEvaluatorHelperSpec> specsByName,
                                                 @NotNull CBodyBuilder bodyBuilder,
                                                 @NotNull LirFunctionDef func,
                                                 @NotNull UnaryOpInsn instruction) {
        var operandVar = resolveOperatorOperandVariable(func, instruction.operandId(), "operand");
        var decision = operatorResolver.resolveUnaryPath(bodyBuilder, instruction.op(), operandVar.type());
        if (decision.path() != OperatorResolver.OperatorPath.BUILTIN_EVALUATOR ||
                decision.semanticResultType() == null) {
            return;
        }
        var semanticReturnType = decision.semanticResultType();
        var functionName = operatorResolver.renderUnaryEvaluatorHelperName(
                instruction.op(), operandVar.type(), semanticReturnType
        );
        specsByName.putIfAbsent(functionName, new OperatorEvaluatorHelperSpec(
                functionName,
                true,
                operatorResolver.resolveVariantOperatorEnumLiteral(instruction.op()),
                operandVar.type(),
                null,
                semanticReturnType,
                operatorResolver.resolveVariantTypeEnumLiteral(bodyBuilder, operandVar.type()),
                null
        ));
    }

    private void collectBinaryEvaluatorHelperSpec(@NotNull Map<String, OperatorEvaluatorHelperSpec> specsByName,
                                                  @NotNull CBodyBuilder bodyBuilder,
                                                  @NotNull LirFunctionDef func,
                                                  @NotNull BinaryOpInsn instruction) {
        var leftVar = resolveOperatorOperandVariable(func, instruction.leftId(), "left");
        var rightVar = resolveOperatorOperandVariable(func, instruction.rightId(), "right");
        var decision = operatorResolver.resolveBinaryPath(bodyBuilder, instruction.op(), leftVar.type(), rightVar.type());
        if (decision.path() != OperatorResolver.OperatorPath.BUILTIN_EVALUATOR ||
                decision.semanticResultType() == null) {
            return;
        }
        var functionName = operatorResolver.renderBinaryEvaluatorHelperName(
                instruction.op(),
                leftVar.type(),
                rightVar.type(),
                decision.semanticResultType()
        );
        specsByName.putIfAbsent(functionName, new OperatorEvaluatorHelperSpec(
                functionName,
                false,
                operatorResolver.resolveVariantOperatorEnumLiteral(instruction.op()),
                leftVar.type(),
                rightVar.type(),
                decision.semanticResultType(),
                operatorResolver.resolveVariantTypeEnumLiteral(bodyBuilder, leftVar.type()),
                operatorResolver.resolveVariantTypeEnumLiteral(bodyBuilder, rightVar.type())
        ));
    }

    private @NotNull LirVariable resolveOperatorOperandVariable(@NotNull LirFunctionDef func,
                                                                @NotNull String varId,
                                                                @NotNull String role) {
        var variable = func.getVariableById(varId);
        if (variable == null) {
            throw new IllegalStateException(
                    "Operator " + role + " operand variable '" + varId + "' not found in function '" + func.getName() + "'"
            );
        }
        return variable;
    }

    private void collectBindingData(@NotNull List<? extends ClassDef> classDefs) {
        bindingDataSet.clear();
        for (var classDef : classDefs) {
            var ownerName = classDef.getName();
            // Properties getter and setters binding data (instance; owner-specific self).
            for (var propertyDef : classDef.getProperties()) {
                bindingDataSet.add(new BindingData(
                        ownerName,
                        List.of(),
                        propertyDef.getType(),
                        List.of(),
                        false,
                        0
                ));
                bindingDataSet.add(new BindingData(
                        ownerName,
                        List.of(propertyDef.getType()),
                        GdVoidType.VOID,
                        List.of(),
                        false,
                        0
                ));
            }
            // Functions binding data
            for (var functionDef : classDef.getFunctions()) {
                if (functionDef.isHidden() || functionDef.isLambda()) {
                    continue;
                }
                var paramTypes = new ArrayList<GdType>();
                for (var parameterDef : functionDef.getParameters()) {
                    if (parameterDef.getName().equals("self")) {
                        continue;
                    }
                    paramTypes.add(parameterDef.getType());
                }
                bindingDataSet.add(new BindingData(
                        functionDef.isStatic() ? null : ownerName,
                        paramTypes,
                        functionDef.getReturnType(),
                        // Source-function defaults never enter the bind-time Variant channel:
                        // the registration surface keeps default_argument_count == 0 so the VM
                        // cannot pre-fill bind-time constants; runtime completion happens
                        // caller-side (exact route) or in the callee-prologue wrapper
                        // (dynamic route, keyed by defaultSlotCount).
                        List.of(),
                        functionDef.isStatic(),
                        countDefaultSlots(functionDef)
                ));
            }
        }
    }

    /// Internal storage / temporary / return C type for a GdType.
    /// Object values use per-type fat pointer structs; container element object slots stay raw Godot pointers.
    public @NotNull String renderGdTypeInC(@NotNull GdType gdType) {
        return switch (gdType) {
            case GdCompilerType compilerType -> compilerType.getCStorageTypeName();
            case GdContainerType gdContainerType -> switch (gdContainerType) {
                case GdArrayType gdArrayType -> {
                    if (gdArrayType.getValueType() instanceof GdVariantType) {
                        yield "godot_Array";
                    } else {
                        yield "godot_TypedArray(" + renderContainerElementTypeInC(gdArrayType.getValueType()) + ")";
                    }
                }
                case GdDictionaryType gdDictionaryType -> {
                    if (gdContainerType.getKeyType() instanceof GdVariantType && gdContainerType.getValueType() instanceof GdVariantType) {
                        yield "godot_Dictionary";
                    } else {
                        yield "godot_TypedDictionary(" +
                                renderContainerElementTypeInC(gdDictionaryType.getKeyType()) + ", " +
                                renderContainerElementTypeInC(gdDictionaryType.getValueType()) + ")";
                    }
                }
                case GdPackedArrayType gdPackedArrayType -> "godot_" + gdPackedArrayType.getTypeName();
            };
            case GdObjectType gdObjectType -> renderObjectFatPtrStorageType(gdObjectType);
            case GdVoidType _ -> "void";
            default -> "godot_" + gdType.getTypeName();
        };
    }

    /// Internal parameter C type for a GdType.
    /// Object parameters are fat pointer structs passed by value (same shape as storage).
    public @NotNull String renderGdTypeRefInC(@NotNull GdType gdType) {
        return switch (gdType) {
            case GdCompilerType compilerType -> compilerType.getCStorageTypeName() + "*";
            case GdContainerType gdContainerType -> switch (gdContainerType) {
                case GdArrayType gdArrayType -> {
                    if (gdArrayType.getValueType() instanceof GdVariantType) {
                        yield "godot_Array*";
                    } else {
                        yield "godot_TypedArray(" + renderContainerElementTypeInC(gdArrayType.getValueType()) + ")*";
                    }
                }
                case GdDictionaryType gdDictionaryType -> {
                    if (gdContainerType.getKeyType() instanceof GdVariantType && gdContainerType.getValueType() instanceof GdVariantType) {
                        yield "godot_Dictionary*";
                    } else {
                        yield "godot_TypedDictionary(" +
                                renderContainerElementTypeInC(gdDictionaryType.getKeyType()) + ", " +
                                renderContainerElementTypeInC(gdDictionaryType.getValueType()) + ")*";
                    }
                }
                case GdPackedArrayType gdPackedArrayType -> "godot_" + gdPackedArrayType.getTypeName() + "*";
            };
            case GdObjectType gdObjectType -> renderObjectFatPtrParameterType(gdObjectType);
            case GdVoidType _ -> "void*";
            case GdPrimitiveType _ -> "godot_" + gdType.getTypeName();
            default -> "godot_" + gdType.getTypeName() + "*";
        };
    }

    /// Capture-struct typedef name `${Class}_Capture_${func}` shared by `func.ftl` / `entry.h.ftl`.
    public @NotNull String renderLambdaCaptureTypeName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return classDef.getName() + "_Capture_" + function.getName();
    }

    public @NotNull String renderLambdaImplName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return classDef.getName() + "_" + function.getName();
    }

    public @NotNull String renderLambdaCallFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderLambdaImplName(classDef, function) + "_call";
    }

    public @NotNull String renderLambdaFreeFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderLambdaImplName(classDef, function) + "_free";
    }

    public @NotNull String renderLambdaIsValidFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderLambdaImplName(classDef, function) + "_is_valid";
    }

    public @NotNull String renderLambdaGetArgumentCountFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderLambdaImplName(classDef, function) + "_get_argument_count";
    }

    /// Heap / local capture-struct field storage. Object captures stay fat pointers by value.
    public @NotNull String renderLambdaCaptureFieldTypeInC(@NotNull GdType captureType) {
        TypeCheckUtil.requireNonCompilerOnly(captureType, "lambda capture field");
        return renderGdTypeInC(captureType);
    }

    /// First-write copy from an enclosing-function slot into the heap capture block.
    /// `sourceValueExpr` is the already-rendered storage expression of the source variable
    /// (frame-aware: coroutine parameters render as frame fields); `sourceEffectivelyRef`
    /// tells whether that expression is a borrowed pointer (sync parameters) or addressable
    /// storage that needs an explicit `&`.
    public @NotNull String renderLambdaCaptureCopyFromSlot(
            @NotNull GdType captureType,
            @NotNull String sourceValueExpr,
            boolean sourceEffectivelyRef
    ) {
        TypeCheckUtil.requireNonCompilerOnly(captureType, "lambda capture copy");
        if (captureType instanceof GdObjectType || captureType instanceof GdPrimitiveType) {
            return sourceValueExpr;
        }
        var copyFunc = renderCopyAssignFunctionName(captureType);
        if (copyFunc.isEmpty()) {
            return sourceValueExpr;
        }
        var sourcePtr = sourceEffectivelyRef ? sourceValueExpr : "&(" + sourceValueExpr + ")";
        return copyFunc + "(" + sourcePtr + ")";
    }

    /// First-write copy from a value-shaped capture-struct field into the lambda-local slot.
    public @NotNull String renderLambdaCaptureCopyExpr(@NotNull GdType captureType, @NotNull String sourceExpr) {
        TypeCheckUtil.requireNonCompilerOnly(captureType, "lambda capture copy");
        if (captureType instanceof GdObjectType || captureType instanceof GdPrimitiveType) {
            return sourceExpr;
        }
        var copyFunc = renderCopyAssignFunctionName(captureType);
        if (copyFunc.isEmpty()) {
            return sourceExpr;
        }
        return copyFunc + "(&(" + sourceExpr + "))";
    }

    /// `free_func` cleanup for one heap capture field. Object fields release via the fat-pointer
    /// live raw + cached `instance_id`; destroyable builtins use the ordinary destroy helper.
    public @NotNull String renderLambdaCaptureFreeStmt(@NotNull GdType captureType, @NotNull String fieldExpr) {
        return renderManagedStorageFreeStmt(captureType, fieldExpr);
    }

    /// Single-point cleanup formula for one owned managed storage slot (lambda capture field or
    /// static backing variable). Object storage releases through the fat-pointer live raw plus
    /// cached `instance_id` per `RefCountedStatus`; destroyable builtins use the destroy helper;
    /// everything else needs no cleanup.
    private @NotNull String renderManagedStorageFreeStmt(@NotNull GdType storageType, @NotNull String storageExpr) {
        TypeCheckUtil.requireNonCompilerOnly(storageType, "managed storage free");
        if (storageType instanceof GdObjectType objectType) {
            var fatType = renderObjectFatPtrStorageType(objectType);
            var liveExpr = fatType + "_live_object(" + storageExpr + ")";
            return switch (context.classRegistry().getRefCountedStatus(objectType)) {
                case YES -> "release_object(" + liveExpr + ");";
                case UNKNOWN -> "try_release_object(" + liveExpr + ", " + storageExpr + ".instance_id);";
                case NO -> "";
            };
        }
        if (!storageType.isDestroyable()) {
            return "";
        }
        return renderDestroyFunctionName(storageType) + "(&(" + storageExpr + "));";
    }

    // ==== Coroutine state class codegen (frontend_await_implementation.md §5-§7) ====
    //
    // Naming formula frozen in `gdcc_facing_class_name_contract.md` §1.3:
    // `_gdcc_coro_state_<canonicalClass>__coro__<func>`. The backend owns this spelling directly
    // (frontend constants guard the source side; importing them here would invert the layering).

    /// True when at least one collected class contains an `is_coroutine="true"` function; lets
    /// the templates keep sync-only modules byte-identical (no coroutine sections at all).
    public boolean hasCoroutineFunctions() {
        return hasCoroutineFunctions;
    }

    /// Canonical (and C-identifier-safe) hidden state class name for one coroutine function.
    public @NotNull String renderCoroStateClassName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return "_gdcc_coro_state_" + classDef.getName() + "__coro__" + function.getName();
    }

    /// Minicoro body function: `void <Class>_<func>__coro_body(mco_coro *_co)`.
    public @NotNull String renderCoroBodyFunctionName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return classDef.getName() + "_" + function.getName() + "__coro_body";
    }

    /// Internal coroutine-start thunk: creates the state object, fills the frame, creates and
    /// resumes the minicoro, and always hands back the OWNED state object reference.
    public @NotNull String renderCoroStartThunkName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return classDef.getName() + "_" + function.getName() + "__coro_start";
    }

    /// Accessor moving the typed return slot out for the engine-boundary sync fast path
    /// (the slot is left moved-from; only called when `done` is already published).
    public @NotNull String renderCoroMoveResultFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderCoroStateClassName(classDef, function) + "__move_result";
    }

    public @NotNull String renderCoroStateDescName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderCoroStateClassName(classDef, function) + "_desc";
    }

    public @NotNull String renderCoroPackResultFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderCoroStateClassName(classDef, function) + "_pack_result";
    }

    public @NotNull String renderCoroCopyRetSlotFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderCoroStateClassName(classDef, function) + "_copy_ret_slot";
    }

    public @NotNull String renderCoroDestroyRetSlotFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderCoroStateClassName(classDef, function) + "_destroy_ret_slot";
    }

    public @NotNull String renderCoroEmitCompletedFuncName(@NotNull ClassDef classDef, @NotNull FunctionDef function) {
        return renderCoroStateClassName(classDef, function) + "_emit_completed";
    }

    /// `completed(result)` signal metadata: the engine-internal coroutine signal shape - one
    /// `Variant` parameter, which automatically carries `PROPERTY_USAGE_NIL_IS_VARIANT`.
    public @NotNull BoundMetadata renderCoroCompletedSignalMetadata() {
        return renderSignalParameterMetadata(GdVariantType.VARIANT);
    }

    // Frame spellings shared with `CBodyBuilder` via `CCoroutineFrameContext` (single source).
    public @NotNull String renderCoroParamFieldPrefix() {
        return CCoroutineFrameContext.PARAM_FIELD_PREFIX;
    }

    public @NotNull String renderCoroCaptureFieldPrefix() {
        return CCoroutineFrameContext.CAPTURE_FIELD_PREFIX;
    }

    public @NotNull String renderCoroHeaderField() {
        return CCoroutineFrameContext.HEADER_FIELD;
    }

    public @NotNull String renderCoroRetField() {
        return CCoroutineFrameContext.RET_FIELD;
    }

    public @NotNull String renderCoroRetInitializedField() {
        return CCoroutineFrameContext.RET_INITIALIZED_FIELD;
    }

    public @NotNull String renderCoroFrameLocal() {
        return CCoroutineFrameContext.FRAME_LOCAL;
    }

    public @NotNull String renderCoroCoParam() {
        return CCoroutineFrameContext.CO_PARAM;
    }

    /// Retain one borrowed object value as it becomes an owning frame field copy
    /// (mirror of `renderLambdaCaptureFreeStmt` in the retain direction).
    public @NotNull String renderObjectRetainStmt(@NotNull GdObjectType objectType, @NotNull String fieldExpr) {
        var fatType = renderObjectFatPtrStorageType(objectType);
        var liveExpr = fatType + "_live_object(" + fieldExpr + ")";
        return switch (context.classRegistry().getRefCountedStatus(objectType)) {
            case YES -> "own_object(" + liveExpr + ");";
            case UNKNOWN -> "try_own_object(" + liveExpr + ", " + fieldExpr + ".instance_id);";
            case NO -> "";
        };
    }

    /// Start-thunk fill of one typed parameter frame field from the borrowed thunk argument
    /// (ownership spec §3.10: borrowed parameters retain / destroyable values copy; the frame
    /// field is the only owning storage and starts uninitialized, so this is a first write).
    public @NotNull String renderCoroParamFillStmt(@NotNull GdType paramType,
                                                   @NotNull String fieldExpr,
                                                   @NotNull String borrowedArgExpr) {
        TypeCheckUtil.requireNonCompilerOnly(paramType, "coroutine parameter field");
        if (paramType instanceof GdPrimitiveType) {
            return fieldExpr + " = " + borrowedArgExpr + ";";
        }
        if (paramType instanceof GdObjectType objectType) {
            var retainStmt = renderObjectRetainStmt(objectType, fieldExpr);
            return fieldExpr + " = " + borrowedArgExpr + ";"
                    + (retainStmt.isEmpty() ? "" : " " + retainStmt);
        }
        // Value-semantic thunk parameters are already passed as storage pointers (ref shape),
        // so the borrowed expression feeds the copy constructor directly.
        return fieldExpr + " = " + renderCopyAssignFunctionName(paramType) + "(" + borrowedArgExpr + ");";
    }

    /// Start-thunk fill of one typed capture frame field of a coroutine lambda from the borrowed
    /// `_capture->name` block field (per-call copy into fresh owning frame storage;
    /// the capture block itself stays owned by the Callable userdata). Unlike
    /// `renderCoroParamFillStmt`, capture block fields are plain *values*, not storage pointers,
    /// so value-semantic copies must take the field address — delegated to
    /// `renderLambdaCaptureCopyExpr`, which copy-constructs `&(source)`; primitives assign
    /// directly and object fields retain the borrowed value after assignment.
    public @NotNull String renderCoroCaptureFillStmt(@NotNull GdType captureType,
                                                     @NotNull String fieldExpr,
                                                     @NotNull String sourceFieldExpr) {
        TypeCheckUtil.requireNonCompilerOnly(captureType, "coroutine capture field");
        if (captureType instanceof GdObjectType objectType) {
            var retainStmt = renderObjectRetainStmt(objectType, fieldExpr);
            return fieldExpr + " = " + sourceFieldExpr + ";"
                    + (retainStmt.isEmpty() ? "" : " " + retainStmt);
        }
        return fieldExpr + " = " + renderLambdaCaptureCopyExpr(captureType, sourceFieldExpr) + ";";
    }

    /// Statements of the generated `copy_ret_slot` desc callback for non-void coroutines:
    /// slot-write into the awaiter's initialized typed result slot (release/destroy the old
    /// value first - done fast path and waiter resume may overwrite a non-default slot), then
    /// copy from the callee's preserved typed return slot. `outSlotExpr` is the raw `void *`.
    public @NotNull String renderCoroCopyRetStmt(@NotNull GdType returnType,
                                                 @NotNull String retFieldExpr,
                                                 @NotNull String outSlotExpr) {
        TypeCheckUtil.requireNonCompilerOnly(returnType, "coroutine copy_ret_slot");
        if (returnType instanceof GdPrimitiveType) {
            return "*(" + renderGdTypeInC(returnType) + " *)" + outSlotExpr + " = " + retFieldExpr + ";";
        }
        if (returnType instanceof GdObjectType objectType) {
            var fatType = renderObjectFatPtrStorageType(objectType);
            var outTyped = "(*(" + fatType + " *)" + outSlotExpr + ")";
            var retainNewStmt = renderObjectRetainStmt(objectType, outTyped);
            var releaseOldStmt = renderLambdaCaptureFreeStmt(objectType, "coro_out_old");
            // Alias-safe slot-write discipline (same as CBodyBuilder.emitObjectSlotWrite):
            // capture the old value first, retain the new one, and only then release the old.
            return fatType + " coro_out_old = " + outTyped + ";\n"
                    + outTyped + " = " + retFieldExpr + ";"
                    + (retainNewStmt.isEmpty() ? "" : "\n" + retainNewStmt)
                    + (releaseOldStmt.isEmpty() ? "" : "\n" + releaseOldStmt);
        }
        var cType = renderGdTypeInC(returnType);
        var destroyOldStmt = returnType.isDestroyable()
                ? renderDestroyFunctionName(returnType) + "((" + cType + " *)" + outSlotExpr + ");\n"
                : "";
        return destroyOldStmt
                + "*(" + cType + " *)" + outSlotExpr + " = "
                + renderCopyAssignFunctionName(returnType) + "(&(" + retFieldExpr + "));";
    }

    public @NotNull String renderValueRef(@NotNull GdType gdType, @NotNull String v) {
        return switch (gdType) {
            // Fat pointer structs and primitives are value-shaped; other value types pass storage addresses.
            case GdObjectType _, GdPrimitiveType _ -> v;
            default -> "&" + v;
        };
    }

    /// Godot TypedArray/TypedDictionary element slots remain raw object pointers, not fat structs.
    private @NotNull String renderContainerElementTypeInC(@NotNull GdType elementType) {
        if (elementType instanceof GdObjectType objectType) {
            return renderObjectBarePointerType(objectType);
        }
        return renderGdTypeInC(elementType);
    }

    /// Bare raw pointer spelling used by Godot container macros (`godot_Node*`, `Player*`).
    public @NotNull String renderObjectBarePointerType(@NotNull GdObjectType objectType) {
        var pointerCType = renderObjectRawPointerType(objectType);
        if (pointerCType.endsWith(" *")) {
            return pointerCType.substring(0, pointerCType.length() - 2) + "*";
        }
        return pointerCType;
    }

    /// Role-specific object renderers for fat-pointer storage vs raw ABI boundaries.
    /// Internal storage/parameter/return use fat pointers; ptrcall slots and Godot receivers stay raw.

    public @NotNull ObjectFatPtrSpec requireObjectFatPtrSpec(@NotNull GdObjectType objectType, @NotNull String surface) {
        return ObjectFatPtrSpec.forObjectType(context.classRegistry(), objectType, surface);
    }

    public @NotNull String renderObjectFatPtrStorageType(@NotNull GdObjectType objectType) {
        return requireObjectFatPtrSpec(objectType, "internal storage type").fatPtrTypeName();
    }

    public @NotNull String renderObjectFatPtrParameterType(@NotNull GdObjectType objectType) {
        return requireObjectFatPtrSpec(objectType, "internal parameter type").fatPtrTypeName();
    }

    public @NotNull String renderObjectFatPtrStorageAddressType(@NotNull GdObjectType objectType) {
        return requireObjectFatPtrSpec(objectType, "internal storage address type").fatPtrTypeName() + " *";
    }

    public @NotNull String renderObjectRawPointerType(@NotNull GdObjectType objectType) {
        return requireObjectFatPtrSpec(objectType, "raw ABI pointer slot").pointerCType();
    }

    public @NotNull String renderObjectReceiverType(@NotNull GdObjectType objectType) {
        // Validate the static object type, but the Godot receiver slot is always the raw ABI pointer.
        requireObjectFatPtrSpec(objectType, "Godot receiver");
        return "GDExtensionObjectPtr";
    }

    /// Collects deterministic upcast helper specs among already-collected fat pointer types.
    /// Only assignable source -> target pairs are emitted; same-type copies are plain struct copies.
    public @NotNull List<ObjectFatPtrUpcastSpec> collectObjectFatPtrUpcastSpecs(@NotNull List<ObjectFatPtrSpec> specs) {
        var upcasts = new ArrayList<ObjectFatPtrUpcastSpec>();
        for (var source : specs) {
            for (var target : specs) {
                if (source.fatPtrTypeName().equals(target.fatPtrTypeName())) {
                    continue;
                }
                if (!context.classRegistry().checkAssignable(source.objectType(), target.objectType())) {
                    continue;
                }
                upcasts.add(ObjectFatPtrUpcastSpec.forPair(source, target));
            }
        }
        upcasts.sort(Comparator.comparing(ObjectFatPtrUpcastSpec::helperName));
        return List.copyOf(upcasts);
    }

    /// Engine bind accessor symbols must stay backend-owned and collision-free relative to public Godot wrappers.
    /// Static and vararg markers remain explicit because later helper surfaces diverge even when bind lookup
    /// still uses the same owner/method/hash triple.
    public @NotNull String renderEngineMethodBindAccessorName(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        return requireEngineMethodSymbolKey(resolved).renderBindAccessorName();
    }

    /// The accessor tries the primary hash first, then compatibility hashes in declared order.
    /// Duplicate or zero hashes are skipped so the generated skeleton stays stable and reviewable.
    public @NotNull List<Long> collectEngineMethodBindLookupHashes(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        var bindSpec = Objects.requireNonNull(
                resolved.engineMethodBindSpec(),
                "Exact engine method bind metadata is required to render lookup hashes"
        );
        var hashes = new LinkedHashSet<Long>();
        hashes.add(bindSpec.hash());
        for (var hashCompatibility : bindSpec.hashCompatibility()) {
            if (hashCompatibility != 0L) {
                hashes.add(hashCompatibility);
            }
        }
        return List.copyOf(hashes);
    }

    /// Direct exact-engine helpers must stay in a backend-owned namespace so later route switches
    /// never collide with public Godot wrappers.
    public @NotNull String renderEngineMethodCallHelperName(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        return requireEngineMethodSymbolKey(resolved).renderCallHelperName();
    }

    /// Helper parameters intentionally mirror the current callable surface:
    /// - primitive/object slots stay value-shaped
    /// - value-semantic wrappers stay storage-pointer shaped
    /// - enum/bitfield keep normalized helper params and let the helper materialize raw slots locally
    public @NotNull List<EngineMethodHelperParam> collectEngineMethodHelperParameters(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        var params = new ArrayList<EngineMethodHelperParam>(resolved.parameters().size());
        for (var i = 0; i < resolved.parameters().size(); i++) {
            var parameter = resolved.parameters().get(i);
            var slotMode = parameter.engineHelperSlotMode();
            var cType = switch (slotMode) {
                case STORAGE_POINTER -> renderGdTypeRefInC(parameter.type());
                case VALUE_ADDRESS, LOCAL_VALUE_SLOT_ADDRESS -> renderGdTypeInC(parameter.type());
            };
            params.add(new EngineMethodHelperParam(
                    "arg" + i,
                    parameter.type(),
                    cType,
                    slotMode,
                    parameter.engineHelperLocalSlotCType()
            ));
        }
        return List.copyOf(params);
    }

    /// Ptrcall consumes addresses of argument storage slots.
    /// - object fat params first materialize a raw local, then pass `&argN_raw`
    /// - other value-shaped params pass `&arg`
    /// - storage-pointer params pass the helper argument directly
    /// - enum/bitfield params first point at a helper-local raw slot
    public @NotNull String renderEngineMethodPtrcallSlotExpr(@NotNull EngineMethodHelperParam param) {
        if (checkEngineMethodHelperObjectParam(param)) {
            return "&" + renderEngineMethodHelperObjectRawSlotName(param);
        }
        return switch (param.slotMode()) {
            case VALUE_ADDRESS -> "&" + param.name();
            case STORAGE_POINTER -> param.name();
            case LOCAL_VALUE_SLOT_ADDRESS -> "&" + renderEngineMethodHelperLocalSlotName(param);
        };
    }

    /// Helper-local pack sites always consume the normalized helper surface.
    public @NotNull String renderEngineMethodHelperValueExpr(@NotNull EngineMethodHelperParam param) {
        return param.name();
    }

    public boolean checkEngineMethodHelperRequiresLocalValueSlot(@NotNull EngineMethodHelperParam param) {
        return param.requiresLocalValueSlot();
    }

    public @NotNull String renderEngineMethodHelperLocalSlotDecl(@NotNull EngineMethodHelperParam param) {
        if (!param.requiresLocalValueSlot() || param.slotCType() == null || param.slotCType().isBlank()) {
            throw new IllegalArgumentException("Engine helper parameter does not require a local slot: " + param.name());
        }
        return "const " + param.slotCType() + " " + renderEngineMethodHelperLocalSlotName(param) +
                " = (" + param.slotCType() + ")" + param.name() + ";";
    }

    /// Exact engine helper public surface uses owner fat `self`.
    public @NotNull String renderEngineMethodHelperSelfType(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        if (!(resolved.ownerType() instanceof GdObjectType ownerObjectType)) {
            throw new IllegalArgumentException(
                    "Exact engine helper self type requires object owner, got '" +
                            resolved.ownerType().getTypeName() + "'"
            );
        }
        return renderObjectFatPtrStorageType(ownerObjectType);
    }

    /// Materialize validated raw Godot receiver for ptrcall/call inside the helper body.
    public @NotNull String renderEngineMethodHelperSelfLiveExpr(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        return renderEngineMethodHelperSelfType(resolved) + "_live_object(self)";
    }

    public boolean checkEngineMethodHelperObjectParam(@NotNull EngineMethodHelperParam param) {
        return param.type() instanceof GdObjectType;
    }

    public @NotNull String renderEngineMethodHelperObjectRawSlotName(@NotNull EngineMethodHelperParam param) {
        if (!checkEngineMethodHelperObjectParam(param)) {
            throw new IllegalArgumentException("Engine helper object raw slot requires object param: " + param.name());
        }
        return param.name() + "_raw";
    }

    /// Object fixed args enter as fat pointers; ptrcall needs a local raw slot address.
    public @NotNull String renderEngineMethodHelperObjectRawSlotDecl(@NotNull EngineMethodHelperParam param) {
        if (!(param.type() instanceof GdObjectType objectType)) {
            throw new IllegalArgumentException("Engine helper object raw slot requires object param: " + param.name());
        }
        var fatType = renderObjectFatPtrStorageType(objectType);
        return "GDExtensionObjectPtr " + renderEngineMethodHelperObjectRawSlotName(param) +
                " = " + fatType + "_live_object(" + param.name() + ");";
    }

    public boolean checkEngineMethodHelperObjectReturn(@NotNull GdType returnType) {
        return returnType instanceof GdObjectType;
    }

    /// Wrap a successful ptrcall raw object return into the helper's fat return surface.
    public @NotNull String renderEngineMethodHelperObjectFromRaw(
            @NotNull GdType returnType,
            @NotNull String rawExpr
    ) {
        if (!(returnType instanceof GdObjectType objectType)) {
            throw new IllegalArgumentException(
                    "Engine helper object from_raw requires object return, got '" + returnType.getTypeName() + "'"
            );
        }
        return renderObjectFatPtrStorageType(objectType) + "_from_raw(" + rawExpr + ")";
    }

    /// Before destroying the temporary return Variant, establish the caller-owned object return.
    /// Empty for non-object / definite non-RefCounted; retain for YES / try_own for UNKNOWN.
    ///
    /// Ownership contract (must stay balanced):
    /// - The vararg dynamic-call path returns the object through a temporary Variant; destroying that
    ///   Variant releases the reference it holds. This retain transfers a strong reference to the
    ///   returned fat pointer so the helper yields an OWNED object result.
    /// - The helper's caller must consume that OWNED result exactly once: slot write
    ///   (`emitObjectSlotWrite(..., OWNED)`), discard (`emitDiscardedCall` immediate release), or a
    ///   public wrapper return (pack via `to_variant` then
    ///   `renderCallWrapperOwnedObjectReturnConsumeStmt` releases the internal OWNED `r`).
    /// - Treating the helper return as BORROWED (retaining again, or never releasing) breaks the
    ///   balance and leaks a RefCounted reference.
    public @NotNull String renderEngineMethodHelperVarargObjectReturnOwnStmt(
            @NotNull GdType returnType,
            @NotNull String resultExpr
    ) {
        if (!(returnType instanceof GdObjectType objectType)) {
            return "";
        }
        var fatType = renderObjectFatPtrStorageType(objectType);
        var liveExpr = fatType + "_live_object(" + resultExpr + ")";
        return switch (context.classRegistry().getRefCountedStatus(objectType)) {
            case YES -> "own_object(" + liveExpr + ");";
            case UNKNOWN -> "try_own_object(" + liveExpr + ", " + resultExpr + ".instance_id);";
            case NO -> "";
        };
    }

    /// Consume the internal OWNED object return carrier `r` after call-wrapper Variant packing.
    ///
    /// Packing (`to_variant` + `variant_new_copy` + `Variant_destroy`) establishes Variant ownership
    /// but does not consume the function's OWNED strong reference on `r`. Release that reference
    /// here so ownership transfers net-zero into `r_return`. Empty for non-object / non-RefCounted.
    /// Must only be used on the return carrier — never on BORROWED argument locals.
    public @NotNull String renderCallWrapperOwnedObjectReturnConsumeStmt(
            @NotNull GdType returnType,
            @NotNull String resultExpr
    ) {
        if (!(returnType instanceof GdObjectType objectType)) {
            return "";
        }
        var fatType = renderObjectFatPtrStorageType(objectType);
        var liveExpr = fatType + "_live_object(" + resultExpr + ")";
        return switch (context.classRegistry().getRefCountedStatus(objectType)) {
            case YES -> "release_object(" + liveExpr + ");";
            case UNKNOWN -> "try_release_object(" + liveExpr + ", " + resultExpr + ".instance_id);";
            case NO -> "";
        };
    }

    public @NotNull String renderFuncBindName(@NotNull BindingData bindingData) {
        var shapeName = renderFuncBindName(
                bindingData.returnType(),
                bindingData.paramTypes(),
                bindingData.defaultSlotCount(),
                bindingData.staticMethod()
        );
        // Instance wrappers are owner-specific so self fat type cannot be shared by ABI shape alone.
        if (bindingData.isInstanceMethod()) {
            return "_" + GodotBindingSupport.cIdentifier(Objects.requireNonNull(bindingData.ownerClassName())) + shapeName;
        }
        return shapeName;
    }

    /// Owner-aware bind name for virtual dispatch (matches BindingData instance naming).
    public @NotNull String renderFuncBindName(@NotNull ClassDef classDef, @NotNull FunctionDef functionDef) {
        return renderFuncBindName(toBindingData(classDef, functionDef));
    }

    /// Shared FunctionDef -> BindingData projection: the bind-time Variant channel stays empty
    /// while the default-slot count feeds the wrapper shape.
    private @NotNull BindingData toBindingData(@NotNull ClassDef classDef, @NotNull FunctionDef functionDef) {
        var paramTypes = new ArrayList<GdType>();
        for (var parameterDef : functionDef.getParameters()) {
            if (parameterDef.getName().equals("self")) {
                continue;
            }
            paramTypes.add(parameterDef.getType());
        }
        return new BindingData(
                functionDef.isStatic() ? null : classDef.getName(),
                paramTypes,
                functionDef.getReturnType(),
                List.of(),
                functionDef.isStatic(),
                countDefaultSlots(functionDef)
        );
    }

    /// Per-shape default-fill userdata struct name. The typedef (entry.h), the wrapper
    /// unwrap (entry.h) and the per-method instance (entry.c) all derive it from the same
    /// bind-name encoding, so the three sites can never disagree on the spelling.
    public @NotNull String renderDefaultUserdataTypeName(@NotNull BindingData bindingData) {
        if (bindingData.defaultSlotCount() <= 0) {
            throw new IllegalArgumentException("default userdata type requires defaultSlotCount > 0");
        }
        return "gdcc_default_ud" + renderFuncBindName(bindingData);
    }

    public @NotNull String renderDefaultUserdataTypeName(@NotNull ClassDef classDef, @NotNull FunctionDef functionDef) {
        return renderDefaultUserdataTypeName(toBindingData(classDef, functionDef));
    }

    /// Per-method default userdata INSTANCE name (entry.c, file scope). The `$` separator cannot
    /// appear in GDScript identifiers, so a user function named `<method>_default_ud` can never
    /// collide with it; the symbol is still registered with the file-scope conflict check as a
    /// defensive net.
    public @NotNull String renderDefaultUserdataInstanceName(@NotNull ClassDef classDef, @NotNull FunctionDef functionDef) {
        if (countDefaultSlots(functionDef) <= 0) {
            throw new IllegalArgumentException(
                    "default userdata instance requires a function with at least one default slot: "
                            + classDef.getName() + "." + functionDef.getName()
            );
        }
        return classDef.getName() + "_" + functionDef.getName() + "$default_ud";
    }

    /// Construct owner fat self from Godot `p_instance` (GDCC wrapper pointer).
    public @NotNull String renderRegisteredMethodSelfFatExpr(@NotNull BindingData bindingData) {
        if (!bindingData.isInstanceMethod()) {
            throw new IllegalArgumentException("static BindingData has no self fat expression");
        }
        var ownerName = Objects.requireNonNull(bindingData.ownerClassName());
        var ownerType = new GdObjectType(ownerName);
        var fatType = renderObjectFatPtrStorageType(ownerType);
        var objectPtrHelper = ownerName + "_object_ptr";
        return fatType + "_from_raw(" + objectPtrHelper + "((" + ownerName + "*)p_instance))";
    }

    public @NotNull String renderRegisteredMethodSelfFatType(@NotNull BindingData bindingData) {
        if (!bindingData.isInstanceMethod()) {
            throw new IllegalArgumentException("static BindingData has no self fat type");
        }
        return renderObjectFatPtrStorageType(new GdObjectType(Objects.requireNonNull(bindingData.ownerClassName())));
    }

    public boolean checkObjectType(@NotNull GdType type) {
        return type instanceof GdObjectType;
    }

    /// Ptrcall object arg: raw Godot pointer slot -> borrowed fat pointer.
    public @NotNull String renderPtrcallObjectArgDecl(@NotNull GdType paramType, int index) {
        if (!(paramType instanceof GdObjectType objectType)) {
            throw new IllegalArgumentException("ptrcall object arg requires object type");
        }
        var fatType = renderObjectFatPtrStorageType(objectType);
        return fatType + " arg" + index + " = " + fatType + "_from_raw(*((const GDExtensionObjectPtr *)p_args[" + index + "]));";
    }

    /// Non-object ptrcall argument expression (storage pointer / value slot as before).
    public @NotNull String renderPtrcallNonObjectArgExpr(@NotNull GdType paramType, int index) {
        if (paramType instanceof GdObjectType) {
            throw new IllegalArgumentException("use renderPtrcallObjectArgDecl for object args");
        }
        return renderValueRef(paramType, "(*((" + renderGdTypeInC(paramType) + "*)p_args[" + index + "]))");
    }

    /// Ptrcall object return: owned fat -> validated raw transfer into `r_return` (no extra release).
    public @NotNull String renderPtrcallObjectReturnWrite(@NotNull GdType returnType, @NotNull String resultExpr) {
        if (!(returnType instanceof GdObjectType objectType)) {
            throw new IllegalArgumentException("ptrcall object return write requires object type");
        }
        var fatType = renderObjectFatPtrStorageType(objectType);
        return "*((GDExtensionObjectPtr *)r_return) = " + fatType + "_live_object(" + resultExpr + ");";
    }

    private @NotNull EngineMethodSymbolKey requireEngineMethodSymbolKey(
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved
    ) {
        return Objects.requireNonNull(
                EngineMethodSymbolKey.from(resolved),
                "Exact engine helper symbol key requires resolved exact engine metadata"
        );
    }

    private @NotNull String renderEngineMethodHelperLocalSlotName(@NotNull EngineMethodHelperParam param) {
        return param.name() + "_slot";
    }

    /// Shape-only bind name (no owner). Instance methods must use {@link #renderFuncBindName(ClassDef, FunctionDef)}.
    /// Restricted to static functions so an owner-less instance bind name can never be generated.
    public @NotNull String renderFuncBindName(@NotNull FunctionDef functionDef) {
        if (!functionDef.isStatic()) {
            throw new IllegalArgumentException(
                    "Instance FunctionDef bind names require owner class; use renderFuncBindName(ClassDef, FunctionDef)"
            );
        }
        var paramTypes = new ArrayList<GdType>();
        for (var parameterDef : functionDef.getParameters()) {
            if (parameterDef.getName().equals("self")) {
                continue;
            }
            paramTypes.add(parameterDef.getType());
        }
        // Same isolation as collectBindingData: only the slot count feeds the wrapper shape.
        return renderFuncBindName(functionDef.getReturnType(), paramTypes, countDefaultSlots(functionDef), true);
    }

    /// The single counter every bind-name/collection path must use: trailing parameters (the
    /// synthetic `self` excluded) that carry a source `defaultValueFunc`. Frontend semantics
    /// guarantee defaults form a contiguous suffix, so the count alone locates the fill range.
    public int countDefaultSlots(@NotNull FunctionDef functionDef) {
        var count = 0;
        for (var parameterDef : functionDef.getParameters()) {
            if (parameterDef.getName().equals("self")) {
                continue;
            }
            if (parameterDef.getDefaultValueFunc() != null) {
                count++;
            }
        }
        return count;
    }

    public @NotNull String renderGdTypeName(@NotNull GdType gdType) {
        return switch (gdType) {
            case GdContainerType gdContainerType -> switch (gdContainerType) {
                case GdArrayType _ -> "Array";
                case GdDictionaryType _ -> "Dictionary";
                case GdPackedArrayType gdPackedArrayType -> gdPackedArrayType.getTypeName();
            };
            case GdVoidType _ -> "void";
            default -> gdType.getTypeName();
        };
    }

    /// Thin wrapper around the shared scope-layer parser for extension metadata normalization.
    ///
    /// The shared parser now understands exported families such as `typeddictionary::K;V`, but the
    /// backend still owns typed-dictionary outward ABI concerns like hint emission and runtime guards.
    public @NotNull GdType parseExtensionType(@Nullable String rawTypeName,
                                              @NotNull String typeUseSite) {
        return ScopeTypeParsers.parseExtensionTypeMetadata(rawTypeName, typeUseSite, context.classRegistry());
    }

    public @NotNull String renderFuncBindName(@Nullable GdType returnType,
                                              @NotNull List<GdType> paramTypes,
                                              int defaultSlotCount,
                                              boolean staticFunction) {
        var sb = new StringBuilder("_");
        sb.append(paramTypes.size()).append("_arg_");
        for (var paramType : paramTypes) {
            sb.append(renderGdTypeName(paramType)).append("_");
        }
        if (returnType != null && !(returnType instanceof GdVoidType)) {
            sb.append("ret_").append(renderGdTypeName(returnType));
        } else {
            sb.append("no_ret");
        }
        // `_K_defslot` is the only default-aware bind-name segment; the legacy `_N_default_`
        // Variant channel is never fed for source functions.
        if (defaultSlotCount > 0) {
            sb.append("_").append(defaultSlotCount).append("_defslot");
        }
        if (staticFunction) {
            sb.append("_static");
        }
        return sb.toString();
    }

    public @NotNull String renderUnpackFunctionName(@NotNull GdType type) {
        if (type instanceof GdCompilerType) {
            throw new IllegalArgumentException("compiler-only type leaked into Variant unpack: " + type.getTypeName());
        }
        if (type instanceof GdObjectType objectType) {
            // Object unpack materializes a fat pointer that preserves the Variant's instance ID.
            return renderObjectFatPtrStorageType(objectType) + "_from_variant";
        } else {
            return "godot_new_" + renderGdTypeName(type) + "_with_Variant";
        }
    }

    /// Render the inbound `call_func` runtime gate for one non-Variant wrapper argument.
    ///
    /// The wrapper keeps exact runtime checks by default. Any non-exact inbound rule must stay
    /// aligned with the frontend ordinary-boundary matrix and must be paired with wrapper-local
    /// materialization in `renderCallWrapperUnpackExpr(...)` (which may be the default unpack
    /// path when the underlying C unpack function already handles the widened inbound natively).
    public @NotNull String renderCallWrapperVariantTypeGate(@NotNull GdType paramType,
                                                            @NotNull String typeExpr) {
        TypeCheckUtil.requireNonCompilerOnly(paramType, "call wrapper type gate");
        switch (paramType) {
            case GdFloatType _ -> {
                return "(" + typeExpr + " == GDEXTENSION_VARIANT_TYPE_FLOAT || "
                        + typeExpr + " == GDEXTENSION_VARIANT_TYPE_INT)";
            }
            case GdFloatVectorType vectorType -> {
                vectorType.ensureValidBuiltinDim();
                var targetType = requireBoundMetadataType(paramType, "call wrapper type gate");
                var vectorSourceType = new GdIntVectorType(vectorType.getDimension()).getGdExtensionType();
                return "(" + typeExpr + " == GDEXTENSION_VARIANT_TYPE_" + targetType.name() + " || "
                        + typeExpr + " == GDEXTENSION_VARIANT_TYPE_" + vectorSourceType.name() + ")";
            }
            case GdStringNameType _ -> {
                return "(" + typeExpr + " == GDEXTENSION_VARIANT_TYPE_STRING_NAME || "
                        + typeExpr + " == GDEXTENSION_VARIANT_TYPE_STRING)";
            }
            case GdStringType _ -> {
                return "(" + typeExpr + " == GDEXTENSION_VARIANT_TYPE_STRING || "
                        + typeExpr + " == GDEXTENSION_VARIANT_TYPE_STRING_NAME)";
            }
            case GdObjectType _ -> {
                // Godot's Variant::can_convert_strict allows NIL -> OBJECT; null fat pointer
                // materialization is already handled inside <Type>_fat_ptr_from_variant.
                return "(" + typeExpr + " == GDEXTENSION_VARIANT_TYPE_OBJECT || "
                        + typeExpr + " == GDEXTENSION_VARIANT_TYPE_NIL)";
            }
            default -> {
                var targetType = requireBoundMetadataType(paramType, "call wrapper type gate");
                return "(" + typeExpr + " == GDEXTENSION_VARIANT_TYPE_" + targetType.name() + ")";
            }
        }
    }

    /// Render the inbound `call_func` local materialization expression for one wrapper argument.
    ///
    /// This is deliberately separate from `renderUnpackFunctionName(...)`: only
    /// Godot-to-GDExtension method calls get wrapper-local inbound widening for selected typed params.
    /// The generated wrapper is responsible for running the runtime gate before evaluating this
    /// expression; wrapper-only helpers are materializers, not validators.
    ///
    /// @param typeExpr cached runtime type expression from the preceding wrapper gate. When this is null, the
    ///                 generated expression repeats `godot_variant_get_type(...)` against `variantPtrExpr`.
    public @NotNull String renderCallWrapperUnpackExpr(@NotNull GdType paramType,
                                                       @NotNull String variantPtrExpr,
                                                       @Nullable String typeExpr) {
        TypeCheckUtil.requireNonCompilerOnly(paramType, "call wrapper unpack expression");
        switch (paramType) {
            case GdFloatType _ -> {
                var actualTypeExpr = typeExpr != null ? typeExpr : "godot_variant_get_type(" + variantPtrExpr + ")";
                return actualTypeExpr + " == GDEXTENSION_VARIANT_TYPE_INT"
                        + " ? (godot_float)godot_new_int_with_Variant(" + variantPtrExpr + ")"
                        + " : godot_new_float_with_Variant(" + variantPtrExpr + ")";
            }
            case GdFloatVectorType vectorType -> {
                vectorType.ensureValidBuiltinDim();
                var actualTypeExpr = typeExpr != null ? typeExpr : "godot_variant_get_type(" + variantPtrExpr + ")";
                return "gdcc_new_" + renderGdTypeName(paramType) + "_from_call_arg_variant(" +
                        variantPtrExpr + ", " + actualTypeExpr + ")";
            }
            case GdStringNameType _ -> {
                var actualTypeExpr = typeExpr != null ? typeExpr : "godot_variant_get_type(" + variantPtrExpr + ")";
                return "gdcc_new_StringName_from_call_arg_variant(" + variantPtrExpr + ", " + actualTypeExpr + ")";
            }
            case GdStringType _ -> {
                var actualTypeExpr = typeExpr != null ? typeExpr : "godot_variant_get_type(" + variantPtrExpr + ")";
                return "gdcc_new_String_from_call_arg_variant(" + variantPtrExpr + ", " + actualTypeExpr + ")";
            }
            default -> {
                return renderUnpackFunctionName(paramType) + "(" + variantPtrExpr + ")";
            }
        }
    }

    /// Unpack without a cached gate type expression: re-evaluates `godot_variant_get_type`.
    /// Used by the default-slot fill branch, which runs after the (argument-count-conditional)
    /// gate block that scoped the cached type variable.
    public @NotNull String renderCallWrapperUnpackExpr(@NotNull GdType paramType,
                                                       @NotNull String variantPtrExpr) {
        return renderCallWrapperUnpackExpr(paramType, variantPtrExpr, null);
    }

    /// Ordinary pack helpers are the unary `godot_new_Variant_with_<Type>` family.
    /// `Nil` is excluded because it uses the dedicated nullary `godot_new_Variant_nil()`.
    public @NotNull String renderPackFunctionName(@NotNull GdType type) {
        switch (type) {
            case GdCompilerType _ ->
                    throw new IllegalArgumentException("compiler-only type leaked into Variant pack: " + type.getTypeName());
            case GdNilType _ ->
                    throw new IllegalArgumentException("Nil uses dedicated godot_new_Variant_nil() materialization");
            case GdObjectType objectType -> {
                // Fat-pointer pack uses the per-type helper so freed IDs degrade through live_object.
                return renderObjectFatPtrStorageType(objectType) + "_to_variant";
            }
            default -> {
                return "godot_new_Variant_with_" + renderGdTypeName(type);
            }
        }
    }

    /// Render the prepare-block init helper for compiler-only storage.
    ///
    /// This is intentionally narrower than ordinary constructor/default-value rendering:
    /// only `GdCompilerType` may use this helper-name path, while regular Godot builtins keep
    /// their existing `Construct*` / literal initialization flow in `CCodegen`.
    public @NotNull String renderCompilerOnlyInitFunctionName(@NotNull GdCompilerType compilerType) {
        compilerType.validateCStorageContract();
        return compilerType.getCInitHelperName();
    }

    public @NotNull String renderCopyAssignFunctionName(@NotNull GdType type) {
        return switch (type) {
            case GdCompilerType compilerType -> {
                compilerType.validateCStorageContract();
                if (!compilerType.isCopyable()) {
                    // Move-only types (e.g. GdccCoroState) have no copy channel at all; an assign
                    // reaching this point is a contract violation and must not degrade into an
                    // empty helper name in generated C.
                    throw new IllegalArgumentException(
                            "Move-only compiler-only type '" + compilerType.getTypeName()
                                    + "' does not support copy assignment");
                }
                yield compilerType.getCCopyHelperName();
            }
            case GdObjectType _, GdPrimitiveType _ -> "";
            case GdVoidType _, GdNilType _ ->
                    throw new IllegalArgumentException("Type " + type.getTypeName() + " does not support copy assignment");
            default -> {
                var symbolTypeName = renderGdTypeName(type);
                yield "godot_new_" + symbolTypeName + "_with_" + symbolTypeName;
            }
        };
    }

    public @NotNull String renderDestroyFunctionName(@NotNull GdType type) {
        if (!type.isDestroyable()) {
            throw new IllegalArgumentException("Type " + type.getTypeName() + " is not destroyable");
        }
        if (type instanceof GdCompilerType compilerType) {
            compilerType.validateCStorageContract();
            return compilerType.getCDestroyHelperName();
        }
        if (type instanceof GdObjectType) {
            return "godot_object_destroy";
        } else {
            return "godot_" + renderGdTypeName(type) + "_destroy";
        }
    }

    /// Render wrapper-local cleanup for generated `call_func` glue code.
    ///
    /// This is intentionally narrower than ordinary backend destruct semantics:
    /// - only destroyable non-object wrappers materialize an addressable local slot that the wrapper must destroy
    /// - object argument locals are BORROWED from Variant args and must not be released here
    /// - OWNED object return carriers use `renderCallWrapperOwnedObjectReturnConsumeStmt` instead
    public @NotNull String renderCallWrapperDestroyStmt(@NotNull GdType type, @NotNull String varName) {
        TypeCheckUtil.requireNonCompilerOnly(type, "call wrapper destroy stmt");
        if (type instanceof GdObjectType || !type.isDestroyable()) {
            return "";
        }
        return renderDestroyFunctionName(type) + "(&" + varName + ");";
    }

    /// Release an OWNED object local produced by a wrapper default-fill shell call
    /// (`ud->defK(...)`). Unlike Variant-unpacked wrapper arguments (BORROWED), a default shell's
    /// object result is OWNED per the ownership spec, so the epilogue must release it; callers
    /// gate this statement behind the runtime `argN_from_default` flag so explicitly supplied
    /// (BORROWED) object arguments are never released. Empty for non-object / non-RefCounted.
    public @NotNull String renderCallWrapperDefaultObjectReleaseStmt(@NotNull GdType type, @NotNull String varName) {
        if (!(type instanceof GdObjectType objectType)) {
            return "";
        }
        var fatType = renderObjectFatPtrStorageType(objectType);
        var liveExpr = fatType + "_live_object(" + varName + ")";
        return switch (context.classRegistry().getRefCountedStatus(objectType)) {
            case YES -> "release_object(" + liveExpr + ");";
            case UNKNOWN -> "try_release_object(" + liveExpr + ", " + varName + ".instance_id);";
            case NO -> "";
        };
    }

    /// Typed Dictionary wrapper preflight only applies to non-generic `Dictionary[K, V]` slots.
    public boolean needsTypedDictionaryCallGuard(@NotNull GdType type) {
        return type instanceof GdDictionaryType dictionaryType && !dictionaryType.isGenericDictionary();
    }

    /// Typed Array wrapper preflight only applies to non-generic `Array[T]` slots.
    public boolean needsTypedArrayCallGuard(@NotNull GdType type) {
        return type instanceof GdArrayType arrayType && !arrayType.isGenericArray();
    }

    /// Render the expected builtin type literal for one typed-array guard element leaf.
    public @NotNull String renderTypedArrayGuardBuiltinTypeLiteral(@NotNull GdType type) {
        return renderTypedArrayRuntimeLeaf(resolveTypedArrayGuardLeaf(type))
                .typeIntLiteral();
    }

    /// Object leaves need extra class/script metadata comparison in the wrapper guard.
    public boolean isTypedArrayGuardObjectLeaf(@NotNull GdType type) {
        return renderTypedArrayRuntimeLeaf(resolveTypedArrayGuardLeaf(type))
                .objectLeaf();
    }

    /// Render the expected class-name literal for one typed-array object leaf.
    public @NotNull String renderTypedArrayGuardClassNameExpr(@NotNull GdType type) {
        return renderTypedArrayRuntimeLeaf(resolveTypedArrayGuardLeaf(type))
                .classNameExpr();
    }

    /// Render the expected builtin type literal for one typed-dictionary guard side.
    public @NotNull String renderTypedDictionaryGuardBuiltinTypeLiteral(@NotNull GdType type,
                                                                        @NotNull String sideName) {
        return renderTypedDictionaryRuntimeLeaf(resolveTypedDictionaryGuardLeaf(type, sideName), sideName + " leaf")
                .typeIntLiteral();
    }

    /// Object leaves need extra class/script metadata comparison in the wrapper guard.
    public boolean isTypedDictionaryGuardObjectLeaf(@NotNull GdType type, @NotNull String sideName) {
        return renderTypedDictionaryRuntimeLeaf(resolveTypedDictionaryGuardLeaf(type, sideName), sideName + " leaf")
                .objectLeaf();
    }

    /// Render the expected class-name literal for one typed-dictionary object leaf.
    public @NotNull String renderTypedDictionaryGuardClassNameExpr(@NotNull GdType type,
                                                                   @NotNull String sideName) {
        return renderTypedDictionaryRuntimeLeaf(resolveTypedDictionaryGuardLeaf(type, sideName), sideName + " leaf")
                .classNameExpr();
    }

    /// Renders the outward-facing metadata literals for a bound slot.
    ///
    /// Current backend-owned outward ABI rules:
    /// - `Variant` still uses `NIL + PROPERTY_USAGE_NIL_IS_VARIANT`
    /// - typed `Array[T]` publishes `PROPERTY_HINT_ARRAY_TYPE` plus one leaf atom whenever `T != Variant`
    /// - typed `Dictionary[K, V]` publishes `PROPERTY_HINT_DICTIONARY_TYPE` plus a flat `key;value`
    ///   hint string whenever either side is stricter than `Variant`
    /// - `class_name` stays on the existing empty default here; typed dictionary leaf identity lives in
    ///   `hint_string`, not in the top-level property info class slot
    /// - GDCC inner classes keep flowing through these metadata surfaces as their canonical
    ///   `Outer__sub__Inner` names; backend does not introduce a separate Godot-facing alias
    public @NotNull BoundMetadata renderBoundMetadata(@NotNull GdType type,
                                                      @NotNull String baseUsageExpr) {
        return renderBoundMetadata(type, baseUsageExpr, "bound slot");
    }

    public @NotNull BoundMetadata renderBoundMetadata(@NotNull GdType type,
                                                      @NotNull String baseUsageExpr,
                                                      @NotNull String useSite) {
        var extensionType = requireBoundMetadataType(type, useSite + " metadata");
        var usageExpr = type instanceof GdVariantType
                ? baseUsageExpr + " | godot_PROPERTY_USAGE_NIL_IS_VARIANT"
                : baseUsageExpr;
        var hintEnumLiteral = "godot_PROPERTY_HINT_NONE";
        var hintStringExpr = "GD_STATIC_S(u8\"\")";
        if (type instanceof GdArrayType arrayType && !arrayType.isGenericArray()) {
            hintEnumLiteral = "godot_PROPERTY_HINT_ARRAY_TYPE";
            hintStringExpr = "GD_STATIC_S(u8\"" + escapeStringLiteral(renderTypedArrayHintString(arrayType, useSite)) + "\")";
        } else if (type instanceof GdDictionaryType dictionaryType && !dictionaryType.isGenericDictionary()) {
            hintEnumLiteral = "godot_PROPERTY_HINT_DICTIONARY_TYPE";
            hintStringExpr = "GD_STATIC_S(u8\"" + escapeStringLiteral(renderTypedDictionaryHintString(dictionaryType)) + "\")";
        }
        return new BoundMetadata(
                "GDEXTENSION_VARIANT_TYPE_" + extensionType.name(),
                hintEnumLiteral,
                hintStringExpr,
                "GD_STATIC_SN(u8\"\")",
                usageExpr
        );
    }

    /// Signal-parameter metadata reuses the method-arg usage surface, not export-property usage.
    ///
    /// Object `class_name` stays on the existing empty default from `renderBoundMetadata`.
    public @NotNull BoundMetadata renderSignalParameterMetadata(@NotNull GdType type) {
        return renderBoundMetadata(type, "godot_PROPERTY_USAGE_DEFAULT", "signal parameter");
    }

    /// Export-variant annotation keys mapped to their Godot property-hint enum literal, iterated
    /// in a fixed priority order: the first key present on a property fully drives
    /// hint/hint_string rendering. Bare `export` is deliberately absent — it is the
    /// lowest-priority fallback that derives hint metadata from the property type. The explicit
    /// list keeps selection deterministic despite `LirPropertyDef.annotations` being an unordered
    /// `HashMap`.
    private static final List<Map.Entry<String, String>> EXPORT_VARIANT_HINT_PRIORITY = List.of(
            Map.entry("export_range", "godot_PROPERTY_HINT_RANGE"),
            Map.entry("export_enum", "godot_PROPERTY_HINT_ENUM"),
            Map.entry("export_flags", "godot_PROPERTY_HINT_FLAGS"),
            Map.entry("export_flags_2d_render", "godot_PROPERTY_HINT_LAYERS_2D_RENDER"),
            Map.entry("export_flags_2d_physics", "godot_PROPERTY_HINT_LAYERS_2D_PHYSICS"),
            Map.entry("export_flags_2d_navigation", "godot_PROPERTY_HINT_LAYERS_2D_NAVIGATION"),
            Map.entry("export_flags_3d_render", "godot_PROPERTY_HINT_LAYERS_3D_RENDER"),
            Map.entry("export_flags_3d_physics", "godot_PROPERTY_HINT_LAYERS_3D_PHYSICS"),
            Map.entry("export_flags_3d_navigation", "godot_PROPERTY_HINT_LAYERS_3D_NAVIGATION"),
            Map.entry("export_flags_avoidance", "godot_PROPERTY_HINT_LAYERS_AVOIDANCE"),
            Map.entry("export_file", "godot_PROPERTY_HINT_FILE"),
            Map.entry("export_file_path", "godot_PROPERTY_HINT_FILE_PATH"),
            Map.entry("export_dir", "godot_PROPERTY_HINT_DIR"),
            Map.entry("export_global_file", "godot_PROPERTY_HINT_GLOBAL_FILE"),
            Map.entry("export_global_dir", "godot_PROPERTY_HINT_GLOBAL_DIR"),
            Map.entry("export_multiline", "godot_PROPERTY_HINT_MULTILINE_TEXT"),
            Map.entry("export_placeholder", "godot_PROPERTY_HINT_PLACEHOLDER_TEXT"),
            Map.entry("export_exp_easing", "godot_PROPERTY_HINT_EXP_EASING"),
            Map.entry("export_color_no_alpha", "godot_PROPERTY_HINT_COLOR_NO_ALPHA"),
            Map.entry("export_node_path", "godot_PROPERTY_HINT_NODE_PATH_VALID_TYPES")
    );

    /// Property registration keeps the export/non-export base-usage split while reusing the same
    /// outward Variant encoding as method args/returns.
    ///
    /// The first export-variant key in `EXPORT_VARIANT_HINT_PRIORITY` present on the property
    /// fully drives hint/hint_string from the annotation value (the frontend already encoded it
    /// as a Godot `hint_string`); bare `export` alone falls back to type-derived hints. Any
    /// export family key implies `PROPERTY_USAGE_DEFAULT`; none implies `NO_EDITOR`. Backend
    /// trusts the frontend compile gate for argument validity and silently ignores unknown
    /// annotation keys.
    public @NotNull BoundMetadata renderPropertyMetadata(@NotNull PropertyDef propertyDef) {
        var annotations = propertyDef.getAnnotations();
        for (var variantHint : EXPORT_VARIANT_HINT_PRIORITY) {
            var hintString = annotations.get(variantHint.getKey());
            if (hintString != null) {
                return renderExportVariantMetadata(propertyDef, variantHint.getValue(), hintString);
            }
        }
        if (annotations.containsKey("export")) {
            return renderBareExportPropertyMetadata(propertyDef);
        }
        return renderBoundMetadata(propertyDef.getType(), "godot_PROPERTY_USAGE_NO_EDITOR", "property");
    }

    /// Variant-keyed exports: hint and hint_string come entirely from the annotation value. The
    /// class_name slot stays empty because the frontend restricts every variant to non-Object
    /// property types.
    private @NotNull BoundMetadata renderExportVariantMetadata(
            @NotNull PropertyDef propertyDef,
            @NotNull String hintEnumLiteral,
            @NotNull String hintString
    ) {
        var type = propertyDef.getType();
        var extensionType = requireBoundMetadataType(type, "property metadata");
        var usageExpr = type instanceof GdVariantType
                ? "godot_PROPERTY_USAGE_DEFAULT | godot_PROPERTY_USAGE_NIL_IS_VARIANT"
                : "godot_PROPERTY_USAGE_DEFAULT";
        return new BoundMetadata(
                "GDEXTENSION_VARIANT_TYPE_" + extensionType.name(),
                hintEnumLiteral,
                "GD_STATIC_S(u8\"" + escapeStringLiteral(hintString) + "\")",
                "GD_STATIC_SN(u8\"\")",
                usageExpr
        );
    }

    /// Bare `@export` keeps the type-derived hint behavior (typed Array/Dictionary via
    /// `renderBoundMetadata`) and extends it to Object exports: Resource-derived types render
    /// `PROPERTY_HINT_RESOURCE_TYPE`, Node-derived types render `PROPERTY_HINT_NODE_TYPE`, both
    /// with the property type class name as hint_string and class_name (Godot parity). Other
    /// Object types were already rejected by the frontend export validation; if one still reaches
    /// here it falls through to the plain type-derived surface.
    private @NotNull BoundMetadata renderBareExportPropertyMetadata(@NotNull PropertyDef propertyDef) {
        var type = propertyDef.getType();
        if (type instanceof GdObjectType objectType) {
            var hintEnumLiteral = resolveExportObjectHintEnum(objectType);
            if (hintEnumLiteral != null) {
                var extensionType = requireBoundMetadataType(type, "property metadata");
                var className = escapeStringLiteral(objectType.getTypeName());
                return new BoundMetadata(
                        "GDEXTENSION_VARIANT_TYPE_" + extensionType.name(),
                        hintEnumLiteral,
                        "GD_STATIC_S(u8\"" + className + "\")",
                        "GD_STATIC_SN(u8\"" + className + "\")",
                        "godot_PROPERTY_USAGE_DEFAULT"
                );
            }
        }
        return renderBoundMetadata(type, "godot_PROPERTY_USAGE_DEFAULT", "property");
    }

    private @Nullable String resolveExportObjectHintEnum(@NotNull GdObjectType objectType) {
        if (context.classRegistry().checkAssignable(objectType, new GdObjectType("Resource"))) {
            return "godot_PROPERTY_HINT_RESOURCE_TYPE";
        }
        if (context.classRegistry().checkAssignable(objectType, new GdObjectType("Node"))) {
            return "godot_PROPERTY_HINT_NODE_TYPE";
        }
        return null;
    }

    private @NotNull GdExtensionTypeEnum requireBoundMetadataType(@NotNull GdType type,
                                                                  @NotNull String useSite) {
        TypeCheckUtil.requireNonCompilerOnly(type, useSite);
        var extensionType = type.getGdExtensionType();
        if (extensionType == null) {
            throw new IllegalArgumentException("Type " + type.getTypeName() + " does not have outward GDExtension metadata");
        }
        return extensionType;
    }

    /// Godot encodes typed array outward metadata as one leaf atom.
    /// Backend only sees object leaves that frontend/lowering has already resolved to stable engine/GDCC
    /// object identities. `script leaf` unsupported remains a documented ABI boundary rather than a helper-local
    /// revalidation branch here.
    private @NotNull String renderTypedArrayHintString(@NotNull GdArrayType type, @NotNull String useSite) {
        return renderContainerHintAtom(type.getValueType(), useSite, "typed-array", false);
    }

    /// Shared leaf renderer for typed-container outward hints.
    /// - typed array forbids `Variant` leaf because `Array[Variant]` must stay generic outwardly
    /// - typed dictionary still publishes `Variant` as a valid side atom
    /// - object leaves are emitted directly by name; backend assumes frontend/lowering already resolved them
    private @NotNull String renderContainerHintAtom(@NotNull GdType type,
                                                    @NotNull String useSite,
                                                    @NotNull String containerKind,
                                                    boolean allowVariantLeaf) {
        return switch (type) {
            case GdCompilerType _ -> throw new IllegalArgumentException(
                    "compiler-only type leaked into " + containerKind + " outward hint leaf at " + useSite + ": " + type.getTypeName()
            );
            case GdVariantType _ -> {
                if (allowVariantLeaf) {
                    yield type.getTypeName();
                }
                throw unsupportedOutwardHintLeaf(
                        containerKind,
                        type,
                        useSite,
                        "Variant element must stay generic Array outwardly"
                );
            }
            case GdPackedArrayType _ -> type.getTypeName();
            case GdArrayType arrayType -> {
                if (arrayType.isGenericArray()) {
                    yield "Array";
                }
                throw unsupportedOutwardHintLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Array leaf is not supported"
                );
            }
            case GdDictionaryType dictionaryType -> {
                if (dictionaryType.isGenericDictionary()) {
                    yield "Dictionary";
                }
                throw unsupportedOutwardHintLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Dictionary leaf is not supported"
                );
            }
            case GdObjectType _ -> type.getTypeName();
            default -> {
                if (type.getGdExtensionType() == null) {
                    throw unsupportedOutwardHintLeaf(
                            containerKind,
                            type,
                            useSite,
                            "missing outward GDExtension metadata"
                    );
                }
                yield type.getTypeName();
            }
        };
    }

    /// Godot encodes typed dictionary outward metadata as a flat `key;value` string.
    /// We only publish one atom per side here, so nested typed containers must fail fast until we have a
    /// real recursive outward grammar for them.
    private @NotNull String renderTypedDictionaryHintString(@NotNull GdDictionaryType type) {
        return renderContainerHintAtom(type.getKeyType(), "key leaf", "typed-dictionary", true) + ";" +
                renderContainerHintAtom(type.getValueType(), "value leaf", "typed-dictionary", true);
    }

    private @NotNull IllegalArgumentException unsupportedOutwardHintLeaf(@NotNull String containerKind,
                                                                         @NotNull GdType type,
                                                                         @NotNull String useSite,
                                                                         @NotNull String reason) {
        return new IllegalArgumentException(
                "Unsupported " + containerKind + " outward hint leaf '" + type.getTypeName() +
                        "' at " + useSite + ": " + reason
        );
    }

    private @NotNull TypedContainerRuntimeLeaf renderTypedArrayRuntimeLeaf(@NotNull GdType type) {
        return renderTypedContainerRuntimeLeaf(type, "element leaf", "typed-array", false);
    }

    private @NotNull TypedContainerRuntimeLeaf renderTypedDictionaryRuntimeLeaf(@NotNull GdType type,
                                                                                @NotNull String useSite) {
        return renderTypedContainerRuntimeLeaf(type, useSite, "typed-dictionary", true);
    }

    /// Typed array and typed dictionary share the same runtime leaf triple shape even though
    /// their outward hint grammars and template blocks stay intentionally separate.
    /// Object leaves keep using the exact engine/GDCC class name that registration published,
    /// including canonical GDCC inner names like `Outer__sub__Inner`.
    private @NotNull TypedContainerRuntimeLeaf renderTypedContainerRuntimeLeaf(@NotNull GdType type,
                                                                               @NotNull String useSite,
                                                                               @NotNull String containerKind,
                                                                               boolean allowVariantLeaf) {
        var typeEnum = requireTypedContainerRuntimeLeafType(type, useSite, containerKind, allowVariantLeaf);
        if (type instanceof GdObjectType objectType) {
            return new TypedContainerRuntimeLeaf(
                    "(godot_int)GDEXTENSION_VARIANT_TYPE_" + typeEnum.name(),
                    "GD_STATIC_SN(u8\"" + escapeStringLiteral(objectType.getTypeName()) + "\")",
                    true
            );
        }

        return new TypedContainerRuntimeLeaf(
                "(godot_int)GDEXTENSION_VARIANT_TYPE_" + typeEnum.name(),
                "GD_STATIC_SN(u8\"\")",
                false
        );
    }

    private @NotNull GdExtensionTypeEnum requireTypedContainerRuntimeLeafType(@NotNull GdType type,
                                                                              @NotNull String useSite,
                                                                              @NotNull String containerKind,
                                                                              boolean allowVariantLeaf) {
        return switch (type) {
            case GdCompilerType _ -> throw new IllegalArgumentException(
                    "compiler-only type leaked into " + containerKind + " runtime leaf at " + useSite + ": " + type.getTypeName()
            );
            case GdVariantType _ -> {
                if (allowVariantLeaf) {
                    yield GdExtensionTypeEnum.NIL;
                }
                throw unsupportedTypedContainerRuntimeLeaf(
                        containerKind,
                        type,
                        useSite,
                        "Variant element must stay generic Array runtime guard"
                );
            }
            case GdArrayType arrayType -> {
                if (arrayType.isGenericArray()) {
                    yield GdExtensionTypeEnum.ARRAY;
                }
                throw unsupportedTypedContainerRuntimeLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Array leaf is not supported"
                );
            }
            case GdDictionaryType dictionaryType -> {
                if (dictionaryType.isGenericDictionary()) {
                    yield GdExtensionTypeEnum.DICTIONARY;
                }
                throw unsupportedTypedContainerRuntimeLeaf(
                        containerKind,
                        type,
                        useSite,
                        "nested typed Dictionary leaf is not supported"
                );
            }
            default -> {
                var extensionType = type.getGdExtensionType();
                if (extensionType == null) {
                    throw unsupportedTypedContainerRuntimeLeaf(
                            containerKind,
                            type,
                            useSite,
                            "missing runtime GDExtension metadata"
                    );
                }
                yield extensionType;
            }
        };
    }

    private @NotNull IllegalArgumentException unsupportedTypedContainerRuntimeLeaf(@NotNull String containerKind,
                                                                                   @NotNull GdType type,
                                                                                   @NotNull String useSite,
                                                                                   @NotNull String reason) {
        return new IllegalArgumentException(
                "Unsupported " + containerKind + " runtime leaf '" + type.getTypeName() +
                        "' at " + useSite + ": " + reason
        );
    }

    private @NotNull GdType resolveTypedArrayGuardLeaf(@NotNull GdType type) {
        if (!(type instanceof GdArrayType arrayType) || arrayType.isGenericArray()) {
            throw new IllegalArgumentException(
                    "Typed-array guard metadata requested for non-typed Array slot '" + type.getTypeName() + "'"
            );
        }
        return arrayType.getValueType();
    }

    private @NotNull GdType resolveTypedDictionaryGuardLeaf(@NotNull GdType type, @NotNull String sideName) {
        if (!(type instanceof GdDictionaryType dictionaryType) || dictionaryType.isGenericDictionary()) {
            throw new IllegalArgumentException(
                    "Typed-dictionary guard metadata requested for non-typed Dictionary slot '" + type.getTypeName() + "'"
            );
        }
        return switch (sideName) {
            case "key" -> dictionaryType.getKeyType();
            case "value" -> dictionaryType.getValueType();
            default -> throw new IllegalArgumentException("Unknown typed-dictionary guard side: " + sideName);
        };
    }

    public boolean checkVirtualMethod(@NotNull ClassDef classDef, @NotNull FunctionDef functionDef) {
        var engineVirtual = context.classRegistry().findEngineVirtualMethod(classDef.getName(), functionDef.getName());
        if (engineVirtual == null) {
            return false;
        }
        return engineVirtual.checkOverrideSignature(functionDef, true);
    }

    public boolean checkGdccClassByName(@NotNull String className) {
        return context.classRegistry().isGdccClass(className);
    }

    /// Renders generated per-class object pointer helper name for a GDCC object type.
    public @NotNull String renderGdccObjectPtrHelperName(@NotNull GdObjectType gdObjectType) {
        if (!gdObjectType.checkGdccType(context.classRegistry())) {
            throw new IllegalArgumentException("Type " + gdObjectType.getTypeName() + " is not a GDCC object type");
        }
        return gdObjectType.getTypeName() + "_object_ptr";
    }

    // ==== Vtable symbols and layout ====
    //
    // Single publication point for every vtable-facing symbol/expression the entry templates
    // emit; templates must never re-spell these locally. Two `_super` chains are involved and
    // must stay distinct (virtual_override_vtable_implementation.md §2.2): the WRAPPER chain
    // (direct parent at every generation, backing the root `_vtable` field access) and the
    // VTABLE chain (nearest slot-introducing ancestor, backing typedef prefix embedding). All
    // name components use the raw canonical class name (same layer as `struct <Class>`), except
    // fat-pointer types which come from `renderObjectFatPtrStorageType` (cIdentifier layer).

    /// True when this class's hierarchy (its GDCC root plus all in-module descendants) carries
    /// at least one vtable slot — exactly the condition for the root wrapper struct to gain a
    /// `const void* _vtable;` field right after `_object`. `resolvedVtableSymbol` is present in
    /// precisely that case; its side-branch `NULL` value still requires the field to exist.
    public boolean requiresVtableField(@NotNull String className) {
        return vtablePlanner.resolvedVtableSymbol(className).isPresent();
    }

    /// Vtable typedef naming this class's instance type: the class's own `gdcc_<C>_vtable`
    /// when it introduces slots, otherwise the nearest introducer ancestor's typedef. Fails for
    /// slotless classes (side branches own no instance type); templates only query slotted ones.
    public @NotNull String renderVtableInstanceTypeName(@NotNull String className) {
        return vtablePlanner.vtableInstanceType(className)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Class '" + className + "' has no vtable instance type (it is not slotted)"));
    }

    /// By-value first member of one introducer's vtable typedef: the nearest slot-introducing
    /// STRICT ancestor's `gdcc_<P>_vtable _super;`, or "" when no ancestor introduces slots.
    /// This is the vtable `_super` chain — it skips pass-through and override-only ancestors,
    /// unlike the wrapper `_super` chain rendered by `renderVtableFieldAccessExpr`.
    public @NotNull String renderVtableSuperMemberDecl(@NotNull ClassDef classDef) {
        return vtablePlanner.vtableInstanceType(classDef.getSuperName())
                .map(superType -> superType + " _super;")
                .orElse("");
    }

    /// Function-pointer member declaration for one newly introduced slot:
    /// `<Ret> (*m_foo)(gdcc_<I>_fat_ptr $self, ...)`. The signature source is the slot
    /// introducer's own function (self keeps the introducer's fat type); coroutine slots take
    /// the start-thunk signature (`godot_Object*` return, same parameters).
    public @NotNull String renderVtableSlotMemberDecl(@NotNull CVtablePlanner.VtableSlot slot) {
        return renderVtableSlotReturnType(slot) + " (*m_" + slot.methodName() + ")("
                + renderVtableSlotParameterList(slot) + ")";
    }

    /// Instance symbol `gdcc_<C>_vtable_inst`; only introducers and override-only classes own
    /// an instance (pass-through classes share the nearest non-pass-through ancestor's one).
    public @NotNull String renderVtableInstanceSymbol(@NotNull String className) {
        if (!vtablePlanner.introducesSlot(className) && !vtablePlanner.overridesInheritedSlot(className)) {
            throw new IllegalArgumentException(
                    "Class '" + className + "' owns no vtable instance (it neither introduces nor overrides a slot)");
        }
        return "gdcc_" + className + "_vtable_inst";
    }

    /// Accessor name `<C>_class_vtable`, generated for introducers only and registered in
    /// `CCodegen.validateFileScopeSymbolsDisjoint` under the same conflict model as `_object_ptr`.
    public @NotNull String renderVtableAccessorName(@NotNull String className) {
        return className + "_class_vtable";
    }

    /// Expression reaching the root `_vtable` field from a `<C>* self`: `self->_vtable` for
    /// root classes, otherwise one `_super` hop per GDCC wrapper ancestor
    /// (`self->_super._super._vtable`). Walks the WRAPPER chain via the registry — mirroring
    /// the struct-embedding decision in entry.h.ftl — so pass-through ancestors are never skipped.
    public @NotNull String renderVtableFieldAccessExpr(@NotNull String className) {
        var registry = context.classRegistry();
        var chain = new StringBuilder("self");
        var visited = new HashSet<String>();
        var current = className;
        while (true) {
            var currentDef = registry.findGdccClass(current);
            if (currentDef == null) {
                throw new IllegalArgumentException(
                        "Unknown GDCC class '" + current + "' while rendering the vtable field access of '" + className + "'");
            }
            var superName = currentDef.getSuperName();
            if (!registry.isGdccClass(superName)) {
                break;
            }
            if (!visited.add(superName)) {
                throw new IllegalStateException(
                        "Detected GDCC inheritance cycle while rendering the vtable field access of '" + className + "'");
            }
            chain.append(chain.length() == "self".length() ? "->_super" : "._super");
            current = superName;
        }
        chain.append(chain.length() == "self".length() ? "->_vtable" : "._vtable");
        return chain.toString();
    }

    /// Right-hand side of the `_vtable` write in `<C>_class_create_instance`: "" when the
    /// hierarchy has no field at all (branch 1), `NULL` for side branches (branch 2), and
    /// `&gdcc_<X>_vtable_inst` otherwise — pass-through classes resolve to the nearest
    /// non-pass-through ancestor's instance, never NULL (branch 3/4, §2.3).
    public @NotNull String renderVtableFieldInitExpr(@NotNull String className) {
        return vtablePlanner.resolvedVtableSymbol(className)
                .map(symbol -> CVtablePlanner.VTABLE_NULL_SYMBOL.equals(symbol) ? symbol : "&" + symbol)
                .orElse("");
    }

    /// Nested C99 designated initializer of one class's vtable instance. Slots stay grouped by
    /// introducer (the planner's prefix ordering guarantees contiguous, root-most-first
    /// segments); each segment but the root-most opens with its own `._super = { ... }` nest.
    /// Every entry is the slot's final overrider for this class: the introducer's own
    /// implementation symbol, an override trampoline, or NULL for an abstract hole.
    public @NotNull String renderVtableInstanceInitializer(@NotNull String className) {
        var slotEntries = vtablePlanner.slots(className);
        if (slotEntries.isEmpty()) {
            throw new IllegalArgumentException(
                    "Class '" + className + "' has no vtable slots to initialize an instance with");
        }
        var segments = new ArrayList<List<CVtablePlanner.VtableSlotEntry>>();
        for (var entry : slotEntries) {
            if (segments.isEmpty()
                    || !segments.getLast().getFirst().slot().introducerClassName().equals(entry.slot().introducerClassName())) {
                segments.add(new ArrayList<>());
            }
            segments.getLast().add(entry);
        }
        return renderVtableInitializerSegment(segments, segments.size() - 1);
    }

    /// Trampoline symbol for one (overriding class × inherited slot) pair: `gdcc_<D>_vslot_<m>`.
    public @NotNull String renderVtableTrampolineName(@NotNull String className, @NotNull String methodName) {
        return "gdcc_" + className + "_vslot_" + methodName;
    }

    /// Whole trampoline definition adapting an inherited slot's introducer signature to this
    /// class's overriding implementation. The `(D*)self.ptr` downcast is the single sanctioned
    /// exception to the no-bare-GDCC-cast contract (explicit_c_inheritance_layout_contract.md
    /// §5): reached through D's own vtable the dynamic type is always D or a descendant, and
    /// the offset-0 wrapper embedding makes the pointer adjustment-free.
    public @NotNull String renderVtableTrampolineDefinition(
            @NotNull String className,
            @NotNull CVtablePlanner.VtableSlotEntry entry
    ) {
        var slot = entry.slot();
        if (!entry.finalOverriderClassName().equals(className) || slot.introducerClassName().equals(className)) {
            throw new IllegalArgumentException(
                    "Class '" + className + "' is not the non-introducer final overrider of slot '" + slot.methodName() + "'");
        }
        var overriderFunction = entry.finalOverriderFunction();
        // Built first: validates the synthetic leading self the forwarding below relies on.
        var parameterList = renderVtableSlotParameterList(slot);
        var forwardedArgs = new StringBuilder();
        for (var index = 1; index < slot.introducerFunction().getParameterCount(); index++) {
            var parameter = slot.introducerFunction().getParameter(index);
            if (parameter == null) {
                throw new IllegalStateException(
                        "Vtable slot '" + slot.methodName() + "' misses parameter at index " + index);
            }
            forwardedArgs.append(", $").append(parameter.getName());
        }
        var call = renderVtableImplSymbol(className, overriderFunction.getName(), slot.coroutine())
                + "(s" + forwardedArgs + ")";
        return renderVtableSlotReturnType(slot) + " " + renderVtableTrampolineName(className, slot.methodName()) + "("
                + parameterList + ") {\n"
                + renderObjectFatPtrStorageType(new GdObjectType(className)) + " s = { (" + className + "*)$self.ptr, $self.instance_id };\n"
                + (isVoidSlot(slot) ? call + ";" : "return " + call + ";") + "\n"
                + "}";
    }

    /// One nested initializer segment: `.{ ._super = <parent segment>, .m_x = value, ... }`
    /// rendered recursively from the root-most introducer up to the instance's own type.
    private @NotNull String renderVtableInitializerSegment(
            @NotNull List<List<CVtablePlanner.VtableSlotEntry>> segments,
            int segmentIndex
    ) {
        var builder = new StringBuilder("{ ");
        if (segmentIndex > 0) {
            builder.append("._super = ")
                    .append(renderVtableInitializerSegment(segments, segmentIndex - 1))
                    .append(", ");
        }
        var first = true;
        for (var entry : segments.get(segmentIndex)) {
            if (!first) {
                builder.append(", ");
            }
            builder.append(".m_").append(entry.slot().methodName()).append(" = ").append(renderVtableSlotEntryValue(entry));
            first = false;
        }
        return builder.append(" }").toString();
    }

    /// Instance entry value for one slot: NULL for an abstract hole, the introducer's own
    /// implementation symbol when it is also the final overrider, else the final overrider's
    /// trampoline.
    private @NotNull String renderVtableSlotEntryValue(@NotNull CVtablePlanner.VtableSlotEntry entry) {
        if (entry.abstractHole()) {
            return CVtablePlanner.VTABLE_NULL_SYMBOL;
        }
        var slot = entry.slot();
        if (entry.finalOverriderClassName().equals(slot.introducerClassName())) {
            return renderVtableImplSymbol(slot.introducerClassName(), slot.methodName(), slot.coroutine());
        }
        return renderVtableTrampolineName(entry.finalOverriderClassName(), slot.methodName());
    }

    /// Slot return C type: coroutine slots point at start thunks (`godot_Object*`), everything
    /// else uses the introducer function's declared return type.
    private @NotNull String renderVtableSlotReturnType(@NotNull CVtablePlanner.VtableSlot slot) {
        return slot.coroutine() ? "godot_Object*" : renderGdTypeInC(slot.introducerFunction().getReturnType());
    }

    /// Slot parameter list mirroring `func.ftl`'s header shape: every introducer parameter
    /// (leading fat `self` included) as `<type> $<name>`.
    private @NotNull String renderVtableSlotParameterList(@NotNull CVtablePlanner.VtableSlot slot) {
        var function = slot.introducerFunction();
        if (function.getParameterCount() == 0
                || function.getParameter(0) == null
                || !"self".equals(Objects.requireNonNull(function.getParameter(0)).getName())) {
            throw new IllegalStateException(
                    "Vtable slot '" + slot.methodName() + "' lacks the synthetic leading self parameter");
        }
        var builder = new StringBuilder();
        for (var index = 0; index < function.getParameterCount(); index++) {
            var parameter = function.getParameter(index);
            if (parameter == null) {
                throw new IllegalStateException(
                        "Vtable slot '" + slot.methodName() + "' misses parameter at index " + index);
            }
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(renderGdTypeRefInC(parameter.getType())).append(" $").append(parameter.getName());
        }
        return builder.toString();
    }

    /// Direct implementation symbol for one class's method: `<C>_<m>`, or the coroutine start
    /// thunk `<C>_<m>__coro_start` for coroutine slots (their entries always start coroutines).
    private @NotNull String renderVtableImplSymbol(@NotNull String className, @NotNull String methodName, boolean coroutine) {
        return className + "_" + methodName + (coroutine ? "__coro_start" : "");
    }

    private boolean isVoidSlot(@NotNull CVtablePlanner.VtableSlot slot) {
        return !slot.coroutine() && slot.introducerFunction().getReturnType() instanceof GdVoidType;
    }

    /// Render the dedicated constructor-time property-init apply helper name.
    /// This stays in `CGenHelper` because it is pure generated-symbol naming, not a
    /// control-flow concern.
    public @NotNull String renderPropertyInitApplyHelperName(
            @NotNull LirClassDef classDef,
            @NotNull LirPropertyDef propertyDef
    ) {
        return classDef.getName() + "_class_apply_property_init_" + propertyDef.getName();
    }

    // ==== Static property storage symbols ====
    //
    // All static-var file-scope symbols are published from this single point; load/store gens,
    // module lifecycle wiring, and the symbol-conflict validator must never re-spell them locally.
    // The class identity component is the raw canonical class name (same surface as
    // `<Class>_object_ptr`), not `cIdentifier()` output; the theoretical ambiguity this creates
    // is absorbed by the module-wide conflict check in `CCodegen.validateFileScopeSymbolsDisjoint`.

    /// File-scope backing variable for one static property: `gdcc_static_<canonicalClassName>_<propName>`.
    public @NotNull String renderStaticBackingSymbol(
            @NotNull String canonicalClassName,
            @NotNull String propertyName
    ) {
        return "gdcc_static_" + canonicalClassName + "_" + propertyName;
    }

    /// Per-class static defaults entry: `gdcc_static_defaults_<canonicalClassName>`. The class name
    /// intentionally does not sit in the prefix position so these compiler-owned symbols stay out
    /// of the user function namespace `${class}_${func}` (`func.ftl`).
    public @NotNull String renderStaticDefaultsSymbol(@NotNull String canonicalClassName) {
        return "gdcc_static_defaults_" + canonicalClassName;
    }

    /// Per-class static initializer entry: `gdcc_static_initializers_<canonicalClassName>`
    /// (same namespace-avoidance rationale as the defaults entry).
    public @NotNull String renderStaticInitializersSymbol(@NotNull String canonicalClassName) {
        return "gdcc_static_initializers_" + canonicalClassName;
    }

    /// `deinitialize()` cleanup statement for one static backing variable. Shares the exact
    /// per-value destroy discipline of lambda capture fields (object three-state release /
    /// destroyable builtin destroy / no-op otherwise); returns "" when there is nothing to free.
    public @NotNull String renderStaticBackingDestroyStmt(@NotNull GdType propertyType, @NotNull String backingExpr) {
        return renderManagedStorageFreeStmt(propertyType, backingExpr);
    }

    /// Constructor/`Class*` sites materialize owner fat self for internal methods that take fat parameters.
    public @NotNull String renderOwnerFatSelfFromWrapperPtr(
            @NotNull String className,
            @NotNull String wrapperPtrExpr
    ) {
        var fatType = renderObjectFatPtrStorageType(new GdObjectType(className));
        return fatType + "_from_raw(" + className + "_object_ptr(" + wrapperPtrExpr + "))";
    }

    /// Resolve the nearest constructible native ancestor for a GDCC class.
    /// This walks canonical GDCC superclass names until the first non-GDCC parent.
    public @NotNull String resolveNearestNativeAncestorName(@NotNull ClassDef classDef) {
        var registry = context.classRegistry();
        var ancestorCanonicalName = classDef.getSuperName();
        var visited = new HashSet<String>();
        while (registry.isGdccClass(ancestorCanonicalName)) {
            if (!visited.add(ancestorCanonicalName)) {
                throw new IllegalStateException("Detected GDCC inheritance cycle while resolving native ancestor for class " + classDef.getName());
            }
            var parentDef = registry.findGdccClass(ancestorCanonicalName);
            if (parentDef == null) {
                throw new IllegalStateException("Missing GDCC class definition for parent " + ancestorCanonicalName + " while resolving native ancestor for class " + classDef.getName());
            }
            ancestorCanonicalName = parentDef.getSuperName();
        }
        if (ancestorCanonicalName.isEmpty()) {
            throw new IllegalStateException("Class " + classDef.getName() + " does not have a native ancestor");
        }
        return ancestorCanonicalName;
    }

    public @NotNull CodegenContext context() {
        return context;
    }

    /// Read-only module vtable plan for entry-template layout and call-site dispatch queries.
    public @NotNull CVtablePlanner vtablePlanner() {
        return vtablePlanner;
    }

    public @NotNull CBuiltinBuilder builtinBuilder() {
        return builtinBuilder;
    }

    /// Normalize a utility function name into the class-registry lookup key.
    /// The registry is keyed by the unprefixed utility name.
    public @NotNull String normalizeUtilityLookupName(@NotNull String functionName) {
        if (!functionName.startsWith(GODOT_UTILITY_PREFIX)) {
            return functionName;
        }
        if (functionName.length() == GODOT_UTILITY_PREFIX.length()) {
            return functionName;
        }
        return functionName.substring(GODOT_UTILITY_PREFIX.length());
    }

    /// Resolve utility/helper call metadata from either `foo` or `godot_foo`.
    public @Nullable UtilityCallResolution resolveUtilityCall(@NotNull String functionName) {
        if (functionName.equals(VARIANT_WRITEBACK_HELPER_NAME)) {
            return new UtilityCallResolution(
                    VARIANT_WRITEBACK_HELPER_NAME,
                    VARIANT_WRITEBACK_HELPER_NAME,
                    VARIANT_WRITEBACK_HELPER_SIGNATURE
            );
        }
        var lookupName = normalizeUtilityLookupName(functionName);
        if (context.classRegistry().isGdScriptLanguageFunction(lookupName)) {
            // Synthetic language functions bypass the `godot_` prefix: their runtime entry points
            // are header-only `gdcc_*` helpers. Check the route-table mapping before the
            // signature so an unmapped name (`load`, which frontend lowering must rewrite) fails
            // fast with a clear contract message even when the surrounding registry cannot parse
            // the declared return metadata (e.g. a minimal test fixture without `Resource`).
            var cName = requireGdScriptLanguageFunctionCName(lookupName);
            var signature = Objects.requireNonNull(
                    context.classRegistry().findUtilityFunctionSignature(lookupName),
                    "registered GDScript language function '" + lookupName + "' must expose a signature"
            );
            return new UtilityCallResolution(lookupName, cName, signature);
        }
        var signature = context.classRegistry().findUtilityFunctionSignature(lookupName);
        if (signature == null) {
            return null;
        }
        return new UtilityCallResolution(lookupName, GODOT_UTILITY_PREFIX + lookupName, signature);
    }

    /// Per-name contract: a registered synthetic GDScript language function must map to a
    /// `gdcc_*` helper. A missing entry means the registration landed without its
    /// backend/runtime counterpart (or, for `load`, that lowering failed to rewrite the call);
    /// fail fast instead of silently emitting a nonexistent `godot_*` wrapper.
    static @NotNull String requireGdScriptLanguageFunctionCName(@NotNull String lookupName) {
        var cName = GDSCRIPT_LANGUAGE_FUNCTION_C_NAMES.get(lookupName);
        if (cName == null) {
            throw new InvalidInsnException("GDScript language function '" + lookupName +
                    "' has no gdcc_* runtime helper mapping; 'load'/'preload' must be rewritten to a " +
                    "ResourceLoader singleton call by frontend lowering, and unmapped names indicate " +
                    "a registration/backend phase mismatch");
        }
        return cName;
    }

    public record UtilityCallResolution(@NotNull String lookupName,
                                        @NotNull String cFunctionName,
                                        @NotNull FunctionSignature signature) {
        public UtilityCallResolution {
            Objects.requireNonNull(lookupName);
            Objects.requireNonNull(cFunctionName);
            Objects.requireNonNull(signature);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (CGenHelper) obj;
        return Objects.equals(this.context, that.context);
    }

    @Override
    public int hashCode() {
        return Objects.hash(context);
    }

    @Override
    public String toString() {
        return "CGenHelper[" +
                "context=" + context + ']';
    }

}
