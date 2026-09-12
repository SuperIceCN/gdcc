package gd.script.gdcc;

import gd.script.gdcc.util.GdccVersion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainEntrypointTest {
    @Test
    void runDelegatesToPicocliHelpWithoutCompileOrchestration() {
        var result = runWithCapturedStreams("--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.outText().contains("Usage: gdcc"));
        assertEquals("", result.errText());
    }

    @Test
    void runDelegatesToPicocliVersionWithoutCompileOrchestration() {
        var result = runWithCapturedStreams("--version");

        assertEquals(0, result.exitCode());
        assertEquals(GdccVersion.displayText() + System.lineSeparator(), result.outText());
        assertEquals("", result.errText());
    }

    @Test
    void runWithoutArgsReachesCliUsageError() {
        var result = runWithCapturedStreams();

        // A bare `gdcc` must land on picocli's missing-parameter usage error, not on an
        // `ArrayIndexOutOfBoundsException` in the first-arg routing.
        assertEquals(2, result.exitCode());
        assertTrue(result.errText().contains("Usage: gdcc"));
    }

    @Test
    void runRoutesServeToTheRpcEntrypoint() {
        var result = runWithCapturedStreams("serve", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.outText().contains("Usage: serve"));
    }

    @Test
    void runKeepsUnknownFirstArgOnTheCliPath() {
        var result = runWithCapturedStreams("frobnicate");

        // `frobnicate` is not a routing keyword, so the CLI treats it as an input file and fails
        // its own host-input validation.
        assertEquals(2, result.exitCode());
        assertTrue(result.errText().contains("gdcc: Input file does not exist: frobnicate"));
    }

    private static CapturedRun runWithCapturedStreams(String... args) {
        var originalOut = System.out;
        var originalErr = System.err;
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            var exitCode = Main.run(args);
            return new CapturedRun(
                    exitCode,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8)
            );
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    private record CapturedRun(int exitCode, String outText, String errText) {
    }
}
