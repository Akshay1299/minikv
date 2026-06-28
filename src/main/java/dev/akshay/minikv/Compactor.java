package dev.akshay.minikv;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Size-tiered compaction (v1): merge every live SSTable into a single new one. Because a
 * full merge sees all data for a key, it can safely keep only the highest-sequence record
 * per key and <b>physically drop tombstones and overwritten versions</b> — reclaiming space
 * and bounding read amplification (one file instead of many).
 */
final class Compactor {

    private Compactor() {
    }

    /**
     * Merges {@code inputs} into a new SSTable at {@code newFile}.
     *
     * @return the opened reader for the merged table.
     */
    static SSTable compactAll(List<SSTable> inputs, java.nio.file.Path newFile, Config cfg)
            throws IOException {
        // Keep the highest-seq record per key across all inputs.
        TreeMap<Bytes, Record> merged = new TreeMap<>();
        for (SSTable t : inputs) {
            for (Record r : t.readAll()) {
                Record existing = merged.get(r.key());
                if (existing == null || r.seq() > existing.seq()) {
                    merged.put(r.key(), r);
                }
            }
        }

        // Drop tombstones — there is no older data to shadow once everything is merged.
        java.util.ArrayList<Record> live = new java.util.ArrayList<>(merged.size());
        for (Map.Entry<Bytes, Record> e : merged.entrySet()) {
            if (!e.getValue().isTombstone()) {
                live.add(e.getValue());
            }
        }

        return SSTable.write(newFile, live, live.size(), cfg.indexInterval, cfg.bloomBitsPerKey);
    }
}
