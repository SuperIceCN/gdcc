package gd.script.gdcc.rpc;

import com.google.gson.JsonObject;
import gd.script.gdcc.api.API;
import gd.script.gdcc.api.AnalyzeOptions;
import gd.script.gdcc.util.GdccVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Method table for the JSON-RPC surface: one entry per dot-namespaced method, mapped 1:1 onto the
/// public `API` facade. The adapter layer owns no compiler, VFS, or task semantics — every handler
/// only binds its dedicated param record and delegates.
///
/// Excluded on purpose: `API.recordCurrentCompileTaskEvent(...)` (bound to the in-process compile
/// thread, meaningless remotely) and the unpaged `listCompileTaskEvents(taskId)` overload (the RPC
/// surface always uses the paged, indexed variant for a stable wire shape).
public final class JsonRpcMethodRegistry {
    /// A bound method invocation. Binding failures (`JsonParseException`, `IllegalArgumentException`,
    /// `NullPointerException`) and API exceptions propagate to the dispatcher's error mapping.
    @FunctionalInterface
    public interface RpcHandler {
        @Nullable Object invoke(@NotNull API api, @NotNull RpcJsonCodec codec, @NotNull JsonObject params);
    }

    /// `compile.start` result shape: the raw task id wrapped in an object for a stable wire shape.
    public record CompileStartResult(long taskId) {
    }

    /// `server.info` result shape, reusing the generated version resource the CLI `--version`
    /// output is built from.
    public record ServerInfo(
            @NotNull String version,
            @NotNull String branch,
            @NotNull String commit,
            int maxCompileTaskEventPageSize
    ) {
        public ServerInfo {
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(branch, "branch must not be null");
            Objects.requireNonNull(commit, "commit must not be null");
        }
    }

    private final @NotNull Map<String, RpcHandler> handlers;

    // Package-visible so tests can inject registries with failing handlers for the `-32603`
    // fallback mapping; production code always uses `create()`.
    JsonRpcMethodRegistry(@NotNull Map<String, RpcHandler> handlers) {
        this.handlers = Map.copyOf(handlers);
    }

    public @Nullable RpcHandler find(@NotNull String method) {
        return handlers.get(method);
    }

    public int methodCount() {
        return handlers.size();
    }

    public static @NotNull JsonRpcMethodRegistry create() {
        var handlers = new LinkedHashMap<String, RpcHandler>();
        handlers.put("server.ping", (api, codec, params) -> "pong");
        handlers.put("server.info", (api, codec, params) -> {
            var info = GdccVersion.current();
            return new ServerInfo(
                    info.version(),
                    info.branch(),
                    info.commit(),
                    API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE
            );
        });
        handlers.put("module.create", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.ModuleCreateParams.class);
            return api.createModule(bound.moduleId(), bound.moduleName());
        });
        handlers.put("module.get", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.ModuleGetParams.class);
            return api.getModule(bound.moduleId());
        });
        handlers.put("module.list", (api, codec, params) -> api.listModules());
        handlers.put("module.delete", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.ModuleDeleteParams.class);
            return api.deleteModule(bound.moduleId());
        });
        handlers.put("vfs.createDirectory", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.VfsCreateDirectoryParams.class);
            return api.createDirectory(bound.moduleId(), bound.path());
        });
        handlers.put("vfs.putFile", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.VfsPutFileParams.class);
            // A missing displayPath must reach the API as "not provided", not as an explicit null.
            return bound.displayPath() == null
                    ? api.putFile(bound.moduleId(), bound.path(), bound.content())
                    : api.putFile(bound.moduleId(), bound.path(), bound.content(), bound.displayPath());
        });
        handlers.put("vfs.readFile", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.VfsReadFileParams.class);
            return api.readFile(bound.moduleId(), bound.path());
        });
        handlers.put("vfs.deletePath", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.VfsDeletePathParams.class);
            return api.deletePath(bound.moduleId(), bound.path(), bound.recursive());
        });
        handlers.put("vfs.listDirectory", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.VfsListDirectoryParams.class);
            return api.listDirectory(bound.moduleId(), bound.path());
        });
        handlers.put("vfs.readEntry", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.VfsReadEntryParams.class);
            return api.readEntry(bound.moduleId(), bound.path());
        });
        handlers.put("vfs.createLink", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.VfsCreateLinkParams.class);
            return api.createLink(bound.moduleId(), bound.path(), bound.linkKind(), bound.target());
        });
        handlers.put("options.get", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.OptionsGetParams.class);
            return api.getCompileOptions(bound.moduleId());
        });
        handlers.put("options.set", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.OptionsSetParams.class);
            return api.setCompileOptions(bound.moduleId(), bound.compileOptions());
        });
        handlers.put("classMap.get", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.ClassMapGetParams.class);
            return api.getTopLevelCanonicalNameMap(bound.moduleId());
        });
        handlers.put("classMap.set", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.ClassMapSetParams.TYPE_TOKEN);
            return api.setTopLevelCanonicalNameMap(bound.moduleId(), bound.topLevelCanonicalNameMap());
        });
        handlers.put("compile.start", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.CompileStartParams.class);
            return new CompileStartResult(api.compile(bound.moduleId()));
        });
        handlers.put("compile.getTask", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.CompileGetTaskParams.class);
            return api.getCompileTask(bound.taskId());
        });
        handlers.put("compile.cancel", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.CompileCancelParams.class);
            return api.cancelCompileTask(bound.taskId());
        });
        handlers.put("compile.getLastResult", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.CompileGetLastResultParams.class);
            return api.getLastCompileResult(bound.moduleId());
        });
        handlers.put("compile.listEvents", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.CompileListEventsParams.class);
            return api.listCompileTaskEvents(
                    bound.taskId(),
                    bound.category(),
                    bound.startIndexOrDefault(),
                    bound.maxCountOrDefault()
            );
        });
        handlers.put("compile.getLatestEvent", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.CompileGetLatestEventParams.class);
            return api.getLatestCompileTaskEvent(bound.taskId());
        });
        handlers.put("compile.clearEvents", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.CompileClearEventsParams.class);
            api.clearCompileTaskEvents(bound.taskId());
            return null;
        });
        handlers.put("analyze.run", (api, codec, params) -> {
            var bound = codec.bindParams(params, RpcParams.AnalyzeRunParams.class);
            return api.analyze(bound.moduleId(), new AnalyzeOptions(bound.includeLoweringOrDefault()));
        });
        return new JsonRpcMethodRegistry(handlers);
    }
}
