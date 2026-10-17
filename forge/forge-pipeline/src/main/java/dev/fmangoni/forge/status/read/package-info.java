/**
 * What a status group reads while building itself.
 *
 * <p><b>Belongs here:</b> The agent's latest action, taken live from its own event stream, and
 * whole seconds between two instants.
 *
 * <p><b>Not here:</b> Any of the groups themselves. Below them rather than beside {@code
 * JobStatus}, so that a group and the status that contains it do not point at each other.
 *
 * <p><b>Start with:</b> {@code Activity.latest}, {@code Seconds.between}.
 *
 * <p>Depends on: agent.stream.
 */
package dev.fmangoni.forge.status.read;
