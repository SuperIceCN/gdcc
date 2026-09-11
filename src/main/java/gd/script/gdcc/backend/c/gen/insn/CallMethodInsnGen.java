package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.backend.c.gen.CVtablePlanner;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.CallMethodInsn;
import gd.script.gdcc.lir.insn.LineNumberInsn;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import gd.script.gdcc.type.GdccCoroStateType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/// C code generator for `CALL_METHOD`.
///
/// Current scope:
/// - GDCC / ENGINE / BUILTIN static dispatch.
/// - OBJECT_DYNAMIC / VARIANT_DYNAMIC runtime dispatch.
/// - Argument/result contract validation with default argument completion.
public final class CallMethodInsnGen implements CInsnGen<CallMethodInsn> {
    private static final Logger LOGGER = LoggerFactory.getLogger(CallMethodInsnGen.class);

    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_METHOD);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var receiverVar = resolveReceiverVar(bodyBuilder, instruction.objectId());
        var argVars = resolveArgumentVariables(bodyBuilder, instruction.methodName(), instruction.args(), "call_method");

        var resolved = BackendMethodCallResolver.resolve(bodyBuilder, receiverVar, instruction.methodName(), argVars);
        switch (resolved.mode()) {
            case OBJECT_DYNAMIC -> emitObjectDynamicCall(bodyBuilder, instruction, receiverVar, argVars);
            case VARIANT_DYNAMIC -> emitVariantDynamicCall(bodyBuilder, instruction, receiverVar, argVars);
            case GDCC, ENGINE, BUILTIN ->
                    emitKnownSignatureCall(bodyBuilder, instruction, receiverVar, argVars, resolved);
            default -> throw bodyBuilder.invalidInsn("Unsupported call_method dispatch mode: " + resolved.mode());
        }
    }

    private record DynamicVariantArgs(@NotNull List<CBodyBuilder.ValueRef> values,
                                      @NotNull List<CBodyBuilder.TempVar> packedTemps) {
        private DynamicVariantArgs {
            values = List.copyOf(values);
            packedTemps = List.copyOf(packedTemps);
        }
    }

    private record VariantDynamicSourceLocation(@NotNull String fileName, int lineNumber) {
        private VariantDynamicSourceLocation {
            fileName = fileName.isBlank() ? "<unknown>" : fileName;
        }
    }

    private void emitObjectDynamicCall(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull CallMethodInsn instruction,
                                       @NotNull LirVariable receiverVar,
                                       @NotNull List<LirVariable> argVars) {
        var dynamicArgs = materializeDynamicVariantArgs(bodyBuilder, argVars, "object_dynamic");
        var fixedArgs = List.of(
                bodyBuilder.valueOfVar(receiverVar),
                bodyBuilder.valueOfStringNamePtrLiteral(instruction.methodName())
        );
        emitDynamicCallResult(
                bodyBuilder,
                instruction,
                "godot_Object_call",
                fixedArgs,
                dynamicArgs.values(),
                dynamicArgs.packedTemps()
        );
    }

    private void emitVariantDynamicCall(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull CallMethodInsn instruction,
                                        @NotNull LirVariable receiverVar,
                                        @NotNull List<LirVariable> argVars) {
        var dynamicArgs = materializeDynamicVariantArgs(bodyBuilder, argVars, "variant_dynamic");
        var sourceLocation = resolveVariantDynamicSourceLocation(bodyBuilder);
        var fixedArgs = List.of(
                bodyBuilder.valueOfVar(receiverVar),
                bodyBuilder.valueOfStringNamePtrLiteral(instruction.methodName()),
                bodyBuilder.valueOfCStringLiteral(sourceLocation.fileName()),
                bodyBuilder.valueOfExpr(Integer.toString(sourceLocation.lineNumber()), GdIntType.INT)
        );
        emitDynamicCallResult(
                bodyBuilder,
                instruction,
                "godot_Variant_call",
                fixedArgs,
                dynamicArgs.values(),
                dynamicArgs.packedTemps()
        );
    }

    private @NotNull VariantDynamicSourceLocation resolveVariantDynamicSourceLocation(@NotNull CBodyBuilder bodyBuilder) {
        var classSourceFile = bodyBuilder.clazz().getSourceFile();
        var baseFileName = classSourceFile != null && !classSourceFile.isBlank()
                ? classSourceFile
                : bodyBuilder.clazz().getName();
        var lineNumber = findNearestLineNumberBeforeCurrentInsn(bodyBuilder);
        if (lineNumber > 0) {
            return new VariantDynamicSourceLocation(baseFileName, lineNumber);
        }
        var fallbackLine = Math.max(0, bodyBuilder.currentInsnIndex());
        return new VariantDynamicSourceLocation(baseFileName + "(assemble)", fallbackLine);
    }

    private int findNearestLineNumberBeforeCurrentInsn(@NotNull CBodyBuilder bodyBuilder) {
        var block = bodyBuilder.currentBlock();
        if (block == null) {
            return -1;
        }
        var instructions = block.getInstructions();
        var upperBoundExclusive = Math.min(bodyBuilder.currentInsnIndex(), instructions.size());
        var nearestLine = -1;
        for (var i = 0; i < upperBoundExclusive; i++) {
            var insn = instructions.get(i);
            if (insn instanceof LineNumberInsn(int lineNumber) && lineNumber > 0) {
                nearestLine = lineNumber;
            }
        }
        return nearestLine;
    }

    private void emitDynamicCallResult(@NotNull CBodyBuilder bodyBuilder,
                                       @NotNull CallMethodInsn instruction,
                                       @NotNull String cFunctionName,
                                       @NotNull List<CBodyBuilder.ValueRef> fixedArgs,
                                       @Nullable List<CBodyBuilder.ValueRef> varargs,
                                       @NotNull List<CBodyBuilder.TempVar> dynamicArgTemps) {
        var resultVar = resolveDynamicResultVariable(bodyBuilder, instruction);
        if (resultVar == null) {
            bodyBuilder.callAssign(bodyBuilder.discardRef(), cFunctionName, GdVariantType.VARIANT, fixedArgs, varargs);
            destroyDefaultTemps(bodyBuilder, dynamicArgTemps);
            return;
        }

        if (resultVar.type() instanceof GdVariantType) {
            bodyBuilder.callAssign(bodyBuilder.targetOfVar(resultVar), cFunctionName, GdVariantType.VARIANT, fixedArgs, varargs);
            destroyDefaultTemps(bodyBuilder, dynamicArgTemps);
            return;
        }

        var dynamicResultTemp = bodyBuilder.newTempVariable("dynamic_result", GdVariantType.VARIANT);
        bodyBuilder.declareTempVar(dynamicResultTemp);
        bodyBuilder.callAssign(dynamicResultTemp, cFunctionName, GdVariantType.VARIANT, fixedArgs, varargs);
        InsnGenSupport.unpackVariantAssign(
                bodyBuilder,
                bodyBuilder.targetOfVar(resultVar),
                resultVar.type(),
                dynamicResultTemp,
                "dynamic call result target"
        );
        bodyBuilder.destroyTempVar(dynamicResultTemp);
        destroyDefaultTemps(bodyBuilder, dynamicArgTemps);
    }

    private @NotNull DynamicVariantArgs materializeDynamicVariantArgs(@NotNull CBodyBuilder bodyBuilder,
                                                                      @NotNull List<LirVariable> argVars,
                                                                      @NotNull String tempPrefix) {
        var values = new ArrayList<CBodyBuilder.ValueRef>(argVars.size());
        var temps = new ArrayList<CBodyBuilder.TempVar>();
        for (var i = 0; i < argVars.size(); i++) {
            var argVar = argVars.get(i);
            if (argVar.type() instanceof GdVariantType) {
                values.add(bodyBuilder.valueOfVar(argVar));
                continue;
            }

            var packedVariantTemp = bodyBuilder.newTempVariable(tempPrefix + "_arg_" + (i + 1), GdVariantType.VARIANT);
            bodyBuilder.declareTempVar(packedVariantTemp);
            InsnGenSupport.packVariantAssign(bodyBuilder, packedVariantTemp, argVar);
            values.add(packedVariantTemp);
            temps.add(packedVariantTemp);
        }
        return new DynamicVariantArgs(values, temps);
    }

    private void emitKnownSignatureCall(@NotNull CBodyBuilder bodyBuilder,
                                        @NotNull CallMethodInsn instruction,
                                        @NotNull LirVariable receiverVar,
                                        @NotNull List<LirVariable> argVars,
                                        @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        // Vtable-indirect gate: only GDCC non-static calls may dispatch
        // polymorphically, and the query must run BEFORE the coroutine split so polymorphic
        // coroutine slots also route through the vtable.
        if (resolved.mode() == BackendMethodCallResolver.DispatchMode.GDCC
                && !resolved.isStatic()
                && bodyBuilder.helper().isPolymorphicCall(receiverVar.type(), resolved.methodName())) {
            emitPolymorphicCall(bodyBuilder, instruction.resultId(), receiverVar, argVars, resolved);
            return;
        }
        if (resolved.coroutine()) {
            if (resolved.isStatic()) {
                // Guard rail for anomalous IR: lowering emits `CallStaticMethodInsn` for static
                // coroutine calls, so a `call_method` resolving to one is an invariant violation.
                throw bodyBuilder.invalidInsn("Coroutine method '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "' is static: static coroutine calls are not supported" +
                        " via call_method (anomalous IR; static calls lower to call_static_method)");
            }
            emitCoroutineStartCall(bodyBuilder, instruction.resultId(), receiverVar, argVars, resolved, "CALL_METHOD");
            return;
        }
        if (resolved.isStatic()) {
            warnStaticMethodCall(bodyBuilder, receiverVar, resolved);
        }
        emitResolvedCall(bodyBuilder, instruction.resultId(), receiverVar, argVars, resolved, "CALL_METHOD");
    }

    /// Vtable-indirect dispatch for a polymorphic GDCC call. The receiver is
    /// materialized ONCE as the slot INTRODUCER's fat self temp: the receiver expression feeds
    /// both the vtable accessor and the first argument, and the slot function pointer signature
    /// is introducer-typed, so an owner-typed (or further-descended) fat self would not typecheck.
    /// `resolved` still drives default completion and the coroutine split — defaults belong to
    /// the statically resolved owner signature, so `default_value_func` keeps receiving the
    /// original receiverVar while the call's first argument becomes the materialized temp.
    private void emitPolymorphicCall(@NotNull CBodyBuilder bodyBuilder,
                                     @Nullable String resultId,
                                     @NotNull LirVariable receiverVar,
                                     @NotNull List<LirVariable> argVars,
                                     @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        var slotEntry = bodyBuilder.helper().findVtableSlot(resolved.ownerClassName(), resolved.methodName())
                .orElseThrow(() -> bodyBuilder.invalidInsn("Polymorphic call '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "' passed isPolymorphicCall but owns no vtable slot" +
                        " (planner invariant violated)"));
        var slot = slotEntry.slot();
        var introducerType = new GdObjectType(slot.introducerClassName());
        var receiverExpr = BackendPropertyAccessResolver.renderReceiverValue(
                bodyBuilder,
                receiverVar,
                introducerType,
                "CALL_METHOD",
                "vtable slot introducer",
                " for polymorphic call '" + resolved.ownerClassName() + "." + resolved.methodName() + "'"
        ).generateCode();
        // Non-owning fat view of the receiver: no retain on copy, hence nothing to release.
        var vtRecv = bodyBuilder.newTempVariable("vt_recv", introducerType, receiverExpr);
        bodyBuilder.declareTempVar(vtRecv);
        var indirect = new IndirectCallShape(slot, vtRecv);
        if (resolved.coroutine()) {
            emitCoroutineStartCall(bodyBuilder, resultId, receiverVar, argVars, resolved, "CALL_METHOD", indirect);
        } else {
            emitResolvedCall(bodyBuilder, resultId, receiverVar, argVars, resolved, "CALL_METHOD", indirect);
        }
    }

    /// Vtable-dispatch call shape, the single source for the indirect emission: the slot
    /// identity plus the materialized introducer-fat receiver temp. The callee expression
    /// (constructed inside `CBodyBuilder.call*VtableSlot`) and the call's first argument both
    /// derive from these two facts. Null on the direct path.
    private record IndirectCallShape(@NotNull CVtablePlanner.VtableSlot slot,
                                     @NotNull CBodyBuilder.TempVar vtRecv) {
    }

    /// Shared exact-signature call flow for `CALL_METHOD`/`CALL_STATIC_METHOD`/`CALL_SUPER_METHOD`:
    /// default completion, vararg collection, void/result handling and engine-usage recording.
    /// `receiverVar` is null exactly on the static route; `validateFixedArgsAndCompleteDefaults`
    /// enforces the receiver contract against `resolved.isStatic()`. `CALL_SUPER_METHOD` always
    /// takes the direct path (`indirect == null`): super names a fixed ancestor implementation and
    /// must never dispatch through the vtable, so do NOT add vtable gating to this shared flow.
    static void emitResolvedCall(@NotNull CBodyBuilder bodyBuilder,
                                 @Nullable String resultId,
                                 @Nullable LirVariable receiverVar,
                                 @NotNull List<LirVariable> argVars,
                                 @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                 @NotNull String insnName) {
        emitResolvedCall(bodyBuilder, resultId, receiverVar, argVars, resolved, insnName, null);
    }

    /// `indirect != null` selects the vtable-dispatch variant: the callee is the slot
    /// member expression (built by `CBodyBuilder.call*VtableSlot` from `indirect`) and the first
    /// fixed argument the pre-materialized introducer-fat receiver; argument/default/vararg/
    /// result rules stay identical to the direct path.
    static void emitResolvedCall(@NotNull CBodyBuilder bodyBuilder,
                                 @Nullable String resultId,
                                 @Nullable LirVariable receiverVar,
                                 @NotNull List<LirVariable> argVars,
                                 @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                 @NotNull String insnName,
                                 @Nullable IndirectCallShape indirect) {
        var callArgs = validateFixedArgsAndCompleteDefaults(bodyBuilder, receiverVar, resolved, argVars, insnName,
                indirect != null ? indirect.vtRecv() : null);
        var fixedCount = resolved.parameters().size();
        var fixedArgs = callArgs.fixedArgs();

        List<CBodyBuilder.ValueRef> varargs = null;
        if (resolved.isVararg()) {
            varargs = new ArrayList<>(Math.max(0, argVars.size() - fixedCount));
            for (var i = fixedCount; i < argVars.size(); i++) {
                varargs.add(bodyBuilder.valueOfVar(argVars.get(i)));
            }
            validateVarargs(bodyBuilder, resolved, argVars, fixedCount);
        }

        var returnType = resolved.returnType();
        if (returnType instanceof GdVoidType) {
            if (resultId != null) {
                throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                        "' has no return value but resultId is provided");
            }
            if (indirect != null) {
                bodyBuilder.callVoidVtableSlot(indirect.slot(), indirect.vtRecv(), fixedArgs, varargs);
            } else {
                bodyBuilder.callVoid(resolved.cFunctionName(), fixedArgs, varargs);
            }
            destroyTemporaryArgs(bodyBuilder, callArgs.temporaryArgs());
            bodyBuilder.recordUsedEngineMethodCall(resolved);
            return;
        }

        var target = resolveResultTarget(bodyBuilder, resultId, resolved);
        if (indirect != null) {
            bodyBuilder.callAssignVtableSlot(target, indirect.slot(), indirect.vtRecv(), returnType, fixedArgs, varargs);
        } else {
            bodyBuilder.callAssign(target, resolved.cFunctionName(), returnType, fixedArgs, varargs);
        }
        destroyTemporaryArgs(bodyBuilder, callArgs.temporaryArgs());
        bodyBuilder.recordUsedEngineMethodCall(resolved);
    }

    /// Internal coroutine-call ABI (`gdcc_low_ir.md` §Coroutine Instructions): the
    /// call targets the coroutine-start thunk (`<Class>_<method>__coro_start`, same parameter
    /// shape as the ClassDB entry) and always writes the OWNED state object reference into the
    /// result variable declared as `compiler::GdccCoroState`. The single-consumer value is then
    /// consumed either by `await` (typed resume channel) or by an `INTERNAL` `destruct`
    /// (statement-position fire-and-forget detach); this generator only produces it.
    /// Shared with `CallStaticMethodInsnGen`: `receiverVar` is null on the static route, whose
    /// start thunk has no receiver parameter.
    static void emitCoroutineStartCall(@NotNull CBodyBuilder bodyBuilder,
                                       @Nullable String resultId,
                                       @Nullable LirVariable receiverVar,
                                       @NotNull List<LirVariable> argVars,
                                       @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                       @NotNull String insnName) {
        emitCoroutineStartCall(bodyBuilder, resultId, receiverVar, argVars, resolved, insnName, null);
    }

    /// `indirect != null` selects the vtable-dispatch variant: the coroutine slot already
    /// points at the final overrider's start thunk, so only the callee changes — the result
    /// target stays the `compiler::GdccCoroState` slot.
    static void emitCoroutineStartCall(@NotNull CBodyBuilder bodyBuilder,
                                       @Nullable String resultId,
                                       @Nullable LirVariable receiverVar,
                                       @NotNull List<LirVariable> argVars,
                                       @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                       @NotNull String insnName,
                                       @Nullable IndirectCallShape indirect) {
        if (resolved.isVararg()) {
            // The generated start thunk has a fixed-parameter signature; a vararg tail would
            // produce a C call with more arguments than parameters.
            throw bodyBuilder.invalidInsn("Coroutine method '" + resolved.ownerClassName() + "." +
                    resolved.methodName() + "' is vararg: the coroutine start thunk is fixed-parameter only");
        }
        var callArgs = validateFixedArgsAndCompleteDefaults(bodyBuilder, receiverVar, resolved, argVars, insnName,
                indirect != null ? indirect.vtRecv() : null);
        var target = resolveCoroutineStateResultTarget(bodyBuilder, resultId, resolved);
        if (indirect != null) {
            bodyBuilder.callAssignVtableSlot(target, indirect.slot(), indirect.vtRecv(), GdccCoroStateType.CORO_STATE, callArgs.fixedArgs());
        } else {
            bodyBuilder.callAssign(target, resolved.cFunctionName(), GdccCoroStateType.CORO_STATE, callArgs.fixedArgs());
        }
        destroyTemporaryArgs(bodyBuilder, callArgs.temporaryArgs());
        bodyBuilder.recordUsedEngineMethodCall(resolved);
    }

    /// The coroutine call MUST declare a result variable (the OWNED state reference has to be
    /// accounted for even on the fire-and-forget path) and the variable MUST be declared with
    /// the compiler-only `compiler::GdccCoroState` type — the only producer of that type.
    static @NotNull CBodyBuilder.TargetRef resolveCoroutineStateResultTarget(
            @NotNull CBodyBuilder bodyBuilder,
            @Nullable String resultId,
            @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        if (resultId == null) {
            throw bodyBuilder.invalidInsn("Call on coroutine method '" + resolved.ownerClassName() + "." +
                    resolved.methodName() + "' must declare a compiler::GdccCoroState result variable" +
                    " (await consumes it; statement-position fire-and-forget destructs it)");
        }
        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' not found in function");
        }
        if (!(resultVar.type() instanceof GdccCoroStateType)) {
            throw bodyBuilder.invalidInsn("Coroutine call result variable '" + resultId +
                    "' must be declared compiler::GdccCoroState, got '" + resultVar.type().getTypeName() + "'");
        }
        return bodyBuilder.targetOfVar(resultVar);
    }

    record CompletedCallArgs(@NotNull List<CBodyBuilder.ValueRef> fixedArgs,
                             @NotNull List<CBodyBuilder.TempVar> temporaryArgs) {
        CompletedCallArgs {
            fixedArgs = List.copyOf(fixedArgs);
            temporaryArgs = List.copyOf(temporaryArgs);
        }
    }

    private void warnStaticMethodCall(@NotNull CBodyBuilder bodyBuilder,
                                      @NotNull LirVariable receiverVar,
                                      @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        var block = bodyBuilder.currentBlock();
        var blockId = block != null ? block.id() : "unknown";
        LOGGER.warn("call_method on receiver '{}' resolved static method '{}.{}'; emitting static call '{}' (function='{}', block='{}', insnIndex={})",
                receiverVar.id(),
                resolved.ownerClassName(),
                resolved.methodName(),
                resolved.cFunctionName(),
                bodyBuilder.func().getName(),
                blockId,
                bodyBuilder.currentInsnIndex());
    }

    static @NotNull CompletedCallArgs validateFixedArgsAndCompleteDefaults(@NotNull CBodyBuilder bodyBuilder,
                                                                           @Nullable LirVariable receiverVar,
                                                                           @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                                                           @NotNull List<LirVariable> argVars,
                                                                           @NotNull String insnName) {
        return validateFixedArgsAndCompleteDefaults(bodyBuilder, receiverVar, resolved, argVars, insnName, null);
    }

    /// `receiverArgOverride != null` replaces the rendered receiver as first fixed argument: the
    /// vtable-indirect path passes the materialized introducer-fat temp instead. Only the
    /// call's first argument is affected — instance `default_value_func` materialization still
    /// consumes the original receiverVar rendered to the owner type.
    static @NotNull CompletedCallArgs validateFixedArgsAndCompleteDefaults(@NotNull CBodyBuilder bodyBuilder,
                                                                           @Nullable LirVariable receiverVar,
                                                                           @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                                                           @NotNull List<LirVariable> argVars,
                                                                           @NotNull String insnName,
                                                                           @Nullable CBodyBuilder.ValueRef receiverArgOverride) {
        var providedCount = argVars.size();
        var fixedCount = resolved.parameters().size();
        if (!resolved.isVararg() && providedCount > fixedCount) {
            throw bodyBuilder.invalidInsn("Too many arguments for method '" + resolved.ownerClassName() + "." +
                    resolved.methodName() + "': expected " + fixedCount + ", got " + providedCount);
        }

        var fixedArgs = new ArrayList<CBodyBuilder.ValueRef>(fixedCount + (resolved.isStatic() ? 0 : 1));
        if (!resolved.isStatic()) {
            if (receiverVar == null) {
                throw bodyBuilder.invalidInsn(insnName + " on instance method '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "' requires a receiver variable");
            }
            fixedArgs.add(receiverArgOverride != null ? receiverArgOverride
                    : BackendPropertyAccessResolver.renderReceiverValue(
                            bodyBuilder,
                            receiverVar,
                            resolved.ownerType(),
                            insnName,
                            "method owner",
                            ""
                    ));
        }
        var temporaryArgs = new ArrayList<CBodyBuilder.TempVar>(Math.max(0, fixedCount - providedCount));

        // Exact engine helpers now own raw ptrcall slot materialization internally, so caller-side
        // fixed args stay on the same normalized surface used by semantic resolution/default filling.
        var providedFixedCount = Math.min(providedCount, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argVar = argVars.get(i);
            var param = resolved.parameters().get(i);
            if (!bodyBuilder.classRegistry().checkAssignable(argVar.type(), param.type())) {
                throw bodyBuilder.invalidInsn("Cannot assign value of type '" + argVar.type().getTypeName() +
                        "' to method parameter #" + (i + 1) + " ('" + param.name() +
                        "') of type '" + param.type().getTypeName() + "'");
            }
            // Exact engine helpers take owner-typed fat params; subtype args must upcast like receivers.
            if (argVar.type() instanceof GdObjectType
                    && param.type() instanceof GdObjectType paramObjType
                    && !argVar.type().getTypeName().equals(paramObjType.getTypeName())) {
                fixedArgs.add(bodyBuilder.valueOfCastedVar(argVar, paramObjType));
            } else {
                fixedArgs.add(bodyBuilder.valueOfVar(argVar));
            }
        }

        for (var i = providedFixedCount; i < fixedCount; i++) {
            var param = resolved.parameters().get(i);
            if (!param.hasDefaultValue()) {
                throw bodyBuilder.invalidInsn("Too few arguments for method '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "': missing required parameter #" + (i + 1) +
                        " ('" + param.name() + "')");
            }

            var temp = bodyBuilder.newTempVariable("default_arg_" + (i + 1), param.type());
            bodyBuilder.declareTempVar(temp);
            switch (param.defaultKind()) {
                case LITERAL -> materializeLiteralDefault(bodyBuilder, resolved, param, temp, i + 1);
                case FUNCTION ->
                        materializeFunctionDefault(bodyBuilder, receiverVar, resolved, param, temp, i + 1, insnName);
                case NONE -> throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "' parameter #" + (i + 1) +
                        " has no default value metadata");
            }
            fixedArgs.add(temp);
            temporaryArgs.add(temp);
        }

        return new CompletedCallArgs(fixedArgs, temporaryArgs);
    }

    static void materializeLiteralDefault(@NotNull CBodyBuilder bodyBuilder,
                                          @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                          @NotNull BackendMethodCallResolver.MethodParamSpec param,
                                          @NotNull CBodyBuilder.TempVar temp,
                                          int parameterIndexBaseOne) {
        var literal = param.defaultLiteral();
        if (literal == null || literal.isBlank()) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " has empty literal default metadata");
        }
        bodyBuilder.helper().builtinBuilder().materializeUtilityDefaultValue(
                bodyBuilder,
                temp,
                literal,
                resolved.ownerClassName() + "." + resolved.methodName(),
                parameterIndexBaseOne
        );
    }

    /// `receiverVar` is null on the static route (`CALL_STATIC_METHOD`); a non-static
    /// `default_value_func` consumes the receiver, so that combination is rejected rather than
    /// silently dereferencing a missing receiver.
    static void materializeFunctionDefault(@NotNull CBodyBuilder bodyBuilder,
                                           @Nullable LirVariable receiverVar,
                                           @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                           @NotNull BackendMethodCallResolver.MethodParamSpec param,
                                           @NotNull CBodyBuilder.TempVar temp,
                                           int parameterIndexBaseOne,
                                           @NotNull String insnName) {
        var defaultFunctionName = param.defaultFunctionName();
        if (defaultFunctionName == null || defaultFunctionName.isBlank()) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " has empty default_value_func metadata");
        }

        var ownerClassDef = bodyBuilder.classRegistry().getClassDef(new GdObjectType(resolved.ownerClassName()));
        if (ownerClassDef == null) {
            throw bodyBuilder.invalidInsn("Method owner type '" + resolved.ownerClassName() +
                    "' metadata is missing while resolving default_value_func '" + defaultFunctionName + "'");
        }

        var defaultFunction = findDefaultFunction(bodyBuilder, ownerClassDef.getFunctions(), resolved, defaultFunctionName);
        if (!defaultFunction.isStatic() && receiverVar == null) {
            // Static route (`CALL_STATIC_METHOD`): an instance default_value_func consumes the
            // receiver the static call does not have — reject before the generic contract check
            // so the diagnostic names the actual mismatch.
            throw bodyBuilder.invalidInsn(insnName + " default_value_func '" + defaultFunctionName +
                    "' of method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' is an instance function but the call has no receiver");
        }
        validateDefaultFunctionContract(bodyBuilder, resolved, receiverVar, param, defaultFunction, parameterIndexBaseOne);

        var defaultCallArgs = new ArrayList<CBodyBuilder.ValueRef>(1);
        if (!defaultFunction.isStatic()) {
            // Non-null by the guard above: an instance default_value_func on the receiver-less
            // static route was already rejected. requireNonNull only pins the invariant for
            // data-flow analysis; it cannot fire.
            defaultCallArgs.add(BackendPropertyAccessResolver.renderReceiverValue(
                    bodyBuilder,
                    Objects.requireNonNull(receiverVar),
                    new GdObjectType(resolved.ownerClassName()),
                    insnName,
                    "method owner",
                    ""
            ));
        }
        var defaultCFunctionName = resolved.ownerClassName() + "_" + defaultFunctionName;
        bodyBuilder.callAssign(temp, defaultCFunctionName, param.type(), defaultCallArgs);
    }

    static @NotNull FunctionDef findDefaultFunction(@NotNull CBodyBuilder bodyBuilder,
                                                    @NotNull List<? extends FunctionDef> ownerFunctions,
                                                    @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                                    @NotNull String defaultFunctionName) {
        FunctionDef found = null;
        for (var function : ownerFunctions) {
            if (!defaultFunctionName.equals(function.getName())) {
                continue;
            }
            if (found != null) {
                throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                        "' default_value_func '" + defaultFunctionName + "' is ambiguous in owner '" +
                        resolved.ownerClassName() + "'");
            }
            found = function;
        }
        if (found == null) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' default_value_func '" + defaultFunctionName + "' is missing in owner '" +
                    resolved.ownerClassName() + "'");
        }
        return found;
    }

    static void validateDefaultFunctionContract(@NotNull CBodyBuilder bodyBuilder,
                                                @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                                @Nullable LirVariable receiverVar,
                                                @NotNull BackendMethodCallResolver.MethodParamSpec param,
                                                @NotNull FunctionDef defaultFunction,
                                                int parameterIndexBaseOne) {
        if (defaultFunction.isVararg()) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                    defaultFunction.getName() + "' must not be vararg");
        }
        var expectedParamCount = defaultFunction.isStatic() ? 0 : 1;
        if (defaultFunction.getParameterCount() != expectedParamCount) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                    defaultFunction.getName() + "' expects " + defaultFunction.getParameterCount() +
                    " parameters, but only " + expectedParamCount + " is supported");
        }
        if (!defaultFunction.isStatic()) {
            var selfParam = defaultFunction.getParameter(0);
            if (receiverVar == null || selfParam == null
                    || !bodyBuilder.classRegistry().checkAssignable(receiverVar.type(), selfParam.getType())) {
                throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                        "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                        defaultFunction.getName() + "' has incompatible receiver parameter type");
            }
        }
        if (!bodyBuilder.classRegistry().checkAssignable(defaultFunction.getReturnType(), param.type())) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' parameter #" + parameterIndexBaseOne + " default_value_func '" +
                    defaultFunction.getName() + "' returns '" + defaultFunction.getReturnType().getTypeName() +
                    "', incompatible with parameter type '" + param.type().getTypeName() + "'");
        }
    }

    static void destroyTemporaryArgs(@NotNull CBodyBuilder bodyBuilder,
                                     @NotNull List<CBodyBuilder.TempVar> temporaryArgs) {
        for (var i = temporaryArgs.size() - 1; i >= 0; i--) {
            bodyBuilder.destroyTempVar(temporaryArgs.get(i));
        }
    }

    private static void destroyDefaultTemps(@NotNull CBodyBuilder bodyBuilder,
                                            @NotNull List<CBodyBuilder.TempVar> temps) {
        destroyTemporaryArgs(bodyBuilder, temps);
    }

    static void validateVarargs(@NotNull CBodyBuilder bodyBuilder,
                                @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved,
                                @NotNull List<LirVariable> argVars,
                                int fixedCount) {
        for (var i = fixedCount; i < argVars.size(); i++) {
            var argType = argVars.get(i).type();
            if (!bodyBuilder.classRegistry().checkAssignable(argType, GdVariantType.VARIANT)) {
                throw bodyBuilder.invalidInsn("Vararg argument #" + (i + 1) + " of method '" +
                        resolved.ownerClassName() + "." + resolved.methodName() +
                        "' must be Variant, got '" + argType.getTypeName() + "'");
            }
        }
    }

    private @NotNull LirVariable resolveReceiverVar(@NotNull CBodyBuilder bodyBuilder,
                                                    @NotNull String receiverId) {
        var receiverVar = bodyBuilder.func().getVariableById(receiverId);
        if (receiverVar == null) {
            throw bodyBuilder.invalidInsn("Receiver variable ID '" + receiverId + "' not found in function");
        }
        if (receiverVar.type() instanceof GdVoidType) {
            throw bodyBuilder.invalidInsn("Receiver variable '" + receiverId + "' cannot be void");
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, receiverVar, "call_method receiver");
        return receiverVar;
    }

    static @NotNull List<LirVariable> resolveArgumentVariables(@NotNull CBodyBuilder bodyBuilder,
                                                               @NotNull String methodName,
                                                               @NotNull List<LirInstruction.Operand> operands,
                                                               @NotNull String insnName) {
        var out = new ArrayList<LirVariable>(operands.size());
        for (var i = 0; i < operands.size(); i++) {
            var operand = operands.get(i);
            if (!(operand instanceof LirInstruction.VariableOperand(var argId))) {
                throw bodyBuilder.invalidInsn("Argument #" + (i + 1) + " of method '" + methodName +
                        "' must be a variable operand");
            }
            var argVar = bodyBuilder.func().getVariableById(argId);
            if (argVar == null) {
                throw bodyBuilder.invalidInsn("Argument variable ID '" + argId + "' not found in function");
            }
            InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, argVar, insnName + " argument #" + (i + 1));
            out.add(argVar);
        }
        return out;
    }

    static @NotNull CBodyBuilder.TargetRef resolveResultTarget(@NotNull CBodyBuilder bodyBuilder,
                                                               @Nullable String resultId,
                                                               @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        if (resultId == null) {
            return bodyBuilder.discardRef();
        }
        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' not found in function");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' cannot be a reference");
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVar, "method call result target");
        if (!bodyBuilder.classRegistry().checkAssignable(resolved.returnType(), resultVar.type())) {
            throw bodyBuilder.invalidInsn("Method '" + resolved.ownerClassName() + "." + resolved.methodName() +
                    "' returns '" + resolved.returnType().getTypeName() + "', but result variable '" + resultId +
                    "' has incompatible type '" + resultVar.type().getTypeName() + "'");
        }
        return bodyBuilder.targetOfVar(resultVar);
    }

    private @Nullable LirVariable resolveDynamicResultVariable(@NotNull CBodyBuilder bodyBuilder,
                                                               @NotNull CallMethodInsn instruction) {
        var resultId = instruction.resultId();
        if (resultId == null) {
            return null;
        }
        var resultVar = bodyBuilder.func().getVariableById(resultId);
        if (resultVar == null) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' not found in function");
        }
        if (resultVar.ref()) {
            throw bodyBuilder.invalidInsn("Result variable ID '" + resultId + "' cannot be a reference");
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, resultVar, "dynamic call result target");
        return resultVar;
    }
}
