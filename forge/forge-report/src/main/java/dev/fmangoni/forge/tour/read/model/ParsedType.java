package dev.fmangoni.forge.tour.read.model;

import java.util.List;

/**
 * One source file as the parser found it: the type it declares, its note, and its methods.
 *
 * <p>Carries the file's lines because every scene about this class shows a window onto them, and
 * reading the file once is the difference between a tour of a large project taking seconds and
 * taking minutes.
 */
public record ParsedType(
        String name,
        String kind,
        String doc,
        String packageName,
        String file,
        List<String> lines,
        List<ParsedMethod> methods) {

    /** The lines between {@code from} and {@code to}, clamped to the file. Both are 1-based. */
    public List<String> between(int from, int to) {
        int first = Math.max(1, from);
        int last = Math.min(lines.size(), to);
        return first > last ? List.of() : lines.subList(first - 1, last);
    }
}
