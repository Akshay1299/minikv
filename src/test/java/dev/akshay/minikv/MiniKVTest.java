package dev.akshay.minikv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class MiniKVTest {

    private static Config tiny() {
        return new Config().memtableFlushThresholdBytes(200).compactionTrigger(2).indexInterval(4);
    }

    @Test
    void basicPutGetDelete(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir)) {
            db.put("hello", "world");
            assertEquals("world", db.get("hello"));
            db.delete("hello");
            assertNull(db.get("hello"));
            assertNull(db.get("never-written"));
        }
    }

    @Test
    void overwriteReturnsLatestValue(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            db.put("k", "v1");
            db.put("k", "v2");
            db.put("k", "v3");
            assertEquals("v3", db.get("k"));
        }
    }

    @Test
    void readsSpanMemtableAndMultipleSSTables(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            for (int i = 0; i < 60; i++) {
                db.put(String.format("k%04d", i), "v" + i);
            }
            // forced multiple flushes/compactions by now
            for (int i = 0; i < 60; i++) {
                assertEquals("v" + i, db.get(String.format("k%04d", i)), "k" + i);
            }
        }
    }

    @Test
    void binaryValuesRoundTrip(@TempDir Path dir) throws IOException {
        byte[] key = {0, (byte) 0xFF, 0x10};
        byte[] val = {(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            db.put(key, val);
            assertArrayEquals(val, db.get(key));
        }
    }

    @Test
    void matchesTreeMapOracleUnderRandomOps(@TempDir Path dir) throws IOException {
        TreeMap<String, String> oracle = new TreeMap<>();
        Random rnd = new Random(7);
        try (MiniKV db = MiniKV.open(dir, tiny())) {
            for (int i = 0; i < 2000; i++) {
                String k = "key" + rnd.nextInt(150);
                if (rnd.nextInt(6) == 0) {
                    db.delete(k);
                    oracle.remove(k);
                } else {
                    String v = "v" + i;
                    db.put(k, v);
                    oracle.put(k, v);
                }
            }
            for (int i = 0; i < 150; i++) {
                String k = "key" + i;
                assertTrue(Objects.equals(db.get(k), oracle.get(k)), "mismatch on " + k);
            }
        }
    }

    @Test
    void rejectsNullKeyOrValue(@TempDir Path dir) throws IOException {
        try (MiniKV db = MiniKV.open(dir)) {
            assertThrows(IllegalArgumentException.class, () -> db.put((byte[]) null, new byte[1]));
        }
    }

    @Test
    void operationsAfterCloseThrow(@TempDir Path dir) throws IOException {
        MiniKV db = MiniKV.open(dir);
        db.put("a", "b");
        db.close();
        assertThrows(IllegalStateException.class, () -> db.get("a"));
    }
}
