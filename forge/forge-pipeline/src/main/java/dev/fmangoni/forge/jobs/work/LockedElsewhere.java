package dev.fmangoni.forge.jobs.work;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Projects another Forge process was found working on, and when to look again.
 *
 * <p>In memory, not in the state file, because the state file belongs to the process holding the
 * lock; and short, because that process may finish at any moment. Without it, the slot freed by the
 * refusal would relaunch the same project straight away, and be refused again, in a loop.
 */
public final class LockedElsewhere {

    static final Duration WAIT = Duration.ofMinutes(2);

    private final Map<String, Instant> lookAgain = new ConcurrentHashMap<>();

    public void record(String id) {
        lookAgain.put(id, Instant.now().plus(WAIT));
    }

    public boolean waiting(String id) {
        Instant when = lookAgain.get(id);
        return when != null && Instant.now().isBefore(when);
    }
}
