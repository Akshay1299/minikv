package dev.akshay.minikv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The source of truth for which SSTables are live and in what order (newest first). Stored
 * as a tiny text file, one SSTable file name per line, and rewritten atomically (temp +
 * atomic rename) so a crash mid-update can never leave a half-written manifest.
 */
final class Manifest {

    private static final String FILE = "MANIFEST";

    private final Path dir;
    private final List<String> sstables; // newest first

    private Manifest(Path dir, List<String> sstables) {
        this.dir = dir;
        this.sstables = sstables;
    }

    static Manifest load(Path dir) throws IOException {
        Path f = dir.resolve(FILE);
        List<String> names = new ArrayList<>();
        if (Files.exists(f)) {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String name = line.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return new Manifest(dir, names);
    }

    /** Current SSTable file names, newest first. */
    List<String> sstables() {
        return new ArrayList<>(sstables);
    }

    void prepend(String name) throws IOException {
        sstables.add(0, name);
        persist();
    }

    /** Replace the entire set (used by compaction). */
    void replaceAll(List<String> names) throws IOException {
        sstables.clear();
        sstables.addAll(names);
        persist();
    }

    private void persist() throws IOException {
        Path tmp = dir.resolve(FILE + ".tmp");
        Files.write(tmp, sstables, StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(FILE),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
