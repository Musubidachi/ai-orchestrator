package dev.fmangoni.forge.ui.editor;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecException;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.SpecWriter;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The editor's operations over the wishlist directory.
 *
 * <p>Every save is validated through {@link SpecLoader} before it touches the disk, so the wishlist
 * cannot accumulate files that the overnight run will silently skip.
 */
public final class SpecApi {

    private final Path wishlist;

    /** Where run directories live, or null when there are none to consult. */
    private final Path runs;

    public SpecApi(Path wishlist) {
        this(wishlist, null);
    }

    public SpecApi(Path wishlist, Path runs) {
        this.wishlist = wishlist;
        this.runs = runs;
    }

    public List<SpecSummary> list() {
        if (!Files.isDirectory(wishlist)) {
            return List.of();
        }
        try (var files = Files.list(wishlist)) {
            List<SpecSummary> out = new ArrayList<>();
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                out.add(summarise(file));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + wishlist, e);
        }
    }

    private SpecSummary summarise(Path file) {
        String name = file.getFileName().toString();
        try {
            Spec spec = SpecLoader.load(file);
            return new SpecSummary(name, spec.id(), spec.title(),
                    spec.front().getAssign(), true, null);
        } catch (SpecException e) {
            return new SpecSummary(name, "", name, "", false, e.getMessage());
        }
    }

    public Draft open(String fileName) {
        Path file = resolve(fileName);
        Spec spec = SpecLoader.load(file);
        Draft draft = new Draft();
        draft.setFront(spec.front());
        draft.setBody(spec.body());
        draft.setOriginalFile(fileName);
        return draft;
    }

    /** Validates without writing, so the editor can show problems as they are introduced. */
    public SaveResult validate(Draft draft) {
        String rendered = SpecWriter.render(draft.getFront(), draft.getBody());
        String target = SpecWriter.fileName(draft.getFront());
        try {
            Spec spec = SpecLoader.parse(wishlist.resolve(target), rendered);
            String clash = claimedBy(spec.id(), draft.getOriginalFile());
            if (clash != null) {
                return new SaveResult(target, false, "id '" + spec.id() + "' is already used by "
                        + clash + ". Each spec needs its own id: the id names its run directory.");
            }
            String previous = earlierProject(spec);
            return previous == null
                    ? new SaveResult(target, true, null)
                    : new SaveResult(target, false, "runs/" + spec.id() + " already holds \""
                            + previous + "\", from a spec no longer in the wishlist. Under this id "
                            + "the new spec would carry on that project's work. Pick another id, or "
                            + "give it the same title to bring that spec back.");
        } catch (SpecException e) {
            return new SaveResult(target, false, e.getMessage());
        }
    }

    /**
     * The proposed id if a new spec can have it, otherwise the next free number.
     *
     * <p>For suggestions. The model is shown the ids in use and still chooses its own, and a taken
     * one filled a form that the save then refused. Numbers are counted past run directories too,
     * so a suggestion never lands on a deleted spec's leftover run.
     */
    public String usableId(String proposed) {
        boolean safe = proposed != null
                && proposed.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") && !proposed.contains("..");
        if (safe && claimedBy(proposed, null) == null
                && (runs == null || !Files.exists(runs.resolve(proposed)))) {
            return proposed;
        }
        int highest = 0;
        for (SpecSummary summary : list()) {
            highest = Math.max(highest, leadingNumber(summary.id()));
        }
        if (runs != null && Files.isDirectory(runs)) {
            try (var dirs = Files.list(runs)) {
                for (Path dir : dirs.toList()) {
                    highest = Math.max(highest, leadingNumber(dir.getFileName().toString()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list " + runs, e);
            }
        }
        return String.format("%03d", highest + 1);
    }

    /** 7 for "007", 12 for "012.2", 0 for anything not starting with digits. */
    private int leadingNumber(String id) {
        Matcher digits = Pattern.compile("^(\\d{1,9})").matcher(
                id == null ? "" : id);
        return digits.find() ? Integer.parseInt(digits.group(1)) : 0;
    }

    /**
     * The title of a project already run under this id by a spec that has since gone, or null.
     *
     * <p>A run directory outlives its spec. Delete 007, write a different 007, and the new spec
     * resumes the old one's state file, repository and reviews — suspended where the other left
     * off, or DONE before a line of it was built. A matching title is let through: that is somebody
     * restoring the spec they deleted, which should carry on.
     */
    private String earlierProject(Spec spec) {
        if (runs == null || claimedBy(spec.id(), null) != null) {
            return null;
        }
        return RunStore.survey(
                        RunPaths.under(runs, spec.id()).stateFile())
                .map(RunState::getTitle)
                .filter(title -> title != null && !title.strip().equalsIgnoreCase(spec.title().strip()))
                .orElse(null);
    }

    /**
     * Another wishlist file holding this id, or null.
     *
     * <p>The id is the name of a project's run directory, so two specs sharing one would build in
     * the same repository against the same state file, each resuming what the other left. Nothing
     * stopped it: an id is typed by hand, or taken from a suggestion that was told the ids in use
     * and is free to ignore them. Saving a new spec whose id and title matched an existing one also
     * overwrote that file without a word, since both render to the same filename.
     */
    private String claimedBy(String id, String originalFile) {
        return list().stream()
                .filter(summary -> summary.valid() && summary.id().equals(id))
                .map(SpecSummary::file)
                .filter(file -> !file.equals(originalFile))
                .findFirst()
                .orElse(null);
    }

    /**
     * Validates, writes, and removes the previous file if the spec was renamed.
     *
     * <p>Refuses to write an invalid spec at all. A wishlist entry that cannot be loaded is worse
     * than no entry: it is skipped at run time, so the project quietly never gets built and the
     * only sign is a line in a log nobody is reading.
     */
    public SaveResult save(Draft draft) {
        SaveResult check = validate(draft);
        if (!check.valid()) {
            return check;
        }
        Path written = SpecWriter.write(wishlist, draft.getFront(), draft.getBody());
        removeRenamed(draft, written);
        return new SaveResult(written.getFileName().toString(), true, null);
    }

    private void removeRenamed(Draft draft, Path written) {
        String original = draft.getOriginalFile();
        if (original == null || original.isBlank()
                || original.equals(written.getFileName().toString())) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(original));
        } catch (IOException e) {
            throw new UncheckedIOException("renamed spec, but could not remove " + original, e);
        }
    }

    public void delete(String fileName) {
        try {
            Files.deleteIfExists(resolve(fileName));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot delete " + fileName, e);
        }
    }

    public String raw(String fileName) {
        try {
            return Files.readString(resolve(fileName), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + fileName, e);
        }
    }

    /**
     * Confines every filename to the wishlist directory.
     *
     * <p>The server is bound to loopback, but a traversal bug would still let any page open in the
     * browser reach arbitrary files through it, so the check belongs here rather than in the routing.
     */
    private Path resolve(String fileName) {
        if (fileName == null || fileName.isBlank() || !fileName.endsWith(".md")) {
            throw new IllegalArgumentException("not a spec filename: " + fileName);
        }
        Path resolved = wishlist.resolve(fileName).normalize();
        if (!resolved.startsWith(wishlist.normalize()) || resolved.getParent() == null
                || !resolved.getParent().equals(wishlist.normalize())) {
            throw new IllegalArgumentException("outside the wishlist: " + fileName);
        }
        return resolved;
    }
}
