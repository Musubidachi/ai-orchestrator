package dev.fmangoni.forge.report.guide;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import lombok.experimental.UtilityClass;

/**
 * The rail of a project page's sections and the bar that keeps the review in reach, as one script
 * at the end of the page.
 *
 * <p>Everything it shows is read off the page in the browser, so it cannot disagree with the
 * sections it points at, and it adds nothing the review form reads. The script is {@code guide.js}
 * in the resources beside this class, read once.
 */
@UtilityClass
public class ReportGuide {

    private final String SCRIPT = load();

    /** The script element, to go after the page's last section so that every section is there. */
    public String render() {
        return "<script id=\"report-guide\">\n" + SCRIPT + "</script>\n";
    }

    private String load() {
        try (InputStream in = ReportGuide.class.getResourceAsStream("guide.js")) {
            if (in == null) {
                throw new IllegalStateException("guide.js is missing from beside ReportGuide");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read guide.js", e);
        }
    }
}
