package dev.akshay.minikv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {

    private static Config tiny() {
        return new Config().memtableFlushThresholdBytes(200).compactionTrigger(3).indexInterval(4);
    }

    @Test
    void dataSurvivesReopen(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            for (int i = 0; i < 40; i++) {
                db.put(String.format("k%04d", i), "v" + i);
            }
            db.delete("k0007");
        }
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            assertEquals("v0", db.get("k0000"));
            assertEquals("v39", db.get("k0039"));
            assertNull(db.get("k0007"), "tombstone must survive reopen");
        }
    }

    @Test
    void unflushedWritesRecoverFromWal(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            db.put("only-in-wal", "value"); // below flush threshold → stays in memtable+WAL
        }
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            assertEquals("value", db.get("only-in-wal"),
                    "an unflushed write must be replayed from the WAL");
        }
    }

    @Test
    void sequenceNumbersContinueAfterReopen(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            db.put("k", "first");
        }
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            db.put("k", "second"); // must out-rank the recovered "first"
            assertEquals("second", db.get("k"));
        }
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            assertEquals("second", db.get("k"));
        }
    }
}
