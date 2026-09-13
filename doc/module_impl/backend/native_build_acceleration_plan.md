# Native Build 加速实施计划（per-TU 并行编译 + LTO 分级 + PCH 缓存）

## 文档状态

- 状态：计划待实施（Draft，已经过多轮审阅修订）。
- 范围：`ZigCcCompiler` 的 native 编译方式重构，不改 `CCompiler` 接口、`CProjectBuilder` 输入收集、artifact 命名与发布合同。
- 依据：`tmp/rotating_camera/` 下的原型实测数据（zig 0.16.0，Xeon E5-2699 v4 44 核，rotating_camera 示例模块）。
- 阅读前提：先读 `doc/gdcc_c_backend.md` §Native Compiler Cache、`doc/module_impl/backend/godot_binding_implementation.md` §构建输入与旧残留规则、`doc/module_impl/api/rpc_api_implementation.md` §Compile Pipeline Contract。

## 1. 背景与动机

### 1.1 现状问题

当前 `ZigCcCompiler.compile()` 用单条 `zig cc -target ... -shared -flto ... <所有 .c>` 完成编译+链接（`src/main/java/gd/script/gdcc/backend/c/build/ZigCcCompiler.java:41-65`）：

- 整个调用是 zig 缓存的**一个单元**：任何用户代码改动（`entry.c` 变化）使全部输入失效，`godot_binding.c`（运行时绑定聚合 TU）每次都全量重编；
- 实测 `godot_binding.c` 占 O0 编译时间约 83%、O2 约 93%（预处理后 ~3.6MB / 35k 行 / 559 函数），但它只随 Godot API 版本和 gdcc runtime 变化，与用户代码无关；
- 4 个 TU 在单进程内串行编译，多核闲置。

### 1.2 原型实测数据（rotating_camera 模块，供验收对照）

下表为**最终形态**（per-TU 并行 + debug 无 LTO / release ThinLTO + zig per-TU 缓存）的实测值，不是分步中间态的预期：

| 场景 | 当前（单命令 full LTO） | 最终形态（原型实测） |
| --- | --- | --- |
| debug(O0) 冷构建 | ~3.0-3.1s | ~3.8s（编译 3.7s + 链接 0.08s） |
| debug(O0) 增量（仅 entry.c 变） | ~3s（全量重编） | **~0.48s**（缓存命中 TU ~30-48ms） |
| release(O2) 冷构建 | ~7.5s | ~7.0s（ThinLTO） |
| release(O2) 增量（仅 entry.c 变） | ~7.5s（全量重编） | **~1.4s**（编译 0.44s + ThinLTO 链接 0.97s） |

补充实测结论（均已在本仓库环境验证）：

- zig cc 自带基于内容哈希的 per-TU 编译缓存，命中代价 30-48ms；**cache key 不含 `-o` 输出路径**（已实测：同源同 flags、不同 `-o`，第二次 34ms 命中），同一 cache root 下跨项目可复用，无需 gdcc 自管 object 缓存；
- debug 无 LTO 的冷构建比当前略慢 ~0.7s（4 次独立机器码生成 vs 合并一次），换来增量 -84%，属于有意取舍；
- ThinLTO 与 full LTO 产物等价性已实测：55 个导出符号一致（含 `gdextension_entry`），体积 0.49MiB vs 0.48MiB；
- PCH（只含 `godot_binding.h` 的前缀头）实测：构建 0.22s，`entry.c` 编译 215ms vs 359ms（-40%），链接产物验证通过；`godot_binding.c` 收益小（聚合 `.c` 占大头）；
- **PCH 不能跨优化级别复用**（已实测硬错误：`__OPTIMIZE__ predefined macro was disabled in precompiled file but is currently enabled`），PCH 缓存 key 必须包含优化级别及 LTO 模式；
- zig cc 拒绝透传 `-Wl,--thinlto-cache-dir` 与 `-fthinlto-cache-dir`，ThinLTO 链接后端无法跨构建缓存（每次 ~0.6-1.0s），列为已知限制；
- `zig ld.lld` 可作为后续直接驱动 lld 的备选（`--time-trace`、`--thinlto-cache-dir` 均可用），本计划不采用。

## 2. 目标与非目标

### 2.1 目标

1. `ZigCcCompiler` 内部改为「逐 TU 编译为 object + 链接」两阶段，per-TU 编译支持并行，从而复用 zig 的 per-TU 内容缓存（主要是 `godot_binding.c`）；
2. debug(`-O0`）不使用 LTO；release(`-O2`）使用 `-flto=thin`；
3. 将 `godot_binding.h` 构建为 PCH 并缓存到现有 compiler cache root 下，按内容哈希自动失效；
4. 保持所有对外合同不变（见 §3.2），现有测试在不改语义的前提下继续通过。

### 2.2 非目标

- 不改 `CCompiler` 接口签名、不改 `CProjectBuilder` 的输入收集与 include 解析逻辑；
- 不改 generated-file 集合与发布合同（当前为 4 个文件：`entry.c`、`entry.h`、`engine_method_binds.h`、`object_fat_ptr_types.h`）；
- 不拆分 `godot_binding.c` 聚合 TU（属绑定生成器输出组织变更，留作后续候选优化）；
- 不实现 gdcc 自管的 object/ThinLTO 后端缓存，不直接调用 `zig ld.lld`；
- 不改 runtime 源码、宏配置（`MCO_USE_ASM` 等）；
- 不做 cache 容量清理/LRU（列入 Backlog）。

## 3. 现状与必须保持的合同

### 3.1 当前编译流程

`CProjectBuilder.buildProject(...)`（`src/main/java/gd/script/gdcc/backend/c/build/CProjectBuilder.java:70-139`）收集固定顺序的 native 输入：

```text
<本轮生成的 .c>（当前恰为 entry.c）
<includeRoot>/godot/godot_binding.c
<includeRoot>/gdcc/minicoro.c
<includeRoot>/gdcc/gdcc_coroutine.c
```

连同 include 目录（`<includeRoot>/gdcc`、`<includeRoot>/godot`）传给 `CCompiler.compile(...)`，由 `ZigCcCompiler` 单命令完成。cache root 解析规则（`ZigCcCompiler.resolveCompilerCacheRoot`）：`GDCC_SHARED_C_COMPILER_CACHE` 可用则用之，否则项目父目录已有 `shared-compiler-cache` 用之，否则项目内 `compiler-cache`。

### 3.2 必须保持的合同（违反即阻塞合入）

1. `CCompiler` 接口（`CCompiler.java:9-17`）签名不变；per-TU+link 只是 `ZigCcCompiler` 的内部实现，所有注入 fake `CCompiler` 的测试（`CProjectBuilderPlaceHolderTest`、`CProjectBuilderSharedIncludeTest`、`CProjectBuilderCoroutineRuntimeInputTest`、`GdScriptBenchmarkRunnerTest` 等）零改动；
2. `CCompileResult.artifacts()` **第一项永远是最终共享库**（CLI 用 `artifacts.getFirst()` 生成 `.gdextension`，`GdccCommand.java:466-473`），Windows PDB 存在时列第二（沿用现有探测，PDB 只可能由链接步产生）；**中间 object、PCH 一律不得进入 artifacts**；
3. native 输入顺序与内容不变（`CProjectBuilderCoroutineRuntimeInputTest` 严格断言）；per-TU 日志按固定次序（PCH 前导 → TU 槽位按输入顺序 → 链接）稳定合并，已启动段的相对次序确定（§4.5）；
4. `CBuildResult.Timing` 字段不变，`nativeCompile` 覆盖「编译全部 TU + 链接」全程；
5. cache root 解析行为与 fallback 不变（`ZigCcCompilerCachePathTest` 现有 6 例全绿）；PCH 只是 cache root 下的新增子目录；
6. 非 Windows 主机交叉 `windows-msvc` → `windows-gnu` 替换及伴随的「禁用 LTO」规则保留；**省略 LTO 的断言由新的 `ZigCcCompilerCommandTest` 门禁**（现有 `ZigCcCompilerTest` 只测 triple 替换，不检查 LTO flag）；
7. artifact 命名 `<module>_<optimization>_<architecture>.<ext>` 不变；`BUILDING_NATIVE` 阶段粒度不变；API 的 module 级串行合同不变（per-TU 并行仅是单次 native build 内部行为）；
8. `entry.h` 的合同（`class_library` 由 includer 在 include `gdcc_helper.h` 之前声明）不变，因此 **PCH 内容只能包含 `godot_binding.h`，不能包含 gdcc 树头文件**（已实测冲突）；同时 **`-include-pch` 只对实际包含 `godot_binding.h` 的 TU 启用**（见 §4.3 白名单）；
9. 编译失败时 `buildLog` 仍需包含完整命令与输出；取消（中断）必须终止**全部**已启动子进程（现有单进程语义见 `ProcessUtil.waitForInterruptibly`，多进程扩展见 §4.4）。

## 4. 总体设计

### 4.1 两阶段编译

`ZigCcCompiler.compile(...)` 内部流程改为：

```text
1. 解析 zig、target（msvc→gnu 规则不变）、cache root（规则不变）
2. 计算各 TU 编译命令与链接命令（§4.2，package-private 静态构建方法）
3. 并行执行 per-TU 编译（§4.4），输出 object（路径规则见下）
4. 全部成功后执行一次链接，产出最终共享库
5. 任一步失败：按输入顺序合并日志返回失败；中断传播到所有子进程
```

object 路径与链接输入的硬规则：

- object 写到 `<projectDir>/obj/<debug|release>/<zigTarget>/<index>_<fileName>.o`；`<index>` 为 `cFiles` 下标，避免不同目录同名 `.c` 冲突；路径含 opt 与 target，避免跨配置残留误用；
- **链接命令的输入只允许是「本轮按 `cFiles` 顺序生成的 object 绝对路径列表」，禁止 glob `obj/` 目录、禁止「.o 已存在则跳过编译」**；每个 TU 每次都调用 `zig cc -c`，复用完全交给 zig 内容缓存（key 不含 `-o`，已实测）；陈旧 `.o` 只是磁盘残留，绝不进入链接；
- object 属于中间产物：不进 artifacts、不随 generated files 发布。

### 4.2 各优化级别的编译参数

per-TU 编译命令（第 i 个 TU）：

```text
zig cc -target <zigTarget> -std=c23 -fPIC -c [-flto=thin|-flto] <-O0|-O2>
       -Wno-macro-redefined -Wno-pointer-sign -I<includeDir>...
       -o <objPath> <cFile>
```

链接命令：

```text
zig cc -target <zigTarget> -shared [-flto=thin|-flto -O2]
       -o <outputPath> <objPath...（按 cFiles 顺序）>
```

（LTO token 的实际取值由下方优先级表决定：`-flto=thin` / `-flto` / 无。）

- `-target`、cache 环境变量（`ZIG_CACHE_DIR`/`ZIG_GLOBAL_CACHE_DIR`）、工作目录（`pb.directory(projectDir)`，沿用现状）在 **TU 编译、PCH 构建、链接**三类进程上完全一致；`-I`/`-std`/`-fPIC`/`-c` 只出现在编译类命令；`-shared` 只出现在链接命令；
- DEBUG：`-O0`，无 LTO；RELEASE：`-O2` + `-flto=thin`（编译与链接都带）；
- LTO 决策按固定优先级，禁止用「链接失败」反推 msvc-on-non-Windows：
  1. `abiSubstituted == true`（msvc→gnu 替换触发）：编译与链接命令都**不追加任何 `-flto*`**（现有规则，`ZigCcCompilerCommandTest` 硬断言）；
  2. DEBUG：无 LTO；
  3. 已知 ThinLTO 不支持的 target：RELEASE 回退 `-flto`（full LTO 仍走「per-TU bitcode + 一次链接」两阶段形态，per-TU 缓存收益不变），并有命令级单测锁定回退 flags；
  4. 其余 RELEASE：`-flto=thin`（编译与链接都带）；
- 交叉 target ThinLTO 兼容性在第一步验收中冒烟（仅验证链接成功 + 产物存在，不运行），但只对**当前工具链可构建的 target** 做真进程冒烟：宿主机（linux-x86-64）、linux 交叉（aarch64/riscv64，zig 内置 libc）、windows-gnu 交叉（zig 内置 mingw）；未替换的 `windows-msvc` 真进程冒烟只在 Windows 宿主执行，非 Windows 宿主仅做 `resolveZigTarget(..., windowsHost=true)` 的命令级断言。**android 与 web-wasm32 属既有环境限制**（minicoro 锁定 `MCO_USE_ASM`，wasm 上按设计 fail loudly；android 链接需要 NDK/Bionic，zig 不内置）：这两个 target 在本计划中只做编译期（`-c`，不链接）验证或显式跳过并记录已知限制，**不得把缺少 sysroot/运行时不支持误判为 ThinLTO 不支持而回退 `-flto`**（其与 LTO 无关）。web 的 Emscripten 后端（ASYNCIFY coroutine + 放宽 `MCO_USE_VMEM_ALLOCATOR` + emcc/Binaryen 构建链）**待实现，另行立项；zig 后端不做此工作**（见 §10）；

### 4.3 PCH 设计（第三步）

- 前缀头内容固定为 `#include <godot_binding.h>`（依据 §3.2-8），由 gdcc 写入缓存目录；
- 存放：`<cacheRoot>/pch/<key>/` 下 `gdcc_godot_prefix.h`、`gdcc_godot_prefix.pch` 与 `.ready` 就绪标记；
- **PCH 使用白名单**：只对实际包含 `godot_binding.h` 的 TU（当前为 `entry.c`、`godot_binding.c`、`gdcc_coroutine.c`）的编译命令追加 `-include-pch <pch 绝对路径>`；`minicoro.c` 等不包含 Godot 头的 TU **禁止**加 PCH（避免把整份 ABI 头强灌进隔离的汇编后端 TU）；`ZigCcCompilerCommandTest` 断言 `minicoro.c` 编译命令无 `-include-pch`；
- `key` = SHA-256 截断，输入：
  - zig 版本字符串（见下）；
  - zigTarget；
  - 语言 flags 全集（编译 flags **减去** `-c`、`-o`、源/目标路径、`-include-pch`；**包含** `-O` 级别与实际 LTO token——`-flto=thin`/`-flto`/无三者分别入 key，防止 full-LTO 回退串缓存；已实测跨优化级别复用会硬错误，debug/release 各一份 PCH）；
  - include 目录（**保序**，`-I` 顺序有语义）：按命令顺序拼接 `序号 + 归一化绝对路径 + 该目录树内容哈希`；只有每个目录**内部**的相对文件路径才排序（length-prefixed 拼接「相对路径 + 文件内容」再哈希；`ZigCcCompiler` 只依赖传入的 includeDirs，不假设 `<includeRoot>/{godot,gdcc}` 结构）；两个目录含同名头文件时顺序不同必须得到不同 key；
  - 注：`GodotVersion`/`--gde`、`REAL_T_IS_DOUBLE` 不进 `zig cc` argv，无需入 key——换版本会换头文件，被内容哈希覆盖；
- 构建与就位协议（**已实测硬约束**：clang PCH 记录主前缀头及全部被包含头文件的绝对路径，构建后移动前缀头会使 PCH 失效——staging 方案禁止移动前缀头）：
  1. 创建 `<cacheRoot>/pch/<key>/`（已存在且 `.ready`/`prefix.h`/`.pch` 齐全则直接复用）；
  2. 在 `<key>/` 内以「临时文件 + rename」方式写入**最终路径**的 `gdcc_godot_prefix.h`（已存在且内容一致则跳过）；
  3. 以该最终路径的 `gdcc_godot_prefix.h` 为输入构建 PCH，输出到同目录临时名 `.gdcc_godot_prefix.pch.tmp-<随机>`；
  4. 校验（probe，见下）通过后 rename 为 `gdcc_godot_prefix.pch`，最后写入 `.ready` 标记；**消费方只使用 `.ready`、`prefix.h`、`.pch` 三者齐全的条目**；
  5. 全程不跨目录 move：staging 与 cache root 同文件系统；rename 目标已存在（`FileAlreadyExistsException` 或文件已就位）视为其他进程获胜，丢弃临时文件并使用已就位条目；`AtomicMoveNotSupportedException` 退化为普通 rename；
- **自愈**：已就位条目 probe/校验失败时，删除该 key 目录（或隔离重命名）并**允许一次重建**；重建仍失败才回退本轮无 PCH，诊断原因写入 `buildLog`（避免一次崩溃/半截文件导致该 key 永久禁用 PCH）；
- PCH 构建命令使用与 TU 编译**完全相同的语言 flags 全集**（`-std=c23`、`-fPIC`、`-O` 级别、实际 LTO token、`-Wno-macro-redefined`、`-Wno-pointer-sign`、`-I`），仅把 `-c`/`-o`/源路径换成 `-x c-header`；clang 对创建/使用选项不一致会直接拒绝 `-include-pch`，flags 不齐会让 PCH 形同虚设：

  ```text
  zig cc -target <zigTarget> -std=c23 -fPIC <-O0|-O2> [-flto=thin|-flto]
         -Wno-macro-redefined -Wno-pointer-sign
         -x c-header <prefix.h> -o <prefix.pch> -I<includeDir>...
  ```

- **生效前 probe**：并行 TU 启动前，先用 `-include-pch` 编译一个临时 trivial `.c` 做廉价探测；probe 进程与其余三类进程同样遵守登记（`start()` 即入注册表）、`pb.directory(projectDir)`、同一 `ZIG_*_CACHE_DIR`、完整 TU flags 的合同，中断走 interrupted 通道而非 PCH 回退；探测失败（非中断）则本轮整体不用 PCH（记录回退行）。若 TU 运行中仍出现 zig 拒绝 `-include-pch`（probe 后理论上不应发生）：销毁已启动 TU、整轮去掉 `-include-pch` 重编；**禁止 PCH/no-PCH object 混链**；

- **回退规则**：PCH 构建失败、校验失败且自愈重建失败、rename 失败、zig 拒绝 `-include-pch` 中的任何一种，都只回退为「本轮不用 PCH」并在 `buildLog` 顶部记录一行回退说明，**不得阻断构建**；
- zig 版本获取：`ZigUtil` 新增 `findZigVersion()`（`@Nullable`、静态缓存**成功**结果；遵循现有 `findZig()` 不抛错的失败模型，不用 `requireXxx` 命名）。语义分层：探测失败（无法解析 `zig version`）→ 仅禁用 PCH，`compile()` 照常；`InterruptedException` → 恢复 interrupt 状态并走现有「`success=false` + `Failed to run zig: interrupted` + 空 artifacts」通道，**不得当成 PCH 回退**（否则取消会被吞成「跑完再标 CANCELED」且孤儿进程不被销毁）；失败与中断都不得缓存成「zig 不存在」；
- 并发：多 gdcc 进程共享 cache root 时，重复构建只是浪费、不会损坏（最终路径前缀头内容相同、临时名唯一、`.ready` 最后发布）；锁文件列入 Backlog。

### 4.4 并行、进程注册表与取消协议

现有取消合同是单进程的：`API.cancelCompileTask`/`close` 只 interrupt runner 线程（`CompileTaskState.interruptRunner`），runner 里那一次 `ProcessUtil.waitForInterruptibly` 负责 destroy 唯一 zig 进程。多进程化后必须按以下硬合同扩展（违反即阻塞）：

- 用**带关闭状态的同步进程注册表**（不是裸 `ConcurrentLinkedQueue`）管理全部子进程。`start()` 后登记与取消清扫存在竞态，协议必须闭合：
  - 注册表提供 `register(process)` 与 `cancelAndSnapshot()` 两个互斥操作；
  - `cancelAndSnapshot()` 先置 closed，再返回全部已登记进程；
  - closed 之后任何 `register()` 必须让调用方**立即 `destroyForcibly()` 该进程**（登记即销毁），不得只入队；
  - 取消路径：closed + 对快照 `destroyForcibly()` + **等待全部 worker 线程结束后**才返回（否则 `API.close()` 可能先于 zig 子进程退出）；
  - 登记范围覆盖一切会启动 zig 的进程：TU 编译、PCH 构建、PCH probe、链接，以及 `findZigVersion()` 的 `zig version` 探测（Zig discovery 统一走同一进程管理器）；
- 进程执行收敛到一个 package-private 可注入的 process launcher（默认实现 = 真实 `ProcessBuilder`），使纯 Java 单测与 API 级接线测试可以注入假进程驱动真实 `ZigCcCompiler` 逻辑（见 §6 验收）。（`StructuredTaskScope` 在 Java 25 仍为 preview 特性，本仓库未开 `--enable-preview`，仅作标准化后的可选替换，不作为本计划依赖。）
- 每个 worker 沿用现有「伴随虚拟线程 drain 输出 + `ProcessUtil.waitForInterruptibly`」模式；
- runner 线程在 `InterruptedException`/`finally` 中对**所有仍活着的已登记进程** `destroyForcibly()`，再 join 输出线程；保持现有语义：catch 中断后 `Thread.currentThread().interrupt()` 并返回 `success=false`，由 `CompileTaskRunner` 转成 `CANCELED`；
- **取消：立即销毁 TU/PCH/链接全部进程，不进入链接，不空等未完成的 TU**；
- 非取消的 TU 失败：可等待其余已启动 TU 结束以收集完整日志，但等待循环必须仍响应 interrupt；
- 并行度 `min(cFiles.size, Runtime.availableProcessors())`；
- 进程管理逻辑（注册表 + launcher）抽成可纯 Java 单测的 helper，取消语义用注入假 `Process` 的单测覆盖（含「closed 后 register 立即销毁」「取消等待 worker 退出」的 latch 竞态用例），不依赖真 zig 长编译。

### 4.5 buildLog 合并格式

- 成功路径：各进程原始输出按固定次序拼接：PCH 前导（构建/probe/回退行）→ TU 槽位（按 `cFiles` 输入顺序）→ 链接；空输出不附加 Command 行（与现状一致）；
- 失败路径：固定格式，**只输出已启动（`start()` 过）进程的 Command 段**，段序同样为 PCH 前导 → TU 槽位（输入顺序）→ 链接；未启动的 TU/PCH 构建/probe/链接一律不出现 Command 段（链接段仅当链接进程启动过才出现——TU 失败或取消时不启动链接，日志也不得有链接段）。
  **确定性合同降级为「已启动段按固定次序稳定排列」**：TU 数超过并行度时，失败瞬间哪些 TU 已启动取决于调度时序，段集合可逐次不同，测试只断言相对次序与格式，不断言段集合相等；

  ```text
  Command: <tu0 argv>
  <tu0 stdout/stderr>
  Command: <tu1 argv>
  <tu1 stdout/stderr>
  ...
  Command: <link argv>（仅链接已启动时）
  <link output>
  ```

- PCH 回退说明固定一行，位于整段日志最前；
- worker 必须先把各自输出缓冲到按 `cFiles` 下标索引的槽位，全部结束后统一次序拼接（并行完成顺序非确定）；拼接逻辑由 `ZigCcCompilerCommandTest` 以假输出断言「仅 TU 失败」与「链接失败」两种形态。

## 5. 第一步：两阶段编译 + LTO 分级（串行 TU）

目标：把单命令拆成「per-TU 编译 + 链接」，并直接落地最终 flags（§4.2），TU 暂串行。行为等价性、冒烟矩阵与增量收益在本步验证。注意：§1.2 的性能数字是含并行的最终形态，本步（串行）增量收益已经主要来自 `godot_binding.c` 缓存命中，但冷构建并行加速尚不可用。

### 建议实施内容

- 重构 `ZigCcCompiler.compile()`：抽出 `buildTuCompileCommand(...)`、`buildLinkCommand(...)`（package-private 静态）、`runProcess(...)` helper、obj 路径规则（§4.1）、artifacts 组装（§3.2-2，链接成功后沿用现有 PDB 探测）；
- 新增 `ZigCcCompilerCommandTest`（纯 Java，不起进程）：编译命令含 `-c` 不含 `-shared`、链接命令含 `-shared` 不含 `-c`/`.c` 源、**编译与链接都带同一 `-target`**、debug 无 `-flto`、release `-flto=thin`、msvc→gnu 时两者都无 LTO flag、obj 路径含 opt/target/序号；
- 新增 `ZigCcCompilerIncrementalIntegrationTest`（真 zig，Assumptions 门控）：同一项目构建两次，第二次前重写一个 entry 级 `.c`；断言两次均成功、artifact 命名不变、动态导出符号含 `gdextension_entry`；**不断言耗时**；
- 交叉 target ThinLTO 冒烟：按 §4.2 的可构建 target 集合（宿主 + linux 交叉 + windows-gnu）验证链接成功 + 产物存在；`abiSubstituted`（msvc→gnu）情形**永不进入 ThinLTO 回退判定**（本就禁 LTO）；未替换的 `windows-msvc` 真进程冒烟只在 Windows 宿主执行，非 Windows 宿主仅做 `resolveZigTarget(..., windowsHost=true)` 命令级断言；android/web 只做编译期（`-c`）验证或显式跳过并记录已知限制；不支持 ThinLTO 的 target 回退 `-flto` 并配命令级单测；
- negative path 测试入口：**直接实例化 `ZigCcCompiler` 并调用其公开 `compile(...)`**，注入含语法错误的 `.c`，断言失败、`buildLog` 按 §4.5 格式含已启动进程的 Command 段（链接未启动则无链接段）、artifacts 为空。

### 验收细则

- happy path：
  - `script/run-gradle-targeted-tests.sh --tests ZigCcCompilerCommandTest,ZigCcCompilerTest,ZigCcCompilerCachePathTest` 全绿；
  - `script/run-gradle-targeted-tests.sh --tests CProjectBuilderCoroutineRuntimeInputTest,CProjectBuilderSharedIncludeTest,CProjectBuilderPlaceHolderTest` 全绿（fake compiler 零改动）；
  - `script/run-gradle-targeted-tests.sh --tests ZigCcCompilerIncrementalIntegrationTest,CProjectBuilderIntegrationTest,FrontendLoweringToCProjectBuilderIntegrationTest` 全绿（真 zig + Godot，环境缺失自动 skip）；
  - `script/run-gradle-targeted-tests.sh --tests GdScriptBenchmarkCompileTest` 全绿（release 路径，断言 `_release_` 命名）；
  - release 产物符号检查（手工/冒烟）：`zig nm`（或 llvm-nm）动态定义符号**必含 `gdextension_entry`，允许多余符号**；交叉 target 只断言链接成功 + 产物存在；
- negative path：
  - 单 TU 语法错误（直调 `compile(...)`）：失败、日志格式正确、无 artifact；
  - msvc→gnu 命令门禁（`ZigCcCompilerCommandTest`）：编译与链接均无 LTO flag；
- 性能验收（**手工、参考机器、写入 PR 描述，CI 不断言**）：rotating_camera 模块 debug 冷 ≤ 4.5s、debug 增量 ≤ 0.9s（串行 TU）；release 冷 ≤ 8s、release 增量 ≤ 2.2s。

## 6. 第二步：per-TU 并行 + 取消协议加固

### 建议实施内容

- 按 §4.4 落地带关闭状态的同步进程注册表与可注入 process launcher、并行度封顶、按输入顺序的日志槽位拼接（§4.5）；
- 新增进程管理 helper 的纯 Java 单测（注入假 `Process`）：中断时全部登记进程收到 destroy、取消不进入链接、非取消失败仍响应 interrupt、closed 后 register 立即销毁、取消等待全部 worker 退出（latch 竞态用例）；
- 新增 API 级接线测试（注入假 process launcher 驱动**真实** `ZigCcCompiler`，经 `CProjectBuilder` 与 API 跑编译任务）：`cancelCompileTask()` 销毁全部已启动进程且不启动链接；`API.close()` 返回前 worker/runner 全部退出（现有 fake `CCompiler` 的取消测试覆盖不到这层接线）；
- 新增并行失败直调测试（真 zig 门控）：3 个 TU、中间一个非法，断言 `buildLog` 按输入顺序含已启动进程的 Command 段、artifacts 为空。

### 验收细则

- happy path：
  - 进程管理 helper 单测全绿；
  - `script/run-gradle-targeted-tests.sh --tests ZigCcCompilerCommandTest,ZigCcCompilerIncrementalIntegrationTest,GdScriptUnitTestCompileRunnerTest,GdScriptBenchmarkCompileTest` 全绿；
- negative path：
  - 并行失败直调测试：日志确定性合并、无半成品 artifact；
  - 取消断言分三层：helper 单测断言「全部登记进程被 destroy、不进入链接、`success=false` 且 interrupt 状态恢复」；API 级接线测试（假 launcher + 真 `ZigCcCompiler`）断言取消/关闭时的进程生命周期；`CANCELED` 与 `BUILD_FAILED` 的 outcome 映射在 API 层验证（扩展现有 `ApiCompileTaskCancellationTest`：interrupt + `cancellationRequested` → `CANCELED`；仅 `success=false` 且未取消 → `BUILD_FAILED`）；
- 性能验收（手工、参考机器、写入 PR 描述）：debug 增量 ≤ 0.8s、release 增量 ≤ 2s（达到 §1.2 最终形态数字）。

## 7. 第三步：godot_binding.h PCH 缓存

### 建议实施内容

- spike（先行小验证，纳入本步验收）：
  - 同 key 同 flags 下 PCH 命中；debug（-O0 无 LTO）与 release（-O2 ThinLTO）使用**各自**的 PCH（key 含优化级别与 LTO 模式，依据 §1.2 实测硬错误）；
  - `minicoro.c` 单独验证：编译命令不含 `-include-pch`，产物与现状一致；
- 落地 §4.3：`findZigVersion()`、include 树内容哈希、key 组装（含 includeDir 保序）、最终路径前缀头 + 临时 pch 就位 + `.ready` 发布、白名单、自愈与回退；**给 `ZigCcCompiler` 增加 package-private 的 cache-root/environment 解析注入点**（不改 `CCompiler` 接口），供测试注入固定临时 cache root——`@TempDir` 无法清除父进程 `GDCC_SHARED_C_COMPILER_CACHE` 环境变量；
- 新增 `ZigCcCompilerPchKeyTest`（纯 Java）：同输入同 key；任一 header 内容变化换 key；语言 flags（含 `-O`/LTO）变化换 key；includeDirs 集合变化换 key；**includeDirs 顺序变化换 key**（同名头文件场景）；shared-include 与 project include 两种 includeRoot 换 key；
- 新增 `ZigCcCompilerPchIntegrationTest`（真 zig 门控，经上述注入点固定 `@TempDir` cache root，**不得触碰 shared-include 或共享 cache**）：两次构建复用同一 pch（断言 `.ready` 存在且 pch 内容/mtime 未变）；篡改 include 树一个 header 后 pch 重建；PCH 构建产物经 `GodotGdextensionTestRunner` 运行验证；
- 更新 `doc/gdcc_c_backend.md` §Native Compiler Cache：补 `<cacheRoot>/pch/<key>/` 布局、key 构成、白名单、失效与并发语义、回退规则。

### 验收细则

- happy path：
  - `script/run-gradle-targeted-tests.sh --tests ZigCcCompilerPchKeyTest,ZigCcCompilerPchIntegrationTest` 全绿；
  - `script/run-gradle-targeted-tests.sh --tests GdScriptUnitTestCompileRunnerTest,GdScriptBenchmarkCompileTest,FrontendLoweringToCProjectBuilderIntegrationTest` 全绿（PCH 开启下行为不变）；
- negative path：
  - PCH 构建失败：用注入 process launcher 让仅含 `-x c-header` 的命令返回非零（普通 TU/链接正常），断言构建回退无 PCH 成功、`buildLog` 顶部含回退说明。（**不得**用「坏 header」构造——前缀头与普通 TU 共享 `godot_binding.h`，头真坏则普通编译同样失败，场景不成立）；
  - 已就位 pch 损坏自愈（真 zig 门控）：发布后的 pch 截断/破坏 → 下一次构建 probe 失败 → 删除该 key 并重建一次 → 成功恢复 PCH 复用；再造失败则回退无 PCH 且诊断入 `buildLog`；
  - key 目录并发就位：两线程/进程共享 cache root 并发构建，产物均正确、无损坏 pch；
  - PCH 白名单：`minicoro.c` 编译命令无 `-include-pch`（`ZigCcCompilerCommandTest`）；前缀头内容恰为 `#include <godot_binding.h>`（单测断言，守护 §3.2-8）；
- 性能验收（手工、参考机器、写入 PR 描述）：rotating_camera debug 增量 ≤ 0.6s；多 `.gd` 文件模块收益更大，作为 PR 描述数据。

## 8. 第四步：文档与收尾

- 更新 `doc/gdcc_c_backend.md` §Native Compiler Cache（PCH 布局，见第三步）、`doc/test_suite.md` §Runtime and Build Prerequisites（如新增环境行为）、`doc/benchmark.md`（release 现用 ThinLTO 的事实记录）；
- 本计划文档状态改为「已实施」，并补齐实施偏差说明；
- 全量验证：`./gradlew clean build --no-daemon --info --console=plain` 全绿（覆盖 `FrontendLoweringToCTypedArrayAbiIntegrationTest` 等大型真 zig + Godot 矩阵，定向名单不替代全量）。

## 9. 风险与缓解

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| 交叉 target 的 ThinLTO 不被 zig/lld 支持 | 中 | 第一步按 §4.2 的可构建 target 冒烟（宿主 + linux 交叉 + windows-gnu），失败 target 回退 `-flto`（两阶段形态不变）+ 命令级单测锁定；android/web 属既有 sysroot/runtime 限制（minicoro `MCO_USE_ASM` 在 wasm 按设计 fail loudly），与 LTO 无关，只做编译期验证或显式跳过并记录 |
| PCH 在未来 zig 版本行为变化（clang 内部格式） | 中 | key 含 zig 版本与完整语言 flags；任何 PCH 失败一律回退无 PCH；第三步 spike 门控 |
| PCH 跨优化级别复用（已实测 `__OPTIMIZE__` 硬错误） | 已消除 | key 含 `-O` 级别与 LTO 模式，debug/release 各一份（§4.3） |
| 多进程取消残留孤儿 zig | 已消除 | §4.4 进程注册表硬合同 + 纯 Java 单测；取消不进链接 |
| 多进程共享 cache root 构建 PCH 竞争 | 低 | 同文件系统临时目录构建 + 就位检测；已存在即复用；锁文件列入 Backlog |
| debug 冷构建略慢（无 LTO 4 次独立 codegen，实测 +0.7s） | 低（已接受的取舍） | 增量收益 -84% 远覆盖；文档与 PR 如实记录 |
| zig cache 并发异常（原型期在默认全局 cache 下偶发 `CacheCheckFailed`，受控 cache dir 下并行实测未复现） | 低 | 并行编译共享同一受控 cache root；复现则串行化回退并上报 zig |
| `obj/` 陈旧文件误入链接 | 已消除 | §4.1 硬规则：链接只传本轮 object 列表，禁止 glob/禁止跳过编译 |
| per-TU 进程数随生成 `.c` 增多（当前恰 4 个） | 低 | 并行度封顶 availableProcessors；生成 `.c` 数量变化时无需改代码 |
| cache 体积膨胀（每个 PCH ~4MB、zig cache 增长） | 低 | 清理/LRU 策略列入 Backlog |

## 10. Post-MVP Backlog

- 拆分 `godot_binding.c` 聚合 TU（`godot_builtin.c` 1.1MB 仍是增量关键路径之一；属绑定生成器输出组织变更，需另行立项）；
- 直接调 `zig ld.lld` 以启用 `--thinlto-cache-dir`（实测全命中链接仅 53ms vs 当前 ~1s）与链接 `--time-trace`；
- PCH 覆盖 gdcc 树头文件（需 `entry.h` 的 `class_library` 声明改为可守卫形式，涉及 codegen 模板变更，另行评估）；
- cache root 容量管理与 LRU 清理、PCH 构建跨进程锁；
- entry 级 TU 的头文件瘦身（`entry.h` 拖入整个 runtime 头，19k 行预处理展开是 entry.c 前端耗时主因）；
- web-wasm32 target 的 Emscripten 后端：**待实现，另行立项；zig 后端不做此工作**。前置条件包括引入 Emscripten 工具链、为 wasm 选择 coroutine 后端（推荐 `MCO_USE_ASYNCIFY`，需链接期 Binaryen `-s ASYNCIFY=1` 变换，zig cc 无法完成）、仅在该后端下放宽 `MCO_USE_ASM`/`MCO_USE_VMEM_ALLOCATOR` 锁定（原生平台维持 ASM 锁定不变）、更新 `doc/gdcc_runtime_lib.md` §Coroutine Runtime 合同并补真实环境验证。

## 11. 测试规则

- 迭代期只跑定向测试：`script/run-gradle-targeted-tests.sh --tests <类名>`，本计划相关锚点类：`ZigCcCompilerCommandTest`、`ZigCcCompilerTest`、`ZigCcCompilerCachePathTest`、`ZigCcCompilerIncrementalIntegrationTest`、`ZigCcCompilerPchKeyTest`、`ZigCcCompilerPchIntegrationTest`、`CProjectBuilderCoroutineRuntimeInputTest`、`CProjectBuilderSharedIncludeTest`、`CProjectBuilderPlaceHolderTest`、`CProjectBuilderIntegrationTest`、`FrontendLoweringToCProjectBuilderIntegrationTest`、`GdScriptUnitTestCompileRunnerTest`、`GdScriptBenchmarkCompileTest`；
- 真 zig/Godot 依赖一律 `ZigUtil.findZig()` + `Assumptions` 门控，与现有集成测试写法一致；
- 涉及 native compiler 行为的 negative path 一律直调 `ZigCcCompiler.compile(...)`（含精心构造的 `.c` 与独立 include 树），不得通过破坏 shared-include / 共享 cache 实现；
- 性能数字一律为手工参考值（注明参考机器），写入 PR 描述，**不进入 CI 断言**；
- 每步完成后运行该步验收列出的定向测试；全部步骤完成后 `./gradlew clean build --no-daemon --info --console=plain`；
- 测试失败先查实现根因，不得改测试去适配新行为；
- 代码风格遵循 AGENTS.md 与 `doc/module_impl/common_rules.md`：`var`/record/`///` 文档注释、最小注释、`requireXxx`/`checkXxx` 命名（`findXxx` 保持现有不抛错模型）、字符串处理复用 `StringUtil`、自定义异常入 `gd.script.gdcc.exception`。
