# Frontend 字符串 `%` 格式化运算符实现说明

> 本文档作为 GDScript `"fmt" % args` 与 `%=` 在 frontend、LIR、C backend 与 Godot runtime evaluator 之间的长期事实源，记录当前冻结的 `MODULE` 重载合同、`RESOLVED(String)` 精度规则、诊断边界与回归锚点。本文档替代原实施计划稿，不保留分步骤实施、阶段状态、验收清单或已完成任务日志。

## 文档状态

- 状态：事实源维护中（shared semantic 精度规则、CFG/body lowering 复用 `BinaryOpInsn(MODULE)`、C backend evaluator / `variant_evaluate` 路径、compile-only gate 与 `string_format/` 端到端测试均已纳入当前实现）
- 更新时间：2026-09-07
- Godot 对齐基线：runtime / GDExtension ABI 固定为 `4.5.1`（`GodotVersion.V451`）；格式化求值对齐 `core/variant/variant_op.h` 的 `do_mod(const String&, ...)` → `String::sprintf`
- 适用范围：
  - `src/main/java/gd/script/gdcc/enums/GodotOperator.java`
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/lir/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/**`
  - `src/main/c/codegen/template_451/entry.h.ftl`
  - `src/main/c/codegen/include_451/gdcc/gdcc_helper.h`
  - 对应的 frontend、LIR、backend 与 test-suite 测试
- 关联文档：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_container_literal_implementation.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`
  - `doc/module_impl/backend/operator_insn_implementation.md`
  - `doc/module_impl/backend/typed_array_abi_contract.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
  - `doc/gdcc_runtime_lib.md`
  - `doc/test_suite.md`
- 明确非目标：
  - 不新增 `GodotOperator.FORMAT` 枚举、新 AST 节点或独立 LIR opcode
  - 不在编译器内重实现 `sprintf`；占位符解析、修饰符、参数数量/类型错误全部委托 Godot evaluator
  - 不修改外部 `gdparser`；`%` 已解析为 `BinaryExpression("%", left, right)`，`%=` 作为 compound assignment 原始文本传入 frontend
  - 不做编译期占位符静态校验
  - 不把 `String % T` 写入 `frontend_implicit_conversion_matrix.md`；该矩阵只覆盖 ordinary typed-boundary conversion
  - 当前精度规则只覆盖左操作数为 `String` 的格式化；`StringName` 左操作数维持 metadata 命中 + runtime-open `DYNAMIC(Variant)`
  - 不新增诊断 category、side table 或 compile-gate 清单条目
  - 不放宽 metadata 精确名匹配：具名 object 子类与 `null` 继续 fail-closed

---

## 1. 当前定位与数据流

GDScript 的 `%` 是二义运算符：数值取模与字符串格式化共享同一源码入口。当前实现与 Godot `Variant::OP_MODULE` 对齐——字符串格式化是 `MODULE` 在左操作数为 `String` 时的类型重载，而不是独立运算符。

当前支持的源码形态为：

```gdscript
"We're waiting for %s." % "Godot"
"%s=%d" % [name, hp]
label %= args
```

完整数据流固定为：

```text
BinaryExpression("%") / compound "%="
  -> GodotOperator.fromSourceLexeme(...) -> MODULE
  -> shared semantic: resolveBinaryOperatorResultType(...)
       special rules
       MODULE + String + runtime-open right -> RESOLVED(String)
       generic runtime-open -> DYNAMIC(Variant)
       exact metadata lookup
  -> type-check / compile gate 只消费已发布 fact
  -> CFG OpaqueExprValueItem / CompoundAssignmentBinaryOpItem
  -> BinaryOpInsn(MODULE, left, right)
  -> C backend OperatorResolver
       BUILTIN_EVALUATOR 或 VARIANT_EVALUATE
  -> Godot evaluator / String::sprintf
```

frontend 只负责操作数合同、结果类型与诊断；lowering / backend 不包含格式化专用分支；真正的格式串求值由 Godot runtime 完成。

---

## 2. 当前支持面与语义合同

### 2.1 `%` 与 `MODULE` 的统一入口

- 源码 `%` 与 metadata `"%"` 都规范化为 `GodotOperator.MODULE`。
- 不存在 `FORMAT` 枚举；字符串格式化与 `int % int` / `float % float` 共用同一 operator。
- 运算符优先级由外部 grammar 决定（`%` 与 `*` `/` 同级）；frontend 不维护优先级表。
- `%=` 复用普通 binary 结果类型与 assignment writeback，不另建独立实现。

### 2.2 静态右操作数

静态右操作数走 ordinary metadata exact lookup，命中后发布 `RESOLVED(String)`。

`extension_api_451.json` 中 `String` builtin 的 `%` 矩阵覆盖：

- `Variant` / `bool` / `int` / `float` / `String` / `StringName` / `NodePath` / `RID` / `Object` / `Callable` / `Signal` / `Dictionary` / `Array`
- 全部 Vector / Transform / Color / Plane / Quaternion / AABB / Basis / Projection
- 全部 `Packed*Array`

lookup 时：

- `Array[T]` 归一化为 `Array`
- `Dictionary[K, V]` 归一化为 `Dictionary`
- 其他类型按精确类型名匹配，不做 swap、对偶试探或 object 继承回退

完整矩阵正确性视为委托 Godot evaluator 继承；编译器测试只抽样代表类型，不逐条 e2e。

### 2.3 runtime-open 右操作数

当同时满足：

```text
operator == MODULE
leftType == String
right operand 为 exact Variant 或 DYNAMIC
```

frontend 在 generic runtime-open 分支之前发布 `RESOLVED(String)`。

依据：Godot `do_mod` 对 String 左操作数、以及全部 `String % T` metadata `return_type`，结果恒为 `String`。

该规则必须同时卡住“右侧 runtime-open”。若只写 `MODULE && left is String`，会先于 exact lookup 把 `String % Node` / `String % null` 也收成 `String`。

该规则内联在 `resolveBinaryOperatorResultType(...)` 主函数中，不得并入 `resolveBinarySpecialReturnType(...)`：后者保持 `(GodotOperator, GdType, GdType)` 纯类型函数，runtime-open 判定需要 `FrontendExpressionTypeStatus`。

### 2.4 明确拒绝的组合

| 源码形态 | 当前结果 | 原因 |
|---|---|---|
| `String % Object` | `RESOLVED(String)` | 精确名 `"Object"` 命中 metadata |
| `String % Node` 等具名 object 子类 | `FAILED` | 类型名无 metadata 条目，不做继承回退 |
| `String %` GDCC script class | `FAILED` | 同上 |
| `String % null` | `FAILED` | `null` 发布 `Nil`，矩阵无 `Nil` 条目 |
| `int % String` | `FAILED` | 左侧不是 `String`，走数值取模合同 |
| `Variant % int` | `DYNAMIC(Variant)` | 左操作数 runtime-open，不触发格式化精度规则 |
| `StringName % Variant` | `DYNAMIC(Variant)` | 当前精度规则不覆盖 `StringName` 左操作数 |
| mixed `int` / `float` `%` | `FAILED` | 不做隐式 numeric promotion |

### 2.5 `%=` 复用合同

`s %= args` 必须与 `"fmt" % args` 共用：

- `FrontendAssignmentSemanticSupport` 把 `%=` 映射为 `%`，再调用 `resolveBinaryOperatorResultType(...)`
- CFG 生成 `CompoundAssignmentBinaryOpItem`
- body lowering 发射 `BinaryOpInsn(MODULE)` 后走普通 store

不需要独立 sema / lowering / codegen 路径。

---

## 3. Shared semantic 与诊断

### 3.1 求值顺序

`resolveBinaryOperatorResultType(...)` 对 `%` 的冻结顺序为：

1. `not in` 复合规则
2. source-level special rules（`and/or`、object/nil equality、object identity equality、typed array preserve）
3. `MODULE` + 左 `String` + 右 runtime-open → `RESOLVED(String)`
4. 任一操作数 runtime-open → `DYNAMIC(Variant)`
5. mixed `int` / `float` 显式拒绝
6. ordinary metadata exact lookup；未命中则 `FAILED`

通用 unary / binary 顺序以 `frontend_unary_binary_expr_semantic_implementation.md` §4.1 为准；本节只冻结格式化特有的第 3 步。

### 3.2 诊断 owner 与恢复

- 不支持的操作数组合继续使用 `sema.expression_resolution`。
- root-owned 诊断由 `FrontendBodyOwnerProcedures` 发布。
- 不新增 `sema.unsupported_expression_route` / `sema.deferred_expression_resolution` 路径。
- 坏 subtree 被跳过；同一 module 的其他合法 subtree 继续分析。
- 典型文案：

```text
Binary operator '%' is not defined for operand types 'String' and 'Node'
Binary operator '%' is not defined for operand types 'String' and 'Nil'
Binary operator '%' is not defined for operand types 'int' and 'String'
```

`null` 在诊断中的类型名固定为 `Nil`。

### 3.3 type-check 与 compile gate

- type-check 只消费已发布的 stable fact，不拥有独立的格式化兼容矩阵。
- `String %` 结果进入 condition / initializer / return 时按 ordinary typed boundary 处理。
- condition 只要求 stable fact，因此 `RESOLVED(String)` 可作为 condition 根。
- 把 `String` 结果赋给 `int` slot 产生 `sema.type_check`。
- compile gate 对本 feature 无特判：
  - 支持面发布 `RESOLVED(String)`，不命中 blocker
  - `DYNAMIC` 不是 compile blocker
  - fail-closed 组合由上游 `sema.expression_resolution` 阻断，不补 `sema.compile_check`

---

## 4. CFG、LIR 与 body lowering

普通 `%` 没有专用 CFG item：

```text
LiteralStringInsn
ConstructContainerLiteralInsn   # 仅当右操作数是数组字面量
BinaryOpInsn(MODULE, left, right)
```

`%=` 的 lowering 形态为：

```text
read current target
BinaryOpInsn(MODULE, current, args)
AssignInsn(target, result)
```

约束：

- `"..." % [a, b]` 必须先走 `construct_container_literal`，再把 Array 作为 `MODULE` 右操作数。
- 数组字面量本身仍遵守 `frontend_container_literal_implementation.md`；格式化运算符不得绕过 typed-array / nested-container / PackedArray 限制。
- 生产 lowering 不包含格式化专用分支；`%` 自动复用 ordinary binary / compound assignment 路径。

---

## 5. Backend 与 Godot runtime evaluator

### 5.1 路径选择

`OperatorResolver.resolveBinaryPath(...)` 与 helper 收集共用同一判定，禁止分叉：

| 操作数 | 路径 | 生成形态 |
|---|---|---|
| `String % Array` / `Array[T]` / 其他静态 metadata 命中 | `BUILTIN_EVALUATOR` | `gdcc_eval_binary_module_<left>_<right>_to_string(...)` |
| 任一侧为 `Variant` | `VARIANT_EVALUATE` | `godot_variant_evaluate(GDEXTENSION_VARIANT_OP_MODULE, ...)`，结果 unpack 到 `String` |
| `int % int` / `float % float` | primitive fast path | 既有数值取模与除零保护，不受格式化合同影响 |

typed array helper 名按 `GdType.getTypeName()` sanitize（例如 `gdcc_eval_binary_module_string_array_int_to_string`），但 metadata 仍命中 plain `Array`。

### 5.2 typed Array ABI

`godot_TypedArray(T)` 是 `godot_Array` 的别名。因此 `String % Array[T]` 把 typed array 指针传给期望 raw `Array` 的 evaluator 是 ABI 安全的。完整 ABI 以 `typed_array_abi_contract.md` 为准。

### 5.3 Variant 生命周期

`String % Variant` 必须：

- 走 `godot_variant_evaluate`
- 对非 `Variant` 结果做运行时类型检查后 unpack 到 `String`
- 遵守 `operator_insn_implementation.md` 的 Variant 拷贝 / unpack 合同

不得把格式化 Variant 路径改成未验收的 ptr-evaluator 路线。

### 5.4 运行时委托边界

Godot `String::sprintf` 负责：

- 占位符：`%s`（`str()` 语义）、`%c`、`%d`、`%o`、`%x` / `%X`、`%f`、`%v`
- 修饰符：`+`、零填充、精度、`-` 左对齐、`*` 动态 padding / precision
- `%%` 转义为字面 `%`
- 多占位符按 Array 顺序传参；单值右操作数在 runtime 内部包装为单元素 Array
- 参数数量或类型不匹配：返回错误描述串并继续执行，不是编译期错误

Godot 4.5 参数不足时的代表结果串：

```text
not enough arguments for format string
```

官方文档表格对 `-` 的 “Pad to the right” 表述有歧义；以同页示例 `"%-10d" % 12345678 -> "12345678  "` 与 `String::sprintf` 行为为准。

---

## 6. Compile-only 边界

- 支持的 `String % Variant` / `String % DYNAMIC` 不产生 `sema.compile_check`。
- `String % null` 只保留上游 `sema.expression_resolution`。
- compile-fail 场景（`Node`、script class、`null`、`int % String`）锚定在 frontend focused tests，不进入 `test_suite`。

---

## 7. 核心实现落点

- `src/main/java/gd/script/gdcc/enums/GodotOperator.java`
  - `"%"` → `MODULE`（source 与 metadata 两侧）
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
  - `resolveBinaryOperatorResultType(...)` 的 String + runtime-open 精度规则
  - `resolveBinaryExactReturnType(...)` / `operatorOperandTypeName(...)` 的容器归一化与精确名匹配
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendAssignmentSemanticSupport.java`
  - `%=` 复用 binary 结果类型
- `src/main/java/gd/script/gdcc/frontend/sema/analyzer/FrontendBodyOwnerProcedures.java`
  - root-owned `sema.expression_resolution`
- `src/main/java/gd/script/gdcc/frontend/lowering/cfg/FrontendCfgGraphBuilder.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendOpaqueExprInsnLoweringProcessors.java`
- `src/main/java/gd/script/gdcc/frontend/lowering/pass/body/FrontendSequenceItemInsnLoweringProcessors.java`
- `src/main/java/gd/script/gdcc/lir/insn/BinaryOpInsn.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/OperatorResolver.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
- `src/main/java/gd/script/gdcc/backend/c/gen/CGenHelper.java`
- `src/main/c/codegen/template_451/entry.h.ftl`

---

## 8. 回归锚点

- `GodotOperatorTest` — `"%" -> MODULE`；不存在 `FORMAT`
- `FrontendExpressionSemanticSupportTest`
  - metadata 抽样矩阵（int / float / bool / String / Array / `Array[int]` / Dictionary / `Dictionary[String, int]` / PackedStringArray / Object）→ `RESOLVED(String)`
  - runtime-open 右操作数 → `RESOLVED(String)`
  - `Node` / `Nil` / `int % String` fail-closed 且文案不变
  - 左 runtime-open 与 `StringName` 左操作数保持 `DYNAMIC(Variant)`
- `FrontendBodyOwnerProceduresExprTypeTest`
  - script class 右操作数恰好一条 `sema.expression_resolution`
  - 坏 subtree `FAILED`，同 module 前后合法表达式仍 `RESOLVED(String)`
- `FrontendAssignmentSemanticSupportTest`
  - `String %= Array` 与 `int %= int` 均 RESOLVED 且 writeback 边界通过
- `FrontendTypeCheckAnalyzerTest`
  - initializer / condition / return 消费 `String` fact
  - `String -> int` slot 恰好一条 `sema.type_check`
- `FrontendCompileCheckAnalyzerTest`
  - `String % Variant` 零诊断
  - `String % null` 仅上游 `sema.expression_resolution`
- `FrontendLoweringBodyInsnPassTest`
  - 字面量左 / 数组字面量右：`construct_container_literal` 后 `BinaryOpInsn(MODULE)`，slot 类型为 String / Array / String
  - `%=`：`BinaryOpInsn(MODULE)` + 本地 store
- `COperatorInsnGenTest`
  - `MODULE(String, Array)` / `MODULE(String, Array[int])` → `BUILTIN_EVALUATOR`
  - `MODULE(String, Variant)` → `VARIANT_EVALUATE` + String unpack type-check
- `CCodegenTest`
  - untyped / typed array 两个 helper spec 均被收集并渲染进 `entry.h`
- `GdScriptUnitTestCompileRunnerTest`
  - `STRING_FORMAT_SCRIPT_PATHS` 与 `compilesAndValidatesStringFormatScripts`
- `src/test/test_suite/unit_test/{script,validation}/string_format/`
  - `single_value`
  - `array_multi_placeholder`
  - `numeric_family`
  - `percent_escape`
  - `typed_array_operand`
  - `variant_operand`
  - `compound_assignment`
  - `runtime_error_arg_count`

`runtime_error_arg_count` 必须先观察到错误描述串，再打印 pass marker，以证明 Godot sprintf 返回错误文本后继续执行。validation 侧使用 `if` / `push_error` 做语义相等断言，不匹配生成 C 文本。

后续若扩张支持面，测试必须继续同时覆盖 happy path、root-owned negative path 与同 module 恢复路径。

---

## 9. 当前局限与长期维护约束

当前明确保持 fail-closed / 未扩张的边界：

- 具名 object 子类作为右操作数。若未来放行，frontend metadata 匹配与 backend helper 签名必须同步增加 object 归一化，并确认 GDCC class 的 `getGdExtensionType()` 映射为 `OBJECT`。
- `String % null`。若未来放行，需单独评估 Godot 的 Nil 单值包装语义，不得借 runtime-open 规则顺手放行。
- `StringName` 左操作数的 runtime-open 精度。metadata 已有完整 `StringName % T -> String` 矩阵，但当前精度规则不覆盖；静态右操作数仍可走 metadata 命中。
- 编译期占位符静态检查。仅当格式串为字面量且右操作数形态静态可知时才有意义，且只适合 warning，不属于当前编译合同。
- `%` 与 `String.format()` 的用户文档互链不属于编译器合同。

长期不变量：

- 不得新增 `FORMAT` operator、专用 AST / LIR / codegen 路径。
- 不得把格式化规则并入 implicit conversion matrix。
- 不得把 runtime-open 精度规则放进 `resolveBinarySpecialReturnType(...)`。
- 不得用该精度规则放宽 `Node` / script class / `null`。
- 不得在编译器中重实现 sprintf，也不得把占位符数量错误升级为 compile-fail。
- 数值取模、mixed `int` / `float` 拒绝、primitive fast path 与除零保护必须继续独立成立。
- 若合同变化，必须同步本文档、`frontend_unary_binary_expr_semantic_implementation.md` §4.7、`frontend_rules.md`、backend operator 文档与对应 targeted / test-suite 测试。

---

## 10. 工程反思

字符串格式化能稳定落地，是因为它没有被建成“新运算符”，而是被建成 `MODULE` 的 `String` 重载：

1. **单一入口优于表面精确。** Godot 把格式化建模为 `OP_MODULE`。新增 `FORMAT` 会让 AST、metadata、lowering、backend helper 命名与 Variant evaluate enum 同时分叉，却换不来额外语义。
2. **精度规则必须比 generic runtime-open 更窄，而不是更宽。** `String % Variant` 的结果类型在 runtime 上没有歧义，值得发布 `RESOLVED(String)`；但同一条规则一旦去掉“右侧 runtime-open”限制，就会把精确名匹配尚未覆盖的 `Node` / `Nil` 一起放行。
3. **helper 签名保持纯类型函数。** runtime-open 判定依赖 expression status，不该污染 `resolveBinarySpecialReturnType(...)`。把格式化规则内联在主函数中，既能插在正确位置，也不把 special-rule helper 变成杂项入口。
4. **编译器合同停在类型与 ABI，不停在格式串内容。** 占位符、padding、参数不足都是 Godot evaluator 的行为。编译器只保证把正确类型的值交给 `MODULE`，并在 frontend 拒绝 metadata 无法证明的静态组合。
