package dev.fmangoni.forge.ui.pictures;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.pictures.PictureBatches;
import dev.fmangoni.forge.pictures.view.BatchView;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.http.Query;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Picture batches: their review page ({@code /pictures}), what the dashboard's card reads, and the
 * two things a person does with them, a review and a click to place what is kept.
 *
 * <p>Registering also starts the look Forge takes at the batches every few seconds, so this one line
 * in {@code UiServer} is all that attaches them. What any of it does is {@code pictures}'.
 */
public final class PictureRoutes {

    private final PictureBatches batches;

    public PictureRoutes(Path runsDir) {
        this.batches = new PictureBatches(runsDir);
    }

    public void register(Endpoints endpoints) {
        endpoints.read("/pictures", exchange -> Http.bundled(exchange, "/ui/pictures.html"));
        endpoints.read("/api/pictures", exchange -> Http.json(exchange, 200, batches.all()));
        endpoints.read("/api/pictures/batch", this::batch);
        endpoints.write("/api/pictures/rule", this::rule);
        endpoints.write("/api/pictures/place", this::place);
        batches.start();
    }

    private void batch(HttpExchange exchange) throws IOException {
        String id = Query.required(exchange, "id");
        Optional<BatchView> batch = batches.one(id);
        if (batch.isEmpty()) {
            Http.error(exchange, 404, "no picture batch " + id);
            return;
        }
        Http.json(exchange, 200, batch.get());
    }

    private void rule(HttpExchange exchange) throws IOException {
        RuleRequest request = Http.bodyAs(exchange, RuleRequest.class);
        int saved = 0;
        Map<String, String> skipped = new LinkedHashMap<>();
        for (RuleItem item : request.getRulings()) {
            boolean keep = "keep".equals(item.getVerdict());
            if (!keep && !"redo".equals(item.getVerdict())) {
                skipped.put(String.valueOf(item.getDraft()), "say keep or redo");
                continue;
            }
            Optional<String> refused = batches.rule(request.getId(), item.getDraft(), item.getStamp(), keep, item.getNote());
            if (refused.isPresent()) {
                skipped.put(String.valueOf(item.getDraft()), refused.get());
            } else {
                saved++;
            }
        }
        String result = saved + (saved == 1 ? " ruling saved" : " rulings saved")
                + (skipped.isEmpty() ? "." : "; " + skipped.size() + " not saved.");
        Http.json(exchange, 200, Map.of("saved", saved, "skipped", skipped, "result", result));
    }

    private void place(HttpExchange exchange) throws IOException {
        Map<?, ?> body = Http.bodyAs(exchange, Map.class);
        String id = String.valueOf(body.get("id"));
        Optional<Integer> asked = batches.place(id);
        if (asked.isEmpty()) {
            Http.error(exchange, 404, "no picture batch " + id);
            return;
        }
        int count = asked.get();
        Http.json(exchange, 200, Map.of("asked", count, "result", count == 0
                ? "Nothing kept is waiting to be placed."
                : "Placing " + count + (count == 1 ? " picture" : " pictures") + ", one at a time."));
    }
}
