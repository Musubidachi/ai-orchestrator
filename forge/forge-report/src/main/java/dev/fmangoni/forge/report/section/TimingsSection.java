package dev.fmangoni.forge.report.section;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.TimingTrend;
import dev.fmangoni.forge.state.Timings;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * What each acceptance command costs, and whether that has changed.
 *
 * <p>The only performance reporting here that is worth trusting. An absolute threshold on a machine
 * that is also running a browser and a virus scanner says nothing; the same machine comparing a
 * command against its own history says something real. It is reported, never enforced — a build
 * that failed because a laptop was busy would be worse than no measurement.
 */
@UtilityClass
public class TimingsSection {

    public String render(RunState state) {
        List<TimingTrend> trends = Timings.of(state);
        if (trends.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("<h2>How long the checks take</h2><div class=\"card\"><ul>");
        for (TimingTrend trend : trends) {
            out.append("<li><code>").append(Html.escape(trend.command())).append("</code> — ")
                    .append(trend.latest()).append("s");
            if (trend.runs() > 1) {
                out.append(" (usually ").append(trend.usual()).append("s over ")
                        .append(trend.runs()).append(" runs)");
            }
            if (trend.slowerThanUsual()) {
                out.append(" <strong>— slower than usual; worth a look</strong>");
            }
            out.append("</li>");
        }
        return out.append("</ul></div>").toString();
    }
}
