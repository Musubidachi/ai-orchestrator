package dev.fmangoni.forge.pictures.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * What a person said about one picture.
 *
 * @param draft the stamp of the picture it is about: a new picture needs a ruling of its own
 * @param at    when it was said
 * @param keep  true to keep the picture, false to have it drawn again
 * @param note  for a redraw, what to fix; for a kept picture the check found a problem with, why it
 *              stands; otherwise null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Ruling(String draft, Instant at, boolean keep, String note) {
}
