package gd.script.gdcc.backend.c.gen;

import gd.script.gdcc.backend.CodegenContext;
import gd.script.gdcc.backend.ProjectInfo;
import gd.script.gdcc.enums.GodotVersion;
import gd.script.gdcc.exception.InvalidInsnException;
import gd.script.gdcc.gdextension.ExtensionAPI;
import gd.script.gdcc.gdextension.ExtensionBuiltinClass;
import gd.script.gdcc.gdextension.ExtensionFunctionArgument;
import gd.script.gdcc.gdextension.ExtensionGdClass;
import gd.script.gdcc.gdextension.ExtensionGlobalEnum;
import gd.script.gdcc.gdextension.ExtensionSingleton;
import gd.script.gdcc.gdextension.ExtensionUtilityFunction;
import gd.script.gdcc.lir.LirBasicBlock;
import gd.script.gdcc.lir.LirClassDef;
import gd.script.gdcc.lir.LirFunctionDef;
import gd.script.gdcc.lir.LirInstruction;
import gd.script.gdcc.lir.LirModule;
import gd.script.gdcc.lir.LirPropertyDef;
import gd.script.gdcc.backend.c.gen.insn.ConstructInsnGen;
import gd.script.gdcc.enums.GdInstruction;
import gd.script.gdcc.lir.insn.AssertObjectLiveInsn;
import gd.script.gdcc.lir.insn.ConstructArrayInsn;
import gd.script.gdcc.lir.insn.ConstructBuiltinInsn;
import gd.script.gdcc.lir.insn.ConstructDictionaryInsn;
import gd.script.gdcc.lir.insn.ConstructObjectInsn;
import gd.script.gdcc.lir.insn.ConstructCallableInsn;
import gd.script.gdcc.lir.insn.ConstructSignalInsn;
import gd.script.gdcc.lir.insn.ConstructStandaloneCallableInsn;
import gd.script.gdcc.lir.insn.StandaloneCallableKind;
import gd.script.gdcc.lir.insn.DestructInsn;
import gd.script.gdcc.lir.insn.GetClassNameInsn;
import gd.script.gdcc.lir.insn.ReturnInsn;
import gd.script.gdcc.scope.ClassRegistry;
import gd.script.gdcc.type.GdArrayType;
import gd.script.gdcc.type.GdBasisType;
import gd.script.gdcc.type.GdBoolType;
import gd.script.gdcc.type.GdDictionaryType;
import gd.script.gdcc.type.GdFloatType;
import gd.script.gdcc.type.GdFloatVectorType;
import gd.script.gdcc.type.GdccForRangeIterType;
import gd.script.gdcc.type.GdIntType;
import gd.script.gdcc.type.GdObjectType;
import gd.script.gdcc.type.GdCallableType;
import gd.script.gdcc.type.GdSignalType;
import gd.script.gdcc.type.GdPackedNumericArrayType;
import gd.script.gdcc.type.GdPackedStringArrayType;
import gd.script.gdcc.type.GdPackedVectorArrayType;
import gd.script.gdcc.type.GdProjectionType;
import gd.script.gdcc.type.GdStringNameType;
import gd.script.gdcc.type.GdStringType;
import gd.script.gdcc.type.GdTransform2DType;
import gd.script.gdcc.type.GdTransform3DType;
import gd.script.gdcc.type.GdType;
import gd.script.gdcc.type.GdVariantType;
import gd.script.gdcc.type.GdVoidType;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CConstructInsnGenTest {
    @Test
    @DisplayName("construct_builtin should emit metadata-backed atomic constructor helpers")
    void constructBuiltinShouldEmitMetadataBackedAtomicCtorHelpers() {
        assertAtomicConstructorCall(GdIntType.INT, List.of(GdIntType.INT), "godot_new_int_with_int");
        assertAtomicConstructorCall(GdBoolType.BOOL, List.of(GdIntType.INT), "godot_new_bool_with_int");
        assertAtomicConstructorCall(GdFloatType.FLOAT, List.of(GdBoolType.BOOL), "godot_new_float_with_bool");
    }

    @Test
    @DisplayName("construct_builtin should emit helper-shim constructor call for Transform2D with 6 float args")
    void constructBuiltinShouldEmitTransform2DHelperCtor() {
        var clazz = newTestClass();
        var func = newFunction("construct_transform2d");
        func.createAndAddVariable("a", GdFloatType.FLOAT);
        func.createAndAddVariable("b", GdFloatType.FLOAT);
        func.createAndAddVariable("c", GdFloatType.FLOAT);
        func.createAndAddVariable("d", GdFloatType.FLOAT);
        func.createAndAddVariable("e", GdFloatType.FLOAT);
        func.createAndAddVariable("f", GdFloatType.FLOAT);
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "t",
                List.of(
                        new LirInstruction.VariableOperand("a"),
                        new LirInstruction.VariableOperand("b"),
                        new LirInstruction.VariableOperand("c"),
                        new LirInstruction.VariableOperand("d"),
                        new LirInstruction.VariableOperand("e"),
                        new LirInstruction.VariableOperand("f")
                )
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Transform2D_with_float_float_float_float_float_float"));
    }

    @Test
    @DisplayName("construct_builtin should emit all flat-float helper-shim constructor calls")
    void constructBuiltinShouldEmitFlatFloatHelperCtorForAllShimTypes() {
        for (var helperCase : flatFloatHelperCtorCases()) {
            var clazz = newTestClass();
            var func = newFunction("construct_" + helperCase.label());
            var args = addFloatArgs(func, helperCase.argCount());
            func.createAndAddVariable("result", helperCase.targetType());

            entry(func).appendInstruction(new ConstructBuiltinInsn("result", args));
            clazz.addFunction(func);

            var body = generateBody(clazz, func);
            assertTrue(
                    body.contains(helperCase.constructorName()),
                    () -> "Expected helper constructor missing for " + helperCase.targetType().getTypeName() + ".\nBody:\n" + body
            );
        }
    }

    @Test
    @DisplayName("construct_builtin should reject helper-shim arity mismatches")
    void constructBuiltinShouldRejectHelperShimArityMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_transform2d_bad_arity");
        var args = addFloatArgs(func, 5);
        func.createAndAddVariable("result", GdTransform2DType.TRANSFORM2D);

        entry(func).appendInstruction(new ConstructBuiltinInsn("result", args));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("Builtin constructor validation failed"));
        assertTrue(ex.getMessage().contains("'Transform2D' with args [float, float, float, float, float]"));
    }

    @Test
    @DisplayName("construct_builtin should reject helper-shim type mismatches")
    void constructBuiltinShouldRejectHelperShimTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_transform2d_bad_type");
        var args = addFloatArgs(func, 5);
        func.createAndAddVariable("bad", GdIntType.INT);
        args.add(new LirInstruction.VariableOperand("bad"));
        func.createAndAddVariable("result", GdTransform2DType.TRANSFORM2D);

        entry(func).appendInstruction(new ConstructBuiltinInsn("result", args));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("Builtin constructor validation failed"));
        assertTrue(ex.getMessage().contains("'Transform2D' with args [float, float, float, float, float, int]"));
    }

    @Test
    @DisplayName("construct_builtin should reject missing result variable ID")
    void constructBuiltinShouldRejectMissingResultId() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_missing_result");

        entry(func).appendInstruction(new ConstructBuiltinInsn(null, List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("Construction instruction missing result variable ID"));
    }

    @Test
    @DisplayName("construct_builtin should reject unknown result variables")
    void constructBuiltinShouldRejectUnknownResultVariable() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_unknown_result");

        entry(func).appendInstruction(new ConstructBuiltinInsn("missing", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("Result variable ID 'missing' does not exist"));
    }

    @Test
    @DisplayName("construct_builtin should reject ref result variables")
    void constructBuiltinShouldRejectRefResultVariable() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_ref_result");
        var result = func.createAndAddRefVariable("result", GdIntType.INT);
        assertNotNull(result);

        entry(func).appendInstruction(new ConstructBuiltinInsn("result", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("Result variable ID 'result' cannot be a reference"));
    }

    @Test
    @DisplayName("construct_builtin should reject compiler-only result types instead of inventing Godot helpers")
    void constructBuiltinShouldRejectCompilerOnlyResultType() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_compiler_only_result");
        func.createAndAddVariable("iter", GdccForRangeIterType.FOR_RANGE_ITER);

        entry(func).appendInstruction(new ConstructBuiltinInsn("iter", List.of()));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("Builtin constructor validation failed"), ex.getMessage());
        assertFalse(ex.getMessage().contains("godot_new_GdccForRangeIter"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_builtin should reject unknown argument variables")
    void constructBuiltinShouldRejectUnknownArgumentVariable() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_unknown_arg");
        func.createAndAddVariable("result", GdIntType.INT);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "result",
                List.of(new LirInstruction.VariableOperand("missing"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_builtin argument variable ID 'missing' not found"));
    }

    @Test
    @DisplayName("construct_builtin should reject non-variable operands")
    void constructBuiltinShouldRejectNonVariableOperand() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_non_var_operand");
        func.createAndAddVariable("t", GdTransform2DType.TRANSFORM2D);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "t",
                List.of(new LirInstruction.StringOperand("not_var"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must be a variable operand"));
    }

    @Test
    @DisplayName("construct_builtin should keep API constructor matching exact and reject Variant operands")
    void constructBuiltinShouldRejectVariantOperandWithoutExactMetadata() {
        var clazz = newTestClass();
        var func = newFunction("construct_builtin_variant_operand");
        func.createAndAddVariable("result", GdIntType.INT);
        func.createAndAddVariable("variant", GdVariantType.VARIANT);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "result",
                List.of(new LirInstruction.VariableOperand("variant"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("Builtin constructor validation failed"));
        assertTrue(ex.getMessage().contains("'int' with args [Variant]"));
    }

    @Test
    @DisplayName("construct_builtin should emit StringName(String) constructor with non-ref source address")
    void constructBuiltinShouldEmitStringNameFromNonRefStringConstructor() {
        var clazz = newTestClass();
        var func = newFunction("construct_string_name_from_string");
        func.createAndAddVariable("text", GdStringType.STRING);
        func.createAndAddVariable("name", GdStringNameType.STRING_NAME);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "name",
                List.of(new LirInstruction.VariableOperand("text"))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithStringFamilyConstructors());
        var call = extractCall(body, "godot_new_StringName_with_String");
        assertTrue(call.contains("&$text"), call);
    }

    @Test
    @DisplayName("construct_builtin should emit String(StringName) constructor with ref source pointer")
    void constructBuiltinShouldEmitStringFromRefStringNameConstructor() {
        var clazz = newTestClass();
        var func = newFunction("construct_string_from_ref_string_name");
        var source = func.createAndAddTmpRefVariable(GdStringNameType.STRING_NAME);
        func.createAndAddVariable("text", GdStringType.STRING);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "text",
                List.of(new LirInstruction.VariableOperand(source.id()))
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithStringFamilyConstructors());
        var call = extractCall(body, "godot_new_String_with_StringName");
        assertTrue(call.contains("$" + source.id()), call);
        assertFalse(call.contains("&$" + source.id()), call);
    }

    @Test
    @DisplayName("construct_builtin should reject non-variable operands on String family constructors")
    void constructBuiltinShouldRejectStringFamilyNonVariableOperand() {
        var clazz = newTestClass();
        var func = newFunction("construct_string_family_non_var_operand");
        func.createAndAddVariable("name", GdStringNameType.STRING_NAME);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "name",
                List.of(new LirInstruction.StringOperand("not_var"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithStringFamilyConstructors())
        );
        assertTrue(ex.getMessage().contains("must be a variable operand"));
    }

    @Test
    @DisplayName("construct_builtin should fail fast when String family constructor metadata is missing")
    void constructBuiltinShouldRejectStringFamilyConstructorWhenMetadataIsMissing() {
        var clazz = newTestClass();
        var func = newFunction("construct_string_family_missing_metadata");
        func.createAndAddVariable("text", GdStringType.STRING);
        func.createAndAddVariable("name", GdStringNameType.STRING_NAME);

        entry(func).appendInstruction(new ConstructBuiltinInsn(
                "name",
                List.of(new LirInstruction.VariableOperand("text"))
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithStringFamilyBuiltinsWithoutConstructors())
        );
        assertTrue(ex.getMessage().contains("Builtin constructor validation failed"));
        assertTrue(ex.getMessage().contains("'StringName' with args [String]"));
    }

    @Test
    @DisplayName("construct_array should emit typed Array constructor when operand type matches result type")
    void constructArrayShouldEmitTypedCtor() {
        var clazz = newTestClass();
        var func = newFunction("construct_typed_array");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", "StringName"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING_NAME"));
        assertTrue(body.contains("godot_Variant __gdcc_tmp_array_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var arrayCtorCall = extractCall(body, "godot_new_Array_with_Array_int_StringName_Variant");
        assertFalse(arrayCtorCall.contains("NULL"), arrayCtorCall);
    }

    @Test
    @DisplayName("construct_array should fail when provided class_name does not match result element type")
    void constructArrayShouldRejectTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_array_mismatch");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", "String"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_array type mismatch"));
    }

    @Test
    @DisplayName("construct_array should keep generic Array construction on the plain constructor path")
    void constructArrayShouldKeepGenericCtorOnPlainPath() {
        var clazz = newTestClass();
        var func = newFunction("construct_generic_array");
        func.createAndAddVariable("arr", new GdArrayType(GdVariantType.VARIANT));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Array()"), body);
        assertFalse(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertFalse(body.contains("__gdcc_tmp_array_script_"), body);
        assertFalse(body.contains("godot_new_Variant_nil();"), body);
    }

    @Test
    @DisplayName("construct_array should emit Packed*Array constructor when result type is packed and class_name is omitted")
    void constructArrayShouldEmitPackedCtorWhenClassNameOmitted() {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);

        entry(func).appendInstruction(new ConstructArrayInsn("packed", null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_PackedInt32Array()"));
    }

    @Test
    @DisplayName("construct_array should reject class_name when result type is Packed*Array")
    void constructArrayShouldRejectClassNameForPackedArray() {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array_with_class_name");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);

        entry(func).appendInstruction(new ConstructArrayInsn("packed", "PackedInt32Array"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must not provide class_name"));
    }

    @Test
    @DisplayName("construct_array should reject empty or blank class_name when result type is Packed*Array")
    void constructArrayShouldRejectEmptyOrBlankClassNameForPackedArray() {
        assertPackedArrayClassNameRejected("");
        assertPackedArrayClassNameRejected("   ");
    }

    @Test
    @DisplayName("construct_dictionary should emit typed Dictionary constructor when key/value operands match result types")
    void constructDictionaryShouldEmitTypedCtor() {
        var clazz = newTestClass();
        var func = newFunction("construct_typed_dictionary");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "StringName", "Variant"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_STRING_NAME"));
        assertTrue(body.contains("GDEXTENSION_VARIANT_TYPE_NIL"));
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_key_script_"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_value_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var dictCtorCall = extractCall(body, "godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant");
        assertFalse(dictCtorCall.contains("NULL"), dictCtorCall);
    }

    @Test
    @DisplayName("construct_dictionary should fail when provided key/value types do not match result types")
    void constructDictionaryShouldRejectTypeMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_mismatch");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "String", "Variant"));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary key type mismatch"));
    }

    @Test
    @DisplayName("construct_array should preserve unknown object leaf hints through registry compatibility parsing")
    void constructArrayShouldPreserveUnknownObjectLeafHints() {
        var clazz = newTestClass();
        var func = newFunction("construct_array_unknown_object_leaf");
        func.createAndAddVariable("arr", new GdArrayType(new GdObjectType("FutureItem")));

        entry(func).appendInstruction(new ConstructArrayInsn("arr", "FutureItem"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("GD_STATIC_SN(u8\"FutureItem\")"));
    }

    @Test
    @DisplayName("construct_dictionary should preserve unknown object leaf hints through registry compatibility parsing")
    void constructDictionaryShouldPreserveUnknownObjectLeafHints() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_unknown_object_leaf");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringType.STRING, new GdObjectType("FutureItem")));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "String", "FutureItem"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func);
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
        assertTrue(body.contains("GD_STATIC_SN(u8\"FutureItem\")"));
    }

    @Test
    @DisplayName("construct container hints should reject non-type registry names exposed by compatibility lookup")
    void constructContainerHintsShouldRejectNonTypeRegistryNames() {
        var api = new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(new ExtensionGlobalEnum("DamageFlags", true, List.of())),
                List.of(new ExtensionUtilityFunction("spawn_helper", "void", "test", false, 0, List.of())),
                List.of(),
                List.of(),
                List.of(new ExtensionSingleton("GameSingleton", "Node")),
                List.of()
        );

        assertInvalidArrayHint(api, "DamageFlags");
        assertInvalidArrayHint(api, "spawn_helper");
        assertInvalidArrayHint(api, "GameSingleton");
    }

    @Test
    @DisplayName("construct_dictionary should fail when one-side typed operand implies Variant but result value type is non-Variant")
    void constructDictionaryShouldRejectImplicitVariantValueMismatch() {
        var clazz = newTestClass();
        var func = newFunction("construct_dictionary_partial_operand_mismatch");
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdStringType.STRING));

        entry(func).appendInstruction(new ConstructDictionaryInsn("dict", "StringName", null));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary value type mismatch"));
    }

    @Test
    @DisplayName("construct_object should emit runtime Godot constructor wrapper call for engine object targets")
    void constructObjectShouldEmitEngineConstructCall() {
        var clazz = newTestClass();
        var func = newFunction("construct_engine_object");
        func.createAndAddVariable("node", new GdObjectType("Node"));

        entry(func).appendInstruction(new ConstructObjectInsn("node", "Node"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("godot_new_Node()"));
        assertFalse(body.contains("gdcc_object_from_godot_object_ptr("), body);
    }

    @Test
    @DisplayName("construct_object should consume fresh engine RefCounted results without extra own")
    void constructObjectShouldNotRetainFreshEngineRefCountedResultAgain() {
        var clazz = newTestClass();
        var func = newFunction("construct_engine_refcounted");
        func.createAndAddVariable("resource", new GdObjectType("RefCounted"));

        entry(func).appendInstruction(new ConstructObjectInsn("resource", "RefCounted"));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(
                body.contains("$resource = gdcc_RefCounted_fat_ptr_from_raw((GDExtensionObjectPtr)(godot_new_RefCounted()));"),
                body
        );
        assertFalse(body.contains("own_object($resource);"), body);
        assertFalse(body.contains("try_own_object($resource);"), body);
    }

    @Test
    @DisplayName("construct_object should externally initialize RefCounted gdcc create_instance results and convert into gdcc wrapper target")
    void constructObjectShouldConvertToGdccRefCountedWrapperTarget() {
        var holderClass = new LirClassDef("Holder", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var workerClass = new LirClassDef("Worker", "RefCounted");
        var func = newFunction("construct_gdcc_object");
        func.createAndAddVariable("worker", new GdObjectType("Worker"));

        entry(func).appendInstruction(new ConstructObjectInsn("worker", "Worker"));
        holderClass.addFunction(func);

        var module = new LirModule("test_module", List.of(holderClass, workerClass));
        var codegen = newCodegen(module, List.of(holderClass, workerClass), apiWithConstructibleObjectClasses());
        var body = codegen.generateFuncBody(holderClass, func);

        assertTrue(body.contains("gdcc_ref_counted_init_raw(Worker_class_create_instance(NULL, false), true)"));
        assertTrue(body.contains("gdcc_Worker_fat_ptr_from_raw((GDExtensionObjectPtr)("), body);
        assertFalse(body.contains("own_object("), body);
        assertFalse(body.contains("try_own_object("), body);
    }

    @Test
    @DisplayName("construct_object should keep plain gdcc create_instance raw when target is not RefCounted")
    void constructObjectShouldKeepPlainGdccCreateInstanceRaw() {
        var holderClass = new LirClassDef("Holder", "Node", false, false, Map.of(), List.of(), List.of(), List.of());
        var plainClass = new LirClassDef("PlainWorker", "Object");
        var func = newFunction("construct_plain_gdcc_object");
        func.createAndAddVariable("worker", new GdObjectType("PlainWorker"));

        entry(func).appendInstruction(new ConstructObjectInsn("worker", "PlainWorker"));
        holderClass.addFunction(func);

        var module = new LirModule("test_module", List.of(holderClass, plainClass));
        var codegen = newCodegen(module, List.of(holderClass, plainClass), apiWithConstructibleObjectClasses());
        var body = codegen.generateFuncBody(holderClass, func);

        assertTrue(body.contains("PlainWorker_class_create_instance(NULL, true)"), body);
        assertFalse(body.contains("gdcc_ref_counted_init_raw("), body);
        assertTrue(body.contains("gdcc_PlainWorker_fat_ptr_from_raw((GDExtensionObjectPtr)("), body);
    }

    @Test
    @DisplayName("construct_object should reject non-object result slots")
    void constructObjectShouldRejectNonObjectResultSlot() {
        var clazz = newTestClass();
        var func = newFunction("construct_object_non_object_slot");
        func.createAndAddVariable("value", GdFloatType.FLOAT);

        entry(func).appendInstruction(new ConstructObjectInsn("value", "Node"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("must be Object type for construct_object"));
    }

    @Test
    @DisplayName("construct_object should reject unknown classes")
    void constructObjectShouldRejectUnknownClass() {
        var clazz = newTestClass();
        var func = newFunction("construct_unknown_object");
        func.createAndAddVariable("obj", new GdObjectType("UnknownType"));

        entry(func).appendInstruction(new ConstructObjectInsn("obj", "UnknownType"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("class 'UnknownType' is not registered"));
    }

    @Test
    @DisplayName("construct_object should trim class_name before registry lookup and diagnostics")
    void constructObjectShouldTrimClassNameBeforeLookup() {
        var clazz = newTestClass();
        var func = newFunction("construct_trimmed_object");
        func.createAndAddVariable("node", new GdObjectType("Node"));

        entry(func).appendInstruction(new ConstructObjectInsn("node", "  Node  "));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("godot_new_Node()"), body);
        assertFalse(body.contains("godot_new_  Node  ()"), body);
    }

    @Test
    @DisplayName("construct_signal should emit live-object Signal constructor and destroy the result")
    void constructSignalShouldEmitSignalFromReceiverAndDestroyResult() {
        var clazz = newTestClass();
        var func = newFunction("construct_signal_value");
        func.createAndAddVariable("self", new GdObjectType("Node"));
        func.createAndAddVariable("sig", new GdSignalType());

        entry(func).appendInstruction(new ConstructSignalInsn("sig", "self", "pinged"));
        entry(func).appendInstruction(new DestructInsn("sig"));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("godot_new_Signal_with_Object_StringName("), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"pinged\")"), body);
        assertTrue(body.contains("_live_object($self)"), body);
        assertTrue(body.contains("godot_Signal_destroy(&$sig);"), body);
        assertFalse(body.contains("assert_object_live"), body);
        assertFalse(body.contains("own_object("), body);
        assertFalse(body.contains("try_own_object("), body);
    }

    @Test
    @DisplayName("construct_signal after assert_object_live should hard-fail a null or freed Object receiver")
    void constructSignalShouldKeepLiveAssertHardFailForNonSelfObjectReceiver() {
        var clazz = newTestClass();
        var func = newFunction("construct_signal_live_assert");
        func.createAndAddVariable("other", new GdObjectType("Node"));
        func.createAndAddVariable("sig", new GdSignalType());

        entry(func).appendInstruction(new AssertObjectLiveInsn("other"));
        entry(func).appendInstruction(new ConstructSignalInsn("sig", "other", "ready"));
        entry(func).appendInstruction(new DestructInsn("sig"));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        var assertIndex = body.indexOf("assert_object_live failed: object 'other' is null or freed");
        var constructIndex = body.indexOf("godot_new_Signal_with_Object_StringName(");
        assertTrue(assertIndex >= 0, body);
        assertTrue(constructIndex >= 0, body);
        assertTrue(assertIndex < constructIndex, body);
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($other).ptr, $other.instance_id)"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"ready\")"), body);
        assertTrue(body.contains("_live_object($other)"), body);
        assertTrue(body.contains("godot_Signal_destroy(&$sig);"), body);
    }

    @Test
    @DisplayName("construct_signal opcode is registered on ConstructInsnGen")
    void constructSignalOpcodeIsRegisteredForDispatch() {
        assertTrue(new ConstructInsnGen().getInsnOpcodes().contains(GdInstruction.CONSTRUCT_SIGNAL));
        assertTrue(new ConstructInsnGen().getInsnOpcodes().contains(GdInstruction.CONSTRUCT_CALLABLE));
        assertTrue(new ConstructInsnGen().getInsnOpcodes().contains(GdInstruction.CONSTRUCT_STANDALONE_CALLABLE));
    }

    /// GET_CLASS_NAME has no CInsnGen (CALL_SUPER_METHOD gained one in vtable plan Step 5, so the
    /// unregistered-opcode probe moved to the remaining gap). Dispatch must throw, not skip the insn.
    @Test
    @DisplayName("CCodegen must fail-fast when an opcode is not registered on any CInsnGen")
    void unregisteredOpcodeFailsDispatchInsteadOfSkipping() {
        var clazz = newTestClass();
        var func = newFunction("unregistered_get_class_name");
        func.createAndAddVariable("self", new GdObjectType("Node"));
        func.createAndAddVariable("result", GdVariantType.VARIANT);
        entry(func).appendInstruction(new GetClassNameInsn("result", "self"));
        clazz.addFunction(func);

        var thrown = assertThrows(
                UnsupportedOperationException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(thrown.getMessage().contains("get_class_name"), thrown.getMessage());
    }

    @Test
    @DisplayName("construct_callable should emit live-object Callable constructor and destroy the result")
    void constructCallableShouldEmitCallableFromReceiverAndDestroyResult() {
        var clazz = newTestClass();
        var func = newFunction("construct_callable_value");
        func.createAndAddVariable("self", new GdObjectType("Node"));
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructCallableInsn("cb", "self", "_handler"));
        entry(func).appendInstruction(new DestructInsn("cb"));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("godot_new_Callable_with_Object_StringName("), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"_handler\")"), body);
        assertTrue(body.contains("_live_object($self)"), body);
        assertTrue(body.contains("godot_Callable_destroy(&$cb);"), body);
        assertFalse(body.contains("assert_object_live"), body);
        assertFalse(body.contains("own_object("), body);
        assertFalse(body.contains("try_own_object("), body);
    }

    @Test
    @DisplayName("construct_callable after assert_object_live should hard-fail a null or freed Object receiver")
    void constructCallableShouldKeepLiveAssertHardFailForNonSelfObjectReceiver() {
        var clazz = newTestClass();
        var func = newFunction("construct_callable_live_assert");
        func.createAndAddVariable("other", new GdObjectType("Node"));
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new AssertObjectLiveInsn("other"));
        entry(func).appendInstruction(new ConstructCallableInsn("cb", "other", "_handler"));
        entry(func).appendInstruction(new DestructInsn("cb"));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        var assertIndex = body.indexOf("assert_object_live failed: object 'other' is null or freed");
        var constructIndex = body.indexOf("godot_new_Callable_with_Object_StringName(");
        assertTrue(assertIndex >= 0, body);
        assertTrue(constructIndex >= 0, body);
        assertTrue(assertIndex < constructIndex, body);
        assertTrue(body.contains("gdcc_object_is_null_raw_and_id((GDExtensionObjectPtr)($other).ptr, $other.instance_id)"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"_handler\")"), body);
        assertTrue(body.contains("_live_object($other)"), body);
        assertTrue(body.contains("godot_Callable_destroy(&$cb);"), body);
    }

    @Test
    @DisplayName("construct_callable should reject missing receiver variables")
    void constructCallableShouldRejectMissingReceiver() {
        var clazz = newTestClass();
        var func = newFunction("construct_callable_missing_receiver");
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructCallableInsn("cb", "missing", "_handler"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("receiver variable ID 'missing' not found"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_callable should emit Callable.create for builtin receivers")
    void constructCallableShouldEmitCallableCreateForBuiltinReceiver() {
        var clazz = newTestClass();
        var func = newFunction("construct_callable_builtin_receiver");
        func.createAndAddVariable("vec", GdFloatVectorType.VECTOR2);
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructCallableInsn("cb", "vec", "abs"));
        entry(func).appendInstruction(new DestructInsn("cb"));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        // Callable.create is a static builtin wrapper: its generated call takes no self parameter.
        assertTrue(body.contains("godot_Callable_create("), body);
        assertFalse(body.contains("godot_Callable_create(NULL"), body);
        assertTrue(body.contains("GD_STATIC_SN(u8\"abs\")"), body);
        assertTrue(body.contains("godot_Variant_destroy("), body);
        assertFalse(body.contains("godot_new_Callable_with_Object_StringName("), body);
        assertFalse(body.contains("_live_object($vec)"), body);
    }

    @Test
    @DisplayName("construct_callable should reject Variant receivers")
    void constructCallableShouldRejectVariantReceiver() {
        var clazz = newTestClass();
        var func = newFunction("construct_callable_variant_receiver");
        func.createAndAddVariable("recv", GdVariantType.VARIANT);
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructCallableInsn("cb", "recv", "abs"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("must not be Variant"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_standalone_callable should emit custom-create helper for utility")
    void constructStandaloneCallableShouldEmitUtilityCustomCreate() {
        var clazz = newTestClass();
        var func = newFunction("construct_standalone_utility");
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructStandaloneCallableInsn(
                "cb",
                StandaloneCallableKind.UTILITY,
                "",
                "print"
        ));
        entry(func).appendInstruction(new DestructInsn("cb"));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("gdcc_new_standalone_callable("), body);
        assertTrue(body.contains("u8\"utility\""), body);
        assertTrue(body.contains("u8\"print\""), body);
        assertTrue(body.contains("2648703342LL"), body);
        assertFalse(body.contains("godot_new_Callable_with_Object_StringName("), body);
    }

    @Test
    @DisplayName("construct_standalone_callable should fail fast for synthetic language functions")
    void constructStandaloneCallableShouldRejectSyntheticLanguageFunction() {
        // Second line of defense for the first-class reference ban (`var f = len`): synthetic
        // language functions are excluded from `findUtilityFunction`, so even hand-written LIR
        // that bypasses the frontend gate must fail fast instead of building a hash-less
        // standalone callable.
        for (var functionName : List.of("len", "range", "is_instance_of", "load")) {
            var clazz = newTestClass();
            var func = newFunction("construct_standalone_language_function");
            func.createAndAddVariable("cb", new GdCallableType());

            entry(func).appendInstruction(new ConstructStandaloneCallableInsn(
                    "cb",
                    StandaloneCallableKind.UTILITY,
                    "",
                    functionName
            ));
            clazz.addFunction(func);

            var ex = assertThrows(
                    InvalidInsnException.class,
                    () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
            );
            assertTrue(ex.getMessage().contains("'" + functionName + "'"), ex.getMessage());
            assertTrue(ex.getMessage().contains("not registered"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("construct_standalone_callable should emit custom-create helper for GDCC static")
    void constructStandaloneCallableShouldEmitGdccStaticCustomCreate() {
        var clazz = newTestClass();
        var build = newFunction("build");
        build.setStatic(true);
        clazz.addFunction(build);
        var func = newFunction("construct_standalone_gdcc_static");
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructStandaloneCallableInsn(
                "cb",
                StandaloneCallableKind.STATIC_GDCC,
                "Worker",
                "build"
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("gdcc_new_standalone_callable("), body);
        assertTrue(body.contains("u8\"static_gdcc\""), body);
        assertTrue(body.contains("u8\"Worker\""), body);
        assertTrue(body.contains("u8\"build\""), body);
    }

    @Test
    @DisplayName("construct_standalone_callable should emit custom-create helper for engine static")
    void constructStandaloneCallableShouldEmitEngineStaticCustomCreate() {
        var clazz = newTestClass();
        var func = newFunction("construct_standalone_engine_static");
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructStandaloneCallableInsn(
                "cb",
                StandaloneCallableKind.STATIC_ENGINE,
                "JSON",
                "parse_string"
        ));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithConstructibleObjectClasses());
        assertTrue(body.contains("gdcc_new_standalone_callable("), body);
        assertTrue(body.contains("u8\"static_engine\""), body);
        assertTrue(body.contains("u8\"JSON\""), body);
        assertTrue(body.contains("u8\"parse_string\""), body);
    }

    @Test
    @DisplayName("construct_standalone_callable should reject unknown utility names")
    void constructStandaloneCallableShouldRejectUnknownUtility() {
        var clazz = newTestClass();
        var func = newFunction("construct_standalone_unknown_utility");
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructStandaloneCallableInsn(
                "cb",
                StandaloneCallableKind.UTILITY,
                "",
                "not_a_utility"
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("utility 'not_a_utility' is not registered"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_standalone_callable should resolve inherited GDCC static to declaring owner")
    void constructStandaloneCallableShouldResolveInheritedGdccStaticToDeclaringOwner() {
        var parentClass = new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
        var build = newFunction("build");
        build.setStatic(true);
        parentClass.addFunction(build);
        var childClass = new LirClassDef("WorkerChild", "Worker", false, false, Map.of(), List.of(), List.of(), List.of());
        var func = newFunction("construct_inherited_gdcc_static");
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructStandaloneCallableInsn(
                "cb",
                StandaloneCallableKind.STATIC_GDCC,
                "WorkerChild",
                "build"
        ));
        childClass.addFunction(func);

        var module = new LirModule("test_module", List.of(parentClass, childClass));
        var codegen = newCodegen(module, List.of(parentClass, childClass), apiWithConstructibleObjectClasses());
        var body = codegen.generateFuncBody(childClass, func);

        assertTrue(body.contains("gdcc_new_standalone_callable("), body);
        assertTrue(body.contains("u8\"static_gdcc\""), body);
        assertTrue(body.contains("u8\"Worker\""), body);
        assertTrue(body.contains("u8\"build\""), body);
        assertFalse(body.contains("u8\"WorkerChild\""), body);
    }

    @Test
    @DisplayName("construct_standalone_callable should reject missing GDCC static symbols")
    void constructStandaloneCallableShouldRejectMissingGdccStatic() {
        var clazz = newTestClass();
        var func = newFunction("construct_standalone_missing_gdcc");
        func.createAndAddVariable("cb", new GdCallableType());

        entry(func).appendInstruction(new ConstructStandaloneCallableInsn(
                "cb",
                StandaloneCallableKind.STATIC_GDCC,
                "Worker",
                "missing"
        ));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("Worker.missing"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_callable should reject non-callable result slots")
    void constructCallableShouldRejectNonCallableResultSlot() {
        var clazz = newTestClass();
        var func = newFunction("construct_callable_non_callable_slot");
        func.createAndAddVariable("self", new GdObjectType("Node"));
        func.createAndAddVariable("value", GdFloatType.FLOAT);

        entry(func).appendInstruction(new ConstructCallableInsn("value", "self", "_handler"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("must be Callable type for construct_callable"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_signal should reject missing receiver variables")
    void constructSignalShouldRejectMissingReceiver() {
        var clazz = newTestClass();
        var func = newFunction("construct_signal_missing_receiver");
        func.createAndAddVariable("sig", new GdSignalType());

        entry(func).appendInstruction(new ConstructSignalInsn("sig", "missing", "pinged"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("receiver variable ID 'missing' not found"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_signal should reject non-object receivers")
    void constructSignalShouldRejectNonObjectReceiver() {
        var clazz = newTestClass();
        var func = newFunction("construct_signal_non_object_receiver");
        func.createAndAddVariable("recv", GdFloatType.FLOAT);
        func.createAndAddVariable("sig", new GdSignalType());

        entry(func).appendInstruction(new ConstructSignalInsn("sig", "recv", "pinged"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("must be Object type"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_signal should reject non-signal result slots")
    void constructSignalShouldRejectNonSignalResultSlot() {
        var clazz = newTestClass();
        var func = newFunction("construct_signal_non_signal_slot");
        func.createAndAddVariable("self", new GdObjectType("Node"));
        func.createAndAddVariable("value", GdFloatType.FLOAT);

        entry(func).appendInstruction(new ConstructSignalInsn("value", "self", "pinged"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("must be Signal type for construct_signal"), ex.getMessage());
    }

    @Test
    @DisplayName("construct_object should reject non-instantiable engine classes")
    void constructObjectShouldRejectNonInstantiableEngineClass() {
        var clazz = newTestClass();
        var func = newFunction("construct_non_instantiable_engine");
        func.createAndAddVariable("obj", new GdObjectType("EditorOnlyThing"));

        entry(func).appendInstruction(new ConstructObjectInsn("obj", "EditorOnlyThing"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("class 'EditorOnlyThing' is not instantiable"));
    }

    @Test
    @DisplayName("construct_object should reject abstract gdcc classes")
    void constructObjectShouldRejectAbstractGdccClass() {
        var holderClass = newTestClass();
        var abstractWorker = new LirClassDef("AbstractWorker", "RefCounted");
        abstractWorker.setAbstract(true);
        var func = newFunction("construct_abstract_gdcc");
        func.createAndAddVariable("worker", new GdObjectType("AbstractWorker"));

        entry(func).appendInstruction(new ConstructObjectInsn("worker", "AbstractWorker"));
        holderClass.addFunction(func);

        var module = new LirModule("test_module", List.of(holderClass, abstractWorker));
        var codegen = newCodegen(module, List.of(holderClass, abstractWorker), apiWithConstructibleObjectClasses());
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(holderClass, func));

        assertTrue(ex.getMessage().contains("class 'AbstractWorker' is abstract"));
    }

    @Test
    @DisplayName("construct_object should reject class targets that are not assignable to result slot type")
    void constructObjectShouldRejectIncompatibleResultType() {
        var clazz = newTestClass();
        var func = newFunction("construct_object_type_mismatch");
        func.createAndAddVariable("node", new GdObjectType("Node"));

        entry(func).appendInstruction(new ConstructObjectInsn("node", "RefCounted"));
        clazz.addFunction(func);

        var ex = assertThrows(
                InvalidInsnException.class,
                () -> generateBody(clazz, func, apiWithConstructibleObjectClasses())
        );
        assertTrue(ex.getMessage().contains("is not assignable to result variable type 'Node'"));
    }

    @Test
    @DisplayName("generate should inject typed construct instructions into __prepare__ for Array and Dictionary variables")
    void generateShouldInjectConstructInstructionsIntoPrepareBlock() {
        var clazz = newTestClass();
        var func = newFunction("prepare_inject_constructs");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepare = func.getBasicBlock("__prepare__");
        assertNotNull(prepare);
        assertEquals("__prepare__", func.getEntryBlockId());

        var hasArrayInsn = prepare.getInstructions().stream()
                .filter(ConstructArrayInsn.class::isInstance)
                .map(ConstructArrayInsn.class::cast)
                .anyMatch(insn -> "arr".equals(insn.resultId()) && "StringName".equals(insn.className()));
        assertTrue(hasArrayInsn);

        var hasDictionaryInsn = prepare.getInstructions().stream()
                .filter(ConstructDictionaryInsn.class::isInstance)
                .map(ConstructDictionaryInsn.class::cast)
                .anyMatch(insn ->
                        "dict".equals(insn.resultId()) &&
                                "StringName".equals(insn.keyClassName()) &&
                                "Variant".equals(insn.valueClassName())
                );
        assertTrue(hasDictionaryInsn);
    }

    @Test
    @DisplayName("generate should inject construct_array with null class_name into __prepare__ for Packed*Array variables")
    void generateShouldInjectPackedConstructArrayIntoPrepareBlock() {
        var clazz = newTestClass();
        var func = newFunction("prepare_inject_packed_construct");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var prepare = func.getBasicBlock("__prepare__");
        assertNotNull(prepare);
        var hasPackedArrayInsn = prepare.getInstructions().stream()
                .filter(ConstructArrayInsn.class::isInstance)
                .map(ConstructArrayInsn.class::cast)
                .anyMatch(insn -> "packed".equals(insn.resultId()) && insn.className() == null);
        assertTrue(hasPackedArrayInsn);

        var hasPackedBuiltinInsn = prepare.getInstructions().stream()
                .filter(ConstructBuiltinInsn.class::isInstance)
                .map(ConstructBuiltinInsn.class::cast)
                .anyMatch(insn -> "packed".equals(insn.resultId()));
        assertFalse(hasPackedBuiltinInsn);
    }

    @Test
    @DisplayName("__prepare__ generated typed construct instructions should emit typed constructor C calls")
    void generatedPrepareConstructsShouldEmitTypedConstructorCalls() {
        var clazz = newTestClass();
        var func = newFunction("prepare_emit_typed_ctor_calls");
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var body = codegen.generateFuncBody(clazz, func);
        assertTrue(body.contains("__prepare__: // __prepare__"));
        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"));
        assertTrue(body.contains("godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant"));
        assertTrue(body.contains("godot_Variant __gdcc_tmp_array_script_"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_key_script_"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_dict_value_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var arrayCtorCall = extractCall(body, "godot_new_Array_with_Array_int_StringName_Variant");
        assertFalse(arrayCtorCall.contains("NULL"), arrayCtorCall);
        var dictCtorCall = extractCall(body, "godot_new_Dictionary_with_Dictionary_int_StringName_Variant_int_StringName_Variant");
        assertFalse(dictCtorCall.contains("NULL"), dictCtorCall);
    }

    @Test
    @DisplayName("__prepare__ generated Packed*Array construct instruction should emit packed constructor C call")
    void generatedPreparePackedConstructShouldEmitPackedConstructorCall() {
        var clazz = newTestClass();
        var func = newFunction("prepare_emit_packed_ctor_call");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).appendInstruction(new ReturnInsn(null));
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var body = codegen.generateFuncBody(clazz, func);
        assertTrue(body.contains("__prepare__: // __prepare__"));
        assertTrue(body.contains("godot_new_PackedInt32Array()"));
    }

    @Test
    @DisplayName("construct_array should emit packed constructors for all supported Packed*Array types")
    void constructArrayShouldEmitPackedCtorForAllSupportedPackedArrayTypes() {
        for (var packedCase : packedCtorCases()) {
            var clazz = newTestClass();
            var func = newFunction("construct_" + packedCase.label() + "_array");
            func.createAndAddVariable("packed", packedCase.type());
            entry(func).appendInstruction(new ConstructArrayInsn("packed", null));
            clazz.addFunction(func);

            var body = generateBody(clazz, func);
            assertTrue(
                    body.contains(packedCase.constructorCall()),
                    () -> "Expected constructor call missing for " + packedCase.typeName() + ".\nBody:\n" + body
            );
        }
    }

    @Test
    @DisplayName("__prepare__ should inject packed construct_array for all supported Packed*Array types")
    void generateShouldInjectPackedConstructArrayIntoPrepareBlockForAllPackedTypes() {
        for (var packedCase : packedCtorCases()) {
            var clazz = newTestClass();
            var func = newFunction("prepare_inject_" + packedCase.label());
            func.createAndAddVariable("packed", packedCase.type());
            entry(func).appendInstruction(new ReturnInsn(null));
            clazz.addFunction(func);

            var module = new LirModule("test_module", List.of(clazz));
            var codegen = newCodegen(module, List.of(clazz));
            codegen.generate();

            var prepare = func.getBasicBlock("__prepare__");
            assertNotNull(prepare);
            var hasPackedArrayInsn = prepare.getInstructions().stream()
                    .filter(ConstructArrayInsn.class::isInstance)
                    .map(ConstructArrayInsn.class::cast)
                    .anyMatch(insn -> "packed".equals(insn.resultId()) && insn.className() == null);
            assertTrue(hasPackedArrayInsn, () -> "Missing construct_array injection for " + packedCase.typeName());

            var hasPackedBuiltinInsn = prepare.getInstructions().stream()
                    .filter(ConstructBuiltinInsn.class::isInstance)
                    .map(ConstructBuiltinInsn.class::cast)
                    .anyMatch(insn -> "packed".equals(insn.resultId()));
            assertFalse(hasPackedBuiltinInsn, () -> "Unexpected construct_builtin injection for " + packedCase.typeName());
        }
    }

    @Test
    @DisplayName("__prepare__ generated packed construct_array should emit constructor calls for all supported Packed*Array types")
    void generatedPreparePackedConstructShouldEmitPackedConstructorCallForAllPackedTypes() {
        for (var packedCase : packedCtorCases()) {
            var clazz = newTestClass();
            var func = newFunction("prepare_emit_" + packedCase.label());
            func.createAndAddVariable("packed", packedCase.type());
            entry(func).appendInstruction(new ReturnInsn(null));
            clazz.addFunction(func);

            var module = new LirModule("test_module", List.of(clazz));
            var codegen = newCodegen(module, List.of(clazz));
            codegen.generate();

            var body = codegen.generateFuncBody(clazz, func);
            assertTrue(body.contains("__prepare__: // __prepare__"));
            assertTrue(
                    body.contains(packedCase.constructorCall()),
                    () -> "Expected prepare constructor call missing for " + packedCase.typeName() + ".\nBody:\n" + body
            );
        }
    }

    @Test
    @DisplayName("generate should inject packed construct_array into default field init functions")
    void generateShouldInjectPackedConstructArrayIntoDefaultFieldInitFunctions() {
        var clazz = newTestClass();
        var propertyCases = new ArrayList<PackedPropertyCase>();
        for (var i = 0; i < packedCtorCases().size(); i++) {
            var packedCase = packedCtorCases().get(i);
            var propertyName = "packed_prop_" + i;
            clazz.addProperty(new LirPropertyDef(propertyName, packedCase.type()));
            propertyCases.add(new PackedPropertyCase(propertyName, packedCase));
        }

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        for (var propertyCase : propertyCases) {
            var initFuncName = "_field_init_" + propertyCase.propertyName();
            var initFunc = findFunctionByName(clazz, initFuncName);
            assertNotNull(initFunc, () -> "Missing init function " + initFuncName);

            var entryBlock = initFunc.getBasicBlock(initFunc.getEntryBlockId());
            assertNotNull(entryBlock, () -> "Missing entry block in " + initFuncName);

            var hasConstructArrayInsn = entryBlock.getInstructions().stream()
                    .filter(ConstructArrayInsn.class::isInstance)
                    .map(ConstructArrayInsn.class::cast)
                    .anyMatch(insn -> insn.className() == null);
            assertTrue(
                    hasConstructArrayInsn,
                    () -> "Field init function should use construct_array for " + propertyCase.packedCase().typeName()
            );

            var hasConstructBuiltinInsn = entryBlock.getInstructions().stream()
                    .anyMatch(ConstructBuiltinInsn.class::isInstance);
            assertFalse(
                    hasConstructBuiltinInsn,
                    () -> "Field init function should not use construct_builtin for " + propertyCase.packedCase().typeName()
            );

            var body = codegen.generateFuncBody(clazz, initFunc);
            assertTrue(
                    body.contains(propertyCase.packedCase().constructorCall()),
                    () -> "Expected field init constructor call missing for " + propertyCase.packedCase().typeName() + ".\nBody:\n" + body
            );
        }
    }

    @Test
    @DisplayName("__prepare__ construct_array should fail fast on type mismatch")
    void prepareConstructArrayTypeMismatchShouldFailFast() {
        var clazz = newTestClass();
        var func = new LirFunctionDef("prepare_array_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("arr", new GdArrayType(GdStringNameType.STRING_NAME));

        var prepare = new LirBasicBlock("__prepare__");
        prepare.appendInstruction(new ConstructArrayInsn("arr", "String"));
        func.addBasicBlock(prepare);
        func.setEntryBlockId("__prepare__");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_array type mismatch"));
    }

    @Test
    @DisplayName("__prepare__ construct_dictionary should fail fast on type mismatch")
    void prepareConstructDictionaryTypeMismatchShouldFailFast() {
        var clazz = newTestClass();
        var func = new LirFunctionDef("prepare_dictionary_mismatch");
        func.setReturnType(GdVoidType.VOID);
        func.createAndAddVariable("dict", new GdDictionaryType(GdStringNameType.STRING_NAME, GdVariantType.VARIANT));

        var prepare = new LirBasicBlock("__prepare__");
        prepare.appendInstruction(new ConstructDictionaryInsn("dict", "String", "Variant"));
        func.addBasicBlock(prepare);
        func.setEntryBlockId("__prepare__");
        clazz.addFunction(func);

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        var ex = assertThrows(InvalidInsnException.class, () -> codegen.generateFuncBody(clazz, func));
        assertTrue(ex.getMessage().contains("construct_dictionary key type mismatch"));
    }

    @Test
    @DisplayName("generate should emit typed array constructor with nil script carrier in default field init helpers")
    void generateShouldEmitTypedArrayCtorInDefaultFieldInitHelper() {
        var clazz = newTestClass();
        clazz.addProperty(new LirPropertyDef("payloads", new GdArrayType(GdStringNameType.STRING_NAME)));

        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        codegen.generate();

        var initFunc = findFunctionByName(clazz, "_field_init_payloads");
        assertNotNull(initFunc);
        assertEquals("__prepare__", initFunc.getEntryBlockId());
        var body = codegen.generateFuncBody(clazz, initFunc);

        assertTrue(body.contains("godot_new_Array_with_Array_int_StringName_Variant"), body);
        assertTrue(body.contains("godot_Variant __gdcc_tmp_array_script_"), body);
        assertTrue(body.contains("godot_new_Variant_nil();"), body);
        var arrayCtorCall = extractCall(body, "godot_new_Array_with_Array_int_StringName_Variant");
        assertFalse(arrayCtorCall.contains("NULL"), arrayCtorCall);
    }

    private LirClassDef newTestClass() {
        return new LirClassDef("Worker", "RefCounted", false, false, Map.of(), List.of(), List.of(), List.of());
    }

    private LirFunctionDef newFunction(String name) {
        var func = new LirFunctionDef(name);
        func.setReturnType(GdVoidType.VOID);
        var entry = new LirBasicBlock("entry");
        func.addBasicBlock(entry);
        func.setEntryBlockId("entry");
        return func;
    }

    private LirBasicBlock entry(LirFunctionDef functionDef) {
        return functionDef.getBasicBlock("entry");
    }

    private String generateBody(LirClassDef clazz, LirFunctionDef func) {
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz));
        return codegen.generateFuncBody(clazz, func);
    }

    private String generateBody(LirClassDef clazz, LirFunctionDef func, ExtensionAPI api) {
        var module = new LirModule("test_module", List.of(clazz));
        var codegen = newCodegen(module, List.of(clazz), api);
        return codegen.generateFuncBody(clazz, func);
    }

    private String extractCall(String body, String callName) {
        var callStart = body.indexOf(callName);
        assertTrue(callStart >= 0, body);
        var callEnd = body.indexOf(");", callStart);
        assertTrue(callEnd >= 0, body);
        return body.substring(callStart, callEnd + 2);
    }

    private void assertPackedArrayClassNameRejected(String className) {
        var clazz = newTestClass();
        var func = newFunction("construct_packed_array_with_blank_class_name");
        func.createAndAddVariable("packed", GdPackedNumericArrayType.PACKED_INT32_ARRAY);
        entry(func).appendInstruction(new ConstructArrayInsn("packed", className));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func));
        assertTrue(ex.getMessage().contains("must not provide class_name"));
    }

    private void assertInvalidArrayHint(ExtensionAPI api, String hintText) {
        var clazz = newTestClass();
        var func = newFunction("construct_array_invalid_hint_" + hintText);
        func.createAndAddVariable("arr", new GdArrayType(GdVariantType.VARIANT));
        entry(func).appendInstruction(new ConstructArrayInsn("arr", hintText));
        clazz.addFunction(func);

        var ex = assertThrows(InvalidInsnException.class, () -> generateBody(clazz, func, api));
        assertTrue(ex.getMessage().contains("construct_array '" + hintText + "' is not a valid type"));
    }

    private void assertAtomicConstructorCall(@NotNull GdType targetType,
                                             @NotNull List<GdType> argTypes,
                                             @NotNull String constructorName) {
        var clazz = newTestClass();
        var func = newFunction("construct_" + targetType.getTypeName() + "_atomic");
        var operands = new ArrayList<LirInstruction.Operand>(argTypes.size());
        for (var i = 0; i < argTypes.size(); i++) {
            var argId = "arg_" + i;
            func.createAndAddVariable(argId, argTypes.get(i));
            operands.add(new LirInstruction.VariableOperand(argId));
        }
        func.createAndAddVariable("result", targetType);

        entry(func).appendInstruction(new ConstructBuiltinInsn("result", operands));
        clazz.addFunction(func);

        var body = generateBody(clazz, func, apiWithAtomicConstructors());
        assertTrue(body.contains(constructorName), body);
    }

    private ArrayList<LirInstruction.Operand> addFloatArgs(@NotNull LirFunctionDef func, int count) {
        var args = new ArrayList<LirInstruction.Operand>(count);
        for (var i = 0; i < count; i++) {
            var argId = "arg_" + i;
            func.createAndAddVariable(argId, GdFloatType.FLOAT);
            args.add(new LirInstruction.VariableOperand(argId));
        }
        return args;
    }

    private LirFunctionDef findFunctionByName(LirClassDef clazz, String functionName) {
        for (var function : clazz.getFunctions()) {
            if (functionName.equals(function.getName())) {
                return function;
            }
        }
        return null;
    }

    private List<PackedCtorCase> packedCtorCases() {
        return List.of(
                new PackedCtorCase("packed_byte", "PackedByteArray", GdPackedNumericArrayType.PACKED_BYTE_ARRAY),
                new PackedCtorCase("packed_int32", "PackedInt32Array", GdPackedNumericArrayType.PACKED_INT32_ARRAY),
                new PackedCtorCase("packed_int64", "PackedInt64Array", GdPackedNumericArrayType.PACKED_INT64_ARRAY),
                new PackedCtorCase("packed_float32", "PackedFloat32Array", GdPackedNumericArrayType.PACKED_FLOAT32_ARRAY),
                new PackedCtorCase("packed_float64", "PackedFloat64Array", GdPackedNumericArrayType.PACKED_FLOAT64_ARRAY),
                new PackedCtorCase("packed_string", "PackedStringArray", GdPackedStringArrayType.PACKED_STRING_ARRAY),
                new PackedCtorCase("packed_vector2", "PackedVector2Array", GdPackedVectorArrayType.PACKED_VECTOR2_ARRAY),
                new PackedCtorCase("packed_vector3", "PackedVector3Array", GdPackedVectorArrayType.PACKED_VECTOR3_ARRAY),
                new PackedCtorCase("packed_vector4", "PackedVector4Array", GdPackedVectorArrayType.PACKED_VECTOR4_ARRAY)
        );
    }

    private List<FlatFloatHelperCtorCase> flatFloatHelperCtorCases() {
        return List.of(
                new FlatFloatHelperCtorCase(
                        "transform2d",
                        GdTransform2DType.TRANSFORM2D,
                        6,
                        "godot_new_Transform2D_with_float_float_float_float_float_float"
                ),
                new FlatFloatHelperCtorCase(
                        "transform3d",
                        GdTransform3DType.TRANSFORM3D,
                        12,
                        "godot_new_Transform3D_with_float_float_float_float_float_float_float_float_float_float_float_float"
                ),
                new FlatFloatHelperCtorCase(
                        "basis",
                        GdBasisType.BASIS,
                        9,
                        "godot_new_Basis_with_float_float_float_float_float_float_float_float_float"
                ),
                new FlatFloatHelperCtorCase(
                        "projection",
                        GdProjectionType.PROJECTION,
                        16,
                        "godot_new_Projection_with_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float_float"
                )
        );
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses) {
        return newCodegen(module, gdccClasses, apiWithPackedConstructors());
    }

    private CCodegen newCodegen(LirModule module, List<LirClassDef> gdccClasses, ExtensionAPI api) {
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

    private ExtensionAPI apiWithPackedConstructors() {
        var packedBuiltins = new ArrayList<ExtensionBuiltinClass>();
        for (var packedCase : packedCtorCases()) {
            packedBuiltins.add(newZeroArgPackedBuiltinClass(packedCase.typeName()));
        }
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                packedBuiltins,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionAPI apiWithStringFamilyConstructors() {
        return apiWithBuiltins(List.of(
                newBuiltinClass(
                        "String",
                        List.of(newConstructor("String", "StringName"))
                ),
                newBuiltinClass(
                        "StringName",
                        List.of(newConstructor("StringName", "String"))
                )
        ));
    }

    private ExtensionAPI apiWithStringFamilyBuiltinsWithoutConstructors() {
        return apiWithBuiltins(List.of(
                newBuiltinClass("String", List.of()),
                newBuiltinClass("StringName", List.of())
        ));
    }

    private ExtensionAPI apiWithAtomicConstructors() {
        return apiWithBuiltins(List.of(
                newBuiltinClass(
                        "bool",
                        List.of(
                                newConstructor("bool", "bool"),
                                newConstructor("bool", "int"),
                                newConstructor("bool", "float")
                        )
                ),
                newBuiltinClass(
                        "int",
                        List.of(
                                newConstructor("int", "int"),
                                newConstructor("int", "float"),
                                newConstructor("int", "bool")
                        )
                ),
                newBuiltinClass(
                        "float",
                        List.of(
                                newConstructor("float", "float"),
                                newConstructor("float", "int"),
                                newConstructor("float", "bool")
                        )
                )
        ));
    }

    private ExtensionAPI apiWithBuiltins(List<ExtensionBuiltinClass> builtins) {
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                builtins,
                List.of(),
                List.of(),
                List.of()
        );
    }

    private ExtensionAPI apiWithConstructibleObjectClasses() {
        var parseString = new ExtensionGdClass.ClassMethod(
                "parse_string",
                true,
                false,
                true,
                false,
                1L,
                List.of(),
                new ExtensionGdClass.ClassMethod.ClassMethodReturn("Variant"),
                List.of(new ExtensionFunctionArgument("json_string", "String", null, null))
        );
        return new ExtensionAPI(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(new ExtensionUtilityFunction("print", "", "general", true, (int) 2648703342L, List.of())),
                List.of(),
                List.of(
                        new ExtensionGdClass("Object", false, true, "", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("Node", false, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("RefCounted", true, true, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass("EditorOnlyThing", false, false, "Object", "core", List.of(), List.of(), List.of(), List.of(), List.of()),
                        new ExtensionGdClass(
                                "JSON",
                                false,
                                true,
                                "Object",
                                "core",
                                List.of(),
                                List.of(parseString),
                                List.of(),
                                List.of(),
                                List.of()
                        )
                ),
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass newBuiltinClass(
            String typeName,
            List<ExtensionBuiltinClass.ConstructorInfo> constructors
    ) {
        return new ExtensionBuiltinClass(
                typeName,
                false,
                List.of(),
                List.of(),
                List.of(),
                constructors,
                List.of(),
                List.of()
        );
    }

    private ExtensionBuiltinClass newZeroArgPackedBuiltinClass(String typeName) {
        return newBuiltinClass(typeName, List.of(new ExtensionBuiltinClass.ConstructorInfo(typeName, 0, List.of())));
    }

    private ExtensionBuiltinClass.ConstructorInfo newConstructor(String owner, String argType) {
        return new ExtensionBuiltinClass.ConstructorInfo(
                owner,
                0,
                List.of(new ExtensionFunctionArgument("from", argType, null, null))
        );
    }

    private record PackedCtorCase(String label, String typeName, GdType type) {
        private String constructorCall() {
            return "godot_new_" + typeName + "()";
        }
    }

    private record FlatFloatHelperCtorCase(
            String label,
            GdType targetType,
            int argCount,
            String constructorName
    ) {
    }

    private record PackedPropertyCase(String propertyName, PackedCtorCase packedCase) {
    }
}
