class_name VtThreeLevelChain
extends Node

# Vtable dispatch anchor: a root/mid/leaf override chain observed through
# root-, mid-, and leaf-typed receivers. Root-typed receivers must dispatch indirectly to the
# most-derived override, a mid-typed receiver holding a leaf exercises owner != introducer
# (rank is introduced by Root but owned by Mid at that static type), and a leaf-typed receiver
# stays on the direct devirtualized call path.
class Root extends RefCounted:
    func rank() -> int:
        return 1

class Mid extends Root:
    func rank() -> int:
        return 2

class Leaf extends Mid:
    func rank() -> int:
        return 3

func run() -> Dictionary:
    var results := {}
    var root: Root = Root.new()
    var mid: Mid = Mid.new()
    var leaf: Leaf = Leaf.new()
    var mid_as_root: Root = mid
    var leaf_as_root: Root = leaf
    var leaf_as_mid: Mid = leaf
    results["root_via_root"] = root.rank()
    results["mid_via_root"] = mid_as_root.rank()
    results["leaf_via_root"] = leaf_as_root.rank()
    results["leaf_via_mid"] = leaf_as_mid.rank()
    results["leaf_direct"] = leaf.rank()
    return results
