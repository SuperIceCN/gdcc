package gd.script.gdcc.rpc;

import gd.script.gdcc.api.API;
import gd.script.gdcc.api.CompileOptions;
import gd.script.gdcc.api.CompileResult;
import gd.script.gdcc.backend.c.build.COptimizationLevel;
import gd.script.gdcc.backend.c.build.GdextensionMetadataFile;
import gd.script.gdcc.backend.c.build.TargetPlatform;
import gd.script.gdcc.backend.c.build.ZigUtil;
import gd.script.gdcc.enums.GodotVersion;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// Installation helper for the editor-addon bootstrap flow, plus a manual `main` entry that
/// installs the compiled client extension **in place** into `src/editor_addon/addons/gdcc/`
/// so the addon project can be opened in the Godot editor for manual plugin testing.
///
/// The built library is named `gdcc_for_editor` (the module id), not after the RPC client:
/// the addon will accumulate more editor-facing features beyond JSON-RPC. The source's
/// `class_name GdccRpcClient` is unaffected by the library file name.
///
/// `main` argument contract: no arguments builds the host platform only; any arguments are
/// `TargetPlatform` names (dashes/underscores and case are normalized) and every listed
/// platform is built and registered into one arch-qualified `.gdextension`.
///
/// Deliberately separate from `GodotGdextensionTestRunner`: that runner rewrites `main.tscn`
/// and binds the `test_project`/`root.gd` stop-signal contract, while the bootstrap flow drives
/// a copied `src/editor_addon` project through `-s` script mode. The generic responsibilities
/// live here: project copying (excluding `.godot/` caches), client-library compilation, and
/// GDExtension installation (`bin/` artifacts + `.gdextension` metadata, with
/// `.godot/extension_list.cfg` only where a plain runtime launch needs it).
public final class EditorAddonProjectInstaller {
    static final String MODULE_ID = "gdcc_for_editor";
    static final String EXTENSION_SUB_DIR = "addons/gdcc";
    static final String EXTENSION_FILE_NAME = "gdcc_for_editor.gdextension";
    private static final Path ADDON_PROJECT_DIR = Path.of("src/editor_addon");
    private static final Path CLIENT_SOURCE_PATH = ADDON_PROJECT_DIR
            .resolve(EXTENSION_SUB_DIR)
            .resolve("gdcc_rpc_client.gd3");
    private static final Path BUILD_ROOT = Path.of("tmp/editor_addon_build");
    private static final long COMPILE_TIMEOUT_MINUTES = 5;

    private EditorAddonProjectInstaller() {
    }

    /// Manual entry point: compiles `gdcc_rpc_client.gd3` natively and installs it as a
    /// loadable GDExtension under `src/editor_addon/addons/gdcc/` (`bin/` +
    /// `gdcc_for_editor.gdextension`). Run it from the repository root (the gradle tasks
    /// `buildAddonNative` / `buildAddonAllPlatform` wrap this); requires zig. With no
    /// arguments only the host platform is built; otherwise each argument names a
    /// `TargetPlatform` to build. The Godot editor discovers the `.gdextension` through its
    /// own filesystem scan, so no `extension_list.cfg` is written into the source tree's
    /// `.godot/` cache.
    static void main(String[] args) throws Exception {
        if (!Files.exists(CLIENT_SOURCE_PATH)) {
            System.err.println("Client source not found at " + CLIENT_SOURCE_PATH.toAbsolutePath()
                    + " — run this from the repository root.");
            System.exit(1);
        }
        if (ZigUtil.findZig() == null) {
            System.err.println("zig not found on PATH or in known locations; cannot build the native library.");
            System.exit(1);
        }
        if (args.length == 0) {
            buildNative();
        } else {
            buildPlatforms(parsePlatforms(args));
        }
        System.out.println("Done. Open " + ADDON_PROJECT_DIR.toAbsolutePath()
                + " in the Godot editor and enable the GDCC plugin"
                + " (Project > Project Settings > Plugins).");
    }

    /// Host-platform build: one unqualified-key `.gdextension` (loads for any arch of the
    /// host platform family).
    private static void buildNative() throws IOException {
        var targetPlatform = TargetPlatform.getNativePlatform();
        System.out.println("Compiling " + CLIENT_SOURCE_PATH + " for the host platform ("
                + targetPlatform + ") ...");
        var result = compileClientLibrary(buildDirFor(targetPlatform), CLIENT_SOURCE_PATH, targetPlatform);
        requireBuildSuccess(result, targetPlatform);
        installExtension(
                ADDON_PROJECT_DIR, EXTENSION_SUB_DIR, result.artifacts(), EXTENSION_FILE_NAME,
                COptimizationLevel.DEBUG, targetPlatform, false);
        printInstalled(result.artifacts());
    }

    /// Explicit platform set: every platform is cross-compiled by zig and registered with
    /// arch-qualified keys in a single `.gdextension`.
    private static void buildPlatforms(List<TargetPlatform> platforms) throws IOException {
        var artifactsByPlatform = new LinkedHashMap<TargetPlatform, List<Path>>();
        for (var platform : platforms) {
            System.out.println("Compiling " + CLIENT_SOURCE_PATH + " for " + platform + " ...");
            var result = compileClientLibrary(buildDirFor(platform), CLIENT_SOURCE_PATH, platform);
            requireBuildSuccess(result, platform);
            artifactsByPlatform.put(platform, result.artifacts());
        }
        installMultiPlatformExtension(
                ADDON_PROJECT_DIR, EXTENSION_SUB_DIR, artifactsByPlatform,
                EXTENSION_FILE_NAME, COptimizationLevel.DEBUG, false);
        for (var artifacts : artifactsByPlatform.values()) {
            printInstalled(artifacts);
        }
    }

    private static @org.jetbrains.annotations.NotNull List<TargetPlatform> parsePlatforms(String[] args) {
        var platforms = new LinkedHashSet<TargetPlatform>();
        for (var arg : args) {
            var normalized = arg.trim().replace('-', '_').toUpperCase(Locale.ROOT);
            try {
                platforms.add(TargetPlatform.valueOf(normalized));
            } catch (IllegalArgumentException exception) {
                System.err.println("Unknown target platform '" + arg + "'. Valid values: "
                        + Arrays.toString(TargetPlatform.values()));
                System.exit(1);
            }
        }
        return List.copyOf(platforms);
    }

    private static Path buildDirFor(TargetPlatform platform) {
        return BUILD_ROOT.resolve(platform.name().toLowerCase(Locale.ROOT));
    }

    private static void requireBuildSuccess(CompileResult result, TargetPlatform platform) {
        if (result.outcome() != CompileResult.Outcome.SUCCESS) {
            System.err.println("Client library build failed for " + platform + ": " + result.outcome()
                    + "\n" + result.failureMessage()
                    + "\nbuild log:\n" + result.buildLog());
            System.exit(1);
        }
    }

    private static void printInstalled(List<Path> artifacts) {
        for (var artifact : artifacts) {
            System.out.println("Installed: " + ADDON_PROJECT_DIR
                    .resolve(EXTENSION_SUB_DIR)
                    .resolve("bin")
                    .resolve(artifact.getFileName().toString()));
        }
    }

    /// Compiles `gdcc_rpc_client.gd3` into a native GDExtension library under `projectPath`
    /// (generated C files and the zig artifact land there; only the artifact is copied on
    /// installation). The module id fixes the artifact basename
    /// (`libgdcc_for_editor_debug_<arch>.so` on Linux).
    static CompileResult compileClientLibrary(
            Path projectPath,
            Path clientSourcePath,
            TargetPlatform targetPlatform
    ) throws IOException {
        try (var api = new API()) {
            api.createModule(MODULE_ID, "GDCC Editor Extension");
            api.setCompileOptions(MODULE_ID, new CompileOptions(
                    GodotVersion.V451, projectPath.toAbsolutePath(),
                    COptimizationLevel.DEBUG, targetPlatform,
                    false, CompileOptions.DEFAULT_OUTPUT_MOUNT_ROOT));
            api.putFile(MODULE_ID, "/src/gdcc_rpc_client.gd3",
                    Files.readString(clientSourcePath));
            var taskId = api.compile(MODULE_ID);
            var deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(COMPILE_TIMEOUT_MINUTES);
            while (System.nanoTime() < deadline) {
                var snapshot = api.getCompileTask(taskId);
                if (snapshot.completed()) {
                    return Objects.requireNonNull(snapshot.result());
                }
                try {
                    //noinspection BusyWait
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting for the client library build");
                }
            }
            throw new AssertionError("Client library build did not complete within the deadline");
        }
    }

    /// Recursively clears `targetDir` (when it already exists) and copies `sourceDir` into it.
    /// Every `.godot/` directory is skipped so a stale editor cache (imported textures, old
    /// extension lists) can never leak into the copy — the test installs its own fresh
    /// `extension_list.cfg` afterwards.
    static void copyProject(Path sourceDir, Path targetDir) throws IOException {
        if (Files.exists(targetDir)) {
            clearDirectory(targetDir);
        }
        Files.createDirectories(targetDir);
        try (var walk = Files.walk(sourceDir)) {
            for (var source : walk.toList()) {
                var relative = sourceDir.relativize(source);
                if (isInsideGodotCache(relative)) {
                    continue;
                }
                var target = targetDir.resolve(relative);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /// Root-level installation used by the bootstrap engine test's project copy: `bin/` and the
    /// `.gdextension` sit at the project root, and `.godot/extension_list.cfg` is written
    /// because the copy is launched through plain `-s` runtime mode without an editor scan.
    static void installExtension(
            Path projectDir,
            List<Path> artifacts,
            String extensionFileName,
            COptimizationLevel optimizationLevel,
            TargetPlatform targetPlatform
    ) throws IOException {
        installExtension(projectDir, "", artifacts, extensionFileName, optimizationLevel, targetPlatform, true);
    }

    /// Installs compiled native artifacts as a loadable GDExtension: copies them into `bin/`
    /// under the extension directory and writes the `.gdextension` metadata pointing at the
    /// single dynamic library among them.
    ///
    /// `extensionSubDir` is the extension directory relative to the project root (`""` for the
    /// root itself, `"addons/gdcc"` for the in-place addon install); resource paths inside the
    /// metadata are derived from it. `writeExtensionList` controls whether
    /// `.godot/extension_list.cfg` is (re)written — needed for plain runtime launches, harmful
    /// for the in-place install where the editor manages its own cache.
    static void installExtension(
            Path projectDir,
            String extensionSubDir,
            List<Path> artifacts,
            String extensionFileName,
            COptimizationLevel optimizationLevel,
            TargetPlatform targetPlatform,
            boolean writeExtensionList
    ) throws IOException {
        var resourcePrefix = "res://" + (extensionSubDir.isEmpty() ? "" : extensionSubDir + "/");
        var extensionDir = projectDir.resolve(extensionSubDir);
        var binDir = extensionDir.resolve("bin");
        Files.createDirectories(binDir);
        var library = copyArtifactsAndFindLibrary(artifacts, binDir, targetPlatform.toString());
        Files.writeString(
                extensionDir.resolve(extensionFileName),
                GdextensionMetadataFile.render(
                        resourcePrefix + "bin/" + library.getFileName(), optimizationLevel, targetPlatform),
                StandardCharsets.UTF_8
        );
        if (writeExtensionList) {
            writeExtensionListFile(projectDir, resourcePrefix + extensionFileName);
        }
    }

    /// Multi-platform variant of `installExtension`: copies every platform's artifacts into the
    /// same `bin/` and registers each platform's dynamic library with an arch-qualified key in
    /// one `.gdextension` (unqualified keys would collide across same-family architectures).
    static void installMultiPlatformExtension(
            Path projectDir,
            String extensionSubDir,
            LinkedHashMap<TargetPlatform, List<Path>> artifactsByPlatform,
            String extensionFileName,
            COptimizationLevel optimizationLevel,
            boolean writeExtensionList
    ) throws IOException {
        var resourcePrefix = "res://" + (extensionSubDir.isEmpty() ? "" : extensionSubDir + "/");
        var extensionDir = projectDir.resolve(extensionSubDir);
        var binDir = extensionDir.resolve("bin");
        Files.createDirectories(binDir);
        var libraryPathByPlatform = new LinkedHashMap<TargetPlatform, String>();
        for (var entry : artifactsByPlatform.entrySet()) {
            var library = copyArtifactsAndFindLibrary(entry.getValue(), binDir, entry.getKey().toString());
            libraryPathByPlatform.put(entry.getKey(), resourcePrefix + "bin/" + library.getFileName());
        }
        Files.writeString(
                extensionDir.resolve(extensionFileName),
                GdextensionMetadataFile.renderMultiPlatform(libraryPathByPlatform, optimizationLevel),
                StandardCharsets.UTF_8
        );
        if (writeExtensionList) {
            writeExtensionListFile(projectDir, resourcePrefix + extensionFileName);
        }
    }

    /// Copies the artifacts into `binDir` and returns the copied single dynamic library.
    /// Auxiliary artifacts (e.g. PDB files) ride along; exactly one loadable library per call
    /// is contractual.
    private static @org.jetbrains.annotations.NotNull Path copyArtifactsAndFindLibrary(
            List<Path> artifacts,
            Path binDir,
            String platformContext
    ) throws IOException {
        Path library = null;
        for (var artifact : artifacts) {
            if (!Files.exists(artifact)) {
                throw new IOException("Artifact not found: " + artifact);
            }
            var copied = binDir.resolve(artifact.getFileName().toString());
            Files.copy(artifact, copied, StandardCopyOption.REPLACE_EXISTING);
            if (isDynamicLibrary(copied.getFileName().toString())) {
                if (library != null) {
                    throw new IOException("Multiple dynamic library artifacts for " + platformContext
                            + ": " + library + " and " + copied);
                }
                library = copied;
            }
        }
        if (library == null) {
            throw new IOException("No dynamic library artifact for " + platformContext);
        }
        return library;
    }

    private static void writeExtensionListFile(Path projectDir, String extensionResourcePath) throws IOException {
        var extensionListPath = projectDir.resolve(".godot").resolve("extension_list.cfg");
        Files.createDirectories(extensionListPath.getParent());
        Files.writeString(extensionListPath, extensionResourcePath + "\n", StandardCharsets.UTF_8);
    }

    /// Returns whether the file name can be loaded through Godot's GDExtension library table.
    /// Shared with the bootstrap test, which asserts exactly one loadable library per build
    /// (auxiliary artifacts like PDB files may legally accompany it).
    static boolean isDynamicLibrary(String fileName) {
        return fileName.endsWith(".dll")
                || fileName.endsWith(".so")
                || fileName.endsWith(".dylib")
                || fileName.endsWith(".wasm");
    }

    private static boolean isInsideGodotCache(Path relativePath) {
        for (var part : relativePath) {
            if (part.toString().equals(".godot")) {
                return true;
            }
        }
        return false;
    }

    /// Deletes the directory content bottom-up, keeping the root directory itself.
    private static void clearDirectory(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(dir)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
