package dev.fmangoni.forge.pictures.batch;

import java.time.Instant;

/**
 * A picture on disk, as Forge tells one drawing from the next.
 *
 * @param stamp    its time and size: a new drawing saved over the last has a new stamp
 * @param modified when it was last written
 */
public record Draft(String stamp, Instant modified) {
}
