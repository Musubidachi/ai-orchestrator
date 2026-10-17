package dev.fmangoni.forge.report.section;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import lombok.experimental.UtilityClass;

/**
 * The one class that shows the shape of the whole program, shown in full.
 *
 * <p>Near the top, and before the caveats, because this is the question a reader actually arrives
 * with: do I agree with how this was built. Answering it should not require cloning anything — and
 * reading the real class rather than a summary of it means what is shown cannot have drifted from
 * what runs.
 */
@UtilityClass
public class ApproachSection {

    private final int LIMIT = 20_000;

    public String render(RunPaths paths, RunState state) {
        String path = state.getPlan() == null ? "" : state.getPlan().getOverview();
        if (path == null || path.isBlank()) {
            return "";
        }
        Path file = inRepository(paths.repo(), path);
        if (file == null || !Files.isRegularFile(file)) {
            return "<h2>The approach</h2><p class=\"muted\">The handoff named <code>"
                    + Html.escape(path) + "</code>, which is not in the repository.</p>";
        }
        return """
                <h2>The approach</h2>
                <p class="muted">%s — the program's top-level flow, as it actually runs.</p>
                <pre>%s</pre>
                """.formatted(Html.escape(path), Html.escape(read(file)));
    }

    /**
     * The named file, or null when the name is not a path inside the repository.
     *
     * <p>The name is the agent's, and an agent that could not finish writes prose there — which on
     * Windows can be an illegal path, and threw here while the report was being written.
     */
    private Path inRepository(Path repo, String relative) {
        try {
            Path file = repo.resolve(relative).normalize();
            return file.startsWith(repo.normalize()) ? file : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Truncated: an overview long enough to hit this is itself worth knowing about. */
    private String read(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.length() <= LIMIT ? text : text.substring(0, LIMIT) + "\n...[truncated; see the repository]";
        } catch (IOException e) {
            return "(could not read " + file.getFileName() + ")";
        }
    }
}
