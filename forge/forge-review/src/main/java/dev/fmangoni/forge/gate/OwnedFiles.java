package dev.fmangoni.forge.gate;

import dev.fmangoni.forge.git.Git;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Holds a version to the folders its spec owns ({@code owns:}): a change anywhere else fails the gate.
 *
 * <p>For a family built one feature per version, where no feature may change another's code. Asked
 * of an agent in words, that holds until the first time it is convenient not to; checked here, against
 * every file changed since the version began, it holds every time. A feature that genuinely needs
 * something outside its folders needs another version, one that owns that.
 *
 * <p>A spec without {@code owns} is not checked, so nothing built before this changes.
 */
@UtilityClass
public class OwnedFiles {

    /**
     * Not the version's own code: the brief, the house rules Forge rewrites before every turn under
     * both names the agents read, the scaffold's ignore file, what the gate saves, and the evidence
     * the acceptance suite writes.
     *
     * <p>Evidence because every version's suite runs every earlier version's tests, and those save
     * their screenshots again. 008.1, the first version held to its folders, changed seven earlier
     * screenshots by passing their tests, and its own three lighting pictures were outside its
     * folders too, though its spec asked for them.
     */
    private final List<String> FORGES = List.of("IMPROVEMENT.md", "SPEC.md", "CLAUDE.md", "AGENTS.md", ".gitignore",
            "artifacts/**", "evidence/**");

    /**
     * The failure to report when the version changed files it does not own, or empty when it did not.
     *
     * @param baseline the commit the version began with; without one there is nothing to measure from
     */
    public Optional<CommandResult> check(Path repo, Path gitLog, String baseline, List<String> owns) {
        if (owns == null || owns.isEmpty() || baseline == null || baseline.isBlank()) {
            return Optional.empty();
        }
        Optional<List<String>> changed = new Git(repo, gitLog).changedFiles(baseline, "HEAD");
        if (changed.isEmpty()) {
            return Optional.of(failure("could not read what changed since " + abbreviated(baseline), """
                    The gate could not list the files this version changed since it began, at %s, so it \
                    cannot tell whether they all lie in the folders this version owns. That is not \
                    taken as a pass.""".formatted(baseline)));
        }
        List<String> outside = outside(changed.get(), owns);
        if (outside.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(failure(outside.size() + " file(s) outside the folders this version owns", """
                The acceptance commands passed, but this version changed files outside the folders its \
                spec says it owns. Each feature of this project is built in its own folders, so that \
                no feature can change another's code.

                Owned by this version:
                %s

                Changed outside them, since it began at %s:
                %s

                Put each back as it was: git checkout %s -- <file>, or delete a file this version \
                added. If the feature cannot be built without one of these changes, do not make it: \
                say in your handoff which file and why, because that change belongs to the version \
                that owns it.""".formatted(listed(owns), abbreviated(baseline), listed(outside), baseline)));
    }

    /** The changed paths no owned glob, and nothing Forge writes, covers. */
    List<String> outside(List<String> changed, List<String> owns) {
        List<Pattern> allowed = new ArrayList<>();
        for (String glob : owns) {
            allowed.add(regex(glob));
        }
        for (String glob : FORGES) {
            allowed.add(regex(glob));
        }
        return changed.stream()
                .map(path -> path.replace('\\', '/'))
                .filter(path -> allowed.stream().noneMatch(pattern -> pattern.matcher(path).matches()))
                .toList();
    }

    /**
     * A glob as a pattern over slash-separated paths from the repository root: {@code **} crosses
     * folders, {@code *} and {@code ?} stay inside one, and a folder ending in {@code /} owns
     * everything under it.
     */
    Pattern regex(String glob) {
        String g = glob.strip().replace('\\', '/').replaceFirst("^\\./", "");
        if (g.endsWith("/")) {
            g = g + "**";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                boolean slash = i + 2 < g.length() && g.charAt(i + 2) == '/';
                out.append(slash ? "(?:.*/)?" : ".*");
                i += slash ? 2 : 1;
            } else if (c == '*') {
                out.append("[^/]*");
            } else if (c == '?') {
                out.append("[^/]");
            } else {
                out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(out.toString());
    }

    private String listed(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append("  - ").append(line).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private String abbreviated(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private CommandResult failure(String what, String detail) {
        return new CommandResult("owned folders: " + what, 1, detail, false, Duration.ZERO, "");
    }
}
