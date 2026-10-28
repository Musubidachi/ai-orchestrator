package dev.fmangoni.forge.security.session;

import static java.util.regex.Pattern.CASE_INSENSITIVE;

import dev.fmangoni.forge.security.model.SecurityFinding;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What in an agent's session a person should know about: the things the house rules forbid an
 * agent, found in what it actually ran and wrote rather than in what it said.
 *
 * <p>Every rule here was calibrated against the logs of every project on this machine, which is how
 * {@code git stash push} stopped reading as a push and a family's versions, which all build in the
 * first version's repository, stopped reading as writing outside it.
 *
 * <p>Stopping processes needs more than a pattern. An agent stopping the server it started a
 * moment ago is doing what the rules ask; one stopping whatever answered on a port is how a
 * person's game was closed while they played it. So a process stopped by its number is high only
 * when that number came out of an earlier look at who held a port — the kill on port 4317 did
 * exactly that, a minute apart — and otherwise is listed for the person to glance at.
 */
public final class RiskRules {

    private static final Pattern PORT_LOOKUP = Pattern.compile(
            "Get-NetTCPConnection|\\bnetstat\\b|\\blsof\\s+-i|OwningProcess|\\bfuser\\b", CASE_INSENSITIVE);

    private static final Pattern KILL = Pattern.compile(
            "Stop-Process|\\btaskkill\\b|(?:^|[;&|\\s(])kill\\s", CASE_INSENSITIVE);

    /** Process numbers written out in a stop: {@code Stop-Process -Id 1,2}, {@code taskkill /PID 3}, {@code kill -9 4}. */
    private static final Pattern LITERAL_PIDS = Pattern.compile(
            "(?:Stop-Process\\s+(?:-Id\\s+)?|\\btaskkill\\b[^;\\n]*?/+PID\\s+|(?:^|[;&|\\s(])kill\\s+(?:-9\\s+|-KILL\\s+)?)"
            + "(\\d+(?:\\s*,\\s*\\d+)*)", CASE_INSENSITIVE);

    private static final Pattern NUMBER = Pattern.compile("\\b\\d{2,7}\\b");

    private static final Pattern DELETE = Pattern.compile(
            "Remove-Item\\b|\\brm\\s+-[a-zA-Z]*[rf]|\\brmdir\\b|\\brd\\s+/s\\b|\\bdel\\s+/[sqf]\\b", CASE_INSENSITIVE);

    /** A Windows path, or the {@code /c/...} form Git Bash writes. */
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "[A-Za-z]:[\\\\/][^\\s'\"`;|&<>)]*|(?<![\\w.])/[a-zA-Z]/[^\\s'\"`;|&<>)]*");

    private static final Pattern GIT_BASH_DRIVE = Pattern.compile("^/([a-zA-Z])/(.*)$");

    private static final String SETTINGS_TITLE = "touched agent settings or credentials";

    private static final String VENDOR_TITLE = "used the vendor's own files outside the project";

    /** A vendor CLI's folder in the person's home, on a path already lowercased with forward slashes. */
    private static final Pattern VENDOR_HOME = Pattern.compile("/users/[^/]+/\\.(?:claude|codex|gemini|antigravity|agy)/");

    /** An agent CLI's settings, whose hooks run commands, and the usual places credentials live. */
    private static final Pattern SETTINGS = Pattern.compile(
            "(?:^|[\\s'\"=\\\\/])\\.claude[\\\\/]+settings(?:\\.local)?\\.json|\\.codex[\\\\/]+config\\.toml"
            + "|\\bauth\\.json\\b|\\.credentials\\.json|\\.git-credentials|[\\\\/]\\.ssh[\\\\/]"
            + "|\\bid_(?:rsa|ed25519|ecdsa)\\b", CASE_INSENSITIVE);

    private static final List<RiskRule> COMMANDS = List.of(
            new RiskRule(SecurityFinding.HIGH, "stopped processes by name", Pattern.compile(
                    "\\btaskkill\\b[^;\\n]*/+IM\\b|Stop-Process\\b[^;\\n|]*-(?:Process)?Name\\b"
                    + "|Get-Process\\s+(?:-Name\\s+)?['\"]?[A-Za-z][\\w.*-]*['\"]?[^;\\n]*\\|\\s*Stop-Process"
                    + "|\\bpkill\\b|\\bkillall\\b|\\bwmic\\s+process\\b[^;\\n]*\\b(?:delete|terminate)\\b",
                    CASE_INSENSITIVE)),
            new RiskRule(SecurityFinding.HIGH, "pushed to a remote", Pattern.compile(
                    "\\bgit\\s+(?:-[Cc]\\s+\\S+\\s+|--?[\\w-]+(?:=\\S+)?\\s+)*push\\b", CASE_INSENSITIVE)),
            new RiskRule(SecurityFinding.HIGH, "published or deployed", Pattern.compile(
                    "\\bnpm\\s+publish\\b|\\bgh\\s+(?:repo\\s+create|release\\s+create|pr\\s+create)\\b"
                    + "|\\bvercel\\s+(?:deploy\\b|--prod\\b)|\\bnetlify\\s+deploy\\b|\\bfirebase\\s+deploy\\b",
                    CASE_INSENSITIVE)),
            new RiskRule(SecurityFinding.HIGH, "installed software outside the project", Pattern.compile(
                    "\\bnpm\\s+(?:i|install|add)\\b[^;\\n]*(?:\\s-g\\b|--global\\b)|\\bwinget\\s+install\\b"
                    + "|\\bchoco\\s+install\\b|\\bscoop\\s+install\\b|\\bInstall-(?:Module|Package)\\b"
                    + "|(?:\\biwr\\b|Invoke-WebRequest|\\bcurl\\b|\\bwget\\b)[^;\\n]*\\|\\s*(?:iex|Invoke-Expression|sh|bash)\\b",
                    CASE_INSENSITIVE)),
            new RiskRule(SecurityFinding.HIGH, "changed a system setting", Pattern.compile(
                    "\\breg(?:\\.exe)?\\s+(?:add|delete|import)\\b|Set-ItemProperty\\s+[^;\\n]*\\bHK(?:LM|CU)\\b"
                    + "|\\bSet-ExecutionPolicy\\b|\\bsetx\\b|\\bnetsh\\b|\\bschtasks\\b[^;\\n]*/create"
                    + "|\\bsc(?:\\.exe)?\\s+(?:config|create|delete|stop)\\b|\\b(?:Set|Add)-MpPreference\\b"
                    + "|\\bbcdedit\\b|\\bNew-NetFirewallRule\\b", CASE_INSENSITIVE)),
            new RiskRule(SecurityFinding.HIGH, SETTINGS_TITLE, SETTINGS),
            new RiskRule(SecurityFinding.MEDIUM, VENDOR_TITLE, Pattern.compile(
                    "(?:Users[\\\\/]+[^\\\\/\\s'\"]+|~|\\$HOME|\\$env:USERPROFILE|%USERPROFILE%)[\\\\/]+"
                    + "\\.(?:claude|codex|gemini|antigravity|agy)\\b(?![^\\s'\"]*tool-results)", CASE_INSENSITIVE)));

    private RiskRules() {
    }

    /**
     * Every risky thing in one log's actions, one finding per action and rule, in the order done.
     *
     * @param allowed the folders an agent may write in: the repository, and the folders Forge
     *                sends design and marketing turns to
     * @param temp    the temp folder, where the CLIs keep agents' scratch files; never flagged
     */
    public static List<SecurityFinding> judge(List<AgentAction> actions, List<Path> allowed, Path temp) {
        List<SecurityFinding> found = new ArrayList<>();
        Set<String> portOwners = new HashSet<>();
        for (AgentAction action : actions) {
            if (action.write()) {
                written(action, allowed, temp, found);
                continue;
            }
            for (RiskRule rule : COMMANDS) {
                if (rule.pattern().matcher(action.text()).find()) {
                    found.add(finding(rule.severity(), rule.title(), action));
                }
            }
            stopped(action, portOwners, found);
            deleted(action, allowed, temp, found);
        }
        return found;
    }

    private static void stopped(AgentAction action, Set<String> portOwners, List<SecurityFinding> found) {
        String command = action.text();
        Matcher lookup = PORT_LOOKUP.matcher(command);
        boolean looked = lookup.find();
        if (looked && KILL.matcher(command).find(lookup.start())) {
            found.add(finding(SecurityFinding.HIGH, "stopped whatever held a port", action));
        } else {
            boolean fromPort = false;
            boolean any = false;
            Matcher pids = LITERAL_PIDS.matcher(command);
            while (pids.find()) {
                for (String pid : pids.group(1).split("\\s*,\\s*")) {
                    any = true;
                    fromPort |= portOwners.contains(pid.strip());
                }
            }
            if (fromPort) {
                found.add(finding(SecurityFinding.HIGH, "stopped a process it had found holding a port", action));
            } else if (any) {
                found.add(finding(SecurityFinding.MEDIUM, "stopped a process by its number", action));
            }
        }
        if (looked) {
            Matcher numbers = NUMBER.matcher(action.output());
            while (numbers.find()) {
                portOwners.add(numbers.group());
            }
        }
    }

    /** A delete aimed at an absolute path outside the allowed folders, within the same statement. */
    private static void deleted(AgentAction action, List<Path> allowed, Path temp, List<SecurityFinding> found) {
        Matcher delete = DELETE.matcher(action.text());
        while (delete.find()) {
            String statement = action.text().substring(delete.end()).split(";|&&|\\|\\||\\||\\n", 2)[0];
            Matcher path = ABSOLUTE_PATH.matcher(statement);
            while (path.find()) {
                Path target = pathOf(path.group(), null);
                if (target != null && !inside(target, allowed) && !inside(target, List.of(temp))) {
                    found.add(finding(SecurityFinding.HIGH, "deleted files outside the project", action));
                    return;
                }
            }
        }
    }

    /**
     * A file written where it should not be. Settings and credentials first, wherever they are:
     * the project's own {@code .claude/settings.json} is inside the repository and is the one
     * file there whose hooks run outside every sandbox.
     */
    private static void written(AgentAction action, List<Path> allowed, Path temp, List<SecurityFinding> found) {
        if (SETTINGS.matcher(action.text()).find()) {
            found.add(finding(SecurityFinding.HIGH, SETTINGS_TITLE, action));
            return;
        }
        Path target = pathOf(action.text(), allowed.isEmpty() ? null : allowed.get(0));
        if (target == null || inside(target, allowed) || inside(target, List.of(temp))) {
            return;
        }
        if (VENDOR_HOME.matcher(key(target)).find()) {
            // The CLI's own memory or notes for this project: listed, not ruled on.
            found.add(finding(SecurityFinding.MEDIUM, VENDOR_TITLE, action));
            return;
        }
        // One ruling per folder, not per file: six helper scripts beside the repository are one act.
        Path folder = target.getParent() == null ? target : target.getParent();
        found.add(new SecurityFinding(SecurityFinding.SESSIONS_LAYER, SecurityFinding.HIGH, "wrote outside the project",
                action.log() + ": " + Evidence.shortened(folder.toString()), Evidence.masked(target.toString()), false));
    }

    /** An absolute path; a relative one is taken from {@code base}, or ignored without one. */
    private static Path pathOf(String text, Path base) {
        String raw = text.strip();
        Matcher bash = GIT_BASH_DRIVE.matcher(raw);
        if (bash.matches()) {
            raw = bash.group(1) + ":/" + bash.group(2);
        }
        try {
            Path path = Path.of(raw);
            if (!path.isAbsolute()) {
                if (base == null) {
                    return null;
                }
                path = base.resolve(path);
            }
            return path.normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Case-insensitive, as Windows paths are. */
    private static boolean inside(Path path, List<Path> folders) {
        String target = key(path);
        for (Path folder : folders) {
            if (folder == null) {
                continue;
            }
            String root = key(folder.toAbsolutePath().normalize());
            if (target.equals(root) || target.startsWith(root.endsWith("/") ? root : root + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String key(Path path) {
        return path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private static SecurityFinding finding(String severity, String title, AgentAction action) {
        return new SecurityFinding(SecurityFinding.SESSIONS_LAYER, severity, title,
                action.log() + ": " + Evidence.shortened(action.text()), Evidence.masked(action.text()), false);
    }
}
