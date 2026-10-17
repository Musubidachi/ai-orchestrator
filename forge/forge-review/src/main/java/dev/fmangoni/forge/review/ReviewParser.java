package dev.fmangoni.forge.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fmangoni.forge.json.JsonText;
import dev.fmangoni.forge.review.model.Review;
import lombok.experimental.UtilityClass;

/**
 * Extracts the review JSON from whatever the reviewing model actually replied with.
 *
 * <p>Models asked for "JSON only" frequently wrap it in a fenced code block or add a sentence of
 * preamble. Rather than tightening the prompt and hoping, this scans for the first balanced JSON
 * object in the reply, which handles every one of those shapes.
 *
 * <p>An unparseable reply is recorded, never thrown. The review is advisory: a reviewer that
 * rambles should degrade the report, not park a project whose gate genuinely passed.
 */
@UtilityClass
public class ReviewParser {

    private final ObjectMapper MAPPER = new ObjectMapper();

    public Review parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return Review.failed("the reviewer returned nothing");
        }
        String json = JsonText.firstObject(reply);
        if (json == null) {
            return Review.failed("no JSON object found in the reviewer's reply");
        }
        try {
            return MAPPER.readValue(json, Review.class);
        } catch (Exception e) {
            return Review.failed("reviewer returned malformed JSON: " + e.getMessage());
        }
    }

}
