package dev.fmangoni.forge.report.look;

import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.Progress;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * What the header says beside the title: the phase once, as a chip, and a line of who built it and
 * how many sessions it took.
 *
 * <p>The chip says what the project waits for in the reader's terms. A project held for a person is
 * "Needs you" whatever phase it stopped in, as the review's own notice says; one suspended on its
 * way into the cross-review waits for that, not for "quota", which is what its phase alone would say.
 */
@UtilityClass
public class LookHead {

    private final String CLOCK = "<svg viewBox=\"0 0 24 24\" aria-hidden=\"true\">"
            + "<circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 7v5l3 2\"/></svg>";

    public String render(RunState state) {
        return chip(state) + meta(state);
    }

    private String chip(RunState state) {
        Phase phase = state.getPhase();
        boolean held = state.getNeedsPerson() != null && !phase.terminal();
        boolean awaitingReview = phase == Phase.SUSPENDED && state.getResumePhase() == Phase.REVIEWING;
        String label;
        if (held) {
            label = "Needs you";
        } else if (awaitingReview) {
            label = "Waiting for the cross-review";
        } else {
            label = capitalized(Progress.label(phase));
        }
        String icon = phase == Phase.SUSPENDED && !held ? CLOCK : "";
        return "<span class=\"rl-chip " + (held ? Phase.PARKED : phase) + "\">"
                + icon + Html.escape(label) + "</span>\n";
    }

    private String meta(RunState state) {
        List<String> said = new ArrayList<>();
        if (state.getId() != null) {
            said.add(state.getId());
        }
        if (state.getAssignedTo() != null) {
            said.add("built by " + capitalized(state.getAssignedTo().name().toLowerCase(Locale.ROOT)));
        }
        if (state.getSessions() > 0) {
            said.add(counted(state.getSessions(), "session"));
        }
        if (state.getRepairRounds() > 0) {
            said.add(counted(state.getRepairRounds(), "repair round"));
        }
        return "<span class=\"rl-meta\">" + Html.escape(String.join(" · ", said)) + "</span>\n";
    }

    private String counted(int count, String what) {
        return count + " " + what + (count == 1 ? "" : "s");
    }

    private String capitalized(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
