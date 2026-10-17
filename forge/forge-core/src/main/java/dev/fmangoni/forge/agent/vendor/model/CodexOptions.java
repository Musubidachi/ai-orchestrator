package dev.fmangoni.forge.agent.vendor.model;

import java.util.List;

/**
 * What Forge tells Codex about a turn beyond the task itself, from {@code vendors.json}.
 *
 * @param windowsSandbox     the Windows sandbox to use, or null to leave the CLI's own configuration
 * @param disabledFeatures   CLI features switched off for this turn
 * @param disabledMcpServers MCP servers named in the person's own Codex configuration, switched
 *                           off for this turn. Disabling the plugin features removes the servers
 *                           plugins provide; one written straight into {@code config.toml} stays
 *                           unless it is named here.
 */
public record CodexOptions(String windowsSandbox, List<String> disabledFeatures,
                           List<String> disabledMcpServers) {

    public CodexOptions {
        disabledFeatures = disabledFeatures == null ? List.of() : List.copyOf(disabledFeatures);
        disabledMcpServers = disabledMcpServers == null ? List.of() : List.copyOf(disabledMcpServers);
    }

    public CodexOptions(String windowsSandbox, List<String> disabledFeatures) {
        this(windowsSandbox, disabledFeatures, List.of());
    }

    /** Nothing overridden: the turn runs exactly as the person's own Codex is configured. */
    public static CodexOptions none() {
        return new CodexOptions(null, List.of(), List.of());
    }
}
