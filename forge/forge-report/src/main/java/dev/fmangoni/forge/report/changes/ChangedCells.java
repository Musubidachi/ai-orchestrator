package dev.fmangoni.forge.report.changes;

import java.awt.image.BufferedImage;

/**
 * Which parts of a screen changed shape between two screenshots of it.
 *
 * <p>By shape rather than colour: each 16 px cell is compared by the pattern of light and dark in
 * it, so a theme that recolours a screen leaves it unmarked and a moved or rewritten label does
 * not. A cell is matched a couple of pixels either way, because the same page rendered twice can
 * land a pixel apart. The same idea as the event checks' {@code lockmap.py}, without straightening,
 * since both pictures come from the same suite at the same size.
 */
final class ChangedCells {

    static final int CELL = 16;

    /** How alike two cells' patterns must be, from -1 to 1, to count as the same. */
    private static final double MATCH = 0.6;

    /** A cell whose grey varies less than this is flat, and only matches another flat one. */
    private static final double FLAT = 6;

    private static final int SHIFT = 2;

    private ChangedCells() {
    }

    /** Changed cells by row and column, or null when the two are not the same size. */
    static boolean[][] between(BufferedImage before, BufferedImage after) {
        if (before.getWidth() != after.getWidth() || before.getHeight() != after.getHeight()) {
            return null;
        }
        int width = before.getWidth();
        int height = before.getHeight();
        double[] a = grey(before);
        double[] b = grey(after);
        boolean[][] out = new boolean[height / CELL][width / CELL];
        for (int row = 0; row < out.length; row++) {
            for (int col = 0; col < out[row].length; col++) {
                out[row][col] = !sameAnywhereNear(a, b, width, height, col * CELL, row * CELL);
            }
        }
        return out;
    }

    static int count(boolean[][] cells) {
        int n = 0;
        for (boolean[] row : cells) {
            for (boolean changed : row) {
                n += changed ? 1 : 0;
            }
        }
        return n;
    }

    private static boolean sameAnywhereNear(double[] a, double[] b, int width, int height, int x, int y) {
        for (int dy = -SHIFT; dy <= SHIFT; dy++) {
            for (int dx = -SHIFT; dx <= SHIFT; dx++) {
                int bx = x + dx;
                int by = y + dy;
                if (bx >= 0 && by >= 0 && bx + CELL <= width && by + CELL <= height
                        && same(a, b, width, x, y, bx, by)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean same(double[] a, double[] b, int width, int ax, int ay, int bx, int by) {
        double sa = 0;
        double sb = 0;
        double saa = 0;
        double sbb = 0;
        double sab = 0;
        int n = CELL * CELL;
        for (int y = 0; y < CELL; y++) {
            for (int x = 0; x < CELL; x++) {
                double va = a[(ay + y) * width + ax + x];
                double vb = b[(by + y) * width + bx + x];
                sa += va;
                sb += vb;
                saa += va * va;
                sbb += vb * vb;
                sab += va * vb;
            }
        }
        double varA = saa / n - (sa / n) * (sa / n);
        double varB = sbb / n - (sb / n) * (sb / n);
        boolean flatA = varA < FLAT * FLAT;
        boolean flatB = varB < FLAT * FLAT;
        if (flatA || flatB) {
            return flatA && flatB;
        }
        double covariance = sab / n - (sa / n) * (sb / n);
        return covariance / Math.sqrt(varA * varB) >= MATCH;
    }

    private static double[] grey(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] rgb = image.getRGB(0, 0, width, height, null, 0, width);
        double[] out = new double[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            out[i] = 0.299 * ((p >> 16) & 0xff) + 0.587 * ((p >> 8) & 0xff) + 0.114 * (p & 0xff);
        }
        return out;
    }
}
