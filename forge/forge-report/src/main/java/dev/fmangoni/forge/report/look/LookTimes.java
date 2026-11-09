package dev.fmangoni.forge.report.look;

import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.TimingTrend;
import dev.fmangoni.forge.state.Timings;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * Each acceptance command's latest time as a bar, all on one scale, with a tick at its usual time.
 *
 * <p>The same trends the page's own list gives ({@code Timings.of}), so the two cannot disagree:
 * the slow one is the one the list calls slower than usual. One scale, so the command that costs the
 * gate its minutes is the long bar. No total: the latest times can come from different gates, and
 * adding them would give a time no gate took.
 */
@UtilityClass
public class LookTimes {

    public String render(RunState state) {
        List<TimingTrend> trends = Timings.of(state);
        if (trends.isEmpty()) {
            return "";
        }
        int scale = trends.stream().mapToInt(trend -> Math.max(trend.latest(), trend.usual())).max().orElse(0);
        StringBuilder out = new StringBuilder("<div class=\"rl-times card\">\n");
        trends.forEach(trend -> out.append(row(trend, scale)));
        if (trends.stream().anyMatch(trend -> trend.runs() > 1)) {
            out.append("<p class=\"rl-foot\">The tick is the usual time: the middle of the runs before the latest.</p>\n");
        }
        return out.append("</div>\n").toString();
    }

    private String row(TimingTrend trend, int scale) {
        boolean slow = trend.slowerThanUsual();
        boolean history = trend.runs() > 1;
        StringBuilder out = new StringBuilder("<div class=\"rl-time").append(slow ? " rl-slow" : "").append("\">")
                .append("<code>").append(Html.escape(trend.command())).append("</code>")
                .append("<span class=\"rl-track\"><span class=\"rl-bar\" style=\"width:")
                .append(share(trend.latest(), scale)).append("\"></span>");
        if (history) {
            out.append("<span class=\"rl-tick\" style=\"left:").append(share(trend.usual(), scale)).append("\"></span>");
        }
        out.append("</span><span class=\"rl-took\"><b>").append(took(trend.latest())).append("</b>");
        if (history) {
            out.append(" · usually ").append(took(trend.usual()));
        }
        if (slow) {
            out.append(" · <em>slower than usual</em>");
        }
        return out.append("</span></div>\n").toString();
    }

    private String share(int seconds, int scale) {
        double percent = scale > 0 ? 100.0 * seconds / scale : 0;
        return String.format(Locale.ROOT, "%.1f%%", percent);
    }

    /** As the dashboard says a time, with no zero tail: 6m 23s, 1m, 20s. */
    private String took(int seconds) {
        int hours = seconds / 3600;
        int minutes = seconds % 3600 / 60;
        int rest = seconds % 60;
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        if (minutes > 0) {
            return minutes + "m" + (rest > 0 ? " " + rest + "s" : "");
        }
        return rest + "s";
    }
}
