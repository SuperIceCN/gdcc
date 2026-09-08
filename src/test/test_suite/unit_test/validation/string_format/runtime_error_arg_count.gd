# gdcc-test: output_contains=not enough arguments for format string
# gdcc-test: output_not_contains=runtime_error_arg_count validation failed
extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    # Godot's sprintf reports placeholder/argument count mismatch by returning the error
    # text as the result string and continuing execution, so the marker goes only after
    # the error text has been observed: an aborting bad call would land in else instead.
    var result = String(target.call("bad"))
    if result.contains("not enough arguments for format string"):
        print(result)
        print("__UNIT_TEST_PASS_MARKER__")
    else:
        push_error("runtime_error_arg_count validation failed: got '%s'" % result)
