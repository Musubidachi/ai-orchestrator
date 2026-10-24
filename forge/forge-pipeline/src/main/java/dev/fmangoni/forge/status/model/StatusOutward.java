package dev.fmangoni.forge.status.model;

import dev.fmangoni.forge.deploy.Deployer;
import dev.fmangoni.forge.deploy.Deployment;
import dev.fmangoni.forge.deploy.github.Publication;
import dev.fmangoni.forge.deploy.github.PublishedBranch;
import dev.fmangoni.forge.deploy.shelf.Shelver;
import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.design.DesignStudio;
import dev.fmangoni.forge.gate.RunFile;
import dev.fmangoni.forge.marketing.KitStatus;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Value;

/** What it would take to put this in front of people: whether it is safe, and whether it can be tried. */
@Value
@Builder(toBuilder = true)
public class StatusOutward {

    /**
    * {@code none} (never checked), {@code clean}, {@code notes} (findings, none needing a
    * decision) or {@code decide} (something serious, or a secret to revoke).
    */
    private final String securityState;

    /** Findings by severity, e.g. "1 critical, 3 low"; blank when none or never checked. */
    private final String securitySummary;

    /** How many security findings wait for a ruling on the report. */
    private final int securityForRuling;

    /** The spec's deploy command, shown in the confirmation; blank when it has none. */
    private final String deployCommand;

    /** {@code none}, {@code never}, {@code deployed}, or the last attempt's failure outcome. */
    private final String deployState;

    /** Where the last successful deploy went, and when, and which commit. */
    private final String deployUrl;

    private final String deployedAt;

    private final String deployedCommit;

    /** Whether commits have landed since the last successful deploy. */
    private final boolean deployStale;

    /** Why Deploy cannot be pressed now, or blank when it can. */
    private final String deployBlockedReason;

    /** What the last attempt said, when it did not deploy. */
    private final String deployDetail;

    /** Whether this Forge publishes to GitHub at all, which is what offers the Publish button. */
    private final boolean githubOn;

    /** {@code none} (never tried), or the last try's outcome: {@code published}, {@code failed} or {@code refused}. */
    private final String githubState;

    /** The family's repository on GitHub, once there is one. */
    private final String githubUrl;

    /** This version's branch there, and the commit it is at; blank until it has been pushed. */
    private final String githubBranch;

    private final String githubCommit;

    /** Why the last try did not publish, in a sentence, or blank. */
    private final String githubDetail;

    /** Where the family is shelved on GitHub, such as {@code shelf/003}; blank when it is here. */
    private final String shelvedBranch;

    /** The repository it is shelved in, and when. */
    private final String shelvedUrl;

    private final String shelvedAt;

    /**
     * Whether the project has begun but its repository is not on this machine, and it is not
     * shelved: deleted by hand, moved, or left behind by a shelf that could not delete everything.
     */
    private final boolean repoMissing;

    /** {@code none}, {@code ready}, {@code problems} or {@code unavailable}. */
    private final String marketingState;

    /** How many problems the last kit's check found. */
    private final int marketingProblems;

    /** Whether the project has a {@code run.bat}, which is what the card's Play button opens. */
    private final boolean playable;

    /**
     * The name of a design picked for it and kept for when it finished, or blank.
     *
     * <p>Said on the dashboard once the project is finished, because a pick kept "for later" that
     * nothing ever mentions again is a pick lost.
     */
    private final String designKept;

    public static StatusOutward of(RunPaths paths) {
        SecurityReport report =
                SecurityReport.load(paths.securityFile());
        StatusOutward unchecked = none().toBuilder().playable(RunFile.present(paths.repo()))
                .designKept(DesignStudio.keptForLater(paths).map(DesignOption::name).orElse("")).build();
        if (report == null) {
            return unchecked;
        }
        int ruling = report.forRuling().size();
        String state = ruling > 0 ? "decide" : report.findings().isEmpty() ? "clean" : "notes";
        String summary = report.counts().entrySet().stream()
                .map(e -> e.getValue() + " " + e.getKey())
                .collect(Collectors.joining(", "));
        return unchecked.toBuilder().securityState(state).securitySummary(summary)
                .securityForRuling(ruling).build();
    }

    /** This group with the state of the project's marketing kit. */
    public StatusOutward withMarketing(RunPaths paths) {
        KitStatus status =
                KitStatus.load(paths.marketingStatusFile());
        return status == null ? this : toBuilder().marketingState(status.outcome())
                .marketingProblems(status.problems() == null ? 0 : status.problems().size()).build();
    }

    /** This group with what the dashboard needs to offer, or explain, a deploy. */
    public StatusOutward withDeploy(Spec spec, RunPaths paths, String blockedReason) {
        if (spec.deploy().isEmpty()) {
            return this;
        }
        List<Deployment> history =
                Deployment.history(paths.deploymentsFile());
        Deployment good =
                Deployment.lastSuccessful(paths.deploymentsFile());
        Deployment latest = history.isEmpty() ? null : history.getLast();
        return toBuilder()
                .deployCommand(spec.deploy().get().getCommand())
                .deployState(latest == null ? "never" : latest.outcome())
                .deployUrl(good == null ? "" : good.url())
                .deployedAt(good == null || good.finishedAt() == null ? "" : good.finishedAt().toString())
                .deployedCommit(good == null ? "" : good.commit())
                .deployStale(Deployer.stale(good,
                        Deployer.headOf(paths.repo())))
                .deployBlockedReason(blockedReason == null ? "" : blockedReason)
                .deployDetail(latest == null || latest.succeeded() ? "" : latest.detail())
                .build();
    }

    /**
     * This group with where the version stands on GitHub.
     *
     * <p>Read from the family's record, which every version of it shares, and this version's own
     * branch in it. Shown whether or not publishing is on now: what is on GitHub stays true after
     * it is switched off. An unreadable record is shown as a failure rather than as nothing.
     */
    public StatusOutward withGitHub(RunPaths paths, String id, boolean enabled) {
        Optional<Publication> found;
        try {
            found = Publication.load(paths.githubFile());
        } catch (UncheckedIOException e) {
            return toBuilder().githubOn(enabled).githubState(Publication.FAILED)
                    .githubDetail(paths.githubFile() + " cannot be read: " + e.getCause().getMessage()).build();
        }
        if (found.isEmpty()) {
            return toBuilder().githubOn(enabled).githubState("none").build();
        }
        Publication record = found.get();
        Optional<PublishedBranch> mine = record.branchOf(id);
        return toBuilder()
                .githubOn(enabled)
                .githubState(record.outcome())
                .githubUrl(record.url() == null ? "" : record.url())
                .githubBranch(mine.map(PublishedBranch::branch).orElse(""))
                .githubCommit(mine.map(PublishedBranch::commit).orElse(""))
                .githubDetail(record.detail())
                .build();
    }

    /**
     * This group with where the family is shelved, when it is.
     *
     * <p>An unreadable mark is shown as shelved, because Start refuses it too: a card offering to
     * work on a project whose repository may well be gone would be the worse mistake.
     */
    public StatusOutward withShelf(RunPaths paths, String id) {
        try {
            return Shelver.markOf(paths.root().getParent(), id)
                    .map(mark -> toBuilder().shelvedBranch(mark.branch()).shelvedUrl(mark.url())
                            .shelvedAt(mark.shelvedAt() == null ? "" : mark.shelvedAt().toString()).build())
                    .orElse(this);
        } catch (UncheckedIOException e) {
            return toBuilder().shelvedBranch("(its shelf mark cannot be read)").build();
        }
    }

    /**
     * This group with whether the repository is here, read after {@link #withShelf}: a shelved
     * family's repository is away on purpose, and its card says that instead.
     */
    public StatusOutward withPresence(RunPaths paths, RunState state) {
        boolean begun = state.getPhase() != Phase.NEW;
        boolean missing = begun && shelvedBranch.isBlank() && !Files.isDirectory(paths.repo().resolve(".git"));
        return missing ? toBuilder().repoMissing(true).build() : this;
    }

    /** Every field named: a positional list this long is how a count arrives as a state. */
    public static StatusOutward none() {
        return builder()
                .securityState("none")
                .securitySummary("")
                .securityForRuling(0)
                .deployCommand("")
                .deployState("none")
                .deployUrl("")
                .deployedAt("")
                .deployedCommit("")
                .deployStale(false)
                .deployBlockedReason("")
                .deployDetail("")
                .githubOn(false)
                .githubState("none")
                .githubUrl("")
                .githubBranch("")
                .githubCommit("")
                .githubDetail("")
                .shelvedBranch("")
                .shelvedUrl("")
                .shelvedAt("")
                .repoMissing(false)
                .marketingState("none")
                .marketingProblems(0)
                .playable(false)
                .designKept("")
                .build();
    }
}
