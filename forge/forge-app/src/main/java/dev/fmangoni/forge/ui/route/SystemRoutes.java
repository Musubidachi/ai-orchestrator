package dev.fmangoni.forge.ui.route;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.ledger.UsageLedger;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.jobs.JobRunner;
import dev.fmangoni.forge.jobs.stop.Quiesce;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.http.Query;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Forge about itself: what it can do here, what the vendors have left, and stopping it safely. */
public final class SystemRoutes {

    private final JobRunner jobs;
    private final boolean canSuggest;

    /** Vendor quota state, shown so a stalled queue explains itself. */
    private final QuotaLedger quota;

    /** What each vendor's current window has spent, from the ledger beside the wishlist. */
    private final UsageLedger usage;

    public SystemRoutes(JobRunner jobs, boolean canSuggest, QuotaLedger quota, UsageLedger usage) {
        this.jobs = jobs;
        this.canSuggest = canSuggest;
        this.quota = quota;
        this.usage = usage;
    }

    public void register(Endpoints endpoints) {
        endpoints.read("/api/capabilities", exchange ->
                Http.json(exchange, 200, Map.of("suggest", canSuggest, "jobs", jobs != null)));
        endpoints.read("/api/quota", exchange -> Http.json(exchange, 200, quota == null ? Map.of() : quota.current()));
        endpoints.write("/api/quota/retry", this::retry);
        endpoints.read("/api/usage", exchange -> Http.json(exchange, 200, usage.snapshot()));
        endpoints.write("/api/quit", this::quit);
    }

    /**
     * Forgets a vendor's quota hold on the person's word, that its limit was reset or topped up.
     *
     * <p>The ledger lets a hold go only when a call completes, and nothing calls a vendor that is
     * held, so a limit lifted early at the vendor went unnoticed until the reset time the vendor had
     * first given: Codex was reset on a Tuesday and Forge kept it out until the Friday. Wrong is
     * cheap: the next call is refused, which spends no session, and the hold comes back.
     */
    private void retry(HttpExchange exchange) throws IOException {
        String vendor = Query.required(exchange, "vendor").strip().toUpperCase(Locale.ROOT);
        Optional<AgentKind> kind = Arrays.stream(AgentKind.values()).filter(k -> k.name().equals(vendor)).findFirst();
        if (kind.isEmpty() || quota == null) {
            Http.json(exchange, 400, Map.of("error", "there is no vendor called " + vendor));
            return;
        }
        boolean held = quota.isBlocked(kind.get());
        quota.clear(kind.get());
        if (held) {
            System.out.println("[quota] " + kind.get() + " hold cleared by hand; the next call will tell");
        }
        Http.json(exchange, 200, Map.of("result", held
                ? kind.get() + " is no longer held: the next call to it will tell. If it is still out, that call "
                  + "is refused without spending a session, and the hold comes back"
                : kind.get() + " was not held"));
    }

    /**
     * Asks Forge to stop once the work in flight is finished.
     *
     * <p>What a rebuild calls instead of killing the process. It stops taking new work, waits for
     * any agent turn to reach its commit, answers with what happened, and only then exits — so a
     * build that begins the moment this returns cannot be the thing that orphans a turn.
     *
     * <p>Answers before exiting, and exits only if it settled. A caller that is told
     * {@code stopped: false} still has a running Forge and knows which project is mid-turn, which
     * is the information needed to decide whether to wait or to insist. Nothing here kills a turn:
     * that decision belongs to a person, and a shutdown that gives up and starts killing things is
     * the behaviour this exists to replace.
     */
    private void quit(HttpExchange exchange) throws IOException {
        if (jobs == null) {
            Http.json(exchange, 200, Map.of("stopped", true, "detail", "nothing was running"));
            stopSoon();
            return;
        }
        long seconds = Long.parseLong(Query.optional(exchange, "wait").orElse("600"));
        Quiesce quiesce = jobs.quiesce(Duration.ofSeconds(seconds));
        Http.json(exchange, 200, Map.of(
                "stopped", quiesce.settled(),
                "working", quiesce.stillWorking(),
                "detail", quiesce.describe()));
        if (quiesce.settled()) {
            stopSoon();
        }
    }

    /**
     * Exits once the answer above has actually reached the caller.
     *
     * <p>On its own thread and after a moment: stopping the server inside a handler closes the
     * connection the response is still being written to, and the script waiting for it sees a
     * dropped request rather than the answer it asked for.
     */
    private static void stopSoon() {
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.println("[forge] stopping on request; no agent turn is in flight");
            System.exit(0);
        }, "forge-shutdown");
        exit.setDaemon(false);
        exit.start();
    }
}
