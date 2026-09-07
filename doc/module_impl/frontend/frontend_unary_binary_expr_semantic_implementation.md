# Frontend Unary / Binary Expression 语义实现说明

> 本文档作为 frontend `UnaryExpression` / `BinaryExpression` 语义分析的长期事实源，定义当前支持面、owner 边界、运算符规范化合同、稳定 typed contract、显式边界与后续工程需要继续遵守的约束。本文档替代原 `frontend_unary_binary_expr_semantic_plan.md`、`frontend_object_identity_equality_plan.md` 与 `frontend_not_in_operator_plan.md`，不再保留阶段拆分、进度记录或已完成任务流水账。

## 文档状态

- 状态：事实源维护中
- 更新时间：2026-09-07
- 适用范围：
  - `src/main/java/gd/script/gdcc/frontend/sema/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/main/java/gd/script/gdcc/frontend/sema/analyzer/support/**`
  - `src/main/java/gd/script/gdcc/enums/GodotOperator.java`
  - `src/main/java/gd/script/gdcc/gdextension/ExtensionBuiltinClass.java`
  - `src/test/java/gd/script/gdcc/frontend/sema/**`
  - `src/test/java/gd/script/gdcc/frontend/sema/analyzer/**`
  - `src/test/java/gd/script/gdcc/frontend/lowering/FrontendLoweringBodyInsnPassTest.java`
  - `src/test/java/gd/script/gdcc/backend/c/gen/COperatorInsnGenTest.java`
  - `src/test/java/gd/script/gdcc/test_suite/GdScriptUnitTestCompileRunnerTest.java`
  - `src/test/test_suite/unit_test/script/smoke/object_nil_equality.gd`
  - `src/test/test_suite/unit_test/validation/smoke/object_nil_equality.gd`
  - `src/test/test_suite/unit_test/script/smoke/object_identity_equality.gd`
  - `src/test/test_suite/unit_test/validation/smoke/object_identity_equality.gd`
  - `src/test/test_suite/unit_test/script/smoke/not_in_membership.gd`
  - `src/test/test_suite/unit_test/validation/smoke/not_in_membership.gd`
  - `src/test/test_suite/unit_test/script/string_format/`
  - `src/test/test_suite/unit_test/validation/string_format/`
  - `src/test/java/gd/script/gdcc/enums/**`
- 关联文档：
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/frontend_chain_binding_expr_type_implementation.md`
  - `doc/module_impl/frontend/frontend_type_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_compile_check_analyzer_implementation.md`
  - `doc/module_impl/frontend/frontend_implicit_conversion_matrix.md`
  - `doc/module_impl/frontend/frontend_string_format_operator_implementation.md`
  - `doc/module_impl/backend/operator_insn_implementation.md`
  - `doc/gdcc_type_system.md`
- 参考实现 / 事实依据：
  - Godot `modules/gdscript/gdscript_analyzer.cpp`
    - `and/or` 在 analyzer 中走 source-level 特判，结果固定为 `bool`
    - `Array + Array` 在元素类型已知且相同时保留 typed array 结果类型
    - 静态类型 object 之间的 `==` / `!=` 在 analyzer 层即被接受并归约为 `bool`
  - Godot `core/variant/variant_op.cpp`
    - `OBJECT/OBJECT` 的 `OP_EQUAL` / `OP_NOT_EQUAL` 为 identity 比较，不要求两侧类相关
  - `E:/Projects/gdparser/vendor/tree-sitter-gdscript/grammar.js`
    - binary grammar 同时接受 `and` / `&&`、`or` / `||`、`in` / `not in`
    - unary grammar 同时接受 `not` / `!`
  - `E:/Projects/gdparser/src/main/java/dev/superice/gdparser/frontend/lowering/CstToAstMapper.java`
    - `UnaryExpression.operator()` / `BinaryExpression.operator()` 保存源码字面量，而不是 extension metadata canonical operator name
- 明确非目标：
  - 不在这里扩张 `Dictionary`、packed array family 或其他 container 的保型规则
  - 不在这里引入 numeric promotion 或 typed-boundary widening；`StringName` / `String` 互转由 ordinary typed boundary helper 管理，unary / binary 语义不拥有这条规则
  - 不在这里把 compile-only blocker 反向回灌到 shared semantic 路径
  - 不在这里把 `not in` 做成枚举 alias 或 synthetic AST；`not in` 已按源码层复合规则 `not (lhs in rhs)` 支持（见 §4.4）
  - 不在这里放宽 object/nil 或 object/object ordering，如 `<`、`>`、`<=`、`>=`
  - 不在这里把 object/nil equality 或 object identity equality 扩张成 assignment compatibility、parameter compatibility 或 slot compatibility 规则
  - 不在这里把 binary semantic 耦合到 `ClassRegistry.checkAssignable(...)` 或任何继承链检查
  - 不在这里把 `object == null` 改写成 object validity 语义
  - 不在这里把 backend 更宽的 nil equality 接受面整体上提为 frontend source-level 合同
  - 不在这里处理 `Signal.get_object()` binding；其返回 `Object` 是正确行为，identity equality 只消费该 published type

---

## 1. 当前集成位置

### 1.1 当前角色分工

frontend 当前将 unary / binary 语义冻结在 shared expression helper，而不是重新引入新的 analyzer：

- `FrontendExpressionSemanticSupport`
  - 负责 unary / binary 的局部纯语义求值
  - 不发布 side table
  - 不发 diagnostic
- `FrontendBodyOwnerProcedures`（内部 `BodyExpressionResolver`）
  - 负责把 unary / binary 结果发布到 `expressionTypes()`
  - 负责 root-owned `FAILED` / `UNSUPPORTED` 的 expr-owned diagnostic
- `FrontendChainBindingAnalyzer`
  - 允许在链头 / 嵌套表达式场景桥接 unary / binary 的局部结果
  - 不接管 `expressionTypes()` 的 owner 身份
- `FrontendTypeCheckAnalyzer`
  - 只消费已发布 typed fact
  - 不复制 operator 语义
- `FrontendCompileCheckAnalyzer`
  - 只消费已发布状态
  - 不再把 unary / binary 当作“表达式家族尚未实现”的 compile blocker

### 1.2 当前支持面

当前 frontend body phase 中：

- `UnaryExpression` 已属于正式支持面
- `BinaryExpression` 已属于正式支持面（含源码运算符 `not in`，按 §4.4 复合规则处理）
- `ConditionalExpression` 已属于正式支持面（专用 resolver + CFG 双语境构图 + `merge_write` boundary，见 `frontend_conditional_expression_implementation.md`）

这条边界意味着：

- unary / binary 的 typed fact 可以继续向 type-check、property initializer、return gate 与 compile gate 传递
- compile-only block 的剩余重心不再是 unary / binary / conditional；表达式级显式 intercept 当前为空

---

## 2. 运算符规范化合同

### 2.1 双入口工厂

`GodotOperator` 当前必须通过两个来源感知入口解释运算符：

1. `fromMetadataName(String name)`
   - 只服务 extension metadata canonical operator name
   - 例如 `and`、`or`、`not`、`unary-`、`unary+`
2. `fromSourceLexeme(String lexeme, OperatorArity arity)`
   - 只服务源码字面量
   - 必须显式带 unary / binary 语境

之所以不能把源码入口做成单参数版本，是因为：

- `-` 既可能是 unary `NEGATE`，也可能是 binary `SUBTRACT`
- `+` 既可能是 unary `POSITIVE`，也可能是 binary `ADD`

没有 arity 的 source factory 不是可靠工厂，只会把歧义推给调用方。

### 2.2 当前源码别名集合

当前 source factory 已冻结的别名集合包括：

- unary
  - `not` / `!` -> `NOT`
  - `+` -> `POSITIVE`
  - `-` -> `NEGATE`
  - `~` -> `BIT_NOT`
- binary
  - `and` / `&&` -> `AND`
  - `or` / `||` -> `OR`
  - `+`、`-`、`*`、`/`、`%`、`**`
  - `<<`、`>>`、`&`、`|`、`^`
  - `==`、`!=`、`<`、`<=`、`>`、`>=`
  - `in`
- 其中 `%`（`MODULE`）是二义入口：数值取模与字符串格式化 `"fmt" % args` 共享同一运算符；字符串格式化的类型合同见 §4.7

### 2.3 fail-closed 边界

当前必须继续保持 fail-closed：

- metadata 工厂拒绝 source-only alias，如 `&&` / `||` / `!`
- source 工厂拒绝未知 lexeme 与错误 arity 组合
- source 工厂当前必须拒绝 `not in`

`not in` 不得被静默映射成 `IN`。那样会直接丢掉源码层的逻辑取反语义，并把“独立的语义复合规则”错误降级成“普通 alias”。

该复合规则已落地：sema 在调用 source 工厂之前拦截 `"not in"` 并按 `not (lhs in rhs)` 处理（见 §4.4）。因此工厂的 fail-closed 不再表现为用户可见的 unsupported 边界，而是继续充当防 alias 退化的内部护栏。

---

## 3. Unary 合同

### 3.1 当前求值顺序与状态传播

`resolveUnaryExpressionType(...)` 当前冻结为：

1. 先解析 operand
2. 若 operand 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`
   - 根节点只传播 upstream 结果
   - `rootOwnsOutcome = false`
3. 若 operand 已发布为 `Variant`
   - 无论来源是 exact `RESOLVED(Variant)` 还是 `DYNAMIC(Variant)`
   - 根节点统一发布 `DYNAMIC(Variant)`
4. 若 operand 为 exact non-`Variant`
   - 先通过 source factory 归一化 operator
   - 再走 builtin operator metadata exact lookup
   - 命中则 `RESOLVED(returnType)`
   - 未命中则 root-owned `FAILED`

### 3.2 typed container owner 归一化

unary metadata owner 查找当前补上了 typed container 回退：

- `Array[T] -> Array`
- `Dictionary[K, V] -> Dictionary`

这样 frontend 不会因为自己持有 richer typed container 信息，就错过 Godot extension metadata 中使用的 raw builtin owner。

### 3.3 当前精度边界

unary 当前有意保持保守精度：

- exact `Variant` operand 不会被硬判成 exact failure
- `DYNAMIC` operand 也不会被伪装成 deferred
- 当前不尝试从 operator family 反推出更窄的 dynamic return type

这条边界不是最终精度上限，但它能保证：

- 不错误承诺过窄类型
- 不阻断 downstream 对 stable fact 的消费
- 不把当前工作膨胀成“整套 Variant-return precision”任务

---

## 4. Binary 合同

### 4.1 当前求值顺序与普通路由

`resolveBinaryExpressionType(...)` 当前冻结为：

1. 固定先算 left，再算 right
2. 任一 child 为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`
   - 根节点只传播 upstream 结果
   - `rootOwnsOutcome = false`
3. 对 exact / stable child：
   - 先处理 source-level special rule（`and/or`、object/nil equality、object identity equality、typed array preserve）
   - 再处理字符串格式化精度规则：`MODULE` + 左 `String` + 右 runtime-open 时发布 `RESOLVED(String)`（内联于 `resolveBinaryOperatorResultType(...)` 主函数，不进 `resolveBinarySpecialReturnType(...)`，见 §4.7）
   - 再处理 runtime-open：任一 operand 为 `DYNAMIC` 或 exact `Variant` 时，根节点保守发布 `DYNAMIC(Variant)`
   - 最后处理普通 builtin metadata exact lookup
4. 普通 exact metadata 未命中
   - 根节点发布 `FAILED`

普通 binary route 必须保持顺序敏感：

- 只按 `leftType + operator + rightType` 查找
- 不做自动 swap
- 不做“双向试探谁能过就算谁”

### 4.2 当前 source-level special rule

binary 当前有四类 source-level special rule（均位于 helper `resolveBinarySpecialReturnType(...)` 内），不得强行回退到 extension metadata。字符串格式化的 `MODULE` + 左 `String` + 右 runtime-open 精度规则（§4.7）内联在主函数中，不是第五条 helper 规则：

1. `and/or`
   - 同时覆盖源码别名 `&&/||`
   - 操作数合同与 condition expression 一致
   - 操作数只要求 stable typed fact，不要求 exact `bool`
   - 结果固定为 `RESOLVED(bool)`
2. typed array preserve
   - 只锚定 `Array[T] + Array[T]`
   - 仅在两侧都为 typed array、元素类型相同、且元素类型不是 exact `Variant` 时命中
   - 命中后保留 `Array[T]`
   - 其他情况必须回退普通 binary route，不能凭空扩张更多保型规则
3. object/nil equality
   - 只锚定 `==` / `!=`
   - 只覆盖 `Nil/Nil`、`Object/Nil`、`Nil/Object`
   - `Nil/Nil` 仅作为同一窄规则的伴随 case 保留，用来维持既有 `NIL_COMPARISON` 一致性；它不表示 frontend 已支持“任意类型与 `null` 可比较”
   - 命中后固定发布 `RESOLVED(bool)`
   - 实现入口固定在 `FrontendExpressionSemanticSupport.resolveBinarySpecialReturnType(...)`
   - 对象侧判断只允许基于 `publishedType instanceof GdObjectType`，不得依赖 source-facing 类名文本
   - 该规则属于 binary semantic，不是 ordinary typed-boundary widening；不得据此扩张
     `ClassRegistry.checkAssignable(...)`、type-check slot compatibility 或 compile gate
   - exact `Variant` / `DYNAMIC` operand 继续保持 runtime-open `DYNAMIC(Variant)` 路由，不被该规则提前收窄
   - backend 已有更宽 `NIL_COMPARISON` 接受面，但 frontend 当前合同不因此顺手放宽 `int == null`、
      `String == null` 或 container 与 `null` 的 equality
4. object identity equality
   - 只锚定 `==` / `!=`
   - 命中条件：两侧 `publishedType` 均为 `GdObjectType`（只允许 `instanceof GdObjectType`，不得依赖 source-facing 类名文本）
   - 覆盖同类、继承相关与继承无关任意静态 object pair（含 GDCC class 与 engine class 混合）；不要求 `checkAssignable`
   - 命中后固定发布 `RESOLVED(bool)`
   - 实现入口固定在 `FrontendExpressionSemanticSupport.resolveBinarySpecialReturnType(...)`，helper 为 `isObjectObjectEqualityPair(...)`
   - helper 与 `isObjectNilEqualityPair(...)` 并列，保持 `isXxxPair` 命名；`resolveBinarySpecialReturnType(...)` 必须继续是不持有 `ClassRegistry` 的纯静态 helper
   - 该规则属于 binary semantic，不是 ordinary typed-boundary widening；不得据此扩张
     `ClassRegistry.checkAssignable(...)`、type-check slot compatibility 或 compile gate
   - exact `Variant` / `DYNAMIC` operand 继续保持 runtime-open `DYNAMIC(Variant)` 路由；实践中 `DYNAMIC` 一律发布 `GdVariantType`，`GdVariantType` 不是 `GdObjectType`，天然不命中
   - object/object ordering（`<` / `>` / `<=` / `>=`）必须继续走普通路由并最终 `FAILED`（`sema.expression_resolution`，detail 含 `not defined for operand types`）
   - 该规则通过 `resolveBinaryOperatorResultType(...)` 自动覆盖 ordinary binary、compound assignment 复用点与 lowering temp typing；`==` / `!=` 不是 compound assignment operator

### 4.3 typed container 元数据匹配

binary metadata 路由当前统一按 raw builtin 名称参与匹配：

- 左操作数 owner `Array[T] -> Array`
- 左操作数 owner `Dictionary[K, V] -> Dictionary`
- 右操作数匹配也使用 raw builtin type name

这保证 metadata lookup 与 extension API 的 builtin owner 协议一致，不会因为 frontend 侧 richer generic type text 失配。

### 4.4 `not in` 复合规则

`not in` 按源码层复合规则 `not (lhs in rhs)` 落地，本节为其长期事实源。

Godot 对齐语义依据（`godotengine/godot` `modules/gdscript` 与 tree-sitter-gdscript）：

- Godot tokenizer 没有 `NOT_IN` token；parser 先构造普通 `in` 二元节点，再用 `UnaryOpNode(OP_LOGIC_NOT)` 包裹；`Variant::Operator` 与 VM 层只有 `OP_IN`，没有 `OP_NOT_IN`
- `OP_IN` evaluator 返回类型恒为 `bool`；`Variant`/dynamic 操作数保持 runtime-open（不在编译期判非法，运行时非法配对报错），不改变结果类型；错误消息锚定内层 `in` 配对
- tree-sitter-gdscript 把 `not in` 作为单个 binary operator，AST operator 字段保留源码字面量 `"not in"`，因此 gdparser 产出的 `BinaryExpression("not in", left, right)` 无需 parser 改动

规则要点：

- 保持原始 `BinaryExpression("not in", ...)` 根节点流经 sema 与 lowering；不做 AST rewrite，不造 synthetic 节点，不新增 `NOT_IN` metadata/operator 枚举——复合语义已由 `IN` + `NOT` 完整表达，新枚举只会制造与 Godot 不一致的第二语义
- sema 在枚举工厂前拦截 `"not in"`：以 `"in"` 递归分析操作数配对，内层 `FAILED` 原样传播（诊断锚定 `'in'` 配对，与 Godot 消息风格一致）；内层 `RESOLVED` 或 `DYNAMIC` 发布 `RESOLVED(bool)`（对 `bool` 查 unary `NOT` metadata 的防御性查询，恒命中）；诊断 owner 仍为 expr analyzer，失败使用 `sema.expression_resolution`，不再产生 `sema.unsupported_expression_route`，`RESOLVED` 直接通过 compile gate
- lowering 在 generic `BinaryOpInsn` 路径前特判 `"not in"`，产出 `BinaryOpInsn(IN, 固定 bool 中间槽) -> UnaryOpInsn(NOT)`；中间槽必须经 `session.allocateWritableRouteTemp(...)` 分配且固定 `bool`：backend unary `NOT` 只有 `NOT + bool` 特化路径（无 Variant evaluator 路径），dynamic 操作数时 `IN` 走 `godot_variant_evaluate` 并 runtime type-check + unpack 进 bool 槽（既有能力），因此 `IN -> bool -> NOT(bool)` 对 typed 与 dynamic 操作数都不需要 backend 新路线
- condition 语境（`if a not in b:`）不做分支翻转优化，走普通 `buildConditionFromValue(...)` 先求值再分支：分支翻转需要内层 `in` 节点作为 condition fragment root，而 synthetic 节点没有 published side-table facts，会在 `requireLoweringReadyExpressionType(...)` 处失败
- backend 对 typed 容器操作数的 metadata 名归一化（`Array[T] -> Array`、`Dictionary[K, V] -> Dictionary`）由 `operator_insn_implementation.md` §4.2 承接，同时服务普通 `in` 与 `not in`

维护约束：sema 结果必须保持 `RESOLVED(bool)` / `FAILED` 两态，不得回退为 `DYNAMIC(Variant)`；`GodotOperator.fromSourceLexeme("not in", ...)` 必须继续 fail-closed，任何把它加进枚举别名表的改动都是架构错误；当前不引入 `not in` 常量折叠，也不做 condition 分支翻转优化。

### 4.5 object equality 的 lowering / backend 合同

object/nil equality 当前必须继续沿用既有 binary compare 主路径：

- lowering 继续产出 `BinaryOpInsn`
- backend 继续复用 `OperatorPath.NIL_COMPARISON`
- 本合同不切换到 `variant_is_nil` / `object_is_null` 路线

object identity equality 同样沿用这条 `BinaryOpInsn` 主路径，但不走 `NIL_COMPARISON`：

- lowering 继续产出 `BinaryOpInsn`
- backend 继续复用 `operator_insn_implementation.md` §3.5 的 object/object equality-normalized raw 比较
- 本合同不新增独立 lowering / codegen 路线，也不把结果先 pack 成 `Variant`

保持这条路由的原因是：

- `BinaryOpInsn` 已经是当前 binary compare 的稳定主路径
- `NIL_COMPARISON` 已覆盖 `Nil/Nil`、`Nil/Object`、`Object/Nil` 与 `!=` 取反语义
- runtime-open `Variant == null` 不能被简化成“只做 nil tag 检查”的更窄规则
- 若切换到独立 low IR 路线，会把当前窄修复扩张成新的 rewrite / codegen 工程

### 4.6 object equality 的 Godot 语义边界

当前 object equality 文档只保留对实现边界有长期价值的 Godot 结论：

- `null` 是 literal，不是 ordinary builtin type
- `Object` 作为 class family 参与语义，不是 ordinary builtin type name 映射的一部分
- Godot/Variant runtime 为 `Object/Nil`、`Nil/Object`、`Nil/Nil` 注册了 equality / inequality evaluator
- Godot/Variant runtime 对 `OBJECT/OBJECT` 的 `OP_EQUAL` / `OP_NOT_EQUAL` 是 identity 语义，不要求两侧类相关
- backend 对部分非 object 类型与 `Nil` 也有既有运行时语义，但这不自动提升为 frontend 当前支持面
- `object == null` 合法，不等于推荐用它判断对象是否已释放；对象有效性判断仍应与 equality 语义分离
- `object == object` 合法，不等于 type equality 或 `is` / `as` 的继承判断
- identity 合同从定义上不需要继承关系判断；不得用 `checkAssignable(...)` 门控这条规则

因此后续实现、注释或文档不得把当前合同反向改写成：

- “Godot 只允许 object/null 与 `null` 比较”
- “`== null` 等价 object validity 检查”
- “backend 既然更宽，frontend 当前也应一起放宽”

### 4.7 字符串格式化 `%` 的 String 左操作数合同

长合同以 `frontend_string_format_operator_implementation.md` 为准；本节只保留 unary / binary 求值顺序需要的摘要。

`%` 与数值取模共享 `MODULE` 运算符入口（与 Godot `Variant::OP_MODULE` 的重载模型一致，不新增枚举）。字符串格式化 = `MODULE` 在 `String` 左操作数上的 metadata 重载，外加一条 runtime-open 精度规则：

- 静态右操作数走普通 metadata exact 匹配：`String % T -> String` 矩阵覆盖全部 builtin 类型；`Array[T]` / `Dictionary[K,V]` 归一化为 `Array` / `Dictionary`；精确名 `Object` 命中。具名 object 子类（如 `Node`、GDCC script class）与 `null` 右操作数 fail-closed（`sema.expression_resolution`）。
- runtime-open 右操作数（exact `Variant` 或 `DYNAMIC`）在主函数 runtime-open 分支正前方内联命中，发布 `RESOLVED(String)`。依据是 Godot `do_mod` 对 String 左操作数与全部 metadata `return_type` 恒为 `String`。该规则故意不进 `resolveBinarySpecialReturnType(...)`：helper 保持 `(GodotOperator, GdType, GdType)` 纯类型签名。
- 左操作数 runtime-open（如 `Variant % int`）仍走通用 runtime-open 路由发布 `DYNAMIC(Variant)`；`StringName` 左操作数当前不覆盖。
- 格式化语义（占位符族、`%%` 转义、padding/精度修饰符、错误描述串）整体委托 Godot runtime evaluator，编译器不重实现；占位符与参数数量不匹配是运行时行为（sprintf 返回错误描述串），不是编译期错误。
- `%=` 复合赋值与普通二元共用 `resolveBinaryOperatorResultType(...)`，自动继承本合同。
- backend 不加特化：静态右操作数走 `BUILTIN_EVALUATOR` helper（typed array helper 名按 `getTypeName()` sanitize，ABI 上 `godot_TypedArray(T)` 即 `godot_Array`）；右 `Variant` 走 `VARIANT_EVALUATE` 并把结果 unpack 到 `String` slot。

---

## 5. Downstream 消费合同

### 5.1 `FrontendBodyOwnerProcedures`

当前 unary / binary 已经不再因为“表达式家族尚未实现”被发布为 `DEFERRED`。

body expression resolver 当前需要继续保持：

- 区分 root-owned 非成功结果与 upstream 传播结果
- 只为 root-owned `FAILED` / `UNSUPPORTED` 补 expr-owned diagnostic
- 对 propagated 结果复用 upstream owner

### 5.2 `FrontendTypeCheckAnalyzer`

type-check 当前直接消费 unary / binary 的 stable fact：

- `RESOLVED`
- `DYNAMIC`

这条合同当前已经覆盖：

- unary condition，如 `!true`
- binary condition，如 `1 + 2`、`payload and 1`
- dynamic unary condition，如 `not payload`

type-check 继续遵守 Godot-compatible condition contract：

- condition 只要求 stable typed fact
- 不把 source-level condition 回退成 undocumented strict-bool dialect

### 5.3 `FrontendCompileCheckAnalyzer`

compile gate 当前只把以下状态视为 blocker：

- `BLOCKED`
- `DEFERRED`
- `FAILED`
- `UNSUPPORTED`

因此：

- `RESOLVED` unary / binary 不再命中 generic compile blocker
- `DYNAMIC` unary / binary 同样不再命中 generic compile blocker
- `not in` 经 §4.4 复合规则发布 `RESOLVED(bool)`（非法配对为 `FAILED`），同样不再命中 generic compile blocker
- 字符串格式化 `%` 不进入 compile-gate 特判：支持面组合发布 `RESOLVED(String)` 不命中 blocker；fail-closed 组合（如 `String % null`）由 upstream `sema.expression_resolution` 阻断且不补 `sema.compile_check`
- `ConditionalExpression` 已不再依赖显式 compile-only block：与 unary/binary 一样只依赖 published fact 是否 lowering-ready（见 `frontend_conditional_expression_implementation.md`）

---

## 6. 测试锚点

当前实现的测试锚点至少包括：

- `GodotOperatorTest`
  - metadata canonical operator 解析
  - source lexeme 解析
  - unary / binary `+` / `-` arity 区分
  - `&&` / `||` / `!` alias
  - `not in` fail-closed
- `ExtensionBuiltinClassTest`
  - metadata operator 解析委托 `GodotOperator.fromMetadataName(...)`
- `FrontendExpressionSemanticSupportTest`
  - unary 正反例
  - binary 正反例
  - `and/or` 合同
  - typed array preserve 正反例
  - object/nil equality 正反例
  - object identity equality 正反例（同类、继承相关、继承无关、GDCC/engine 混合、ordering 拒绝、Variant/DYNAMIC 保持 runtime-open）
  - `not in` 复合规则正反例（typed / dynamic / Variant 恒 `RESOLVED(bool)`，非法配对 `FAILED` 且消息锚定 `'in'`）
  - 字符串格式化 `%`（§4.7）：metadata 抽样矩阵（含容器归一化与精确名 `Object`）恒 `RESOLVED(String)`；runtime-open 右操作数 `RESOLVED(String)`；具名 object 子类 / `null`（`Nil`）/ `int % String` fail-closed 且文案不变；左 runtime-open 与 `StringName` 左操作数保持 `DYNAMIC(Variant)`
- `FrontendBodyOwnerProcedures` / body expression resolver 路径
  - unary / binary 结果发布
  - root-owned 与 upstream-propagated 区分
  - 字符串格式化恢复路径：script class 右操作数恰好一条 `sema.expression_resolution`，坏 subtree FAILED，同 module 前后合法格式化表达式仍 `RESOLVED(String)`
- `FrontendTypeCheckAnalyzerTest`
  - unary / binary 稳定结果进入 condition / initializer / return 消费面
  - 字符串格式化结果作为 condition 根 / initializer / return 的 String fact 消费，`String -> int` slot 拒绝恰好一条 `sema.type_check`
- `FrontendCompileCheckAnalyzerTest`
  - unary / binary resolved / dynamic route 不再触发 compile blocker
  - object/nil equality 不再触发 compile blocker
  - object identity equality 不再触发 compile blocker
  - object/object ordering 继续被 `sema.expression_resolution` 阻断
  - `ConditionalExpression` 已放行：支持面三元零 compile_check，FAILED/UNSUPPORTED 三元经 upstream owner + exact-range 去重阻断
  - 字符串格式化 `%`：`String % Variant` 零诊断；`String % null` 仅上游 `sema.expression_resolution`，不补 `sema.compile_check`
- `FrontendLoweringBodyInsnPassTest`
  - object/nil equality 继续进入 ordinary `BinaryOpInsn` lowering 路由
  - object identity equality 进入 ordinary `BinaryOpInsn`，结果 `bool`，不经 `Pack/UnpackVariantInsn`
  - `not in` 复合 lowering：`BinaryOpInsn(IN, 固定 bool 中间槽) -> UnaryOpInsn(NOT)`，覆盖 typed / dynamic 操作数与 condition 语境分支极性
  - 字符串格式化：字面量左 / 数组字面量右 lowering 为 `BinaryOpInsn(MODULE)`（String/Array slot 类型锚定，数组先 `construct_container_literal`）；`%=` lowering 为 `BinaryOpInsn(MODULE)` + 本地 store
- `FrontendAssignmentSemanticSupportTest`
  - `%=` 字符串格式化（`String %= Array`）与数值取模（`int %= int`）均 RESOLVED 且 writeback 边界通过
- `COperatorInsnGenTest`
  - `Object/Nil`、`Nil/Object`、`Nil/Nil` 继续走 nil specialization codegen
  - `Object/Object`、`Node/Node`、GDCC/engine 混合 pair 继续走 equality-normalized raw codegen
  - 字符串格式化三条 codegen 用例：`MODULE(String, Array)` / `MODULE(String, Array[int])` 走 `BUILTIN_EVALUATOR`（helper 名 sanitize + metadata 归一化命中），`MODULE(String, Variant)` 走 `VARIANT_EVALUATE` + String unpack type-check
- `CCodegenTest`
  - 字符串格式化 untyped / typed array 两个 helper spec 均被收集并渲染进 `entry.h`
- `GdScriptUnitTestCompileRunnerTest`
  - `object_nil_equality` smoke 资源保持端到端可编译与结果对齐
  - `object_identity_equality` smoke 资源覆盖自反 `==`、不同实例 `!=`、`Node == Object`、`Signal.get_object() == node` 与 `null` 分支不误翻转
  - `not_in_membership` smoke（script + validation 孪生）覆盖 typed / dynamic 操作数、`Array` / `Dictionary` / `String` 容器、value / condition 语境及与 `not (a in b)` 的等价性
  - `string_format/` 用例组覆盖单值、多占位符数组、数字族修饰符、`%%` 转义、typed array 右操作数、Variant 右操作数、`%=` 与 sprintf 参数不足的运行时错误描述串

后续若扩张 unary / binary 行为，测试必须继续同时覆盖：

- happy path
- root-owned negative path
- upstream propagation path
- 与 `frontend_rules.md`、type-check 合同、compile-check 合同的对齐

---

## 7. 风险与后续工程约束

### 7.1 `NOT` 仍可能需要后续精度升级

当前 `NOT` 已经进入 unary 框架并具备 source alias，但它是否要完全升级为与 condition contract 对齐的 source-level special rule，当前尚未收口为独立任务。

后续若继续提升它的精度，应保持：

- 不修改现有 operator normalization 边界
- 不破坏 `DYNAMIC(Variant)` 的保守合同
- 用 characterization tests 先冻结当前行为，再讨论收窄

### 7.2 dynamic 精度仍然保守

除 `and/or -> bool` 与 §4.7 的 `MODULE` + 左 `String` + 右 runtime-open → `RESOLVED(String)` 外，当前 unary / binary 对 runtime-open operand 统一保守发布 `DYNAMIC(Variant)`。

这条边界的价值在于：

- 不错误承诺过窄类型
- 不阻断 downstream 消费 stable fact
- 不把当前事实源重新拉回“为实现阶段服务的计划文档”

### 7.3 object equality 仍是窄规则

当前 object/nil equality 的工程价值在于补齐 frontend 对已知静态安全组合的缺口，而不是把 backend
更宽的 nil equality 运行时语义整体上提为 source-level 合同。

当前 object identity equality 按 Godot `OBJECT/OBJECT` identity 语义接受任意两个静态 `GdObjectType` pair，
结果 `bool`。它不是 type equality，也不是 assignability。后续若继续扩张 equality 规则，必须再次论证：

- 是否仍属于 binary semantic：只回答 `==` / `!=` 的结果类型，不触碰 assignment / parameter / slot compatibility
- 是否会误伤 `Variant` / `DYNAMIC` 的 runtime-open 路由：pair 检查必须继续基于 `instanceof GdObjectType`
- lowering / backend 是否仍可复用既有 `BinaryOpInsn` 主路径；smoke 锚点 `object_nil_equality` 与 `object_identity_equality` 是否继续成立

### 7.4 smoke / end-to-end 锚点也属于合同的一部分

object equality 当前不只依赖 shared semantic 与 focused unit tests，还要求以下 smoke 事实继续成立：

- `src/test/test_suite/unit_test/script/smoke/object_nil_equality.gd`
- `src/test/test_suite/unit_test/validation/smoke/object_nil_equality.gd`
- `src/test/test_suite/unit_test/script/smoke/object_identity_equality.gd`
- `src/test/test_suite/unit_test/validation/smoke/object_identity_equality.gd`

这些资源用于锚定：

- typed object / `null` 双向 `==` / `!=` 的端到端结果
- `null == null` / `null != null` 的稳定行为
- 已存在对象与缺失对象的 true/false 分支不被误翻转
- typed object / object identity `==` / `!=` 的端到端结果
- `Node == Object` 与 `Signal.get_object() == node` 的 identity 语义

后续若调整 object equality 支持面，必须同步复核这些 smoke 资源，而不是只更新语义单测。

### 7.5 `not in` 的真实成本高于 alias

`not in` 已按复合规则落地（见 §4.4），其实际成本分布证实了它不能被当成“多映射一个别名”的小修：

- source operator normalization 边界（枚举工厂继续 fail-closed，防止 alias 退化）
- `in` 共享 containment contract 的复用（非法配对诊断锚定 `'in'`）
- root anchor / diagnostic owner 维持（两段式发布，不引入 synthetic 节点）
- compile gate 与 downstream 消费者的一致性（`RESOLVED(bool)` 直接放行；backend unary `NOT` 仅 `bool` 路径约束中间槽类型）
- backend metadata 匹配层对 typed 容器操作数的归一化（`Array[int]` -> plain `Array` 条目；该缺口对普通 typed `in` 同样存在，契约由 `operator_insn_implementation.md` §4.2 承接）

本文档保留这条工程反思，目的是防止后续改动又把它错误简化回 alias。

### 7.6 文档维护约束

后续若继续扩张 unary / binary 支持面，应优先更新：

- 本文档中的当前合同
- `frontend_string_format_operator_implementation.md`（仅当改动触及 `%` / `MODULE` 的 String 左操作数合同）
- `frontend_chain_binding_expr_type_implementation.md`
- `frontend_type_check_analyzer_implementation.md`
- `frontend_compile_check_analyzer_implementation.md`

不要重新恢复阶段拆分、完成清单或执行流水账；阶段过程应留在提交历史，不应回流到 implementation 文档。
