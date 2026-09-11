class_name VtTemplateMethod
extends Node

# Template-method anchor: Greeter.greet() calls the virtual name() on its own self receiver.
# Because Greeter has a true descendant overriding name(), the self call must dispatch
# indirectly through the vtable and reach the runtime class override.
class Greeter extends RefCounted:
    func name() -> String:
        return "base"

    func greet() -> String:
        return "hi " + name()

class LoudGreeter extends Greeter:
    func name() -> String:
        return "loud"

class QuietGreeter extends Greeter:
    pass

func run() -> Dictionary:
    var results := {}
    var base: Greeter = Greeter.new()
    var loud: LoudGreeter = LoudGreeter.new()
    var quiet: QuietGreeter = QuietGreeter.new()
    results["base_greet"] = base.greet()
    results["loud_greet"] = loud.greet()
    results["quiet_greet"] = quiet.greet()
    return results
