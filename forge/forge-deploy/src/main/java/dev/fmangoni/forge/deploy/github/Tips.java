package dev.fmangoni.forge.deploy.github;

import dev.fmangoni.forge.git.Commit;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The commit a version's work ends at, which its branch points to.
 *
 * <p>Recorded when a run finishes ({@code RunState.finishedCommit}). A version that finished before
 * that was recorded has its tip read back out, two independent ways, and only an answer both give
 * is used: an enhancement branch that quietly held part of the next enhancement would be a
 * plausible wrong answer, which is worse than a refusal that says why.
 *
 * <ul>
 *   <li>The history: a family's versions take turns in one repository, and each enhancement begins
 *       with a commit Forge makes, "Requested improvement: " and its title. A version's work ends
 *       just before the next one begins.
 *   <li>Its own log: every commit Forge makes is followed in that run's git log by the id git
 *       printed for it, so the last one there is the last commit that version made.
 * </ul>
 */
final class Tips {

    /** The subject of the commit an enhancement begins with: {@code Workspace.scaffoldImprovement}. */
    static final String BEGINS = "Requested improvement: ";

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");

    private Tips() {
    }

    /**
     * @param family every version of the family that has started, this one included
     * @throws IllegalStateException saying why, when it cannot be told for certain
     */
    static String of(RunState version, List<RunState> family, Git git, Path runsDir) {
        String recorded = version.getFinishedCommit();
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        List<Commit> history = git.history();
        Map<String, Integer> began = new HashMap<>();
        for (RunState member : family) {
            int start = Branches.isInitial(member.getId()) ? 0 : firstWithSubject(history, BEGINS + member.getTitle());
            if (start < 0) {
                throw new IllegalStateException("cannot tell where " + version.getId() + "'s work ends: "
                        + member.getId() + "'s first commit is not in the history");
            }
            began.put(member.getId(), start);
        }
        int mine = began.get(version.getId());
        int next = began.values().stream().mapToInt(Integer::intValue).filter(start -> start > mine).min().orElse(-1);
        String byHistory = history.get(next < 0 ? history.size() - 1 : next - 1).sha();
        String byLog = lastCommitInLog(RunPaths.under(runsDir, version.getId()).gitLog());
        if (byLog == null) {
            throw new IllegalStateException("cannot tell where " + version.getId() + "'s work ends: its own git log"
                    + " has no commit to confirm the history by");
        }
        if (!byLog.equals(byHistory)) {
            throw new IllegalStateException("cannot tell where " + version.getId() + "'s work ends: its own log says "
                    + byLog.substring(0, 7) + " and the history says " + byHistory.substring(0, 7));
        }
        return byHistory;
    }

    private static int firstWithSubject(List<Commit> history, String subject) {
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).subject().equals(subject)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The id git printed after the last commit in a run's git log.
     *
     * <p>Read a line at a time, and with anything that is not UTF-8 replaced rather than refused:
     * one of these logs here is 173 MB, and a log is not worth failing over.
     */
    static String lastCommitInLog(Path log) {
        if (!Files.isRegularFile(log)) {
            return null;
        }
        String tip = null;
        boolean afterCommit = false;
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(Files.newInputStream(log),
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                if (line.startsWith("$ git") && line.contains(" commit ")) {
                    afterCommit = true;
                } else if (afterCommit && SHA.matcher(line.strip()).matches()) {
                    tip = line.strip();
                    afterCommit = false;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + log, e);
        }
        return tip;
    }
}
