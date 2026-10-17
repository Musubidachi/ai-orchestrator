package dev.fmangoni.forge.review.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.Sitting;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * Every review a person has submitted on a project, oldest first.
 *
 * <p>Appended to, never rewritten: it is the record of what was decided, and a later sitting does
 * not change what an earlier one said.
 */
@UtilityClass
public class ReviewHistory {

    private final ObjectMapper JSON = Json.mapper();

    public List<Sitting> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return List.of(JSON.readValue(JsonFile.read(file), Sitting[].class));
        } catch (Exception e) {
            // Unreadable history must not stop a report or a submission; the cost is a list shown
            // shorter than it should be, and the file is left for a person to look at.
            System.err.println("Ignoring unreadable " + file + ": " + e.getMessage());
            return List.of();
        }
    }

    public void append(Path file, Sitting sitting) {
        List<Sitting> all = new ArrayList<>(load(file));
        all.add(sitting);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JsonFile.write(file, JSON.writeValueAsString(all));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** Every review item ever ruled on in a submitted sitting. */
    public Set<String> ruled(List<Sitting> history) {
        Set<String> ids = new HashSet<>();
        for (Sitting sitting : history) {
            for (Decision ruling : sitting.getRulings()) {
                ids.add(ruling.getItemId());
            }
        }
        return ids;
    }

    /** The most recent ruling on one item, with the sitting it was made in. */
    public Optional<Sitting> lastSittingFor(List<Sitting> history, String itemId) {
        for (int i = history.size() - 1; i >= 0; i--) {
            for (Decision ruling : history.get(i).getRulings()) {
                if (itemId.equals(ruling.getItemId())) {
                    return Optional.of(history.get(i));
                }
            }
        }
        return Optional.empty();
    }

    /** That ruling itself. */
    public Optional<Decision> lastRulingOn(List<Sitting> history, String itemId) {
        return lastSittingFor(history, itemId).flatMap(sitting -> sitting.getRulings().stream()
                .filter(ruling -> itemId.equals(ruling.getItemId()))
                .findFirst());
    }

    /**
     * Blocking findings a person has explicitly ruled acceptable, by their text.
     *
     * <p>From submitted sittings only. Rulings made before the history existed are left out on
     * purpose: the old form pre-ticked "Fine as it is", so an old "fine" may be a default nobody
     * chose, and telling a reviewer never to raise a real defect again on the strength of one would
     * be the worse mistake.
     */
    public List<String> ruledFine(List<Sitting> history) {
        List<String> fine = new ArrayList<>();
        for (Sitting sitting : history) {
            for (Decision ruling : sitting.getRulings()) {
                if (ruling.getItemId() != null && ruling.getItemId().startsWith("blocking-")
                        && "ok".equalsIgnoreCase(ruling.getVerdict()) && !fine.contains(ruling.getText())) {
                    fine.add(ruling.getText());
                }
            }
        }
        return fine;
    }
}
