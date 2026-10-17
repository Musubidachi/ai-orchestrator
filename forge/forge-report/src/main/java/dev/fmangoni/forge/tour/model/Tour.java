package dev.fmangoni.forge.tour.model;

import java.util.List;

/**
 * A whole narrated tour of a codebase, in the order it is meant to be heard.
 *
 * <p>Deliberately just data. What the tour is gets decided once, from the source; a page, a set of
 * sound files and a video are then three ways of playing the same thing, and none of them gets to
 * decide what it says.
 */
public record Tour(String title, String subtitle, List<Chapter> chapters) {

    public List<Scene> scenes() {
        return chapters.stream().flatMap(chapter -> chapter.scenes().stream()).toList();
    }

    public double seconds() {
        return chapters.stream().mapToDouble(Chapter::seconds).sum();
    }
}
