package dev.fmangoni.forge.tour.film;

/**
 * Where a scene has got to, at one instant.
 *
 * <p>A scene is not a picture but a short stretch of time: the highlight arrives, the code slides
 * under it, the sentence appears as it is said. This is that stretch sampled once — everything the
 * drawing needs to know about when it is.
 *
 * @param highlight how far the lit line has faded in, 0 to 1
 * @param scroll the line number at the top of the window, fractional while it is moving
 * @param typed how many characters of the sentence have been said
 * @param progress how far through the whole tour, 0 to 1
 * @param title how far a title card has faded in, 0 when this is not one
 */
public record Shot(double highlight, double scroll, int typed, double progress, double title) {

    /** A scene with nothing moving: the whole line lit, the whole sentence up. */
    public static Shot still(int typed, double scroll) {
        return new Shot(1, scroll, typed, 0, 1);
    }
}
