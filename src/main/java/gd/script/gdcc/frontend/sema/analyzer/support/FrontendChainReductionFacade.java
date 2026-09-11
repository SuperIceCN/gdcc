package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendAstSideTable;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.scope.Scope;
import dev.superice.gdparser.frontend.ast.AttributeExpression;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import gd.script.gdcc.frontend.sema.FrontendBinding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/// Analyzer-side cached facade for attribute-chain reduction.
///
/// The facade keeps only orchestration concerns that were duplicated in both analyzers:
/// - local `AttributeExpression -> ReductionResult?` caching
/// - cache hit/miss handling
/// - chain-head receiver lookup through `FrontendChainHeadReceiverSupport`
/// - the handoff into `FrontendChainReductionHelper.reduce(...)`
///
/// It deliberately does not publish side tables, emit diagnostics, or own whole-module state.
public final class FrontendChainReductionFacade {
    /// `computedNow` tells the caller whether this invocation crossed a cache miss.
    /// Chain-binding can use it to publish diagnostics only once for a newly reduced chain.
    public record CachedReduction(
            @Nullable FrontendChainReductionHelper.ReductionResult result,
            boolean computedNow
    ) {
    }

    private final @NotNull FrontendAnalysisData analysisData;
    private final @NotNull FrontendAstSideTable<Scope> scopesByAst;
    private final @NotNull Supplier<ResolveRestriction> restrictionSupplier;
    private final @NotNull BooleanSupplier staticContextSupplier;
    private final @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext>
            propertyInitializerContextSupplier;
    private final @NotNull ClassRegistry classRegistry;
    private final @NotNull FrontendChainReductionHelper.ExpressionTypeResolver expressionTypeResolver;
    private final @NotNull Function<IdentifierExpression, FrontendBinding> bindingLookup;
    private final @NotNull IdentityHashMap<AttributeExpression, Optional<FrontendChainReductionHelper.ReductionResult>> reducedChains =
            new IdentityHashMap<>();

    public FrontendChainReductionFacade(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull BooleanSupplier staticContextSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendChainReductionHelper.ExpressionTypeResolver expressionTypeResolver
    ) {
        this(
                analysisData,
                scopesByAst,
                restrictionSupplier,
                staticContextSupplier,
                () -> null,
                classRegistry,
                expressionTypeResolver,
                analysisData.symbolBindings()::get
        );
    }

    public FrontendChainReductionFacade(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull BooleanSupplier staticContextSupplier,
            @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext>
                    propertyInitializerContextSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendChainReductionHelper.ExpressionTypeResolver expressionTypeResolver
    ) {
        this(
                analysisData,
                scopesByAst,
                restrictionSupplier,
                staticContextSupplier,
                propertyInitializerContextSupplier,
                classRegistry,
                expressionTypeResolver,
                analysisData.symbolBindings()::get
        );
    }

    public FrontendChainReductionFacade(
            @NotNull FrontendAnalysisData analysisData,
            @NotNull FrontendAstSideTable<Scope> scopesByAst,
            @NotNull Supplier<ResolveRestriction> restrictionSupplier,
            @NotNull BooleanSupplier staticContextSupplier,
            @NotNull Supplier<FrontendPropertyInitializerSupport.PropertyInitializerContext>
                    propertyInitializerContextSupplier,
            @NotNull ClassRegistry classRegistry,
            @NotNull FrontendChainReductionHelper.ExpressionTypeResolver expressionTypeResolver,
            @NotNull Function<IdentifierExpression, FrontendBinding> bindingLookup
    ) {
        this.analysisData = Objects.requireNonNull(analysisData, "analysisData must not be null");
        this.scopesByAst = Objects.requireNonNull(scopesByAst, "scopesByAst must not be null");
        this.restrictionSupplier = Objects.requireNonNull(restrictionSupplier, "restrictionSupplier must not be null");
        this.staticContextSupplier = Objects.requireNonNull(staticContextSupplier, "staticContextSupplier must not be null");
        this.propertyInitializerContextSupplier = Objects.requireNonNull(
                propertyInitializerContextSupplier,
                "propertyInitializerContextSupplier must not be null"
        );
        this.classRegistry = Objects.requireNonNull(classRegistry, "classRegistry must not be null");
        this.expressionTypeResolver = Objects.requireNonNull(
                expressionTypeResolver,
                "expressionTypeResolver must not be null"
        );
        this.bindingLookup = Objects.requireNonNull(bindingLookup, "bindingLookup must not be null");
    }

    public @NotNull FrontendChainHeadReceiverSupport headReceiverSupport() {
        return new FrontendChainHeadReceiverSupport(
                analysisData,
                scopesByAst,
                bindingLookup,
                restrictionSupplier.get(),
                staticContextSupplier.getAsBoolean(),
                propertyInitializerContextSupplier.get(),
                attributeExpression -> {
                    var nestedReduction = reduce(attributeExpression);
                    return nestedReduction.result() != null ? nestedReduction.result().finalReceiver() : null;
                },
                expression -> FrontendChainStatusBridge.toReceiverState(expressionTypeResolver.resolve(expression, false))
        );
    }

    public @NotNull CachedReduction reduce(@NotNull AttributeExpression attributeExpression) {
        var attribute = Objects.requireNonNull(attributeExpression, "attributeExpression must not be null");
        var cached = reducedChains.get(attribute);
        if (cached != null) {
            return new CachedReduction(cached.orElse(null), false);
        }

        var headReceiver = headReceiverSupport().resolveHeadReceiver(attribute.base());
        if (headReceiver == null) {
            reducedChains.put(attribute, Optional.empty());
            return new CachedReduction(null, true);
        }

        var result = FrontendChainReductionHelper.reduce(new FrontendChainReductionHelper.ReductionRequest(
                attribute,
                headReceiver,
                analysisData,
                classRegistry,
                propertyInitializerContextSupplier.get(),
                expressionTypeResolver,
                _ -> {
                },
                bindingLookup
        ));
        reducedChains.put(attribute, Optional.of(result));
        return new CachedReduction(result, true);
    }
}
