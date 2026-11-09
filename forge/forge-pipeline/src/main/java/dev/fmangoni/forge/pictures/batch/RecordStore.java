package dev.fmangoni.forge.pictures.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.pictures.model.BatchRecord;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A batch's {@code record.json}, read and changed one change at a time.
 *
 * <p>The watcher and a person's clicks both change it. Each change reads the file, changes it and
 * writes it back under one lock per file, so neither can write over what the other just saved. The
 * lock is never held while a command runs: a check takes seconds, and a click would wait for it.
 */
public final class RecordStore {

    public static final String FILE = "record.json";

    private static final ObjectMapper JSON = Json.mapper();

    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private final Path file;

    private final Object lock;

    public RecordStore(Path batchRoot) {
        this.file = batchRoot.resolve(FILE).toAbsolutePath().normalize();
        this.lock = LOCKS.computeIfAbsent(file, key -> new Object());
    }

    public BatchRecord read() {
        synchronized (lock) {
            return load();
        }
    }

    /** Makes a change and saves it, and returns what the change returned. */
    public <T> T update(Function<BatchRecord, T> change) {
        synchronized (lock) {
            BatchRecord record = load();
            T result = change.apply(record);
            try {
                JsonFile.write(file, JSON.writeValueAsString(record));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write " + file, e);
            }
            return result;
        }
    }

    private BatchRecord load() {
        if (!Files.exists(file)) {
            return new BatchRecord();
        }
        try {
            return JSON.readValue(JsonFile.read(file), BatchRecord.class);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
