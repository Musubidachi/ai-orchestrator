package dev.fmangoni.forge.git;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a reviewing model is shown.
 *
 * <p>Every case here is the shape of a real review payload measured on this machine: one reviewer
 * was sent 60,000 characters that were 93% an encrypted PDF, sorted ahead of the source because
 * {@code sample/} precedes {@code src/}, and it never saw a line of Java.
 */
class ReviewDiffTest {

    private static String file(String path, String body) {
        return "diff --git a/" + path + " b/" + path + "\n"
                + "--- a/" + path + "\n+++ b/" + path + "\n@@ -0,0 +1 @@\n" + body + "\n";
    }

    private static final String SOURCE = file("src/main/java/Pipeline.java",
            "+public final class Pipeline { void run() {} }");

    @Test
    @DisplayName("a document git mistakes for text does not crowd out the source")
    void documentsDoNotCrowdOutSource() {
        // The measured case, in miniature: a PDF sorted ahead of the code and far bigger than it.
        String raw = file("sample/secured.pdf", "+%PDF-1.7 " + "x".repeat(50_000)) + SOURCE;

        String shaped = ReviewDiff.shape(raw, 60_000);

        assertTrue(shaped.contains("public final class Pipeline"), "the source was not shown:\n"
                + shaped.substring(0, Math.min(400, shaped.length())));
        assertFalse(shaped.contains("%PDF-1.7"), "the PDF body was sent to the reviewer");
        assertTrue(shaped.contains("sample/secured.pdf (pdf, not reviewable text)"),
                "the reviewer must be told what it is not seeing");
    }

    @Test
    @DisplayName("one enormous file is left out rather than eating the whole budget")
    void oversizedFilesAreSummarised() {
        // Catches what no extension list names: the fixture, the minified bundle, the generated SVG.
        String raw = file("fixtures/rows.csv", "+" + "1,2,3\n".repeat(20_000)) + SOURCE;

        String shaped = ReviewDiff.shape(raw, 60_000);

        assertTrue(shaped.contains("public final class Pipeline"));
        assertTrue(shaped.contains("fixtures/rows.csv ("), shaped.substring(0, 300));
        assertTrue(shaped.contains("too large to read in a review"));
    }

    @Test
    @DisplayName("a large test class is still shown, because size means generated only for non-code")
    void largeCodeIsNotSummarised() {
        // The first version of the size rule dropped a real 26,000-character test class from the
        // very project it was written to fix.
        String raw = file("src/test/java/ServiceTest.java", "+" + "assertTrue(ok);\n".repeat(1_600))
                + SOURCE;

        String shaped = ReviewDiff.shape(raw, 60_000);

        assertTrue(shaped.contains("ServiceTest.java\n"), "the test class was left out");
        assertFalse(shaped.contains("too large to read"));
    }

    @Test
    @DisplayName("code comes before configuration, so a cut lands on the less important part")
    void codeIsOrderedFirst() {
        String raw = file("README.md", "+# notes") + file("vite.config.ts", "+export default {}")
                + file("src/App.test.tsx", "+test('x', () => {})") + SOURCE;

        String shaped = ReviewDiff.shape(raw, 60_000);

        int source = shaped.indexOf("Pipeline.java");
        int test = shaped.indexOf("App.test.tsx");
        int readme = shaped.indexOf("README.md");
        assertTrue(source < test && test < readme,
                "expected source, then tests, then prose:\n" + shaped);
    }

    @Test
    @DisplayName("a small diff passes through with nothing added")
    void smallDiffsAreUntouched() {
        assertTrue(ReviewDiff.shape(SOURCE, 60_000).startsWith("diff --git"),
                "a note about excluded files where none were excluded is noise");
    }

    @Test
    @DisplayName("when it still does not fit, the reviewer is told the rest was not shown")
    void truncationIsAnnounced() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            raw.append(file("src/F" + i + ".java", "+" + "int x;".repeat(600)));
        }

        String shaped = ReviewDiff.shape(raw.toString(), 60_000);

        assertTrue(shaped.length() < 62_000, "over budget: " + shaped.length());
        assertTrue(shaped.contains("diff truncated here"));
    }
}
