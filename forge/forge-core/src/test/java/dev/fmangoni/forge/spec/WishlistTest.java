package dev.fmangoni.forge.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.spec.model.Complexity;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.rank.RankAssignment;
import dev.fmangoni.forge.spec.rank.Ranking;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WishlistTest {

    @Test
    @DisplayName("complexity maps onto whatever ladder the user configured")
    void complexityPicksATierProportionally() {
        ModelTiers tiers = new ModelTiers(Map.of(
                "CLAUDE", List.of("haiku", "sonnet", "opus", "fable"),
                "CODEX", List.of("a", "b", "c", "d", "e", "f")));

        assertEquals("fable", tiers.at(Complexity.HARD, AgentKind.CLAUDE));
        assertEquals("f", tiers.at(Complexity.HARD, AgentKind.CODEX));
        assertEquals("sonnet", tiers.at(Complexity.ROUTINE, AgentKind.CLAUDE));
    }

    @Test
    @DisplayName("a ladder of one still works, and an empty one yields the CLI default")
    void copesWithOddLadders() {
        ModelTiers one = new ModelTiers(Map.of("CLAUDE", List.of("only")));

        assertEquals("only", one.at(Complexity.HARD, AgentKind.CLAUDE));
        assertEquals("", one.at(Complexity.HARD, AgentKind.CODEX));
    }

    @Test
    @DisplayName("an unrecognised complexity falls to the middle, not to an extreme")
    void unknownComplexityIsNotTreatedAsHard() {
        assertEquals(Complexity.INVOLVED, Complexity.parse("enormous"));
        assertEquals(Complexity.INVOLVED, Complexity.parse(null));
        assertEquals(Complexity.HARD, Complexity.parse("  Hard "));
    }

    @TempDir
    Path dir;

    private Spec spec(String id, String parent, String acceptance, String evidence)
            throws IOException {
        return spec(id, parent, acceptance, evidence, 50);
    }

    private Spec spec(String id, String parent, String acceptance, String evidence, int priority)
            throws IOException {
        String file = id + "-spec.md";
        return SpecLoader.load(Files.writeString(dir.resolve(file), """
                ---
                id: "%s"
                %s
                title: Project %s
                priority: %d
                acceptance:
                  - %s
                evidence:
                  - %s
                ---
                Do the thing.
                """.formatted(id, parent.isEmpty() ? "" : "parent: \"" + parent + "\"",
                id, priority, acceptance, evidence)));
    }

    @Test
    @DisplayName("lower priority number runs first, across projects and improvements alike")
    void ordersEverythingByPriority() throws IOException {
        Spec urgent = spec("003", "", "mvn -q test", "a.png", 1);
        Spec normal = spec("001", "", "mvn -q test", "b.png", 50);
        Spec later = spec("002", "", "mvn -q test", "c.png", 90);

        assertEquals(List.of("003", "001", "002"),
                Ranking.runOrder(new Wishlist(List.of(normal, later, urgent)))
                        .stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("equal priorities fall back to id, so the order is reproducible")
    void breaksTiesByIdRatherThanLoadOrder() throws IOException {
        Spec b = spec("002", "", "mvn -q test", "a.png", 10);
        Spec a = spec("001", "", "mvn -q test", "b.png", 10);

        assertEquals(List.of("001", "002"),
                Ranking.runOrder(new Wishlist(List.of(b, a))).stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("improvements to one project are worked in their own priority order")
    void ordersImprovementsWithinAParent() throws IOException {
        Spec parent = spec("001", "", "mvn -q test", "a.png", 50);
        Spec second = spec("001.1", "001", "mvn -q test -Dtest=A", "b.png", 80);
        Spec first = spec("001.2", "001", "mvn -q test -Dtest=B", "c.png", 20);

        assertEquals(List.of("001.2", "001.1"),
                new Wishlist(List.of(parent, second, first))
                        .improvementsTo("001").stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("every project ranks above every improvement, however the numbers fall")
    void projectsAlwaysPrecedeImprovements() throws IOException {
        Spec parent = spec("001", "", "mvn -q test", "a.png", 50);
        Spec lowNumberedFix = spec("001.1", "001", "mvn -q test -Dtest=Fix", "b.png", 1);
        Spec idleProject = spec("002", "", "mvn -q test", "c.png", 70);

        // The improvement holds rank 1 and still comes last: finish what does not exist before
        // polishing what does. It is an ordering preference, not a barrier -- the improvement
        // still runs whenever the projects above it are done, parked or waiting on quota.
        assertEquals(List.of("001", "002", "001.1"),
                Ranking.runOrder(new Wishlist(List.of(parent, lowNumberedFix, idleProject)))
                        .stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("normalising numbers projects 1..P then improvements P+1..P+E")
    void normalisesToAContiguousSequence() throws IOException {
        Spec a = spec("001", "", "mvn -q test", "a.png", 30);
        Spec b = spec("002", "", "mvn -q test", "b.png", 10);
        Spec fix = spec("002.1", "002", "mvn -q test -Dtest=Fix", "c.png", 99);
        Spec fix2 = spec("001.1", "001", "mvn -q test -Dtest=Two", "d.png", 5);

        List<RankAssignment> ranking =
                new Ranking(new Wishlist(List.of(a, b, fix, fix2))).normalised();

        assertEquals(List.of("002", "001", "001.1", "002.1"),
                ranking.stream().map(r -> r.spec().id()).toList());
        assertEquals(List.of(1, 2, 3, 4), ranking.stream().map(RankAssignment::rank).toList());
    }

    @Test
    @DisplayName("moving a project one place renumbers the whole list, improvements included")
    void movingAProjectRenumbersEverything() throws IOException {
        Spec first = spec("001", "", "mvn -q test", "a.png", 1);
        Spec second = spec("002", "", "mvn -q test", "b.png", 2);
        Spec fix = spec("001.1", "001", "mvn -q test -Dtest=Fix", "c.png", 3);

        List<RankAssignment> moved =
                new Ranking(new Wishlist(List.of(first, second, fix))).moved("002", -1);

        assertEquals(List.of("002", "001", "001.1"),
                moved.stream().map(r -> r.spec().id()).toList());
        assertEquals(List.of(1, 2, 3), moved.stream().map(RankAssignment::rank).toList());
    }

    @Test
    @DisplayName("an improvement cannot be promoted above the projects")
    void improvementsMoveOnlyWithinTheirOwnGroup() throws IOException {
        Spec project = spec("001", "", "mvn -q test", "a.png", 1);
        Spec fix = spec("001.1", "001", "mvn -q test -Dtest=Fix", "b.png", 2);
        Ranking ranking = new Ranking(new Wishlist(List.of(project, fix)));

        // Already first among improvements, so moving up is a no-op rather than a promotion.
        assertEquals(List.of("001", "001.1"),
                ranking.moved("001.1", -1).stream().map(r -> r.spec().id()).toList());
    }

    @Test
    void rootsExcludeImprovements() throws IOException {
        Spec parent = spec("001", "", "mvn -q test", "a.png", 50);
        Spec child = spec("001.1", "001", "mvn -q test -Dtest=A", "b.png", 10);

        assertEquals(List.of("001"),
                new Wishlist(List.of(parent, child)).roots().stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("an improvement must satisfy its parent's acceptance as well as its own")
    void combinesAcceptanceWithTheParent() throws IOException {
        Spec parent = spec("001", "", "mvn -q test", "out.html");
        Spec child = spec("002", "001", "mvn -q verify -Dtest=NewFeature", "feature.png");
        Wishlist wishlist = new Wishlist(List.of(parent, child));

        assertEquals(
                List.of("mvn -q test", "mvn -q verify -Dtest=NewFeature"),
                wishlist.effectiveAcceptance(child),
                "the parent's commands run first, so a regression is reported before the new work");
        assertEquals(List.of("out.html", "feature.png"), wishlist.effectiveEvidence(child));
    }

    @Test
    @DisplayName("a project on its own is unaffected")
    void aRootSpecKeepsItsOwnCommands() throws IOException {
        Spec parent = spec("001", "", "mvn -q test", "out.html");

        assertEquals(List.of("mvn -q test"),
                new Wishlist(List.of(parent)).effectiveAcceptance(parent));
    }

    @Test
    @DisplayName("improvements chain, and all of them work in the original project's repository")
    void followsTheChainToTheRepositoryOwner() throws IOException {
        Spec root = spec("001", "", "mvn -q test", "a.png");
        Spec first = spec("002", "001", "mvn -q test -Dtest=One", "b.png");
        Spec second = spec("003", "002", "mvn -q test -Dtest=Two", "c.png");
        Wishlist wishlist = new Wishlist(List.of(root, first, second));

        assertEquals("001", wishlist.repoOwner(second));
        assertEquals(List.of("001", "002"), wishlist.ancestry(second).stream().map(Spec::id).toList());
        assertEquals(
                List.of("mvn -q test", "mvn -q test -Dtest=One", "mvn -q test -Dtest=Two"),
                wishlist.effectiveAcceptance(second));
    }

    @Test
    @DisplayName("a command shared with an ancestor is not run twice")
    void deduplicatesRepeatedCommands() throws IOException {
        Spec parent = spec("001", "", "mvn -q test", "out.html");
        Spec child = spec("002", "001", "mvn -q test", "out.html");

        assertEquals(List.of("mvn -q test"),
                new Wishlist(List.of(parent, child)).effectiveAcceptance(child));
    }

    @Test
    @DisplayName("an improvement naming a parent that is not there is an orphan, not a new project")
    void detectsOrphans() throws IOException {
        Spec orphan = spec("002", "999", "mvn -q test", "out.html");
        Wishlist wishlist = new Wishlist(List.of(orphan));

        assertTrue(wishlist.isOrphan(orphan),
                "otherwise it would scaffold an empty repo and improve nothing");
        assertFalse(wishlist.isOrphan(spec("001", "", "mvn -q test", "out.html")));
    }

    @Test
    void listsImprovementsAgainstAProject() throws IOException {
        Spec parent = spec("001", "", "mvn -q test", "a.png");
        Spec childB = spec("003", "001", "mvn -q test -Dtest=B", "b.png");
        Spec childA = spec("002", "001", "mvn -q test -Dtest=A", "c.png");

        assertEquals(List.of("002", "003"),
                new Wishlist(List.of(parent, childB, childA)).improvementsTo("001")
                        .stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("reading order nests improvements under improvements, however deep the chain runs")
    void nestsImprovementsToAnyDepth() throws IOException {
        Spec root = spec("001", "", "mvn -q test", "a.png", 1);
        Spec other = spec("002", "", "mvn -q test", "b.png", 2);
        Spec child = spec("001.1", "001", "mvn -q test", "c.png", 50);
        Spec grandchild = spec("001.2", "001.1", "mvn -q test", "d.png", 50);
        Spec greatGrandchild = spec("001.3", "001.2", "mvn -q test", "e.png", 50);
        Wishlist wishlist = new Wishlist(List.of(root, other, child, grandchild, greatGrandchild));

        assertEquals(List.of("001", "001.1", "001.2", "001.3", "002"),
                wishlist.inFamilyOrder().stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("every spec appears in reading order, including ones no project leads to")
    void readingOrderLeavesNothingOut() throws IOException {
        Spec root = spec("001", "", "mvn -q test", "a.png", 1);
        Spec orphan = spec("002", "999", "mvn -q test", "b.png", 2);
        Spec cycleA = spec("003", "004", "mvn -q test", "c.png", 3);
        Spec cycleB = spec("004", "003", "mvn -q test", "d.png", 4);
        Wishlist wishlist = new Wishlist(List.of(root, orphan, cycleA, cycleB));

        // The descent from the projects reaches 001 alone. The other three are listed anyway: a
        // spec missing from this list is one nobody can see is stuck, and the queue runs a spec
        // whether or not anything listed it.
        assertEquals(List.of("001", "002", "003", "004"),
                wishlist.inFamilyOrder().stream().map(Spec::id).toList());
    }

    @Test
    @DisplayName("a parent cycle written by hand terminates rather than hanging the run")
    void survivesACycle() throws IOException {
        Spec a = spec("001", "002", "mvn -q test", "a.png");
        Spec b = spec("002", "001", "mvn -q test -Dtest=B", "b.png");
        Wishlist wishlist = new Wishlist(List.of(a, b));

        assertTrue(wishlist.repoOwner(a).equals("001") || wishlist.repoOwner(a).equals("002"));
    }
}
