extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var shapes = target.call("make_shapes")
    # Shape is continue-skipped, then Square(4)+Triangle(30)=34 breaks; the trailing Square is unreachable.
    if int(target.call("total_weighted", shapes)) != 34:
        push_error("Loop/match dispatch returned an unexpected total.")
        return
    if int(target.call("pick_area_case", true, true)) != 4:
        push_error("Ternary dispatch on Square failed.")
        return
    if int(target.call("pick_area_case", false, true)) != 3:
        push_error("Ternary dispatch on Triangle failed.")
        return
    if int(target.call("pick_area_case", true, false)) != -1:
        push_error("Ternary else arm failed.")
        return
    if int(target.call("accumulate_case", true, 5)) != 8:
        push_error("While dispatch on Square failed.")
        return
    if int(target.call("accumulate_case", false, 5)) != 9:
        push_error("While dispatch on Triangle failed.")
        return
    print("__UNIT_TEST_PASS_MARKER__")
