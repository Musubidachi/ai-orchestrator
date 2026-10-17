package dev.fmangoni.forge.git;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Turns a raw diff into something a reviewing model can actually read in one turn.
 *
 * <p>Written after measuring what the reviewer on one project had been sent: 1.5 MB of diff, 93% of
 * it {@code sample/secured.pdf}. An encrypted PDF has no NUL bytes near its start, so git calls it
 * text and prints all of it; git also sorts paths, so {@code sample/} came before {@code src/}; and
 * the review cap then cut the diff at 60,000 characters. The reviewer received a screenful of PDF
 * internals and not one line of Java — and reported, accurately, that the visible diff was binary
 * data. The review was blamed on the free model. It was Forge's input.
 *
 * <p>Three rules, each aimed at one part of that:
 * <ul>
 *   <li>files that are documents or media by extension are left out, whatever git thinks of their
 *       bytes;</li>
 *   <li>any single file larger than a quarter of the budget is replaced by a line saying so, which
 *       also catches the fixture, the minified bundle and the generated SVG that no list will
 *       ever name;</li>
 *   <li>code comes first, so if the budget runs out it runs out on configuration and prose.</li>
 * </ul>
 */
@UtilityClass
public class ReviewDiff {

    /** Formats a reviewer cannot read and a builder did not hand-write. */
    static final Set<String> NOT_TEXT = Set.of(
            "pdf", "docx", "doc", "xlsx", "xls", "pptx", "ppt", "odt", "ods",
            "zip", "jar", "war", "gz", "tgz", "7z", "rar",
            "webm", "mp4", "mov", "avi", "mkv", "mp3", "wav", "ogg", "flac",
            "gif", "png", "jpg", "jpeg", "webp", "bmp", "ico", "tif", "tiff", "psd",
            "glb", "gltf", "obj", "fbx", "stl",
            "woff", "woff2", "ttf", "otf", "eot",
            "class", "exe", "dll", "so", "dylib", "bin", "db", "sqlite", "wasm");

    private static final Pattern HEADER = Pattern.compile("(?m)^diff --git a/(.+?) b/");

    public String shape(String raw, int maxChars) {
        List<DiffSection> sections = split(raw);
        int perFile = Math.max(2_000, maxChars / 4);

        List<String> leftOut = new ArrayList<>();
        List<DiffSection> kept = new ArrayList<>();
        for (DiffSection section : sections) {
            if (NOT_TEXT.contains(section.extension())) {
                leftOut.add(section.path() + " (" + section.extension() + ", not reviewable text)");
            } else if (!section.code() && section.body().length() > perFile) {
                // Code is exempt: a 26,000-character test class is exactly what a reviewer should
                // read, and the first version of this rule dropped one. Size only ever meant
                // "generated" for the things that are not code.
                leftOut.add(section.path() + " (" + section.body().length()
                        + " characters, too large to read in a review)");
            } else {
                kept.add(section);
            }
        }

        // Code, then tests, then everything else. A stable sort keeps git's own order within each.
        kept.sort(Comparator.comparingInt(section ->
                section.code() && !section.test() ? 0 : section.code() ? 1 : 2));

        StringBuilder out = new StringBuilder();
        if (!leftOut.isEmpty()) {
            out.append("[Forge left these files out of the diff below. They are in the repository ")
                    .append("if a finding genuinely depends on one:\n");
            leftOut.forEach(line -> out.append("  - ").append(line).append('\n'));
            out.append("]\n\n");
        }

        for (DiffSection section : kept) {
            if (out.length() + section.body().length() > maxChars) {
                out.append("\n...[diff truncated here: it is larger than one review can usefully ")
                        .append("read. Everything above is code or tests; what was not shown is ")
                        .append("configuration and prose. Say in your findings that the rest was ")
                        .append("not reviewed.]\n");
                break;
            }
            out.append(section.body());
        }
        return out.toString();
    }

    /** Sections in git's order, each starting at its own {@code diff --git} line. */
    List<DiffSection> split(String raw) {
        List<DiffSection> sections = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return sections;
        }
        Matcher matcher = HEADER.matcher(raw);
        List<int[]> starts = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        while (matcher.find()) {
            starts.add(new int[] {matcher.start()});
            paths.add(matcher.group(1));
        }
        if (starts.isEmpty()) {
            sections.add(new DiffSection("(unrecognised)", raw));
            return sections;
        }
        for (int i = 0; i < starts.size(); i++) {
            int from = starts.get(i)[0];
            int to = i + 1 < starts.size() ? starts.get(i + 1)[0] : raw.length();
            sections.add(new DiffSection(paths.get(i), raw.substring(from, to)));
        }
        return sections;
    }
}
