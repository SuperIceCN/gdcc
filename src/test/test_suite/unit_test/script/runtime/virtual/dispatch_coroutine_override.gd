class_name VtCoroutineOverrideDispatch
extends Node

# Polymorphic coroutine anchor: awaiting run() on a BaseWorker-typed receiver holding a
# LeafWorker must start LeafWorker.run through the vtable slot start thunk, not the base one.
class BaseWorker extends Node:
    func run() -> int:
        await get_tree().process_frame
        return 1

class LeafWorker extends BaseWorker:
    func run() -> int:
        await get_tree().process_frame
        return 2

var leaf_result: int = 0
var base_result: int = 0

func start_dispatch() -> void:
    drive()

func drive() -> void:
    var leaf := LeafWorker.new()
    add_child(leaf)
    var leaf_as_base: BaseWorker = leaf
    leaf_result = await leaf_as_base.run()
    var base := BaseWorker.new()
    add_child(base)
    base_result = await base.run()

func get_leaf_result() -> int:
    return leaf_result

func get_base_result() -> int:
    return base_result
