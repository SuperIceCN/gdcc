class_name StringFormatCompoundAssignment
extends Node

func render() -> String:
    var label := "hp=%d"
    label %= [5]
    return label
