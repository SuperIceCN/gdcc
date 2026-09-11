# JSON-RPC 服务与编辑器插件实施计划

> 本文档是将 `gd.script.gdcc.api.API` 通过 HTTP JSON-RPC 暴露出去、以及消费该服务的
> Godot 编辑器插件（GDScript 客户端）的实施计划。它是一份**计划**，尚不是事实源：
> 除步骤 A0 对现有 API 源码收集的修改外，文中描述的代码目前都不存在。实现落地后，
> 必须按 `doc/module_impl/common_rules.md` 的要求将本文档转换为事实源文档（或按
> 子系统拆分），并移除步骤/验收章节。

## 文档状态

- 状态：实施计划——待实现
- 更新日期：2026-09-11
- 范围：
  - `src/main/java/gd/script/gdcc/api/ModuleState.java`（源码收集过滤，步骤 A0）
    及相关阶段文案/错误消息与 API 测试
  - 新包 `src/main/java/gd/script/gdcc/rpc/**`
  - `src/main/java/gd/script/gdcc/Main.java`（仅入口路由）
  - `src/main/java/module-info.java`（新增 `jdk.httpserver` 依赖声明）
  - `src/editor_addon/**`（Godot 编辑器插件 + GDScript JSON-RPC 客户端库）
  - 新测试：`src/test/java/gd/script/gdcc/rpc/**` 与
    `src/test/java/gd/script/gdcc/api/**` 的增补
- 直接事实源：
  - `doc/module_impl/api/rpc_api_implementation.md`（进程内 API 门面合同）
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/cli/cli_implementation.md`（适配层先例、版本资源）
  - `doc/test_suite.md` 与 `doc/test_error/*`（引擎集成测试规则与限制）
  - `src/main/java/gd/script/gdcc/api/API.java`
- 明确非目标：
  - Godot 的 `@rpc` 多人游戏特性（见 `rpc_api_implementation.md` §12）。
  - 认证/授权、TLS、非 loopback 暴露加固。
  - JSON-RPC 批量请求、位置（by-index）参数、WebSocket/流式传输。
  - 将编辑器插件发布到任何资产库。
  - 让 `plugin.gd` / dock UI 脚本可被 gdcc 编译。只有客户端库
    （`gdcc_rpc_client.gd3`）是自举编译目标。

---

## 1. 目标与分层

RPC 服务让 Godot 编辑器插件能够在本地驱动编译器：创建 module、把 GDScript 源码上传到
module VFS、执行分析、启动编译、轮询任务进度。编辑器插件附带一个 GDScript JSON-RPC
客户端库，该库本身可被 gdcc 编译（自举），并由一个引擎集成测试证明：在真实 Godot
进程中运行 gdcc 编译出的客户端，调用真实的 RPC 服务端。

分层（对应 `rpc_api_implementation.md` §1 与 CLI 先例）：

1. GDScript 客户端（编辑器插件）编码 JSON-RPC 2.0 请求并通过 HTTP POST 发送。
2. Java HTTP/JSON-RPC 适配层（`gd.script.gdcc.rpc`）校验信封、绑定参数、映射异常并
   序列化结果。
3. `gd.script.gdcc.api.API` 拥有 module 状态、编译任务与分析。适配层不得复制任何
   编译器、VFS 或任务语义。
4. CLI（`gd.script.gdcc.cli`）保持不动；其文档已明确将网络传输排除在 CLI 范围之外。

---

## 2. Java RPC 服务端合同

### 2.1 传输层

- 使用 JDK 内置 HTTP 服务端：`com.sun.net.httpserver.HttpServer`（模块
  `jdk.httpserver`）。不引入新的 Maven/Gradle 依赖。`module-info.java` 增加
  `requires jdk.httpserver;`（属于 `src/` 源文件，不是构建脚本）。
- JSON 使用现有 Gson 依赖（`com.google.code.gson:gson:2.13.2`）。
- 单一端点：`POST /rpc`，`Content-Type: application/json`（允许带
  `; charset=...` 后缀；body 一律按 UTF-8 解码）。
- HTTP 状态码规则：
  - 对任何格式良好的 POST（body 为 JSON 或类 JSON 文本）返回 `200` 与 JSON-RPC 响应
    对象，包括 JSON-RPC 解析错误与方法错误。
  - notification（无 `id` 的请求）执行后返回 `204` 与空 body。
  - body 超过配置的请求大小上限时返回 `413`。handler 必须通过计数
    `InputStream` 读取 body，到达上限立即停止、回答 `413` 并关闭 exchange；
    `HttpServer` 没有 max-body 设置，直接 `readAllBytes()` 会先把超大 body 缓冲进
    内存再拒绝，使上限形同虚设。
  - 非 JSON 媒体类型返回 `415`；非 POST 方法返回 `405`（并携带 `Allow: POST`
    响应头）。
- 默认值：host `127.0.0.1`（仅 loopback），port `6099`，请求体上限 16 MiB。
  允许通过显式参数绑定非 loopback host，但会记录警告日志，因为 v1 没有认证
  （见 §2.7）。
- JSON-RPC 2.0 信封规则：
  - 请求：`{"jsonrpc": "2.0", "method": <string>, "params": <object>, "id": <string|number>}`。
  - `params` 必须是 by-name 对象；by-position 数组参数以 `-32602` 拒绝。
  - 批量数组以 `-32600` 拒绝（后续工作）。
  - 响应：`{"jsonrpc": "2.0", "result": ...}` 或
    `{"jsonrpc": "2.0", "error": {"code", "message", "data"?}}`，并回显请求的 `id`。

### 2.2 方法面

方法名采用点分命名空间，与公开 `API` 面一一对应。
`API.recordCurrentCompileTaskEvent(...)` 被排除，因为它绑定进程内编译线程，远程调用
没有意义；未分页的 `listCompileTaskEvents(taskId)` 重载也被排除，因为 RPC 面始终使用
分页、带索引的变体。

所有方法都按 request/response 用法设计：GDScript 客户端总是携带 `id`。不带 `id` 的
请求仍是合法的 JSON-RPC notification；服务端执行它、丢弃结果或错误，并回答 `204`
（见 §2.1）。

| JSON-RPC 方法 | params（by-name） | 结果 |
|---|---|---|
| `server.ping` | 无 | `"pong"` |
| `server.info` | 无 | `{version, branch, commit, maxCompileTaskEventPageSize}` |
| `module.create` | `{moduleId, moduleName}` | `ModuleSnapshot` |
| `module.get` | `{moduleId}` | `ModuleSnapshot` |
| `module.list` | `{}` | `ModuleSnapshot[]` |
| `module.delete` | `{moduleId}` | `ModuleSnapshot` |
| `vfs.createDirectory` | `{moduleId, path}` | `DirectoryEntrySnapshot` |
| `vfs.putFile` | `{moduleId, path, content, displayPath?}` | `FileEntrySnapshot` |
| `vfs.readFile` | `{moduleId, path}` | 文件内容字符串 |
| `vfs.deletePath` | `{moduleId, path, recursive}` | `VfsEntrySnapshot` |
| `vfs.listDirectory` | `{moduleId, path}` | `VfsEntrySnapshot[]` |
| `vfs.readEntry` | `{moduleId, path}` | `VfsEntrySnapshot` |
| `vfs.createLink` | `{moduleId, path, linkKind, target}` | `LinkEntrySnapshot` |
| `options.get` | `{moduleId}` | `CompileOptions` |
| `options.set` | `{moduleId, compileOptions}` | `CompileOptions`（替换后回显） |
| `classMap.get` | `{moduleId}` | `Map<String,String>` |
| `classMap.set` | `{moduleId, topLevelCanonicalNameMap}` | `Map<String,String>`（替换后回显） |
| `compile.start` | `{moduleId}` | `{taskId}` |
| `compile.getTask` | `{taskId}` | `CompileTaskSnapshot` |
| `compile.cancel` | `{taskId}` | `CompileTaskSnapshot` |
| `compile.getLastResult` | `{moduleId}` | `CompileResult` 或 `null` |
| `compile.listEvents` | `{taskId, startIndex?=0, maxCount?=1000, category?}` | `Indexed[]`（`{index, event}`） |
| `compile.getLatestEvent` | `{taskId}` | `CompileTaskEvent`；任务无事件时为 `null` |
| `compile.clearEvents` | `{taskId}` | `null` |
| `analyze.run` | `{moduleId, includeLowering?=false}` | `AnalysisResult` |

说明：

- `compile.listEvents` 始终使用分页、带索引的 API 变体，以保证 wire shape 稳定；
  `maxCount` 受 `API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE`（1000）上限约束。
- `options.set` 接受与 `options.get` 返回完全相同的 JSON 形状（对称 wire format）；
  `compileOptions` 必须是完整对象，因为 `API.setCompileOptions` 替换整个快照。
- `classMap.*` 是 `getTopLevelCanonicalNameMap` / `setTopLevelCanonicalNameMap` 的
  有意短别名。该 map 在其他所有位置（例如 `ModuleSnapshot` 内）都以 Java 组件名
  `topLevelCanonicalNameMap` 往返，`classMap.set` 的参数也使用同一字段名，客户端
  永远不需要处理两种拼写。

### 2.3 Wire Format

序列化拆成两层，使 `serializeNulls` 永远无法污染信封：

- **信封**（`JsonRpcDispatcher`，手工构建 `JsonObject`）：成功响应只包含
  `{"jsonrpc": "2.0", "result": ...}`，错误响应只包含
  `{"jsonrpc": "2.0", "error": {...}}`——JSON-RPC 2.0 禁止同时携带两个成员，所以
  信封绝不由开启了 `serializeNulls` 的 Gson 实例生成。请求的 `id` 以原始
  `JsonElement` 捕获并逐字回显（不经过 `Object`/`Double` 往返，整数 id 永远不会
  变成 `1.0` 返回）；解析错误时 `id` 成员为 JSON `null`。
- **DTO codec**（`RpcJsonCodec`，共享 Gson 实例）：开启 `serializeNulls`，使结果
  形状对 GDScript `Dictionary` 消费方保持稳定。

DTO codec 规则：

- record 按组件名序列化为 JSON 对象。
- 枚举序列化为其 Java `name()` 字符串；params 中的未知枚举名以 `-32602` 失败。
  wire 值有意与 CLI 参数拼写（`cli_implementation.md`）不同：`godotVersion` 为
  `"V451"`，`optimizationLevel` 为 `"DEBUG"` 或 `"RELEASE"`，`targetPlatform` 为
  `"WINDOWS_X86_64"`、`"WINDOWS_AARCH64"`、`"LINUX_X86_64"`、`"LINUX_AARCH64"`、
  `"LINUX_RISCV64"`、`"ANDROID_X86_64"`、`"ANDROID_AARCH64"`、`"WEB_WASM32"` 之一。
  `RpcJsonCodecTest` 钉死该表。
- `java.nio.file.Path` 序列化为 `Path.toString()`（主机路径文本；暴露策略见 §2.7），
  反序列化经 `Path.of(...)`。
- `java.time.Instant` 序列化为 ISO-8601 UTC 字符串（`Instant.toString()`）。
- `long` 值（`taskId`、`revision`、事件 `index`、`byteCount`）序列化为 JSON number。
  Godot 整数是 64 位，目标客户端不存在精度损失。
- `VfsEntrySnapshot`（sealed interface）使用自定义 serializer 作为硬合同；普通的
  record 反射**不够**，因为 `kind()` 与 `path()` 是派生方法而非组件，且
  `FileEntrySnapshot.path()` 返回 display path。serializer 输出公共字段（`kind`、
  `path`、`virtualPath`、`name`）加上 `rpc_api_implementation.md` §4.4 定义的子类型
  字段，例如一个 file 条目：
  `{"kind": "FILE", "path": "res://main.gd", "virtualPath": "/src/main.gd",
  "name": "main.gd", "displayPath": "res://main.gd", "byteCount": 12,
  "updatedAt": "2026-09-11T08:30:00Z"}`。`RpcJsonCodecTest` 钉死全部三个子类型的
  完整 JSON 形状。快照永远不需要反序列化，因为它们只用于输出。
- 派生 record 方法（`success()`、`completed()`、`hasErrors()`、`broken()` 等）不是
  record 组件，不序列化。

参数绑定算法（每个方法一个专用 param record）：

1. 缺失或 JSON-`null` 的 `params` 成员按 `{}` 处理。
2. 必填组件——包括 `recursive`、`taskId` 这类本应是原语的字段——必须在 params
   对象中出现且非 null；缺失或 JSON-`null` 的必填组件以 `-32602` 失败。因此 param
   record 的**每个组件都声明装箱类型**（`Boolean recursive`、`Long taskId`……），
   因为 Gson 会给缺失的原语字段填零值，缺失将与显式 `false`/`0` 无法区分。
   （绑定前检查 `JsonObject.has(name)` 是等价替代方案；不允许的是依赖原语默认值。）
3. 可选组件在 param record 上使用装箱类型以便检测缺失：`Long startIndex`
   （默认 `0`）、`Integer maxCount`（默认 `API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE`）、
   `Boolean includeLowering`（默认 `false`）、`String displayPath` /
   `String category`（默认 `null`）。
4. 类型错误的组件（Gson `JsonSyntaxException`/`JsonParseException`）以 `-32602` 失败。
5. param record 在紧凑构造器中执行 blank/格式校验，覆盖那些 API 层更晚才会失败的
   情况；API 层抛出的 `IllegalArgumentException` **和** `NullPointerException`
   （`Objects.requireNonNull` / `StringUtil.requireTrimmedNonBlank`）都映射为
   `-32602`，绝不映射为 `-32603`。

### 2.4 错误映射

| JSON-RPC 错误码 | 条件 |
|---|---|
| `-32700` | JSON body 格式错误 |
| `-32600` | 信封非法（`jsonrpc` 缺失/错误、`method` 缺失、批量数组） |
| `-32601` | 未知方法名 |
| `-32602` | params 非法（绑定失败、校验失败、API 参数检查抛出的 `IllegalArgumentException` 或 `NullPointerException`） |
| `-32603` | 未预期的内部错误（兜底；记录日志） |
| `-32000` | `ApiModuleNotFoundException` |
| `-32001` | `ApiModuleAlreadyExistsException` |
| `-32002` | `ApiModuleBusyException` |
| `-32003` | `ApiCompileAlreadyRunningException` |
| `-32004` | `ApiCompileTaskNotFoundException` |
| `-32005` | `ApiPathNotFoundException` |
| `-32006` | `ApiEntryTypeMismatchException` |
| `-32007` | `ApiDirectoryNotEmptyException` |
| `-32008` | `ApiBrokenLinkException` |
| `-32009` | `ApiLinkCycleException` |

`error.data` 携带 `{exception: <simple class name>, message: <exception message>}`，
客户端可以按异常类型分支，无需解析 message 文本。

普通的编译/分析失败**不是** JSON-RPC 错误：它们按门面的定义保留在
`CompileResult.outcome` / `AnalysisResult.outcome` 与 diagnostics 中。

### 2.5 入口与打包

- `Main.run(args)` 在 CLI 委托之前增加首参路由，写法对 null 与空数组安全，裸 `gdcc`
  调用仍然到达 picocli 的 usage 错误，而不是 `ArrayIndexOutOfBoundsException`：

  ```java
  if (args.length > 0 && "serve".equals(args[0])) {
      return RpcServeCommand.execute(Arrays.copyOfRange(args, 1, args.length));
  }
  return GdccCommand.execute(args);
  ```

  扩展 `MainEntrypointTest` 风格的三条路由覆盖：无参数（CLI usage 错误）、
  `serve --help`、无法识别的首参（CLI 错误）。
- `RpcServeCommand` 是 picocli `Callable<Integer>`，带 `mixinStandardHelpOptions`：
  `--host`（默认 `127.0.0.1`）、`--port`（默认 `6099`）、`--max-request-bytes`
  （默认 16 MiB）。它拥有服务端生命周期：创建 `API`、创建并启动 `JsonRpcServer`、
  阻塞至 SIGINT、优雅停止。
- `server.info` 复用 CLI `--version` 输出已在使用的 `gdcc-version.properties` 生成
  资源机制。
- zig launcher 原样把用户参数传给 jar，因此 `gdcc serve` 无需 launcher 改动即可在
  原生 launcher 下工作。

### 2.6 并发与生命周期

- `JsonRpcServer` 在 `HttpServer` 上安装虚拟线程 executor
  （`Executors.newVirtualThreadPerTaskExecutor()`，与服务端一同关闭），每个请求在
  自己的虚拟线程上运行；按 module 的串行化仍由 API module gate 负责
  （`rpc_api_implementation.md` §8）。适配层除共享的 `API` 实例外不持有跨请求可变
  状态。
- `compile.start` 立即返回 task id。在某 module 有排队或进行中的编译期间，该 module
  的所有 module 门控调用（`module.*`、`vfs.*`、`options.*`、`classMap.*`、
  `analyze.run`、`compile.getLastResult`）都会在 module gate 上阻塞直到编译结束，
  且 `analyze.run` 本身就是同步的。虚拟线程保证服务端整体仍可响应，但发起调用的
  客户端仍会等待。因此进度轮询只能使用任务表读取 `compile.getTask`、
  `compile.listEvents`、`compile.getLatestEvent`——它们不进入 module gate；GDScript
  客户端的轮询循环只建立在 `compile.getTask` 之上。`compile.start` 本身总是立即返回
  task id；适配层不在 API gate 已有行为之外额外 join 整个编译。
- 测试在端口 `0`（临时端口）启动服务端并读回实际端口。

### 2.7 安全边界（v1）

按 `rpc_api_implementation.md` §11，传输策略由适配层负责。v1 策略：

- 默认仅绑定 loopback；绑定非 loopback 需要显式参数并记录警告日志；
- 请求体大小上限；
- 不提供产物/主机文件下载端点：`LOCAL` link target 仅作为元数据；
- 主机路径（`CompileOptions.projectPath`、`CompileResult.generatedFiles`、
  `artifacts`）以明文字符串暴露。这对本地单用户编辑器场景是可接受的，并与
  `rpc_api_implementation.md` §13 的条目一起列为后续加固项（脱敏/不透明句柄）。

---

## 3. GDScript 客户端与编辑器插件合同

### 3.1 文件布局

所有插件文件位于 `src/editor_addon/addons/gdcc/`：

- `plugin.cfg` — Godot 4.5 插件清单，五个常规字段齐全，否则插件设置页可能无法列出
  或启用它：

  ```ini
  [plugin]

  name="GDCC"
  description="Drive the local gdcc JSON-RPC compiler service from the editor."
  author="gdcc"
  version="0.1.0"
  script="plugin.gd"
  ```
- `plugin.gd` — `@tool extends EditorPlugin`；拥有 dock 与一个 `GdccRpcClient` 节点；
  在编辑器中解释执行（不是自举目标）。
- `gdcc_dock.gd` — `@tool extends VBoxContainer`；用代码构建的最小 UI：host/port 输入、
  连接检查（`server.ping`）、module id 输入、按钮（创建 module、上传当前脚本、
  analyze、compile、cancel），以及只读日志 `TextEdit`。解释执行。
- `gdcc_rpc_client.gd3` — JSON-RPC 客户端库源码。**自举编译目标。** 使用专属扩展名
  `.gd3` 而非 `.gd`：Godot 引擎（含编辑器）不加载、不解析、不注册 `.gd3` 文件，
  因此源码中的 `class_name GdccRpcClient` 与编译产物 GDExtension 注册的同名类
  **从构造上就不会冲突**（§4.5 的副本项目也无需删除该文件）。`.gd3` 后续将作为
  gdcc addon 在 Godot 引擎中识别"需要编译的脚本"的专属扩展名（§8 后续工作）。
  文件内容携带顶层 `@tool` 注解（gdcc 已支持，见
  `frontend_annotation_implementation.md`）：虽然 `.gd3` 不被引擎执行，`@tool`
  使同一源码在任何直接以 GDScript 形式使用它的上下文中保持正确语义。
- 源码收集规则（根源修复，步骤 A0）：API 编译/分析的源码收集从"只认 `.gd`
  虚拟路径"扩展为"同时认 `.gd` 和 `.gd3`"（`ModuleState.rememberSource()` 是唯一
  功能过滤点，compile 与 analyze 共用）。因此 `gdcc_rpc_client.gd3` 以 `.gd3`
  虚拟路径原样写入模块 VFS（如 `/src/gdcc_rpc_client.gd3`），无需任何扩展名
  映射；`displayPath` 可同样使用 `res://addons/gdcc/gdcc_rpc_client.gd3`。

### 3.2 GDScript 兼容性约束（自举目标）

`gdcc_rpc_client.gd3` 必须能被当前 gdcc 编译。以下来自前端实现文档与 4.5.1 扩展元
数据的约束已核实：

- 持有引擎对象的局部变量一律标注类型，使调用走 exact engine route
  （`var http: HTTPRequest = HTTPRequest.new()`），依据
  `frontend_exact_call_extension_metadata_contract.md`；
- 支持对信号的 `await`（gdcc 能力，包括 `HTTPRequest.request_completed` 与自定义
  信号）；但**本文件禁止使用**：`gdcc_rpc_client.gd3` 不得出现 `await`/任何协程
  （§3.2 跨边界协程限制决定了公开 API 的形态），value-position 协程调用限制因此
  对本文件不适用；
- 不使用 `Callable.bind/unbind`，不把 Dictionary 方法引用当 Callable；
- **跨边界协程限制（决定公开 API 形态）**：gdcc 把每个协程编译为隐藏 `RefCounted`
  状态类并注册 `completed(result)` 信号。跨越引擎调用边界时，只有声明 `Variant`
  返回值的编译协程会把状态对象交回调用方，且解释型调用方必须显式
  `await state.completed`；声明具体返回类型（如 `-> Dictionary`）的协程在挂起时只
  向外部调用方返回类型默认值并报告运行时错误，`void` 协程则不交回任何可等待对象
  （`frontend_await_implementation.md`、`gdcc_runtime_lib.md`）。因此编译客户端的
  公开 API **不含任何协程**；异步结果改由"按请求挂起对象 + `completed` 信号"
  跨越边界——这正是 gdcc 协程状态对象自身的互操作先例（隐藏 `RefCounted` +
  `completed(result)` 自发射；解释型 `await state.completed` 有直接锚点
  `interop_state_completed_signal.gd`）；
- 信号发射采用 pending **自发射**：客户端调用 `pending._finish(...)` 普通方法，
  方法内 `completed.emit(...)`；不做跨对象 materialized emit（无直接锚点），也
  不使用 `emit_signal(...)`（无锚点）；
- `JSON.parse_string(...)` 返回 `Variant`：用 `typeof(...) == TYPE_DICTIONARY` /
  `is Dictionary` 守卫，并在类型边界显式转换（`int(...)`、`str(...)`）；
- 只用普通 Array/Dictionary 字面量；不用嵌套 typed 容器；
- `class_name` + `extends Node`、`static func`、`match`、`signal`/`emit`/`connect`
  与元数据支持的 `String` 方法均可用；
- `HTTPRequest`、`HTTPClient`、`JSON`、`JSONRPC`、`FileAccess`、`ClassDB` 都存在于
  `extension_api_451.json`。元数据存在加上通用的信号 connect/emit 规则，只说明
  这些 API *可达*，并未被证明：`JSON.parse_string` 有测试锚点，但
  `JSON.stringify`、`PackedByteArray.get_string_from_utf8()`、`HTTPRequest` 全链路
  都只有元数据。步骤 B1 验证 lowering，只有步骤 B3 才能证明它们在编译产物中的
  运行时行为。

没有现有测试覆盖的构造不得出现在客户端的关键路径上。特别地，v1 **不**使用
"`while` 循环 await 自定义信号"作为互斥锁（无测试锚点）；并发由普通 Array 队列加
信号驱动的泵处理（§3.3），整条路径不含任何编译协程。未来任何超出上述清单的构造，
都必须先通过步骤 B1 的分析门禁证明可用。

### 3.3 客户端库 API

`gdcc_rpc_client.gd3`（编译目标，公开 API 全部是非协程的普通方法，见 §3.2 的跨边界
协程限制）：

```gdscript
@tool
class_name GdccRpcClient
extends Node

class PendingRequest extends RefCounted:
    signal completed(response: Dictionary)

    func _finish(response: Dictionary) -> void:
        completed.emit(response)

var host: String = "127.0.0.1"
var port: int = 6099
```

- 结果形态：`call_rpc` 返回一个按请求创建的 `PendingRequest` 挂起对象，解释型
  调用方直接 `await` 其 `completed` 信号——这正是 gdcc 协程状态对象的互操作
  先例（隐藏 `RefCounted` + `completed(result)` 自发射，解释型
  `await state.completed` 有直接锚点，见 §7 风险 2）。调用写法：

  ```gdscript
  var res: Dictionary = await client.call_rpc("server.ping", {}).completed
  # 或两步写法（动态 Object，最不依赖链式解析）：
  var pending: Object = client.call_rpc("server.ping", {})
  var res2: Dictionary = await pending.completed
  ```

  注意：**不**采用"返回共享 Signal 对象"的形态——共享信号无法区分并发请求，
  也解决不了先发射后 await 的时序问题；按请求挂起对象同时消除关联与乱序问题。
- 使用合同：`GdccRpcClient` 必须先加入 `SceneTree` 才能发起请求（测试中
  `root.add_child(client)`；插件把它加为 `EditorPlugin` 的子节点）。`_ready()` 以
  显式类型创建内部 `HTTPRequest` 子节点
  （`var http: HTTPRequest = HTTPRequest.new()`），把 `http.request_completed`
  connect 到自身的普通方法 `_on_request_completed(...)`，并以
  `var tree: SceneTree = get_tree()` 显式标注后把 `tree.process_frame` 持久
  connect 到 `_on_process_frame()`（Callable 方法引用与引擎信号 connect 的基元
  均有锚点，但该完整组合无直接 fixture，见 §7 风险 2）；节点不在树内时
  `HTTPRequest.request()` 返回 `ERR_UNCONFIGURED`。
- **信号时序合同（关键）**：普通 signal 没有结果缓存，`completed` 在 `await`
  建立 one-shot 连接之前发射就会让调用方永久挂起。因此：
  1. 客户端保证 `completed` 只从异步上下文发射——`_on_request_completed`
     （引擎信号栈）与 `_on_process_frame`（帧回调栈冲刷 `_pending_finish`）；
     帧泵保证任何完成都发生在 `call_rpc` 返回之后的更晚帧，绝不发生在
     `call_rpc` 的同步调用栈上。不使用 `Signal.emit_deferred`（4.5.1 元数据中
     不存在）或 `call_deferred`（编译代码内无调用锚点）。
  2. 调用方必须在拿到 `PendingRequest` 后的**同一同步执行段**内
     `await pending.completed`（中间不得插入任何帧让渡）；driver、dock 与辅助
     写法天然满足该约束。
- 请求面：`func call_rpc(method: String, params: Dictionary = {}) -> PendingRequest`
  ——**普通方法**，创建 `PendingRequest`，分配单调递增的 JSON-RPC 协议 id
  （仅用于信封 `id` 字段，不对调用方暴露），把
  `{pending, id, method, params}` 追加到内部 `Array` 队列（队列原语只用已有锚点
  的 `push_back(...)`、`size()`、下标与 `remove_at(0)`，不使用无锚点的
  `pop_front()`），立即返回 pending。`call_rpc` 不发送、不发射、不调用
  `_pump_next()`；发送完全由帧回调驱动。
- 帧泵合同（单一泵站点，成功与失败路径共用同一出队+泵状态机；队列只含未发送
  项，在途请求单独持有于 `_inflight`，先出队再发送）：

  ```gdscript
  func _on_process_frame() -> void:
      # 1) 冲刷上一帧积压的同步失败结果（异步上下文，发射安全）
      while _pending_finish.size() > 0:
          var item: Dictionary = _pending_finish[0]
          _pending_finish.remove_at(0)
          (item["pending"] as PendingRequest)._finish(item["response"])
      # 2) 空闲且队列非空时驱动发送
      if _inflight == null and _queue.size() > 0:
          _pump_next()

  func _pump_next() -> void:
      var item: Dictionary = _queue[0]
      _queue.remove_at(0)  # 先出队，再发送
      _inflight = item["pending"] as PendingRequest
      # 从队列项构建信封并编码（普通 Dictionary，不经引擎 JSONRPC 类）
      var envelope: Dictionary = {"jsonrpc": "2.0", "method": item["method"],
          "params": item["params"], "id": item["id"]}
      var body: String = JSON.stringify(envelope)
      var url: String = "http://%s:%d/rpc" % [host, port]
      var headers := PackedStringArray(["Content-Type: application/json"])
      var err: int = http.request(url, headers, HTTPClient.METHOD_POST, body)
      if err != OK:
          # request() 同步失败时不会发射 request_completed；不发射，改压入
          # _pending_finish，下一帧由 _on_process_frame 冲刷，然后继续泵下一项
          var failed: PendingRequest = _inflight
          _inflight = null
          _pending_finish.push_back({"pending": failed, "response": {"ok": false,
              "error": {"code": -1, "message": "HTTPRequest.request failed: %s" % err}}})
          if _queue.size() > 0:
              _pump_next()
  ```

  发送时把信封构建为普通 Dictionary（`{"jsonrpc": "2.0", "method": method,
  "params": params, "id": id}`，不依赖引擎 `JSONRPC` 类），用 `JSON.stringify`
  序列化，POST 到 `http://<host>:<port>/rpc`，显式携带
  `PackedStringArray(["Content-Type: application/json"])` 与
  `HTTPClient.METHOD_POST`（`request()` 默认是 GET）。
- 响应泵：`_on_request_completed` 是普通方法（不是协程），参数按引擎信号签名
  标注类型以走 exact route：

  ```gdscript
  func _on_request_completed(
          result: int,
          response_code: int,
          headers: PackedStringArray,
          body: PackedByteArray
  ) -> void:
  ```

  它先检查 `result == HTTPRequest.RESULT_SUCCESS` 且 `response_code == 200`（其余
  一律视为传输失败），用 `get_string_from_utf8()` 解码 body，再用
  `JSON.parse_string(...)` 解析并以 `typeof(...) == TYPE_DICTIONARY` 守卫；然后
  取出 `_inflight`、复位为 `null`，调用 `pending._finish(normalized)`（pending
  自发射 `completed`，不跨对象发射）。它不调用 `_pump_next()`；下一帧的
  `_on_process_frame` 自然驱动后续请求（每请求至多增加一帧延迟，对本场景可
  忽略）。单个 `HTTPRequest` 节点只服务一个请求，队列因此天然串行化，无需忙
  标志或互斥锁。规范化响应形状：
  - 成功：`{"ok": true, "result": <Variant>}`
  - RPC 错误：`{"ok": false, "error": {"code": <int>, "message": <String>, "data": <Variant>}}`
  - 传输/解析失败：`{"ok": false, "error": {"code": <int>, "message": <String>}}`，
    使用与服务端区间不同的客户端本地负错误码（从 `-1` 开始）。
- 类型化便捷 wrapper（`call_rpc` 之上的薄封装，同样返回 `PendingRequest` 的普通
  方法）：`ping`、`server_info`、`create_module`、`delete_module`、`put_file`、
  `read_file`、`list_directory`、`set_compile_options`（接受 `options.get` wire
  形状的普通 Dictionary）、`start_compile`、`get_compile_task`、
  `cancel_compile_task`、`get_last_compile_result`、`list_compile_task_events`、
  `analyze`。wrapper 的参数名与 §2.2 保持一致。
- 生命周期：`PendingRequest` 是普通 `RefCounted` 对象（fresh object return 的
  ownership 合同见 `gdcc_ownership_lifecycle_spec.md`）；客户端在队列/在途槽中
  持有它直到 `_finish(...)`，调用方持有它直到 `await` 返回，双方随后各自释放，
  无跨边界保活问题。

wrapper 与 driver 的结果形状规则：规范化响应成功时为
`{"ok": true, "result": <method result>}`，所以 `compile.start` 的结果是
`rpc["result"]["taskId"]`（用 `int(...)` 转换，wire 值是 JSON number）；任务快照的
生命周期字符串在 `rpc["result"]["state"]`
（`"QUEUED"/"RUNNING"/"SUCCEEDED"/"FAILED"/"CANCELED"`）；编译结果在更深一层
`rpc["result"]["result"]["outcome"]`（`"SUCCESS"` 属于 `CompileResult.outcome`，
绝不属于任务的 `state`）。

**gdcc 编译消费者（后续 addon 编译脚本使用该库的形态）**：编译代码同样可以直接
`await`，但形态与解释型不同——不标 `.completed`，而是把挂起对象按 `Variant`
交给 gdcc 的 dynamic `completed` 路由（runtime 对外部对象 duck-type 查找
`completed(result)`，与 await gdcc 协程状态对象完全同形）：

```gdscript
func fetch_module() -> Variant:
    var pending: Variant = client.call_rpc("module.get", {"moduleId": "demo"})
    var response: Dictionary = await pending  # 单参数信号，结果是 Dictionary 本身
    return response
```

约束（依据 `frontend_await_implementation.md`、`gdcc_runtime_lib.md` 与 inner class
合同）：

- 不写 `GdccRpcClient.PendingRequest` 类型标注：跨脚本 qualified inner class 类型
  引用当前无支持锚点（inner class 的 source-facing 名字只在词法命名空间内可见）；
- 不把挂起对象标注为 `Object`/`RefCounted`：await 分类中只有 `Variant` 进入
  dynamic 路由，静态非 Signal 对象会被拒绝；
- 不写链式 `await client.call_rpc(...).completed`：无锚点；一律两步（先取
  `Variant` 挂起对象，再 `await pending`）；
- 同一同步段内 `await`：外部 `completed` 对象没有结果缓存，完成后再连接会永久
  挂起（与解释型调用方的时序合同相同）；
- 消费者函数因 `await` 成为协程：若它又被解释型脚本调用，同样适用 §3.2 的跨边界
  规则——声明 `-> Variant`（解释型调用方拿到状态对象后 `await state.completed`），
  不要声明具体返回类型。
该路径（dynamic `completed` 路由 + 编译 `PendingRequest` 对象的组合）尚无端到端
fixture，列入 §8 后续工作。

### 3.4 插件与 Dock（仅解释执行）

- `plugin.gd` 在 `_enter_tree()` 中通过
  `add_control_to_dock(EditorPlugin.DOCK_SLOT_RIGHT_UL, dock)` 创建 dock，在
  `_exit_tree()` 中用 `remove_control_from_docks(dock)` 加 `dock.free()` 移除，并把
  一个 `GdccRpcClient` 节点加为自己的子节点（使客户端位于编辑器 `SceneTree` 内）。
  注意：`.gd3` 源码不被引擎加载，编辑器中的 `GdccRpcClient` 只能来自**已安装的
  编译产物 GDExtension**；因此"启用插件 + dock ping"的手工验收并入 B3，在 §4.5
  产出的已安装扩展的副本项目中执行（见 B2/B3 验收）。
- dock 直接以 `var res: Dictionary = await client.create_module(...).completed` 的
  形式驱动 `GdccRpcClient`（挂起对象信号，§3.3；无需任何辅助文件），并向
  日志追加人类可读的行。它按 §2.2/§2.3 的定义精确读取 JSON 中的 record 组件名：
  ping 结果、module 快照、analyze 结果与 diagnostics
  （`severity`/`category`/`message`/`sourcePath`/`range`）、编译任务进度
  （`state`/`stage`/`completedUnits`/`totalUnits`；没有单独的 `progress` 字段，
  `createdAt`/`completedAt` 是 ISO-8601 字符串），以及最终的
  `CompileResult.outcome`。
- dock 绝不阻塞编辑器主线程；所有网络等待都是信号 await。

---

## 4. 测试计划

新测试位于 `src/test/java/gd/script/gdcc/rpc/`。被测 GDScript 源码直接从仓库读取
（`src/editor_addon/...`），沿用 `test_project` 先例。环境门控测试沿用现有假设模式
（`ZigUtil.findZig()` 或 `GodotGdextensionTestRunner.findGodotBinaryFromEnv()`
返回 `null` 时 `Assumptions.abort(...)`）。

### 4.1 Java 单元测试（无 HTTP，始终可运行）

- `RpcJsonCodecTest` —— 往返 §2.2 中每个结果 DTO；钉死 `ModuleSnapshot`（包括
  `topLevelCanonicalNameMap` 组件名）、`CompileOptions`（含 `null` `projectPath`）、
  `CompileResult`、`CompileTaskSnapshot`、`AnalysisResult`、`DiagnosticSnapshot`
  （含与不含 `range`）、全部三个 `VfsEntrySnapshot` 子类型（按 §2.3 的完整预期
  JSON）、`CompileTaskEvent.Indexed` 的精确 JSON 字段名与形状；Path/Instant 编码；
  §2.3 的完整枚举 wire 值表；param record 绑定失败。
- `JsonRpcDispatcherTest` —— 信封规则（成功不携带 `error` 成员、错误不携带
  `result` 成员、数字与字符串 id 逐字回显、解析错误时 `id: null`）、§2.3 的参数
  绑定算法（缺必填 `recursive` → `-32602`；缺 `maxCount` 按 `1000` 而非 `0`
  处理；缺 `moduleId` → `-32602`，即使 API 抛出的是 `NullPointerException`）、
  §2.4 的每个错误码（通过真实 `API` 实例触发，例如对不存在的 module 调
  `module.get` → `-32000`）、notification 执行后不产生响应对象、批量拒绝、
  空事件日志使 `compile.getLatestEvent` 返回 `null`，以及全部 25 个方法的路由。
  dispatcher 测试直接构造 `new API()`；`ApiCompileTestSupport` 对 api 测试包是
  package-private，不复用。

### 4.2 Java HTTP 集成测试（无引擎、无 zig）

- `RpcServerHttpTest` —— 临时端口上的真实服务端加 JDK `HttpClient`：POST 往返、
  `Content-Type` 强制（`415`）、经有界读取的大小上限（`413`，用刚好超过上限的
  body 覆盖）、非 POST（`405` 带 `Allow: POST`）、格式错误的 JSON（`200` +
  `-32700`）、notification（`204`）。
- `RpcApiRoundTripHttpTest` —— 真实 `API` 上的完整 HTTP 工作流：`module.create` →
  `vfs.putFile`（两个源文件）→ `options.get`/`options.set` →
  `classMap.set`/`classMap.get` → `analyze.run`（合法源码预期 `COMPLETED` 且无
  diagnostics；非法源码预期带 diagnostics）→ `module.delete`。该路径绝不启动原生
  构建。

### 4.3 zig 门控的 Compile-over-HTTP 集成测试

- `RpcCompileHttpIntegrationTest` —— 同上 HTTP 工作流，外加用 `@TempDir`
  `projectPath` 的 `options.set`、`compile.start`、带 deadline 轮询
  `compile.getTask` 直至完成、断言 `SUCCESS`、断言 `compile.getLastResult` 与
  `compile.listEvents` 分页，然后验证已完成任务上的
  `compile.cancel`/`compile.clearEvents` 行为。zig 不可用时跳过。

### 4.4 插件编译就绪测试（始终可运行）

- `EditorAddonClientAnalysisTest` —— 从仓库读取 `gdcc_rpc_client.gd3`，以 `.gd3`
  虚拟路径原样写入模块 VFS（步骤 A0 后 API 直接收集 `.gd3` 源码），并以
  `includeLowering = true` 运行 `analyze(moduleId)`。断言
  `AnalysisResult.outcome == COMPLETED`、无 `ERROR` diagnostics、且
  `loweringStatus == SUCCEEDED`。这是证明 gdcc 无需 zig 或 Godot 即可解析、分析并
  lower 客户端库的廉价门禁。
- 同一测试还做源码级合同断言（`AnalysisResult` 不暴露协程信息，lowering 门禁本身
  无法证明"无协程"，因为含 `await` 的文件也能合法 lower）：
  1. `gdcc_rpc_client.gd3` 源码不含 `await` 标记（GDScript 中只有 `await` 会把函数
     变成协程）；
  2. 存在 `PendingRequest` inner class（`extends RefCounted` + `signal completed` +
     `_finish` 自发射方法）、`call_rpc(...) -> PendingRequest` 普通方法、以及按
     §3.3 签名标注类型的普通方法 `_on_request_completed`；
  3. 帧泵合同存在且未被绕过：源码出现 `_on_process_frame`、`_pump_next`、
     `_pending_finish`、`_inflight` 与 `process_frame`，且 `call_rpc` 函数体不含
     `http.request`、`_finish` 或 `_pump_next`（否则同步失败会在 `call_rpc` 栈上
     完成，踩中"先发射后 await"陷阱，而 ping 成功路径暴露不出它）。
  互操作链的运行时行为不属于本测试，由 B3 的 ping 冒烟证明（§7 风险 2）。

### 4.5 引擎自举测试（zig + Godot 门控）

- `EditorAddonBootstrapEngineTest` —— 端到端自举证明：
  1. 除非 zig 与 `GODOT_BIN` 均可用，否则中止
     （对 `ZigUtil.findZig()` / `GodotGdextensionTestRunner.findGodotBinaryFromEnv()`
     使用 `Assumptions.abort(...)`）。
  2. 在 `127.0.0.1:0` 以真实 `API` 启动 `JsonRpcServer`；读回实际端口。服务端在
     `finally` 块中停止，失败的测试绝不泄漏端口。
  3. 通过公开 `API`（`gdcc_rpc_client.gd3` 以 `.gd3` 虚拟路径写入模块 VFS +
     `compile`）原生编译客户端库，在临时构建目录产出 GDExtension 库。
  4. 把整个 `src/editor_addon` 项目复制到
     `tmp/test/editor_addon_bootstrap/<case>/`（递归清空再复制，沿用
     `GdScriptBenchmarkRunner` 先例），**排除任何 `.godot/` 目录**以避免带入过期的
     编辑器缓存。`.gd3` 源码不被引擎加载，**无需从副本中删除任何文件**：
     `GdccRpcClient` 只由 GDExtension 注册，不存在类名冲突。
  5. 把编译产物装进副本：`bin/` 产物 + 一个 `.gdextension` 文件 +
     `.godot/extension_list.cfg` 条目，通过 rpc 测试包中一个新的小测试 helper 完成。
     **不复用** `GodotGdextensionTestRunner`：它的 `prepareProject` 会改写
     `main.tscn`，且其安装方法是 private 并绑定 `test_project` / `root.gd` 合同。
  6. 写入 driver 脚本 `rpc_driver.gd`（继承 `SceneTree` 的解释型脚本，gdcc 特性限制
     不适用于它）与一个携带服务端端口和测试内编译用临时 `projectPath` 的 JSON 配置
     文件。
  7. 运行 `godot --headless --path <副本项目> -s rpc_driver.gd`，Java 侧进程超时
     严格大于 driver 的墙钟 deadline；超时用 `destroyForcibly()`。`-s` 模式不加载
     `main.tscn`/`root.gd`，因此现有 `TEST_STOP_SIGNAL` /
     `GodotGdextensionTestRunner.run()` 监督机制不适用；测试改为逐行扫描 stdout 中
     的 `RPC_TEST_RESULT: ` 前缀（Godot 的启动横幅与引擎噪音共享该流，不允许整缓冲
     匹配）。
  8. 从标记行解析 summary JSON，并断言每一步都是 `ok == true`。

- Driver 脚本流程（解释型；`await pending.completed` 有直接互操作锚点）：

  ```gdscript
  extends SceneTree

  func _initialize() -> void:
      _run()  # fire-and-forget 协程；绝不在 _init() 中做 RPC

  func _rpc(client: Object, method: String, params: Dictionary = {}) -> Dictionary:
      var pending: Object = client.call_rpc(method, params)
      return await pending.completed  # 同一同步段内建立 await（§3.3 时序合同）

  func _run() -> void:
      # …读取配置，然后：
      var client: GdccRpcClient = GdccRpcClient.new()
      client.host = "127.0.0.1"
      client.port = int(config.port)  # 测试服务端绑定临时端口
      root.add_child(client)  # 必须：_ready() 创建 HTTPRequest 子节点并 connect
      var ping: Dictionary = await _rpc(client, "server.ping", {})
      # …其余步骤：wrapper 返回 PendingRequest，await 其 completed…
      print("RPC_TEST_RESULT: " + JSON.stringify(summary))
      quit()
  ```

  步骤（wrapper 调用直接 `await ....completed`；裸方法名调用经 `_rpc`；每步把
  `{step, ok, detail}` 记入 summary）：
  1. 经 `FileAccess` 读取配置（port、projectPath）；
  2. `await _rpc(client, "server.ping", {})` → `ok == true`。本步同时是互操作链
     冒烟检查：`process_frame` → `_pump_next` → `http.request()` → 编译类 connect
     `HTTPRequest.request_completed` → 普通方法处理 → `PendingRequest`
     创建/返回/`completed` 自发射 → 解释型 `await` 挂起对象信号，任一环节失败
     都会在这里首先暴露（挂死优先排查帧泵 connect，而非 HTTP）；
  3. `var rpc: Dictionary = await client.create_module("demo", "Demo").completed`
     → `rpc["ok"] == true`；
  4. `rpc = await client.put_file("demo", "/src/main.gd",
     <极小的合法脚本源码>, "res://main.gd").completed` → `rpc["ok"] == true`；
  5. 完整 options 流程：`rpc = await _rpc(client, "options.get",
     {"moduleId": "demo"})` 拿到 `opts := rpc["result"]`，设置
     `opts["projectPath"] = config.project_path`，然后
     `rpc = await client.set_compile_options("demo", opts).completed`
     → `rpc["ok"] == true`。绝不手写残缺 options 对象，因为 `options.set` 替换
     整个快照；
  6. `rpc = await client.analyze("demo").completed` →
     `rpc["ok"] == true` 且 `rpc["result"]["outcome"] == "COMPLETED"`；
  7. `rpc = await client.start_compile("demo").completed` →
     `var task_id := int(rpc["result"]["taskId"])`；以**墙钟** deadline
     （`Time.get_ticks_msec()`，至少 60 秒，因为任务包含 zig 原生构建——现有
     10/60 帧的引擎测试预算不适用，且不传 `--quit-after`）反复
     `rpc = await client.get_compile_task(task_id).completed`，直到
     `rpc["result"]["state"]` 为 `SUCCEEDED`/`FAILED`/`CANCELED` 之一，然后断言
     `state == "SUCCEEDED"` 且 `rpc["result"]["result"]["outcome"] == "SUCCESS"`；
  8. `rpc = await client.get_last_compile_result("demo").completed`
     → `rpc["result"]["outcome"] == "SUCCESS"`；
  9. 负向检查：`rpc = await _rpc(client, "no.such.method", {})`
     → `rpc["ok"] == false` 且 `rpc["error"]["code"] == -32601`；
  10. 打印 `RPC_TEST_RESULT: ` + `JSON.stringify(summary)` 并 `quit()`。

- 类实例化：主路径是 `GdccRpcClient.new()`——`.gd3` 源码不被引擎加载，类名只由
  GDExtension 注册，`.new()` 解析无歧义。现有引擎测试证明 GDExtension 类会注册
  到 ClassDB，且可从解释型脚本和场景通过 `.new()` 实例化；
  `ClassDB.instantiate("GdccRpcClient")` 兜底路径**没有**任何现有锚点证明，必须在
  本测试内验证（或移除），不得直接假定可用。

### 4.6 文档与引用更新（实现的最后一步）

- 把本计划转换为事实源形式（或拆分为服务端/插件两份文档）。
- 在 `doc/module_impl/cli/cli_implementation.md` 的边界章节补充 `serve` 路由的一句
  说明（网络传输位于 `gd.script.gdcc.rpc`，不属于 CLI 范围）。
- 同步 `rpc_api_implementation.md`：按步骤 A0 的精确清单更新源码收集表述（§4.9、
  §7、§7.1 的 `.gd` → `.gd`/`.gd3`；CLI 宿主输入条款与 §6 不动），并在 §14 登记
  新适配层测试锚点。

---

## 5. 实施步骤与验收细则

按顺序执行；上一步验收未通过不得开始下一步。每个定向测试在 Linux 上通过
`script/run-gradle-targeted-tests.sh` 运行。完整的
`./gradlew clean build --no-daemon --info --console=plain` 只在 PR 前需要，步骤之间
不需要。

### 步骤 A0 — `.gd3` 源码收集根源修复

- 交付物：
  - `ModuleState.rememberSource()` 的扩展名过滤从 `.gd` 扩展为 `.gd` + `.gd3`
    （compile 与 analyze 共用 `freezeCompileRequest()`，单点修改），并同步
    `ModuleState` 注释、阶段文案与错误消息（`CompileTaskRunner` 的
    "Collecting .gd sources..."/"has no .gd source files to compile"、
    `AnalysisRunner` 的 "has no .gd source files to analyze" 改为同时提及
    `.gd`/`.gd3`）。
  - 行为规则：`.gd` 与 `.gd3` 同 basename 共存（如 `a.gd` 与 `a.gd3`）时两者都会
    被收集；logical path 天然不冲突（完整虚拟路径进入 `vfs/<moduleId>/...`），但
    默认类名推导（`FrontendClassNameContract.deriveDefaultTopLevelSourceName`：去
    最后一个扩展名后 PascalCase，两者都得到 `A`）会产生同名类，由 frontend 现有
    的重复类名诊断处理——API 层不新增专门规则（不得在 `rememberSource()` 加
    basename 去重），调用方应避免同 basename 混用或显式 `class_name` 区分。
  - 文案目标串（钉死，现有测试同步改期望值）：阶段消息
    `Collecting .gd/.gd3 sources from module VFS`；错误消息
    `has no .gd/.gd3 source files to compile` 与
    `has no .gd/.gd3 source files to analyze`。
  - CLI 不受影响：`GdccCommand` 对宿主输入的 `.gd` 校验是 CLI 自有边界，本次
    不改变（CLI 接受宿主 `.gd3` 列入 §8 后续工作）。
- 测试：在 `src/test/java/gd/script/gdcc/api/` 增补——`.gd3` 被 analyze 收集
  （`ApiAnalyzeTest` 风格）、`.gd3` 被 compile 收集（`ApiCompileDiagnosticsTest`
  风格，用 fake compiler）、`.txt` 仍被排除（现有两个测试在错误消息更新后保持
  绿色）。同 basename `.gd`+`.gd3` 的行为按以下具体合同钉死（防止实现在 API 层
  加 basename 特判）：
  - `putFile("/src/a.gd")` + `putFile("/src/a.gd3")`（均无 `class_name`）后，
    `sourcePaths()` 含两条，收集**不是** `SOURCE_COLLECTION_FAILED`；
  - analyze：`outcome == COMPLETED`，diagnostics 含 `sema.class_skeleton` 且
    message 含 `Duplicate top-level class source name 'A'`（`COMPLETED` 允许带
    ERROR）；
  - compile（fake compiler）：`outcome == FRONTEND_FAILED`，同样的诊断，且
    compiler 调用次数为 0；
  - 正例：两侧显式不同 `class_name` 时零 ERROR 诊断。
- 验收：`script/run-gradle-targeted-tests.sh --tests ApiAnalyzeTest,ApiCompileDiagnosticsTest`
  通过；`./gradlew classes --no-daemon --info --console=plain` 编译通过。
- 文档：精确同步 `rpc_api_implementation.md`：§4.9 的 "no `.gd` sources" →
  `.gd`/`.gd3`；§7 与 §7.1 的 "Collect `.gd` files" → 收集 `.gd`/`.gd3`；§7 的
  "Non-`.gd` VFS files ... are not compile sources" → 非 `.gd`/`.gd3`。§7 下一句
  CLI 宿主输入校验（reject non-`.gd` host inputs）本次**不改**；§6 Link Contract
  无收集扩展名表述，无需改动。

### 步骤 A1 — Codec

- 交付物：`gd/script/gdcc/rpc/RpcJsonCodec.java`，含 Gson 配置与
  Path/Instant/VfsEntrySnapshot 适配器；所有方法的 param record（可放在一个嵌套的
  `RpcParams.java` 持有者中）。
- 测试：`RpcJsonCodecTest`（§4.1）。
- 验收：`script/run-gradle-targeted-tests.sh --tests RpcJsonCodecTest` 通过；
  `./gradlew classes --no-daemon --info --console=plain` 编译通过。

### 步骤 A2 — Dispatcher

- 交付物：`JsonRpcDispatcher.java`（解析信封 → 路由 → 调用 → 编码）、
  `JsonRpcMethodRegistry.java`（§2.2 的方法表）、§2.4 的异常→错误映射。
- 测试：`JsonRpcDispatcherTest`（§4.1）。
- 验收：`script/run-gradle-targeted-tests.sh --tests JsonRpcDispatcherTest` 通过；
  全部 25 个方法已路由；§2.4 的每个错误码至少有一个钉死的测试。

### 步骤 A3 — HTTP 服务端与 `serve` 命令

- 交付物：`JsonRpcServer.java`、`JsonRpcHttpHandler.java`（含 §2.1 的计数有界
  body 读取）、`RpcServeCommand.java`、`Main.java` 路由、`module-info.java` 的
  `requires jdk.httpserver;`。
- 测试：`RpcServerHttpTest`、`RpcServeCommandTest`（仅参数默认值/校验），以及 §2.5
  扩展的 `Main` 路由测试（无参数 / `serve --help` / 未知首参）。
- 验收：`script/run-gradle-targeted-tests.sh --tests RpcServerHttpTest,RpcServeCommandTest,MainEntrypointTest`
  通过；`./gradlew classes --no-daemon --info --console=plain` 编译通过。

### 步骤 A4 — HTTP 上的 API 往返

- 交付物：无（仅装配）。
- 测试：`RpcApiRoundTripHttpTest`（§4.2）。
- 验收：`script/run-gradle-targeted-tests.sh --tests RpcApiRoundTripHttpTest` 通过。

### 步骤 A5 — HTTP 上的编译（zig 门控）

- 测试：`RpcCompileHttpIntegrationTest`（§4.3）。
- 验收：zig 存在时
  `script/run-gradle-targeted-tests.sh --tests RpcCompileHttpIntegrationTest` 通过；
  否则干净地跳过。

### 步骤 B1 — 客户端库与编译就绪门禁

- 交付物：`src/editor_addon/addons/gdcc/gdcc_rpc_client.gd3`（顶层 `@tool`、
  `class_name GdccRpcClient`、`extends Node`、§3.3 的挂起对象 API：
  `PendingRequest` inner class + `call_rpc` 返回挂起对象、Array 队列、帧泵、
  `_on_request_completed` 普通方法；全文件无协程）。
- 测试：`EditorAddonClientAnalysisTest`（§4.4）。
- 验收：`script/run-gradle-targeted-tests.sh --tests EditorAddonClientAnalysisTest`
  通过，且 `outcome == COMPLETED`、零 `ERROR` diagnostics、
  `loweringStatus == SUCCEEDED`，并通过 §4.4 的全部源码级合同断言（无 `await`、
  信号/方法签名齐备、帧泵合同存在且 `call_rpc` 未绕过）。如果分析或 lowering
  拒绝了客户端的某个构造，按 §3.2 简化客户端后重跑；不得削弱测试期望。

### 步骤 B2 — 插件骨架

- 交付物：`plugin.cfg`、`plugin.gd`、`gdcc_dock.gd`。
- 测试：v1 没有自动化测试，只有并入 `EditorAddonClientAnalysisTest` 的解析级检查
  （仅解析，不 lowering，因为编辑器专用 API 不是自举目标）。
- 验收：文件级验收——`plugin.cfg` 五字段齐备、`plugin.gd`/`gdcc_dock.gd` 结构与
  §3.4 一致。"启用插件 + dock ping + 编辑器空闲活性"的手工验收**不在本步骤**：
  `.gd3` 源码不被引擎加载，编辑器中的 `GdccRpcClient` 只能来自已安装的编译产物
  GDExtension，因此该验收并入 B3（§4.5 产出已安装扩展的副本项目后执行，见 B3
  验收），避免出现 B2 依赖 B3 产出的顺序循环。

### 步骤 B3 — 引擎自举

- 交付物：`EditorAddonBootstrapEngineTest`，外加 rpc 测试包中一个小测试 helper，
  负责项目复制（按 §4.5 排除 `.godot/`；`.gd3` 源码引擎不可见，无需删除任何
  文件）与扩展安装（不重构 `GdScriptBenchmarkRunner`，也不复用
  `GodotGdextensionTestRunner.prepareProject`；除非重复量很大才提取共享 helper）。
- 测试：`EditorAddonBootstrapEngineTest`（§4.5）。
- 验收：`GODOT_BIN` 与 zig 存在时
  `script/run-gradle-targeted-tests.sh --tests EditorAddonBootstrapEngineTest` 通过
  （全部十个 driver 步骤 `ok == true`，采用 §4.5 的墙钟 deadline，且
  `ClassDB.instantiate` 兜底路径已被显式验证或移除）；否则干净地跳过。
  另含手工验收（使用本步骤产出的已安装扩展的副本项目）：插件项目能在 Godot 4.5
  中打开，插件无错误启用，dock 能 ping 通运行中的服务端，且在"点击 ping/compile
  后不再移动鼠标"的条件下请求仍能完成（覆盖 §7 风险 7 的编辑器空闲活性）；结果
  记录在 PR 描述中。

### 步骤 C — 文档

- 交付物：§4.6 各项。
- 验收：文档描述已实现的行为；计划状态横幅已移除。

---

## 6. 稳定测试锚点（实现后）

- codec 与 wire format：`RpcJsonCodecTest`
- dispatch 与错误映射：`JsonRpcDispatcherTest`
- HTTP 传输：`RpcServerHttpTest`
- API 往返：`RpcApiRoundTripHttpTest`
- HTTP 上的编译：`RpcCompileHttpIntegrationTest`
- 客户端编译就绪：`EditorAddonClientAnalysisTest`
- 引擎自举：`EditorAddonBootstrapEngineTest`

---

## 7. 风险与缓解

1. **gdcc 无法编译客户端的某个构造**（例如未测过的引擎调用路由）。缓解：v1 客户端
   的公开 API 不含任何协程（§3.2 的跨边界协程限制），内部异步也只用普通方法 +
   signal connect + Array 队列，全程避开无覆盖的构造；且步骤 B1 门禁（含源码级
   无 `await` 断言，§4.4）先于任何引擎工作落地，使失败以低成本暴露并迫使客户端
   简化，而不是削弱测试（B1 门禁含源码级无 `await` 与帧泵合同断言，§4.4）。此外 `HTTPRequest.request()` 的同步失败路径（返回非
   `OK` 时不发射 `request_completed`）与"同步栈完成早于 await 连接"的时序陷阱，
   已按 §3.3 的信号时序合同覆盖：`call_rpc` 只入队、不发送、不发射；发送由后续
   `process_frame` → `_pump_next()` 驱动；`request()` 同步失败压入
   `_pending_finish`，下一帧由帧回调冲刷 `_finish`；成功响应在
   `_on_request_completed` 中直接 `_finish`（不经 `_pending_finish`）。
2. **互操作链缺少直接锚点**：以下四个环节的基元能力各有锚点，但完整链路没有
   端到端 fixture：① 编译类 connect `HTTPRequest.request_completed` 到自身普通
   方法；② gdcc 编译的 inner `RefCounted` 类（`PendingRequest`）声明并经
   `_finish` 自发射 `completed` 信号；③ 解释型 `await pending.completed`
   （直接锚点 `interop_state_completed_signal.gd`，但用户自定义 inner class 的
   完整组合无 fixture）；④ `_ready()` 中 `tree.process_frame` 到编译类普通方法的**持久** connect（帧泵的活性依赖——若该回调不触发，队列
   永不发送，ping 会挂死而非报错）。缓解：driver 第 2 步（ping）即该链路的冒烟
   检查，挂死或失败都会在第一步网络交互处暴露；若失败，回退为在 test_suite 中
   新增一个聚焦 fixture 单独定位环节（特别是环节 ②④），再继续 B3。
3. **编译出的类无法通过 `class_name` 被解释型 driver 引用**。缓解：源码使用 `.gd3`
   扩展名，引擎不加载它，`GdccRpcClient` 只由 GDExtension 注册，不存在源脚本与
   扩展注册的类名冲突（§3.1、§4.5）；现有引擎测试证明 ClassDB 注册与 `.new()`
   实例化；`ClassDB.instantiate("GdccRpcClient")` 兜底路径没有现有锚点，在 B3 内
   验证而非假定可用。
4. **`GodotGdextensionTestRunner` 与 `test_project` 耦合**（改写 `main.tscn`）。
   缓解：自举测试复制 `src/editor_addon` 并使用 `-s` 脚本模式与自有的小型安装
   helper，不复用 `prepareProject`。
5. **JDK 模块中的 HTTP 服务端**在 keep-alive 或错误状态码上可能存在跨平台差异。
   缓解：§4.2 的 HTTP 合同测试精确钉死 GDScript 客户端依赖的状态码；客户端把任何
   非 200 视为携带状态码的传输失败。
6. **主机路径暴露**在非 loopback 绑定普及后会扩大。缓解：保持 loopback 默认，绑定
   非 loopback 时记录日志，并按 `rpc_api_implementation.md` §13 的口径把脱敏列为
   后续工作。
7. **编辑器空闲时帧泵/HTTP 停转**：发送与同步失败完成依赖
   `SceneTree.process_frame`，且 `HTTPRequest` 默认在主循环中推进；Godot 编辑器
   默认开启 `OS.low_processor_usage_mode`，无输入/重绘时主循环跳过迭代，
   `process_frame` 不发射、HTTP 不前进。B3 的 `--headless` 全速主循环测不到该
   场景，headless ping 碰巧成功也不能证明编辑器空闲活性。
   缓解（任选或组合，优先不依赖无锚点 API）：
   - `_ready()` 中设置 `http.use_threads = true`（元数据存在；完成信号的投递仍
     可能等下一帧）；
   - 忙时（`_queue`/`_inflight`/`_pending_finish` 非空）由解释型 `plugin.gd`/
     `gdcc_dock.gd`（非自举目标，可用编辑器 API）周期 `queue_redraw()` 或在忙时
     暂时关闭 `OS.low_processor_usage_mode`、空闲时恢复。
   B3 手工验收必须包含"点击 ping/compile 后不再移动鼠标，请求仍能完成"一条。

---

## 8. 非目标与后续工作

- JSON-RPC 批量、位置参数、经 WebSocket/SSE 的流式进度。
- 认证令牌、TLS、远程（非 loopback）部署。
- 服务端拥有的工作区分配与 project-path 锁（见 `rpc_api_implementation.md` §13）。
- 产物下载端点；编辑器插件只读取 `LOCAL` link 元数据。
- 编辑器 UI 打磨（设置持久化、dock 中的多 module、脚本编辑器中的诊断标注）。
- `.gd3` 生态扩展：CLI 接受宿主 `.gd3` 输入（`GdccCommand` 校验、默认输出名推导
  与 CLI 文档）；addon 识别项目中的 `.gd3` 文件并驱动"编译 → 安装 GDExtension →
  重载"的自动化工作流（§3.1 的 `.gd3` 定位即为此预留）。
- gdcc 编译消费者路径的端到端 fixture（dynamic `completed` 路由 + 编译
  `PendingRequest` 对象的组合，§3.3 末尾）：addon 后续编译脚本依赖该形态，应在
  首次出现编译消费者之前补充 test_suite 锚点。
