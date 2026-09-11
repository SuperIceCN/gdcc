class_name VtControlFlowDispatch
extends Node

# Complex-control-flow anchor: polymorphic area()/kind() calls inside a for loop over a typed
# array with continue/break filtering, match dispatch, a ternary receiver, and a while loop.
class Shape extends RefCounted:
    func area() -> int:
        return 0

    func kind() -> String:
        return "shape"

class Square extends Shape:
    var side: int = 2

    func area() -> int:
        return side * side

    func kind() -> String:
        return "square"

class Triangle extends Shape:
    func area() -> int:
        return 3

    func kind() -> String:
        return "triangle"

func make_shapes() -> Array[Shape]:
    var shapes: Array[Shape] = []
    shapes.append(Shape.new())
    shapes.append(Square.new())
    shapes.append(Triangle.new())
    shapes.append(Square.new())
    return shapes

func total_weighted(shapes: Array[Shape]) -> int:
    var total := 0
    for s in shapes:
        if s.kind() == "shape":
            continue
        match s.kind():
            "square":
                total = total + s.area()
            "triangle":
                total = total + s.area() * 10
            _:
                total = total + 100
        if total > 30:
            break
    return total

func pick_area_case(use_square: bool, use_it: bool) -> int:
    var s: Shape
    if use_square:
        s = Square.new()
    else:
        s = Triangle.new()
    return s.area() if use_it else -1

func accumulate_case(use_square: bool, limit: int) -> int:
    var s: Shape
    if use_square:
        s = Square.new()
    else:
        s = Triangle.new()
    var acc := 0
    var i := 0
    while i < limit:
        acc = acc + s.area()
        if acc >= 8:
            break
        i = i + 1
    return acc
