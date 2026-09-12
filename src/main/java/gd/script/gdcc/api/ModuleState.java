package gd.script.gdcc.api;

import gd.script.gdcc.exception.ApiBrokenLinkException;
import gd.script.gdcc.exception.ApiDirectoryNotEmptyException;
import gd.script.gdcc.exception.ApiEntryTypeMismatchException;
import gd.script.gdcc.exception.ApiLinkCycleException;
import gd.script.gdcc.exception.ApiPathNotFoundException;
import gd.script.gdcc.frontend.FrontendClassNameContract;
import gd.script.gdcc.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ModuleState {
    private static final @NotNull VirtualPath ROOT_PATH = VirtualPath.parse("/");
    private static final @NotNull String GENERATED_OUTPUT_DIR = "generated";
    private static final @NotNull String ARTIFACT_OUTPUT_DIR = "artifacts";

    private final @NotNull String moduleId;
    private final @NotNull String moduleName;
    private final @NotNull Clock clock;
    private final @NotNull DirectoryNode root;
    private @NotNull CompileOptions compileOptions;
    private @NotNull Map<String, String> topLevelCanonicalNameMap;
    private @Nullable CompileResult lastCompileResult;
    private @Nullable VirtualPath publishedOutputMountRoot;

    ModuleState(@NotNull String moduleId, @NotNull String moduleName, @NotNull Clock clock) {
        this.moduleId = Objects.requireNonNull(moduleId, "moduleId must not be null");
        this.moduleName = Objects.requireNonNull(moduleName, "moduleName must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        root = new DirectoryNode();
        compileOptions = CompileOptions.defaults();
        topLevelCanonicalNameMap = Map.of();
        lastCompileResult = null;
    }

    @NotNull
    synchronized ModuleSnapshot snapshot() {
        return new ModuleSnapshot(
                moduleId,
                moduleName,
                compileOptions,
                topLevelCanonicalNameMap,
                lastCompileResult != null,
                root.childCount()
        );
    }

    /// `createDirectory(...)` is idempotent for existing directories and creates missing ancestors.
    synchronized @NotNull VfsEntrySnapshot.DirectoryEntrySnapshot createDirectory(@NotNull VirtualPath path) {
        if (path.isRoot()) {
            return root.snapshot(path);
        }
        var parent = requireParentDirectory(path, true);
        var existing = parent.child(path.name());
        if (existing == null) {
            var created = new DirectoryNode();
            parent.putChild(path.name(), created);
            return created.snapshot(path);
        }
        return switch (existing) {
            case DirectoryNode directoryNode -> directoryNode.snapshot(path);
            case FileNode _, LinkNode _ ->
                    throw typeMismatch(path.text(), nodeLabel(existing), VfsEntrySnapshot.Kind.DIRECTORY);
        };
    }

    /// `putFile(...)` owns the "mkdir -p parent directories" behavior promised by the RPC plan.
    synchronized @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull VirtualPath path,
            @NotNull String content
    ) {
        return putFile(path, content, null);
    }

    /// `displayPath` is purely a caller-facing source label. It does not change where the file
    /// lives in module VFS, but snapshots and compile diagnostics prefer it over the surfaced
    /// virtual path whenever the caller provided one.
    synchronized @NotNull VfsEntrySnapshot.FileEntrySnapshot putFile(
            @NotNull VirtualPath path,
            @NotNull String content,
            @Nullable String displayPath
    ) {
        if (path.isRoot()) {
            throw new IllegalArgumentException("path '/' cannot be used as a file path");
        }
        Objects.requireNonNull(content, "content must not be null");
        var parent = requireParentDirectory(path, true);
        var existing = parent.child(path.name());
        if (existing instanceof DirectoryNode) {
            throw typeMismatch(path.text(), "directory", VfsEntrySnapshot.Kind.FILE);
        }
        var fileNode = FileNode.fromContent(content, resolveDisplayPath(path, displayPath, existing), clock);
        parent.putChild(path.name(), fileNode);
        return fileNode.snapshot(path);
    }

    synchronized @NotNull String readFile(@NotNull VirtualPath path) {
        var resolved = resolvePath(path, true, VfsEntrySnapshot.Kind.FILE);
        return switch (resolved.node()) {
            case FileNode fileNode -> fileNode.content();
            case DirectoryNode _ -> throw contentTypeMismatch(
                    path.text(),
                    "directory",
                    VfsEntrySnapshot.Kind.FILE,
                    resolved.finalLinkResolved()
            );
            case LinkNode linkNode -> throw typeMismatch(path.text(), nodeLabel(linkNode), VfsEntrySnapshot.Kind.FILE);
        };
    }

    synchronized @NotNull VfsEntrySnapshot.LinkEntrySnapshot createLink(
            @NotNull VirtualPath path,
            @NotNull VfsEntrySnapshot.LinkKind linkKind,
            @NotNull String target
    ) {
        if (path.isRoot()) {
            throw new IllegalArgumentException("path '/' cannot be used as a link path");
        }
        var normalizedKind = Objects.requireNonNull(linkKind, "linkKind must not be null");
        var normalizedTarget = Objects.requireNonNull(target, "target must not be null");
        var parent = requireParentDirectory(path, true);
        var existing = parent.child(path.name());
        if (existing instanceof DirectoryNode) {
            throw typeMismatch(path.text(), "directory", VfsEntrySnapshot.Kind.LINK);
        }
        var linkNode = switch (normalizedKind) {
            case VIRTUAL -> LinkNode.virtual(VirtualPath.parse(normalizedTarget));
            case LOCAL -> LinkNode.local(StringUtil.requireTrimmedNonBlank(normalizedTarget, "target"));
        };
        parent.putChild(path.name(), linkNode);
        return linkNode.snapshot(path, inspectLink(path, linkNode).brokenReason());
    }

    synchronized @NotNull VfsEntrySnapshot deletePath(@NotNull VirtualPath path, boolean recursive) {
        if (path.isRoot()) {
            throw new IllegalArgumentException("path '/' cannot be deleted; delete the module instead");
        }
        var parent = requireParentDirectory(path, false);
        var existing = parent.child(path.name());
        if (existing == null) {
            throw pathNotFound(path.text());
        }
        if (!recursive && existing instanceof DirectoryNode directoryNode && directoryNode.hasChildren()) {
            throw new ApiDirectoryNotEmptyException(
                    "Directory '" + path.text() + "' in module '" + moduleId + "' is not empty; recursive delete required"
            );
        }
        var removedSnapshot = snapshotNode(path, existing);
        parent.removeChild(path.name());
        return removedSnapshot;
    }

    synchronized @NotNull List<VfsEntrySnapshot> listDirectory(@NotNull VirtualPath path) {
        var resolved = resolvePath(path, true, VfsEntrySnapshot.Kind.DIRECTORY);
        return switch (resolved.node()) {
            case DirectoryNode directoryNode -> snapshotChildren(path, directoryNode);
            case FileNode _ -> throw contentTypeMismatch(
                    path.text(),
                    "file",
                    VfsEntrySnapshot.Kind.DIRECTORY,
                    resolved.finalLinkResolved()
            );
            case LinkNode linkNode ->
                    throw typeMismatch(path.text(), nodeLabel(linkNode), VfsEntrySnapshot.Kind.DIRECTORY);
        };
    }

    synchronized @NotNull VfsEntrySnapshot readEntry(@NotNull VirtualPath path) {
        return snapshotNode(path, resolvePath(path, false, null).node());
    }

    synchronized @NotNull CompileOptions getCompileOptions() {
        return compileOptions;
    }

    /// Compile options are replaced as one immutable snapshot so later compile orchestration can
    /// freeze exactly one coherent configuration per module.
    synchronized @NotNull CompileOptions setCompileOptions(@NotNull CompileOptions compileOptions) {
        this.compileOptions = Objects.requireNonNull(compileOptions, "compileOptions must not be null");
        return this.compileOptions;
    }

    synchronized @NotNull Map<String, String> getTopLevelCanonicalNameMap() {
        return topLevelCanonicalNameMap;
    }

    /// The API layer deliberately reuses the frontend public contract instead of re-implementing
    /// reserved-sequence and blank-entry checks under a second set of rules.
    synchronized @NotNull Map<String, String> setTopLevelCanonicalNameMap(@NotNull Map<String, String> topLevelCanonicalNameMap) {
        this.topLevelCanonicalNameMap = FrontendClassNameContract.freezeTopLevelCanonicalNameMap(Objects.requireNonNull(
                topLevelCanonicalNameMap,
                "topLevelCanonicalNameMap must not be null"
        ));
        return this.topLevelCanonicalNameMap;
    }

    /// Compile orchestration runs outside the module lock, so this method freezes all compile-facing
    /// inputs up front and resolves virtual source aliases while the tree is still stable.
    synchronized @NotNull CompileRequest freezeCompileRequest() {
        var sourcesByFile = new IdentityHashMap<FileNode, SourceSnapshot>();
        CompileRequestFailure failure = null;
        try {
            collectCompileSources(ROOT_PATH, root, sourcesByFile, new ArrayDeque<>(), new LinkedHashSet<>());
        } catch (ApiBrokenLinkException | ApiLinkCycleException exception) {
            failure = new CompileRequestFailure(CompileResult.Outcome.SOURCE_COLLECTION_FAILED, exception.getMessage());
        }
        var sources = sourcesByFile.values().stream()
                .sorted(Comparator.comparing(SourceSnapshot::virtualPath))
                .toList();
        return new CompileRequest(moduleId, moduleName, compileOptions, topLevelCanonicalNameMap, sources, failure);
    }

    synchronized @Nullable CompileResult getLastCompileResult() {
        return lastCompileResult;
    }

    synchronized void setLastCompileResult(@NotNull CompileResult compileResult) {
        lastCompileResult = Objects.requireNonNull(compileResult, "compileResult must not be null");
    }

    /// Validate that the caller-selected mount root and compiler-owned subdirectories can later host
    /// generated outputs without mutating any existing published links yet.
    synchronized void validateOutputMountRoot(@NotNull String outputMountRoot) {
        var mountRoot = VirtualPath.parse(outputMountRoot);
        validateManagedOutputDirectories(mountRoot);
    }

    /// Once the compile has passed source collection, configuration, and frontend validation, the
    /// API can replace any previously published outputs before native build begins.
    synchronized void prepareOutputPublication(@NotNull String outputMountRoot) {
        var currentRoot = VirtualPath.parse(outputMountRoot);
        clearManagedOutputDirectories(currentRoot, true);
        if (publishedOutputMountRoot != null && !publishedOutputMountRoot.equals(currentRoot)) {
            clearManagedOutputDirectories(publishedOutputMountRoot, false);
        }
        publishedOutputMountRoot = null;
    }

    /// Successful compiles publish their generated files and final artifacts back into the module
    /// VFS as `LOCAL` links so remote callers can inspect them without host-directory scans.
    synchronized @NotNull List<VfsEntrySnapshot.LinkEntrySnapshot> mountCompileOutputs(
            @NotNull String outputMountRoot,
            @NotNull List<Path> generatedFiles,
            @NotNull List<Path> artifacts
    ) {
        var mountRoot = VirtualPath.parse(outputMountRoot);
        createDirectory(mountRoot);
        var mountedLinks = new ArrayList<VfsEntrySnapshot.LinkEntrySnapshot>(generatedFiles.size() + artifacts.size());

        var generatedDir = mountRoot.child(GENERATED_OUTPUT_DIR);
        createDirectory(generatedDir);
        for (var generatedFile : generatedFiles) {
            mountedLinks.add(createLink(
                    generatedDir.child(generatedFile.getFileName().toString()),
                    VfsEntrySnapshot.LinkKind.LOCAL,
                    generatedFile.toString()
            ));
        }

        var artifactsDir = mountRoot.child(ARTIFACT_OUTPUT_DIR);
        createDirectory(artifactsDir);
        for (var artifact : artifacts.stream()
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).thenComparing(Path::toString))
                .toList()) {
            mountedLinks.add(createLink(
                    artifactsDir.child(artifact.getFileName().toString()),
                    VfsEntrySnapshot.LinkKind.LOCAL,
                    artifact.toString()
            ));
        }
        publishedOutputMountRoot = mountRoot;
        return List.copyOf(mountedLinks);
    }

    private @NotNull DirectoryNode requireParentDirectory(@NotNull VirtualPath path, boolean createMissing) {
        var linkStack = new ArrayDeque<String>();
        var activeLinks = new LinkedHashSet<String>();
        var current = root;
        for (var i = 0; i < path.segments().size() - 1; i++) {
            var segment = path.segments().get(i);
            var existing = current.child(segment);
            if (existing == null) {
                if (!createMissing) {
                    throw pathNotFound(path.text());
                }
                var created = new DirectoryNode();
                current.putChild(segment, created);
                current = created;
                continue;
            }
            var currentPath = path.prefixText(i + 1);
            current = switch (existing) {
                case DirectoryNode directoryNode -> directoryNode;
                case FileNode _ -> throw typeMismatch(currentPath, "file", VfsEntrySnapshot.Kind.DIRECTORY);
                case LinkNode linkNode -> {
                    if (linkNode.linkKind() == VfsEntrySnapshot.LinkKind.LOCAL) {
                        throw typeMismatch(currentPath, nodeLabel(linkNode), VfsEntrySnapshot.Kind.DIRECTORY);
                    }
                    yield requireDirectoryTarget(currentPath, resolveLinkTarget(currentPath, linkNode, linkStack, activeLinks));
                }
            };
        }
        return current;
    }

    private @NotNull ResolvedNode resolvePath(
            @NotNull VirtualPath path,
            boolean followFinalLink,
            @Nullable VfsEntrySnapshot.Kind finalExpectedKind
    ) {
        return resolvePath(path, followFinalLink, finalExpectedKind, new ArrayDeque<>(), new LinkedHashSet<>());
    }

    private @NotNull ResolvedNode resolvePath(
            @NotNull VirtualPath path,
            boolean followFinalLink,
            @Nullable VfsEntrySnapshot.Kind finalExpectedKind,
            @NotNull ArrayDeque<String> linkStack,
            @NotNull LinkedHashSet<String> activeLinks
    ) {
        if (path.isRoot()) {
            return new ResolvedNode(root, false);
        }
        return resolveFromDirectory(root, path, 0, followFinalLink, finalExpectedKind, linkStack, activeLinks);
    }

    @SuppressWarnings("SameParameterValue")
    private @NotNull ResolvedNode resolveFromDirectory(
            @NotNull DirectoryNode directoryNode,
            @NotNull VirtualPath surfacePath,
            int startIndex,
            boolean followFinalLink,
            @Nullable VfsEntrySnapshot.Kind finalExpectedKind,
            @NotNull ArrayDeque<String> linkStack,
            @NotNull LinkedHashSet<String> activeLinks
    ) {
        var current = directoryNode;
        // Intermediate virtual links behave like directory aliases, while final-link following
        // stays opt-in so metadata APIs can still observe the link node itself.
        for (var i = startIndex; i < surfacePath.segments().size(); i++) {
            var segment = surfacePath.segments().get(i);
            var existing = current.child(segment);
            var currentPath = surfacePath.prefixText(i + 1);
            var isFinal = i == surfacePath.segments().size() - 1;
            if (existing == null) {
                throw pathNotFound(surfacePath.text());
            }
            switch (existing) {
                case DirectoryNode nestedDirectory -> {
                    if (isFinal) {
                        return new ResolvedNode(nestedDirectory, false);
                    }
                    current = nestedDirectory;
                }
                case FileNode fileNode -> {
                    if (isFinal) {
                        return new ResolvedNode(fileNode, false);
                    }
                    throw typeMismatch(currentPath, "file", VfsEntrySnapshot.Kind.DIRECTORY);
                }
                case LinkNode linkNode -> {
                    if (isFinal && !followFinalLink) {
                        return new ResolvedNode(linkNode, false);
                    }
                    if (linkNode.linkKind() == VfsEntrySnapshot.LinkKind.LOCAL) {
                        if (isFinal && finalExpectedKind == null) {
                            return new ResolvedNode(linkNode, false);
                        }
                        throw typeMismatch(
                                currentPath,
                                nodeLabel(linkNode),
                                isFinal ? Objects.requireNonNull(finalExpectedKind) : VfsEntrySnapshot.Kind.DIRECTORY
                        );
                    }
                    var resolvedTarget = resolveLinkTarget(currentPath, linkNode, linkStack, activeLinks);
                    if (isFinal) {
                        return new ResolvedNode(resolvedTarget.node(), true);
                    }
                    current = requireDirectoryTarget(currentPath, resolvedTarget);
                }
            }
        }
        throw new IllegalStateException("Failed to resolve path: " + surfacePath.text());
    }

    private @NotNull ResolvedNode resolveLinkTarget(
            @NotNull String surfaceLinkPath,
            @NotNull LinkNode linkNode,
            @NotNull ArrayDeque<String> linkStack,
            @NotNull LinkedHashSet<String> activeLinks
    ) {
        // Cycle detection is keyed by the surfaced link path so aliases such as `/src-link` and
        // `/other-link` can be reported exactly as the RPC caller observed them.
        if (!activeLinks.add(surfaceLinkPath)) {
            throw linkCycle(linkStack, surfaceLinkPath);
        }
        linkStack.addLast(surfaceLinkPath);
        try {
            return resolvePath(linkNode.requireVirtualTarget(), true, null, linkStack, activeLinks);
        } catch (ApiPathNotFoundException _) {
            throw brokenLink(surfaceLinkPath, linkNode.target());
        } finally {
            linkStack.removeLast();
            activeLinks.remove(surfaceLinkPath);
        }
    }

    private @NotNull DirectoryNode requireDirectoryTarget(
            @NotNull String surfacePath,
            @NotNull ResolvedNode resolvedTarget
    ) {
        return switch (resolvedTarget.node()) {
            case DirectoryNode directoryNode -> directoryNode;
            case FileNode _ -> throw resolvedTypeMismatch(surfacePath, "file", VfsEntrySnapshot.Kind.DIRECTORY);
            case LinkNode linkNode -> throw resolvedTypeMismatch(
                    surfacePath,
                    nodeLabel(linkNode),
                    VfsEntrySnapshot.Kind.DIRECTORY
            );
        };
    }

    private @NotNull List<VfsEntrySnapshot> snapshotChildren(
            @NotNull VirtualPath directoryPath,
            @NotNull DirectoryNode directoryNode
    ) {
        var entries = new ArrayList<VfsEntrySnapshot>(directoryNode.childCount());
        for (var childEntry : directoryNode.children()) {
            var childPath = directoryPath.child(childEntry.getKey());
            entries.add(snapshotNode(childPath, childEntry.getValue()));
        }
        return entries;
    }

    private @NotNull VfsEntrySnapshot snapshotNode(@NotNull VirtualPath path, @NotNull VfsNode node) {
        return switch (node) {
            case DirectoryNode directoryNode -> directoryNode.snapshot(path);
            case FileNode fileNode -> fileNode.snapshot(path);
            case LinkNode linkNode -> linkNode.snapshot(path, inspectLink(path, linkNode).brokenReason());
        };
    }

    private @NotNull LinkInspection inspectLink(@NotNull VirtualPath path, @NotNull LinkNode linkNode) {
        if (linkNode.linkKind() == VfsEntrySnapshot.LinkKind.LOCAL) {
            return new LinkInspection(null);
        }
        try {
            resolveLinkTarget(path.text(), linkNode, new ArrayDeque<>(), new LinkedHashSet<>());
            return new LinkInspection(null);
        } catch (ApiBrokenLinkException _) {
            return new LinkInspection(VfsEntrySnapshot.BrokenReason.MISSING_TARGET);
        } catch (ApiLinkCycleException _) {
            return new LinkInspection(VfsEntrySnapshot.BrokenReason.CYCLE);
        }
    }

    /// Virtual links participate in source discovery, but only surfaced `.gd`/`.gd3` files are
    /// compile sources. Duplicate surfaced aliases of the same backing file are collapsed so one
    /// file node cannot be compiled twice in a single module pass.
    private void collectCompileSources(
            @NotNull VirtualPath directoryPath,
            @NotNull DirectoryNode directoryNode,
            @NotNull IdentityHashMap<FileNode, SourceSnapshot> sourcesByFile,
            @NotNull ArrayDeque<String> linkStack,
            @NotNull LinkedHashSet<String> activeLinks
    ) {
        for (var childEntry : directoryNode.children()) {
            collectCompileEntry(
                    directoryPath.child(childEntry.getKey()),
                    childEntry.getValue(),
                    sourcesByFile,
                    linkStack,
                    activeLinks
            );
        }
    }

    private void collectCompileEntry(
            @NotNull VirtualPath surfacePath,
            @NotNull VfsNode node,
            @NotNull IdentityHashMap<FileNode, SourceSnapshot> sourcesByFile,
            @NotNull ArrayDeque<String> linkStack,
            @NotNull LinkedHashSet<String> activeLinks
    ) {
        switch (node) {
            case DirectoryNode directoryNode ->
                    collectCompileSources(surfacePath, directoryNode, sourcesByFile, linkStack, activeLinks);
            case FileNode fileNode -> rememberSource(surfacePath, fileNode, sourcesByFile);
            case LinkNode linkNode -> {
                if (linkNode.linkKind() == VfsEntrySnapshot.LinkKind.LOCAL) {
                    return;
                }
                var resolvedTarget = resolveLinkTarget(surfacePath.text(), linkNode, linkStack, activeLinks);
                switch (resolvedTarget.node()) {
                    case DirectoryNode directoryNode ->
                            collectCompileSources(surfacePath, directoryNode, sourcesByFile, linkStack, activeLinks);
                    case FileNode fileNode -> rememberSource(surfacePath, fileNode, sourcesByFile);
                    case LinkNode nestedLink -> throw new IllegalStateException(
                            "Virtual link '" + surfacePath.text() + "' unexpectedly resolved to link '" + nestedLink.target() + "'"
                    );
                }
            }
        }
    }

    private void rememberSource(
            @NotNull VirtualPath surfacePath,
            @NotNull FileNode fileNode,
            @NotNull IdentityHashMap<FileNode, SourceSnapshot> sourcesByFile
    ) {
        if (!isSourceFileName(surfacePath.name())) {
            return;
        }
        var candidate = new SourceSnapshot(
                surfacePath.text(),
                fileNode.displayPath(),
                logicalPathFor(surfacePath),
                fileNode.content()
        );
        var existing = sourcesByFile.get(fileNode);
        if (existing == null || candidate.virtualPath().compareTo(existing.virtualPath()) < 0) {
            sourcesByFile.put(fileNode, candidate);
        }
    }

    /// Compile sources carry a `.gd` or `.gd3` extension; every other file stays an ordinary
    /// module file. Same-basename pairs like `a.gd`/`a.gd3` are both collected here — a duplicate
    /// default class name is diagnosed by the frontend, not by the API layer.
    private static boolean isSourceFileName(@NotNull String fileName) {
        return fileName.endsWith(".gd") || fileName.endsWith(".gd3");
    }

    private @NotNull String resolveDisplayPath(
            @NotNull VirtualPath surfacePath,
            @Nullable String displayPath,
            @Nullable VfsNode existingNode
    ) {
        if (displayPath != null) {
            return normalizeDisplayPath(displayPath);
        }
        return existingNode instanceof FileNode fileNode ? fileNode.displayPath() : surfacePath.text();
    }

    /// Compiler-facing paths are internal anchors only. They stay detached from caller-facing
    /// `displayPath` strings so labels such as `res://player.gd` never need to masquerade as a
    /// host-native `Path`.
    private @NotNull Path logicalPathFor(@NotNull VirtualPath sourcePath) {
        return Path.of("vfs", moduleId).resolve(sourcePath.text().substring(1));
    }

    private @NotNull String normalizeDisplayPath(@NotNull String displayPath) {
        return StringUtil.requireTrimmedNonBlank(displayPath, "displayPath");
    }

    private void validateManagedOutputDirectories(@NotNull VirtualPath mountRoot) {
        resolveExistingOutputDirectory(mountRoot, true);
        resolveExistingOutputDirectory(mountRoot.child(GENERATED_OUTPUT_DIR), true);
        resolveExistingOutputDirectory(mountRoot.child(ARTIFACT_OUTPUT_DIR), true);
    }

    private void clearManagedOutputDirectories(@NotNull VirtualPath mountRoot, boolean strict) {
        var rootDirectory = resolveExistingOutputDirectory(mountRoot, strict);
        if (rootDirectory == null) {
            return;
        }
        deleteManagedOutputDirectory(mountRoot.child(GENERATED_OUTPUT_DIR), strict);
        deleteManagedOutputDirectory(mountRoot.child(ARTIFACT_OUTPUT_DIR), strict);
        if (!mountRoot.isRoot() && !rootDirectory.hasChildren()) {
            deletePath(mountRoot, false);
        }
    }

    private void deleteManagedOutputDirectory(@NotNull VirtualPath directoryPath, boolean strict) {
        var existing = resolveExistingOutputNode(directoryPath);
        if (existing == null) {
            return;
        }
        switch (existing) {
            case DirectoryNode _ -> deletePath(directoryPath, true);
            case FileNode _, LinkNode _ -> {
                if (strict) {
                    throw typeMismatch(directoryPath.text(), nodeLabel(existing), VfsEntrySnapshot.Kind.DIRECTORY);
                }
            }
        }
    }

    private @Nullable DirectoryNode resolveExistingOutputDirectory(@NotNull VirtualPath path, boolean strict) {
        var existing = resolveExistingOutputNode(path);
        if (existing == null) {
            return null;
        }
        return switch (existing) {
            case DirectoryNode directoryNode -> directoryNode;
            case FileNode _, LinkNode _ -> {
                if (strict) {
                    throw typeMismatch(path.text(), nodeLabel(existing), VfsEntrySnapshot.Kind.DIRECTORY);
                }
                yield null;
            }
        };
    }

    private @Nullable VfsNode resolveExistingOutputNode(@NotNull VirtualPath path) {
        try {
            return resolvePath(path, false, null).node();
        } catch (ApiPathNotFoundException _) {
            return null;
        }
    }

    private @NotNull ApiPathNotFoundException pathNotFound(@NotNull String pathText) {
        return new ApiPathNotFoundException("Path '" + pathText + "' does not exist in module '" + moduleId + "'");
    }

    private @NotNull ApiBrokenLinkException brokenLink(@NotNull String pathText, @NotNull String targetText) {
        return new ApiBrokenLinkException(
                "Virtual link '" + pathText + "' in module '" + moduleId + "' points to missing path '" + targetText + "'"
        );
    }

    private @NotNull ApiLinkCycleException linkCycle(
            @NotNull ArrayDeque<String> linkStack,
            @NotNull String repeatedPath
    ) {
        var stackView = new ArrayList<>(linkStack);
        var cycleStart = stackView.indexOf(repeatedPath);
        if (cycleStart < 0) {
            cycleStart = 0;
        }
        var cycle = new ArrayList<>(stackView.subList(cycleStart, stackView.size()));
        cycle.add(repeatedPath);
        return new ApiLinkCycleException(
                "Virtual link cycle detected in module '" + moduleId + "': " + String.join(" -> ", cycle)
        );
    }

    private @NotNull ApiEntryTypeMismatchException typeMismatch(
            @NotNull String pathText,
            @NotNull String actualLabel,
            @NotNull VfsEntrySnapshot.Kind expectedKind
    ) {
        return new ApiEntryTypeMismatchException(
                "Path '" + pathText + "' in module '" + moduleId + "' is a "
                        + actualLabel
                        + ", not a "
                        + kindLabel(expectedKind)
        );
    }

    private @NotNull ApiEntryTypeMismatchException contentTypeMismatch(
            @NotNull String pathText,
            @NotNull String actualLabel,
            @NotNull VfsEntrySnapshot.Kind expectedKind,
            boolean resolvedThroughFinalLink
    ) {
        return resolvedThroughFinalLink
                ? resolvedTypeMismatch(pathText, actualLabel, expectedKind)
                : typeMismatch(pathText, actualLabel, expectedKind);
    }

    private @NotNull ApiEntryTypeMismatchException resolvedTypeMismatch(
            @NotNull String pathText,
            @NotNull String actualLabel,
            @NotNull VfsEntrySnapshot.Kind expectedKind
    ) {
        return new ApiEntryTypeMismatchException(
                "Path '" + pathText + "' in module '" + moduleId + "' resolves to a "
                        + actualLabel
                        + ", not a "
                        + kindLabel(expectedKind)
        );
    }

    private @NotNull String nodeLabel(@NotNull VfsNode node) {
        return switch (node) {
            case DirectoryNode _ -> "directory";
            case FileNode _ -> "file";
            case LinkNode linkNode -> switch (linkNode.linkKind()) {
                case VIRTUAL -> "virtual link";
                case LOCAL -> "local link";
            };
        };
    }

    private @NotNull String kindLabel(@NotNull VfsEntrySnapshot.Kind kind) {
        return switch (kind) {
            case DIRECTORY -> "directory";
            case FILE -> "file";
            case LINK -> "link";
        };
    }

    private record ResolvedNode(@NotNull VfsNode node, boolean finalLinkResolved) {
    }

    private record LinkInspection(@Nullable VfsEntrySnapshot.BrokenReason brokenReason) {
    }

    record CompileRequest(
            @NotNull String moduleId,
            @NotNull String moduleName,
            @NotNull CompileOptions compileOptions,
            @NotNull Map<String, String> topLevelCanonicalNameMap,
            @NotNull List<SourceSnapshot> sourceSnapshots,
            @Nullable CompileRequestFailure failure
    ) {
        CompileRequest {
            Objects.requireNonNull(moduleId, "moduleId must not be null");
            Objects.requireNonNull(moduleName, "moduleName must not be null");
            Objects.requireNonNull(compileOptions, "compileOptions must not be null");
            topLevelCanonicalNameMap = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(
                    topLevelCanonicalNameMap,
                    "topLevelCanonicalNameMap must not be null"
            )));
            sourceSnapshots = List.copyOf(Objects.requireNonNull(sourceSnapshots, "sourceSnapshots must not be null"));
        }
    }

    record SourceSnapshot(
            @NotNull String virtualPath,
            @NotNull String displayPath,
            @NotNull Path logicalPath,
            @NotNull String source
    ) {
        SourceSnapshot {
            virtualPath = VirtualPath.parse(virtualPath).text();
            displayPath = StringUtil.requireTrimmedNonBlank(displayPath, "displayPath");
            Objects.requireNonNull(logicalPath, "logicalPath must not be null");
            Objects.requireNonNull(source, "source must not be null");
        }
    }

    record CompileRequestFailure(
            @NotNull CompileResult.Outcome outcome,
            @NotNull String message
    ) {
        CompileRequestFailure {
            Objects.requireNonNull(outcome, "outcome must not be null");
            message = StringUtil.requireTrimmedNonBlank(message, "message");
        }
    }
}
