package dev.fmangoni.forge.security.layer;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.security.layer.model.SecretRule;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Credentials committed to the project, in the tree or anywhere in its history.
 *
 * <p>Precise rules only: each one matches a format a provider actually issues, so a hit is almost
 * always real. A scanner that flags every long random-looking string gets ignored after the first
 * week, and an ignored secret scanner is worse than none because its silence reads as a clean bill.
 *
 * <p>History matters as much as the tree. A key deleted in the next commit is still in the
 * repository, and anyone who can clone it can read it; the only fix is to revoke the key.
 */
public final class SecretScan {

    public static final String LAYER = SecurityFinding.SECRETS_LAYER;

    /** Files larger than this are not source anyone typed a key into. */
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    private static final List<SecretRule> RULES = List.of(
            new SecretRule("private key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
                    SecurityFinding.CRITICAL, true),
            new SecretRule("AWS access key id", Pattern.compile("AKIA[0-9A-Z]{16}"),
                    SecurityFinding.CRITICAL, true),
            new SecretRule("Stripe live secret key", Pattern.compile("sk_live_[0-9A-Za-z]{24,}"),
                    SecurityFinding.CRITICAL, true),
            new SecretRule("GitHub token", Pattern.compile("ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{22,}"),
                    SecurityFinding.HIGH, true),
            new SecretRule("Slack token", Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
                    SecurityFinding.HIGH, true),
            // Medium and not serious: Firebase and Maps keys are designed to sit in a web page and
            // are protected by referrer restrictions, so a hit is a prompt to check those, not a leak.
            new SecretRule("Google API key", Pattern.compile("AIza[0-9A-Za-z_-]{35}"),
                    SecurityFinding.MEDIUM, false));

    /** The same rules as one extended regular expression, for {@code git grep} over history. */
    private static final String HISTORY_PATTERN = String.join("|",
            "-----BEGIN [A-Z ]*PRIVATE KEY-----", "AKIA[0-9A-Z]{16}", "sk_live_[0-9A-Za-z]{24,}",
            "ghp_[A-Za-z0-9]{36}", "github_pat_[A-Za-z0-9_]{22,}", "xox[baprs]-[A-Za-z0-9-]{10,}",
            "AIza[0-9A-Za-z_-]{35}");

    /** {@code .env}, {@code .env.production} — but not the templates meant to be committed. */
    private static final Pattern ENV_FILE =
            Pattern.compile("(^|/)\\.env(\\.(?!example$|sample$|template$|dist$)[A-Za-z0-9_.-]+)?$");

    private static final Pattern ENV_ASSIGNMENT =
            Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\\S.*)$");

    private final Path repo;
    private final Git git;

    public SecretScan(Path repo, Git git) {
        this.repo = repo;
        this.git = git;
    }

    public LayerResult run() {
        Map<String, SecurityFinding> found = new LinkedHashMap<>();
        List<String> files = git.files();
        for (String file : files) {
            scanFile(file, found);
        }
        int inTree = found.size();
        scanHistory(found);
        String detail = files.size() + " file(s) in the tree and every commit in history";
        return LayerResult.ran(LAYER, found.size() > inTree
                ? detail + "; " + (found.size() - inTree) + " only in history" : detail,
                new ArrayList<>(found.values()));
    }

    private void scanFile(String relative, Map<String, SecurityFinding> found) {
        Path file = repo.resolve(relative);
        String text = readText(file);
        if (text == null) {
            return;
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            matchLine(lines[i], relative + ":" + (i + 1), "", true, found);
        }
        if (ENV_FILE.matcher(relative).find()) {
            envFile(relative, lines, found);
        }
    }

    /**
     * Git-tracked dotenv files with real values in them.
     *
     * <p>A {@code .env} is where a project keeps what must not be committed, so one in the tree with
     * assignments is a leak whatever the values look like. Blank values and obvious placeholders
     * are left alone.
     */
    private void envFile(String relative, String[] lines, Map<String, SecurityFinding> found) {
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            Matcher assignment = ENV_ASSIGNMENT.matcher(line);
            if (!assignment.matches()) {
                continue;
            }
            String value = assignment.group(2).replaceAll("^[\"']|[\"']$", "").strip();
            String lower = value.toLowerCase(Locale.ROOT);
            if (value.isEmpty() || lower.contains("changeme") || lower.contains("your_")
                    || lower.startsWith("<") || lower.equals("xxx") || lower.equals("todo")) {
                continue;
            }
            names.add(assignment.group(1));
        }
        if (!names.isEmpty()) {
            found.putIfAbsent("env:" + relative, new SecurityFinding(LAYER, SecurityFinding.HIGH,
                    "environment file committed with values", relative,
                    "sets " + String.join(", ", names) + " — move it out of git and add it to "
                            + ".gitignore, and rotate anything real", true));
        }
    }

    private void scanHistory(Map<String, SecurityFinding> found) {
        for (String line : git.grepHistory(HISTORY_PATTERN).lines().toList()) {
            // rev:path:line:text
            String[] parts = line.split(":", 4);
            if (parts.length < 4) {
                continue;
            }
            String where = parts[1] + ":" + parts[2];
            matchLine(parts[3], where, " in commit " + shortRev(parts[0])
                    + " (still in history even if removed since)", false, found);
        }
    }

    /**
     * @param inTree whether this is the working tree. A secret only in history is not serious: a
     *               repair turn cannot fix it, since the agent must not rewrite history and the key
     *               has to be revoked by its owner. It still goes to the person to rule on.
     */
    private void matchLine(String line, String where, String suffix, boolean inTree,
                           Map<String, SecurityFinding> found) {
        for (SecretRule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(line);
            while (matcher.find()) {
                String value = matcher.group();
                // The documentation's own example keys, which appear in tutorials and tests.
                if (value.contains("EXAMPLE")) {
                    continue;
                }
                // One finding per secret, wherever it appears: the fix is to revoke it once.
                found.putIfAbsent(rule.name() + ":" + value, new SecurityFinding(LAYER,
                        rule.severity(), rule.name() + " committed", where + suffix,
                        redact(value) + (inTree ? "" : " — revoke it; removing the line does not"),
                        inTree && rule.serious()));
            }
        }
    }

    /** Enough to find it again, not enough to use it. */
    static String redact(String value) {
        String head = value.length() <= 4 ? value : value.substring(0, 4);
        return head + "… (" + value.length() + " characters, redacted)";
    }

    private static String shortRev(String rev) {
        return rev.length() > 7 ? rev.substring(0, 7) : rev;
    }

    /** The file as text, or null for anything binary, huge, or unreadable. */
    private static String readText(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                byte[] head = in.readNBytes(8192);
                for (byte b : head) {
                    if (b == 0) {
                        return null;
                    }
                }
            }
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
