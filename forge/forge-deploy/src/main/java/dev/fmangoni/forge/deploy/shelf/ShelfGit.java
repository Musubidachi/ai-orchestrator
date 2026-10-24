package dev.fmangoni.forge.deploy.shelf;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The git a shelf needs beyond {@link Git}: a branch made and committed to for the shelf alone,
 * reading the working tree's state, and the three calls that reach GitHub.
 *
 * <p>Every call carries Forge's guard settings ({@link Git#untrusted}). The calls that reach GitHub
 * also switch off the credential helpers in the person's git config and ask their {@code gh} alone,
 * so nothing can open a sign-in window behind an editor with no screen.
 */
final class ShelfGit {

    /** A shelf push or clone carries the whole history, screenshots, recordings and logs included. */
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final Path repo;
    private final Path log;

    ShelfGit(Path repo, Path log) {
        this.repo = repo;
        this.log = log;
    }

    /** Working-tree files changed from HEAD or new, not ignored. */
    List<String> changed() {
        List<String> changed = new ArrayList<>();
        for (String[] entry : status()) {
            if (entry[0].indexOf('D') < 0) {
                changed.add(entry[1]);
            }
        }
        return changed;
    }

    /** Working-tree files deleted without a commit. */
    List<String> deleted() {
        List<String> deleted = new ArrayList<>();
        for (String[] entry : status()) {
            if (entry[0].indexOf('D') >= 0) {
                deleted.add(entry[1]);
            }
        }
        return deleted;
    }

    /** Each entry as {status, path}; NUL-separated, so no path needs unquoting. */
    private List<String[]> status() {
        String[] tokens = run("status", "--porcelain=v1", "-z", "--untracked-files=all").stdout().split("\0");
        List<String[]> entries = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.length() < 4) {
                continue;
            }
            String code = token.substring(0, 2);
            entries.add(new String[] {code, token.substring(3)});
            if (code.charAt(0) == 'R' || code.charAt(0) == 'C') {
                i++; // a rename names where it came from next, which is not a file here
            }
        }
        return entries;
    }

    /** Commits the payload on a branch of its own, leaving the branch the project is on as it was. */
    String commitShelf(String branch, String message) {
        run("checkout", "-q", "-b", branch);
        run("add", "-f", "--", ShelfFiles.DIR);
        run("commit", "-q", "-m", message);
        return new Git(repo, log).head();
    }

    /** Undoes a shelf that did not get to GitHub: back on the project's branch, the shelf branch gone. */
    void abandon(String localBranch, String branch) {
        Git git = new Git(repo, log);
        if (git.currentBranch().map(branch::equals).orElse(false)) {
            // Never -f: it would throw away the uncommitted work the shelf was meant to carry. The
            // shelf commit only adds the payload, so the plain switch keeps every change as it was.
            run("checkout", "-q", localBranch);
        }
        if (git.branchExists(branch)) {
            run("branch", "-D", branch);
        }
    }

    /** Puts a fresh clone back on the project's own branch at its own last commit, the shelf off it. */
    void reinstate(String localBranch, String head, String branch) {
        run("branch", localBranch, head);
        run("checkout", "-q", localBranch);
        run("branch", "-D", branch);
    }

    /** The commit a branch is at on GitHub, or blank when it is not there or cannot be read. */
    String remoteTip(String url, String branch, String helper) {
        ProcResult result = attempt(withCredentials(helper, "ls-remote", url, "refs/heads/" + branch));
        String out = result.stdout().strip();
        return result.ok() && out.length() >= 40 ? out.substring(0, 40) : "";
    }

    /** Takes a branch off GitHub. @return null, or what went wrong */
    String deleteRemote(String url, String branch, String helper) {
        ProcResult result = attempt(withCredentials(helper, "push", url, "--delete", branch));
        return result.ok() ? null : lastLine(result);
    }

    /** Clones one branch of a repository on GitHub. @return null, or what went wrong */
    static String clone(String url, String branch, Path into, String helper, Path log) {
        List<String> args = withCredentials(helper, "clone", "-q", "--single-branch", "--branch", branch,
                url, into.toString());
        ProcResult result = CliRunner.run(Path.of("git"), Git.untrusted(args), into.getParent(), null, TIMEOUT, log);
        return result.ok() ? null : lastLine(result);
    }

    private static List<String> withCredentials(String helper, String... args) {
        List<String> all = new ArrayList<>(List.of("-c", "credential.helper=", "-c", "credential.helper=" + helper));
        all.addAll(List.of(args));
        return all;
    }

    private ProcResult run(String... args) {
        ProcResult result = attempt(List.of(args));
        if (!result.ok()) {
            throw new IllegalStateException("git " + args[0] + " failed: " + lastLine(result));
        }
        return result;
    }

    private ProcResult attempt(List<String> args) {
        return CliRunner.run(Path.of("git"), Git.untrusted(args), repo, null, TIMEOUT, log);
    }

    private static String lastLine(ProcResult result) {
        return result.combined().strip().lines().reduce((first, second) -> second).orElse("exit " + result.exitCode());
    }
}
