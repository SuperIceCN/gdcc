extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var padded = String(target.call("padded"))
    if padded != "00042":
        push_error("StringFormatNumericFamily padded failed: got '%s'" % padded)
        return
    var precise = String(target.call("precise"))
    if precise != "3.14":
        push_error("StringFormatNumericFamily precise failed: got '%s'" % precise)
        return
    var hexed = String(target.call("hexed"))
    if hexed != "ff":
        push_error("StringFormatNumericFamily hexed failed: got '%s'" % hexed)
        return
    print("__UNIT_TEST_PASS_MARKER__")
