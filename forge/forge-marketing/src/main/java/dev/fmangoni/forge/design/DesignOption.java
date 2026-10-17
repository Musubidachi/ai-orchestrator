package dev.fmangoni.forge.design;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One look a vendor offered: a mock-up to open, and a few words to choose it by.
 *
 * @param id     which vendor and which of its options, such as {@code CLAUDE-1}
 * @param vendor who drew it
 * @param name   a short name for the look
 * @param pitch  one sentence a person could choose it by
 * @param file   the mock-up, relative to the run's own folder, such as
 *               {@code design/CLAUDE/option-1.html}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DesignOption(String id, String vendor, String name, String pitch, String file) {
}
