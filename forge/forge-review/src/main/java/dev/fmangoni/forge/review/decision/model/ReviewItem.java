package dev.fmangoni.forge.review.decision.model;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanStep;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * One thing a person is being asked to rule on before a project is accepted.
 *
 * <p>Approval used to be a single yes or no over a whole project, with a free-text box for the no.
 * That is the wrong shape for what is actually in front of the reader: a list of specific,
 * individually true statements about what was not checked, what the reviewer objected to, and what
 * the agent knows is still missing. Ruling on them one at a time is both easier to do and far more
 * useful to send back — "the draw branch has never been rendered, fix that" is a brief; "it is not
 * quite right" is not.
 *
 * @param id     stable across regenerations of the report, so a decision survives a new session
 * @param source where it came from, shown as a label
 * @param text   the item itself, in the model's own words
 */
public record ReviewItem(String id, String source, String text) {

    public static final String NOT_VERIFIED = "Not proven by the gate";
    public static final String BLOCKING = "Blocking review finding";
    public static final String REMAINING = "The agent says this is still to do";
    public static final String SECURITY = "Security finding";

    /**
     * Everything outstanding, in the order a reader should meet it.
     *
     * <p>Blocking findings first: they are another model's objection to code that shipped anyway
     * because the repair budget ran out, which is the most alarming thing on the page. Then what
     * the gate did not prove, then work the agent itself says is unfinished.
     */
    public static List<ReviewItem> of(Plan plan, Review review) {
        return of(plan, review, null);
    }

    /**
     * Everything outstanding, security included.
     *
     * <p>Security findings come first of all: they are the ones that can hurt somebody other than
     * the person reading. Only what needs a decision is listed — serious findings the repair did not
     * clear, and secrets that sit in history where no repair can reach them — so the notes stay in
     * the report instead of burying the form.
     */
    public static List<ReviewItem> of(Plan plan, Review review,
                                      SecurityReport security) {
        List<ReviewItem> items = new ArrayList<>();
        if (security != null) {
            for (SecurityFinding finding : security.forRuling()) {
                items.add(of("security", SECURITY, textOf(finding)));
            }
        }
        if (review != null) {
            for (Finding finding : review.blocking()) {
                items.add(of("blocking", BLOCKING, textOf(finding)));
            }
        }
        if (plan != null) {
            for (String claim : plan.getNotVerified()) {
                items.add(of("unproven", NOT_VERIFIED, claim));
            }
            for (PlanStep step : plan.getRemaining()) {
                items.add(of("remaining", REMAINING, step.getStep()));
            }
        }
        return items;
    }

    /**
     * One item, identified by what it says rather than by where it sits in the list.
     *
     * <p>The id used to be the index — {@code unproven-0}, {@code unproven-1} — and the list it
     * indexed is rewritten by the agent at the end of every session. In a real project here that
     * had run nine sessions, a note reading "this should be addressed in a later build" was filed
     * against {@code unproven-2} when it meant a floating-point tolerance, and by the time it was
     * read {@code unproven-2} meant an RPM gauge. Nothing warned about it; the note simply sat
     * under the wrong claim, sounding sensible.
     *
     * <p>Hashing the text fixes both directions. An unchanged claim keeps its id across
     * regenerations, so what you said about it follows it. A reworded claim gets a new id and
     * therefore no inherited opinion — which is right, because it is not the same statement any
     * more, and inheriting your approval of the old one is the more dangerous mistake.
     */
    public static ReviewItem of(String prefix, String source, String text) {
        return new ReviewItem(prefix + "-" + fingerprint(text), source, text);
    }

    /** How a blocking finding reads on the form: where, then what. */
    public static String textOf(Finding finding) {
        String where = finding.getFile() == null || finding.getFile().isBlank()
                ? "" : finding.getFile() + " — ";
        return where + finding.getSummary();
    }

    /** How a security finding reads on the form: severity, what, and where. */
    public static String textOf(SecurityFinding finding) {
        return "[" + finding.severity() + "] " + finding.title()
                + (finding.location().isBlank() ? "" : " — " + finding.location());
    }

    /**
     * The id a blocking finding carries through review rounds — the same one the form rules on.
     *
     * <p>One name for both, so what a person said about a finding and the reviewer's verdict on
     * whether it was fixed are about the same thing, and a finding that comes back is recognised
     * as having come back.
     */
    public static String idOf(Finding finding) {
        return of("blocking", BLOCKING, textOf(finding)).id();
    }

    /**
     * Eight hex characters of SHA-256 over the item's text.
     *
     * <p>Short on purpose: it appears in a {@code data-item} attribute and in a JSON file a person
     * may read. Collisions between two of the half-dozen claims on one report are not a practical
     * concern, and the cost of one would be a shared note rather than lost work.
     */
    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text.strip())
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is genuinely absent the report is the least
            // of the problems, but an id is still needed for the page to render.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
