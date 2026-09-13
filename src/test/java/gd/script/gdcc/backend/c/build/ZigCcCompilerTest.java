package gd.script.gdcc.backend.c.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Pins the non-Windows-host ABI substitution: `TargetPlatform` keeps declaring
/// `-windows-msvc`, and `ZigCcCompiler` swaps it to `-windows-gnu` only off Windows hosts,
/// because zig cannot provide libc for MSVC targets there.
class ZigCcCompilerTest {
    @Test
    void msvcTargetsAreSubstitutedWithGnuOnNonWindowsHosts() {
        var x86 = ZigCcCompiler.resolveZigTarget(TargetPlatform.WINDOWS_X86_64, false);
        assertEquals("x86_64-windows-gnu", x86.zigTarget());
        assertEquals(true, x86.abiSubstituted());
        var arm64 = ZigCcCompiler.resolveZigTarget(TargetPlatform.WINDOWS_AARCH64, false);
        assertEquals("aarch64-windows-gnu", arm64.zigTarget());
        assertEquals(true, arm64.abiSubstituted());
    }

    @Test
    void msvcTargetsAreKeptUntouchedOnWindowsHosts() {
        var x86 = ZigCcCompiler.resolveZigTarget(TargetPlatform.WINDOWS_X86_64, true);
        assertEquals("x86_64-windows-msvc", x86.zigTarget());
        assertEquals(false, x86.abiSubstituted());
        var arm64 = ZigCcCompiler.resolveZigTarget(TargetPlatform.WINDOWS_AARCH64, true);
        assertEquals("aarch64-windows-msvc", arm64.zigTarget());
        assertEquals(false, arm64.abiSubstituted());
    }

    @Test
    void nonMsvcTargetsAreNeverSubstituted() {
        for (var platform : TargetPlatform.values()) {
            if (platform.zigTarget.endsWith("-windows-msvc")) {
                continue;
            }
            var offWindows = ZigCcCompiler.resolveZigTarget(platform, false);
            assertEquals(platform.zigTarget, offWindows.zigTarget(),
                    platform + " must pass through on non-Windows hosts");
            assertEquals(false, offWindows.abiSubstituted());
            var onWindows = ZigCcCompiler.resolveZigTarget(platform, true);
            assertEquals(platform.zigTarget, onWindows.zigTarget(),
                    platform + " must pass through on Windows hosts");
            assertEquals(false, onWindows.abiSubstituted());
        }
    }
}
