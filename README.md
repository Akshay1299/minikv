<h1 align="center">MiniKV</h1>

<p align="center">
  <b>An embedded, persistent, crash-safe key-value store in plain Java — built on an LSM-tree.</b><br>
  <sub>Zero runtime dependencies · WAL durability · SSTables · Bloom filters · compaction · crash recovery</sub>
</p>

<p align="center">
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-orange">
  <img alt="Build" src="https://img.shields.io/badge/build-Gradle-02303A">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
</p>

<p align="center">
  <img src="docs/demo.gif" alt="MiniKV CLI demo — put, get, flush, stats, delete" width="760">
</p>

---

MiniKV is the write path of a real storage engine (think LevelDB / RocksDB / Cassandra),
shrunk to something you can read in an afternoon. Writes are made durable in a log, buffered
in a sorted in-memory table, flushed to immutable sorted files, and periodically merged.

It's a learning project I built to understand database internals by writing one — not a
production database.

## Why it exists

I kept reading *about* LSM-trees, WALs, and compaction. Building one end-to-end — and making
it survive a `kill -9` mid-write — taught me far more than any blog post. The code is meant
to be read: every component maps to one concept.

## How it works

```
  put(k,v) ─► WAL (append + fsync)  ─►  Memtable (sorted, in-memory)
                                              │  when full
                                              ▼  flush
                                        SSTable  (immutable: data + sparse index + bloom)
                                              │  when many
                                              ▼  size-tiered compaction (k-way merge)
                                        one merged SSTable

  get(k): Memtable ─► SSTable(newest) ─► … ─► SSTable(oldest)
          (Bloom filter skips files that can't contain the key)
```

Full write-up in **[DESIGN.md](DESIGN.md)** — data model, file formats, read/write paths,
recovery, compaction, concurrency, and complexity trade-offs.

## Quick start

```java
import dev.akshay.minikv.MiniKV;
import java.nio.file.Path;

try (MiniKV db = MiniKV.open(Path.of("data"))) {
    db.put("hello", "world");
    String v = db.get("hello");   // "world"
    db.delete("hello");
    System.out.println(db.stats()); // sstables=… memtableEntries=… lastSeq=…
}
```

Keys and values are arbitrary `byte[]` (String overloads provided). An acknowledged write
survives a crash — reopen the same directory and it's there.

## See it in action

A narrated walkthrough exercises every stage of the engine — write path, flush, reads,
update/delete, compaction, and crash recovery — printing the real files that appear on disk:

```bash
./gradlew demo
```

<details>
<summary>Sample output (abridged)</summary>

```
1) WRITE PATH  —  put() = append to WAL, then insert into the memtable
  >> state: 0 SSTable(s), 3 key(s) buffered in memtable (254 B), lastSeq=3
  >> files on disk:
       wal.log                      137 bytes

2) FLUSH  —  full memtable becomes an immutable SSTable; WAL is rotated
  >> state: 1 SSTable(s), 0 key(s) buffered in memtable (0 B), lastSeq=3
  >> files on disk:
       MANIFEST                      18 bytes
       sst-0000000001.db            190 bytes
       wal.log                        0 bytes

5) COMPACTION  —  many SSTables get merged into one
  >> state: 1 SSTable(s), ...           # dozens of writes, still one merged SSTable
       sst-0000000016.db           1482 bytes

6) CRASH RECOVERY  —  reopen the same directory; nothing is lost
    get(only:in:wal) -> not yet flushed   (recovered by replaying the WAL!)
```
</details>

### Try the CLI

```bash
./gradlew run --args="data"
> put hello world
> get hello
world
> del hello
> stats
> exit
```

## What's implemented

- ✅ **Write-Ahead Log** — length-prefixed records with CRC32; torn tail tolerated on replay
- ✅ **Memtable** — lock-free sorted skip list
- ✅ **SSTables** — immutable sorted files with a **sparse index** + per-file **Bloom filter**
- ✅ **Crash recovery** — replay the WAL on startup; rebuild from the manifest
- ✅ **Size-tiered compaction** — k-way merge, drops tombstones & overwritten versions
- ✅ **Atomic manifest** — temp-file + rename so an update is never half-written
- ✅ Tested against a `TreeMap` oracle under thousands of randomized ops

## Roadmap

Range scans (merging iterator) · benchmark harness (read/write/space amplification, p99) ·
leveled compaction · block cache · snapshots/MVCC · background flush. See
[DESIGN.md §10](DESIGN.md).

## Build & test

```bash
./gradlew build      # compile + run the JUnit suite
./gradlew test
```

Requires JDK 17+. The code uses only the standard library.

## License

MIT © Akshay Lavate
