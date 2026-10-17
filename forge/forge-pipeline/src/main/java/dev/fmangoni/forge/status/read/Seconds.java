package dev.fmangoni.forge.status.read;

import java.time.Duration;
import java.time.Instant;
import lombok.experimental.UtilityClass;

/** Whole seconds between two instants, or 0 when either is unknown. */
@UtilityClass
public class Seconds {

    public long between(Instant from, Instant to) {
        return from == null || to == null ? 0 : Duration.between(from, to).toSeconds();
    }
}
