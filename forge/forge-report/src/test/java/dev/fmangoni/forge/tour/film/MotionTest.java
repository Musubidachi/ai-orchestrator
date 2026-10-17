package dev.fmangoni.forge.tour.film;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fmangoni.forge.tour.model.CodeView;
import dev.fmangoni.forge.tour.model.Scene;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a scene moves while it is being said.
 *
 * <p>The thing to hold onto is that the picture serves the voice. The sentence has to be fully up
 * before the narration finishes it, the highlight has to have arrived long before that, and the
 * code has to stop sliding rather than still be easing when the next line is being discussed.
 */
class MotionTest {

    private Scene scene(String said) {
        List<String> lines = IntStream.rangeClosed(1, 40).mapToObj(n -> "line " + n).toList();
        return new Scene("Demo.run", said, new CodeView("Demo.java", 1, lines, 20, 20));
    }

    @Test
    @DisplayName("the sentence is up before the voice has finished saying it")
    void textLeadsTheVoice() {
        Scene scene = scene("A sentence of some length that takes a while to say.");
        Shot early = Motion.at(scene, 0.1, 4, 0, 0, 0);
        Shot late = Motion.at(scene, 3.2, 4, 0, 0, 0);
        assertTrue(early.typed() < scene.said().length(), "the whole sentence arrived at once");
        assertEquals(scene.said().length(), late.typed(), "the voice outran the words");
    }

    @Test
    @DisplayName("the highlight fades in rather than snapping on, and is there well before the end")
    void highlightArrivesEarly() {
        Scene scene = scene("Answers with nothing.");
        assertTrue(Motion.at(scene, 0.0, 3, 0, 0, 0).highlight() < 0.1, "it snapped on");
        assertEquals(1.0, Motion.at(scene, 0.5, 3, 0, 0, 0).highlight(), 0.001);
    }

    @Test
    @DisplayName("the code slides from where the last scene left it, and settles")
    void codeSlides() {
        Scene scene = scene("Answers with nothing.");
        assertEquals(4.0, Motion.at(scene, 0.0, 3, 4, 12, 0).scroll(), 0.001, "it did not start where it was");
        double moving = Motion.at(scene, 0.2, 3, 4, 12, 0).scroll();
        assertTrue(moving > 4 && moving < 12, "it jumped rather than slid: " + moving);
        assertEquals(12.0, Motion.at(scene, 1.0, 3, 4, 12, 0).scroll(), 0.001, "it never settled");
    }

    @Test
    @DisplayName("a scene that stays put does not drift")
    void stillScenesAreStill() {
        Scene scene = scene("Answers with nothing.");
        assertEquals(12.0, Motion.at(scene, 0.05, 3, 12, 12, 0).scroll(), 0.001);
    }

    @Test
    @DisplayName("a title card fades in; a code scene has no card to fade")
    void titleCards() {
        Scene card = Scene.spoken("Forge Report", "What a person reads and hears.");
        assertTrue(Motion.at(card, 0.3, 3, 0, 0, 0).title() > 0.5);
        assertEquals(0.0, Motion.at(scene("Answers."), 0.3, 3, 0, 0, 0).title(), 0.001);
    }
}
