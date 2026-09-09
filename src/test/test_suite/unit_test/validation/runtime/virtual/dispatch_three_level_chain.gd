extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var results = target.call("run")
    var expected := {
        "root_via_root": 1,
        "mid_via_root": 2,
        "leaf_via_root": 3,
        "leaf_via_mid": 3,
        "leaf_direct": 3,
    }
    for key in expected:
        if int(results[key]) != expected[key]:
            push_error("Three-level dispatch mismatch for %s: got %s, want %s." % [key, results[key], expected[key]])
            return
    print("__UNIT_TEST_PASS_MARKER__")
