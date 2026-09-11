class_name VtMixedEngineVirtual
extends Node

# Dual-channel anchor: the same instance is observed through the engine channel (inherited
# _ready forwarding) and through the internal GDCC channel (vtable-indirect label() dispatch).
# SpecialWorker overrides only label(), so the engine must still reach Worker._ready for it.
class Worker extends Node:
    var ready_count: int = 0

    func _ready() -> void:
        ready_count = ready_count + 1

    func label() -> String:
        return "worker"

class SpecialWorker extends Worker:
    func label() -> String:
        return "special"

var worker: Worker
var special: SpecialWorker

func _ready() -> void:
    worker = Worker.new()
    special = SpecialWorker.new()
    add_child(worker)
    add_child(special)

func run_labels() -> Dictionary:
    var results := {}
    var special_as_worker: Worker = special
    results["worker_label"] = worker.label()
    results["special_via_worker"] = special_as_worker.label()
    results["special_direct"] = special.label()
    return results

func get_worker_ready_count() -> int:
    return worker.ready_count

func get_special_ready_count() -> int:
    return special.ready_count
