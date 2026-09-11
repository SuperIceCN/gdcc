# Frontend super 调用实现合同

- 状态：当前冻结合同
- 更新时间：2026-09-10
- 适用范围：`super.m(...)` 与裸 `super(...)` 两种 GDScript super 调用形态的前端解析、事实发布与 lowering。
- 关联文档：
    - `../backend/virtual_override_vtable_implementation.md`（Step 6/7 为本文的实施记录；§2.5 为后端 `CALL_SUPER_METHOD` 合同）
    - `frontend_resolution_pipeline_implementation.md`（阶段 owner 边界）
    - `frontend_dynamic_call_lowering_implementation.md`（published fact 消费合同）
    - `superclass_canonical_name_contract.md`（canonical 父类名来源）

## 1. 目标与职责边界

`super` 是**词法父类调用**语义：目标从词法当前类的 canonical 父类起解析最近祖先实现，绕过虚分派（含 GDCC vtable）。前端负责把两种语法形态解析为 `FrontendCallResolutionKind.SUPER_METHOD` 事实；lowering 据此发射 `CallSuperMethodInsn`；目标 owner 的 C 代码生成由后端 `CallSuperMethodInsnGen` 完成。

- parser（gdparser 0.5.3）**没有**专用 super AST 节点：`super.m(args)` 映射为 `AttributeExpression(IdentifierExpression("super"), [AttributeCallStep])`，裸 `super(args)` 映射为 `CallExpression(IdentifierExpression("super"), args)`。前端不得以新建 AST 节点的方式实现 super。
- `super` 是关键字位置标记，不是值符号：top binding 发布 `FrontendBindingKind.SUPER`（无值负载），super 标识符本身不进入值命名空间竞争。

## 2. 支持的语法形态与边界

| 形态 | 处理 | 事实键 |
|---|---|---|
| `super.m(args)` 链式调用 | chain binding 阶段 step-0 拦截（`reduceSuperCallStep`） | `AttributeCallStep` |
| 裸 `super(args)`（隐式同名方法） | 表达式解析阶段由 body owner 拦截，方法名取 `context.callableOwner()` | `CallExpression` |
| `super.m(...)` 调用结果继续链式（`super.m().x`） | step 0 之后的后缀按调用结果类型走普通链式归约 | 各步自有事实 |
| `super.prop`（链式属性步） | FAILED（"super only supports method calls; property access ..."） | `AttributePropertyStep`（member 事实） |
| `super.payload[0]`（链式下标步） | FAILED（"super only supports method calls; subscript access ..."） | 无 member/call 事实，经根表达式传播 |
| 裸 `super` 值位置（`var x = super`、`foo(super)`、`super[0]` 下标基位置） | compile-check 位置门禁报错 | 无（binding 事实） |
| static 函数内的 super | top binding 报错（镜像 `self` 规则）；链式形态仍发布 BLOCKED 精确事实 | 同左 |
| 属性初始化器内的 super | fail-closed（`FrontendPropertyInitializerSupport.superBoundaryDetail`） | 无 |
| `super._init(...)` / `_init` 内裸 `super(...)` | FAILED：GDCC 构造器自动链式调用父类 `_init`（`entry.c.ftl` `class_constructor`），显式调用会双跑 | 各调用键 |
| lambda 内裸 `super(...)` | FAILED（lambda 无名可借）；lambda 内 `super.m(...)` 合法且触发 self 捕获 | 各调用键 |

## 3. 解析合同

- **解析起点**：`GdObjectType(当前类 canonical 名)` 的 `getSuperName()`。
- **词法 super 查找**（`ScopeMethodResolver.resolveNearestDeclaredInstanceMethod`，前后端共用）：沿父链向上，**遇到第一个声明该方法名的 owner 即停止**，仅在该 owner 的候选内做参数匹配——参数不适用的近端声明必须报错，不得跳过它去绑定参数恰好适用的远端声明（Godot `get_function_signature` 语义）。后端 `BackendMethodCallResolver.resolveSuper` 使用同一入口，前端发布的目标与后端发射的 owner 不可能漂移。
- **参数适用性 rank**：链式复用 `literalAwareParameterRank`；裸调用经 `FrontendCallableLiteralArgumentSupport.parameterCompatibilityRank` 构建等价 rank。
- **fail-closed**（`FrontendSuperCallSupport`，链式/裸调用共用）：无父类、`_init` 目标、DynamicFallback、static 目标、GDCC abstract 目标均失败。**engine virtual 的 abstract 标记不在拒绝范围**——engine 侧 abstract 是 virtual hook，默认空实现仍可经 super 调用（如 `super._ready()`）。
- **发布事实**：`SUPER_METHOD` + `INSTANCE` receiverKind + receiverType = 词法当前类（**不是**父类类型；后端词法 self 不变量要求 receiver 静态类型恰为 `bodyBuilder.clazz()`）+ 父类方法的 returnType/ownerKind/declarationSite + `exactCallableBoundary`（`FrontendResolvedCall` 已对 SUPER_METHOD 放行）。
- **pending 事实视图**：chain binding 阶段 top-binding 事实尚未 flush，super 链头检测必须走 `ReductionRequest.bindingLookup`（pending 感知），禁止读稳定表 `analysisData.symbolBindings()`。
- **类型发布**：super 标识符本身发布当前类实例类型（与 self 同）；其值位置合法性由 compile-check 门禁而非类型系统负责。

## 4. Lowering 合同

- `FrontendCallInsnLoweringProcessor` 的 `SUPER_METHOD` 分支发射 `CallSuperMethodInsn(result, name, receiver, args)`：receiver 经 `materializeCallReceiverLeaf`（链式 = super 标识符 opaque 物化为 self 别名 temp；裸调用 = 隐式 `self` 槽）。
- super receiver 不参与逆提交 writeback（self 别名写回恒等；`FrontendCallMutabilitySupport` 对非 `INSTANCE_METHOD` 天然返回 false）。
- opaque 标识符 lowering：`SUPER` binding → `AssignInsn(slot, "self")`；CFG 可写路由发布中 SUPER 归 null payload 组（只读别名）。
- coroutine super 调用复用既有 `isPublishedCoroutineCall` + `emitCoroutineDetachIfNeeded` / await 路径（declarationSite 为父类 `LirFunctionDef`）。
- lambda 内的 super 调用依赖 `FrontendVariableAnalyzer` 的捕获扫描：`super` 标识符视同显式 `self` 使用，触发 enclosing 实例捕获。

## 5. 测试锚点

- `FrontendSuperCallSemanticsTest`（19 例，注意：compile 门禁用例必须走 `analyzeForCompile`，compile-check analyzer 不在普通 `analyze` 路径运行）。
- `FrontendSuperCallSupportTest`（11 例单元：源码无法表达的 GDCC abstract/未注册父类/无父类拒绝、静态目标拒绝、engine virtual hook 放行、最近祖先解析、失败消息锚定、stop-at-first-declarer 两条负例——近端参数不适用声明不得被跳过）。
- `FrontendSuperCallLoweringTest`（3 例：链式/裸/engine 父）。
- runtime fixture `runtime/virtual/super_method_dispatch.gd`（多态层级绕过 vtable、跨祖父解析、裸 `super()`）。

## 6. 已知限制（MVP）

- 不支持显式 `super(...)` 父类构造调用（与 Godot 语义分歧，因 GDCC 构造自动链；后续评估见 `gdcc_backend_todo.md` 登记）。
- super 目标为 GDCC abstract 方法时 fail-closed（engine virtual hook 除外，见 §3）。
