package dev.fmangoni.forge.ui.route;

import com.sun.net.httpserver.HttpExchange;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Sections;
import dev.fmangoni.forge.spec.rank.RankWriter;
import dev.fmangoni.forge.spec.rank.Ranking;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.ui.editor.Draft;
import dev.fmangoni.forge.ui.editor.SpecApi;
import dev.fmangoni.forge.ui.editor.SpecSummary;
import dev.fmangoni.forge.ui.http.Endpoints;
import dev.fmangoni.forge.ui.http.Http;
import dev.fmangoni.forge.ui.http.Query;
import dev.fmangoni.forge.ui.request.RankRequest;
import dev.fmangoni.forge.ui.request.RefineRequest;
import dev.fmangoni.forge.ui.request.SuggestRequest;
import dev.fmangoni.forge.ui.suggest.IdeaSource;
import dev.fmangoni.forge.ui.suggest.Suggester;
import dev.fmangoni.forge.ui.suggest.Suggestion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The editor's endpoints: listing, opening, checking and saving specs, drafting them, ranking them. */
public final class SpecRoutes {

    private final SpecApi api;
    private final Path wishlistDir;

    /** Null when neither CLI is installed; the editor still works, only suggestions are off. */
    private final Suggester suggester;

    /** Null when no idea generator is named beside the wishlist; the idea buttons are then hidden. */
    private final IdeaSource ideas;

    public SpecRoutes(SpecApi api, Path wishlistDir, Suggester suggester, IdeaSource ideas) {
        this.api = api;
        this.wishlistDir = wishlistDir;
        this.suggester = suggester;
        this.ideas = ideas;
    }

    public void register(Endpoints endpoints) {
        endpoints.read("/api/list", exchange -> Http.json(exchange, 200, api.list()));
        endpoints.read("/api/open", exchange ->
                Http.json(exchange, 200, api.open(Query.required(exchange, "file"))));
        endpoints.read("/api/raw", exchange ->
                Http.json(exchange, 200, Map.of("text", api.raw(Query.required(exchange, "file")))));
        endpoints.write("/api/validate", exchange ->
                Http.json(exchange, 200, api.validate(Http.bodyAs(exchange, Draft.class))));
        endpoints.write("/api/save", exchange ->
                Http.json(exchange, 200, api.save(Http.bodyAs(exchange, Draft.class))));
        endpoints.write("/api/delete", exchange -> {
            api.delete(Http.bodyAs(exchange, Draft.class).getOriginalFile());
            Http.json(exchange, 200, Map.of("deleted", true));
        });
        endpoints.write("/api/suggest", this::suggest);
        endpoints.read("/api/ideas", exchange -> Http.json(exchange, 200, ideas == null ? Map.of()
                : Map.of("project", ideas.project(), "categories", ideas.categories())));
        endpoints.write("/api/idea", this::idea);
        endpoints.write("/api/refine", this::refine);
        endpoints.write("/api/rank", this::rank);
        endpoints.read("/api/models", exchange ->
                Http.json(exchange, 200, ModelTiers.load(wishlistDir.getParent()).byVendor()));
    }

    /**
     * One new idea from the idea generator, for the description box. A write, though it saves
     * nothing, because it runs a program: only a press in the page may do that.
     */
    private void idea(HttpExchange exchange) throws IOException {
        if (ideas == null) {
            Http.json(exchange, 200, Map.of("error", "no idea generator is set up (" + IdeaSource.CONFIG + ")"));
            return;
        }
        try {
            Http.json(exchange, 200, Map.of("project", ideas.project(),
                    "idea", ideas.next(Query.optional(exchange, "category").orElse(""),
                            Query.optional(exchange, "maxEffort").orElse(""))));
        } catch (IllegalStateException e) {
            Http.json(exchange, 200, Map.of("error", e.getMessage()));
        }
    }

    /** Proposes a draft from a rough description. Never writes; the reply only fills the form. */
    private void suggest(HttpExchange exchange) throws IOException {
        if (suggester == null) {
            Http.error(exchange, 503, "no agent CLI installed, so suggestions are unavailable");
            return;
        }
        SuggestRequest request = Http.bodyAs(exchange, SuggestRequest.class);
        List<String> usedIds = api.list().stream()
                .map(SpecSummary::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        Suggestion suggestion = suggester.suggest(request.getDescription(), request.agentKind(), usedIds);
        if (suggestion.draft() != null) {
            suggestion.draft().getFront().setId(api.usableId(suggestion.draft().getFront().getId()));
        }
        Http.json(exchange, 200, suggestion);
    }

    /**
     * Rewrites one section of a description, with the other two sent along as context.
     *
     * <p>Takes the sections from the request rather than from the file: the editor holds unsaved
     * text, and refining against what is on disk would quietly discard whatever was just typed.
     */
    private void refine(HttpExchange exchange) throws IOException {
        if (suggester == null) {
            Http.error(exchange, 503, "no agent CLI installed, so suggestions are unavailable");
            return;
        }
        RefineRequest request = Http.bodyAs(exchange, RefineRequest.class);
        Sections sections = new Sections(request.getGoal(), request.getNonGoals(), request.getNotes(), "");
        Http.json(exchange, 200, suggester.refine(
                request.agentKind(), request.getTitle(), sections, request.getTarget(), request.getFeedback()));
    }

    /**
     * Renumbers the wishlist, optionally moving one spec a place within its group first.
     *
     * <p>The whole list is rewritten each time rather than nudging one number, because the ranking
     * is only meaningful as a contiguous sequence — leaving a gap or a duplicate behind would make
     * the next reorder ambiguous.
     */
    private void rank(HttpExchange exchange) throws IOException {
        RankRequest request = Http.bodyAs(exchange, RankRequest.class);
        Ranking ranking = new Ranking(Wishlist.load(wishlistDir));
        int changed = RankWriter.apply(request.getId().isBlank()
                ? ranking.normalised()
                : ranking.moved(request.getId(), request.getDelta()));
        Http.json(exchange, 200, Map.of("renumbered", changed));
    }
}
