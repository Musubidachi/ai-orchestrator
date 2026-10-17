package dev.fmangoni.forge.jobs.work;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;

/** A spec, the wishlist it was read from, and where its run's files are. */
public record Project(Spec spec, Wishlist wishlist, RunPaths paths) {
}
