package dev.akshay.minikv;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Immutable wrapper over a {@code byte[]} that orders keys
 * <b>unsigned lexicographically</b> — the ordering a real storage engine needs so that
 * byte 0x80 sorts after 0x7F rather than before it (as signed {@code byte} comparison
 * would). Used as the key type in the memtable and for sorting SSTable entries.
 */
public final class Bytes implements Comparable<Bytes> {

    private final byte[] data;
    private int hash; // cached

    private Bytes(byte[] data) {
        this.data = data;
    }

    /** Wraps the array directly (no copy). Callers must not mutate it afterwards. */
    public static Bytes wrap(byte[] data) {
        return new Bytes(data);
    }

    /** Copies the array defensively. */
    public static Bytes copyOf(byte[] data) {
        return new Bytes(Arrays.copyOf(data, data.length));
    }

    public static Bytes of(String s) {
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] raw() {
        return data;
    }

    public int length() {
        return data.length;
    }

    public String asString() {
        return new String(data, StandardCharsets.UTF_8);
    }

    @Override
    public int compareTo(Bytes other) {
        return compare(this.data, other.data);
    }

    /** Unsigned lexicographic comparison of two byte arrays. */
    public static int compare(byte[] a, byte[] b) {
        int min = Math.min(a.length, b.length);
        for (int i = 0; i < min; i++) {
            int x = a[i] & 0xFF;
            int y = b[i] & 0xFF;
            if (x != y) {
                return x - y;
            }
        }
        return a.length - b.length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bytes)) return false;
        return Arrays.equals(data, ((Bytes) o).data);
    }

    @Override
    public int hashCode() {
        int h = hash;
        if (h == 0 && data.length > 0) {
            h = Arrays.hashCode(data);
            hash = h;
        }
        return h;
    }

    @Override
    public String toString() {
        return asString();
    }
}
