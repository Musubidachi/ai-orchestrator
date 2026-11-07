package dev.fmangoni.forge.report.changes;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.shell.Html;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * Where each screenshot changed since the version of this family you accepted last, washed red.
 *
 * <p>Informational: it fails nothing and asks nothing. It is here so that a screen you already
 * accepted can be looked at only where something moved, instead of all over again. Only
 * screenshots saved under the same name by both versions are compared; every version's suite runs
 * the earlier versions' tests, so most screens are.
 *
 * <p>The red pictures are written beside the report, under {@code changes/}, and only rewritten
 * when a screenshot is newer than the picture.
 */
public final class ScreenChangesSection {

    private ScreenChangesSection() {
    }

    public static String render(RunPaths paths) {
        Path root = paths.root().toAbsolutePath().normalize();
        Optional<Path> accepted = LastAccepted.of(new RunPaths(root, paths.repo().toAbsolutePath().normalize()));
        Optional<Path> now = LastAccepted.latestEvidence(root);
        if (accepted.isEmpty() || now.isEmpty()) {
            return "";
        }
        Optional<Path> then = LastAccepted.latestEvidence(accepted.get());
        if (then.isEmpty()) {
            return "";
        }
        List<String> same = new ArrayList<>();
        StringBuilder changed = new StringBuilder();
        for (Path picture : LastAccepted.pictures(now.get())) {
            Path name = now.get().relativize(picture);
            Path before = then.get().resolve(name);
            if (Files.isRegularFile(before)) {
                compare(root, name, before, picture).ifPresentOrElse(changed::append,
                        () -> same.add(name.toString().replace('\\', '/')));
            }
        }
        if (changed.isEmpty() && same.isEmpty()) {
            return "";
        }
        String version = accepted.get().getFileName().toString();
        return "<h2 id=\"screen-changes\">What changed since you accepted " + Html.escape(version) + "</h2><div class=\"card\">"
                + "<p>Each screenshot both versions saved, with the parts that changed shape washed red. "
                + "A change of colour alone is not marked. Nothing here fails the project.</p>"
                + changed
                + (same.isEmpty() ? "" : "<p class=\"muted\">The same as when you accepted it: "
                        + Html.escape(String.join(", ", same)) + ".</p>")
                + "</div>";
    }

    /** One changed screen as a figure pair, or nothing when it did not change. */
    private static Optional<String> compare(Path root, Path name, Path before, Path after) {
        try {
            BufferedImage then = ImageIO.read(before.toFile());
            BufferedImage now = ImageIO.read(after.toFile());
            if (then == null || now == null) {
                return Optional.empty();
            }
            boolean[][] cells = ChangedCells.between(then, now);
            String title = Html.escape(name.toString().replace('\\', '/'));
            if (cells == null) {
                return Optional.of("<h3>" + title + "</h3><p>The screenshot changed size, from " + then.getWidth()
                        + "×" + then.getHeight() + " to " + now.getWidth() + "×" + now.getHeight() + ".</p>"
                        + pair(root, before, after, "Now"));
            }
            int count = ChangedCells.count(cells);
            if (count == 0) {
                return Optional.empty();
            }
            Path overlay = root.resolve("changes").resolve(name.toString().replace('\\', '_').replace('/', '_'));
            if (!Files.isRegularFile(overlay)
                    || Files.getLastModifiedTime(overlay).compareTo(Files.getLastModifiedTime(after)) < 0) {
                ChangeOverlay.write(now, cells, overlay);
            }
            return Optional.of("<h3>" + title + "</h3>" + pair(root, before, overlay,
                    "Now, " + count + (count == 1 ? " part" : " parts") + " changed"));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String pair(Path root, Path before, Path after, String afterLabel) {
        return "<div class=\"polish-pair\">" + figure(root, before, "When you accepted it")
                + figure(root, after, afterLabel) + "</div>";
    }

    private static String figure(Path root, Path file, String label) {
        String href = Html.escape(root.relativize(file).toString().replace('\\', '/'));
        return "<figure><a href=\"" + href + "\" target=\"_blank\"><img src=\"" + href + "\" alt=\""
                + Html.escape(label) + "\"></a><figcaption>" + Html.escape(label) + "</figcaption></figure>";
    }
}
