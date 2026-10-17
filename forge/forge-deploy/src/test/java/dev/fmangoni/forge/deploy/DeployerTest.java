package dev.fmangoni.forge.deploy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deploys run with commands that only echo what a real CLI prints: no network, no account. */
class DeployerTest {

    @TempDir
    Path root;

    private RunPaths paths;
    private Git git;

    @BeforeEach
    void setUp() throws Exception {
        paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.repo());
        git = new Git(paths.repo(), paths.gitLog());
        git.init();
        Files.writeString(paths.repo().resolve("index.html"), "<h1>hi</h1>");
        git.commitAll("built");
    }

    private Spec spec(String deployBlock, String extra) throws Exception {
        return SpecLoader.load(Files.writeString(root.resolve("001.md"), """
                ---
                id: "001"
                title: App
                acceptance:
                  - echo ok
                %s
                %s
                ---
                Build it.
                """.formatted(deployBlock, extra)));
    }

    private static final String PUBLISHES = """
            deploy:
              command: echo Uploading... done. Visit https://app-123.pages.dev
              check: exit 0""";

    private RunState accepted() {
        RunState state = new RunState();
        state.setId("001");
        state.setPhase(Phase.ACCEPTED);
        return state;
    }

    private void securityWith(SecurityFinding... findings) {
        new SecurityReport(git.head(), git.tree(), List.of(LayerResult.ran("secrets", "", List.of(findings))))
                .save(paths.securityFile());
    }

    private Deployer deployer(Spec spec) {
        return new Deployer(spec, paths, git, System.getenv());
    }

    @Test
    @DisplayName("Deploy is offered only for an accepted, security-checked project, and says why not")
    void preconditions() throws Exception {
        Deployer deployer = deployer(spec(PUBLISHES, ""));
        RunState state = accepted();

        state.setPhase(Phase.DONE);
        assertEquals("only an accepted project can be deployed", deployer.blockedReason(state));

        state.setPhase(Phase.ACCEPTED);
        assertEquals("run the security check before deploying it", deployer.blockedReason(state));

        SecurityFinding key = new SecurityFinding("secrets", "critical", "AWS access key id committed",
                "src/keys.ts:3", "AKIA… (redacted)", true);
        securityWith(key);
        assertTrue(deployer.blockedReason(state).contains("need a ruling"), deployer.blockedReason(state));

        // Ruled on — here, saved even though the project was already accepted.
        String itemId = ReviewItem.of(null, null, SecurityReport.load(paths.securityFile())).get(0).id();
        DecisionStore.save(paths.decisionsFile(), List.of(Decision.of(itemId, "key", "ok", "rotated it")));
        assertNull(deployer.blockedReason(state));

        DecisionStore.save(paths.decisionsFile(), List.of(Decision.of(itemId, "key", "changes", "fix this")));
        assertTrue(deployer.blockedReason(state).contains("sent back"), deployer.blockedReason(state));

        assertEquals("the spec has no deploy command", deployer(spec("", "")).blockedReason(state));
    }

    @Test
    @DisplayName("a CLI that is not installed is named before anything runs")
    void missingTool() throws Exception {
        securityWith();
        Deployer deployer = deployer(spec(PUBLISHES + "\n  requires: [forge-no-such-cli-xyz]", ""));

        assertTrue(deployer.blockedReason(accepted()).startsWith("forge-no-such-cli-xyz is not installed"),
                deployer.blockedReason(accepted()));
    }

    @Test
    @DisplayName("a deploy records the commit, the URL the CLI printed, and a passing health check")
    void successfulDeploy() throws Exception {
        Deployment deployment = deployer(spec(PUBLISHES, "")).deploy();

        assertEquals(Deployment.DEPLOYED, deployment.outcome(), deployment.detail());
        assertEquals("https://app-123.pages.dev", deployment.url());
        assertEquals(git.head(), deployment.commit());
        assertEquals(1, deployment.number());
        assertEquals(List.of(deployment), Deployment.history(paths.deploymentsFile()));
        assertFalse(Deployer.stale(deployment, git.head()));

        Files.writeString(paths.repo().resolve("index.html"), "<h1>changed</h1>");
        git.commitAll("changed");
        assertTrue(Deployer.stale(Deployment.lastSuccessful(paths.deploymentsFile()), git.head()));
    }

    @Test
    @DisplayName("HEAD read from .git files matches git, loose or packed")
    void headWithoutRunningGit() throws Exception {
        assertEquals(git.head(), Deployer.headOf(paths.repo()));

        Process pack = new ProcessBuilder("git", "pack-refs", "--all", "--prune")
                .directory(paths.repo().toFile()).redirectErrorStream(true).start();
        pack.getInputStream().readAllBytes();
        assertEquals(0, pack.waitFor());
        assertFalse(Files.exists(paths.repo().resolve(".git/refs/heads/master"))
                && Files.exists(paths.repo().resolve(".git/refs/heads/main")), "refs should now be packed");
        assertEquals(git.head(), Deployer.headOf(paths.repo()), "after the refs are packed");
        assertNull(Deployer.headOf(root.resolve("not-a-repo")));
    }

    @Test
    @DisplayName("a CLI that is not logged in is reported as such, and Forge does not try to log in")
    void notLoggedIn() throws Exception {
        Deployment deployment = deployer(spec("""
                deploy:
                  command: echo Error - You are not logged in. Please run wrangler login & exit 1""", "")).deploy();

        assertEquals(Deployment.NOT_LOGGED_IN, deployment.outcome());
        assertTrue(deployment.detail().contains("yourself"), deployment.detail());
    }

    @Test
    @DisplayName("a deploy whose health check fails is recorded as unhealthy, not as deployed")
    void unhealthyDeploy() throws Exception {
        Deployment deployment = deployer(spec("""
                deploy:
                  command: echo done https://app-123.pages.dev
                  check: exit 3""", "")).deploy();

        assertEquals(Deployment.UNHEALTHY, deployment.outcome());
        assertTrue(deployment.detail().startsWith("deployed, but the health check failed: the check command exited 3"),
                deployment.detail());
        assertNull(Deployment.lastSuccessful(paths.deploymentsFile()));
    }

    @Test
    @DisplayName("only a deploy of a live-ads spec gets the public ad ids, and nothing shell-shaped gets through")
    void adsGoLiveOnlyInADeploy() throws Exception {
        String live = deployer(spec(PUBLISHES, """
                monetization:
                  ads: live
                  provider: adsense
                  ids: { publisher: ca-pub-1234567890, slot: "1 & calc.exe" }""")).withEnvironment("npm run deploy");

        assertTrue(live.contains("FORGE_ADS_MODE=live"), live);
        assertTrue(live.contains("FORGE_AD_PUBLISHER=ca-pub-1234567890"), live);
        assertFalse(live.contains("calc.exe"), "an id with shell syntax in it must never reach the script");
        assertTrue(live.strip().endsWith("npm run deploy"));

        String test = deployer(spec(PUBLISHES, """
                monetization:
                  ads: test
                  ids: { publisher: ca-pub-1234567890 }""")).withEnvironment("npm run deploy");
        assertTrue(test.contains("FORGE_ADS_MODE=test"));
        assertFalse(test.contains("ca-pub-1234567890"), "test mode never carries the real ids");
    }
}
