class_name StringFormatNumericFamily
extends Node

func padded() -> String:
    return "%05d" % 42

func precise() -> String:
    return "%.2f" % 3.14159

func hexed() -> String:
    return "%x" % 255
