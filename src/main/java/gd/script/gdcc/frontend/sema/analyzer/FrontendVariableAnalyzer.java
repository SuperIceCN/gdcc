package gd.script.gdcc.frontend.sema.analyzer;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.diagnostic.FrontendRange;
import gd.script.gdcc.frontend.scope.BlockScope;
import gd.script.gdcc.frontend.scope.CallableScope;
import gd.script.gdcc.frontend.scope.CallableScopeKind;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.frontend.sema.FrontendDeclaredTypeSupport;
import gd.script.gdcc.frontend.sema.FrontendExecutableInventorySupport;
import gd.script.gdcc.frontend.sema.FrontendLambdaCapturePlan;
import gd.script.gdcc.frontend.sema.FrontendLambdaCapturePlanner;
import gd.script.gdcc.frontend.sema.FrontendMatchSupport;
import gd.script.gdcc.frontend.sema.FrontendModuleSkeleton;
import gd.script.gdcc.frontend.sema.LambdaCaptureEntry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import gd.script.gdcc.scope.ScopeValue;
import gd.script.gdcc.scope.ScopeValueKind;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdVariantType;
import dev.superice.gdparser.frontend.ast.ASTNodeHandler;
import dev.superice.gdparser.frontend.ast.ASTWalker;
import dev.superice.gdparser.frontend.ast.Block;
import dev.superice.gdparser.frontend.ast.ClassDeclaration;
import dev.superice.gdparser.frontend.ast.ConstructorDeclaration;
import dev.superice.gdparser.frontend.ast.DeclarationKind;
import dev.superice.gdparser.frontend.ast.ElifClause;
import dev.superice.gdparser.frontend.ast.ForStatement;
import dev.superice.gdparser.frontend.ast.FrontendASTTraversalDirective;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.GetNodeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.IfStatement;
import dev.superice.gdparser.frontend.ast.LambdaExpression;
import dev.superice.gdparser.frontend.ast.MatchSection;
import dev.superice.gdparser.frontend.ast.MatchStatement;
import dev.superice.gdparser.frontend.ast.Node;
import dev.superice.gdparser.frontend.ast.PatternBindingExpression;
import dev.superice.gdparser.frontend.ast.Parameter;
import dev.superice.gdparser.frontend.ast.SelfExpression;
import dev.superice.gdparser.frontend.ast.SourceFile;
import dev.superice.gdparser.frontend.ast.Statement;
import dev.superice.gdparser.frontend.ast.VariableDeclaration;
import dev.superice.gdparser.frontend.ast.WhileStatement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/// Frontend parameter/local inventory analyzer.
///
/// Current responsibilities are frozen as:
/// - require skeleton, diagnostics, and top-level source scopes to be published first
/// - write function/constructor parameters into `CallableScope`
/// - write supported ordinary locals into `BlockScope`
/// - bind lambda parameters/locals and derive `CAPTURE` bindings (names plus `sourceDeclaration`,
///   `Variant` placeholder types) for lambdas inside supported executable bodies
/// - bind match pattern bindings into each section `MATCH_SECTION_BODY` as `LOCAL` inventory
/// - keep block-local `const` inventory outside the current support boundary
/// - emit explicit recovery diagnostics instead of letting unsupported inventory sources fail silently
public class FrontendVariableAnalyzer {
    private static final @NotNull String VARIABLE_BINDING_CATEGORY = "sema.variable_binding";
    private static final @NotNull String UNSUPPORTED_PARAMETER_DEFAULT_VALUE_CATEGORY =
            "sema.unsupported_parameter_default_value";
    private static final @NotNull String UNSUPPORTED_VARIABLE_INVENTORY_SUBTREE_CATEGORY =
            "sema.unsupported_variable_inventory_subtree";

    /// Runs variable analysis against the shared analysis carrier.
    ///
    /// The published scope graph is enriched in place so later phases can consume one stable
    /// lexical structure plus declaration inventory without rebuilding scope objects.
    public void analyze(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull DiagnosticManager diagnosticManager
    ) {
        Objects.requireNonNull(analysisData, "analysisData must not be null");
        Objects.requireNonNull(diagnosticManager, "diagnosticManager must not be null");

        var moduleSkeleton = analysisData.moduleSkeleton();
        analysisData.diagnostics();

        // Missing top-level scopes indicate a broken phase boundary rather than a recoverable
        // source error, so this remains a fail-fast guard rail.
        var scopesByAst = analysisData.scopesByAst();
        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            var sourceFile = sourceClassRelation.unit().ast();
            if (!scopesByAst.containsKey(sourceFile)) {
                throw new IllegalStateException(
                        "Scope graph has not been published for source file: " + sourceClassRelation.unit().path()
                );
            }
        }

        for (var sourceClassRelation : moduleSkeleton.sourceClassRelations()) {
            new AstWalkerVariableBinder(
                    sourceClassRelation.unit().path(),
                    moduleSkeleton,
                    scopesByAst,
                    diagnosticManager
            ).walk(sourceClassRelation.unit().ast());
        }
    }

    /// ASTWalker-backed declaration-directed binder used by the current variable analyzer.
    ///
    /// `ASTWalker` is used here only as the typed dispatch mechanism. The analyzer still keeps
    /// explicit subtree control so unsupported domains remain sealed:
    /// - only source/class statement lists and supported executable blocks are descended into
    /// - function/constructor parameters are bound at the callable boundary
    /// - ordinary locals are bound only while the walker is inside a supported executable block
    /// - lambda inventories (parameters, locals, captures) are bound through
    ///   [`bindLambdaInventory`](#bindLambdaInventory), usually reached via the boundary reporter
    ///   because this walk does not descend into arbitrary expression children
    /// - `match` section binds are published by [`bindMatchSectionBindings`](#bindMatchSectionBindings)
    /// - arbitrary expression children stay outside the binding walk
    private static final class AstWalkerVariableBinder implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull FrontendModuleSkeleton moduleSkeleton;
        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull UnsupportedVariableBoundaryReporter unsupportedBoundaryReporter;
        private @Nullable Node currentCallableOwner;
        /// Nearest enclosing non-lambda callable (function/constructor) whose `self` a lambda may capture.
        ///
        /// Tracked separately from `currentCallableOwner` because nested lambdas replace the callable owner
        /// while the `self` source stays the outermost instance callable of the chain.
        private @Nullable Node currentSelfSourceCallable;
        /// Completed per-lambda capture plans, keyed by AST identity, so an enclosing lambda can transfer
        /// nested captures upward. Never leaves this analyzer run: inventory must not
        /// publish placeholder plans into `FrontendAnalysisData.lambdaPlans()`.
        private final @NotNull IdentityHashMap<LambdaExpression, FrontendLambdaCapturePlan> lambdaCapturePlans =
                new IdentityHashMap<>();
        /// Idempotency guard: both the declaration walk and the boundary reporter can reach the same lambda,
        /// but parameters/captures must be bound exactly once.
        private final @NotNull Set<LambdaExpression> processedLambdas =
                Collections.newSetFromMap(new IdentityHashMap<>());
        /// Counts how many supported executable-block boundaries the walker is currently inside.
        ///
        /// The counter acts as a narrow capability flag rather than a generic nesting metric:
        /// - `0` means the current traversal position is outside any block where local-variable
        ///   binding is allowed, such as source/class declaration containers
        /// - `> 0` means the walker is inside a function/constructor body or one of the currently
        ///   supported nested executable blocks beneath it
        ///
        /// This deliberately keeps variable binding disabled for non-executable containers while
        /// still allowing nested `Block` / `if` / `elif` / `while` nodes to participate once the
        /// callable body has opened the first supported executable scope.
        private int supportedExecutableBlockDepth;

        private AstWalkerVariableBinder(
                @NotNull Path sourcePath,
                @NotNull FrontendModuleSkeleton moduleSkeleton,
                @NotNull FrontendAstSideTable<Scope> scopesByAst,
                @NotNull DiagnosticManager diagnosticManager
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.moduleSkeleton = Objects.requireNonNull(moduleSkeleton, "moduleSkeleton");
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager");
            this.astWalker = new ASTWalker(this);
            unsupportedBoundaryReporter = new UnsupportedVariableBoundaryReporter(
                    sourcePath,
                    diagnosticManager,
                    this::bindLambdaInventory
            );
        }

        private void walk(@NotNull SourceFile sourceFile) {
            astWalker.walk(sourceFile);
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSourceFile(@NotNull SourceFile sourceFile) {
            walkNonExecutableContainerStatements(sourceFile.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            if (isNotPublished(classDeclaration)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkNonExecutableContainerStatements(classDeclaration.body().statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            bindCallableParameters(functionDeclaration, functionDeclaration.parameters(), functionDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            bindCallableParameters(constructorDeclaration, constructorDeclaration.parameters(), constructorDeclaration.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleBlock(@NotNull Block block) {
            // A plain `Block` is only considered bindable when the current traversal has already
            // entered a supported executable region. This prevents the walker from treating
            // structurally block-shaped but non-executable containers as local-binding domains.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(block)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkStatements(block.statements());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIfStatement(@NotNull IfStatement ifStatement) {
            // `if` branches contribute local scopes only when they appear inside an already
            // accepted executable block. At top level or class body they must stay inert.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(ifStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(ifStatement.body());
            for (var elifClause : ifStatement.elifClauses()) {
                astWalker.walk(elifClause);
            }
            if (ifStatement.elseBody() != null) {
                walkSupportedExecutableBlock(ifStatement.elseBody());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleElifClause(@NotNull ElifClause elifClause) {
            // `elif` is gated by the same executable-context check as `if`, because its body is
            // only meaningful as a nested runtime branch, never as a declaration container.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(elifClause)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(elifClause.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleWhileStatement(@NotNull WhileStatement whileStatement) {
            // Loop bodies are part of the current supported executable-block set, but only once
            // the walker is already under a callable body. This keeps unsupported outer
            // containers sealed.
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(whileStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            walkSupportedExecutableBlock(whileStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            // Variable declarations are only interpreted as callable-local inventory once the
            // walker is inside a supported executable block. Outside that region, the same AST
            // shape may represent class members and must remain untouched here.
            if (supportedExecutableBlockDepth <= 0) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() == DeclarationKind.CONST) {
                reportUnsupportedBlockLocalConst(variableDeclaration);
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            if (variableDeclaration.kind() != DeclarationKind.VAR) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            bindLocal(variableDeclaration);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            // Lambda inventory (parameters, locals, captures) is part of the supported executable
            // surface. In practice the boundary reporter routes lambdas here because this binder
            // never descends into expression children; binding stays idempotent either way.
            bindLambdaInventory(lambdaExpression);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleForStatement(@NotNull ForStatement forStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(forStatement.body())) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            bindForIterator(forStatement);
            walkSupportedExecutableBlock(forStatement.body());
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleMatchStatement(@NotNull MatchStatement matchStatement) {
            if (supportedExecutableBlockDepth <= 0 || isNotPublished(matchStatement)) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            for (var section : matchStatement.sections()) {
                bindMatchSectionBindings(section);
                walkSupportedExecutableBlock(section.body());
            }
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        private void bindCallableParameters(
                @NotNull Node callableOwner,
                @NotNull List<Parameter> parameters,
                @NotNull Block body
        ) {
            if (isNotPublished(callableOwner)) {
                return;
            }
            var previousCallableOwner = currentCallableOwner;
            var previousSelfSourceCallable = currentSelfSourceCallable;
            currentCallableOwner = callableOwner;
            // Only a named function/constructor can be the `self` source for lambda captures;
            // a nested lambda keeps the enclosing non-lambda callable as its self source.
            if (!(callableOwner instanceof LambdaExpression)) {
                currentSelfSourceCallable = callableOwner;
            }
            try {
                for (var parameter : parameters) {
                    bindParameter(parameter);
                }
                if (isNotPublished(body)) {
                    return;
                }
                // Locals must be bound before the boundary scan: the scan binds nested lambdas
                // whose capture planning resolves names against this callable's published locals.
                walkSupportedExecutableBlock(body);
                unsupportedBoundaryReporter.report(body);
            } finally {
                currentCallableOwner = previousCallableOwner;
                currentSelfSourceCallable = previousSelfSourceCallable;
            }
        }

        /// Binds the complete inventory of one lambda inside a supported executable body:
        /// parameters, body locals, nested-lambda inventories (via the boundary reporter), and
        /// finally this lambda's own `CAPTURE` bindings.
        ///
        /// Capture planning is deliberately post-order: every nested lambda has
        /// finished binding and planning before this lambda scans its own uses, so nested
        /// parameter/local bindings can never be misread as captures of this lambda.
        private void bindLambdaInventory(@NotNull LambdaExpression lambdaExpression) {
            if (processedLambdas.contains(lambdaExpression) || isNotPublished(lambdaExpression)) {
                return;
            }
            processedLambdas.add(lambdaExpression);
            if (!(scopesByAst.get(lambdaExpression) instanceof CallableScope lambdaScope)
                    || lambdaScope.kind() != CallableScopeKind.LAMBDA_EXPRESSION
                    || !(scopesByAst.get(lambdaExpression.body()) instanceof BlockScope lambdaBodyScope)) {
                reportBindingError(lambdaExpression, "Lambda expression has no published lambda scope inventory");
                return;
            }
            bindCallableParameters(lambdaExpression, lambdaExpression.parameters(), lambdaExpression.body());
            var capturePlan = planLambdaCaptures(lambdaExpression, lambdaScope, lambdaBodyScope);
            lambdaCapturePlans.put(lambdaExpression, capturePlan);
            for (var capture : capturePlan.captures()) {
                defineCaptureGuarded(lambdaScope, capture, lambdaExpression);
            }
        }

        /// Derives this lambda's ordered capture list from its own identifier uses plus captures
        /// transferred from directly nested lambdas, keeping first-appearance source order (plan
        /// §3.4 rule 6). A leading `self` entry is prepended when the body needs the enclosing
        /// instance (§3.5).
        private @NotNull FrontendLambdaCapturePlan planLambdaCaptures(
                @NotNull LambdaExpression lambdaExpression,
                @NotNull CallableScope lambdaScope,
                @NotNull BlockScope lambdaBodyScope
        ) {
            var scanner = new LambdaCaptureSourceScanner(scopesByAst);
            scanner.scan(lambdaExpression.body());
            var capturesByName = new LinkedHashMap<String, LambdaCaptureEntry>();
            var needsSelf = scanner.usesExplicitSelf;
            for (var event : scanner.events) {
                switch (event) {
                    case LambdaCaptureSourceScanner.IdentifierEvent identifierEvent -> {
                        var use = identifierEvent.use();
                        if (capturesByName.containsKey(use.name())) {
                            continue;
                        }
                        var ownCapture = captureForOwnUse(lambdaScope, use);
                        if (ownCapture != null) {
                            capturesByName.put(ownCapture.name(), ownCapture);
                        }
                    }
                    case LambdaCaptureSourceScanner.NestedLambdaEvent nestedLambdaEvent -> {
                        var childPlan = lambdaCapturePlans.get(nestedLambdaEvent.lambda());
                        if (childPlan == null) {
                            // The nested lambda failed to bind; its own diagnostics already exist.
                            continue;
                        }
                        if (childPlan.capturesSelf()) {
                            needsSelf = true;
                        }
                        for (var childCapture : childPlan.captures()) {
                            if (FrontendLambdaCapturePlan.SELF_CAPTURE_NAME.equals(childCapture.name())) {
                                // `self` is never merged by name; it is rebuilt as the leading entry.
                                continue;
                            }
                            if (capturesByName.containsKey(childCapture.name())) {
                                continue;
                            }
                            // Transfer only when the source lives outside this lambda and this
                            // lambda does not shadow the name (planner returns null otherwise).
                            var transferred = FrontendLambdaCapturePlanner.transferredCapture(
                                    lambdaScope,
                                    lambdaBodyScope,
                                    childCapture
                            );
                            if (transferred != null) {
                                capturesByName.put(transferred.name(), transferred);
                            }
                        }
                    }
                }
            }
            if (!needsSelf && usesImplicitInstanceMember(scanner.events, capturesByName.keySet())) {
                needsSelf = true;
            }
            var entries = new ArrayList<LambdaCaptureEntry>();
            var selfCapture = needsSelf ? buildSelfCaptureEntry(lambdaScope) : null;
            if (selfCapture != null) {
                entries.add(selfCapture);
            }
            entries.addAll(capturesByName.values());
            return FrontendLambdaCapturePlan.of(entries);
        }

        /// Plans a capture for one bare identifier use of this lambda, reusing the pure planner
        /// with a singleton use list so all capture decisions share one code path.
        private @Nullable LambdaCaptureEntry captureForOwnUse(
                @NotNull CallableScope lambdaScope,
                @NotNull FrontendLambdaCapturePlanner.IdentifierUse use
        ) {
            var plan = FrontendLambdaCapturePlanner.planCaptures(lambdaScope, List.of(use));
            return plan.captures().isEmpty() ? null : plan.captures().getFirst();
        }

        /// Implicit `self` is needed when a bare identifier that was not captured resolves to an
        /// INSTANCE member (non-static property/signal value, or a non-static method overload) —
        /// such uses need the instance receiver at runtime. Static members and global
        /// utility functions (`print`, `abs`, ...) never need a receiver and must not synthesize
        /// a `self` capture.
        private boolean usesImplicitInstanceMember(
                @NotNull List<LambdaCaptureSourceScanner.LambdaCaptureEvent> events,
                @NotNull Set<String> capturedNames
        ) {
            for (var event : events) {
                if (!(event instanceof LambdaCaptureSourceScanner.IdentifierEvent(var use))) {
                    continue;
                }
                if (capturedNames.contains(use.name())) {
                    continue;
                }
                var valueResult = use.startingScope().resolveValue(use.name(), ResolveRestriction.unrestricted());
                if (valueResult.isFound()) {
                    var value = valueResult.requireValue();
                    var kind = value.kind();
                    if ((kind == ScopeValueKind.PROPERTY || kind == ScopeValueKind.SIGNAL)
                            && !value.staticMember()) {
                        return true;
                    }
                    continue;
                }
                var functionsResult = use.startingScope().resolveFunctions(use.name(), ResolveRestriction.unrestricted());
                if (functionsResult.isAllowed()
                        && functionsResult.requireValue().stream().anyMatch(function -> !function.isStatic())) {
                    return true;
                }
            }
            return false;
        }

        /// Builds the leading `self` capture entry, or `null` when `self` is unavailable here:
        /// the enclosing non-lambda callable is static (the restriction diagnostic stays with the
        /// body-typing phases) or no owning class can be determined.
        private @Nullable LambdaCaptureEntry buildSelfCaptureEntry(@NotNull CallableScope lambdaScope) {
            var selfSourceCallable = currentSelfSourceCallable;
            var instanceSelfSource = switch (selfSourceCallable) {
                case FunctionDeclaration functionDeclaration when !functionDeclaration.isStatic() ->
                        functionDeclaration;
                case ConstructorDeclaration constructorDeclaration -> constructorDeclaration;
                case null, default -> null;
            };
            if (instanceSelfSource == null) {
                return null;
            }
            var owningClass = lambdaScope.owningClassOrNull();
            if (owningClass == null) {
                return null;
            }
            // `self` behaves like the enclosing callable's implicit parameter; all transfer layers
            // share this same declaration identity and the enclosing class object type (§3.5).
            return new LambdaCaptureEntry(
                    FrontendLambdaCapturePlan.SELF_CAPTURE_NAME,
                    new GdObjectType(owningClass.getName()),
                    ScopeValueKind.PARAMETER,
                    instanceSelfSource
            );
        }

        /// Registers one capture on the lambda scope without letting user source reach
        /// `CallableScope`'s fail-fast duplicate guard.
        ///
        /// Inventory registers the capture NAME with a `Variant` placeholder type plus the source
        /// declaration identity; nested suite resolution later replaces the placeholder with the
        /// declaration-site type.
        private void defineCaptureGuarded(
                @NotNull CallableScope lambdaScope,
                @NotNull LambdaCaptureEntry capture,
                @NotNull LambdaExpression lambdaExpression
        ) {
            var existingBinding = lambdaScope.resolveValueHere(capture.name());
            if (existingBinding != null) {
                if (existingBinding.kind() == ScopeValueKind.CAPTURE
                        && existingBinding.declaration() == capture.sourceDeclaration()) {
                    // Idempotent re-bind of the same capture (defensive; planning dedupes by name).
                    return;
                }
                reportBindingError(
                        lambdaExpression,
                        "Capture '" + capture.name() + "' conflicts with existing "
                                + describeShadowingTarget(existingBinding) + " in the same lambda"
                );
                return;
            }
            try {
                lambdaScope.defineCapture(capture.name(), GdVariantType.VARIANT, capture.sourceDeclaration());
            } catch (IllegalArgumentException exception) {
                reportBindingError(
                        lambdaExpression,
                        "Duplicate capture '" + capture.name() + "' in the same lambda"
                );
            }
        }

        private void walkStatements(@NotNull List<Statement> statements) {
            for (var statement : statements) {
                astWalker.walk(statement);
            }
        }

        /// Top-level and class-body statement lists are only declaration containers.
        /// Local-variable binding must stay disabled there so class properties are not reclassified
        /// as block locals.
        private void walkNonExecutableContainerStatements(@NotNull List<Statement> statements) {
            var previousDepth = supportedExecutableBlockDepth;
            supportedExecutableBlockDepth = 0;
            try {
                walkStatements(statements);
            } finally {
                supportedExecutableBlockDepth = previousDepth;
            }
        }

        private void walkSupportedExecutableBlock(@Nullable Block block) {
            if (isNotPublished(block)) {
                return;
            }
            // Entering a supported executable block enables local binding for its statements and
            // any further supported nested blocks beneath it. The `finally` restore is required so
            // sibling top-level/class-body statements do not accidentally inherit executable state.
            supportedExecutableBlockDepth++;
            try {
                astWalker.walk(block);
            } finally {
                supportedExecutableBlockDepth--;
            }
        }

        private void bindParameter(@NotNull Parameter parameter) {
            var parameterName = parameter.name().trim();
            // Lambda parameter defaults stay fail-closed here. Source function defaults are no
            // longer reported: their expressions are analyzed by the parameter-default metadata
            // owner during the suite phase. Constructor/parameterized `_init` defaults stay
            // permanently unsupported on the existing suite rejection path and never enter the
            // owner's sweep.
            if (currentCallableOwner instanceof LambdaExpression) {
                reportUnsupportedDefaultValue(parameter);
            }

            var targetScope = scopesByAst.get(parameter);
            if (targetScope == null) {
                return;
            }
            if (!(targetScope instanceof CallableScope callableScope)) {
                reportBindingError(
                        parameter,
                        "Parameter '" + parameterName + "' expected CallableScope, but found "
                                + targetScope.getClass().getSimpleName()
                );
                return;
            }

            var parameterType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    parameter.type(),
                    callableScope,
                    moduleSkeleton.topLevelCanonicalNameMap(),
                    sourcePath,
                    diagnosticManager
            );
            var existingBinding = callableScope.resolveValueHere(parameterName);
            if (existingBinding != null) {
                reportBindingError(parameter, switch (existingBinding.kind()) {
                    case PARAMETER -> "Duplicate parameter '" + parameterName + "' in the same callable";
                    case CAPTURE ->
                            "Parameter '" + parameterName + "' conflicts with existing capture '" + parameterName + "'";
                    default ->
                            "Parameter '" + parameterName + "' conflicts with existing callable binding '" + parameterName + "'";
                });
                return;
            }
            callableScope.defineParameter(parameterName, parameterType, parameter);
        }

        private void bindLocal(@NotNull VariableDeclaration variableDeclaration) {
            var variableName = variableDeclaration.name().trim();
            var targetScope = scopesByAst.get(variableDeclaration);
            if (targetScope == null) {
                return;
            }
            if (!(targetScope instanceof BlockScope blockScope)) {
                reportBindingError(
                        variableDeclaration,
                        "Local variable '" + variableName + "' expected BlockScope, but found "
                                + targetScope.getClass().getSimpleName()
                );
                return;
            }

            if (!FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind())) {
                reportBindingError(
                        variableDeclaration,
                        "Local variable '" + variableName + "' expected supported executable BlockScope, but found "
                                + blockScope.kind()
                );
                return;
            }

            var existingLocal = blockScope.resolveValueHere(variableName);
            if (existingLocal != null) {
                reportLocalConflict(variableDeclaration, blockScope, existingLocal, false);
                return;
            }

            var sameCallableConflict = findSameCallableConflict(blockScope, variableName);
            if (sameCallableConflict != null) {
                reportLocalConflict(variableDeclaration, blockScope, sameCallableConflict, true);
                return;
            }

            var variableType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    variableDeclaration.type(),
                    blockScope,
                    moduleSkeleton.topLevelCanonicalNameMap(),
                    sourcePath,
                    diagnosticManager
            );
            try {
                blockScope.defineLocal(variableName, variableType, variableDeclaration);
            } catch (IllegalArgumentException exception) {
                reportBindingError(
                        variableDeclaration,
                        "Duplicate local variable '" + variableName + "' in the same block"
                );
            }
        }

        private void bindForIterator(@NotNull ForStatement forStatement) {
            var iteratorName = forStatement.iterator().trim();
            var targetScope = scopesByAst.get(forStatement.body());
            if (!(targetScope instanceof BlockScope blockScope)
                    || !FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind())) {
                reportBindingError(forStatement, "Loop iterator '" + iteratorName + "' has no supported `for` body scope");
                return;
            }

            var existingLocal = blockScope.resolveValueHere(iteratorName);
            if (existingLocal != null) {
                reportBindingError(forStatement, "Duplicate loop iterator '" + iteratorName + "' in the same `for` body");
                return;
            }
            var sameCallableConflict = findSameCallableConflict(blockScope, iteratorName);
            if (sameCallableConflict != null) {
                reportBindingError(
                        forStatement,
                        "Loop iterator '" + iteratorName + "' in " + describeLocalContext(blockScope)
                                + " shadows " + describeShadowingTarget(sameCallableConflict)
                );
            }

            var headerScope = scopesByAst.get(forStatement);
            if (headerScope == null) {
                throw new IllegalStateException("Loop iterator '" + iteratorName + "' has no published header scope");
            }
            var iteratorType = FrontendDeclaredTypeSupport.resolveTypeOrVariant(
                    forStatement.iteratorType(),
                    headerScope,
                    moduleSkeleton.topLevelCanonicalNameMap(),
                    sourcePath,
                    diagnosticManager
            );
            blockScope.defineLocal(iteratorName, iteratorType, forStatement);
        }

        /// Publishes every `var x` bind in one match section, including binds nested in
        /// array/dictionary patterns, as `LOCAL` inventory on the shared `MATCH_SECTION_BODY`.
        /// Baseline type is always `Variant`; later `MATCH_PATTERN_RESOLUTION` may refine top-level
        /// binds. Duplicate / shadowing reuse the ordinary callable-local `sema.variable_binding` owner.
        private void bindMatchSectionBindings(@NotNull MatchSection section) {
            var targetScope = scopesByAst.get(section);
            if (!(targetScope instanceof BlockScope blockScope)
                    || !FrontendExecutableInventorySupport.canPublishCallableLocalValueInventory(blockScope.kind())) {
                reportBindingError(section, "Match section has no supported `match` section body scope");
                return;
            }
            for (var pattern : section.patterns()) {
                for (var bindingPlan : FrontendMatchSupport.collectPatternBindings(pattern, true)) {
                    bindMatchPatternBinding(blockScope, bindingPlan.declaration());
                }
            }
        }

        private void bindMatchPatternBinding(
                @NotNull BlockScope blockScope,
                @NotNull PatternBindingExpression patternBinding
        ) {
            var bindName = patternBinding.name();
            var existingLocal = blockScope.resolveValueHere(bindName);
            if (existingLocal != null) {
                reportBindingError(
                        patternBinding,
                        "Duplicate pattern binding '" + bindName + "' in the same `match` section"
                );
                return;
            }
            var sameCallableConflict = findSameCallableConflict(blockScope, bindName);
            if (sameCallableConflict != null) {
                reportBindingError(
                        patternBinding,
                        "Pattern binding '" + bindName + "' in " + describeLocalContext(blockScope)
                                + " shadows " + describeShadowingTarget(sameCallableConflict)
                );
            }
            try {
                blockScope.defineLocal(bindName, GdVariantType.VARIANT, patternBinding);
            } catch (IllegalArgumentException exception) {
                reportBindingError(
                        patternBinding,
                        "Duplicate pattern binding '" + bindName + "' in the same `match` section"
                );
            }
        }

        /// Duplicate and shadowing locals are user-facing source errors rather than phase
        /// invariants. The message therefore carries both declaration locations plus the callable /
        /// block context so compile-only callers can stop before lowering while shared analysis keeps
        /// processing unaffected facts.
        private void reportLocalConflict(
                @NotNull VariableDeclaration variableDeclaration,
                @NotNull BlockScope blockScope,
                @NotNull ScopeValue conflictingBinding,
                boolean shadowing
        ) {
            var variableName = variableDeclaration.name().trim();
            var currentRange = FrontendRange.fromAstRange(variableDeclaration.range());
            var conflictingDeclaration = conflictingBinding.declaration();
            var conflictingRange = conflictingDeclaration instanceof Node conflictingNode
                    ? FrontendRange.fromAstRange(conflictingNode.range())
                    : null;
            var message = shadowing
                    ? "Local variable '%s' in %s shadows %s; shadowing declaration is at %s and the earlier declaration is at %s in %s"
                      .formatted(
                              variableName,
                              describeLocalContext(blockScope),
                              describeShadowingTarget(conflictingBinding),
                              formatRange(currentRange),
                              formatRange(conflictingRange),
                              sourcePath
                      )
                    : "Duplicate local variable '%s' in %s; current declaration is at %s and the earlier declaration is at %s in %s"
                      .formatted(
                              variableName,
                              describeLocalContext(blockScope),
                              formatRange(currentRange),
                              formatRange(conflictingRange),
                              sourcePath
                      );
            reportBindingError(variableDeclaration, message);
        }

        private @NotNull String describeLocalContext(@NotNull BlockScope blockScope) {
            return switch (blockScope.kind()) {
                case FUNCTION_BODY, CONSTRUCTOR_BODY -> describeCallableContext();
                case BLOCK_STATEMENT -> "block statement of " + describeCallableContext();
                case IF_BODY -> "if-body of " + describeCallableContext();
                case ELIF_BODY -> "elif-body of " + describeCallableContext();
                case ELSE_BODY -> "else-body of " + describeCallableContext();
                case WHILE_BODY -> "while-body of " + describeCallableContext();
                case LAMBDA_BODY -> "lambda-body of " + describeCallableContext();
                case FOR_BODY -> "`for` body of " + describeCallableContext();
                case MATCH_SECTION_BODY -> "`match` section of " + describeCallableContext();
            };
        }

        private @NotNull String describeCallableContext() {
            return switch (currentCallableOwner) {
                case FunctionDeclaration functionDeclaration -> "function '" + functionDeclaration.name().trim() + "'";
                case ConstructorDeclaration _ -> "constructor '_init'";
                case LambdaExpression _ -> "lambda expression";
                case null -> "callable";
                default -> currentCallableOwner.getClass().getSimpleName();
            };
        }

        private @NotNull String describeShadowingTarget(@NotNull ScopeValue conflictingBinding) {
            var targetKind = switch (conflictingBinding.kind()) {
                case PARAMETER -> "parameter";
                case CAPTURE -> "capture";
                case LOCAL -> "outer local";
                case CONSTANT -> "outer constant";
                default -> "callable-local binding";
            };
            return targetKind + " '" + conflictingBinding.name() + "'";
        }

        private static @NotNull String formatRange(@Nullable FrontendRange range) {
            if (range == null) {
                return "<unknown-range>";
            }
            return "%d:%d-%d:%d".formatted(
                    range.start().line(),
                    range.start().column(),
                    range.end().line(),
                    range.end().column()
            );
        }

        /// Local shadowing is forbidden only inside the current callable boundary.
        /// Class/global bindings are intentionally not part of this check because they remain legal
        /// outer names for callable-local declarations.
        private @Nullable ScopeValue findSameCallableConflict(
                @NotNull BlockScope blockScope,
                @NotNull String variableName
        ) {
            return FrontendBodyOwnerProcedures.findCallableLocalBindingUpScopes(
                    blockScope.getParentScope(), variableName);
        }

        private void reportUnsupportedDefaultValue(@NotNull Parameter parameter) {
            if (parameter.defaultValue() == null) {
                return;
            }
            diagnosticManager.error(
                    UNSUPPORTED_PARAMETER_DEFAULT_VALUE_CATEGORY,
                    "Parameter default value for '" + parameter.name().trim()
                            + "' is not supported by the current frontend body-typing contract; "
                            + "the current variable analyzer ignores the default value expression",
                    sourcePath,
                    FrontendRange.fromAstRange(parameter.defaultValue().range())
            );
        }

        private void reportUnsupportedBlockLocalConst(@NotNull VariableDeclaration variableDeclaration) {
            diagnosticManager.error(
                    UNSUPPORTED_VARIABLE_INVENTORY_SUBTREE_CATEGORY,
                    "Variable analysis does not support block-local `const` declarations yet; constant '"
                            + variableDeclaration.name().trim()
                            + "' is not bound into the current executable scope yet",
                    sourcePath,
                    FrontendRange.fromAstRange(variableDeclaration.range())
            );
        }

        private void reportBindingError(
                @NotNull Node declaration,
                @NotNull String message
        ) {
            diagnosticManager.error(
                    VARIABLE_BINDING_CATEGORY,
                    message,
                    sourcePath,
                    FrontendRange.fromAstRange(declaration.range())
            );
        }

        private boolean isNotPublished(@Nullable Node astNode) {
            return astNode == null || !scopesByAst.containsKey(astNode);
        }
    }

    /// Scans supported callable bodies for unsupported variable-inventory boundaries that the
    /// current binder intentionally does not enter.
    ///
    /// This reporter exists because the binder itself is declaration-directed and therefore skips
    /// arbitrary expression children. Without a separate scan, lambdas nested inside expressions
    /// such as local initializers or return values would remain completely silent.
    ///
    /// Lambdas found inside supported executable bodies are no longer reported; they are handed
    /// to the binder's lambda-inventory pipeline instead. Match subtrees are bound by the main
    /// walker, so this reporter no longer treats them as an unsupported inventory boundary.
    /// Block-local `const` subtrees stay sealed here as well: the binder already reports the
    /// declaration itself, and names inside such a subtree belong to a not-yet-supported domain.
    private static final class UnsupportedVariableBoundaryReporter implements ASTNodeHandler {
        private final @NotNull Path sourcePath;
        private final @NotNull DiagnosticManager diagnosticManager;
        private final @NotNull Consumer<LambdaExpression> lambdaInventoryBinder;
        private final @NotNull ASTWalker astWalker;

        private UnsupportedVariableBoundaryReporter(
                @NotNull Path sourcePath,
                @NotNull DiagnosticManager diagnosticManager,
                @NotNull Consumer<LambdaExpression> lambdaInventoryBinder
        ) {
            this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
            this.diagnosticManager = Objects.requireNonNull(diagnosticManager, "diagnosticManager");
            this.lambdaInventoryBinder = Objects.requireNonNull(lambdaInventoryBinder, "lambdaInventoryBinder");
            astWalker = new ASTWalker(this);
        }

        private void report(@NotNull Block callableBody) {
            astWalker.walk(callableBody);
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.CONTINUE;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleClassDeclaration(@NotNull ClassDeclaration classDeclaration) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleFunctionDeclaration(
                @NotNull FunctionDeclaration functionDeclaration
        ) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleConstructorDeclaration(
                @NotNull ConstructorDeclaration constructorDeclaration
        ) {
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            // Lambdas inside supported executable bodies are bound, not reported. The binder walks
            // the lambda body itself, so this scan must not descend into it a second time.
            lambdaInventoryBinder.accept(lambdaExpression);
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            // Block-local `const` inventory stays unsupported; the binder reports the declaration
            // and nothing inside its initializer subtree may bind inventory here.
            if (variableDeclaration.kind() == DeclarationKind.CONST) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            return FrontendASTTraversalDirective.CONTINUE;
        }

    }

    /// Collects capture-relevant events of one lambda body in source order.
    ///
    /// Two event kinds feed capture planning:
    /// - bare identifier uses, each pre-bound to the scope that owns the use site
    /// - direct nested lambdas, whose completed plans may transfer captures upward
    ///
    /// The scanner skips nested lambda bodies (bound and planned on their own) and block-local
    /// `const` subtrees: names inside not-yet-supported domains must never become captures.
    /// Match sections are walked so bare identifiers and nested lambdas inside them participate
    /// in capture planning. Explicit `self` expressions and get-node expressions (`$` / `%`) are
    /// flagged separately because both need the enclosing instance receiver without being an
    /// identifier use.
    private static final class LambdaCaptureSourceScanner implements ASTNodeHandler {
        /// One source-ordered capture-relevant event inside a lambda body.
        private sealed interface LambdaCaptureEvent {
        }

        /// Bare identifier use that may resolve to a capturable outer binding.
        private record IdentifierEvent(
                @NotNull FrontendLambdaCapturePlanner.IdentifierUse use
        ) implements LambdaCaptureEvent {
        }

        /// Direct nested lambda whose own capture plan may transfer entries to this lambda.
        private record NestedLambdaEvent(@NotNull LambdaExpression lambda) implements LambdaCaptureEvent {
        }

        private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
        private final @NotNull ASTWalker astWalker;
        private final @NotNull List<LambdaCaptureEvent> events = new ArrayList<>();
        private boolean usesExplicitSelf;

        private LambdaCaptureSourceScanner(@NotNull FrontendAstSideTable<Scope> scopesByAst) {
            this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst");
            astWalker = new ASTWalker(this);
        }

        private void scan(@NotNull Block lambdaBody) {
            astWalker.walk(lambdaBody);
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleNode(@NotNull Node node) {
            return FrontendASTTraversalDirective.CONTINUE;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleLambdaExpression(@NotNull LambdaExpression lambdaExpression) {
            events.add(new NestedLambdaEvent(lambdaExpression));
            return FrontendASTTraversalDirective.SKIP_CHILDREN;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleVariableDeclaration(
                @NotNull VariableDeclaration variableDeclaration
        ) {
            // The declaration name is a plain String, not an IdentifierExpression, so declaration
            // sites never leak into uses; only the initializer of a supported `var` is scanned.
            if (variableDeclaration.kind() == DeclarationKind.CONST) {
                return FrontendASTTraversalDirective.SKIP_CHILDREN;
            }
            return FrontendASTTraversalDirective.CONTINUE;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleIdentifierExpression(
                @NotNull IdentifierExpression identifierExpression
        ) {
            // `super.m(...)` needs the enclosing instance receiver exactly like an explicit `self`
            // use: the super route resolves the target from the lexical superclass but still invokes
            // it on `self`, so the lambda must capture it.
            if ("super".equals(identifierExpression.name())) {
                usesExplicitSelf = true;
            }
            var useSiteScope = scopesByAst.get(identifierExpression);
            if (useSiteScope != null) {
                events.add(new IdentifierEvent(new FrontendLambdaCapturePlanner.IdentifierUse(
                        identifierExpression.name().trim(),
                        useSiteScope
                )));
            }
            return FrontendASTTraversalDirective.CONTINUE;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleSelfExpression(@NotNull SelfExpression selfExpression) {
            usesExplicitSelf = true;
            return FrontendASTTraversalDirective.CONTINUE;
        }

        @Override
        public @NotNull FrontendASTTraversalDirective handleGetNodeExpression(@NotNull GetNodeExpression getNodeExpression) {
            // Get-node (`$` / `%`) desugars to `self.get_node(path)`, so it needs the enclosing
            // instance receiver exactly like an explicit `self` expression. The node is a leaf
            // with no child expressions, and capture validity (static enclosing callable, class
            // hierarchy) stays with `buildSelfCaptureEntry` and the body-typing phases.
            usesExplicitSelf = true;
            return FrontendASTTraversalDirective.CONTINUE;
        }
    }
}
