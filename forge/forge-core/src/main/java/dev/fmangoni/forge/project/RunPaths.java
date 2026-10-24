package dev.fmangoni.forge.project;

import java.nio.file.Path;

/**
 * The directory layout of a single run, in one place.
 *
 * <p>Every path an agent or a gate touches is derived from {@link #repo()}: the agent is handed that
 * directory as its working root and never a path above it. That is where an agent works, not a
 * wall around it — Claude runs with its permissions bypassed and no sandbox, so it can reach
 * anything the person running Forge can, and transcripts show agents using {@code %TEMP%} freely.
 * Codex's sandbox is a real boundary when it works. The run's own bookkeeping deliberately sits
 * <i>outside</i> the repository, so agent commits cannot rewrite the state file that governs them.
 *
 * @param root the run directory, e.g. {@code runs/001}
 * @param repo the project being worked on. Usually {@code root/repo}, but an improvement points at
 *             its parent's repository — that is what makes it a change to existing code rather than
 *             a new project, while keeping its own logs, evidence and state separate.
 */
public record RunPaths(Path root, Path repo) {

    public RunPaths(Path root) {
        this(root, root.resolve("repo"));
    }

    /** The same run, but working inside another project's repository. */
    public RunPaths workingIn(Path otherRepo) {
        return new RunPaths(root, otherRepo);
    }

    /**
     * The reference picture of the project's default look, held still while extras are added.
     *
     * <p>Beside the repository rather than under this run, because an enhancement works in its
     * parent's repository and must be held to the same reference as the parent.
     */
    public Path lookReference() {
        return repo.resolveSibling("look-reference.png");
    }

    /**
     * Where the family's repository on GitHub is recorded, and what has been pushed to it.
     *
     * <p>Beside the repository, like {@link #lookReference()}, because every project of a family
     * works in the one repository and publishes to the one place.
     */
    public Path githubFile() {
        return repo.resolveSibling("github.json");
    }

    /** Agent transcripts, gate output, and the generated acceptance scripts. */
    public Path logs() {
        return root.resolve("logs");
    }

    /** Artefacts named by the spec's evidence globs, kept after the project's build output is pruned. */
    public Path evidence() {
        return root.resolve("evidence");
    }

    /**
     * Where this session's artefacts go.
     *
     * <p>Kept apart rather than overwritten, because the question a report has to answer on the
     * second reading is "what changed", and a screenshot that is replaced in place can never
     * answer it. A project running eight sessions leaves eight views of itself.
     */
    public Path evidenceForSession(int session) {
        return evidence().resolve("session-" + session);
    }

    public Path stateFile() {
        return root.resolve("state.json");
    }

    public Path reviewFile() {
        return root.resolve("review.json");
    }

    /** Where the security check keeps its report and the output of the tools it ran. */
    public Path securityDir() {
        return root.resolve("security");
    }

    public Path securityFile() {
        return securityDir().resolve("security.json");
    }

    /** Deploy scripts, their logs, and the history of every deploy. */
    public Path deployDir() {
        return root.resolve("deploy");
    }

    public Path deploymentsFile() {
        return deployDir().resolve("deployments.json");
    }

    /** The marketing kit's own folder, beside the repository and never in it. */
    public Path marketingDir() {
        return root.resolve("marketing");
    }

    public Path marketingStatusFile() {
        return marketingDir().resolve("status.json");
    }

    /**
     * Where the vendors' design mock-ups are made and kept, beside the repository and never in it.
     *
     * <p>Each vendor works in a folder of its own under here. The one a person picks is copied into
     * the repository for the building agent to see; the rest never touch the product.
     */
    public Path designDir() {
        return root.resolve("design");
    }

    public Path designBoardFile() {
        return designDir().resolve("board.json");
    }

    /**
     * The design a polish was asked to match, kept outside {@link #designDir()} so that asking for a
     * new set of designs, which empties that folder, does not erase the record of what was built.
     */
    public Path polishFile() {
        return root.resolve("polish.json");
    }

    public Path reportFile() {
        return root.resolve("report.html");
    }

    /** What the reader has already ruled on, so the report is not a form to fill in twice. */
    public Path decisionsFile() {
        return root.resolve("decisions.json");
    }

    /**
     * Every review the reader has submitted, one entry per submission, oldest first.
     *
     * <p>Beside {@code decisions.json} rather than in it: that file is the latest position on each
     * item and is rewritten by every sitting, while this one is only ever added to.
     */
    public Path reviewHistoryFile() {
        return root.resolve("review-history.json");
    }

    /**
     * Which proposed features were approved, and what was said about each.
     *
     * <p>Apart from {@code decisions.json} because the two answer different questions and are
     * submitted from different forms: one is a judgement on finished work, the other decides what
     * the project owes a recording of. Keeping them in one file would make an approval look like
     * an acceptance.
     */
    public Path featuresFile() {
        return root.resolve("features.json");
    }

    /**
     * Present while feedback left during a running session is still waiting to be acted on.
     *
     * <p>A file rather than a flag in memory, so a restart between the submission and the end of
     * the session does not quietly drop what was asked for.
     */
    public Path pendingFeedbackFile() {
        return root.resolve("feedback.pending");
    }

    /**
     * The plan the planning turn wrote, in front of every turn that follows it.
     *
     * <p>Beside the run rather than inside the repository: it is Forge's context for the agent,
     * not part of the project, and committing it would put it in the diff the reviewer reads.
     */
    public Path planFile() {
        return root.resolve("plan.md");
    }

    /** What this project is, in prose meant to be read aloud. */
    public Path walkthroughFile() {
        return root.resolve("walkthrough.md");
    }

    /** The same walkthrough, recorded, for listening to away from the machine. */
    public Path walkthroughAudio() {
        return root.resolve("walkthrough.wav");
    }

    /** The agent's standing description of the project, rewritten each session. */
    public Path digestFile() {
        return root.resolve("digest.md");
    }

    public Path gitLog() {
        return logs().resolve("git.log");
    }

    public Path gateLog() {
        return logs().resolve("gate.log");
    }

    public static RunPaths under(Path runsDir, String id) {
        return new RunPaths(runsDir.resolve(id));
    }
}
