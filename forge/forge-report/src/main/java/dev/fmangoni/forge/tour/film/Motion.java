package dev.fmangoni.forge.tour.film;

import dev.fmangoni.forge.tour.model.Scene;
import lombok.experimental.UtilityClass;

/**
 * When things happen inside a scene.
 *
 * <p>Three movements, all short. The highlight fades in rather than snapping on, so the eye is led
 * to the line instead of being startled by it. The code slides from wherever the last scene left it
 * to where this one needs it, so a long method reads as one continuous piece of paper. The sentence
 * appears at roughly the pace it is spoken, and finishes a little early — text that lands after the
 * voice has said it is text you read twice.
 *
 * <p>Everything eases. Linear movement is the thing that makes generated video look generated.
 */
@UtilityClass
public class Motion {

    private final double FADE_SECONDS = 0.28;
    private final double SLIDE_SECONDS = 0.42;

    /** The whole sentence is up by this far through the scene, leaving the voice to catch up. */
    private final double TYPED_BY = 0.75;

    public Shot at(Scene scene, double into, double length, double from, double to, double through) {
        double highlight = eased(Math.min(1, into / FADE_SECONDS));
        double scroll = to;
        if (from != to && into < SLIDE_SECONDS) {
            scroll = from + (to - from) * eased(into / SLIDE_SECONDS);
        }
        int typed = typed(scene, into, length);
        double title = scene.code().empty() ? eased(Math.min(1, into / FADE_SECONDS)) : 0;
        return new Shot(highlight, scroll, typed, through, title);
    }

    private int typed(Scene scene, double into, double length) {
        double window = Math.max(0.6, length * TYPED_BY);
        double share = Math.min(1, into / window);
        return (int) Math.ceil(scene.said().length() * share);
    }

    /**
     * Slow at both ends, quick in the middle.
     *
     * <p>A smoothstep rather than anything cleverer: it costs two multiplications and is the
     * difference between something that slides and something that is dragged.
     */
    private double eased(double through) {
        double at = Math.max(0, Math.min(1, through));
        return at * at * (3 - 2 * at);
    }
}
