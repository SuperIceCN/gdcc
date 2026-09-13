package gd.script.gdcc.rpc;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Parse-level coverage for `gdcc serve` options: documented defaults, explicit overrides, and
/// validation failures. The blocking server lifecycle is exercised separately through the HTTP
/// tests, so nothing here starts a listener.
class RpcServeCommandTest {
    @Test
    void defaultsMatchTheDocumentedServiceValues() {
        var command = new RpcServeCommand();

        command.commandLine().parseArgs();

        assertEquals("127.0.0.1", command.host);
        assertEquals(6099, command.port);
        assertEquals(16 * 1024 * 1024, command.maxRequestBytes);
    }

    @Test
    void explicitOptionsOverrideDefaults() {
        var command = new RpcServeCommand();

        command.commandLine().parseArgs("--host", "0.0.0.0", "--port", "7000", "--max-request-bytes", "1024");

        assertEquals("0.0.0.0", command.host);
        assertEquals(7000, command.port);
        assertEquals(1024, command.maxRequestBytes);
    }

    @Test
    void helpExitsSuccessfullyWithoutStartingTheServer() {
        assertEquals(0, RpcServeCommand.execute(new String[]{"--help"}));
    }

    @Test
    void invalidOptionValuesFailAsUsageErrors() {
        // Every validation failure must surface as picocli usage exit code 2 before any listener
        // is created.
        assertEquals(2, RpcServeCommand.execute(new String[]{"--port", "-1"}));
        assertEquals(2, RpcServeCommand.execute(new String[]{"--port", "65536"}));
        assertEquals(2, RpcServeCommand.execute(new String[]{"--max-request-bytes", "0"}));
        assertEquals(2, RpcServeCommand.execute(new String[]{"--max-request-bytes", "-8"}));
        assertEquals(2, RpcServeCommand.execute(new String[]{"--host", "   "}));
        // Non-numeric values fail picocli's type conversion with the same usage exit code.
        assertEquals(2, RpcServeCommand.execute(new String[]{"--port", "abc"}));
    }
}
