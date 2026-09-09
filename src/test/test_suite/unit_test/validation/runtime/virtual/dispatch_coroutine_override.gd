extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    target.call("start_dispatch")
    if int(target.call("get_leaf_result")) != 0:
        push_error("Polymorphic coroutine did not suspend before the first frame.")
        return

    for _i in range(4):
        await get_tree().process_frame

    if int(target.call("get_leaf_result")) != 2:
        push_error("Vtable coroutine start thunk did not reach the leaf override.")
        return
    if int(target.call("get_base_result")) != 1:
        push_error("Base coroutine result regressed.")
        return
    print("__UNIT_TEST_PASS_MARKER__")
