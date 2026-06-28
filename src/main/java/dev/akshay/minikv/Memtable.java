package dev.akshay.minikv;

import java.util.Collection;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The in-memory write buffer: a sorted map of the newest {@link Record} per key, backed by
 * a lock-free {@link ConcurrentSkipListMap}. Being sorted means a flush is a single
 * sequential pass that produces an already-ordered SSTable.
 */
final class Memtable {

    private final ConcurrentSkipListMap<Bytes, Record> map = new ConcurrentSkipListMap<>();
    private final AtomicLong approxBytes = new AtomicLong(0);

    /** Overhead estimate per entry (object headers, seq, type, map node). */
    private static final long ENTRY_OVERHEAD = 64;

    void put(Record r) {
        Record prev = map.put(r.key(), r);
        approxBytes.addAndGet(sizeOf(r) - (prev == null ? 0 : sizeOf(prev)));
    }

    /** @return the newest record for the key, or {@code null} if the key was never written here. */
    Record get(Bytes key) {
        return map.get(key);
    }

    /** Records in ascending key order — exactly what the SSTable writer wants. */
    Collection<Record> records() {
        return map.values();
    }

    int count() {
        return map.size();
    }

    long approxBytes() {
        return approxBytes.get();
    }

    boolean isEmpty() {
        return map.isEmpty();
    }

    private static long sizeOf(Record r) {
        long v = r.value() == null ? 0 : r.value().length;
        return r.key().length() + v + ENTRY_OVERHEAD;
    }
}
