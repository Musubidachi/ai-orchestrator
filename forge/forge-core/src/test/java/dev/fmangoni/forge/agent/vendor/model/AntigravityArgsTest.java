package dev.fmangoni.forge.agent.vendor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Antigravity command line.
 *
 * <p>Every assertion here is about something whose absence fails quietly. The prompt route in
 * particular: {@code agy} refuses an empty {@code -p} outright but will happily accept a prompt
 * that has been truncated by Windows' argument cap, so getting this wrong produces a review of half
 * a diff rather than an error.
 */
class AntigravityArgsTest {

    private static final Duration CAP = Duration.ofMinutes(20);

    private String after(List<String> args, String flag) {
        int at = args.indexOf(flag);
        assertTrue(at >= 0 && at + 1 < args.size(), flag + " is missing from " + args);
        return args.get(at + 1);
    }

    @Test
    @DisplayName("a reviewer runs in plan mode and is given no permission to skip")
    void reviewersCannotWrite() {
        List<String> args = AntigravityArgs.of(true, null, null, null, CAP);

        // Verified against agy 1.2.2: a plan-mode turn asked to write two files called
        // write_to_file three times and changed nothing on disk.
        assertEquals("plan", after(args, "--mode"));
        assertFalse(args.contains("--dangerously-skip-permissions"),
                "a reviewer has no business auto-approving anything");
    }

    @Test
    @DisplayName("a builder may act, because there is nobody to answer a prompt")
    void buildersCanWrite() {
        List<String> args = AntigravityArgs.of(false, null, null, null, CAP);

        assertEquals("accept-edits", after(args, "--mode"));
        assertTrue(args.contains("--dangerously-skip-permissions"));
    }

    @Test
    @DisplayName("the brief goes in on stdin, never as an argument")
    void promptIsNotAnArgument() {
        // agy takes its prompt as the value of -p, and Windows caps a command line near 32k.
        // A review brief is a spec plus a diff; REVIEW_DIFF_CAP alone is 60k. Passing it as an
        // argument does not look untidy, it fails.
        List<String> args = AntigravityArgs.of(true, null, null, null, CAP);

        assertEquals("stream-json", after(args, "--input-format"));
        assertEquals("stream-json", after(args, "--output-format"));
        // Exactly "--print=", nothing after it. A value here is rejected outright when the input
        // format is stream-json, and a bare -p swallows whatever argument follows it.
        assertTrue(args.contains("--print="), "print mode is not selected: " + args);
        assertFalse(args.contains("-p"), "a separated -p takes the next argument as its prompt");
        assertTrue(args.stream().noneMatch(a -> a.length() > 200),
                "nothing long should ever reach the command line");
    }

    @Test
    @DisplayName("the brief is one NDJSON line the CLI will accept")
    void stdinMessageIsWellFormed() throws Exception {
        String line = AntigravityArgs.stdinMessage("review this diff");

        JsonNode node =
                new ObjectMapper().readTree(line);

        // Learned by being told: the discriminator is "event", not Claude's "type", and the
        // message needs "content" — a bare string where the object goes is rejected.
        assertEquals("user", node.path("event").asText());
        assertEquals("review this diff", node.path("message").path("content").asText());
        assertTrue(line.endsWith(System.lineSeparator()), "NDJSON is one object per line");
    }

    @Test
    @DisplayName("a brief with newlines and quotes survives the encoding")
    void stdinMessageEscapes() throws Exception {
        // A diff is full of both, and one raw newline would split the message into two lines,
        // the second of which is not JSON.
        String brief = "line one\n\"quoted\"\nline three\\end";

        String line = AntigravityArgs.stdinMessage(brief);

        assertEquals(1, line.strip().lines().count(), "the message must stay on one line");
        assertEquals(brief, new ObjectMapper()
                .readTree(line).path("message").path("content").asText());
    }

    @Test
    @DisplayName("Forge's own cap is passed on, so the CLI's five-minute default cannot fire first")
    void timeoutIsCarried() {
        assertEquals("1200s", after(AntigravityArgs.of(true, null, null, null, CAP),
                "--print-timeout"));
        assertFalse(AntigravityArgs.of(true, null, null, null, null).contains("--print-timeout"),
                "no cap given means leave the CLI's own default alone");
    }

    @Test
    @DisplayName("a resumed turn continues its conversation; a new one names none")
    void conversationHandling() {
        assertEquals("conv-1",
                after(AntigravityArgs.of(true, "conv-1", null, null, CAP), "--conversation"));

        // There is no flag to assign an id up front — the CLI names the conversation itself.
        assertFalse(AntigravityArgs.of(true, null, null, null, CAP).contains("--conversation"));
        assertFalse(AntigravityArgs.of(true, "  ", null, null, CAP).contains("--conversation"),
                "a blank id is not a conversation");
    }

    @Test
    @DisplayName("the turn's model wins over the run-wide one, and blank means neither")
    void modelPrecedence() {
        assertEquals("gemini-3.1-pro-high",
                after(AntigravityArgs.of(true, null, "gemini-3.1-pro-high", "gemini-3.6-flash-low",
                        CAP), "--model"));
        assertEquals("gemini-3.6-flash-low",
                after(AntigravityArgs.of(true, null, "  ", "gemini-3.6-flash-low", CAP), "--model"));
        assertFalse(AntigravityArgs.of(true, null, null, null, CAP).contains("--model"),
                "no model named means the CLI's own default, not an empty flag");
    }

    @Test
    @DisplayName("no working-directory flag is passed, because there is not one")
    void noCwdFlag() {
        // agy takes its workspace from the process's own directory, which CliRunner sets. That is
        // what keeps an agent inside runs/NNN/repo, so it is worth stating.
        List<String> args = AntigravityArgs.of(true, null, null, null, CAP);

        assertFalse(args.contains("-C"));
        assertFalse(args.contains("--add-dir"));
        assertFalse(args.contains("--new-project"));
    }
}
