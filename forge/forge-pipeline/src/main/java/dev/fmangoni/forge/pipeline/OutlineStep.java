package dev.fmangoni.forge.pipeline;

import dev.fmangoni.forge.project.Phase;

/**
 * One line of the {@link Outline}: what is done, and in which phase.
 *
 * @param phase  the phase it belongs to; null for what is done once before any step
 * @param what   what it does, in a few words, for whoever reads the outline or a failure
 * @param action the work itself
 */
record OutlineStep(Phase phase, String what, Runnable action) {
}
