package dev.fmangoni.forge.report.section;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/** The title, who built it, and the acceptance result, stated plainly, including for a project that failed. */
@UtilityClass
public class VerdictSection {

    public String render(RunState state) {
        return header(state) + verdict(state);
    }

    private String header(RunState state) {
        return """
                <h1>%s</h1>
                <p class="sub"><span class="tag %s">%s</span> &nbsp; built by %s &nbsp;·&nbsp; \
                %d repair round(s)</p>
                """.formatted(
                Html.escape(state.getTitle()),
                state.getPhase(), state.getPhase(),
                state.getAssignedTo(), state.getRepairRounds());
    }

    private String verdict(RunState state) {
        CommandResult failure = state.getLastFailure();
        if (failure == null && state.getLastError() == null) {
            return passed(state.getNotRunAtLastGate());
        }
        StringBuilder out = new StringBuilder("<div class=\"card\"><strong>Not accepted.</strong>");
        if (state.getLastError() != null) {
            out.append("<p class=\"muted\">").append(Html.escape(state.getLastError())).append("</p>");
        }
        if (failure != null) {
            out.append("<p class=\"muted\">Last failing command:</p><pre>")
                    .append(Html.escape(failure.command()))
                    .append("</pre><pre>")
                    .append(Html.escape(failure.output()))
                    .append("</pre>")
                    .append("<p class=\"muted\">Rerun it yourself: <code>")
                    .append(Html.escape(failure.script()))
                    .append("</code></p>");
        }
        return out.append("</div>").toString();
    }

    /**
     * A gate that passed, and what it covers. A quick gate ({@code QuickGate}) ran only some of the
     * commands, and calling that "all" would read as a version ready for review.
     */
    private String passed(List<String> notRun) {
        if (notRun == null || notRun.isEmpty()) {
            return "<div class=\"card\"><strong>All acceptance commands passed.</strong></div>";
        }
        return "<div class=\"card\"><strong>The quicker acceptance commands passed.</strong>"
                + "<p class=\"muted\">The builder says the work is unfinished, so these wait for the full"
                + " gate:</p><pre>" + Html.escape(String.join("\n", notRun)) + "</pre></div>";
    }
}
