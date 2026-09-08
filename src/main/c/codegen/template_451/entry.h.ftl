<#-- @ftlvariable name="module" type="gd.script.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="gd.script.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="inheritanceOrderedClassDefs" type="java.util.List<gd.script.gdcc.lir.LirClassDef>" -->
<#include "trim.ftl">
<#include "func.ftl">
#ifndef GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H
#define GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H

#include <godot_binding.h>
static GDExtensionClassLibraryPtr class_library = NULL;
#include <gdcc_helper.h>
<#if helper.hasCoroutineFunctions()>
<#-- Coroutine runtime types (mco_coro, gdcc_coro_state_header/desc). Only modules with at -->
<#-- least one `is_coroutine="true"` function see this include, keeping sync-only output stable. -->
#include <gdcc_coroutine.h>
</#if>

struct GDExtensionInitializationStatus {
    godot_bool initialized;
};

void initialize(void* userdata, GDExtensionInitializationLevel p_level);
void deinitialize(void* userdata, GDExtensionInitializationLevel p_level);

// Class declarations

<#list inheritanceOrderedClassDefs as classDef>
typedef struct ${classDef.name} ${classDef.name};
</#list>

#include "object_fat_ptr_types.h"

<#-- Vtable typedefs in base-before-derived order: a child table embeds its nearest -->
<#-- slot-introducing ancestor's table BY VALUE as `_super` (the vtable `_super` chain, which -->
<#-- skips pass-through/override-only classes — never to be confused with the wrapper `_super` -->
<#-- below), so the ancestor typedef must be complete first. Only slot introducers own a -->
<#-- typedef; override-only classes reuse that ancestor type, pass-through classes own nothing. -->
<#list inheritanceOrderedClassDefs as classDef>
    <#if helper.vtablePlanner().introducesSlot(classDef.name)>
        <#assign vtableTypeName = helper.renderVtableInstanceTypeName(classDef.name)>
typedef struct ${vtableTypeName} {
        <#assign superMemberDecl = helper.renderVtableSuperMemberDecl(classDef)>
        <#if superMemberDecl?has_content>
    ${superMemberDecl}
        </#if>
        <#list helper.vtablePlanner().slots(classDef.name) as slotEntry>
            <#-- Inherited slots arrive through the embedded `_super` prefix; only slots this -->
            <#-- class introduces are declared in its own segment. -->
            <#if slotEntry.slot.introducerClassName == classDef.name>
    ${helper.renderVtableSlotMemberDecl(slotEntry.slot)};
            </#if>
        </#list>
} ${vtableTypeName};

    </#if>
</#list>

<#-- Struct definitions iterate the base-before-derived inheritance order (never raw module -->
<#-- order): a non-root wrapper embeds its parent BY VALUE as the first field (`Parent _super`), -->
<#-- which is only legal once the parent struct definition is complete. -->
<#list inheritanceOrderedClassDefs as classDef>
// Class definition for ${classDef.name}

struct ${classDef.name} {
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName} _super;
    <#else>
        GDExtensionObjectPtr _object;
        <#-- The vtable pointer physically exists only in the root segment and only when the -->
        <#-- whole hierarchy carries at least one slot; subclasses share it through the -->
        <#-- offset-0 wrapper embedding. `const void*` sidesteps typedef-order and empty-struct -->
        <#-- portability concerns (§2.2). -->
        <#if helper.requiresVtableField(classDef.name)>
        const void* _vtable;
        </#if>
    </#if>
    <#list classDef.properties as property>
        <#if !property.static>
            ${helper.renderGdTypeInC(property.type)} ${property.name};
        </#if>
    </#list>
};


static inline GDExtensionObjectPtr ${classDef.name}_object_ptr(${classDef.name}* self);
static inline void ${classDef.name}_set_object_ptr(${classDef.name}* self, GDExtensionObjectPtr obj);
<#if helper.vtablePlanner().introducesSlot(classDef.name)>
<#-- Vtable accessor declaration (introducers only; override-only classes reuse the nearest -->
<#-- introducer ancestor's accessor, pass-through classes own none). -->
static inline const ${helper.renderVtableInstanceTypeName(classDef.name)}* ${helper.renderVtableAccessorName(classDef.name)}(${classDef.name}* self);
</#if>

const GDExtensionInstanceBindingCallbacks ${classDef.name}_class_binding_callbacks = {
    NULL,
    NULL,
    NULL,
};

static void ${classDef.name}_class_bind_methods();

GDExtensionObjectPtr ${classDef.name}_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize);

void ${classDef.name}_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance);

void ${classDef.name}_class_constructor(${classDef.name}* self);

void ${classDef.name}_class_destructor(${classDef.name}* self);

void ${classDef.name}_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed);

void* ${classDef.name}_class_get_virtual_with_data(void* p_class_userdata, GDExtensionConstStringNamePtr p_name, uint32_t p_hash);

void ${classDef.name}_class_call_virtual_with_data(GDExtensionClassInstancePtr p_instance, GDExtensionConstStringNamePtr p_name, void* p_virtual_call_userdata, const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_ret);

// Methods for ${classDef.name}

<#list classDef.functions as func>
<#-- Lambda capture heap block. Empty structs are omitted so C stay portable. -->
<#if func.lambda && func.captureCount gt 0>
typedef struct <@lambdaCaptureName classDef func/> {
<#list func.captureList as capture>
    ${helper.renderLambdaCaptureFieldTypeInC(capture.type)} ${capture.name};
</#list>
} <@lambdaCaptureName classDef func/>;
</#if>
<#-- Normal function. Coroutine lambdas have no plain function surface: their body is the -->
<#-- `__coro_body` in the coroutine section and the Callable ABI enters through the start thunk. -->
<#if !func.coroutine || !func.lambda>
<@funcHeader helper classDef func/>;
</#if>
<#if func.lambda && func.coroutine>
<#-- Forward declarations for the coroutine lambda `call_func` below: the full state-class -->
<#-- declarations live in the later coroutine section, after this per-function loop, so the -->
<#-- Callable dispatch needs the start thunk (and the non-void move_result accessor) early. -->
<@coroStartThunkHeader helper classDef func/>;
<#if func.returnType.typeName != "void">
${helper.renderGdTypeInC(func.returnType)} ${helper.renderCoroMoveResultFuncName(classDef, func)}(gdcc_coro_state_header *coro_header);
</#if>
</#if>
<#if func.lambda>
static GDExtensionInt ${helper.renderLambdaGetArgumentCountFuncName(classDef, func)}(void *userdata, GDExtensionBool *r_is_valid) {
    (void)userdata;
    if (r_is_valid != NULL) {
        *r_is_valid = true;
    }
    return ${func.parameterCount};
}

static GDExtensionBool ${helper.renderLambdaIsValidFuncName(classDef, func)}(void *userdata) {
<#if func.captureCount gt 0>
    if (userdata == NULL) {
        return false;
    }
    <#assign leadingCapture = func.captureList[0]>
    <#if leadingCapture.name == "self" && helper.checkObjectType(leadingCapture.type)>
    <@lambdaCaptureName classDef func/> *captures = (<@lambdaCaptureName classDef func/> *)userdata;
    // ObjectDB is the authority: Godot disconnects / rejects calls when object_id is dead.
    return godot_object_get_instance_from_id(captures->self.instance_id) != NULL;
    <#else>
    (void)userdata;
    return true;
    </#if>
<#else>
    (void)userdata;
    return true;
</#if>
}

static void ${helper.renderLambdaFreeFuncName(classDef, func)}(void *userdata) {
<#if func.captureCount gt 0>
    if (userdata == NULL) {
        return;
    }
    <@lambdaCaptureName classDef func/> *captures = (<@lambdaCaptureName classDef func/> *)userdata;
    <#list func.captureList as capture>
        <#assign freeStmt = helper.renderLambdaCaptureFreeStmt(capture.type, "captures->" + capture.name)>
        <#if freeStmt?has_content>
    ${freeStmt}
        </#if>
    </#list>
    godot_mem_free(userdata);
<#else>
    (void)userdata;
</#if>
}

static void ${helper.renderLambdaCallFuncName(classDef, func)}(
        void *userdata,
        const GDExtensionConstVariantPtr *p_args,
        GDExtensionInt p_argument_count,
        GDExtensionVariantPtr r_return,
        GDExtensionCallError *r_error) {
    if (r_error != NULL) {
        r_error->error = GDEXTENSION_CALL_OK;
        r_error->argument = 0;
        r_error->expected = 0;
    }
    if (p_argument_count < ${func.parameterCount}) {
        godot_variant_new_nil(r_return);
        if (r_error != NULL) {
            r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;
            r_error->expected = ${func.parameterCount};
        }
        return;
    }
    if (p_argument_count > ${func.parameterCount}) {
        godot_variant_new_nil(r_return);
        if (r_error != NULL) {
            r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
            r_error->expected = ${func.parameterCount};
        }
        return;
    }
<#if func.captureCount gt 0>
    if (userdata == NULL) {
        godot_variant_new_nil(r_return);
        if (r_error != NULL) {
            r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
        }
        return;
    }
    <@lambdaCaptureName classDef func/> *captures = (<@lambdaCaptureName classDef func/> *)userdata;
<#else>
    (void)userdata;
</#if>
    <#list func.parameters as paramType>
    <#if paramType.type.typeName != "Variant">
    const GDExtensionVariantType arg${paramType_index}_type = godot_variant_get_type(p_args[${paramType_index}]);
    if (!${helper.renderCallWrapperVariantTypeGate(paramType.type, "arg${paramType_index}_type")}) {
        godot_variant_new_nil(r_return);
        if (r_error != NULL) {
            r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
            r_error->expected = GDEXTENSION_VARIANT_TYPE_${paramType.type.gdExtensionType.name()};
            r_error->argument = ${paramType_index};
        }
        return;
    }
    </#if>
    </#list>
    <#list func.parameters as paramType>
        <#if helper.needsTypedArrayCallGuard(paramType.type)>
        <#assign probeVarName = "probe" + paramType_index>
        <#assign expectedBuiltinType = helper.renderTypedArrayGuardBuiltinTypeLiteral(paramType.type)>
        {
            godot_Array ${probeVarName} = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[${paramType_index}]);
            godot_bool typed_mismatch = godot_Array_get_typed_builtin(&${probeVarName}) != ${expectedBuiltinType};
            <#if helper.isTypedArrayGuardObjectLeaf(paramType.type)>
                <#assign expectedClassNameExpr = helper.renderTypedArrayGuardClassNameExpr(paramType.type)>
            if (!typed_mismatch) {
                godot_StringName ${probeVarName}_class_name = godot_Array_get_typed_class_name(&${probeVarName});
                godot_Variant ${probeVarName}_script = godot_Array_get_typed_script(&${probeVarName});
                godot_Variant ${probeVarName}_script_nil = godot_new_Variant_nil();
                godot_Variant ${probeVarName}_script_is_null_result;
                godot_bool ${probeVarName}_script_is_null_valid = false;
                godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &${probeVarName}_script, &${probeVarName}_script_nil, (GDExtensionUninitializedVariantPtr)&${probeVarName}_script_is_null_result, &${probeVarName}_script_is_null_valid);
                const godot_bool ${probeVarName}_script_is_null = ${probeVarName}_script_is_null_valid && godot_new_bool_with_Variant(&${probeVarName}_script_is_null_result);
                typed_mismatch = !godot_StringName_op_equal_StringName(&${probeVarName}_class_name, ${expectedClassNameExpr}) || !${probeVarName}_script_is_null;
                if (${probeVarName}_script_is_null_valid) {
                    godot_Variant_destroy(&${probeVarName}_script_is_null_result);
                }
                godot_Variant_destroy(&${probeVarName}_script_nil);
                godot_Variant_destroy(&${probeVarName}_script);
                godot_StringName_destroy(&${probeVarName}_class_name);
            }
            </#if>
            godot_Array_destroy(&${probeVarName});
            if (typed_mismatch) {
                godot_variant_new_nil(r_return);
                if (r_error != NULL) {
                    r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
                    r_error->expected = GDEXTENSION_VARIANT_TYPE_ARRAY;
                    r_error->argument = ${paramType_index};
                }
                return;
            }
        }
        </#if>
    </#list>
    <#list func.parameters as paramType>
        <#if helper.needsTypedDictionaryCallGuard(paramType.type)>
        <#assign probeVarName = "probe" + paramType_index>
        {
            godot_Dictionary ${probeVarName} = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[${paramType_index}]);
            godot_bool typed_mismatch = false;
            <#list ["key", "value"] as typedSide>
                <#assign expectedBuiltinType = helper.renderTypedDictionaryGuardBuiltinTypeLiteral(paramType.type, typedSide)>
            if (!typed_mismatch) {
                typed_mismatch = godot_Dictionary_get_typed_${typedSide}_builtin(&${probeVarName}) != ${expectedBuiltinType};
            }
                <#if helper.isTypedDictionaryGuardObjectLeaf(paramType.type, typedSide)>
                    <#assign expectedClassNameExpr = helper.renderTypedDictionaryGuardClassNameExpr(paramType.type, typedSide)>
            if (!typed_mismatch) {
                godot_StringName ${probeVarName}_${typedSide}_class_name = godot_Dictionary_get_typed_${typedSide}_class_name(&${probeVarName});
                godot_Variant ${probeVarName}_${typedSide}_script = godot_Dictionary_get_typed_${typedSide}_script(&${probeVarName});
                godot_Variant ${probeVarName}_${typedSide}_script_nil = godot_new_Variant_nil();
                godot_Variant ${probeVarName}_${typedSide}_script_is_null_result;
                godot_bool ${probeVarName}_${typedSide}_script_is_null_valid = false;
                godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &${probeVarName}_${typedSide}_script, &${probeVarName}_${typedSide}_script_nil, (GDExtensionUninitializedVariantPtr)&${probeVarName}_${typedSide}_script_is_null_result, &${probeVarName}_${typedSide}_script_is_null_valid);
                const godot_bool ${probeVarName}_${typedSide}_script_is_null = ${probeVarName}_${typedSide}_script_is_null_valid && godot_new_bool_with_Variant(&${probeVarName}_${typedSide}_script_is_null_result);
                typed_mismatch = !godot_StringName_op_equal_StringName(&${probeVarName}_${typedSide}_class_name, ${expectedClassNameExpr}) || !${probeVarName}_${typedSide}_script_is_null;
                if (${probeVarName}_${typedSide}_script_is_null_valid) {
                    godot_Variant_destroy(&${probeVarName}_${typedSide}_script_is_null_result);
                }
                godot_Variant_destroy(&${probeVarName}_${typedSide}_script_nil);
                godot_Variant_destroy(&${probeVarName}_${typedSide}_script);
                godot_StringName_destroy(&${probeVarName}_${typedSide}_class_name);
            }
                </#if>
            </#list>
            godot_Dictionary_destroy(&${probeVarName});
            if (typed_mismatch) {
                godot_variant_new_nil(r_return);
                if (r_error != NULL) {
                    r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
                    r_error->expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY;
                    r_error->argument = ${paramType_index};
                }
                return;
            }
        }
        </#if>
    </#list>
    <#list func.parameters as paramType>
        <#assign argCleanupStmt = helper.renderCallWrapperDestroyStmt(paramType.type, "arg${paramType_index}")>
        <#if paramType.type.typeName != "Variant">
            <#assign argTypeExpr = "arg${paramType_index}_type">
        <#else>
            <#assign argTypeExpr = "NULL">
        </#if>
        <#if argCleanupStmt?has_content>
        ${helper.renderGdTypeInC(paramType.type)} arg${paramType_index} = ${helper.renderCallWrapperUnpackExpr(paramType.type, "(GDExtensionVariantPtr)p_args[${paramType_index}]", argTypeExpr)};
        <#else>
        const ${helper.renderGdTypeInC(paramType.type)} arg${paramType_index} = ${helper.renderCallWrapperUnpackExpr(paramType.type, "(GDExtensionVariantPtr)p_args[${paramType_index}]", argTypeExpr)};
        </#if>
    </#list>
    <#assign callArgs>
        <#list func.parameters as paramType>${helper.renderValueRef(paramType.type, "arg${paramType_index}")}<#if paramType_has_next || func.captureCount gt 0>, </#if></#list><#if func.captureCount gt 0>captures</#if>
    </#assign>
    <#if func.coroutine>
    <#-- Coroutine lambda (frontend_await_implementation.md §5): the Callable ABI enters -->
    <#-- through the start thunk with the capture block as the tail argument, then dispatches -->
    <#-- on done/suspend. A suspended coroutine always hands the state object out as a Variant -->
    <#-- regardless of the declared return type (the Callable ABI has only a Variant return -->
    <#-- channel) — deliberately unlike the named engine entry, which errors on typed -->
    <#-- non-Variant suspension. The single-exit shape keeps the trailing argument destroys shared. -->
    godot_Object* coro_state_obj = ${helper.renderCoroStartThunkName(classDef, func)}(${callArgs?trim});
    if (coro_state_obj == NULL) {
        godot_variant_new_nil(r_return);
        if (r_error != NULL) {
            r_error->error = GDEXTENSION_CALL_ERROR_INVALID_METHOD;
        }
    } else {
        gdcc_coro_state_header* coro_header = gdcc_coro_state_identify(coro_state_obj);
        if (coro_header == NULL) {
            release_object(coro_state_obj);
            godot_variant_new_nil(r_return);
            GDCC_PRINT_RUNTIME_ERROR("gdcc: coroutine lambda start returned an invalid state object", __func__, __FILE__, __LINE__);
        } else if (coro_header->done) {
        <#if func.returnType.typeName != "void">
            // Synchronous completion fast path: move the typed result out of the frame slot.
            ${helper.renderGdTypeInC(func.returnType)} r = ${helper.renderCoroMoveResultFuncName(classDef, func)}(coro_header);
            godot_Variant ret = ${helper.renderPackFunctionName(func.returnType)}(${helper.renderValueRef(func.returnType, "r")});
            godot_variant_new_copy(r_return, &ret);
            godot_Variant_destroy(&ret);
            <#assign returnObjectConsumeStmt = helper.renderCallWrapperOwnedObjectReturnConsumeStmt(func.returnType, "r")>
            <#if returnObjectConsumeStmt?has_content>
            ${returnObjectConsumeStmt}
            </#if>
            <#assign returnCleanupStmt = helper.renderCallWrapperDestroyStmt(func.returnType, "r")>
            <#if returnCleanupStmt?has_content>
            ${returnCleanupStmt}
            </#if>
        <#else>
            godot_variant_new_nil(r_return);
        </#if>
            release_object(coro_state_obj);
        } else {
            // Suspended: the Variant retains the RefCounted state object, so the thunk's OWNED
            // reference is released right after wrapping.
            godot_Variant coro_state_variant = godot_new_Variant_with_Object(coro_state_obj);
            godot_variant_new_copy(r_return, &coro_state_variant);
            godot_Variant_destroy(&coro_state_variant);
            release_object(coro_state_obj);
        }
    }
    <#else>
    <#if func.returnType.typeName != "void">
        ${helper.renderGdTypeInC(func.returnType)} r = ${classDef.name}_${func.name}(${callArgs?trim});
        godot_Variant ret = ${helper.renderPackFunctionName(func.returnType)}(${helper.renderValueRef(func.returnType, "r")});
        godot_variant_new_copy(r_return, &ret);
        godot_Variant_destroy(&ret);
        <#assign returnObjectConsumeStmt = helper.renderCallWrapperOwnedObjectReturnConsumeStmt(func.returnType, "r")>
        <#if returnObjectConsumeStmt?has_content>
        ${returnObjectConsumeStmt}
        </#if>
        <#assign returnCleanupStmt = helper.renderCallWrapperDestroyStmt(func.returnType, "r")>
        <#if returnCleanupStmt?has_content>
        ${returnCleanupStmt}
        </#if>
    <#else>
        godot_variant_new_nil(r_return);
        (${classDef.name}_${func.name}(${callArgs?trim}));
    </#if>
    </#if>
    <#assign argCount = func.parameters?size>
    <#list func.parameters?reverse as paramType>
        <#assign argIndex = argCount - paramType_index - 1>
        <#assign argCleanupStmt = helper.renderCallWrapperDestroyStmt(paramType.type, "arg${argIndex}")>
        <#if argCleanupStmt?has_content>
        ${argCleanupStmt}
        </#if>
    </#list>
}
</#if>
</#list>

</#list>

<#if helper.hasCoroutineFunctions()>
// Hidden coroutine state classes (frontend_await_implementation.md §5-§6)
// One state class per `is_coroutine="true"` function: direct RefCounted child, runtime-only,
// never exposed. The wrapper root field is `_object` (no GDCC `_super` chain); the common
// `gdcc_coro_state_header` follows it and is exposed through the dedicated coroutine binding
// token. Typed parameter fields are the only owning parameter storage; the typed return slot
// plus its written flag sit behind it (non-void coroutines only).
<#list module.classDefs as classDef>
<#list classDef.functions as func>
<#if func.coroutine>
<#assign stateName = helper.renderCoroStateClassName(classDef, func)>
typedef struct ${stateName} ${stateName};

struct ${stateName} {
    GDExtensionObjectPtr _object;
    gdcc_coro_state_header ${helper.renderCoroHeaderField()};
    <#list func.parameters as param>
    ${helper.renderGdTypeInC(param.type)} ${helper.renderCoroParamFieldPrefix()}${param.name};
    </#list>
    <#-- Coroutine lambda captures: per-call owning copies filled by the start -->
    <#-- thunk from the borrowed capture block; destroyed exactly once by free_instance. -->
    <#list func.captureList as capture>
    ${helper.renderLambdaCaptureFieldTypeInC(capture.type)} ${helper.renderCoroCaptureFieldPrefix()}${capture.name};
    </#list>
    <#if func.returnType.typeName != "void">
    ${helper.renderGdTypeInC(func.returnType)} ${helper.renderCoroRetField()};
    godot_bool ${helper.renderCoroRetInitializedField()};
    </#if>
};

const GDExtensionInstanceBindingCallbacks ${stateName}_class_binding_callbacks = {
    NULL,
    NULL,
    NULL,
};

GDExtensionObjectPtr ${stateName}_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize);

void ${stateName}_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance);

void ${stateName}_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed);

static void ${helper.renderCoroPackResultFuncName(classDef, func)}(gdcc_coro_state_header *coro_header);
static void ${helper.renderCoroCopyRetSlotFuncName(classDef, func)}(gdcc_coro_state_header *coro_header, void *out_typed);
static void ${helper.renderCoroDestroyRetSlotFuncName(classDef, func)}(gdcc_coro_state_header *coro_header);
static void ${helper.renderCoroEmitCompletedFuncName(classDef, func)}(gdcc_coro_state_header *coro_header);
<#if func.returnType.typeName != "void">
${helper.renderGdTypeInC(func.returnType)} ${helper.renderCoroMoveResultFuncName(classDef, func)}(gdcc_coro_state_header *coro_header);
</#if>

void ${helper.renderCoroBodyFunctionName(classDef, func)}(mco_coro *${helper.renderCoroCoParam()});

<@coroStartThunkHeader helper classDef func/>;
</#if>
</#list>
</#list>

</#if>
<#assign operatorEvaluatorHelperSpecs = helper.collectOperatorEvaluatorHelperSpecs(module)>
<#if operatorEvaluatorHelperSpecs?size gt 0>
// Operator evaluator helpers
// Internal signatures use fat-pointer object values; Godot's evaluator ABI still takes raw object pointer slots.
<#list operatorEvaluatorHelperSpecs as spec>
static inline ${helper.renderOperatorEvaluatorHelperReturnTypeInC(spec.returnType)} ${spec.functionName}(
    ${helper.renderOperatorEvaluatorHelperTypeInC(spec.leftType)} left<#if !spec.unary>,
    ${helper.renderOperatorEvaluatorHelperTypeInC(spec.rightType)} right</#if>
) {
    static GDExtensionPtrOperatorEvaluator evaluator = NULL;
    if (evaluator == NULL) {
        evaluator = godot_variant_get_ptr_operator_evaluator(
            ${spec.operatorEnumLiteral},
            ${spec.leftVariantTypeEnumLiteral},
            <#if spec.unary>GDEXTENSION_VARIANT_TYPE_NIL<#else>${spec.rightVariantTypeEnumLiteral}</#if>
        );
        if (evaluator == NULL) {
            GDCC_PRINT_RUNTIME_ERROR("operator evaluator is unavailable: ${spec.functionName}", __func__, __FILE__, __LINE__);
            return ${helper.renderDefaultValueExprInC(spec.returnType)};
        }
    }
    ${helper.renderOperatorEvaluatorObjectRawSlotDecl(spec.leftType, "left")}<#if !spec.unary>${helper.renderOperatorEvaluatorObjectRawSlotDecl(spec.rightType, "right")}</#if>
    // Operator evaluators assign into an existing carrier; destroyable returns must start initialized.
    // Object returns (if any) use a raw carrier then ownership-neutral _from_raw. Live production paths
    // today only need object operands (e.g. String in Object); object return is defensive for metadata.
    ${helper.renderOperatorEvaluatorResultCarrierTypeInC(spec.returnType)} result = { 0 };
    evaluator(
        ${helper.renderOperatorEvaluatorArgExpr(spec.leftType, "left")},
        <#if spec.unary>NULL<#else>${helper.renderOperatorEvaluatorArgExpr(spec.rightType, "right")}</#if>,
        &result
    );
    return ${helper.renderOperatorEvaluatorReturnExpr(spec.returnType, "result")};
}
</#list>
</#if>

#include "engine_method_binds.h"

// Method binding helpers

<#list helper.bindingDataList as bindingData>
<#assign paramCount = bindingData.paramTypes?size>
<#-- Default-slot contract: defaults form a contiguous trailing suffix, so -->
<#-- requiredCount = paramCount - defaultSlotCount locates the fill range. -->
<#assign requiredCount = paramCount - bindingData.defaultSlotCount>
<#assign defaultFlavor = (bindingData.defaultSlotCount gt 0)>
<#if defaultFlavor>
<#-- Per-shape default-fill userdata layout, shared by all methods with this ABI shape; the -->
<#-- per-method exclusive instances live at the registration site in entry.c. Default function -->
<#-- pointers are typed per slot (default return types are heterogeneous within one method); -->
<#-- the instance flavor takes owner fat self as the leading argument, matching the synthetic -->
<#-- shell ABI (non-static, first parameter self). -->
typedef struct {
    <#-- impl keeps the exact impl signature so no function-pointer <-> void* round-trip is -->
    <#-- involved; only the userdata STRUCT address crosses the bind helper as void*. -->
    ${helper.renderGdTypeInC(bindingData.returnType)} (*impl)(<#if !bindingData.staticMethod>${helper.renderRegisteredMethodSelfFatType(bindingData)}<#if paramCount gt 0>, </#if></#if><#list bindingData.paramTypes as paramType>${helper.renderGdTypeRefInC(paramType)}<#if paramType_has_next>, </#if></#list>);
    <#list 0..(bindingData.defaultSlotCount - 1) as defIndex>
    ${helper.renderGdTypeInC(bindingData.paramTypes[requiredCount + defIndex])} (*def${defIndex})(<#if !bindingData.staticMethod>${helper.renderRegisteredMethodSelfFatType(bindingData)}</#if>);
    </#list>
} ${helper.renderDefaultUserdataTypeName(bindingData)};

</#if>
static void call${helper.renderFuncBindName(bindingData)}(
    void* method_userdata,
    GDExtensionClassInstancePtr p_instance, const GDExtensionConstVariantPtr* p_args, GDExtensionInt p_argument_count,
    GDExtensionVariantPtr r_return, GDExtensionCallError* r_error) {
<#--     Check argument count: the argc-aware flavor admits [requiredCount, paramCount].-->
    if (p_argument_count < ${requiredCount}) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_FEW_ARGUMENTS;
        r_error->expected = ${requiredCount};
        return;
    }
    if (p_argument_count > ${paramCount}) {
        r_error->error = GDEXTENSION_CALL_ERROR_TOO_MANY_ARGUMENTS;
        r_error->expected = ${paramCount};
        return;
    }

<#--Check the argument type. -->
<#--Variant outward slots are encoded as NIL metadata, so only non-Variant -->
<#--parameters keep the runtime gate here. Non-exact inbound exceptions must be -->
<#--accepted by this gate before wrapper-local materialization runs below. -->
<#--Default-slot gates only run when the caller actually supplied that argument. -->
    <#list bindingData.paramTypes as paramType>
    <#if paramType.typeName != "Variant">
    <#if paramType_index gte requiredCount>if (p_argument_count > ${paramType_index}) {</#if>
    const GDExtensionVariantType arg${paramType_index}_type = godot_variant_get_type(p_args[${paramType_index}]);
    if (!${helper.renderCallWrapperVariantTypeGate(paramType, "arg${paramType_index}_type")}) {
        r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
        r_error->expected = GDEXTENSION_VARIANT_TYPE_${paramType.gdExtensionType.name()};
        r_error->argument = ${paramType_index};
        return;
    }
    <#if paramType_index gte requiredCount>}</#if>
    </#if>
    </#list>

<#--Typed-container preflight stays ahead of wrapper-local unpack/materialization so -->
<#--mismatches can return without introducing a second cleanup contract for partially -->
<#--materialized locals.-->
    <#list bindingData.paramTypes as paramType>
        <#if helper.needsTypedArrayCallGuard(paramType)>
        <#assign probeVarName = "probe" + paramType_index>
        <#assign expectedBuiltinType = helper.renderTypedArrayGuardBuiltinTypeLiteral(paramType)>
        <#if paramType_index gte requiredCount>if (p_argument_count > ${paramType_index}) </#if>{
            // Compare compile-time known typed-array metadata directly to avoid extra
            // is_same_typed(...) overhead on the wrapper hot path.
            godot_Array ${probeVarName} = godot_new_Array_with_Variant((GDExtensionVariantPtr)p_args[${paramType_index}]);
            godot_bool typed_mismatch = godot_Array_get_typed_builtin(&${probeVarName}) != ${expectedBuiltinType};
            <#if helper.isTypedArrayGuardObjectLeaf(paramType)>
                <#assign expectedClassNameExpr = helper.renderTypedArrayGuardClassNameExpr(paramType)>
            if (!typed_mismatch) {
                godot_StringName ${probeVarName}_class_name = godot_Array_get_typed_class_name(&${probeVarName});
                godot_Variant ${probeVarName}_script = godot_Array_get_typed_script(&${probeVarName});
                godot_Variant ${probeVarName}_script_nil = godot_new_Variant_nil();
                godot_Variant ${probeVarName}_script_is_null_result;
                godot_bool ${probeVarName}_script_is_null_valid = false;
                // Godot reports absent script leaf metadata as OBJECT/null; evaluate constructs into raw result storage.
                godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &${probeVarName}_script, &${probeVarName}_script_nil, (GDExtensionUninitializedVariantPtr)&${probeVarName}_script_is_null_result, &${probeVarName}_script_is_null_valid);
                const godot_bool ${probeVarName}_script_is_null = ${probeVarName}_script_is_null_valid && godot_new_bool_with_Variant(&${probeVarName}_script_is_null_result);
                typed_mismatch = !godot_StringName_op_equal_StringName(&${probeVarName}_class_name, ${expectedClassNameExpr}) || !${probeVarName}_script_is_null;
                if (${probeVarName}_script_is_null_valid) {
                    godot_Variant_destroy(&${probeVarName}_script_is_null_result);
                }
                godot_Variant_destroy(&${probeVarName}_script_nil);
                godot_Variant_destroy(&${probeVarName}_script);
                godot_StringName_destroy(&${probeVarName}_class_name);
            }
            </#if>
            godot_Array_destroy(&${probeVarName});
            if (typed_mismatch) {
                r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
                r_error->expected = GDEXTENSION_VARIANT_TYPE_ARRAY;
                r_error->argument = ${paramType_index};
                return;
            }
        }
        </#if>
    </#list>

    <#list bindingData.paramTypes as paramType>
        <#if helper.needsTypedDictionaryCallGuard(paramType)>
        <#assign probeVarName = "probe" + paramType_index>
        <#if paramType_index gte requiredCount>if (p_argument_count > ${paramType_index}) </#if>{
            // Typed Dictionary slots need a second-stage typedness check before wrapper locals exist.
            godot_Dictionary ${probeVarName} = godot_new_Dictionary_with_Variant((GDExtensionVariantPtr)p_args[${paramType_index}]);
            godot_bool typed_mismatch = false;
            <#list ["key", "value"] as typedSide>
                <#assign expectedBuiltinType = helper.renderTypedDictionaryGuardBuiltinTypeLiteral(paramType, typedSide)>
            if (!typed_mismatch) {
                typed_mismatch = godot_Dictionary_get_typed_${typedSide}_builtin(&${probeVarName}) != ${expectedBuiltinType};
            }
                <#if helper.isTypedDictionaryGuardObjectLeaf(paramType, typedSide)>
                    <#assign expectedClassNameExpr = helper.renderTypedDictionaryGuardClassNameExpr(paramType, typedSide)>
            if (!typed_mismatch) {
                godot_StringName ${probeVarName}_${typedSide}_class_name = godot_Dictionary_get_typed_${typedSide}_class_name(&${probeVarName});
                godot_Variant ${probeVarName}_${typedSide}_script = godot_Dictionary_get_typed_${typedSide}_script(&${probeVarName});
                godot_Variant ${probeVarName}_${typedSide}_script_nil = godot_new_Variant_nil();
                godot_Variant ${probeVarName}_${typedSide}_script_is_null_result;
                godot_bool ${probeVarName}_${typedSide}_script_is_null_valid = false;
                // Godot reports absent script leaf metadata as OBJECT/null; evaluate constructs into raw result storage.
                godot_variant_evaluate(GDEXTENSION_VARIANT_OP_EQUAL, &${probeVarName}_${typedSide}_script, &${probeVarName}_${typedSide}_script_nil, (GDExtensionUninitializedVariantPtr)&${probeVarName}_${typedSide}_script_is_null_result, &${probeVarName}_${typedSide}_script_is_null_valid);
                const godot_bool ${probeVarName}_${typedSide}_script_is_null = ${probeVarName}_${typedSide}_script_is_null_valid && godot_new_bool_with_Variant(&${probeVarName}_${typedSide}_script_is_null_result);
                typed_mismatch = !godot_StringName_op_equal_StringName(&${probeVarName}_${typedSide}_class_name, ${expectedClassNameExpr}) || !${probeVarName}_${typedSide}_script_is_null;
                if (${probeVarName}_${typedSide}_script_is_null_valid) {
                    godot_Variant_destroy(&${probeVarName}_${typedSide}_script_is_null_result);
                }
                godot_Variant_destroy(&${probeVarName}_${typedSide}_script_nil);
                godot_Variant_destroy(&${probeVarName}_${typedSide}_script);
                godot_StringName_destroy(&${probeVarName}_${typedSide}_class_name);
            }
                </#if>
            </#list>
            godot_Dictionary_destroy(&${probeVarName});
            if (typed_mismatch) {
                r_error->error = GDEXTENSION_CALL_ERROR_INVALID_ARGUMENT;
                r_error->expected = GDEXTENSION_VARIANT_TYPE_DICTIONARY;
                r_error->argument = ${paramType_index};
                return;
            }
        }
        </#if>
    </#list>

    // Extract the argument. Wrapper-owned non-object locals stay mutable so the
    // cleanup epilogue below can destroy them before returning to Godot.
<#if defaultFlavor>
    <#if !bindingData.staticMethod>
    // self_fat must be materialized before any default fill below; it is borrowed
    // from p_instance and needs no cleanup.
    ${helper.renderRegisteredMethodSelfFatType(bindingData)} self_fat = ${helper.renderRegisteredMethodSelfFatExpr(bindingData)};
    </#if>
    ${helper.renderDefaultUserdataTypeName(bindingData)}* ud = method_userdata;
</#if>
    <#list bindingData.paramTypes as paramType>
        <#if paramType_index gte requiredCount>
        <#-- Default slot: always mutable so the omitted-argument branch can assign the shell -->
        <#-- result; the unpack branch re-evaluates the Variant type (the gate's cached -->
        <#-- argN_type is scoped inside the conditional gate above). -->
        ${helper.renderGdTypeInC(paramType)} arg${paramType_index};
        <#if helper.renderCallWrapperDefaultObjectReleaseStmt(paramType, "arg${paramType_index}")?has_content>
        <#-- Shell-produced objects are OWNED while Variant-unpacked arguments stay BORROWED; -->
        <#-- the flag lets the epilogue release only default-produced references. Only emitted -->
        <#-- for RefCounted-tracked object types so no unused flag is generated. -->
        godot_bool arg${paramType_index}_from_default = false;
        </#if>
        if (p_argument_count > ${paramType_index}) {
            arg${paramType_index} = ${helper.renderCallWrapperUnpackExpr(paramType, "(GDExtensionVariantPtr)p_args[${paramType_index}]")};
        } else {
            arg${paramType_index} = ud->def${paramType_index - requiredCount}(<#if !bindingData.staticMethod>self_fat</#if>);
            <#if helper.renderCallWrapperDefaultObjectReleaseStmt(paramType, "arg${paramType_index}")?has_content>arg${paramType_index}_from_default = true;</#if>
        }
        <#else>
        <#assign argCleanupStmt = helper.renderCallWrapperDestroyStmt(paramType, "arg${paramType_index}")>
        <#if argCleanupStmt?has_content>
        ${helper.renderGdTypeInC(paramType)} arg${paramType_index} = ${helper.renderCallWrapperUnpackExpr(paramType, "(GDExtensionVariantPtr)p_args[${paramType_index}]", "arg${paramType_index}_type")};
        <#else>
        const ${helper.renderGdTypeInC(paramType)} arg${paramType_index} = ${helper.renderCallWrapperUnpackExpr(paramType, "(GDExtensionVariantPtr)p_args[${paramType_index}]", "arg${paramType_index}_type")};
        </#if>
        </#if>
    </#list>

    // Call the function. Instance methods receive owner fat self; static methods omit self.
    // Wrapper-local non-object values materialized above must be destroyed here.
<#if bindingData.staticMethod>
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(<#list bindingData.paramTypes as paramType>${helper.renderGdTypeRefInC(paramType)}<#if paramType_has_next>, </#if></#list>) = <#if defaultFlavor>ud->impl<#else>method_userdata</#if>;
    <#if bindingData.returnType.typeName != "void">
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(<#list bindingData.paramTypes as paramType>${helper.renderValueRef(paramType, "arg${paramType_index}")}<#if paramType_has_next>, </#if></#list>);
        godot_Variant ret = ${helper.renderPackFunctionName(bindingData.returnType)}(${helper.renderValueRef(bindingData.returnType, "r")});
        godot_variant_new_copy(r_return, &ret);
        godot_Variant_destroy(&ret);
        <#assign returnObjectConsumeStmt = helper.renderCallWrapperOwnedObjectReturnConsumeStmt(bindingData.returnType, "r")>
        <#if returnObjectConsumeStmt?has_content>
        ${returnObjectConsumeStmt}
        </#if>
        <#assign returnCleanupStmt = helper.renderCallWrapperDestroyStmt(bindingData.returnType, "r")>
        <#if returnCleanupStmt?has_content>
        ${returnCleanupStmt}
        </#if>
    <#else>
        (function(<#list bindingData.paramTypes as paramType>${helper.renderValueRef(paramType, "arg${paramType_index}")}<#if paramType_has_next>, </#if></#list>));
    </#if>
<#else>
<#if !defaultFlavor>
    ${helper.renderRegisteredMethodSelfFatType(bindingData)} self_fat = ${helper.renderRegisteredMethodSelfFatExpr(bindingData)};
</#if>
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(${helper.renderRegisteredMethodSelfFatType(bindingData)}<#list bindingData.paramTypes as paramType>, ${helper.renderGdTypeRefInC(paramType)}</#list>) = <#if defaultFlavor>ud->impl<#else>method_userdata</#if>;
    <#if bindingData.returnType.typeName != "void">
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(self_fat<#list bindingData.paramTypes as paramType>, ${helper.renderValueRef(paramType, "arg${paramType_index}")}</#list>);
        godot_Variant ret = ${helper.renderPackFunctionName(bindingData.returnType)}(${helper.renderValueRef(bindingData.returnType, "r")});
        godot_variant_new_copy(r_return, &ret);
        godot_Variant_destroy(&ret);
        <#assign returnObjectConsumeStmt = helper.renderCallWrapperOwnedObjectReturnConsumeStmt(bindingData.returnType, "r")>
        <#if returnObjectConsumeStmt?has_content>
        ${returnObjectConsumeStmt}
        </#if>
        <#assign returnCleanupStmt = helper.renderCallWrapperDestroyStmt(bindingData.returnType, "r")>
        <#if returnCleanupStmt?has_content>
        ${returnCleanupStmt}
        </#if>
    <#else>
        (function(self_fat<#list bindingData.paramTypes as paramType>, ${helper.renderValueRef(paramType, "arg${paramType_index}")}</#list>));
    </#if>
</#if>
    <#assign argCount = bindingData.paramTypes?size>
    <#list bindingData.paramTypes?reverse as paramType>
        <#assign argIndex = argCount - paramType_index - 1>
        <#assign argCleanupStmt = helper.renderCallWrapperDestroyStmt(paramType, "arg${argIndex}")>
        <#if argCleanupStmt?has_content>
        ${argCleanupStmt}
        </#if>
        <#if defaultFlavor && (argIndex gte requiredCount)>
        <#assign defaultObjectReleaseStmt = helper.renderCallWrapperDefaultObjectReleaseStmt(paramType, "arg${argIndex}")>
        <#if defaultObjectReleaseStmt?has_content>
        if (arg${argIndex}_from_default) {
            ${defaultObjectReleaseStmt}
        }
        </#if>
        </#if>
    </#list>
}

static void ptrcall${helper.renderFuncBindName(bindingData)}(
    void* method_userdata, GDExtensionClassInstancePtr p_instance,
    const GDExtensionConstTypePtr* p_args, GDExtensionTypePtr r_return) {
    // Object args/returns use raw Godot pointer slots; self is owner fat for instance methods.
<#-- The default flavor shares the same userdata layout as the call wrapper: ptrcall keeps the -->
<#-- fixed full-argument ABI (no argc guard, no fill) but must still reach impl via ud->impl. -->
<#if defaultFlavor>
    ${helper.renderDefaultUserdataTypeName(bindingData)}* ud = method_userdata;
</#if>
<#list bindingData.paramTypes as paramType>
<#if helper.checkObjectType(paramType)>
    ${helper.renderPtrcallObjectArgDecl(paramType, paramType_index)}
</#if>
</#list>
<#if bindingData.staticMethod>
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(<#list bindingData.paramTypes as paramType>${helper.renderGdTypeRefInC(paramType)}<#if paramType_has_next>, </#if></#list>) = <#if defaultFlavor>ud->impl<#else>method_userdata</#if>;
<#else>
    ${helper.renderRegisteredMethodSelfFatType(bindingData)} self_fat = ${helper.renderRegisteredMethodSelfFatExpr(bindingData)};
    ${helper.renderGdTypeInC(bindingData.returnType)} (*function)(${helper.renderRegisteredMethodSelfFatType(bindingData)}<#list bindingData.paramTypes as paramType>, ${helper.renderGdTypeRefInC(paramType)}</#list>) = <#if defaultFlavor>ud->impl<#else>method_userdata</#if>;
</#if>
<#if bindingData.returnType.typeName == "void">
    <#if bindingData.staticMethod>
        (function(<#list bindingData.paramTypes as paramType><#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if><#if paramType_has_next>, </#if></#list>));
    <#else>
        (function(self_fat<#list bindingData.paramTypes as paramType>, <#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if></#list>));
    </#if>
<#elseif helper.checkObjectType(bindingData.returnType)>
    <#if bindingData.staticMethod>
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(<#list bindingData.paramTypes as paramType><#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if><#if paramType_has_next>, </#if></#list>);
    <#else>
        ${helper.renderGdTypeInC(bindingData.returnType)} r = function(self_fat<#list bindingData.paramTypes as paramType>, <#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if></#list>);
    </#if>
        ${helper.renderPtrcallObjectReturnWrite(bindingData.returnType, "r")}
<#else>
    <#if bindingData.staticMethod>
        *((${helper.renderGdTypeInC(bindingData.returnType)}*)r_return) = function(<#list bindingData.paramTypes as paramType><#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if><#if paramType_has_next>, </#if></#list>);
    <#else>
        *((${helper.renderGdTypeInC(bindingData.returnType)}*)r_return) = function(self_fat<#list bindingData.paramTypes as paramType>, <#if helper.checkObjectType(paramType)>arg${paramType_index}<#else>${helper.renderPtrcallNonObjectArgExpr(paramType, paramType_index)}</#if></#list>);
    </#if>
</#if>
}

static void gdcc_bind_method${helper.renderFuncBindName(bindingData)}(
    godot_StringName* class_name,
    godot_StringName* method_name,
    void* function<#if bindingData.paramTypes?size gt 0>,</#if>
    <#list bindingData.paramTypes as paramType>
        const godot_StringName* arg${paramType_index}_name,
        const GDExtensionVariantType arg${paramType_index}_type<#if paramType_has_next>,</#if>
    </#list><#if bindingData.defaultVariables?size gt 0>,</#if>
    <#list bindingData.defaultVariables as defaultVarType>
        const ${helper.renderGdTypeRefInC(defaultVarType)} default_${defaultVarType_index}_value<#if defaultVarType_has_next>,</#if>
    </#list>) {

    GDExtensionClassMethodCall call_func = call${helper.renderFuncBindName(bindingData)};
    GDExtensionClassMethodPtrCall ptrcall_func = ptrcall${helper.renderFuncBindName(bindingData)};

    // Bound-slot outward metadata stays centralized in CGenHelper so Variant, typed Array and typed
    // Dictionary keep sharing one backend-owned contract instead of growing template-local special cases.
    GDExtensionPropertyInfo args_info[] = {
    <#list bindingData.paramTypes as paramType>
        <#assign boundMetadata = helper.renderBoundMetadata(paramType, "godot_PROPERTY_USAGE_DEFAULT", "method arg")>
        gdcc_make_property_full(arg${paramType_index}_type, arg${paramType_index}_name, ${boundMetadata.hintEnumLiteral}, ${boundMetadata.hintStringExpr}, ${boundMetadata.classNameExpr}, ${boundMetadata.usageExpr}),
    </#list>
    };
    GDExtensionClassMethodArgumentMetadata args_metadata[] = {
    <#list bindingData.paramTypes as paramType>
        GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE,
    </#list>
    };

    <#if bindingData.defaultVariables?size gt 0>
        // Default argument variants
        <#list bindingData.defaultVariables as defaultVarType>
            godot_Variant default_var_${defaultVarType_index} = ${helper.renderPackFunctionName(defaultVarType)}(default_${defaultVarType_index}_value);
        </#list>
        GDExtensionVariantPtr default_args_ptrs[] = {
        // Default argument pointers
        <#list bindingData.defaultVariables as defaultVarType>
            &default_var_${defaultVarType_index},
        </#list>
        };
    </#if>
    <#if bindingData.returnType.typeName != "void">
        // Return outward metadata reuses the same helper path as arguments so typed Array / typed
        // Dictionary hints stay consistent across both sides of the method boundary.
        <#assign returnMetadata = helper.renderBoundMetadata(bindingData.returnType, "godot_PROPERTY_USAGE_DEFAULT", "method return")>
        GDExtensionPropertyInfo return_info = gdcc_make_property_full(${returnMetadata.typeEnumLiteral}, GD_STATIC_SN(u8""), ${returnMetadata.hintEnumLiteral}, ${returnMetadata.hintStringExpr}, ${returnMetadata.classNameExpr}, ${returnMetadata.usageExpr});
    </#if>
    GDExtensionClassMethodInfo method_info = { 0 };
    method_info.name = method_name;
    method_info.method_userdata = function;
    method_info.call_func = call_func;
    method_info.ptrcall_func = ptrcall_func;
    method_info.method_flags = GDEXTENSION_METHOD_FLAGS_DEFAULT<#if bindingData.staticMethod> | GDEXTENSION_METHOD_FLAG_STATIC</#if>;
    <#if bindingData.returnType.typeName != "void">
        method_info.has_return_value = true;
        method_info.return_value_info = &return_info;
        method_info.return_value_metadata = GDEXTENSION_METHOD_ARGUMENT_METADATA_NONE;
    <#else>
        method_info.has_return_value = false;
    </#if>
    method_info.argument_count = ${bindingData.paramTypes?size};
    method_info.arguments_info = args_info;
    method_info.arguments_metadata = args_metadata;
    <#if bindingData.defaultVariables?size gt 0>
        method_info.default_argument_count = ${bindingData.defaultVariables?size};
        method_info.default_arguments = default_args_ptrs;
    </#if>
    godot_classdb_register_extension_class_method(class_library, class_name, &method_info);
    // Clean up
    <#list bindingData.paramTypes as paramType>
        gdcc_destruct_property(&args_info[${paramType_index}]);
    </#list>
    <#list bindingData.defaultVariables as defaultVarType>
        godot_Variant_destroy(&default_var_${defaultVarType_index});
    </#list>
    <#if bindingData.returnType.typeName != "void">
        gdcc_destruct_property(&return_info);
    </#if>
}
</#list>

#endif //GDEXTENSION_${module.moduleName?upper_case}_ENTRY_H
