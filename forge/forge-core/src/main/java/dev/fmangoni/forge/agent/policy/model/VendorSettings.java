package dev.fmangoni.forge.agent.policy.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One vendor's settings.
 *
 * @param builds whether {@code auto} may hand this vendor a project of its own. Building is
 *               where quota goes — a dozen or more turns, each carrying a growing conversation
 *               — while reviewing is a single read-only turn. Turning this off is not "use this
 *               vendor less"; it is "spend it on the work that unblocks other work", which on a
 *               lopsided pair of plans is the difference between a queue that moves and one
 *               that does not.
 * @param concurrency how many calls may reach this vendor at once. This does not change what
 *               the work costs, only how fast the window empties: two at a time finishes two
 *               projects in the time one would take, at the price of being cut off with two
 *               half-built projects instead of one finished. Raise it when a vendor has
 *               headroom and you want the wall clock back; leave it at 1 when quota is tight.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class VendorSettings {
    private boolean builds = true;
    private int concurrency = 1;

    /**
     * Which Windows sandbox the CLI is told to use for Forge's turns, or null to leave it to the
     * CLI's own configuration. Codex only: {@code elevated}, {@code unelevated} or {@code mxc}.
     *
     * <p>A setting here rather than in the person's own {@code ~/.codex/config.toml}, because that
     * file is shared with the desktop app and what works there need not work here. On this machine
     * {@code elevated} cannot provision when started from Forge — every command is refused with
     * {@code helper_unknown_error} — while {@code unelevated} runs, writes inside the workspace
     * and is denied outside it. Both were checked against codex-cli 0.159.3.
     */
    private String windowsSandbox;

    /**
     * CLI features switched off for Forge's turns. Codex only, passed as {@code --disable}.
     *
     * <p>An agent turn inherits whatever the person has enabled for their own use. In Forge's logs
     * that came to 2,223 plugin calls from Codex agents, 1,250 of them driving the desktop through
     * computer use and 461 searching GitHub under the person's account — none of which a build
     * running unattended inside one repository has any business doing.
     */
    private List<String> disabledFeatures = new ArrayList<>();

    /**
     * MCP servers from the person's own CLI configuration, switched off for Forge's turns. Codex
     * only, passed as {@code -c mcp_servers.<name>.enabled=false}.
     *
     * <p>Separate from the features above because those only remove the servers plugins provide.
     * One written directly into {@code ~/.codex/config.toml} is still offered to every turn, and
     * its name is the person's, so it is listed here rather than guessed at. {@code codex mcp list}
     * shows what is configured.
     */
    private List<String> disabledMcpServers = new ArrayList<>();

    public VendorSettings(boolean builds, int concurrency) {
        this.builds = builds;
        this.concurrency = concurrency;
    }
}
