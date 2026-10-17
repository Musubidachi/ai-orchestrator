package dev.fmangoni.forge.agent.vendor;

import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * The environment an agent CLI must not see, because it would move the CLI off its login.
 *
 * <p>Each vendor's CLI prefers an API key in its environment over the subscription it is logged in
 * with, and a key there bills by the token without a word to anyone. Forge never uses a key, but
 * before this it only held true because none happened to be set: the agents inherited Forge's whole
 * environment, and anything a person exported for some other project would have been used here.
 * Withholding them makes the rule Forge's rather than the machine's.
 *
 * <p>Only the agents'. The gate, deploys and security checks run the project's own commands, which
 * are the project's business.
 */
@UtilityClass
public class SubscriptionOnly {

    public final Set<String> API_KEYS = Set.of(
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN",
            "OPENAI_API_KEY", "CODEX_API_KEY",
            "GEMINI_API_KEY", "GOOGLE_API_KEY");
}
