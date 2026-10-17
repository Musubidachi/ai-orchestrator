package dev.fmangoni.forge.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.deploy.Deployment;
import dev.fmangoni.forge.feature.Clips;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarketingKitTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private Spec spec;
    private RunState state;
    private final List<String> briefs = new ArrayList<>();

    private static final String SITE = "https://pink-slip.pages.dev";
    private static final Feature TUNING = new Feature("Garage tuning", "change the gearing");

    @BeforeEach
    void setUp() throws Exception {
        paths = new RunPaths(root.resolve("runs/003"));
        Files.createDirectories(paths.repo());
        spec = SpecLoader.load(Files.writeString(root.resolve("003.md"), """
                ---
                id: "003"
                title: Pink Slip
                acceptance:
                  - echo ok
                ---
                A drag racing game where you bet your car.
                """));
        state = new RunState();
        state.setId("003");
        state.setPhase(Phase.ACCEPTED);
        state.setAssignedTo(AgentKind.CLAUDE);
        DecisionStore.save(paths.featuresFile(), List.of(Decision.of(TUNING.id(), TUNING.name(), "approved", "")));
        Clips.write(paths.repo().resolve(TUNING.recordingPath(".gif")));
        Deployment.append(paths.deploymentsFile(), new Deployment(1, "abc", false, Instant.now(), Instant.now(),
                0, Deployment.DEPLOYED, "", SITE, "ok", "log"));
    }

    /** An agent that writes the kit it is given into its working folder. */
    private Agent writer(AgentKind kind, String claimFeature, String proof) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return kind;
            }

            @Override
            public AgentResult run(AgentTask task) {
                briefs.add(task.brief());
                try {
                    Path dir = task.cwd();
                    Files.writeString(dir.resolve("kit.json"), """
                            {"tagline":"Race for pink slips","short_description":"Bet your car on a drag race.",
                             "long_description":"Tune it, race it.","press":"A browser drag racing game.",
                             "claims":[{"text":"Tune the gearing","feature":"%s"}],
                             "posts":{"x":"Bet your car: %s/?utm_source=x"}}
                            """.formatted(claimFeature, SITE));
                    Files.createDirectories(dir.resolve("site"));
                    Files.writeString(dir.resolve("site/index.html"), "<h1>Pink Slip</h1>" + proof
                            + "<video src=\"../media/" + TUNING.slug() + "\"></video>");
                    Files.createDirectories(dir.resolve("campaigns"));
                    Files.writeString(dir.resolve("campaigns/google-search.csv"),
                            "Campaign,Headline 1,Description 1,Final URL\nPS,Bet Your Car,Race it,"
                                    + SITE + "/?utm_source=google\n");
                    String budget = "\"budget\":{\"daily\":5,\"total_cap\":50,\"stop_when\":\"no plays after 200 clicks\"}";
                    Files.writeString(dir.resolve("campaigns/meta.json"), "{\"headline\":\"Bet your car\",\"primary_text\":\"Race.\",\"url\":\""
                            + SITE + "/?utm_source=meta\"," + budget + "}");
                    Files.writeString(dir.resolve("campaigns/reddit.json"), "{\"headline\":\"Bet your car\",\"url\":\""
                            + SITE + "/?utm_source=reddit\"," + budget + "}");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return new AgentResult(AgentOutcome.COMPLETED, "done", new ProcResult(0, "", "", false, Duration.ZERO), null);
            }
        };
    }

    private static Agent outOfQuota(AgentKind kind) {
        return new Agent() {
            @Override
            public AgentKind kind() {
                return kind;
            }

            @Override
            public AgentResult run(AgentTask task) {
                return new AgentResult(AgentOutcome.RATE_LIMITED, "limit", new ProcResult(1, "", "", false, Duration.ZERO), null);
            }
        };
    }

    private KitStatus make(Agent claude, Agent codex) {
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, claude);
        map.put(AgentKind.CODEX, codex);
        return new MarketingKit(new Agents(map)).make(spec, paths, state, "abc");
    }

    @Test
    @DisplayName("a kit is written in its own folder with the recordings, checked, and a stale file does not survive")
    void goodKit() throws Exception {
        Files.createDirectories(paths.marketingDir().resolve("campaigns"));
        Files.writeString(paths.marketingDir().resolve("campaigns/old.md"), "Loved by 10,000 players");

        KitStatus status = make(outOfQuota(AgentKind.CLAUDE), writer(AgentKind.CODEX, TUNING.id(), ""));

        assertEquals("ready", status.outcome(), status.problems().toString());
        assertTrue(Files.isRegularFile(paths.marketingDir().resolve("media").resolve(TUNING.id() + ".gif")),
                "the feature's recording is copied in for the landing page");
        assertFalse(Files.exists(paths.marketingDir().resolve("campaigns/old.md")), "last kit's file was left behind");
        assertTrue(briefs.get(0).contains(TUNING.id()) && briefs.get(0).contains(SITE), briefs.get(0));
        assertEquals("ready", KitStatus.load(paths.marketingStatusFile()).outcome());
        assertFalse(Files.exists(paths.repo().resolve("kit.json")), "nothing is written into the product");
    }

    @Test
    @DisplayName("a kit that invents its audience is marked with problems, not ready")
    void inventedKit() {
        KitStatus status = make(writer(AgentKind.CLAUDE, TUNING.id(), "<p>Join 5,000 players</p>"),
                writer(AgentKind.CODEX, TUNING.id(), "<p>Join 5,000 players</p>"));

        assertEquals("problems", status.outcome());
        assertTrue(status.problems().stream().anyMatch(p -> p.what().startsWith("invented social proof")),
                status.problems().toString());
    }

    @Test
    @DisplayName("only an accepted project gets a kit")
    void onlyAccepted() {
        state.setPhase(Phase.DONE);
        assertEquals("only an accepted project gets a marketing kit", MarketingKit.blockedReason(state));
    }
}
