package gd.script.gdcc.frontend.sema;

/// Route kind published for one call-resolution step.
public enum FrontendCallResolutionKind {
    /// The analyzer does not yet have a stable call-route classification.
    ///
    /// This is mainly a placeholder for partially built or non-success results whose route could not
    /// be frozen yet. Stable successful calls should not remain in this state.
    UNKNOWN,
    /// The call is being treated as an ordinary instance-style method route.
    ///
    /// This covers receiver-known calls such as `player.move()` and builtin/object instance method
    /// lookup. It does not imply success on its own; the paired status still decides whether the
    /// route resolved, deferred, failed, and so on.
    INSTANCE_METHOD,
    /// The call is being treated as a static/type-meta method route.
    ///
    /// This is used for class-like receivers such as `ClassName.build()`, and also for cases where
    /// the final chosen callable is static even if source syntax used an instance receiver and later
    /// diagnostics need to explain that mismatch.
    STATIC_METHOD,
    /// The call is on a constructor-specific route rather than ordinary method lookup.
    ///
    /// Keeping constructor calls separate prevents the analyzer from smuggling `ClassName.new(...)`
    /// or builtin/object constructors through shared ordinary method resolution.
    CONSTRUCTOR,
    /// The published route is a runtime-dynamic fallback rather than an exact callable.
    ///
    /// This kind is only valid together with `FrontendCallResolutionStatus.DYNAMIC`.
    DYNAMIC_FALLBACK,
    /// The call is a `super` route resolved from the lexical superclass chain of the enclosing class.
    ///
    /// Super semantics fix the target to the nearest ancestor implementation and bypass virtual
    /// dispatch, so this kind is never merged into `INSTANCE_METHOD` even though the receiver value
    /// is the ordinary `self` instance.
    SUPER_METHOD
}
