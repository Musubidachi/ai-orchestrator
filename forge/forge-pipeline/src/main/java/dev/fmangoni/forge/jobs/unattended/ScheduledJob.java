package dev.fmangoni.forge.jobs.unattended;

import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.Wishlist;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;

record ScheduledJob(Spec spec, RunPaths paths, RunState state, Wishlist wishlist) {
}
