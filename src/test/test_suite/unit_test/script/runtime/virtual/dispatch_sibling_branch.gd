class_name VtSiblingBranch
extends Node

# Sibling-branch anchor (Step 4 scenario 5): Right does not override score(), so calls on a
# Right-typed receiver stay on the direct call path to the inherited Base implementation even
# though the slot exists for the Left branch. Base-typed receivers still dispatch indirectly.
class Base extends RefCounted:
    func score() -> int:
        return 100

class Left extends Base:
    func score() -> int:
        return 1

class Right extends Base:
    pass

func run() -> Dictionary:
    var results := {}
    var left: Left = Left.new()
    var right: Right = Right.new()
    var left_as_base: Base = left
    var right_as_base: Base = right
    results["left_via_base"] = left_as_base.score()
    results["right_via_base"] = right_as_base.score()
    results["left_direct"] = left.score()
    results["right_direct"] = right.score()
    return results
