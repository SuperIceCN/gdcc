# C 后端虚函数 / 覆写 / vtable / CALL_SUPER_METHOD 实施计划

## 0. 文档状态与范围

- 状态：**部分已实施**（Step 1–7 已完成并回填验收结论；Step 8 文档修订与全量回归待做）。
- 范围（对应需求编号 R1–R5）：
    - R1：子类未覆写某 engine virtual 时，子类的 `get_virtual_with_data` 转发父类 `get_virtual_with_data`。
    - R2：子类覆写了 engine virtual 时，`get_virtual_with_data` / `call_virtual_with_data` 正确分派到最派生覆写。
    - R3：为 GDCC 类中"被子类覆写"或"覆写了父类"的方法生成 vtable（前缀兼容父类 vtable），虚表指针插入 C 结构体 `GDExtensionObjectPtr _object` 之后。
    - R4：静态分发（已解析到 GDCC owner 的 `CALL_METHOD`）的调用点检查目标方法是否可能被模块内子类覆写：可能 → 生成经 vtable 的间接调用；确定不会 → 照常直接调用（即使该方法覆写了父类）。
    - R5：实现 `CALL_SUPER_METHOD` 指令的 C 代码生成。
- 非目标（本计划明确不做）：
    - 跨 GDCC module 的父类（MVP 不支持，`superclass_canonical_name_contract.md` §6）。
    - GDScript 脚本子类（运行时 attach 的 script instance）对 GDCC 方法的覆写分派（见 §5 D3）。
    - `final` / 禁止覆写语义（类型系统当前无此概念，`gdcc_type_system.md` 全文未定义）。
    - engine 非 virtual 方法的覆写分派（GDScript 语义下属于脚本遮蔽，本计划不涉及）。
    - `super(...)` / `super._init(...)` 形式的显式父类构造调用（GDCC 构造器自动链式调用父类 `_init`，`entry.c.ftl` `class_constructor`，显式调用会双跑；前端 fail-closed，见 Step 6）。

## 1. 现状调研结论（代码事实）

### 1.1 Godot / godot-cpp 参考机制（外部事实）

- **engine 只查询最派生 extension 类的 virtual 回调**。`godotengine/godot` `core/object/object.cpp` `Object::_gdvirtual_init_method_ptr`（master 分支约 962 行）：
  `fn_ptr = _extension->get_virtual_call_data2(_extension->class_userdata, &p_fn_name, p_compat_hash);`
  `_extension` 是 per-Object 结构，回调由 `object_set_instance` 时使用的类的 creation info 决定，即最派生 GDCC 类。**因此"子类未覆写时向父类转发"必须由扩展侧自己实现**，engine 不会代查父类。
- **godot-cpp 的父类转发模式**。`godotengine/godot-cpp` `src/core/class_db.cpp` `ClassDB::get_virtual_func`（约 288 行）：`while (type != nullptr)` 循环沿 `type->parent_ptr` 在 **extension 类链**内查找 virtual（注释原文："Find method in current class, or any of its parent classes (Godot classes not included)"），并比较 `method_it->value.hash == p_hash`。native 父类的 virtual 不在此处处理（extension 返回 `nullptr` 后 engine 走自身 fallback）。
- **回调配对 ABI**。`src/main/c/codegen/include_451/godot/gdextension/gdextension_interface.h:275-278,368-402`：`GDExtensionClassGetVirtualCallData2` 返回的 `void*` 数据由扩展管理、有效期到扩展反初始化；engine 随后用同一 `p_name` + 该数据调用 `GDExtensionClassCallVirtualWithData`。
- **`p_hash` 是 virtual compatibility hash**。godot-cpp 用等值比较；本项目冻结合同（`frontend_engine_virtual_override_implementation.md` §4.4）规定 backend 不依赖 `p_hash` 判定 override，本计划继续 `(void)p_hash`。

### 1.2 LIR 层事实

- `<class_def super="...">` 携带 canonical 父类名（`superclass_canonical_name_contract.md` §2）；`LirModule` 持有模块全部 `LirClassDef`，`CCodegen.computeInheritanceClassOrder()`（`CCodegen.java:779-819`）已证明可在后端重建模块内继承拓扑并 fail-fast 检测环。**vtable 分析所需的模块内类闭包在后端完整可得，无需改 LIR 元数据。**
- `LirFunctionDef` 无任何 override/virtual/final 标记（`LirFunctionDef.java:17-37`）。
- `CALL_SUPER_METHOD` 已在 `GdInstruction.java:77` 定义（`call_super_method`，可选返回值，操作数 = 方法名 + 对象变量 + VARARGS），语义见 `gdcc_low_ir.md` §Call Instructions 582-589 行（"调用对象的父类方法；父类不存在该方法时产生运行时错误"）。`ParsedLirInstruction.java:194-200` 已能解析为 `CallSuperMethodInsn(resultId, methodName, objectId, args)`。
- `CCodegen.java:41-74` 的 `INSN_GENS` 注册表中**没有** `CallSuperMethodInsnGen`；缺失 generator 时 `CCodegen.java:584-594` 抛 `UnsupportedOperationException`。`CConstructInsnGenTest.java:723-743` 正是用 `CALL_SUPER_METHOD` 作为"未注册 opcode 必须 fail-fast"的探针——实现后须换用其他未注册 opcode（当前候选 `GET_CLASS_NAME`，已核对未注册，实施时复核）。
- 调研时前端 lowering 不产生 `CallSuperMethodInsn`；**Step 6–7（2026-09-10）起前端 super 语法接线落地**，`super.m(...)` / `super(...)` 经 `SUPER_METHOD` 路由降低为该指令（前端合同见 `frontend_super_call_implementation.md`）。

### 1.3 后端调用链事实

- `CallMethodInsnGen.generateCCode`（`CallMethodInsnGen.java:41-54`）经 `BackendMethodCallResolver.resolve(...)` 得到 `DispatchMode`：`GDCC`/`ENGINE`/`BUILTIN` 静态分发 + `OBJECT_DYNAMIC`/`VARIANT_DYNAMIC`。
- GDCC 静态分发的 C 函数名规则为 `ownerClassName + "_" + methodName`（`BackendMethodCallResolver.java:421-422`）。**注意 coroutine 分支提前返回**：`emitKnownSignatureCall`（`CallMethodInsnGen.java:195-214`）在 `resolved.coroutine()` 时直接走 `emitCoroutineStartCall` 并 return，普通路径才到 `emitResolvedCall`（221-256）——vtable 查询必须放在 coroutine 分流**之前**（§2.6）。
- receiver 由 `BackendPropertyAccessResolver.renderReceiverValue(...)`（`BackendPropertyAccessResolver.java:177-194`）渲染：同类型直传 `valueOfVar`，GDCC 子类→父类经 `valueOfCastedVar`（`_super` 链 upcast，禁止裸 cast，`explicit_c_inheritance_layout_contract.md` §5）。
- **`CBodyBuilder.callVoid/callAssign` 的 `funcName` 实参会进入 `recordUsedGodotBindingCall(funcName)`（`CBodyBuilder.java:605,640,1573`）做 godot binding 用量登记**，间接调用的 callee 表达式不能直接塞进去，需要新增的间接调用 API（§2.6）。
- fat pointer 结构为 `{ .ptr, .instance_id }`（`object_value_fat_pointer_implementation.md` §2；`object_fat_ptr_types.h.ftl:19-22`；测试断言形态见 `CConstructInsnGenTest.java` 中 `$other).ptr, $other.instance_id`）。GDCC 类型的 `.ptr` 是 wrapper 指针。
- 实例方法的第一个 C 参数是 owner fat self（`CGenHelper.renderObjectFatPtrParameterType`，`entry.h.ftl:682-703` bind wrapper 用 `renderRegisteredMethodSelfFatExpr` 从 `p_instance` 构造）。

### 1.4 入口模板与类注册事实

- 结构体生成（`entry.h.ftl:36-50`）：非根 GDCC 类首字段 `Parent _super;`（父按值嵌入、偏移 0）；根类首字段 `GDExtensionObjectPtr _object;`。**当前没有任何 vtable 字段。**
- `<Class>_object_ptr` / `<Class>_set_object_ptr` 沿 `_super` 递归（`entry.c.ftl:240-261`），且**链上每个类都生成**（包括无自有字段的中间类）；constructor/destructor 同样递归（`entry.c.ftl:294-334`）。
- `<Class>_class_create_instance`（`entry.c.ftl:274-284`）：构造最近 native ancestor → `godot_mem_alloc` wrapper → set_object_ptr → `object_set_instance(最派生类名)` → 绑 binding callbacks → POSTINITIALIZE 通知（触发 `_class_constructor`）。
- `<Class>_class_get_virtual_with_data`（`entry.c.ftl:345-361`）：只遍历**本类** functions，`helper.checkVirtualMethod`（`CGenHelper.java:1775-1781`，严格签名判定）通过则按名匹配返回 `(void*)Class_method` 或默认 userdata 地址；未匹配 `return NULL;`，**无父类转发**。
- `<Class>_class_call_virtual_with_data`（`entry.c.ftl:363-388`）：按 userdata 地址匹配本类覆写，命中则 `ptrcall<bind>(...)`；`_process`/`_physics_process` 在非 tool 类内有 `gdcc_is_editor_hint()` 门（冻结行为，见 `CCodegenTest` 现有 golden）；未命中直接结束，**无父类转发**。
- 默认参数 userdata 协议：ClassDB 注册与 virtual 分派共享 per-method 独占 userdata 实例（`entry.c.ftl:159-177`；`godot_binding_implementation.md` §340-376 冻结合同）。转发设计必须保持"userdata 地址即方法身份"这一协议。
- 父类 ptrcall wrapper 收到的 `p_instance` 是**最派生** wrapper 指针；`renderRegisteredMethodSelfFatExpr`（`CGenHelper.java:1008-1018`）生成 `FatType_from_raw(Owner_object_ptr((Owner*)p_instance))`——偏移 0 嵌入保证 `(Parent*)childPtr` 合法，因此父类的 call_virtual 可以直接处理子类实例。
- `CCodegen.prepare`（`CCodegen.java:1004-1013`）先把全部模块类注册进 `ClassRegistry`，再以 `module.getClassDefs()` 构造 `CGenHelper`。**但大量既有测试直接 `new CGenHelper(ctx, classDefs)`（如 `CBodyBuilderPhaseCTest.java:122`、`CGenHelperTest.java:64`），vtable 规划器必须容忍 registry 不完备的构造场景（§2.1 容错规则）。**
- `validateFileScopeSymbolsDisjoint`（`CCodegen.java:835-887`）登记每类固定符号（`_class_*` machinery、`_object_ptr` 等）与用户函数 `${class}_${func}`——**任何 `<C>_<suffix>` 形态的新 machinery 符号都与用户方法同命名空间，必须登记并 fail-fast**。
- GDCC 类符号（`struct ${classDef.name}`、`<C>_object_ptr`）使用 raw canonical 类名（inner class 含 `__sub__`），而 fat pointer typedef 经 `cIdentifier()` 归一（`superclass_canonical_name_contract.md` §2.5）——vtable 符号命名须冻结其中之一（§2.7）。

### 1.5 virtual 元数据共享面

- `ClassRegistry` 维护 visible / engine-only 双 virtual map（`ClassRegistry.java:1014-1073`）：`buildVirtualMethodMap` 把 `isAbstract()` 方法视为 virtual 并沿 `superName` 继承；engine-only map 排除 GDCC 类（防同名遮蔽）。即 **GDCC abstract 方法在当前模型里就是 virtual**，vtable 规划须兼容"覆写 GDCC 父类 abstract 方法"这一来源。
- `VirtualMethodInfo.checkOverrideSignature(...)`（`VirtualMethodInfo.java:19-61`）是 frontend/backend 共用的严格签名判定；其 `ignoreLeadingSelfParameter` 只跳过 override 侧 self，且**不比较 coroutine/static 之外的标记**（实际只比较 static、vararg、参数、返回类型）。GDCC↔GDCC 覆写比较需要新入口（§2.1），不能松散声称"复用等价语义"。

## 2. 总体设计

### 2.1 核心判定：polymorphic method 与 vtable slot

**定义**：GDCC 类 `C` 的实例方法 `m` 是 *polymorphic*，当且仅当模块内存在 `C` 的真后代类 `D`，`D` 声明了同名实例方法 `m'` 且签名兼容（见下）。polymorphic 方法才占 vtable slot。

**签名兼容（MVP 严格规则）**：新增两侧都跳 leading `self` 的 GDCC↔GDCC 专用比较入口（不直接复用 `VirtualMethodInfo.checkOverrideSignature`，它不查 coroutine 且只跳单侧 self，§1.5），逐项检查：

- 两侧均非 static、非 hidden、非 lambda；
- vararg 形状一致；
- **coroutine 标记一致**（协程覆写非协程或反之 → 不兼容）；
- 去掉两侧 leading `self` 后参数个数、参数类型、返回类型严格相等。

协变返回放宽属于未来工作。

**排除项**：`_init`（构造语义，模板已直接调 `Class__init`，`entry.c.ftl:307-312`；`ScopeMethodResolver` 也拒绝把 `_init` 当普通方法解析）、static/hidden/lambda 方法不占 slot、不进入 polymorphic 判定，也**不走 D1**（父子 `_init` 参数不同是 GDScript 常态，不得报错）。

**冲突处理（D1，待确认，见 §5）**：后代声明同名但签名不兼容的方法 → 编译期 fail-fast（报两类名与方法名），不静默退化、不生成 UB 调用。D1 只作用于两侧均为未排除实例方法的祖先/后代对。

**planner 容错规则**：仅当冲突双方类都存在于 registry/模块中时才 fail-fast；规划时遇到 registry 查不到的 GDCC 父类名（既有测试直接 `new CGenHelper` 的不完备场景，§1.4）→ 按"无 GDCC 父类、`slots(C)=[]`"处理，不得抛错。

**两个形式谓词**（全文只允许用这两个词描述类的 vtable 角色，禁止含混的"零 slot"）：

- `slotted(C)` ⟺ `slots(C)` 非空（**含**从祖先拷贝来的继承 slot）——决定实例是否需要有效 `_vtable` 值；
- `introducesSlot(C)` ⟺ `C` 在算法步骤 2 追加了至少一个**新** slot 字段——决定是否生成 vtable typedef / accessor。

第三条派生谓词（角色只允许用这三套说法）：`pass-through(C)` ⟺ `slotted(C)` ∧ ¬`introducesSlot(C)` ∧ `C` 未覆写任何继承 slot。`!slotted(C)` 再按层级切分：层级无 slot → 整层无 slot（branch 1）；层级有 slot → 旁支（branch 2）。二者都不得称为 pass-through。（"层级" = 该 GDCC 根类及其模块内全部后代，与 §2.2 字段判定的口径一致。）

| 角色 | 谓词 | vtable 符号 | `create_instance`（§2.3） |
|---|---|---|---|
| 整层无 slot | 层级无任何 slot | 无 `_vtable` 字段 | branch 1：不赋值 |
| 旁支 | 层级有 slot，`!slotted(C)` | 无符号 | branch 2：写 `NULL` |
| 引入者 / 仅覆写不引入 | `slotted(C)` ∧（`introducesSlot(C)` ∨ 覆写了继承 slot） | 引入者：typedef+accessor+自有实例；仅覆写：本类实例（最近 `introducesSlot` 祖先 typedef） | branch 3：写 `&gdcc_<C>_vtable_inst` |
| pass-through | `slotted(C)` ∧ ¬引入 ∧ ¬覆写 | 无符号 | branch 4：写最近非 pass-through 祖先的表值 |

**slot 分配算法**（按 `computeInheritanceClassOrder` 的 base-before-derived 序逐类计算）：

1. `slots(C)` 以 `slots(parent)` 的有序拷贝为前缀（前缀兼容由此保证；GDCC 父类不存在时为空列表）。
2. `C` 自声明方法按声明顺序处理：
   - 若继承的 slot 列表已含同名 slot（即 `m` 覆写祖先方法）→ 复用该 slot 下标，不新增；
   - 否则若 `m` 是 polymorphic（被某后代覆写）→ 追加新 slot。
3. 每类每 slot 记录 *final overrider*（从 slot 引入者到 `C` 链上的最派生实现）与 slot 引入者（introducer）身份；调用点用 introducer 物化 fat self 并调 `<I>_class_vtable`（§2.6）——**调用点不做任何 vtable `->_super` 导航**；vtable `_super` 只存在于 typedef 前缀嵌入与实例的嵌套初始化器中（§2.2）。

等价判定（供调用点使用）：已解析调用 `(owner O, method m)`（receiver 静态类型为 `T`，`O` 是从 `T` 出发的最近声明者）需间接分发 ⟺ **`T` 的模块内真后代覆写 `m`**。注意两个不等价于它的谓词：`m ∈ slots(O)` 过宽（`O` 可能因兄弟分支而继承 slot，且最终覆写者自身也含继承 slot）；`O` 的后代覆写 `m` 也过宽（receiver 静态类型已把动态类型收窄到 `T` 的子树）。该谓词同时覆盖 R4 括号条款：`T` 自身覆写了父类但无真后代再覆写（`T` 是最终覆写者）→ 直接调用 `T` 的实现。

vtable 布局仍按类计算，与调用点谓词分离：`slots(C)` 由上述算法（无条件拷贝父表前缀 + 本类 polymorphic 才追加）决定，**不得**改写成"只保留本类子树用到的 slot"——兄弟分支诱导的继承 slot 必须留在 pass-through 类上，否则 `Child2` 会变成 `!slotted`、走 §2.3 branch 2 写 `NULL`，`B b = Child2.new(); b.foo()`（`isPolymorphicCall(B, foo)` 命中）空解引用。
调用点不变量（单向蕴含）：`isPolymorphicCall(T, m)` 命中时，`findVtableSlot(O, m)` 必然非空（`O` 是从 `T` 出发的最近声明者，`T` 的真后代也是 `O` 的真后代）；**反向不成立**（兄弟分支 / 最终覆写者的继承 slot），禁止用 `findVtableSlot` 是否命中充当间接分发闸门。

**完备性依据**：MVP 下 GDCC 类的父类只能是同模块 GDCC 类或 engine 类（`superclass_canonical_name_contract.md` §3.1-3.3、§6），因此模块内后代闭包就是全部可能覆写者。

### 2.2 vtable C 布局合同（修订 `explicit_c_inheritance_layout_contract.md` 的对象）

**两条 `_super` 链的互斥定义**（实施时必须严格区分，混淆会生成不存在的成员访问）：

| 链 | 成员类型 | 是否跳过非 `introducesSlot` 类（pass-through 与仅覆写不引入） | 用途 |
|---|---|---|---|
| wrapper `_super` | 永远是**直接父类** wrapper | 否 | 结构体嵌入、accessor 取根 `_vtable` 字段、fat self 上行 |
| vtable `_super` | **最近 `introducesSlot` 祖先**的 vtable | 是 | vtable typedef 嵌入、slot 前缀继承 |

示例层级 `A（引入 slot foo）→ B（pass-through）→ C（引入 slot bar）`：

```c
/* wrapper 链：直接父类逐级嵌入（B 是 pass-through 也存在） */
struct C { B _super; ... };            /* B 内是 A _super */

/* vtable 链：跳过 pass-through 的 B，直接嵌入最近 introducesSlot 祖先 A 的表 */
typedef struct gdcc_C_vtable {
    gdcc_A_vtable _super;              /* 不是 gdcc_B_vtable——B 没有自己的 vtable 类型 */
    <Ret> (*m_bar)(<gdcc_C_fat_ptr> self, ...);   /* C 本层新引入的 slot，self 为引入者 C 的 fat 类型 */
} gdcc_C_vtable;
```

**vtable struct：父表按值嵌入为首成员**（与 wrapper 布局同一哲学，禁止"扁平复制字段再跨类型 cast"——后者在 ISO C 下是 strict-aliasing UB，且违反本项目布局合同"父对象必须是偏移 0 真实首成员"的既定条款）：

- slot 的函数指针签名以 **slot 引入类**的方法 C 签名为准（self 为引入类 fat 类型）；继承 slot 的字段只声明一次（在引入者层段的 struct 内），经 `->_super` 前缀链继承。
- typedef 在 `entry.h` 中按 base-before-derived 序生成（父表类型先于子表完整，嵌入才合法）。
- 所有 fat 类型名一律经 `helper.renderObjectFatPtrStorageType(...)` 渲染（`cIdentifier()` 归一层，inner class 形如 `gdcc_Outer_sub_Inner_fat_ptr`），禁止手写 raw 拼接（§2.7）。
- 发射规则（与 §2.1 谓词一一对应）：
    - `introducesSlot(C)` → 生成 vtable typedef + accessor + 自有类型的实例；
    - `slotted(C)` 且非 `introducesSlot(C)` 且覆写了至少一个继承 slot → 不生成 typedef/accessor，但生成**以最近 `introducesSlot` 祖先的 vtable 为类型**的实例 `gdcc_<C>_vtable_inst`（条目含本类覆写的 trampoline）；
    - pass-through（`slotted(C)` 但既不引入也不覆写）→ 不生成任何 vtable 符号，实例创建时共享最近非 pass-through 祖先解析到的同一表值（§2.3）；
    - `!slotted(C)` → 不生成任何 vtable 符号。
- 层次判定：根 GDCC 类仅当所属层级（自身 + 模块内全部后代）含至少一个 slot 时，才在 `_object` 之后插入字段：

```c
struct <Root> {
    GDExtensionObjectPtr _object;
    const void* _vtable;   /* 指向按本类解析的 vtable 实例（§2.3）；本类及全部祖先均 !slotted 时为 NULL */
    ...
};
```

`const void*` 类型避免空 struct 不可移植问题与 typedef 顺序问题。

**accessor**（每 `introducesSlot` 类一个，命名 `<C>_class_vtable`，登记进符号冲突表）：

```c
/* 沿 wrapper 链直达根段字段，不递归调用父 accessor（父类可能不引入 slot、无 accessor） */
static inline const gdcc_<C>_vtable* <C>_class_vtable(<C>* self) {
    return (const gdcc_<C>_vtable*)self->_super._super._vtable;  /* 跳数 = 到根的 wrapper _super 层数；根类为 self->_vtable */
}
```

安全性（accessor 只生成在 `introducesSlot` 的类上；按动态类型 D 的存储形态分析，禁止假设"最派生类 D 一定存在 `gdcc_<D>_vtable`"）：

- D 自身 `introducesSlot`：`_vtable` 指向 `gdcc_<D>_vtable` 对象，`gdcc_<C>_vtable` 是其 vtable `_super` 链上真实存在的前缀子对象且地址相同（C17 §6.7.2.1 初始成员指针互转，可递归应用于嵌套首成员）；
- D 仅覆写不引入：`_vtable` 指向**本类自有实例** `gdcc_<D>_vtable_inst`，对象类型为最近 `introducesSlot` 祖先的 typedef（条目含本类 trampoline——指针身份是本类的，不是祖先的）；
- D 为 pass-through：`_vtable` 与最近非 pass-through 祖先 N 的表值相同，对象类型仍是最近 `introducesSlot` 祖先的 typedef；
- 三种形态下，若该对象类型恰好是 `gdcc_<C>_vtable` 则为 void* 往返后的精确类型匹配，否则 C 仍是该对象的真实前缀子对象。

调用点不变量：间接调用的 introducer `I` 必为实例类的 `introducesSlot` 祖先，因此从不把精确的祖先表对象当成更派生的 vtable 类型解读。对齐亦满足（`D` 的对齐 ≥ 其前缀）。

**vtable 实例**：条目 = final overrider，C99 嵌套指定初始化器逐层填：

```c
/* 层级 A(引入 foo) → C(覆写 foo，引入 bar)，C 的实例： */
static const gdcc_C_vtable gdcc_C_vtable_inst = {
    ._super = { .m_foo = gdcc_C_vslot_foo },   /* 覆写 A.foo → trampoline */
    .m_bar = C_bar,                            /* C 引入且未被更深覆写 → 直填 impl */
};
```

- 实例生成规则（配合 §2.2 发射规则）：`introducesSlot(C)` → 自有类型实例；仅覆写不引入 → 以最近 `introducesSlot` 祖先类型为类型的实例；pass-through → **不生成实例**，`_vtable` 直接复用最近非 pass-through 祖先解析到的同一指针值（内容逐字段相同，共享安全——`_vtable` 是 `const void*`，accessor 按引入者前缀子对象解读）；
- 实例条目取值：final overrider 即 slot 引入类自身 → 直填 `<C>_<m>`（签名精确匹配）；
- 否则填 trampoline（见下）；
- coroutine 覆写链：slot 存 coroutine **start thunk**（`<C>_<m>__coro_start`，`godot_Object*` 返回），trampoline 同理适配（D4）；
- abstract 方法引入的 slot：abstract 类自身表条目填 `NULL`（engine 不实例化 abstract 类）；具体（非 abstract）类的任何 slot 若最终无实现 → planner fail-fast。

**trampoline**（每个"覆写类 D × 非引入 slot m"一对，`static`，签名取 slot 签名）：

```c
static <Ret> gdcc_<D>_vslot_<m>(<gdcc_Introducer_fat_ptr> self, ...) {
    /* 经 D 的 vtable 到达此处时动态类型必为 D 或其后代；wrapper 偏移 0 嵌入使下行转换安全。 */
    /* 本转换是"禁止裸 C cast 表达 GDCC 上下行"合同（explicit_c_inheritance_layout_contract.md §5）的
       唯一新增例外，仅允许 vtable trampoline 下行；上行仍禁止裸 cast。 */
    <gdcc_D_fat_ptr> s = { (<D>*)self.ptr, self.instance_id };
    return <D>_<m>(s, ...);
}
```

**附：根类定义与"中途引入 slot"的完整形态**

- **根类** = 继承链中直接继承 engine 类的那个 GDCC 类（`checkGdccClassByName(superName)` 为 false）；只有它的结构体真正持有 `_object` 字段，`_vtable` 指针也只物理存在于这一层根段，子类经 `_super` 偏移 0 嵌入共享。字段插入判定（D5）按"根 + 模块内全部后代"的层级闭包做出。
- **中途引入示例**：`A extends Node`（根，无相关方法）→ `B extends A`（声明 `bar`，被后代覆写）→ `C extends B`（覆写 `bar`，无新引入）：
    - A：层级含 slot（B 引入）→ 结构体插入 `_vtable` 字段；但 A 自身 `!slotted`（slot 列表为空）→ `A.new()` 写 `NULL`（A 未声明 `bar`，以 A 为 receiver 类型的调用编译期就解析不到该 slot，NULL 永不被读）；
    - B：`introducesSlot(B)` → `gdcc_B_vtable`（无 `_super` 成员——A 没有表）+ accessor + `gdcc_B_vtable_inst` 三件套；
    - C：`仅覆写不引入` → **不生成** `gdcc_C_vtable` typedef / accessor（避免与 `gdcc_B_vtable` 形成纯别名类型）；实例为 `static const gdcc_B_vtable gdcc_C_vtable_inst = { .m_bar = gdcc_C_vslot_bar };`（类型取最近 `introducesSlot` 祖先 B 的 typedef，条目填 C 的 trampoline），`C.new()` 写 `&gdcc_C_vtable_inst`；
    - 对比形态——若 C 同时引入新 polymorphic 方法 `baz`（`introducesSlot(C)`）：则生成 `gdcc_C_vtable { gdcc_B_vtable _super; ... m_baz }`、accessor `C_class_vtable` 与自有类型实例（嵌套初始化器 `._super = { .m_bar = gdcc_C_vslot_bar }, .m_baz = C_baz`）。
- **全形态穷举**（除"整层无 slot"外的一切类都落在角色表四行内）：根或中间类都可能是引入者/仅覆写/pass-through/旁支；同一模块多条独立继承链各自独立判定（一条链含 slot 不影响另一条链的结构体）。
- **四级混合链示例**：`A(根)` → `B(引入 m1)` → `C(引入 m2)` → `D(覆写 m1、m2，无新引入)`：
    - typedef：`gdcc_B_vtable { m_m1 }`；`gdcc_C_vtable { gdcc_B_vtable _super; m_m2 }`（m2 追加在前缀之后）；D 仅覆写不引入 → 无 typedef/accessor；
    - 实例：`gdcc_B_vtable_inst { .m_m1 = B_m1 }`；`gdcc_C_vtable_inst { ._super = { .m_m1 = B_m1 }, .m_m2 = C_m2 }`（C 未覆写 m1，final overrider 仍是 B）；`gdcc_D_vtable_inst`（类型为 `gdcc_C_vtable`）`{ ._super = { .m_m1 = gdcc_D_vslot_m1 }, .m_m2 = gdcc_D_vslot_m2 }`；
    - `B b = ...; b.m1()`：`isPolymorphicCall(B, m1)` 命中（D 覆写）→ 间接 `B_class_vtable($vt.ptr)->m_m1($vt)`（B 即引入者，成员直接可达、无 `->_super` 导航）；动态类型为 B/C/D 时分别读 `B_m1` / `B_m1`（C 未覆写）/ `gdcc_D_vslot_m1`，分派正确。

### 2.3 实例创建与 vtable 初始化

`<C>_class_create_instance` 在 `godot_mem_alloc` 之后、POSTINITIALIZE 通知之前赋值（保证用户 `_init` 内的虚调用可用）。四分支规则（与 §2.1/§2.2 谓词一一对应）：

```c
<C>* self = godot_mem_alloc(sizeof(<C>));
/* branch 1) 整棵层级无 slot：根类无 _vtable 字段，不生成任何赋值 */
/* branch 2) 层级含 slot，但本类及全部祖先均 !slotted（本类是旁支）：
              self->_super…_vtable = NULL;
              —— 没有任何 accessor 会读到它：命中 slot 的调用其 introducer 必 slotted，
              而本类实例的方法解析不会落到任何含 slot 的 owner 上 */
/* branch 3) slotted(C) 且内容与本类实例一致（introducesSlot 或含覆写）：
              self->_super…_vtable = &gdcc_<C>_vtable_inst; */
/* branch 4) pass-through（slotted 但既不引入也不覆写）：
              self->_super…_vtable = <最近非 pass-through 祖先解析到的同一表值>;
              —— 禁止写 NULL：B.new() 赋给祖先静态类型后经 introducer accessor 的间接调用
              会真实读取该字段 */
```

字段访问链沿 **wrapper 链**求值（镜像 `_set_object_ptr` 递归逻辑，由 `CGenHelper` 渲染）。每个类的 create_instance 写入**按本类解析**的表值——实例化哪个类就由哪个类的 create_instance 执行，父类 create_instance 不参与（`explicit_c_inheritance_layout_contract.md` §3）。

### 2.4 engine virtual：get_virtual_with_data / call_virtual_with_data 父类转发（R1/R2）

模板 `entry.c.ftl` 两个函数的尾部各加一段（仅当 `helper.checkGdccClassByName(classDef.superName)`）：

```c
void* <C>_class_get_virtual_with_data(void* p_class_userdata, GDExtensionConstStringNamePtr p_name, uint32_t p_hash) {
    (void)p_class_userdata;
    (void)p_hash;
    /* 本类覆写按名匹配（现状不变）... */
    /* 未命中：沿 GDCC extension 类链转发（godot-cpp class_db.cpp get_virtual_func 模式；native 父类由 engine fallback 处理） */
    return <P>_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);
}

void <C>_class_call_virtual_with_data(GDExtensionClassInstancePtr p_instance, ..., void* p_virtual_call_userdata, ...) {
    (void)p_name;
    /* 本类 userdata 匹配分支（现状不变，含 _process/_physics_process editor 门）... */
    /* 未命中：userdata 可能来自父类 get_virtual（父类 impl 地址或父类默认 userdata 实例），转发父类 dispatch */
    <P>_class_call_virtual_with_data(p_instance, p_name, p_virtual_call_userdata, p_args, r_ret);
}
```

正确性依据：

- engine 固定调用**最派生**实例上登记的两个回调（§1.1），因此子类未覆写时父类 userdata 会回到子类的 `call_virtual_with_data`，必须逐级 fall-through；父类 wrapper 用 `(Parent*)p_instance` 解释子类指针，偏移 0 嵌入保证合法（§1.4）。
- 子类覆写时子类 `get_virtual_with_data` 先命中本类分支，父类分支不可达，分派到最派生覆写（R2）；`_process` editor 门各自留在命中分支内，门语义不变（转发发生在所有本类分支之后，父类命中后执行父类的门）。
- 默认参数覆写的 userdata 协议在转发链上保持一致（userdata 地址即方法身份，per-method 独占实例跨类唯一）。

**engine virtual 的 GDCC↔GDCC 覆写链（双通道一致性）**：`A(根, 覆写 _ready) → B(覆写 _ready)` 时两条分派通道并存、对同一实例解析到同一最派生实现：

- **engine 通道**（本节）：engine 查询最派生类登记的回调——B 实例 → `B_class_get_virtual_with_data` 本类命中 → `B__ready`；未覆写的更深子类实例 → 逐级转发到 B；A 实例 → `A__ready`。该通道不经过 vtable。
- **内部调用通道**（§2.6）：`A._ready` 因 B 的 GDCC 覆写成为 polymorphic → 在 **A** 处引入 slot（`gdcc_A_vtable { m__ready }`）；B 仅覆写 → 本类实例 `gdcc_B_vtable_inst`（类型 `gdcc_A_vtable`，条目为 trampoline）。以 A 为 receiver 类型的内部调用 `a._ready()` 间接分派到最派生实现；以 B 为 receiver 类型且无更后代覆写 → 直接调 `B__ready`（R4 去虚）。
- editor 门（`_process`/`_physics_process` 非 tool 抑制）只存在于 engine 通道的 `call_virtual_with_data` 命中分支；内部显式调用 `self._process(delta)` 不经门——与 GDScript 显式调用语义一致（门抑制的是引擎帧回调，不是方法本体）。
- engine virtual 覆写的签名精确性由 frontend fail-closed 保证（`frontend_engine_virtual_override_implementation.md` §4.1-4.2），链上各覆写必然签名一致，因此 engine virtual 覆写链不会触发 D1 冲突。

### 2.5 CALL_SUPER_METHOD 代码生成（R5）

新增 `CallSuperMethodInsnGen implements CInsnGen<CallSuperMethodInsn>`（注册进 `CCodegen.INSN_GENS`）：

1. **词法 super 语义不变量**：GDScript 的 `super` 相对**词法当前类**（而非 receiver 静态类型）。指令的 object 操作数静态类型必须等于当前正在生成的类（`bodyBuilder.clazz()`，即 `$self`），否则 `invalidInsn`。解析起点取 `bodyBuilder.clazz().getSuperName()`（词法父类），不依赖 receiver 变量类型的父类。
2. `BackendMethodCallResolver.resolveSuper(bodyBuilder, receiverVar, methodName, argVars)`（新增；2026-09-09 实施时签名补入 `receiverVar`，词法 self 校验收敛在 resolver 内对所有调用方强制）：
   - receiver 静态类型必须恰为 `bodyBuilder.clazz()`（词法 self 不变量），否则 `invalidInsn`；
   - `bodyBuilder.clazz()` 的 `superName` 为空 → `invalidInsn`（无父类）；
   - 以 `GdObjectType(superName)` 为起点调用 `ScopeMethodResolver.resolveInstanceMethod(...)`——owner 是"最近的祖先实现"（GDCC 祖父类也可能成为 owner），即 super 语义；
   - `DynamicFallback` → `invalidInsn`（super 调用必须静态可解析）；`Failed` → `invalidInsn`；解析到 static 方法 → `invalidInsn`（super 是实例语义，无 static 形态，不像 CALL_METHOD 仅告警）；
   - `super._init` 不走本指令（`_init` 被 resolver 拒绝；构造路径仍是 `entry.c.ftl:294-313` 的递归 constructor），文档注明。
3. 生成：
   - owner GDCC → `emitResolvedCall` 直接调用 `<Owner>_<m>`，**绕过 vtable**（即使该方法 polymorphic——super 语义要求固定父类实现）；receiver（`$self`）经既有 `renderReceiverValue` 沿 `_super` 链 upcast 到 owner；
   - owner ENGINE → 复用既有 exact engine helper 路径；
   - coroutine → `emitCoroutineStartCall` 直接调 owner 的 start thunk；
   - 缺参补全 / vararg / 结果写入全部复用 `emitResolvedCall` 现有逻辑。
4. 父链完全不可解析时编译期 fail-fast（偏离 `gdcc_low_ir.md` 的"运行时错误"措辞，见 §5 D2：父链在编译期完全可知，静态不可解析即程序错误；文档修订并入 Step 5）。

### 2.6 CALL_METHOD 调用点：直接调用 vs 虚表间接调用（R4）

`CallMethodInsnGen.emitKnownSignatureCall` 中，**在 coroutine 分流之前**（`CallMethodInsnGen.java:200` 的 early-return 之前）插入 `isPolymorphicCall` 判定；仅当 `resolved.mode() == GDCC && !resolved.isStatic()` 时查询，命中后再用 `findVtableSlot(owner, method)` 取 introducer（后者不是闸门）：

- `helper.isPolymorphicCall(receiverVar.type(), resolved.methodName())`（receiver 静态类型 `T` 的模块内真后代覆写该方法，§2.1 等价判定）命中 → 间接路径（从 owner `O` 取 slot 引入者 `I`——`I` 可能不同于 `O`：三层链 `Parent(引入 foo)←Child(覆写)←Grandchild(再覆写)` 中 receiver 类型为 `Child` 时 `O=Child`、`I=Parent`）：
  1. 将 receiver 直接物化为 **slot 引入者** `I` 的 fat self 临时变量（`renderReceiverValue(receiverVar, GdObjectType(I))`，复用既有 `_super` 链 upcast；`I` 是 receiver 静态类型的祖先，assignable 恒成立）：
     ```c
     <gdcc_I_fat_ptr> $vt_recv = <receiver 按 I 渲染的表达式>;
     ```
     物化避免 callee 与首参双重求值；同时消除"owner fat 类型 vs 引入者签名 self 类型"的不匹配（slot 函数指针的 self 是 `I` 的 fat 类型，直接传 owner/子类 fat 无法通过 C 类型检查）。
  2. callee 表达式（accessor 与成员访问都在引入者层段，**无需**任何 `->_super` 导航）：
     `<I>_class_vtable($vt_recv.ptr)->m_<method>`；
  3. 缺参补全、vararg、结果类型检查复用现有 `validateFixedArgsAndCompleteDefaults` 产物，首参传 `$vt_recv`；
  4. 命中后按原分流分别走 `emitResolvedCall` / `emitCoroutineStartCall` 的间接变体（callee 替换即可）；coroutine 结果目标规则不变（`compiler::GdccCoroState`）。
  5. 若 receiver 静态类型 ≠ `I`，确保 receiver→`I` 的 fat upcast helper 已被 `CObjectFatPtrCollector` 收集（与既有 owner upcast 同一收集通道，Step 4 需验证该收集对新路径可见）。
- 未命中 → 现状直接调用（包括"覆写了 engine virtual 但没有 GDCC 后代再覆写"的方法，符合 R4 括号条款）。
- `resolved.isStatic()`（实例语法调静态方法，现有 warn 路径）不查 vtable。
- ENGINE / BUILTIN / 动态路由完全不变。

**CBodyBuilder 新增间接调用 API**（2026-09-09 修订：callee 构造内移，替代原 `callAssignIndirect(target, calleeExpr, ...)` / `callVoidIndirect(...)` 形态）：`callAssignVtableSlot(target, slot, vtRecv, returnType, args, varargs)` / `callVoidVtableSlot(slot, vtRecv, args, varargs)`。callee 表达式（`<I>_class_vtable(<vtRecv>.ptr)->m_<method>`）由 CBodyBuilder **内部**经 `CGenHelper.renderVtableSlotCalleeExpr` 渲染，并 fail-fast 校验 `vtRecv` 必须承载 slot 引入者 fat 类型且调用首参与 `vtRecv` 为同一对象——调用者只提供 slot 与物化 temp，callee 与首参由此单一来源派生。与 `callAssign/callVoid` 的差异：不做 `recordUsedGodotBindingCall`（callee 是表达式而非 binding 符号，§1.3），对象返回按内部 fat-ptr 产物处理（vtable 槽指向内部 GDCC 函数，等价于直接 GDCC 调用的 `PtrKind.FAT_PTR` 路径），其余 temp 声明/析构、discard 语义保持一致。

### 2.7 命名与符号冲突注册

命名分两层（冻结，与 `gdcc_facing_class_name_contract.md` 的既有分层一致）：

| 层 | 类名分量 | 适用符号 | 例子（inner class `Outer__sub__Inner`） |
|---|---|---|---|
| Godot / identity / wrapper | **raw canonical**（含 `__sub__`，与 `struct ${classDef.name}`、`<C>_object_ptr` 一致） | vtable typedef / 实例 / accessor / trampoline / `_vtable` 字段 | `gdcc_Outer__sub__Inner_vtable`、`Outer__sub__Inner_class_vtable` |
| fat_ptr / upcast helper | **`cIdentifier()` 归一**（连续下划线折叠），一律经 `helper.renderObjectFatPtrStorageType(...)` 渲染，禁止手写拼接 | slot 签名中的 self 类型、trampoline 体内的 fat 类型、`$vt_recv` 声明类型 | `gdcc_Outer_sub_Inner_fat_ptr` |

| 符号 | 形态 | 说明 |
|---|---|---|
| vtable 类型 | `gdcc_<C>_vtable` | typedef，compiler 前缀 |
| vtable 实例 | `gdcc_<C>_vtable_inst` | `static const` |
| accessor | `<C>_class_vtable(<C>* self)` | 与 `_class_*` machinery 同族，**登记进 `validateFileScopeSymbolsDisjoint`**；用户方法 `class_vtable` 撞名 → fail-fast（与 `_object_ptr` 等既有 machinery 同一冲突模型） |
| trampoline | `gdcc_<D>_vslot_<m>` | `static` |
| 结构体字段 | `_vtable` | 根类，`const void*` |
| slot 字段 | `m_<method>` | vtable struct 内（引入者层段声明一次），无需文件级登记 |

## 3. 分步实施与验收细则

> 通用要求：每步完成后跑该步列出的 targeted 测试（`script/run-gradle-targeted-tests.sh --tests ...`）；禁止为通过测试而修改既有断言来接受新行为——既有 golden 若因布局/转发合理变化需更新，必须在该步说明中列出清单并人工核对 diff。

### Step 1：vtable 规划器 `CVtablePlanner`（纯分析，不改生成输出）

- **实施状态：已完成（2026-09-08）**。
    - 验收：`CVtablePlannerTest`（25 个用例）全绿；`./gradlew classes --no-daemon --info --console=plain` 通过；回归 `gd.script.gdcc.backend.c.gen.*` 全包及 `GdScriptUnitTestCompileRunnerTest`、`GdScriptEngineVirtualOverrideRuntimeTest` 全绿。
    - 实施要点回填：
        - `CVtablePlanner`（`src/main/java/gd/script/gdcc/backend/c/gen/CVtablePlanner.java`）构造期完成全部规划；GDCC 父边仅在父类**同时**存在于模块列表与 registry 时承认（容错规则）；覆写目标按"最近祖先声明"解析，排除项（`_init`/static/hidden/lambda）声明会断开覆写链（其下同名方法视为全新声明，不触发 D1）；final overrider 沿链取最近非排除声明。
        - D1 冲突与"具体类未实现 abstract slot"抛 `CodegenException`（含两类名与方法名）；继承环抛 `IllegalStateException`；coroutine slot 经 `VtableSlot.coroutine` 暴露（start thunk 规划，D4）。
        - `CGenHelper` 构造链挂载 planner，`CGenHelper.vtablePlanner()` 暴露只读查询。
    - 既有断言更新清单（已人工核对 diff，均属"fail-fast 提前"的合理变化）：
        - `CCodegenTest.generateFailsFastOnModuleInheritanceCycle`：环检测提前到 `prepare()`（planner 构造期），断言改为覆盖 prepare+generate 序列；异常仍为 `IllegalStateException`，检测语义不变。
        - `BackendPropertyAccessResolverTest.resolveObjectPropertyFailsOnInheritanceCycle`：环在 helper 构造期抛 `IllegalStateException`（原断言为 resolver 路径的 `InvalidInsnException`）；resolver 自身的环检测转为防御性兜底。
- 改动：
    - 新增 `src/main/java/gd/script/gdcc/backend/c/gen/CVtablePlanner.java`：输入模块类列表 + `ClassRegistry`，输出每类 slot 列表、final overrider / introducer 映射、调用点判定谓词 `isPolymorphicCall(receiverTypeName, methodName)`（receiver 真后代覆写，§2.1 等价判定）、slot 查询 `findVtableSlot(ownerClassName, methodName)`，以及只读角色查询 `slotted`/`introducesSlot`/`pass-through` 和两个**全角色**查询（无对应角色时返回空）：
      ```
      vtableInstanceType(C):      introducesSlot(C) → gdcc_<C>_vtable
                                  仅覆写 / pass-through → 最近 introducesSlot 祖先的 typedef
                                  !slotted(C) → 空
      resolvedVtableSymbol(C):    introducesSlot / 仅覆写 → gdcc_<C>_vtable_inst
                                  pass-through → 最近非 pass-through 祖先已解析的同一符号
                                                 （递归；该祖先可以是仅覆写类，不必是 introducer）
                                  旁支 → NULL；整层无 slot → 空
      ```
      注意两个查询的"祖先"是两种不同关系（typedef 链跟最近 introducesSlot 祖先，表值链跟最近非 pass-through 祖先），实施与测试必须区分；
      实现 §2.1 算法、D1 冲突 fail-fast、abstract/`!slotted` 规则、**planner 容错规则**（registry 查不到父类 → 按 `slots(C)=[]` 处理，不抛错）。
    - `CGenHelper` 构造链挂载 planner（`CCodegen.prepare` 路径 registry 完备；直接构造路径靠容错规则兜底），暴露只读查询。
    - GDCC↔GDCC 签名兼容判定新入口（两侧跳 leading self + coroutine/static/vararg/hidden/lambda 检查，§2.1）。
- 测试：新增 `CVtablePlannerTest`：
    - 无覆写层级 → 全部类 `!slotted`；
    - 查询哨兵：整层无 slot → `vtableInstanceType` 与 `resolvedVtableSymbol` 均为空（不是 NULL）；旁支（`Root←A 引入 foo` / `Root←B 无方法`）→ `vtableInstanceType(B)` 为空、`resolvedVtableSymbol(B)` 为 NULL；
    - 两段/三段覆写链 → slot 复用与前缀顺序、final overrider / introducer 正确；
    - **pass-through 中间类层级 `A(引入 foo)→B(pass-through)→C(引入 bar)`** → `slots(B)` 含 `foo` 且 introducer=A；C 的 slot 前缀直接接 A（跳过 B）；`pass-through(B)` 为 true、`resolvedVtableSymbol(B)` 解析为 `gdcc_A_vtable_inst`（生成层的"不产出符号"断言在 Step 2）；
    - 仅覆写不引入的类 → `introducesSlot` 为 false、`vtableInstanceType` 为最近 introducesSlot 祖先的 typedef、`resolvedVtableSymbol` 为本类实例符号 `gdcc_<C>_vtable_inst`；
    - **typedef 链与表值链分离夹具**（`A(引入 foo)→B(仅覆写 foo)→C(pass-through)`）→ `resolvedVtableSymbol(C)` = `gdcc_B_vtable_inst`（不是 `gdcc_A_vtable_inst`），`vtableInstanceType(B)` = `vtableInstanceType(C)` = `gdcc_A_vtable`；
    - D1 不覆盖排除项：父子 `_init` 签名不同、static 同名等 → 不抛错；
    - **兄弟分支夹具**（`B.foo` 因 `Child1` 覆写而引入 slot，`Child2 extends B` 不覆写）→ `foo ∈ slots(Child2)` 且 introducer=B，但 `isPolymorphicCall(Child2, foo)` 为 false、`isPolymorphicCall(B, foo)` 为 true（锁定"不得以 findVtableSlot 命中充当闸门"）；
    - 覆写 engine virtual 但无 GDCC 后代再覆写 → 无 slot（R4 括号条款）；GDCC↔GDCC 最终覆写者（无真后代再覆写）→ 对该类的 `isPolymorphicCall` 为 false（调用点保持直接调用），但其 slot 仍存在于继承表中；
    - 签名不兼容同名后代方法 → 抛错（D1）；**coroutine 覆写非协程（或反之）→ 抛错**；
    - abstract 引入（夹具形状：`A(abstract foo)` + 兄弟 `C(实现 foo)` 引入 slot，具体类 `B extends A` 未实现）→ 具体类 B 因继承 slot 无实现而 fail-fast；abstract 类自身 slot 为 NULL 条目；
    - coroutine 覆写链 → slot 指向 start thunk 规划；
    - static/hidden/lambda/`_init` 排除；
    - 父类未注册进 registry 的直接构造场景 → `!slotted` 容错，不抛错。
- 验收：`script/run-gradle-targeted-tests.sh --tests CVtablePlannerTest` 全绿；`./gradlew classes --no-daemon --info --console=plain` 通过。

### Step 2：vtable 布局与模板生成（结构落地，不改调用点）

- **实施状态：已完成（2026-09-08）**。
    - 验收：`script/run-gradle-targeted-tests.sh --tests CVtableCodegenTest,CCodegenTest` 全绿（`CVtableCodegenTest` 10 个用例）；回归 `gd.script.gdcc.backend.c.gen.*` 全包及 `GdScriptUnitTestCompileRunnerTest`、`GdScriptEngineVirtualOverrideRuntimeTest` 全绿（后者经 zig 编译 + Godot 运行，证明含 vtable 的生成 C 代码可编译可运行）。
    - 实施要点回填：
        - `CGenHelper` 新增 vtable 渲染区段（单一发布点）：`requiresVtableField`（层级判定 = `resolvedVtableSymbol` 非空，旁支 `NULL` 也要求字段存在）、`renderVtableInstanceTypeName`、`renderVtableSuperMemberDecl`（vtable `_super` 链，跳过非 introducer）、`renderVtableSlotMemberDecl`（slot 函数指针签名，self 为引入者 fat 类型；coroutine slot 用 start thunk 签名 `godot_Object*`）、`renderVtableInstanceSymbol`、`renderVtableAccessorName`、`renderVtableFieldAccessExpr`（沿 **wrapper 链**直达根字段，经 registry 逐级走直接父类，pass-through 不跳过）、`renderVtableFieldInitExpr`（四分支 RHS：空/`NULL`/`&实例符号`）、`renderVtableInstanceInitializer`（C99 嵌套指定初始化器，按 introducer 分段、final overrider 直填/ trampoline/abstract hole 填 `NULL`）、`renderVtableTrampolineName`/`renderVtableTrampolineDefinition`（体内含唯一获准的 GDCC 下行裸 cast 复合字面量）。
        - `entry.h.ftl`：vtable typedef 段置于 `object_fat_ptr_types.h` include 之后、wrapper struct 之前，按 `inheritanceOrderedClassDefs` 序生成（父表先完整）；根类 `_object` 后条件插入 `const void* _vtable;`；accessor 声明仅 introducer。
        - `entry.c.ftl`：trampoline+实例段置于 default userdata 段之后、bind methods 之前，**按 `inheritanceOrderedClassDefs`（base-before-derived）发射**——子类实例会取**祖先** trampoline 的地址（祖先仍是继承 slot 的 final overrider 时），而 static trampoline 无头文件原型，必须定义先于使用；同类内保持 trampoline 先于本类实例；accessor 定义紧随 `_object_ptr` helpers 之后；create_instance 在 `set_object_ptr` 之后、`godot_object_set_instance`/POSTINITIALIZE 之前写入 `_vtable`。
        - `CCodegen.validateFileScopeSymbolsDisjoint`：**条件登记** `<C>_class_vtable`（仅 `introducesSlot` 类）——accessor 只为 introducer 生成，slotless 类的用户方法 `class_vtable` 保持合法；撞名时与既有 machinery 同一 fail-fast 模型。
    - 既有断言更新清单：无（`CCodegenTest` 全量零 diff 通过——整层无 slot 的既有 golden 不受模板改动影响）。
    - 审阅回填：`review-expert-a` 初审"有条件通过"——高风险 1 条（trampoline/实例段按 `module.classDefs` 源文件序发射，derived-first 时子类实例引用未定义的祖先 trampoline，C99 取址未声明）已修复为 `inheritanceOrderedClassDefs` 并补 derived-first 非空转回归；低风险 2 条（连续 introducer typedef 锚点已补；`gdcc_` 前缀符号登记维持计划范围、评审确认不上调）。复核结论：**通过**。`review-expert-c` 独立复核（C17 语义、符号顺序、边界形态推演）：**通过**，无高/中/低风险问题。
    - 测试锚定回填（`CVtableCodegenTest`，正反对照）：
        - 全布局链路（`A(引入 foo)→B(pass-through)→C(引入 bar)→D(覆写两者)`）：根字段位置、typedef 前缀跳过 pass-through、accessor 沿 wrapper 链直达根字段（不递归父 accessor）、实例 final overrider 三分支（直填/trampoline/嵌套初始化器）、void 与非 void trampoline 形态、create_instance branch 3/4 与 POSTINITIALIZE 顺序、pass-through 禁止写 NULL；
        - 旁支夹具（`Root + A(引入)+AChild(覆写) + B(旁支)`）：根有字段、Root/B 写 `NULL`（防塌成 branch 1 无赋值）、A/AChild 写本类实例；
        - typedef 链与表值链分离（`A(引入)→B(仅覆写)→C(pass-through)`）：无 `gdcc_B_vtable` typedef/accessor，`gdcc_B_vtable_inst` 以 `gdcc_A_vtable` 为类型含 trampoline，`C` 的 create_instance 写 `&gdcc_B_vtable_inst`（非 A 的实例）；
        - 整层无 slot：两份 entry 文件完全不出现 "vtable" 子串（模块名已避开该子串）；
        - **derived-first 模块序回归**（评审高风险修复锚定）：`DfA(引入 m1)→DfB(覆写 m1)→DfC(引入 m2)→DfD(覆写 m2)` 以子类在前顺序构造模块，断言祖先 trampoline `gdcc_DfB_vslot_m1` 的 static 定义先于引用它的 `gdcc_DfC_vtable_inst`（已实证回退为 `module.classDefs` 时该断言失败，测试非空转）；
        - **连续 introducer / §2.2 四级混合链**：`MxA(根，无方法)→MxB(引入 m1)→MxC(引入 m2)→MxD(覆写 m1、m2)` → `gdcc_MxC_vtable` 嵌相邻 introducer `gdcc_MxB_vtable _super;`（不跳更远祖先）、`gdcc_MxB_vtable` 无 `_super`、slotless 根 MxA 写 `NULL`（中途引入形态）、MxD 实例以 `gdcc_MxC_vtable` 为类型含双 trampoline；
        - inner class：vtable 符号保留 raw canonical（`gdcc_Outer__sub__Base_vtable`），slot 签名 fat 类型归一（`gdcc_Outer_sub_Base_fat_ptr`）；
        - `class_vtable` 撞名：introducer 上撞名 fail-fast（含符号名）；非 introducer 上同名用户方法合法（锚定条件登记）；
        - coroutine slot：签名为 `godot_Object*`，实例条目 `<C>_<m>__coro_start`，trampoline 调 start thunk；
        - abstract 引入 slot：abstract 类实例条目填 `NULL`，具体实现类填 trampoline。
- 改动：`entry.h.ftl`（vtable typedef 按继承序、根类 `_vtable` 字段、accessor 声明）、`entry.c.ftl`（accessor 定义、trampoline、vtable 实例、create_instance 初始化赋值）、`CGenHelper`（渲染方法：vtable 类型名/实例名/accessor 名/根字段访问链/trampoline 名/层级判定）、`CCodegen.validateFileScopeSymbolsDisjoint`（`<C>_class_vtable` 登记）。
- 测试：新增 `CVtableCodegenTest`（必要时并入 `CCodegenTest`）：
    - 含 polymorphic 层级的 `entry.h`：根类结构体 `_object` 后紧跟 `const void* _vtable;`；子表 typedef 以**最近 `introducesSlot` 祖先**的 `gdcc_<P>_vtable _super;` 为首成员（覆盖 `A(引入 foo)→B(pass-through)→C(引入 bar)` 层级：C 的表直接嵌 `gdcc_A_vtable`，B 无任何 vtable 符号）；accessor 声明；
    - `entry.c`：accessor 沿 wrapper 链直达根字段（不递归父 accessor）；实例条目为 final overrider（引入类直填、覆写类填 trampoline、嵌套指定初始化器形态如 `._super = { .m_foo = gdcc_C_vslot_foo }`）；trampoline 体含 downcast 复合字面量；create_instance 四分支（§2.3）：整层无 slot 不生成赋值 / 旁支写 NULL / 含内容写本类实例（**含仅覆写不引入**：无 `gdcc_<C>_vtable` typedef，但有以最近 `introducesSlot` 祖先 typedef 声明的 `gdcc_<C>_vtable_inst`，create_instance 写该符号）/ pass-through 写最近非 pass-through 祖先的表值（禁止 NULL），且赋值在 POSTINITIALIZE 之前；
    - 旁支 golden 夹具形状（防止塌成 branch 1）：`Root`（无方法）+ 子 `A`（引入 `foo`，被 `AChild` 覆写）+ 旁支子 `B`（无方法）→ 根有 `_vtable` 字段、`B_class_create_instance` 写 `NULL`、`A_class_create_instance` 写本类实例；
    - typedef 链与表值链分离 golden：`A(引入 foo)→B(仅覆写 foo)→C(pass-through)` → `C_class_create_instance` 写 `&gdcc_B_vtable_inst`（类型 `gdcc_A_vtable`，条目含 `gdcc_B_vslot_foo`）；
    - 整层无 slot 的层级 → 无字段、无 typedef、无实例（既有全部 golden 零 diff 是本轮最强回归信号）；
    - **inner class 继承链**（`Outer__sub__Inner`）→ vtable 符号使用 raw canonical 类名；
    - 用户方法命名 `class_vtable` 与 accessor 撞名 → `validateFileScopeSymbolsDisjoint` fail-fast（对标既有 `_object_ptr` 冲突模型）。
- 验收：`script/run-gradle-targeted-tests.sh --tests CVtableCodegenTest,CCodegenTest` 全绿。

### Step 3：engine virtual 父类转发（R1/R2）

- **实施状态：已完成（2026-09-09）**。
    - 验收：`script/run-gradle-targeted-tests.sh --tests CCodegenTest,GdScriptEngineVirtualOverrideRuntimeTest,GdScriptUnitTestCompileRunnerTest` 全绿（runtime 套件 6 用例含新 fixture 实跑通过，非 skipped）；回归 `gd.script.gdcc.backend.c.gen.*` 全包全绿。
    - 实施要点回填：
        - `entry.c.ftl`：`get_virtual_with_data` 尾部在 `helper.checkGdccClassByName(classDef.superName)` 时由 `return NULL;` 改为 `return <P>_class_get_virtual_with_data(p_class_userdata, p_name, p_hash);`，engine 父类保持 `return NULL;` 不变；`call_virtual_with_data` 尾部在同条件追加 `<P>_class_call_virtual_with_data(p_instance, p_name, p_virtual_call_userdata, p_args, r_ret);`，位于全部本类 userdata 分支之后（editor 门留在本类命中分支内）。
        - 跨类引用顺序无新约束：两个 virtual 函数均由 `entry.h.ftl:111-113` 提前声明，子类函数体引用父类符号与模块类序无关。
        - 符号命名沿用 raw canonical 类名（与 `_class_destructor` 等既有 machinery 一致），无新增文件级符号，无需登记冲突表。
    - 既有断言更新清单：无（`CCodegenTest`/`CVtableCodegenTest` 全量零 diff 通过——既有断言均未钉住 GDCC 父类场景的 `return NULL;` 尾部）。
    - 测试锚定回填（正反对照）：
        - `CCodegenTest.generateShouldForwardVirtualCallbacksToGdccParent`：子类两函数尾部转发 + 本类分支先于转发（`assertOrdered`）+ `_process` editor 门留在本类命中分支内 + 转发后无 `return NULL;`；父类（engine 父）保持 `return NULL;` 且无转发调用（反向锚定）。
        - `CCodegenTest.generateShouldForwardVirtualCallbacksForSubclassWithoutOwnOverrides`：本类零 virtual 覆写 → 两函数无本类分支（反向）但尾部仍转发。
        - `CCodegenTest.generateShouldChainVirtualForwardingAcrossMultipleGdccLevels`：三级链逐跳转发（Leaf→Mid、Mid→Root），叶类不直接跳到根（反向锚定"逐级"语义）。
        - runtime fixture `runtime/virtual/ready_parent_chain_dispatch.gd`：顶层 Node harness + inner `Parent`/`ChildFallback`（仅 `pass`，不覆写）/`ChildOverride`（覆写 `_ready`），harness `_ready` 内 `add_child` 两子实例纯 engine 驱动；validation 断言 `ChildFallback` 实例父实现计数=1（修复锚点）、`ChildOverride` 实例子实现=1 且父实现=0（R2 最派生分派不回归）。已实证回退模板改动后该 fixture 在 Godot 实跑中失败（red→green 锚定）。
        - runtime fixture `runtime/virtual/engine_virtual_three_level_forwarding.gd`（2026-09-09 补）：`_process` 经三级 GDCC 链（`LeafPassThrough→Mid→Base`）逐跳转发由 engine 驱动计数；覆写孙类 `LeafOverride` 只计自身、base 计数恒 0（深层遮蔽不回归）。已登记 `GdScriptEngineVirtualOverrideRuntimeTest.SCRIPT_RESOURCE_PATHS`。
    - 审阅回填：`review-expert-a` 审阅结论**通过**，无高/中/低风险问题；逐项确认转发条件/参数透传/editor 门顺序/声明先于使用/偏移 0 ABI/默认 userdata 地址身份/夹具合同/文档一致性，并对照 Step 3 测试清单逐条确认覆盖无遗漏。
- 改动：`entry.c.ftl` 两个 virtual 函数尾部转发段（§2.4）。
- **既有 golden 影响清单（须人工核对 diff）**：所有"有 GDCC 父类"的类（如现有继承用例中的 `GDChildNode`、`GDLeafNode`、inner 继承链等），其 `get_virtual_with_data` 尾部从 `return NULL;` 变为 `return <P>_class_get_virtual_with_data(...)`，`call_virtual_with_data` 尾部追加转发调用；engine 父类的类不变化。
- 测试：
    - golden（扩展 `CCodegenTest` 现有 virtual 用例形态）：GDCC 父类的子类两函数尾部含转发；父类为 engine 类时无转发段（`return NULL;` 结尾不变）；新增"有 GDCC 父类、本类无 virtual 覆写"用例：本类分支为空 + 尾部仍转发；editor 门断言保持在本类命中分支内、转发在其后（golden 层验证，**不进 runtime**——headless 游戏模式观测不到 editor 抑制，见既有 `tool_process_runtime.gd` 夹具注释）。
    - runtime anchor（扩展 `GdScriptEngineVirtualOverrideRuntimeTest` + 新增 suite 夹具，遵守 `frontend_engine_virtual_override_implementation.md` §5 与 `test_suite.md` 合同：engine 驱动、禁止主动调 helper/`set_process` 伪造）：
        - 夹具形态：顶层 Node harness 类 + inner `Parent`/`Child`（`classDefs.getFirst()` 之外的类由 harness 以 `add_child(Child.new())` 驱动）；
        - 用例 1：父覆写 `_ready` + 子未覆写 → 子实例触发父实现；
        - 用例 2：父子均覆写 `_ready` → 只触发子实现；
        - 同步更新 `GdScriptEngineVirtualOverrideRuntimeTest.SCRIPT_RESOURCE_PATHS` 与 `GdScriptUnitTestCompileRunnerTest.EXPECTED_SCRIPT_PATHS`。
- 验收：`script/run-gradle-targeted-tests.sh --tests CCodegenTest,GdScriptEngineVirtualOverrideRuntimeTest,GdScriptUnitTestCompileRunnerTest` 全绿（runtime 用例在具备 Godot 环境下通过，环境缺失按 suite 规则跳过）。

### Step 4：CALL_METHOD 调用点间接分发（R4）

- **实施状态：已完成（2026-09-09）**。
    - 验收：`script/run-gradle-targeted-tests.sh --tests CallMethodInsnGenTest,CallMethodInsnGenEngineInheritanceTest,CCodegenTest,CVtableCodegenTest` 全绿（engine 继承 runtime 2 用例实跑非 skip）；回归 `gd.script.gdcc.backend.c.gen.*` 全包及 `GdScriptUnitTestCompileRunnerTest`、`GdScriptEngineVirtualOverrideRuntimeTest` 全绿。
    - 实施要点回填：
        - `CBodyBuilder`：`callVoid`/`callAssign` 主体提取为私有 `emitVoidCall`/`emitAssignCall`；新增 `callVoidVtableSlot`/`callAssignVtableSlot`（2026-09-09 方案 A 重构，替代初版 `callVoidIndirect`/`callAssignIndirect`）——签名收 `(slot, vtRecv)` 结构化参数，callee 表达式由私有 `requireVtableSlotCalleeExpr(slot, vtRecv, args)` 内部渲染（委托 `CGenHelper.renderVtableSlotCalleeExpr`），并 fail-fast 校验 vtRecv 承载 slot 引入者 fat 类型且首参与 vtRecv 为同一对象；与直调共享同一发射体，仅跳过 `recordUsedGodotBindingCall`（callee 是 vtable 槽成员表达式而非 `godot_*` binding 符号，§1.3）。对象返回自然落入 `resolveCallResultPtrKind` 的 FAT_PTR 分支（callee 表达式不匹配 `godot_` 前缀），与直接 GDCC 调用一致；temp 声明/析构、vararg 尾部、discard 语义零变化（重构前后生成输出逐字相同，全量 golden 零 diff 通过）。
        - `CGenHelper` 新增三方法（vtable 渲染区段）：`isPolymorphicCall(GdType, String)`（仅 GdObjectType 委托 planner，唯一合法闸门）、`findVtableSlot`（薄委托，永不当闸门）、`renderVtableSlotCalleeExpr`（`<I>_class_vtable(<recvPtrExpr>)->m_<method>`，accessor 与槽成员同在引入者层段，无 `->_super` 导航）。
        - `CallMethodInsnGen.emitKnownSignatureCall`：闸门插在 coroutine 分流**之前**，条件 `mode()==GDCC && !isStatic() && helper.isPolymorphicCall(receiverVar.type(), methodName)`；新增 `emitPolymorphicCall`——`findVtableSlot` 取引入者（空 → invalidInsn 钉 planner 不变量），`renderReceiverValue` 复用既有安全 upcast 把 receiver 物化为引入者 fat self temp（`__gdcc_tmp_vt_recv_N`，非持有引用故无需 destroy）；私有 record `IndirectCallShape(slot, vtRecv)` 作为单一来源贯通 `emitResolvedCall`/`emitCoroutineStartCall` 的间接变体（callee 由 CBodyBuilder 从 slot+vtRecv 派生、首参为 vtRecv，其余规则不变；coroutine 结果仍 `compiler::GdccCoroState`）。
        - `validateFixedArgsAndCompleteDefaults` 新增 `receiverArgOverride` 参数：仅替换调用的首参，实例 `default_value_func` 仍收原始 receiverVar 按 owner 类型渲染（缺省参数属于静态解析出的 owner 签名）。
        - upcast helper 收集（§2.6 第 5 条）：无需新机制——receiver 静态类型与引入者类型都经模块类/变量类型进入 `CObjectFatPtrCollector` 既有通道，端到端 golden 已断言 `object_fat_ptr_types.h` 含 `gdcc_GeB_fat_ptr_upcast_to_GeA(`。
    - 既有断言更新清单：无（`CCodegenTest`/`CVtableCodegenTest`/`CallMethodInsnGenTest` 全量零 diff——闸门仅在"receiver 真后代覆写"时命中，既有 golden 无此形态）。
    - 测试锚定回填（正反对照）：
        - `CallMethodInsnGenTest`：polymorphic 命中 → `VtBase_class_vtable(__gdcc_tmp_vt_recv_0.ptr)->m_foo(__gdcc_tmp_vt_recv_0);` 且无 `VtBase_foo(`/`VtDerived_foo(`；三层链 mid 型 receiver → 物化为 `gdcc_VtRoot_fat_ptr` 且经 `gdcc_VtMid_fat_ptr_upcast_to_VtRoot`，无 `VtMid_foo(`；两层最终覆写者 → `VtFinal_foo($child);` 且无 `_class_vtable(`/`vt_recv`（去虚锚点）；GDCC static 经实例语法（后代有同名实例方法）→ warn + `VtStaticBase_make();` 直调且无 vtable 形态（静态闸门锚点）；polymorphic coroutine → `$state = CoroRoot_class_vtable(...)->m_fire(...)`，无 `CoroRoot_fire__coro_start(`，且 `gdcc_coro_state_slot_destroy` 先于写入（slot-write 顺序不变）。
        - `CVtableCodegenTest.threeLevelChainWithMidLayerCallSiteGeneratesVtableDispatchEndToEnd`：端到端 golden——中间层调用点间接形态（`assertOrdered` 物化→调用）+ 三层实例表 trampoline 条目 + `object_fat_ptr_types.h` upcast helper 收集可见性 + 调用点无 `GeB_foo(`/`GeA_foo(` 反例。
        - `CallMethodInsnGenEngineInheritanceTest.callMethodPolymorphicVtableDispatchShouldWorkInRealGodot`：Zig+Godot 实跑单测五场景——root 型持有 leaf/mid/root 实例分别分派 3/2/1（R4 主锚点）、mid 型持有 leaf 分派 3（owner≠introducer 实跑）、leaf 型直调返 3（去虚）、pass-through 链 A 型持有 C/B 实例分派 30/10（共享表值不空解引用、B 无任何 vtable 符号）、兄弟分支 plain 型直调 `GDSibBaseWorker_baz(...)` 返 100（slot 存在但调用点不间接）；entry.c 函数体级正反断言 + GDScript 分场景独立错误消息。
        - test_suite runtime 夹具（2026-09-09 补，真实 Godot 端到端资源对，全部经 `GdScriptUnitTestCompileRunnerTest` runtime 工厂执行）：`runtime/virtual/dispatch_three_level_chain.gd`（三级链 root/mid/leaf 静态型分派 1/2/3 + owner≠introducer + 去虚）；`dispatch_pass_through.gd`（pass-through 中间类共享表值，A/B 静态型持有 B/C 实例分派 10/30）；`dispatch_sibling_branch.gd`（兄弟分支 plain 型直调 base 实现返 100，Left 分支间接返 1）；`dispatch_deep_chain.gd`（五级链覆写只在 L1/L3/L5，八个静态型×实例组合断言最近祖先实现）；`dispatch_template_method.gd`（模板方法形态——基类 `greet()` 内 self 调 `name()` 经 vtable 分到孙类覆写）；`dispatch_control_flow.gd`（多态调用置于 for+match+continue/break、三元、while 复杂控制流——`Shape` 基类元素走 `continue` 过滤活路径，含 `Array[Shape]` typed 容器跨 ABI 回传）；`dispatch_coroutine_override.gd`（`BaseWorker` 静态型持有 `LeafWorker` 实例 `await run()` 经 vtable start thunk 分派到叶实现返 2）。`mixed_engine_virtual_and_vtable.gd`（engine `_ready` 转发通道与 GDCC vtable `label()` 通道对同一实例的一致性，已登记 `GdScriptEngineVirtualOverrideRuntimeTest.SCRIPT_RESOURCE_PATHS`）。本步新增 8 对 dispatch/mixed 夹具（Step 3 另补 `_process` 三级转发 1 对）实跑全绿（runtime 工厂 46 例 0 失败）；审阅后加强：`dispatch_deep_chain.gd` 补 `l5_via_l2`/`l5_via_l4`（pass-through 静态型 × 最深实例，防 L2/L4 被错误去虚成 L1/L3）。
    - 审阅回填：`review-expert-a` 审阅结论**通过**，无高/中风险问题；3 条低风险已处理——(1) `resolveFunctionBody`/`resolveFunctionBodyByPrefix` 注释冻结"entry.c 用户方法无前置原型（原型在 entry.h），首个前缀匹配即定义"合同；(2) 补两条形态锚定 golden：`callMethodPolymorphicObjectReturnShouldKeepFatPtrResultPath`（对象返回无 `_from_raw`，锚定 FAT_PTR 内部产物路径）与 `callMethodPolymorphicDefaultValueFuncShouldKeepOwnerReceiverForDefault`（default_value_func 收原始 receiver 按 owner 渲染、vtable 首参为 vt_recv、物化顺序钉定）；(3) 文首"尚未实施"与 D4"待确认"措辞属 Step 6 文档收口范围，按计划保留。
    - 方案 A 重构审阅回填（2026-09-09，callee 构造内移）：`review-expert-a` 结论**通过**，无高/中风险；2 条低风险已处理——私有渲染器按项目命名约定改名 `requireVtableSlotCalleeExpr`、补"首参与 vtRecv 同一对象"身份校验；§1.3 调研段过时行号留待 Step 6 统一收口。
- 改动：`CBodyBuilder.callAssignVtableSlot/callVoidVtableSlot`（§2.6，2026-09-09 方案 A 重构：callee 构造内移）、`CallMethodInsnGen.emitKnownSignatureCall` 在 coroutine 分流前接入 `isPolymorphicCall` 判定与 receiver 物化（按 slot 引入者类型）、`CGenHelper` 渲染 accessor/callee 表达式。
- 测试：
    - `CallMethodInsnGenTest` 新增：polymorphic 方法调用（receiver 真后代覆写）→ 出现 `<I>_class_vtable(...)->m_<m>(` 且无直接 `<O>_<m>(` 调用；**owner ≠ introducer 的间接场景用三层夹具**（`Parent(引入 foo)←Child(覆写)←Grandchild(再覆写)`，receiver 静态类型 `Child`）→ receiver 物化为 Parent fat self、首参与 callee 均为 Parent 层段类型；**两层最终覆写者**（`Parent←Child`，无第三层）→ `child.foo()` 保持直接调用 `Child_foo`（R4 括号条款的 GDCC↔GDCC 形态）；静态方法经实例语法调用 → 不查 vtable；coroutine polymorphic → 间接调 start thunk（结果仍 `compiler::GdccCoroState`）；
    - 验证 receiver→introducer 的 fat upcast helper 已被 `CObjectFatPtrCollector` 收集（该收集通道对新调用路径可见）；
    - 扩展现有 engine 继承测试（`CallMethodInsnGenEngineInheritanceTest`）：父类型变量持有子实例调用 polymorphic 方法 → 间接分派到子实现；三层链中 **Child 类型变量调 `foo()`** → 经 vtable 分派到 Grandchild 实现（覆盖 owner≠introducer 间接主路径）；两层链中 **Child 类型变量调 `foo()`** → 直接调用（去虚正确性锚点）；**pass-through 中间类实例经祖先静态类型调用**（`A(引入 foo)→B(pass-through)→C(覆写 foo)`，`A a = B.new(); a.foo()`）→ 读到共享表值、不空解引用、分派到 A 实现；**兄弟分支调用点**（`Child2 c; c.foo()`，覆写发生在 `Child1`）→ 保持直接调用 `B_foo`（不出现 vtable 形态）；
    - golden 端到端：父/子/孙三层 + 中间层调用点多态分发。
- 验收：`script/run-gradle-targeted-tests.sh --tests CallMethodInsnGenTest,CallMethodInsnGenEngineInheritanceTest,CCodegenTest,CVtableCodegenTest` 全绿。

### Step 5：CALL_SUPER_METHOD 实现（R5）

- 改动：`BackendMethodCallResolver.resolveSuper`、新增 `CallSuperMethodInsnGen`、注册进 `CCodegen.INSN_GENS`；`CConstructInsnGenTest.unregisteredOpcodeFailsDispatchInsteadOfSkipping` 的探针改为 `GET_CLASS_NAME`（实施时复核注册表）；**同步修订 `gdcc_low_ir.md` 的 `call_super_method` 措辞**（D2：静态不可解析 → 编译期错误；补词法 super 起点与 `_init` 说明），不拖到 Step 6。
- 测试：新增 `CallSuperMethodInsnGenTest`：
    - GDCC 父方法 → 直接 `<P>_<m>(` 且**不含** vtable 间接形态（绕过验证）；
    - 父未声明、祖父声明 → 解析到祖父 owner；
    - engine 父方法 → exact engine helper 路径；
    - coroutine super → start thunk 直调；
    - receiver 静态类型 ≠ 当前类（词法不变量违反）→ `invalidInsn`；
    - 无父类 / 父链不可解析 → `invalidInsn`（D2）；
    - 缺参补全、vararg、void/结果写入与 `call_method` 同规则；
    - LIR 文本解析 round-trip 断言（`ParsedLirInstruction` 已有解析，补缺）。
- 验收：`script/run-gradle-targeted-tests.sh --tests CallSuperMethodInsnGenTest,CConstructInsnGenTest` 全绿。
- 实施回填（2026-09-09，已完成）：
    - `BackendMethodCallResolver.resolveSuper(bodyBuilder, receiverVar, methodName, argVars)`：词法 super 不变量在 resolver 内强制（receiver 静态类型必须恰为 `bodyBuilder.clazz()`，解析起点固定为其声明 `getSuperName()`，superName 空白 → `invalidInsn`），复用共享 `ScopeMethodResolver.resolveInstanceMethod` 沿父链静态解析最近祖先实现；`DynamicFallback` 与 `Failed`（含 `_init` 的 constructor-route 拒绝）一律转为 `invalidInsn`（D2）。
    - 新增 `CallSuperMethodInsnGen`（注册进 `CCodegen.INSN_GENS`，位于 `CallMethodInsnGen` 之后）：GDCC/ENGINE owner 复用 `CallMethodInsnGen.emitResolvedCall`/`emitCoroutineStartCall` 共享发射器——GDCC owner 直调 `<Owner>_<method>`（**永不查 `isPolymorphicCall`、不走 vtable**，即使目标方法持有 polymorphic slot），engine owner 走 exact helper，coroutine owner 直调 start thunk（static coroutine 异常 IR 守卫与 CALL_METHOD 一致）；receiver 经既有 `renderReceiverValue` 沿 `_super` 链安全 upcast 到 owner，缺参补全/vararg/void/结果写入零差异复用。
    - `CConstructInsnGenTest.unregisteredOpcodeFailsDispatchInsteadOfSkipping` 探针按计划改用 `GET_CLASS_NAME`（该 opcode 有 LIR 解析但无 CInsnGen）。
    - `gdcc_low_ir.md` §call_super_method 措辞已修订：词法 super 起点、object 必须为当前类 self、静态不可解析 = 编译期错误（D2）、`super._init` 不落此 opcode、coroutine ABI 引用 call_method。
- 测试回填：`CallSuperMethodInsnGenTest`（16 例：GDCC 父方法直调且断言**无** `class_vtable`/`vt_recv` 形态、祖父 owner 解析、engine exact helper、coroutine start thunk 直调、结果写入、owner default_value_func 缺参补全、vararg 尾部打包、inner class 父类 canonical 命名双层锚定（C 符号保留 `__sub__`、fat helper 用 `cIdentifier` 单层下划线），及词法不变量违反/无父类/父链不可解析（`RECEIVER_METADATA_UNKNOWN`）/已知父类缺方法（`METHOD_MISSING`）/static 父方法（instance-only 守卫）/`super._init`/void 带 resultId/coroutine 缺 result 八条负例）；round-trip 断言按既有 lir 合同测试惯例独立为 `CallSuperMethodInsnContractTest`（11 例，含序列化形态与负例解析）。验收命令加 `CallSuperMethodInsnContractTest` 后全绿；`gd.script.gdcc.backend.c.gen.*` 全包回归全绿。
- 审阅回填：`review-expert-a` 初审结论**通过**，无高风险；2 条中风险已修复——(1) `gdcc_low_ir.md` 的 `compiler::GdccCoroState` 生产者集合扩为 `call_method`/`call_super_method`/`call_static_method`（call_method 小节与 §Coroutine Instructions 两处同步）；(2) super 解析到 static 方法由静默丢 receiver 改为 `invalidInsn`（super 是实例语义，无 static 形态，比 CALL_METHOD 的 warn 更严）。低风险已处理：§2.5 设计签名补 `receiverVar` 实参并记录 static 守卫；`emitResolvedCall` 注释承认第三调用方并冻结"super 必须 `indirect == null`、禁止在本共享流加 vtable 闸门"；DynamicFallback 诊断消息改带 `DynamicFallbackReason`；测试补强（Ghost 断言精确化 + 三个新锚定用例）。engine virtual（如 `super._ready()`）经 exact helper 的 MethodBind 限制属 CALL_METHOD 既有限制被继承，非本步缺陷，不在此处理。

### Step 6：前端 super 语法绑定与语义解析（R5 前端接线 · sema）

- **实施状态：已完成（2026-09-10）**。
    - 验收：`script/run-gradle-targeted-tests.sh --tests FrontendSuperCallSemanticsTest,FrontendSuperCallSupportTest`（19+11 例）全绿；回归 `gd.script.gdcc.frontend.**` 全包全绿。
    - 实施要点回填：
        - **语法形态**（gdparser 0.5.3 实证，无专用 AST 节点）：`super.m(args)` → `AttributeExpression(IdentifierExpression("super"), [AttributeCallStep])`；裸 `super(args)` → `CallExpression(IdentifierExpression("super"), args)`；非调用形态全部 fail-closed——`super.prop`（链式属性步）与 `super.payload[0]`（链式 `AttributeSubscriptStep`）走 step-0 拦截 FAILED，裸 `super` 值 / `super[0]`（`SubscriptExpression` 基位置）走 compile 位置门禁。
        - **绑定**：`FrontendBindingKind.SUPER` 新枚举（仅关键字位置标记，不携带值负载）；`FrontendBodyOwnerProcedures.bindIdentifier` 对 `super` 标识符短路进 `bindSuper`（否则被误报为不可解析标识符），属性初始化器边界与 static 上下文规则镜像 `bindSelf`。
        - **链式解析**：`FrontendChainHeadReceiverSupport.resolveSuperReceiver` 把 super 链头解析为**词法当前类**实例 receiver（`GdObjectType(owningClass)`，与 self 同存储）；`FrontendChainReductionHelper.reduceStep` 在 `stepIndex == 0 && isSuperChainHead(request)` 时进入 `reduceSuperCallStep`——**super 检测走 `ReductionRequest.bindingLookup`（pending 感知视图）而非稳定表**，chain binding 阶段 top-binding 事实尚未 flush（此为本步实证修复的关键坑）。新增 `RouteKind.SUPER_METHOD`；`super.prop` 附 FAILED member 事实（精确锚定诊断），subscript 失败经根表达式传播（镜像既有 subscript 失败路径）。
        - **共享解析**（`FrontendSuperCallSupport.resolveSuperInstanceMethod`，链式与裸调用共用）：从 `GdObjectType(superName)` 起经 **`ScopeMethodResolver.resolveNearestDeclaredInstanceMethod`**（词法 super 专用入口，见下）解析；fail-closed：无父类 / `_init`（构造自动链，禁止显式调用）/ DynamicFallback / static 目标 / GDCC abstract 目标。**engine virtual 的 abstract 标记不适用该拒绝**（engine 侧的 abstract 是 virtual hook，默认空实现仍可经 super 调用——`super._ready()` 是合法锚点）。
        - **词法 super 查找入口**（`ScopeMethodResolver.resolveNearestDeclaredInstanceMethod`，2026-09-10 经 review-expert-c 复核后新增，前后端共用）：沿父链向上，**遇到第一个声明该方法名的 owner 即停止**，仅在该 owner 候选内做参数匹配；普通链式实例解析是"先按参数适用性过滤、再按 owner 距离取最近"，会把参数不适用的近端同名声明跳过去绑定参数恰好适用的远端声明（示例：祖父 `m(int)`、直接父 `m(String)`、`super.m(1)` 被错误解析到祖父）——违反 Godot `get_function_signature` 的 stop-at-first-declarer 语义。后端 `BackendMethodCallResolver.resolveSuper` 同步切换到该入口，前后端目标零漂移。
        - **裸 `super(...)`**：`FrontendBodyOwnerProcedures.resolveCallExpressionType` 拦截 SUPER callee（先于普通 bare-call 路径，避免按字面名 "super" 查函数），隐式方法名取 `context.callableOwner()`（`FunctionDeclaration` 名 / `ConstructorDeclaration`→`_init` 被构造规则拒绝 / lambda→FAILED "enclosing named function"）；`FrontendExpressionSemanticSupport.resolveBareSuperCallExpression` 复用 preliminary/finalized 双段参数定稿（含 container-literal 上下文）。
        - **事实形态**：`FrontendResolvedCall` 允许 `SUPER_METHOD` 携带 `exactCallableBoundary`；链式事实键 = `AttributeCallStep`，裸调用键 = `CallExpression`（与既有键控合同一致）；receiverType = 词法当前类（后端词法 self 不变量的前提）。
        - **compile 闸口**：`FrontendCompileCheckAnalyzer.scanSuperPositionCompileBlocks`——SUPER binding 只允许出现在裸调用 callee 或链头位置；`var x = super`、`foo(super)` 等值位置形态在 compile 模式报错（否则会被静默降级为 self 别名）。注意该 analyzer 只在 `analyzeForCompile` 运行，测试基建必须用此入口。
        - **lambda 捕获**：`FrontendVariableAnalyzer` 的捕获扫描器把 `super` 标识符视同显式 `self` 使用（super 调用仍向 self 发收，lambda 必须捕获 enclosing 实例）。
- 测试：`FrontendSuperCallSemanticsTest`（19 例：链式 RESOLVED + receiverType=当前类 canonical + boundary 发布、裸 `super()` 隐式同名、祖父解析、父链缺失 FAILED+可读消息、参数不匹配携带 resolver 细节、`super.prop` FAILED+member 诊断、`super.payload[0]` 链式下标步精确消息、裸 `super[0]` 值位置闸口、值位置（`var x = super` / `foo(super)`）compile 闸口、static 上下文 binding 错误、static 目标 FAILED、"super 调用结果续链按父类返回类型普通归约"、属性初始化器边界、`super()` in `_init` FAILED 构造链原因、lambda 裸 `super()` FAILED、lambda 内 `super.m()` RESOLVED+捕获 self、engine 父 `super._ready()` RESOLVED/ENGINE owner）；`FrontendSuperCallSupportTest`（11 例单元：无父类/未注册父类/`_init`/static 目标/GDCC abstract 拒绝、engine virtual hook 放行、最近祖先解析、缺方法与参数不匹配的可读消息锚定、**stop-at-first-declarer 两条**——近端参数不适用声明不得被跳过绑定远端适用声明、近端 static 声明不被远端实例方法遮蔽）。
- 审阅回填：`review-expert-a` 三轮复核——初审 2 中风险（失败诊断压成内部枚举名、合同负例缺锚点）+ 2 低风险（lowering 测试绕过 compile 入口、文首状态行过时）已修复；二轮残留 2 低风险（`super[i]` 锚点形态、文档计数）已修复；三轮**通过**，残留 1 条文档措辞建议已处理。`review-expert-c` 独立终审初审**不通过**：1 条高风险——普通链式实例解析"先按参数适用性过滤、再按 owner 距离取最近"导致 super 跳过近端参数不适用声明绑定远端适用声明（违反 Godot stop-at-first-declarer 语义，且前后端共用同一缺陷）——已修复为新增 `ScopeMethodResolver.resolveNearestDeclaredInstanceMethod` 词法 super 专用入口并同步切换 `FrontendSuperCallSupport` 与 `BackendMethodCallResolver.resolveSuper`（复现测试先行转绿锚定，scope/frontend/backend 全包回归全绿）。

### Step 7：前端 lowering 生成 CALL_SUPER_METHOD（R5 前端接线 · lowering）

- **实施状态：已完成（2026-09-10）**。
    - 验收：`script/run-gradle-targeted-tests.sh --tests FrontendSuperCallLoweringTest`（3 例）全绿；runtime fixture `runtime/virtual/super_method_dispatch.gd` 经 `GdScriptUnitTestCompileRunnerTest` 实跑通过（非 skip）；回归 `gd.script.gdcc.frontend.**`、`gd.script.gdcc.backend.**`、`gd.script.gdcc.lir.**` 全绿。
    - 实施要点回填：
        - `FrontendSequenceItemInsnLoweringProcessors.FrontendCallInsnLoweringProcessor` 新增 `case SUPER_METHOD -> lowerSuperMethodCall`：receiver 经既有 `materializeCallReceiverLeaf`（链式 = super 标识符 opaque 物化 / 裸调用 = 隐式 self 槽），发射 `CallSuperMethodInsn(result, name, receiver, args)`；**不做 receiver 逆提交 writeback**（super receiver 恒为 self 别名，写回恒等；`FrontendCallMutabilitySupport` 对非 INSTANCE_METHOD 天然返回 false），coroutine detach 复用 `emitCoroutineDetachIfNeeded`。
        - `FrontendOpaqueExprInsnLoweringProcessors` 标识符分支新增 `case SUPER -> AssignInsn(slot, "self")`（super 与 self 同存储，仅调用目标解析不同）。
        - `FrontendCfgGraphBuilder.buildIdentifierOpaqueRoute`：SUPER 归入 null payload 组（只读别名，永不作可写路由根）。
        - `FrontendBodyLoweringSession`：`requiresPublishedExactCallableBoundary` 与 `callBoundaryParameterTypes` 覆盖 SUPER_METHOD（后者仅在裸调用无 receiverValueId 的兜底路径触达；engine 目标经 `ClassMethod implements FunctionDef` 兼容）。
        - 后端词法 self 不变量实证：链式形态 receiver 槽类型 = `SuperLow__sub__Child`（inner class canonical 名），与 `bodyBuilder.clazz()` 一致。
- 测试：`FrontendSuperCallLoweringTest`（3 例：链式 → 唯一 `CallSuperMethodInsn` + receiver 为 self 别名 temp + 无同名 `CallMethodInsn` + 槽类型 canonical 锚定 + 非 void 结果槽；裸 `super(name)` → objectId 恒为 `self`；engine 父 `super._ready()` 语句位 void 形态）；runtime fixture 覆盖多态层级中 super 绕过 vtable（Leaf 30+Mid 20=50）、跨祖父解析、裸 `super()` 返回值拼接。

### Step 8：文档修订与全量回归

- 修订：
    - `explicit_c_inheritance_layout_contract.md`：把 `_vtable` 字段（位置/条件/accessor/根段链式访问）写入**已锁定结论**（不是附录）；新增条款：vtable trampoline 下行转换是"禁止裸 C cast"的唯一例外（§2.2）；create_instance 序列补 vtable 赋值；
    - `gdcc_c_backend.md`：对象构造序列补 `_vtable` 赋值；
    - `call_method_implementation.md`：`CALL_SUPER_METHOD` 移出"非目标"，分派模式表补 vtable 间接调用（GDCC 静态分发子形态），示例命令统一为 `script/run-gradle-targeted-tests.sh`；
    - `frontend_engine_virtual_override_implementation.md` §5.1：runtime anchor 名单补继承用例；
    - `test_suite.md`：补 engine-virtual 继承观察用例的夹具写法合同；
    - `gdcc_backend_todo.md`（路径：`doc/gdcc_backend_todo.md`）：登记遗留项（协变返回放宽、D3 边界、D1 的用户态诊断路径、GDScript 式显式 `super(...)` 构造调用的支持评估等）；
    - 本文回填"当前最终状态"。
- 验收：`./gradlew clean build --no-daemon --info --console=plain` 全绿；既有 golden 变更清单人工核对完毕。

## 4. 测试计划总表

| 层 | 位置 | 覆盖 |
|---|---|---|
| 单元 | `CVtablePlannerTest`（新建） | slot 算法、前缀、冲突、coroutine 标记、abstract、排除项、容错 |
| 单元 | `CallSuperMethodInsnGenTest`（新建）/ `CallMethodInsnGenTest` 新增用例 | 指令级生成形态 |
| 前端 sema | `FrontendSuperCallSemanticsTest`（Step 6 新建） | super 绑定/链式与裸调用解析、负例诊断、compile 位置闸口、lambda 捕获 |
| 前端 lowering | `FrontendSuperCallLoweringTest`（Step 7 新建） | `CALL_SUPER_METHOD` 发射形态、self 接收者不变量 |
| golden | `CCodegenTest` 扩展 / `CVtableCodegenTest`（新建） | entry.h/entry.c 结构、转发段、初始化、inner 链、撞名 fail-fast |
| runtime | `GdScriptEngineVirtualOverrideRuntimeTest` 扩展 + suite 新夹具；`CallMethodInsnGenEngineInheritanceTest` 扩展；`runtime/virtual/super_method_dispatch.gd`（Step 7） | engine 驱动 virtual 继承分派、多态方法调用、super 端到端 |
| 回归 | `CConstructInsnGenTest` 探针调整；全量 `clean build` | 既有行为不变 |

## 5. 决策点

- **D1（已确认，按推荐执行）**：模块内后代类声明同名但签名不兼容的**未排除**实例方法时编译期报错（`_init` / static / hidden / lambda 不在 D1 范围，见 §2.1）。frontend 对 GDCC↔GDCC 同名遮蔽目前没有诊断合同，MVP 先 planner/backend fail-fast（异常消息含两类名+方法名），并在 `gdcc_backend_todo.md` 登记 frontend sema 诊断的后续项。
- **D2（已确认，按推荐执行）**：`CALL_SUPER_METHOD` 父链不可解析时编译期报错而非 `gdcc_low_ir.md` 措辞的运行时错误；文档修订并入 Step 5。
- **D3（已确认，按推荐执行）**：运行时 attach 的 GDScript 子类覆写不进入 vtable（模块外不可见）。经 engine `call()` 的调用不受影响（ClassDB 方法表按类查找天然"虚"）；仅 GDCC 编译代码内的直接/间接调用不感知脚本覆写——与 godot-cpp 直接 C++ 调用行为一致。
- **D4（待确认）**：coroutine 覆写链的 vtable slot 存 start thunk 指针（非 impl 本体），调用方 ABI 与直接 coroutine 调用一致。
- **D5（待确认）**：`_vtable` 字段仅在"层级含 slot"的根类插入（非全部类统一插入），以最小化既有 golden 变化；代价是结构体形态按层级分析条件化。

### 5.1 预留扩展点：abstract 方法的脚本动态覆写（下一步规划）

本计划为"允许 GDCC abstract 方法被运行时 GDScript 脚本子类动态覆写"预留的接缝与已知冲突如下（落地该特性时另立文档，本节只冻结接口位置）：

- **已有接缝（本计划落地后即存在）**：
    - vtable 间接调用点：所有 polymorphic 调用已经走 `<I>_class_vtable(...)->m_x(...)`，是唯一的拦截点；
    - trampoline 层：每（覆写类 × slot）一对 trampoline 是安装"脚本桥"的天然位置——把下行转型调用换成动态分派即可；
    - abstract slot 的 `NULL` 条目（§2.2）：正是脚本桥 trampoline 的安装位（脚本子类实例仍走抽象类的 `create_instance`，`_vtable` 指向抽象类表，因此该条目届时不得为 NULL）；
    - engine 侧动态调用（`Object::call` 优先查 script instance）天然能到达脚本覆写，无需任何新机制。
- **已知冲突（落地该特性时必须修改，本计划不预留开关）**：
    - §2.2 的 fail-fast 规则（具体类 + 未实现 abstract slot → planner 报错）会直接拒绝"实现由脚本提供"的程序，届时改为生成脚本桥 trampoline（经 `godot_Object_call` 分派，未 attach 脚本时报运行时错误）；
    - §2.1 的 polymorphic 判定（需模块内真后代声明）对 abstract 方法要放宽为"abstract 方法恒视为 polymorphic"（脚本覆写编译期不可见）；
    - §5 D3 的边界只覆盖普通方法；该特性是对 abstract 方法的最小开口，普通方法的脚本覆写仍不进入 vtable。

## 6. 需要同步修订的文档

- `doc/module_impl/frontend/frontend_super_call_implementation.md`（**Step 6–7 新建**，前端 super 合同）
- `doc/module_impl/backend/explicit_c_inheritance_layout_contract.md`（Step 8，锁定结论 + trampoline 例外条款）
- `doc/gdcc_c_backend.md`（Step 8，create_instance 序列）
- `doc/module_impl/backend/call_method_implementation.md`（Step 8）
- `doc/module_impl/frontend/frontend_engine_virtual_override_implementation.md` §5.1（Step 8，anchor 名单）
- `doc/test_suite.md`（Step 8，夹具写法合同）
- `doc/gdcc_low_ir.md`（**Step 5**，call_super_method 措辞）
- `doc/gdcc_backend_todo.md`（Step 8，遗留项）
- 本文（Step 8 回填最终状态）
