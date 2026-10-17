package dev.fmangoni.forge.report.deck;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** A feature nobody has ruled on yet, with its recording when it has one. */
@Value
@Builder
public class FeatureCard {

    private final String id;

    private final String name;

    /** What a recording of it should show, in the agent's words. */
    private final String demo;

    /** Where the recording is served from, or blank when there is none to watch. */
    private final String clip;

    /** {@code video}, {@code image} for an animated GIF, or blank. */
    private final String clipKind;

    /** How far to trust the clip, or why there is none. */
    private final String clipNote;

    private final List<String> notes;
}
