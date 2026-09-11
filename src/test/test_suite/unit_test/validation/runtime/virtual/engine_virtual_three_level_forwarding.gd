extends Node

const REQUIRED_PROCESS_FRAMES := 3

func _ready() -> void:
    # Wait for a few idle frames so the engine can drive _process through the GDCC chain.
    for _i in range(REQUIRED_PROCESS_FRAMES):
        await get_tree().process_frame

    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var pass_base_ticks = int(target.call("get_pass_base_ticks"))
    if pass_base_ticks < REQUIRED_PROCESS_FRAMES - 1:
        push_error("Engine _process did not forward through Mid to Base for the pass-through leaf.")
        return
    if int(target.call("get_override_own_ticks")) < REQUIRED_PROCESS_FRAMES - 1:
        push_error("Overriding leaf _process was not driven by the engine.")
        return
    if int(target.call("get_override_base_ticks")) != 0:
        push_error("Base _process ran for an overriding leaf; most-derived dispatch regressed.")
        return
    print("__UNIT_TEST_PASS_MARKER__")
