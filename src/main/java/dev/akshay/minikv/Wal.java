package dev.akshay.minikv;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log: the durability anchor. Every mutation is appended here (length-prefixed
 * with a CRC32) before it is acknowledged, so an acknowledged write survives a crash. On
 * startup the log is replayed to rebuild the memtable; after a flush it is rotated
 * (truncated), since its contents are then safely in an SSTable.
 *
 * <p>Record layout: {@code [int payloadLen][payload][int crc32]} where
 * {@code payload = [long seq][byte type][int keyLen][key][int valLen][value]}.
 * A torn write at the tail (partial record or bad CRC) ends replay cleanly — everything
 * before it was fully written and is therefore valid.
 */
final class Wal implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private final boolean syncOnWrite;

    Wal(Path path, boolean syncOnWrite) throws IOException {
        this.path = path;
        this.syncOnWrite = syncOnWrite;
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.channel.position(this.channel.size()); // append at end
    }

    void append(Record r) throws IOException {
        byte[] key = r.key().raw();
        byte[] val = r.value() == null ? new byte[0] : r.value();
        int payloadLen = 8 + 1 + 4 + key.length + 4 + val.length;

        ByteBuffer payload = ByteBuffer.allocate(payloadLen);
        payload.putLong(r.seq());
        payload.put(r.type().code);
        payload.putInt(key.length);
        payload.put(key);
        payload.putInt(val.length);
        payload.put(val);
        payload.flip();

        CRC32 crc = new CRC32();
        crc.update(payload.duplicate());

        ByteBuffer frame = ByteBuffer.allocate(4 + payloadLen + 4);
        frame.putInt(payloadLen);
        frame.put(payload);
        frame.putInt((int) crc.getValue());
        frame.flip();

        while (frame.hasRemaining()) {
            channel.write(frame);
        }
        if (syncOnWrite) {
            channel.force(false);
        }
    }

    /** Replays valid records in write order, stopping at the first torn/corrupt tail record. */
    void replay(Consumer<Record> consumer) throws IOException {
        long size = channel.size();
        if (size == 0) {
            return;
        }
        ByteBuffer all = ByteBuffer.allocate((int) size);
        channel.read(all, 0);
        all.flip();

        while (all.remaining() >= 4) {
            all.mark();
            int payloadLen = all.getInt();
            if (payloadLen < 0 || all.remaining() < payloadLen + 4) {
                all.reset();
                break; // truncated tail
            }
            byte[] payload = new byte[payloadLen];
            all.get(payload);
            int storedCrc = all.getInt();

            CRC32 crc = new CRC32();
            crc.update(payload);
            if ((int) crc.getValue() != storedCrc) {
                break; // corrupt tail
            }

            consumer.accept(decode(payload));
        }
    }

    private static Record decode(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        long seq = b.getLong();
        Record.ValueType type = Record.ValueType.fromCode(b.get());
        int keyLen = b.getInt();
        byte[] key = new byte[keyLen];
        b.get(key);
        int valLen = b.getInt();
        byte[] val = new byte[valLen];
        b.get(val);
        if (type == Record.ValueType.DELETE) {
            return Record.tombstone(Bytes.wrap(key), seq);
        }
        return Record.put(Bytes.wrap(key), val, seq);
    }

    /** Truncate the log to empty — called right after a successful flush. */
    void rotate() throws IOException {
        channel.truncate(0);
        channel.position(0);
        channel.force(true);
    }

    void sync() throws IOException {
        channel.force(false);
    }

    Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        channel.force(true);
        channel.close();
    }
}
