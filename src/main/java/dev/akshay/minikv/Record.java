package dev.akshay.minikv;

/**
 * A single mutation in the store. Everything written — puts and deletes alike — flows
 * through the system as a {@code Record}. A delete is just a {@link ValueType#DELETE}
 * record (a "tombstone") with no value.
 *
 * <p>The {@code seq} (sequence number) imposes a total order across the memtable and all
 * SSTables: for any given key, the record with the highest {@code seq} is the live one.
 */
public final class Record {

    public enum ValueType {
        PUT((byte) 1),
        DELETE((byte) 2);

        public final byte code;

        ValueType(byte code) {
            this.code = code;
        }

        public static ValueType fromCode(byte code) {
            switch (code) {
                case 1:
                    return PUT;
                case 2:
                    return DELETE;
                default:
                    throw new IllegalArgumentException("Unknown value type code: " + code);
            }
        }
    }

    private final Bytes key;
    private final byte[] value; // null for tombstones
    private final long seq;
    private final ValueType type;

    private Record(Bytes key, byte[] value, long seq, ValueType type) {
        this.key = key;
        this.value = value;
        this.seq = seq;
        this.type = type;
    }

    public static Record put(Bytes key, byte[] value, long seq) {
        return new Record(key, value, seq, ValueType.PUT);
    }

    public static Record tombstone(Bytes key, long seq) {
        return new Record(key, null, seq, ValueType.DELETE);
    }

    public Bytes key() {
        return key;
    }

    public byte[] value() {
        return value;
    }

    public long seq() {
        return seq;
    }

    public ValueType type() {
        return type;
    }

    public boolean isTombstone() {
        return type == ValueType.DELETE;
    }

    @Override
    public String toString() {
        return type + "(" + key + ", seq=" + seq + ")";
    }
}
