extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var results = target.call("run")
    var expected := {
        "b_via_a": 10,
        "c_via_a": 30,
        "c_via_b": 30,
        "b_via_b": 10,
    }
    for key in expected:
        if int(results[key]) != expected[key]:
            push_error("Pass-through dispatch mismatch for %s: got %s, want %s." % [key, results[key], expected[key]])
            return
    print("__UNIT_TEST_PASS_MARKER__")
