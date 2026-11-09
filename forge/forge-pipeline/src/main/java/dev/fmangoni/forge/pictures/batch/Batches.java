package dev.fmangoni.forge.pictures.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.pictures.model.BatchSettings;
import dev.fmangoni.forge.pictures.model.PictureJob;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;

/**
 * Finds the batch folders under {@code runs/} and reads them.
 *
 * <p>A batch is a folder there with a {@code batch.json}. It has no {@code state.json}, so everything
 * that looks after projects passes it by.
 */
@UtilityClass
public class Batches {

    private final ObjectMapper JSON = Json.mapper();

    private final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /** Every batch folder, by name. */
    public List<Path> folders(Path runsDir) {
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(runsDir)) {
            return children.filter(dir -> Files.isRegularFile(dir.resolve(Batch.SETTINGS))).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The batch folder with this name, if there is one. */
    public Optional<Path> folder(Path runsDir, String id) {
        if (id == null || !NAME.matcher(id).matches()) {
            return Optional.empty();
        }
        Path dir = runsDir.resolve(id);
        return Files.isRegularFile(dir.resolve(Batch.SETTINGS)) ? Optional.of(dir) : Optional.empty();
    }

    /**
     * Reads a batch folder.
     *
     * @throws UncheckedIOException     when its settings or its list cannot be read
     * @throws IllegalArgumentException saying what is wrong with the list
     */
    public Batch load(Path root) {
        try {
            BatchSettings settings = JSON.readValue(JsonFile.read(root.resolve(Batch.SETTINGS)), BatchSettings.class);
            String cards = Files.readString(root.resolve(settings.cards()), StandardCharsets.UTF_8);
            if (!cards.isEmpty() && cards.charAt(0) == BYTE_ORDER_MARK) {
                cards = cards.substring(1);
            }
            List<PictureJob> jobs = JobCards.parse(cards);
            if (jobs.isEmpty()) {
                throw new IllegalArgumentException(settings.cards() + " has no jobs");
            }
            return new Batch(root, settings, jobs);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the batch in " + root.getFileName() + ": " + e.getMessage(), e);
        }
    }
}
