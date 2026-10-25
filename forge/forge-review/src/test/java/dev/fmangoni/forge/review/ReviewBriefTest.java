package dev.fmangoni.forge.review;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.review.model.ReviewRound;
import dev.fmangoni.forge.spec.model.FrontMatter;
import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a review against a picture is told to open. The person's own pictures, when they attached
 * any, are the look being asked for, and a reviewer told only about the vendor's redrawing compared
 * the result with the wrong thing.
 */
class ReviewBriefTest {

    private static final String PICTURES = "Match these [image: .forge-images/garage.png] and "
            + "[image: .forge-images/race.png].";

    private Spec spec(String body) {
        FrontMatter front = new FrontMatter();
        front.setId("003.3");
        front.setTitle("Gamma Project visual update");
        return new Spec(Path.of("003.3.md"), front, body);
    }

    private String brief(String design, String body) {
        return ReviewBrief.of(spec(body), "diff --git a/x b/x", List.of(), ReviewRound.first(), design);
    }

    @Test
    @DisplayName("attached pictures are opened, outrank the redrawing, and are each compared with their own screen")
    void attachedPicturesAreTheLook() {
        String brief = brief("Night Garage", PICTURES);

        assertTrue(brief.contains("The person also attached pictures of the look they want"), brief);
        assertTrue(brief.contains("where a picture and the design disagree, the picture wins"), brief);
        assertTrue(brief.contains("`artifacts/polish-after-<screen>.png`"), brief);
        assertTrue(brief.indexOf("The person also attached pictures")
                        < brief.indexOf("Say in your reply whether you did this"),
                "said before the reply is asked for, so the pictures are opened before anything is claimed");
        assertTrue(brief.contains("once you have opened every file named here"), brief);
    }

    @Test
    @DisplayName("a design with no pictures attached is compared as it always was")
    void noPicturesNoParagraph() {
        String brief = brief("Night Garage", "Make it look like the design.");

        assertTrue(brief.contains("## The look it was asked for"), brief);
        assertFalse(brief.contains("The person also attached pictures"), brief);
    }

    @Test
    @DisplayName("pictures alone do not make a review into a comparison: only a picked design does")
    void picturesWithoutADesign() {
        String brief = brief(null, PICTURES);

        assertFalse(brief.contains("## The look it was asked for"), brief);
        assertFalse(brief.contains("The person also attached pictures"), brief);
        assertTrue(brief.startsWith("Review this change. Do not modify any files, and do not run any commands"),
                "still read-only: " + brief);
    }

    @Test
    @DisplayName("built to the person's own pictures with no design picked, each picture is compared with its own screen")
    void ownPicturesWithNoPickedDesign() {
        // Twin Thrones: every screen built from a mockup, with no vendor's redrawing in between.
        String brief = brief(ReviewBrief.OWN_PICTURES, PICTURES);

        assertTrue(brief.contains("This change builds screens to the person's own pictures"), brief);
        assertTrue(brief.contains("`artifacts/polish-after-<screen>.png`"), brief);
        assertTrue(brief.contains("the stand-in itself is not a defect"), "art drawn later is not raised: " + brief);
        assertTrue(brief.contains("\"compared_with_design\": true"), brief);
        assertFalse(brief.contains(ReviewBrief.PICKED_DESIGN), "there is no picked design to open: " + brief);
        assertTrue(brief.contains("except to open the files named"), "allowed to open the pictures: " + brief);
    }
}
