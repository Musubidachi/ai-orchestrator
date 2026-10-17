package dev.fmangoni.forge.tour.sound;

import lombok.experimental.UtilityClass;

/**
 * The two sounds the tour itself makes: a line landing, and a chapter starting.
 *
 * <p>Both are quiet and both are short, because they happen hundreds of times. The rule they are
 * written to is that a sound should be noticed the first time and stop being noticed by the tenth:
 * anything with a long tail, or pitched like a notification, fails that within a minute.
 */
@UtilityClass
public class Sfx {

    /** The highlight moving to a new line: a key-tap, not a wood block. */
    public void tick(double[] left, double[] right, float rate, double at) {
        int start = (int) (at * rate);
        for (int i = 0; i < rate * 0.035 && start + i >= 0 && start + i < left.length; i++) {
            double t = i / rate;
            double envelope = Math.exp(-t * 160);
            double value = (Math.sin(2 * Math.PI * 1750 * t) + 0.4 * Math.sin(2 * Math.PI * 2600 * t))
                    * envelope * 0.045;
            left[start + i] += value;
            right[start + i] += value;
        }
    }

    /** A new chapter: three notes of the chord, rising, over before they are noticed. */
    public void chime(double[] left, double[] right, float rate, double at) {
        double[] pitches = {523.25, 659.25, 783.99};
        for (int note = 0; note < pitches.length; note++) {
            struck(left, right, rate, pitches[note], at + note * 0.10);
        }
    }

    private void struck(double[] left, double[] right, float rate, double pitch, double at) {
        int start = (int) (at * rate);
        for (int i = 0; i < rate * 2.2 && start + i >= 0 && start + i < left.length; i++) {
            double t = i / rate;
            double envelope = Math.min(1, t / 0.006) * Math.exp(-t * 3.4);
            double value = (Math.sin(2 * Math.PI * pitch * t)
                    + 0.30 * Math.sin(2 * Math.PI * pitch * 2 * t)
                    + 0.10 * Math.sin(2 * Math.PI * pitch * 3 * t)) * envelope * 0.11;
            left[start + i] += value;
            right[start + i] += value;
        }
    }
}
