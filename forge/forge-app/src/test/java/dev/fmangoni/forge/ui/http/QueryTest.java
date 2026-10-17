package dev.fmangoni.forge.ui.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QueryTest {

    @Test
    @DisplayName("a parameter that was not sent is absent, so a default can apply instead of a 400")
    void missingIsAbsent() {
        assertEquals(Optional.empty(), Query.optional((String) null, "wait"));
        assertEquals(Optional.empty(), Query.optional("file=a.md", "wait"));
        assertEquals(Optional.of("30"), Query.optional("file=a.md&wait=30", "wait"));
    }

    @Test
    @DisplayName("values are URL-decoded, and a key without a value is not a match")
    void decoding() {
        assertEquals(Optional.of("001 app.md"), Query.optional("file=001%20app.md", "file"));
        assertEquals(Optional.empty(), Query.optional("file", "file"));
    }
}
