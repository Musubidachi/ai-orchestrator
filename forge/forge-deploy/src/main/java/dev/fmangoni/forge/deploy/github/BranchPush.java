package dev.fmangoni.forge.deploy.github;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Pushes one commit to one branch of a repository on GitHub.
 *
 * <p>Never forced. A branch there that has commits this one lacks is someone's work, and is left
 * as it is with a sentence saying so. Git gets the account's credentials by asking {@code gh}, and
 * only {@code gh}: the helpers in the person's own git config are switched off for the call, so
 * nothing can open a sign-in window behind an editor that has no screen to show it on.
 */
public final class BranchPush {

    /** A first push carries the whole history, screenshots and recordings included. */
    private static final Duration TIMEOUT = Duration.ofMinutes(15);

    private final Path repo;
    private final Path log;

    public BranchPush(Path repo, Path log) {
        this.repo = repo;
        this.log = log;
    }

    /** @return null once the branch on GitHub is at {@code commit}, or what went wrong, in a sentence */
    public String push(String url, String commit, String branch, String credentialHelper) {
        ProcResult result = CliRunner.run(Path.of("git"), Git.untrusted(List.of(
                        "-c", "credential.helper=",
                        "-c", "credential.helper=" + credentialHelper,
                        "push", "--porcelain", url, commit + ":refs/heads/" + branch)),
                repo, null, TIMEOUT, log);
        if (result.ok()) {
            return null;
        }
        String output = result.combined();
        if (output.contains("[rejected]") || output.contains("non-fast-forward") || output.contains("fetch first")) {
            return branch + " on GitHub has commits this one does not, and Forge never force-pushes,"
                    + " so it was left as it is";
        }
        if (result.timedOut()) {
            return "pushing " + branch + " timed out after " + TIMEOUT.toMinutes() + " minutes";
        }
        String last = output.strip().lines().reduce((first, second) -> second).orElse("exit " + result.exitCode());
        return "git could not push " + branch + ": " + last;
    }
}
