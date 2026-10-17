/**
 * What a running program printed: drained, retained, written down, and timestamped.
 *
 * <p><b>Belongs here:</b> Pumping a stream onto a daemon thread, keeping only the last of it, the
 * on-disk transcript, and stamping Forge's own console lines with the time.
 *
 * <p><b>Not here:</b> Starting or killing anything.
 *
 * <p><b>Start with:</b> {@code StreamPump.start}, {@code TailBuffer}, {@code ProcessLog}, {@code
 * Stamped.install}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.process.output;
