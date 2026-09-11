package gd.script.gdcc.frontend.sema;

/// Symbol binding categories resolved during frontend semantic analysis.
public enum FrontendBindingKind {
    /// Special binding published for the `self` expression.
    SELF,
    /// Literal expression node such as `0`, `"text"`, or `true`.
    LITERAL,
    LOCAL_VAR,
    PARAMETER,
    CAPTURE,
    PROPERTY,
    /// A frontend binding that resolved to a signal value/member.
    ///
    /// Signals are intentionally kept separate from properties and functions so later semantic
    /// stages can preserve Godot-style signal behavior without guessing from declaration shape.
    SIGNAL,
    /// Bare callee binding that resolved to a global utility function.
    UTILITY_FUNCTION,
    /// Bare callee binding that resolved to an instance-method overload set.
    METHOD,
    /// Bare callee binding that resolved to a static-method overload set.
    STATIC_METHOD,
    /// The `super` keyword used as a chain head (`super.foo(...)`) or bare callee (`super(...)`).
    ///
    /// This is a keyword position marker only: it never carries a resolvable value payload, and
    /// value-position uses (`var x = super`) are rejected by the compile-check position gate.
    SUPER,
    CONSTANT,
    SINGLETON,
    GLOBAL_ENUM,
    TYPE_META,
    UNKNOWN
}
