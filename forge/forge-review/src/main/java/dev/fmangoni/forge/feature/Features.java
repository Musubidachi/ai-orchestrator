package dev.fmangoni.forge.feature;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * What has been approved, and which approvals are still owed a recording.
 *
 * <p>Rulings are stored as {@link Decision}s — the same id, text, verdict and accumulating notes
 * the review form already uses, in {@code features.json} rather than {@code decisions.json}. The
 * shape is genuinely the same act: a person going through a list and saying yes or no to each,
 * with a remark. Only the vocabulary differs, and it lives here rather than on {@code Decision},
 * which should not have to know that features exist.
 */
@UtilityClass
public class Features {

    public static final String APPROVED = "approved";
    public static final String DECLINED = "declined";

    /**
     * Keep it, but change it — and the note beside it says how.
     *
     * <p>Added after a request typed beside an approved feature ("if a batch fails, offer an ad
     * to buy more credit") went nowhere. Approval meant "film this", so the
     * session that followed filmed it, and the one sentence that was actually an instruction was
     * never put in front of an agent. A note is not an instruction until somebody says it is;
     * this verdict is how.
     */
    public static final String CHANGES = "changes";

    /** Wanted in some form, and so owed a recording of how it ends up. */
    public boolean approved(Decision ruling) {
        return ruling != null && (APPROVED.equalsIgnoreCase(ruling.getVerdict())
                || CHANGES.equalsIgnoreCase(ruling.getVerdict()));
    }

    public boolean changes(Decision ruling) {
        return ruling != null && CHANGES.equalsIgnoreCase(ruling.getVerdict());
    }

    public boolean declined(Decision ruling) {
        return ruling != null && DECLINED.equalsIgnoreCase(ruling.getVerdict());
    }

    /** Rulings by the feature they are about, newest wins. */
    public Map<String, Decision> byId(List<Decision> rulings) {
        Map<String, Decision> byId = new LinkedHashMap<>();
        for (Decision ruling : rulings == null ? List.<Decision>of() : rulings) {
            byId.put(ruling.getItemId(), ruling);
        }
        return byId;
    }

    /**
     * Every feature that owes a recording.
     *
     * <p>Silence is not consent. A feature nobody has ruled on is left out, so approving nothing
     * owes nothing and a project cannot acquire obligations by the agent listing more things.
     *
     * <p>An approval outlives the proposal that prompted it, which is the opposite of how a review
     * verdict works, and the difference is the point. A verdict is a judgement <em>about</em>
     * something the agent claimed, so when the agent stops claiming it the verdict has nothing
     * left to be about. An approval is a request the reader made: the agent leaving it out of the
     * next handoff is not the reader withdrawing it, and letting a list the agent controls cancel
     * work the reader asked for would hand the agent the one decision it is deliberately not
     * given. It is also how a feature the reader added themselves survives at all — nothing ever
     * proposed it.
     */
    public List<Feature> owed(List<Feature> proposed, List<Decision> rulings) {
        Map<String, Feature> byId = new LinkedHashMap<>();
        for (Feature feature : proposed == null ? List.<Feature>of() : proposed) {
            byId.put(feature.id(), feature);
        }

        List<Feature> out = new ArrayList<>();
        for (Decision ruling : rulings == null ? List.<Decision>of() : rulings) {
            if (!approved(ruling)) {
                continue;
            }
            Feature proposal = byId.get(ruling.getItemId());
            // A feature nobody proposed takes its brief from what was typed when it was asked for,
            // which is the only description of it that exists.
            out.add(proposal != null ? proposal
                    : new Feature(ruling.getText() == null ? "" : ruling.getText(),
                            ruling.latestComment()));
        }
        return out;
    }

    /**
     * Everything the report should offer a ruling on.
     *
     * <p>What the agent proposed this session, followed by anything approved that it did not —
     * features the reader asked for, and ones the agent has since stopped listing. Both still owe
     * a recording, so both have to be visible; a row that silently disappears while the obligation
     * behind it stays is how a project ends up parked with no explanation on the page.
     */
    public List<Feature> listed(List<Feature> proposed, List<Decision> rulings) {
        return listed(proposed, List.of(), rulings);
    }

    /**
     * @param ideas suggestions, consulted only for the description of one that has been approved.
     *              An idea nobody has said yes to is not something the project does, and must not
     *              appear as a feature.
     */
    public List<Feature> listed(List<Feature> proposed, List<Feature> ideas,
                                List<Decision> rulings) {
        List<Feature> out = new ArrayList<>(proposed == null ? List.of() : proposed);
        Set<String> seen = new HashSet<>();
        out.forEach(feature -> seen.add(feature.id()));

        List<Feature> known = new ArrayList<>(out);
        known.addAll(ideas == null ? List.of() : ideas);
        for (Feature feature : owed(known, rulings)) {
            if (seen.add(feature.id())) {
                out.add(feature);
            }
        }
        return out;
    }

    /**
     * Every feature and idea the handoff describes, for looking up what one should show.
     *
     * <p>Only ever passed to {@link #owed}, which returns approvals and nothing else. Handing the
     * ideas to {@link #listed} instead would present every suggestion as something the project
     * already does.
     */
    public List<Feature> knownTo(Plan plan) {
        List<Feature> known = new ArrayList<>(Feature.of(plan));
        known.addAll(Feature.ideas(plan));
        return known;
    }

    /**
     * Approvals for things the project does not do yet — ideas taken up, and features asked for
     * by hand.
     *
     * <p>The distinction the brief needs. "Film this" and "build this, then film it" are different
     * instructions, and an agent told only the first about a feature that does not exist will film
     * whatever screen is nearest and call it done.
     */
    public Set<String> toBuild(List<Feature> existing, List<Decision> rulings) {
        Set<String> have = new HashSet<>();
        for (Feature feature : existing == null ? List.<Feature>of() : existing) {
            have.add(feature.id());
        }
        Set<String> out = new LinkedHashSet<>();
        for (Decision ruling : rulings == null ? List.<Decision>of() : rulings) {
            if (approved(ruling) && !have.contains(ruling.getItemId())) {
                out.add(ruling.getItemId());
            }
        }
        return out;
    }

    /**
     * What the project has for one feature, whether or not it is any good.
     *
     * <p>Any of the playable formats counts. Insisting on one would make the rule about the codec
     * available on the machine rather than about whether anyone can watch the feature work.
     */
    public RecordingTake takeOf(Path repo, Feature feature) {
        for (String candidate : feature.acceptablePaths()) {
            Path file = repo.resolve(candidate).normalize();
            if (file.startsWith(repo.normalize()) && Files.isRegularFile(file)) {
                return new RecordingTake(feature, file, Recording.inspect(file));
            }
        }
        return new RecordingTake(feature, null, Clip.bad("", "was never produced"));
    }

    /** The recording of one feature, if the project has produced one worth watching. */
    public Optional<Path> recordingOf(Path repo, Feature feature) {
        RecordingTake take = takeOf(repo, feature);
        return take.usable() ? Optional.of(take.file()) : Optional.empty();
    }

    /** Approved features with nothing watchable — the work an approval creates. */
    public List<RecordingTake> unrecorded(Path repo, List<Feature> approved) {
        List<RecordingTake> missing = new ArrayList<>();
        for (Feature feature : approved == null ? List.<Feature>of() : approved) {
            RecordingTake take = takeOf(repo, feature);
            if (!take.usable()) {
                missing.add(take);
            }
        }
        return missing;
    }

    /**
     * The newest copy of a feature's recording kept in the run's evidence.
     *
     * <p>Needed because the gate deletes the repository's copy before every run, so between a
     * failed gate and the next successful one the working tree has nothing to show. Evidence is
     * kept per session and is the only durable record of what the feature looked like working.
     *
     * <p>A clip found here and not in the repository is <em>old</em>, and the report has to say so.
     * Showing last week's recording beside a feature the current build did not manage to film
     * would be the report lying in the one place it exists to tell the truth.
     */
    public Optional<Path> archivedRecordingOf(Path evidenceDir, Feature feature) {
        if (!Files.isDirectory(evidenceDir)) {
            return Optional.empty();
        }
        List<String> names = feature.acceptablePaths().stream()
                .map(path -> path.substring(path.lastIndexOf('/') + 1))
                .toList();
        try (var tree = Files.walk(evidenceDir)) {
            return tree.filter(Files::isRegularFile)
                    .filter(file -> names.contains(file.getFileName().toString()))
                    .filter(file -> Recording.inspect(file).usable())
                    .max(Comparator.comparing(Features::modifiedAt));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private FileTime modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    /**
     * Globs that collect every feature recording into the run's evidence, without the spec
     * having to name them.
     *
     * <p>The spec's {@code evidence} list is what the author thought to ask for. These are owed by
     * the contract itself, so requiring them to be listed twice would just be a way to forget.
     */
    public List<String> evidenceGlobs(List<Feature> approved) {
        List<String> globs = new ArrayList<>();
        for (Feature feature : approved == null ? List.<Feature>of() : approved) {
            globs.addAll(feature.acceptablePaths());
        }
        return globs;
    }
}
