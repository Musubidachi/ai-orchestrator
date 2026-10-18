package dev.fmangoni.forge.gate;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.feature.Features;
import dev.fmangoni.forge.feature.RecordingTake;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.process.local.ShellScript;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * Decides whether a project is done, by running the spec's acceptance commands itself.
 *
 * <p>This class is the reason the pipeline is worth building. An agent asked whether it finished
 * will almost always say yes; on this machine Claude Code reported {@code subtype: "success"} for a
 * turn that did nothing at all. So nothing an agent says reaches this decision — the verdict comes
 * from the exit codes of commands the spec author wrote, run in a fresh process against the files
 * actually on disk.
 */
@RequiredArgsConstructor
public final class Gate {

    private final Path repo;
    private final Path scriptDir;
    private final Path evidenceDir;
    private final Path logFile;
    private final Duration commandTimeout;

    /** Below this a file cannot be showing anyone the shape of a program. */
    private static final long MIN_OVERVIEW_BYTES = 200;

    /**
     * Runs acceptance in the order the spec declared, stopping at the first failure.
     *
     * <p>Stopping early is deliberate: later commands usually depend on earlier ones (a demo run
     * after a build), so continuing past a failure produces a cascade of errors that buries the one
     * that matters in the repair prompt.
     */
    /**
     * @param overview path the agent named as the class describing the whole flow. {@code null}
     *                 means it named none, which is itself a failure — there is deliberately no
     *                 overload that omits this, so no caller can skip the requirement by accident.
     * @param owed     features that have been approved and therefore owe a recording. Empty until
     *                 somebody approves something, so a project acquires this obligation only by a
     *                 person saying yes to it.
     */
    public GateResult run(List<String> acceptance, List<String> evidenceGlobs, String overview,
                          List<Feature> owed) {
        clearRecordings(owed);
        List<CommandResult> results = new ArrayList<>();
        for (int i = 0; i < acceptance.size(); i++) {
            CommandResult result = runOne(i + 1, acceptance.get(i));
            results.add(result);
            if (!result.passed()) {
                break;
            }
        }
        if (results.stream().allMatch(CommandResult::passed)) {
            checkEvidence(evidenceGlobs).ifPresent(results::add);
            checkOverview(overview).ifPresent(results::add);
            checkRecordings(owed).ifPresent(results::add);
            RunFile.check(repo).ifPresent(results::add);
        }

        // Recordings are collected whether or not they were all produced: a run that filmed two of
        // three features should show the two, and the report is how anyone finds out which.
        List<String> collect = new ArrayList<>(evidenceGlobs);
        collect.addAll(Features.evidenceGlobs(owed));
        return new GateResult(results, EvidenceCollector.collect(repo, collect, evidenceDir));
    }

    /**
     * Fails the gate when an approved feature has no recording anyone can watch.
     *
     * <p>The reason this is a gate check and not a note in the report: a screenshot cannot show
     * whether a thing responds, keeps time, or survives the third click, and that is the whole of
     * what a person wants to know about a program they did not build. Asked politely, it does not
     * happen — the house rules have asked for recordings of interactive work since the beginning
     * and six projects produced one recording between them.
     *
     * <p>Names the exact path each one is expected at. A requirement the agent has to guess the
     * filename of is a requirement that fails on spelling.
     */
    private Optional<CommandResult> checkRecordings(List<Feature> owed) {
        List<RecordingTake> missing = Features.unrecorded(repo, owed);
        if (missing.isEmpty()) {
            return Optional.empty();
        }

        StringBuilder detail = new StringBuilder("""
                The acceptance commands passed, but %d approved feature(s) have no usable \
                recording. Each one needs a short clip of itself actually working — not a \
                screenshot, and not a test that asserts it works.

                """.formatted(missing.size()));
        for (RecordingTake take : missing) {
            Feature feature = take.feature();
            detail.append("  - ").append(feature.name())
                    .append("  —  ").append(take.problem()).append('\n')
                    .append("      show: ")
                    .append(feature.demo().isBlank() ? "the feature being used" : feature.demo())
                    .append('\n')
                    .append("      write to: ").append(feature.recordingPath(".webm"))
                    .append("  (")
                    .append(String.join(" or ", Feature.FORMATS))
                    .append(" all count)\n");
        }
        detail.append(everyClip(owed, missing));
        detail.append("""

                These are deleted before the acceptance commands run and looked for afterwards, so \
                a recording has to be produced by one of those commands — a clip committed to the \
                repository will not survive to be checked. Wire the capture into the suite the \
                spec already runs: that is also what makes it a recording of what the code does \
                today rather than of what it did the day someone filmed it.

                How to do that for this stack is the record-feature skill \
                (.claude/skills/record-feature/SKILL.md, or .agents/skills/record-feature/SKILL.md), \
                with a tested GIF writer for desktop and command-line programs. Do not \
                fake these. A clip of a mock, or of a screen the feature does not actually drive, \
                is worse than no clip, because it is evidence of something that was never true.""");

        return Optional.of(new CommandResult(
                "recordings: " + missing.size() + " feature(s) unfilmed",
                1, detail.toString(), false, Duration.ZERO, ""));
    }

    /**
     * Every clip the gate wanted, filmed or not, each under the exact name it is owed by.
     *
     * <p>Listing only the missing ones let an agent fix one by breaking another. 003 had one feature
     * approved under two names and one test filming it; told only "Report export is missing", the
     * agent renamed the clip from the other name, and the next gate found that one missing instead.
     * It went back and forth until the sessions ran out. Seen whole, the list says that every name
     * needs its own clip, and that two names for one thing is a question for the person.
     */
    private String everyClip(List<Feature> owed, List<RecordingTake> missing) {
        StringBuilder out = new StringBuilder("""

                Every approved feature needs a clip of its own, under its own name. All of them, as \
                this gate found them:
                """);
        for (Feature feature : owed) {
            boolean absent = missing.stream().anyMatch(take -> take.feature().id().equals(feature.id()));
            String where = absent ? feature.recordingPath(".webm")
                    : Features.recordingOf(repo, feature)
                            .map(file -> repo.relativize(file).toString().replace('\\', '/'))
                            .orElse(feature.recordingPath(".webm"));
            out.append("  - ").append(feature.name()).append(": ")
                    .append(absent ? "MISSING, expected at " : "filmed, at ").append(where).append('\n');
        }
        out.append("""

                If two of these names are the same feature, do not rename one clip to satisfy the \
                other: that films one name and loses the other, and the next gate fails on the one \
                you lost. Say so in your handoff instead, naming both. Which name stays is for the \
                person to decide.
                """);
        return out.toString();
    }

    /**
     * Deletes the approved features' recordings before anything runs.
     *
     * <p>This is the check, rather than anything that inspects the file afterwards. Forge cannot
     * watch a video and never will, so asking "is this clip real" of the bytes on disk is the
     * wrong question — it can only ever catch a careless fake. Asking instead that the file be
     * <em>produced, from source, by a command Forge itself ran</em> is the same answer the rest of
     * the pipeline gives about everything else, and it is not one an agent can talk its way past:
     * a hand-made clip committed to the repository is deleted here and does not come back.
     *
     * <p>It has a second effect worth as much as the first. A recording that has to be regenerated
     * every gate is a recording of what the code does today, not of what it did on the afternoon
     * somebody filmed it — so a feature that quietly breaks in session nine stops having a clip.
     */
    private void clearRecordings(List<Feature> owed) {
        for (Feature feature : owed == null ? List.<Feature>of() : owed) {
            for (String candidate : feature.acceptablePaths()) {
                Path file = repo.resolve(candidate).normalize();
                if (!file.startsWith(repo.normalize())) {
                    continue;
                }
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // A file that cannot be deleted is reported by the check below as the stale
                    // one it is, rather than failing the whole gate here with an IO error.
                    System.err.println("could not clear " + file + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Fails the gate when the spec nominated evidence the project did not produce.
     *
     * <p>Only checked once the commands pass, because a build that failed has an obvious reason for
     * producing no screenshots and naming that second would bury the first.
     *
     * <p>Reported as a synthetic command failure so it flows through the existing machinery: the
     * repair brief names the missing artefacts, and the agent gets an actionable instruction rather
     * than a silent omission nobody notices until the report is empty.
     */
    private Optional<CommandResult> checkEvidence(List<String> evidenceGlobs) {
        List<String> missing = EvidenceCollector.unmatched(repo, evidenceGlobs);
        if (missing.isEmpty()) {
            return Optional.empty();
        }
        String detail = """
                The acceptance commands passed, but the spec's Evidence list names artefacts that \
                were not produced:

                %s

                These are how a human checks this work, so the project is not accepted without \
                them. Produce them as part of the normal run — do not fabricate them, and do not \
                remove them from the spec.""".formatted(
                missing.stream().map(g -> "  - " + g).reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b));

        return Optional.of(new CommandResult(
                "evidence: " + String.join(", ", missing),
                1, detail, false, Duration.ZERO, ""));
    }

    /**
     * Fails the gate when the class that explains the approach is missing.
     *
     * <p>Checks only that the named file exists and is not a stub — whether it genuinely reads as
     * the program's flow is a judgement, and belongs to the cross-review and to the person reading
     * the report. This catches the cheaper failure: not writing one at all, or naming a path that
     * was never created.
     *
     * @param overview the path named in the handoff; blank if the handoff named none, and
     *                 {@code null} if there was no handoff at all. The two are not the same
     *                 failure and must not be reported as though they were — an interrupted turn
     *                 produces the second, and telling the agent it forgot a field it never
     *                 reached sends the next turn after the wrong thing entirely.
     */
    private Optional<CommandResult> checkOverview(String overview) {
        if (overview == null) {
            return Optional.of(missingOverview(
                    "The last turn ended without reporting a handoff at all — it was interrupted, "
                            + "or it stopped before writing one. Its work is committed, but "
                            + "nothing is known about what was built, including which class reads "
                            + "as the whole program's flow. Finish what is outstanding and report "
                            + "a handoff, naming that class as `overview`."));
        }
        if (overview.isBlank()) {
            return Optional.of(missingOverview(
                    "No overview class was named in the handoff. AGENTS.md requires one class that "
                            + "reads as the whole program's flow, reported as `overview`."));
        }
        Optional<Path> named = inRepository(overview);
        if (named.isEmpty()) {
            return Optional.of(missingOverview(
                    "The handoff's `overview` was `" + overview + "`, which is not a path inside the "
                            + "repository. Name the one class that reads as the whole program's "
                            + "flow, as a path relative to the repository root."));
        }
        Path file = named.get();
        if (!Files.isRegularFile(file)) {
            return Optional.of(missingOverview(
                    "The handoff named `" + overview + "` as the overview class, but no such file "
                            + "exists in the repository."));
        }
        if (sizeOf(file) < MIN_OVERVIEW_BYTES) {
            return Optional.of(missingOverview(
                    "`" + overview + "` is too small to describe anything. It should show the "
                            + "top-level flow in order, each step delegating to a named collaborator."));
        }
        return Optional.empty();
    }

    /**
     * The file a handoff's path names, if it is a path at all and stays inside the repository.
     *
     * <p>The value is whatever the agent wrote, and an agent that could not do its job writes
     * prose. Project 004's said "not verified: workspace access failed before source inspection";
     * on Windows the colon makes that an illegal path, resolving it threw, and the gate — which had
     * just passed every acceptance command — failed with it. It did that every twelve minutes for
     * three days. Anything that is not a usable path is simply not the overview class.
     */
    private Optional<Path> inRepository(String relative) {
        try {
            Path file = repo.resolve(relative).normalize();
            return file.startsWith(repo.normalize()) ? Optional.of(file) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private CommandResult missingOverview(String detail) {
        return new CommandResult("overview class", 1, detail, false, Duration.ZERO, "");
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private CommandResult runOne(int ordinal, String command) {
        Path script = ShellScript.write(scriptDir, "gate-" + ordinal, command);
        List<String> invocation = ShellScript.invocation(script);

        ProcResult proc = CliRunner.run(
                Paths.get(invocation.get(0)),
                invocation.subList(1, invocation.size()),
                repo,
                null,
                commandTimeout,
                logFile);

        return new CommandResult(
                command, proc.exitCode(), proc.combined(), proc.timedOut(), proc.elapsed(),
                script.toString());
    }
}
