# Compiler RPC API Implementation

> This document is the long-term fact source for the RPC-friendly compiler control API in
> `gd.script.gdcc.api`. It describes the in-process facade, state model, virtual filesystem,
> compile task lifecycle, and output publication contract used by external RPC adapters.

## Document Status

- Status: fact source maintained
- Updated: 2026-09-06
- Scope:
  - `src/main/java/gd/script/gdcc/api/**`
  - `src/test/java/gd/script/gdcc/api/**`
  - RPC adapters that call `gd.script.gdcc.api.API`
- Direct fact sources:
  - `doc/module_impl/common_rules.md`
  - `doc/module_impl/frontend/frontend_rules.md`
  - `doc/module_impl/frontend/runtime_name_mapping_implementation.md`
  - `doc/module_impl/frontend/gdcc_facing_class_name_contract.md`
  - `doc/gdcc_low_ir.md`
  - `doc/gdcc_type_system.md`
  - `doc/gdcc_c_backend.md`
  - `src/main/java/gd/script/gdcc/api/API.java`
  - `src/main/java/gd/script/gdcc/api/ModuleState.java`
  - `src/main/java/gd/script/gdcc/api/CompileOptions.java`
  - `src/main/java/gd/script/gdcc/api/CompileResult.java`
  - `src/main/java/gd/script/gdcc/api/CompileTaskSnapshot.java`
  - `src/main/java/gd/script/gdcc/api/AnalyzeOptions.java`
  - `src/main/java/gd/script/gdcc/api/AnalysisResult.java`
  - `src/main/java/gd/script/gdcc/api/AnalysisRunner.java`
  - `src/main/java/gd/script/gdcc/api/DiagnosticSourcePathRemapper.java`
  - `src/main/java/gd/script/gdcc/api/VfsEntrySnapshot.java`
  - `src/main/java/gd/script/gdcc/api/task/CompileTaskRunner.java`
- Explicit non-goals:
  - This API is **not** Godot's GDScript `@rpc` / `Node.rpc(...)` / `Node.rpc_config(...)` feature.
  - This API does not implement a network protocol by itself. HTTP, JSON-RPC, gRPC, authorization,
    authentication, transport serialization, and download endpoints belong to the adapter layer.
  - This API does not change frontend, lowering, LIR, or backend semantics. It orchestrates existing
    compiler phases.
  - The module virtual filesystem is not a general operating-system filesystem abstraction. It only
    models compiler inputs, output links, and caller-facing metadata for remote compile workflows.

---

## 1. Purpose

`API` is an in-memory facade for controlling the compiler from a remote process through an adapter.
It owns module state, virtual files, compile configuration, compile task tracking, and publication of
local build outputs back into module VFS as links. It also exposes a synchronous analyze-only
entrypoint so editor-plugin integrations can collect frontend warnings and errors, and optionally
verify lowering readiness, without running a full compile.

The facade deliberately remains transport-neutral:

- public methods use Java records, enums, strings, lists, maps, and paths rather than HTTP or JSON
  framework types
- API exceptions are project exceptions under `gd.script.gdcc.exception`
- adapters are responsible for mapping method calls, records, exceptions, and artifacts to their
  chosen protocol

The intended layering is:

1. RPC/network adapter validates user/session permissions and decodes protocol payloads.
2. Adapter calls `gd.script.gdcc.api.API`.
3. `API` serializes same-module state changes, freezes compile inputs, and runs compiler phases.
4. Adapter polls task/result surfaces and exposes diagnostics, logs, and artifact handles to clients.

---

## 2. Terminology

- **Compiler RPC API**
  - The Java facade documented here. It remotely controls gdcc compilation.
- **Godot RPC**
  - Godot's multiplayer scripting feature around `@rpc`, `rpc`, `rpc_id`, and `rpc_config`.
  - It is a separate language/runtime feature and is not implemented by this API document.
- **Module**
  - One compiler API state container identified by caller-provided `moduleId`.
- **Module VFS**
  - In-memory virtual filesystem owned by one module.
- **Virtual path**
  - POSIX-style absolute module path such as `/src/player.gd`.
- **Display path**
  - Caller-facing source label for diagnostics and results, for example `res://player.gd`.
- **Local link**
  - VFS link whose target is a host filesystem path. It is metadata only inside this API.
- **Virtual link**
  - VFS link whose target is another path inside the same module VFS.
- **Compile task**
  - One asynchronous compile attempt started by `compile(moduleId)`.
- **Analysis request**
  - One synchronous analyze-only pass started by `analyze(moduleId, analyzeOptions)`. It reports
    frontend diagnostics and optional lowering verification without producing artifacts.
- **Output mount root**
  - VFS directory where successful compile outputs are published as local links.

---

## 3. Public Facade Surface

`API` is the only public facade. Do not introduce service interfaces with a single implementation.
New RPC-visible capability should normally appear as a method or DTO on this facade unless there is a
clear existing package pattern that says otherwise.

Current public operations are grouped below by responsibility.

### 3.1 Module Lifecycle

- `createModule(moduleId, moduleName)`
- `getModule(moduleId)`
- `listModules()`
- `deleteModule(moduleId)`

Module IDs are caller-provided, trimmed, non-blank strings. Duplicate IDs fail. Deleting a module
removes its in-memory state completely, except retained compile task snapshots that already exist in
the task table until their TTL expires.

Deleting a module with a queued or active compile task is rejected. The API does not silently cancel
or interrupt compilation during deletion.

### 3.2 Virtual Filesystem

- `createDirectory(moduleId, path)`
- `putFile(moduleId, path, content)`
- `putFile(moduleId, path, content, displayPath)`
- `readFile(moduleId, path)`
- `deletePath(moduleId, path, recursive)`
- `listDirectory(moduleId, path)`
- `readEntry(moduleId, path)`

`putFile(...)` creates missing parent directories. `createDirectory(...)` is idempotent for existing
directories and also creates missing ancestors. Root `/` is a directory and cannot be overwritten as a
file or link, or deleted through `deletePath(...)`.

`readEntry(...)` returns metadata. File content is only returned by `readFile(...)` so directory
listing and entry reads do not accidentally transmit large source bodies.

### 3.3 Links

- `createLink(moduleId, path, linkKind, target)`

`VIRTUAL` links target another module VFS path. `LOCAL` links target a host filesystem path string.
Reading link metadata does not dereference the final link. `readFile(...)` and `listDirectory(...)`
explicitly dereference virtual links. Local links remain metadata-only; this API does not read host
files through them.

### 3.4 Compile Configuration

- `getCompileOptions(moduleId)`
- `setCompileOptions(moduleId, compileOptions)`
- `getTopLevelCanonicalNameMap(moduleId)`
- `setTopLevelCanonicalNameMap(moduleId, map)`

`CompileOptions` is replaced as a whole immutable snapshot. Updating options or class-name mappings
does not trigger compilation.

Top-level canonical name mapping keeps the frontend contract: the map is passed to `FrontendModule`
and validated through `FrontendClassNameContract`. API code must not maintain a second copy of the
`__sub__` reserved-sequence rules.

### 3.5 Compile Tasks

- `compile(moduleId)`
- `getCompileTask(taskId)`
- `cancelCompileTask(taskId)`
- `getLastCompileResult(moduleId)`
- `getLatestCompileTaskEvent(taskId)`
- `listCompileTaskEvents(taskId)`
- `listCompileTaskEvents(taskId, startIndex, maxCount)`
- `listCompileTaskEvents(taskId, category, startIndex, maxCount)`
- `clearCompileTaskEvents(taskId)`
- `API.recordCurrentCompileTaskEvent(category, detail)`

`compile(moduleId)` returns a task ID immediately after queuing a compile reservation. Callers poll
`getCompileTask(taskId)` for lifecycle state, stage, progress fields, and final result. Completed task
snapshots and their event logs are retained for a TTL, then removed by a background cleaner.

`recordCurrentCompileTaskEvent(...)` only records into the compile task bound to the current thread.
Calls outside an API-managed compile thread return `false`.

### 3.6 Module Analysis

- `analyze(moduleId)`
- `analyze(moduleId, analyzeOptions)`

`analyze(...)` is the synchronous analyze-only entrypoint for editor-plugin style integrations. It
runs the parser plus the shared semantic pipeline over the module's frozen sources and returns an
`AnalysisResult` with frontend diagnostics. It never requires `projectPath`, never generates C
code, never starts a native build, and never touches the module's last compile result.

When `AnalyzeOptions.includeLowering()` is `true`, the pass continues into
`FrontendLoweringPassManager.lower(...)` after parsing so the caller can verify whether the module
can currently lower to LIR; the C backend still never runs. Lowering reruns semantic analysis
through the compile-only gate, so this mode can additionally surface `sema.compile_check`
diagnostics that plain analysis intentionally skips.

The analysis result surface is deliberately independent from the compile surface: `AnalysisResult`
and `AnalyzeOptions` are separate DTOs and must not reuse `CompileResult`, compile task snapshots,
or compile outcomes.

### 3.7 API Instance Lifecycle

`API` implements `AutoCloseable`. `close()` is the SIGINT-style teardown entrypoint and is
idempotent. In order, it:

1. Stops the TTL cleaner permanently (the sweep thread is interrupted and later `ensureRunning()`
   calls become no-ops, so the cleaner cannot resurrect on a non-empty task table).
2. Requests cancellation for every unfinished compile task, serialized against `compile(...)`
   admission: a task is either fully registered before the sweep (and is then canceled by it) or
   the compile call fails because the instance is already closed. Queued tasks complete as
   `CANCELED` synchronously (last-result writeback included); running tasks are interrupted —
   their runners notice the cancellation flag, finish as `CANCELED` through the normal runner
   completion path, and release the module gate exactly like an explicit `cancelCompileTask(...)`.
3. Joins runner threads with a bounded total budget; tasks still alive after the budget are logged
   as a warning instead of blocking shutdown forever. Native subprocesses respond to interruption
   by being destroyed, so runners are expected to die promptly.

After `close()` returns, the instance rejects new work: state-mutating methods (`createModule`,
`deleteModule`, all VFS writes/links/deletes, `setCompileOptions`, `setTopLevelCanonicalNameMap`,
`analyze(...)`, `compile(...)`, `cancelCompileTask(...)`, `clearCompileTaskEvents(...)`) throw
`IllegalStateException`. Read-only queries (`getModule`, `listModules`, `readFile`,
`listDirectory`, `readEntry`, `getCompileOptions`, `getTopLevelCanonicalNameMap`,
`getLastCompileResult`, `getCompileTask`, `getLatestCompileTaskEvent`, `listCompileTaskEvents`)
keep serving the final snapshots so teardown outcomes stay inspectable. Adapters mapping
`IllegalStateException` from a closing instance treat it as an internal error, which is acceptable
because the transport is already shutting down.

---

## 4. Data Model

### 4.1 Module State

Each module owns:

- `moduleId`
- `moduleName`
- root `DirectoryNode`
- `CompileOptions`
- top-level canonical name map
- last `CompileResult`
- last published output mount root

State is mutable inside `ModuleState`, but callers only receive frozen snapshots and records.

### 4.2 Module Snapshot

`ModuleSnapshot` is intentionally coarse:

- module identity
- compile options
- top-level canonical name map
- whether a last compile result exists
- root direct entry count

Detailed VFS inspection belongs to the VFS APIs rather than module snapshot expansion.

### 4.3 VFS Nodes

The VFS node hierarchy is package-private and sealed:

- `DirectoryNode`
- `FileNode`
- `LinkNode`

Directory children are stored in a sorted map so listing order is stable. File nodes store text
content, display path, UTF-8 byte count, and update time. Link nodes store kind and target text.

### 4.4 VFS Entry Snapshots

`VfsEntrySnapshot` is the transport-facing metadata shape:

- directory snapshot: virtual path, name, child count
- file snapshot: virtual path, display path, name, byte count, update time
- link snapshot: virtual path, name, link kind, target, optional broken reason

For file snapshots:

- `path()` returns the caller-facing display path
- `virtualPath()` returns the VFS location for subsequent API operations

For directories and links, `path()` and `virtualPath()` are the same virtual path.

### 4.5 Compile Options

`CompileOptions` contains:

- `GodotVersion godotVersion`
- `Path projectPath`
- `COptimizationLevel optimizationLevel`
- `TargetPlatform targetPlatform`
- `boolean strictMode`
- `String outputMountRoot`

Defaults:

- Godot version: `V451`
- optimization: `DEBUG`
- target platform: native platform
- strict mode: `false`
- output mount root: `/__build__`
- project path: `null`

`projectPath == null` is accepted while configuring a module, but compilation fails with
`CONFIGURATION_FAILED` until a project path is set.

### 4.6 Compile Result

`CompileResult` freezes the observable outcome of one compile attempt:

- `outcome`
- `compileOptions`
- top-level canonical name map
- source paths using display paths
- diagnostics snapshot
- failure message
- build log
- generated host paths
- artifact host paths
- output VFS local links

Current outcomes:

- `SUCCESS`
- `SOURCE_COLLECTION_FAILED`
- `CONFIGURATION_FAILED`
- `FRONTEND_FAILED`
- `BUILD_FAILED`
- `CANCELED`

### 4.7 Compile Task Snapshot

`CompileTaskSnapshot` exposes coarse lifecycle plus fine-grained stage:

States:

- `QUEUED`
- `RUNNING`
- `SUCCEEDED`
- `FAILED`
- `CANCELED`

Stages:

- `QUEUED`
- `FREEZING_INPUTS`
- `COLLECTING_SOURCES`
- `PARSING`
- `LOWERING`
- `CODEGEN_PREPARE`
- `BUILDING_NATIVE`
- `FINISHED`

Progress fields:

- `stageMessage`
- `completedUnits`
- `totalUnits`
- `currentSourcePath`
- monotonic `revision`

Parsing progress is counted by source unit. Native build progress is intentionally coarse.

### 4.8 Analyze Options

`AnalyzeOptions` contains:

- `boolean includeLowering`

Defaults: `includeLowering == false`, so `analyze(moduleId)` runs the shared semantic pipeline
only.

### 4.9 Analysis Result

`AnalysisResult` freezes the observable outcome of one synchronous analysis attempt:

- `outcome`
- `analyzeOptions`
- `godotVersion` taken from the module's `CompileOptions`; extension metadata is only loaded once
  source collection and parsing have succeeded
- top-level canonical name map
- source paths using display paths
- diagnostics snapshot
- failure message
- `loweringStatus`

Current outcomes:

- `COMPLETED`: the parse/analyze pipeline (and lowering, when requested) ran to completion.
  Diagnostics may still contain errors; `COMPLETED` describes the pipeline, not code health.
- `SOURCE_COLLECTION_FAILED`: module VFS source collection failed before parsing, for example on
  broken or cyclic virtual links, or the module has no `.gd`/`.gd3` sources.
- `INTERNAL_FAILED`: required compiler metadata such as the Godot extension API could not be
  loaded.

Lowering status:

- `NOT_REQUESTED`: `includeLowering` was `false`; no lowering answer is available.
- `SUCCEEDED`: lowering was requested, published a `LirModule`, and diagnostics contain no errors.
- `FAILED`: lowering was requested but the module cannot currently lower, either because
  diagnostics contain errors or because the pipeline stopped before LIR publication.

---

## 5. Virtual Path Contract

Remote callers always use POSIX-style module paths.

Rules:

- root is `/`
- paths must be absolute
- separator is `/`
- blank paths are rejected
- backslashes are rejected
- empty path segments are rejected
- `.` and `..` segments are rejected
- no host filesystem semantics are inferred from VFS paths

`displayPath` is separate from VFS identity:

- it must be non-blank
- it is not parsed as a VFS path
- it may use labels such as `res://player.gd`
- diagnostics and result source paths prefer it
- overwriting a file without a new display path preserves the old display path

Compiler-facing logical paths are synthetic host-compatible paths under `vfs/<moduleId>/...`. They
exist only to satisfy parser/frontend path APIs and are remapped back to display paths in diagnostics
when possible.

---

## 6. Link Contract

Links are first-class VFS entries.

`VIRTUAL` links:

- target a normalized VFS path in the same module
- may point to files or directories
- are dereferenced by `readFile(...)`, `listDirectory(...)`, and source collection
- are inspected for broken target and cycles

`LOCAL` links:

- target non-blank host path text
- are exposed as metadata
- are not dereferenced by `readFile(...)`, `listDirectory(...)`, or source collection
- are used to publish generated files and artifacts

Broken virtual links remain readable as metadata via `readEntry(...)`. If a broken or cyclic virtual
link participates in source collection, the compile result is `SOURCE_COLLECTION_FAILED`.

When the same backing `FileNode` is reachable through multiple virtual paths, compile source
collection deduplicates by file node and keeps the lexically earliest surfaced virtual path as the
stable ordering anchor.

---

## 7. Compile Pipeline Contract

Compilation reuses the existing compiler pipeline. API code must not duplicate frontend, lowering, or
backend semantics.

One compile task performs:

1. Wait for same-module gate.
2. Freeze compile options, class-name map, and VFS source snapshots.
3. Collect `.gd`/`.gd3` files from the whole module VFS.
4. Ignore `LOCAL` links during source collection.
5. Parse each source with `GdScriptParserService.parseUnit(...)`.
6. Construct `FrontendModule(moduleName, units, topLevelCanonicalNameMap)`.
7. Load extension metadata for `CompileOptions.godotVersion`.
8. Create `ClassRegistry`.
9. Run `FrontendLoweringPassManager.lower(...)`.
10. Prepare C codegen with `CCodegen.prepare(...)`.
11. Validate and prepare output publication.
12. Build the local C project with `CProjectBuilder.buildProject(...)`, which returns `CBuildResult`:
    the generated files written by this codegen pass plus the native `CCompileResult`.
13. Publish generated files and artifacts into module VFS as `LOCAL` links if build succeeds.
14. Write final `CompileResult` to the task and module's last-result slot.

If parser or frontend diagnostics contain errors, compilation stops before native build and returns
`FRONTEND_FAILED`.

Non-`.gd`/`.gd3` VFS files remain ordinary module files and are not compile sources. Adapters that
expose a source-file input contract, such as the CLI `files` argument, must reject non-`.gd` host
inputs at their own boundary before writing them into the module VFS.

If `projectPath` is missing or cannot be created as a directory, compilation returns
`CONFIGURATION_FAILED`.

Build failures return `BUILD_FAILED` with build log and the generated files that the current
`CProjectBuilder` invocation actually wrote. The task runner must not infer generated files by
scanning fixed names under `projectPath`, because stale files from earlier attempts are not part of
the current compile result.

The result boundary is intentional:

- `CCompileResult` belongs to `CCompiler` implementations such as `ZigCcCompiler`; it reports only
  native compiler success, build log, and artifacts.
- `CBuildResult` belongs to `CProjectBuilder`; it wraps the native compile result and adds
  `generatedFiles`, because only the project builder knows which `GeneratedFile` instances were
  written for the current invocation.

### 7.1 Analysis Pipeline Boundary

One analysis request performs:

1. Freeze compile options, class-name map, and VFS source snapshots through the same module-state
   freeze used by compile tasks.
2. Collect `.gd`/`.gd3` files from the whole module VFS, with identical link and dedup rules.
3. Parse each source with `GdScriptParserService.parseUnit(...)`.
4. Stop after parsing when parse diagnostics contain errors, because semantic phases require a
   well-formed AST.
5. Load extension metadata for the module's `CompileOptions.godotVersion` and create
   `ClassRegistry`.
6. Run `FrontendSemanticAnalyzer.analyze(...)`, the shared semantic pipeline without the
   compile-only gate, unless lowering was requested.
7. When `AnalyzeOptions.includeLowering()` is `true`, run
   `FrontendLoweringPassManager.lower(...)` instead, which reruns analysis through
   `analyzeForCompile(...)` and emits a `LirModule` when no errors exist.

The analysis boundary ends before `CCodegen.prepare(...)`: analysis never instantiates C codegen,
never writes generated files, never invokes a native compiler, and never publishes output links.
Only `CompileOptions.godotVersion` influences analysis; build-directory, optimization, platform,
and output-mount settings are compile-only inputs.

Semantic analyzers and lowering passes keep per-run state (for example lambda name counters and
scope reverse indexes), so each analysis request constructs fresh `FrontendSemanticAnalyzer` /
`FrontendLoweringPassManager` instances. Analysis never shares a lowering pass manager with compile
tasks; both paths construct a fresh instance per request or task.

---

## 8. Concurrency Contract

The implementation favors correctness over same-module read/write concurrency.

Per module:

- VFS reads and writes, link operations, compile options, class-name mapping, snapshots, and
  last-result queries serialize through one module gate.
- `analyze(moduleId, ...)` is synchronous and also serializes through the module gate, so it waits
  for a queued or active compile of the same module to finish before its inputs are frozen.
- `compile(moduleId)` reserves the module's compile slot before returning a task ID.
- A queued compile prevents later same-module operations from overtaking it before input freeze.
- Once a compile task becomes active, it holds the module gate until result writeback and output
  publication are complete.
- A second compile request for the same module fails while a queued or active compile exists.
- Deleting a module fails while a compile task is queued or active.
- `compile(...)` admission is serialized against `close()`: a compile call either registers its
  task before the close sweep (and gets canceled by it) or fails on the closed instance; no runner
  thread can leak between the sweep and the cancellation requests.

Across modules:

- module gates are independent
- in-memory VFS and configuration state are isolated by module

Host filesystem build directories are outside this in-memory isolation contract. RPC adapters or
future API work must ensure different modules do not concurrently use the same physical project path
unless a separate project-path lock and cleanup policy exists.

---

## 9. Output Publication Contract

Successful builds publish local outputs under `CompileOptions.outputMountRoot`.

Default layout:

- `/__build__/generated/entry.c`
- `/__build__/generated/entry.h`
- `/__build__/generated/engine_method_binds.h`
- `/__build__/artifacts/<artifact-file-name>`

Each entry is a `LOCAL` link whose target is the generated or artifact host path.

Publication is split into two phases:

1. Validate that the output mount root and compiler-managed subdirectories can host output links
   without mutating current VFS state.
2. After source collection, configuration, frontend lowering, and codegen prepare have succeeded,
   clear old generated/artifact output directories and publish the new links.

Early failures keep previously published successful output links intact. Build failures clear old
published output before build, but do not publish new output links.

The current implementation treats `generated` and `artifacts` under the output mount root as
compiler-managed names. Future changes that allow arbitrary caller content under those names need an
explicit ownership marker or publication manifest.

The API compile pipeline does not generate Godot `.gdextension` metadata files. That host-file
convenience belongs to adapters with enough caller context to relate host source files to a
`project.godot` file. The local CLI currently performs that post-success step when its `--project`
option proves that all input scripts belong to the same Godot project.

---

## 10. Task Retention and Events

Completed compile task snapshots and event logs are retained for a configured TTL. A background
virtual-thread cleaner periodically removes expired completed tasks. Queued and running tasks are not
removed by TTL.

Events are simple append-order records:

- `category`
- `detail`

Event retrieval supports append-order indexed paging. Callers can read pages with
`listCompileTaskEvents(taskId, startIndex, maxCount)`, where `startIndex` is the zero-based event
index in the retained append order and `maxCount` is the requested page size.

The API also supports category-filtered paging with
`listCompileTaskEvents(taskId, category, startIndex, maxCount)`. The returned indexes still refer to
the retained full event stream, not to a re-numbered category-local stream.

`maxCount` is limited by `API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE`. Requests outside `1..MAX` fail
rather than silently truncating. Adapters that need incremental reads should persist the next unread
index and resume from that index on the next call. The latest event and full retained event list are
still available, but consumers should not assume the only choices are full-list reads or latest-event
polling. The API exposes paging limits, but it does not attach per-page truncation metadata beyond
the returned event slice itself.

---

## 11. RPC Adapter Responsibilities

Because `API` is transport-neutral, adapters must own the following policies:

- authentication and authorization
- tenant/session ownership of module IDs
- request size limits
- source file size limits
- module count and VFS entry quotas
- compile task rate limits
- compile timeout and cancellation policy
- project workspace allocation
- validation that host build paths stay inside approved workspace roots
- artifact download endpoints or opaque artifact handles
- redaction of host paths if clients must not see server filesystem layout
- serialization of diagnostics, snapshots, enums, exceptions, and path-like values

Adapters must not expose `LOCAL` link targets as arbitrary host file read capability. A local link is
metadata unless an adapter deliberately maps it to a safe artifact download operation.

---

## 12. Godot RPC Boundary

Do not confuse this document with Godot's script/multiplayer RPC feature.

Godot's `@rpc` annotation and `Node.rpc_config(...)` contract belongs to GDScript parsing,
multiplayer metadata, and generated class registration. Supporting that language/runtime feature in
gdcc requires a separate design covering at least:

- accepted `@rpc` annotation arguments
- validation against Godot's RPC config parser
- lowering or LIR annotation carry-through
- backend calls to register RPC config for generated methods
- runtime parity tests against Godot multiplayer behavior

The compiler control API documented here may compile source files that contain annotations, but it
does not by itself define or implement Godot RPC semantics.

---

## 13. Known Follow-Up Work

The current API is useful as an in-process compiler control surface, but long-lived remote compiler
services need additional hardening before exposing it directly to untrusted clients.

Important follow-up items:

- Replace caller-provided raw `projectPath` with server-owned workspace allocation, or validate it
  against configured writable roots.
- Add project-path-level locking so different modules cannot concurrently write the same build
  directory.
- Add native build timeout policy.
- Add quotas for modules, VFS entries, source bytes, retained tasks, and event logs.
- Add event retention caps or truncation metadata if adapters need bounded long-running task logs.
- Replace or supplement host `Path` exposure with opaque artifact handles for remote clients.
- Add ownership markers or manifests for compiler-published output directories.
- Split unexpected internal failures from native build failures in `CompileResult.Outcome` if clients
  need more precise retry or reporting behavior.

These items are not excuses to broaden the current abstraction. They are boundary policies around
the existing facade and should remain focused unless a concrete RPC adapter requires more.

---

## 14. Stable Test Anchors

Focused API tests currently anchor the contract:

- module lifecycle: `ApiModuleLifecycleTest`
- VFS path and CRUD: `ApiVirtualPathTest`, `ApiVirtualFileSystemTest`
- links: `ApiVirtualLinkTest`
- compile configuration: `ApiCompileOptionsTest`, `ApiCanonicalNameMapTest`
- compile pipeline and diagnostics: `ApiCompilePipelineTest`, `ApiCompileDiagnosticsTest`,
  `ApiMappedClassCompileTest`
- analyze-only surface: `ApiAnalyzeTest`
- informational pipeline throughput (no perf thresholds): `ApiCompileThroughputTest`
- task lifecycle, progress, events, retention: `ApiCompileTaskTest`,
  `ApiCompileTaskProgressTest`, `ApiCompileTaskFailureStageTest`, `ApiCompileTaskEventTest`,
  `ApiCompileTaskTtlTest`
- task cancellation and instance lifecycle: `ApiCompileTaskCancellationTest`, `ApiCloseTest`
- output publication: `ApiCompileArtifactLinkTest`, `ApiRecompileArtifactRefreshTest`
- concurrency and module isolation: `ApiConcurrentMutationTest`, `ApiMultiModuleIsolationTest`
- generated-file provenance: `ApiCompileDiagnosticsTest`, `CProjectBuilderSharedIncludeTest`

When extending this API, prefer targeted tests that pin the affected contract instead of broad,
slow suite runs during iteration.
