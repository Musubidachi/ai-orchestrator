package dev.fmangoni.forge.security.session;

import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The security layer that reads what the agents did, rather than the code they left.
 *
 * <p>The other layers judge the project. This one judges the sessions that built it, from the
 * vendors' own event streams in {@code logs/}: every command an agent ran and every file it wrote,
 * against {@link RiskRules}. An agent that pushed, published, installed something, changed a
 * system setting, wrote outside its project or stopped a process it found holding a port has done
 * something no repair turn can undo, so its findings are never "serious" — they earn no repair —
 * but a high one needs a person's ruling before the version is approved, one per distinct command.
 * Medium ones are listed in the report, one line per kind and log.
 */
public final class SessionAudit {

    public static final String LAYER = SecurityFinding.SESSIONS_LAYER;

    private static final List<String> LOGS = List.of("claude.log", "codex.log", "antigravity.log");

    private static final int EXAMPLES = 3;

    private final Path logs;
    private final List<Path> allowed;
    private final Path temp;

    /**
     * @param logs    the run's {@code logs/} folder
     * @param allowed the folders an agent may write in; the first is the repository, which
     *                relative paths are taken from
     * @param temp    the temp folder, where the CLIs keep agents' scratch files
     */
    public SessionAudit(Path logs, List<Path> allowed, Path temp) {
        this.logs = logs;
        this.allowed = List.copyOf(allowed);
        this.temp = temp;
    }

    public LayerResult run() throws IOException {
        List<SecurityFinding> found = new ArrayList<>();
        int read = 0;
        int actions = 0;
        for (String name : LOGS) {
            Path log = logs.resolve(name);
            if (!Files.isRegularFile(log)) {
                continue;
            }
            read++;
            List<AgentAction> done = SessionLog.read(log);
            actions += done.size();
            found.addAll(RiskRules.judge(done, allowed, temp));
        }
        if (read == 0) {
            return LayerResult.skipped(LAYER, "no agent has worked on this project yet");
        }
        return LayerResult.ran(LAYER, "read " + actions + " commands and file writes from " + read
                + (read == 1 ? " agent log" : " agent logs"), grouped(found));
    }

    /**
     * High findings once per distinct location — a command, or the folder files were written in —
     * and medium ones once per kind and log, each with a few examples.
     *
     * <p>The location is what names a ruling, so it must read the same on every check: a count
     * belongs in the evidence, never in the location.
     */
    private static List<SecurityFinding> grouped(List<SecurityFinding> found) {
        Map<String, List<SecurityFinding>> same = new LinkedHashMap<>();
        for (SecurityFinding finding : found) {
            String where = finding.rank() <= 1 ? finding.location() : logOf(finding);
            same.computeIfAbsent(finding.title() + "\n" + where, key -> new ArrayList<>()).add(finding);
        }
        List<SecurityFinding> out = new ArrayList<>();
        for (List<SecurityFinding> group : same.values()) {
            SecurityFinding first = group.get(0);
            List<String> examples = group.stream().map(SecurityFinding::evidence).distinct().limit(EXAMPLES).toList();
            String evidence = group.size() == 1 ? first.evidence()
                    : group.size() + " times, for example: " + String.join("  ·  ", examples);
            String where = first.rank() <= 1 ? first.location() : logOf(first);
            out.add(new SecurityFinding(LAYER, first.severity(), first.title(), where, evidence, false));
        }
        out.sort(Comparator.comparingInt(SecurityFinding::rank));
        return out;
    }

    /** The log a finding came from: its location reads {@code claude.log: <command>}. */
    private static String logOf(SecurityFinding finding) {
        int colon = finding.location().indexOf(": ");
        return colon < 0 ? finding.location() : finding.location().substring(0, colon);
    }
}
