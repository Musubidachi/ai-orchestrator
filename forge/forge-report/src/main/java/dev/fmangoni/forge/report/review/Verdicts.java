package dev.fmangoni.forge.report.review;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * A ruling as the person saw it on the form, and when it was made, for reading back to them.
 *
 * <p>The words on disk are the form's values — {@code ok}, {@code changes}, {@code approved} — and
 * a history that answers "you said ok" to someone who pressed "Fine as it is" reads as though it
 * were about something else.
 */
@UtilityClass
public class Verdicts {

    private final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    public String said(String verdict) {
        String value = verdict == null ? "" : verdict.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "ok" -> "Fine as it is";
            case "changes" -> "Needs work";
            case "approved" -> "Worth seeing";
            case "declined" -> "Not wanted";
            case "", "undecided" -> "no ruling";
            default -> value;
        };
    }

    public String when(Instant at) {
        return at == null ? "date not recorded" : WHEN.format(at);
    }
}
