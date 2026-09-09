package gd.script.gdcc.backend.c.gen.insn;

import gd.script.gdcc.backend.c.gen.CBodyBuilder;
import gd.script.gdcc.backend.c.gen.binding.EngineMethodAbiSignature;
import gd.script.gdcc.backend.c.gen.binding.EngineMethodSymbolKey;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirVariable;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ParameterDef;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.scope.resolver.ScopeDefaultArgKind;
import gd.script.gdcc.scope.resolver.ScopeMethodParameter;
import gd.script.gdcc.scope.resolver.ScopeMethodResolver;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdPrimitiveType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Backend adapter for the shared method resolver.
///
/// - validate backend-only receiver/argument variable shape constraints
/// - translate shared results into legacy `ResolvedMethodCall` records consumed by codegen
/// - preserve the existing `DispatchMode` contract used by `CallMethodInsnGen`
public final class BackendMethodCallResolver {
    private BackendMethodCallResolver() {
    }

    public enum DispatchMode {
        GDCC,
        ENGINE,
        BUILTIN,
        OBJECT_DYNAMIC,
        VARIANT_DYNAMIC
    }

    public enum DefaultArgKind {
        NONE,
        LITERAL,
        FUNCTION
    }

    /// Exact-engine helpers use one normalized callable surface and let the generated helper decide
    /// how ptrcall should see each fixed argument.
    public enum EngineHelperSlotMode {
        /// The helper parameter is the normalized value itself, and ptrcall receives `&arg`.
        VALUE_ADDRESS,
        /// The helper parameter already points at stable storage, so ptrcall receives `arg` directly.
        STORAGE_POINTER,
        /// The helper parameter stays normalized, but the helper must first materialize a raw leaf slot.
        LOCAL_VALUE_SLOT_ADDRESS
    }

    /// Backend-only per-parameter ABI facts that do not belong in the shared semantic signature.
    public sealed interface ExtraParamSpecData permits EngineHelperSlotExtraParamSpecData {
    }

    /// Shared semantic resolution keeps enum/bitfield parameters normalized as `int`.
    /// Exact-engine helpers still need the raw exported leaf CType so they can materialize the
    /// ptrcall slot locally without pushing wrapper-compatible casts back into Java callers.
    public record EngineHelperSlotExtraParamSpecData(@NotNull EngineHelperSlotMode slotMode,
                                                     @Nullable String slotCType) implements ExtraParamSpecData {
        public EngineHelperSlotExtraParamSpecData {
            Objects.requireNonNull(slotMode);
            if (slotMode == EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS &&
                    (slotCType == null || slotCType.isBlank())) {
                throw new IllegalArgumentException("slotCType must be present for LOCAL_VALUE_SLOT_ADDRESS");
            }
        }
    }

    public record MethodParamSpec(@NotNull String name,
                                  @NotNull GdType type,
                                  @NotNull DefaultArgKind defaultKind,
                                  @Nullable String defaultLiteral,
                                  @Nullable String defaultFunctionName,
                                  @Nullable ExtraParamSpecData extraParamSpecData) {
        public MethodParamSpec {
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            Objects.requireNonNull(defaultKind);
            if (defaultKind == DefaultArgKind.LITERAL && (defaultLiteral == null || defaultLiteral.isBlank())) {
                throw new IllegalArgumentException("defaultLiteral must be present when defaultKind is LITERAL");
            }
            if (defaultKind == DefaultArgKind.FUNCTION &&
                    (defaultFunctionName == null || defaultFunctionName.isBlank())) {
                throw new IllegalArgumentException("defaultFunctionName must be present when defaultKind is FUNCTION");
            }
        }

        public boolean hasDefaultValue() {
            return defaultKind != DefaultArgKind.NONE;
        }

        public @NotNull EngineHelperSlotMode engineHelperSlotMode() {
            var slotExtraData = engineHelperSlotExtraParamSpecData();
            if (slotExtraData != null) {
                return slotExtraData.slotMode();
            }
            return type instanceof GdPrimitiveType || type instanceof GdObjectType
                    ? EngineHelperSlotMode.VALUE_ADDRESS
                    : EngineHelperSlotMode.STORAGE_POINTER;
        }

        public boolean requiresEngineHelperLocalValueSlot() {
            return engineHelperSlotMode() == EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS;
        }

        public @Nullable String engineHelperLocalSlotCType() {
            var slotExtraData = engineHelperSlotExtraParamSpecData();
            return slotExtraData == null ? null : slotExtraData.slotCType();
        }

        public @Nullable EngineHelperSlotExtraParamSpecData engineHelperSlotExtraParamSpecData() {
            return extraParamSpecData instanceof EngineHelperSlotExtraParamSpecData slotExtraData
                    ? slotExtraData
                    : null;
        }
    }

    /// Backend-only bind lookup identity for exact engine methods.
    /// Shared semantic resolution stays normalized and does not carry these direct-bind facts.
    public record EngineMethodBindSpec(long hash, @NotNull List<Long> hashCompatibility) {
        public EngineMethodBindSpec {
            if (hash == 0L) {
                throw new IllegalArgumentException("engine method bind hash must not be zero");
            }
            hashCompatibility = List.copyOf(hashCompatibility);
        }
    }

    public record ResolvedMethodCall(@NotNull DispatchMode mode,
                                     @NotNull String methodName,
                                     @NotNull String ownerClassName,
                                     @NotNull GdType ownerType,
                                     @NotNull String cFunctionName,
                                     @NotNull GdType returnType,
                                     @NotNull List<MethodParamSpec> parameters,
                                     @Nullable EngineMethodBindSpec engineMethodBindSpec,
                                     boolean isVararg,
                                     boolean isStatic,
                                     boolean coroutine) {
        public ResolvedMethodCall {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(methodName);
            Objects.requireNonNull(ownerClassName);
            Objects.requireNonNull(ownerType);
            Objects.requireNonNull(cFunctionName);
            Objects.requireNonNull(returnType);
            parameters = List.copyOf(parameters);
        }
    }

    public static @NotNull ResolvedMethodCall resolve(@NotNull CBodyBuilder bodyBuilder,
                                                      @NotNull LirVariable receiverVar,
                                                      @NotNull String methodName,
                                                      @NotNull List<LirVariable> argVars) {
        var receiverType = receiverVar.type();
        InsnGenSupport.rejectCompilerOnlyType(bodyBuilder, receiverType, "call_method receiver");
        if (receiverType instanceof GdVoidType || receiverType instanceof GdNilType) {
            throw bodyBuilder.invalidInsn("Receiver variable '" + receiverVar.id() +
                    "' has invalid type '" + receiverType.getTypeName() + "' for call_method");
        }

        for (var i = 0; i < argVars.size(); i++) {
            InsnGenSupport.rejectCompilerOnlyType(bodyBuilder, argVars.get(i).type(), "call_method argument #" + (i + 1));
        }

        var argTypes = argVars.stream().map(LirVariable::type).toList();
        var result = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                receiverType,
                methodName,
                argTypes
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> toResolvedMethodCall(bodyBuilder, resolved.method());
            case ScopeMethodResolver.DynamicFallback dynamicFallback ->
                    dynamicPlaceholder(toDispatchMode(dynamicFallback.dynamicKind()), receiverType, methodName);
            case ScopeMethodResolver.Failed failed -> throw bodyBuilder.invalidInsn(failed.message());
        };
    }

    /// Resolve a `super` call (vtable plan §2.5 / Step 5). `super` is lexical: the receiver must
    /// be the containing class's own `self` (static type exactly `bodyBuilder.clazz()`), and
    /// resolution starts at that class's declared super name — never at the receiver's own type —
    /// so the emitted call always names the fixed nearest-ancestor implementation. Dynamic
    /// fallback and unresolvable chains (including a missing super class) are compile-time errors
    /// per D2; `super._init` is rejected by the shared resolver's constructor-route guard.
    public static @NotNull ResolvedMethodCall resolveSuper(@NotNull CBodyBuilder bodyBuilder,
                                                           @NotNull LirVariable receiverVar,
                                                           @NotNull String methodName,
                                                           @NotNull List<LirVariable> argVars) {
        var currentClassName = bodyBuilder.clazz().getName();
        if (!(receiverVar.type() instanceof GdObjectType)
                || !receiverVar.type().getTypeName().equals(currentClassName)) {
            throw bodyBuilder.invalidInsn("call_super_method receiver '" + receiverVar.id() +
                    "' must be the lexical self of class '" + currentClassName + "', got static type '" +
                    receiverVar.type().getTypeName() + "'");
        }
        for (var i = 0; i < argVars.size(); i++) {
            InsnGenSupport.rejectCompilerOnlyType(bodyBuilder, argVars.get(i).type(), "call_super_method argument #" + (i + 1));
        }

        var superName = bodyBuilder.clazz().getSuperName();
        if (superName.isBlank()) {
            throw bodyBuilder.invalidInsn("call_super_method '" + methodName + "' on class '" + currentClassName +
                    "' has no super class to resolve from");
        }
        var argTypes = argVars.stream().map(LirVariable::type).toList();
        var result = ScopeMethodResolver.resolveInstanceMethod(
                bodyBuilder.classRegistry(),
                new GdObjectType(superName),
                methodName,
                argTypes
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> {
                var call = toResolvedMethodCall(bodyBuilder, resolved.method());
                if (call.isStatic()) {
                    // `super` is instance semantics; the shared instance resolver can still leave
                    // a static candidate when no instance overload matches, but such anomalous IR
                    // must fail fast rather than silently drop the receiver (unlike CALL_METHOD,
                    // which only warns, super has no valid static form at all).
                    throw bodyBuilder.invalidInsn("call_super_method '" + currentClassName + "." + methodName +
                            "' resolved to static method '" + call.ownerClassName() + "." + methodName +
                            "': super calls are instance-only");
                }
                yield call;
            }
            case ScopeMethodResolver.DynamicFallback dynamicFallback -> throw bodyBuilder.invalidInsn(
                    "call_super_method '" + currentClassName + "." + methodName + "' is not statically resolvable (" +
                            dynamicFallback.reason() + "): super calls never fall back to dynamic dispatch");
            case ScopeMethodResolver.Failed failed -> throw bodyBuilder.invalidInsn(failed.message());
        };
    }

    /// Static counterpart of `resolve(...)`.
    ///
    /// `className` is the receiver canonical name published by lowering (GDCC class, inner class
    /// `Outer__sub__Inner`, engine class, or builtin type name); it is restored to a `ScopeTypeMeta`
    /// through `ClassRegistry.resolveTypeMeta` — never `getClassDef(new GdObjectType(...))`, which
    /// misses builtin owners. The shared static resolver can only produce `Resolved`/`Failed` (no
    /// dynamic fallback exists for static calls), and the emitted C symbol/start thunk names the
    /// declaring owner class (`resolved.ownerClass()`), which differs from `className` under
    /// inheritance.
    public static @NotNull ResolvedMethodCall resolveStatic(@NotNull CBodyBuilder bodyBuilder,
                                                            @NotNull String className,
                                                            @NotNull String methodName,
                                                            @NotNull List<LirVariable> argVars) {
        for (var i = 0; i < argVars.size(); i++) {
            InsnGenSupport.rejectCompilerOnlyType(bodyBuilder, argVars.get(i).type(), "call_static_method argument #" + (i + 1));
        }

        var receiverTypeMeta = bodyBuilder.classRegistry().resolveTypeMeta(className);
        if (receiverTypeMeta == null) {
            throw bodyBuilder.invalidInsn("Static method owner type '" + className + "' was not found");
        }
        var argTypes = argVars.stream().map(LirVariable::type).toList();
        var result = ScopeMethodResolver.resolveStaticMethod(
                bodyBuilder.classRegistry(),
                receiverTypeMeta,
                methodName,
                argTypes
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> toResolvedMethodCall(bodyBuilder, resolved.method());
            case ScopeMethodResolver.DynamicFallback _ -> throw bodyBuilder.invalidInsn(
                    "Static method '" + className + "." + methodName + "' unexpectedly resolved to dynamic dispatch");
            case ScopeMethodResolver.Failed failed -> throw bodyBuilder.invalidInsn(failed.message());
        };
    }

    private static @NotNull DispatchMode toDispatchMode(@NotNull ScopeMethodResolver.DynamicKind dynamicKind) {
        return switch (dynamicKind) {
            case OBJECT_DYNAMIC -> DispatchMode.OBJECT_DYNAMIC;
            case VARIANT_DYNAMIC -> DispatchMode.VARIANT_DYNAMIC;
        };
    }

    private static @NotNull DispatchMode toDispatchMode(@NotNull ScopeOwnerKind ownerKind) {
        return switch (ownerKind) {
            case GDCC -> DispatchMode.GDCC;
            case ENGINE -> DispatchMode.ENGINE;
            case BUILTIN -> DispatchMode.BUILTIN;
        };
    }

    private static @NotNull ResolvedMethodCall dynamicPlaceholder(@NotNull DispatchMode mode,
                                                                  @NotNull GdType receiverType,
                                                                  @NotNull String methodName) {
        return new ResolvedMethodCall(
                mode,
                methodName,
                receiverType.getTypeName(),
                receiverType,
                "",
                GdVariantType.VARIANT,
                List.of(),
                null,
                true,
                false,
                false
        );
    }

    private static @NotNull ResolvedMethodCall toResolvedMethodCall(@NotNull CBodyBuilder bodyBuilder,
                                                                    @NotNull ScopeResolvedMethod resolved) {
        var mode = toDispatchMode(resolved.ownerKind());
        var ownerClassName = resolved.ownerClass().getName();
        var parameters = toMethodParamSpecs(resolved);
        var engineMethodBindSpec = resolveEngineMethodBindSpec(bodyBuilder, resolved, mode);
        // Internal coroutine-call ABI (`gdcc_low_ir.md` §Coroutine Instructions): a call on an
        // `is_coroutine="true"` GDCC callee targets the coroutine-start thunk (which always
        // hands back the OWNED state object reference) instead of the ClassDB entry, and the
        // call result is the compiler-only `compiler::GdccCoroState` single-consumer value.
        var coroutine = mode == DispatchMode.GDCC
                && resolved.function() instanceof LirFunctionDef lirFunction
                && lirFunction.isCoroutine();
        return new ResolvedMethodCall(
                mode,
                resolved.methodName(),
                ownerClassName,
                resolved.ownerType(),
                coroutine
                        ? bodyBuilder.helper().renderCoroStartThunkName(resolved.ownerClass(), resolved.function())
                        : renderMethodCFunctionName(
                        mode,
                        ownerClassName,
                        resolved.methodName(),
                        parameters,
                        resolved.returnType(),
                        engineMethodBindSpec,
                        resolved.isVararg(),
                        resolved.isStatic()
                ),
                resolved.returnType(),
                parameters,
                engineMethodBindSpec,
                resolved.isVararg(),
                resolved.isStatic(),
                coroutine
        );
    }

    static @NotNull List<MethodParamSpec> toMethodParamSpecs(@NotNull ScopeResolvedMethod resolved) {
        var sourceParameters = resolved.function().getParameters();
        var parameters = new java.util.ArrayList<MethodParamSpec>(resolved.parameters().size());
        for (var i = 0; i < resolved.parameters().size(); i++) {
            var sourceParameter = isExtensionMethodMetadata(resolved)
                    ? sourceParameters.get(i)
                    : null;
            parameters.add(toMethodParamSpec(resolved.parameters().get(i), sourceParameter));
        }
        return List.copyOf(parameters);
    }

    /// Only exact engine class metadata contributes method-bind identity.
    /// A non-zero primary hash is required for every exact engine route.
    private static @Nullable EngineMethodBindSpec resolveEngineMethodBindSpec(@NotNull CBodyBuilder bodyBuilder,
                                                                              @NotNull ScopeResolvedMethod resolved,
                                                                              @NotNull DispatchMode mode) {
        if (mode != DispatchMode.ENGINE) {
            return null;
        }
        return switch (resolved.function()) {
            case ExtensionGdClass.ClassMethod method when method.hash() != 0L ->
                    new EngineMethodBindSpec(method.hash(), normalizeHashCompatibility(method.hashCompatibility()));
            case ExtensionGdClass.ClassMethod _ -> throw bodyBuilder.invalidInsn(
                    "Exact engine method '" + resolved.ownerClass().getName() + "." + resolved.methodName() +
                            "' is missing method-bind hash in extension metadata"
            );
            default -> null;
        };
    }

    private static @NotNull List<Long> normalizeHashCompatibility(@Nullable List<Long> hashCompatibility) {
        return hashCompatibility == null ? List.of() : hashCompatibility;
    }

    private static boolean isExtensionMethodMetadata(@NotNull ScopeResolvedMethod resolved) {
        return resolved.function() instanceof ExtensionBuiltinClass.ClassMethod
                || resolved.function() instanceof ExtensionGdClass.ClassMethod;
    }

    private static @NotNull MethodParamSpec toMethodParamSpec(@NotNull ScopeMethodParameter parameter,
                                                              @Nullable ParameterDef sourceParameter) {
        return new MethodParamSpec(
                parameter.name(),
                parameter.type(),
                toDefaultArgKind(parameter.defaultArgKind()),
                parameter.defaultLiteral(),
                parameter.defaultFunctionName(),
                resolveExtraParamSpecData(sourceParameter)
        );
    }

    /// Shared semantic resolution keeps the published parameter type normalized.
    /// Backend-only helper slot facts stay here so the shared resolver does not need to care about
    /// raw ptrcall slot spellings exported by extension metadata.
    private static @Nullable ExtraParamSpecData resolveExtraParamSpecData(@Nullable ParameterDef sourceParameter) {
        if (!(sourceParameter instanceof ExtensionFunctionArgument extensionArgument)) {
            return null;
        }
        var rawType = extensionArgument.type();
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        return switch (detectEngineHelperLocalSlotMetadataFamily(rawType)) {
            case NONE -> null;
            case ENUM, BITFIELD -> new EngineHelperSlotExtraParamSpecData(
                    EngineHelperSlotMode.LOCAL_VALUE_SLOT_ADDRESS,
                    renderEngineHelperLocalSlotCType(rawType)
            );
        };
    }

    private static @NotNull EngineHelperLocalSlotMetadataFamily detectEngineHelperLocalSlotMetadataFamily(
            @NotNull String rawType
    ) {
        var normalized = rawType.trim();
        if (normalized.startsWith("enum::")) {
            return EngineHelperLocalSlotMetadataFamily.ENUM;
        }
        if (normalized.startsWith("bitfield::")) {
            return EngineHelperLocalSlotMetadataFamily.BITFIELD;
        }
        return EngineHelperLocalSlotMetadataFamily.NONE;
    }

    private static @NotNull String renderEngineHelperLocalSlotCType(@NotNull String rawType) {
        var normalized = rawType.trim();
        var family = detectEngineHelperLocalSlotMetadataFamily(normalized);
        if (family == EngineHelperLocalSlotMetadataFamily.NONE) {
            throw new IllegalArgumentException("Unsupported engine helper local-slot metadata: " + rawType);
        }
        var leafName = normalized.substring(normalized.indexOf("::") + 2).trim();
        if (leafName.isBlank()) {
            throw new IllegalArgumentException("Malformed engine helper local-slot metadata type: " + rawType);
        }
        return "godot_" + leafName.replace('.', '_');
    }

    private enum EngineHelperLocalSlotMetadataFamily {
        NONE,
        ENUM,
        BITFIELD
    }

    private static @NotNull DefaultArgKind toDefaultArgKind(@NotNull ScopeDefaultArgKind defaultArgKind) {
        return switch (defaultArgKind) {
            case NONE -> DefaultArgKind.NONE;
            case LITERAL -> DefaultArgKind.LITERAL;
            case FUNCTION -> DefaultArgKind.FUNCTION;
        };
    }

    private static @NotNull String renderMethodCFunctionName(@NotNull DispatchMode mode,
                                                             @NotNull String ownerClassName,
                                                             @NotNull String methodName,
                                                             @NotNull List<MethodParamSpec> parameters,
                                                             @NotNull GdType returnType,
                                                             @Nullable EngineMethodBindSpec engineMethodBindSpec,
                                                             boolean isVararg,
                                                             boolean isStatic) {
        return switch (mode) {
            case GDCC -> ownerClassName + "_" + methodName;
            case ENGINE -> renderEngineMethodCFunctionName(
                    ownerClassName,
                    methodName,
                    parameters,
                    returnType,
                    engineMethodBindSpec,
                    isVararg,
                    isStatic
            );
            case BUILTIN -> "godot_" + ownerClassName + "_" + methodName;
            default -> "";
        };
    }

    /// Exact engine helper naming assumes bind lookup identity has already been validated.
    static @NotNull String renderEngineMethodCFunctionName(@NotNull String ownerClassName,
                                                           @NotNull String methodName,
                                                           @NotNull List<MethodParamSpec> parameters,
                                                           @NotNull GdType returnType,
                                                           @Nullable EngineMethodBindSpec engineMethodBindSpec,
                                                           boolean isVararg,
                                                           boolean isStatic) {
        Objects.requireNonNull(engineMethodBindSpec, "Exact engine route requires method bind spec");
        return new EngineMethodSymbolKey(
                ownerClassName,
                methodName,
                isStatic,
                new EngineMethodAbiSignature(
                        parameters.stream().map(MethodParamSpec::type).toList(),
                        returnType,
                        isVararg
                )
        ).renderCallHelperName();
    }
}
