package dev.fmangoni.forge.gate;

import dev.fmangoni.forge.git.Git;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Whether a version is stuck on a check that lives in a file it does not own.
 *
 * <p>A version held to its folders ({@link OwnedFiles}) cannot change another version's test, so a
 * repair turn cannot fix a failure that is only there. 008.1 was told to replace the foundation's
 * always-day lighting, and the foundation's own test pinned "always day": the agent said so on its
 * first turn and spent all four repair rounds, three million tokens, saying it again.
 *
 * <p>It takes two gates in a row. The first failure gets its repair turn as usual, because a version
 * can break another's test through its own code, and that it can fix. The same command failing again,
 * naming the same files and only files the version does not own, is a question for a person: is
 * that check out of date, or is the version wrong?
 */
@UtilityClass
public class ForeignFailure {

    /** A relative path with at least one folder and an extension, not part of a longer one. */
    private final Pattern PATH = Pattern.compile("(?<![\\w./-])((?:[\\w.-]+/)+[\\w.-]+\\.[A-Za-z]\\w*)");

    /** Why to hold the version for a person, or empty when another repair turn may help. */
    public Optional<String> stuck(Path repo, Path gitLog, List<String> owns, CommandResult before, CommandResult now) {
        if (!comparable(owns, before, now)) {
            return Optional.empty();
        }
        return stuck(repo, new HashSet<>(new Git(repo, gitLog).files()), owns, before, now);
    }

    Optional<String> stuck(Path repo, Set<String> tracked, List<String> owns, CommandResult before, CommandResult now) {
        if (!comparable(owns, before, now)) {
            return Optional.empty();
        }
        Set<String> named = named(repo, tracked, now);
        if (named.isEmpty() || !named.equals(named(repo, tracked, before))) {
            return Optional.empty();
        }
        List<String> foreign = OwnedFiles.outside(List.copyOf(named), owns);
        if (foreign.size() != named.size()) {
            return Optional.empty();
        }
        return Optional.of("`" + now.command() + "` failed the same way twice running, in "
                + String.join(", ", foreign) + ", which this version does not own: decide whether that "
                + "check is out of date (add the file to this version's owns) or the version is wrong "
                + "(send it back)");
    }

    /** A version held to folders, failing the same command at two gates in a row. */
    private boolean comparable(List<String> owns, CommandResult before, CommandResult now) {
        return owns != null && !owns.isEmpty() && before != null && now != null
                && Objects.equals(before.command(), now.command());
    }

    /** The files the repository tracks that this failure's output names, by path from its root. */
    Set<String> named(Path repo, Set<String> tracked, CommandResult failure) {
        Set<String> named = new TreeSet<>();
        if (failure.output() == null) {
            return named;
        }
        String root = repo.toAbsolutePath().normalize().toString().replace('\\', '/') + "/";
        String text = Pattern.compile(Pattern.quote(root), Pattern.CASE_INSENSITIVE)
                .matcher(failure.output().replace('\\', '/'))
                .replaceAll(" ");
        Matcher matcher = PATH.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(1).replaceFirst("^\\./", "");
            if (tracked.contains(path)) {
                named.add(path);
            }
        }
        return named;
    }
}
