package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ScopeOwnerKind;
import gd.script.gdcc.scope.resolver.ScopeMethodResolver;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/// Shared `super` call-resolution rules for both syntactic forms (`super.m(...)` chain head and
/// bare `super(...)`).
///
/// Godot semantics (gdscript_analyzer.cpp `reduce_call` / `get_function_signature`): resolution
/// starts at the *lexical* superclass of the enclosing class — never at the receiver's dynamic
/// type — walks up the chain, and stops at the FIRST owner declaring the method name; argument
/// matching then runs within that owner only. This bypasses virtual dispatch and never skips an
/// argument-incompatible nearer declaration for an argument-compatible farther one. This helper
/// owns exactly that start-point computation plus the fail-closed target checks; callers stay
/// responsible for argument typing, fact publication, and diagnostics anchoring.
///
/// Fail-closed rules (aligned with the backend `CALL_SUPER_METHOD` contract,
/// `virtual_override_vtable_implementation.md` §2.5):
/// - the enclosing class must have a superclass
/// - `_init` is rejected: GDCC constructors chain the parent `_init` automatically
///   (`entry.c.ftl` `class_constructor`), so an explicit super constructor call would double-run it
/// - the target must resolve statically (no dynamic fallback)
/// - static targets are rejected (super is instance-only semantics)
/// - abstract targets without an implementation are rejected
public final class FrontendSuperCallSupport {
    /// Outcome of one super-call resolution: exactly one of the two components is non-null.
    public record SuperMethodResolution(
            @Nullable ScopeResolvedMethod method,
            @Nullable String detailReason
    ) {
        public SuperMethodResolution {
            if ((method == null) == (detailReason == null)) {
                throw new IllegalArgumentException("exactly one of method/detailReason must be present");
            }
        }

        public static @NotNull SuperMethodResolution resolved(@NotNull ScopeResolvedMethod method) {
            return new SuperMethodResolution(Objects.requireNonNull(method, "method must not be null"), null);
        }

        public static @NotNull SuperMethodResolution failed(@NotNull String detailReason) {
            return new SuperMethodResolution(null, StringUtil.requireNonBlank(detailReason, "detailReason"));
        }

        public boolean isResolved() {
            return method != null;
        }
    }

    private FrontendSuperCallSupport() {
    }

    /// Resolves `methodName` starting from the lexical superclass of `currentClassType`.
    ///
    /// Uses the lexical-super entry [ScopeMethodResolver#resolveNearestDeclaredInstanceMethod]:
    /// the lookup stops at the FIRST owner declaring the name (Godot semantics), and argument
    /// matching runs only within that owner — an argument-incompatible nearer declaration fails
    /// instead of being skipped for an argument-compatible farther one.
    /// `parameterCompatibilityRank` is injected by the caller so chain and bare-call sites keep
    /// their existing literal-aware applicability rules.
    public static @NotNull SuperMethodResolution resolveSuperInstanceMethod(
            @NotNull ClassRegistry registry,
            @NotNull GdType currentClassType,
            @NotNull String methodName,
            @NotNull List<GdType> argumentTypes,
            @NotNull ScopeMethodResolver.ParameterCompatibilityRank parameterCompatibilityRank
    ) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(currentClassType, "currentClassType must not be null");
        Objects.requireNonNull(methodName, "methodName must not be null");
        Objects.requireNonNull(argumentTypes, "argumentTypes must not be null");
        Objects.requireNonNull(parameterCompatibilityRank, "parameterCompatibilityRank must not be null");
        if (!(currentClassType instanceof GdObjectType currentObjectType)) {
            return SuperMethodResolution.failed(
                    "Super call requires an object-typed enclosing class, but got "
                            + currentClassType.getTypeName()
            );
        }
        var currentClassDef = registry.getClassDef(currentObjectType);
        if (currentClassDef == null) {
            return SuperMethodResolution.failed(
                    "Enclosing class '" + currentObjectType.getTypeName() + "' is not registered; "
                            + "cannot resolve the super call target"
            );
        }
        var superName = currentClassDef.getSuperName();
        if (superName.isBlank()) {
            return SuperMethodResolution.failed(
                    "Class '" + currentClassDef.getName() + "' has no superclass; 'super' call has no target"
            );
        }
        if (methodName.equals("_init")) {
            return SuperMethodResolution.failed(
                    "Explicit super constructor call is not supported: GDCC chains the parent '_init' "
                            + "automatically before the current class body"
            );
        }
        var result = ScopeMethodResolver.resolveNearestDeclaredInstanceMethod(
                registry,
                new GdObjectType(superName),
                methodName,
                argumentTypes,
                parameterCompatibilityRank
        );
        return switch (result) {
            case ScopeMethodResolver.Resolved resolved -> {
                var method = resolved.method();
                if (method.isStatic()) {
                    yield SuperMethodResolution.failed(
                            "Super call target '" + methodName + "' resolved to static method '"
                                    + method.ownerClass().getName() + "." + methodName
                                    + "'; super is instance-only semantics"
                    );
                }
                // Only GDCC abstract methods are implementation-less; engine "abstract" entries are
                // virtual hooks whose default no-op implementation remains callable through super.
                if (method.ownerKind() == ScopeOwnerKind.GDCC && method.function().isAbstract()) {
                    yield SuperMethodResolution.failed(
                            "Super call target '" + method.ownerClass().getName() + "." + methodName
                                    + "' is abstract and has no implementation to invoke"
                    );
                }
                yield SuperMethodResolution.resolved(method);
            }
            case ScopeMethodResolver.DynamicFallback fallback -> SuperMethodResolution.failed(
                    switch (fallback.reason()) {
                        case METHOD_MISSING -> "Super method '" + methodName
                                + "' was not found on the superclass chain of '" + currentClassDef.getName() + "'";
                        case RECEIVER_METADATA_UNKNOWN -> "Superclass of '" + currentClassDef.getName()
                                + "' is not registered; cannot resolve super call '" + methodName + "'";
                        default -> "Super call target '" + methodName
                                + "' must resolve statically, but lookup on the superclass chain of '"
                                + currentClassDef.getName() + "' chose dynamic fallback: " + fallback.reason();
                    }
            );
            case ScopeMethodResolver.Failed failed -> SuperMethodResolution.failed(
                    "Super method lookup for '" + methodName + "' on the superclass chain of '"
                            + currentClassDef.getName() + "' failed: " + failed.message()
            );
        };
    }
}
