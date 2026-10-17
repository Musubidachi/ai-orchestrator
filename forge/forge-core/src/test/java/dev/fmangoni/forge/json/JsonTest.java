package dev.fmangoni.forge.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one mapper, and the sweep that keeps it the only one.
 *
 * <p>The failure this replaces was silent in the worst way: {@code Http} lacked the time module,
 * so an endpoint answered 200 with a body Jackson had abandoned half-written. A truncated
 * successful response is indistinguishable from a working one until a person notices a banner is
 * missing.
 */
class JsonTest {

    @Test
    @DisplayName("time is written as a date a person can read, not as a number")
    void writesReadableTimestamps() throws Exception {
        String written = Json.mapper().writeValueAsString(
                Map.of("at", Instant.parse("2026-09-13T08:00:00Z")));

        assertTrue(written.contains("2026-09-13"), written);
    }

    @Test
    @DisplayName("time round-trips, so a state file written today loads tomorrow")
    void readsTimestampsBack() throws Exception {
        Instant at = Instant.parse("2026-09-13T08:00:00Z");
        String written = Json.mapper().writeValueAsString(Map.of("at", at));

        assertEquals(at, Json.mapper().readValue(written, Holder.class).at());
    }

    record Holder(Instant at) { }

    @Test
    @DisplayName("output is indented, because these files are read by people")
    void writesIndented() throws Exception {
        assertTrue(Json.mapper().writeValueAsString(Map.of("a", 1)).contains("\n"));
    }
}
