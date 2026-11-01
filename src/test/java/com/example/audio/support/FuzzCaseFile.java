package com.example.audio.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The fuzz cases being run, one per line in {@code target/fuzz/<name>.case}. FFmpeg runs in the JVM: if it crashes
 * the process, this file still names the inputs in flight (seed, index, mutation). The file is removed when the test
 * ends, so a file left behind means the JVM died on one of those cases.
 */
public final class FuzzCaseFile implements AutoCloseable {

    private final Path file;
    private final Set<String> running = ConcurrentHashMap.newKeySet();
    /** Each write is a snapshot of the running cases: writes in sequence, so an older one never replaces a newer one. */
    private final Lock writes = new ReentrantLock();

    public FuzzCaseFile(final String name) {
        this.file = Path.of(System.getProperty("fuzz.dir", "target/fuzz")).resolve(name + ".case");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs a case, recorded in the file while it runs. */
    public void run(final String description, final Runnable job) {
        running.add(description);
        write();
        try {
            job.run();
        } finally {
            running.remove(description);
            write();
        }
    }

    private void write() {
        writes.lock();
        try {
            Files.write(file, running);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writes.unlock();
        }
    }

    @Override
    public void close() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
