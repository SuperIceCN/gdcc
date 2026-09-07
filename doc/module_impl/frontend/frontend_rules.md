# Frontend Rules

## 恢复约定

- frontend 对普通源码错误必须优先通过 `DiagnosticManager` 发诊断，不要把异常当成常规控制流。
- 当某个 AST 节点树已经无法稳定产生产物时，当前 phase 必须跳过该节点树，并继续处理同一 module 中其他仍可恢复的节点树。
- 对 deferred subtree 的 warning 与 unsupported feature boundary 的 error，都应优先锚定到被跳过子树的根节点；若无法识别更大的恢复根，才允许退化到节点自身这一最小 skipped root。
- 只有 programmer error、共享 side-table 破坏、协议不变量失真等不可恢复 guard rail，才允许抛异常；`FrontendSemanticException` 不作为普通源码错误的主路径。

## 诊断约定

- parser 必须保持 tolerant：`gdparser` lowering diagnostics 映射为 `parse.lowering`，parser/runtime 失败映射为 `parse.internal`，不要把运行时异常直接抛给调用方。
- skeleton / 当前 analyzers / 后续新增 frontend phase 对可恢复错误都必须采用“diagnostic + skip subtree”策略；不要因为单个坏节点打断整条 frontend pipeline。
- 若 skeleton phase 已判定某个 member subtree 必须跳过，必须把该 root 显式发布到 `FrontendAnalysisData.skippedSubtreeRoots()`，并由 scope phase 停止为该 subtree 发布 scope；后续 analyzer 只能沿用既有 skipped-subtree 合同恢复，不得再假设这些节点仍拥有完整 skeleton metadata。
- 新增 frontend 诊断或恢复路径时，必须同步更新 `diagnostic_manager.md`、相关实现注释和受影响的模块文档，避免代码与文档冲突。
- 当前合同中“已识别但明确不支持”的 feature boundary 统一发 error；只有真正的 deferred/暂缓恢复路径才保留 warning。
- body phase 的 diagnostic owner 必须保持单一：
  - top binding 负责 bare `TYPE_META` ordinary-value misuse 的首条 `sema.binding`
  - chain binding 负责 `sema.member_resolution` / `sema.call_resolution` / `sema.static_access_via_instance` / chain deferred/unsupported boundary
  - expr analyzer 负责 `sema.expression_resolution` / `sema.deferred_expression_resolution` / `sema.unsupported_expression_route` / `sema.discarded_expression`
  - var-type-post analyzer 负责 `sema.variable_slot_publication`
  - annotation-usage analyzer 负责 `sema.annotation_usage`
  - virtual-override analyzer 负责 `sema.virtual_override`
  - type-check analyzer 负责 `sema.type_check` / `sema.type_hint`
  - loop-control analyzer 负责 `sema.loop_control_flow`
  - compile-only `FrontendCompileCheckAnalyzer` 负责 `sema.compile_check`
  - statement 位置裸 lambda 语句（`func ...` 作为 statement root，parser 映射为 statement 级 `FunctionDeclaration` / `ConstructorDeclaration`）的 `sema.unsupported_binding_subtree` 由 enclosing suite 的 top binding（`runUnsupported`）持有；interface 记录边界不收录该 declaration 为 callable owner，type-check / compile-only gate 对该子树保持跳过且不补发同级诊断
  - 若同一根源错误已经有 upstream diagnostic，下游 analyzer 只能保留 side-table status，不得再补第二条同级错误
- `FrontendLocalTypeStabilizationAnalyzer` 可以在 slot 写回边界显式拒绝 bare `TYPE_META` ordinary-value initializer，但不拥有诊断；首条 `sema.binding` 仍由 top binding 发出。
- 命中父类 engine virtual 名称的 source method，必须在 shared semantic 路径上通过独立 `FrontendVirtualOverrideAnalyzer` 按 metadata 精确校验：
  - 必须是 instance method
  - 参数个数、参数类型、返回类型必须与 engine virtual 完全一致
  - 错误签名统一发 `sema.virtual_override`
- `sema.virtual_override` 只负责报错，不得把同一函数 subtree 提前记入 skipped roots；函数体后续的 binding / expr / type-check facts 仍必须继续发布，供 inspection/LSP 和 compile precheck 复用。
- compile-only 入口不得把已有的 `sema.virtual_override` 重新包装成 `sema.compile_check`；engine virtual override 的错误签名属于 shared semantic declaration error，compile-only 只沿用已有 error 阻断进入 lowering/backend。
- 普通非-virtual 方法缺失显式类型时，现有 `resolveTypeOrVariant(...) -> Variant` fallback 合同保持不变；只有命中 engine virtual 的 override 路径才额外 fail-closed。
- `break` / `continue` 的位置合法性属于 shared semantic contract；`FrontendLoopControlFlowAnalyzer` 必须在进入 compile-only gate 前就对非法 loop control 发出 `sema.loop_control_flow`，lowering 中的 loop-frame fail-fast 只能保留为实现不变量保护。
- `for-in` 的可迭代性属于 shared type-check contract：`FrontendForLoopSupport.classifyIterableSemantics(...)` 是 plan 构造与诊断的统一分类真源。静态已知不可迭代的 hard type 必须由 `FrontendTypeCheckAnalyzer` 在 iterable expression 上发 `sema.type_check` `Unable to iterate on value of type "X"`；`Variant`、`Object` 与静态未知类型保持 runtime-open，不得误报。该诊断不得阻断 plan 发布或 for body 遍历；iterable 已有 upstream 不稳定事实时不得重复发错。
- `_field_init_`、`_field_getter_`、`_field_setter_` 是 compiler-owned synthetic property helper 前缀；`_lambda_` 是 compiler-owned synthesized lambda function 前缀（并入 `RESERVED_PREFIXES`）。source class member 一旦以这些前缀开头，skeleton phase 必须发出清晰的 `sema.class_skeleton` 并跳过该 member subtree，而不是等到 lowering/backend 再因 helper 名冲突抛异常。
- `_gdcc_coro_state_` 是 compiler-owned **class 级**保留前缀（协程隐藏状态类，canonical name 派生公式 `_gdcc_coro_state_<canonicalClass>__coro__<func>`，见 `gdcc_facing_class_name_contract.md` §1.3）。source class 声明一旦以该前缀开头，skeleton phase 必须发 `sema.class_skeleton` 并跳过该 class subtree；compiler 生成的隐藏状态类不属于用户 source declaration，不经过这条拒绝路径。同理，用户类名（sourceName 或 canonical mapping）不得包含保留序列 `__coro__`。
- GDCC class 新声明的 signal 不得覆盖 inherited engine/native signal；skeleton 必须发 `sema.class_skeleton` 并跳过该 `SignalStatement`。inherited GDCC signal 的 nearest-child shadow 保持允许，不得回退 `ScopeSignalResolver` 合同。
- `FrontendCompileCheckAnalyzer` 只能挂在 compile-only 入口上；默认共享 `FrontendSemanticAnalyzer.analyze(...)`、inspection 与未来 LSP 入口不得隐式附带 compile-only gate。
- compile-only gate 只允许扫描未来 lowering 会消费的 compile surface：supported executable body、supported property initializer island 与已接受 parameter default 表达式根（`parameter.defaultValue() != null` 且根已发布 `expressionTypes`，纯字面量默认不要求 `resolvedCalls`）；不得重新深入被拒绝的 parameter default 子树（无 published facts 的根、或子树 range 跨度内已有阻断性上游诊断的根均整体跳过）、未记录 lambda、statement 位置裸 lambda 语句（executable 深度 > 0 的 statement 级 `FunctionDeclaration` / `ConstructorDeclaration`）、block-local `const` 或 skipped subtree。已记录 lambda（published `FrontendLambdaPlan` + body）放上 compile surface 并递归扫描 body facts。`ForStatement` 已进入 shared semantic，compile-only gate 按 route-aware policy 处理：`ForLoweringContractRegistry` 中已注册的 route 放行并进入 body 重扫 semantic facts，未注册 contract 的 route（当前仅 `OBJECT_CUSTOM`）在 statement root 发 route-not-ready blocker；已注册 route 的 CFG/body lowering 已落地。`MatchStatement` 同样已进入 shared semantic，compile-only gate 按 route-aware policy 处理：`WILDCARD` / `BINDING` / `LITERAL` / `EXPRESSION` 放行并重扫 facts，`ARRAY` / `DICTIONARY` 同样放行并重扫 facts（六 route 全部进入 CFG/body lowering）。
- compile-only gate 一旦放行 supported property initializer，默认 lowering pipeline 必须把它 materialize 为真实 `init_func` helper；backend 不得再把同名 shell-only function 当作可修补中间态消费。
- compile-only gate 对已发布 side-table 事实的最终阻断范围固定为 `BLOCKED` / `DEFERRED` / `FAILED` / `UNSUPPORTED`；`DYNAMIC` 继续保留为 frontend 已认可的 runtime-open 事实，不得在 compile gate 中误判成 blocker。
- executable-body body lowering 对 call 的 lowering-ready surface 必须与 compile gate / CFG builder 保持一致：`resolvedCalls()` 只要已发布为 `RESOLVED` 或 `DYNAMIC` 就允许进入 body pass；其中 `DYNAMIC_FALLBACK` 当前只允许 instance route，必须继续复用 `CallMethodInsn` surface，结果类型真源来自 call anchor 的 `expressionTypes()`，frontend 不得为该路由重做 callable signature 推导。runtime-open 调用本身仍由 backend dynamic dispatch 承接，但其已发布的 `Variant` 结果若继续跨越 ordinary typed boundary，则仍由 frontend ordinary boundary helper 负责后续 `(un)pack`；若同一 call site 还发布了 writable receiver access-chain payload，则 body lowering 必须整体消费这条 payload 做 receiver-side leaf selection / post-call commit，不得把同一条 chain 重新拆成额外 step item 或回头重跑 AST；同时 sequence-item lowering 必须继续线程化当前 continuation block，避免 runtime-gated writeback 把后续 lowering 错挂回原始 lexical block。长合同以 `frontend_dynamic_call_lowering_implementation.md` 为准。
- statement-position、且已稳定解析为 `RESOLVED(void)` 的 ordinary call，是当前唯一允许不发布 standalone result value / 独立 temp slot 的 call surface；其它 value-required call path 不得静默复用这条例外。
- 若未来 type-check / compile gate 回归，让 `var x = print(1)`、`return values.push_back(1)` 这类 value-required `RESOLVED(void)` call 漏进 lowering，`FrontendCfgGraphBuilder` 与 body-lowering materialization/boundary helper 都必须 fail-fast；不得继续发布 `cfg_tmp_* : void`、未初始化读，或任何“看起来还能跑”的漂移行为。
- executable-body assignment final-store lowering 也必须遵守同样的“整体消费 frozen route”合同：对 lowering-ready assignment target，mandatory `AssignmentItem.writableRoutePayload` 必须由 CFG builder 发布，body lowering 只能通过 shared writable-route support 完成 leaf write 与 reverse commit；`targetOperandValueIds` 只继续服务 source-order sequencing 与 compound current-value read，不得再驱动第二套 assignment-target AST replay。长合同以 `frontend_complex_writable_target_implementation.md` 与 `frontend_lowering_cfg_pass_implementation.md` 为准。
- 显式 `SelfExpression` 作为 supported assignment-target receiver prefix 时，必须在 semantic 阶段作为 frozen `expressionTypes()` fact 发布；CFG / body lowering 只能消费该 fact，不得在 lowering 侧补做 `self` receiver type 推断。当前这条支持面只覆盖 plain direct `self.<property> = value`，不把 `IdentifierExpression + SELF`、nested property、container mutation 或 compound assignment 隐式纳入同一合同。
- compile-only gate 还必须检查 lowering-only published fact 的缺洞：若 supported callable-local `var` 或 compile-ready `PatternBindingExpression` 因已登记为 non-error blocker 的 diagnostic（当前为 `sema.variable_slot_publication`）仍缺少 `slotTypes()`，compile gate 必须补发 `sema.compile_check` error 并阻止进入 lowering。无 upstream warning 的缺洞属于协议破坏，由 CFG 跨表验证 fail-fast，不在 compile gate 兜底。
- compile-only gate 的去重规则不得继续写死单个 category；哪些 upstream diagnostic 与 `sema.compile_check` 不冲突、因此允许共存，必须通过静态 category 配置维护。
- compile-only gate 对 direct explicit-self assignment target 的 prefix-owned blocking fact 必须保持 upstream owner：同一 `SelfExpression` exact range 已有 upstream error 时，不得再补 assignment-root 级 generic `sema.compile_check`；只有没有 upstream owner 的 non-lowering-ready self fact 才由 compile-check 在具体 `SelfExpression` anchor 上兜底。
- `assert` 在共享语义路径中继续沿用 Godot-compatible condition contract；compile-only `FrontendCompileCheckAnalyzer` 不再阻断该 statement（lowering/backend 已闭环），不得把它回退成 grammar unsupported。
- 脚本类 `static var` 已进入 compile-ready 支持面：shared semantic 发布 static property 事实与访问路由，lowering 经 `LoadStaticInsn` / `StoreStaticInsn` 读写共享存储，backend 以 C 文件级 backing 变量与两段式全局 static 初始化（先类型默认值、后 source initializer）承接；不再存在 declaration-level compile gate。实例语法访问（`self.x` / `obj.x`）只发 `sema.static_access_via_instance` warning，不阻断编译。完整合同见 `frontend_static_var_implementation.md`。
- `GetNodeExpression` 已离开 temporary compile intercept 列表：shared semantic 在 Node 派生类的非 static 函数体及这些函数体内已记录 lambda 中发布 `RESOLVED(Node)`（static 函数与非 Node 派生类由上游 `sema.expression_resolution` 持有失败事实；property initializer 中的 `$`/`%` 当前为 DEFERRED，由 generic published-fact scan 升级为 compile blocker），CFG 建 `OpaqueExprValueItem` leaf，body lowering 由专用 opaque processor 改写为 `literal_node_path` + `self` 上溯 `Node` 的 `assign` + `call_method "get_node"` 三指令序列（receiver 静态类型钉住 `Node`，GDCC 子类同名 override 不参与 `$` 解析），backend 经既有 ENGINE method-bind dispatch 闭环（见 `frontend_node_literal_implementation.md`）。`PreloadExpression` 已离开该列表：shared semantic 要求路径为字符串字面量并发布 `RESOLVED(Resource)`（非字面量发 `sema.expression_resolution`，路径原样透传、不做编译期归一化），CFG 建 `OpaqueExprValueItem` leaf，body lowering 由专用 opaque processor 改写为 `load_static "@GlobalScope" "ResourceLoader"` + `call_method "load"` 指令对（与合成语言函数 `load` 的改写共享同一指令对，均不落 `call_global`），backend 经 singleton BORROWED 物化 + ENGINE 实例 dispatch（缺省参数 `type_hint`/`cache_mode` 由 backend 物化）已闭环；类级 `const X = preload(...)` 仍随 class-constant 工作流整体拦截（见 MVP 约定），不在本期放行。`ArrayExpression` / `DictionaryExpression` 已离开该列表：shared semantic 发布 `FrontendContainerLiteralPlan`，CFG 建 `ContainerLiteralItem`，body lowering 发射 `construct_container_literal`，backend `ContainerLiteralInsnGen` 已闭环（见 `frontend_container_literal_implementation.md`、`construct_container_literal_implementation.md`）。`TypeTestExpression` 已移除 intercept：shared semantic 发布 `RESOLVED(bool)` 与 `typeTestTargets()`，body lowering 发射统一 `is_instance_of` / 常量 bool，backend 已落地（见 `frontend_is_type_test_implementation.md`）。`CastExpression` 同样已离开 intercept：shared semantic 发布 `RESOLVED(targetType)` 与 `sema.unsafe_cast` / `sema.type_check`，body lowering 按 `ExplicitCastDecision` 发射 assign / pack / `builtin_cast` / `object_cast`（见 `frontend_cast_expression_implementation.md`）。`ConditionalExpression` 也已离开 intercept：shared semantic 发布双臂合并类型（binary 式 root 重持有），CFG value 语境走 branch-result merge、condition 语境走纯控制流展开，body lowering 经 `merge_write` boundary 物化，e2e 已闭环（见 `frontend_conditional_expression_implementation.md`、`frontend_lowering_cfg_pass_implementation.md` §5.1/§5.2）。其余 intercept 表达式仍可走 deferred/unstable facts，但 compile-only gate 必须在进入 lowering 前最终封口。compound assignment 已不再属于这组 temporary compile intercept。
- 共享 `FrontendSemanticAnalyzer.analyze(...)` 的结果不是 lowering-ready 合同；未来 frontend -> LIR lowering 只能以前置的 `analyzeForCompile(...)` 结果为准，并在 diagnostics 无 error 时继续。

## 测试约定

- 每条新的 frontend 恢复规则都必须同时覆盖 happy path 与 negative path。
- negative path 至少要锚定：正确 diagnostic category、坏 subtree 被跳过、同一 module 中其他合法 subtree 仍继续工作。
- engine virtual override 的 compile-fail negative path 应优先锚定到 frontend focused tests，例如 `FrontendVirtualOverrideAnalyzerTest` 与 `FrontendCompileCheckAnalyzerTest`；`test_suite` 只保留 compile / link / run 的正向 runtime 锚点。

## MVP 支持约定

- 下述 MVP 约定描述的是当前 frontend 共享语义、body analyzer 与 compile surface 的正式支持面；它们不否认 parser 与 scope phase 对部分语法结构已经能识别或建图。
- `for` 已进入 frontend shared body semantic 支持面：iterator、ordinary body local、declaration index、typed baseline 与 suite entry 在 typed resolution 前按结构无条件发布，header 解析后通过普通 child-suite path 进入 body。compile-only gate 为 route-aware policy；已注册 lowering contract 的 route 进入 CFG/body lowering，未注册 route 在 statement root 发 route-not-ready blocker。完整合同见 `frontend_for_range_loop_implementation.md`。`lambda` 已进入 frontend shared body semantic 支持面（已记录 lambda 放行，未记录 lambda 保持 fail-closed，合同见 `frontend_lambda_implementation.md`）。`match` 已进入 frontend shared body semantic 支持面：section inventory / pattern bind / declaration index / suite entry 与 `FrontendMatchPlan` 在 typed resolution 前按结构无条件发布；compile-only gate 为 route-aware policy，六 route（`WILDCARD` / `BINDING` / `LITERAL` / `EXPRESSION` / `ARRAY` / `DICTIONARY`）全部进入 CFG/body lowering。完整合同见 `frontend_match_statement_implementation.md`。
- **`ForStatement` scope 双录合同**：`scopesByAst[ForStatement]` 只表示 header 外层 scope；iterator local 与 `FOR_ITERATION_RESOLUTION` slot update 的 scope 必须是 `scopesByAst[forStatement.body()]` 的 **`FOR_BODY` `BlockScope` 对象身份**。`effectiveBinding` / `owningScopeForDeclaration` 对 iterator 声明只能查 `FOR_BODY`，因为 overlay 匹配使用 `scope ==`。细节见 `scope_analyzer_implementation.md` §6.1 与 `frontend_for_range_loop_implementation.md`。
- 普通 executable function 与已记录 lambda body 中的 `await` 已进入 frontend semantic、CFG/LIR、minicoro backend 与 compile-ready MVP：支持静态 Signal、Variant/dynamic、instance 与 static coroutine call（backend `CallStaticMethodInsnGen`）、statement-position fire-and-forget，并按 `frontend_await_implementation.md` §8 分类；lambda 内 await 已闭环（协程 lambda 经 Callable ABI done/suspend 分派、capture 逐调用拷贝入协程帧）。value-position coroutine call、property initializer / parameter default 内 await 仍 fail-closed。`Signal.emit(...)` / `Signal.connect(...)` / `Signal.disconnect(...)` 走既有 builtin `CallMethodInsn` 路径；Object/self、非 Dictionary builtin 实例、GDCC/engine 静态与 utility 值引用 → Callable 已闭环；已记录 lambda → `Callable` 经 `construct_lambda` 已闭环。builtin type-meta / `Dictionary` key / `Node.new` 仍被拒绝。长合同见 `frontend_signal_support.md`。
- path-based `extends`、autoload superclass、global-script-class superclass 绑定不实施。
- 多 gdcc module 的 header superclass 绑定不在最小可行产品范围内。
- source function 参数默认值已进入 shared island 语义与 compile surface（见 `frontend_parameter_default_implementation.md`）：默认值表达式只可见字面量/常量/枚举/类型/单例/builtin 构造器/utility/global/static 调用，instance 方法额外可见 `self` 与实例成员；constructor/`_init`/lambda 参数默认值、默认值内 `await`、对参数/局部变量/capture 的引用仍 fail-closed。
- class constant 的收集、注册、继承可见性与绑定不在 MVP 范围内，整体延后到 MVP 之后再实施。
- callable scope / block scope 中手动声明或发布的类型别名不在 MVP 范围内；frontend body phase 必须对这类 scope-local `type-meta` 采用 fail-closed 的 deferred / unsupported 处理，而不是把它们当成普通 class-like `TYPE_META` 消费。
- H1 subscript MVP 只正式支持 container family 的最小 typed contract：`Array[T]`、`Dictionary[K, V]`、packed array family。
- 上述 container-family subscript 当前统一复用 `FrontendVariantBoundaryCompatibility` 做 key/index typed-boundary 校验；因此 plain `Dictionary`（`Dictionary[Variant, Variant]`）已接受 `String` 等 stable key 写入 `Variant` key slot，`Dictionary[float, V]` 也会因 ordinary `int -> float` boundary 接受 `int` key。`Dictionary[StringName, V]` 与 `Dictionary[String, V]` 的 key boundary 分别通过 ordinary `String -> StringName` / `StringName -> String` constructor materialization 生效。MVP 仍不追求复刻 Godot 更宽的 keyed/index 兼容规则；`Array` / packed array 的 `float` index 仍禁止，因为这需要 `float -> int` 收窄转换。
- builtin instance property access 与 builtin keyed access 必须继续严格区分：`vector.x`、`Color(...).r`、`Basis.IDENTITY.x` 当前属于 compile-ready ordinary property route；`vector["x"]` 仍保持 unsupported。
- builtin keyed access 即使在 extension metadata 中声明了 `isKeyed`，当前也不属于 MVP 支持面；frontend 必须发出显式 `UNSUPPORTED`，而不是猜测 `String` / `Vector*` / `Color` / `Basis` / `Transform*` / `Object` 等 builtin keyed route 的结果类型。
- `DYNAMIC` target 的 runtime-open 处理仍属于 assignment semantic helper 的内聚语义；其他 frontend 路径若只需要 concrete slot 兼容判断，必须调用 `checkAssignmentCompatible(...)`，不要各自硬编码 `Variant` 分支。
- 除 `DYNAMIC` target 的 runtime-open 语义外，frontend 若需要调整 typed boundary compatibility，必须先更新 `frontend_implicit_conversion_matrix.md`，再改 shared helper、测试与下游 materialization；已正式支持的 `int -> float`、同维度 `Vector*i -> Vector*`、`StringName` / `String` 等 widened conversion 不得被某个 consumer 私下改写或扩展。
- builtin 单参数 stable `Variant` constructor 是一条并列的 constructor 合同：shared sema 通过 builtin-only shortcut 接受，body lowering 直接 lower 为 `UnpackVariantInsn`；它不属于 `frontend_implicit_conversion_matrix.md` 的 ordinary typed-boundary widened conversion，也不得再要求 callable signature metadata。
- 上述 builtin unary-`Variant` constructor special route 在 sema 上必须保持“resolved route + warning 并存”：
  - `resolvedCalls()` 继续发布 `RESOLVED(CONSTRUCTOR)`，供 lowering/compile-check 消费
  - bare direct constructor call 同时发 `sema.unsafe_call_argument` warning，明确这是 runtime-open 的 `Variant -> concrete builtin` 转换
- source-level `if` / `elif` / `while` / `assert` condition 当前采用 Godot-compatible 合同：frontend 只要求 condition root 已稳定发布 typed fact，不再把非 `bool` 一概当作 `sema.type_check`。
- `frontend.lowering.cfg` 中 `FrontendIfRegion` / `FrontendElifRegion` / `FrontendWhileRegion` 的 `conditionEntryId` 表达的是整个 condition subgraph 的稳定入口；consumer 与测试都不得假设固定 `SequenceNode -> BranchNode` 两节点模板。
- `FrontendCfgGraph.BranchNode.conditionRoot` 表达的是“当前 branch 直接测试的 condition fragment root”，必须与 `conditionValueId` 的直接 producer subtree 对齐；它不保证等于外围 source-level condition 的最外层根，也不承诺可以仅凭 `conditionValueId` 从整个 condition region 中反推出唯一一个 producer item。
- short-circuit lowering 现已要求每个 `BranchNode.conditionValueId` 都保持为当前 fragment 自己计算出的 branch-local 独立 value id；不得复用 value-context `and` / `or` 或 `ConditionalExpression` 的 outward-facing merge result value id 作为 branch condition id。
- frontend CFG value id 默认仍是 single-definition 合同，但有一个刻意保留的例外：同一个 outward-facing merged result value id 可以由多个 `MergeValueItem` 在互斥路径上写入。
  - 若同一个 value id 出现多个 producer，则所有 producer 都必须是 `MergeValueItem`；`MergeValueItem.resultValueId` 不允许与 `OpaqueExprValueItem`、`CallItem`、`CastItem`、`BoolConstantItem` 等普通 producer 共享同一个 value id。
  - 任何按 value id 收集 producer 的代码、测试或 future lowering 都必须按“可能有多个 reaching producers”建模；不得把 merged result 当作可唯一反查的 SSA expression definition。
- merge 槽类型以 `mergeAnchor` 的 `expressionTypes` 事实为准（同一 `resultValueId` 的多生产者共享同一 anchor，天然无类型冲突）；`MergeValueItem.sourceValueId` 正常需同 `SequenceNode` 内先产，窄例外见 `frontend_lowering_cfg_pass_implementation.md` 的 merge-of-merge 合同（全图 `MergeValueItem`-only 源可跨 sequence）。
- `assert` 已解除 compile-only block：source-level condition 合同不变；message 的 String 直接可赋值校验是新增的显式 `sema.type_check` 合同，而非对 condition 规则的反向污染。
- backend/LIR 的 control-flow 仍保持 bool-only 边界；truthiness / condition normalization 由 lowering 侧显式完成，不得反向把 frontend 收紧成 undocumented strict-bool dialect。
- lowering 侧的 condition normalization 合同已经冻结：`bool` 直接消费，`Variant` 只做 `unpack_variant -> bool temp`，其余 stable type 必须先 `pack_variant` 再 `unpack_variant`，不得绕过这条路径。唯一实现点是共享 helper `FrontendBodyLoweringSupport.materializeTruthinessToBool`（branch processor 消费后自行 `GoIfInsn`，assert processor 消费后发射 `AssertInsn`；helper 自身不设置 terminator）。
- body lowering 的 slot/materialization 命名必须固定：temp-backed CFG value 继续用 `cfg_tmp_<valueId>`，merge-backed value 继续用 `cfg_merge_<valueId>`，source-level local 直接沿用源码名；direct-slot alias value 与 statement-position resolved-void `CallItem` 则故意不声明独立 `cfg_tmp_*` 变量。
- merge 写入（`merge_write`）继续复用 `materializeFrontendBoundaryValue(...)` 唯一入口：`FrontendMergeValueInsnLoweringProcessor` 将每臂 `sourceValueId` 的类型物化到 `mergeAnchor` 的 published 合并类型后 `AssignInsn(cfg_merge_<id>, materialized)`；`bool->bool` 为 `ALLOW_DIRECT`，故 value 语境 `and/or` 的 LIR 仍仅 `LiteralBoolInsn` + `AssignInsn(cfg_merge_*, cfg_tmp_*)`。
- `OpaqueExprValueItem` 当前只允许承载 ordinary leaf / eager unary / 非短路 eager binary / `PreloadExpression`（后者由专用 opaque processor 改写为 ResourceLoader singleton 调用对）；`and` / `or`、assignment-as-opaque、以及绕过 dedicated item 的 attribute / call / subscript 必须视为协议违例。direct-slot mutating receiver 的 alias publication 现已通过独立 `DirectSlotAliasValueItem`。
- direct-slot receiver alias 的安全性必须写成显式语义合同，而不是“扫描参数 AST 里有没有某个节点名”：
  - explicit `SelfExpression` 可直接 alias，因为 `self` slot 不可被用户代码重绑定
  - `LOCAL_VAR` / `PARAMETER` root 只有在后续 argument subtree 全部落在 proven no-rebinding 子集时才允许 alias
   - `CAPTURE` 当前不在 alias root 支持面内；lambda/capture lowering 与 storage semantics 已落地（`construct_lambda` + capture block），但 capture-backed live-slot alias surface 仍未开放，不能提前承诺
  - `IdentifierExpression + SELF` 不是合法 alias/input surface；当前 analyzer 只会对 explicit `SelfExpression` 发布 `SELF`，所以 builder 与 body lowering 都必须 fail-fast，不能把它静默恢复成 `"self"`
  - `receiverValueIdOrNull == null` 的 implicit self fallback 仍是另一条 call execution 路径，不能和 explicit `SelfExpression` 或非法 `IdentifierExpression + SELF` 混为一谈
  - nested `CallExpression`、`AttributeCallStep`、以及其它 effect-open / 未分类 argument surface 必须回退 ordinary temp snapshot，避免 future rebinding form 静默穿透 alias
- `frontend.lowering.cfg` 中用于 opaque value 的 route helper 必须返回非空 carrier；carrier 内的 nullable
  payload 才表达“这个 ordinary value 没有 writable route”。`FrontendBinding` 可作为 builder 内部 provenance
  保留，但 binding kind 不等于完整读写语义，不能直接扩散到 `FrontendWritableRoutePayload` 或 CFG public item。
- value-context `and` / `or` 的 LIR 形态必须保持为“branch + branch-local bool constant + merge slot assign”；不得生成 `BinaryOpInsn(AND/OR)`。
- `FrontendTopBindingAnalyzer` 当前只发布 symbol category，不区分 read / write / call 等 usage 语义；assignment 左值链头等 use-site 也可能进入 `symbolBindings()`。
- 若后续 frontend 需要记录完整用法，必须扩展 `FrontendBinding` 模型，不要依赖当前 binding kind 反推读写调用语义。
- `ScopeValue.writable` 当前只表达 bare identifier direct-write contract；不要把它误当成完整的 member/container/property mutation 语义模型。
- property writable 判定必须统一复用共享 helper，而不是在 scope publication、assignment analyzer、其他 frontend 路径里各自硬编码 engine/builtin property metadata 分支。
- property initializer 的 MVP 支持面是“published subtree facts”，不是“完整 class-member initializer 语义”。
- supported property initializer 当前已经属于 compile-ready lowering surface：它们在默认 pipeline 终态拥有真实 CFG/LIR helper body，但这不等于完整实例初始化时序语义已经闭环。
- 脚本类 static property declaration 同样属于 compile-ready 支持面：declaration 与 supported static initializer 直接进入普通 compile surface，由 module 级两段式全局初始化承接（先类型默认值、后 source initializer），不走实例 property 的 constructor-time apply。
- MVP 不支持 property initializer 访问同 class 下的 non-static property / method / signal / `self`；这类访问必须 fail-closed，而不是假装已经拥有 declaration-order / default-state / cycle-aware 语义。
- property initializer 若确实需要静态 helper，优先通过 global name、type-meta route 或其他不依赖当前类实例状态的路径进入；不要把当前类 direct member namespace 当成实例初始化期可见性模型。
- property `:=` 在 MVP 中不支持类型推导，也不会因为 RHS 稳定类型而回写 class property metadata。
- property `:=` 与未声明显式类型 property 在 type-check 中仍按普通 initializer expression 处理；若 RHS 已稳定，type-check analyzer 需要发 `sema.type_hint` warning，提示用户手动补写建议的显式类型。
- `sema.type_hint` 的职责是提醒用户手动添加显式类型，而不是暗中把 property 当成已经推导完成的 typed slot。
- `@onready` 的 MVP 合同当前是“annotation retention + usage validation”，不是完整 ready-time 执行模型。
- `@onready` 的最小合法性规则固定为：只能用于 Node 派生类中的 non-static class property；相关非法用法由独立的 `sema.annotation_usage` owner 负责，不应混入 `sema.unsupported_annotation` 或 `sema.type_check`。
- `@tool` 的合同固定为 script 级语义（Godot `AnnotationInfo::SCRIPT` 对齐）：仅顶层 `SourceFile` 上的零参 `@tool` 合法，skeleton 把该文件顶层与全部 inner `LirClassDef` 置 `isTool=true`（顶层额外写 `tool` class annotation）；inner class / member / 尾随位置的 `@tool` 与带参 `@tool` 一律由 `sema.annotation_usage` 拒绝。backend 侧的效果只覆盖帧循环 gate（非 tool 类在编辑器内不执行 `_process` / `_physics_process`），其余回调的编辑器内抑制不在 MVP 内。
- `@export*` 家族（裸 `export` 与 `export_range` / `export_enum` / `export_flags*` / `export_file*` / `export_dir` / `export_global_*` / `export_multiline` / `export_placeholder` / `export_exp_easing` / `export_color_no_alpha` / `export_node_path`）的合同固定为：参数只接受字面量（含数值一元 `+`/`-`），经共享 helper `FrontendExportAnnotationSupport` 求值并编码为 Godot `hint_string` 写入 property annotation value；挂载位置（仅 class `var` property）、static 拒绝、参数与类型兼容校验统一由 `sema.annotation_usage` owner 发出，skeleton 对 malformed 参数只 retention 不发诊断。容器声明类型上的所有 export variant 一律拒绝（不做 Godot 的 Array peel），裸 `export` 不受限；`export_category` / `export_group` / `export_subgroup` / `export_storage` / `export_custom` / `export_tool_button` 仍走 `sema.unsupported_annotation`。
- 全局枚举成员（如裸 `TYPE_NIL` / `OK`）、全局常量与 GDScript 语言常量（`PI` / `TAU` / `INF` / `NAN` 及合成极值常量 `INT*_MIN/MAX` 等）的裸访问已进入 compile-ready 支持面：sema 发布 `CONSTANT` binding 与 `int` / `float` 表达式类型，body lowering 物化为 `literal_int` / `literal_float`；局部/类作用域遮蔽规则不变。`match` 进入 shared semantic 后，pattern 内的全局常量/枚举裸访问走普通 `EXPRESSION` 合同，不再 deferred。限定式 `Variant.Type.TYPE_NIL` 等 chain 路径不受影响。
