class_name Test extends MeshInstance3D

@export var rotating_speed: float = 30;

func _process(delta: float) -> void:
    self.rotation_degrees.y += rotating_speed * delta;
