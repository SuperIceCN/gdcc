extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var results = target.call("run")
    var expected := {
        "base_greet": "hi base",
        "loud_greet": "hi loud",
        "quiet_greet": "hi base",
    }
    for key in expected:
        if String(results[key]) != expected[key]:
            push_error("Template-method dispatch mismatch for %s: got %s, want %s." % [key, results[key], expected[key]])
            return
    print("__UNIT_TEST_PASS_MARKER__")
