class_name VtPassThroughChain
extends Node

# Pass-through anchor: B does not override tag(), so its vtable shares the
# slot value of A. Dispatch through an A- or B-typed receiver holding a B or C instance must
# resolve to the nearest real implementation without dereferencing a missing slot.
class A extends RefCounted:
    func tag() -> int:
        return 10

class B extends A:
    pass

class C extends B:
    func tag() -> int:
        return 30

func run() -> Dictionary:
    var results := {}
    var b: B = B.new()
    var c: C = C.new()
    var b_as_a: A = b
    var c_as_a: A = c
    var c_as_b: B = c
    results["b_via_a"] = b_as_a.tag()
    results["c_via_a"] = c_as_a.tag()
    results["c_via_b"] = c_as_b.tag()
    results["b_via_b"] = b.tag()
    return results
