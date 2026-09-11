package gd.script.gdcc.scope.resolver;

import gd.script.gdcc.exception.ScopeMethodResolutionException;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ParameterDef;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeTypeMetaKind;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.BiPredicate;
import java.util.function.ToIntBiFunction;

/// Shared method metadata resolver for frontend/backend.
///
/// Boundaries:
/// - supports instance receivers (`GdType`) and static/type-meta receivers (`ScopeTypeMeta`)
/// - centralizes candidate collection, applicability checks, and ranking rules
/// - does not decide frontend lowering shape or backend C emission details
/// - does not treat constructors (`ClassName.new(...)` / `_init`) as ordinary methods
/// - remains AST-free: index-aware ranking callbacks may encode call-site argument position, but
///   never parser AST nodes
public final class ScopeMethodResolver {
    private ScopeMethodResolver() {
    }

    /// Index-aware fixed-parameter compatibility rank.
    ///
    /// `argumentIndex` is the call-site fixed-argument position (0-based). Rank `0` rejects the
    /// pair; larger positive ranks describe a more specific accepted boundary for overload
    /// applicability (and for the default specificity path when no
    /// [CandidateSpecificity] is supplied). Frontend callers may encode
    /// container-literal element-boundary ranks here without pulling parser AST into the scope
    /// package.
    ///
    /// When a [CandidateSpecificity] callback is supplied, this rank still gates
    /// applicability via `matchesArguments`; candidate-level tie-breaking uses the callback
    /// instead of packing per-argument ranks into min/sum.
    @FunctionalInterface
    public interface ParameterCompatibilityRank {
        int rank(int argumentIndex, @NotNull GdType sourceType, @NotNull GdType targetType);
    }

    /// Optional candidate-level specificity comparator.
    ///
    /// Returns `true` when `candidate` is strictly more specific than `baseline` among already
    /// applicable overloads. Scope stays AST-free: frontend may close over call-site expressions
    /// and implement §5.4 `worstRank`/`totalRank` aggregation (for example via
    /// `literalAggregateRank`) without embedding AST types in this package.
    @FunctionalInterface
    public interface CandidateSpecificity {
        boolean isStrictlyMoreSpecific(
                @NotNull ScopeResolvedMethod candidate,
                @NotNull ScopeResolvedMethod baseline
        );
    }

    /// Runtime-dynamic route that the caller may still choose after static lookup stops.
    public enum DynamicKind {
        OBJECT_DYNAMIC,
        VARIANT_DYNAMIC
    }

    /// Why the resolver allowed a dynamic fallback instead of returning a unique candidate.
    public enum DynamicFallbackReason {
        RECEIVER_METADATA_UNKNOWN,
        METHOD_MISSING,
        AMBIGUOUS_OVERLOAD,
        VARIANT_RECEIVER
    }

    /// Stable hard-failure categories.
    public enum FailureKind {
        BUILTIN_CLASS_NOT_FOUND,
        METHOD_NOT_FOUND,
        NO_APPLICABLE_OVERLOAD,
        AMBIGUOUS_OVERLOAD,
        UNSUPPORTED_OWNER,
        MALFORMED_METADATA,
        UNSUPPORTED_STATIC_RECEIVER,
        CONSTRUCTOR_ROUTE_UNSUPPORTED
    }

    public sealed interface Result permits Resolved, DynamicFallback, Failed {
    }

    public record Resolved(@NotNull ScopeResolvedMethod method) implements Result {
        public Resolved {
            Objects.requireNonNull(method, "method");
        }
    }

    public record DynamicFallback(@NotNull DynamicKind dynamicKind,
                                  @NotNull DynamicFallbackReason reason) implements Result {
        public DynamicFallback {
            Objects.requireNonNull(dynamicKind, "dynamicKind");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Failed(@NotNull FailureKind kind,
                         @NotNull String message) implements Result {
        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(message, "message");
        }
    }

    private record MethodCandidate(@NotNull ScopeResolvedMethod resolved) {
        private MethodCandidate {
            Objects.requireNonNull(resolved, "resolved");
        }
    }

    private sealed interface CandidateSelection permits CandidateSelected, CandidateAmbiguous, CandidateRejected {
    }

    private enum MatchPreference {
        BETTER,
        WORSE,
        EQUAL
    }

    private record CandidateSelected(@NotNull MethodCandidate candidate) implements CandidateSelection {
        private CandidateSelected {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    private record CandidateAmbiguous(@NotNull List<MethodCandidate> pool) implements CandidateSelection {
        private CandidateAmbiguous {
            pool = List.copyOf(pool);
        }
    }

    private record CandidateRejected(@NotNull String message) implements CandidateSelection {
        private CandidateRejected {
            Objects.requireNonNull(message, "message");
        }
    }

    /// Resolve an instance-style method call.
    public static @NotNull Result resolveInstanceMethod(@NotNull ClassRegistry registry,
                                                        @NotNull GdType receiverType,
                                                        @NotNull String methodName,
                                                        @NotNull List<GdType> argTypes) {
        return resolveInstanceMethodWithParameterRank(
                registry,
                receiverType,
                methodName,
                argTypes,
                (sourceType, targetType) -> registry.checkAssignable(sourceType, targetType) ? 1 : 0
        );
    }

    /// Frontend may relax only the fixed-parameter applicability check by supplying a dedicated
    /// source/target compatibility predicate. Resolver ranking, fallback rules, and backend callers
    /// still stay on the shared strict path unless they opt into this overload explicitly.
    public static @NotNull Result resolveInstanceMethod(@NotNull ClassRegistry registry,
                                                        @NotNull GdType receiverType,
                                                        @NotNull String methodName,
                                                        @NotNull List<GdType> argTypes,
                                                        @NotNull BiPredicate<GdType, GdType> parameterCompatibility) {
        Objects.requireNonNull(parameterCompatibility, "parameterCompatibility");
        return resolveInstanceMethodWithParameterRank(
                registry,
                receiverType,
                methodName,
                argTypes,
                (sourceType, targetType) -> parameterCompatibility.test(sourceType, targetType) ? 1 : 0
        );
    }

    /// Frontend-specific callers use this rank-aware path when applicability alone is not enough:
    /// rank 0 rejects a source/target pair, while larger positive ranks describe a more specific
    /// accepted ordinary boundary for overload tie-breaking.
    public static @NotNull Result resolveInstanceMethodWithParameterRank(
            @NotNull ClassRegistry registry,
            @NotNull GdType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ToIntBiFunction<GdType, GdType> parameterCompatibilityRank
    ) {
        Objects.requireNonNull(parameterCompatibilityRank, "parameterCompatibilityRank");
        return resolveInstanceMethodWithParameterRank(
                registry,
                receiverType,
                methodName,
                argTypes,
                (_, sourceType, targetType) ->
                        parameterCompatibilityRank.applyAsInt(sourceType, targetType)
        );
    }

    /// Same as type-pair ranking, but the callback also receives the fixed-argument index so
    /// frontend callers can inject per-position ranks (e.g. container-literal element boundaries)
    /// without encoding AST inside the scope package.
    public static @NotNull Result resolveInstanceMethodWithParameterRank(
            @NotNull ClassRegistry registry,
            @NotNull GdType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank
    ) {
        return resolveInstanceMethodWithParameterRank(
                registry,
                receiverType,
                methodName,
                argTypes,
                parameterCompatibilityRank,
                null
        );
    }

    /// Index-aware applicability plus optional candidate-level specificity.
    ///
    /// `parameterCompatibilityRank` always gates fixed-parameter applicability. When
    /// `candidateSpecificity` is non-null, it replaces the default per-argument rank min/sum
    /// Pareto path for selecting among applicable overloads (frontend uses this to share
    /// `literalAggregateRank` with bare/constructor selection).
    public static @NotNull Result resolveInstanceMethodWithParameterRank(
            @NotNull ClassRegistry registry,
            @NotNull GdType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank,
            @Nullable CandidateSpecificity candidateSpecificity
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(argTypes, "argTypes");
        Objects.requireNonNull(parameterCompatibilityRank, "parameterCompatibilityRank");

        try {
            if (methodName.equals("_init")) {
                throw new ScopeMethodResolutionException(
                        FailureKind.CONSTRUCTOR_ROUTE_UNSUPPORTED,
                        "Constructor member '_init' must not be resolved through ordinary instance method lookup"
                );
            }
            if (receiverType instanceof GdObjectType objectType) {
                return resolveKnownObjectInstanceMethod(
                        registry,
                        objectType,
                        methodName,
                        argTypes,
                        parameterCompatibilityRank,
                        candidateSpecificity
                );
            }
            if (receiverType instanceof GdVariantType) {
                return new DynamicFallback(DynamicKind.VARIANT_DYNAMIC, DynamicFallbackReason.VARIANT_RECEIVER);
            }
            return resolveBuiltinInstanceMethod(
                    registry,
                    receiverType,
                    methodName,
                    argTypes,
                    parameterCompatibilityRank,
                    candidateSpecificity
            );
        } catch (ScopeMethodResolutionException ex) {
            return new Failed(ex.kind(), ex.getMessage());
        }
    }

    /// Resolve a static/type-meta method call.
    ///
    /// Constructor routes are explicitly excluded here and must be lowered through dedicated
    /// constructor resolution instead of pretending to be a normal method call.
    public static @NotNull Result resolveStaticMethod(@NotNull ClassRegistry registry,
                                                      @NotNull ScopeTypeMeta receiverTypeMeta,
                                                      @NotNull String methodName,
                                                      @NotNull List<GdType> argTypes) {
        return resolveStaticMethodWithParameterRank(
                registry,
                receiverTypeMeta,
                methodName,
                argTypes,
                (sourceType, targetType) -> registry.checkAssignable(sourceType, targetType) ? 1 : 0
        );
    }

    /// Shared static lookup keeps strict routing by default, but frontend chain analysis may supply
    /// a wider fixed-parameter compatibility rule for `Variant` boundaries only.
    public static @NotNull Result resolveStaticMethod(@NotNull ClassRegistry registry,
                                                      @NotNull ScopeTypeMeta receiverTypeMeta,
                                                      @NotNull String methodName,
                                                      @NotNull List<GdType> argTypes,
                                                      @NotNull BiPredicate<GdType, GdType> parameterCompatibility) {
        Objects.requireNonNull(parameterCompatibility, "parameterCompatibility");
        return resolveStaticMethodWithParameterRank(
                registry,
                receiverTypeMeta,
                methodName,
                argTypes,
                (sourceType, targetType) -> parameterCompatibility.test(sourceType, targetType) ? 1 : 0
        );
    }

    public static @NotNull Result resolveStaticMethodWithParameterRank(
            @NotNull ClassRegistry registry,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ToIntBiFunction<GdType, GdType> parameterCompatibilityRank
    ) {
        Objects.requireNonNull(parameterCompatibilityRank, "parameterCompatibilityRank");
        return resolveStaticMethodWithParameterRank(
                registry,
                receiverTypeMeta,
                methodName,
                argTypes,
                (_, sourceType, targetType) ->
                        parameterCompatibilityRank.applyAsInt(sourceType, targetType)
        );
    }

    /// Index-aware static method ranking counterpart of
    /// `resolveInstanceMethodWithParameterRank(ClassRegistry, GdType, String, List, ParameterCompatibilityRank)`.
    public static @NotNull Result resolveStaticMethodWithParameterRank(
            @NotNull ClassRegistry registry,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank
    ) {
        return resolveStaticMethodWithParameterRank(
                registry,
                receiverTypeMeta,
                methodName,
                argTypes,
                parameterCompatibilityRank,
                null
        );
    }

    /// Static counterpart with optional candidate-level specificity (see instance overload).
    public static @NotNull Result resolveStaticMethodWithParameterRank(
            @NotNull ClassRegistry registry,
            @NotNull ScopeTypeMeta receiverTypeMeta,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank,
            @Nullable CandidateSpecificity candidateSpecificity
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(receiverTypeMeta, "receiverTypeMeta");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(argTypes, "argTypes");
        Objects.requireNonNull(parameterCompatibilityRank, "parameterCompatibilityRank");

        try {
            if (methodName.equals("new")) {
                throw new ScopeMethodResolutionException(
                        FailureKind.CONSTRUCTOR_ROUTE_UNSUPPORTED,
                        "Constructor lookup for type '" + receiverTypeMeta.displayName() +
                                "' must use constructor resolution instead of static method lookup"
                );
            }
            var ownerClass = resolveStaticOwnerClass(registry, receiverTypeMeta);
            var candidates = collectMethodCandidates(registry, ownerClass, methodName).stream()
                    .filter(candidate -> candidate.resolved().isStatic())
                    .toList();
            if (candidates.isEmpty()) {
                throw new ScopeMethodResolutionException(
                        FailureKind.METHOD_NOT_FOUND,
                        "Static method '" + methodName + "' not found on type '" + receiverTypeMeta.displayName() + "'"
                );
            }
            var selection = chooseBestCandidate(
                    methodName,
                    receiverTypeMeta.displayName(),
                    candidates,
                    argTypes,
                    false,
                    parameterCompatibilityRank,
                    candidateSpecificity
            );
            return switch (selection) {
                case CandidateSelected selected -> new Resolved(selected.candidate().resolved());
                case CandidateAmbiguous ambiguous -> new Failed(
                        FailureKind.AMBIGUOUS_OVERLOAD,
                        "Ambiguous overload for method '" + methodName + "' on type '" +
                                receiverTypeMeta.displayName() + "': " + renderCandidates(ambiguous.pool())
                );
                case CandidateRejected rejected -> new Failed(FailureKind.NO_APPLICABLE_OVERLOAD, rejected.message());
            };
        } catch (ScopeMethodResolutionException ex) {
            return new Failed(ex.kind(), ex.getMessage());
        }
    }

    private static @NotNull Result resolveKnownObjectInstanceMethod(
            @NotNull ClassRegistry registry,
            @NotNull GdObjectType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank,
            @Nullable CandidateSpecificity candidateSpecificity
    ) {
        var classDef = registry.getClassDef(receiverType);
        if (classDef == null) {
            return new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.RECEIVER_METADATA_UNKNOWN);
        }

        var candidates = collectMethodCandidates(registry, classDef, methodName);
        if (candidates.isEmpty()) {
            return new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.METHOD_MISSING);
        }

        var selection = chooseBestCandidate(
                methodName,
                receiverType.getTypeName(),
                candidates,
                argTypes,
                true,
                parameterCompatibilityRank,
                candidateSpecificity
        );
        return switch (selection) {
            case CandidateSelected selected -> new Resolved(selected.candidate().resolved());
            case CandidateAmbiguous _ ->
                    new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.AMBIGUOUS_OVERLOAD);
            case CandidateRejected rejected -> new Failed(FailureKind.NO_APPLICABLE_OVERLOAD, rejected.message());
        };
    }

    /// Lexical-super resolution (Godot `super` semantics): walk from `startType` up the inheritance
    /// chain and stop at the FIRST class that declares `methodName` at all; argument applicability
    /// is then evaluated within that declaring owner only.
    ///
    /// This intentionally differs from [resolveInstanceMethodWithParameterRank]: the ordinary
    /// chain-wide lookup filters applicability BEFORE owner distance, which would skip an
    /// argument-incompatible nearer declaration and silently bind an argument-compatible farther
    /// one. `super` must instead bind the nearest declaring owner and report the mismatch there.
    ///
    /// Result contract mirrors the ordinary object path: an unregistered start class or a chain
    /// with no declaring owner yields `DynamicFallback`; a declaring owner whose candidates all
    /// reject the arguments yields `Failed(NO_APPLICABLE_OVERLOAD)` naming that owner.
    public static @NotNull Result resolveNearestDeclaredInstanceMethod(
            @NotNull ClassRegistry registry,
            @NotNull GdObjectType startType,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(startType, "startType");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(argTypes, "argTypes");
        Objects.requireNonNull(parameterCompatibilityRank, "parameterCompatibilityRank");

        try {
            if (methodName.equals("_init")) {
                throw new ScopeMethodResolutionException(
                        FailureKind.CONSTRUCTOR_ROUTE_UNSUPPORTED,
                        "Constructor member '_init' must not be resolved through ordinary instance method lookup"
                );
            }
            var startClass = registry.getClassDef(startType);
            if (startClass == null) {
                return new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.RECEIVER_METADATA_UNKNOWN);
            }
            ClassDef current = startClass;
            var visited = new HashSet<String>();
            while (current != null) {
                if (!visited.add(current.getName())) {
                    break;
                }
                var ownerKind = resolveOwnerKind(registry, current.getName());
                var declaredHere = new ArrayList<MethodCandidate>();
                for (var function : current.getFunctions()) {
                    if (!function.getName().equals(methodName)) {
                        continue;
                    }
                    declaredHere.add(new MethodCandidate(toResolvedMethod(registry, ownerKind, current, function, 0)));
                }
                if (!declaredHere.isEmpty()) {
                    var selection = chooseBestCandidate(
                            methodName,
                            current.getName(),
                            declaredHere,
                            argTypes,
                            true,
                            parameterCompatibilityRank,
                            null
                    );
                    return switch (selection) {
                        case CandidateSelected selected -> new Resolved(selected.candidate().resolved());
                        case CandidateAmbiguous _ ->
                                new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.AMBIGUOUS_OVERLOAD);
                        case CandidateRejected rejected ->
                                new Failed(FailureKind.NO_APPLICABLE_OVERLOAD, rejected.message());
                    };
                }
                var superCanonicalName = current.getSuperName();
                if (superCanonicalName.isBlank()) {
                    break;
                }
                current = registry.getClassDef(new GdObjectType(superCanonicalName));
            }
            return new DynamicFallback(DynamicKind.OBJECT_DYNAMIC, DynamicFallbackReason.METHOD_MISSING);
        } catch (ScopeMethodResolutionException ex) {
            return new Failed(ex.kind(), ex.getMessage());
        }
    }

    private static @NotNull Result resolveBuiltinInstanceMethod(
            @NotNull ClassRegistry registry,
            @NotNull GdType receiverType,
            @NotNull String methodName,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank,
            @Nullable CandidateSpecificity candidateSpecificity
    ) {
        var lookupName = normalizeBuiltinReceiverLookupName(receiverType);
        var builtinClass = registry.findBuiltinClass(lookupName);
        if (builtinClass == null) {
            return new Failed(
                    FailureKind.BUILTIN_CLASS_NOT_FOUND,
                    "Builtin class '" + receiverType.getTypeName() +
                            "' not found for call_method receiver (lookup key: '" + lookupName + "')"
            );
        }
        var candidates = collectMethodCandidates(registry, builtinClass, methodName);
        if (candidates.isEmpty()) {
            return new Failed(
                    FailureKind.METHOD_NOT_FOUND,
                    "Method '" + methodName + "' not found in builtin class '" + receiverType.getTypeName() + "'"
            );
        }
        var selection = chooseBestCandidate(
                methodName,
                receiverType.getTypeName(),
                candidates,
                argTypes,
                false,
                parameterCompatibilityRank,
                candidateSpecificity
        );
        return switch (selection) {
            case CandidateSelected selected -> new Resolved(selected.candidate().resolved());
            case CandidateAmbiguous ambiguous -> new Failed(
                    FailureKind.AMBIGUOUS_OVERLOAD,
                    "Ambiguous overload for method '" + methodName + "' on type '" +
                            receiverType.getTypeName() + "': " + renderCandidates(ambiguous.pool())
            );
            case CandidateRejected rejected -> new Failed(FailureKind.NO_APPLICABLE_OVERLOAD, rejected.message());
        };
    }

    private static @NotNull List<MethodCandidate> collectMethodCandidates(@NotNull ClassRegistry registry,
                                                                          @NotNull ClassDef startClass,
                                                                          @NotNull String methodName) {
        var out = new ArrayList<MethodCandidate>();
        ClassDef current = startClass;
        var visited = new HashSet<String>();
        var ownerDistance = 0;
        while (current != null) {
            if (!visited.add(current.getName())) {
                break;
            }
            var ownerKind = resolveOwnerKind(registry, current.getName());
            for (var function : current.getFunctions()) {
                if (!function.getName().equals(methodName)) {
                    continue;
                }
                out.add(new MethodCandidate(toResolvedMethod(registry, ownerKind, current, function, ownerDistance)));
            }
            var superCanonicalName = current.getSuperName();
            if (superCanonicalName.isBlank()) {
                break;
            }
            current = registry.getClassDef(new GdObjectType(superCanonicalName));
            ownerDistance++;
        }
        return out;
    }

    /// Candidate ranking is intentionally shared with the backend baseline:
    /// - only applicable signatures survive first
    /// - nearest owner distance wins
    /// - instance methods win over static ones for instance calls
    /// - non-vararg wins over vararg
    /// - among remaining candidates, either an injected [CandidateSpecificity] or the default
    ///   per-argument rank path picks a unique most-specific overload
    /// - if several equally-best object candidates remain, the caller may choose dynamic fallback
    private static @NotNull CandidateSelection chooseBestCandidate(
            @NotNull String methodName,
            @NotNull String receiverDisplayName,
            @NotNull List<MethodCandidate> candidates,
            @NotNull List<GdType> argTypes,
            boolean preferInstanceOverStatic,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank,
            @Nullable CandidateSpecificity candidateSpecificity
    ) {
        var applicable = new ArrayList<MethodCandidate>();
        for (var candidate : candidates) {
            if (matchesArguments(candidate.resolved(), argTypes, parameterCompatibilityRank)) {
                applicable.add(candidate);
            }
        }
        if (applicable.isEmpty()) {
            var preferred = choosePreferredCandidate(candidates, preferInstanceOverStatic);
            var mismatchReason = buildMismatchReason(preferred.resolved(), argTypes, parameterCompatibilityRank);
            return new CandidateRejected(
                    "No applicable overload for method '" + methodName + "' on type '" +
                            receiverDisplayName + "': " + mismatchReason + ". candidates: " +
                            renderCandidates(candidates)
            );
        }

        var minOwnerDistance = applicable.stream().mapToInt(candidate -> candidate.resolved().ownerDistance()).min().orElse(0);
        var ownerPool = applicable.stream()
                .filter(candidate -> candidate.resolved().ownerDistance() == minOwnerDistance)
                .toList();

        var pool = ownerPool;
        if (preferInstanceOverStatic) {
            var nonStaticPool = ownerPool.stream().filter(candidate -> !candidate.resolved().isStatic()).toList();
            pool = nonStaticPool.isEmpty() ? ownerPool : nonStaticPool;
        }

        var nonVarargPool = pool.stream().filter(candidate -> !candidate.resolved().isVararg()).toList();
        pool = nonVarargPool.isEmpty() ? pool : nonVarargPool;

        if (pool.size() == 1) {
            return new CandidateSelected(pool.getFirst());
        }
        var mostSpecific = selectMostSpecificCandidate(
                pool,
                argTypes,
                parameterCompatibilityRank,
                candidateSpecificity
        );
        return mostSpecific == null
                ? new CandidateAmbiguous(pool)
                : new CandidateSelected(mostSpecific);
    }

    private static @Nullable MethodCandidate selectMostSpecificCandidate(
            @NotNull List<MethodCandidate> pool,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank,
            @Nullable CandidateSpecificity candidateSpecificity
    ) {
        MethodCandidate selected = null;
        for (var candidate : pool) {
            var dominated = false;
            for (var other : pool) {
                if (candidate == other) {
                    continue;
                }
                var otherMoreSpecific = candidateSpecificity != null
                        ? candidateSpecificity.isStrictlyMoreSpecific(other.resolved(), candidate.resolved())
                        : isStrictlyMoreSpecific(
                        other.resolved(),
                        candidate.resolved(),
                        argTypes,
                        parameterCompatibilityRank
                );
                if (otherMoreSpecific) {
                    dominated = true;
                    break;
                }
            }
            if (dominated) {
                continue;
            }
            if (selected != null) {
                return null;
            }
            selected = candidate;
        }
        return selected;
    }

    /// Default specificity when no [CandidateSpecificity] is injected.
    ///
    /// Uses per-argument ranks from `ParameterCompatibilityRank`: higher min rank (worst) wins
    /// first, then higher sum (total), then classic Pareto. Sum uses `long` so an empty-literal
    /// encoded rank of `Integer.MAX_VALUE` cannot wrap when mixed with additional fixed arguments.
    /// Frontend chain paths inject `CandidateSpecificity` instead so they share bare/ctor
    /// `literalAggregateRank` / `compareCandidateRanks` (§5.4).
    private static boolean isStrictlyMoreSpecific(
            @NotNull ScopeResolvedMethod candidate,
            @NotNull ScopeResolvedMethod baseline,
            @NotNull List<GdType> argTypes,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank
    ) {
        var candidateMin = Integer.MAX_VALUE;
        var baselineMin = Integer.MAX_VALUE;
        long candidateSum = 0L;
        long baselineSum = 0L;
        var strictlyBetter = false;
        var strictlyWorse = false;
        for (var index = 0; index < argTypes.size(); index++) {
            var candidateTarget = parameterTypeAt(candidate, index);
            var baselineTarget = parameterTypeAt(baseline, index);
            var preference = compareArgumentSpecificity(
                    index,
                    argTypes.get(index),
                    candidateTarget,
                    baselineTarget,
                    parameterCompatibilityRank
            );
            var candidateRank = rankAt(index, argTypes.get(index), candidateTarget, parameterCompatibilityRank);
            var baselineRank = rankAt(index, argTypes.get(index), baselineTarget, parameterCompatibilityRank);
            candidateMin = Math.min(candidateMin, candidateRank);
            baselineMin = Math.min(baselineMin, baselineRank);
            candidateSum += candidateRank;
            baselineSum += baselineRank;
            switch (preference) {
                case WORSE -> strictlyWorse = true;
                case BETTER -> strictlyBetter = true;
                case EQUAL -> {
                }
            }
        }
        if (candidateMin != baselineMin) {
            return candidateMin > baselineMin;
        }
        if (candidateSum != baselineSum) {
            return candidateSum > baselineSum;
        }
        return strictlyBetter && !strictlyWorse;
    }

    private static int rankAt(
            int argumentIndex,
            @NotNull GdType sourceType,
            @Nullable GdType targetType,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank
    ) {
        if (targetType == null) {
            return 0;
        }
        return parameterCompatibilityRank.rank(argumentIndex, sourceType, targetType);
    }

    private static @NotNull MatchPreference compareArgumentSpecificity(
            int argumentIndex,
            @NotNull GdType sourceType,
            @Nullable GdType candidateTarget,
            @Nullable GdType baselineTarget,
            @NotNull ParameterCompatibilityRank parameterCompatibilityRank
    ) {
        if (candidateTarget == null && baselineTarget == null) {
            return MatchPreference.EQUAL;
        }
        if (candidateTarget == null) {
            return MatchPreference.WORSE;
        }
        if (baselineTarget == null) {
            return MatchPreference.BETTER;
        }
        var candidateRank = parameterCompatibilityRank.rank(argumentIndex, sourceType, candidateTarget);
        var baselineRank = parameterCompatibilityRank.rank(argumentIndex, sourceType, baselineTarget);
        if (candidateRank > baselineRank) {
            return MatchPreference.BETTER;
        }
        if (candidateRank < baselineRank) {
            return MatchPreference.WORSE;
        }
        return MatchPreference.EQUAL;
    }

    private static @NotNull MethodCandidate choosePreferredCandidate(@NotNull List<MethodCandidate> candidates,
                                                                     boolean preferInstanceOverStatic) {
        var minOwnerDistance = candidates.stream().mapToInt(candidate -> candidate.resolved().ownerDistance()).min().orElse(0);
        var ownerPool = candidates.stream()
                .filter(candidate -> candidate.resolved().ownerDistance() == minOwnerDistance)
                .toList();

        var pool = ownerPool;
        if (preferInstanceOverStatic) {
            var nonStaticPool = ownerPool.stream().filter(candidate -> !candidate.resolved().isStatic()).toList();
            pool = nonStaticPool.isEmpty() ? ownerPool : nonStaticPool;
        }

        var nonVarargPool = pool.stream().filter(candidate -> !candidate.resolved().isVararg()).toList();
        pool = nonVarargPool.isEmpty() ? pool : nonVarargPool;
        return pool.getFirst();
    }

    private static @NotNull String buildMismatchReason(@NotNull ScopeResolvedMethod candidate,
                                                       @NotNull List<GdType> argTypes,
                                                       @NotNull ParameterCompatibilityRank parameterCompatibilityRank) {
        var fixedCount = candidate.parameters().size();
        var providedCount = argTypes.size();
        if (providedCount < fixedCount && !canOmitTrailingParameters(candidate.parameters(), providedCount)) {
            var missingParam = firstMissingRequiredParameter(candidate.parameters(), providedCount);
            return "Too few arguments for method '" + candidate.ownerClass().getName() + "." + candidate.methodName() +
                    "': missing required parameter #" + (missingParam + 1) + " ('" +
                    candidate.parameters().get(missingParam).name() + "')";
        }
        if (!candidate.isVararg() && providedCount > fixedCount) {
            return "Too many arguments for method '" + candidate.ownerClass().getName() + "." + candidate.methodName() +
                    "': expected " + fixedCount + ", got " + providedCount;
        }
        var providedFixedCount = Math.min(providedCount, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argType = argTypes.get(i);
            var param = candidate.parameters().get(i);
            if (parameterCompatibilityRank.rank(i, argType, param.type()) <= 0) {
                return "Cannot assign value of type '" + argType.getTypeName() +
                        "' to method parameter #" + (i + 1) + " ('" + param.name() +
                        "') of type '" + param.type().getTypeName() + "'";
            }
        }
        return "no compatible signature found";
    }

    private static @NotNull String renderCandidates(@NotNull List<MethodCandidate> candidates) {
        var joiner = new StringJoiner("; ");
        for (var candidate : candidates) {
            var resolved = candidate.resolved();
            joiner.add(renderSignature(resolved) + " [distance=" + resolved.ownerDistance() + "]");
        }
        return joiner.toString();
    }

    private static @NotNull String renderSignature(@NotNull ScopeResolvedMethod resolved) {
        var argsJoiner = new StringJoiner(", ");
        for (var parameter : resolved.parameters()) {
            argsJoiner.add(parameter.type().getTypeName());
        }
        if (resolved.isVararg()) {
            argsJoiner.add("...");
        }
        var staticPrefix = resolved.isStatic() ? "static " : "";
        return staticPrefix + resolved.ownerClass().getName() + "." + resolved.methodName() + "(" + argsJoiner + ")";
    }

    private static boolean matchesArguments(@NotNull ScopeResolvedMethod candidate,
                                            @NotNull List<GdType> argTypes,
                                            @NotNull ParameterCompatibilityRank parameterCompatibilityRank) {
        var fixedCount = candidate.parameters().size();
        var provided = argTypes.size();
        if (provided < fixedCount && !canOmitTrailingParameters(candidate.parameters(), provided)) {
            return false;
        }
        if (!candidate.isVararg() && provided > fixedCount) {
            return false;
        }
        var providedFixedCount = Math.min(provided, fixedCount);
        for (var i = 0; i < providedFixedCount; i++) {
            var argType = argTypes.get(i);
            var paramType = candidate.parameters().get(i).type();
            if (parameterCompatibilityRank.rank(i, argType, paramType) <= 0) {
                return false;
            }
        }
        if (!candidate.isVararg()) {
            return true;
        }
        // Godot varargs are carried through Variant packing, so typed callers should not be forced
        // to prove a strict `T -> Variant` conversion for the tail.
        return true;
    }

    private static @Nullable GdType parameterTypeAt(@NotNull ScopeResolvedMethod candidate, int index) {
        if (index < candidate.parameters().size()) {
            return candidate.parameters().get(index).type();
        }
        return null;
    }

    private static boolean canOmitTrailingParameters(@NotNull List<ScopeMethodParameter> parameters,
                                                     int providedCount) {
        for (var i = providedCount; i < parameters.size(); i++) {
            if (!parameters.get(i).hasDefaultValue()) {
                return false;
            }
        }
        return true;
    }

    private static int firstMissingRequiredParameter(@NotNull List<ScopeMethodParameter> parameters,
                                                     int providedCount) {
        for (var i = providedCount; i < parameters.size(); i++) {
            if (!parameters.get(i).hasDefaultValue()) {
                return i;
            }
        }
        return providedCount;
    }

    private static @NotNull ScopeOwnerKind resolveOwnerKind(@NotNull ClassRegistry registry,
                                                            @NotNull String ownerClassName) {
        if (registry.isGdccClass(ownerClassName)) {
            return ScopeOwnerKind.GDCC;
        }
        if (registry.isGdClass(ownerClassName)) {
            return ScopeOwnerKind.ENGINE;
        }
        if (registry.isBuiltinClass(ownerClassName)) {
            return ScopeOwnerKind.BUILTIN;
        }
        throw new ScopeMethodResolutionException(
                FailureKind.UNSUPPORTED_OWNER,
                "Unsupported method owner '" + ownerClassName + "': unable to classify as GDCC/ENGINE/BUILTIN"
        );
    }

    private static @NotNull String normalizeBuiltinReceiverLookupName(@NotNull GdType receiverType) {
        return switch (receiverType) {
            case GdArrayType _ -> "Array";
            case GdDictionaryType _ -> "Dictionary";
            default -> receiverType.getTypeName();
        };
    }

    private static @NotNull ScopeResolvedMethod toResolvedMethod(@NotNull ClassRegistry registry,
                                                                 @NotNull ScopeOwnerKind ownerKind,
                                                                 @NotNull ClassDef ownerClass,
                                                                 @NotNull FunctionDef function,
                                                                 int ownerDistance) {
        var params = new ArrayList<ScopeMethodParameter>();
        var ownerType = resolveOwnerType(ownerKind, ownerClass.getName());
        var startParamIndex = ownerKind == ScopeOwnerKind.GDCC && !function.isStatic()
                ? resolveGdccInstanceParameterStartIndex(ownerClass, function, ownerType)
                : 0;
        for (var i = startParamIndex; i < function.getParameterCount(); i++) {
            var parameter = function.getParameter(i);
            if (parameter == null) {
                throw new ScopeMethodResolutionException(
                        FailureKind.MALFORMED_METADATA,
                        "Method parameter #" + (i + 1) + " metadata is missing for '" +
                                ownerClass.getName() + "." + function.getName() + "'"
                );
            }
            var parameterType = resolveMethodParameterType(registry, ownerClass, function, parameter, i + 1);
            var defaultKind = resolveDefaultArgKind(parameter);
            var defaultLiteral = defaultKind == ScopeDefaultArgKind.LITERAL && parameter instanceof ExtensionFunctionArgument extensionArgument
                    ? extensionArgument.defaultValue()
                    : null;
            var defaultFunctionName = defaultKind == ScopeDefaultArgKind.FUNCTION
                    ? parameter.getDefaultValueFunc()
                    : null;
            params.add(new ScopeMethodParameter(
                    parameter.getName(),
                    parameterType,
                    defaultKind,
                    defaultLiteral,
                    defaultFunctionName
            ));
        }
        var returnType = resolveMethodReturnType(registry, ownerClass, function);
        return new ScopeResolvedMethod(
                ownerKind,
                ownerClass,
                function,
                ownerType,
                returnType,
                params,
                ownerDistance
        );
    }

    /// GDCC currently has two stable metadata layouts for instance methods:
    /// - lowered/shared LIR may materialize a synthetic leading `self` parameter
    /// - frontend class skeleton publishes only user-visible parameters
    /// Shared method lookup accepts both so frontend semantic phases can reuse the resolver without
    /// forcing skeleton metadata to mimic backend lowering shape.
    private static int resolveGdccInstanceParameterStartIndex(@NotNull ClassDef ownerClass,
                                                              @NotNull FunctionDef function,
                                                              @NotNull GdType ownerType) {
        var firstParam = function.getParameter(0);
        if (firstParam == null) {
            return 0;
        }
        if (!firstParam.getName().equals("self")) {
            return 0;
        }
        if (!firstParam.getType().getTypeName().equals(ownerType.getTypeName())) {
            throw new ScopeMethodResolutionException(
                    FailureKind.MALFORMED_METADATA,
                    "GDCC method '" + ownerClass.getName() + "." + function.getName()
                            + "' has synthetic self parameter of type '" + firstParam.getType().getTypeName()
                            + "', expected '" + ownerType.getTypeName() + "'"
            );
        }
        return 1;
    }

    private static @NotNull ScopeDefaultArgKind resolveDefaultArgKind(@NotNull ParameterDef parameter) {
        if (parameter instanceof ExtensionFunctionArgument extensionArgument &&
                extensionArgument.defaultValue() != null && !extensionArgument.defaultValue().isBlank()) {
            return ScopeDefaultArgKind.LITERAL;
        }
        var defaultValueFunc = parameter.getDefaultValueFunc();
        if (defaultValueFunc != null && !defaultValueFunc.isBlank()) {
            return ScopeDefaultArgKind.FUNCTION;
        }
        return ScopeDefaultArgKind.NONE;
    }

    private static @NotNull GdType resolveMethodParameterType(@NotNull ClassRegistry registry,
                                                              @NotNull ClassDef ownerClass,
                                                              @NotNull FunctionDef function,
                                                              @NotNull ParameterDef parameter,
                                                              int parameterIndexBaseOne) {
        if (parameter instanceof ExtensionFunctionArgument extensionArgument) {
            return parseExtensionTypeWithScopeHelper(
                    registry,
                    extensionArgument.type(),
                    "method parameter #" + parameterIndexBaseOne + " of '" +
                            ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        return parameter.getType();
    }

    private static @NotNull GdType resolveMethodReturnType(@NotNull ClassRegistry registry,
                                                           @NotNull ClassDef ownerClass,
                                                           @NotNull FunctionDef function) {
        if (function instanceof ExtensionBuiltinClass.ClassMethod builtinMethod) {
            var rawReturnType = builtinMethod.returnValue() != null
                    ? builtinMethod.returnValue().type()
                    : builtinMethod.returnType();
            return parseExtensionTypeWithScopeHelper(
                    registry,
                    rawReturnType,
                    "return type of '" + ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        if (function instanceof ExtensionGdClass.ClassMethod gdMethod) {
            var returnInfo = gdMethod.returnValue();
            var rawReturnType = returnInfo == null ? "void" : returnInfo.type();
            return parseExtensionTypeWithScopeHelper(
                    registry,
                    rawReturnType,
                    "return type of '" + ownerClass.getName() + "." + function.getName() + "'"
            );
        }
        return function.getReturnType();
    }

    private static @NotNull GdType parseExtensionTypeWithScopeHelper(@NotNull ClassRegistry registry,
                                                                     @Nullable String rawTypeName,
                                                                     @NotNull String typeUseSite) {
        try {
            return ScopeTypeParsers.parseExtensionTypeMetadata(rawTypeName, typeUseSite, registry);
        } catch (IllegalArgumentException ex) {
            throw new ScopeMethodResolutionException(FailureKind.MALFORMED_METADATA, ex.getMessage());
        }
    }

    private static @NotNull GdType resolveOwnerType(@NotNull ScopeOwnerKind ownerKind,
                                                    @NotNull String ownerClassName) {
        return switch (ownerKind) {
            case GDCC, ENGINE -> new GdObjectType(ownerClassName);
            case BUILTIN -> {
                var parsed = ClassRegistry.tryParseStrictTextType(ownerClassName, null);
                if (parsed == null) {
                    throw new ScopeMethodResolutionException(
                            FailureKind.MALFORMED_METADATA,
                            "Unsupported builtin owner type '" + ownerClassName + "'"
                    );
                }
                yield parsed;
            }
        };
    }

    private static @NotNull ClassDef resolveStaticOwnerClass(@NotNull ClassRegistry registry,
                                                             @NotNull ScopeTypeMeta receiverTypeMeta) {
        if (receiverTypeMeta.pseudoType() || receiverTypeMeta.kind() == ScopeTypeMetaKind.GLOBAL_ENUM) {
            throw new ScopeMethodResolutionException(
                    FailureKind.UNSUPPORTED_STATIC_RECEIVER,
                    "Type meta '" + receiverTypeMeta.displayName() + "' does not support static method lookup"
            );
        }
        if (receiverTypeMeta.declaration() instanceof ClassDef classDef) {
            return classDef;
        }
        return switch (receiverTypeMeta.kind()) {
            case BUILTIN -> {
                var lookupName = normalizeBuiltinReceiverLookupName(receiverTypeMeta.instanceType());
                var builtinClass = registry.findBuiltinClass(lookupName);
                if (builtinClass == null) {
                    throw new ScopeMethodResolutionException(
                            FailureKind.BUILTIN_CLASS_NOT_FOUND,
                            "Builtin class '" + receiverTypeMeta.displayName() +
                                    "' not found for static method receiver (lookup key: '" + lookupName + "')"
                    );
                }
                yield builtinClass;
            }
            case ENGINE_CLASS, GDCC_CLASS -> {
                if (receiverTypeMeta.instanceType() instanceof GdObjectType objectType) {
                    var classDef = registry.getClassDef(objectType);
                    if (classDef != null) {
                        yield classDef;
                    }
                }
                throw new ScopeMethodResolutionException(
                        FailureKind.UNSUPPORTED_STATIC_RECEIVER,
                        "Class metadata for static receiver '" + receiverTypeMeta.displayName() + "' is unavailable"
                );
            }
            case GLOBAL_ENUM -> throw new ScopeMethodResolutionException(
                    FailureKind.UNSUPPORTED_STATIC_RECEIVER,
                    "Type meta '" + receiverTypeMeta.displayName() + "' does not support static method lookup"
            );
        };
    }
}
