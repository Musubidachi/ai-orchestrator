package dev.fmangoni.forge.jobs.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The questions both schedulers ask, now asked in one place.
 *
 * <p>Every case here is a divergence that actually happened while the dashboard and the unattended
 * run kept their own copies: {@code DONE} versus {@code ACCEPTED}, a null quota ledger on one side,
 * and a suspension rule that only one of them learned. The point of the class is that there is
 * nowhere left for a fourth to hide.
 */
class EligibilityTest {

    @TempDir
    Path root;

    private Path runs;
    private QuotaLedger quota;
    private Agents agents;

    private record Unused(AgentKind kind) implements Agent {
        @Override
        public AgentResult run(AgentTask task) {
            throw new AssertionError("nothing here should reach a vendor");
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        runs = Files.createDirectories(root.resolve("runs"));
        quota = new QuotaLedger(root.resolve("quota.json"));
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        for (AgentKind kind : AgentKind.values()) {
            map.put(kind, new Unused(kind));
        }
        agents = new Agents(map, null, quota);
    }

    private Eligibility rules() {
        return new Eligibility(runs, agents, quota);
    }

    private RunState state(Phase phase, Phase resume, Instant retryAfter) {
        RunState s = new RunState();
        s.setId("001");
        s.setPhase(phase);
        s.setResumePhase(resume);
        s.setRetryAfter(retryAfter);
        s.setAssignedTo(AgentKind.CLAUDE);
        return s;
    }

    private void parentIn(String id, Phase phase) {
        RunState s = new RunState();
        s.setId(id);
        s.setPhase(phase);
        RunStore.save(RunPaths.under(runs, id).stateFile(), s);
    }

    private Spec spec(String id, String parent) {
        Path file = root.resolve(id + "-thing.md");
        try {
            Files.writeString(file, """
                    ---
                    id: "%s"
                    title: Thing
                    %sacceptance:
                      - echo ok
                    ---

                    ## Goal
                    Thing.
                    """.formatted(id, parent == null ? "" : "parent: \"" + parent + "\"\n"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return SpecLoader.load(file);
    }

    // ------------------------------------------------------- parent approval

    @Test
    @DisplayName("an improvement waits for ACCEPTED, not merely DONE")
    void acceptedNotDone() {
        // The original divergence: one scheduler compared against DONE, the other against
        // ACCEPTED. DONE means the commands passed and nobody has looked; building on it means
        // building on a foundation that may be about to be sent back.
        parentIn("001", Phase.DONE);
        assertFalse(rules().parentAccepted(spec("001.1", "001")));

        parentIn("001", Phase.ACCEPTED);
        assertTrue(rules().parentAccepted(spec("001.1", "001")));
    }

    // ------------------------------------------------------- one enhancement at a time, on GitHub

    /** Publishing switched on in this home. Safe here: deciding who may begin never calls gh. */
    private void publishing() throws IOException {
        Files.writeString(root.resolve("publishing.json"), "{\"github\": true}");
    }

    private void established(String family) throws IOException {
        Files.writeString(RunPaths.under(runs, family).githubFile(), """
                {"repository":"fabrizio/game","url":"https://github.com/fabrizio/game",
                 "establishedAt":"2026-10-23T00:00:00Z","branches":[],"outcome":"published","detail":""}
                """);
    }

    @Test
    @DisplayName("with publishing off, an enhancement waits for nothing new")
    void publishingOffChangesNothing() {
        parentIn("003", Phase.ACCEPTED);
        parentIn("003.4", Phase.GATING);

        assertNull(rules().familyWait(spec("003.3", "003")));
    }

    @Test
    @DisplayName("with publishing on, an enhancement begins only once the family's repository is on GitHub")
    void beginningWaitsForTheRepository() throws IOException {
        publishing();
        parentIn("003", Phase.ACCEPTED);

        assertEquals("waiting for 003's repository on GitHub, which approving 003 makes",
                rules().familyWait(spec("003.1", "003")));
        established("003");
        assertNull(rules().familyWait(spec("003.1", "003")));
    }

    @Test
    @DisplayName("and only once the most recent version is approved, while one already under way carries on")
    void oneEnhancementAtATime() throws IOException {
        publishing();
        parentIn("003", Phase.ACCEPTED);
        established("003");
        parentIn("003.4", Phase.GATING);

        assertEquals("waiting for 003.4 to be approved: each enhancement begins from the most recent approved version",
                rules().familyWait(spec("003.3", "003")));
        assertNull(rules().familyWait(spec("003.4", "003")), "begun before the rule, it is not stopped half-way");

        parentIn("003.4", Phase.ACCEPTED);
        assertNull(rules().familyWait(spec("003.3", "003")));
    }

    // ------------------------------------------------------- the repository not here

    @Test
    @DisplayName("a project that has begun without its repository is not here; one never started has none to miss")
    void aMissingRepositoryIsNotHere() throws IOException {
        parentIn("003", Phase.ACCEPTED);
        String gone = "its repository is not on this machine (runs/003/repo is missing), "
                + "so nothing here can work on it until it is back";

        assertEquals(gone, rules().notHere(spec("003", null)));
        assertEquals(gone, rules().notHere(spec("003.4", "003")), "an enhancement works in its family's");
        assertNull(rules().notHere(spec("009", null)), "never started, so it has no repository yet");
        assertNull(rules().notHere(spec("009.1", "009")),
                "nor has its family: it is waiting for something else, and should say what");

        Files.createDirectories(RunPaths.under(runs, "003").repo().resolve(".git"));
        assertNull(rules().notHere(spec("003", null)));
    }

    // ------------------------------------------------------- a fresh run

    @Test
    @DisplayName("a fresh run of an enhancement built to a design owes a comparison with it; any other owes none")
    void aFreshRunInheritsTheDesign() {
        // Start and the scheduler each kept their own builder, and only the design button's DONE
        // path ever set this: an enhancement built to a picture was reviewed as if there were none.
        Spec enhancement = spec("001.1", "001");
        assertNull(rules().create(enhancement, new Assignment(List.of())).getPolishDesign());

        enhancement.front().setDesign("Neon arcade");
        assertEquals("Neon arcade", rules().create(enhancement, new Assignment(List.of())).getPolishDesign(),
                "held up to the picture, as a project restyled while DONE is");
    }

    @Test
    @DisplayName("a fresh run of an extra holds the default look still; any other may change it")
    void aFreshRunInheritsTheExtra() {
        Spec enhancement = spec("001.1", "001");
        assertNull(rules().create(enhancement, new Assignment(List.of())).getExtraKind());

        enhancement.front().setExtra("theme");
        assertEquals("theme", rules().create(enhancement, new Assignment(List.of())).getExtraKind());
    }

    @Test
    @DisplayName("a project that is not an improvement has nothing to wait for")
    void plainProjectsAreNeverBlockedByAParent() {
        assertTrue(rules().parentAccepted(spec("001", null)));
    }

    @Test
    @DisplayName("an improvement whose parent has never run is not ready")
    void unstartedParentBlocks() {
        assertFalse(rules().parentAccepted(spec("001.1", "001")));
    }

    // ------------------------------------------------------- readiness

    @Test
    @DisplayName("a finished project is left alone")
    void terminalIsNotReady() {
        for (Phase phase : Phase.values()) {
            if (phase.terminal()) {
                assertFalse(rules().ready(state(phase, null, null)), phase + " should be left alone");
            }
        }
    }

    @Test
    @DisplayName("a suspended project waits out the window the vendor named")
    void suspendedWaits() {
        assertFalse(rules().ready(
                state(Phase.SUSPENDED, Phase.GATING, Instant.now().plusSeconds(3600))));
        assertTrue(rules().ready(
                state(Phase.SUSPENDED, Phase.GATING, Instant.now().minusSeconds(1))));
    }

    @Test
    @DisplayName("a project waiting on a reviewer runs when a different reviewer is free")
    void freeReviewerBeatsAClosedWindow() {
        // The most recent divergence, and the one that cost a day: the window belongs to the
        // vendor that refused, not to the project.
        RunState waiting = state(Phase.SUSPENDED, Phase.REVIEWING, Instant.now().plusSeconds(86_400));

        assertTrue(rules().ready(waiting), "an idle reviewer should have been used");
        assertTrue(rules().waitingOnTheWrongVendor(waiting));
    }

    @Test
    @DisplayName("with every reviewer blocked it goes back to waiting")
    void allReviewersBlockedMeansWait() {
        for (AgentKind kind : AgentKind.values()) {
            quota.record(kind, Instant.now().plusSeconds(3600), "you have hit your usage limit");
        }
        RunState waiting = state(Phase.SUSPENDED, Phase.REVIEWING, Instant.now().plusSeconds(86_400));

        assertFalse(rules().ready(waiting));
        assertFalse(rules().waitingOnTheWrongVendor(waiting));
    }

    @Test
    @DisplayName("a project suspended for something other than a review keeps waiting")
    void nonReviewSuspensionIsUnaffected() {
        // The reviewer clause must not become a general excuse to ignore a quota window.
        assertFalse(rules().ready(
                state(Phase.SUSPENDED, Phase.REPAIRING, Instant.now().plusSeconds(3600))));
    }

    // ------------------------------------------------------- paths

    @Test
    @DisplayName("an improvement keeps its own state but works in its parent's repository")
    void improvementsShareTheParentRepo() {
        Spec parent = spec("001", null);
        Spec child = spec("001.1", "001");
        Wishlist wishlist =
                new Wishlist(List.of(parent, child));

        RunPaths paths = rules().pathsFor(child, wishlist);

        assertEquals(RunPaths.under(runs, "001").repo(), paths.repo(),
                "an improvement edits the project it improves");
        assertTrue(paths.stateFile().toString().contains("001.1"),
                "but keeps its own state, or the two would overwrite each other");
    }

    @Test
    @DisplayName("a project of its own works in its own repository")
    void plainProjectsOwnTheirRepo() {
        Spec only = spec("001", null);
        RunPaths paths = rules().pathsFor(only,
                new Wishlist(List.of(only)));

        assertEquals(RunPaths.under(runs, "001").repo(), paths.repo());
    }

    // ------------------------------------------------------- no agents at all

    @Test
    @DisplayName("without agents or a ledger it still answers, just with less to go on")
    void degradesWithoutCollaborators() {
        // The unattended path used to construct these with nulls, which is how the two sides came
        // to disagree in the first place. It must not throw.
        Eligibility bare = new Eligibility(runs);

        assertTrue(bare.parentAccepted(spec("001", null)));
        assertTrue(bare.ready(state(Phase.GATING, null, null)));
        assertFalse(bare.anyReviewerFree(state(Phase.REVIEWING, null, null)));
    }
}
