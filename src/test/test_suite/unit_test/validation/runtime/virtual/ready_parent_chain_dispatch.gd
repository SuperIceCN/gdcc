extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    if int(target.call("get_fallback_parent_ready_count")) != 1:
        push_error("Engine dispatch on a non-overriding child did not reach the parent _ready.")
        return
    if int(target.call("get_override_child_ready_count")) != 1:
        push_error("Overriding child _ready did not run exactly once.")
        return
    if int(target.call("get_override_parent_ready_count")) != 0:
        push_error("Parent _ready ran for an overriding child; most-derived dispatch regressed.")
        return
    print("__UNIT_TEST_PASS_MARKER__")
