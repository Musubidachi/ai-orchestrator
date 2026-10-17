package dev.fmangoni.forge.status.model;

import dev.fmangoni.forge.project.DiskUsage;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.TimingTrend;
import dev.fmangoni.forge.state.Timings;
import dev.fmangoni.forge.status.read.Seconds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Value;

/** What the project has taken: time, attempts, disk and tokens. */
@Value
@Builder
public class StatusBudget {

    /**
    * Wall-clock seconds from creation to now, or to the finish for a project that is done.
    *
    * <p>The age of the project, and only shown as such. It used to be labelled "working
    * for", which made it a claim about effort that it could not support: nothing freezes
    * it while a project sits suspended on quota or waits for a person, so it counted the
    * night as work.
    */
    private final long totalSeconds;

    /** Seconds actually spent stepping this project — agent turns, gate, review. */
    private final long workedSeconds;

    /**
    * Seconds since anything about this project changed on disk.
    *
    * <p>Read as staleness, not progress. While a turn is in flight this is time in the
    * current step, and while nothing is running it is how long the project has been
    * sitting — which is the more useful of the two, since a job stalled for six hours is
    * worth spotting and no other number on the card says so.
    */
    private final long idleSeconds;

    private final int repairRounds;

    private final int maxRepairRounds;

    /** Agent turns taken, across every run and every process. */
    private final int sessions;

    /** Times the vendor conversation was dropped and restarted from the digest. */
    private final int compactions;

    /** Tokens its build turns have moved; see {@link RunState#getTokensSpent()}. */
    private final long tokensSpent;

    private final int commits;

    private final long diskBytes;

    /** Seconds the most recent gate's commands took between them. */
    private final long gateSeconds;

    /**
    * Acceptance commands running well past their own history, as a person would read it.
    *
    * <p>The report has always listed these; the card did not, so a test suite that had
    * crept from forty seconds to three minutes was only visible to someone who opened the
    * report looking for it. See {@link TimingTrend#slowerThanUsual()} for the rule.
    */
    private final List<String> slowerThanUsual;

    public static StatusBudget of(RunState state, RunPaths paths, int maxRepairRounds, Instant now) {
        Instant finished = state.getPhase().terminal() && state.getUpdatedAt() != null ? state.getUpdatedAt() : now;
        List<TimingTrend> trends = Timings.of(state);
        return builder()
                .totalSeconds(Seconds.between(state.getCreatedAt(), finished))
                .workedSeconds(state.getWorkedMillis() / 1000)
                .idleSeconds(Seconds.between(state.getUpdatedAt(), now))
                .repairRounds(state.getRepairRounds())
                .maxRepairRounds(maxRepairRounds)
                .sessions(state.getSessions())
                .compactions(state.getCompactions())
                .tokensSpent(state.getTokensSpent())
                .commits(countCommits(paths))
                // Per project rather than only as a total: generated projects pull down dependency
                // trees and browser bundles, and on a nearly-full disk it matters which one is
                // responsible. Cached and link-safe; see DiskUsage for what a plain walk cost.
                .diskBytes(DiskUsage.of(paths.root()))
                .gateSeconds(trends.stream().mapToLong(TimingTrend::latest).sum())
                .slowerThanUsual(trends.stream()
                        .filter(TimingTrend::slowerThanUsual)
                        .map(trend -> trend.command() + " took " + trend.latest() + "s, usually " + trend.usual() + "s")
                        .toList())
                .build();
    }

    public static StatusBudget queued(int maxRepairRounds) {
        return builder().maxRepairRounds(maxRepairRounds).slowerThanUsual(List.of()).build();
    }

    private static int countCommits(RunPaths paths) {
        Path head = paths.repo().resolve(".git/logs/HEAD");
        if (!Files.isRegularFile(head)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(head)) {
            return (int) lines.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
