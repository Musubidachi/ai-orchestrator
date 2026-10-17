package dev.fmangoni.forge.ui.suggest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.brief.draft.DraftBrief;
import dev.fmangoni.forge.brief.draft.RefineBrief;
import dev.fmangoni.forge.json.JsonText;
import dev.fmangoni.forge.spec.model.Complexity;
import dev.fmangoni.forge.spec.model.FrontMatter;
import dev.fmangoni.forge.spec.model.Sections;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.ui.editor.Draft;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;

/**
 * Drafts a spec from a rough description, or rewrites one section of it, by asking an agent.
 *
 * <p>The result is a proposal that lands in the editor's fields for the author to change or throw
 * away; nothing here writes to the wishlist. That separation is deliberate rather than incidental —
 * the acceptance commands are the pipeline's only independent check on the code, so a person
 * deciding what they say is the point, not a formality.
 *
 * <p>Both requests go through one path: {@link #ask} runs the brief and extracts the JSON, and
 * {@link #firstAnswer} decides who is asked. They used to be two copies of the same plumbing, and
 * the copies had already drifted — only one of them guarded against a null error message.
 */
@RequiredArgsConstructor
public final class Suggester {

    /** Short: this blocks someone sitting in front of the editor, unlike an overnight build turn. */
    private static final Duration CAP = Duration.ofMinutes(4);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Agents agents;
    private final Path scratchDir;

    /** The user's model ordering, which is what a complexity judgement is mapped onto. */
    private final ModelTiers tiers;

    /** Asks for a draft, falling back to another vendor when this one cannot be reached. */
    public Suggestion suggest(String description, AgentKind preferred, List<String> usedIds) {
        if (description == null || description.isBlank()) {
            return Suggestion.failed(preferred, "describe the idea first");
        }
        SuggesterReply reply = firstAnswer(preferred, DraftBrief.of(description, usedIds));
        if (!reply.answered()) {
            return Suggestion.failed(reply.kind(), reply.error());
        }
        Complexity complexity = Complexity.parse(text(reply.json(), "complexity"));
        return new Suggestion(reply.kind().name(), toDraft(reply.json(), complexity), null,
                complexity.name().toLowerCase(Locale.ROOT),
                text(reply.json(), "complexity_reason"));
    }

    /** Asks for a better version of one section, with the same fallback as {@link #suggest}. */
    public Refinement refine(AgentKind preferred, String title, Sections sections, String target,
                             String feedback) {
        if (Sections.refinable().stream().noneMatch(name -> name.equalsIgnoreCase(target))) {
            return Refinement.failed(preferred, "unknown section: " + target);
        }
        SuggesterReply reply = firstAnswer(preferred, RefineBrief.of(title, sections, target, feedback));
        if (!reply.answered()) {
            return Refinement.failed(reply.kind(), reply.error());
        }
        return new Refinement(reply.kind().name(), text(reply.json(), "section"),
                text(reply.json(), "notes"), text(reply.json(), "what_changed"), null);
    }

    /**
     * The preferred vendor's reply, or the first other vendor's that answers.
     *
     * <p>Both requests block a person sitting in front of the editor, and "come back after 10pm"
     * is a poor answer when another subscription is sitting idle. Every other vendor is tried
     * rather than one alternative: with more than two, stopping at the first fallback would
     * report everything unavailable while a third sat unused.
     *
     * <p>Only a vendor that could not be reached is passed over. One that answered badly still
     * has its answer returned: asking the next one to guess again spends a second window on the
     * same idea.
     *
     * @return a reply that answered, or a failed reply naming the preferred vendor and every reason
     */
    private SuggesterReply firstAnswer(AgentKind preferred, String brief) {
        SuggesterReply first = ask(preferred, brief);
        if (first.answered() || !unreachable(first.error())) {
            return first;
        }
        StringBuilder why = new StringBuilder(first.error());
        for (AgentKind kind : agents.reviewersFor(preferred)) {
            SuggesterReply next = ask(kind, brief);
            if (next.answered()) {
                return next;
            }
            why.append("; ").append(next.error());
        }
        return new SuggesterReply(preferred, null, why.toString());
    }

    /**
     * Whether the vendor could not be asked at all, as opposed to asked and answering badly.
     *
     * <p>Logged out as well as out of quota. Only the limit used to fall back, so a CLI whose login
     * had lapsed answered a person waiting in the editor with "NOT_AUTHENTICATED" while another
     * subscription sat idle.
     */
    private boolean unreachable(String error) {
        String text = String.valueOf(error);
        return text.startsWith("RATE_LIMITED") || text.startsWith("NOT_AUTHENTICATED");
    }

    /** Runs one read-only turn and pulls the first JSON object out of what came back. */
    private SuggesterReply ask(AgentKind kind, String brief) {
        Path workspace = scratch(kind);
        AgentResult result = agents.get(kind).run(
                AgentTask.readOnly(workspace, brief, CAP, workspace));

        if (result.blockedExternally()) {
            return new SuggesterReply(kind, null, result.outcome() + ": " + result.finalMessage());
        }
        String json = JsonText.firstObject(result.finalMessage());
        if (json == null) {
            return new SuggesterReply(kind, null, "no JSON in the reply: " + shorten(result.finalMessage()));
        }
        try {
            return new SuggesterReply(kind, JSON.readTree(json), null);
        } catch (IOException e) {
            return new SuggesterReply(kind, null, "unreadable reply: " + e.getMessage());
        }
    }

    /**
     * Maps the reply field by field rather than binding it straight onto {@link FrontMatter}.
     *
     * <p>A model that omits a field, or answers {@code "acceptance": "mvn test"} instead of a list,
     * should produce a partly filled form the author can finish — not an exception that loses the
     * whole suggestion and the minute spent waiting for it.
     */
    private Draft toDraft(JsonNode node, Complexity complexity) {
        FrontMatter front = new FrontMatter();
        // The agent sized the work; the user's own ordering decides which tier that is — for every
        // vendor. Antigravity used to be left out, so it reviewed on its CLI default whatever the
        // work was judged to need.
        front.setClaudeModel(tiers.at(complexity, AgentKind.CLAUDE));
        front.setCodexModel(tiers.at(complexity, AgentKind.CODEX));
        front.setAntigravityModel(tiers.at(complexity, AgentKind.ANTIGRAVITY));
        front.setId(text(node, "id"));
        front.setTitle(text(node, "title"));
        front.setStack(node.hasNonNull("stack") ? text(node, "stack") : "java");
        front.setAcceptance(strings(node.get("acceptance")));
        front.setEvidence(strings(node.get("evidence")));
        // Only a serve command Forge can actually aim: without the placeholder it would pick its own
        // port, and the spec would be refused on save for it.
        String serve = text(node, "serve");
        front.setServe(serve.contains("{port}") ? serve : "");

        Draft draft = new Draft();
        draft.setFront(front);
        draft.setBody(text(node, "body"));
        return draft;
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText().strip() : "";
    }

    private List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return out;
        }
        if (node.isTextual()) {
            out.add(node.asText().strip());
            return out;
        }
        node.forEach(element -> {
            String value = element.asText().strip();
            if (!value.isEmpty()) {
                out.add(value);
            }
        });
        return out;
    }

    /** An empty directory per vendor: the agent runs read-only and has nothing here to reach for. */
    private Path scratch(AgentKind kind) {
        Path dir = scratchDir.resolve("suggest-" + kind.name().toLowerCase(Locale.ROOT));
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + dir, e);
        }
    }

    private String shorten(String reply) {
        String text = reply == null ? "" : reply.strip();
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }
}
