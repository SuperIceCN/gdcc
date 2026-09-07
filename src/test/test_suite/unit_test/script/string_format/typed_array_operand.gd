class_name StringFormatTypedArrayOperand
extends Node

func render() -> String:
    var values: Array[int] = [1, 2]
    return "%d,%d" % values
