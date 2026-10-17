package dev.fmangoni.forge.tour.sound;

import java.util.Random;
import lombok.experimental.UtilityClass;

/**
 * The bed a tour plays over: lo-fi, 75 beats a minute, generated rather than fetched.
 *
 * <p>Generated because Forge downloads nothing and owns nothing it did not make. A track from a
 * library would need a licence, a network and a file to keep; a few hundred lines of arithmetic
 * needs none of them and is the same every time.
 *
 * <p>Seeded deliberately. A build tool whose output differs between two runs of the same input is
 * one you cannot diff, so the timing is humanised off the grid by a fixed sequence rather than a
 * random one.
 *
 * <p>What makes it sound the way it does, in case it ever needs changing: sevenths and ninths in
 * the chords rather than triads, the register kept in the middle, the off-beats landing late, and
 * everything under a lowpass. The last one is most of it — lo-fi is largely the same notes with
 * the top taken off.
 */
@UtilityClass
public class Music {

    private final double BEAT = 60.0 / 75;
    private final double BAR = BEAT * 4;

    /** I, vi, ii, V in C: the turnaround that never quite resolves, so a loop does not announce itself. */
    private final double[][] CHORDS = {
        {329.63, 392.00, 493.88, 587.33},
        {220.00, 261.63, 329.63, 392.00},
        {293.66, 349.23, 440.00, 523.25},
        {293.66, 349.23, 392.00, 493.88},
    };

    private final double[] ROOTS = {65.41, 55.00, 73.42, 49.00};

    /** Fills {@code left} and {@code right} with as many bars as they hold. */
    public void bed(double[] left, double[] right, float rate) {
        Random dice = new Random(7);
        int bars = (int) Math.ceil(left.length / rate / BAR);
        for (int bar = 0; bar < bars; bar++) {
            double at = bar * BAR;
            int chord = bar % CHORDS.length;
            keys(left, right, rate, CHORDS[chord], at, dice);
            bass(left, right, rate, ROOTS[chord], at, dice);
            drums(left, right, rate, at, bar, dice);
        }
        vinyl(left, right, dice);
    }

    /** The chord on beat one, and again, quieter, on the and of three. */
    private void keys(double[] left, double[] right, float rate, double[] chord, double at, Random dice) {
        hit(left, right, rate, chord, at + human(dice), 0.15);
        hit(left, right, rate, chord, at + BEAT * 2.5 + human(dice), 0.09);
    }

    private void hit(double[] left, double[] right, float rate, double[] chord, double at, double gain) {
        for (int note = 0; note < chord.length; note++) {
            // The notes land a few milliseconds apart: a hand, not a machine.
            rhodes(left, right, rate, chord[note], at + note * 0.012, gain * (1 - note * 0.08), note / 3.0);
        }
    }

    /**
     * An electric piano: a bell-like knock that dies at once, over a soft sine that rings on.
     *
     * <p>Two envelopes rather than one is the whole trick. With only the sine it is a flute; with
     * only the knock it is a glockenspiel.
     */
    private void rhodes(double[] left, double[] right, float rate,
            double pitch, double at, double gain, double pan) {
        int start = (int) (at * rate);
        for (int i = 0; i < rate * 2.6 && start + i >= 0 && start + i < left.length; i++) {
            double t = i / rate;
            double attack = Math.min(1, t / 0.004);
            double body = Math.exp(-t * 1.5);
            double tine = Math.exp(-t * 22);
            double tremolo = 1 + 0.07 * Math.sin(2 * Math.PI * 4.6 * t);
            double value = attack * gain * tremolo * (
                    Math.sin(2 * Math.PI * pitch * t) * body
                    + 0.45 * Math.sin(2 * Math.PI * pitch * 2 * t) * body * 0.5
                    + 0.30 * Math.sin(2 * Math.PI * pitch * 4.1 * t) * tine);
            left[start + i] += value * (0.75 - pan * 0.25);
            right[start + i] += value * (0.5 + pan * 0.25);
        }
    }

    private void bass(double[] left, double[] right, float rate, double root, double at, Random dice) {
        round(left, right, rate, root, at + human(dice), 0.30, 1.9);
        round(left, right, rate, root, at + BEAT * 3 + human(dice), 0.18, 0.7);
    }

    private void round(double[] left, double[] right, float rate,
            double pitch, double at, double gain, double length) {
        int start = (int) (at * rate);
        for (int i = 0; i < rate * length && start + i >= 0 && start + i < left.length; i++) {
            double t = i / rate;
            double envelope = Math.min(1, t / 0.012) * Math.exp(-t * (1.6 / length));
            double value = (Math.sin(2 * Math.PI * pitch * t)
                    + 0.22 * Math.sin(2 * Math.PI * pitch * 2 * t)) * envelope * gain;
            left[start + i] += value;
            right[start + i] += value;
        }
    }

    /** Kick on one and the and of three, rim on two and four, swung hats throughout. */
    private void drums(double[] left, double[] right, float rate, double at, int bar, Random dice) {
        kick(left, right, rate, at + human(dice));
        kick(left, right, rate, at + BEAT * 2.5 + human(dice));
        rim(left, right, rate, at + BEAT + human(dice), dice);
        rim(left, right, rate, at + BEAT * 3 + human(dice), dice);
        for (int eighth = 0; eighth < 8; eighth++) {
            // Swing: the off-beats land late, which is the difference between a groove and a grid.
            double swing = eighth % 2 == 1 ? BEAT * 0.09 : 0;
            double when = at + eighth * BEAT / 2 + swing + human(dice);
            hat(left, right, rate, when, eighth % 2 == 1 ? 0.035 : 0.055, dice);
        }
        if (bar % 4 == 3) {
            hat(left, right, rate, at + BEAT * 3.75, 0.05, dice);
        }
    }

    private void kick(double[] left, double[] right, float rate, double at) {
        int start = (int) (at * rate);
        for (int i = 0; i < rate * 0.45 && start + i >= 0 && start + i < left.length; i++) {
            double t = i / rate;
            // The pitch falls away as it decays, which is what a kick drum is.
            double pitch = 48 + 90 * Math.exp(-t * 32);
            double value = Math.sin(2 * Math.PI * pitch * t) * Math.exp(-t * 8) * 0.55;
            left[start + i] += value;
            right[start + i] += value;
        }
    }

    private void rim(double[] left, double[] right, float rate, double at, Random dice) {
        int start = (int) (at * rate);
        for (int i = 0; i < rate * 0.16 && start + i >= 0 && start + i < left.length; i++) {
            double t = i / rate;
            double envelope = Math.exp(-t * 34);
            double noise = dice.nextDouble() * 2 - 1;
            double value = (noise * 0.5 + Math.sin(2 * Math.PI * 320 * t)) * envelope * 0.14;
            left[start + i] += value * 1.05;
            right[start + i] += value * 0.9;
        }
    }

    private void hat(double[] left, double[] right, float rate, double at, double gain, Random dice) {
        int start = (int) (at * rate);
        for (int i = 0; i < rate * 0.07 && start + i >= 0 && start + i < left.length; i++) {
            double t = i / rate;
            double value = (dice.nextDouble() * 2 - 1) * Math.exp(-t * 110) * gain;
            left[start + i] += value * 0.9;
            right[start + i] += value * 1.1;
        }
    }

    /** Tape hiss and the odd pop: quiet enough to be noticed only when it stops. */
    private void vinyl(double[] left, double[] right, Random dice) {
        for (int i = 0; i < left.length; i++) {
            left[i] += (dice.nextDouble() * 2 - 1) * 0.004;
            right[i] += (dice.nextDouble() * 2 - 1) * 0.004;
            if (dice.nextInt(30000) == 0) {
                double pop = (dice.nextDouble() * 2 - 1) * 0.05;
                for (int j = 0; j < 40 && i + j < left.length; j++) {
                    double envelope = Math.exp(-j / 8.0);
                    left[i + j] += pop * envelope;
                    right[i + j] += pop * envelope;
                }
            }
        }
    }

    /** Timing that is never quite on the grid. */
    private double human(Random dice) {
        return (dice.nextDouble() - 0.5) * 0.014;
    }
}
