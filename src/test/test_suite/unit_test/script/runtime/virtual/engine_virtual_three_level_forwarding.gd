class_name VtEngineVirtualForwarding
extends Node

# Engine-driven _process forwarding anchor: a grandchild that does not override _process must be
# driven through the GDCC parent chain (Leaf -> Mid -> Base), while an overriding grandchild
# shadows the base implementation entirely. Everything is triggered by the engine; nothing is
# invoked manually as evidence.
class Base extends Node:
    var base_ticks: int = 0

    func _process(delta: float) -> void:
        base_ticks = base_ticks + 1

class Mid extends Base:
    pass

class LeafPassThrough extends Mid:
    pass

class LeafOverride extends Mid:
    var own_ticks: int = 0

    func _process(delta: float) -> void:
        own_ticks = own_ticks + 1

var pass_leaf: LeafPassThrough
var override_leaf: LeafOverride

func _ready() -> void:
    pass_leaf = LeafPassThrough.new()
    override_leaf = LeafOverride.new()
    add_child(pass_leaf)
    add_child(override_leaf)

func get_pass_base_ticks() -> int:
    return pass_leaf.base_ticks

func get_override_base_ticks() -> int:
    return override_leaf.base_ticks

func get_override_own_ticks() -> int:
    return override_leaf.own_ticks
