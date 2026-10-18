package dev.fmangoni.forge.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlanParserTest {

    private static final String HANDOFF = """
            {"status":"partial",
             "summary":"Parser and model are done; codegen is not started.",
             "completed":["Record lexer","Field tree model"],
             "remaining":[
               {"step":"Generate the resource classes","detail":"Reuse FieldTree; one per top-level record","estimate_minutes":45},
               {"step":"Wire the CLI","detail":"picocli is already a dependency","estimate_minutes":20}],
             "sessions_needed":2,
             "risks":["Overlapping field definitions are not handled yet"]}
            """;

    @Test
    void readsTheHandoff() {
        Plan plan = PlanParser.parse("Here is where I got to.\n\n" + HANDOFF);

        assertEquals("partial", plan.getStatus());
        assertEquals(2, plan.getCompleted().size());
        assertEquals(2, plan.getRemaining().size());
        assertEquals(2, plan.getSessionsNeeded());
        assertEquals("Generate the resource classes", plan.getRemaining().get(0).getStep());
        assertEquals(65, plan.remainingMinutes());
        assertTrue(plan.partial());
    }

    @Test
    @DisplayName("the features the agent proposes survive the handoff")
    void readsProposedFeatures() {
        // Every field added to this object has to be read by something, and the one that is easy
        // to add and forget to parse is the one nobody notices is always empty.
        Plan plan = PlanParser.parse("""
                Built it.
                {"status":"complete","summary":"done",
                 "features":[
                   {"name":"Report export","demo":"export a report, open the file"},
                   {"name":"Bulk import","demo":"import a batch, see the rows appear"}]}
                """);

        assertEquals(2, plan.getFeatures().size());
        assertEquals("Report export", plan.getFeatures().get(0).getName());
        assertEquals("export a report, open the file",
                plan.getFeatures().get(0).getDemo());
    }

    @Test
    @DisplayName("ideas for what next survive the handoff, separately from the features")
    void readsIdeas() {
        Plan plan = PlanParser.parse("""
                Done.
                {"status":"complete","summary":"done",
                 "features":[{"name":"Report export","demo":"x"}],
                 "ideas":[{"name":"Rival leaderboard","demo":"a table of beaten bots"}]}
                """);

        assertEquals(1, plan.getFeatures().size());
        assertEquals(1, plan.getIdeas().size());
        assertEquals("Rival leaderboard", plan.getIdeas().get(0).getName());
    }

    @Test
    @DisplayName("a handoff from before features existed still loads")
    void featuresAreOptional() {
        assertTrue(PlanParser.parse(HANDOFF).getFeatures().isEmpty());
    }

    @Test
    @DisplayName("the handoff is taken from the end, past any JSON quoted in the summary")
    void ignoresExampleJsonEarlierInTheMessage() {
        String reply = """
                I added a config file that looks like this:

                {"port": 8080, "mode": "strict"}

                and a fixture {"id": 1}. Handoff follows.

                """ + HANDOFF;

        Plan plan = PlanParser.parse(reply);

        assertEquals("partial", plan.getStatus(), "picked up an example instead of the handoff");
        assertEquals(2, plan.getSessionsNeeded());
    }

    @Test
    @DisplayName("a complete turn reports no remaining work")
    void readsACompleteHandoff() {
        Plan plan = PlanParser.parse(
                "{\"status\":\"complete\",\"summary\":\"All acceptance commands pass.\","
                        + "\"completed\":[\"everything\"],\"remaining\":[],\"sessions_needed\":0}");

        assertFalse(plan.partial());
        assertEquals(0, plan.getSessionsNeeded());
        assertTrue(plan.usable());
    }

    @Test
    @DisplayName("a missing handoff is recorded, not thrown — the code on disk still counts")
    void missingHandoffIsNotFatal() {
        Plan plan = PlanParser.parse("I finished the work. No JSON here.");

        assertFalse(plan.usable());
        assertTrue(plan.getParseError().contains("no handoff JSON"), plan.getParseError());
    }

    @Test
    void malformedHandoffIsRecorded() {
        Plan plan = PlanParser.parse("{\"status\":\"partial\", \"remaining\": [ oops }");

        assertFalse(plan.usable());
        assertTrue(plan.getParseError().toLowerCase().contains("malformed"), plan.getParseError());
    }

    @Test
    void emptyReplyIsRecorded() {
        assertFalse(PlanParser.parse("").usable());
        assertFalse(PlanParser.parse(null).usable());
    }
}
