# MiniKV — High-Level Design

> An embedded, persistent, crash-safe key-value store in plain Java, built on an
> **LSM-tree** (Log-Structured Merge-tree). Zero runtime dependencies.

MiniKV is a teaching-grade-but-real storage engine. It is the same shape as the
write path inside RocksDB / LevelDB / Cassandra, shrunk to something you can read
in an afternoon: writes are made durable in a log, buffered in memory, flushed to
sorted immutable files, and periodically merged.

---

## 1. Goals & non-goals

**Goals**
- **Durability:** an acknowledged write survives a process crash (Write-Ahead Log + fsync).
- **Fast writes:** every write is one sequential append + one in-memory insert — no random I/O.
- **Ordered, immutable on-disk files (SSTables)** with a sparse index + Bloom filter for fast reads.
- **Crash recovery:** rebuild in-memory state by replaying the WAL on startup.
- **Compaction:** merge SSTables to bound read amplification and reclaim space from
  overwritten keys and tombstones.
- **Small, dependency-free, readable.** Keys and values are arbitrary `byte[]`.

**Non-goals (v1)**
- Networking / client-server (it's an embedded library).
- Distribution, replication, sharding.
- Transactions / MVCC snapshots / secondary indexes.
- Leveled compaction & a block cache (size-tiered only for now — see roadmap).

---

## 2. Architecture at a glance

```
                          put(k,v) / delete(k)
                                  │
                                  ▼
                    ┌───────────────────────────┐
              (1)   │   Write-Ahead Log (WAL)    │  append + fsync  → durability
                    │   length | seq | CRC32     │
                    └───────────────────────────┘
                                  │
                                  ▼
              (2)   ┌───────────────────────────┐
                    │   Memtable (skip list)     │  sorted, in-memory
                    └───────────────────────────┘
                                  │  size ≥ threshold
                                  ▼  flush (sequential write, then rotate WAL)
              (3)   ┌───────────────────────────┐
                    │  SSTable_n (immutable)     │  data | sparse index | bloom | footer
                    │  SSTable_n-1 ...           │
                    │  SSTable_0                 │
                    └───────────────────────────┘
                                  │  count > trigger
                                  ▼  (4) compaction: k-way merge → one SSTable

   get(k):  Memtable ─miss→ SSTable_n ─miss→ … ─miss→ SSTable_0 ─miss→ not found
            (Bloom filter short-circuits most SSTable misses)
```

The four numbered stages are the whole engine: **(1) log, (2) buffer, (3) flush, (4) merge.**

---

## 3. Data model

Internally every mutation is a **`Record`**:

| field | meaning |
|-------|---------|
| `key`   | `byte[]`, compared **unsigned lexicographically** (`Bytes`) |
| `value` | `byte[]` (absent for a delete) |
| `seq`   | monotonically increasing 64-bit sequence number — defines "newest wins" |
| `type`  | `PUT` or `DELETE` |

A **delete is a tombstone**: a `DELETE` record that shadows older values for the key
until compaction physically removes it. Sequence numbers give a total order across the
memtable and every SSTable, so the newest record for a key always wins regardless of
which file it lives in.

---

## 4. Components

| Component | Responsibility |
|-----------|----------------|
| `Bytes` | Unsigned-lexicographic comparable wrapper over `byte[]` (key ordering). |
| `Record` / `ValueType` | One mutation: key, value, seq, type. |
| `Wal` | Append records (length-prefixed, CRC32); replay on recovery; rotate after a flush. |
| `Memtable` | `ConcurrentSkipListMap` of newest record per key; tracks approximate size. |
| `BloomFilter` | Per-SSTable membership filter (Kirsch–Mitzenmacher double hashing). No false negatives. |
| `SSTable` | Writer + reader for an immutable sorted file (data + sparse index + bloom + footer). |
| `Manifest` | The ordered (newest-first) list of live SSTables; persisted atomically (temp + rename). |
| `Compactor` | Size-tiered merge of SSTables; keeps newest seq per key, drops tombstones on full merge. |
| `MiniKV` | Orchestrator + public API: `open / put / get / delete / flush / close`. |
| `cli.MiniKvCli` | Tiny REPL/CLI over a store directory. |

---

## 5. On-disk formats

### 5.1 WAL record
```
[ int totalLen ][ long seq ][ byte type ][ int keyLen ][ key ][ int valLen ][ value ][ int crc32 ]
```
- `crc32` covers the payload (seq..value). On replay, a record failing CRC or a
  truncated tail (torn write from a crash) **stops replay cleanly** — everything before
  it is valid and acknowledged.

### 5.2 SSTable file
```
┌──────────── DATA (sorted by key, one entry per key) ────────────┐
│  [int keyLen][key][long seq][byte type][int valLen][value] ...   │
├──────────── SPARSE INDEX (every Nth entry) ─────────────────────┤
│  [int keyLen][key][long offset] ...                              │
├──────────── BLOOM FILTER ───────────────────────────────────────┤
│  [int numBits][int numHashes][long[] bits]                       │
├──────────── FOOTER (fixed 36 bytes, read from EOF) ─────────────┤
│  [long indexOffset][long bloomOffset][long maxSeq][long count][int MAGIC] │
└──────────────────────────────────────────────────────────────────┘
```

---

## 6. Read & write paths

### 6.1 Write (`put` / `delete`)
1. Assign `seq = nextSeq++`.
2. Append the record to the WAL (fsync if configured) — **the durability point**.
3. Insert into the memtable.
4. If `memtable.bytes ≥ flushThreshold` → **flush**.

### 6.2 Flush (memtable → SSTable)
The skip list is already sorted, so flushing is a single sequential pass: write data
entries, emit a sparse index every `indexInterval` keys, build the Bloom filter over all
keys, write the footer. Then **rotate (truncate) the WAL** — its contents are now durable
in the SSTable — and clear the memtable. After a flush, if `liveSSTables > compactionTrigger`,
run compaction.

### 6.3 Read (`get`)
1. Check the memtable — a hit (value or tombstone) ends the search.
2. Otherwise walk SSTables **newest → oldest**. For each: Bloom filter check
   (skip the file entirely on a negative), else binary-search the sparse index for the
   greatest indexed key ≤ target, seek there, and scan forward until the key is found
   (return) or exceeded (miss → next file).
3. A tombstone hit means "deleted" → return `null`.

The newest-first walk + sequence numbers guarantee the latest write wins; the Bloom
filter keeps read amplification low by skipping files that can't contain the key.

---

## 7. Recovery

On `open`: load the manifest and open each SSTable reader; reset `nextSeq` to
`max(seq across all SSTable footers, seq in WAL) + 1`; replay the WAL back into a fresh
memtable. Because flush rotates the WAL, the log only ever contains writes not yet in an
SSTable — recovery is bounded and fast.

---

## 8. Compaction (size-tiered, v1)

When the live SSTable count exceeds the trigger, MiniKV merges them into one: a k-way
merge keeps the **highest-seq record per key** and, because a full merge sees all data,
**drops tombstones and overwritten versions**. The new SSTable atomically replaces the
inputs in the manifest, then the old files are deleted. This bounds read amplification
and reclaims space. Leveled compaction is a future refinement (§10).

---

## 9. Concurrency

v1 uses a single `ReentrantReadWriteLock`: `get` takes the read lock; `put`/`delete`/
`flush`/`compaction` take the write lock. The memtable itself is a lock-free
`ConcurrentSkipListMap`, and SSTables are immutable once written, so reads are cheap and
never block each other. (Background, non-blocking flush via immutable-memtable handoff is
a documented next step — the seams are in place.)

---

## 10. Roadmap

- **P1** WAL + memtable + recovery + public API ✅
- **P2** SSTable flush + multi-file read path ✅
- **P3** Bloom filters + sparse index + CRC ✅
- **P4** Size-tiered compaction + manifest + tombstone GC ✅
- **P5** Range scans / ordered iteration via a merging iterator
- **P6** CLI + benchmark harness (write/read/space amplification, p99) + diagrams
- **P7 (stretch)** Leveled compaction, block cache, snapshots/MVCC, background flush, metrics endpoint

---

## 11. Complexity & trade-offs

| Operation | Cost | Why |
|-----------|------|-----|
| `put` / `delete` | O(log m) memory + 1 sequential append | skip-list insert + WAL append |
| `get` (hot) | O(log m) | memtable hit |
| `get` (cold) | O(#sstables) Bloom checks + O(log idx + scan) on a hit | newest-first walk |
| flush | O(m) sequential write | sorted scan of the memtable |
| compaction | O(total entries) | k-way merge |

LSM trades **read amplification** (a key may live in several files) and **write
amplification** (data is rewritten during compaction) for **fast, sequential writes** and
**no in-place updates** — the right trade for write-heavy, SSD-backed workloads.
