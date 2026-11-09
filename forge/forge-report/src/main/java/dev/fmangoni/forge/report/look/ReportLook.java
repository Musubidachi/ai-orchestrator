package dev.fmangoni.forge.report.look;

import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;

/**
 * A project page drawn as the mockup draws it, as one script at the end of the page and the parts
 * it places.
 *
 * <p>The parts are written here, from the project's state, into a template the browser does not
 * show; the script moves them into place and restyles the guide's rail and bar. So every word it
 * adds is one the report wrote, and the script itself says nothing. The script is {@code look.js}
 * in the resources beside this class, read once.
 */
@UtilityClass
public class ReportLook {

    private final String SCRIPT = load();

    /** The template and the script, to go after the guide's script, which builds what this restyles. */
    public String render(RunState state) {
        return "<template id=\"report-look-parts\">\n"
                + LookHead.render(state)
                + LookTimes.render(state)
                + "<a class=\"rl-go\" href=\"#decide\">Go to your review</a>\n"
                + "</template>\n"
                + "<script id=\"report-look\">\n" + SCRIPT + "</script>\n";
    }

    private String load() {
        try (InputStream in = ReportLook.class.getResourceAsStream("look.js")) {
            if (in == null) {
                throw new IllegalStateException("look.js is missing from beside ReportLook");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read look.js", e);
        }
    }
}
