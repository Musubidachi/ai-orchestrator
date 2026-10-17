package dev.fmangoni.forge.security.osv.model;






















/**
 * A vulnerability as a report needs it.
 *
 * @param severity  critical, high, medium or low; medium when OSV gives nothing usable
 * @param fixedIn   the first fixed version for this package, or blank when there is none yet
 */
public record OsvVuln(String id, String summary, String severity, String fixedIn) {
}
