package gd.script.gdcc;

import gd.script.gdcc.cli.GdccCommand;
import gd.script.gdcc.rpc.RpcServeCommand;

import java.util.Arrays;

public final class Main {
    private Main() {
    }

    static void main(String[] args) {
        System.exit(run(args));
    }

    /// First-argument routing: `gdcc serve ...` goes to the JSON-RPC service entry point and every
    /// other invocation stays on the CLI. The check is null- and empty-array safe so a bare `gdcc`
    /// still reaches picocli's usage error instead of an `ArrayIndexOutOfBoundsException`.
    static int run(String[] args) {
        if (args != null && args.length > 0 && "serve".equals(args[0])) {
            return RpcServeCommand.execute(Arrays.copyOfRange(args, 1, args.length));
        }
        return GdccCommand.execute(args);
    }
}
