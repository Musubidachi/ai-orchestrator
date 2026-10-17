package dev.fmangoni.forge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.Agent;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentOutcome;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.gate.RunFile;
import dev.fmangoni.forge.pipeline.run.ReviewLoop;
import dev.fmangoni.forge.pipeline.run.Workspace;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.ReviewStore;
import dev.fmangoni.forge.review.decision.ReviewHistory;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.review.decision.model.Sitting;
import dev.fmangoni.forge.review.model.Finding;
import dev.fmangoni.forge.spec.SpecLoader;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.spec.tier.ModelTiers;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every blocking finding has to be fixed: two attempts each, checked by id, then a person.
 *
 * <p>What this replaced finished a project after one repair turn whatever the next review said, and
 * each review was a fresh read that reworded any defect nobody had fixed. Four of five finished
 * projects here were DONE with blocking findings on them.
 */
class ReviewLoopTest {

    @TempDir
    Path root;

    private RunPaths paths;

    /** The builder: writes a real overview each turn, and answers each finding it was given. */
    private record Builder(List<String> briefs, String answer) implements Agent {
        @Override
        public AgentKind kind() {
            return AgentKind.CLAUDE;
        }

        @Override
        public AgentResult run(AgentTask task) {
            briefs.add(task.brief());
            try {
                Files.writeString(task.cwd().resolve("Pipeline.java"), OVERVIEW + "// turn " + briefs.size());
                Files.writeString(task.cwd().resolve(RunFile.NAME), "@echo off\ncall mvn -q exec:java\npause\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String handoff = """
                    {"status":"complete","summary":"built","completed":["it"],"remaining":[],
                     "sessions_needed":0,"overview":"Pipeline.java","review_findings":[%s]}
                    """.formatted(answer);
            return new AgentResult(AgentOutcome.COMPLETED, handoff,
                    new ProcResult(0, handoff, "", false, Duration.ofSeconds(1)), null);
        }
    }

    /**
     * The reviewer: replies from a script, the last reply repeating; remembers what it was asked.
     *
     * <p>Only cross-reviews are scripted. The same vendor is asked for the security pass, and that
     * gets an empty answer rather than one of these.
     */
    private record Reviewer(List<String> briefs, List<String> replies) implements Agent {
        @Override
        public AgentKind kind() {
            return AgentKind.CODEX;
        }

        @Override
        public AgentResult run(AgentTask task) {
            if (!task.brief().startsWith("Review this change.")) {
                return new AgentResult(AgentOutcome.COMPLETED, "{\"findings\":[]}",
                        new ProcResult(0, "", "", false, Duration.ofSeconds(1)), null);
            }
            briefs.add(task.brief());
            String reply = replies.get(Math.min(briefs.size() - 1, replies.size() - 1));
            return new AgentResult(AgentOutcome.COMPLETED, reply,
                    new ProcResult(0, reply, "", false, Duration.ofSeconds(1)), null);
        }
    }

    private static final String OVERVIEW = """
            /** Reads the input, transforms it, writes the result. */
            public final class Pipeline {
                public void run(Path in, Path out) {
                    var rows = reader.read(in);
                    var result = transformer.apply(rows);
                    writer.write(result, out);
                }
            }
            """;

    private static final String A = finding("src/Docx.java", "text around tables comes out of order",
            "paragraphs() skips table cells and appends them at the end");

    private static final ModelTiers TIERS = new ModelTiers(Map.of(
            "CLAUDE", List.of("cheap", "best"), "CODEX", List.of("codex-cheap", "codex-best")));

    private final List<String> built = new CopyOnWriteArrayList<>();
    private final List<String> reviewed = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        paths = new RunPaths(root.resolve("runs/001"));
        Files.createDirectories(paths.root());
        Files.writeString(paths.planFile(), "## Approach\nBuild it.\n");
    }

    private static String finding(String file, String summary, String detail) {
        return "{\"severity\":\"blocking\",\"file\":\"" + file + "\",\"summary\":\"" + summary
                + "\",\"detail\":\"" + detail + "\"}";
    }

    private static String idOf(String file, String summary) {
        Finding finding = new Finding();
        finding.setFile(file);
        finding.setSummary(summary);
        return ReviewItem.idOf(finding);
    }

    private static final String A_ID = idOf("src/Docx.java", "text around tables comes out of order");

    private static String review(String findings) {
        return "{\"findings\":[" + findings + "]}";
    }

    private static String recheck(String id, String status, String note, String findings) {
        return "{\"rechecks\":[{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"note\":\"" + note
                + "\"}],\"findings\":[" + findings + "]}";
    }

    private RunState run(String... replies) throws IOException {
        Path file = Files.writeString(root.resolve("001-test.md"), """
                ---
                id: "001"
                title: Test project
                assign: claude
                acceptance:
                  - exit 0
                max_repair_rounds: 2
                max_sessions: 20
                ---
                Build the thing.
                """);
        Spec spec = SpecLoader.load(file);
        String answer = "{\"id\":\"" + A_ID + "\",\"action\":\"fixed\","
                + "\"how\":\"walk cells in document order\",\"test\":\"DocxTest::tablesInOrder\"}";
        Map<AgentKind, Agent> map = new EnumMap<>(AgentKind.class);
        map.put(AgentKind.CLAUDE, new Builder(built, answer));
        map.put(AgentKind.CODEX, new Reviewer(reviewed, List.of(replies)));
        RunState state = RunState.builder().id("001").title("Test project")
                .assignedTo(AgentKind.CLAUDE).createdAt(Instant.now()).build();
        return new Orchestrator(spec, paths, state, new Agents(map), new Workspace(paths, "rules"),
                new Wishlist(List.of(spec)), TIERS, null).advance();
    }

    private String repairBrief(int n) {
        return built.get(n); // turn 0 is the build; repairs follow
    }

    @Test
    @DisplayName("a finding the reviewer checks and finds fixed lets the project finish")
    void fixedFindingsFinish() throws IOException {
        RunState state = run(review(A), recheck(A_ID, "fixed", "cells now in order", ""));

        assertEquals(Phase.DONE, state.getPhase());
        assertTrue(state.getBlockingFindings().isEmpty());
        assertEquals(2, built.size(), "one build and one repair");
        assertTrue(repairBrief(1).contains("paragraphs() skips table cells"), "the detail, not one line");
        assertTrue(repairBrief(1).contains("Attempt 1 of " + ReviewLoop.MAX_ATTEMPTS), repairBrief(1));
        assertTrue(repairBrief(1).contains("must be fixed"), "mandatory, not negotiable");

        String second = reviewed.get(1);
        assertTrue(second.contains(A_ID), "the reviewer is asked about the finding by name");
        assertTrue(second.contains("walk cells in document order (test: DocxTest::tablesInOrder)"),
                "and shown what the builder says it did:\n" + second);
    }

    @Test
    @DisplayName("a recheck is shown the finding's file as it stands now, not only a diff that may stop short of it")
    void aRecheckSeesTheFileAsItIsNow() throws IOException {
        // 005 was held over a fix that was in place and tested: the reviewer said "DocxParagraphEdit
        // is not included in the supplied diff", because the diff was cut at its cap before that file.
        String inPipeline = finding("Pipeline.java", "the overview names a step it never runs", "no writer call");
        String id = idOf("Pipeline.java", "the overview names a step it never runs");

        run(review(inPipeline), recheck(id, "fixed", "the writer is called", ""));

        String second = reviewed.get(1);
        assertTrue(second.contains("## Those files as they stand now"), second);
        assertTrue(second.contains("### Pipeline.java"), second);
        assertTrue(second.contains("// turn 2"), "the repaired file, as the repair left it:\n" + second);
        assertTrue(second.contains("from the file it is in, as it stands now"), second);
    }

    @Test
    @DisplayName("a finding still present goes back with the reviewer's reason, as the last attempt")
    void stillPresentCarriesTheReason() throws IOException {
        RunState state = run(review(A),
                recheck(A_ID, "still_present", "cells inside nested tables are still appended last", ""),
                recheck(A_ID, "fixed", "nested tables handled", ""));

        assertEquals(Phase.DONE, state.getPhase());
        assertEquals(3, built.size());
        assertTrue(repairBrief(2).contains("cells inside nested tables are still appended last"),
                repairBrief(2));
        assertTrue(repairBrief(2).contains("Attempt 2 of 2"), repairBrief(2));
    }

    @Test
    @DisplayName("a finding still present after its last attempt waits for a person, not DONE")
    void exhaustedFindingsHold() throws IOException {
        RunState state = run(review(A),
                recheck(A_ID, "still_present", "no", ""),
                recheck(A_ID, "still_present", "still no", ""));

        assertEquals(Phase.REVIEW_REPAIR, state.getPhase());
        assertTrue(state.getNeedsPerson().contains("text around tables comes out of order"),
                state.getNeedsPerson());
        assertEquals(1 + ReviewLoop.MAX_ATTEMPTS, built.size(), "no third attempt without a person");

        Finding carried = ReviewStore.load(paths.reviewFile()).blocking().getFirst();
        assertEquals(A_ID, carried.getId(), "the report shows what the project is held to");
        assertEquals("still no", carried.getStillPresent());
    }

    @Test
    @DisplayName("a finding the reviewer withdraws is settled")
    void withdrawnFindingsSettle() throws IOException {
        RunState state = run(review(A), recheck(A_ID, "withdrawn", "the spec allows it", ""));

        assertEquals(Phase.DONE, state.getPhase());
    }

    @Test
    @DisplayName("a reviewer that says nothing about a finding has not settled it")
    void silenceIsNotAFix() throws IOException {
        // The same defect, reworded, is also a new finding — that is the pattern this exists for.
        RunState state = run(review(A), review(""), review(""));

        assertEquals(Phase.REVIEW_REPAIR, state.getPhase());
        assertNotNull(state.getNeedsPerson());
    }

    @Test
    @DisplayName("a reviewer that keeps finding new things stops at the round cap, with a person")
    void newFindingsEveryRoundHitTheCap() throws IOException {
        List<String> replies = new ArrayList<>();
        replies.add(review(finding("F0.java", "defect 0", "d")));
        for (int round = 1; round <= ReviewLoop.MAX_ROUNDS + 2; round++) {
            String previous = idOf("F" + (round - 1) + ".java", "defect " + (round - 1));
            replies.add(recheck(previous, "fixed", "ok", finding("F" + round + ".java", "defect " + round, "d")));
        }
        RunState state = run(replies.toArray(String[]::new));

        assertEquals(Phase.REVIEW_REPAIR, state.getPhase());
        assertTrue(state.getNeedsPerson().contains("still raising"), state.getNeedsPerson());
        assertEquals(1 + ReviewLoop.MAX_ROUNDS, built.size());
    }

    @Test
    @DisplayName("an unreadable reply to a recheck holds the project; it settles nothing")
    void unreadableRecheckHolds() throws IOException {
        RunState state = run(review(A), "I looked at it and it seems fine now.");

        assertEquals(Phase.REVIEWING, state.getPhase());
        assertTrue(state.getNeedsPerson().contains("could not be read"), state.getNeedsPerson());
        assertEquals(1, state.getBlockingFindings().size(), "the finding is still owed its answer");
    }

    @Test
    @DisplayName("a finding the person ruled acceptable is put to the reviewer as not to be raised again")
    void ruledFineIsPassedOn() throws IOException {
        Decision fine = Decision.of("blocking-00000000", "src/Grid.java — grid can overflow", "ok", "");
        ReviewHistory.append(paths.reviewHistoryFile(),
                new Sitting(Instant.now(), "accepted", List.of(fine), List.of()));

        RunState state = run(review(""));

        assertEquals(Phase.DONE, state.getPhase());
        assertTrue(reviewed.getFirst().contains("Already ruled acceptable"), reviewed.getFirst());
        assertTrue(reviewed.getFirst().contains("src/Grid.java — grid can overflow"));
        assertNull(state.getNeedsPerson());
    }
}
