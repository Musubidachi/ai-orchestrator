package dev.fmangoni.forge.feature;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.plan.PlanFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One capability of a finished project, and the recording that shows it working.
 *
 * <p>A report full of screenshots answers "did it build". It cannot answer the question a person
 * actually has about a game or an editor — does it respond, does it keep time, does it fall apart
 * after the third click — and that question is why the work was commissioned. So every feature the
 * project claims owes a recording of itself, and the gate counts them.
 *
 * <p>The agent proposes the list and does not settle it. Left to decide how many features it has,
 * an agent that is behind will discover it has one; the person who asked for the program approves
 * or declines each one, and an approved feature becomes work like any other line of the contract.
 *
 * @param name what the feature is, in a few words — also its identity, see {@link #slug()}
 * @param demo what a recording of it should show, so the next session knows what to film
 */
public record Feature(String name, String demo) {

    /** Where a feature's recording lives in the project, without its extension. */
    private static final String DIRECTORY = "artifacts/feature-";

    /**
     * Every feature recording, as a git pathspec.
     *
     * <p>A gate deletes and re-records these on every run, so a tracked recording is a new binary
     * blob in every commit after every gate. One project here carried 21.8 MB of them across 73
     * blobs, and agents were spending turns on commits titled "Refresh feature recordings". The
     * report reads them from disk and from {@code evidence/}, never from history.
     */
    public static final String RECORDINGS = DIRECTORY + "*";

    /**
     * Playable in a report, in descending order of how much they show.
     *
     * <p>GIF earns its place: a Swing window driven by {@code Robot} can be written frame
     * by frame with {@code ImageIO} and nothing else, where webm would mean a native encoder on a
     * machine that is supposed to run unattended. A recording that exists beats a better format
     * that needs a dependency and a working codec.
     */
    public static final List<String> FORMATS = List.of(".webm", ".mp4", ".gif");

    /**
     * The feature's identity, derived from its name.
     *
     * <p>Readable on purpose, unlike the hashed ids on review items. This one is a filename the
     * agent has to produce and a person has to recognise in a directory listing —
     * {@code feature-pink-slip-wager.webm} says what it is and {@code feature-a3f91c2b.webm} does
     * not. It shares the property that matters: rename the feature and it is a different feature,
     * which is right, because an approval of the old wording should not carry over to new wording.
     */
    public String slug() {
        String cleaned = (name == null ? "" : name).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (cleaned.isBlank()) {
            return "unnamed";
        }
        // Long enough to stay distinct, short enough to survive a path length limit on Windows,
        // where these sit under runs/NNN/repo/artifacts and again under evidence/session-N.
        return cleaned.length() <= 60 ? cleaned : cleaned.substring(0, 60);
    }

    /** The id the report and {@code features.json} use. */
    public String id() {
        return "feature-" + slug();
    }

    /** Where its recording is expected, for a given format. */
    public String recordingPath(String extension) {
        return DIRECTORY + slug() + extension;
    }

    /** Every path that would count as this feature's recording. */
    public List<String> acceptablePaths() {
        return FORMATS.stream().map(this::recordingPath).toList();
    }

    /**
     * What the agent proposed in its handoff.
     *
     * <p>Nothing is inferred when the handoff named none: a project with no proposals owes no
     * recordings, and inventing a feature list from the summary would put words in the agent's
     * mouth and then hold it to them.
     */
    public static List<Feature> of(Plan plan) {
        return from(plan == null ? null : plan.getFeatures());
    }

    /** What the agent suggests building next, in the same shape so an approval can become one. */
    public static List<Feature> ideas(Plan plan) {
        return from(plan == null ? null : plan.getIdeas());
    }

    private static List<Feature> from(List<PlanFeature> listed) {
        List<Feature> features = new ArrayList<>();
        if (listed == null) {
            return features;
        }
        for (PlanFeature proposed : listed) {
            if (proposed == null || proposed.getName() == null || proposed.getName().isBlank()) {
                continue;
            }
            features.add(new Feature(proposed.getName().strip(),
                    proposed.getDemo() == null ? "" : proposed.getDemo().strip()));
        }
        return features;
    }
}
