<#-- @ftlvariable name="module" type="gd.script.gdcc.lir.LirModule" -->
<#-- @ftlvariable name="helper" type="gd.script.gdcc.backend.c.gen.CGenHelper" -->
<#-- @ftlvariable name="objectFatPtrSpecs" type="java.util.List<gd.script.gdcc.backend.c.gen.fatptr.ObjectFatPtrSpec>" -->
#ifndef ${headerGuardPrefix}_OBJECT_FAT_PTR_TYPES_H
#define ${headerGuardPrefix}_OBJECT_FAT_PTR_TYPES_H

#include <godot_binding.h>
#include <gdcc_helper.h>

<#list objectFatPtrSpecs as spec>
<#if spec.kind.name() == "GDCC">
typedef struct ${spec.canonicalClassName} ${spec.canonicalClassName};
</#if>
</#list>
<#if objectFatPtrSpecs?size gt 0>
// Object fat pointer declarations

<#list objectFatPtrSpecs as spec>
typedef struct ${spec.fatPtrTypeName} {
    ${spec.pointerCType}ptr;
    GDObjectInstanceID instance_id;
} ${spec.fatPtrTypeName};

</#list>
<#list objectFatPtrSpecs as spec>
<#if spec.kind.name() == "GDCC">
static inline GDExtensionObjectPtr ${spec.objectPtrHelperName}(${spec.canonicalClassName} *self);
</#if>
</#list>
// Object fat pointer helpers
// Query helpers below are ownership-neutral (no retain/release). They may observe ObjectDB
// state; pure/const attributes are applied only where the runtime header already does so for
// shared helpers. `_to_variant` constructs a new Variant and is therefore side-effecting.

<#list objectFatPtrSpecs as spec>
/// Canonical null fat pointer `{ NULL, 0 }`. Ownership-neutral constructor.
static inline ${spec.fatPtrTypeName} ${spec.fatPtrTypeName}_null(void) {
    ${spec.fatPtrTypeName} result = { NULL, 0 };
    return result;
}

/// Captures a live raw Godot object pointer into a fat pointer (ownership-neutral).
/// Callers must pass NULL or a live raw pointer; dangling raw pointers are undefined.
static inline ${spec.fatPtrTypeName} ${spec.fatPtrTypeName}_from_raw(GDExtensionObjectPtr raw) {
    if (raw == NULL) {
        return ${spec.fatPtrTypeName}_null();
    }
    GDObjectInstanceID id = godot_object_get_instance_id(raw);
<#if spec.kind.name() == "GDCC">
    ${spec.pointerCType}ptr = (${spec.pointerCType})gdcc_object_from_godot_object_ptr(raw);
<#else>
    ${spec.pointerCType}ptr = (${spec.pointerCType})raw;
</#if>
    ${spec.fatPtrTypeName} result = { ptr, id };
    return result;
}

/// Reads an OBJECT Variant into a fat pointer (ownership-neutral materialization).
/// The original instance ID is preserved; freed payloads keep the ID but have a NULL typed pointer.
/// This relies on ObjectDB removing freed entries so `gdcc_object_live_ptr` returns NULL.
static inline ${spec.fatPtrTypeName} ${spec.fatPtrTypeName}_from_variant(const godot_Variant *value) {
    if (value == NULL || godot_variant_get_type(value) != GDEXTENSION_VARIANT_TYPE_OBJECT) {
        return ${spec.fatPtrTypeName}_null();
    }
    GDObjectInstanceID id = godot_variant_get_object_instance_id(value);
    if (id == 0) {
        return ${spec.fatPtrTypeName}_null();
    }
    GDExtensionObjectPtr raw = gdcc_object_live_ptr(id);
    if (raw == NULL) {
        ${spec.fatPtrTypeName} result = { NULL, id };
        return result;
    }
<#if spec.kind.name() == "GDCC">
    ${spec.pointerCType}ptr = (${spec.pointerCType})gdcc_object_from_godot_object_ptr(raw);
<#else>
    ${spec.pointerCType}ptr = (${spec.pointerCType})raw;
</#if>
    ${spec.fatPtrTypeName} result = { ptr, id };
    return result;
}

/// Returns the validated raw Godot object pointer for this fat pointer (query; no ownership change).
/// RefCountedStatus specializes the fast path: YES uses the cached pointer, NO uses ObjectDB,
/// UNKNOWN checks the ObjectID reference bit at runtime.
static inline GDExtensionObjectPtr ${spec.fatPtrTypeName}_live_object(${spec.fatPtrTypeName} value) {
<#if spec.refCountedStatus.name() == "YES">
    if (unlikely(value.instance_id == 0 || value.ptr == NULL)) {
        return NULL;
    }
<#if spec.kind.name() == "GDCC">
    return ${spec.objectPtrHelperName}(value.ptr);
<#else>
    return (GDExtensionObjectPtr)value.ptr;
</#if>
<#elseif spec.refCountedStatus.name() == "NO">
    return gdcc_object_live_ptr(value.instance_id);
<#else>
    if (unlikely(value.instance_id == 0)) {
        return NULL;
    }
    if (gdcc_object_id_is_ref_counted(value.instance_id)) {
        if (unlikely(value.ptr == NULL)) {
            return NULL;
        }
<#if spec.kind.name() == "GDCC">
        return ${spec.objectPtrHelperName}(value.ptr);
<#else>
        return (GDExtensionObjectPtr)value.ptr;
</#if>
    }
    return gdcc_object_live_ptr(value.instance_id);
</#if>
}

/// Returns the validated statically-typed pointer for this fat pointer (query; no ownership change).
static inline ${spec.pointerCType}${spec.fatPtrTypeName}_live_ptr(${spec.fatPtrTypeName} value) {
<#if spec.kind.name() == "GDCC">
<#if spec.refCountedStatus.name() == "YES">
    if (value.instance_id == 0 || value.ptr == NULL) {
        return NULL;
    }
    return value.ptr;
<#elseif spec.refCountedStatus.name() == "NO">
    GDExtensionObjectPtr raw = gdcc_object_live_ptr(value.instance_id);
    if (raw == NULL) {
        return NULL;
    }
    return (${spec.pointerCType})gdcc_object_from_godot_object_ptr(raw);
<#else>
    if (value.instance_id == 0) {
        return NULL;
    }
    if (gdcc_object_id_is_ref_counted(value.instance_id)) {
        return value.ptr;
    }
    GDExtensionObjectPtr raw = gdcc_object_live_ptr(value.instance_id);
    if (raw == NULL) {
        return NULL;
    }
    return (${spec.pointerCType})gdcc_object_from_godot_object_ptr(raw);
</#if>
<#else>
    return (${spec.pointerCType})${spec.fatPtrTypeName}_live_object(value);
</#if>
}

/// Packs a fat pointer into an OBJECT Variant (side-effecting: constructs a new Variant).
/// Freed fat pointers degrade to OBJECT/null because public ABI cannot construct an ID-only Variant.
static inline godot_Variant ${spec.fatPtrTypeName}_to_variant(${spec.fatPtrTypeName} value) {
    return godot_new_Variant_with_Object(${spec.fatPtrTypeName}_live_object(value));
}

</#list>
<#assign objectFatPtrUpcastSpecs = helper.collectObjectFatPtrUpcastSpecs(objectFatPtrSpecs)>
<#if objectFatPtrUpcastSpecs?size gt 0>
// Object fat pointer upcast helpers

<#list objectFatPtrUpcastSpecs as upcast>
/// Upcasts a fat pointer while preserving its instance ID (ownership-neutral).
/// Dead sources keep the ID but produce a NULL target pointer.
static inline ${upcast.target.fatPtrTypeName} ${upcast.helperName}(${upcast.source.fatPtrTypeName} value) {
    GDExtensionObjectPtr raw = ${upcast.source.fatPtrTypeName}_live_object(value);
    ${upcast.target.fatPtrTypeName} result;
    result.instance_id = value.instance_id;
    if (raw == NULL) {
        result.ptr = NULL;
        return result;
    }
<#if upcast.target.kind.name() == "GDCC">
    result.ptr = (${upcast.target.pointerCType})gdcc_object_from_godot_object_ptr(raw);
<#else>
    result.ptr = (${upcast.target.pointerCType})raw;
</#if>
    return result;
}

</#list>
</#if>
</#if>
#endif
