extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var results = target.call("run")
    var expected := {
        "left_via_base": 1,
        "right_via_base": 100,
        "left_direct": 1,
        "right_direct": 100,
    }
    for key in expected:
        if int(results[key]) != expected[key]:
            push_error("Sibling-branch dispatch mismatch for %s: got %s, want %s." % [key, results[key], expected[key]])
            return
    print("__UNIT_TEST_PASS_MARKER__")
