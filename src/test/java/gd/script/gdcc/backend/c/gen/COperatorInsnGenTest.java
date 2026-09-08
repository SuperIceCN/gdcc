package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotOperator;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.insn.BinaryOpInsn;
import gd.script.gdcc.lir.insn.UnaryOpInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdNilType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class COperatorInsnGenTest {
    @Test
    @DisplayName("primitive compare should use direct C expression when metadata supports")
    void primitiveCompareUsesDirectExpressionWhenMetadataMatches() {
        var body = generateBody(
                primitiveCompareApi(),
                new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = ($left > $right);"), body);
    }

    @Test
    @DisplayName("primitive compare should fail-fast when metadata is missing")
    void primitiveCompareFailsWhenMetadataMissing() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Primitive compare metadata is missing"), ex.getMessage());
    }

    @Test
    @DisplayName("engine object == should compare C1 equality-normalized raw pointers")
    void objectEqualUsesNormalizedRawComparison() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_obj", "right_obj"),
                List.of(
                        new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($left_obj).ptr, $left_obj.instance_id) ? NULL : (GDExtensionObjectPtr)($left_obj).ptr)"),
                body);
        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($right_obj).ptr, $right_obj.instance_id) ? NULL : (GDExtensionObjectPtr)($right_obj).ptr)"),
                body);
        assertTrue(body.contains(" == "), body);
        assertFalse(body.contains(".instance_id =="), body);
        assertFalse(body.contains("godot_object_get_instance_id("), body);
    }

    @Test
    @DisplayName("engine object != should compare C1 equality-normalized raw pointers")
    void objectNotEqualUsesNormalizedRawComparison() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.NOT_EQUAL, "left_obj", "right_obj"),
                List.of(
                        new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($left_obj).ptr, $left_obj.instance_id) ? NULL : (GDExtensionObjectPtr)($left_obj).ptr)"),
                body);
        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($right_obj).ptr, $right_obj.instance_id) ? NULL : (GDExtensionObjectPtr)($right_obj).ptr)"),
                body);
        assertTrue(body.contains(" != "), body);
        assertFalse(body.contains(".instance_id !="), body);
        assertFalse(body.contains("godot_object_get_instance_id("), body);
    }

    @Test
    @DisplayName("GDCC object == should normalize via is_null_raw_and_id and live_object without dead object_ptr")
    void gdccObjectEqualUsesNormalizedLiveObject() {
        var myObject = new LirClassDef("MyObject", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_obj", "right_obj"),
                List.of(
                        new VariableSpec("left_obj", new GdObjectType("MyObject"), false),
                        new VariableSpec("right_obj", new GdObjectType("MyObject"), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                ),
                GdVoidType.VOID,
                List.of(myObject)
        );

        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($left_obj).ptr, $left_obj.instance_id) ? NULL : gdcc_MyObject_fat_ptr_live_object($left_obj))"),
                body);
        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($right_obj).ptr, $right_obj.instance_id) ? NULL : gdcc_MyObject_fat_ptr_live_object($right_obj))"),
                body);
        assertTrue(body.contains(" == "), body);
        assertFalse(body.contains("MyObject_object_ptr"), body);
        assertFalse(body.contains(".instance_id =="), body);
        assertFalse(body.contains("godot_object_get_instance_id("), body);
    }

    @Test
    @DisplayName("mixed GDCC/engine object == should normalize each side with its own live path")
    void mixedGdccEngineObjectEqualUsesPerSideNormalization() {
        var myObject = new LirClassDef("MyObject", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_gdcc", "right_engine"),
                List.of(
                        new VariableSpec("left_gdcc", new GdObjectType("MyObject"), false),
                        new VariableSpec("right_engine", GdObjectType.OBJECT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                ),
                GdVoidType.VOID,
                List.of(myObject)
        );

        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($left_gdcc).ptr, $left_gdcc.instance_id) ? NULL : gdcc_MyObject_fat_ptr_live_object($left_gdcc))"),
                body);
        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($right_engine).ptr, $right_engine.instance_id) ? NULL : (GDExtensionObjectPtr)($right_engine).ptr)"),
                body);
        assertFalse(body.contains("MyObject_object_ptr"), body);
    }

    @Test
    @DisplayName("engine Node == Node should reuse C1 equality-normalized raw pointers")
    void engineNodeEqualUsesNormalizedRawComparison() {
        var body = generateBody(
                engineObjectApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_node", "right_node"),
                List.of(
                        new VariableSpec("left_node", new GdObjectType("Node"), false),
                        new VariableSpec("right_node", new GdObjectType("Node"), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($left_node).ptr, $left_node.instance_id) ? NULL : (GDExtensionObjectPtr)($left_node).ptr)"),
                body);
        assertTrue(body.contains(
                        "(gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($right_node).ptr, $right_node.instance_id) ? NULL : (GDExtensionObjectPtr)($right_node).ptr)"),
                body);
        assertTrue(body.contains(" == "), body);
        assertFalse(body.contains(".instance_id =="), body);
        assertFalse(body.contains("godot_object_get_instance_id("), body);
    }

    @Test
    @DisplayName("object non-==/!= compare should fail-fast")
    void objectNonEqualityCompareFailsFast() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left_obj", "right_obj"),
                        List.of(
                                new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Object comparison supports only == and !="), ex.getMessage());
    }

    @Test
    @DisplayName("Nil == Nil should emit true")
    void nilEqualNilEmitsTrue() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_nil", "right_nil"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("right_nil", GdNilType.NIL, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = (true);"), body);
    }

    @Test
    @DisplayName("Nil != Nil should emit false semantics")
    void nilNotEqualNilEmitsFalseSemantics() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.NOT_EQUAL, "left_nil", "right_nil"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("right_nil", GdNilType.NIL, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = (false);"), body);
    }

    @Test
    @DisplayName("Nil == Object should compare the raw object pointer against NULL")
    void nilEqualObjectUsesNullCompare() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_nil", "obj"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("obj", new GdObjectType("Node"), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id)"), body);
    }

    @Test
    @DisplayName("Object == Nil should use gdcc_object_is_null_raw_and_id")
    void objectEqualNilUsesNullCompare() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "obj", "right_nil"),
                List.of(
                        new VariableSpec("obj", new GdObjectType("Node"), false),
                        new VariableSpec("right_nil", GdNilType.NIL, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id)"), body);
    }

    @Test
    @DisplayName("Object != Nil should negate gdcc_object_is_null_raw_and_id")
    void objectNotEqualNilNegatesNullCompare() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.NOT_EQUAL, "obj", "right_nil"),
                List.of(
                        new VariableSpec("obj", new GdObjectType("Node"), false),
                        new VariableSpec("right_nil", GdNilType.NIL, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("(!gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($obj).ptr, $obj.instance_id))"), body);
    }

    @Test
    @DisplayName("Nil == non-Object should emit false")
    void nilEqualNonObjectEmitsFalse() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left_nil", "value"),
                List.of(
                        new VariableSpec("left_nil", GdNilType.NIL, false),
                        new VariableSpec("value", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = (false);"), body);
    }

    @Test
    @DisplayName("compare result type must be compatible with bool")
    void compareResultTypeMustBeBoolAssignable() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.EQUAL, "left_obj", "right_obj"),
                        List.of(
                                new VariableSpec("left_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("right_obj", GdObjectType.OBJECT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Operator result type 'bool' is not assignable"), ex.getMessage());
    }

    @Test
    @DisplayName("result ref variable should fail-fast")
    void resultRefVariableFailsFast() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        primitiveCompareApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, true)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("cannot be a reference"), ex.getMessage());
    }

    @Test
    @DisplayName("unary metadata should match with normalized empty rightType and emit evaluator call")
    void unaryMetadataWithEmptyRightTypeEmitsEvaluatorCall() {
        var body = generateBody(
                evaluatorIntApi(),
                new UnaryOpInsn("result", GodotOperator.NEGATE, "operand"),
                List.of(
                        new VariableSpec("operand", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_unary_negate_int_to_int($operand);"), body);
    }

    @Test
    @DisplayName("binary non-compare builtin should emit ptr evaluator call")
    void binaryNonCompareBuiltinEmitsEvaluatorCall() {
        var body = generateBody(
                evaluatorIntApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_binary_in_int_int_to_bool($left, $right);"), body);
    }

    @Test
    @DisplayName("binary metadata lookup should skip malformed entries and keep valid match")
    void binaryMetadataLookupSkipsMalformedEntries() {
        var body = generateBody(
                malformedEvaluatorIntApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_binary_in_int_int_to_bool($left, $right);"), body);
    }

    @Test
    @DisplayName("evaluator path should fail-fast when semantic result type is incompatible")
    void evaluatorPathFailsWhenResultTypeIncompatible() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        evaluatorIntApi(),
                        new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Operator result type 'bool' is not assignable"), ex.getMessage());
    }

    @Test
    @DisplayName("operators should fail-fast when original-order metadata is missing")
    void missingOriginalMetadataFailsWithoutSwapFallback() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        dualFallbackApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, GREATER, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("non-commutative operators should fail-fast without swap fallback")
    void nonRegisteredOperatorDoesNotSwapFallback() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        nonSwappableFallbackCandidateApi(),
                        new BinaryOpInsn("result", GodotOperator.SUBTRACT, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, SUBTRACT, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("IN should fail-fast when original-order metadata is missing")
    void inOperatorShouldNotUseSwapFallback() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        inFallbackCandidateApi(),
                        new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, IN, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("comparison operators should fail-fast when original-order metadata is missing")
    void registeredFallbackOperatorFailsWhenBothDirectionsMiss() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.GREATER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdStringType.STRING, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (String, GREATER, int)"), ex.getMessage());
    }

    @Test
    @DisplayName("binary op should use variant_evaluate when any operand is Variant")
    void variantOperandForcesVariantEvaluatePath() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_ADD"), body);
        assertTrue(body.contains("&$left"), body);
        assertTrue(body.contains("godot_new_Variant_with_int($right)"), body);
        assertTrue(body.matches("(?s).*godot_Variant __gdcc_tmp_op_eval_result_\\d+;.*"), body);
        assertFalse(body.matches("(?s).*godot_Variant __gdcc_tmp_op_eval_result_\\d+\\s*=.*"), body);
        assertTrue(body.contains("$result = godot_new_Variant_with_Variant(&__gdcc_tmp_op_eval_result_"), body);
        assertFalse(
                body.matches("(?s).*godot_Variant __gdcc_tmp_variant_\\d+ = godot_new_Variant_with_Variant\\(&__gdcc_tmp_op_eval_result_\\d+\\);.*"),
                body
        );
        assertTrue(body.contains("GDCC_PRINT_RUNTIME_ERROR(\"godot_variant_evaluate failed for operator 'ADD'\""), body);
        assertTrue(body.contains("if (!__gdcc_tmp_op_eval_valid_"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertFalse(body.contains("gdcc_eval_binary_"), body);
    }

    @Test
    @DisplayName("variant_evaluate path should unpack to non-Variant builtin result with runtime type check")
    void variantEvaluatePathUnpacksToBuiltinResultWithRuntimeTypeCheck() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_IN"), body);
        assertTrue(body.contains("gdcc_check_variant_type_builtin(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_BOOL"), body);
        assertTrue(body.contains("$result = godot_new_bool_with_Variant(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("variant_evaluate type check failed for operator 'IN': expected bool"), body);
    }

    @Test
    @DisplayName("variant_evaluate path should reject compiler-only result target")
    void variantEvaluatePathRejectsCompilerOnlyResultTarget() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdVariantType.VARIANT, false),
                                new VariableSpec("right", GdVariantType.VARIANT, false),
                                new VariableSpec("result", GdccForRangeIterType.FOR_RANGE_ITER, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("compiler-only type leaked into operator result target variable 'result'"), ex.getMessage());
    }

    @Test
    @DisplayName("variant_evaluate path should allow engine object subclass check before unpack")
    void variantEvaluatePathSupportsEngineObjectSubtypeCheck() {
        var body = generateBody(
                engineObjectApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdVariantType.VARIANT, false),
                        new VariableSpec("result", new GdObjectType("Node"), false)
                )
        );

        assertTrue(body.contains("gdcc_check_variant_type_object(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"Node\")"), body);
        assertTrue(body.contains(", true)"), body);
        assertFalse(body.contains(", false) || gdcc_check_variant_type_object("), body);
        assertTrue(body.contains("gdcc_Node_fat_ptr_from_variant(&__gdcc_tmp_op_eval_result_"), body);
    }

    @Test
    @DisplayName("variant_evaluate path should allow GDCC object subclass check while keeping canonical expected name")
    void variantEvaluatePathSupportsGdccObjectSubtypeCheck() {
        var sharedClass = new LirClassDef(
                "RuntimeOuter__sub__Shared",
                "RefCounted",
                false,
                false,
                Map.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdVariantType.VARIANT, false),
                        new VariableSpec("result", new GdObjectType("RuntimeOuter__sub__Shared"), false)
                ),
                GdVoidType.VOID,
                List.of(sharedClass)
        );

        assertTrue(body.contains("gdcc_check_variant_type_object(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"RuntimeOuter__sub__Shared\")"), body);
        assertTrue(body.contains(", true)"), body);
        assertFalse(body.contains(", false) || gdcc_check_variant_type_object("), body);
        assertTrue(body.contains("_fat_ptr_from_variant(&__gdcc_tmp_op_eval_result_"), body);
    }

    @Test
    @DisplayName("variant operand should still use variant_evaluate even when metadata exists")
    void variantOperandStillUsesVariantEvaluateWhenMetadataExists() {
        var body = generateBody(
                evaluatorIntApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_IN"), body);
        assertFalse(body.contains("gdcc_eval_binary_in_int_int_to_bool"), body);
    }

    @Test
    @DisplayName("variant == Nil should materialize Nil with dedicated nil ctor")
    void variantEqualNilUsesDedicatedNilVariantCtor() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left", "right_nil"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right_nil", GdNilType.NIL, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL"), body);
        assertTrue(body.contains("godot_new_Variant_nil()"), body);
        assertFalse(body.contains("godot_new_Variant_with_Nil"), body);
    }

    @Test
    @DisplayName("variant_evaluate failure should return function default value for non-void function")
    void variantEvaluateFailureReturnsFunctionDefaultValue() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdVariantType.VARIANT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdVariantType.VARIANT, false)
                ),
                GdBoolType.BOOL,
                List.of()
        );

        assertTrue(body.contains("GDCC_PRINT_RUNTIME_ERROR(\"godot_variant_evaluate failed for operator 'ADD'\""), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
    }

    @Test
    @DisplayName("POWER(float,int) should use pow fast path")
    void powerFloatIntUsesPowFastPath() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.POWER, "left", "right"),
                List.of(
                        new VariableSpec("left", GdFloatType.FLOAT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdFloatType.FLOAT, false)
                )
        );

        assertTrue(body.contains("$result = pow($left, $right);"), body);
    }

    @Test
    @DisplayName("POWER(int,int) should use pow_int fast path")
    void powerIntIntUsesPowIntFastPath() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.POWER, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                )
        );

        assertTrue(body.contains("if (false) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'POWER': no guard violation"), body);
        assertTrue(body.contains("$result = pow_int($left, $right);"), body);
    }

    @Test
    @DisplayName("MODULE(float,float) should hit primitive fast path with fmod")
    void moduleFloatFloatUsesFmodFastPath() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.MODULE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdFloatType.FLOAT, false),
                        new VariableSpec("right", GdFloatType.FLOAT, false),
                        new VariableSpec("result", GdFloatType.FLOAT, false)
                )
        );

        assertTrue(body.contains("if (gdcc_float_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'MODULE': floating modulo by zero"), body);
        assertTrue(body.contains("$result = fmod($left, $right);"), body);
    }

    @Test
    @DisplayName("ADD(int,int) fast path should emit no-op guard without overflow check")
    void addIntIntFastPathEmitsNoopGuardWithoutOverflowCheck() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.ADD, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL,
                List.of()
        );

        assertTrue(body.contains("if (false) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'ADD': no guard violation"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left + $right);"), body);
    }

    @Test
    @DisplayName("DIVIDE(int,int) fast path should emit zero-divisor guard and return function default value")
    void divideIntIntFastPathEmitsZeroDivisorGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.DIVIDE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL,
                List.of()
        );

        assertTrue(body.contains("if (gdcc_int_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'DIVIDE': integer division by zero"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left / $right);"), body);
    }

    @Test
    @DisplayName("MODULE(int,int) fast path should emit zero-divisor guard and return function default value")
    void moduleIntIntFastPathEmitsZeroDivisorGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.MODULE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL,
                List.of()
        );

        assertTrue(body.contains("if (gdcc_int_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'MODULE': integer modulo by zero"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left % $right);"), body);
    }

    @Test
    @DisplayName("SHIFT_LEFT(int,int) fast path should emit invalid-shift guard and return function default value")
    void shiftLeftFastPathEmitsInvalidShiftGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.SHIFT_LEFT, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL,
                List.of()
        );

        assertTrue(body.contains("if (gdcc_int_shift_left_invalid($left, $right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'SHIFT_LEFT': invalid shift amount or negative left operand"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left << $right);"), body);
    }

    @Test
    @DisplayName("SHIFT_RIGHT(int,int) fast path should emit invalid-shift guard and return function default value")
    void shiftRightFastPathEmitsInvalidShiftGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.SHIFT_RIGHT, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdIntType.INT, false)
                ),
                GdBoolType.BOOL,
                List.of()
        );

        assertTrue(body.contains("if (gdcc_int_shift_right_invalid($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'SHIFT_RIGHT': invalid shift amount"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left >> $right);"), body);
    }

    @Test
    @DisplayName("primitive fast path should still fail-fast when metadata is missing")
    void primitiveFastPathFailsWhenMetadataMissing() {
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        emptyApi(),
                        new BinaryOpInsn("result", GodotOperator.POWER, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", GdIntType.INT, false),
                                new VariableSpec("result", GdIntType.INT, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Primitive fast path metadata is missing"), ex.getMessage());
    }

    @Test
    @DisplayName("XOR(int,int) should use logical xor semantics instead of bit xor")
    void xorIntIntUsesLogicalXorSemantics() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.XOR, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", GdIntType.INT, false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("if (false) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'XOR': no guard violation"), body);
        assertTrue(body.contains("$result = (($left ? 1 : 0) != ($right ? 1 : 0));"), body);
    }

    @Test
    @DisplayName("DIVIDE(float,float) fast path should emit float zero-divisor guard")
    void divideFloatFloatFastPathEmitsZeroDivisorGuard() {
        var body = generateBody(
                primitiveFastPathApi(),
                new BinaryOpInsn("result", GodotOperator.DIVIDE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdFloatType.FLOAT, false),
                        new VariableSpec("right", GdFloatType.FLOAT, false),
                        new VariableSpec("result", GdFloatType.FLOAT, false)
                ),
                GdBoolType.BOOL,
                List.of()
        );

        assertTrue(body.contains("if (gdcc_float_division_by_zero($right)) {"), body);
        assertTrue(body.contains("Primitive fast path guard failed for operator 'DIVIDE': floating division by zero"), body);
        assertTrue(body.contains("_return_val = false;"), body);
        assertTrue(body.contains("goto __finally__;"), body);
        assertTrue(body.contains("$result = ($left / $right);"), body);
    }

    @Test
    @DisplayName("IN(int, Array) should bypass primitive fast path and resolve by metadata in original order")
    void inIntArrayBypassesPrimitiveFastPathAndUsesEvaluator() {
        var body = generateBody(
                inIntArrayApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_binary_in_int_array_to_bool($left, &$right);"), body);
        assertFalse(body.contains("godot_variant_evaluate"), body);
        assertFalse(body.contains("pow("), body);
        assertFalse(body.contains("pow_int("), body);
    }

    @Test
    @DisplayName("IN(int, Array[int]) should match the plain Array metadata entry")
    void inIntTypedArrayMatchesPlainArrayMetadata() {
        var body = generateBody(
                typedContainerOperatorApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdIntType.INT, false),
                        new VariableSpec("right", new GdArrayType(GdIntType.INT), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("gdcc_eval_binary_in_int_array_int_to_bool($left, &$right)"), body);
        assertFalse(body.contains("godot_variant_evaluate"), body);
    }

    @Test
    @DisplayName("IN(String, Dictionary[String, int]) should match the plain Dictionary metadata entry")
    void inStringTypedDictionaryMatchesPlainDictionaryMetadata() {
        var body = generateBody(
                typedContainerOperatorApi(),
                new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                List.of(
                        new VariableSpec("left", GdStringType.STRING, false),
                        new VariableSpec("right", new GdDictionaryType(GdStringType.STRING, GdIntType.INT), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("gdcc_eval_binary_in_string_dictionary_string_int_to_bool(&$left, &$right)"), body);
        assertFalse(body.contains("godot_variant_evaluate"), body);
    }

    @Test
    @DisplayName("Array[int] == Array[int] should resolve the plain Array owner class metadata")
    void typedArrayEqualityResolvesPlainArrayOwnerMetadata() {
        var body = generateBody(
                typedContainerOperatorApi(),
                new BinaryOpInsn("result", GodotOperator.EQUAL, "left", "right"),
                List.of(
                        new VariableSpec("left", new GdArrayType(GdIntType.INT), false),
                        new VariableSpec("right", new GdArrayType(GdIntType.INT), false),
                        new VariableSpec("result", GdBoolType.BOOL, false)
                )
        );

        assertTrue(body.contains("gdcc_eval_binary_equal_array_int_array_int_to_bool(&$left, &$right)"), body);
        assertFalse(body.contains("godot_variant_evaluate"), body);
    }

    @Test
    @DisplayName("container normalization should still fail-fast when the plain metadata entry is missing")
    void typedContainerNormalizationFailsWhenPlainMetadataMissing() {
        // The int owner class exists (only `in Array`), so the lookup reaches the normalized
        // rhs match: `Dictionary[String, int]` -> `Dictionary`, whose plain entry is absent.
        // This anchors that normalization never invents a match for genuinely missing entries.
        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(
                        typedContainerOperatorApi(),
                        new BinaryOpInsn("result", GodotOperator.IN, "left", "right"),
                        List.of(
                                new VariableSpec("left", GdIntType.INT, false),
                                new VariableSpec("right", new GdDictionaryType(GdStringType.STRING, GdIntType.INT), false),
                                new VariableSpec("result", GdBoolType.BOOL, false)
                        )
                )
        );

        assertInstanceOf(InvalidInsnException.class, ex);
        assertTrue(ex.getMessage().contains("Binary operator metadata is missing for signature (int, IN, Dictionary[String, int])"), ex.getMessage());
    }

    @Test
    @DisplayName("MODULE(String, Array) should resolve the builtin evaluator helper for string formatting")
    void moduleStringArrayUsesBuiltinEvaluator() {
        var body = generateBody(
                stringFormatApi(),
                new BinaryOpInsn("result", GodotOperator.MODULE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdStringType.STRING, false),
                        new VariableSpec("right", new GdArrayType(GdVariantType.VARIANT), false),
                        new VariableSpec("result", GdStringType.STRING, false)
                )
        );

        assertTrue(body.contains("$result = gdcc_eval_binary_module_string_array_to_string(&$left, &$right);"), body);
        assertFalse(body.contains("godot_variant_evaluate"), body);
    }

    @Test
    @DisplayName("MODULE(String, Array[int]) should keep the sanitized typed helper name and normalized metadata hit")
    void moduleStringTypedArrayKeepsSanitizedHelperName() {
        var body = generateBody(
                stringFormatApi(),
                new BinaryOpInsn("result", GodotOperator.MODULE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdStringType.STRING, false),
                        new VariableSpec("right", new GdArrayType(GdIntType.INT), false),
                        new VariableSpec("result", GdStringType.STRING, false)
                )
        );

        // The helper name is sanitized from `getTypeName()` while metadata matching normalizes
        // `Array[int]` to the plain `Array` entry. `godot_TypedArray(T)` is an alias of
        // `godot_Array`, so passing the typed array pointer to this evaluator is ABI-safe.
        assertTrue(body.contains("$result = gdcc_eval_binary_module_string_array_int_to_string(&$left, &$right);"), body);
        assertFalse(body.contains("godot_variant_evaluate"), body);
    }

    @Test
    @DisplayName("MODULE(String, Variant) should route through variant_evaluate and unpack to String with runtime type check")
    void moduleStringVariantUsesVariantEvaluateWithUnpackTypeCheck() {
        var body = generateBody(
                emptyApi(),
                new BinaryOpInsn("result", GodotOperator.MODULE, "left", "right"),
                List.of(
                        new VariableSpec("left", GdStringType.STRING, false),
                        new VariableSpec("right", GdVariantType.VARIANT, false),
                        new VariableSpec("result", GdStringType.STRING, false)
                )
        );

        assertTrue(body.contains("godot_variant_evaluate(GDEXTENSION_VARIANT_OP_MODULE"), body);
        assertTrue(body.contains("gdcc_check_variant_type_builtin(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING"), body);
        assertTrue(body.contains("$result = godot_new_String_with_Variant(&__gdcc_tmp_op_eval_result_"), body);
        assertTrue(body.contains("variant_evaluate type check failed for operator 'MODULE': expected String"), body);
    }

    private @NotNull String generateBody(@NotNull ExtensionAPI api,
                                         @NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs) {
        return generateBody(api, instruction, variableSpecs, GdVoidType.VOID, List.of());
    }

    private @NotNull String generateBody(@NotNull ExtensionAPI api,
                                         @NotNull LirInstruction instruction,
                                         @NotNull List<VariableSpec> variableSpecs,
                                         @NotNull GdType returnType,
                                         @NotNull List<LirClassDef> extraGdccClasses) {
        var workerClass = new LirClassDef("Worker", "RefCounted", false, false,
                Map.of(), List.of(), List.of(), List.of());
        var func = new LirFunctionDef("operator_test");
        func.setReturnType(returnType);
        for (var variableSpec : variableSpecs) {
            if (variableSpec.ref()) {
                func.createAndAddRefVariable(variableSpec.id(), variableSpec.type());
            } else {
                func.createAndAddVariable(variableSpec.id(), variableSpec.type());
            }
        }

        var entry = new LirBasicBlock("entry");
        entry.appendInstruction(instruction);
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        workerClass.addFunction(func);

        var gdccClasses = new ArrayList<LirClassDef>();
        gdccClasses.add(workerClass);
        gdccClasses.addAll(extraGdccClasses);
        var module = new LirModule("test_module", List.copyOf(gdccClasses));
        var codegen = newCodegen(api, module, gdccClasses);
        return codegen.generateFuncBody(workerClass, func);
    }

    private @NotNull CCodegen newCodegen(@NotNull ExtensionAPI api,
                                         @NotNull LirModule module,
                                         @NotNull List<LirClassDef> gdccClasses) {
        var classRegistry = new ClassRegistry(api);
        for (var gdccClass : gdccClasses) {
            classRegistry.addGdccClass(gdccClass);
        }
        ProjectInfo projectInfo = new ProjectInfo("TestProject", GodotVersion.V451, Path.of(".")) {
        };
        var ctx = new CodegenContext(projectInfo, classRegistry);
        var codegen = new CCodegen();
        codegen.prepare(ctx, module);
        return codegen;
    }

    private @NotNull ExtensionAPI emptyApi() {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI primitiveCompareApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator(">", "int", "bool"),
                        new ExtensionBuiltinClass.ClassOperator("==", "int", "bool"),
                        new ExtensionBuiltinClass.ClassOperator("!=", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI engineObjectApi() {
        var objectClass = new ExtensionGdClass(
                "Object",
                false,
                true,
                "",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var nodeClass = new ExtensionGdClass(
                "Node",
                false,
                true,
                "Object",
                "core",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(objectClass, nodeClass),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI evaluatorIntApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("unary-", "", "int"),
                        new ExtensionBuiltinClass.ClassOperator("in", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI malformedEvaluatorIntApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("invalid_op", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("in", "", "bool"),
                        new ExtensionBuiltinClass.ClassOperator("in", "int", ""),
                        new ExtensionBuiltinClass.ClassOperator("in", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI primitiveFastPathApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("+", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("/", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("**", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("%", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("<<", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator(">>", "int", "int"),
                        new ExtensionBuiltinClass.ClassOperator("xor", "int", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var floatBuiltin = new ExtensionBuiltinClass(
                "float",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("/", "float", "float"),
                        new ExtensionBuiltinClass.ClassOperator("%", "float", "float"),
                        new ExtensionBuiltinClass.ClassOperator("**", "int", "float")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin, floatBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI dualFallbackApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("<", "String", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI nonSwappableFallbackCandidateApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("-", "String", "int")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI inFallbackCandidateApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "String", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    /// Mirrors the real Godot metadata shape: containment/equality entries are keyed by the
    /// plain container class name (`Array` / `Dictionary`), while the frontend publishes
    /// precisely-typed container operands such as `Array[int]`.
    private @NotNull ExtensionAPI typedContainerOperatorApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "Array", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var stringBuiltin = new ExtensionBuiltinClass(
                "String",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "Dictionary", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        var arrayBuiltin = new ExtensionBuiltinClass(
                "Array",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("==", "Array", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin, stringBuiltin, arrayBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI inIntArrayApi() {
        var intBuiltin = new ExtensionBuiltinClass(
                "int",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("in", "Array", "bool")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(intBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private @NotNull ExtensionAPI stringFormatApi() {
        var stringBuiltin = new ExtensionBuiltinClass(
                "String",
                false,
                List.of(
                        new ExtensionBuiltinClass.ClassOperator("%", "Array", "String")
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(stringBuiltin),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private record VariableSpec(@NotNull String id, @NotNull GdType type, boolean ref) {
    }
}
