package dev.akshay.minikv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SSTableTest {

    private static Record put(String k, String v, long seq) {
        return Record.put(Bytes.of(k), v.getBytes(StandardCharsets.UTF_8), seq);
    }

    @Test
    void writeThenReadBackEveryKey(@TempDir Path dir) throws IOException {
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            // zero-padded so lexicographic order == numeric order
            records.add(put(String.format("k%04d", i), "v" + i, i + 1));
        }
        try (SSTable t = SSTable.write(dir.resolve("t.db"), records, 100, 4, 10)) {
            for (int i = 0; i < 100; i++) {
                Record r = t.get(Bytes.of(String.format("k%04d", i)));
                assertNotNull(r, "key k" + i + " should be present");
                assertEquals("v" + i, new String(r.value(), StandardCharsets.UTF_8));
            }
            assertNull(t.get(Bytes.of("k9999")), "absent key returns null");
            assertNull(t.get(Bytes.of("a")), "key below min returns null");
            assertEquals(100, t.count());
        }
    }

    @Test
    void tombstonesAreReadAsTombstones(@TempDir Path dir) throws IOException {
        List<Record> records = List.of(
                put("a", "1", 1),
                Record.tombstone(Bytes.of("b"), 2),
                put("c", "3", 3));
        try (SSTable t = SSTable.write(dir.resolve("t.db"), records, 3, 4, 10)) {
            assertTrue(t.get(Bytes.of("b")).isTombstone());
            assertFalse(t.get(Bytes.of("a")).isTombstone());
        }
    }

    @Test
    void readAllPreservesOrder(@TempDir Path dir) throws IOException {
        List<Record> records = List.of(put("a", "1", 1), put("b", "2", 2), put("c", "3", 3));
        try (SSTable t = SSTable.write(dir.resolve("t.db"), records, 3, 4, 10)) {
            List<Record> all = t.readAll();
            assertEquals(3, all.size());
            assertEquals("a", all.get(0).key().asString());
            assertEquals("c", all.get(2).key().asString());
        }
    }
}
