package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.CInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.lir.insn.CallSuperMethodInsn;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.List;

/// C code generator for `CALL_SUPER_METHOD`.
///
/// A super call names the FIXED ancestor implementation selected by `BackendMethodCallResolver
/// .resolveSuper` from the lexical parent of the class being generated: GDCC owners emit a direct
/// `<Owner>_<method>` call (never the vtable-indirect form, even when the method owns a
/// polymorphic slot — super bypasses virtual dispatch), engine owners the exact engine helper, and
/// GDCC coroutine owners the start thunk. Receiver upcast, default completion, vararg, void/result
/// and lifecycle rules reuse the shared `CallMethodInsnGen` emitters unchanged.
public final class CallSuperMethodInsnGen implements CInsnGen<CallSuperMethodInsn> {
    @Override
    public @NotNull EnumSet<GdInstruction> getInsnOpcodes() {
        return EnumSet.of(GdInstruction.CALL_SUPER_METHOD);
    }

    @Override
    public void generateCCode(@NotNull CBodyBuilder bodyBuilder) {
        var instruction = bodyBuilder.getCurrentInsn(this);
        var receiverVar = resolveSuperReceiverVar(bodyBuilder, instruction.objectId());
        var argVars = CallMethodInsnGen.resolveArgumentVariables(
                bodyBuilder,
                instruction.methodName(),
                instruction.args(),
                "call_super_method"
        );

        var resolved = BackendMethodCallResolver.resolveSuper(bodyBuilder, receiverVar, instruction.methodName(), argVars);
        switch (resolved.mode()) {
            case GDCC, ENGINE -> emitSuperCall(bodyBuilder, instruction, receiverVar, argVars, resolved);
            // resolveSuper already fails on dynamic fallback; a builtin owner is unreachable because
            // a super class is always an object type. Anything else is an invariant violation.
            default -> throw bodyBuilder.invalidInsn("Unsupported call_super_method dispatch mode: " + resolved.mode());
        }
    }

    private void emitSuperCall(@NotNull CBodyBuilder bodyBuilder,
                               @NotNull CallSuperMethodInsn instruction,
                               @NotNull LirVariable receiverVar,
                               @NotNull List<LirVariable> argVars,
                               @NotNull BackendMethodCallResolver.ResolvedMethodCall resolved) {
        if (resolved.coroutine()) {
            if (resolved.isStatic()) {
                // Same anomalous-IR guard as CALL_METHOD: static coroutine calls lower to
                // `call_static_method`, whose start thunk has no receiver parameter.
                throw bodyBuilder.invalidInsn("Coroutine method '" + resolved.ownerClassName() + "." +
                        resolved.methodName() + "' is static: static coroutine calls are not supported" +
                        " via call_super_method (anomalous IR; static calls lower to call_static_method)");
            }
            CallMethodInsnGen.emitCoroutineStartCall(
                    bodyBuilder, instruction.resultId(), receiverVar, argVars, resolved, "CALL_SUPER_METHOD");
            return;
        }
        CallMethodInsnGen.emitResolvedCall(
                bodyBuilder, instruction.resultId(), receiverVar, argVars, resolved, "CALL_SUPER_METHOD");
    }

    /// Existence and compiler-only checks only; the lexical-self type contract itself is enforced
    /// inside `resolveSuper` so the invariant holds for every caller of the resolver.
    private @NotNull LirVariable resolveSuperReceiverVar(@NotNull CBodyBuilder bodyBuilder,
                                                         @NotNull String objectId) {
        var receiverVar = bodyBuilder.func().getVariableById(objectId);
        if (receiverVar == null) {
            throw bodyBuilder.invalidInsn("Receiver variable ID '" + objectId + "' not found in function");
        }
        InsnGenSupport.rejectCompilerOnlyVariable(bodyBuilder, receiverVar, "call_super_method receiver");
        return receiverVar;
    }
}
