# OperatorInsnGen 语义规范（`unary_op` / `binary_op`）

## 文档状态

- 状态：`Active`
- 文档类型：`规范 / 事实源`
- 更新时间：`2026-09-07`
- 适用范围：`backend.c` 中 `UNARY_OP`、`BINARY_OP` 的 C 代码生成与校验
- 说明：本文件描述“已落地语义与约束”。如与历史文档或旧实现描述冲突，以本文件为准。

---

## 1. 范围与设计目标

本文聚焦以下问题的当前权威定义：

1. `unary_op` / `binary_op` 的语义分流规则。
2. metadata 匹配与结果类型约束。
3. primitive 快路径与 guard 规则。
4. `Variant` 路径的生命周期与错误处理约束。
5. 面向后续工程迭代的风险边界与 deferred 项。

### 1.1 关键实现锚点

1. `src/main/java/gd/script/gdcc/backend/c/gen/insn/OperatorInsnGen.java`
2. `src/main/java/gd/script/gdcc/backend/c/gen/insn/OperatorResolver.java`
3. `src/main/java/gd/script/gdcc/backend/c/gen/CBodyBuilder.java`
4. `src/main/c/codegen/include_451/gdcc/gdcc_operator.h`
5. `src/main/c/codegen/template_451/entry.c.ftl`
6. `src/test/java/gd/script/gdcc/backend/c/gen/COperatorInsnGenTest.java`
7. `src/test/java/gd/script/gdcc/backend/c/gen/COperatorInsnGenEngineTest.java`
8. `src/test/java/gd/script/gdcc/backend/c/gen/CCodegenTest.java`

---

## 2. 当前语义基线

1. `swap/dual` 回退暂不支持（`unary_op` 与 `binary_op` 均不支持）。
2. `binary_op` metadata 仅按原顺序 `(leftType, op, rightType)` 匹配；不做隐式交换，不做对偶操作符替换。
3. `binary_op` 只要任一操作数为 `Variant`，统一走 `godot_variant_evaluate`。
4. `MODULE` 同时承载数值取模与 `String % args` 格式化。backend 不加格式化专用路径：静态右操作数走 `BUILTIN_EVALUATOR`（typed Array 仍归一化为 plain `Array`），右 `Variant` 走 `VARIANT_EVALUATE` 后 unpack 到 `String`。长合同见 `frontend_string_format_operator_implementation.md`。
5. `unary_op` 不走 `godot_variant_evaluate`，保持 metadata + builtin evaluator 路径。
6. `IN` 仅允许原顺序解析（`left IN right`），不支持任何交换/对偶补救。
7. `variant_evaluate` 的语义结果类型仍固定为 `Variant`，但 `result` 可为非 `Variant`：在运行时通过类型检查后自动 `unpack` 到目标类型。
8. `Variant -> Variant` 回写必须走构造拷贝（`godot_new_Variant_with_Variant` + `callAssign`），禁止“浅拷贝赋值 + 临时变量销毁”模式。
9. primitive 快路径统一发射 guard：
   - `ADD/SUBTRACT/MULTIPLY` 不做整型溢出 guard（使用 NOOP guard 占位）。
   - `DIVIDE/MODULE`：整型检查除数 `0`；浮点检查除数 `0.0`。
   - `SHIFT_LEFT/SHIFT_RIGHT` 检查移位量非法；`SHIFT_LEFT` 额外拒绝负左操作数（避免 C UB）。
10. `pow_int` 已修复负指数死循环：负指数下按 `base=1/-1/other` 返回 `1/-1(or1)/0`；正指数使用快速幂，保留 `__int128` 中间计算以提升截断前精度。

---

## 3. 语义与约束细则

### 3.1 通用输入输出约束

1. `resultId` 必须存在且对应非 `ref` 变量。
2. 操作数变量必须存在。
3. `result` 类型校验分路径执行：非 `variant_evaluate` 路径使用 `ClassRegistry#checkAssignable`；`variant_evaluate` 非 `Variant` 结果走运行时类型检查 + `unpack`。
4. 任一校验失败立即抛 `InvalidInsnException`。

### 3.2 `unary_op`

1. 读取 `(op, operand)`。
2. evaluator 查询时 `type_b` 固定 `GDEXTENSION_VARIANT_TYPE_NIL`。
3. 当前统一走 metadata + builtin evaluator 路径（不做 unary 快路径，不走 `godot_variant_evaluate`）。

### 3.3 `binary_op`

1. 读取 `(op, left, right)`。
2. metadata 严格按原顺序 `(leftType, op, rightType)` 匹配。
3. 原顺序不命中直接 fail-fast。

### 3.4 比较特化范围

比较运算仅对以下类型特化：

1. 基本类型（`bool/int/float`）。
2. `Object`（仅 `==` / `!=`）。
3. `Nil`（仅比较运算）。

其他类型（向量、容器、Variant 等）不走比较特化。

### 3.5 Object 比较特化（`==` / `!=`）

仅当左右均为 `Object` 类型时生效（fat-pointer C1 normalized raw equality）。
frontend 对任意两个静态 `GdObjectType` 的 `==` / `!=` 发布 `RESOLVED(bool)`，见
`frontend_unary_binary_expr_semantic_implementation.md` §4.2 / §4.5；本节只定义 lowering 之后的
codegen，不回写 frontend 接受面。

1. 两侧各自 materialize **equality-normalized raw**（`CBodyBuilder.renderEqualityNormalizedRaw`）：
   - 先用 `gdcc_object_is_null_raw_and_id(raw_sentinel, instance_id)` 判定 null ∪ freed；
   - null/freed → `NULL`；
   - live engine → `(GDExtensionObjectPtr)value.ptr`；
   - live GDCC → `gdcc_<Type>_fat_ptr_live_object(value)`（禁止 dead 路径 `Type_object_ptr`）。
2. `==`：`normalized_left == normalized_right`；`!=` 同理。
3. 语义折叠：`null == null` / `freed == null` / `freedA == freedB` 为 true；live 与 null/freed 为 false；
   live 同/异实例按 Godot raw 指针 identity。
4. **禁止**直接比较 fat struct；**禁止**以 `instance_id` 为 `==` 比较键；**禁止**从 raw 调用
   `godot_object_get_instance_id(...)`。
5. nullness 查询仅服务 “禁止解引用 dead wrapper / 折叠 dead”，与 `object_is_null` 指令职责分离。
6. 其他 Object 运算符：fail-fast。

### 3.6 Nil 比较特化

1. `Nil == Nil` -> `true`。
2. `Nil != Nil` -> `false`。
3. `Nil` 与非 `Nil` 比较：
   - 对方为 Object 时：使用 `gdcc_object_is_null_raw_and_id(raw, instance_id)`（null ∪ freed → true）。
   - 其余情况不相等。
4. `Nil` 非比较运算不特化，走默认分流。
5. 该特化只在左右都不是已发布 `Variant` 时参与 resolver。
   - 若任一操作数已经是 `Variant`，resolver 仍优先走 `godot_variant_evaluate`。
   - 此时另一侧的 `Nil` 必须被物化为真实 nil `Variant`，即 `godot_new_Variant_nil()`，而不是伪造 `godot_new_Variant_with_Nil(...)`。

### 3.7 primitive 快路径（`bool` / `int` / `float`）

快路径必须同时满足：

1. `(leftType, op, rightType)` 命中快路径白名单矩阵。
2. metadata 确认该运算签名可用。

补充约束：

1. 快路径白名单为三元矩阵，不允许降维判断。
2. `POWER` 统一按 `pow/pow_int` 分流，不使用 C 原生幂运算写法。
3. 逻辑 `XOR` 按逻辑 xor 语义生成，不做简单位运算替代。
4. guard 当前聚焦 UB/除零防护，不覆盖整型算数溢出。

`POWER` 快路径规则：

1. 任一操作数为浮点：生成 `pow`（`math.h`）。
2. 双整型：生成 `pow_int`（`gdcc_operator.h`）。

### 3.8 Variant 参与策略

1. `binary_op`：任一操作数为 `Variant` 即命中 `godot_variant_evaluate`。
2. `unary_op`：不使用 `godot_variant_evaluate`。
3. `IN`：不参与 primitive 快路径，仅按原顺序 metadata 解析。
4. `Variant -> Variant` 结果回写：统一构造拷贝写回（`godot_new_Variant_with_Variant`）。
5. `Variant -> 非 Variant` 结果回写：先做运行时类型检查（`gdcc_check_variant_type_builtin` / `gdcc_check_variant_type_object`），通过后再 `unpack` 到目标类型。
6. 这与 Godot GDScript analyzer 的一个 compile-time typing shortcut 不同：
   - Godot 在分析 `x == null` / `x != null` 时，会先把结果类型固定成 `bool`，哪怕 `x` 已是 `Variant`
   - GDCC 当前一旦操作数已发布为 `Variant`，backend 仍保持统一的 `godot_variant_evaluate` 路径
   - 两者的运行时语义仍可对齐，前提是 `Nil` 侧确实打包成真实 nil `Variant`

---

## 4. 元数据与类型规则

### 4.1 `GodotOperator` -> `GDExtensionVariantOperator`

由 resolver 维护确定性映射，覆盖 `GodotOperator` 全量值（含 `BIT_NOT -> GDEXTENSION_VARIANT_OP_BIT_NEGATE`）。

### 4.2 metadata 匹配规则

1. unary：`rightType` 使用空串语义归一化匹配。
2. binary：按 `(leftType, op, rightType)` 匹配。
3. 原顺序不命中：直接 fail-fast（无交换/对偶补救）。
4. metadata 异常记录（空类型名、非法 operator 字符串等）：跳过异常项并输出可定位告警；最终无可用匹配则 fail-fast。

#### 4.2.1 typed container operand 归一化

metadata 条目按 plain 容器名注册，而 LIR 类型可能是 richer generic text，因此两处 metadata key lookup 统一经 `OperatorResolver.operatorOperandTypeName(...)` 归一化（与 frontend sema 侧同名规则逐字一致，见 `frontend_unary_binary_expr_semantic_implementation.md` §4.3）：

1. `Array[T] -> Array`、`Dictionary[K, V] -> Dictionary`；`GdPackedArrayType` 不属于 `GdArrayType`，保持 raw `Packed*Array` 名。
2. 左操作数 owner-class 查询与 binary 右操作数 metadata 匹配都必须先归一化。
3. 归一化只是 metadata key lookup 规则，不是类型协变，不改变 source-level 类型身份；不引入交换/对偶 fallback，原顺序仍严格匹配。
4. 归一化后仍无匹配条目时必须 fail-fast，不得凭空发明匹配。

### 4.3 结果类型规则

1. 快路径、evaluator、Variant evaluate 都必须先解析语义结果类型。
2. 结果类型与 `result` 不兼容时，编译期 fail-fast。
3. `binary_op` 的 Variant evaluate 语义结果固定为 `Variant`；当 `result` 为非 `Variant` 时，改为运行时类型检查后 `unpack`，不再在编译期直接拒绝。

### 4.4 `GdType -> GDExtensionVariantType`

1. evaluator 与 `variant_evaluate` 路径均使用确定性映射。
2. 映射来源以 `GdType.getGdExtensionType()` 为基线，再转换为对应 C 枚举常量名。
3. 映射失败属于编译期错误。

---

## 5. 生成路径架构

### 5.1 分流顺序

1. `binary_op` 先看 `Variant` 参与：任一操作数为 `Variant` 即走 `godot_variant_evaluate`。
2. 其余再看比较特化（primitive / Object / Nil）。
3. primitive 快路径（含 `POWER -> pow/pow_int`）。
4. 非 Variant builtin evaluator 路径（严格原顺序 metadata）。
5. 其余 fail-fast。

### 5.2 `godot_variant_evaluate` 槽位与生命周期

1. `r_return` 使用未初始化临时槽位（`declareUninitializedTempVar`）。
2. evaluate 后通过 `callAssign` 写回目标。
3. 禁止直接把 `r_return` 指向已初始化目标槽位。
4. 输入侧按需 `pack`；结果侧按目标类型分流：
   - `Nil -> Variant`：必须使用 dedicated nullary ctor `godot_new_Variant_nil()`；
   - `Variant -> Variant`：构造拷贝写回；
   - `Variant -> 非 Variant`：运行时类型检查通过后 `unpack`。
5. `Variant -> Variant` 必须构造拷贝写回，避免浅拷贝 + 临时销毁导致悬挂。
6. `Variant -> 非 Variant` 的类型检查规则：
   - builtin：要求 `GDExtensionVariantType` 精确匹配（`gdcc_check_variant_type_builtin`）；
   - Object：expected class name 继续使用注册时的 canonical class name；exact match 始终通过，且 engine / gdcc object 都允许通过 `gdcc_check_variant_type_object(..., true)` 走 subclass-compatible fallback。

### 5.3 evaluator helper（缓存）

建议命名模式：

1. `gdcc_eval_unary_<op>_<type_a>(...)`
2. `gdcc_eval_binary_<op>_<type_a>_<type_b>(...)`

运行时约束：

1. helper 缓存 evaluator 指针。
2. `evaluator == NULL` 立即 hard-fail。
3. `valid == false` 与 `evaluator == NULL` 使用统一运行时错误风格。

### 5.4 快路径 guard 规范

1. `DIVIDE/MODULE`：
   - 整型：`gdcc_int_division_by_zero`
   - 浮点：`gdcc_float_division_by_zero`
2. `SHIFT_LEFT/SHIFT_RIGHT`：
   - `gdcc_int_shift_left_invalid`
   - `gdcc_int_shift_right_invalid`
3. 其他 primitive op 发射 NOOP guard，保持代码生成结构一致。

---

## 6. 错误处理契约（Fail-Fast）

以下场景必须抛 `InvalidInsnException`：

1. result 变量缺失或 result 为 `ref`。
2. 操作数变量缺失。
3. 非 `variant_evaluate` 路径中，结果类型与 `result` 不兼容。
4. metadata 原顺序签名不支持。
5. `Object` 使用 `==` / `!=` 以外运算。
6. `Nil` 比较特化条件不满足却误入特化分支。
7. `POWER` 快路径无法确定 `pow` 或 `pow_int`。
8. 生成路径绕过 `assignVar/callAssign` 生命周期管道。
9. `variant_evaluate` 目标类型无法映射到合法类型检查/`unpack` 路径。

---

## 7. 回归与验收基线

### 7.1 单元与代码生成回归

核心覆盖点：

1. `POWER(float,int)` -> `pow`；`POWER(int,int)` -> `pow_int`。
2. `MODULE(float,float)` 快路径 + 浮点除零 guard。
3. 原顺序 metadata 严格匹配（无交换回退）。
4. Variant 参与场景统一 evaluate；`Variant -> 非 Variant` 通过运行时类型检查与 `unpack` 回写。
5. `IN(int, Array)` 不走快路径。
6. typed container operand 归一化：`IN(int, Array[int])` / `IN(String, Dictionary[String, int])` 命中 plain `Array` / `Dictionary` 条目，`Array[int]` 可作左 owner；归一化后 plain 条目缺失必须 fail-fast（`COperatorInsnGenTest`）。
7. guard 覆盖规则：浮点除零、整型除零、移位 UB、防护 NOOP 占位。

辅助回归：`CCodegenTest`。

### 7.2 引擎集成基线

重点验证：

1. `binary_op` evaluate 与 `unary_op` evaluator 在真实 Godot 运行时行为一致性。
2. `POWER`、比较特化、metadata 原顺序约束。
3. Variant 回写生命周期稳定性与运行时错误可观测性。

建议门禁层级：

1. `L1-SMOKE`（阻塞）
2. `L2-SEMANTIC`（阻塞）
3. `L3-STRESS`（非阻塞）

### 7.3 最近有效回归结果（快照）

1. `COperatorInsnGenTest`：`37/37` 通过。
2. `COperatorInsnGenEngineTest`：`4/4` 通过。
3. `CCodegenTest`：`6/6` 通过。

---

## 8. 风险与工程反思

### 8.1 当前风险边界

1. `pow` / `pow_int` 的边界语义仍需持续与 Godot 对齐（负指数与极值输入尤需关注）。
2. `binary_op` Variant 路径依赖运行时 hard-fail 分支兜底，且 `Variant -> 非 Variant` 依赖运行时类型检查，需持续做引擎级回归。
3. metadata 原顺序严格匹配提高了确定性，但会放大 metadata 缺失时的编译期失败暴露。
4. 目前 guard 策略不覆盖整型算数溢出，这是显式取舍而非遗漏。

### 8.2 工程反思（后续迭代准则）

1. 单一事实源优先：语义约束必须集中表达，避免“代码已变、文档滞后”。
2. 编译期错误优先：可静态判定的问题不应下放到运行时。
3. 生命周期安全优先于局部性能：`Variant` 值语义必须通过构造拷贝与受控赋值管道落地。
4. guard 设计应可解释：每条 guard 都需要可追溯的 UB/运行时风险依据。

---

## 9. Deferred 能力

1. 交换/对偶回退注册表（`A op B -> B dualOp A`）仍为 deferred；恢复时需单独立项并补齐完整回归矩阵。
2. `binary_op` Variant 混合场景的 ptr-evaluator 优先策略仍为 deferred；恢复时必须与生命周期与错误处理规则一并重新验收。
