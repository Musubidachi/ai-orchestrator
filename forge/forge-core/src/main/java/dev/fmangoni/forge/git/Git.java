package dev.fmangoni.forge.git;

import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * The git operations Forge needs, and no more.
 *
 * <p>Every agent turn is committed, which is what makes an unattended pipeline reviewable after the
 * fact: each project carries a history of exactly what each attempt changed, the reviewer diffs
 * against the scaffold baseline, and anything unwanted is one {@code git revert} away.
 *
 * <p>{@code git} is a real executable rather than a {@code .cmd} shim, so arguments bypass
 * {@code cmd.exe} and its quoting rules — commit messages can be passed directly.
 */
@RequiredArgsConstructor
public final class Git {

    private static final Path GIT = Paths.get("git");
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /**
     * Settings that stop the repository from running its own code inside Forge's git calls.
     *
     * <p>The repository belongs to an agent, and git will execute what a repository asks it to: a
     * hook in {@code .git/hooks} runs on every commit, and a {@code core.fsmonitor} command in
     * {@code .git/config} runs on {@code add} and {@code status}. Forge commits after every turn,
     * so either would run the agent's code as Forge — outside any sandbox the agent's own CLI put
     * around it — the next time a turn ended. Both were confirmed on this machine to run without
     * these settings and not to run with them. {@code NUL}, Windows' null device, rather than an
     * empty directory, because there is nowhere on disk an agent could not also write to.
     *
     * <p>Not covered: filter and diff drivers a repository's config defines. Those need a line in
     * {@code .git/config} as well as in {@code .gitattributes}, which is a longer reach.
     */
    private static final List<String> UNTRUSTED_REPOSITORY = List.of(
            "-c", "core.hooksPath=NUL",
            "-c", "core.fsmonitor=false");

    private final Path repo;
    private final Path logFile;

    public void init() {
        run("init", "-q");
        // Identity is set per-repo so Forge never depends on, or writes to, global git config.
        run("config", "user.name", "Forge");
        run("config", "user.email", "forge@localhost");
    }

    /**
     * Stages everything and commits, returning the new commit's SHA.
     *
     * <p>Returns the existing HEAD when there is nothing to commit — an agent turn that changed no
     * files is a real outcome the gate should judge, not an error to throw on.
     */
    public String commitAll(String message) {
        run("add", "-A");
        ProcResult result = attempt(List.of("commit", "-q", "-m", message, "--allow-empty"));
        if (!result.ok()) {
            throw new IllegalStateException("git commit failed: " + result.combined());
        }
        return head();
    }

    /**
     * Stops git tracking files matching a pattern, and keeps it from picking them up again.
     *
     * <p>For files a gate regenerates every time it runs, which then land in every commit that
     * follows. The files stay on disk; only the index forgets them. Excluded through
     * {@code .git/info/exclude} rather than {@code .gitignore}, which belongs to the project and
     * which an agent is free to rewrite.
     */
    public void keepOutOfHistory(String pattern) {
        Path exclude = repo.resolve(".git").resolve("info").resolve("exclude");
        try {
            Files.createDirectories(exclude.getParent());
            String existing = Files.exists(exclude)
                    ? Files.readString(exclude) : "";
            if (existing.lines().noneMatch(line -> line.strip().equals(pattern))) {
                String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : "\n";
                Files.writeString(exclude, existing + separator + pattern + "\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + exclude, e);
        }
        run("rm", "-r", "-q", "--cached", "--ignore-unmatch", "--", pattern);
    }

    /**
     * Files in the working tree that git would commit: tracked, plus untracked but not ignored.
     *
     * <p>What a security scan should read. A plain walk goes through {@code node_modules},
     * {@code dist} and {@code target}, where vendored libraries carry strings that look like keys
     * and are nobody's to fix.
     */
    public List<String> files() {
        return run("ls-files", "-co", "--exclude-standard").stdout().lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    /**
     * Lines in any commit matching an extended regular expression, as {@code rev:path:line:text}.
     *
     * <p>Searched in batches of revisions, because one {@code git grep} naming every commit overruns
     * the Windows command-line limit on a project with a long history. Empty output when nothing
     * matches, which {@code git grep} reports with exit status 1.
     */
    public String grepHistory(String extendedRegex) {
        List<String> revisions = run("rev-list", "--all").stdout().lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
        StringBuilder out = new StringBuilder();
        for (int from = 0; from < revisions.size(); from += 200) {
            List<String> args = new ArrayList<>(List.of("grep", "-I", "-n", "-E", "-e", extendedRegex));
            args.addAll(revisions.subList(from, Math.min(from + 200, revisions.size())));
            ProcResult result = attempt(args);
            if (result.exitCode() == 0) {
                out.append(result.stdout());
            }
        }
        return out.toString();
    }

    /**
     * The id of HEAD's tree: what the files are, regardless of how many commits said so.
     *
     * <p>Commits here are made with {@code --allow-empty}, so a turn that changed nothing still moves
     * HEAD. Anything that wants to know whether the code is the same asks this instead.
     */
    public String tree() {
        return run("rev-parse", "HEAD^{tree}").stdout().strip();
    }

    public String head() {
        return run("rev-parse", "HEAD").stdout().strip();
    }

    /**
     * True when the working tree holds changes that are not committed.
     *
     * <p>Used to detect a turn that was interrupted between the agent writing files and Forge
     * committing them — a crash, a reboot, a killed process. Build output is git-ignored by the
     * scaffold, so a dirty tree means real work, not leftover {@code target/}.
     */
    public boolean isDirty() {
        return !run("status", "--porcelain").stdout().strip().isEmpty();
    }

    /**
     * Whether anything changed between {@code sha} and HEAD.
     *
     * <p>Commits are made with {@code --allow-empty}, so the existence of a commit proves nothing.
     * This is what separates a turn that made progress from one that spun.
     */
    public boolean changedSince(String sha) {
        if (sha == null || sha.isBlank()) {
            return true;
        }
        return !attempt(List.of("diff", "--quiet", sha, "HEAD")).ok();
    }

    /** The unified diff between two commits. */
    public String diff(String from, String to) {
        return run("diff", from + ".." + to).stdout();
    }

    /**
     * Files nobody reviews, excluded from the reviewer's diff.
     *
     * <p>Every one of these is machine-written, and a reviewing model has nothing useful to say
     * about any of it. Measured on this machine: {@code package-lock.json} alone was 1527 lines of
     * one project's review payload — the single largest file in it, and larger than the source
     * being reviewed.
     */
    private static final List<String> NOT_WORTH_REVIEWING = List.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "npm-shrinkwrap.json",
            "Cargo.lock", "poetry.lock", "go.sum", "Gemfile.lock", "composer.lock",
            "**/node_modules/**", "**/dist/**", "**/build/**", "**/target/**",
            "**/*.min.js", "**/*.min.css", "**/*.map");

    /**
     * The reviewer's input: what a person would actually read, and no more.
     *
     * <p>A cross-review is a single read-only turn, so its whole cost is the size of this string —
     * which makes it the one place where trimming directly buys more reviews out of a fixed
     * subscription window. Lock files and build output are excluded because they cost tokens and
     * carry no reviewable decision.
     *
     * <p>Capped as well. A diff far past the cap is a project whose review was never going to be
     * thorough; sending 400 KB of it spends the window without improving the answer, so the tail is
     * dropped and the reviewer is told, rather than quietly truncated mid-hunk.
     */
    public String reviewDiff(String from, String to, int maxChars) {
        List<String> args = new ArrayList<>(List.of("diff", from + ".." + to, "--"));
        NOT_WORTH_REVIEWING.forEach(pattern -> args.add(":(exclude)" + pattern));

        ProcResult result = attempt(args);
        // Older git, or a pathspec it dislikes, must not cost the review entirely.
        String diff = result.ok() ? result.stdout() : diff(from, to);

        // Not a plain cut at maxChars any more: that is what sent one reviewer 60,000 characters
        // of an encrypted PDF and no source at all. See ReviewDiff.
        return ReviewDiff.shape(diff, maxChars);
    }

    /**
     * The paths changed between two commits, or empty when the range cannot be read.
     *
     * <p>Empty rather than an empty list on failure: "nothing changed" and "could not tell" lead to
     * opposite decisions for a caller deciding how much checking a change needs.
     */
    public Optional<List<String>> changedFiles(String from, String to) {
        if (from == null || from.isBlank()) {
            return Optional.empty();
        }
        ProcResult result = attempt(List.of("diff", "--name-only", from + ".." + to));
        return result.ok()
                ? Optional.of(result.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).toList())
                : Optional.empty();
    }

    /** The unified diff of one path between two commits, or empty when the range cannot be read. */
    public String diffOf(String from, String to, String path) {
        ProcResult result = attempt(List.of("diff", from + ".." + to, "--", path));
        return result.ok() ? result.stdout() : "";
    }

    public String log(String from, String to) {
        return run("log", "--oneline", from + ".." + to).stdout();
    }

    /**
     * One line per changed file, with how much moved in each.
     *
     * <p>Unlike {@link #diff}, this is for a person rather than a reviewing model: a full diff of a
     * session is thousands of lines nobody reads, while the summary answers the only question a
     * report needs to — what did it touch, and how much.
     *
     * <p>Returns empty rather than throwing when the range is not resolvable. History can be
     * rewritten or a run copied between machines, and a report that fails to render because of a
     * missing commit is worse than one that quietly omits a section.
     */
    public String diffStat(String from, String to) {
        if (from == null || from.isBlank()) {
            return "";
        }
        ProcResult result = attempt(List.of("diff", "--stat", from + ".." + to));
        return result.ok() ? result.stdout() : "";
    }

    /** Subject lines of the commits in a range, newest first; empty when unresolvable. */
    public List<String> subjects(String from, String to) {
        if (from == null || from.isBlank()) {
            return List.of();
        }
        ProcResult result = attempt(List.of("log", "--format=%s", from + ".." + to));
        return result.ok()
                ? result.stdout().lines().map(String::strip).filter(line -> !line.isEmpty()).toList()
                : List.of();
    }

    private ProcResult run(String... args) {
        ProcResult result = attempt(List.of(args));
        if (!result.ok()) {
            throw new IllegalStateException(
                    "git " + String.join(" ", args) + " failed: " + result.combined());
        }
        return result;
    }

    private ProcResult attempt(List<String> args) {
        List<String> guarded = new ArrayList<>(UNTRUSTED_REPOSITORY);
        guarded.addAll(args);
        return CliRunner.run(GIT, guarded, repo, null, TIMEOUT, logFile);
    }
}
