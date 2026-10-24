package dev.fmangoni.forge.deploy.shelf;

import dev.fmangoni.forge.deploy.github.BranchPush;
import dev.fmangoni.forge.deploy.github.Branches;
import dev.fmangoni.forge.deploy.github.GitHubAccount;
import dev.fmangoni.forge.deploy.github.Publication;
import dev.fmangoni.forge.deploy.github.Publisher;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Puts a whole family away on GitHub to free this machine, and brings it back to carry on.
 *
 * <p>A family, because its versions share one repository: one cannot go without the others. Its
 * shelf is the branch {@code shelf/<family>} of the family's private repository, made if it has
 * none: the project's own history with one commit on top carrying Forge's files for it
 * ({@link ShelfFiles}). That commit stays on the shelf branch, so the project's history never holds it.
 *
 * <p>The order is the point. Nothing here is deleted until GitHub holds the shelf at the very commit
 * that was pushed; anything that fails before then is undone, and the repository is left as it was.
 * Once deleted, each version keeps its state and a {@link ShelfMark}, so its card still says what it
 * is and Start refuses to build it again from nothing. Resuming restores every file, checked against
 * its checksum, puts the repository back on its own branch at its own last commit, and only then
 * takes the shelf off GitHub.
 *
 * <p>Runs under the repository's lock, which its caller holds. Never throws: the outcome is a sentence.
 */
public final class Shelver {

    public static final String PREFIX = "shelf/";

    /** Under GitHub's limit on one file with room to spare, and under its warning at 50 MB. */
    static final long PART_SIZE = 45L * 1024 * 1024;

    /** What stays of a version here while it is shelved. */
    private static final Set<String> KEPT = Set.of("state.json", ShelfMark.FILE, "repo.lock", "shelf.log");

    private final Path runsDir;
    private final String family;
    private final GitHubAccount github;
    private final long partSize;
    private final Path repo;
    private final Path log;

    public Shelver(Path runsDir, String projectId, GitHubAccount github) {
        this(runsDir, projectId, github, PART_SIZE);
    }

    Shelver(Path runsDir, String projectId, GitHubAccount github, long partSize) {
        this.runsDir = runsDir;
        this.family = Branches.familyOf(projectId);
        this.github = github;
        this.partSize = partSize;
        this.repo = RunPaths.under(runsDir, family).repo();
        this.log = RunPaths.under(runsDir, family).root().resolve("shelf.log");
    }

    /** Where the family is shelved, when it is. */
    public static Optional<ShelfMark> markOf(Path runsDir, String projectId) {
        return ShelfMark.of(runsDir, Branches.familyOf(projectId));
    }

    public String shelve() {
        String refused = refusal();
        if (refused != null) {
            return refused;
        }
        Publication home;
        try {
            home = new Publisher(runsDir, family, github).ensureRepository();
        } catch (IllegalStateException | UncheckedIOException e) {
            return "not shelved: " + e.getMessage();
        }
        Git git = new Git(repo, log);
        Optional<String> localBranch = git.currentBranch();
        if (localBranch.isEmpty()) {
            return "not shelved: its repository is not on a branch";
        }
        List<String> versions = Publisher.begun(runsDir, family).stream().map(RunState::getId).toList();
        String branch = PREFIX + family;
        ShelfGit shelf = new ShelfGit(repo, log);
        String head = git.head();
        String commit;
        try {
            new ShelfFiles(partSize).pack(runsDir, family, versions, repo, head, localBranch.get(),
                    shelf.changed(), shelf.deleted());
            commit = shelf.commitShelf(branch, "Shelved by Forge: " + String.join(", ", versions)
                    + " at " + head.substring(0, 7));
        } catch (IOException | IllegalStateException | UncheckedIOException e) {
            return abandon(shelf, localBranch.get(), branch, e.getMessage());
        }
        String problem = new BranchPush(repo, log).push(home.remote(), commit, branch, github.credentialHelper());
        if (problem == null && !commit.equals(shelf.remoteTip(home.remote(), branch, github.credentialHelper()))) {
            problem = "GitHub does not show " + branch + " at the commit that was pushed";
        }
        if (problem != null) {
            return abandon(shelf, localBranch.get(), branch, problem);
        }
        new ShelfMark(family, home.repository(), home.url(), branch, commit, head, localBranch.get(), versions,
                Instant.now()).place(runsDir);
        List<Path> left = new ArrayList<>(LocalDelete.tree(repo));
        for (String version : versions) {
            left.addAll(LocalDelete.allBut(RunPaths.under(runsDir, version).root(), KEPT));
        }
        return left.isEmpty()
                ? "shelved on GitHub as " + branch + " in " + home.repository() + ", and deleted here"
                : "shelved on GitHub as " + branch + ", but " + left.size() + " file(s) here could not be deleted,"
                        + " such as " + left.getFirst();
    }

    private String refusal() {
        if (ShelfMark.of(runsDir, family).isPresent()) {
            return family + " is already shelved on GitHub";
        }
        if (!Files.isDirectory(repo.resolve(".git"))) {
            return family + " has no repository here to shelve";
        }
        if (Files.exists(repo.resolve(ShelfFiles.DIR))) {
            return "not shelved: " + repo.resolve(ShelfFiles.DIR) + " is already there, from a shelf that did not finish";
        }
        return Publisher.begun(runsDir, family).stream().filter(RunState::isTurnInFlight).findFirst()
                .map(busy -> busy.getId() + " is in the middle of a turn: shelve it once that stops")
                .orElse(null);
    }

    /** Back to exactly where it was, the shelf undone, and nothing deleted. */
    private String abandon(ShelfGit shelf, String localBranch, String branch, String why) {
        String undone = "";
        try {
            shelf.abandon(localBranch, branch);
        } catch (IllegalStateException e) {
            undone = "; putting the repository back also failed: " + e.getMessage();
        }
        LocalDelete.tree(repo.resolve(ShelfFiles.DIR));
        return "not shelved, and nothing was deleted: " + why + undone;
    }

    public String resume() {
        Optional<ShelfMark> found;
        try {
            found = ShelfMark.of(runsDir, family);
        } catch (UncheckedIOException e) {
            return "not resumed: " + e.getMessage();
        }
        if (found.isEmpty()) {
            return family + " is not shelved";
        }
        ShelfMark mark = found.get();
        String remote = mark.url().endsWith(".git") ? mark.url() : mark.url() + ".git";
        String setAside = setAside();
        String problem = ShelfGit.clone(remote, mark.branch(), repo, github.credentialHelper(), log);
        if (problem != null) {
            return "not resumed: " + problem + setAside;
        }
        Git git = new Git(repo, log);
        if (!git.head().equals(mark.commit())) {
            LocalDelete.tree(repo);
            return "not resumed: " + mark.branch() + " on GitHub is not the shelf Forge made, so it was left alone" + setAside;
        }
        Path incoming = RunPaths.under(runsDir, family).root().resolve(".shelf-incoming");
        try {
            copyTree(repo.resolve(ShelfFiles.DIR), incoming);
            ShelfManifest manifest = ShelfFiles.manifest(incoming);
            ShelfFiles files = new ShelfFiles(partSize);
            files.restore(incoming, manifest, "runs/", runsDir);
            ShelfGit shelf = new ShelfGit(repo, log);
            shelf.reinstate(mark.localBranch(), mark.head(), mark.branch());
            files.restore(incoming, manifest, "worktree/", repo);
            for (String rel : manifest.deleted()) {
                Files.deleteIfExists(repo.resolve(rel));
            }
            // Last: the clone's own settings name GitHub, and the project's never did.
            files.restore(incoming, manifest, "git/", repo.resolve(".git"));
            if (!git.head().equals(mark.head())) {
                throw new IllegalStateException("the repository is not back at " + mark.head().substring(0, 7));
            }
        } catch (IOException | IllegalStateException | UncheckedIOException e) {
            return "not resumed: " + e.getMessage() + "; the shelf is still on GitHub, so resume again" + setAside;
        } finally {
            LocalDelete.tree(incoming);
        }
        mark.lift(runsDir);
        String gone = new ShelfGit(repo, log).deleteRemote(remote, mark.branch(), github.credentialHelper());
        return (gone == null
                ? "resumed here, and " + mark.branch() + " was taken off GitHub"
                : "resumed here, but " + mark.branch() + " is still on GitHub: " + gone) + setAside;
    }

    /** A repository already where the clone goes, from a shelf that could not delete it all, kept aside. */
    private String setAside() {
        if (!Files.exists(repo)) {
            return "";
        }
        Path aside = repo.resolveSibling("repo.before-resume");
        for (int n = 2; Files.exists(aside); n++) {
            aside = repo.resolveSibling("repo.before-resume-" + n);
        }
        try {
            Files.move(repo, aside);
            return " (what was left of the old repository is in " + aside + ")";
        } catch (IOException e) {
            throw new UncheckedIOException("cannot set " + repo + " aside to resume into", e);
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        LocalDelete.tree(to);
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
