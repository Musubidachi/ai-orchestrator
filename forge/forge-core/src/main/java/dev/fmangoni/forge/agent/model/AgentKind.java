package dev.fmangoni.forge.agent.model;

import java.util.Locale;

/**
 * The vendors Forge can drive.
 *
 * <p>There is deliberately no {@code other()} here any more. It read naturally while there were
 * exactly two — the reviewer is the one that did not build — but it encoded "two" as a fact about
 * the world rather than as today's count, and it had no way to say "that one is out of quota, ask
 * somebody else". Choosing a reviewer is a decision with reasons behind it and lives in
 * {@code Reviewers}.
 */
public enum AgentKind {

    CLAUDE("claude"),
    CODEX("codex"),

    /**
     * Google's Antigravity CLI, {@code agy}. Reviewer by default rather than builder.
     *
     * <p>The successor to the Gemini CLI, which is what Google's own migration notice points at —
     * and the Gemini CLI's free personal-account tier is what stopped working. Named for the tool
     * rather than the model because the binary is {@code agy} and it fronts several models,
     * Anthropic's and OpenAI's among them. Choosing one of those to review a project the same
     * vendor built would quietly undo the point of a cross-review, so the default tier list here
     * is Google's own models.
     *
     * <p>Not a builder: the free tier is a request cap, ample for a review — one read-only turn —
     * and nowhere near a build, which is a dozen turns carrying a growing conversation. Declared
     * last so that, all else equal, the two paid vendors keep their existing order.
     */
    ANTIGRAVITY("agy");

    private final String cliName;

    AgentKind(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    public static AgentKind parse(String s) {
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
