package dev.fmangoni.forge.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.review.model.Review;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/** Persists {@code review.json}, and reads it back for the report. */
@UtilityClass
public class ReviewStore {

    private final ObjectMapper JSON = Json.mapper();

    /** @return the review, or {@code null} when the project never reached the review stage */
    public Review load(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JSON.readValue(JsonFile.read(file), Review.class);
        } catch (Exception e) {
            return Review.failed("could not read " + file.getFileName() + ": " + e.getMessage());
        }
    }

    public void save(Path file, Review review) {
        try {
            Files.createDirectories(file.getParent());
            JsonFile.write(file, JSON.writeValueAsString(review));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
