package gd.script.gdcc.backend.c.build;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public final class GdextensionMetadataFile {
    public static final @NotNull String ENTRY_SYMBOL = "gdextension_entry";
    public static final @NotNull String COMPATIBILITY_MINIMUM = "4.5";

    private GdextensionMetadataFile() {
    }

    public static @NotNull Path write(
            @NotNull Path metadataPath,
            @NotNull Path libraryPath,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) throws IOException {
        var metadataDir = Objects.requireNonNull(metadataPath, "metadataPath must not be null")
                .toAbsolutePath()
                .normalize()
                .getParent();
        if (metadataDir != null) {
            Files.createDirectories(metadataDir);
        }
        var libraryText = metadataDir == null
                ? pathText(libraryPath)
                : pathText(metadataDir.relativize(libraryPath.toAbsolutePath().normalize()));
        Files.writeString(
                metadataPath,
                render(libraryText, optimizationLevel, targetPlatform),
                StandardCharsets.UTF_8
        );
        return metadataPath;
    }

    public static @NotNull String render(
            @NotNull String libraryPath,
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) {
        var key = libraryKey(optimizationLevel, targetPlatform);
        var compatibilityKeys = compatibilityLibraryKeys(targetPlatform);
        var libraries = new StringBuilder();
        libraries.append(key).append(" = \"").append(Objects.requireNonNull(libraryPath, "libraryPath must not be null")).append("\"");
        for (var compatibilityKey : compatibilityKeys) {
            if (compatibilityKey.equals(key)) {
                continue;
            }
            libraries.append("\n").append(compatibilityKey).append(" = \"").append(libraryPath).append("\"");
        }
        return """
                [configuration]
                
                entry_symbol = "%s"
                compatibility_minimum = "%s"
                
                [libraries]
                %s
                """.formatted(
                ENTRY_SYMBOL,
                COMPATIBILITY_MINIMUM,
                libraries
        );
    }

    public static @NotNull String libraryKey(
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) {
        return platformFeature(targetPlatform)
                + "."
                + Objects.requireNonNull(optimizationLevel, "optimizationLevel must not be null")
                .name()
                .toLowerCase(Locale.ROOT);
    }

    /// Renders metadata with one arch-qualified `[libraries]` entry per platform, used when a
    /// single install ships binaries for several platforms or architectures (an unqualified
    /// `libraryKey` would collide for two builds of the same platform family, e.g. Linux
    /// x86_64 vs AArch64). Every platform also gets a release/debug compatibility alias
    /// pointing at the same binary, mirroring `render(...)`'s compatibility keys.
    public static @NotNull String renderMultiPlatform(
            @NotNull java.util.Map<TargetPlatform, String> libraryPathByPlatform,
            @NotNull COptimizationLevel optimizationLevel
    ) {
        if (libraryPathByPlatform.isEmpty()) {
            throw new IllegalArgumentException("libraryPathByPlatform must not be empty");
        }
        var compatibilityLevel = optimizationLevel == COptimizationLevel.DEBUG
                ? COptimizationLevel.RELEASE
                : COptimizationLevel.DEBUG;
        var libraries = new StringBuilder();
        for (var entry : libraryPathByPlatform.entrySet()) {
            var libraryPath = Objects.requireNonNull(entry.getValue(), "libraryPath must not be null");
            if (!libraries.isEmpty()) {
                libraries.append("\n");
            }
            libraries.append(archQualifiedLibraryKey(optimizationLevel, entry.getKey()))
                    .append(" = \"").append(libraryPath).append("\"")
                    .append("\n")
                    .append(archQualifiedLibraryKey(compatibilityLevel, entry.getKey()))
                    .append(" = \"").append(libraryPath).append("\"");
        }
        return """
                [configuration]

                entry_symbol = "%s"
                compatibility_minimum = "%s"

                [libraries]
                %s
                """.formatted(
                ENTRY_SYMBOL,
                COMPATIBILITY_MINIMUM,
                libraries
        );
    }

    /// Arch-qualified library key (`linux.debug.arm64`) using Godot's architecture names —
    /// which differ from gdcc's `HardwareArchitecture` spellings (`aarch64` → `arm64`).
    public static @NotNull String archQualifiedLibraryKey(
            @NotNull COptimizationLevel optimizationLevel,
            @NotNull TargetPlatform targetPlatform
    ) {
        return libraryKey(optimizationLevel, targetPlatform)
                + "."
                + godotArchitectureName(targetPlatform);
    }

    private static @NotNull String godotArchitectureName(@NotNull TargetPlatform targetPlatform) {
        return switch (targetPlatform.architecture) {
            case X86_64 -> "x86_64";
            case AARCH64 -> "arm64";
            case RISCV64 -> "rv64";
            case WASM32 -> "wasm32";
        };
    }

    private static @NotNull String platformFeature(@NotNull TargetPlatform targetPlatform) {
        return switch (Objects.requireNonNull(targetPlatform, "targetPlatform must not be null")) {
            case WINDOWS_X86_64, WINDOWS_AARCH64 -> "windows";
            case LINUX_X86_64, LINUX_AARCH64, LINUX_RISCV64 -> "linux";
            case ANDROID_X86_64, ANDROID_AARCH64 -> "android";
            case WEB_WASM32 -> "web";
        };
    }

    private static @NotNull java.util.List<String> compatibilityLibraryKeys(@NotNull TargetPlatform targetPlatform) {
        var feature = platformFeature(targetPlatform);
        return java.util.List.of(
                feature + ".release",
                feature + ".debug"
        );
    }

    private static @NotNull String pathText(@NotNull Path path) {
        return path.toString().replace('\\', '/');
    }
}
