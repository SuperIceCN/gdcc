class_name VtSuperDispatch
extends Node

# Super route anchors (frontend super wiring): `super.m(...)` bypasses the current-class override
# and hits the nearest ancestor implementation (never the vtable); bare `super(...)` reuses the
# enclosing method name against the same parent chain.
class Base extends RefCounted:
    func score() -> int:
        return 10
    func greet(name: String) -> String:
        return "Base:" + name

class Mid extends Base:
    func score() -> int:
        return 20

class Leaf extends Mid:
    func score() -> int:
        # nearest ancestor implementation is Mid.score (20), not Base.score (10)
        return 30 + super.score()
    func greet(name: String) -> String:
        # Mid has no greet, so the chain reaches the grandparent implementation
        return super.greet(name)

class BareSuper extends Base:
    func greet(name: String) -> String:
        return super(name) + "!"

func run() -> Dictionary:
    var results := {}
    var leaf := Leaf.new()
    results["leaf_super_score"] = leaf.score()
    results["leaf_super_greet"] = leaf.greet("x")
    var bare := BareSuper.new()
    results["bare_super_greet"] = bare.greet("y")
    return results
