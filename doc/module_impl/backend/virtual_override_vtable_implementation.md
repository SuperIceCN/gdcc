# GDCC 虚函数覆写 / vtable / CALL_SUPER_METHOD 实现

## 0. 文档状态

- 状态：**已实施，长期事实源**。vtable 规划与 C 布局、实例初始化、engine virtual 父类转发、`CALL_METHOD` 多态分发、`CALL_SUPER_METHOD`（后端 + 前端 super 语法）全部落地。
- 本文只描述当前代码事实与长期合同，不记录实施过程；修改相关代码时必须同步修订本文。
- 关联文档：
    - `doc/module_impl/frontend/frontend_super_call_implementation.md`：前端 super 语法合同（绑定 / 语义 / lowering）。
    - `doc/module_impl/backend/explicit_c_inheritance_layout_contract.md`：wrapper `_super` 偏移 0 嵌入布局与禁裸 cast 合同。
    - `doc/module_impl/backend/call_method_implementation.md`：`CALL_METHOD` 分派模式总表。
    - `doc/module_impl/frontend/frontend_engine_virtual_override_implementation.md`：engine virtual 覆写的前端精确签名合同。
    - `doc/gdcc_low_ir.md`：`call_super_method` 指令语义。
    - `doc/module_impl/frontend/superclass_canonical_name_contract.md`：canonical 类名与模块边界。
- 实现范围（需求编号 R1–R5）：
    - R1/R2：engine virtual 的 `get_virtual_with_data` / `call_virtual_with_data` 沿 GDCC 类链父类转发，最派生覆写正确分派（§5）。
    - R3：被模块内子类覆写 / 覆写父类的 GDCC 方法生成前缀兼容的 vtable（§2–§4）。
    - R4：`CALL_METHOD` 调用点按"receiver 静态类型的模块内真后代是否覆写"选择 vtable 间接调用或直调（§6）。
    - R5：`CALL_SUPER_METHOD` 代码生成与前端 super 语法（§7）。

## 1. 术语与角色定义

- **polymorphic method**：GDCC 类 `C` 的实例方法 `m` 是 polymorphic，当且仅当模块内存在 `C` 的真后代类声明了同名且签名兼容（§2）的实例方法。只有 polymorphic 方法占 vtable slot。
- **slot / introducer / final overrider**：slot 是一个 polymorphic 方法的 vtable 身份，由首次需要它的类（introducer）引入；某类的 slot 条目指向 final overrider——从该类沿链向上到 introducer 之间最近的非排除声明。
- **层级（hierarchy）**：一个 GDCC 根类（直接继承 engine 类的类）及其模块内全部后代。
- 角色谓词（全文只允许用这套词汇描述类的 vtable 角色）：
    - `slotted(C)`：`slots(C)` 非空（含继承 slot）；
    - `introducesSlot(C)`：`C` 自己追加了至少一个新 slot；
    - `pass-through(C)`：slotted 但既不引入也不覆写任何 slot；
    - **旁支（side branch）**：层级含 slot 但 `!slotted(C)`；**整层无 slot**：层级无任何 slot。旁支与整层无 slot 都不得称为 pass-through。
- **两条 `_super` 链严格互斥**（混淆会生成不存在的成员访问）：

| 链 | 成员类型 | 是否跳过非 introducer 类 | 用途 |
|---|---|---|---|
| wrapper `_super` | 永远是**直接父类** wrapper | 否 | 结构体嵌入、accessor 取根 `_vtable` 字段、fat self 上行 |
| vtable `_super` | **最近 introducer 祖先**的 vtable typedef | 是 | vtable typedef 前缀嵌入、slot 继承 |

## 2. vtable 规划合同（`CVtablePlanner`）

`src/main/java/gd/script/gdcc/backend/c/gen/CVtablePlanner.java`：纯分析（不发射 C），构造期完成全部规划并 fail-fast；经 `CGenHelper.vtablePlanner()` 暴露只读查询。

- **GDCC↔GDCC 签名兼容**（`checkGdccOverrideSignature`，不复用 `VirtualMethodInfo.checkOverrideSignature`——后者不查 coroutine 且只跳单侧 self）：两侧均非 static / hidden / lambda；vararg 形状一致；coroutine 标记一致；各自去掉 leading `self` 后参数个数、参数类型、返回类型**严格相等**（类型等值，不用 assignability）。协变返回放宽属未来工作。
- **排除项**：`_init`、static、hidden、lambda 方法不占 slot、不参与 polymorphic 判定、不触发冲突；排除声明会**断开覆写链**（其下同名方法视为全新声明，不是覆写）。
- **fail-fast（D1）**：后代声明同名但签名不兼容的未排除实例方法 → `CodegenException`（含两类名与方法名），因为该声明否则会被装进祖先 slot 并按错误签名调用。具体（非 abstract）类的继承 slot 最终无实现 → `CodegenException`；abstract 类自身保留 `NULL` 条目。排除声明断开覆写链后，更深的同名 polymorphic 新声明若与仍存活的继承 slot 撞名 → `CodegenException`（同一 vtable struct 不能有两个同名字段，须改名其一）。继承环 → `IllegalStateException`。
- **容错规则**：GDCC 父边仅当父类**同时**在模块列表与 `ClassRegistry` 中注册时才承认；否则该类按**层级根**处理（继承前缀为空，不抛错——自身仍可因模块内真后代覆写而引入 slot）。生产路径在 `CCodegen.prepare` 先注册整个模块，但大量测试直接 `new CGenHelper(...)`，registry 不完备。
- **slot 分配**（按 base-before-derived 序逐类）：`slots(C)` 以 `slots(parent)` 的有序拷贝为前缀（前缀兼容由此保证）；兼容覆写复用继承 slot；polymorphic 新声明按声明顺序追加。兄弟分支诱导的继承 slot 必须留在 pass-through 类上——不得改写成"只保留本类子树用到的 slot"，否则旁支实例会以 `NULL` 表值被间接调用空解引用。
- **调用点谓词**（`isPolymorphicCall(receiverTypeName, methodName)`）：receiver 静态类型 `T` 的模块内**真后代**覆写该方法时才需要间接分发。它同时覆盖去虚条款：`T` 是最终覆写者（无真后代再覆写）→ 直调。两个不得用作闸门的谓词：`m ∈ slots(O)` 过宽（兄弟分支与最终覆写者都携带继承 slot）；`O` 的后代覆写 `m` 过宽（receiver 静态类型已收窄到 `T` 的子树）。
- **单向蕴含不变量**：`isPolymorphicCall(T, m)` 命中 ⇒ `findVtableSlot(O, m)` 必非空（`O` 是从 `T` 出发的最近声明者）；反向不成立，**禁止用 `findVtableSlot` 命中充当间接分发闸门**。
- 完备性依据：GDCC 类的父类只能是同模块 GDCC 类或 engine 类，模块内后代闭包即全部可能覆写者。

## 3. vtable C 布局合同

模板落点：`src/main/c/codegen/template_451/entry.h.ftl`（typedef / 根字段 / accessor 声明）、`entry.c.ftl`（trampoline / 实例 / accessor 定义）；渲染单一发布点为 `CGenHelper` 的 "Vtable symbols and layout" 区段（模板禁止本地拼写这些符号）。

- **vtable typedef**：只有 `introducesSlot(C)` 的类拥有 `gdcc_<C>_vtable` typedef；父表**按值嵌入为首成员**（vtable `_super` 链，跳过 pass-through 与仅覆写类），typedef 按 base-before-derived 序生成（父表先完整）。继承 slot 只在引入者层段声明一次，经 `->_super` 前缀链到达。slot 函数指针签名以 introducer 的方法 C 签名为准（self 为 introducer fat 类型）。禁止"扁平复制字段再跨类型 cast"（strict-aliasing UB，且违反布局合同的偏移 0 真实首成员条款）。
- **根类 `_vtable` 字段**：仅当层级含至少一个 slot 时，根类结构体在 `GDExtensionObjectPtr _object;` 之后插入 `const void* _vtable;`；物理上只存在于根段，子类经 wrapper 偏移 0 嵌入共享。`const void*` 回避空 struct 可移植性与 typedef 顺序问题。
- **accessor**：每 introducer 类一个 `static inline const gdcc_<C>_vtable* <C>_class_vtable(<C>* self)`，沿 **wrapper 链**直达根字段（跳数 = 到根的 wrapper `_super` 层数），**不递归父 accessor**（父可能是 pass-through 而无 accessor）、不走 vtable `_super` 链。安全性：动态类型无论是 introducer / 仅覆写 / pass-through，`_vtable` 指向的对象其类型都是某 introducer typedef，目标前缀子对象真实存在且地址相同（C17 §6.7.2.1 初始成员指针互转，可递归应用于嵌套首成员）；对齐满足（派生表对齐 ≥ 前缀）。
- **vtable 实例**：条目 = final overrider，C99 嵌套指定初始化器逐层填。`introducesSlot(C)` → 自有类型实例 `gdcc_<C>_vtable_inst`；仅覆写不引入 → 以最近 introducer 祖先 typedef 为类型的实例（**无 typedef/accessor**，避免纯别名类型）；pass-through → 不生成实例，`_vtable` 共享最近非 pass-through 祖先的同一表值。final overrider 即 introducer 自身 → 直填 `<C>_<m>`；否则填 trampoline；abstract 引入的 slot 在 abstract 类表内填 `NULL`。
- **trampoline**：每（覆写类 D × 非引入 slot m）一对 `static` 函数 `gdcc_<D>_vslot_<m>`，签名取 slot 签名，体内以复合字面量把 introducer fat self 下行转换为 D 的 fat self 后直调 `<D>_<m>`。该下行转换是"禁止裸 C cast 表达 GDCC 上下行"合同（`explicit_c_inheritance_layout_contract.md`）的**唯一例外**（经 D 的 vtable 到达时动态类型必为 D 或其后代，偏移 0 嵌入使转换无指针调整）；上行仍禁止裸 cast。
- **coroutine slot**：slot 存 coroutine **start thunk**（`godot_Object*` 返回）而非 impl 本体；trampoline 同理适配；链上 coroutine 标记一致性由签名兼容检查强制。
- **发射顺序**：trampoline 与实例段按 `inheritanceOrderedClassDefs`（base-before-derived）发射——子类实例会取**祖先** trampoline 的地址（祖先仍是继承 slot 的 final overrider 时），而 static trampoline 无头文件原型，必须定义先于使用；同类内 trampoline 先于本类实例。

## 4. 实例创建与 `_vtable` 初始化

`<C>_class_create_instance`（`entry.c.ftl`）在 `godot_mem_alloc` 之后、POSTINITIALIZE 通知（触发用户 `_init` 的构造链）之前写入 `_vtable`，保证 `_init` 内的虚调用已经走有效表。四分支（与角色谓词一一对应，RHS 由 `CGenHelper.renderVtableFieldInitExpr` 给出）：

1. 整层无 slot → 根类无 `_vtable` 字段，不生成赋值；
2. 旁支 → 写 `NULL`（命中 slot 的调用其 introducer 必 slotted，本类实例的方法解析不会落到任何含 slot 的 owner，NULL 永不被读）；
3. `slotted(C)` 且（引入或覆写）→ 写 `&gdcc_<C>_vtable_inst`；
4. pass-through → 写最近非 pass-through 祖先解析到的同一表值（**禁止写 NULL**：该实例经祖先静态类型间接调用时会真实读取此字段）。

字段访问链沿 **wrapper 链**求值（镜像 `_set_object_ptr` 递归）。每个类的 create_instance 写入**按本类解析**的表值——实例化哪个类就由哪个类的 create_instance 执行，父类 create_instance 不参与。

## 5. engine virtual 父类转发合同

外部机制事实（对齐依据）：

- engine 只查询**最派生** extension 类登记的 virtual 回调（`godotengine/godot` `core/object/object.cpp` `Object::_gdvirtual_init_method_ptr`：回调由 `object_set_instance` 时使用的类的 creation info 决定），因此"子类未覆写时向父类查找"必须由扩展侧自己实现。
- godot-cpp 的参照模式：`godotengine/godot-cpp` `src/core/class_db.cpp` `ClassDB::get_virtual_func` 沿 **extension 类链**逐级查找（native 父类不在此处，engine 对 `nullptr` 走自身 fallback）。
- 回调配对 ABI：`GDExtensionClassGetVirtualCallData2` 返回的 `void*` 由扩展管理、有效期到扩展反初始化；engine 随后用同一 `p_name` + 该数据调 `GDExtensionClassCallVirtualWithData`。`p_hash` 是 compatibility hash，GDCC 不依赖它判定 override（`(void)p_hash`）。

GDCC 侧合同（`entry.c.ftl`）：

- `<C>_class_get_virtual_with_data` 尾部：本类覆写按名匹配（现状不变）之后，父类是 GDCC 类时 `return <P>_class_get_virtual_with_data(...)`；父类是 engine 类时保持 `return NULL;`。
- `<C>_class_call_virtual_with_data` 尾部：全部本类 userdata 分支之后，父类是 GDCC 类时追加 `<P>_class_call_virtual_with_data(p_instance, ...)` 逐级 fall-through。
- 正确性：engine 固定调用最派生实例的回调，父类 userdata 会回到子类的 dispatch，必须逐级转发；`(Parent*)p_instance` 经偏移 0 嵌入合法。`_process`/`_physics_process` 非 tool 类的 `gdcc_is_editor_hint()` 抑制门留在**本类命中分支内**（转发发生在所有本类分支之后，父类命中后执行父类的门）。
- userdata 协议跨类一致：userdata 地址即方法身份（默认参数覆写用 per-method 独占 userdata 实例，否则用 impl 地址），ClassDB 注册与 virtual 分派共享同一协议。
- **双通道一致性**：engine virtual 的 GDCC↔GDCC 覆写链上，engine 通道（本节，不经 vtable）与内部调用通道（§6，经 vtable slot）对同一实例解析到同一最派生实现。editor 门只存在于 engine 通道——内部显式 `self._process(delta)` 不经门（与 GDScript 显式调用语义一致：门抑制的是引擎帧回调，不是方法本体）。engine virtual 覆写签名精确性由前端 fail-closed 保证，链上覆写必然签名一致，不会触发 D1。

## 6. `CALL_METHOD` 多态分发合同

落点：`src/main/java/gd/script/gdcc/backend/c/gen/insn/CallMethodInsnGen.java`（`emitKnownSignatureCall` / `emitPolymorphicCall`）、`CBodyBuilder`（`callVoidVtableSlot` / `callAssignVtableSlot` / `requireVtableSlotCalleeExpr`）、`CGenHelper.isPolymorphicCall` / `findVtableSlot` / `renderVtableSlotCalleeExpr`。

- **闸门**：`resolved.mode() == GDCC && !resolved.isStatic() && helper.isPolymorphicCall(receiverVar.type(), methodName)`，且必须位于 coroutine 分流**之前**（polymorphic coroutine slot 也走 vtable）。static 经实例语法调用维持 warn 直调，不查 vtable；ENGINE / BUILTIN / 动态路由完全不变。
- **间接路径**：
    1. receiver 物化**一次**为 slot **introducer** `I` 的 fat self 临时变量（`renderReceiverValue` 复用既有 `_super` 链安全 upcast；`I` 是 receiver 静态类型的祖先，assignable 恒成立）——避免 callee 与首参双重求值，且 slot 函数指针的 self 是 `I` 的 fat 类型，owner/子类 fat 无法通过 C 类型检查；
    2. callee 表达式 `<I>_class_vtable($vt_recv.ptr)->m_<method>`：accessor 与 slot 成员同在 introducer 层段，调用点**不做任何 vtable `->_super` 导航**；
    3. 缺参补全 / vararg / 结果写入复用直调共享流程，首参为 `$vt_recv`；实例 `default_value_func` 仍收**原始 receiver** 按 **owner** 类型渲染（缺省参数属于静态解析出的 owner 签名）；
    4. coroutine 命中后走 start thunk 间接变体，结果目标仍是 `compiler::GdccCoroState`。
- **`CBodyBuilder` vtable slot API**：callee 由 `requireVtableSlotCalleeExpr(slot, vtRecv, args)` 内部构造并 fail-fast 校验（`vtRecv` 必须承载 introducer fat 类型、调用首参与 `vtRecv` 为同一对象）——调用者只提供 slot 与物化 temp，callee 与首参单一来源派生。与 `callVoid`/`callAssign` 的差异仅两点：不做 `recordUsedGodotBindingCall`（callee 是 vtable 槽成员表达式而非 `godot_*` binding 符号）；对象返回按内部 FAT_PTR 产物处理（vtable 槽指向内部 GDCC 函数，等价于直接 GDCC 调用的 `PtrKind.FAT_PTR` 路径）。
- receiver→introducer 的 fat upcast helper 经 `CObjectFatPtrCollector` 既有通道收集（receiver 静态类型与 introducer 类型都是模块类/变量类型），无需专门机制。
- 去虚正确性锚点：最终覆写者 receiver 直调；兄弟分支 receiver 直调（slot 存在但调用点不间接）；pass-through 实例经祖先静态类型间接调用读共享表值，不空解引用。

## 7. `CALL_SUPER_METHOD` 合同

落点：`src/main/java/gd/script/gdcc/backend/c/gen/insn/CallSuperMethodInsnGen.java`、`BackendMethodCallResolver.resolveSuper`；前端合同见 `doc/module_impl/frontend/frontend_super_call_implementation.md`。

- **词法 super 不变量**：`super` 相对**词法当前类**而非 receiver 动态类型。`resolveSuper` 强制 receiver 静态类型恰为 `bodyBuilder.clazz()`（即 `$self`），解析起点固定为该类的声明 `getSuperName()`；superName 空白 → `invalidInsn`。
- **词法解析入口**（前后端共用，目标零漂移）：`ScopeMethodResolver.resolveNearestDeclaredInstanceMethod` 沿父链向上，**遇到第一个声明该方法名的 owner 即停止**，仅在该 owner 的候选内做参数匹配；参数不适用的近端声明必须报错，不得跳过它绑定参数恰好适用的远端声明（Godot `get_function_signature` 的 stop-at-first-declarer 语义）。普通链式实例解析（`resolveInstanceMethod`）是"先按参数适用性过滤、再按 owner 距离取最近"，**不得**用于 super。
- **fail-closed**（编译期错误，不做运行时回退）：`DynamicFallback` / `Failed`（含无父类、父链缺方法）→ `invalidInsn`；解析到 static 目标 → `invalidInsn`（super 是实例语义，比 `CALL_METHOD` 的 warn 更严）；`super._init` 由共享 resolver 的 constructor-route 守卫拒绝（GDCC 构造器自动链式调用父类 `_init`，显式调用会双跑）。前端同样 fail-closed，且不产生 `super(...)` 显式构造调用形态。
- **生成**（`CallSuperMethodInsnGen`，注册于 `CCodegen.INSN_GENS`）：GDCC owner → 共享发射器直调 `<Owner>_<m>`，**永不查 `isPolymorphicCall`、不走 vtable**（super 固定父类实现，即使目标持有 polymorphic slot）；ENGINE owner → exact engine helper；coroutine owner → start thunk 直调（static coroutine 异常 IR 守卫与 `CALL_METHOD` 一致）。receiver（`$self`）经 `renderReceiverValue` 沿 `_super` 链 upcast 到 owner；缺参补全 / vararg / void / 结果写入与 `CALL_METHOD` 零差异复用。共享发射器 `emitResolvedCall` 约定 super 路径 `indirect == null`，禁止在该共享流上加 vtable 闸门。
- lowering 侧不变量：super receiver 恒为当前类 `self` 别名（链式 = super 标识符 opaque 物化，裸调用 = 隐式 self 槽），无逆提交 writeback；lambda 内 super 捕获 enclosing self。

## 8. 命名与符号注册合同

命名分两层（冻结，与 `gdcc_facing_class_name_contract.md` 的既有分层一致）：

| 层 | 类名分量 | 适用符号 | 例子（inner class `Outer__sub__Inner`） |
|---|---|---|---|
| Godot / identity / wrapper | **raw canonical**（含 `__sub__`，与 `struct <Class>`、`<C>_object_ptr` 同层） | vtable typedef / 实例 / accessor / trampoline / `_vtable` 字段 | `gdcc_Outer__sub__Inner_vtable`、`Outer__sub__Inner_class_vtable` |
| fat_ptr / upcast helper | **`cIdentifier()` 归一**（连续下划线折叠），一律经 `helper.renderObjectFatPtrStorageType(...)` 渲染，禁止手写拼接 | slot 签名 self 类型、trampoline 体内 fat 类型、`$vt_recv` 声明类型 | `gdcc_Outer_sub_Inner_fat_ptr` |

| 符号 | 形态 | 说明 |
|---|---|---|
| vtable 类型 | `gdcc_<C>_vtable` | typedef，compiler 前缀 |
| vtable 实例 | `gdcc_<C>_vtable_inst` | `static const` |
| accessor | `<C>_class_vtable(<C>* self)` | **条件登记**进 `CCodegen.validateFileScopeSymbolsDisjoint`（仅 introducer 类）；用户方法 `class_vtable` 撞名 → fail-fast（与 `_object_ptr` 等既有 machinery 同一冲突模型），slotless 类的同名用户方法保持合法 |
| trampoline | `gdcc_<D>_vslot_<m>` | `static` |
| 结构体字段 | `_vtable` | 根类，`const void*` |
| slot 字段 | `m_<method>` | vtable struct 内（introducer 层段声明一次），无需文件级登记 |

## 9. 回归测试基线

- 单元：`CVtablePlannerTest`（slot 算法 / 前缀 / D1 冲突 / coroutine 标记 / abstract / 排除项 / 容错 / 兄弟分支与最终覆写者谓词）；`CallSuperMethodInsnGenTest`、`CallSuperMethodInsnContractTest`（LIR round-trip）；`CallMethodInsnGenTest`（多态闸门正反用例）。
- golden：`CVtableCodegenTest`（全布局链路、旁支、typedef 链与表值链分离、derived-first 发射顺序、连续 introducer、整层无 slot 时两份 entry 文件零 `vtable` 子串、三层链 mid 调用点端到端分发、inner class 命名、`class_vtable` 撞名、coroutine slot、abstract hole）；`CCodegenTest`（virtual 转发段正反断言、零 diff 回归）。
- 前端：`FrontendSuperCallSemanticsTest`、`FrontendSuperCallSupportTest`、`FrontendSuperCallLoweringTest`（见前端文档）。
- runtime（真实 Godot，经 `GdScriptUnitTestCompileRunnerTest` / `GdScriptEngineVirtualOverrideRuntimeTest` 执行）：`CallMethodInsnGenEngineInheritanceTest`（Zig+Godot 五场景分派）；夹具目录 `src/test/test_suite/unit_test/{script,validation}/runtime/virtual/`——`dispatch_three_level_chain.gd`、`dispatch_pass_through.gd`、`dispatch_sibling_branch.gd`、`dispatch_deep_chain.gd`、`dispatch_template_method.gd`、`dispatch_control_flow.gd`、`dispatch_coroutine_override.gd`、`ready_parent_chain_dispatch.gd`、`engine_virtual_three_level_forwarding.gd`、`mixed_engine_virtual_and_vtable.gd`、`super_method_dispatch.gd`。

## 10. 工程反思（实施中实证的关键坑，后续改动必须重读）

1. **发射顺序是正确性问题**：trampoline 是 `static` 且无头文件原型，子类 vtable 实例会取祖先 trampoline 的地址；按源文件模块序（derived-first）发射会产生"取址未定义符号"。一切跨类引用段必须按 base-before-derived 继承序发射。
2. **typedef 链与表值链是两种祖先关系**：typedef 链跟"最近 introducer 祖先"，表值链跟"最近非 pass-through 祖先"；仅覆写类的实例以前者 typedef 为类型、pass-through 共享后者的表值。混用会生成不存在的符号或错误的实例类型。
3. **闸门谓词单向蕴含**：`findVtableSlot` 命中（兄弟分支、最终覆写者都命中）绝不能当间接分发闸门；唯一合法闸门是 `isPolymorphicCall`。
4. **前端 super 检测必须走 pending 感知的 `ReductionRequest.bindingLookup`**：chain binding 阶段 top-binding 事实尚未 flush 到稳定表，用稳定表检测会漏判 super 链头。
5. **词法 super 解析是 stop-at-first-declarer**：普通实例解析"先按参数适用性过滤、再按 owner 距离取最近"会跳过近端参数不适用声明绑定远端适用声明（祖父 `m(int)` / 父 `m(String)` / `super.m(1)` 误绑祖父）。前后端曾共用同一缺陷——修复一侧必然漂移，必须共用一个专用入口。
6. **vtable 闸门必须先于 coroutine 分流**：`emitKnownSignatureCall` 的 coroutine 分支提前 return，polymorphic coroutine 也要间接分发。
7. **间接调用不做 binding 用量登记**：callee 是表达式而非 `godot_*` 符号，塞进 `recordUsedGodotBindingCall` 会污染使用集。
8. **receiver 必须物化为 introducer fat self 单次求值**：callee 与首参同源，且 slot 签名 self 是 introducer fat 类型。
9. **machinery 符号条件登记**：accessor 只为 introducer 生成，因此 `<C>_class_vtable` 只对 introducer 登记冲突；无条件登记会误杀 slotless 类的合法用户方法。

## 11. 已知限制与非目标

- 跨 GDCC module 的父类不支持（`superclass_canonical_name_contract.md`）。
- 运行时 attach 的 GDScript 脚本子类覆写**不进入 vtable**（模块外编译期不可见）：经 engine `call()` 的调用不受影响（ClassDB 方法表按类查找天然"虚"），仅 GDCC 编译代码内的直接/间接调用不感知脚本覆写——与 godot-cpp 直接 C++ 调用行为一致。
- 无 `final` / 禁止覆写语义（类型系统未定义）。
- engine 非 virtual 方法的覆写分派不涉及（GDScript 语义下属脚本遮蔽）。
- `super(...)` / `super._init(...)` 显式父类构造调用 fail-closed（GDCC 构造器自动链式调用父类 `_init`）。
- **跨文档同步待办**（本特性已实施完毕，但以下外围文档尚未回填对应内容，属后续工作）：
    - `explicit_c_inheritance_layout_contract.md`：`_vtable` 字段（位置 / 条件 / accessor / 根段链式访问）写入已锁定结论；trampoline 下行转换例外条款；create_instance 序列补 `_vtable` 赋值。
    - `doc/gdcc_c_backend.md`：对象构造序列补 `_vtable` 赋值。
    - `call_method_implementation.md`：`CALL_SUPER_METHOD` 移出"非目标（当前不做）"清单，分派模式表补 vtable 间接形态。
    - `frontend_engine_virtual_override_implementation.md`：runtime anchor 名单补继承用例。
    - `doc/test_suite.md`：engine-virtual 继承观察用例的夹具写法合同。
    - `doc/gdcc_backend_todo.md`：登记遗留项（协变返回放宽、脚本覆写边界、D1 的前端 sema 诊断路径、显式 `super(...)` 构造调用支持评估）。

## 12. 后续扩展接缝（abstract 方法的脚本动态覆写）

若未来允许 GDCC abstract 方法被运行时 GDScript 脚本子类动态覆写（落地时另立文档），既有接缝与已知冲突：

- **已有接缝**：vtable 间接调用点（所有 polymorphic 调用统一走 `<I>_class_vtable(...)->m_x(...)`，是唯一拦截点）；trampoline 层（每（覆写类 × slot）一对，是把下行转型换成动态分派的天然位置）；abstract slot 的 `NULL` 条目（脚本桥 trampoline 的安装位——脚本子类实例仍走抽象类的 create_instance，`_vtable` 指向抽象类表，届时该条目不得为 NULL）；engine 侧动态调用（`Object::call` 优先查 script instance）天然能到达脚本覆写。
- **已知冲突（落地时必须修改，无预留开关）**："具体类 + 未实现 abstract slot → planner fail-fast"规则会拒绝"实现由脚本提供"的程序，届时改为生成脚本桥 trampoline（经 `godot_Object_call` 分派，未 attach 脚本时报运行时错误）；polymorphic 判定（需模块内真后代声明）对 abstract 方法要放宽为"abstract 方法恒视为 polymorphic"；脚本覆写不进入 vtable 的边界只覆盖普通方法，该特性是对 abstract 方法的最小开口。
