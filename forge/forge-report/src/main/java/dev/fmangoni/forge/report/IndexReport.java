package dev.fmangoni.forge.report;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * The morning skim: every project, its state, and what it is waiting on.
 *
 * <p>Ordered so the rows needing a decision come first. Finished work sinks to the bottom, because
 * the point of an unattended pipeline is that most mornings most of it needs nothing from you.
 */
@RequiredArgsConstructor
public final class IndexReport {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    private final Path root;
    private final List<RunState> states;

    public void write() {
        String body = """
                <h1>Forge</h1>
                <p class="sub">%d project(s) · generated %s</p>
                <table>
                <tr><th>Project</th><th>State</th><th>Built by</th><th>Waiting on</th><th>Updated</th></tr>
                %s
                </table>
                """.formatted(states.size(), STAMP.format(Instant.now()), rows());

        try {
            Files.writeString(root.resolve("index.html"),
                    Html.page("Forge", body), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write index.html", e);
        }
    }

    private String rows() {
        return states.stream()
                .sorted(Comparator
                        .comparingInt((RunState s) -> priority(s.getPhase()))
                        .thenComparing(RunState::getId))
                .map(this::row)
                .reduce("", String::concat);
    }

    /** Parked projects need a person; suspended ones need only time; done ones need nothing. */
    private int priority(Phase phase) {
        return switch (phase) {
            case PARKED -> 0;
            case DONE -> 3;
            case SUSPENDED -> 2;
            default -> 1;
        };
    }

    private String row(RunState state) {
        return """
                <tr>
                  <td><a href="runs/%s/report.html">%s</a></td>
                  <td><span class="tag %s">%s</span></td>
                  <td>%s</td>
                  <td class="muted">%s</td>
                  <td class="muted">%s</td>
                </tr>
                """.formatted(
                Html.escape(state.getId()),
                Html.escape(state.getTitle()),
                state.getPhase(), state.getPhase(),
                state.getAssignedTo() == null ? "—" : state.getAssignedTo(),
                Html.escape(waitingOn(state)),
                state.getUpdatedAt() == null ? "—" : STAMP.format(state.getUpdatedAt()));
    }

    private String waitingOn(RunState state) {
        if (state.getNeedsPerson() != null && !state.getPhase().terminal()) {
            return "you — " + summarise(state.getNeedsPerson());
        }
        return switch (state.getPhase()) {
            case DONE -> "nothing";
            case PARKED -> "you — " + summarise(state.getLastError());
            case SUSPENDED -> state.getRetryAfter() == null
                    ? "a quota window"
                    : "quota, until " + STAMP.format(state.getRetryAfter());
            default -> "in progress";
        };
    }

    private String summarise(String error) {
        if (error == null || error.isBlank()) {
            return "see the report";
        }
        String firstLine = error.lines().findFirst().orElse(error);
        return firstLine.length() > 90 ? firstLine.substring(0, 90) + "…" : firstLine;
    }
}
