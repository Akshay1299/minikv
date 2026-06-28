package dev.akshay.minikv;

/**
 * Tunables for a {@link MiniKV} instance. Defaults are sensible for general use; tests
 * shrink {@link #memtableFlushThresholdBytes} and {@link #compactionTrigger} so flushes
 * and compactions happen with only a handful of keys.
 */
public final class Config {

    /** Flush the memtable to an SSTable once its approximate size reaches this many bytes. */
    public long memtableFlushThresholdBytes = 4L * 1024 * 1024; // 4 MiB

    /** Run compaction once the number of live SSTables exceeds this. */
    public int compactionTrigger = 4;

    /** Emit a sparse-index entry every Nth record in an SSTable. */
    public int indexInterval = 16;

    /** Bloom filter bits allocated per key (≈1% false positive at 10 bits, 7 hashes). */
    public int bloomBitsPerKey = 10;

    /** fsync the WAL on every write (durable but slower). Off → durable on flush/close only. */
    public boolean syncOnWrite = false;

    public Config memtableFlushThresholdBytes(long v) {
        this.memtableFlushThresholdBytes = v;
        return this;
    }

    public Config compactionTrigger(int v) {
        this.compactionTrigger = v;
        return this;
    }

    public Config indexInterval(int v) {
        this.indexInterval = v;
        return this;
    }

    public Config bloomBitsPerKey(int v) {
        this.bloomBitsPerKey = v;
        return this;
    }

    public Config syncOnWrite(boolean v) {
        this.syncOnWrite = v;
        return this;
    }
}
