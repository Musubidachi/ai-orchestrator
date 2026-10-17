package dev.fmangoni.forge.tour.read.model;

/**
 * One method as the parser found it: its name, its shape, its note, and where it sits in the file.
 *
 * <p>Line numbers rather than text. The tour shows the file's own lines, so the only thing worth
 * keeping is where to look — and a copy of the body would be a second version of the code, able to
 * disagree with the file it came from.
 */
public record ParsedMethod(String name, String signature, String doc, int firstLine, int lastLine) {

    /** A constructor is named {@code <init>} by the parser, which is not a thing to say out loud. */
    public boolean constructor() {
        return name.equals("<init>");
    }
}
