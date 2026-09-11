package gd.script.gdcc.frontend.sema;

import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ParameterDef;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.resolver.ScopeMethodParameter;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// Published call-resolution fact for one body-phase chain step.
///
/// The model keeps route kind and result status separate so downstream consumers can distinguish
/// constructor/static/dynamic paths without flattening everything into one generic "call hit".
/// `argumentTypes()` stays the call-site argument snapshot; any exact callable signature published
/// from shared resolver metadata must live in `exactCallableBoundary()` instead of reusing that list.
/// The contract is single publication plus downstream reuse: once the shared resolver has emitted an
/// exact boundary for one selected callable, later frontend stages must consume that fact rather than
/// reconstructing another signature projection from raw metadata.
public record FrontendResolvedCall(
        @NotNull String callableName,
        @NotNull FrontendCallResolutionKind callKind,
        @NotNull FrontendCallResolutionStatus status,
        @NotNull FrontendReceiverKind receiverKind,
        @Nullable ScopeOwnerKind ownerKind,
        @Nullable GdType receiverType,
        @Nullable GdType returnType,
        @NotNull List<GdType> argumentTypes,
        @Nullable ExactCallableBoundary exactCallableBoundary,
        @Nullable Object declarationSite,
        @Nullable String detailReason
) {
    public FrontendResolvedCall {
        callableName = StringUtil.requireNonBlank(callableName, "callableName");
        Objects.requireNonNull(callKind, "callKind must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(receiverKind, "receiverKind must not be null");
        argumentTypes = copyTypeList(argumentTypes, "argumentTypes");
        exactCallableBoundary = copyExactCallableBoundary(exactCallableBoundary);
        validateState(callKind, status, receiverKind, receiverType, returnType, detailReason, exactCallableBoundary);
    }

    public static @NotNull FrontendResolvedCall resolved(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType returnType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite
    ) {
        return resolved(
                callableName,
                callKind,
                receiverKind,
                ownerKind,
                receiverType,
                returnType,
                argumentTypes,
                declarationSite,
                null
        );
    }

    public static @NotNull FrontendResolvedCall resolved(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType returnType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @Nullable ExactCallableBoundary exactCallableBoundary
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.RESOLVED,
                receiverKind,
                ownerKind,
                receiverType,
                Objects.requireNonNull(returnType, "returnType must not be null"),
                argumentTypes,
                exactCallableBoundary,
                declarationSite,
                null
        );
    }

    public static @NotNull FrontendResolvedCall blocked(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType returnType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return blocked(
                callableName,
                callKind,
                receiverKind,
                ownerKind,
                receiverType,
                returnType,
                argumentTypes,
                declarationSite,
                detailReason,
                null
        );
    }

    public static @NotNull FrontendResolvedCall blocked(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull GdType returnType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason,
            @Nullable ExactCallableBoundary exactCallableBoundary
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.BLOCKED,
                receiverKind,
                ownerKind,
                receiverType,
                Objects.requireNonNull(returnType, "returnType must not be null"),
                argumentTypes,
                exactCallableBoundary,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall deferred(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.DEFERRED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                null,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall dynamic(
            @NotNull String callableName,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                FrontendCallResolutionKind.DYNAMIC_FALLBACK,
                FrontendCallResolutionStatus.DYNAMIC,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                null,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall failed(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.FAILED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                null,
                declarationSite,
                detailReason
        );
    }

    public static @NotNull FrontendResolvedCall unsupported(
            @NotNull String callableName,
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable ScopeOwnerKind ownerKind,
            @Nullable GdType receiverType,
            @NotNull List<GdType> argumentTypes,
            @Nullable Object declarationSite,
            @NotNull String detailReason
    ) {
        return new FrontendResolvedCall(
                callableName,
                callKind,
                FrontendCallResolutionStatus.UNSUPPORTED,
                receiverKind,
                ownerKind,
                receiverType,
                null,
                argumentTypes,
                null,
                declarationSite,
                detailReason
        );
    }

    private static void validateState(
            @NotNull FrontendCallResolutionKind callKind,
            @NotNull FrontendCallResolutionStatus status,
            @NotNull FrontendReceiverKind receiverKind,
            @Nullable GdType receiverType,
            @Nullable GdType returnType,
            @Nullable String detailReason,
            @Nullable ExactCallableBoundary exactCallableBoundary
    ) {
        if (exactCallableBoundary != null) {
            if (status != FrontendCallResolutionStatus.RESOLVED
                    && status != FrontendCallResolutionStatus.BLOCKED) {
                throw new IllegalArgumentException(
                        "exactCallableBoundary is only valid for RESOLVED or BLOCKED call results"
                );
            }
            if (callKind != FrontendCallResolutionKind.INSTANCE_METHOD
                    && callKind != FrontendCallResolutionKind.STATIC_METHOD
                    && callKind != FrontendCallResolutionKind.SUPER_METHOD
                    && callKind != FrontendCallResolutionKind.CONSTRUCTOR) {
                throw new IllegalArgumentException(
                        "exactCallableBoundary is only valid for exact instance/static/super/constructor routes"
                );
            }
        }
        if (status == FrontendCallResolutionStatus.RESOLVED
                || status == FrontendCallResolutionStatus.BLOCKED) {
            if (callKind == FrontendCallResolutionKind.UNKNOWN
                    || callKind == FrontendCallResolutionKind.DYNAMIC_FALLBACK) {
                throw new IllegalArgumentException("callKind must be a concrete route for resolved/blocked call results");
            }
            if (receiverKind == FrontendReceiverKind.UNKNOWN) {
                throw new IllegalArgumentException("receiverKind must not be UNKNOWN for resolved/blocked call results");
            }
            Objects.requireNonNull(returnType, "returnType must not be null for resolved/blocked call results");
            // Exact instance-method resolution must never publish a Variant receiver surface.
            // `Variant` instance receivers are forced onto the runtime-dynamic route earlier by
            // shared method resolution; only the call result/outgoing carrier may still be Variant.
            if (status == FrontendCallResolutionStatus.RESOLVED
                    && callKind == FrontendCallResolutionKind.INSTANCE_METHOD
                    && receiverKind == FrontendReceiverKind.INSTANCE
                    && receiverType instanceof GdVariantType) {
                throw new IllegalArgumentException(
                        "RESOLVED instance method calls must not publish Variant receiverType; use DYNAMIC_FALLBACK instead"
                );
            }
            if (status == FrontendCallResolutionStatus.RESOLVED && detailReason != null) {
                throw new IllegalArgumentException("detailReason must be null for RESOLVED call results");
            }
            if (status == FrontendCallResolutionStatus.BLOCKED) {
                StringUtil.requireNonBlank(detailReason, "detailReason");
            }
            return;
        }

        if (returnType != null) {
            throw new IllegalArgumentException("returnType must be null for non-success call results");
        }
        StringUtil.requireNonBlank(detailReason, "detailReason");
        if (status == FrontendCallResolutionStatus.DYNAMIC) {
            if (callKind != FrontendCallResolutionKind.DYNAMIC_FALLBACK) {
                throw new IllegalArgumentException("DYNAMIC call results must use DYNAMIC_FALLBACK callKind");
            }
            if (receiverKind == FrontendReceiverKind.UNKNOWN) {
                throw new IllegalArgumentException("receiverKind must not be UNKNOWN for DYNAMIC call results");
            }
            return;
        }
        if (callKind == FrontendCallResolutionKind.DYNAMIC_FALLBACK) {
            throw new IllegalArgumentException("DYNAMIC_FALLBACK callKind is only valid for DYNAMIC call results");
        }
    }

    /// Exact callable-boundary fact published from one already-selected shared resolver result.
    ///
    /// This carries the normalized fixed-parameter signature once so later phases no longer need to
    /// rebuild it from raw extension metadata. It is intentionally separate from `argumentTypes()`,
    /// which still reports the call-site argument snapshot.
    public record ExactCallableBoundary(
            @NotNull List<GdType> fixedParameterTypes,
            boolean isVararg
    ) {
        public ExactCallableBoundary {
            fixedParameterTypes = copyTypeList(fixedParameterTypes, "fixedParameterTypes");
        }

        public static @NotNull ExactCallableBoundary fromResolvedMethod(@NotNull ScopeResolvedMethod resolvedMethod) {
            var fixedParameterTypes = Objects.requireNonNull(resolvedMethod, "resolvedMethod must not be null")
                    .parameters()
                    .stream()
                    .map(ScopeMethodParameter::type)
                    .toList();
            return new ExactCallableBoundary(fixedParameterTypes, resolvedMethod.isVararg());
        }

        /// Builds a boundary from a selected constructor/`FunctionDef` declaration site.
        public static @NotNull ExactCallableBoundary fromFunctionDef(@NotNull FunctionDef functionDef) {
            var fixedParameterTypes = Objects.requireNonNull(functionDef, "functionDef must not be null")
                    .getParameters()
                    .stream()
                    .map(ParameterDef::getType)
                    .toList();
            return new ExactCallableBoundary(fixedParameterTypes, functionDef.isVararg());
        }
    }

    private static @Nullable ExactCallableBoundary copyExactCallableBoundary(@Nullable ExactCallableBoundary exactCallableBoundary) {
        if (exactCallableBoundary == null) {
            return null;
        }
        return new ExactCallableBoundary(
                exactCallableBoundary.fixedParameterTypes(),
                exactCallableBoundary.isVararg()
        );
    }

    private static @NotNull List<GdType> copyTypeList(@Nullable List<GdType> types, @NotNull String fieldName) {
        var source = Objects.requireNonNull(types, fieldName + " must not be null");
        var copied = new ArrayList<GdType>(source.size());
        for (var type : source) {
            if (type == null) {
                throw new IllegalArgumentException(fieldName + " must not contain null elements");
            }
            copied.add(type);
        }
        return List.copyOf(copied);
    }
}
