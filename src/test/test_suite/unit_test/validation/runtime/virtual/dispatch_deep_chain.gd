extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var results = target.call("run")
    var expected := {
        "l2_via_l1": 1,
        "l4_via_l1": 3,
        "l4_via_l3": 3,
        "l5_via_l1": 5,
        "l5_via_l2": 5,
        "l5_via_l3": 5,
        "l5_via_l4": 5,
        "l2_direct": 1,
        "l4_direct": 3,
        "l5_direct": 5,
    }
    for key in expected:
        if int(results[key]) != expected[key]:
            push_error("Deep-chain dispatch mismatch for %s: got %s, want %s." % [key, results[key], expected[key]])
            return
    print("__UNIT_TEST_PASS_MARKER__")
