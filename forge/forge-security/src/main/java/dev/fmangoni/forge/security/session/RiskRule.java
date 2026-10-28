package dev.fmangoni.forge.security.session;

import java.util.regex.Pattern;

/**
 * One kind of command the house rules forbid an agent, as it appears on a command line.
 *
 * @param severity {@code high} needs a person's ruling before the version is approved;
 *                 {@code medium} is listed in the report only
 * @param title    what the agent did, as the report and the ruling form say it
 */
public record RiskRule(String severity, String title, Pattern pattern) {
}
