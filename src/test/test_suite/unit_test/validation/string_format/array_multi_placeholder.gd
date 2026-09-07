extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var result = String(target.call("render"))
    if result == "hp=10":
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("StringFormatArrayMultiPlaceholder validation failed: got '%s'" % result)
