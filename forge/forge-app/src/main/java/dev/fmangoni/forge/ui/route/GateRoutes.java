package dev.fmangoni.forge.ui.route;

import dev.fmangoni.forge.status.gate.LastGateReader;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.http.Query;
import java.nio.file.Path;

/**
 * One project's last gate, for the gate section of its panel ({@code extras/gate.js}): quick or full,
 * and what it did with each acceptance command. What it reads is {@code status.gate}'s.
 */
public final class GateRoutes {

    private final Path runsDir;
    private final Path wishlistDir;

    public GateRoutes(Path runsDir, Path wishlistDir) {
        this.runsDir = runsDir;
        this.wishlistDir = wishlistDir;
    }

    public void register(Endpoints endpoints) {
        endpoints.read("/api/gate", exchange -> Http.json(exchange, 200,
                LastGateReader.read(runsDir, wishlistDir, Query.required(exchange, "id"))));
    }
}
