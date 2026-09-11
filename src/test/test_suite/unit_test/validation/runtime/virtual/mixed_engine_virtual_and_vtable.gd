extends Node

func _ready() -> void:
    var target = get_parent().get_node_or_null("__UNIT_TEST_TARGET_NODE_NAME__")
    if target == null:
        push_error("Target node missing.")
        return

    var results = target.call("run_labels")
    if String(results["worker_label"]) != "worker":
        push_error("Worker label regressed.")
        return
    if String(results["special_via_worker"]) != "special":
        push_error("Vtable-indirect label() on a Worker-typed receiver did not reach the override.")
        return
    if String(results["special_direct"]) != "special":
        push_error("Direct label() on SpecialWorker regressed.")
        return
    if int(target.call("get_worker_ready_count")) != 1:
        push_error("Engine _ready did not run exactly once for Worker.")
        return
    if int(target.call("get_special_ready_count")) != 1:
        push_error("Engine _ready did not forward to Worker._ready for SpecialWorker.")
        return
    print("__UNIT_TEST_PASS_MARKER__")
