/**
 * Running the built project on this machine: as a script, or as a server on loopback.
 *
 * <p><b>Belongs here:</b> Writing an acceptance command to a script file so it runs without
 * quoting, and serving a project for a few minutes so it can be looked at.
 *
 * <p><b>Not here:</b> Anything that decides to run it. Only loopback is ever bound, and that is not
 * a detail to trade away.
 *
 * <p><b>Start with:</b> {@code ShellScript}, {@code LocalServer.start}.
 *
 * <p>Depends on: process.
 */
package dev.fmangoni.forge.process.local;
