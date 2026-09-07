package gd.script.gdcc.frontend.sema.analyzer.support;

import gd.script.gdcc.frontend.diagnostic.DiagnosticManager;
import gd.script.gdcc.frontend.parse.FrontendModule;
import gd.script.gdcc.frontend.parse.GdScriptParserService;
import gd.script.gdcc.frontend.sema.FrontendAnalysisData;
import gd.script.gdcc.frontend.sema.FrontendExpressionTypeStatus;
import gd.script.gdcc.frontend.sema.analyzer.FrontendSemanticAnalyzer;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionApiLoader;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.scope.ResolveRestriction;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdNodePathType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import dev.superice.gdparser.frontend.ast.AssignmentExpression;
import dev.superice.gdparser.frontend.ast.ExpressionStatement;
import dev.superice.gdparser.frontend.ast.FunctionDeclaration;
import dev.superice.gdparser.frontend.ast.IdentifierExpression;
import dev.superice.gdparser.frontend.ast.Node;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrontendAssignmentSemanticSupportTest {
    @Test
    void resolveAssignmentExpressionTypePublishesResolvedVoidForSupportedTargets() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_positive.gd",
                """
                        class_name AssignmentSemanticSupportPositive
                        extends RefCounted
                        
                        var hp: int = 0
                        
                        class Holder:
                            var values: Array[int] = []
                        
                        func ping(values: Array[int], holder: Holder):
                            hp = 1
                            self.hp = 2
                            values[0] = 3
                            holder.values[0] = 4
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var assignments = List.of(
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression()
                ),
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
                ),
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
                ),
                assertInstanceOf(
                        AssignmentExpression.class,
                        assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
                )
        );

        for (var assignment : assignments) {
            var result = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                    support,
                    assignment,
                    FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                    publishedResolver,
                    false
            );
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals(GdVoidType.VOID, result.expressionType().publishedType());
        }
    }

    @Test
    void resolveAssignmentExpressionTypeTreatsBuiltinMemberBackedPropertiesAsWritableTargets() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_builtin_member_property.gd",
                """
                        class_name AssignmentSemanticSupportBuiltinMemberProperty
                        extends RefCounted
                        
                        func ping(vector: Vector3, color: Color):
                            vector.x = 1.0
                            color.a = 0.5
                            vector.x = ""
                            vector.missing = 1.0
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        for (var successIndex : List.of(0, 1)) {
            var result = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                    support,
                    assignments.get(successIndex),
                    FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                    publishedResolver,
                    false
            );
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals(GdVoidType.VOID, result.expressionType().publishedType());
        }

        var typeMismatch = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(2),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(typeMismatch.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, typeMismatch.expressionType().status());
        assertTrue(typeMismatch.expressionType().detailReason().contains("not assignable"));
        assertTrue(typeMismatch.expressionType().detailReason().contains("float"));

        var missingMember = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(3),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.FAILED, missingMember.expressionType().status());
        assertTrue(missingMember.expressionType().detailReason().contains("missing"));
        assertTrue(missingMember.expressionType().detailReason().contains("Vector3"));
    }

    @Test
    void resolveAssignmentExpressionTypeRejectsIllegalTargetsAndValueRequiredUsage() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_negative.gd",
                """
                        class_name AssignmentSemanticSupportNegative
                        extends RefCounted
                        
                        signal pinged
                        
                        class Worker:
                            static func build() -> int:
                                return 1
                        
                        func helper() -> int:
                            return 1
                        
                        func ping(values: Array[int]):
                            pinged = 1
                            self.pinged = 1
                            Worker = 1
                            helper = 1
                            values[0] = ""
                            values[0] = 1
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bareSignalAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression()
        );
        var signalAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );
        var typeMetaAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(2)).expression()
        );
        var callableAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(3)).expression()
        );
        var assignabilityFailure = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(4)).expression()
        );
        var valueRequiredAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(5)).expression()
        );

        var bareSignalResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                bareSignalAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(bareSignalResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, bareSignalResult.expressionType().status());
        assertEquals(
                "Signal 'pinged' is read-only and cannot be assigned",
                bareSignalResult.expressionType().detailReason()
        );
        assertInstanceOf(IdentifierExpression.class, bareSignalAssignment.left());

        var signalResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                signalAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(signalResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, signalResult.expressionType().status());
        assertTrue(signalResult.expressionType().detailReason().contains("Signal"));

        var typeMetaResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                typeMetaAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(typeMetaResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, typeMetaResult.expressionType().status());
        assertTrue(typeMetaResult.expressionType().detailReason().contains("Type-meta"));

        var callableResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                callableAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(callableResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, callableResult.expressionType().status());
        assertTrue(callableResult.expressionType().detailReason().contains("cannot be assigned"));

        var assignabilityResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignabilityFailure,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(assignabilityResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, assignabilityResult.expressionType().status());
        assertTrue(assignabilityResult.expressionType().detailReason().contains("not assignable"));

        var valueRequiredResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                valueRequiredAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED,
                publishedResolver,
                false
        );
        assertTrue(valueRequiredResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, valueRequiredResult.expressionType().status());
        assertTrue(valueRequiredResult.expressionType().detailReason().contains("ordinary value"));
    }

    @Test
    void resolveAssignmentExpressionTypeSupportsClosedCompoundOperatorSetButKeepsValueAndWritebackChecks()
            throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_compound.gd",
                """
                        class_name AssignmentSemanticSupportCompound
                        extends RefCounted
                        
                        var hp: int = 0
                        var payload
                        
                        func ping(values: Array[int], raw_array: Array):
                            hp += 1
                            self.hp -= 1
                            values[0] |= 1
                            payload += 1
                            values += raw_array
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var assignments = findNodes(pingFunction, AssignmentExpression.class, _ -> true);

        for (var successfulAssignment : assignments.subList(0, 4)) {
            var result = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                    support,
                    successfulAssignment,
                    FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                    publishedResolver,
                    false
            );
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, result.expressionType().status());
            assertEquals(GdVoidType.VOID, result.expressionType().publishedType());
        }

        var assignabilityFailure = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(4),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertTrue(assignabilityFailure.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, assignabilityFailure.expressionType().status());
        assertTrue(assignabilityFailure.expressionType().detailReason().contains("not assignable"));
        assertTrue(assignabilityFailure.expressionType().detailReason().contains("Array[int]"));

        var valueRequiredResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.getFirst(),
                FrontendAssignmentSemanticSupport.AssignmentUsage.VALUE_REQUIRED,
                publishedResolver,
                false
        );
        assertTrue(valueRequiredResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, valueRequiredResult.expressionType().status());
        assertTrue(valueRequiredResult.expressionType().detailReason().contains("ordinary value"));
    }

    @Test
    void resolveAssignmentExpressionTypeSupportsStringFormatCompoundAssignment() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_string_format_compound.gd",
                """
                        class_name AssignmentSemanticSupportStringFormatCompound
                        extends RefCounted
                        
                        var label: String = "hp=%s"
                        var hp: int = 0
                        
                        func ping(args: Array):
                            label %= args
                            hp %= 2
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        // `String % Array` resolves through metadata and the String -> String boundary is accepted.
        // The array-literal right-operand form is covered by the lowering MODULE test instead.
        // Success outcomes of member-target assignments are owned by the writeback sub-route, so
        // only the status/published-type contract is asserted (matching the convention above).
        var stringFormatResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, stringFormatResult.expressionType().status());
        assertEquals(GdVoidType.VOID, stringFormatResult.expressionType().publishedType());

        // Numeric modulo compound assignment keeps its existing behavior.
        var numericResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        assertEquals(FrontendExpressionTypeStatus.RESOLVED, numericResult.expressionType().status());
        assertEquals(GdVoidType.VOID, numericResult.expressionType().publishedType());
    }

    @Test
    void resolveAssignmentExpressionTypeKeepsUnknownCompoundOperatorsFailClosed() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_unknown_compound.gd",
                """
                        class_name AssignmentSemanticSupportUnknownCompound
                        extends RefCounted
                        
                        var hp: int = 0
                        
                        func ping():
                            hp += 1
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var compoundAssignment = findNode(analyzed.ast(), AssignmentExpression.class, assignment -> "+=".equals(assignment.operator()));
        var syntheticUnknownOperator = new AssignmentExpression(
                "??=",
                compoundAssignment.left(),
                compoundAssignment.right(),
                compoundAssignment.range()
        );

        var result = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                syntheticUnknownOperator,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        assertTrue(result.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.UNSUPPORTED, result.expressionType().status());
        assertTrue(result.expressionType().detailReason().contains("Compound assignment operator '??='"));
    }

    @Test
    void resolveAssignmentExpressionTypeUsesSharedReadonlyPropertyContractForBareAndAttributeRoutes() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_readonly_property.gd",
                """
                        class_name AssignmentSemanticSupportReadonlyProperty
                        extends ReadonlyBase
                        
                        func ping():
                            locked = 1
                            self.locked = 1
                        """,
                registryWithReadonlyEngineBase()
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var pingFunction = findFunction(analyzed.ast(), "ping");
        var bareAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(0)).expression()
        );
        var attributeAssignment = assertInstanceOf(
                AssignmentExpression.class,
                assertInstanceOf(ExpressionStatement.class, pingFunction.body().statements().get(1)).expression()
        );

        var bareResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                bareAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var attributeResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                attributeAssignment,
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        assertTrue(bareResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, bareResult.expressionType().status());
        assertEquals("Property 'locked' is not writable", bareResult.expressionType().detailReason());

        assertTrue(attributeResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, attributeResult.expressionType().status());
        assertEquals("Property 'locked' is not writable", attributeResult.expressionType().detailReason());
    }

    @Test
    void checkAssignmentCompatiblePublishesFrontendWidePublicContract() throws Exception {
        var support = createSupport(
                analyze(
                        "assignment_semantic_support_public_gate.gd",
                        """
                                class_name AssignmentSemanticSupportPublicGate
                                extends RefCounted
                                
                                func ping():
                                    pass
                                """
                ),
                ResolveRestriction.instanceContext(),
                false
        );

        assertTrue(FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdVariantType.VARIANT,
                GdIntType.INT
        ));
        assertTrue(FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdIntType.INT,
                GdVariantType.VARIANT
        ));
        assertTrue(FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdObjectType.OBJECT,
                GdNilType.NIL
        ));
        assertTrue(!FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdIntType.INT,
                GdFloatType.FLOAT
        ));
        assertTrue(FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdFloatType.FLOAT,
                GdIntType.INT
        ));
        assertTrue(FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdStringNameType.STRING_NAME,
                GdStringType.STRING
        ));
        assertTrue(FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdStringType.STRING,
                GdStringNameType.STRING_NAME
        ));
        assertFalse(FrontendAssignmentSemanticSupport.checkAssignmentCompatible(
                support,
                GdNodePathType.NODE_PATH,
                GdStringType.STRING
        ));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsVariantAndDynamicTargetsButKeepsImplicitConversionsStrict()
            throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_variant_dynamic.gd",
                """
                        class_name AssignmentSemanticSupportVariantDynamic
                        extends RefCounted
                        
                        var field
                        
                        func ping(dynamic_value):
                            var payload
                            var ratio: float = 0.0
                            payload = 1
                            field = 2
                            self.field = 3
                            dynamic_value[0] = 4
                            ratio = 1
                            var count: int = 0
                            count = 1.0
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var localVariantResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var bareVariantPropertyResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var attributeVariantPropertyResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(2),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var dynamicTargetResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(3),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var primitiveCastResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(4),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var reversePrimitiveCastFailure = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(5),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        for (var successfulResult : List.of(
                localVariantResult,
                bareVariantPropertyResult,
                attributeVariantPropertyResult,
                dynamicTargetResult,
                primitiveCastResult
        )) {
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, successfulResult.expressionType().status());
            assertEquals(GdVoidType.VOID, successfulResult.expressionType().publishedType());
        }

        assertTrue(reversePrimitiveCastFailure.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, reversePrimitiveCastFailure.expressionType().status());
        assertTrue(reversePrimitiveCastFailure.expressionType().detailReason().contains("not assignable"));
        assertTrue(reversePrimitiveCastFailure.expressionType().detailReason().contains("int"));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsOnlySameDimensionVectoriToVectorWidening() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_vector_widening.gd",
                """
                        class_name AssignmentSemanticSupportVectorWidening
                        extends RefCounted
                        
                        func ping() -> void:
                            var vector: Vector3 = Vector3(0.0, 0.0, 0.0)
                            var vector_i: Vector3i = Vector3i(0, 0, 0)
                            var vector2: Vector2 = Vector2(0.0, 0.0)
                            vector = Vector3i(1, 2, 3)
                            vector_i = Vector3(1.0, 2.0, 3.0)
                            vector2 = Vector3i(1, 2, 3)
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var wideningResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var reverseResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var wrongDimensionResult = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(2),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, wideningResult.expressionType().status());
        assertEquals(GdVoidType.VOID, wideningResult.expressionType().publishedType());
        assertTrue(reverseResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, reverseResult.expressionType().status());
        assertTrue(reverseResult.expressionType().detailReason().contains("Vector3"));
        assertTrue(reverseResult.expressionType().detailReason().contains("Vector3i"));
        assertTrue(wrongDimensionResult.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, wrongDimensionResult.expressionType().status());
        assertTrue(wrongDimensionResult.expressionType().detailReason().contains("Vector3i"));
        assertTrue(wrongDimensionResult.expressionType().detailReason().contains("Vector2"));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsStringFamilyConstructorBoundaries() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_string_family.gd",
                """
                        class_name AssignmentSemanticSupportStringFamily
                        extends RefCounted

                        var text: String = ""
                        var name: StringName = &""
                        var count: int = 0

                        func ping() -> void:
                            name = text
                            text = name
                            count = text
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var stringToStringName = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var stringNameToString = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var unrelatedScalarMismatch = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(2),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        for (var successfulResult : List.of(stringToStringName, stringNameToString)) {
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, successfulResult.expressionType().status());
            assertEquals(GdVoidType.VOID, successfulResult.expressionType().publishedType());
        }

        assertTrue(unrelatedScalarMismatch.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, unrelatedScalarMismatch.expressionType().status());
        assertTrue(unrelatedScalarMismatch.expressionType().detailReason().contains("String"));
        assertTrue(unrelatedScalarMismatch.expressionType().detailReason().contains("int"));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsStableVariantSourcesForConcreteTargets() throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_variant_source.gd",
                """
                        class_name AssignmentSemanticSupportVariantSource
                        extends RefCounted
                        
                        func ping(any_value: Variant, dynamic_value):
                            var int_slot: int = 0
                            int_slot = any_value
                            int_slot = dynamic_value.ping().length
                            int_slot = "x"
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var exactVariantSource = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var dynamicVariantSource = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var strictMismatch = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(2),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        for (var successfulResult : List.of(exactVariantSource, dynamicVariantSource)) {
            assertEquals(FrontendExpressionTypeStatus.RESOLVED, successfulResult.expressionType().status());
            assertEquals(GdVoidType.VOID, successfulResult.expressionType().publishedType());
        }

        assertTrue(strictMismatch.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, strictMismatch.expressionType().status());
        assertTrue(strictMismatch.expressionType().detailReason().contains("String"));
        assertTrue(strictMismatch.expressionType().detailReason().contains("int"));
    }

    @Test
    void resolveAssignmentExpressionTypeAcceptsNullSourcesForObjectTargetsButKeepsNilToScalarRejected()
            throws Exception {
        var analyzed = analyze(
                "assignment_semantic_support_null_object_source.gd",
                """
                        class_name AssignmentSemanticSupportNullObjectSource
                        extends RefCounted
                        
                        var payload_obj: Object
                        var payload_i: int = 0
                        
                        func ping() -> void:
                            payload_obj = null
                            payload_i = null
                        """
        );

        var support = createSupport(analyzed, ResolveRestriction.instanceContext(), false);
        var publishedResolver = publishedExpressionResolver(analyzed);
        var assignments = findNodes(findFunction(analyzed.ast(), "ping"), AssignmentExpression.class, _ -> true);

        var objectAssignment = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(0),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );
        var scalarMismatch = FrontendAssignmentSemanticSupport.resolveAssignmentExpressionType(
                support,
                assignments.get(1),
                FrontendAssignmentSemanticSupport.AssignmentUsage.STATEMENT_ROOT,
                publishedResolver,
                false
        );

        assertEquals(FrontendExpressionTypeStatus.RESOLVED, objectAssignment.expressionType().status());
        assertEquals(GdVoidType.VOID, objectAssignment.expressionType().publishedType());
        assertTrue(scalarMismatch.rootOwnsOutcome());
        assertEquals(FrontendExpressionTypeStatus.FAILED, scalarMismatch.expressionType().status());
        assertTrue(scalarMismatch.expressionType().detailReason().contains("Nil"));
        assertTrue(scalarMismatch.expressionType().detailReason().contains("int"));
    }

    private @NotNull FrontendAssignmentSemanticSupport.Context createSupport(
            @NotNull AnalyzedScript analyzed,
            @NotNull ResolveRestriction restriction,
            boolean staticContext
    ) {
        var chainReduction = new FrontendChainReductionFacade(
                analyzed.analysisData(),
                analyzed.analysisData().scopesByAst(),
                () -> restriction,
                () -> staticContext,
                () -> null,
                analyzed.classRegistry(),
                (expression, finalizeWindow) -> FrontendChainReductionHelper.ExpressionTypeResult.fromPublished(
                        publishedExpressionResolver(analyzed).resolve(expression, finalizeWindow)
                )
        );
        return FrontendAssignmentSemanticSupport.createContext(
                analyzed.analysisData().symbolBindings(),
                analyzed.analysisData().scopesByAst(),
                analyzed.analysisData().moduleSkeleton(),
                () -> restriction,
                analyzed.classRegistry(),
                chainReduction
        );
    }

    private static @NotNull FrontendExpressionSemanticSupport.NestedExpressionResolver publishedExpressionResolver(
            @NotNull AnalyzedScript analyzed
    ) {
        return (expression, finalizeWindow) -> Objects.requireNonNull(
                analyzed.analysisData().expressionTypes().get(expression),
                "Expected published expression type for " + expression.getClass().getSimpleName()
        );
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source
    ) throws Exception {
        return analyze(fileName, source, new ClassRegistry(ExtensionApiLoader.loadDefault()));
    }

    private static @NotNull AnalyzedScript analyze(
            @NotNull String fileName,
            @NotNull String source,
            @NotNull ClassRegistry registry
    ) throws Exception {
        var diagnostics = new DiagnosticManager();
        var parserService = new GdScriptParserService();
        var unit = parserService.parseUnit(Path.of("tmp", fileName), source, diagnostics);
        var analysisData = new FrontendSemanticAnalyzer().analyze(
                new FrontendModule("test_module", List.of(unit)),
                registry,
                diagnostics
        );
        return new AnalyzedScript(unit.ast(), analysisData, registry);
    }

    private static @NotNull ClassRegistry registryWithReadonlyEngineBase() throws Exception {
        var api = ExtensionApiLoader.loadDefault();
        var classes = new ArrayList<>(api.classes());
        classes.add(new ExtensionGdClass(
                "ReadonlyBase",
                false,
                true,
                "RefCounted",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionGdClass.PropertyInfo("locked", "int", true, false, "0", "get_locked", "set_locked", null)),
                List.of()
        ));
        return new ClassRegistry(new ExtensionAPI(
                api.header(),
                api.builtinClassSizes(),
                api.builtinClassMemberOffsets(),
                api.globalEnums(),
                api.utilityFunctions(),
                api.builtinClasses(),
                List.copyOf(classes),
                api.singletons(),
                api.nativeStructures()
        ));
    }

    private static @NotNull FunctionDeclaration findFunction(@NotNull Node root, @NotNull String name) {
        return findNode(root, FunctionDeclaration.class, function -> function.name().equals(name));
    }

    private static <T extends Node> @NotNull T findNode(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        return findNodes(root, nodeType, predicate).getFirst();
    }

    private static <T extends Node> @NotNull List<T> findNodes(
            @NotNull Node root,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate
    ) {
        var matches = new ArrayList<T>();
        collectNodes(root, nodeType, predicate, matches);
        if (matches.isEmpty()) {
            throw new AssertionError("No node of type " + nodeType.getSimpleName() + " matched the predicate");
        }
        return List.copyOf(matches);
    }

    private static <T extends Node> void collectNodes(
            @NotNull Node node,
            @NotNull Class<T> nodeType,
            @NotNull Predicate<T> predicate,
            @NotNull List<T> matches
    ) {
        if (nodeType.isInstance(node)) {
            var typedNode = nodeType.cast(node);
            if (predicate.test(typedNode)) {
                matches.add(typedNode);
            }
        }
        for (var child : node.getChildren()) {
            collectNodes(child, nodeType, predicate, matches);
        }
    }

    private record AnalyzedScript(
            @NotNull Node ast,
            @NotNull FrontendAnalysisData analysisData,
            @NotNull ClassRegistry classRegistry
    ) {
    }
}
