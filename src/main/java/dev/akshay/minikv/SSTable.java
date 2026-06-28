package dev.akshay.minikv;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * An immutable, sorted on-disk table. Written once (from a sorted record stream) and then
 * only read. Layout:
 *
 * <pre>
 *   DATA    : [int keyLen][key][long seq][byte type][int valLen][value]  (sorted by key)
 *   INDEX   : [int keyLen][key][long offset]   (sparse — every Nth record)
 *   BLOOM   : serialized {@link BloomFilter}
 *   FOOTER  : [long indexOffset][long bloomOffset][long maxSeq][long count][int MAGIC]
 * </pre>
 *
 * <p>Reads consult the Bloom filter first (skip the whole file on a "no"), then binary-search
 * the sparse index for the block to scan and walk forward to the key. For simplicity v1 holds
 * the file bytes in memory once opened (a block cache is on the roadmap).
 */
final class SSTable implements AutoCloseable {

    private static final int MAGIC = 0x4D4B5642; // "MKVB"
    private static final int FOOTER_SIZE = 8 + 8 + 8 + 8 + 4;

    private final Path path;
    private final ByteBuffer buf;     // whole file in memory
    private final long indexOffset;
    private final long maxSeq;
    private final long count;
    private final Bytes[] indexKeys;
    private final long[] indexOffsets;
    private final BloomFilter bloom;

    private SSTable(Path path, ByteBuffer buf, long indexOffset, long maxSeq, long count,
                    Bytes[] indexKeys, long[] indexOffsets, BloomFilter bloom) {
        this.path = path;
        this.buf = buf;
        this.indexOffset = indexOffset;
        this.maxSeq = maxSeq;
        this.count = count;
        this.indexKeys = indexKeys;
        this.indexOffsets = indexOffsets;
        this.bloom = bloom;
    }

    // ---------------------------------------------------------------- writing

    /**
     * Writes {@code records} (which MUST be in ascending key order, one per key) to a new
     * SSTable, atomically (temp file + rename), and returns an opened reader for it.
     */
    static SSTable write(Path path, Iterable<Record> records, int expectedCount,
                         int indexInterval, int bloomBitsPerKey) throws IOException {
        ByteArrayOutputStream dataBaos = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(dataBaos);
        List<Bytes> idxKeys = new ArrayList<>();
        List<Long> idxOffsets = new ArrayList<>();
        BloomFilter bloom = BloomFilter.create(Math.max(1, expectedCount), bloomBitsPerKey);

        long maxSeq = 0;
        long count = 0;
        int i = 0;
        for (Record r : records) {
            long entryOffset = dataBaos.size();
            if (i % indexInterval == 0) {
                idxKeys.add(r.key());
                idxOffsets.add(entryOffset);
            }
            writeEntry(data, r);
            bloom.add(r.key().raw());
            maxSeq = Math.max(maxSeq, r.seq());
            count++;
            i++;
        }
        data.flush();
        byte[] dataBytes = dataBaos.toByteArray();
        long indexOffset = dataBytes.length;

        ByteArrayOutputStream idxBaos = new ByteArrayOutputStream();
        DataOutputStream idx = new DataOutputStream(idxBaos);
        for (int k = 0; k < idxKeys.size(); k++) {
            byte[] key = idxKeys.get(k).raw();
            idx.writeInt(key.length);
            idx.write(key);
            idx.writeLong(idxOffsets.get(k));
        }
        idx.flush();
        byte[] idxBytes = idxBaos.toByteArray();
        long bloomOffset = indexOffset + idxBytes.length;
        byte[] bloomBytes = bloom.serialize();

        ByteArrayOutputStream footBaos = new ByteArrayOutputStream();
        DataOutputStream foot = new DataOutputStream(footBaos);
        foot.writeLong(indexOffset);
        foot.writeLong(bloomOffset);
        foot.writeLong(maxSeq);
        foot.writeLong(count);
        foot.writeInt(MAGIC);
        foot.flush();

        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (var out = Files.newOutputStream(tmp)) {
            out.write(dataBytes);
            out.write(idxBytes);
            out.write(bloomBytes);
            out.write(footBaos.toByteArray());
        }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return open(path);
    }

    private static void writeEntry(DataOutputStream out, Record r) throws IOException {
        byte[] key = r.key().raw();
        byte[] val = r.value() == null ? new byte[0] : r.value();
        out.writeInt(key.length);
        out.write(key);
        out.writeLong(r.seq());
        out.writeByte(r.type().code);
        out.writeInt(val.length);
        out.write(val);
    }

    // ---------------------------------------------------------------- reading

    static SSTable open(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        int footerStart = bytes.length - FOOTER_SIZE;
        if (footerStart < 0) {
            throw new IOException("SSTable too small / corrupt: " + path);
        }
        ByteBuffer fb = buf.duplicate();
        fb.position(footerStart);
        long indexOffset = fb.getLong();
        long bloomOffset = fb.getLong();
        long maxSeq = fb.getLong();
        long count = fb.getLong();
        int magic = fb.getInt();
        if (magic != MAGIC) {
            throw new IOException("Bad SSTable magic in " + path);
        }

        // index
        List<Bytes> keys = new ArrayList<>();
        List<Long> offs = new ArrayList<>();
        ByteBuffer ib = buf.duplicate();
        ib.position((int) indexOffset);
        while (ib.position() < bloomOffset) {
            int keyLen = ib.getInt();
            byte[] key = new byte[keyLen];
            ib.get(key);
            long off = ib.getLong();
            keys.add(Bytes.wrap(key));
            offs.add(off);
        }
        Bytes[] indexKeys = keys.toArray(new Bytes[0]);
        long[] indexOffsets = new long[offs.size()];
        for (int i = 0; i < offs.size(); i++) {
            indexOffsets[i] = offs.get(i);
        }

        // bloom
        ByteBuffer bb = buf.duplicate();
        bb.position((int) bloomOffset);
        bb.limit(footerStart);
        BloomFilter bloom = BloomFilter.deserialize(bb.slice());

        return new SSTable(path, buf, indexOffset, maxSeq, count, indexKeys, indexOffsets, bloom);
    }

    /** @return the newest record for {@code key} in this file, or {@code null} if absent. */
    Record get(Bytes key) {
        if (indexKeys.length == 0) {
            return null;
        }
        if (!bloom.mightContain(key.raw())) {
            return null;
        }
        if (key.compareTo(indexKeys[0]) < 0) {
            return null; // smaller than the smallest key in the file
        }
        int block = floorIndex(key);
        int pos = (int) indexOffsets[block];
        ByteBuffer b = buf.duplicate();
        b.position(pos);
        while (b.position() < indexOffset) {
            EntryRef e = readEntry(b);
            int cmp = Bytes.compare(e.key, key.raw());
            if (cmp == 0) {
                return e.toRecord();
            }
            if (cmp > 0) {
                return null; // passed where it would be — not present
            }
        }
        return null;
    }

    /** Largest index slot whose key is &le; the target (binary search). */
    private int floorIndex(Bytes key) {
        int lo = 0, hi = indexKeys.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (indexKeys[mid].compareTo(key) <= 0) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** All records in ascending key order — used by compaction. */
    List<Record> readAll() {
        List<Record> out = new ArrayList<>((int) count);
        ByteBuffer b = buf.duplicate();
        b.position(0);
        while (b.position() < indexOffset) {
            out.add(readEntry(b).toRecord());
        }
        return out;
    }

    private static EntryRef readEntry(ByteBuffer b) {
        int keyLen = b.getInt();
        byte[] key = new byte[keyLen];
        b.get(key);
        long seq = b.getLong();
        byte type = b.get();
        int valLen = b.getInt();
        byte[] val = new byte[valLen];
        b.get(val);
        return new EntryRef(key, seq, type, val);
    }

    private static final class EntryRef {
        final byte[] key;
        final long seq;
        final byte type;
        final byte[] val;

        EntryRef(byte[] key, long seq, byte type, byte[] val) {
            this.key = key;
            this.seq = seq;
            this.type = type;
            this.val = val;
        }

        Record toRecord() {
            if (Record.ValueType.fromCode(type) == Record.ValueType.DELETE) {
                return Record.tombstone(Bytes.wrap(key), seq);
            }
            return Record.put(Bytes.wrap(key), val, seq);
        }
    }

    long maxSeq() {
        return maxSeq;
    }

    long count() {
        return count;
    }

    Path path() {
        return path;
    }

    void delete() throws IOException {
        Files.deleteIfExists(path);
    }

    @Override
    public void close() {
        // file bytes are plain heap; nothing to release.
    }
}
