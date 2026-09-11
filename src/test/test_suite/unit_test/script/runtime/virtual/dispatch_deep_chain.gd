class_name VtDeepChainDispatch
extends Node

# Five-level chain with overrides only at L1/L3/L5 and pass-through levels in between. Calls
# through every intermediate static type must resolve the nearest ancestor implementation, which
# stresses owner != introducer resolution across multiple hops.
class L1 extends RefCounted:
    func level() -> int:
        return 1

class L2 extends L1:
    pass

class L3 extends L2:
    func level() -> int:
        return 3

class L4 extends L3:
    pass

class L5 extends L4:
    func level() -> int:
        return 5

func run() -> Dictionary:
    var results := {}
    var l2: L2 = L2.new()
    var l4: L4 = L4.new()
    var l5: L5 = L5.new()
    var l2_as_l1: L1 = l2
    var l4_as_l1: L1 = l4
    var l4_as_l3: L3 = l4
    var l5_as_l1: L1 = l5
    var l5_as_l2: L2 = l5
    var l5_as_l3: L3 = l5
    var l5_as_l4: L4 = l5
    results["l2_via_l1"] = l2_as_l1.level()
    results["l4_via_l1"] = l4_as_l1.level()
    results["l4_via_l3"] = l4_as_l3.level()
    results["l5_via_l1"] = l5_as_l1.level()
    results["l5_via_l2"] = l5_as_l2.level()
    results["l5_via_l3"] = l5_as_l3.level()
    results["l5_via_l4"] = l5_as_l4.level()
    results["l2_direct"] = l2.level()
    results["l4_direct"] = l4.level()
    results["l5_direct"] = l5.level()
    return results
