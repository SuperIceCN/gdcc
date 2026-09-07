# Frontend 字符串 `%` 格式化运算符实施计划

> 本文档记录 GDScript 字符串格式化运算符 `"fmt" % args` 的调研结论、设计决策、分步实施方案与验收细则。它既是本次任务的执行清单，也是后续维护该 feature 时需要持续对齐的事实源。

## 文档状态

- 状态：已实施完成（代码、测试、文档同步与全量回归均已完成）。§1/§2.2/§2.6 保留实施前调研快照；最终口径以 §2.7、`frontend_rules.md` 与 `frontend_unary_binary_expr_semantic_implementation.md` §4.7 为准
- 最后更新：2026-09-07
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java`
  - `src/main/java/gd/script/gdcc/frontend/lowering/**`
  - `src/main/java/gd/script/gdcc/backend/c/gen/insn/Operator*.java`
  - `src/test/java/gd/script/gdcc/**` 相关测试
  - `src/test/test_suite/unit_test/**` e2e 用例
  - `doc/module_impl/frontend/**`
- 关联文档（本期必须同步修改）：
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_unary_binary_expr_semantic_implementation.md`
- 关联文档（只读参考，本期明确不改）：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/diagnostic_manager.md`（不新增 category，`sema.expression_resolution` 已有条目）
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`（compile gate 零改动，见 §3 阶段 C）
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`（`%=` 复用 `resolveBinaryOperatorResultType`，D2 内联规则自动生效，publication 合同不变）
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`（不扩展）
  - `doc/gdcc_type_system.md`
  - `doc/module_impl/backend/operator_insn_implementation.md`
  - `doc/test_suite.md`
- 明确非目标：
  - 不新增 `GodotOperator.FORMAT` 枚举或新 AST 节点；字符串格式化与数值取模共享 `%` / `MODULE` 入口，与 Godot Variant `OP_MODULE` 的类型重载模型对齐。`GodotOperatorTest` 已覆盖 `"%" -> MODULE`（metadata 与 source 两侧），本期保持零改动
  - 不在编译器内自行实现 `sprintf` 占位符求值；格式化语义整体委托 Godot runtime（builtin operator evaluator / `godot_variant_evaluate`）
  - 不修改 parser（外部 `gdparser` 依赖已能解析 `%` 与 `%=`）
  - 不做编译期占位符静态校验（格式串可动态生成，见 Post-MVP Backlog）
  - 不扩展隐式转换矩阵；`%` 的操作数/返回契约属于 operator 自身契约，由 operator metadata 与本文档的专用规则决定
  - 本期只覆盖左操作数为 `String` 的格式化；`StringName` 左操作数维持现状（静态右操作数走 metadata 命中，runtime-open 右操作数仍 `DYNAMIC(Variant)`），列入 Post-MVP Backlog
  - 不新增诊断 category、不新增 side table、不改 compile gate 清单

---

## 1. 问题背景

GDScript 中 `%` 是二义运算符：

- 数值取模：`5 % 2 -> 1`、`5.5 % 2.0 -> 1.5`
- 字符串格式化：`"We're waiting for %s." % "Godot"`、`"%s was reluctant to learn %s" % ["Estragon", "GDScript"]`

实施前 `frontend_rules.md` 曾写着"字符串格式化 `%` 语法在 MVP 版本中不支持"（第 130 行，阶段 F 已改写为 compile-ready 支持面），但调研发现 metadata-driven 的现有链路实际上已部分打通了该 feature：extension metadata 声明了 `String % <所有 builtin 类型> -> String`，frontend sema 与 backend codegen 对大多数静态右操作数已经能闭环。实施前的真正缺口是：

1. 没有任何专门的单元测试 / e2e 测试锚定字符串 `%` 行为，正确性未被验证；
2. `String % <runtime-open 操作数>`（`Variant` / dynamic）当时发布 `DYNAMIC(Variant)`，丢失了"结果必为 `String`"的静态精度（已由 D2 关闭）；
3. `frontend_rules.md` 的 MVP 声明与代码现状矛盾，需要按实际支持面改写；
4. 少数右操作数形态（具名 object 子类、`null` 字面量）尚无明确的支持/拒绝合同。

## 2. 调研结论

### 2.1 语法与运算符规范化现状

- 词法/语法层由外部依赖 `gdparser`（tree-sitter-gdscript）提供，`%` 已作为二元运算符解析为 `BinaryExpression("%", left, right)`，`%=` 作为 augmented assignment 原始文本传入前端。无需改动 parser。
- `GodotOperator`（`src/main/java/gd/script/gdcc/enums/GodotOperator.java:88-110`）把源码 `%` 规范化为 `MODULE`；metadata 侧同样 `"%" -> MODULE`（`:54-65`）。不存在独立的 FORMAT 运算符，这与 Godot 一致：Godot 把字符串格式化建模为 `Variant::OP_MODULE` 在 `String` 左操作数上的重载。
- 运算符优先级由外部 grammar 决定（`%` 与 `*` `/` 同级，见 Godot `core/math/expression.cpp`），frontend 不维护优先级表。

### 2.2 前端语义（实施前快照）

入口：`FrontendExpressionSemanticSupport.resolveBinaryOperatorResultType(...)`
（`src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/FrontendExpressionSemanticSupport.java:682-743`），固定顺序为：

1. `not in` 复合规则拦截；
2. source-level special rules（`resolveBinarySpecialReturnType`，`:2090-2114`）：`and/or -> bool`、object/nil 与 object/object `==`/`!=`、`Array[T] + Array[T]`；
3. runtime-open 判断（`isRuntimeOpenOperatorOperand`，`:2130-2136`）：任一侧为 exact `Variant` 或 `DYNAMIC` → 发布 `DYNAMIC(Variant)`；
4. mixed `int/float` 显式拒绝（不做隐式 numeric promotion）；
5. ordinary metadata 精确匹配（`resolveBinaryExactReturnType`，`:2143-2167`）：左操作数取 builtin owner class，右操作数按名字精确匹配，且 `Array[T] -> "Array"`、`Dictionary[K,V] -> "Dictionary"` 归一化（`operatorOperandTypeName`，`:2178-2186`）。右操作数匹配是**精确名匹配**，不存在 object 继承层级回退。

extension metadata（`src/main/resources/extension_api_451.json`，String builtin class 的 `operators`）声明了完整的 `String % T -> String` 矩阵，包括 `Variant/bool/int/float/String/StringName/NodePath/RID/Object/Callable/Signal/Dictionary/Array`、全部 Vector/Transform/Color/Plane/Quaternion/AABB/Basis/Projection 与全部 Packed*Array；**不包含 `Nil`，也不包含任何具名 object 子类或 script class**。

由此得出实施前的实际行为（已逐行核实；`String % Variant/dynamic` 行已被 D2 改变，见 §2.7 与 unary/binary §4.7）：

| 源码形态 | 实施前结果 |
|---|---|
| `String % int/float/bool/String/Array/Dictionary/Packed*Array/...`（metadata 精确命中） | `RESOLVED(String)` |
| `String % Array[T]`（typed array，归一化为 `Array`） | `RESOLVED(String)` |
| `String % Object`（精确类型名 `"Object"` 命中 metadata） | `RESOLVED(String)` |
| `String % Variant` / `String % <dynamic>` | 实施前 `DYNAMIC(Variant)`（runtime-open 分支先于 metadata）；D2 实施后 `RESOLVED(String)` |
| `String % <具名 object 子类>`（如 `Node`、GDCC script class，类型名 `"Node"`/`"Foo"` 无 metadata 条目） | `FAILED` |
| `String % null`（`null` 字面量发布 `GdNilType`，类型名为 `Nil`，无 metadata 条目） | `FAILED` |
| 上述 `FAILED` 的诊断形态 | `Binary operator '%' is not defined for operand types 'String' and 'X'`（`X` 为发布的类型名，如 `Nil`），category `sema.expression_resolution`，由 `FrontendBodyOwnerProcedures` 以 root-owned 发布 |
| `int % int`、`float % float` | 既有数值取模路径，不受影响 |

### 2.3 lowering 现状

- 普通二元表达式：`FrontendCfgGraphBuilder.java:2025-2101` 建 `OpaqueExprValueItem`，`FrontendOpaqueExprInsnLoweringProcessors.java:303-348` 发射 `BinaryOpInsn(result, MODULE, left, right)`。无运算符特化，`%` 自动复用。
- `%=` compound assignment：sema 与普通二元共用 `resolveBinaryOperatorResultType(...)`（`FrontendAssignmentSemanticSupport.java:322-327`）；CFG 把 `%=` 映射为 `%`（`FrontendCfgGraphBuilder.java:4386-4413`），item lowering 发射 `BinaryOpInsn(MODULE)` 后走普通 store（`FrontendSequenceItemInsnLoweringProcessors.java:1514-1559`）。
- 结论：lowering 层预期**零改动**，只需回归测试锚定。

### 2.4 后端与运行时现状

- `OperatorResolver.resolveBinaryPath(...)`（`src/main/java/gd/script/gdcc/backend/c/gen/insn/OperatorResolver.java:81-189`）：
  - 任一侧为 `Variant` → `VARIANT_EVALUATE`，生成 `godot_variant_evaluate(GDEXTENSION_VARIANT_OP_MODULE, ...)`（`OperatorInsnGen.java:273-340`），结果 slot 非 `Variant` 时经 `emitVariantUnpackTypeCheck` + `unpackVariantAssign` unpack 到目标类型（`OperatorInsnGen.java:305-331`），含运行时类型检查与失败 hard-fail；
  - 否则 metadata 命中 → `BUILTIN_EVALUATOR`，生成 `gdcc_eval_binary_module_<left>_<right>_to_string(...)` helper（命名取 `GdType.getTypeName()` sanitize 后的结果，见 `OperatorResolver.java:273-280`），内部调用 Godot `GDExtensionPtrOperatorEvaluator`（模板 `src/main/c/codegen/template_451/entry.h.ftl:440-472`）；backend 的 metadata 匹配同样做 `Array[T] -> Array` / `Dictionary[K,V] -> Dictionary` 归一化（`OperatorResolver.java:407-415`）。
  - helper 收集（`CGenHelper.collectBinaryEvaluatorHelperSpec`，`CGenHelper.java:245-272`）与指令发射（`OperatorInsnGen.emitBinary`）调用同一个 `resolveBinaryPath`，path 判定不可能分叉。
- typed array ABI 安全性已核实：`godot_TypedArray(T)` 在 `src/main/c/codegen/include_451/gdcc/gdcc_helper.h:67` 就是 `#define godot_TypedArray(value) godot_Array`，因此 `String % Array[T]` 直接把 typed array 指针传给期望 raw `Array` 的 evaluator 是 ABI 兼容的。
- object 操作数的 fat-pointer → raw slot 物化也已由 helper 模板支持（`CGenHelper.java:138-156`）。
- 结论：backend 预期**零改动**；格式化正确性由 Godot runtime 保证。

### 2.5 Godot 语义参考（委托边界）

Godot 上游实现：`core/variant/variant_op.h` 中 `do_mod(const String&, ...)` → `String::sprintf`（`core/string/ustring.cpp`）；单值右操作数在 runtime 内部包装为单元素 `Array`。官方语义（`godotengine/godot-docs` `tutorials/scripting/gdscript/gdscript_format_string.rst`）：

- 占位符类型：`%s`（`str()` 语义）、`%c`（Unicode 码点/单字符）、`%d`、`%o`、`%x`/`%X`、`%f`、`%v`（Vector 族）；
- 修饰符：`+`、整数 padding（前导 `0` 补零）、`.` 精度、`-` 左对齐（右侧补 padding；官方文档表格的 "Pad to the right" 表述有歧义，以同页示例 `"%-10d" % 12345678 -> "12345678  "` 与 `String::sprintf` 行为为准）、`*` 动态 padding/precision（额外消费一个 int 参数）；
- 多占位符用 Array 按序传值；`%%` 转义字面 `%`；
- 格式化失败（占位符与参数数量不匹配、类型不符等）是**运行时**行为：返回错误描述串并产生运行时错误，不是编译期错误。

因为 GDCC 把求值整体委托给 Godot evaluator / `godot_variant_evaluate`，上述语义自动逐字继承，编译器不需要也不应该重新实现。

### 2.6 差距清单

| # | 差距 | 性质 |
|---|---|---|
| G1 | 无字符串 `%` 的 frontend sema / lowering / backend / e2e 测试 | 测试缺口（已由阶段 C/D/E 关闭） |
| G2 | `String % <runtime-open>` 发布 `DYNAMIC(Variant)`，丢失静态 `String` 精度，导致下游 assignment 多走一次 Variant unpack，且 `analyze(...)` 观察到的类型过宽 | 精度缺口（已由 D2 关闭） |
| G3 | `frontend_rules.md:130` 与代码现状矛盾 | 文档缺口（已由阶段 F 关闭） |
| G4 | `String % <具名 object 子类>`（如 `Node`、GDCC script class）与 `String % null` 无明确合同（当前恰好 fail-closed，但从未被声明或测试锚定） | 合同缺口（已由 D3 关闭：fail-closed 固化为明确合同并补测试锚定） |

### 2.7 设计决策

- **D1：保持 `MODULE` 单一入口。** 不新增 FORMAT 枚举；字符串格式化 = `MODULE` 在 `String` 左操作数上的 metadata 重载，与 Godot 建模一致。
- **D2：修复 G2 的精度规则，内联在 runtime-open 分支正前方。** 在 `resolveBinaryOperatorResultType(...)` 的 runtime-open 判断（`FrontendExpressionSemanticSupport.java:711-717`）之前插入：

  ```java
  if (operator == GodotOperator.MODULE
          && publishedLeftType instanceof GdStringType
          && isRuntimeOpenOperatorOperand(rightOperandType, publishedRightType)) {
      return FrontendExpressionType.resolved(GdStringType.STRING);
  }
  ```

  要点与依据：
  - 不扩展 `resolveBinarySpecialReturnType(...)` 签名：该 helper 保持 `(GodotOperator, GdType, GdType)` 纯类型函数；runtime-open 判定需要 `FrontendExpressionTypeStatus`，内联在主函数中最小 diff。
  - 条件必须同时卡住"右侧 runtime-open"：若只写 `MODULE && left instanceof GdStringType`，会先于 exact 匹配把 `String % <具名 object 子类>` / `String % null` 也收成 `String`，意外放行 G4 边界。
  - 规则放在 runtime-open 分支正前方而非更靠后：exact 静态右操作数继续走 metadata 精确匹配，行为逐字不变。
  - 语义依据与 `not in` 固定 `bool` 相同：runtime 结果类型恒为 `String`（Godot `do_mod` 返回类型与全部 metadata `return_type` 都是 `String`）。
  - 副作用已核对：`%=`（`FrontendAssignmentSemanticSupport` 共用此函数）自动获益；`not in` 在 `:695` 先行拦截不会进入本分支；`Variant % int`（左 runtime-open、左非 String）仍 `DYNAMIC(Variant)`。
  - backend 侧无需联动：左 `String` + 右 `Variant` 的 `BinaryOpInsn` 仍走 `VARIANT_EVALUATE`，结果 slot 为 `String`，既有 unpack+type-check 路径直接复用。
- **D3：G4 按 fail-closed 固化为明确合同，分三档记录。**
  - `String % Object`（精确类型名 `"Object"`）：现状 `RESOLVED(String)`，本期保持并补测试锚定；
  - `String % <具名 object 子类>`（`Node`、GDCC script class 等）：保持 `FAILED` + `sema.expression_resolution`，列入 Post-MVP Backlog 评估 object 归一化；不在本期放宽精确名匹配（避免前后端签名口径漂移）；
  - `String % null`：保持 `FAILED`（诊断中的类型名为 `Nil`），列入 Post-MVP Backlog 评估 Nil 单值包装语义。
- **D4：不新增诊断 category。** 不支持的操作数组合继续复用 root-owned `sema.expression_resolution`，诊断 owner 仍是 `FrontendBodyOwnerProcedures`；本 feature 不产生 deferred/unsupported 新路径，`diagnostic_manager.md` 无需更新。
- **D5：`%=` 视为同一 feature 的组成部分。** `s %= args` 复用 binary 语义，只需测试锚定，不改实现。

## 3. 分阶段实施方案

### 阶段 A：事实基线与计划

目标：

- 固化本调研结论、设计决策 D1-D5 与验收细则。

产出：

- 本文档。

验收细则：

- 文档覆盖 §2.6 全部差距项的处理方案，且每条方案标注实现位置或"无改动"结论。

### 阶段 B：sema 精度规则实现（G2）

目标：

- 按 D2 在 `FrontendExpressionSemanticSupport.resolveBinaryOperatorResultType(...)` 的 runtime-open 分支正前方内联 `MODULE` + 左 `String` + 右 runtime-open → `String` 规则。

实施内容：

- 只改 `FrontendExpressionSemanticSupport.java` 一处；在代码注释中写明"结果恒为 String"的依据（Godot `do_mod` 与全部 metadata `return_type`）。
- 文档同步统一放到阶段 F，本阶段不动 `frontend_unary_binary_expr_semantic_implementation.md`。

验收细则：

- happy path：`String % Variant`、`String % <untyped dynamic>` 发布 `RESOLVED(String)`。
- negative path：`String % <GDCC script class>`、`String % null`、`int % String` 仍 `FAILED` 且诊断文案/category 不变。
- invariant：exact 静态右操作数行为逐字不变（`String % int` 等仍走 metadata，不因新规则改变结果）；`Variant % int`（左 runtime-open）仍为 `DYNAMIC(Variant)`；`StringName % Variant` 仍 `DYNAMIC(Variant)`（本期不覆盖）。
- 定向测试：`FrontendExpressionSemanticSupportTest` 新增/更新用例并通过。

### 阶段 C：frontend 全链路单测（G1 frontend 部分）

目标：

- 为字符串 `%` 建立 frontend sema → type-check → compile-check → lowering 的测试锚点。

实施内容（测试文件按现状落点，允许按实际类名微调）：

- `FrontendExpressionSemanticSupportTest`：`String % int/float/bool/String/Array/Array[int]/Dictionary/Dictionary[String, int]/PackedStringArray/Object -> RESOLVED(String)`；`String % Variant/dynamic -> RESOLVED(String)`（阶段 B 后）；`String % Node` / `String % null -> FAILED`（`null` 诊断文案中的类型名为 `Nil`）。
- `FrontendBodyOwnerProceduresExprTypeTest`（或等价 owner 测试）：`String % <具名 object 子类>` 这类 root-owned 失败恰好发一条 `sema.expression_resolution`。注意：binary 根节点当前默认再拥有诊断（binary-style root re-owning），本期**不**断言"上游失败不重复包装"，该话题超出本 feature。
- 恢复路径 fixture（`frontend_rules.md` 测试约定）：同一函数内合法 `var ok: String = "%s" % "a"` 与非法 `var bad = "%s" % <某个 GDCC class 实例>` 并存；断言仅一条 `sema.expression_resolution`、坏 subtree 被 skip、合法表达式仍 `RESOLVED(String)`。
- `FrontendAssignmentSemanticSupport` 相关测试：`s %= [...]`（`s: String`）解析为 `String` 且 assignment boundary 通过；`i %= 2`（`i: int`）保持既有数值行为。
- `FrontendTypeCheckAnalyzerTest`：`String %` 结果进入 condition / initializer / return 的 typed fact 消费。
- `FrontendCompileCheckAnalyzerTest` 回归两条：`"%s" % <Variant>` 不产生 `sema.compile_check`；`String % null` 只有上游 `sema.expression_resolution`，不补 `sema.compile_check`。
- `FrontendLoweringBodyInsnPassTest`：`"..." % [a, b]` lowering 后存在一条 `BinaryOpInsn(MODULE, String-slot, Array-slot)`（数组字面量先经 `construct_container_literal` 建组，不断言"函数体仅一条指令"）；`s %= x` lowering 为 `BinaryOpInsn(MODULE)` + store。

验收细则：

- sema/owner/恢复锚点同时覆盖 happy path 与 negative path（含 category、skip、同 module 继续）；lowering/compile-check 锚点只覆盖 happy path 与 invariant（`FAILED` 表达式不会进入 lowering）。
- `script/run-gradle-targeted-tests.sh --tests` 定向执行上述测试类全部通过。

### 阶段 D：backend codegen 回归测试（G1 backend 部分）

目标：

- 锚定 `String %` 的两条 codegen 路径——`BUILTIN_EVALUATOR`（含 typed array 归一化）与 `VARIANT_EVALUATE`——测试按 untyped Array / typed Array[int] / Variant 三条用例锚定。

实施内容：

- `COperatorInsnGenTest`：
  - `BinaryOpInsn(MODULE, String, Array)`（untyped）→ `gdcc_eval_binary_module_string_array_to_string` helper 调用，且 helper spec 被收集（`CGenHelper.collectBinaryEvaluatorHelperSpec`）；
  - `BinaryOpInsn(MODULE, String, Array[int])`（typed）→ helper 名按 `getTypeName()` sanitize 断言（`gdcc_eval_binary_module_string_array_int_to_string`），且 metadata 归一化仍命中 `String % Array -> String`；
  - `BinaryOpInsn(MODULE, String, Variant)` → `godot_variant_evaluate(GDEXTENSION_VARIANT_OP_MODULE, ...)` + `String` unpack type-check；
  - `MODULE(int, int)` / `MODULE(float, float)` 既有 primitive fast path 与除零 guard 用例不回归；
  - invariant：`GodotOperatorTest` 保持零改动即通过（不新增 `FORMAT`）。
- 若实测发现 typed array helper 参数类型渲染与 evaluator ABI 不匹配（理论上 `godot_TypedArray(T)` 即 `godot_Array`，应无问题），在此处记录并按 §2.4 结论修复。

验收细则：

- happy path：三条用例（untyped Array、typed Array、Variant）的生成 C 片段断言通过。
- invariant：既有 `COperatorInsnGenTest`、`GodotOperatorTest` 全量通过。
- 定向测试通过。

### 阶段 E：e2e test_suite（G1 e2e 部分）

目标：

- 通过 `GdScriptUnitTestCompileRunner` 全链路验证真实格式化结果。

实施内容（严格遵循 `doc/test_suite.md`）：

- 新增 `src/test/test_suite/unit_test/script/string_format/` 与对应 `validation/string_format/` 用例对；script 侧 `extends Node`，一行一用例；validation 侧仅在全部断言成功时打印 `__UNIT_TEST_PASS_MARKER__`。
- 正向用例至少覆盖：
  - 单占位符单值：`"We're waiting for %s." % "Godot"`；
  - 多占位符数组：`"%s=%d" % ["hp", 10]`；
  - 数字族：`"%05d"` / `"%.2f"` / `"%x"`；
  - `%%` 转义；
  - typed array 右操作数（`Array[int]` 局部变量，兼作 typed array ABI 运行时锚点）；
  - `String % <untyped Variant>`（如函数未标注参数）结果赋给 `String` 变量；
  - `%=` 复合赋值。
- 运行时错误语义锚定拆为**独立用例对**（如 `runtime_error_arg_count.gd`）：pass marker 打在坏调用之前，坏调用（占位符多于参数）按 Godot 实际行为断言——用 `# gdcc-test: output_contains=...` 或 `output_contains_any` 锚定运行时错误通道（实施时填入实测子串，不强求冻结完整错误文案），并按现有负向惯例加 `output_not_contains` 防止坏调用之后的输出被判为成功；必须有一条失败信号，不接受"非空输出"这类无判别力断言（runner 只认 `output_contains` / `output_not_contains` / `output_contains_any`）；这是 runtime 锚点，不是 compile-fail 用例（compile-fail 不进 test_suite）。
- validation 侧做语义断言（`assert` 结果字符串相等），不做生成 C 文本匹配。
- 更新 `GdScriptUnitTestCompileRunnerTest`：`EXPECTED_SCRIPT_PATHS` 加入新用例对，并新增 `STRING_FORMAT_SCRIPT_PATHS = scriptPathsWithPrefix("string_format/")` 及对应 `@TestFactory`（该测试类按目录前缀拆 TestFactory，只更新清单不会有任何 DynamicTest 执行新用例）。

验收细则：

- e2e 在具备 Godot+zig 环境时通过；缺 `zig` 或 `GODOT_BIN` 时由 JUnit assumption skip（与 `test_suite.md` 现有环境感知约定逐字对齐），不引入硬依赖。
- `EXPECTED_SCRIPT_PATHS` 与新用例对一致，且新目录对应的前缀常量与 `@TestFactory` 已接入（e2e 用例确实会被执行，而非仅通过清单校验）。
- 运行时错误用例与正向用例分离，互不污染 pass marker。

### 阶段 F：文档同步（G3）

目标：

- 让所有事实源与最终实现口径一致。

实施内容：

- `frontend_rules.md:130`：删除"不支持"声明，替换为当前正式支持面摘要（静态 metadata 命中面 + runtime-open 右操作数 + `%=`），并写明 D3 边界（具名 object 子类 / `null` 右操作数 fail-closed，`String % Object` 精确名命中保持支持）。
- `frontend_unary_binary_expr_semantic_implementation.md`：
  - §4.1 求值顺序中，把 D2 插在 special rule 与 runtime-open 之间，注明内联位置（`resolveBinaryOperatorResultType` 主函数）、明确**不进** `resolveBinarySpecialReturnType`；
  - §4.2 引言改写为"helper 内四类 special rule + 主函数内联的 MODULE/String 规则"，或为 D2 在 §4.2 之后另开独立小节，不得写成第五条 helper 规则；
  - §7.2 的"runtime-open 一律 `DYNAMIC(Variant)`"表述更新：补记 `MODULE` + 左 `String` + 右 runtime-open → `RESOLVED(String)` 例外；
  - §2.2 注明字符串格式化与取模共享 `%`/`MODULE`；§6 测试锚点补充本 feature 的测试类（含 `FrontendCompileCheckAnalyzerTest` 回归）；§5.3 注明本 feature 对 compile gate 零改动。
- 本文档任务状态与实施日志收尾。

验收细则：

- 三处文档描述与代码行为一致；不存在"文档说不支持但代码已放行"的矛盾。
- 只读参考清单中的文档（见文档状态）确未被改动。

## 4. 验收准则

实现完成后，必须同时满足以下条件：

1. sema：`String %` 对抽样代表类型发布 `RESOLVED(String)`——抽样至少含 `int`、`float`、`bool`、`String`、untyped `Array`、`Array[int]`、untyped `Dictionary`、`Dictionary[String, int]`、`PackedStringArray`、`Object`。完整 metadata 矩阵的正确性视为"委托 Godot evaluator 继承"，不要求逐类型 e2e。
2. codegen：untyped `Array`、typed `Array[int]`、`Variant` 三条用例的 C 生成断言通过；e2e 对阶段 E 列出的代表用例断言运行时结果字符串与 Godot 行为一致。
3. `String % <Variant/dynamic>` 发布 `RESOLVED(String)`，backend 走 `VARIANT_EVALUATE` 且结果 unpack 到 `String` slot。
4. `s %= args`（`s: String`）在 sema、lowering、codegen、e2e 四层的行为与 `"fmt" % args` 一致。
5. `String % <具名 object 子类>`、`String % null`（诊断类型名 `Nil`）、`int % String` 保持 fail-closed：恰好一条 root-owned `sema.expression_resolution` error，坏 subtree 被跳过，同 module 其它合法 subtree 继续处理；`String % Object` 保持 `RESOLVED(String)`。
6. 数值取模（`int % int`、`float % float`）、mixed `int/float` 拒绝、primitive fast path 与除零保护全部不回归。
7. 不新增 `GodotOperator` 枚举、不新增诊断 category、不新增 side table、不改 parser、不改 compile gate 清单。
8. `frontend_rules.md` 与 `frontend_unary_binary_expr_semantic_implementation.md` 已同步为最终口径。
9. 定向单元测试（阶段 B/C/D）与 e2e（阶段 E，环境允许时）全部通过。

## 5. 任务状态

- [x] 任务 1：完成调研与实施计划文档（阶段 A）。
- [x] 任务 2：实现 sema 精度规则（阶段 B，只改代码与单测）。
- [x] 任务 3：补齐 frontend sema/type-check/compile-check/lowering 单测，含 `%=` 与恢复路径 fixture（阶段 C）。
- [x] 任务 4：补齐 backend codegen 回归测试（阶段 D）。
- [x] 任务 5：新增 e2e test_suite 用例，更新 `EXPECTED_SCRIPT_PATHS` 并接入 `STRING_FORMAT_SCRIPT_PATHS` + `@TestFactory`（阶段 E）。
- [x] 任务 6：同步 `frontend_rules.md` 与 unary/binary 事实源并全量回归（阶段 F）。

## 6. Post-MVP Backlog

- `String % <具名 object 子类>`（`Node`、GDCC script class 等）：评估为 `MODULE`+`String` 左操作数增加 object 归一化（frontend metadata 匹配与 backend 签名收集需同步），并确认 GDCC class 类型的 `getGdExtensionType()` 映射为 `OBJECT`。
- `String % null`：评估 Nil 右操作数按 Godot 单值包装语义放行。
- `StringName % <...>` 左操作数：metadata 同样声明了完整 `StringName % T -> String` 矩阵；评估把 D2 的左操作数条件扩展为 `GdStringType || GdStringNameType`（返回仍为 `String`），与 String 对齐。
- 编译期占位符静态校验：仅当左操作数为字符串字面量且右操作数形态静态可知时做 warning 级校验；不属于本期。
- `%` 格式化与 `String.format()` 的文档互链（用户指南层面），不属于编译器合同。

## 7. 实施日志

- 2026-09-07
  - 已完成并行调研（frontend 文档/代码、backend/LIR/运行时、类型系统规范）与 Godot 上游参考核对（`variant_op.h` `do_mod` → `String::sprintf`、godot-docs format strings）。
  - 已核实现状支持矩阵（§2.2）与 typed array ABI 安全性（§2.4），确认实现面收敛为"sema 一条精度规则 + 全层测试 + 文档同步"。
  - 已形成设计决策 D1-D5 与分阶段计划。
  - 经两轮独立审阅后修订：拆分 G4 为三档合同（`Object` 精确名命中保持支持；具名 object 子类与 `null` fail-closed）；D2 定为 runtime-open 分支正前方内联、不扩 helper 签名；`StringName` 左操作数移入 Post-MVP；验收准则 1 改为可判定的抽样+委托口径；补充恢复路径 fixture、compile-check 回归、typed array codegen 锚点与 e2e 用例约定；统一文档同步职责到阶段 F。
  - 第二轮复核后修订：阶段 F 的 unary/binary 同步目标细化为 §4.1 求值顺序 + §4.2 引言改写或另开独立小节 + §7.2 例外（D2 不写成第五条 helper 规则）；chain-binding 旁注措辞修正；阶段 E 补 `STRING_FORMAT_SCRIPT_PATHS` + `@TestFactory` 接入要求，runtime-error 用例改为 `output_contains`/`output_contains_any` + `output_not_contains` 断言口径；阶段 C 与验收准则 1 的 Dictionary/PackedStringArray 抽样对齐；阶段 D 目标句改为两路径三用例。
  - 阶段 B 已实施：`resolveBinaryOperatorResultType(...)` 的 runtime-open 分支正前方内联 `MODULE` + 左 `GdStringType` + 右 runtime-open → `RESOLVED(String)`；`FrontendExpressionSemanticSupportTest` 新增 `resolveBinaryExpressionTypeStringFormatPublishesStringForRuntimeOpenRightOperand`（Variant/dynamic 右操作数 happy path）与 `resolveBinaryExpressionTypeStringFormatKeepsBoundariesAndInvariants`（exact int 不变、`Node`/`Nil`/`int % String` fail-closed 且诊断文案不变、`Variant % int` 与 `StringName % Variant` 仍 DYNAMIC）；定向测试与全类回归通过。阶段 B 经 review-expert-a 审阅 APPROVE，唯一低优先级问题（测试注释提到本层未断言的 category）已修正注释措辞。
  - 阶段 C 已实施：`FrontendExpressionSemanticSupportTest.resolveBinaryExpressionTypeStringFormatResolvesMetadataOperandMatrix` 锚定抽样矩阵（int/float/bool/String/Array/Array[int]/Dictionary/Dictionary[String,int]/PackedStringArray/Object → String）；`FrontendBodyOwnerProceduresExprTypeTest.analyzePublishesStringFormatTypesAndRecoversFromUnsupportedRightOperand` 锚定恢复路径（script class 右操作数恰好一条 `sema.expression_resolution`、坏 subtree FAILED、前后合法表达式仍 RESOLVED、Variant 右操作数 RESOLVED String）；`FrontendAssignmentSemanticSupportTest.resolveAssignmentExpressionTypeSupportsStringFormatCompoundAssignment` 锚定 `label %= args`（String）与 `hp %= 2`（int）均 RESOLVED+VOID——实施中发现 member-target 成功的 outcome 由 writeback 子路由 own（status=RESOLVED 但 rootOwnsOutcome=false），与既有 compound 测试惯例一致，故成功路径不断言 rootOwnsOutcome；`FrontendTypeCheckAnalyzerTest.analyzeConsumesStringFormatTypedFactsAcrossInitializerConditionAndReturn` 锚定 initializer/condition/return 消费（合法函数零诊断 + `var wrong: int = "%s" % value` 恰好一条 `sema.type_check`）；`FrontendCompileCheckAnalyzerTest` 新增两条回归（Variant 右操作数零诊断、`String % null` 仅上游 `sema.expression_resolution` 不补 compile_check）；`FrontendLoweringBodyInsnPassTest` 新增 `runLowersStringFormatBinaryIntoModuleInsnAfterContainerConstruction`（container 建组 → MODULE → return 链路）与 `runLowersStringFormatCompoundAssignmentOnLocalIntoModuleAndAssign`（`%=` → MODULE + AssignInsn）。6 个测试类全量回归通过。
  - 阶段 C 经 review-expert-a 审阅后修订：type-check fixture 改为 `if "%s" % value:` 使 `%` 成为 condition 根并断言其 RESOLVED(String) fact；lowering 测试补 `LiteralStringInsn` 左操作数链路与 left/right/result slot 类型断言（String/Array/String）；恢复路径诊断补 `contains("Point")` 提高判别力；复核 APPROVE 无残留问题。
  - 阶段 D 已实施：`COperatorInsnGenTest` 新增 `moduleStringArrayUsesBuiltinEvaluator`（untyped → `gdcc_eval_binary_module_string_array_to_string`）、`moduleStringTypedArrayKeepsSanitizedHelperName`（typed → `..._array_int_...`，metadata 归一化命中 plain Array）、`moduleStringVariantUsesVariantEvaluateWithUnpackTypeCheck`（VARIANT_EVALUATE + String unpack type-check），配套 `stringFormatApi()` fixture；`CCodegenTest.rendersStringFormatEvaluatorHelpersForUntypedAndTypedArrayRightOperands` 锚定两个 helper spec 的收集与 entry.h 渲染。`COperatorInsnGenTest`/`CCodegenTest`/`GodotOperatorTest` 全量回归通过，MODULE primitive fast path 与除零 guard 用例不回归。阶段 D 经 review-expert-a 审阅 APPROVE，无高/中/低问题。
  - 阶段 E 已实施：新增 8 对 `string_format/` e2e 用例（`single_value`、`array_multi_placeholder`、`numeric_family`、`percent_escape`、`typed_array_operand`、`variant_operand`、`compound_assignment`、`runtime_error_arg_count`）；已核对 Godot 4.5 `OperatorEvaluatorStringFormat.do_mod` 源码——sprintf 参数不足时**返回错误描述串**（`"not enough arguments for format string"`）且执行继续，故 runtime-error 用例采用"pass marker 前置 + `output_contains` 锚定错误文本 + `output_not_contains` 锚定 validation 失败路径"结构；`GdScriptUnitTestCompileRunnerTest` 已加 8 条 `EXPECTED_SCRIPT_PATHS`、`STRING_FORMAT_SCRIPT_PATHS` 常量与 `compilesAndValidatesStringFormatScripts` `@TestFactory`。本机 zig+Godot 环境下 `GdScriptUnitTestCompileRunnerTest` 全量通过（含真实编译运行，约 4 分钟）。validation 侧遵循现有 if/push_error 惯例做语义相等断言（仓库无 assert 用例）。阶段 E 经 review-expert-a 审阅 APPROVE；其低风险建议（runtime-error 用例 marker 后置以同时锚定"执行继续"）已采纳并复跑全量 e2e 通过。
  - 阶段 F 已实施：`frontend_rules.md` 的 MVP"不支持"声明替换为支持面摘要（含 D3 边界）；`frontend_unary_binary_expr_semantic_implementation.md` 同步 §2.2 二义入口注记、§4.1 求值顺序插入 D2 步骤、§4.2 引言改写（明确非第五条 helper 规则）、新增 §4.7 合同小节、§5.3 compile gate 零改动条目、§6 各层测试锚点、§7.2 例外；只读参考清单文档零改动。`./gradlew clean build --no-daemon --info --console=plain` 全量回归通过（约 6 分钟，含 e2e）。阶段 F 经 review-expert-a 审阅：事实源正文无误；计划文档自身收尾（状态/任务 6/日志）与 §1/§2.2/§2.6 实施前快照标注已按审阅意见补齐；unary/binary 文档头日期同步更新。
