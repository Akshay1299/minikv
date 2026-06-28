package dev.akshay.minikv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CompactionTest {

    private static Config tiny() {
        return new Config().memtableFlushThresholdBytes(150).compactionTrigger(2).indexInterval(4);
    }

    @Test
    void compactionBoundsSSTableCountAndKeepsData(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            for (int i = 0; i < 200; i++) {
                db.put(String.format("k%04d", i), "v" + i);
            }
            assertTrue(db.stats().sstableCount <= tiny().compactionTrigger + 1,
                    "compaction should keep SSTable count bounded, was " + db.stats().sstableCount);
            for (int i = 0; i < 200; i++) {
                assertEquals("v" + i, db.get(String.format("k%04d", i)), "k" + i);
            }
        }
    }

    @Test
    void compactionDropsTombstonesButKeepsLiveKeys(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            for (int i = 0; i < 100; i++) {
                db.put(String.format("k%04d", i), "v" + i);
            }
            for (int i = 0; i < 50; i++) {
                db.delete(String.format("k%04d", i)); // delete the first half
            }
            // push more writes to trigger further flush + compaction passes
            for (int i = 100; i < 160; i++) {
                db.put(String.format("k%04d", i), "v" + i);
            }
            for (int i = 0; i < 50; i++) {
                assertNull(db.get(String.format("k%04d", i)), "deleted key " + i + " must stay gone");
            }
            for (int i = 50; i < 100; i++) {
                assertEquals("v" + i, db.get(String.format("k%04d", i)));
            }
            for (int i = 100; i < 160; i++) {
                assertEquals("v" + i, db.get(String.format("k%04d", i)));
            }
        }
    }

    @Test
    void compactionKeepsLatestVersionOfOverwrittenKey(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            for (int round = 0; round < 30; round++) {
                db.put("hot", "value-" + round);
                for (int i = 0; i < 5; i++) {
                    db.put("pad" + round + "_" + i, "x"); // force flushes/compactions
                }
            }
            assertEquals("value-29", db.get("hot"));
        }
    }
}
