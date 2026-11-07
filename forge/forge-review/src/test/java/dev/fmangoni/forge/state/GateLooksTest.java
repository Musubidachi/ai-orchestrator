package dev.fmangoni.forge.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.gate.CommandResult;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A command that passed and asked a person to look becomes one review item per thing it named. */
class GateLooksTest {

    private static CommandResult ran(String command, int exit, String output) {
        return new CommandResult(command, exit, output, false, Duration.ofSeconds(1), null);
    }

    @Test
    @DisplayName("each LOOK line is an item, saying which command asked")
    void oneItemPerLine() {
        List<String> looks = GateLooks.from(List.of(ran("python check.py", CommandResult.LOOK, """
                comparing 3 screens
                LOOK: the shop's prices moved 12px
                ok: landing
                look: the event banner is a different colour
                LOOK: the shop's prices moved 12px
                """)));

        assertEquals(List.of(
                "`python check.py`: LOOK: the shop's prices moved 12px",
                "`python check.py`: look: the event banner is a different colour"), looks);
    }

    @Test
    @DisplayName("a command that names nothing still asks, and points at the log")
    void namesNothing() {
        List<String> looks = GateLooks.from(List.of(ran("check.bat", CommandResult.LOOK, "done\n")));

        assertEquals(1, looks.size());
        assertTrue(looks.get(0).contains("gate log"), looks.get(0));
    }

    @Test
    @DisplayName("a plain pass, a failure or a timeout asks nothing, whatever it printed")
    void onlyTheLookCode() {
        CommandResult timedOut = new CommandResult("slow", CommandResult.LOOK, "LOOK: x", true,
                Duration.ofMinutes(5), null);

        assertEquals(List.of(), GateLooks.from(List.of(
                ran("a", 0, "LOOK: x"), ran("b", 1, "LOOK: y"), timedOut)));
    }

    @Test
    @DisplayName("the next gate replaces the last one's looks, so a fixed one goes away")
    void replacedByTheNextGate() {
        RunState state = new RunState();
        GateLooks.record(state, List.of(ran("check", CommandResult.LOOK, "LOOK: one")));
        GateLooks.record(state, List.of(ran("check", 0, "")));

        assertEquals(List.of(), state.getGateLooks());
    }

    @Test
    @DisplayName("added after the review's own items, with the same id at every gate")
    void addedToTheReview() {
        RunState state = new RunState();
        GateLooks.record(state, List.of(ran("check", CommandResult.LOOK, "took 3.2s\nLOOK: the banner")));
        ReviewItem first = ReviewItem.of("plan", "The agent", "something unproven");

        List<ReviewItem> items = GateLooks.with(List.of(first), state);

        assertEquals(2, items.size());
        assertEquals(first, items.get(0));
        assertTrue(GateLooks.asks(items.get(1)));
        assertTrue(!GateLooks.asks(first));

        RunState later = new RunState();
        GateLooks.record(later, List.of(ran("check", CommandResult.LOOK, "took 4.9s\nLOOK: the banner")));
        assertEquals(items.get(1).id(), GateLooks.with(List.of(), later).get(0).id());
    }

    @Test
    @DisplayName("exit 42 passes the command; 3 still fails it")
    void theCodeIsAPass() {
        assertTrue(ran("check", CommandResult.LOOK, "").passed());
        assertTrue(!ran("check", 3, "").passed());
        assertTrue(ran("check", CommandResult.LOOK, "").summary().startsWith("PASS, LOOK"));
    }
}
