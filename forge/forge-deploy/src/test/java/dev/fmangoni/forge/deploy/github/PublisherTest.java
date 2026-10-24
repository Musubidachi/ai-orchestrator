package dev.fmangoni.forge.deploy.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A family's approved versions on GitHub: what goes up, what stays home, and every refusal.
 *
 * <p>The repositories are real ones on disk and every push is a real git push; only the account
 * is pretend ({@link FakeGitHub}).
 */
class PublisherTest {

    private static final String REPOSITORY = "gamma-project";

    @TempDir
    Path root;

    private Path runs;
    private FakeGitHub github;

    @BeforeEach
    void setUp() throws Exception {
        runs = root.resolve("runs");
        RunPaths initial = RunPaths.under(runs, "003");
        Files.createDirectories(initial.repo());
        new Git(initial.repo(), initial.gitLog()).init();
        github = new FakeGitHub(root.resolve("github"), "fabrizio");
    }

    /** A commit made by one version's run, logged in that run's own git log, as Forge does. */
    private String commit(String id, String subject) throws Exception {
        RunPaths paths = RunPaths.under(runs, id);
        Files.createDirectories(paths.logs());
        return new Git(RunPaths.under(runs, "003").repo(), paths.gitLog()).commitAll(subject);
    }

    private RunState version(String id, String title, Phase phase, String finished, SecurityFinding... findings) {
        RunState state = new RunState();
        state.setId(id);
        state.setTitle(title);
        state.setPhase(phase);
        state.setFinishedCommit(finished);
        RunStore.save(RunPaths.under(runs, id).stateFile(), state);
        new SecurityReport("head", "tree", List.of(LayerResult.ran("secrets", "", List.of(findings))))
                .save(RunPaths.under(runs, id).securityFile());
        return state;
    }

    private Publication publish(String id) {
        return new Publisher(runs, id, github).publish();
    }

    /** Records the commit a version began with, as the scaffold step does. */
    private void began(RunState state, String first) {
        state.setBaselineCommit(first);
        RunStore.save(RunPaths.under(runs, state.getId()).stateFile(), state);
    }

    @Test
    @DisplayName("main is the initial version, each approved enhancement its own branch, and one under way only its beginning")
    void approvedVersionsGoUp() throws Exception {
        String initial = commit("003", "Build (CLAUDE)");
        version("003", "Gamma Project", Phase.ACCEPTED, initial);
        String dealershipBegan = commit("003.1", "Requested improvement: Report Export");
        String dealership = commit("003.1", "Build (CLAUDE)");
        began(version("003.1", "Report Export", Phase.ACCEPTED, dealership), dealershipBegan);
        String navBegan = commit("003.4", "Requested improvement: Saved Filters");
        commit("003.4", "Build (CLAUDE)");
        began(version("003.4", "Saved Filters", Phase.GATING, null), navBegan);

        Publication published = publish("003.1");

        assertEquals(Publication.PUBLISHED, published.outcome(), published.detail());
        assertEquals("fabrizio/" + REPOSITORY, published.repository());
        assertEquals(List.of(REPOSITORY), github.created);
        assertEquals(Map.of("main", initial, "003.1-report-export", dealership,
                        "003.4-saved-filters", navBegan), github.branches(REPOSITORY),
                "the enhancement under way has its branch, at its first commit and none of its work");
    }

    @Test
    @DisplayName("an enhancement's branch is made as it begins, at its first commit")
    void aBranchIsMadeAsAnEnhancementBegins() throws Exception {
        version("003", "Gamma Project", Phase.ACCEPTED, commit("003", "Build (CLAUDE)"));
        publish("003");
        String first = commit("003.5", "Requested improvement: Visual Update");

        Publication begun = new Publisher(runs, "003.5", github).begin("003.5", "Visual Update", first);

        assertEquals(Publication.PUBLISHED, begun.outcome(), begun.detail());
        assertEquals(first, github.branches(REPOSITORY).get("003.5-visual-update"));
        assertEquals(List.of(REPOSITORY), github.created, "beginning never makes a repository");
    }

    @Test
    @DisplayName("a branch that cannot be made yet says why, and forgets nothing already recorded")
    void aFailedBeginKeepsTheRecord() throws Exception {
        Publication none = new Publisher(runs, "003.5", github).begin("003.5", "Visual Update", "f".repeat(40));
        assertTrue(none.detail().contains("began before 003 had a repository on GitHub"), none.detail());

        String initial = commit("003", "Build (CLAUDE)");
        version("003", "Gamma Project", Phase.ACCEPTED, initial);
        publish("003");
        // A commit that does not exist cannot be pushed: the push fails after the record was read.
        Publication failed = new Publisher(runs, "003.5", github).begin("003.5", "Visual Update", "f".repeat(40));

        assertEquals(Publication.FAILED, failed.outcome());
        Publication kept = Publisher.of(runs, "003").orElseThrow();
        assertTrue(kept.established(), "the repository is still recorded, so nothing would make a second one");
        assertEquals(initial, kept.branchOf("003").orElseThrow().commit());
    }

    @Test
    @DisplayName("an older version reopened on top of a newer one is not pushed under its own name")
    void aReopenedVersionCarryingLaterWorkIsRefused() throws Exception {
        version("003", "Gamma Project", Phase.ACCEPTED, commit("003", "Build (CLAUDE)"));
        String dealershipBegan = commit("003.1", "Requested improvement: Report Export");
        commit("003.1", "Build (CLAUDE)");
        String navBegan = commit("003.4", "Requested improvement: Saved Filters");
        // 003.1 sent back and finished again after 003.4 began: its new tip holds 003.4's beginning.
        String reopened = commit("003.1", "Requested changes (CLAUDE)");
        began(version("003.1", "Report Export", Phase.ACCEPTED, reopened), dealershipBegan);
        began(version("003.4", "Saved Filters", Phase.GATING, null), navBegan);

        Publication refused = publish("003");

        assertEquals(Publication.REFUSED, refused.outcome());
        assertEquals("003.1's work now sits on top of 003.4's, so its branch would carry that too: nothing was pushed",
                refused.detail());
        assertEquals(List.of(), github.created);
    }

    @Test
    @DisplayName("a later approval adds its own branch, and main stays at the initial version")
    void aLaterApprovalAddsItsBranch() throws Exception {
        String initial = commit("003", "Build (CLAUDE)");
        version("003", "Gamma Project", Phase.ACCEPTED, initial);
        publish("003");
        commit("003.1", "Requested improvement: Report Export");
        String dealership = commit("003.1", "Build (CLAUDE)");
        version("003.1", "Report Export", Phase.ACCEPTED, dealership);

        Publication published = publish("003.1");

        assertEquals(Publication.PUBLISHED, published.outcome(), published.detail());
        assertEquals(List.of(REPOSITORY), github.created, "the repository is made once");
        assertEquals(initial, github.branches(REPOSITORY).get("main"), "main is never moved up");
        assertEquals(dealership, github.branches(REPOSITORY).get("003.1-report-export"));
    }

    @Test
    @DisplayName("nothing is made until the initial version is approved and every approved version is fit to go")
    void refusalsMakeNothing() throws Exception {
        String initial = commit("003", "Build (CLAUDE)");
        version("003", "Gamma Project", Phase.DONE, initial);

        Publication refused = publish("003");
        assertEquals(Publication.REFUSED, refused.outcome());
        assertTrue(refused.detail().contains("initial version, 003, has to be approved first"), refused.detail());

        version("003", "Gamma Project", Phase.ACCEPTED, initial);
        commit("003.1", "Requested improvement: Report Export");
        version("003.1", "Report Export", Phase.ACCEPTED, commit("003.1", "Build (CLAUDE)"),
                new SecurityFinding("secrets", "critical", "AWS access key id committed", "src/keys.ts:3", "AKIA", true));

        refused = publish("003");
        assertEquals(Publication.REFUSED, refused.outcome());
        assertTrue(refused.detail().startsWith("003.1: 1 security finding(s) need a ruling"), refused.detail());
        assertEquals(List.of(), github.created, "a refusal makes no repository");
        assertTrue(!refused.established());
    }

    @Test
    @DisplayName("a repository already on GitHub under the name is somebody else's, and is not pushed into")
    void anExistingRepositoryIsRefused() throws Exception {
        version("003", "Gamma Project", Phase.ACCEPTED, commit("003", "Build (CLAUDE)"));
        github.existing.add("fabrizio/" + REPOSITORY);

        Publication refused = publish("003");

        assertEquals(Publication.FAILED, refused.outcome());
        assertTrue(refused.detail().contains("is already on GitHub, and Forge only pushes into repositories it made"),
                refused.detail());
        assertEquals(List.of(), github.created);
    }

    @Test
    @DisplayName("a gh that is not logged in is said so, and the person logs in themselves")
    void notLoggedIn() throws Exception {
        version("003", "Gamma Project", Phase.ACCEPTED, commit("003", "Build (CLAUDE)"));
        github = new FakeGitHub(root.resolve("github"), null);

        Publication failed = publish("003");

        assertEquals(Publication.FAILED, failed.outcome());
        assertEquals("gh is not logged in: run gh auth login yourself, then publish again", failed.detail());
        assertEquals(List.of(), github.created);
    }

    @Test
    @DisplayName("versions that finished before tips were recorded go up at the tips their history and logs agree on")
    void oldTipsAreRecovered() throws Exception {
        String initial = commit("003", "Build (CLAUDE)");
        version("003", "Gamma Project", Phase.ACCEPTED, null);
        commit("003.1", "Requested improvement: Delta Project — Night theme");
        String theme = commit("003.1", "Build (CLAUDE)");
        version("003.1", "Delta Project — Night theme", Phase.ACCEPTED, null);
        commit("003.4", "Requested improvement: Saved Filters");
        version("003.4", "Saved Filters", Phase.GATING, null);

        Publication published = publish("003");

        assertEquals(Publication.PUBLISHED, published.outcome(), published.detail());
        assertEquals(Map.of("main", initial, "003.1-delta-project-night-theme", theme),
                github.branches(REPOSITORY));
        assertEquals(theme, RunStore.load(RunPaths.under(runs, "003.1").stateFile()).orElseThrow().getFinishedCommit(),
                "worked out once, and written down");
    }

    @Test
    @DisplayName("a tip the history and the version's own log disagree on is refused, not guessed")
    void aDisputedTipIsNotGuessed() throws Exception {
        version("003", "Gamma Project", Phase.ACCEPTED, commit("003", "Build (CLAUDE)"));
        commit("003.1", "Requested improvement: Report Export");
        commit("003.1", "Build (CLAUDE)");
        // A commit nobody's run logged: the history now ends somewhere the log never saw.
        commit("someone-else", "made by hand");
        version("003.1", "Report Export", Phase.ACCEPTED, null);

        Publication failed = publish("003");

        assertEquals(Publication.FAILED, failed.outcome());
        assertTrue(failed.detail().startsWith("cannot tell where 003.1's work ends"), failed.detail());
        assertEquals(List.of(), github.created, "every tip is settled before anything is made or pushed");
    }
}
