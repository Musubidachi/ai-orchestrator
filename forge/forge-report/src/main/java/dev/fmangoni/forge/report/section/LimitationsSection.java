package dev.fmangoni.forge.report.section;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * What the passing commands still do not prove, in the builder's own words.
 *
 * <p>Placed near the verdict rather than at the bottom, because this is precisely the moment a
 * reader decides whether to trust a green tick — and the gate can only ever check what the spec
 * thought to check.
 */
@UtilityClass
public class LimitationsSection {

    public String render(RunState state) {
        List<String> gaps = state.getPlan() == null ? List.of() : state.getPlan().getNotVerified();
        if (gaps == null || gaps.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>What this does not prove</h2><div class=\"card\"><ul>");
        gaps.forEach(gap -> out.append("<li>").append(Html.escape(gap)).append("</li>"));
        return out.append("</ul><p class=\"muted\">Reported by the model that built it. "
                + "The acceptance commands passed; these are the things they do not cover.</p>"
                + "</div>").toString();
    }
}
