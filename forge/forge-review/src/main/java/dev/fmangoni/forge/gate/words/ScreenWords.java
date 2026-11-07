package dev.fmangoni.forge.gate.words;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The words a project's suite saved for each screen in each look, read from {@code <screen>.<look>.txt}.
 *
 * <p>Compared as lines, each with its spacing tidied and blank ones left out, in any order: a theme
 * may lay a screen out differently, and what has to stay the same is what it says.
 */
final class ScreenWords {

    private static final String SUFFIX = ".txt";

    private ScreenWords() {
    }

    /** Screen, then look, then the file. Names without a look are kept under an empty look. */
    static Map<String, Map<String, Path>> in(Path folder) {
        Map<String, Map<String, Path>> out = new TreeMap<>();
        for (Path file : texts(folder)) {
            String name = file.getFileName().toString();
            String stem = name.substring(0, name.length() - SUFFIX.length());
            int dot = stem.lastIndexOf('.');
            String screen = dot < 0 ? stem : stem.substring(0, dot);
            String look = dot < 0 ? "" : stem.substring(dot + 1);
            out.computeIfAbsent(screen, key -> new TreeMap<>()).put(look, file);
        }
        return out;
    }

    /** The text files directly in the folder; none when there is no folder. */
    static List<Path> texts(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * What one look says differently from the default, as lines starting {@code -} (only in the
     * default) and {@code +} (only in this look). Empty when they say the same.
     */
    static List<String> differences(Path defaultFile, Path lookFile) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        lines(defaultFile).forEach(line -> counts.merge(line, 1, Integer::sum));
        List<String> added = new ArrayList<>();
        for (String line : lines(lookFile)) {
            if (counts.getOrDefault(line, 0) > 0) {
                counts.merge(line, -1, Integer::sum);
            } else {
                added.add("+ " + line);
            }
        }
        List<String> out = new ArrayList<>();
        counts.forEach((line, left) -> {
            for (int i = 0; i < left; i++) {
                out.add("- " + line);
            }
        });
        out.addAll(added);
        return out;
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).lines()
                    .map(line -> line.strip().replaceAll("\\s+", " "))
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
