package dev.akshay.minikv;

import java.nio.ByteBuffer;

/**
 * A classic Bloom filter over keys. Per SSTable, it answers "is this key <i>possibly</i>
 * in the file?" with <b>no false negatives</b> (a "no" is always correct), letting reads
 * skip files that can't contain the key. Uses the Kirsch–Mitzenmacher trick of deriving
 * {@code k} hashes from two base hashes ({@code h1 + i*h2}).
 */
final class BloomFilter {

    private final long[] words;
    private final int numBits;
    private final int numHashes;

    private BloomFilter(long[] words, int numBits, int numHashes) {
        this.words = words;
        this.numBits = numBits;
        this.numHashes = numHashes;
    }

    static BloomFilter create(int expectedKeys, int bitsPerKey) {
        int bits = Math.max(64, expectedKeys * Math.max(1, bitsPerKey));
        int words = (bits + 63) / 64;
        // k = bitsPerKey * ln2, clamped to a reasonable range.
        int k = Math.max(1, Math.min(12, (int) Math.round(bitsPerKey * 0.693)));
        return new BloomFilter(new long[words], words * 64, k);
    }

    void add(byte[] key) {
        long h = hash64(key);
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        for (int i = 0; i < numHashes; i++) {
            int bit = Math.floorMod(h1 + i * h2, numBits);
            words[bit >>> 6] |= (1L << (bit & 63));
        }
    }

    boolean mightContain(byte[] key) {
        long h = hash64(key);
        int h1 = (int) h;
        int h2 = (int) (h >>> 32);
        for (int i = 0; i < numHashes; i++) {
            int bit = Math.floorMod(h1 + i * h2, numBits);
            if ((words[bit >>> 6] & (1L << (bit & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /** Serialized layout: [int numBits][int numHashes][int numWords][long * numWords]. */
    byte[] serialize() {
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 4 + words.length * 8);
        buf.putInt(numBits);
        buf.putInt(numHashes);
        buf.putInt(words.length);
        for (long w : words) {
            buf.putLong(w);
        }
        return buf.array();
    }

    static BloomFilter deserialize(ByteBuffer buf) {
        int numBits = buf.getInt();
        int numHashes = buf.getInt();
        int numWords = buf.getInt();
        long[] words = new long[numWords];
        for (int i = 0; i < numWords; i++) {
            words[i] = buf.getLong();
        }
        return new BloomFilter(words, numBits, numHashes);
    }

    /** FNV-1a 64-bit hash — small, dependency-free, good enough for a Bloom filter. */
    private static long hash64(byte[] data) {
        long h = 0xcbf29ce484222325L;
        for (byte b : data) {
            h ^= (b & 0xFF);
            h *= 0x100000001b3L;
        }
        // Final avalanche so the high and low 32 bits are both well-mixed.
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }
}
