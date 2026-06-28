package dev.akshay.minikv;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WalTest {

    private static Record put(String k, String v, long seq) {
        return Record.put(Bytes.of(k), v.getBytes(StandardCharsets.UTF_8), seq);
    }

    @Test
    void appendThenReplayRoundTrips(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("wal.log");
        try (Wal wal = new Wal(f, true)) {
            wal.append(put("a", "1", 1));
            wal.append(put("b", "2", 2));
            wal.append(Record.tombstone(Bytes.of("a"), 3));
        }
        List<Record> replayed = new ArrayList<>();
        try (Wal wal = new Wal(f, false)) {
            wal.replay(replayed::add);
        }
        assertEquals(3, replayed.size());
        assertEquals("a", replayed.get(0).key().asString());
        assertArrayEquals("2".getBytes(StandardCharsets.UTF_8), replayed.get(1).value());
        assertTrue(replayed.get(2).isTombstone());
    }

    @Test
    void truncatedTailIsIgnored(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("wal.log");
        try (Wal wal = new Wal(f, true)) {
            wal.append(put("a", "1", 1));
            wal.append(put("b", "2", 2));
        }
        // Simulate a torn write: append a partial frame (a length header with no payload).
        Files.write(f, new byte[]{0, 0, 0, 99, 1, 2, 3}, StandardOpenOption.APPEND);

        List<Record> replayed = new ArrayList<>();
        try (Wal wal = new Wal(f, false)) {
            wal.replay(replayed::add);
        }
        assertEquals(2, replayed.size(), "partial tail record must be skipped");
    }

    @Test
    void rotateEmptiesTheLog(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("wal.log");
        try (Wal wal = new Wal(f, true)) {
            wal.append(put("a", "1", 1));
            wal.rotate();
            List<Record> replayed = new ArrayList<>();
            wal.replay(replayed::add);
            assertTrue(replayed.isEmpty());
        }
    }
}
