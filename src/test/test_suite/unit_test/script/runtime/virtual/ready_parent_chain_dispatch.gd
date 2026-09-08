class_name ReadyParentChainDispatch
extends Node

# Engine-virtual parent forwarding anchor (R1/R2): engine-driven dispatch on a non-overriding
# child must fall through to the GDCC parent's _ready, while an overriding child must shadow the
# parent implementation entirely. Everything below is triggered by the engine; nothing is
# invoked manually as evidence.
class Parent extends Node:
    var ready_count: int = 0

    func _ready() -> void:
        ready_count = ready_count + 1

class ChildFallback extends Parent:
    pass

class ChildOverride extends Parent:
    var child_ready_count: int = 0

    func _ready() -> void:
        child_ready_count = child_ready_count + 1

var fallback_child: Parent
var override_child: ChildOverride

func _ready() -> void:
    fallback_child = ChildFallback.new()
    override_child = ChildOverride.new()
    add_child(fallback_child)
    add_child(override_child)

func get_fallback_parent_ready_count() -> int:
    return fallback_child.ready_count

func get_override_parent_ready_count() -> int:
    return override_child.ready_count

func get_override_child_ready_count() -> int:
    return override_child.child_ready_count
