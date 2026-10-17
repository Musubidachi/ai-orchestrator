package dev.fmangoni.forge.pipeline.run;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Puts Forge's skills where each vendor's CLI looks for them in a project.
 *
 * <p>A skill is a procedure an agent loads when it needs it — how to record a feature, how to write
 * {@code run.bat} — rather than text carried through every turn of every project the way the house
 * rules are. Each lives in {@code skills/<name>/} beside {@code house-rules.md}, as a {@code SKILL.md}
 * and any helper files, and is copied into the project before every turn, like the rules, so an
 * edit reaches projects already under way.
 *
 * <p>Two folders, because the CLIs disagree on where to look: Claude Code reads {@code .claude/skills},
 * and Codex and Antigravity both read {@code .agents/skills} (found in their binaries: Codex 0.160's
 * capability locations, and agy's {@code {workspace}/.agents/skills/{skill_name}/SKILL.md}). A
 * vendor reading a third place would miss them — so the house rules keep each contract the gate
 * enforces, and a skill only ever adds the how.
 *
 * <p>Only the skills Forge put there are replaced or removed, recorded in a manifest beside them; a
 * skill a project made for itself is left alone.
 */
final class SkillInstall {

    /** Relative to the repository. Kept out of git by {@link Workspace}. */
    static final List<String> FOLDERS = List.of(".claude/skills", ".agents/skills");

    static final String MANIFEST = ".forge-skills";

    private SkillInstall() {
    }

    /** Copies every {@code <source>/<name>/} holding a {@code SKILL.md} into each folder in the repository. */
    static void into(Path repo, Path source) {
        Set<String> skills = skillsIn(source);
        for (String folder : FOLDERS) {
            Path target = repo.resolve(folder);
            try {
                for (String old : installed(target)) {
                    if (!skills.contains(old)) {
                        delete(target.resolve(old));
                    }
                }
                for (String name : skills) {
                    delete(target.resolve(name));
                    copy(source.resolve(name), target.resolve(name));
                }
                if (!skills.isEmpty() || Files.isDirectory(target)) {
                    Files.createDirectories(target);
                    Files.writeString(target.resolve(MANIFEST), String.join("\n", skills) + "\n", StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot install the skills into " + target, e);
            }
        }
    }

    private static Set<String> skillsIn(Path source) {
        Set<String> names = new TreeSet<>();
        if (source == null || !Files.isDirectory(source)) {
            return names;
        }
        try (Stream<Path> dirs = Files.list(source)) {
            dirs.filter(dir -> Files.isRegularFile(dir.resolve("SKILL.md")))
                    .forEach(dir -> names.add(dir.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the skills in " + source, e);
        }
        return names;
    }

    private static List<String> installed(Path target) throws IOException {
        Path manifest = target.resolve(MANIFEST);
        if (!Files.isRegularFile(manifest)) {
            return List.of();
        }
        return Files.readAllLines(manifest, StandardCharsets.UTF_8).stream()
                .map(String::strip)
                // A name is one folder: never a path that could reach outside the skills folder.
                .filter(name -> !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.startsWith("."))
                .toList();
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path file : files.toList()) {
                Path placed = to.resolve(from.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(placed);
                } else {
                    Files.createDirectories(placed.getParent());
                    Files.copy(file, placed);
                }
            }
        }
    }

    private static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(file);
            }
        }
    }
}
