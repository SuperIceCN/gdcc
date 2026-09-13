package gd.script.gdcc.backend.c.build;

import gd.script.gdcc.util.ProcessUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class ZigCcCompiler implements CCompiler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ZigCcCompiler.class);
    private static final String PROJECT_CACHE_DIR_NAME = "compiler-cache";
    private static final String SHARED_CACHE_DIR_NAME = "shared-compiler-cache";
    private static final String SHARED_CACHE_ENV = "GDCC_SHARED_C_COMPILER_CACHE";
    private static final String MSVC_ABI_SUFFIX = "-windows-msvc";
    private static final String GNU_ABI_SUFFIX = "-windows-gnu";
    private static final Duration OUTPUT_READER_JOIN_TIMEOUT = Duration.ofSeconds(1);

    @Override
    public CCompileResult compile(@NotNull Path projectDir, @NotNull List<Path> includeDirs, @NotNull List<Path> cFiles, @NotNull String outputBaseName, @NotNull COptimizationLevel optimizationLevel, @NotNull TargetPlatform targetPlatform) {
        var zig = ZigUtil.findZig();
        if (zig == null) {
            return new CCompileResult(false, "Zig executable not found on PATH or known locations", List.of());
        }

        if (cFiles.isEmpty()) {
            return new CCompileResult(false, "No C files to compile", List.of());
        }

        // Build command: zig cc -shared -I<includeDir> -o <output> <cFiles...>
        var outName = targetPlatform.sharedLibraryFileName(outputBaseName);

        var outputPath = projectDir.resolve(outName).toAbsolutePath();
        var cachePath = resolveCompilerCacheRoot(projectDir);
        var targetResolution = resolveZigTarget(targetPlatform);

        var cmd = new ArrayList<String>();
        cmd.add(zig.toString());
        cmd.add("cc");
        cmd.add("-target");
        cmd.add(targetResolution.zigTarget());
        cmd.add("-std=c23");
        cmd.add("-shared");
        if (!targetResolution.abiSubstituted()) {
            // zig's LTO link for windows-gnu fails to pull in libmingwex/compiler-rt symbols
            // (undefined long-double math/wmem* at lld-link), so the substituted cross build
            // links without LTO; declared targets keep LTO unchanged.
            cmd.add("-flto");
        }
        cmd.add("-Wno-macro-redefined");
        cmd.add("-Wno-pointer-sign");

        // optimization mapping
        switch (optimizationLevel) {
            case DEBUG -> cmd.add("-O0");
            case RELEASE -> cmd.add("-O2");
        }

        // primary include dir (use -I<path> form)
        for (var inc : includeDirs) {
            cmd.add("-I" + inc.toAbsolutePath());
        }

        cmd.add("-o");
        cmd.add(outputPath.toString());
        for (var f : cFiles) cmd.add(f.toAbsolutePath().toString());

        try {
            var pb = new ProcessBuilder(cmd);
            pb.directory(projectDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("ZIG_CACHE_DIR", cachePath.resolve("local").toString());
            pb.environment().put("ZIG_GLOBAL_CACHE_DIR", cachePath.resolve("global").toString());
            var p = pb.start();
            // Drain Zig output on a companion virtual thread so a verbose compiler cannot fill the
            // process pipe and block shutdown. The compile thread stays interruptible; cancellation
            // destroys the Zig process through ProcessUtil and interrupts the output reader.
            var outputBytes = new ByteArrayOutputStream();
            var outputFailure = new AtomicReference<IOException>();
            var outputReader = Thread.ofVirtual()
                    .name("gdcc-zig-output")
                    .start(() -> {
                        try (var input = p.getInputStream()) {
                            input.transferTo(outputBytes);
                        } catch (IOException exception) {
                            outputFailure.set(exception);
                        }
                    });
            var interrupted = false;
            int exit;
            try {
                exit = ProcessUtil.waitForInterruptibly(p, outputReader);
            } catch (InterruptedException exception) {
                interrupted = true;
                throw exception;
            } finally {
                if (interrupted) {
                    outputReader.interrupt();
                    ProcessUtil.joinThreadAfterInterrupt(outputReader, OUTPUT_READER_JOIN_TIMEOUT);
                } else {
                    outputReader.join();
                }
            }
            var readFailure = outputFailure.get();
            if (readFailure != null) {
                throw readFailure;
            }
            var out = outputBytes.toString(StandardCharsets.UTF_8);
            boolean success = exit == 0 && Files.exists(outputPath);
            var artifacts = new ArrayList<Path>(3);
            if (success) {
                artifacts.add(outputPath);
                if (targetPlatform.isWindows()) {
                    var pdbPath = projectDir.resolve(outputBaseName + ".pdb").toAbsolutePath();
                    if (Files.exists(pdbPath)) {
                        artifacts.add(pdbPath);
                    }
                }
            }
            if (!success) {
                var cmdStr = String.join(" ", cmd);
                out = "Command: " + cmdStr + "\n" + out;
            }
            return new CCompileResult(success, out, artifacts);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CCompileResult(false, "Failed to run zig: interrupted", List.of());
        } catch (IOException e) {
            return new CCompileResult(false, "Failed to run zig: " + e.getMessage(), List.of());
        }
    }

    /// Resolves the zig target triple actually passed to `zig cc`. The declared
    /// `TargetPlatform.zigTarget` is passed through untouched except in one case: on a
    /// non-Windows host, `-windows-msvc` cannot work because zig only provides libc for the
    /// MinGW ABI (`-windows-gnu`), never for MSVC (that requires an installed Windows SDK +
    /// MSVC libraries). Cross-compiling Windows builds from Linux/macOS therefore substitutes
    /// the GNU ABI and warns — the produced DLL stays a valid self-contained GDExtension (the
    /// GDExtension boundary is a plain C ABI), matching the zig launcher's own ABI choice.
    static @NotNull ZigTargetResolution resolveZigTarget(@NotNull TargetPlatform targetPlatform) {
        return resolveZigTarget(targetPlatform, isWindowsHost());
    }

    static @NotNull ZigTargetResolution resolveZigTarget(@NotNull TargetPlatform targetPlatform, boolean windowsHost) {
        var zigTarget = Objects.requireNonNull(targetPlatform, "targetPlatform must not be null").zigTarget;
        if (!windowsHost && zigTarget.endsWith(MSVC_ABI_SUFFIX)) {
            var substituted = zigTarget.substring(0, zigTarget.length() - MSVC_ABI_SUFFIX.length())
                    + GNU_ABI_SUFFIX;
            LOGGER.warn("zig cannot provide libc for {} on a non-Windows host; substituting {} "
                    + "(MinGW ABI, matching the zig launcher, LTO disabled for this build). "
                    + "The GDExtension DLL remains loadable.", zigTarget, substituted);
            return new ZigTargetResolution(substituted, true);
        }
        return new ZigTargetResolution(zigTarget, false);
    }

    /// `abiSubstituted` marks the non-Windows-host MinGW substitution, which also disables LTO.
    record ZigTargetResolution(@NotNull String zigTarget, boolean abiSubstituted) {
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static @NotNull Path resolveCompilerCacheRoot(@NotNull Path projectDir) {
        return resolveCompilerCacheRoot(projectDir, System.getenv());
    }

    static @NotNull Path resolveCompilerCacheRoot(@NotNull Path projectDir, @NotNull Map<String, String> environment) {
        var normalizedProjectDir = projectDir.toAbsolutePath().normalize();
        var envCacheValue = environment.get(SHARED_CACHE_ENV);
        if (envCacheValue != null && !envCacheValue.isBlank()) {
            try {
                var envCacheDir = Path.of(envCacheValue).toAbsolutePath().normalize();
                Files.createDirectories(envCacheDir);
                if (Files.isDirectory(envCacheDir)) {
                    return envCacheDir;
                }
            } catch (IOException | InvalidPathException exception) {
                // Fall back to the project-location cache rule below.
            }
        }

        var projectParent = normalizedProjectDir.getParent();
        if (projectParent == null) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }

        var sharedCacheDir = projectParent.resolve(SHARED_CACHE_DIR_NAME);
        if (Files.exists(sharedCacheDir) && !Files.isDirectory(sharedCacheDir)) {
            return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
        }
        if (Files.isDirectory(sharedCacheDir)) {
            return sharedCacheDir.toAbsolutePath().normalize();
        }

        return normalizedProjectDir.resolve(PROJECT_CACHE_DIR_NAME);
    }
}
