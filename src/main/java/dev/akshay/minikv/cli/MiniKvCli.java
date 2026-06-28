package dev.akshay.minikv.cli;

import dev.akshay.minikv.MiniKV;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

/**
 * A tiny REPL over a MiniKV store directory.
 *
 * <pre>
 *   gradle run --args="data"
 *   &gt; put hello world
 *   &gt; get hello
 *   world
 *   &gt; del hello
 *   &gt; stats
 * </pre>
 */
public final class MiniKvCli {

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "data");
        System.out.println("MiniKV @ " + dir.toAbsolutePath());
        System.out.println("commands: put <k> <v> | get <k> | del <k> | flush | stats | help | exit");

        try (MiniKV db = MiniKV.open(dir);
             BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            System.out.print("> ");
            while ((line = in.readLine()) != null) {
                try {
                    if (!handle(db, line.trim())) {
                        break;
                    }
                } catch (RuntimeException e) {
                    System.out.println("error: " + e.getMessage());
                }
                System.out.print("> ");
            }
        }
        System.out.println("bye");
    }

    private static boolean handle(MiniKV db, String line) {
        if (line.isEmpty()) {
            return true;
        }
        String[] parts = line.split("\\s+", 3);
        String cmd = parts[0].toLowerCase();
        switch (cmd) {
            case "put":
                require(parts.length == 3, "usage: put <k> <v>");
                db.put(parts[1], parts[2]);
                System.out.println("ok");
                return true;
            case "get":
                require(parts.length == 2, "usage: get <k>");
                String v = db.get(parts[1]);
                System.out.println(v == null ? "(nil)" : v);
                return true;
            case "del":
            case "delete":
                require(parts.length == 2, "usage: del <k>");
                db.delete(parts[1]);
                System.out.println("ok");
                return true;
            case "flush":
                db.flush();
                System.out.println("flushed");
                return true;
            case "stats":
                System.out.println(db.stats());
                return true;
            case "help":
                System.out.println("put <k> <v> | get <k> | del <k> | flush | stats | exit");
                return true;
            case "exit":
            case "quit":
                return false;
            default:
                System.out.println("unknown command: " + cmd + " (try 'help')");
                return true;
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) {
            throw new IllegalArgumentException(msg);
        }
    }
}
