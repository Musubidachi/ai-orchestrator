package dev.fmangoni.forge.tour.model;

/**
 * One screen of the tour: a heading, the sentence said over it, and the code it is about.
 *
 * <p>The spoken text is the unit of the whole tool. A scene lasts exactly as long as its sentences
 * take to say, which is what lets the same tour be a page you click through, a sequence of {@code
 * .wav} files, and a video whose frames change on the narration rather than on a timer.
 */
public record Scene(String title, String said, CodeView code) {

    /** A scene with nothing to show: an introduction, or the summary at the end. */
    public static Scene spoken(String title, String said) {
        return new Scene(title, said, CodeView.none());
    }

    /** Roughly how long this takes to say, at the pace a synthesiser reads. */
    public double seconds() {
        int words = said.isBlank() ? 0 : said.strip().split("\\s+").length;
        return Math.max(2.0, words / 2.6);
    }
}
