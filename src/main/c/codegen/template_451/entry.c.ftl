<#-- @ftlvariable name="module" type="gd.script.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="gd.script.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="bodyRender" type="gd.script.gdcc.backend.c.gen.binding.GenerateRenderFacade" -->
<#-- @ftlvariable name="staticInitClassDefs" type="java.util.List<gd.script.gdcc.lir.LirClassDef>" -->
<#-- @ftlvariable name="inheritanceOrderedClassDefs" type="java.util.List<gd.script.gdcc.lir.LirClassDef>" -->
<#include "func.ftl">
<#include "trim.ftl">

#include "entry.h"

<#-- Static property backing variables (file-scope shared storage): zero-initialized at -->
<#-- program load; every value write goes through the module lifecycle sections or the -->
<#-- load/store_static gens, all via CBodyBuilder slot-write semantics. -->
<#list module.classDefs as classDef>
    <#list classDef.properties as property>
        <#if property.static>
${helper.renderGdTypeInC(property.type)} ${helper.renderStaticBackingSymbol(classDef.name, property.name)};
        </#if>
    </#list>
</#list>
<#-- Per-class static lifecycle entries (two-phase global static initialization): declared -->
<#-- before `initialize()` below which calls them in base-before-derived order. -->
<#list staticInitClassDefs as classDef>
static void ${helper.renderStaticDefaultsSymbol(classDef.name)}(void);
static void ${helper.renderStaticInitializersSymbol(classDef.name)}(void);
</#list>

GDE_EXPORT GDExtensionBool gdextension_entry(
    GDExtensionInterfaceGetProcAddress p_get_proc_address,
    GDExtensionClassLibraryPtr p_library,
    GDExtensionInitialization* r_initialization
) {
    if (!godot_initialize_interface(p_get_proc_address)) {
        return false;
    }
    class_library = p_library;

    r_initialization->minimum_initialization_level = GDEXTENSION_INITIALIZATION_SCENE;
    r_initialization->userdata = NULL;
    r_initialization->initialize = &initialize;
    r_initialization->deinitialize = &deinitialize;

    return true;
}

void initialize(void* userdata, const GDExtensionInitializationLevel p_level) {
    (void)userdata;
    if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
    gdcc_init();
    <#--  Print start loading  -->
    {
        godot_Variant msg_variant = godot_new_Variant_with_String(GD_STATIC_S(u8"Loading ${module.moduleName}..."));
        godot_print(&msg_variant, NULL, 0);
        godot_Variant_destroy(&msg_variant);
    }
    <#-- Register user classes.-->
    <#-- Registration, bind-owner lookup, and instance attach intentionally all reuse the-->
    <#-- same canonical class name directly. There is no backend-only Godot alias layer here.-->
    <#-- Registration iterates the base-before-derived inheritance order (never raw module -->
    <#-- order): Godot rejects registering an extension class whose parent extension class is -->
    <#-- not registered yet, and binds the parent/child extension pair at registration time. -->
    <#list inheritanceOrderedClassDefs as classDef>
    {
        GDExtensionClassCreationInfo5 creation_info = {};
        creation_info.is_abstract = ${classDef.abstract?c};
        creation_info.is_runtime = false;
        creation_info.is_virtual = false;
        creation_info.is_exposed = true;
        creation_info.create_instance_func = ${classDef.name}_class_create_instance;
        creation_info.free_instance_func = ${classDef.name}_class_free_instance;
        creation_info.get_virtual_call_data_func = ${classDef.name}_class_get_virtual_with_data;
        creation_info.call_virtual_with_data_func = ${classDef.name}_class_call_virtual_with_data;
        creation_info.notification_func = ${classDef.name}_class_notification;
        godot_classdb_register_extension_class5(class_library,
                                                GD_STATIC_SN(u8"${classDef.name}"), GD_STATIC_SN(u8"${classDef.superName}"),
                                                &creation_info);
        ${classDef.name}_class_bind_methods();
    }
    </#list>
    <#if helper.hasCoroutineFunctions()>
    <#-- Hidden coroutine state classes: runtime-only, never script-exposed, direct RefCounted -->
    <#-- children. Each registers the engine-internal coroutine `completed(result)` signal shape. -->
    <#list module.classDefs as classDef>
        <#list classDef.functions as func>
            <#if func.coroutine>
                <#assign stateName = helper.renderCoroStateClassName(classDef, func)>
    {
        GDExtensionClassCreationInfo5 creation_info = {};
        creation_info.is_abstract = false;
        creation_info.is_runtime = true;
        creation_info.is_virtual = false;
        creation_info.is_exposed = false;
        creation_info.create_instance_func = ${stateName}_class_create_instance;
        creation_info.free_instance_func = ${stateName}_class_free_instance;
        creation_info.notification_func = ${stateName}_class_notification;
        godot_classdb_register_extension_class5(class_library,
                                                GD_STATIC_SN(u8"${stateName}"), GD_STATIC_SN(u8"RefCounted"),
                                                &creation_info);
        <#assign completedMetadata = helper.renderCoroCompletedSignalMetadata()>
        GDExtensionPropertyInfo completed_args[] = {
            gdcc_make_property_full(${completedMetadata.typeEnumLiteral}, GD_STATIC_SN(u8"result"), ${completedMetadata.hintEnumLiteral}, ${completedMetadata.hintStringExpr}, ${completedMetadata.classNameExpr}, ${completedMetadata.usageExpr}),
        };
        godot_classdb_register_extension_class_signal(class_library, GD_STATIC_SN(u8"${stateName}"), GD_STATIC_SN(u8"completed"), completed_args, 1);
        gdcc_destruct_property(&completed_args[0]);
    }
            </#if>
        </#list>
    </#list>
    </#if>
    <#if staticInitClassDefs?size gt 0>
    <#-- Global two-phase static init: ALL classes' defaults run first (base-before-derived), -->
    <#-- then ALL classes' initializers in the same order, so a static initializer reading another -->
    <#-- class's static var always observes at least the materialized type default. Both phases run -->
    <#-- only after EVERY class (including hidden coroutine state classes) is registered, because an -->
    <#-- initializer may call static functions that instantiate script classes or start coroutines. -->
    <#list staticInitClassDefs as classDef>
    ${helper.renderStaticDefaultsSymbol(classDef.name)}();
    </#list>
    <#list staticInitClassDefs as classDef>
    ${helper.renderStaticInitializersSymbol(classDef.name)}();
    </#list>
    </#if>
}

void deinitialize(void* userdata, GDExtensionInitializationLevel p_level) {
    (void)userdata;
    if (p_level != GDEXTENSION_INITIALIZATION_SCENE) {
        return;
    }
    <#--  Print start unloading  -->
    {
        godot_Variant msg_variant = godot_new_Variant_with_String(GD_STATIC_S(u8"Unloading ${module.moduleName}..."));
        godot_print(&msg_variant, NULL, 0);
        godot_Variant_destroy(&msg_variant);
    }
    <#--  Destroy static backing variables in reverse initialization order BEFORE the runtime  -->
    <#--  registries below: destroy/release paths may still touch interned StringName/String state.  -->
    <#list staticInitClassDefs?reverse as classDef>
    ${bodyRender.generateStaticDeinitializeBody(classDef)}</#list>
    <#--  Destroy Const StringNames, Strings, and interned standalone Callables  -->
    gdcc_sn_registry_destroy_all();
    gdcc_s_registry_destroy_all();
    gdcc_standalone_callable_registry_destroy_all();
}

<#-- Per-class static lifecycle entry definitions (two-phase global static initialization). -->
<#list staticInitClassDefs as classDef>
static void ${helper.renderStaticDefaultsSymbol(classDef.name)}(void) {
    ${bodyRender.generateStaticDefaultsBody(classDef)}
}

static void ${helper.renderStaticInitializersSymbol(classDef.name)}(void) {
    ${bodyRender.generateStaticInitializersBody(classDef)}
}
</#list>

<#-- Per-method exclusive default-fill userdata at file scope: the ClassDB registration
     below and the virtual dispatch path share these instances, so both userdata protocols resolve
     impl through the same struct — a raw function address is never reinterpreted as userdata.
     Default slots form a contiguous trailing suffix, so the filtered initializer order matches
     the typedef's def0..defK layout. -->
<#list module.classDefs as classDef>
<#list classDef.functions as function>
    <#if !function.hidden && !function.lambda && helper.countDefaultSlots(function) gt 0>
static ${helper.renderDefaultUserdataTypeName(classDef, function)} ${helper.renderDefaultUserdataInstanceName(classDef, function)} = {
    ${classDef.name}_${function.name},
    <#list function.parameters as parameter>
        <#if parameter.name != "self" && parameter.defaultValueFunc??>
    ${classDef.name}_${parameter.defaultValueFunc},
        </#if>
    </#list>
};
    </#if>
</#list>
</#list>

<#-- Vtable trampolines and instances. A trampoline adapts an inherited slot's introducer -->
<#-- signature to the final overrider's implementation (its downcast is the single sanctioned -->
<#-- exception to the no-bare-GDCC-cast contract); instances fill each slot with the final -->
<#-- overrider — direct impl symbol, trampoline, or NULL for an abstract hole. Iteration uses -->
<#-- the base-before-derived inheritance order (never raw module order): a derived class's -->
<#-- instance takes the address of an ANCESTOR's trampoline (when the ancestor stays the final -->
<#-- overrider of an inherited slot), and static trampolines have no header prototype, so every -->
<#-- trampoline must be defined before any instance that can reference it. -->
<#list inheritanceOrderedClassDefs as classDef>
    <#list helper.vtablePlanner().slots(classDef.name) as slotEntry>
        <#if slotEntry.finalOverriderClassName == classDef.name && slotEntry.slot.introducerClassName != classDef.name>
static ${helper.renderVtableTrampolineDefinition(classDef.name, slotEntry)}

        </#if>
    </#list>
    <#if helper.vtablePlanner().introducesSlot(classDef.name) || helper.vtablePlanner().overridesInheritedSlot(classDef.name)>
static const ${helper.renderVtableInstanceTypeName(classDef.name)} ${helper.renderVtableInstanceSymbol(classDef.name)} = ${helper.renderVtableInstanceInitializer(classDef.name)};

    </#if>
</#list>

<#-- Bind Methods for each class.-->
<#-- The local `class_name` slot remains the canonical owner identity that registration used above.-->
<#list module.classDefs as classDef>
void ${classDef.name}_class_bind_methods() {
    godot_StringName* class_name = GD_STATIC_SN(u8"${classDef.name}");
    // Methods
    <#list classDef.functions as function>
        <#if !function.hidden && !function.lambda>
            <#if helper.countDefaultSlots(function) gt 0>
                <#-- The bind helper keeps its existing `void* function` formal and receives &ud. -->
                <#assign implArg = "&" + helper.renderDefaultUserdataInstanceName(classDef, function)>
            <#else>
                <#assign implArg = classDef.name + "_" + function.name>
            </#if>
    gdcc_bind_method${helper.renderFuncBindName(classDef, function)}(class_name, GD_STATIC_SN(u8"${function.name}"), ${implArg}<#if function.parameters?size gt function.static?then(0, 1)>,<#else>);</#if>
            <#list function.parameters as parameter>
                <#if parameter.name != "self">
                    GD_STATIC_SN(u8"${parameter.name}"), GDEXTENSION_VARIANT_TYPE_${parameter.type.gdExtensionType.name()}<#if parameter_has_next>,<#else>);</#if>
                </#if>
            </#list>
        </#if>
    </#list>
    // Properties
    <#list classDef.properties as property>
    {
        <#if !property.static>
            <#assign propertyMetadata = helper.renderPropertyMetadata(property)>
            <#-- Property outward metadata stays centralized in renderPropertyMetadata(...): it owns type/hint/hint_string/class_name/usage
                 for Variant, typed Array, typed Dictionary and Object exports alike; the class_name slot carries the
                 property type class (Object exports) instead of the owner class. -->
            gdcc_bind_property_full(class_name, GD_STATIC_SN(u8"${property.name}"), ${propertyMetadata.typeEnumLiteral}, ${propertyMetadata.hintEnumLiteral}, ${propertyMetadata.hintStringExpr}, ${propertyMetadata.classNameExpr}, ${propertyMetadata.usageExpr}, GD_STATIC_SN(u8"${property.getterFunc}"), GD_STATIC_SN(u8"${property.setterFunc}"));
        </#if>
    }
    </#list>
    // Signals
    <#-- Only classDef.signals (current-class declarations) are registered. Zero-arg signals must
         pass NULL, 0. Argument metadata reuses renderSignalParameterMetadata / gdcc_make_property_full
         and is released with gdcc_destruct_property after ClassDB takes its copy. -->
    <#list classDef.signals as signal>
    {
        <#if (signal.parameters?size) == 0>
        godot_classdb_register_extension_class_signal(class_library, class_name, GD_STATIC_SN(u8"${signal.name}"), NULL, 0);
        <#else>
        GDExtensionPropertyInfo signal_args[] = {
            <#list signal.parameters as parameter>
                <#assign signalParamMetadata = helper.renderSignalParameterMetadata(parameter.type)>
            gdcc_make_property_full(${signalParamMetadata.typeEnumLiteral}, GD_STATIC_SN(u8"${parameter.name}"), ${signalParamMetadata.hintEnumLiteral}, ${signalParamMetadata.hintStringExpr}, ${signalParamMetadata.classNameExpr}, ${signalParamMetadata.usageExpr}),
            </#list>
        };
        godot_classdb_register_extension_class_signal(class_library, class_name, GD_STATIC_SN(u8"${signal.name}"), signal_args, ${signal.parameters?size});
            <#list signal.parameters as parameter>
        gdcc_destruct_property(&signal_args[${parameter_index}]);
            </#list>
        </#if>
    }
    </#list>
}
</#list>

// Object pointer helpers for GDCC wrapper layout
<#list module.classDefs as classDef>
static inline GDExtensionObjectPtr ${classDef.name}_object_ptr(${classDef.name}* self) {
    if (self == NULL) {
        return NULL;
    }
    <#if helper.checkGdccClassByName(classDef.superName)>
        return ${classDef.superName}_object_ptr(&self->_super);
    <#else>
        return self->_object;
    </#if>
}

static inline void ${classDef.name}_set_object_ptr(${classDef.name}* self, GDExtensionObjectPtr obj) {
    if (self == NULL) {
        return;
    }
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName}_set_object_ptr(&self->_super, obj);
    <#else>
        self->_object = obj;
    </#if>
}
</#list>

<#-- Vtable accessors (introducers only) read the root `_vtable` field straight through the -->
<#-- WRAPPER `_super` chain — never a parent accessor (a pass-through parent has none) and -->
<#-- never the vtable `_super` chain (which skips non-introducer classes). -->
<#list module.classDefs as classDef>
    <#if helper.vtablePlanner().introducesSlot(classDef.name)>
        <#assign vtableTypeName = helper.renderVtableInstanceTypeName(classDef.name)>
static inline const ${vtableTypeName}* ${helper.renderVtableAccessorName(classDef.name)}(${classDef.name}* self) {
    return (const ${vtableTypeName}*)${helper.renderVtableFieldAccessExpr(classDef.name)};
}

    </#if>
</#list>

// GdExtension Methods for each class
<#list module.classDefs as classDef>
<#list classDef.properties as property>
<#-- Static properties have no instance apply helper; their init runs via module lifecycle. -->
<#if !property.static>
static inline void ${helper.renderPropertyInitApplyHelperName(classDef, property)}(${classDef.name}* self) {
    ${bodyRender.generatePropertyInitApplyBody(classDef, property)}
}
</#if>
</#list>

GDExtensionObjectPtr ${classDef.name}_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize) {
    GDExtensionObjectPtr obj = godot_classdb_construct_object2(GD_STATIC_SN(u8"${helper.resolveNearestNativeAncestorName(classDef)}"));
    ${classDef.name}* self = godot_mem_alloc(sizeof(${classDef.name}));
    ${classDef.name}_set_object_ptr(self, obj);
    <#-- Vtable field initialization happens before the POSTINITIALIZE notification (which is -->
    <#-- what runs user `_init` through the constructor chain), so virtual calls inside `_init` -->
    <#-- already dispatch through a valid table. Four cases: no field at all / NULL for -->
    <#-- side branches / this class's own instance / the nearest non-pass-through ancestor's -->
    <#-- instance value for pass-through classes — driven entirely by the planner. -->
    <#assign vtableFieldInit = helper.renderVtableFieldInitExpr(classDef.name)>
    <#if vtableFieldInit?has_content>
    ${helper.renderVtableFieldAccessExpr(classDef.name)} = ${vtableFieldInit};
    </#if>
    godot_object_set_instance(obj, GD_STATIC_SN(u8"${classDef.name}"), self);
    godot_object_set_instance_binding(obj, class_library, self, &${classDef.name}_class_binding_callbacks);
    if (p_notify_postinitialize) {
        godot_Object_notification(obj, godot_Object_NOTIFICATION_POSTINITIALIZE(), false);
    }
    return obj;
}

void ${classDef.name}_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance) {
    if (p_instance == NULL) {
        return;
    }
    ${classDef.name}* self = p_instance;
    godot_mem_free(self);
}

void ${classDef.name}_class_constructor(${classDef.name}* self) {
    if (self == NULL) {
        return;
    }
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName}_class_constructor(&self->_super);
    </#if>
    <#list classDef.properties as property>
        <#-- Static properties are not instance fields; the constructor must not touch them. -->
        <#if !property.static>
        ${helper.renderPropertyInitApplyHelperName(classDef, property)}(self);
        </#if>
    </#list>
    <#list classDef.functions as function>
        <#if function.name == "_init" && !function.static && function.parameters?size == 1>
            <#-- _init takes owner fat self; the constructor still has a Class* wrapper. -->
            ${classDef.name}__init(${helper.renderOwnerFatSelfFromWrapperPtr(classDef.name, "self")});
        </#if>
    </#list>
}

void ${classDef.name}_class_destructor(${classDef.name}* self) {
    if (self == NULL) {
        return;
    }
    <#list classDef.properties as property>
        <#-- Static backing variables are cleaned up in deinitialize(), never here. -->
        <#if !property.static && property.type.destroyable>
            <#if property.type.gdExtensionType.name() == "OBJECT">
                // Object properties store fat pointers; release the validated live raw Godot object.
                // The cached instance_id drives the runtime RefCounted reference-bit check.
                try_release_object(${helper.renderObjectFatPtrStorageType(property.type)}_live_object(self->${property.name}), self->${property.name}.instance_id);
            <#else>
                ${helper.renderDestroyFunctionName(property.type)}(&(self->${property.name}));
            </#if>
        </#if>
    </#list>
    <#if helper.checkGdccClassByName(classDef.superName)>
        ${classDef.superName}_class_destructor(&self->_super);
    </#if>
}

void ${classDef.name}_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed) {
    ${classDef.name}* self = p_instance;
    if (p_what == godot_Object_NOTIFICATION_POSTINITIALIZE()) {
        ${classDef.name}_class_constructor(self);
    } else if (p_what == godot_Object_NOTIFICATION_PREDELETE()) {
        ${classDef.name}_class_destructor(self);
    }
}

void* ${classDef.name}_class_get_virtual_with_data(void* p_class_userdata, GDExtensionConstStringNamePtr p_name,
                                                     uint32_t p_hash) {
    (void)p_class_userdata;
    (void)p_hash;
    // Bind virtual methods
    <#list classDef.functions as function>
        <#if helper.checkVirtualMethod(classDef, function)>
            <#-- Virtual dispatch must hand the ptrcall wrapper the same userdata protocol as
                 ClassDB registration: the shared per-method default userdata for default-carrying
                 overrides, the raw impl address otherwise. -->
            if (godot_StringName_op_equal_StringName(p_name, GD_STATIC_SN(u8"${function.name}"))) {
                return (void*)<#if helper.countDefaultSlots(function) gt 0>&${helper.renderDefaultUserdataInstanceName(classDef, function)}<#else>${classDef.name}_${function.name}</#if>;
            }
        </#if>
    </#list>
    <#if helper.checkGdccClassByName(classDef.superName)>
    <#-- No own override matched: forward along the GDCC extension class chain (godot-cpp
         class_db.cpp get_virtual_func pattern); native parents are covered by the engine's
         own fallback, so the chain only hops across GDCC classes. -->
    return ${classDef.superName}_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);
    <#else>
    return NULL;
    </#if>
}

void ${classDef.name}_class_call_virtual_with_data(GDExtensionClassInstancePtr p_instance,
                                                     GDExtensionConstStringNamePtr p_name,
                                                     void* p_virtual_call_userdata,
                                                     const GDExtensionConstTypePtr* p_args,
                                                     GDExtensionTypePtr r_ret) {
    (void)p_name;
    // Call virtual methods
    <#list classDef.functions as function>
        <#if helper.checkVirtualMethod(classDef, function)>
            if (p_virtual_call_userdata == <#if helper.countDefaultSlots(function) gt 0>&${helper.renderDefaultUserdataInstanceName(classDef, function)}<#else>&${classDef.name}_${function.name}</#if>) {
                <#-- GDExtension registration has no tool-script flag, so the editor suppression of
                     frame-loop callbacks is gated here: non-tool classes skip `_process` /
                     `_physics_process` while in the editor (Godot placeholder parity, frame-loop
                     part only); tool classes emit no check at all. The gate stays inside the
                     userdata-matched branch so non-frame-loop virtuals (`_ready`, ...) still run. -->
                <#if !classDef.tool && (function.name == "_process" || function.name == "_physics_process")>
                if (gdcc_is_editor_hint()) {
                    return;
                }
                </#if>
                ptrcall${helper.renderFuncBindName(classDef, function)}(p_virtual_call_userdata, p_instance, p_args, r_ret);
                return;
            }
        </#if>
    </#list>
    <#if helper.checkGdccClassByName(classDef.superName)>
    <#-- No own branch matched: the userdata may come from a GDCC parent's get_virtual (parent
         impl address or parent default userdata instance), so forward to the parent dispatch.
         The engine always invokes the most-derived instance's callback, hence the chain must
         fall through level by level; (Parent*)p_instance is layout-legal via offset-0 embedding.
         Runs after every own branch so the editor gates stay inside their own-hit branches. -->
    ${classDef.superName}_class_call_virtual_with_data(p_instance, p_name, p_virtual_call_userdata, p_args, r_ret);
    </#if>
}

// Methods for ${classDef.name}

<#list classDef.functions as func>
<#-- Coroutine functions are defined in the dedicated coroutine section below (body + start -->
<#-- thunk + engine entry); this loop stays the plain synchronous function surface. -->
<#if !func.coroutine>
<@funcHeader helper classDef func/> {
    <#list func.variables?values as var>
        <#if !func.checkVariableParameter(var.id)>
            ${helper.renderGdTypeInC(var.type)} $${var.id};
        </#if>
    </#list>
    ${bodyRender.generateFuncBody(classDef, func)}
}
</#if>
</#list>

</#list>

<#if helper.hasCoroutineFunctions()>
// Coroutine state class machinery, body functions, start thunks and engine entries
// (frontend_await_implementation.md §5-§7; ownership state machine: ownership spec §3.10)
<#list module.classDefs as classDef>
<#list classDef.functions as func>
<#if func.coroutine>
<#assign stateName = helper.renderCoroStateClassName(classDef, func)>
<#-- The desc object is defined before every use site (notification takes its address); -->
<#-- the four callbacks it references are declared `static` in entry.h. -->
static const gdcc_coro_state_desc ${helper.renderCoroStateDescName(classDef, func)} = {
    .pack_result = ${helper.renderCoroPackResultFuncName(classDef, func)},
    .copy_ret_slot = ${helper.renderCoroCopyRetSlotFuncName(classDef, func)},
    .destroy_ret_slot = ${helper.renderCoroDestroyRetSlotFuncName(classDef, func)},
    .emit_completed = ${helper.renderCoroEmitCompletedFuncName(classDef, func)},
};

GDExtensionObjectPtr ${stateName}_class_create_instance(void* p_class_userdata, GDExtensionBool p_notify_postinitialize) {
    (void)p_class_userdata;
    GDExtensionObjectPtr obj = godot_classdb_construct_object2(GD_STATIC_SN(u8"RefCounted"));
    ${stateName}* self = godot_mem_alloc(sizeof(${stateName}));
    self->_object = obj;
    godot_object_set_instance(obj, GD_STATIC_SN(u8"${stateName}"), self);
    // Hidden states need only their module-private identity binding. `set_instance` above,
    // not this binding, supplies the wrapper pointer to notification/free_instance callbacks.
    godot_object_set_instance_binding(obj, gdcc_coro_binding_token(),
        &self->${helper.renderCoroHeaderField()}, &${stateName}_class_binding_callbacks);
    if (p_notify_postinitialize) {
        godot_Object_notification(obj, godot_Object_NOTIFICATION_POSTINITIALIZE(), false);
    }
    return obj;
}

void ${stateName}_class_free_instance(void* p_class_userdata, GDExtensionClassInstancePtr p_instance) {
    (void)p_class_userdata;
    if (p_instance == NULL) {
        return;
    }
    ${stateName}* self = p_instance;
    <#list func.parameters as param>
        <#assign paramFreeStmt = helper.renderLambdaCaptureFreeStmt(param.type, "self->" + helper.renderCoroParamFieldPrefix() + param.name)>
        <#if paramFreeStmt?has_content>
    ${paramFreeStmt}
        </#if>
    </#list>
    <#-- Coroutine lambda capture frame fields: same exactly-once destroy discipline as the -->
    <#-- parameter fields above; the capture block itself stays with the Callable. -->
    <#list func.captureList as capture>
        <#assign captureFreeStmt = helper.renderLambdaCaptureFreeStmt(capture.type, "self->" + helper.renderCoroCaptureFieldPrefix() + capture.name)>
        <#if captureFreeStmt?has_content>
    ${captureFreeStmt}
        </#if>
    </#list>
    // The typed return slot is destroyed exactly once here (never from the cancel path).
    ${helper.renderCoroDestroyRetSlotFuncName(classDef, func)}(&self->${helper.renderCoroHeaderField()});
    gdcc_coro_state_free(&self->${helper.renderCoroHeaderField()});
    godot_mem_free(self);
}

void ${stateName}_class_notification(GDExtensionClassInstancePtr p_instance, int32_t p_what, GDExtensionBool p_reversed) {
    (void)p_reversed;
    ${stateName}* self = p_instance;
    if (p_what == godot_Object_NOTIFICATION_POSTINITIALIZE()) {
        gdcc_coro_state_header_init(&self->${helper.renderCoroHeaderField()}, &${helper.renderCoroStateDescName(classDef, func)}, self->_object);
<#if func.returnType.typeName != "void">
        self->${helper.renderCoroRetInitializedField()} = false;
</#if>
    } else if (p_what == godot_Object_NOTIFICATION_PREDELETE()) {
        // Abandonment path only: cancel-resume. Frame fields are left for free_instance;
        // state classes deliberately have no user-destructor equivalent.
        gdcc_coro_cancel(&self->${helper.renderCoroHeaderField()});
    }
}

static void ${helper.renderCoroPackResultFuncName(classDef, func)}(gdcc_coro_state_header *coro_header) {
<#if func.returnType.typeName == "void">
    // Void coroutine: result_cache stays the constructed nil Variant.
    (void)coro_header;
<#else>
    ${stateName}* self = (${stateName}*)((char*)coro_header - offsetof(${stateName}, ${helper.renderCoroHeaderField()}));
    if (!self->${helper.renderCoroRetInitializedField()}) {
        return;
    }
    // Copy (never move) the typed slot into result_cache; the slot stays alive for typed
    // waiters and the done fast path. Destroy-then-write: result_cache is always constructed.
    godot_Variant coro_packed = ${helper.renderPackFunctionName(func.returnType)}(${helper.renderValueRef(func.returnType, "self->" + helper.renderCoroRetField())});
    godot_variant_destroy(&coro_header->result_cache);
    coro_header->result_cache = coro_packed;
</#if>
}

static void ${helper.renderCoroCopyRetSlotFuncName(classDef, func)}(gdcc_coro_state_header *coro_header, void *out_typed) {
<#if func.returnType.typeName == "void">
    // Void specialization: the awaiter's result slot is a Variant whose resume value is nil
    // (aligned with Godot's completed(nil) for void coroutines).
    (void)coro_header;
    godot_variant_destroy((godot_Variant*)out_typed);
    *(godot_Variant*)out_typed = godot_new_Variant_nil();
<#else>
    ${stateName}* self = (${stateName}*)((char*)coro_header - offsetof(${stateName}, ${helper.renderCoroHeaderField()}));
    ${helper.renderCoroCopyRetStmt(func.returnType, "self->" + helper.renderCoroRetField(), "out_typed")}
</#if>
}

static void ${helper.renderCoroDestroyRetSlotFuncName(classDef, func)}(gdcc_coro_state_header *coro_header) {
<#if func.returnType.typeName == "void">
    (void)coro_header;
<#else>
    ${stateName}* self = (${stateName}*)((char*)coro_header - offsetof(${stateName}, ${helper.renderCoroHeaderField()}));
    if (!self->${helper.renderCoroRetInitializedField()}) {
        return; // tolerates never-written and moved-from slots
    }
    <#assign retFreeStmt = helper.renderLambdaCaptureFreeStmt(func.returnType, "self->" + helper.renderCoroRetField())>
    <#if retFreeStmt?has_content>
    ${retFreeStmt}
    </#if>
    self->${helper.renderCoroRetInitializedField()} = false;
</#if>
}

static void ${helper.renderCoroEmitCompletedFuncName(classDef, func)}(gdcc_coro_state_header *coro_header) {
    // Emit the engine-internal coroutine `completed(result)` signal shape on the state object.
    godot_StringName coro_completed_name = godot_new_StringName_with_utf8_chars("completed");
    godot_Signal coro_completed_sig = godot_new_Signal_with_Object_StringName((godot_Object*)coro_header->obj, &coro_completed_name);
    godot_StringName_destroy(&coro_completed_name);
    const godot_Variant* coro_completed_argv[] = { &coro_header->result_cache };
    godot_Signal_emit(&coro_completed_sig, coro_completed_argv, 1);
    godot_Signal_destroy(&coro_completed_sig);
}

<#if func.returnType.typeName != "void">
${helper.renderGdTypeInC(func.returnType)} ${helper.renderCoroMoveResultFuncName(classDef, func)}(gdcc_coro_state_header *coro_header) {
    ${stateName}* self = (${stateName}*)((char*)coro_header - offsetof(${stateName}, ${helper.renderCoroHeaderField()}));
    // Ownership moves to the caller (engine-boundary sync fast path); the slot is left
    // moved-from so the single destroy_ret_slot in free_instance stays correct.
    self->${helper.renderCoroRetInitializedField()} = false;
    return self->${helper.renderCoroRetField()};
}

</#if>
void ${helper.renderCoroBodyFunctionName(classDef, func)}(mco_coro *${helper.renderCoroCoParam()}) {
    ${stateName}* ${helper.renderCoroFrameLocal()} = (${stateName}*)mco_get_user_data(${helper.renderCoroCoParam()});
    <#list func.variables?values as var>
        <#-- Parameters and coroutine-lambda captures live in typed frame fields addressed -->
        <#-- through `CBodyBuilder`; declaring local C slots for them would double the storage. -->
        <#if !func.checkVariableParameter(var.id) && !func.checkVariableCapture(var.id)>
            ${helper.renderGdTypeInC(var.type)} $${var.id};
        </#if>
    </#list>
    ${bodyRender.generateFuncBody(classDef, func)}
}

<@coroStartThunkHeader helper classDef func/> {
    GDExtensionObjectPtr coro_state_obj = ${stateName}_class_create_instance(NULL, false);
    if (coro_state_obj == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: failed to create coroutine state object", __func__, __FILE__, __LINE__);
        return NULL;
    }
    gdcc_ref_counted_init_raw(coro_state_obj, true);
    gdcc_coro_state_header *coro_header = gdcc_coro_state_identify(coro_state_obj);
    if (coro_header == NULL) {
        GDCC_PRINT_RUNTIME_ERROR("gdcc: coroutine state object is missing its identity binding", __func__, __FILE__, __LINE__);
        release_object(coro_state_obj);
        return NULL;
    }
    ${stateName}* coro_state = (${stateName}*)((char*)coro_header
        - offsetof(${stateName}, ${helper.renderCoroHeaderField()}));
    <#list func.parameters as param>
    ${helper.renderCoroParamFillStmt(param.type, "coro_state->" + helper.renderCoroParamFieldPrefix() + param.name, "$" + param.name)}
    </#list>
    <#-- Coroutine lambda captures: per-call copy from the borrowed `_capture` -->
    <#-- block into fresh owning frame fields. Filled before `mco_create` so the OOM path is -->
    <#-- still cleaned by the uniform `free_instance` field sweep. -->
    <#list func.captureList as capture>
    ${helper.renderCoroCaptureFillStmt(capture.type, "coro_state->" + helper.renderCoroCaptureFieldPrefix() + capture.name, "_capture->" + capture.name)}
    </#list>
    mco_desc coro_desc = mco_desc_init(${helper.renderCoroBodyFunctionName(classDef, func)}, GDCC_CORO_STACK_SIZE);
    coro_desc.user_data = coro_state;
    if (mco_create(&coro_state->${helper.renderCoroHeaderField()}.co, &coro_desc) != MCO_SUCCESS) {
        // OOM: report, then complete synchronously with the default result so callers still
        // receive a well-formed done state object whose `co` is NULL (start thunk contract).
        GDCC_PRINT_RUNTIME_ERROR("gdcc: failed to create coroutine stack (out of memory); completing with the default result", __func__, __FILE__, __LINE__);
<#if func.returnType.typeName != "void">
        coro_state->${helper.renderCoroRetField()} = ${helper.renderDefaultValueExprInC(func.returnType)};
        coro_state->${helper.renderCoroRetInitializedField()} = true;
</#if>
        ${helper.renderCoroPackResultFuncName(classDef, func)}(&coro_state->${helper.renderCoroHeaderField()});
        coro_state->${helper.renderCoroHeaderField()}.done = true;
        return (godot_Object*)coro_state_obj;
    }
    mco_resume(coro_state->${helper.renderCoroHeaderField()}.co);
    if (mco_status(coro_state->${helper.renderCoroHeaderField()}.co) == MCO_DEAD) {
        gdcc_coro_finalize(&coro_state->${helper.renderCoroHeaderField()});
    }
    return (godot_Object*)coro_state_obj;
}

<#-- Engine entry: keeps the exact synchronous method name/signature, so ClassDB call/ptrcall -->
<#-- wrappers and virtual wiring stay untouched; internally dispatches through the start thunk. -->
<#-- Coroutine lambdas are excluded: they have no ClassDB surface at all — the Callable ABI's -->
<#-- `call_func` (entry.h) enters the start thunk directly with the capture block tail argument. -->
<#if !func.lambda>
<@funcHeader helper classDef func/> {
    godot_Object* coro_state_obj = ${helper.renderCoroStartThunkName(classDef, func)}(<#list func.parameters as param>$${param.name}<#if param_has_next>, </#if></#list>);
    if (coro_state_obj == NULL) {
<#if func.returnType.typeName == "void">
        return;
<#else>
        return ${helper.renderDefaultValueExprInC(func.returnType)};
</#if>
    }
<#if func.returnType.typeName == "void">
    // Void coroutine at the engine boundary: always detach; a suspended coroutine continues
    // in the background (Godot-aligned), a synchronous one simply dies with the state object.
    release_object(coro_state_obj);
<#else>
    gdcc_coro_state_header* coro_header = gdcc_coro_state_identify(coro_state_obj);
    if (coro_header == NULL) {
        release_object(coro_state_obj);
        GDCC_PRINT_RUNTIME_ERROR("gdcc: coroutine start returned an invalid state object", __func__, __FILE__, __LINE__);
        return ${helper.renderDefaultValueExprInC(func.returnType)};
    }
    if (coro_header->done) {
        // Synchronous completion fast path: no coroutine state escapes the engine boundary.
        ${helper.renderGdTypeInC(func.returnType)} r = ${helper.renderCoroMoveResultFuncName(classDef, func)}(coro_header);
        release_object(coro_state_obj);
        return r;
    }
    <#if func.returnType.typeName == "Variant">
    // Suspended: hand the state object out as a Variant (externally awaitable). The Variant
    // retains the RefCounted state object, so the thunk's OWNED reference is released here.
    godot_Variant r = godot_new_Variant_with_Object(coro_state_obj);
    release_object(coro_state_obj);
    return r;
    <#else>
    // Suspended with a typed non-Variant return: detach + default value + runtime error
    // (deliberate deviation, frontend_await_implementation.md §7.5 engine boundary); the coroutine continues detached.
    release_object(coro_state_obj);
    GDCC_PRINT_RUNTIME_ERROR("gdcc: coroutine method suspended at the engine boundary; typed non-Variant returns cannot carry the coroutine state, returning the default value", __func__, __FILE__, __LINE__);
    return ${helper.renderDefaultValueExprInC(func.returnType)};
    </#if>
</#if>
}
</#if>
</#if>
</#list>
</#list>
</#if>
