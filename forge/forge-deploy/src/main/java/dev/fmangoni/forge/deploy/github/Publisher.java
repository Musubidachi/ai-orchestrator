package dev.fmangoni.forge.deploy.github;

import dev.fmangoni.forge.deploy.Readiness;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Puts a family's versions on GitHub: one private repository, the initial version on {@code main},
 * which never moves, and each enhancement on a branch of its own.
 *
 * <p>An enhancement's branch is made as it begins, at its first commit ({@link #begin}), and moved
 * to where its work ends when it is approved ({@link #publish}). Each approved version goes up at
 * its own tip ({@link Tips}), so a branch never carries work that was not approved under it. The
 * repository is made the first time, and only then: one already there under the name is someone
 * else's, and is refused rather than pushed into.
 *
 * <p>Runs under the repository's lock, which its caller holds, so no turn moves anything meanwhile.
 * Never throws for a refusal or a failed push: the outcome is recorded, and shown on the card.
 */
public final class Publisher {

    /** The switch, at the Forge root: {@code {"github": true}} turns publishing on. */
    public static final String SETTINGS = "publishing.json";

    private final Path runsDir;
    private final String family;
    private final GitHubAccount github;
    private final RunPaths initial;
    private final Path file;
    private final Git git;
    private final BranchPush push;

    public Publisher(Path runsDir, String projectId, GitHubAccount github) {
        this.runsDir = runsDir;
        this.family = Branches.familyOf(projectId);
        this.github = github;
        this.initial = RunPaths.under(runsDir, family);
        this.file = initial.githubFile();
        this.git = new Git(initial.repo(), initial.gitLog());
        this.push = new BranchPush(initial.repo(), initial.logs().resolve("github.log"));
    }

    /**
     * Whether this Forge publishes to GitHub at all.
     *
     * <p>Off unless the file says on. That is what keeps a test suite, which accepts projects in
     * temporary homes all day on a machine where {@code gh} is logged in, from making repositories
     * on the person's account. An unreadable file is off: publishing by mistake is the worse error.
     */
    public static boolean enabled(Path forgeRoot) {
        Path settings = forgeRoot.resolve(SETTINGS);
        if (!Files.isRegularFile(settings)) {
            return false;
        }
        try {
            return Json.mapper().readTree(JsonFile.read(settings)).path("github").asBoolean(false);
        } catch (IOException e) {
            System.err.println("Treating unreadable " + settings + " as publishing switched off: " + e.getMessage());
            return false;
        }
    }

    /** The family's record on GitHub, or empty before the first try. */
    public static Optional<Publication> of(Path runsDir, String projectId) {
        return Publication.load(RunPaths.under(runsDir, Branches.familyOf(projectId)).githubFile());
    }

    /** Every version of a family that has begun, read from its state. */
    public static List<RunState> begun(Path runsDir, String family) {
        try (Stream<Path> dirs = Files.list(runsDir)) {
            List<RunState> begun = new ArrayList<>();
            dirs.map(dir -> dir.getFileName().toString())
                    .filter(name -> name.equals(family) || name.startsWith(family + "."))
                    .sorted()
                    .forEach(name -> RunStore.survey(RunPaths.under(runsDir, name).stateFile())
                            .filter(state -> state.getPhase() != Phase.NEW)
                            .ifPresent(begun::add));
            return begun;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + runsDir, e);
        }
    }

    /**
     * Puts every approved version up at its tip, making the repository first if there is none, and
     * gives any enhancement under way the branch it should have had from its beginning.
     */
    public Publication publish() {
        Instant now = Instant.now();
        try {
            List<RunState> begun = begun(runsDir, family);
            List<RunState> approved = approvedInOrder(begun);
            Map<String, String> tips = new LinkedHashMap<>();
            for (RunState version : approved) {
                RunPaths paths = RunPaths.under(runsDir, version.getId()).workingIn(initial.repo());
                String unready = Readiness.blockedReason(version, paths, "published", "publishing");
                if (unready != null) {
                    return finish(Publication.REFUSED, version.getId() + ": " + unready, now);
                }
                String tip = Tips.of(version, begun, git, runsDir);
                String later = laterWorkIn(version, tip, begun);
                if (later != null) {
                    return finish(Publication.REFUSED, version.getId() + "'s work now sits on top of " + later
                            + "'s, so its branch would carry that too: nothing was pushed", now);
                }
                tips.put(version.getId(), tip);
            }
            if (approved.isEmpty() || !Branches.isInitial(approved.getFirst().getId())) {
                return finish(Publication.REFUSED, "the initial version, " + family
                        + ", has to be approved first: main is where the repository starts", now);
            }
            if (github.login().isEmpty()) {
                return finish(Publication.FAILED, "gh is not logged in: run gh auth login yourself, then publish again", now);
            }
            if (!Publication.load(file).map(Publication::established).orElse(false)) {
                establish(approved.getFirst().getTitle(), now).save(file);
            }
            for (RunState version : approved) {
                String problem = pushOnce(version.getId(), version.getTitle(), tips.get(version.getId()));
                if (problem != null) {
                    return finish(Publication.FAILED, version.getId() + ": " + problem, now);
                }
                remember(version, tips.get(version.getId()));
            }
            // Made as an enhancement begins; here for one that began before branches were, or whose
            // first push did not go through.
            for (RunState version : begun) {
                boolean underWay = version.getPhase() != Phase.ACCEPTED && !Branches.isInitial(version.getId());
                boolean branchless = Publication.load(file).orElseThrow().branchOf(version.getId()).isEmpty();
                if (underWay && branchless && version.getBaselineCommit() != null) {
                    String problem = pushOnce(version.getId(), version.getTitle(), version.getBaselineCommit());
                    if (problem != null) {
                        return finish(Publication.FAILED, version.getId() + ": " + problem, now);
                    }
                }
            }
            return finish(Publication.PUBLISHED, "", now);
        } catch (IllegalStateException | UncheckedIOException e) {
            return finish(Publication.FAILED, e.getMessage(), now);
        }
    }

    /**
     * Makes an enhancement's branch on GitHub as it begins, at its first commit.
     *
     * <p>Beginning waits for the family's repository, so this never makes one: with none there is
     * nowhere to put the branch, and it says so. A branch that could not be made now is made by the
     * next publish.
     */
    public Publication begin(String id, String title, String firstCommit) {
        Instant now = Instant.now();
        try {
            if (!Publication.load(file).map(Publication::established).orElse(false)) {
                return finish(Publication.FAILED, id + " began before " + family
                        + " had a repository on GitHub, so it has no branch there yet", now);
            }
            String problem = pushOnce(id, title, firstCommit);
            return problem == null
                    ? finish(Publication.PUBLISHED, "", now)
                    : finish(Publication.FAILED, id + ": " + problem, now);
        } catch (IllegalStateException | UncheckedIOException e) {
            return finish(Publication.FAILED, e.getMessage(), now);
        }
    }

    /**
     * The family's repository, made now if it has none, with nothing pushed to it.
     *
     * <p>For a shelf, which needs somewhere to go before anything was approved. The same refusals as
     * publishing: a name already on GitHub is someone else's, and gh must be logged in.
     *
     * @throws IllegalStateException saying why there is no repository
     */
    public Publication ensureRepository() {
        Optional<Publication> record = Publication.load(file);
        if (record.map(Publication::established).orElse(false)) {
            return record.get();
        }
        if (github.login().isEmpty()) {
            throw new IllegalStateException("gh is not logged in: run gh auth login yourself, then try again");
        }
        RunState first = RunStore.survey(initial.stateFile()).orElseThrow(() ->
                new IllegalStateException(family + " has not begun, so there is nothing to put on GitHub"));
        Publication made = establish(first.getTitle(), Instant.now());
        made.save(file);
        return made;
    }

    /** Pushes one version to its branch and records it, unless GitHub has it there already; the problem, or null. */
    private String pushOnce(String id, String title, String commit) {
        Publication record = Publication.load(file).orElseThrow();
        String branch = Branches.of(id, title);
        if (record.has(id, branch, commit)) {
            return null;
        }
        String problem = push.push(record.remote(), commit, branch, github.credentialHelper());
        if (problem == null) {
            record.withBranch(new PublishedBranch(id, branch, commit, Instant.now())).save(file);
        }
        return problem;
    }

    /**
     * Another version whose work a version's tip contains, or null.
     *
     * <p>Versions take turns, so a tip holds only the work of versions that began before it. One that
     * holds a later beginning is an older version reopened after a newer one was built on top of it,
     * and its branch would carry the newer one's work, unapproved there, under its own name.
     */
    private String laterWorkIn(RunState version, String tip, List<RunState> begun) {
        String own = version.getBaselineCommit();
        for (RunState other : begun) {
            String began = other.getBaselineCommit();
            if (other == version || began == null || Branches.isInitial(other.getId())) {
                continue;
            }
            boolean beganAfter = own == null || !git.isAncestor(began, own);
            if (beganAfter && git.isAncestor(began, tip)) {
                return other.getId();
            }
        }
        return null;
    }

    /** Makes the repository, refusing one that is already there under the name: Forge did not make it. */
    private Publication establish(String title, Instant now) {
        String name = Branches.repositoryName(title);
        String repository = github.login().orElseThrow() + "/" + name;
        if (github.urlOf(repository).isPresent()) {
            throw new IllegalStateException("a repository called " + repository + " is already on GitHub, and"
                    + " Forge only pushes into repositories it made: rename or remove that one, then publish again");
        }
        String url = github.createPrivate(name, title + ", built with Forge");
        return new Publication(repository, url, now, List.of(), Publication.FAILED, "nothing pushed yet", now);
    }

    /** A tip read back out of the history is written down, so it is only ever worked out once. */
    private void remember(RunState version, String tip) {
        if (tip.equals(version.getFinishedCommit())) {
            return;
        }
        version.setFinishedCommit(tip);
        RunStore.save(RunPaths.under(runsDir, version.getId()).stateFile(), version);
    }

    /**
     * Records how this try went, on top of whatever the record on disk already says.
     *
     * <p>Always the file's own copy, never one held from earlier: what was pushed is written as it is
     * pushed, and an older copy written over it would forget a branch, or the repository itself.
     * A record that cannot be read is left alone rather than replaced.
     */
    private Publication finish(String outcome, String detail, Instant now) {
        Optional<Publication> current;
        try {
            current = Publication.load(file);
        } catch (UncheckedIOException e) {
            return Publication.notYet(Publication.FAILED, file + " cannot be read, so it was left as it is: "
                    + e.getCause().getMessage(), now);
        }
        Publication finished = current.map(record -> record.attempted(outcome, detail, now))
                .orElseGet(() -> Publication.notYet(outcome, detail, now));
        finished.save(file);
        return finished;
    }

    /** The approved versions, initial first, so main is the first branch GitHub sees and its default. */
    private static List<RunState> approvedInOrder(List<RunState> begun) {
        List<RunState> approved = new ArrayList<>(begun.stream()
                .filter(state -> state.getPhase() == Phase.ACCEPTED).toList());
        approved.sort((a, b) -> Boolean.compare(!Branches.isInitial(a.getId()), !Branches.isInitial(b.getId())));
        return approved;
    }
}
