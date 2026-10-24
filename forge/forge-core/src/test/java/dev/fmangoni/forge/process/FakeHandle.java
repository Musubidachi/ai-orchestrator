package dev.fmangoni.forge.process;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * A process that exists only on paper, for saying exactly who claims whom as a parent.
 *
 * <p>{@link #children()} answers the way the operating system does: by parent id alone, whenever
 * each one started. That is the half of the picture a real process cannot be made to show on
 * demand, since it takes thousands of spawns before a process id comes round again.
 */
final class FakeHandle implements ProcessHandle, ProcessHandle.Info {

    private final long pid;
    private final Instant started;
    private final List<FakeHandle> claimed = new ArrayList<>();
    private boolean killed;
    private int refusals;
    private RuntimeException failure;
    private int looks;

    /** @param started when it started, or null for a process that will not say */
    FakeHandle(long pid, Instant started) {
        this.pid = pid;
        this.started = started;
    }

    /** Records {@code child} as having this process's id for a parent, and returns this. */
    FakeHandle claims(FakeHandle child) {
        claimed.add(child);
        return this;
    }

    /**
     * Refuses the next {@code times} looks at its children the way Windows does when it cannot
     * take a process snapshot, which the JDK reports as a bare RuntimeException.
     */
    FakeHandle refuses(int times) {
        refusals = times;
        return this;
    }

    /** Fails every look at its children with {@code failure}, which is not a refused snapshot. */
    FakeHandle failsWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    /** How many times its children were asked for. */
    int looks() {
        return looks;
    }

    boolean killed() {
        return killed;
    }

    @Override
    public long pid() {
        return pid;
    }

    @Override
    public Optional<ProcessHandle> parent() {
        return Optional.empty();
    }

    @Override
    public Stream<ProcessHandle> children() {
        looks++;
        if (failure != null) {
            throw failure;
        }
        if (refusals > 0) {
            refusals--;
            throw new RuntimeException("snapshot not available");
        }
        return claimed.stream().map(ProcessHandle.class::cast);
    }

    @Override
    public Stream<ProcessHandle> descendants() {
        throw new UnsupportedOperationException("nothing in Forge may ask for this; see ProcessTree");
    }

    @Override
    public Info info() {
        return this;
    }

    @Override
    public CompletableFuture<ProcessHandle> onExit() {
        return CompletableFuture.completedFuture(this);
    }

    @Override
    public boolean supportsNormalTermination() {
        return false;
    }

    @Override
    public boolean destroy() {
        return destroyForcibly();
    }

    @Override
    public boolean destroyForcibly() {
        killed = true;
        return true;
    }

    @Override
    public boolean isAlive() {
        return !killed;
    }

    @Override
    public int compareTo(ProcessHandle other) {
        return Long.compare(pid, other.pid());
    }

    @Override
    public Optional<String> command() {
        return Optional.empty();
    }

    @Override
    public Optional<String> commandLine() {
        return Optional.empty();
    }

    @Override
    public Optional<String[]> arguments() {
        return Optional.empty();
    }

    @Override
    public Optional<Instant> startInstant() {
        return Optional.ofNullable(started);
    }

    @Override
    public Optional<Duration> totalCpuDuration() {
        return Optional.empty();
    }

    @Override
    public Optional<String> user() {
        return Optional.empty();
    }
}
