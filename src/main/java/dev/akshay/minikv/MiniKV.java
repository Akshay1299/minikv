package dev.akshay.minikv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An embedded, persistent, crash-safe key-value store built on an LSM-tree.
 *
 * <pre>{@code
 *   try (MiniKV db = MiniKV.open(Path.of("data"))) {
 *       db.put("hello", "world");
 *       String v = db.get("hello");   // "world"
 *       db.delete("hello");
 *   }
 * }</pre>
 *
 * Writes are logged (WAL) then buffered in a sorted memtable; when the memtable fills it is
 * flushed to an immutable SSTable; SSTables are merged by compaction. See {@code DESIGN.md}.
 */
public final class MiniKV implements AutoCloseable {

    private static final String WAL_FILE = "wal.log";

    private final Path dir;
    private final Config cfg;
    private final Wal wal;
    private final Manifest manifest;
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicLong fileCounter = new AtomicLong(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private volatile Memtable memtable;
    private volatile List<SSTable> sstables; // newest first
    private boolean closed = false;

    private MiniKV(Path dir, Config cfg, Wal wal, Manifest manifest,
                   Memtable memtable, List<SSTable> sstables) {
        this.dir = dir;
        this.cfg = cfg;
        this.wal = wal;
        this.manifest = manifest;
        this.memtable = memtable;
        this.sstables = sstables;
    }

    public static MiniKV open(Path dir) throws IOException {
        return open(dir, new Config());
    }

    public static MiniKV open(Path dir, Config cfg) throws IOException {
        Files.createDirectories(dir);
        Manifest manifest = Manifest.load(dir);

        List<SSTable> sstables = new ArrayList<>();
        long maxSeq = 0;
        long maxFileId = 0;
        for (String name : manifest.sstables()) {
            SSTable t = SSTable.open(dir.resolve(name));
            sstables.add(t);
            maxSeq = Math.max(maxSeq, t.maxSeq());
            maxFileId = Math.max(maxFileId, parseFileId(name));
        }

        Wal wal = new Wal(dir.resolve(WAL_FILE), cfg.syncOnWrite);
        Memtable memtable = new Memtable();
        long[] walMax = {maxSeq};
        wal.replay(r -> {
            memtable.put(r);
            if (r.seq() > walMax[0]) {
                walMax[0] = r.seq();
            }
        });

        MiniKV db = new MiniKV(dir, cfg, wal, manifest, memtable, sstables);
        db.seq.set(walMax[0]);
        db.fileCounter.set(maxFileId);
        return db;
    }

    // ---------------------------------------------------------------- public API

    public void put(String key, String value) {
        put(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8));
    }

    public void put(byte[] key, byte[] value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key and value must be non-null");
        }
        lock.writeLock().lock();
        try {
            ensureOpen();
            long s = seq.incrementAndGet();
            Record r = Record.put(Bytes.copyOf(key), value.clone(), s);
            wal.append(r);
            memtable.put(r);
            maybeFlush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String get(String key) {
        byte[] v = get(key.getBytes(StandardCharsets.UTF_8));
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    public byte[] get(byte[] key) {
        lock.readLock().lock();
        try {
            ensureOpen();
            Bytes k = Bytes.wrap(key);
            Record r = memtable.get(k);
            if (r != null) {
                return r.isTombstone() ? null : r.value();
            }
            for (SSTable t : sstables) { // newest first
                Record rr = t.get(k);
                if (rr != null) {
                    return rr.isTombstone() ? null : rr.value();
                }
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void delete(String key) {
        delete(key.getBytes(StandardCharsets.UTF_8));
    }

    public void delete(byte[] key) {
        lock.writeLock().lock();
        try {
            ensureOpen();
            long s = seq.incrementAndGet();
            Record r = Record.tombstone(Bytes.copyOf(key), s);
            wal.append(r);
            memtable.put(r);
            maybeFlush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Force the memtable to disk now (also called automatically when it fills). */
    public void flush() {
        lock.writeLock().lock();
        try {
            ensureOpen();
            doFlush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Stats stats() {
        lock.readLock().lock();
        try {
            return new Stats(sstables.size(), memtable.count(), memtable.approxBytes(), seq.get());
        } finally {
            lock.readLock().unlock();
        }
    }

    // ---------------------------------------------------------------- internals

    private void maybeFlush() throws IOException {
        if (memtable.approxBytes() >= cfg.memtableFlushThresholdBytes) {
            doFlush();
        }
    }

    private void doFlush() throws IOException {
        if (memtable.isEmpty()) {
            return;
        }
        long id = fileCounter.incrementAndGet();
        String name = sstableName(id);
        SSTable t = SSTable.write(dir.resolve(name), memtable.records(),
                memtable.count(), cfg.indexInterval, cfg.bloomBitsPerKey);

        manifest.prepend(name);
        List<SSTable> next = new ArrayList<>(sstables.size() + 1);
        next.add(t);
        next.addAll(sstables);
        sstables = next;

        memtable = new Memtable();
        wal.rotate();

        maybeCompact();
    }

    private void maybeCompact() throws IOException {
        if (sstables.size() <= cfg.compactionTrigger) {
            return;
        }
        List<SSTable> old = sstables;
        long id = fileCounter.incrementAndGet();
        String name = sstableName(id);
        SSTable merged = Compactor.compactAll(old, dir.resolve(name), cfg);

        if (merged.count() == 0) {
            // everything was tombstones/overwritten — keep no SSTables at all.
            merged.close();
            merged.delete();
            manifest.replaceAll(List.of());
            sstables = new ArrayList<>();
        } else {
            manifest.replaceAll(List.of(name));
            sstables = new ArrayList<>(List.of(merged));
        }

        for (SSTable t : old) {
            t.close();
            t.delete();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("store is closed");
        }
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            wal.close();
            for (SSTable t : sstables) {
                t.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static String sstableName(long id) {
        return String.format("sst-%010d.db", id);
    }

    private static long parseFileId(String name) {
        try {
            int dash = name.indexOf('-');
            int dot = name.indexOf('.');
            return Long.parseLong(name.substring(dash + 1, dot));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Lightweight snapshot of store state for the CLI / benchmarks. */
    public static final class Stats {
        public final int sstableCount;
        public final int memtableEntries;
        public final long memtableBytes;
        public final long lastSeq;

        Stats(int sstableCount, int memtableEntries, long memtableBytes, long lastSeq) {
            this.sstableCount = sstableCount;
            this.memtableEntries = memtableEntries;
            this.memtableBytes = memtableBytes;
            this.lastSeq = lastSeq;
        }

        @Override
        public String toString() {
            return "sstables=" + sstableCount + " memtableEntries=" + memtableEntries
                    + " memtableBytes=" + memtableBytes + " lastSeq=" + lastSeq;
        }
    }
}
