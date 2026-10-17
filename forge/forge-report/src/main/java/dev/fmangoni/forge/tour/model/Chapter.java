package dev.fmangoni.forge.tour.model;

import java.util.List;

/**
 * A run of scenes about one thing: a module, a class, or the closing summary.
 *
 * <p>Chapters are what make a long tour usable. Nobody watches a codebase end to end, but a person
 * looking for how the gate decides will watch the four minutes about the gate, so every chapter is
 * something you can jump to and leave from.
 */
public record Chapter(String title, String subtitle, List<Scene> scenes) {

    public double seconds() {
        return scenes.stream().mapToDouble(Scene::seconds).sum();
    }
}
