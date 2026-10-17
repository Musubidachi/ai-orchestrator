package dev.fmangoni.forge.review.decision.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * Something the reader wrote about one outstanding item, kept.
 *
 * <p>A note used to be a single field that the next submission overwrote, which meant the only
 * record of what you thought was whatever you happened to have typed most recently. Keeping them
 * as a list is what makes the report answer the question you actually come back with — "what did I
 * say about this last time" — rather than presenting an input box you have to re-read from scratch.
 *
 * @param text what was said, in the reader's words
 * @param at   when, so a note written before a repair round is distinguishable from one after it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Comment(String text, Instant at) {

    public static Comment now(String text) {
        return new Comment(text == null ? "" : text.strip(), Instant.now());
    }
}
