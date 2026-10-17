package dev.fmangoni.forge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.ledger.QuotaLedger;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.git.LockHolder;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.project.RunPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The editor and another Forge process, one repository: see {@code RepoLockTest} for the lock itself. */
class JobRunnerLockTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("the editor will not start a project another Forge process is running")
    void startRefusesAHeldRepository() throws Exception {
        ForgeHome home = new ForgeHome(root);
        Files.createDirectories(home.wishlist());
        Files.writeString(home.houseRulesFile(), "# House rules\n");
        Files.writeString(home.wishlist().resolve("001-alpha.md"), """
                ---
                id: "001"
                title: Alpha
                priority: 1
                assign: claude
                acceptance:
                  - echo ok
                ---
                Build it.
                """);
        AtomicInteger calls = new AtomicInteger();
        Agent counting = new Agent() {
            @Override
            public AgentKind kind() {
                return AgentKind.CLAUDE;
            }

            @Override
            public AgentResult run(AgentTask task) {
                calls.incrementAndGet();
                throw new IllegalStateException("must not be reached");
            }
        };
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, counting);
        JobRunner runner = new JobRunner(home, new Agents(map), 4,
                new QuotaLedger(root.resolve("quota.json")));

        Process holder = LockHolder.holdInAnotherProcess(root, RunPaths.under(home.runs(), "001"));
        try {
            String result = runner.start("001");

            assertTrue(result.contains("another Forge process"), result);
            Thread.sleep(300);
            assertEquals(0, calls.get(), "an agent was started in a repository already in use");
        } finally {
            holder.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }
}
