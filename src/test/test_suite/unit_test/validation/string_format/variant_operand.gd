extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var result = String(target.call("render", 7))
    if result == "value=7":
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("StringFormatVariantOperand validation failed: got '%s'" % result)
