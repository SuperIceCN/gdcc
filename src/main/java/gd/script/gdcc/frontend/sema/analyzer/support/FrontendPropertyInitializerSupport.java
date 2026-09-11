package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.scope.ClassScope;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendBindingKind;
import gd.script.gdcc.scope.ClassDef;
import gd.script.gdcc.scope.FunctionDef;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeTypeMeta;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.scope.resolver.ScopeResolvedMethod;
import gd.script.gdcc.scope.resolver.ScopeResolvedProperty;
import gd.script.gdcc.scope.resolver.ScopeResolvedSignal;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdType;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Freezes the minimal body-phase support island for class property initializer subtrees.
///
/// Current contract supports only:
/// - `VariableDeclaration(kind == VAR && value != null)`
/// - whose declaration scope is a `ClassScope`
///
/// This keeps property initializer publication explicit without widening the whole class body into
/// an executable region.
public final class FrontendPropertyInitializerSupport {
    /// The property declaration plus the exact class scope whose current-instance hierarchy becomes
    /// the MVP boundary for this initializer.
    public record PropertyInitializerContext(
            @NotNull VariableDeclaration declaration,
            @NotNull ClassScope declaringClassScope
    ) {
        public PropertyInitializerContext {
            Objects.requireNonNull(declaration, "declaration must not be null");
            Objects.requireNonNull(declaringClassScope, "declaringClassScope must not be null");
        }

        public @NotNull ClassDef declaringClass() {
            return declaringClassScope.getCurrentClass();
        }
    }

    private FrontendPropertyInitializerSupport() {
    }

    public static boolean isSupportedPropertyInitializer(
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");
        if (variableDeclaration.kind() != DeclarationKind.VAR || variableDeclaration.value() == null) {
            return false;
        }
        return scopesByAst.get(variableDeclaration) instanceof ClassScope;
    }

    public static @NotNull ResolveRestriction restrictionFor(@NotNull VariableDeclaration variableDeclaration) {
        return Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null").isStatic()
                ? ResolveRestriction.staticContext()
                : ResolveRestriction.instanceContext();
    }

    public static @Nullable PropertyInitializerContext contextOrNull(
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        Objects.requireNonNull(variableDeclaration, "variableDeclaration must not be null");
        if (!isSupportedPropertyInitializer(scopesByAst, variableDeclaration)) {
            return null;
        }
        return new PropertyInitializerContext(
                variableDeclaration,
                (ClassScope) Objects.requireNonNull(scopesByAst.get(variableDeclaration), "classScope must not be null")
        );
    }

    public static @NotNull PropertyInitializerContext contextFor(
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull VariableDeclaration variableDeclaration
    ) {
        var context = contextOrNull(scopesByAst, variableDeclaration);
        if (context == null) {
            throw new IllegalArgumentException("variableDeclaration is not a supported property initializer");
        }
        return context;
    }

    public static boolean isInsidePropertyInitializer(@Nullable PropertyInitializerContext context) {
        return context != null;
    }

    /// Reuses the published binding kind to decide whether this use site should be fail-closed by
    /// the property-initializer MVP boundary before downstream phases try to consume it as a normal
    /// value/callable receiver.
    public static @Nullable String detailForBindingBoundary(
            @Nullable PropertyInitializerContext context,
            @NotNull FrontendBindingKind bindingKind,
            @NotNull String symbolName
    ) {
        Objects.requireNonNull(bindingKind, "bindingKind must not be null");
        Objects.requireNonNull(symbolName, "symbolName must not be null");
        if (context == null) {
            return null;
        }
        return switch (bindingKind) {
            case SELF -> selfBoundaryDetail();
            case SUPER -> superBoundaryDetail();
            case PROPERTY -> currentInstanceHierarchyNonStaticValueKind(context, symbolName) == ScopeValueKind.PROPERTY
                    ? currentInstanceHierarchyNonStaticMemberDetail("property", symbolName)
                    : null;
            case SIGNAL -> currentInstanceHierarchyNonStaticValueKind(context, symbolName) == ScopeValueKind.SIGNAL
                    ? currentInstanceHierarchyNonStaticMemberDetail("signal", symbolName)
                    : null;
            case METHOD, STATIC_METHOD -> hasCurrentInstanceHierarchyNonStaticFunction(context, symbolName)
                    ? currentInstanceHierarchyNonStaticMemberDetail("method", symbolName)
                    : null;
            case LITERAL, LOCAL_VAR, PARAMETER, CAPTURE, UTILITY_FUNCTION, CONSTANT, SINGLETON,
                 GLOBAL_ENUM, TYPE_META, UNKNOWN -> null;
        };
    }

    public static @Nullable String detailForValueBoundary(
            @Nullable PropertyInitializerContext context,
            @NotNull ScopeValue value
    ) {
        Objects.requireNonNull(value, "value must not be null");
        if (context == null || value.staticMember()) {
            return null;
        }
        return switch (value.kind()) {
            case PROPERTY ->
                    currentInstanceHierarchyNonStaticValueKind(context, value.name()) == ScopeValueKind.PROPERTY
                            ? currentInstanceHierarchyNonStaticMemberDetail("property", value.name())
                            : null;
            case SIGNAL -> currentInstanceHierarchyNonStaticValueKind(context, value.name()) == ScopeValueKind.SIGNAL
                    ? currentInstanceHierarchyNonStaticMemberDetail("signal", value.name())
                    : null;
            case LOCAL, PARAMETER, CAPTURE, CONSTANT, SINGLETON, GLOBAL_ENUM, TYPE_META -> null;
        };
    }

    public static @Nullable String detailForCallableBoundary(
            @Nullable PropertyInitializerContext context,
            @NotNull String callableName
    ) {
        Objects.requireNonNull(callableName, "callableName must not be null");
        return context != null && hasCurrentInstanceHierarchyNonStaticFunction(context, callableName)
                ? currentInstanceHierarchyNonStaticMemberDetail("method", callableName)
                : null;
    }

    public static @Nullable String detailForResolvedPropertyBoundary(
            @Nullable PropertyInitializerContext context,
            @NotNull ScopeResolvedProperty property
    ) {
        Objects.requireNonNull(property, "property must not be null");
        return context != null
                && isCurrentInstanceHierarchyClass(context, property.ownerClass())
                && !property.property().isStatic()
                ? currentInstanceHierarchyNonStaticMemberDetail("property", property.property().getName())
                : null;
    }

    public static @Nullable String detailForResolvedSignalBoundary(
            @Nullable PropertyInitializerContext context,
            @NotNull ScopeResolvedSignal signal
    ) {
        Objects.requireNonNull(signal, "signal must not be null");
        return context != null && isCurrentInstanceHierarchyClass(context, signal.ownerClass())
                ? currentInstanceHierarchyNonStaticMemberDetail("signal", signal.signal().getName())
                : null;
    }

    public static @Nullable String detailForResolvedMethodBoundary(
            @Nullable PropertyInitializerContext context,
            @NotNull ScopeResolvedMethod method
    ) {
        Objects.requireNonNull(method, "method must not be null");
        return context != null
                && isCurrentInstanceHierarchyClass(context, method.ownerClass())
                && !method.isStatic()
                ? currentInstanceHierarchyNonStaticMemberDetail("method", method.methodName())
                : null;
    }

    public static @Nullable String detailForMethodReferenceBoundary(
            @Nullable PropertyInitializerContext context,
            @NotNull ClassDef ownerClass,
            @NotNull FunctionDef function
    ) {
        Objects.requireNonNull(ownerClass, "ownerClass must not be null");
        Objects.requireNonNull(function, "function must not be null");
        return context != null && isCurrentInstanceHierarchyClass(context, ownerClass) && !function.isStatic()
                ? currentInstanceHierarchyNonStaticMemberDetail("method", function.getName())
                : null;
    }

    public static @NotNull String selfBoundaryDetail() {
        return "Property initializer MVP does not support accessing 'self' or other current-instance-hierarchy non-static members";
    }

    public static @NotNull String superBoundaryDetail() {
        return "Property initializer MVP does not support 'super' calls "
                + "(current-instance-hierarchy non-static member access)";
    }

    public static @NotNull String unsupportedSelfMessage() {
        return selfBoundaryDetail();
    }

    public static @NotNull String currentInstanceHierarchyNonStaticMemberDetail(
            @NotNull String memberKind,
            @NotNull String memberName
    ) {
        return "Property initializer MVP does not support accessing current-instance-hierarchy non-static "
                + Objects.requireNonNull(memberKind, "memberKind must not be null")
                + " '" + Objects.requireNonNull(memberName, "memberName must not be null") + "'";
    }

    public static @Nullable ScopeValueKind currentInstanceHierarchyNonStaticValueKind(
            @Nullable PropertyInitializerContext context,
            @NotNull String valueName
    ) {
        Objects.requireNonNull(valueName, "valueName must not be null");
        if (context == null) {
            return null;
        }
        // Reuse the class-scope winner so direct static members can legally shadow inherited
        // instance members, while plain inherited instance members still get sealed on miss.
        var valueResult = context.declaringClassScope().resolveValueHere(valueName, ResolveRestriction.instanceContext());
        if (!valueResult.isFound()) {
            return null;
        }
        var resolvedValue = valueResult.requireValue();
        if (resolvedValue.staticMember()) {
            return null;
        }
        return switch (resolvedValue.kind()) {
            case PROPERTY, SIGNAL -> resolvedValue.kind();
            case LOCAL, PARAMETER, CAPTURE, CONSTANT, SINGLETON, GLOBAL_ENUM, TYPE_META -> null;
        };
    }

    public static boolean hasCurrentInstanceHierarchyNonStaticFunction(
            @Nullable PropertyInitializerContext context,
            @NotNull String functionName
    ) {
        return currentInstanceHierarchyNonStaticFunction(context, functionName) != null;
    }

    public static @Nullable FunctionDef currentInstanceHierarchyNonStaticFunction(
            @Nullable PropertyInitializerContext context,
            @NotNull String functionName
    ) {
        Objects.requireNonNull(functionName, "functionName must not be null");
        if (context == null) {
            return null;
        }
        // Function lookup follows the same "nearest non-empty class layer wins" rule as value
        // lookup, so the visible overload set already encodes whether a static helper suppresses
        // an inherited instance member with the same name.
        var functionResult = context.declaringClassScope().resolveFunctionsHere(
                functionName,
                ResolveRestriction.instanceContext()
        );
        if (!functionResult.isFound()) {
            return null;
        }
        return functionResult.requireValue().stream()
                .filter(function -> function.getName().equals(functionName) && !function.isStatic())
                .findFirst()
                .orElse(null);
    }

    public static boolean isCurrentInstanceHierarchyTypeMeta(
            @Nullable PropertyInitializerContext context,
            @Nullable ScopeTypeMeta receiverTypeMeta
    ) {
        return receiverTypeMeta != null
                && isCurrentInstanceHierarchyInstanceType(context, receiverTypeMeta.instanceType());
    }

    public static boolean isCurrentInstanceHierarchyInstanceType(
            @Nullable PropertyInitializerContext context,
            @Nullable GdType receiverType
    ) {
        if (context == null || !(receiverType instanceof GdObjectType objectType)) {
            return false;
        }
        var currentType = new GdObjectType(context.declaringClass().getName());
        return context.declaringClassScope().getClassRegistry().checkAssignable(currentType, objectType);
    }

    public static boolean isTopBindingOwnedUnsupportedHead(
            @Nullable PropertyInitializerContext context,
            @Nullable Node recoveryRoot
    ) {
        if (context == null) {
            return false;
        }
        return recoveryRoot instanceof IdentifierExpression || recoveryRoot instanceof SelfExpression;
    }

    public static @NotNull String unsupportedValueMessage(
            @NotNull String valueName,
            @NotNull ScopeValueKind valueKind
    ) {
        var memberKind = switch (Objects.requireNonNull(valueKind, "valueKind must not be null")) {
            case PROPERTY -> "property";
            case SIGNAL -> "signal";
            default ->
                    throw new IllegalArgumentException("unsupported current-instance-hierarchy value kind: " + valueKind);
        };
        return currentInstanceHierarchyNonStaticMemberDetail(memberKind, valueName);
    }

    public static @NotNull String unsupportedMethodMessage(@NotNull String methodName) {
        return currentInstanceHierarchyNonStaticMemberDetail(
                "method",
                Objects.requireNonNull(methodName, "methodName must not be null")
        );
    }

    public static @NotNull String unsupportedTypeMetaValueMessage(
            @NotNull String receiverName,
            @NotNull String valueName,
            @NotNull ScopeValueKind valueKind
    ) {
        return "Property initializer MVP does not support accessing current-instance-hierarchy non-static "
                + switch (Objects.requireNonNull(valueKind, "valueKind must not be null")) {
            case PROPERTY -> "property";
            case SIGNAL -> "signal";
            default ->
                    throw new IllegalArgumentException("unsupported current-instance-hierarchy value kind: " + valueKind);
        }
                + " '" + Objects.requireNonNull(receiverName, "receiverName must not be null") + "."
                + Objects.requireNonNull(valueName, "valueName must not be null") + "'";
    }

    public static @NotNull String unsupportedTypeMetaMethodMessage(
            @NotNull String receiverName,
            @NotNull String methodName
    ) {
        return "Property initializer MVP does not support accessing current-instance-hierarchy non-static method '"
                + Objects.requireNonNull(receiverName, "receiverName must not be null")
                + "." + Objects.requireNonNull(methodName, "methodName must not be null") + "()'";
    }

    public static @NotNull String unsupportedTypeMetaMethodReferenceMessage(
            @NotNull String receiverName,
            @NotNull String methodName
    ) {
        return "Property initializer MVP does not support accessing current-instance-hierarchy non-static method '"
                + Objects.requireNonNull(receiverName, "receiverName must not be null")
                + "." + Objects.requireNonNull(methodName, "methodName must not be null") + "'";
    }

    private static boolean isCurrentInstanceHierarchyClass(
            @NotNull PropertyInitializerContext context,
            @NotNull ClassDef ownerClass
    ) {
        var currentType = new GdObjectType(context.declaringClass().getName());
        return context.declaringClassScope().getClassRegistry().checkAssignable(
                currentType,
                new GdObjectType(ownerClass.getName())
        );
    }
}
