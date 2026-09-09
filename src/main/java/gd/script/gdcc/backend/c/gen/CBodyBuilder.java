package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.c.gen.binding.ModuleLocalGodotBinding;
import gd.script.gdcc.backend.c.gen.binding.usage.GodotBindingUsageBuffer;
import gd.script.gdcc.backend.c.gen.insn.BackendMethodCallResolver;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.lir.*;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.RefCountedStatus;
import gd.script.gdcc.type.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static gd.script.gdcc.util.StringUtil.escapeStringLiteral;

/// Builder for generating C function body code.
///
/// This builder is created per function and used on a single thread.
/// It tracks current instruction position to provide precise codegen errors.
@SuppressWarnings("UnusedReturnValue")
public final class CBodyBuilder {
    private static final String RETURN_SLOT_NAME = "_return_val";
    private static final @NotNull Pattern NON_TEMP_PREFIX_CHAR_PATTERN = Pattern.compile("[^a-z0-9_]");

    private final @NotNull CGenHelper helper;
    private final @NotNull LirClassDef clazz;
    private final @NotNull LirFunctionDef func;
    private final @NotNull GodotBindingUsageBuffer usageBuffer;
    private final @Nullable CCoroutineFrameContext coroContext;
    private final @NotNull StringBuilder out = new StringBuilder();

    private @Nullable LirBasicBlock currentBlock;
    private int currentInsnIndex = -1;
    private @Nullable LirInstruction currentInsn;

    private int tempVarCounter = 0;

    public CBodyBuilder(@NotNull CGenHelper helper,
                        @NotNull LirClassDef clazz,
                        @NotNull LirFunctionDef func) {
        this(helper, clazz, func, GodotBindingUsageBuffer.noOp());
    }

    CBodyBuilder(@NotNull CGenHelper helper,
                 @NotNull LirClassDef clazz,
                 @NotNull LirFunctionDef func,
                 @NotNull GodotBindingUsageBuffer usageBuffer) {
        this(helper, clazz, func, usageBuffer, null);
    }

    CBodyBuilder(@NotNull CGenHelper helper,
                 @NotNull LirClassDef clazz,
                 @NotNull LirFunctionDef func,
                 @NotNull GodotBindingUsageBuffer usageBuffer,
                 @Nullable CCoroutineFrameContext coroContext) {
        this.helper = Objects.requireNonNull(helper);
        this.clazz = Objects.requireNonNull(clazz);
        this.func = Objects.requireNonNull(func);
        this.usageBuffer = Objects.requireNonNull(usageBuffer);
        this.coroContext = coroContext;
    }

    /// True while rendering a `__coro_body` function: parameters live in typed frame fields
    /// (no C slots), `_co` / the state header are addressable, and `__finally__` consumes
    /// `_return_val` into the frame's typed return slot instead of a plain C return.
    public boolean isCoroutineBody() {
        return coroContext != null;
    }

    public @Nullable CCoroutineFrameContext coroutineContext() {
        return coroContext;
    }

    /// Parameters of a coroutine body map to typed frame fields (ownership spec §3.10).
    /// Public for instruction generators that must not apply the borrowed-ref rejection to them.
    public boolean isCoroutineFrameParameter(@NotNull LirVariable variable) {
        return coroContext != null && func.checkVariableParameter(variable.id());
    }

    /// Captures of a coroutine lambda body map to typed frame fields: the start
    /// thunk copies the capture block into per-call owning frame fields, so the body never sees
    /// the `_capture` block. Mirrors `isCoroutineFrameParameter`; false for non-coroutine bodies.
    public boolean isCoroutineFrameCapture(@NotNull LirVariable variable) {
        return coroContext != null && func.getCapture(variable.id()) != null;
    }

    /// C storage expression of a variable: `$<id>` for ordinary slots, the typed frame field
    /// for parameters/captures of a coroutine body (no parameter/capture C slots exist there).
    private @NotNull String renderVariableStorageExpr(@NotNull LirVariable variable) {
        if (isCoroutineFrameParameter(variable)) {
            return CCoroutineFrameContext.paramFieldAccessExpr(variable);
        }
        if (isCoroutineFrameCapture(variable)) {
            return CCoroutineFrameContext.captureFieldAccessExpr(variable);
        }
        return "$" + variable.id();
    }

    /// Whether the variable keeps reference (pointer) semantics in generated C. Coroutine
    /// parameters/captures lose it: their frame fields are ordinary owning storage, so
    /// reads/writes / address-of / argument passing follow the non-ref rules.
    public boolean isEffectivelyRef(@NotNull LirVariable variable) {
        return variable.ref() && !isCoroutineFrameParameter(variable) && !isCoroutineFrameCapture(variable);
    }

    public @NotNull CBodyBuilder setCurrentPosition(@NotNull LirBasicBlock block,
                                                    int insnIndex,
                                                    @NotNull LirInstruction instruction) {
        this.currentBlock = Objects.requireNonNull(block);
        this.currentInsnIndex = insnIndex;
        this.currentInsn = Objects.requireNonNull(instruction);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <Insn extends LirInstruction> @NotNull Insn getCurrentInsn(@NotNull CInsnGen<Insn> gen) {
        Objects.requireNonNull(gen);
        var insn = currentInsn;
        if (insn == null || currentBlock == null) {
            throw new IllegalStateException("Current instruction position is not set");
        }
        if (!gen.getInsnOpcodes().contains(insn.opcode())) {
            throw new InvalidInsnException(
                    func.getName(),
                    currentBlock.id(),
                    currentInsnIndex,
                    insn.opcode().opcode(),
                    "Current instruction opcode '" + insn.opcode().opcode() +
                            "' is not handled by generator '" + gen.getClass().getSimpleName() + "'"
            );
        }
        return (Insn) insn;
    }

    public @NotNull CBodyBuilder beginBasicBlock(@NotNull String blockId) {
        out.append(blockId).append(": // ").append(blockId).append("\n");
        // Add _return_val decl if we are in __prepare__
        if ("__prepare__".equals(blockId)) {
            var returnType = func.getReturnType();
            if (!(returnType instanceof GdVoidType)) {
                out.append(helper.renderGdTypeInC(returnType)).append(" ").append(RETURN_SLOT_NAME);
                if (returnType instanceof GdObjectType objectType) {
                    // Object return slots start zeroed so overwrite can safely release the old fat pointer.
                    out.append(" = ").append(helper.renderDefaultValueExprInC(objectType));
                }
                out.append(";\n");
            }
        }
        return this;
    }

    public @NotNull CBodyBuilder appendLine(@NotNull String line) {
        out.append(line).append("\n");
        return this;
    }

    public @NotNull CBodyBuilder appendRaw(@NotNull String code) {
        out.append(code);
        return this;
    }

    private @NotNull String newTempName(@NotNull String prefix) {
        return "__gdcc_tmp_" + prefix + "_" + tempVarCounter++;
    }

    public @NotNull TempVar newTempVariable(@NotNull String prefix,
                                            @NotNull GdType type) {
        return new TempVar(newTempName(prefix), type, null, resolvePtrKind(type), false);
    }

    public @NotNull TempVar newTempVariable(@NotNull String prefix,
                                            @NotNull GdType type,
                                            @NotNull String initCode) {
        return new TempVar(newTempName(prefix), type, initCode, resolvePtrKind(type), true);
    }

    public @NotNull CBodyBuilder declareTempVar(@NotNull TempVar temp) {
        out.append(helper.renderGdTypeInC(temp.type())).append(" ").append(temp.name());
        if (temp.hasInitializer()) {
            out.append(" = ").append(Objects.requireNonNull(temp.initCode()));
            temp.setInitialized(true);
        } else {
            temp.setInitialized(false);
        }
        out.append(";\n");
        return this;
    }

    /// Declares a temp variable without initializer and marks it as uninitialized.
    /// This is required for APIs that write into out-parameters (for example `godot_variant_evaluate`).
    public @NotNull CBodyBuilder declareUninitializedTempVar(@NotNull TempVar temp) {
        if (temp.hasInitializer()) {
            throw new IllegalArgumentException(
                    "declareUninitializedTempVar requires temp without initializer: " + temp.name()
            );
        }
        out.append(helper.renderGdTypeInC(temp.type())).append(" ").append(temp.name()).append(";\n");
        temp.setInitialized(false);
        return this;
    }

    public @NotNull CBodyBuilder destroyTempVar(@NotNull TempVar temp) {
        if (!temp.initialized()) {
            return this;
        }
        emitDestroy(temp.name(), temp.type());
        temp.setInitialized(false);
        return this;
    }

    /// Initializes an uninitialized temp variable without old-value destroy/own-release semantics.
    /// This is used for first-write initialization where assign/callAssign lifecycle hooks are not desired.
    public @NotNull CBodyBuilder initTempVar(@NotNull TempVar temp, @NotNull ValueRef value) {
        checkAssignable(value.type(), temp.type());
        var initCode = value.generateCode();
        if (temp.type() instanceof GdObjectType targetObjType) {
            var valueObjType = value.type() instanceof GdObjectType objectType ? objectType : null;
            initCode = convertObjectValueIfNeeded(initCode, value.ptrKind(), valueObjType, targetObjType);
        }
        out.append(temp.name()).append(" = ").append(initCode).append(";\n");
        temp.setInitialized(true);
        return this;
    }

    public @NotNull InvalidInsnException invalidInsn(@NotNull String reason) {
        var insn = currentInsn;
        var block = currentBlock;
        if (insn == null || block == null) {
            return new InvalidInsnException("Invalid instruction in function '" + func.getName() + "': " + reason);
        }
        return new InvalidInsnException(func.getName(), block.id(), currentInsnIndex, insn.opcode().opcode(), reason);
    }

    public @NotNull CGenHelper helper() {
        return helper;
    }

    public @NotNull ClassRegistry classRegistry() {
        return helper.context().classRegistry();
    }

    public @NotNull LirClassDef clazz() {
        return clazz;
    }

    public @NotNull LirFunctionDef func() {
        return func;
    }

    public @Nullable LirBasicBlock currentBlock() {
        return currentBlock;
    }

    public int currentInsnIndex() {
        return currentInsnIndex;
    }

    public @NotNull String build() {
        return out.toString();
    }

    /// Exact engine usage collection is function-local at builder scope.
    /// The buffer stays isolated from module session state until the caller commits after a successful render.
    public void recordUsedEngineMethodCall(@NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        usageBuffer.recordEngineMethodCall(resolved);
    }

    /// Engine constructors keep their public `godot_new_<Class>()` call shape in generated bodies.
    /// Recording here only decides which module-local wrapper definitions are emitted later.
    /// For definite `RefCounted` engine classes the wrapper also calls `gdcc_ref_counted_init_raw`
    /// so the returned raw pointer is OWNED at refcount=1 (aligned with GDCC class create paths).
    public void recordUsedEngineConstructor(@NotNull GdObjectType constructedType) {
        var needsRefCountedInit = classRegistry().getRefCountedStatus(constructedType)
                == RefCountedStatus.YES;
        usageBuffer.recordEngineConstructor(constructedType, needsRefCountedInit);
    }

    /// Explicitly records a module-local Godot wrapper before emitting the matching `godot_*` call.
    /// Runtime-provided wrappers are ignored here and stay owned by `godot_binding.h/.c`.
    public void recordModuleLocalGodotBinding(@NotNull ModuleLocalGodotBinding binding) {
        usageBuffer.recordModuleLocalGodotBinding(binding);
    }

    /// Resolves the value representation for a GdType.
    /// Ordinary object storage/parameters/returns are fat pointers; raw ABI producers must be marked
    /// explicitly as `RAW_PRODUCER` at the call/construct site.
    private @NotNull PtrKind resolvePtrKind(@NotNull GdType type) {
        if (type instanceof GdObjectType) {
            return PtrKind.FAT_PTR;
        }
        return PtrKind.NON_OBJECT;
    }

    /// Creates a value reference from an existing storage read.
    /// Reads from locals/parameters/fields remain `BORROWED` producers even when the value later
    /// needs pointer-shape conversion, because the conversion changes representation only.
    public @NotNull ValueRef valueOfVar(@NotNull LirVariable variable) {
        return new VarValue(variable, resolvePtrKind(variable.type()), renderVariableStorageExpr(variable));
    }

    /// Creates a value reference by explicitly casting a variable expression to `castType`.
    /// This is expression-only and must not be used as an assignment target.
    /// Cast/render helpers keep the source storage provenance, so the returned value stays
    /// `BORROWED` unless a fresh producer explicitly marks it as `OWNED`.
    public @NotNull ValueRef valueOfCastedVar(@NotNull LirVariable variable, @NotNull GdType castType) {
        var sourceType = variable.type();
        if (sourceType instanceof GdObjectType sourceObjectType && castType instanceof GdObjectType targetObjectType) {
            if (sourceObjectType.getTypeName().equals(targetObjectType.getTypeName())) {
                return valueOfVar(variable);
            }
            var sourceCode = renderVariableStorageExpr(variable);
            var convertedCode = convertObjectValueIfNeeded(
                    sourceCode,
                    PtrKind.FAT_PTR,
                    sourceObjectType,
                    targetObjectType
            );
            return valueOfExpr(convertedCode, castType, PtrKind.FAT_PTR);
        }
        if (sourceType instanceof GdObjectType || castType instanceof GdObjectType) {
            throw invalidInsn("Cannot cast between object and non-object types: '" +
                    sourceType.getTypeName() + "' -> '" + castType.getTypeName() + "'");
        }
        var castTypeCode = helper.renderGdTypeInC(castType);
        var castExpr = "(" + castTypeCode + ")" + renderVariableStorageExpr(variable);
        return valueOfExpr(castExpr, castType);
    }

    public @NotNull ValueRef valueOfVar(@NotNull String variableName) {
        var variable = func.getVariableById(variableName);
        if (variable == null) {
            throw new InvalidInsnException(func.getName(), currentBlock() != null ? currentBlock().id() : "unknown",
                    currentInsnIndex(), currentInsn != null ? currentInsn.toString() : "unknown",
                    "Variable '" + variableName + "' not found in function");
        }
        return valueOfVar(variable);
    }

    /// Creates a value reference from a raw C expression and type.
    /// PtrKind is auto-resolved from the type.
    ///
    /// Even for object-typed expressions this route stays `BORROWED`: it is only a raw wrapper over
    /// an existing C expression and must not be used for fresh object producers such as call/construct
    /// results. Fresh object routes must opt into `valueOfOwnedExpr(...)` explicitly so slot writes do
    /// not re-retain caller-owned results.
    /// If the expression denotes an existing addressable storage slot, use `valueOfAddressableExpr(...)`
    /// instead so copy-by-address paths can borrow `&expr` directly without first materializing a
    /// shallow temp.
    public @NotNull ValueRef valueOfExpr(@NotNull String code, @NotNull GdType type) {
        return new ExprValue(code, type, resolvePtrKind(type));
    }

    /// Creates a value reference from a raw C expression, type, and explicit pointer kind.
    /// This overload keeps the same `BORROWED` provenance contract as `valueOfExpr(code, type)`.
    public @NotNull ValueRef valueOfExpr(@NotNull String code, @NotNull GdType type, @NotNull PtrKind ptrKind) {
        return new ExprValue(code, type, ptrKind);
    }

    /// Creates an OWNED value reference from a raw C expression.
    /// Use this only for fresh object producer routes where the expression already transfers ownership
    /// to the current lowering site, for example direct constructor/materialization calls.
    public @NotNull ValueRef valueOfOwnedExpr(@NotNull String code, @NotNull GdType type, @NotNull PtrKind ptrKind) {
        // OWNED sources are consumed by destination slots and must not be owned again.
        return new ExprValue(code, type, ptrKind, OwnershipKind.OWNED);
    }

    /// Creates a BORROWED value reference for an already-existing addressable storage expression.
    /// This is narrower than `valueOfExpr(...)`: callers must only use it for lvalues whose address
    /// can be taken safely (for example `self->field` backing slots). The payoff is that copy paths
    /// can use `&expr` directly instead of shallow-copying the storage into a temp first.
    public @NotNull ValueRef valueOfAddressableExpr(@NotNull String code, @NotNull GdType type) {
        return new AddressableExprValue(code, type, resolvePtrKind(type));
    }

    /// Creates a value reference for a static StringName pointer literal.
    public @NotNull ValueRef valueOfStringNamePtrLiteral(@NotNull String value) {
        return new StringNamePtrLiteralValue(value);
    }

    /// Creates a value reference for a static String pointer literal.
    public @NotNull ValueRef valueOfStringPtrLiteral(@NotNull String value) {
        return new StringPtrLiteralValue(value);
    }

    /// Creates a value reference for a C `const char*` string literal.
    public @NotNull ValueRef valueOfCStringLiteral(@NotNull String value) {
        return new CStringLiteralValue(value);
    }

    /// Creates a target reference from a variable.
    ///
    /// Throws InvalidInsnException if the variable is a reference variable (ref=true).
    /// Coroutine frame parameters/captures are exempt: their `ref` flag describes the borrowed
    /// thunk boundary, while the frame field itself is writable owning storage (§3.10).
    public @NotNull TargetRef targetOfVar(@NotNull LirVariable variable) {
        if (variable.ref() && !isCoroutineFrameParameter(variable) && !isCoroutineFrameCapture(variable)) {
            throw invalidInsn("Cannot assign to reference variable '" + variable.id() + "'");
        }
        return new VarTargetRef(variable, renderVariableStorageExpr(variable), isEffectivelyRef(variable));
    }

    /// Creates an assignment-only target reference from a raw C lvalue expression.
    ///
    /// This target is intentionally limited to assignment paths (`assignVar` / `assignExpr`).
    /// Do not use it for result targets of `callAssign`, return-slot flow, or discard flow.
    public @NotNull TargetRef targetOfExpr(@NotNull String code, @NotNull GdType type) {
        return new ExprTargetRef(code, type);
    }

    /// Creates a special target reference that discards call return values.
    public @NotNull DiscardRef discardRef() {
        return DiscardRef.INSTANCE;
    }

    public @NotNull CBodyBuilder assignVar(@NotNull TargetRef target, @NotNull ValueRef value) {
        checkTargetAssignable(target);
        checkAssignable(value.type(), target.type());

        var targetCode = target.generateCode();
        var targetType = target.type();
        var canDestroyOldValue = canDestroyOldValue(target);

        if (targetType instanceof GdObjectType objType) {
            // Object writes only need representation conversion; alias-sensitive borrow handling is specific
            // to non-object value-semantic slots where destroy(target) can invalidate source_ptr.
            var rhsResult = prepareRhsValue(value, targetType);
            emitTempDecls(rhsResult.temps());
            // Route all object writes through one ownership-aware slot write path.
            emitObjectSlotWrite(
                    targetCode,
                    objType,
                    canDestroyOldValue && !checkInPrepareBlock(),
                    rhsResult.code(),
                    value.ptrKind(),
                    value.type() instanceof GdObjectType objectType ? objectType : null,
                    value.ownership()
            );
            markTargetInitialized(target);
            emitTempDestroys(rhsResult.temps());
            return this;
        } else {
            var rhsPlan = prepareAssignedNonObjectRhs(target, value);
            emitTempDecls(rhsPlan.tempsToDeclare());
            emitNonObjectSlotWrite(targetCode, targetType, canDestroyOldValue, rhsPlan.code());
            markTargetInitialized(target);
            emitTempDestroys(rhsPlan.tempsToDestroy());
            return this;
        }
    }

    /// Assigns a raw expression into a target variable.
    public @NotNull CBodyBuilder assignExpr(@NotNull TargetRef target, @NotNull String expr, @NotNull GdType type) {
        return assignVar(target, valueOfExpr(expr, type));
    }

    /// Assigns a raw expression into a target variable with an explicit pointer kind.
    public @NotNull CBodyBuilder assignExpr(@NotNull TargetRef target, @NotNull String expr, @NotNull GdType type, @NotNull PtrKind ptrKind) {
        return assignVar(target, valueOfExpr(expr, type, ptrKind));
    }

    /// Emits constructor-time property initializer application as a direct backing-field first write.
    /// This route intentionally stays separate from setter dispatch and from ordinary overwrite stores:
    /// - it never destroys/releases an old field value
    /// - object writes still reuse the unified ptr-conversion and ownership-consume rules
    /// - OWNED rhs is consumed directly; BORROWED rhs is retained by the field
    public @NotNull CBodyBuilder applyPropertyInitializerFirstWrite(@NotNull String targetCode,
                                                                    @NotNull GdType targetType,
                                                                    @NotNull String rhsCode,
                                                                    @NotNull GdType rhsType,
                                                                    @NotNull PtrKind rhsPtrKind,
                                                                    @NotNull OwnershipKind ownership) {
        checkAssignable(rhsType, targetType);
        if (targetType instanceof GdObjectType targetObjType) {
            if (!(rhsType instanceof GdObjectType rhsObjType)) {
                throw invalidInsn(
                        "Property initializer first-write target '" + targetObjType.getTypeName()
                                + "' requires object rhs, but got '" + rhsType.getTypeName() + "'"
                );
            }
            emitObjectSlotWrite(
                    targetCode,
                    targetObjType,
                    false,
                    rhsCode,
                    rhsPtrKind,
                    rhsObjType,
                    ownership
            );
            return this;
        }
        emitNonObjectSlotWrite(targetCode, targetType, false, rhsCode);
        return this;
    }

    /// Moves a fresh OWNED call result into an already-initialized target slot.
    /// Value-semantic destroyable targets apply destroy-then-move: the call result is captured
    /// into a carrier temp, the old target value is destroyed, and the carrier is moved in by
    /// plain struct assignment (the carrier is intentionally never destroyed afterwards — its
    /// value now belongs to the target). Unlike `assignVar(target, valueOfOwnedExpr(...))`, no
    /// copy construction or carrier destroy is emitted. Object targets delegate to the unified
    /// object slot write with OWNED consumption (capture old -> assign -> release old, no re-own).
    /// Non-destroyable targets take the plain direct assignment.
    ///
    /// Used by the static initializer overwrite route, where the hidden `_field_init_<name>`
    /// helper result must move into the backing variable after the materialized default is
    /// destroyed.
    public @NotNull CBodyBuilder moveOwnedCallIntoSlot(@NotNull TargetRef target,
                                                       @NotNull String callExpr,
                                                       @NotNull GdType returnType) {
        checkTargetAssignable(target);
        checkAssignable(returnType, target.type());
        var targetCode = target.generateCode();
        var targetType = target.type();
        if (targetType instanceof GdObjectType targetObjType) {
            if (!(returnType instanceof GdObjectType returnObjType)) {
                throw invalidInsn(
                        "moveOwnedCallIntoSlot target '" + targetObjType.getTypeName()
                                + "' requires object return type, but got '" + returnType.getTypeName() + "'"
                );
            }
            // Materialize the call result BEFORE capturing the old slot value: initializer calls
            // may re-enter and overwrite the same storage, and capturing first would leak that
            // re-entrant reference when the result is assigned over it.
            var carrier = newTempVariable("owned_move", targetType, callExpr);
            declareTempVar(carrier);
            // The carrier hands its reference to the slot (OWNED consume: capture old -> assign
            // -> release old, no re-own) and is intentionally never destroyed afterwards.
            emitObjectSlotWrite(
                    targetCode,
                    targetObjType,
                    canDestroyOldValue(target) && !checkInPrepareBlock(),
                    carrier.name(),
                    PtrKind.FAT_PTR,
                    returnObjType,
                    OwnershipKind.OWNED
            );
            markTargetInitialized(target);
            return this;
        }
        if (!targetType.isDestroyable()) {
            emitNonObjectSlotWrite(targetCode, targetType, false, callExpr);
            markTargetInitialized(target);
            return this;
        }
        var carrier = newTempVariable("owned_move", targetType, callExpr);
        declareTempVar(carrier);
        emitNonObjectSlotWrite(
                targetCode,
                targetType,
                canDestroyOldValue(target) && !checkInPrepareBlock(),
                carrier.name()
        );
        markTargetInitialized(target);
        return this;
    }

    /// Assigns a global enum constant to a target variable.
    public @NotNull CBodyBuilder assignGlobalConst(@NotNull TargetRef target,
                                                   @NotNull String enumName,
                                                   @NotNull String valueName) {
        var globalEnum = classRegistry().findGlobalEnum(enumName);
        if (globalEnum == null) {
            throw invalidInsn("Global enum '" + enumName + "' not found");
        }
        var matchedValue = globalEnum.values().stream()
                .filter(value -> value.name().equals(valueName))
                .findFirst()
                .orElse(null);
        if (matchedValue == null) {
            throw invalidInsn("Global enum value '" + valueName + "' not found in enum '" + enumName + "'");
        }
        return assignVar(target, valueOfExpr(Long.toString(matchedValue.value()), GdIntType.INT));
    }

    /// Assigns a top-level Godot global constant to a target variable.
    public @NotNull CBodyBuilder assignGlobalConstant(@NotNull TargetRef target,
                                                      @NotNull String constantName) {
        var globalConstant = classRegistry().findGlobalConstant(constantName);
        if (globalConstant == null) {
            throw invalidInsn("Global constant '" + constantName + "' not found");
        }
        return assignVar(target, valueOfExpr(Long.toString(globalConstant.value()), GdIntType.INT));
    }

    public @NotNull CBodyBuilder callVoid(@NotNull String funcName, @NotNull List<ValueRef> args) {
        return callVoid(funcName, args, null);
    }

    /// Emits a void call.
    /// This builder does not validate global/utility function signatures.
    /// Caller is responsible for argument count/type checks.
    /// When `varargs == null`, vararg tail generation is skipped.
    /// When `varargs != null`, the vararg tail is always generated, including the empty case
    /// (which emits `NULL,(godot_int)0`).
    public @NotNull CBodyBuilder callVoid(@NotNull String funcName,
                                          @NotNull List<ValueRef> args,
                                          @Nullable List<ValueRef> varargs) {
        recordUsedGodotBindingCall(funcName);
        return emitVoidCall(funcName, args, varargs);
    }

    public @NotNull CBodyBuilder callVoidVtableSlot(@NotNull CVtablePlanner.VtableSlot slot,
                                                    @NotNull TempVar vtRecv,
                                                    @NotNull List<ValueRef> args) {
        return callVoidVtableSlot(slot, vtRecv, args, null);
    }

    /// Void call through a vtable slot (vtable plan §2.6). The callee expression
    /// (`<I>_class_vtable(<vtRecv>.ptr)->m_<method>`) is constructed HERE from the slot identity
    /// and the materialized introducer-fat receiver temp — callers never assemble callee strings.
    /// Emission is identical to `callVoid` except that no Godot binding usage is recorded: the
    /// callee is not a fixed `godot_*` binding symbol (godot_binding_implementation.md 使用集收集合同).
    public @NotNull CBodyBuilder callVoidVtableSlot(@NotNull CVtablePlanner.VtableSlot slot,
                                                    @NotNull TempVar vtRecv,
                                                    @NotNull List<ValueRef> args,
                                                    @Nullable List<ValueRef> varargs) {
        return emitVoidCall(requireVtableSlotCalleeExpr(slot, vtRecv, args), args, varargs);
    }

    /// Renders the vtable-slot callee expression and pins the core invariants: `vtRecv` must be
    /// the materialized fat self of the slot INTRODUCER's type (the accessor takes `<I>*` and the
    /// slot function pointer's self parameter is the introducer fat type), and it must be the
    /// call's first argument — the vtable and the receiver are always read from the same object.
    private @NotNull String requireVtableSlotCalleeExpr(@NotNull CVtablePlanner.VtableSlot slot,
                                                        @NotNull TempVar vtRecv,
                                                        @NotNull List<ValueRef> args) {
        if (!(vtRecv.type() instanceof GdObjectType vtRecvType)
                || !vtRecvType.getTypeName().equals(slot.introducerClassName())) {
            throw new IllegalArgumentException("vtRecv temp must carry the slot introducer fat type '" +
                    slot.introducerClassName() + "', got '" + vtRecv.type().getTypeName() + "'");
        }
        if (args.isEmpty() || args.getFirst() != vtRecv) {
            throw new IllegalArgumentException(
                    "vtable slot call first argument must be the same vtRecv temp used for the callee");
        }
        return helper().renderVtableSlotCalleeExpr(slot.introducerClassName(), vtRecv.name() + ".ptr", slot.methodName());
    }

    private @NotNull CBodyBuilder emitVoidCall(@NotNull String calleeExpr,
                                               @NotNull List<ValueRef> args,
                                               @Nullable List<ValueRef> varargs) {
        RenderResult argsResult;
        if (varargs == null) {
            argsResult = renderArgs(calleeExpr, args);
            emitTempDecls(argsResult.temps());
        } else {
            argsResult = renderArgsWithVarargs(calleeExpr, args, varargs);
            emitTempDecls(argsResult.temps());
            if (argsResult.preCode() != null) {
                out.append(argsResult.preCode());
            }
        }
        out.append(calleeExpr).append("(").append(argsResult.code()).append(");\n");
        emitTempDestroys(argsResult.temps());
        return this;
    }

    public @NotNull CBodyBuilder callAssign(@NotNull TargetRef target,
                                            @NotNull String funcName,
                                            @NotNull GdType returnType,
                                            @NotNull List<ValueRef> args) {
        return callAssign(target, funcName, returnType, args, null);
    }

    /// Emits a call with assignment/discard handling.
    /// This builder does not validate global/utility function signatures.
    /// Caller is responsible for argument count/type checks.
    /// When `varargs == null`, vararg tail generation is skipped.
    /// When `varargs != null`, the vararg tail is always generated, including the empty case
    /// (which emits `NULL,(godot_int)0`).
    public @NotNull CBodyBuilder callAssign(@NotNull TargetRef target,
                                            @NotNull String funcName,
                                            @NotNull GdType returnType,
                                            @NotNull List<ValueRef> args,
                                            @Nullable List<ValueRef> varargs) {
        recordUsedGodotBindingCall(funcName);
        return emitAssignCall(target, funcName, returnType, args, varargs);
    }

    public @NotNull CBodyBuilder callAssignVtableSlot(@NotNull TargetRef target,
                                                      @NotNull CVtablePlanner.VtableSlot slot,
                                                      @NotNull TempVar vtRecv,
                                                      @NotNull GdType returnType,
                                                      @NotNull List<ValueRef> args) {
        return callAssignVtableSlot(target, slot, vtRecv, returnType, args, null);
    }

    /// Assigning call through a vtable slot (vtable plan §2.6); the callee expression is
    /// constructed internally from `slot` + `vtRecv` (see `requireVtableSlotCalleeExpr`). No Godot
    /// binding usage is recorded (callee is not a `godot_*` symbol). Object returns follow the
    /// internal FAT_PTR path — vtable slots point at internal GDCC functions, never the `godot_*`
    /// raw-producer backlog, so `resolveCallResultPtrKind` yields the same result as a direct
    /// GDCC call; discard/temp-destroy semantics are unchanged.
    public @NotNull CBodyBuilder callAssignVtableSlot(@NotNull TargetRef target,
                                                      @NotNull CVtablePlanner.VtableSlot slot,
                                                      @NotNull TempVar vtRecv,
                                                      @NotNull GdType returnType,
                                                      @NotNull List<ValueRef> args,
                                                      @Nullable List<ValueRef> varargs) {
        return emitAssignCall(target, requireVtableSlotCalleeExpr(slot, vtRecv, args), returnType, args, varargs);
    }

    private @NotNull CBodyBuilder emitAssignCall(@NotNull TargetRef target,
                                                 @NotNull String calleeExpr,
                                                 @NotNull GdType returnType,
                                                 @NotNull List<ValueRef> args,
                                                 @Nullable List<ValueRef> varargs) {
        var discardResult = target instanceof DiscardRef;
        if (!discardResult) {
            checkTargetAssignable(target);
        }
        validateCallAssignReturnContract(calleeExpr, returnType, target, discardResult);

        RenderResult argsResult;
        if (varargs == null) {
            argsResult = renderArgs(calleeExpr, args);
            emitTempDecls(argsResult.temps());

            var callExpr = calleeExpr + "(" + argsResult.code() + ")";
            if (discardResult) {
                // Discarded non-void calls still need lifecycle cleanup for destroyable returns.
                emitDiscardedCall(calleeExpr, callExpr, returnType);
            } else {
                emitCallResultAssignment(target, calleeExpr, returnType, callExpr);
            }
        } else {
            argsResult = renderArgsWithVarargs(calleeExpr, args, varargs);
            emitTempDecls(argsResult.temps());
            if (argsResult.preCode() != null) {
                out.append(argsResult.preCode());
            }

            var callExpr = calleeExpr + "(" + argsResult.code() + ")";
            if (discardResult) {
                // Discarded non-void calls still need lifecycle cleanup for destroyable returns.
                emitDiscardedCall(calleeExpr, callExpr, returnType);
            } else {
                emitCallResultAssignment(target, calleeExpr, returnType, callExpr);
            }
        }
        emitTempDestroys(argsResult.temps());
        return this;
    }

    /// Unified codegen entry for the `assert_object_live` hard-fail guard.
    /// Backend paths that need a validated receiver/owner must request this guard instead of
    /// emitting ad-hoc inline liveness branches.
    /// Uses generic `gdcc_object_is_null_raw_and_id(raw, instance_id)` on the fat pointer fields;
    /// never recovers ID from raw and never generates per-class assert helpers.
    /// The implicit error edge publishes a default return via `returnDefault()` so non-object
    /// `_return_val` slots (Signal, Callable, String, ...) are initialized before `__finally__`.
    public @NotNull CBodyBuilder emitAssertObjectLiveGuard(@NotNull LirVariable objectVariable) {
        if (checkInFinallyBlock()) {
            throw invalidInsn("assert_object_live must not appear in __finally__ block");
        }
        if (!(objectVariable.type() instanceof GdObjectType)) {
            throw invalidInsn("assert_object_live target '" + objectVariable.id() + "' must be an object type, got '" +
                    objectVariable.type().getTypeName() + "'");
        }
        var objectCode = valueOfVar(objectVariable).generateCode();
        var objectName = escapeStringLiteral(objectVariable.id());
        appendLine("if (" + renderObjectIsNullExpr(objectCode) + ") {");
        appendLine("    GDCC_PRINT_RUNTIME_ERROR(\"assert_object_live failed: object '" + objectName +
                "' is null or freed\", __func__, __FILE__, __LINE__);");
        returnDefault();
        appendLine("}");
        return this;
    }

    /// Unified codegen entry for the user-level `assert` guard.
    ///
    /// Emits the assertion failure shape: when the already-bool condition is false, report through
    /// `gdcc_assert_failed` (which reuses the `GDCC_PRINT_RUNTIME_ERROR` channel) and then leave
    /// the function via `returnDefault()`, exactly like `emitAssertObjectLiveGuard`. The message
    /// argument is passed as `const godot_String*`: locals are addressed (`&$msg`), `ref`
    /// parameters are already pointers, and a missing message lowers to `NULL`. The condition and
    /// message types are validated by `AssertInsnGen` before delegation; the `__finally__`
    /// prohibition is re-checked here because a failing assert inside `__finally__` would
    /// self-jump forever.
    public @NotNull CBodyBuilder emitAssertGuard(
            @NotNull LirVariable conditionVariable,
            @Nullable LirVariable messageVariableOrNull
    ) {
        if (checkInFinallyBlock()) {
            throw invalidInsn("assert must not appear in __finally__ block");
        }
        var conditionCode = valueOfVar(conditionVariable).generateCode();
        var messageCode = messageVariableOrNull == null
                ? "NULL"
                : renderArgument(valueOfVar(messageVariableOrNull), false).code();
        appendLine("if (!(" + conditionCode + ")) {");
        appendLine("    gdcc_assert_failed(" + messageCode + ", __func__, __FILE__, __LINE__);");
        returnDefault();
        appendLine("}");
        return this;
    }

    /// Nullness/assert raw operand from a fat pointer without liveness validation and without
    /// dereferencing a potentially freed GDCC wrapper. The helper only treats this value as a
    /// NULL-ness sentinel, then decides liveness from `instance_id`.
    /// Engine fat pointers already store a Godot raw pointer. GDCC fat pointers store a wrapper
    /// pointer; casting it to GDExtensionObjectPtr is only safe as a NULL check sentinel.
    public @NotNull String renderNullQueryRawOperand(@NotNull String fatPtrCode) {
        return "(GDExtensionObjectPtr)(" + fatPtrCode + ").ptr";
    }

    /// Equality-normalized raw Godot object pointer used as the `==`/`!=` comparison key.
    ///
    /// Contract:
    /// - null ∪ freed → `NULL` via `gdcc_object_is_null_raw_and_id`
    ///   (raw operand is only a NULL sentinel; GDCC wrappers are never dereferenced here)
    /// - live engine → `(GDExtensionObjectPtr)value.ptr`
    /// - live GDCC → `gdcc_<Type>_fat_ptr_live_object(value)` (never unvalidated `Type_object_ptr`)
    ///
    /// Does not use `instance_id` as the comparison key; callers compare two normalized raws with
    /// `==`/`!=`. Separated from null/assert emitters which only need the nullness query.
    public @NotNull String renderEqualityNormalizedRaw(@NotNull String fatPtrCode, @NotNull GdObjectType objType) {
        var isNullExpr = renderObjectIsNullExpr(fatPtrCode);
        var liveRaw = objType.checkGdccType(classRegistry())
                ? renderLiveGodotObjectPtr(fatPtrCode, objType)
                : renderNullQueryRawOperand(fatPtrCode);
        return "(" + isNullExpr + " ? NULL : " + liveRaw + ")";
    }

    /// Validated live raw Godot object pointer from a fat pointer (RefCounted fast path or ObjectDB).
    public @NotNull String renderLiveGodotObjectPtr(@NotNull String fatPtrCode, @NotNull GdObjectType objType) {
        var fatType = helper.renderObjectFatPtrStorageType(objType);
        return fatType + "_live_object(" + fatPtrCode + ")";
    }

    /// Captures a live raw Godot object pointer into the target fat pointer type.
    public @NotNull String renderFatPtrFromRaw(@NotNull String rawCode, @NotNull GdObjectType objType) {
        var fatType = helper.renderObjectFatPtrStorageType(objType);
        return fatType + "_from_raw((GDExtensionObjectPtr)(" + rawCode + "))";
    }

    /// Fat-pointer upcast helper name for an assignable source -> target pair.
    public @NotNull String renderObjectFatPtrUpcastHelperName(@NotNull GdObjectType sourceType,
                                                              @NotNull GdObjectType targetType) {
        var sourceSpec = helper.requireObjectFatPtrSpec(sourceType, "object upcast source");
        var targetSpec = helper.requireObjectFatPtrSpec(targetType, "object upcast target");
        // Must match ObjectFatPtrUpcastSpec.forPair helperName (cIdentifier, not raw class name).
        return sourceSpec.fatPtrTypeName() + "_upcast_to_" + targetSpec.cIdentifier();
    }

    /// Null/freed query expression for an object fat pointer storage.
    public @NotNull String renderObjectIsNullExpr(@NotNull String fatPtrCode) {
        return "gdcc_object_is_null_raw_and_id(" + renderNullQueryRawOperand(fatPtrCode) + ", " +
                fatPtrCode + ".instance_id)";
    }

    /// Common logic for writing a fresh call result into a target variable.
    /// Call/construct/helper returns are treated as `OWNED` producers and therefore flow into the
    /// slot-write core without an extra retain on the new value.
    /// This consumes the producer's strong reference: exact-engine vararg helpers establish it via
    /// `renderEngineMethodHelperVarargObjectReturnOwnStmt`, and the slot takes it over here (released
    /// later by overwrite or `__finally__` cleanup). Treating such a return as BORROWED would retain
    /// twice and leak.
    private void emitCallResultAssignment(@NotNull TargetRef target,
                                          @NotNull String cFuncName,
                                          @NotNull GdType returnType,
                                          @NotNull String callExpr) {
        var targetCode = target.generateCode();
        var targetType = target.type();
        var canDestroyOldValue = canDestroyOldValue(target);

        if (targetType instanceof GdObjectType targetObjType) {
            // Object targets only accept object-return calls so ptr-kind conversion stays type-safe.
            if (!(returnType instanceof GdObjectType returnObjType)) {
                throw invalidInsn("CallAssign target '" + targetObjType.getTypeName() +
                        "' requires object return type, but function '" + cFuncName +
                        "' returns '" + returnType.getTypeName() + "'");
            }
            var rhsPtrKind = resolveCallResultPtrKind(cFuncName, returnObjType);
            emitObjectSlotWrite(
                    targetCode,
                    targetObjType,
                    canDestroyOldValue && !checkInPrepareBlock(),
                    callExpr,
                    rhsPtrKind,
                    returnObjType,
                    OwnershipKind.OWNED
            );
            markTargetInitialized(target);
            return;
        }

        emitNonObjectSlotWrite(targetCode, targetType, canDestroyOldValue, callExpr);
        markTargetInitialized(target);
    }

    public @NotNull CBodyBuilder jump(@NotNull String blockId) {
        out.append("goto ").append(blockId).append(";\n");
        return this;
    }

    public @NotNull CBodyBuilder jumpIf(@NotNull ValueRef condition, @NotNull String trueBlockId, @NotNull String falseBlockId) {
        var conditionResult = renderCondition(condition);
        emitTempDecls(conditionResult.temps());
        out.append("if (").append(conditionResult.code()).append(") goto ").append(trueBlockId).append(";\n");
        out.append("else goto ").append(falseBlockId).append(";\n");
        emitTempDestroys(conditionResult.temps());
        return this;
    }

    public @NotNull CBodyBuilder returnVoid() {
        var returnType = func.getReturnType();
        if (!checkInFinallyBlock()) {
            if (!(returnType instanceof GdVoidType)) {
                throw invalidInsn("Cannot return void from non-void function");
            }
            out.append("goto __finally__;\n");
            return this;
        }
        out.append("return;\n");
        return this;
    }

    /// Returns default value for current function return type.
    /// - void: emits `goto __finally__` (or `return` when already in `__finally__`)
    /// - non-void: emits return with `renderDefaultValueExpr(returnType)` semantics
    public @NotNull CBodyBuilder returnDefault() {
        var returnType = func.getReturnType();
        if (returnType instanceof GdVoidType) {
            return returnVoid();
        }
        var defaultExpr = helper.renderDefaultValueExprInC(returnType);
        return returnValue(valueOfExpr(defaultExpr, returnType));
    }

    public @NotNull CBodyBuilder returnTerminal() {
        var returnType = func.getReturnType();
        if (!checkInFinallyBlock()) {
            throw invalidInsn("Cannot return " + RETURN_SLOT_NAME + " from non finally block");
        }
        if (returnType instanceof GdVoidType) {
            throw invalidInsn("Cannot return " + RETURN_SLOT_NAME + " from void function");
        }
        if (coroContext != null) {
            return returnTerminalCoroutine(returnType);
        }
        out.append("return ").append(RETURN_SLOT_NAME).append(";\n");
        return this;
    }

    /// Coroutine-body terminal: consume `_return_val` into the frame's typed return slot
    /// (move semantics - `_return_val` is considered cleared afterwards, matching the ordinary
    /// move-return contract) and return from the `void __coro_body` C function. `__finally__`
    /// runs exactly once per coroutine lifetime (normal completion or cancel-resume), so the
    /// destroy-before-overwrite guard for destroyable types is defensive by design.
    private @NotNull CBodyBuilder returnTerminalCoroutine(@NotNull GdType returnType) {
        if (returnType.isDestroyable()) {
            var destroyOldStmt = helper.renderLambdaCaptureFreeStmt(returnType, CCoroutineFrameContext.retFieldExpr());
            if (!destroyOldStmt.isEmpty()) {
                out.append("if (").append(CCoroutineFrameContext.retInitializedExpr()).append(") {\n");
                out.append(destroyOldStmt).append("\n");
                out.append("}\n");
            }
        }
        out.append(CCoroutineFrameContext.retFieldExpr()).append(" = ").append(RETURN_SLOT_NAME).append(";\n");
        out.append(CCoroutineFrameContext.retInitializedExpr()).append(" = true;\n");
        out.append("return;\n");
        return this;
    }

    /// Returns a value from the current function.
    /// Generated non-void LIR must publish through `_return_val` and let `__finally__` emit the
    /// terminal return. The direct-return branch below remains a low-level escape hatch for manual
    /// builder use and tests, but `ControlFlowIntegrityValidator` rejects non-void `__finally__`
    /// blocks that try to return anything other than `_return_val`.
    public @NotNull CBodyBuilder returnValue(@NotNull ValueRef value) {
        var returnType = func.getReturnType();
        if (returnType instanceof GdVoidType) {
            throw invalidInsn("Cannot return a value from void function");
        }
        checkAssignable(value.type(), returnType);
        var movedReturnSource = resolveMovedObjectReturnSource(value, returnType);

        var returnResult = prepareReturnValue(value);
        emitTempDecls(returnResult.temps());
        var returnCode = returnResult.code();

        if (!checkInFinallyBlock()) {
            if (returnType instanceof GdObjectType objType) {
                // Object return publishing is modeled as writing `_return_val`, not as a blanket
                // "retain everything before function exit" rule. Borrowed sources retain here;
                // owned sources are consumed here.
                emitObjectSlotWrite(
                        RETURN_SLOT_NAME,
                        objType,
                        true,
                        returnCode,
                        value.ptrKind(),
                        value.type() instanceof GdObjectType objectType ? objectType : null,
                        movedReturnSource != null ? OwnershipKind.OWNED : value.ownership()
                );
                if (movedReturnSource != null) {
                    // Returning an owning local object slot transfers that ownership to `_return_val`.
                    // Clear using the source variable's fat pointer type (may differ after upcast).
                    var sourceClearType = movedReturnSource.variable().type() instanceof GdObjectType sourceObjectType
                            ? sourceObjectType
                            : objType;
                    out.append(movedReturnSource.generateCode()).append(" = ")
                            .append(helper.renderDefaultValueExprInC(sourceClearType)).append(";\n");
                }
            } else {
                // Keep non-object return-slot write as a direct assignment.
                // _return_val for non-object return types is not modeled as a regular managed slot:
                // we intentionally avoid coupling this path to assign/callAssign target initialization hooks.
                out.append(RETURN_SLOT_NAME).append(" = ").append(returnCode).append(";\n");
            }
            emitTempDestroys(returnResult.temps());
            out.append("goto __finally__;\n");
            return this;
        }

        if (returnType instanceof GdObjectType objType) {
            // This branch is intentionally not the published LIR return surface for non-void
            // functions. It exists so the builder can still emit direct C returns in manual/test
            // scenarios after the value has already been prepared.
            var sourceObjType = value.type() instanceof GdObjectType objectType ? objectType : null;
            returnCode = convertObjectValueIfNeeded(returnCode, value.ptrKind(), sourceObjType, objType);
        }

        if (returnResult.temps().isEmpty()) {
            out.append("return ").append(returnCode).append(";\n");
            return this;
        }

        var retTemp = newTempVariable("ret", returnType);
        declareTempVar(retTemp);
        initTempVar(retTemp, valueOfExpr(returnCode, returnType, resolvePtrKind(returnType)));
        emitTempDestroys(returnResult.temps());
        out.append("return ").append(retTemp.name()).append(";\n");
        return this;
    }

    /// Only ordinary local object slots may transfer ownership directly into `_return_val`.
    /// Parameters, ref aliases, captures, and non-slot expressions stay on the borrowed-return path,
    /// so the publish boundary itself performs the retain when needed.
    private @Nullable VarValue resolveMovedObjectReturnSource(@NotNull ValueRef value, @NotNull GdType returnType) {
        if (!(returnType instanceof GdObjectType)) {
            return null;
        }
        if (!(value instanceof VarValue varValue)) {
            return null;
        }
        var variable = varValue.variable();
        if (variable.ref() || func.checkVariableParameter(variable.id())) {
            return null;
        }
        if (func.getCapture(variable.id()) != null) {
            return null;
        }
        return varValue;
    }

    private @NotNull RenderResult renderArgs(@NotNull String funcName, @NotNull List<ValueRef> args) {
        var requireGodotRawPtr = checkGlobalFuncRequireGodotRawPtr(funcName);
        var rendered = new StringBuilder();
        var temps = new ArrayList<TempVar>();
        for (var i = 0; i < args.size(); i++) {
            if (i > 0) {
                rendered.append(", ");
            }
            var value = args.get(i);
            var argResult = renderArgument(value, requireGodotRawPtr);
            rendered.append(argResult.code());
            temps.addAll(argResult.temps());
        }
        return new RenderResult(rendered.toString(), temps);
    }

    private @NotNull RenderResult renderArgsWithVarargs(@NotNull String funcName,
                                                        @NotNull List<ValueRef> args,
                                                        @NotNull List<ValueRef> varargs) {
        var fixedArgsResult = renderArgs(funcName, args);
        var argvRenderResult = renderVarargArgv(varargs);

        var rendered = new StringBuilder(fixedArgsResult.code());
        if (!fixedArgsResult.code().isEmpty()) {
            rendered.append(", ");
        }
        rendered.append(argvRenderResult.code());
        rendered.append(", (godot_int)").append(varargs.size());

        var temps = new ArrayList<>(fixedArgsResult.temps());
        temps.addAll(argvRenderResult.temps());
        return new RenderResult(rendered.toString(), temps, argvRenderResult.preCode());
    }

    /// Checks if a value of sourceType can be assigned to a variable of targetType.
    /// Throws InvalidInsnException if not assignable.
    private void checkAssignable(@NotNull GdType sourceType, @NotNull GdType targetType) {
        if (!classRegistry().checkAssignable(sourceType, targetType)) {
            throw invalidInsn("Cannot assign value of type '" + sourceType.getTypeName() +
                    "' to variable of type '" + targetType.getTypeName() + "'");
        }
    }

    private void checkTargetAssignable(@NotNull TargetRef target) {
        if (target.isRef()) {
            throw invalidInsn("Cannot assign to reference variable");
        }
    }

    /// Validates `callAssign` return type contract.
    /// - return type must be explicit and non-void.
    /// - for object targets, return type must also be object to preserve pointer semantics.
    /// - for non-discard targets, return type must be assignable to target type.
    private void validateCallAssignReturnContract(@NotNull String funcName,
                                                  @NotNull GdType returnType,
                                                  @NotNull TargetRef target,
                                                  boolean discardResult) {
        if (returnType instanceof GdVoidType) {
            throw invalidInsn("CallAssign expects a non-void function: " + funcName);
        }
        if (!discardResult && target.type() instanceof GdObjectType targetObjType && !(returnType instanceof GdObjectType)) {
            // Emit a clear type error for object slots before entering object write logic.
            throw invalidInsn("CallAssign target '" + targetObjType.getTypeName() +
                    "' requires object return type, but function '" + funcName +
                    "' returns '" + returnType.getTypeName() + "'");
        }
        if (discardResult) {
            return;
        }
        checkAssignable(returnType, target.type());
    }

    /// Renders a ValueRef for a conditional expression without emitting code.
    private @NotNull RenderResult renderCondition(@NotNull ValueRef condition) {
        if (!(condition.type() instanceof GdBoolType)) {
            throw invalidInsn("jumpIf condition must be bool, got '" + condition.type().getTypeName() + "'");
        }
        return new RenderResult(condition.generateCode(), List.of());
    }

    @NotNull
    public static String renderStaticStringLiteral(@NotNull String value) {
        return "GD_STATIC_S(u8\"" + escapeStringLiteral(value) + "\")";
    }

    @NotNull
    public static String renderStaticStringNameLiteral(@NotNull String value) {
        return "GD_STATIC_SN(u8\"" + escapeStringLiteral(value) + "\")";
    }

    /// Renders the C expression of one type's zero/default return value for non-object types.
    /// Object defaults require a context-aware fat pointer type and must use
    /// `CGenHelper.renderDefaultValueExprInC(...)`.
    @NotNull
    public static String renderDefaultValueExpr(@NotNull GdType type) {
        return switch (type) {
            case GdCompilerType _ -> throw new IllegalArgumentException(
                    "compiler-only type leaked into default value expression: " + type.getTypeName()
            );
            case GdVoidType _ -> "";
            case GdBoolType _ -> "false";
            case GdIntType _ -> "0";
            case GdFloatType _ -> "0.0";
            case GdObjectType objectType -> throw new IllegalArgumentException(
                    "object default values require CGenHelper.renderDefaultValueExprInC: " + objectType.getTypeName()
            );
            case GdNilType _, GdVariantType _ -> "godot_new_Variant_nil()";
            case GdContainerType containerType -> switch (containerType) {
                case GdArrayType _ -> "godot_new_Array()";
                case GdDictionaryType _ -> "godot_new_Dictionary()";
                case GdPackedArrayType packedArrayType -> "godot_new_" + packedArrayType.getTypeName() + "()";
            };
            default -> "godot_new_" + type.getTypeName() + "()";
        };
    }

    private @NotNull RenderResult renderVarargArgv(@NotNull List<ValueRef> varargs) {
        if (varargs.isEmpty()) {
            return new RenderResult("NULL", List.of());
        }

        var pointers = new ArrayList<String>(varargs.size());
        var temps = new ArrayList<TempVar>();
        for (var arg : varargs) {
            if (!classRegistry().checkAssignable(arg.type(), GdVariantType.VARIANT)) {
                throw invalidInsn("Vararg argument must be Variant, got '" + arg.type().getTypeName() + "'");
            }
            var pointerResult = renderValueAddress(arg);
            pointers.add(pointerResult.code());
            temps.addAll(pointerResult.temps());
        }

        var argvName = newTempName("argv");
        var preCode = "const godot_Variant* " + argvName + "[] = { " + String.join(", ", pointers) + " };\n";
        return new RenderResult(argvName, temps, preCode);
    }

    /// Renders a ValueRef as a C argument, adding '&' if needed for pass-by-reference types.
    /// - Primitive types and object fat pointers: pass by value (no &)
    /// - Value-semantic types (String, StringName, Variant, etc.): pass by pointer (&)
    /// When requireGodotRawPtr is true, fat pointers expand to validated live Godot raw pointers.
    public @NotNull RenderResult renderArgument(@NotNull ValueRef value, boolean requireGodotRawPtr) {
        var type = value.type();

        if (requireGodotRawPtr && type instanceof GdObjectType objType) {
            if (value.ptrKind() == PtrKind.NON_OBJECT) {
                throw invalidInsn("Internal ptr kind/type mismatch for object argument '" + value.generateCode() +
                        "': ptrKind=NON_OBJECT, type='" + type.getTypeName() + "'");
            }
            if (value.ptrKind() == PtrKind.RAW_PRODUCER) {
                return new RenderResult("(GDExtensionObjectPtr)(" + value.generateCode() + ")", List.of());
            }
            return new RenderResult(renderLiveGodotObjectPtr(value.generateCode(), objType), List.of());
        }
        if (requireGodotRawPtr && value.ptrKind() != PtrKind.NON_OBJECT) {
            throw invalidInsn("Internal ptr kind/type mismatch for non-object argument '" + value.generateCode() +
                    "': ptrKind=" + value.ptrKind() + ", type='" + type.getTypeName() + "'");
        }

        // Special handling for variable references that are already refs
        if (value instanceof VarValue varValue && isEffectivelyRef(varValue.variable())) {
            // ref variables are already pointers, use as-is
            return new RenderResult(value.generateCode(), List.of());
        }

        // Determine if we need to add &
        if (needsAddressOf(type)) {
            return renderValueAddress(value);
        }
        return new RenderResult(value.generateCode(), List.of());
    }

    /// Determines if a type needs '&' when passed as argument.
    /// - Primitives (bool, int, float): NO
    /// - Object fat pointers: NO (value-shaped struct by value)
    /// - Value-semantic types (String, Variant, Array, etc.): YES
    private boolean needsAddressOf(@NotNull GdType type) {
        if (type instanceof GdCompilerType compilerType) {
            compilerType.validateCStorageContract();
            return compilerType.isPassedByPointerInC();
        }
        // Primitives and object fat pointers are passed by value.
        // All other types (String, StringName, Variant, Array, Dictionary, etc.)
        // are value-semantic structs that need to be passed by pointer.
        return !(type instanceof GdPrimitiveType) && !(type instanceof GdObjectType);
    }

    /// Prepares the RHS value for assignment, copying if needed for value-semantic types.
    /// Returns the C code expression for the prepared value.
    @SuppressWarnings("unused") // targetType reserved for future type conversion logic
    private @NotNull RenderResult prepareRhsValue(@NotNull ValueRef value, @NotNull GdType targetType) {
        var type = value.type();
        var code = value.generateCode();

        // Primitives and object pointers: direct assignment
        if (type instanceof GdPrimitiveType || type instanceof GdObjectType) {
            return new RenderResult(code, List.of());
        }

        if (checkCompilerOnlyDirectAssignment(type)) {
            return new RenderResult(code, List.of());
        }

        // Value-semantic types: need to copy
        // For String, StringName, Variant, Array, Dictionary, etc.
        var copyFunc = requireCopyAssignFunctionName(type);
        if (!copyFunc.isEmpty()) {
            // This is the proven-no-alias fast path used when the destination slot can consume the
            // copy result directly. may-alias overwrite routes must stage a separate stable carrier
            // before destroy(target); see prepareAssignedNonObjectRhs(...).
            var sourcePtr = renderValueAddress(value);
            return new RenderResult(copyFunc + "(" + sourcePtr.code() + ")", sourcePtr.temps());
        }

        return new RenderResult(code, List.of());
    }

    /// Prepares the RHS for non-object assignment.
    /// - proven no-alias: keep the direct `slot = copy(source_ptr)` fast path
    /// - may-alias overwrite: stage an independent stable carrier before destroy(target)
    private @NotNull PreparedAssignmentRhs prepareAssignedNonObjectRhs(@NotNull TargetRef target,
                                                                       @NotNull ValueRef value) {
        var rhsResult = prepareRhsValue(value, target.type());
        if (!CBodyBuilderAliasSafetySupport.requiresStableCarrier(
                checkInPrepareBlock(),
                canDestroyOldValue(target),
                target,
                value,
                value.type(),
                !requireCopyAssignFunctionName(value.type()).isEmpty()
        )) {
            return PreparedAssignmentRhs.ordinary(rhsResult);
        }

        var copyFunc = requireCopyAssignFunctionName(value.type());
        if (copyFunc.isEmpty()) {
            throw new IllegalStateException(
                    "Alias-safe stable carrier requires copy helper for type '" + value.type().getTypeName() + "'"
            );
        }

        var sourcePtr = renderValueAddress(value);
        var stableCarrier = newTempVariable(
                renderSafeTempPrefix(target.type()),
                target.type(),
                copyFunc + "(" + sourcePtr.code() + ")"
        );
        var tempsToDeclare = new ArrayList<TempVar>(sourcePtr.temps().size() + 1);
        tempsToDeclare.addAll(sourcePtr.temps());
        tempsToDeclare.add(stableCarrier);

        // `target = stableCarrier;` is a plain struct assignment that transfers the copied carrier
        // into the managed slot. The temp must not flow into the ordinary destroy-temp path after
        // assignment, otherwise we would reintroduce the old "copy temp -> assign -> destroy temp"
        // premature-release bug through a different code shape.
        return new PreparedAssignmentRhs(
                stableCarrier.name(),
                List.copyOf(tempsToDeclare),
                List.copyOf(sourcePtr.temps())
        );
    }

    /// Prepares a value for return, copying if needed.
    private @NotNull RenderResult prepareReturnValue(@NotNull ValueRef value) {
        var type = value.type();
        var code = value.generateCode();

        // Primitives and object pointers: direct return
        if (type instanceof GdPrimitiveType || type instanceof GdObjectType) {
            return new RenderResult(code, List.of());
        }

        if (checkCompilerOnlyDirectAssignment(type)) {
            return new RenderResult(code, List.of());
        }

        // Value-semantic types: need to copy for return
        var copyFunc = requireCopyAssignFunctionName(type);
        if (!copyFunc.isEmpty()) {
            // Generate copy: godot_new_<Type>_with_<Type>(source_ptr)
            var sourcePtr = renderValueAddress(value);
            return new RenderResult(copyFunc + "(" + sourcePtr.code() + ")", sourcePtr.temps());
        }

        return new RenderResult(code, List.of());
    }

    /// Fetches the copy-assign helper name, translating contract violations into instruction
    /// errors. `renderCopyAssignFunctionName` throws `IllegalArgumentException` for move-only
    /// compiler-only types (e.g. `GdccCoroState`); at the instruction boundary that is an invalid
    /// LIR instruction (single-consumer contract: `gdcc_low_ir.md` §Coroutine Instructions), so it
    /// must surface as `InvalidInsnException` carrying function/block context.
    private @NotNull String requireCopyAssignFunctionName(@NotNull GdType type) {
        try {
            return helper.renderCopyAssignFunctionName(type);
        } catch (IllegalArgumentException ex) {
            throw invalidInsn(ex.getMessage());
        }
    }

    /// Compiler-only direct assignment is an explicit protocol, not an empty-helper side effect.
    private boolean checkCompilerOnlyDirectAssignment(@NotNull GdType type) {
        if (!(type instanceof GdCompilerType compilerType)) {
            return false;
        }
        compilerType.validateCStorageContract();
        return compilerType.isDirectStructAssignmentSafe();
    }

    private void emitTempDecls(@NotNull List<TempVar> temps) {
        for (var temp : temps) {
            declareTempVar(temp);
        }
    }

    private void emitTempDestroys(@NotNull List<TempVar> temps) {
        for (var i = temps.size() - 1; i >= 0; i--) {
            var temp = temps.get(i);
            destroyTempVar(temp);
        }
    }

    /// Determines if we can skip old value destruction for a target based on whether it's initialized.
    /// Mainly used to optimize first-write initialization of temp variables where there is no old value to destroy.
    private boolean canDestroyOldValue(@NotNull TargetRef target) {
        if (target instanceof TempVar tempVar) {
            return tempVar.initialized();
        }
        return true;
    }

    private void markTargetInitialized(@NotNull TargetRef target) {
        if (target instanceof TempVar tempVar) {
            tempVar.setInitialized(true);
        }
    }

    /// Renders a pointer to a value, materializing expressions when needed.
    /// - ref variables already point to the value
    /// - non-ref variables use &var
    /// - expressions are materialized to a temp, then &temp
    /// - string literals use GD_STATIC_S or GD_STATIC_SN macros, which are already pointers
    private @NotNull RenderResult renderValueAddress(@NotNull ValueRef value) {
        if (value instanceof StringNamePtrLiteralValue || value instanceof StringPtrLiteralValue || value instanceof CStringLiteralValue) {
            return new RenderResult(value.generateCode(), List.of());
        }
        switch (value) {
            case VarValue varValue -> {
                var code = value.generateCode();
                if (isEffectivelyRef(varValue.variable())) {
                    return new RenderResult(code, List.of());
                }
                return new RenderResult("&" + code, List.of());
            }
            case AddressableExprValue addressableExprValue -> {
                // Existing lvalue storage can be borrowed by address directly; avoid materializing a
                // shallow temp from the slot before copy helpers such as `godot_new_Variant_with_Variant`.
                return new RenderResult("&(" + addressableExprValue.generateCode() + ")", List.of());
            }
            case ExprValue exprValue -> {
                var temp = newTempVariable(renderSafeTempPrefix(exprValue.type()), exprValue.type(), exprValue.generateCode());
                return new RenderResult("&" + temp.name(), List.of(temp));
            }
            default -> {
            }
        }
        return new RenderResult("&" + value.generateCode(), List.of());
    }

    private @NotNull String renderSafeTempPrefix(@NotNull GdType type) {
        var normalizedTypeName = helper.renderGdTypeName(type).toLowerCase(Locale.ROOT);
        var safePrefix = NON_TEMP_PREFIX_CHAR_PATTERN.matcher(normalizedTypeName).replaceAll("_");
        if (safePrefix.isBlank()) {
            return "tmp";
        }
        if (Character.isDigit(safePrefix.charAt(0))) {
            return "tmp_" + safePrefix;
        }
        return safePrefix;
    }

    private void emitDestroy(@NotNull String varCode, @NotNull GdType type) {
        if (!type.isDestroyable() || type instanceof GdObjectType) {
            return;
        }
        var destroyFunc = helper.renderDestroyFunctionName(type);
        out.append(destroyFunc).append("(&").append(varCode).append(");\n");
    }

    /// Writes an object value into a storage slot with ownership-aware semantics:
    /// capture old fat pointer → convert rhs to target fat pointer (preserve ID) → struct assign →
    /// own validated live raw for BORROWED rhs → release validated live raw of captured old.
    private void emitObjectSlotWrite(@NotNull String targetCode,
                                     @NotNull GdObjectType targetType,
                                     boolean releaseOldValue,
                                     @NotNull String rhsCode,
                                     @NotNull PtrKind rhsPtrKind,
                                     @Nullable GdObjectType rhsObjType,
                                     @NotNull OwnershipKind ownership) {
        TempVar oldValueTemp = null;
        if (releaseOldValue) {
            // Capture the full fat pointer before overwriting the slot (alias/self-assignment safe).
            oldValueTemp = newTempVariable("old_obj", targetType, targetCode);
            declareTempVar(oldValueTemp);
        }
        var assignCode = convertObjectValueIfNeeded(rhsCode, rhsPtrKind, rhsObjType, targetType);
        out.append(targetCode).append(" = ").append(assignCode).append(";\n");
        if (ownership == OwnershipKind.BORROWED) {
            // BORROWED rhs must be retained by the slot after assignment.
            emitOwnObject(targetCode, targetType);
        }
        if (oldValueTemp != null) {
            emitReleaseObject(oldValueTemp.name(), targetType);
        }
    }

    /// Writes a non-object value into a storage slot with value-lifecycle semantics:
    /// destroy old when needed (skip in __prepare__/first-write) -> assign rhs.
    /// Any may-alias stable carrier must already have been prepared before entering this helper.
    /// Caller keeps target-initialization and temp lifecycle responsibilities.
    private void emitNonObjectSlotWrite(@NotNull String targetCode,
                                        @NotNull GdType targetType,
                                        boolean destroyOldValue,
                                        @NotNull String rhsCode) {
        if (destroyOldValue && !checkInPrepareBlock() && targetType.isDestroyable()) {
            emitDestroy(targetCode, targetType);
        }
        out.append(targetCode).append(" = ").append(rhsCode).append(";\n");
    }

    /// Emits a discarded call with immediate cleanup for destroyable return types.
    private void emitDiscardedCall(@NotNull String cFuncName,
                                   @NotNull String callExpr,
                                   @NotNull GdType returnType) {
        if (!returnType.isDestroyable()) {
            out.append(callExpr).append(";\n");
            return;
        }

        if (returnType instanceof GdObjectType objType) {
            var rhsPtrKind = resolveCallResultPtrKind(cFuncName, objType);
            var assignCode = convertObjectValueIfNeeded(callExpr, rhsPtrKind, objType, objType);
            var discardTemp = newTempVariable("discard", returnType, assignCode);
            declareTempVar(discardTemp);
            // Discarded OWNED object returns are consumed by immediate release. This is the balancing
            // release for producer-side retains such as the exact-engine vararg helper's return own;
            // skipping it would leak the producer's strong reference.
            emitReleaseObject(discardTemp.name(), objType);
            return;
        }

        var discardTemp = newTempVariable("discard", returnType, callExpr);
        declareTempVar(discardTemp);
        // Non-object destroyable returns are cleaned up immediately on discard.
        emitDestroy(discardTemp.name(), returnType);
    }

    /// Emits code to release ownership of an object fat pointer via its validated live raw pointer.
    private void emitReleaseObject(@NotNull String fatPtrCode, @NotNull GdObjectType objType) {
        releaseOrTryRelease(fatPtrCode, objType);
    }

    /// Emits code to own an object fat pointer via its validated live raw pointer.
    private void emitOwnObject(@NotNull String fatPtrCode, @NotNull GdObjectType objType) {
        ownOrTryOwn(fatPtrCode, objType);
    }

    /// `try_*` lifecycle helpers take a second `instance_id` argument that drives the runtime
    /// RefCounted reference-bit check; the precise (`own_object` / `release_object`) variants already
    /// know the object is RefCounted and stay single-argument.
    private static final Set<String> TWO_ARG_LIFECYCLE_HELPERS =
            Set.of("try_own_object", "try_release_object", "try_destroy_object");

    /// Emits a named object lifecycle helper (`own_object` / `try_own_object` / `release_object` /
    /// `try_release_object` / `try_destroy_object`) for an object fat pointer value. The validated
    /// live raw pointer is the action target; the fat pointer's cached `instance_id` is appended for
    /// the two-argument `try_*` variants. Used by the explicit lifecycle instruction generators.
    public void emitObjectLifecycleCall(@NotNull String funcName, @NotNull ValueRef value) {
        if (!(value.type() instanceof GdObjectType objType)) {
            throw invalidInsn("Object lifecycle helper '" + funcName + "' requires an object value, but got '" +
                    value.type().getTypeName() + "'");
        }
        var fatPtrCode = value.generateCode();
        out.append(funcName).append("(").append(renderLiveGodotObjectPtr(fatPtrCode, objType));
        if (TWO_ARG_LIFECYCLE_HELPERS.contains(funcName)) {
            out.append(", ").append(fatPtrCode).append(".instance_id");
        }
        out.append(");\n");
    }

    /// Converts an object value expression into the target fat-pointer storage type.
    /// Ownership-neutral: never introduces retain/release.
    /// RAW_PRODUCER captures ID via `_from_raw`; FAT_PTR uses same-type copy or generated upcast.
    private @NotNull String convertObjectValueIfNeeded(@NotNull String code,
                                                       @NotNull PtrKind valuePtrKind,
                                                       @Nullable GdObjectType valueObjType,
                                                       @NotNull GdObjectType targetObjType) {
        if (valuePtrKind == PtrKind.RAW_PRODUCER) {
            return renderFatPtrFromRaw(code, targetObjType);
        }
        if (valuePtrKind == PtrKind.NON_OBJECT) {
            throw invalidInsn("Cannot convert non-object expression '" + code + "' into object type '" +
                    targetObjType.getTypeName() + "'");
        }
        if (valueObjType == null) {
            throw invalidInsn("Cannot convert object expression '" + code + "' without a static object type");
        }
        if (valueObjType.getTypeName().equals(targetObjType.getTypeName())) {
            return code;
        }
        if (!classRegistry().checkAssignable(valueObjType, targetObjType)) {
            throw invalidInsn("Cannot upcast object type '" + valueObjType.getTypeName() +
                    "' to '" + targetObjType.getTypeName() + "'");
        }
        return renderObjectFatPtrUpcastHelperName(valueObjType, targetObjType) + "(" + code + ")";
    }

    /// Emits own_object or try_own_object based on RefCounted status.
    /// The validated live raw pointer is the action target; the fat pointer's cached instance_id
    /// drives the runtime RefCounted reference-bit check for the UNKNOWN (`try_`) variant.
    private void ownOrTryOwn(@NotNull String fatPtrCode, @NotNull GdObjectType objType) {
        var status = classRegistry().getRefCountedStatus(objType);
        var livePtr = renderLiveGodotObjectPtr(fatPtrCode, objType);
        switch (status) {
            case YES -> out.append("own_object(").append(livePtr).append(");\n");
            case NO -> {
            }
            case UNKNOWN -> out.append("try_own_object(").append(livePtr).append(", ")
                    .append(fatPtrCode).append(".instance_id);\n");
        }
    }

    /// Emits release_object or try_release_object based on RefCounted status.
    /// The validated live raw pointer is the action target; the fat pointer's cached instance_id
    /// drives the runtime RefCounted reference-bit check for the UNKNOWN (`try_`) variant.
    private void releaseOrTryRelease(@NotNull String fatPtrCode, @NotNull GdObjectType objType) {
        var status = classRegistry().getRefCountedStatus(objType);
        var livePtr = renderLiveGodotObjectPtr(fatPtrCode, objType);
        switch (status) {
            case YES -> out.append("release_object(").append(livePtr).append(");\n");
            case NO -> {
            }
            case UNKNOWN -> out.append("try_release_object(").append(livePtr).append(", ")
                    .append(fatPtrCode).append(".instance_id);\n");
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean checkInPrepareBlock() {
        return currentBlock != null && "__prepare__".equals(currentBlock.id());
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean checkInFinallyBlock() {
        return currentBlock != null && "__finally__".equals(currentBlock.id());
    }

    /// Explicit non-`godot_*` helpers that still take raw `GDExtensionObjectPtr` at the call site.
    /// Operator evaluators (`gdcc_eval_*`) and exact engine helpers
    /// (`gdcc_engine_call_*` / `gdcc_engine_callv_*`) use fat-pointer surfaces and must not appear here.
    /// Lifecycle helpers (`own_object` / `try_*` / `release_object`) never route through
    /// `callVoid` / `callAssign`; they are emitted by `emitObjectLifecycleCall`.
    private static final Set<String> GLOBAL_FUNCS_REQUIRE_GODOT_RAW_PTR = Set.of(
            "gdcc_object_from_godot_object_ptr",
            "gdcc_object_is_null_raw_and_id"
    );

    /// Name-prefix backlog for generated Godot public wrappers that still expose raw object ABI.
    /// Prefer structured callee metadata over growing name-prefix rules; do not add new prefixes.
    private static final String GODOT_RAW_ABI_PREFIX_BACKLOG = "godot_";

    /// Whether a global C helper's object operands must be raw Godot pointers at the call site.
    private boolean checkGlobalFuncRequireGodotRawPtr(@NotNull String funcName) {
        if (GLOBAL_FUNCS_REQUIRE_GODOT_RAW_PTR.contains(funcName)) {
            return true;
        }
        // See GODOT_RAW_ABI_PREFIX_BACKLOG: transitional raw-ABI recognition for public Godot wrappers.
        return funcName.startsWith(GODOT_RAW_ABI_PREFIX_BACKLOG);
    }

    /// Verifies that an emitted `godot_*` wrapper call is either runtime-provided or has already
    /// been registered as module-local by the current lowering path.
    public void recordUsedGodotBindingCall(@NotNull String funcName) {
        try {
            usageBuffer.recordGodotCall(funcName);
        } catch (IllegalStateException exception) {
            throw invalidInsn(exception.getMessage());
        }
    }

    /// Whether a global helper still returns a raw Godot object pointer that must be captured via `_from_raw`.
    /// Fat-pointer helpers (`gdcc_eval_*`, exact engine helpers) return fat values and are excluded.
    /// Only the transitional `godot_*` public-wrapper prefix backlog is treated as raw return (see backlog constant).
    private boolean checkGlobalFuncReturnGodotRawPtr(@NotNull String funcName) {
        return funcName.startsWith(GODOT_RAW_ABI_PREFIX_BACKLOG);
    }

    private @NotNull PtrKind resolveCallResultPtrKind(@NotNull String cFuncName,
                                                      @NotNull GdObjectType returnObjType) {
        if (checkGlobalFuncReturnGodotRawPtr(cFuncName)) {
            // External Godot/raw ABI helpers produce raw pointers that must capture an ID.
            return PtrKind.RAW_PRODUCER;
        }
        return resolvePtrKind(returnObjType);
    }

    /// Value representation of an expression used by object conversion and argument rendering.
    public enum PtrKind {
        /// Internal object storage: `gdcc_<Type>_fat_ptr` with cached typed ptr + instance_id.
        FAT_PTR,
        /// Fresh raw Godot object pointer that must be captured into a fat pointer (`_from_raw`).
        RAW_PRODUCER,
        /// Not an object value (primitives, value-semantic types, etc.)
        NON_OBJECT
    }

    /// Ownership category for object values.
    /// This is executable lowering data, not passive metadata.
    ///
    /// Current production sites:
    /// - `ValueRef#ownership()` defaults existing vars/exprs to `BORROWED`
    /// - `valueOfOwnedExpr(...)` marks explicit fresh/transfer-producing expressions as `OWNED`
    /// - `callAssign(...)` currently treats object call returns as `OWNED`
    /// - constructor/property-init first-write uses `OWNED` when the init helper semantically produces a fresh value
    ///
    /// Current consumers:
    /// - `assignVar(...)`
    /// - `emitCallResultAssignment(...)`
    /// - `returnValue(...)`
    ///
    /// All of those routes funnel into `emitObjectSlotWrite(...)`, which uses the ownership kind to decide
    /// whether the destination slot must retain the RHS:
    /// - `BORROWED` => emit `own_object` / `try_own_object`
    /// - `OWNED` => consume directly, do not retain again
    ///
    /// Because of that, changing a value source from `OWNED` to `BORROWED` (or the reverse) changes generated C
    /// and reference-count balance. It is not safe to treat this enum as documentation-only metadata.
    public enum OwnershipKind {
        BORROWED,
        OWNED
    }

    public sealed interface ValueRef permits VarValue, ExprValue, AddressableExprValue, StringNamePtrLiteralValue, StringPtrLiteralValue, CStringLiteralValue, TempVar {
        @NotNull GdType type();

        @NotNull String generateCode();

        @NotNull PtrKind ptrKind();

        // Existing value sources are treated as BORROWED unless explicitly marked OWNED.
        default @NotNull OwnershipKind ownership() {
            return OwnershipKind.BORROWED;
        }
    }

    public record VarValue(@NotNull LirVariable variable, @NotNull PtrKind ptrKind,
                           @NotNull String code) implements ValueRef {
        public VarValue(@NotNull LirVariable variable, @NotNull PtrKind ptrKind) {
            this(variable, ptrKind, "$" + variable.id());
        }

        public VarValue {
            Objects.requireNonNull(variable);
            Objects.requireNonNull(ptrKind);
            Objects.requireNonNull(code);
        }

        @Override
        public @NotNull GdType type() {
            return variable.type();
        }

        @Override
        public @NotNull String generateCode() {
            return code;
        }
    }

    public record ExprValue(@NotNull String code,
                            @NotNull GdType type,
                            @NotNull PtrKind ptrKind,
                            @NotNull OwnershipKind ownership) implements ValueRef {
        public ExprValue(@NotNull String code, @NotNull GdType type, @NotNull PtrKind ptrKind) {
            this(code, type, ptrKind, OwnershipKind.BORROWED);
        }

        public ExprValue {
            Objects.requireNonNull(code);
            Objects.requireNonNull(type);
            Objects.requireNonNull(ptrKind);
            Objects.requireNonNull(ownership);
        }

        @Override
        public @NotNull GdType type() {
            return type;
        }

        @Override
        public @NotNull String generateCode() {
            return code;
        }
    }

    public record AddressableExprValue(@NotNull String code,
                                       @NotNull GdType type,
                                       @NotNull PtrKind ptrKind) implements ValueRef {
        public AddressableExprValue {
            Objects.requireNonNull(code);
            Objects.requireNonNull(type);
            Objects.requireNonNull(ptrKind);
        }

        @Override
        public @NotNull GdType type() {
            return type;
        }

        @Override
        public @NotNull String generateCode() {
            return code;
        }
    }

    public record StringNamePtrLiteralValue(@NotNull String value) implements ValueRef {
        public StringNamePtrLiteralValue {
            Objects.requireNonNull(value);
        }

        @Override
        public @NotNull GdType type() {
            return GdStringNameType.STRING_NAME;
        }

        @Override
        public @NotNull String generateCode() {
            return renderStaticStringNameLiteral(value);
        }

        @Override
        public @NotNull PtrKind ptrKind() {
            return PtrKind.NON_OBJECT;
        }
    }

    public record StringPtrLiteralValue(@NotNull String value) implements ValueRef {
        public StringPtrLiteralValue {
            Objects.requireNonNull(value);
        }

        @Override
        public @NotNull GdType type() {
            return GdStringType.STRING;
        }

        @Override
        public @NotNull String generateCode() {
            return renderStaticStringLiteral(value);
        }

        @Override
        public @NotNull PtrKind ptrKind() {
            return PtrKind.NON_OBJECT;
        }
    }

    /// Direct C UTF-8 string pointer literal (`const char*`), not an object value.
    public record CStringLiteralValue(@NotNull String value) implements ValueRef {
        public CStringLiteralValue {
            Objects.requireNonNull(value);
        }

        @Override
        public @NotNull GdType type() {
            return GdStringType.STRING;
        }

        @Override
        public @NotNull String generateCode() {
            return "u8\"" + escapeStringLiteral(value) + "\"";
        }

        @Override
        public @NotNull PtrKind ptrKind() {
            return PtrKind.NON_OBJECT;
        }
    }

    public sealed interface TargetRef permits VarTargetRef, ExprTargetRef, TempVar, DiscardRef {
        @NotNull GdType type();

        @NotNull String generateCode();

        boolean isRef();
    }

    public record VarTargetRef(@NotNull LirVariable variable, @NotNull String code,
                               boolean effectivelyRef) implements TargetRef {
        public VarTargetRef(@NotNull LirVariable variable) {
            this(variable, "$" + variable.id(), variable.ref());
        }

        public VarTargetRef {
            Objects.requireNonNull(variable);
            Objects.requireNonNull(code);
        }

        @Override
        public @NotNull GdType type() {
            return variable.type();
        }

        @Override
        public @NotNull String generateCode() {
            return code;
        }

        @Override
        public boolean isRef() {
            return effectivelyRef;
        }
    }

    /// Assignment-only raw lvalue target.
    ///
    /// Keep usage scoped to assignment writes so lifecycle and ownership semantics remain
    /// centralized in assignment APIs, instead of spreading to generic call/return paths.
    public record ExprTargetRef(@NotNull String code, @NotNull GdType type) implements TargetRef {
        public ExprTargetRef {
            Objects.requireNonNull(code);
            Objects.requireNonNull(type);
        }

        @Override
        public @NotNull GdType type() {
            return type;
        }

        @Override
        public @NotNull String generateCode() {
            return code;
        }

        @Override
        public boolean isRef() {
            return false;
        }
    }

    public static final class DiscardRef implements TargetRef {
        private static final DiscardRef INSTANCE = new DiscardRef();

        private DiscardRef() {
        }

        @Override
        public @NotNull GdType type() {
            throw new IllegalStateException("DiscardRef does not carry a target type");
        }

        @Override
        public @NotNull String generateCode() {
            throw new IllegalStateException("DiscardRef does not generate target code");
        }

        @Override
        public boolean isRef() {
            return false;
        }
    }

    public static final class TempVar implements ValueRef, TargetRef {
        private final @NotNull String name;
        private final @NotNull GdType type;
        private final @Nullable String initCode;
        private final @NotNull PtrKind ptrKind;
        private final boolean initializedAtDeclaration;
        private boolean initialized;

        public TempVar(@NotNull String name,
                       @NotNull GdType type,
                       @Nullable String initCode,
                       @NotNull PtrKind ptrKind,
                       boolean initializedAtDeclaration) {
            this.name = Objects.requireNonNull(name);
            this.type = Objects.requireNonNull(type);
            this.initCode = initCode;
            this.ptrKind = Objects.requireNonNull(ptrKind);
            this.initializedAtDeclaration = initializedAtDeclaration;
            this.initialized = false;
        }

        public @NotNull String name() {
            return name;
        }

        @Override
        public @NotNull GdType type() {
            return type;
        }

        public @Nullable String initCode() {
            return initCode;
        }

        @Override
        public @NotNull PtrKind ptrKind() {
            return ptrKind;
        }

        public boolean hasInitializer() {
            return initializedAtDeclaration && initCode != null;
        }

        public boolean initialized() {
            return initialized;
        }

        public void setInitialized(boolean initialized) {
            this.initialized = initialized;
        }

        @Override
        public @NotNull String generateCode() {
            return name;
        }

        @Override
        public boolean isRef() {
            return false;
        }
    }

    public record RenderResult(@NotNull String code,
                               @NotNull List<TempVar> temps,
                               @Nullable String preCode) {
        private RenderResult(@NotNull String code, @NotNull List<TempVar> temps) {
            this(code, temps, null);
        }

        public RenderResult {
            Objects.requireNonNull(code);
            Objects.requireNonNull(temps);
        }
    }

    private record PreparedAssignmentRhs(@NotNull String code,
                                         @NotNull List<TempVar> tempsToDeclare,
                                         @NotNull List<TempVar> tempsToDestroy) {
        private PreparedAssignmentRhs {
            Objects.requireNonNull(code);
            Objects.requireNonNull(tempsToDeclare);
            Objects.requireNonNull(tempsToDestroy);
        }

        private static @NotNull PreparedAssignmentRhs ordinary(@NotNull RenderResult rhsResult) {
            return new PreparedAssignmentRhs(rhsResult.code(), rhsResult.temps(), rhsResult.temps());
        }
    }
}
