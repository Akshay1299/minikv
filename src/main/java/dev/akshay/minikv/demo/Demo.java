package dev.akshay.minikv.demo;

import dev.akshay.minikv.Config;
import dev.akshay.minikv.MiniKV;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * A narrated, end-to-end walkthrough of MiniKV: every stage of the LSM-tree
 * (log → buffer → flush → merge → recover) is exercised with sample data and explained,
 * printing the real files that appear on disk along the way.
 *
 * <p>Run it with: {@code ./gradlew demo}
 */
public final class Demo {

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("minikv-demo");

        banner("MiniKV — a tour of an LSM-tree key-value store");
        say("MiniKV makes writes durable in a log, buffers them in a sorted in-memory");
        say("table, flushes that to immutable sorted files (SSTables), and merges those");
        say("files in the background. Let's watch each stage happen.");
        say("");
        say("Store directory: " + dir);

        // Small thresholds so a handful of keys is enough to trigger flushes & compaction.
        Config cfg = new Config()
                .memtableFlushThresholdBytes(300) // flush after ~300 bytes of writes
                .compactionTrigger(2)             // compact once >2 SSTables exist
                .indexInterval(4);                // sparse index entry every 4 keys
        say("Config: flush at 300 B, compact when >2 SSTables, sparse index every 4 keys.");

        try (MiniKV db = MiniKV.open(dir, cfg)) {

            // ---------------------------------------------------------- 1. write path
            section("1) WRITE PATH  —  put() = append to WAL, then insert into the memtable");
            db.put("user:1001", "Ada Lovelace");
            db.put("user:1002", "Alan Turing");
            db.put("user:1003", "Grace Hopper");
            say("Wrote 3 keys. Each was appended to the write-ahead log (durability) and");
            say("inserted into the in-memory memtable (sorted). Nothing is on disk as an");
            say("SSTable yet — only the WAL.");
            stats(db);
            files(dir);

            // ---------------------------------------------------------- 2. flush
            section("2) FLUSH  —  full memtable becomes an immutable SSTable; WAL is rotated");
            say("Forcing a flush (also happens automatically once the memtable fills).");
            db.flush();
            say("The memtable was written out as one sorted SSTable (data + sparse index +");
            say("Bloom filter), the manifest was updated, and the WAL was truncated to 0.");
            stats(db);
            files(dir);

            // ---------------------------------------------------------- 3. read path
            section("3) READ PATH  —  memtable first, then SSTables newest→oldest");
            say("get(user:1001) -> " + db.get("user:1001"));
            say("get(user:9999) -> " + db.get("user:9999") + "   (absent: Bloom filter skips the file)");
            say("A read checks the memtable, then walks SSTables newest-first. Each SSTable's");
            say("Bloom filter rules out files that can't contain the key; the sparse index");
            say("narrows the search to a small block to scan.");

            // ---------------------------------------------------------- 4. update & delete
            section("4) UPDATE & DELETE  —  newest sequence number wins; deletes are tombstones");
            db.put("user:1002", "Alan M. Turing");   // overwrite
            db.delete("user:1003");                   // tombstone
            say("Overwrote user:1002 and deleted user:1003.");
            say("get(user:1002) -> " + db.get("user:1002") + "   (newest write wins via its sequence number)");
            say("get(user:1003) -> " + db.get("user:1003") + "   (a tombstone shadows the old value)");

            // ---------------------------------------------------------- 5. compaction
            section("5) COMPACTION  —  many SSTables get merged into one");
            say("Writing a burst of keys to force several flushes and trigger compaction...");
            for (int i = 0; i < 40; i++) {
                db.put(String.format("k%04d", i), "value-" + i);
            }
            say("Despite many writes, the SSTable count stays bounded — compaction k-way-merges");
            say("the files, keeping only the newest version of each key and physically dropping");
            say("tombstones and overwritten data.");
            stats(db);
            files(dir);
            say("Spot checks after compaction:");
            say("  get(user:1001) -> " + db.get("user:1001") + "   (survived)");
            say("  get(user:1002) -> " + db.get("user:1002") + "   (kept the updated value)");
            say("  get(user:1003) -> " + db.get("user:1003") + "   (deleted — tombstone removed)");
            say("  get(k0039)     -> " + db.get("k0039"));

            // Write something we deliberately DON'T flush, to prove WAL recovery next.
            db.put("only:in:wal", "not yet flushed");
        }

        // ---------------------------------------------------------- 6. recovery
        section("6) CRASH RECOVERY  —  reopen the same directory; nothing is lost");
        say("The store was closed. Reopening from the same files on disk...");
        try (MiniKV db = MiniKV.open(dir, cfg)) {
            say("  get(user:1001)   -> " + db.get("user:1001") + "   (from an SSTable)");
            say("  get(user:1003)   -> " + db.get("user:1003") + "   (still deleted)");
            say("  get(only:in:wal) -> " + db.get("only:in:wal") + "   (recovered by replaying the WAL!)");
            say("On open, MiniKV loads the manifest + SSTables and replays the WAL into a fresh");
            say("memtable — so even writes that were never flushed come back.");
            stats(db);
        }

        banner("Done. That's the whole engine: log → buffer → flush → merge → recover.");
        say("Read the code in src/main/java/dev/akshay/minikv — each class maps to one concept,");
        say("and DESIGN.md has the full architecture write-up.");
    }

    // ---------------------------------------------------------------- output helpers

    private static void banner(String title) {
        String bar = "=".repeat(72);
        System.out.println("\n" + bar);
        System.out.println("  " + title);
        System.out.println(bar);
    }

    private static void section(String title) {
        System.out.println("\n" + "-".repeat(72));
        System.out.println(title);
        System.out.println("-".repeat(72));
    }

    private static void say(String s) {
        System.out.println(s.isEmpty() ? "" : "  " + s);
    }

    private static void stats(MiniKV db) {
        MiniKV.Stats s = db.stats();
        say(String.format(">> state: %d SSTable(s), %d key(s) buffered in memtable (%d B), lastSeq=%d",
                s.sstableCount, s.memtableEntries, s.memtableBytes, s.lastSeq));
    }

    private static void files(Path dir) {
        System.out.println("  >> files on disk:");
        try (Stream<Path> s = Files.list(dir)) {
            s.sorted().forEach(p -> {
                try {
                    System.out.printf("       %-24s %7d bytes%n", p.getFileName(), Files.size(p));
                } catch (IOException ignored) {
                }
            });
        } catch (IOException e) {
            System.out.println("       (could not list: " + e.getMessage() + ")");
        }
    }

    private Demo() {
    }
}
