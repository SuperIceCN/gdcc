# Low IR

Low IR is the intermediate representation (IR) emitted by the middle-end
of the compiler mapping directly to GDExtension APIs.

## Overview

Low IR is a function level IR. Each function consists of 3 parts:

- Signature: names, parameters, return type
- Variables: local variables used in the function
  - Variables are indexed starting from 0, var 0-N are function parameters
  - Variables have id, type, name(optional) and storage class (parameter, stack, constant)
- Basic Blocks: sequences of instructions with a single entry and exit point
  - Basic blocks have id and a list of instructions
  - Control flow is represented by branches between basic blocks
  - Each function has an entry block and may have multiple exit blocks
  - Each basic block ends with a terminator instruction (branch, return, etc.)

Low IR does NOT use an SSA form since it is designed to be transpiled to
GDExtension C code. Instead, Low IR uses explicit load/store instructions to
manipulate variables.

## Types

See [Types](gdcc_type_system.md) for details on the type system used in Low IR.

Low IR can also carry backend-owned compiler-only types. They use the `compiler::<Name>` textual grammar and are only valid on function-local `<variables>` storage unless a dedicated validator explicitly allows a wider surface.

Current MVP contract:

- Compiler-only type text is LIR-only and does not reuse source-facing declared-type parsing.
- `compiler::<Name>` is accepted only for function `<variables>`.
- Compiler-only types must not appear on:
  - function `<parameters>`
  - function `<return_type>`
  - class `<properties>`
  - signal parameters
  - lambda captures
- Concrete grammar instances include `compiler::GdccForRangeIter`, per-family
  `compiler::GdccForPacked*ArrayIter` (e.g. `compiler::GdccForPackedInt32ArrayIter`), and
  `compiler::GdccCoroState` (hidden coroutine state object reference, see
  [Coroutine Instructions](#coroutine-instructions)).

## Instructions

Each instruction has an optional return value, a string id, and a list of operands:

```
($<result_id>)? = <instruction_id> ($<operand_id>|<literial>) ...
```

### New Data Instructions

#### literal_bool

Creates a new boolean constant.

```
$<result_id> = literal_bool <true|false>
```

#### literal_int

Creates a new i64 constant.

```
$<result_id> = literal_int <i64_value>
```

#### literal_float

Creates a new f64 constant.

```
$<result_id> = literal_float <f64_value>
```

#### literal_string

Creates a new String constant.

```
$<result_id> = literal_string "<string_value_utf8>"
```

#### literal_string_name

Creates a new StringName constant.

```
$<result_id> = literal_string_name "<string_value_utf8>"
```

#### literal_node_path

Creates a new NodePath constant.

```
$<result_id> = literal_node_path "<node_path_value_utf8>"
```

The operand is the already-decoded NodePath payload: the `^` prefix and outer quotes of the
`^"..."` source lexeme should be stripped.

#### literal_nil

Creates a new Variant Nil constant.

```
$<result_id> = literal_nil
```

#### literal_null

Create a new Object null constant.

```
$<result_id> = literal_null
```

#### assign

Assign one variable to another.
The source variable must be assignable to the result variable type.
Current assignability rules in backend codegen are:

- Same type.
- Object inheritance upcast.
- Container covariance (limited):
  - `Array[T]` to `Array` / `Array[Variant]`.
  - `Array[SubClass]` to `Array[SuperClass]`.
  - `Dictionary[K, V]` to `Dictionary` / `Dictionary[Variant, Variant]`.
  - `Dictionary[K1, V1]` to `Dictionary[K2, V2]` when `K1` is assignable to `K2` and `V1` is assignable to `V2`.
- Compiler-only types are not widened into ordinary ABI-facing assignability. They stay on backend-owned local/intrinsic paths.

```
$<result_id> = assign $<source_id>
```

### Construction & Destruction Instructions

#### construct_builtin

Constructs a builtin of a specific type with arguments. The type is the same as the type
of the result variable.

```
$<result_id> = construct_builtin $<arg1_id> $<arg2_id> ...
```

#### construct_array

Constructs a new `Array` or `Packed*Array` depending on the result variable type.

Rules:

- If result variable type is `Array` (`GdArrayType`):
  - `class_name` is optional.
  - If omitted, constructs generic `Array[Variant]`.
  - If provided, it is a typed hint and must match the result variable element type.
- If result variable type is `Packed*Array` (`GdPackedArrayType`):
  - Construction type is inferred only from the result variable type.
  - `class_name` must not be provided; providing it is invalid and should fail fast.

```
$<result_id> = construct_array "<class_name>"?
```

#### construct_dictionary

Constructs a new TypedDictionary of a specific class / type.
If both class names are omitted, constructs a generic Dictionary.
If only one class name is provided, constructs a TypedDictionary with
the specified key type with a generic value type.

```
$<result_id> = construct_dictionary "<key_class_name>"? "<value_class_name>"?
```

#### construct_container_literal

Constructs a fresh `Array` or `Dictionary` and fills it from already-materialized
operand slots. Family is decided solely by the result variable type.

Rules:

- Result variable type must be non-ref `GdArrayType` or `GdDictionaryType` (never
  `Packed*Array` or other builtins). Backend rejects missing / ref / non-container results.
- All operands must be `VariableOperand`. Empty operands are legal for both families.
- If result is `GdArrayType`: operands are element0..elementN in source order.
- If result is `GdDictionaryType`: operands are key0/value0/key1/value1/...;
  operand count must be even (backend validation).
- Element/key/value ordinary-boundary conversions (e.g. `int -> float`,
  `String -> StringName`, Variant pack/unpack) are emitted by frontend body lowering
  _before_ this instruction. Backend only packs operands to Variant for container writes.

This instruction does **not** replace empty `construct_array` / `construct_dictionary`,
which remain the contract for `__prepare__`, property default init, and explicit empty
typed/generic construction.

```
$<result_id> = construct_container_literal $<operand0_id> $<operand1_id> ...
```

#### construct_object

Constructs a new Object of a specific class.
If the new class object extends RefCounted, the returned object is owned (reference count increased by 1).
Current C backend lowers this instruction by:

- calling `godot_new_XXX()` directly for engine classes
- calling generated `XXX_class_create_instance(NULL, true)` for non-`RefCounted` GDCC classes
- calling `XXX_class_create_instance(NULL, false)` and then `gdcc_ref_counted_init_raw(..., true)`
  when the constructed GDCC class definitely inherits `RefCounted`
- leaving engine-driven / GDScript-driven GDCC `RefCounted` instantiation to Godot's own reference
  count initialization path instead of duplicating that work inside `*_class_create_instance(...)`
- then reusing the existing object-slot write / pointer-conversion ownership path instead of
  introducing a dedicated constructor-only lifecycle branch

```
$<result_id> = construct_object <class_name>
```

#### construct_signal

Constructs a new Signal value from a live object receiver and a compile-time signal name.
The result is a destroyable builtin value and does not keep the receiver alive.

```
$<result_id> = construct_signal $<receiver_id> "<signal_name>"
```

#### construct_callable

Constructs a new Callable from an instance receiver and a compile-time method name.
Operand schema stays `(VARIABLE, STRING)` min/max=2. The old one-operand form is illegal.
`$receiver` type dispatch:

- `GdObjectType` → `godot_new_Callable_with_Object_StringName`
- non-Object builtin → pack a temporary Variant, then `godot_Callable_create(&tmp, name)`
  (the static wrapper takes no `self` parameter; its ptrcall base is NULL internally)
- `Variant` / static / utility → illegal
  The result is a destroyable builtin value and does not keep the receiver alive.

```
$<result_id> = construct_callable $<receiver_id> "<method_name>"
```

#### construct_standalone_callable

Constructs a new Callable for a compile-time known function that has no instance receiver.
`kind` is one of `utility`, `static_gdcc`, `static_engine`. Utility owner must be empty;
static kinds require a class owner. Backend uses `godot_callable_custom_create2`.

```
$<result_id> = construct_standalone_callable "<kind>" "<owner_or_empty>" "<name>"
```

#### construct_lambda

Constructs a new Callable from a lambda function in this compiling unit.
Backend uses `gdcc_new_lambda_callable` → `godot_callable_custom_create2`.
Captures are copied into a heap `${Class}_Capture_${func}` and stored as
`callable_userdata`. If there are no captures, userdata is `NULL` and
`free_func` is a no-op. With captures, `free_func` destroys each destroyable
field and then `godot_mem_free`s the block.
The generated lambda C function takes source parameters plus a trailing
`_capture` pointer when `captureCount > 0`. After locals are declared and
before `__prepare__`, the backend copies `_capture->name` into the matching
`$name` slot. Capture locals are excluded from `__prepare__` default
construction so destroyable types are not leaked.

```
$<result_id> = construct_lambda "<lambda_function_name>" $<capture1_id> $<capture2_id> ...
```

#### destruct

Destructs a variable, releasing any resources it holds.
All Variants must be destructed after use to avoid memory leaks.

Warning:

- Destruct object that is not ref-counted is not always needed since users may want to do it manually.
- Destruct object that is not ref-counted does actually mem-delete the object.
- However, destructing ref-counted objects is required to decrease the reference count.

Types can be destruct:

- String
- StringName
- NodePath
- Callable
- Signal
- Dictionary
- Array
- Packed*Array
- Object
- Variant
- `compiler::GdccCoroState` (destruction releases the owned state object reference —
  this is the fire-and-forget detach for statement-position coroutine calls, see
  [await](#await)); other compiler-only types follow their own registered destroy helper.
  All types not in the above list are stack allocated and do not need to be destructed.
  However, destructing them is a no-op and allowed.

An optional lifecycle provenance enum string can be provided: `AUTO_GENERATED`, `INTERNAL`, `USER_EXPLICIT`, `UNKNOWN`.
If it is not provided, it defaults to `UNKNOWN` and a warning should be emitted since provenance is important for validating the correct usage of this instruction.

This instruction should not be used arbitrarily on any variable. It should only be used in specific scenarios that meets the provenance requirement.

Restrictions:

- Allowed:
  - `AUTO_GENERATED`: only compiler-injected destruct in `__finally__`.
  - `INTERNAL`: only compiler internal/temp variables (for example numeric IDs or `__tmp_*` IDs).
  - `USER_EXPLICIT`: only frontend-lowered explicit lifecycle intent from user GDScript source.
  - `UNKNOWN`: compatibility mode warning only; strict mode rejects.
- Forbidden:
  - Hand-written or externally injected lifecycle instructions without valid provenance.
  - `AUTO_GENERATED` outside compiler auto-generated paths.
  - `INTERNAL` on ordinary user-named variables.
- Violation result:
  - Backend validation fails fast with `InvalidInsnException` before C code generation.

```
destruct $<variant_id> "[lifecycle provenance]"
```

#### try_own_object

Attempts to take ownership of an Object. If successful, the reference count is increased.
If the Object is not ref-counted, this is a no-op.

The lifecycle provenance is the same as `destruct` instruction.
The same restrictions and validation behavior apply.

```
try_own_object $<object_id> "[lifecycle provenance]"
```

#### try_release_object

Attempts to release ownership of an Object. If successful, the reference count is decreased.
If the Object is not ref-counted, this is a no-op.

The lifecycle provenance is the same as `destruct` instruction.
The same restrictions and validation behavior apply.

```
try_release_object $<object_id> "[lifecycle provenance]"
```

#### unary_op

Performs a built-in operation on one operand.
For all available operations, see enum `GodotOperator`.

```
$<result_id> = unary_op "<op_name>" $<operand_id>
```

#### binary_op

Performs a built-in operation on two operands.
For all available operations, see enum `GodotOperator`.

```
$<result_id> = binary_op "<op_name>" $<left_operand_id> $<right_operand_id>
```

### Indexing Instructions

#### variant_get

Gets a value from a Variant by another Variant.

```
$<result_id> = variant_get $<variant_id> $<key>
```

#### variant_get_keyed

Gets a value from a keyed Variant (usually Dictionary) by another Variant

```
$<result_id> = variant_get_keyed $<keyed_variant_id> $<Variant>
```

#### variant_get_named

Gets a value from a named Variant (usually Object) by StringName.

```
$<result_id> = variant_get_named $<named_variant_id> $<name_id:StringName>
```

#### variant_get_indexed

Gets a value in a Variant by an int variable.

```
$<result_id> = variant_get_indexed $<variant_id> $<index_id:int>
```

#### variant_set

Sets a value in a Variant by another Variant.

```
variant_set $<variant_id> $<key> $<value>
```

#### variant_set_keyed

Sets a value in a keyed Variant (usually Dictionary) by another Variant

```
variant_set_keyed $<keyed_variant_id> $<key> $<value>
```

#### variant_set_named

Sets a value in a named Variant (usually Object) by StringName.

```
variant_set_named $<named_variant_id> $<name_id:StringName> $<value>
```

#### variant_set_indexed

Sets a value in a Variant by an int variable.

```
variant_set_indexed $<variant_id> $<index_id:int> $<value>
```

### Type Instructions

#### get_variant_type

Gets the type int id of Variant. The id is the same in order of `GdExtensionTypeEnum`.

```
$<result_id:int> = get_variant_type $<variant_id>
```

#### get_class_name

Gets the type name of a variable as String:

- If this variable has a static type (not Variant), returns the static type name.
- If this variable is of Variant type, returns the type name.
- If this variable is an Object, returns the class name.

```
$<result_id:String> = get_class_name $<id>
```

#### object_cast

Runtime class cast for GDScript `as` when the target is an Object class.
Source may be Object, Variant, or Nil (`$value_id`). Failure cases (null, freed, non-object
Variant payload, class mismatch) yield canonical null; success returns the same instance as a
target-typed fat pointer without changing ownership category.
`class_name` is the canonical / Godot-facing runtime name (opaque text at parse time).
Result is optional: missing result is a validated no-op at the backend.

```
$<result_id:TargetObject> = object_cast "<class_name>" $<value_id>
```

#### builtin_cast

Runtime builtin conversion for GDScript `as` when the target is a non-Object, non-Variant,
non-Nil runtime builtin (including parameterized `Array[T]` / `Dictionary[K, V]`).
The same as Godot `Variant::construct` / `can_convert` semantics at the backend.
Parameterized containers keep full declared type text.
Result is required. Exact same-type and `as Variant` use `assign` / `pack_variant` instead.

```
$<result_id:target_type> = builtin_cast "<target_type_name>" $<value_id>
```

#### is_instance_of

Checks whether `$value_id` is an instance of the compile-time type `"<type_name>"` (GDScript `is`).
`$value_id` may be any ordinary typed value (including `Variant`), not only Object.
Class names must be canonical / Godot-facing; parameterized containers use full type text (e.g. `"Array[int]"`).
Result is always `bool`; `null` / nil object yields `false` for non-`Variant` targets.
`Variant` is the top type: frontend (and backend fold insurance) constant-folds `x is Variant` to
`true` / `x is not Variant` to `false`; stable LIR should not carry `is_instance_of "Variant"`.
Bare `Array` / `Dictionary` values tested against parameterized targets remain as
`is_instance_of "Array[T]"` / `"Dictionary[K, V]"` (runtime typed-metadata check); they must not
be folded away based only on the static bare slot type.

```
$<result_id:bool> = is_instance_of "<type_name>" $<value_id>
```

#### pack_variant

Packs a value into a Variant.

```
$<result_id> = pack_variant $<value_id>
```

#### unpack_variant

Unpacks a value from a Variant.

```
$<result_id> = unpack_variant $<variant_id>
```

#### variant_is_nil

Checks if a Variant is nil.

```
$<result_id:bool> = variant_is_nil $<variant_id:Variant>
```

#### object_is_null

Checks if an Object is null.

```
$<result_id:bool> = object_is_null $<object_id:Object>
```

### Control Flow Instructions

#### goto

Unconditional branch to a basic block.

```
goto <target_block_id>
```

#### go_if

Conditional branch to one of two basic blocks based on a boolean condition.

```
go_if $<condition_id:bool> <true_block_id> <false_block_id>
```

#### return

Returns from the current function, optionally with a return value.

```
return ($<return_value_id>)?
```

### Call Instructions

#### call_global

Calls a global function by name.

```
$<result_id>? = call_global "<function_name>" $<arg1_id> $<arg2_id> ...
```

#### call_method

Calls a method on an Object by method name.

When the callee is an `is_coroutine="true"` GDCC instance method, the result is mandatory
and is typed `compiler::GdccCoroState` (see [await](#await)); `call_method` /
`call_super_method` on an instance coroutine and `call_static_method` on a static coroutine
are the only producers of `compiler::GdccCoroState` values.

```
$<result_id>? = call_method "<method_name>" $<object_id> $<arg1_id> $<arg2_id> ...
```

#### call_super_method

Calls a super method on an Object by method name. `super` is lexical: resolution starts at the
declared super class of the class whose body contains the instruction, and `$<object_id>` must be
that class's own `self` — its static type must equal the containing class exactly. The nearest
ancestor implementation is selected statically at compile time; if the containing class has no
super class or the method cannot be statically resolved from the super chain, compilation fails
(no runtime fallback). `super._init` never lowers to this opcode — constructor chaining follows
the dedicated construction sequence. When the resolved callee is an `is_coroutine="true"` GDCC
instance method, the `call_method` coroutine ABI applies: the call targets the coroutine-start
thunk and the result is a mandatory `compiler::GdccCoroState` variable.

```
$<result_id>? = call_super_method "<method_name>" $<object_id> $<arg1_id> $<arg2_id> ...
```

#### call_static_method

Calls a static method on a class by class name and method name. `<class_name>` is the receiver
canonical name (GDCC class, inner class `Outer__sub__Inner`, engine class, or builtin type name);
the backend resolves it through `ClassRegistry.resolveTypeMeta` + the shared static method resolver
and always emits the exact GDCC/engine/builtin route (no receiver, no dynamic fallback). When the
callee is a marked GDCC coroutine, the call targets the coroutine-start thunk and the result must
be a `compiler::GdccCoroState` variable, following the same coroutine ABI as `call_method`.

The C backend implements this opcode through `CallStaticMethodInsnGen`
(see `frontend_await_implementation.md`).

```
$<result_id>? = call_static_method "<class_name>" "<method_name>" $<arg1_id> $<arg2_id> ...
```

#### call_intrinsic

Calls a backend-owned intrinsic function by name. The full surface, registry contract, and
intrinsic catalog are maintained in [GDCC LIR Intrinsic](gdcc_lir_intrinsic.md).

```
$<result_id>? = call_intrinsic "<intrinsic_name>" $<arg1_id> $<arg2_id> ...
```

### Coroutine Instructions

#### await

Suspends the enclosing coroutine function until the awaited operand produces a result, then
publishes that result as the instruction value. Suspension is a runtime stack switch on the
minicoro stackful coroutine frame; the instruction itself looks synchronous at the LIR level
and does not split basic blocks.

Rules:

- `await` is only valid inside a function whose `is_coroutine` attribute is `true` (see
  [Functions](#functions)). Backend validation fails fast with `InvalidInsnException` when an
  `await` appears in a non-coroutine function.
- The operand static type selects the dispatch path, frozen in
  [GDCC Runtime Library](gdcc_runtime_lib.md) §Coroutine Runtime:
  - `Signal` operand: one-shot signal wait (`gdcc_coro_await_signal`).
  - `compiler::GdccCoroState` operand: static coroutine-call path
    (`gdcc_coro_await_state` on the hidden state object). This compiler-only type is the
    result type of every `call_method` / `call_super_method` whose callee is an
    `is_coroutine="true"` GDCC instance method, and of every `call_static_method` whose
    callee is an `is_coroutine="true"` GDCC static method (see the ABI clause below) — such
    calls are the only producers of `compiler::GdccCoroState` values.
  - `Variant` operand: runtime dispatch (`gdcc_coro_await_dynamic`).
- An operand that is neither `Signal`-typed nor `Variant`-typed nor
  `compiler::GdccCoroState`-typed is invalid: backend validation fails fast with
  `InvalidInsnException`. Dispatch is purely static-type-driven; no name-derived temporary
  pairing or cross-instruction bookkeeping exists at the LIR/backend level.
- Internal coroutine-call ABI (frozen): the backend generates two entry points for an
  `is_coroutine="true"` GDCC method (instance or static) — an internal coroutine-start thunk that
  always returns the OWNED state object reference (`godot_Object*`; on synchronous
  completion the state object is already `done`), and the ClassDB call/ptrcall wrappers that
  invoke the start thunk and keep the no-observable-state fast path internally. A
  `call_method` / `call_super_method` / `call_static_method` on such a callee calls the
  start thunk and **must** declare a result
  variable (backend fails fast with `InvalidInsnException` otherwise); the result variable
  is declared with the compiler-only type `compiler::GdccCoroState` (C storage
  `godot_Object*`), so the state reference is an ordinary typed LIR value with normal
  ownership: an `await` consumes it through `gdcc_coro_await_state` (which releases the
  reference, see `gdcc_runtime_lib.md` §Coroutine Runtime), while a statement-position
  (fire-and-forget) call is expressed as destructing that result variable — releasing the
  last call-site reference detaches the coroutine, which stays alive through its own wait
  edges.
- `compiler::GdccCoroState` values are single-consumer owned references. The only legal
  consumers are `await` (consumes the reference and leaves the source slot in a moved-from
  `NULL` state) and `destruct` (releases the reference — the fire-and-forget detach — with
  `INTERNAL` provenance). They must not be copied or moved via `assign`, packed/unpacked
  to/from `Variant`, passed as call arguments, returned, stored into properties or
  containers, or marked `ref`; backend validation fails fast with `InvalidInsnException` on
  any such use.
- Typed result handoff: the value channel of the static coroutine-call path is typed, not
  Variant-based. The awaiter passes a pointer to its own typed result storage
  (`void *out_typed`, statically the callee's declared return type — the frontend-published
  await result variable type must match it; for a `void` callee the storage is a
  `godot_Variant` and the descriptor's `copy_ret_slot` specialization writes a constructed
  nil) to `gdcc_coro_await_state`; both the done fast path and the waiter resume copy the
  value out of the frame through the state class descriptor's generated `copy_ret_slot`
  callback, so the await dispatch channel never casts to a concrete state wrapper type
  outside the state class's own generated functions. The single exception is the ClassDB
  wrapper's synchronous-completion fast path, which extracts the result through the state
  class's own generated move accessor (`<state>__move_result`). The typed return slot outlives `done` (finalization copies —
  never moves — into the Variant `result_cache`, which only feeds the `completed` signal
  emission and dynamic awaits) and is destroyed exactly once at `free_instance`; the
  detailed storage state machine is frozen in
  [GDCC C Backend Lifecycle and Ownership Specification](gdcc_ownership_lifecycle_spec.md)
  §3.10.
- Result type is whatever the frontend published for the result variable: 0-arg signal →
  `Variant`; 1-arg signal → the declared argument type; multi-arg signal → `Array[Variant]`;
  coroutine call → callee declared return type, or `Variant` holding nil for a `void`
  callee (mirroring Godot's `completed(nil)` emission); dynamic → `Variant`.
- `await` is a value-producing instruction, not a terminator: `entryBlockId` and block
  terminator integrity rules are unaffected.
- Ownership clauses for the coroutine frame, the return-value storage state machine and the
  cancel-resume path are frozen in
  [GDCC C Backend Lifecycle and Ownership Specification](gdcc_ownership_lifecycle_spec.md)
  §3.10.

```
$<result_id> = await $<operand_id>
```

### Load/Store Instructions

#### load_property

Loads a property from an Object by property name.

```
$<result_id> = load_property "<property_name>" $<object_id>
```

#### store_property

Stores a value to a property in an Object by property name.

```
store_property "<property_name>" $<object_id> $<value_id>
```

#### load_static

Loads a static variable/property by name.

```
$<result_id> = load_static "<class_name>" "<static_name>"
```

`<class_name>` may be `@GlobalScope` for top-level Godot global constants and
Godot singleton properties such as `Engine` or `Input`. A singleton property read
materializes the engine-owned singleton object; method calls on that receiver still
use ordinary `call_method`.

#### store_static

Stores a value to a static variable/property by name.

```
store_static "<class_name>" "<static_name>" $<value_id>
```

### Misc Instructions

#### nop

No operation. Does nothing.

```
nop
```

#### line_number

Sets the current source code line number for debugging purposes.

```
line_number <line_number:int>
```

#### assert

User-level condition assertion. Fails the current function when the condition is false.

```
assert $<cond_id:bool>
assert $<cond_id:bool> $<msg_id:String>
```

No result. If the condition is true, execution falls through. If the condition is false,
the current function enters its stable runtime-error/default-return cleanup path (`goto __finally__`).

- `cond_id` must already be `bool`. Truthiness normalization is frontend lowering's
  responsibility; this instruction does not booleanize other types.
- `msg_id` is optional. When present it must be assignable to `String`.
- Does not retain/release/destroy; does not mutate object state; requires no lifecycle provenance.
- Distinct from `assert_object_live`, which is an object-liveness dereference guard.
- Not a terminator: it sits in the ordinary instruction region of a basic block.

#### assert_object_live

Hard-fail dereference guard. Asserts that an object reference is still live before use.

```
assert_object_live $<object_id:Object>
```

No result. If the object is live, execution falls through. If the object is null or freed,
the current function enters its stable runtime-error/default-return cleanup path (`goto __finally__`).

- Does not retain/release/destroy; does not mutate object state; requires no lifecycle provenance.
- Used only for method receivers, property receivers, `_super` chain access, and direct GDCC field/method owners.
- NOT used for user conditional checks, equality, own/release/destroy, or Variant pack/unpack.
- The `self` receiver is exempt: it is guaranteed live during method execution.
- RefCounted objects (`RefCountedStatus.YES`) are exempt: holding a reference guarantees liveness.

C lowering: calls `gdcc_object_is_null_raw_and_id(raw, instance_id)`.

### Instruction Usage Restrictions

The lifecycle instructions `destruct`, `try_own_object`, and `try_release_object` are controlled instructions.
They are not general-purpose instructions for arbitrary external LIR.

Allowed/forbidden quick reference:

| Provenance       | Allowed                                                            | Forbidden                                                        |
| ---------------- | ------------------------------------------------------------------ | ---------------------------------------------------------------- |
| `AUTO_GENERATED` | Compiler-generated `destruct` in `__finally__`                     | Any non-`__finally__` usage; any own/release instruction         |
| `INTERNAL`       | Compiler internal lifecycle maintenance on temp/internal variables | User-named variables (for example `obj`, `value`)                |
| `USER_EXPLICIT`  | Frontend-lowered explicit lifecycle intent from user source        | Emitting in auto-generated blocks (`__prepare__`, `__finally__`) |
| `UNKNOWN`        | Compat mode warning pass-through                                   | Strict mode                                                      |

Legal IR snippets:

```text
__finally__:
destruct $17 "AUTO_GENERATED";
```

```text
entry:
try_release_object $tmp_ref "USER_EXPLICIT";
```

Illegal IR snippets:

```text
entry:
destruct $value "AUTO_GENERATED"; // invalid: AUTO_GENERATED outside __finally__
```

```text
entry:
try_own_object $obj "INTERNAL"; // invalid: INTERNAL on ordinary user-named variable
```

## Syntax

A Low IR file (which is a .xml format file) consists of 4 parts:

- Class Definitions
- Signals
- Properties
- Functions

### Class Definitions

```xml
<!-- name is optional for anonymous classes -->
<!-- is_abstract and is_tool are optional, default to false -->
<!-- is_tool is script-level state: when a `@tool` script compiles to multiple classes
     (top-level plus inner classes), every compiled class_def carries is_tool="true",
     while only the top-level class_def carries the `tool` annotation below -->
<class_def name="<class_name>"
           super="super_class_name"
           is_abstract="false"
           is_tool="false">
    <!-- class-level annotations; currently only `tool` (value always empty) is produced -->
    <annotation key="<annotation_key>" value="<annotation_value>"/>
    <annotation key="<annotation_key>" value="<annotation_value>"/>
    <signals>...</signals>
    <properties>...</properties>
    <functions>...</functions>
</class_def>
```

### Signals

```xml
<signals>
    <signal name="<signal_name>">
        <!-- type is optional, defaults to Variant -->
        <parameter name="<arg_name>" type="<arg_type>"/>
        <parameter name="<arg_name>" type="<arg_type>"/>
    </signal>
</signals>
```

### Properties

```xml
<properties>
    <!-- init_func is optional -->
    <!-- The return value of the function will be used to initialize the property.
         If this is not a static property, the first parameter must be of type Object representing 'self'. -->
    <!-- getter_func & setter_func are optional  -->
    <!-- The getter func should receive a self parameter and return the same type as the prop.  -->
    <!-- The setter func should receive a self and a value parameter in the same type as the prop. -->
    <!-- If getter & setter are not present, a default one is generated -->
    <!-- Property annotation values for the `@export*` family encode the Godot `hint_string`
         payload verbatim (no quotes, no annotation-name prefix; e.g. `@export_range(0, 20, 0.5)`
         becomes <annotation key="export_range" value="0,20,0.5"/>). An empty value means an
         empty hint_string. The backend maps each key to its PROPERTY_HINT_* enum by a fixed
         priority order, never by annotation map iteration order. -->
    <property name="<prop_name>"
              type="<prop_type>"
              is_static="false"
              init_func="<init_function_name>"
              getter_func="<getter_function_name>"
              setter_func="<setter_function_name>">
        <annotation key="<annotation_key>" value="<annotation_value>"/>
        <annotation key="<annotation_key>" value="<annotation_value>"/>
    </property>
</properties>
```

### Functions

```xml
<functions>
    <!-- is_coroutine is optional, defaults to false -->
    <!-- is_coroutine="true" marks a stackful-coroutine function: the backend emits an entry
         thunk + minicoro body function + hidden state class for it (see §Coroutine
         Instructions and gdcc_runtime_lib.md §Coroutine Runtime). -->
    <function name="<function_name>"
              is_static="false"
              is_abstract="false"
              is_lambda="false"
              is_vararg="false"
              is_hidden="false"
              is_coroutine="false">
        <annotation key="<annotation_key>" value="<annotation_value>"/>
        <annotation key="<annotation_key>" value="<annotation_value>"/>
        <parameters>
            <!-- default_value_func is optional -->
            <!-- if present, the function will be called if no argument is provided and its return value will be used -->
            <!-- if no default_value_func and the argument is not provided, it is an error -->
            <parameter name="<param_name>" type="<param_type>" default_value_func="<default_value_function_name"/>
            <parameter name="<param_name>" type="<param_type>" default_value_func="<default_value_function_name"/>
        </parameters>
        <!-- Only lambda function contains this field  -->
        <captures>
            <capture name="<capture_name>" type="<capture_type>"/>
            <capture name="<capture_name>" type="<capture_type>"/>
        </captures>
        <return_type type="<return_type>"/>
        <variables>
            <variable id="<var_id>" type="<var_type>"/>
        </variables>
        <basic_blocks entry="<entry_block_id>">
            <basic_block id="<block_id>">
                instruction1;
                instruction2;
                ...
            </basic_block>
            <!-- more basic blocks -->
        </basic_blocks>
    </function>
</functions>
```

### Demo

GdScript:

```gdscript
class_name RotatingCamera extends Camera3D

@export var pitch_degree: float = 45;
@export var rotating_speed_degree: float = 60;
@export var length: float = 5;
var time: float = 0;

func _init() -> void:
    print("Camera init");

func _ready() -> void:
    print("Camera ready");

func _process(delta: float) -> void:
    time += delta;
    if length < 0:
        printerr("Length should not be less than 0");
        return;
    var vec = Vector3(length, 0, 0);
    vec = vec.rotated(Vector3.BACK, deg_to_rad(pitch_degree));
    vec = vec.rotated(Vector3.UP, deg_to_rad(rotating_speed_degree * time));
    self.position = vec;
    self.look_at_from_position(vec, Vector3.ZERO);

func get_pitch(to_radius := false) -> float:
    if to_radius:
        return deg_to_rad(self.pitch_degree);
    return self.pitch_degree;
```

Low IR:

> Note: lifecycle instructions in this demo are shown with explicit provenance to avoid ambiguity.
> They are illustrative controlled examples, not a signal that external hand-written IR can use lifecycle instructions freely.

```xml

<ir>
    <class_def name="RotatingCamera" super="Camera3D" is_abstract="false" is_tool="false">
        <properties>
            <property name="pitch_degree" type="float" is_static="false" init_func="_field_init_pitch_degree">
                <annotation key="export" value=""/>
            </property>
            <property name="rotating_speed_degree" type="float" is_static="false"
                      init_func="_field_init_rotating_speed_degree">
                <annotation key="export" value=""/>
            </property>
            <property name="length" type="float" is_static="false" init_func="_field_init_length">
                <annotation key="export" value=""/>
            </property>
            <property name="time" type="float" is_static="false" init_func="_field_init_time"/>
        </properties>
        <signals></signals>
        <functions>
            <function name="_init"
                      is_static="false"
                      is_abstract="false"
                      is_lambda="false"
                      is_vararg="false"
                      is_hidden="false"
                      is_coroutine="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="void"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="String"/>
                    <variable id="1" type="Variant"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 9;
                        $0 = literal_string "Camera init";
                        $1 = pack_variant $0;
                        call_global "print" $1;
                        destruct $1 "INTERNAL";
                        destruct $0 "INTERNAL";
                        return;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_ready"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="void"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="String"/>
                    <variable id="1" type="Variant"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 12;
                        $0 = literal_string "Camera ready";
                        $1 = pack_variant $0;
                        call_global "print" $1;
                        destruct $1 "INTERNAL";
                        destruct $0 "INTERNAL";
                        return;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_process"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                    <parameter name="delta" type="float"/>
                </parameters>
                <return_type type="void"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="delta" type="float"/>
                    <variable id="0" type="float"/>
                    <variable id="1" type="float"/>
                    <variable id="2" type="float"/>
                    <variable id="3" type="float"/>
                    <variable id="4" type="Vector3"/>
                    <variable id="5" type="Vector3"/>
                    <variable id="6" type="float"/>
                    <variable id="7" type="float"/>
                    <variable id="8" type="Vector3"/>
                    <variable id="9" type="Vector3"/>
                    <variable id="10" type="float"/>
                    <variable id="11" type="float"/>
                    <variable id="12" type="float"/>
                    <variable id="13" type="float"/>
                    <variable id="14" type="Vector3"/>
                    <variable id="15" type="Vector3"/>
                    <variable id="16" type="String"/>
                    <variable id="17" type="Variant"/>
                    <variable id="18" type="bool"/>
                </variables>
                <basic_blocks entry="bb1">
                    <basic_block id="bb1">
                        line_number 15;
                        $0 = load_property "time" $self;
                        $1 = binary_op "ADD" $0 $delta;
                        store_property "time" $self $1;
                        line_number 16;
                        $2 = literal_float 0;
                        $3 = load_property "length" $self;
                        $18 = binary_op "LESS" $3 $2;
                        go_if $18 bb2 bb3;
                    </basic_block>
                    <basic_block id="bb2">
                        line_number 17;
                        $16 = literal_string "Length should not be less than 0";
                        $17 = pack_variant $16;
                        call_global "printerr" $17;
                        destruct $17 "INTERNAL";
                        destruct $16 "INTERNAL";
                        return;
                    </basic_block>
                    <basic_block id="bb3">
                        line_number 19;
                        $4 = construct_builtin $3 $2 $2;
                        $5 = load_static "Vector3" "BACK";
                        $6 = load_property "pitch_degree" $self;
                        $7 = call_global "deg_to_rad" $6;
                        $8 = call_method "rotated" $4 $5 $7;
                        line_number 20;
                        $9 = load_static "Vector3" "UP";
                        $10 = load_property "rotating_speed_degree" $self;
                        $11 = load_property "time" $self;
                        $12 = binary_op "MULTIPLY" $10 $11;
                        $13 = call_global "deg_to_rad" $12;
                        $14 = call_method "rotated" $8 $9 $13;
                        line_number 22;
                        store_property "position" $self $14;
                        line_number 23;
                        $15 = load_static "Vector3" "ZERO";
                        call_method "look_at_from_position" $self $14 $15;
                        return;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="get_pitch"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="false">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                    <parameter name="to_radius" type="bool" default_value_func="_default_get_pitch$to_radius"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="to_radius" type="bool"/>
                    <variable id="0" type="float"/>
                    <variable id="1" type="float"/>
                </variables>
                <basic_blocks entry="bb1">
                    <basic_block id="bb1">
                        line_number 26;
                        go_if $to_radius bb2 bb3;
                    </basic_block>
                    <basic_block id="bb2">
                        line_number 27;
                        $0 = load_property "pitch_degree" $self;
                        $1 = call_global "deg_to_rad" $0;
                        return $1;
                    </basic_block>
                    <basic_block id="bb3">
                        line_number 29;
                        $0 = load_property "pitch_degree" $self;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_default_get_pitch$to_radius"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="bool"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="bool"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 26;
                        $0 = literal_bool false;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_field_init_pitch_degree"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="float"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 6;
                        $0 = literal_float 45;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_field_init_rotating_speed_degree"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="float"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 7;
                        $0 = literal_float 60;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
            <function name="_field_init_length"
                      is_static="false"
                      is_abstract="false"
                      is_vararg="false"
                      is_hidden="true">
                <parameters>
                    <parameter name="self" type="RotatingCamera"/>
                </parameters>
                <return_type type="float"/>
                <variables>
                    <variable id="self" type="RotatingCamera"/>
                    <variable id="0" type="float"/>
                </variables>
                <basic_blocks entry="entry">
                    <basic_block id="entry">
                        line_number 8;
                        $0 = literal_float 5;
                        return $0;
                    </basic_block>
                </basic_blocks>
            </function>
        </functions>
    </class_def>
</ir>
```
