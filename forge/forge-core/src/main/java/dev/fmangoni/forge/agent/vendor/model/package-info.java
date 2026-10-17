/**
 * The command line each vendor's CLI expects, as data rather than as a string built at the call
 * site.
 *
 * <p><b>Belongs here:</b> One {@code *Args} class per vendor, which knows that vendor's flags and
 * nothing else.
 *
 * <p><b>Not here:</b> Running anything. These build argument lists; {@code process} runs them.
 *
 * <p><b>Start with:</b> {@code ClaudeArgs}, {@code CodexArgs}, {@code AntigravityArgs}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.agent.vendor.model;
