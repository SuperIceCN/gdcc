package gd.script.gdcc.rpc;

import com.google.gson.reflect.TypeToken;
import gd.script.gdcc.api.API;
import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.VfsEntrySnapshot;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;

/// Dedicated param records for every JSON-RPC method that accepts by-name params.
///
/// Binding rules (enforced together with `RpcJsonCodec.bindParams(...)`):
/// - every component is boxed (`Boolean`, `Long`, `Integer`) or a reference type, because Gson
///   silently zero-fills missing primitive fields, which would make a missing required component
///   indistinguishable from an explicit `false`/`0`;
/// - required components are rejected in the compact constructor when missing or JSON-`null`
///   (`NullPointerException`), and blank strings fail with `IllegalArgumentException` — both map
///   to JSON-RPC `-32602` at the dispatcher, never `-32603`;
/// - optional components stay nullable on the record and expose their default through explicit
///   `...OrDefault()` accessors so the dispatch table never re-encodes default knowledge;
/// - deeper format checks (virtual-path shape, enum values, ranges) are left to the API layer,
///   whose `IllegalArgumentException`/`NullPointerException` also map to `-32602`.
public final class RpcParams {
    private RpcParams() {
    }

    /// `params` member missing or JSON-`null` binds as an empty object; methods without required
    /// components (`server.ping`, `server.info`, `module.list`) therefore need no record at all.

    public record ModuleCreateParams(@NotNull String moduleId, @NotNull String moduleName) {
        public ModuleCreateParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            moduleName = StringUtil.requireTrimmedNonBlank(moduleName, "moduleName");
        }
    }

    public record ModuleGetParams(@NotNull String moduleId) {
        public ModuleGetParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        }
    }

    public record ModuleDeleteParams(@NotNull String moduleId) {
        public ModuleDeleteParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        }
    }

    public record VfsCreateDirectoryParams(@NotNull String moduleId, @NotNull String path) {
        public VfsCreateDirectoryParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            path = StringUtil.requireNonBlank(path, "path");
        }
    }

    public record VfsPutFileParams(@NotNull String moduleId, @NotNull String path,
                                   @NotNull String content, @Nullable String displayPath) {
        public VfsPutFileParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            path = StringUtil.requireNonBlank(path, "path");
            // File content may legitimately be empty; only a missing/`null` component is invalid.
            Objects.requireNonNull(content, "content must not be null");
            displayPath = StringUtil.requireNullableNonBlank(displayPath, "displayPath");
        }
    }

    public record VfsReadFileParams(@NotNull String moduleId, @NotNull String path) {
        public VfsReadFileParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            path = StringUtil.requireNonBlank(path, "path");
        }
    }

    public record VfsDeletePathParams(@NotNull String moduleId, @NotNull String path,
                                      @NotNull Boolean recursive) {
        public VfsDeletePathParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            path = StringUtil.requireNonBlank(path, "path");
            // Boxed on purpose: a missing `recursive` must not silently become `false`.
            Objects.requireNonNull(recursive, "recursive must not be null");
        }
    }

    public record VfsListDirectoryParams(@NotNull String moduleId, @NotNull String path) {
        public VfsListDirectoryParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            path = StringUtil.requireNonBlank(path, "path");
        }
    }

    public record VfsReadEntryParams(@NotNull String moduleId, @NotNull String path) {
        public VfsReadEntryParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            path = StringUtil.requireNonBlank(path, "path");
        }
    }

    public record VfsCreateLinkParams(@NotNull String moduleId, @NotNull String path,
                                      @NotNull VfsEntrySnapshot.LinkKind linkKind,
                                      @NotNull String target) {
        public VfsCreateLinkParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            path = StringUtil.requireNonBlank(path, "path");
            Objects.requireNonNull(linkKind, "linkKind must not be null");
            // Blank is invalid for both link flavors; the VIRTUAL shape check stays in the API.
            target = StringUtil.requireNonBlank(target, "target");
        }
    }

    public record OptionsGetParams(@NotNull String moduleId) {
        public OptionsGetParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        }
    }

    /// `compileOptions` must be the complete object (same shape as `options.get` output) because
    /// `API.setCompileOptions` replaces the whole snapshot.
    public record OptionsSetParams(@NotNull String moduleId, @NotNull CompileOptions compileOptions) {
        public OptionsSetParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            Objects.requireNonNull(compileOptions, "compileOptions must not be null");
        }
    }

    public record ClassMapGetParams(@NotNull String moduleId) {
        public ClassMapGetParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        }
    }

    /// The map component keeps the Java name `topLevelCanonicalNameMap` on the wire even though the
    /// method namespace is the shorter `classMap.*` alias.
    public record ClassMapSetParams(@NotNull String moduleId,
                                    @NotNull Map<String, String> topLevelCanonicalNameMap) {
        public static final TypeToken<ClassMapSetParams> TYPE_TOKEN = new TypeToken<>() {
        };

        public ClassMapSetParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
            Objects.requireNonNull(
                    topLevelCanonicalNameMap,
                    "topLevelCanonicalNameMap must not be null"
            );
        }
    }

    public record CompileStartParams(@NotNull String moduleId) {
        public CompileStartParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        }
    }

    public record CompileGetTaskParams(@NotNull Long taskId) {
        public CompileGetTaskParams {
            Objects.requireNonNull(taskId, "taskId must not be null");
        }
    }

    public record CompileCancelParams(@NotNull Long taskId) {
        public CompileCancelParams {
            Objects.requireNonNull(taskId, "taskId must not be null");
        }
    }

    public record CompileGetLastResultParams(@NotNull String moduleId) {
        public CompileGetLastResultParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        }
    }

    public record CompileListEventsParams(@NotNull Long taskId, @Nullable Long startIndex,
                                          @Nullable Integer maxCount, @Nullable String category) {
        public CompileListEventsParams {
            Objects.requireNonNull(taskId, "taskId must not be null");
            category = category == null ? null : StringUtil.requireNonBlank(category, "category");
        }

        public long startIndexOrDefault() {
            return startIndex == null ? 0 : startIndex;
        }

        public int maxCountOrDefault() {
            return maxCount == null ? API.MAX_COMPILE_TASK_EVENT_PAGE_SIZE : maxCount;
        }
    }

    public record CompileGetLatestEventParams(@NotNull Long taskId) {
        public CompileGetLatestEventParams {
            Objects.requireNonNull(taskId, "taskId must not be null");
        }
    }

    public record CompileClearEventsParams(@NotNull Long taskId) {
        public CompileClearEventsParams {
            Objects.requireNonNull(taskId, "taskId must not be null");
        }
    }

    public record AnalyzeRunParams(@NotNull String moduleId, @Nullable Boolean includeLowering) {
        public AnalyzeRunParams {
            moduleId = StringUtil.requireTrimmedNonBlank(moduleId, "moduleId");
        }

        public boolean includeLoweringOrDefault() {
            return includeLowering != null && includeLowering;
        }
    }
}
