# GDCC CLI 实现说明

> 本文档作为本地 `gdcc` 命令行适配层的长期事实源。CLI 是
> `gd.script.gdcc.api.API` 之上的薄适配层：它从宿主机 `.gd` 文件准备一个
> API module，配置 compile options 与顶层 canonical name 映射，启动一个
> compile task，drain task events，并把最终结果渲染给终端用户。本文档替代旧的
> `cli_implementation_plan.md`，不再保留实施步骤、进度记录或验收流水账。

## 文档状态

- 状态：事实源维护中
- 更新时间：2026-09-13
- 适用范围：
  - `src/main/java/gd/script/gdcc/Main.java`
  - `src/main/java/gd/script/gdcc/cli/**`
  - `src/main/java/gd/script/gdcc/util/ConsoleOutputUtil.java`
  - `src/main/java/gd/script/gdcc/util/GdccVersion.java`
  - `build.gradle.kts`
  - `src/test/java/gd/script/gdcc/cli/**`
  - CLI 行为依赖的聚焦 API / frontend / backend 测试
- 直接事实源：
  - `doc/module_impl/api/rpc_api_implementation.md`
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/runtime_name_mapping_implementation.md`
  - `doc/module_impl/frontend/gdcc_facing_class_name_contract.md`
  - `src/main/java/gd/script/gdcc/api/API.java`
  - `src/main/java/gd/script/gdcc/api/CompileOptions.java`
  - `src/main/java/gd/script/gdcc/api/CompileResult.java`
  - `src/main/java/gd/script/gdcc/api/CompileTaskSnapshot.java`
  - `src/main/java/gd/script/gdcc/api/CompileTaskEvent.java`
  - `src/main/java/gd/script/gdcc/frontend/FrontendClassNameContract.java`
  - `src/main/java/gd/script/gdcc/frontend/sema/FrontendClassSkeletonBuilder.java`
  - `src/main/java/gd/script/gdcc/util/GdccVersion.java`
  - `build.gradle.kts`
- 关联事实源：
  - `src/main/java/gd/script/gdcc/backend/c/build/CProjectBuilder.java`
  - `src/test/java/gd/script/gdcc/api/**`
  - `src/test/java/gd/script/gdcc/backend/c/build/**`
- 明确非目标：
  - 不实现网络或 RPC transport。
  - 不在 CLI 代码中实现第二套编译管线。
  - 除 `--prefix` 展开所需的窄范围 source-name discovery preflight 外，不让 CLI 直接 parse / lower / codegen。
  - 不新增一套镜像 `CompileOptions` 的配置抽象。
  - 不在 build 后移动或重命名 backend 产物。

---

## 1. 职责与分层

`Main` 只负责入口路由：首参为 `serve` 时委托 `gd.script.gdcc.rpc.RpcServeCommand`（网络传输属于 RPC
适配层，见 `doc/module_impl/api/json_rpc_service_implementation.md`，不属于 CLI 范围），其余情况委托
`GdccCommand.execute(args)`。命令行解析、host input 准备、API 调用和终端渲染都收敛在
`gd.script.gdcc.cli` 包内；公共 API、frontend、backend 和根包不承载 CLI helper。

CLI 的合法职责是：

- 解析 picocli 命令行参数。
- 处理 `--help` / `--version` 这类 picocli early-exit 输出。
- 在 API state mutation 前完成 host input 边界校验。
- 把 host `.gd` 文件读成 UTF-8 文本，并写入一个 API module VFS。
- 构造 API `CompileOptions`。
- 组装顶层 source-name 到 canonical-name 的 mapping。
- 启动并等待一个 API compile task。
- 按 verbosity level 渲染 diagnostics、events、artifacts、output links 与 build log。

CLI 不拥有 frontend / LIR / backend 语义。source collection、semantic diagnostics、lowering、C codegen、artifact publication
继续由 API pipeline 及其下游模块负责。

---

## 2. 命令面

当前命令形态：

```powershell
gdcc [options] <files...>
```

稳定选项：

- `files`：非空宿主机 `.gd` 源文件列表。
- `-o` / `--output <output>`：可选 output target path。
- `--prefix <prefix>`：为顶层 source name 生成 canonical prefix。
- `--class-map Source=Canonical`：显式顶层 source-to-canonical mapping，可重复传入。
- `--gde <version>`：Godot GDExtension API 版本。当前唯一支持值是 `4.5.1`。
- `--opt` / `--optimize <level>`：C backend 编译优化等级。支持 `debug`、`release`，默认 `debug`。
- `--target <platform>`：C backend 目标平台。默认 native host platform。
- `--project <project.godot>`：Godot 项目文件。仅当所有输入脚本的最近 `project.godot` 都是该文件时，成功编译后生成
  `.gdextension` metadata 文件。
- `-v` / `--verbose`：可重复传入的 verbosity flag。`-v` 为 level 1，`-vv` 为 level 2。
- `-V` / `--version`：输出编译器构建版本信息并退出，不创建 API module 或 compile task。

示例：

```powershell
gdcc src/player.gd src/enemy.gd
gdcc -o build/demo src/player.gd src/enemy.gd
gdcc -o build/demo --prefix Game_ src/player.gd src/enemy.gd
gdcc -o build/demo --class-map Player=RuntimePlayer --class-map Enemy=RuntimeEnemy src/player.gd src/enemy.gd
gdcc -o build/demo --gde 4.5.1 -v src/player.gd src/enemy.gd
gdcc -o build/demo --optimize release --target linux-x86-64 src/player.gd
gdcc --project game/project.godot -o build/demo game/src/player.gd
gdcc --version
```

picocli 解析阶段发现的 option / parameter 错误属于 usage error，返回 exit code `2`，且不进入 command body。

`--help` 和 `--version` 由 picocli 标准 help/version option 短路处理，返回 exit code `0`，且不要求 `files` 参数。

### 2.1 构建版本资源

Gradle 在 `processResources` 前生成临时 classpath resource：

```text
gdcc-version.properties
```

该资源包含：

- `version`：当前 Gradle project version。
- `branch`：构建时 `git rev-parse --abbrev-ref HEAD` 的结果。
- `commit`：构建时 `git rev-parse --short HEAD` 的结果。

资源写入 `build/generated/resources/gdccVersion`，并通过 main source set 进入运行时 classpath。运行时代码不读取 jar manifest，也不在 CLI 内执行 git 命令。

`GdccVersion` 是读取该资源的工具类。它首次调用时加载 properties，随后缓存同一个 `Info` 实例；如果资源不存在，显示值降级为 `unknown`，以便 IDE/classpath 运行仍能完成 `--version` 输出。

### 2.2 Jar Distribution

Gradle 的 `jar` task 生成可通过 `java -jar` 启动的主 jar。manifest 固定包含：

```text
Main-Class: gd.script.gdcc.Main
Class-Path: lib/<runtime-dependency>.jar ...
```

`Class-Path` 条目只使用相对路径和 `/` 分隔符，指向主 jar 同级目录下的 `lib` 文件夹。运行时依赖来自 Gradle `runtimeClasspath`；`compileOnly` 依赖不进入 `lib`，也不写入 manifest。

`syncRuntimeLibs` task 将 runtime dependencies 同步到：

```text
build/libs/lib/
```

`jar` task 依赖 `syncRuntimeLibs`，因此直接运行 `jar` 也会刷新同级 `lib` 文件夹。

`buildZigLauncher` task 使用 Zig 构建默认 native launcher 矩阵：

```text
windows-x86_64/gdcc.exe
linux-x86_64/gdcc
linux-aarch64/gdcc
```

`packageDistribution` task 是聚合任务，生成三个平台 zip distribution：

```text
build/distributions/gdcc-<version>-windows-x86_64.zip
build/distributions/gdcc-<version>-linux-x86_64.zip
build/distributions/gdcc-<version>-linux-aarch64.zip
```

每个 zip 顶层只包含对应平台的 launcher、主 jar 与 `lib/` 文件夹，不包含其他平台 launcher。launcher 与主 jar 同级，最终仍通过 classpath-style `java -jar gdcc-<version>.jar` 启动；module-path 启动不依赖 manifest `Class-Path`，不属于当前分发合同。

launcher 启动时从自身可执行文件同级目录扫描 `gdcc-*.*.*.jar`，不依赖构建时固定 jar 文件名。在选择普通 jar 前，
launcher 会先扫描 `gdcc-*.*.*-update.jar`；若恰好存在一个 update jar，则删除同目录下所有普通 `gdcc-*.*.*.jar`，
再把该 update jar 重命名为去掉 `-update` 的普通 jar 名称，并启动重命名后的 jar。若存在多个 update jar，或没有 update jar
但匹配到多个普通 jar，launcher 以错误退出并要求用户删除多余文件。launcher 传给 JVM 的固定参数包含：

```text
--enable-native-access=ALL-UNNAMED
-Dgdparser.gdscript.resourceDir=<launcher-dir>
```

launcher 使用自身可执行文件同级目录作为本地工具链优先搜索根，不使用用户当前工作目录作为本地搜索根。启动 Java 前会优先检查同级的 `java`、`java/bin/java`、`jre/bin/java`、`jdk/bin/java`，随后才检查 Java 环境变量、`PATH` 与常见系统位置，并且只接受 `java --version` 报告为 25 或更高版本的运行时。launcher 还会优先检查同级的 `zig`、`zig/zig`、`zig/bin/zig`；若找到 Zig 可执行文件，会在传给 JVM 的环境中设置 `ZIG_HOME=<zig-executable-dir>`，使 Java 侧 `ZigUtil` 可以优先复用分发包旁边的 Zig。若找不到 Java 25+ 或 Zig，launcher 都会以错误退出。launcher 错误文本支持英文与中文，会根据 `LC_ALL`、`LC_MESSAGES`、`LANG`、`LANGUAGE` 中的首个受支持语言环境选择翻译；Windows 在环境变量未命中时会使用系统 UI 语言偏好，再回退到用户 locale。不支持的语言环境回退英文。Windows 控制台错误输出使用 UTF-16 console API，避免非 UTF-8 代码页下中文错误文本乱码；重定向输出仍保留 UTF-8 字节。

Windows Java 常见系统位置包括 `%ProgramW6432%`、`%ProgramFiles%`、`%ProgramFiles(x86)%` 下的常见 JDK vendor 目录，以及 `%USERPROFILE%/.jdks` 和 `%LOCALAPPDATA%/Programs/Java` 下的一层 JDK 目录。

---

## 3. 输入与 Module 准备

每个 input 必须满足：

- path 文本非 blank。
- 宿主机文件存在。
- 不是目录。
- 是 regular file。
- 文件名以 `.gd` 结尾。

违反以上条件时，CLI 在创建 API module 和 compile task 前返回 usage error。

输入文件按 UTF-8 读取。每个文件写入同一个 API module VFS，布局为：

```text
/src/%04d/<host-file-name>
```

其中 `%04d` 是输入参数顺序的补零 index。该 index 让普通输入规模下的 lexical order 与参数顺序一致，也避免不同目录下同名
basename 互相覆盖。`displayPath` 保留用户传入的原始 host path 文本，因此 diagnostics 与 `CompileResult.sourcePaths()` 面向终端用户显示真实输入路径，而不是 synthetic VFS path。

重复 host file argument 当前被视为用户有意创建重复 source entry。来自不同目录的两个 `main.gd` 也会作为独立 VFS files
写入；如果它们都依赖默认 `Main` source name，frontend diagnostic 是权威结果，CLI 不隐藏或改写该冲突。

---

## 4. Output Target 合同

### 4.1 默认 output target

省略 `-o` / `--output` 时，CLI 从已通过输入校验的 host file names 推导默认 output target：

1. 对每个输入文件取 host file name。
2. 去掉末尾 `.gd` 后缀。
3. 按命令行输入顺序用 `_` 连接。
4. 将连接后的字符串当作无父路径 output target。

例如：

```powershell
gdcc src/player.gd src/enemy.gd
```

等价于：

```powershell
gdcc -o player_enemy src/player.gd src/enemy.gd
```

来自不同目录的 `one/main.gd` 与 `two/main.gd` 会推导为 `main_main`；CLI 不做去重或 sanitization。

### 4.2 显式 output target

显式 `-o` / `--output` 使用一个 path 参数：

```text
-o <output-dir>/<module-name>
```

映射规则：

- 最后一个 path segment 是 API `moduleId` 和 `moduleName`。
- 完整 path 是 `CompileOptions.projectPath`，也是该 CLI target 的 concrete host build directory。
- 无父路径 output target 使用当前工作目录下的同名 build directory。
- output target 是宿主机文件系统路径，不是 API VFS path。

示例：

```text
-o build/demo
```

含义：

- API module id/name：`demo`
- 宿主机构建目录：`build/demo`

显式 blank output path、root-only path、以及被 `API.createModule(...)` 拒绝的 module name 都以 usage error 失败。

### 4.3 Artifact 命名与报告

CLI 不改变 `CProjectBuilder` 的 native artifact 命名。当前 native artifact 由 backend 按如下规则命名：

```text
<module>_<optimization>_<architecture>.<platform-extension>
```

例如 native Windows debug x86_64 下：

```text
build/demo/demo_debug_x86_64.dll
```

成功输出时，CLI 从 `CompileResult.artifacts()` 报告 artifact path，不自行猜测、不移动、不重命名 backend 产物。

### 4.4 `.gdextension` metadata 生成

CLI 可以在成功编译后生成 Godot 可读取的 `.gdextension` metadata 文件。该行为由 `--project <project.godot>`
启用，但是否写文件取决于输入脚本是否都属于同一个 Godot 项目：

- `--project` 必须指向真实存在的 `project.godot` regular file，否则在创建 API module 前返回 usage error；
  不存在与存在但不是 regular file 的路径分别使用明确错误信息。
- 对每个输入 `.gd`，CLI 从该文件的父目录向上查找最近的 `project.godot`。
- 当且仅当所有输入文件找到的最近 `project.godot` 都等于 `--project` 传入的文件时，CLI 在成功编译后写 metadata。
- 省略 `--project`、某个输入不在任何 Godot 项目下、或某个输入属于另一个更近的 Godot 项目时，CLI 不生成 metadata。
- metadata 文件位于 output target directory 内，文件名等于该目录名加 `.gdextension`。例如 `-o build/demo`
  会生成 `build/demo/demo.gdextension`。
- metadata 内的 library path 使用相对 `.gdextension` 文件所在目录的路径。当前 backend artifact 写在同一个 output
  directory 下，因此通常是 artifact 文件名。
- `web-wasm32` 产物的 `.wasm` 文件也可以作为 metadata `[libraries]` entry 的 library path。

生成内容复用 C backend 的 `GdextensionMetadataFile` 规则，与 Godot runtime 测试项目使用的 metadata 形状一致：

```text
[configuration]

entry_symbol = "gdextension_entry"
compatibility_minimum = "4.5"

[libraries]
<platform>.<debug|release> = "<relative-library-path>"
```

CLI 只生成 `.gdextension` 文件，不修改 Godot 的 `.godot/extension_list.cfg` 或编辑器缓存。用户仍可在 Godot 项目中按
Godot 的 extension loading 流程启用该文件。

---

## 5. CompileOptions 合同

CLI 直接构造 API `CompileOptions`，不引入平行配置对象：

```java
new CompileOptions(
        parsedGodotVersion,
        outputTarget.projectPath(),
        parsedOptimizationLevel,
        parsedTargetPlatformOrNativeHost,
        false,
        CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT
)
```

当前 `--gde` 规则：

- 接受 `4.5.1`，映射到 `GodotVersion.V451`。
- 拒绝所有其他值，并在错误信息中列出支持版本。
- 不允许静默 fallback 到 `4.5.1`。
- unsupported `--gde` 在 API state mutation 和 compile task 创建前返回 usage error。

`--opt` / `--optimize` 规则：

- 接受 `debug` 与 `release`，大小写不敏感。
- `-` 与 `_` 在输入中等价；例如 `release` 映射到 `COptimizationLevel.RELEASE`。
- 省略时使用 `COptimizationLevel.DEBUG`。
- unsupported `--opt` 在 API state mutation 和 compile task 创建前返回 usage error。

`--target` 规则：

- 接受 `TargetPlatform` 当前枚举值的 CLI 形式，大小写不敏感，`-` 与 `_` 在输入中等价。
- 支持值为 `windows-x86-64`、`windows-aarch64`、`linux-x86-64`、`linux-aarch64`、`linux-riscv64`、
  `android-x86-64`、`android-aarch64`、`web-wasm32`。
- 省略时使用 `TargetPlatform.getNativePlatform()`。
- `--target` 只设置 backend output platform，不改变 `-o` / `--output` 指向的 host build directory。
- unsupported `--target` 在 API state mutation 和 compile task 创建前返回 usage error。

CLI 当前没有 strict flag。后续除非明确扩展 CLI surface，否则不应因为 `CompileOptions` 有字段就添加未使用 flags。

---

## 6. 顶层 Canonical Name Mapping

### 6.1 `--class-map`

语法：

```text
--class-map Source=Canonical
```

规则：

- 选项可重复传入。
- `Source` 和 `Canonical` 是顶层类名，不是 VFS path。
- 每个显式 entry 必须只有一个 `=`。
- 重复显式 `Source` key 在 CLI 边界 early fail。
- blank、reserved `__sub__`、canonical collision 等名称合法性继续由 `FrontendClassNameContract` / API boundary 统一校验。

### 6.2 `--prefix`

语法：

```text
--prefix PrefixText
```

`--prefix` 作用于每个可发现的顶层 source name。顶层 source name 与 frontend 合同一致：

- 若 source 有显式顶层 `class_name` statement，使用该 `class_name`。
- 否则使用文件名推导结果。

对每个这类 source name，CLI 生成 mapping：

```text
<sourceName> -> <prefix><sourceName>
```

默认 source-name 推导复用 `FrontendClassNameContract.deriveDefaultTopLevelSourceName(Path)`，与
`FrontendClassSkeletonBuilder` 保持同一规则真源。CLI 不复制 frontend 文件名规则。

发现显式 `class_name` 是窄范围 option-expansion preflight：CLI 使用 `GdScriptParserService` 解析 source 并读取 top-level
`ClassNameStatement`；若缺失有效 `class_name`，再回退到默认文件名推导。该 preflight 不替代 API compile pipeline 的 parse /
lowering / diagnostics。如果 preflight parsing 本身产生错误，CLI 跳过该文件的 prefix-generated mapping，让实际 API compile
产生 canonical diagnostics。

### 6.3 Merge 顺序

mapping merge 规则：

1. 先添加 `--prefix` 生成的 mappings。
2. 再应用显式 `--class-map` entries。
3. 显式 entry 覆盖同 source name 的 generated entry。
4. 最终 merged map 通过 `API.setTopLevelCanonicalNameMap(...)` 传入。

当前实现使用 insertion-ordered map，以保持 verbose output 与测试 snapshot 稳定。generated entry 可以来自显式
`class_name` 或文件名推导；例如 `--prefix Game_ --class-map Player=RuntimePlayer` 会让显式 `class_name Player` 的最终
mapping 从 generated `Player -> Game_Player` 覆盖为 `Player -> RuntimePlayer`。

---

## 7. Compile Task 生命周期与 Event Draining

CLI 通过 API task surface 运行编译：

1. `api.compile(moduleId)` 创建 task。
2. 循环调用 `api.getCompileTask(taskId)`，直到 snapshot completed。
3. 每轮 poll 之间 sleep，避免 busy spin。
4. completed snapshot 必须携带 `CompileResult`。
5. 最终 exit code 由 `CompileResult` outcome 映射。

等待期间若线程被 interrupt，CLI 必须：

- 恢复 interrupt flag。
- 调用 `api.cancelCompileTask(taskId)`。
- 返回 exit code `130`。

verbosity level 大于 `0` 时，CLI 通过 indexed paging drain retained events：

```java
api.listCompileTaskEvents(taskId, nextEventIndex, API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE)
```

CLI 保存下一个未读 event index，避免重复打印。task completed 后还会 final drain 一次剩余 pages。事件读取不依赖全量
`listCompileTaskEvents(taskId)`。

---

## 8. 终端输出合同

### 8.1 Diagnostics

CLI 渲染 `CompileResult.diagnostics()` 中的 frontend diagnostics。诊断 path 使用 API result 中的 source/display path；格式为：

```text
<severity> <category> <source-path>[:line:column]: <message>
```

缺失 source path 时显示 `<unknown>`。

### 8.2 默认输出

成功时 stdout 输出：

```text
Compiled module <moduleId>
Artifact: <artifact-path>
Metadata: <metadata-path-if-generated>
```

失败时 stderr 输出：

```text
Compile failed: <outcome>
<failure-message-if-present>
```

`BUILD_FAILED` 默认在 stderr 打印非空 build log。frontend failure 默认不打印 build log，除非 verbosity level 至少为 `2`。

### 8.3 Verbose 输出

`-v` / verbosity level `1`：

- stderr 打印 task state / stage / stage message。
- stderr 打印 retained compile task events。
- stdout 打印 output VFS links。

`-vv` / verbosity level `2+`：

- stdout 打印 compile options。
- stdout 打印 source display paths。
- stdout 打印 top-level canonical map。
- stdout 打印 generated file paths。
- 成功时也打印非空 build log。

verbosity levels 只增加渲染信息，不改变编译行为。

### 8.4 Version 输出

`--version` stdout 输出一行：

```text
gdcc <version> (<branch> <commit>)
```

其中三个字段来自 `GdccVersion` 缓存的 build metadata。`--version` 不输出 diagnostics、task events、artifacts、output links 或 build log，stderr 为空。

---

## 9. Logger 与控制台编码

CLI 执行期间临时启用 `GdccLogger` plain output mode，并在 `finally` 中恢复进入 CLI 前的 logger 状态。plain output 不包含时间、等级、logger name 或 ANSI 颜色。

CLI stdout / stderr writer 使用各自 `PrintStream.charset()`。输出前按 code point 检查可编码性；目标编码不可表示的字符转义为：

- `\uXXXX`
- `\UXXXXXXXX`

该策略避免在 ASCII / GBK 等受限控制台上静默丢字或替换成 `?`。

---

## 10. Exit Codes 与错误边界

稳定 exit-code surface：

- `0`：compile succeeded，或 `--help` / `--version` early-exit succeeded。
- `1`：compile task completed，但 `CompileResult.outcome()` 不是 `SUCCESS`。
- `2`：compile task 创建前的命令行用法或配置错误。
- `130`：等待 compile task 时被用户中断，CLI 已尝试 cancel task。

错误边界：

- picocli option / parameter 错误返回 `2`。
- picocli `--help` / `--version` 返回 `0`，不执行 CLI command body。
- host input 校验、unsupported `--gde`、显式 output target 错误、duplicate `--class-map` key 以及 API module creation failure 返回 `2`。
- frontend diagnostics、frontend failure、native build failure 都是 compile result，不是 CLI usage error，返回 `1`。
- class-name 合法性和 canonical mapping 深层语义继续由 `FrontendClassNameContract` / API / frontend diagnostics 负责。
- API exceptions 在 CLI 边界渲染为 user-facing error，不包一层平行异常体系。

---

## 11. 稳定测试锚点

CLI 聚焦测试：

- `GdccCommandOptionTest`
  - picocli annotations
  - help text
  - version text
  - optional `-o/--output` 解析
  - optional `--project` 解析
  - missing input parameter
  - repeatable `--class-map`
  - `-vv` verbosity count
  - valid invocation smoke
- `GdccCommandInputTest`
  - host file validation
  - UTF-8 source loading
  - VFS layout 与 display path
  - 默认 output target 推导
  - 显式 output target 与 `CompileOptions.projectPath`
  - `--project` 触发的 `.gdextension` metadata 生成条件
  - explicit blank / root-only output failure
  - shared parent 下不同 output target 的 generated file 隔离
- `GdccCommandMappingTest`
  - `--prefix` generated mapping
  - `--prefix` 应用于显式 `class_name` 与 filename-derived 顶层 source name
  - 显式 `--class-map` 覆盖 generated mapping
  - duplicate explicit key
  - reserved canonical name failure
- `GdccCommandTaskTest`
  - success / failure rendering
  - artifact output
  - build log rule
  - retained event draining
  - output links
  - interrupt cancellation

跨模块锚点：

- `FrontendClassNameContractTest`：CLI 与 frontend 共享默认 top-level source-name 推导。
- `GdccVersionTest`：build metadata resource 读取、缓存和显示格式。
- `ApiCompilePipelineTest`：API compile task pipeline 行为。
- `ApiCompileArtifactLinkTest`、`ApiRecompileArtifactRefreshTest`：output publication 与 stale artifact refresh。
- backend C project builder tests：generated files、shared include、artifact provenance。

推荐聚焦命令：

```powershell
rtk powershell -ExecutionPolicy Bypass -File script/run-gradle-targeted-tests.ps1 -Tests GdccCommandOptionTest,GdccCommandInputTest,GdccCommandMappingTest,GdccCommandTaskTest,MainEntrypointTest,GdccVersionTest,FrontendClassNameContractTest
```

---

## 12. 已知后续工作

- Artifact naming 仍由 backend/API output-name contract 控制，当前不完全 clang-compatible。若要改变 artifact basename，应修改 backend/API 契约，而不是在 CLI build 后移动或重命名文件。
- CLI 默认 `projectPath` 与显式完整 output path 已避免普通 CLI 调用共享 host build directory；直接 API 调用者若复用同一个 physical
  `projectPath`，仍需要未来的 project-path lock 或 server-owned workspace policy 才能获得跨 module host filesystem 隔离。
- `--prefix` source-name discovery preflight 会重复 API parse pass。当前这是 option expansion 的窄范围成本，不是编译管线替代品；若后续性能或语义变脆，应新增明确的 API / frontend preflight surface。
- 可执行 wrapper scripts 暂未提供。当前稳定分发面是主 jar、同级 `lib/` runtime dependencies，以及包含两者的 zip distribution。
