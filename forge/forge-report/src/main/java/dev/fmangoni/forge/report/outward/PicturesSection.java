package dev.fmangoni.forge.report.outward;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.spec.SpecPictures;
import dev.fmangoni.forge.spec.model.PictureRow;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * Each picture the spec was built to, beside the builder's screenshot of the same state.
 *
 * <p>A spec built to pictures has a table, "Picture | State | Your screenshot of it", and the
 * question it raises is the one a polish raises: did it come out like the picture. Neither the gate
 * nor a review answers that, so the page puts the two side by side, row by row, the way
 * {@link PolishSection} does for a chosen design.
 *
 * <p>The spec is read from the copy Forge keeps in the repository: {@code IMPROVEMENT.md} while it
 * is this version's, otherwise {@code SPEC.md} when it is this project's own. A family's earlier
 * version, whose {@code IMPROVEMENT.md} a later one has replaced, shows nothing rather than another
 * version's pictures.
 */
@UtilityClass
public class PicturesSection {

    public String render(RunPaths paths, RunState state) {
        List<PictureRow> rows = specText(paths, state).map(SpecPictures::rows).orElse(List.of());
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2 id=\"pictures\">Pictures</h2><div class=\"card\">"
                + "<p>Each picture the spec was built to, beside the screenshot of the same state.</p>");
        for (PictureRow row : rows) {
            out.append("<h3>").append(Html.escape(row.state())).append("</h3><div class=\"polish-pair\">")
                    .append(figure(paths, row.picture(), "The picture",
                            "The picture is not in the repository."))
                    .append(figure(paths, row.screenshot(), "The screenshot",
                            "No screenshot yet. The gate has not passed, or the suite does not save it."))
                    .append("</div>");
        }
        return out.append("<p class=\"muted\">The screenshots are saved by the project's own suite. "
                + "Whether each one matches its picture is for you, or the review, to say.</p></div>")
                .toString();
    }

    /** This project's spec as Forge last wrote it into the repository, if it is this project's. */
    private Optional<String> specText(RunPaths paths, RunState state) {
        String title = state.getTitle() == null ? "" : state.getTitle().strip();
        Optional<String> improvement = read(paths.repo().resolve("IMPROVEMENT.md"));
        if (improvement.isPresent()) {
            return improvement.filter(text -> firstLine(text).equals("# Improvement: " + title));
        }
        return read(paths.repo().resolve("SPEC.md")).filter(text -> firstLine(text).equals("# " + title));
    }

    private String firstLine(String text) {
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).strip();
    }

    private Optional<String> read(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private String figure(RunPaths paths, String relative, String label, String whyMissing) {
        Optional<Path> file = inRepository(paths, relative).filter(Files::isRegularFile);
        if (file.isEmpty()) {
            return "<figure><div class=\"polish-missing\">" + Html.escape(whyMissing) + "</div><figcaption>"
                    + label + " · " + Html.escape(relative) + "</figcaption></figure>";
        }
        String href = Html.escape(paths.root().toAbsolutePath().normalize().relativize(file.get())
                .toString().replace('\\', '/'));
        return "<figure><a href=\"" + href + "\" target=\"_blank\"><img src=\"" + href + "\" alt=\""
                + label + "\"></a><figcaption>" + label + " · " + Html.escape(relative)
                + "</figcaption></figure>";
    }

    private Optional<Path> inRepository(RunPaths paths, String relative) {
        Path repo = paths.repo().toAbsolutePath().normalize();
        try {
            Path file = repo.resolve(relative).normalize();
            return file.startsWith(repo) ? Optional.of(file) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
