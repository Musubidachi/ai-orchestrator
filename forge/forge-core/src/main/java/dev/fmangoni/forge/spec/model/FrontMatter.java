package dev.fmangoni.forge.spec.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;
import lombok.Data;

/**
 * The YAML block at the top of a wishlist entry — the machine-readable half of a spec.
 *
 * <p>Written by hand, so field names are snake_case and every field with a sensible default has
 * one. The exception is {@link #acceptance}: it has no default on purpose, because a project with
 * no executable definition of done is precisely the kind this pipeline cannot verify.
 */
@Data
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class FrontMatter {

    private String id;
    private String title;

    /**
     * The id of the project this improves, or blank for a project in its own right.
     *
     * <p>A sub-spec is a change request against work that already exists: it runs in the parent's
     * repository, against the parent's code, and is only eligible once the parent is accepted.
     */
    private String parent = "";

    /**
     * What to work on first. Lower runs earlier; the default leaves room either side.
     *
     * <p>Governs the order work is <em>started</em> in, not preemption. A running turn is never
     * interrupted by a higher-priority arrival, because the quota already spent on it would be
     * thrown away and the project left mid-edit for nothing.
     */
    private int priority = 50;

    /** {@code claude}, {@code codex}, or {@code auto} to let Forge balance the queue. */
    private String assign = "auto";

    /** Hint for the scaffold, e.g. {@code java} or {@code node}. */
    private String stack = "java";

    /**
     * Which model each vendor uses for this spec; blank leaves the CLI's own default.
     *
     * <p>Per spec rather than global because the projects differ in kind: a throwaway script and a
     * code generator do not warrant the same model, and paying the largest tier for the former
     * spends a quota window that the latter needed.
     */
    private String claudeModel = "";

    private String codexModel = "";

    private String antigravityModel = "";

    /** Commands Forge runs itself to decide whether the project is done. */
    private List<String> acceptance = List.of();

    /** Globs copied into the run's evidence directory after a passing gate. */
    private List<String> evidence = List.of();

    private int maxRepairRounds = 4;

    /**
     * Total agent turns allowed before the project parks, however they were spent.
     *
     * <p>Separate from {@code maxRepairRounds}: a large project legitimately needs many building
     * turns, and should not park merely for being large. This is the backstop against a project
     * that keeps claiming progress forever.
     */
    private int maxSessions = 12;

    /**
     * Sessions to run on one vendor conversation before dropping it and starting fresh.
     *
     * <p>A resumed turn re-sends the whole transcript, so on a long project most of what each call
     * pays for is history it has already acted on. Restarting from the agent's own digest keeps the
     * knowledge and discards the replay. Set to 0 to keep one conversation for the whole project.
     *
     * <p>Three is a judgement, not a measurement: long enough that short projects never compact at
     * all, short enough that a transcript does not reach the megabytes seen here before it is
     * traded for a page of notes.
     */
    private int compactAfterSessions = 3;

    /** Wall-clock cap for a single agent turn. */
    private int agentTimeoutMinutes = 30;

    /** Wall-clock cap for a single acceptance command. */
    private int gateTimeoutMinutes = 20;

    /**
     * How to run the built project locally, with {@code {port}} where the port goes.
     *
     * <p>Forge picks the port, always on loopback, and runs this to probe the app for security
     * problems and to take screenshots of it. Blank for a project with nothing to serve. The
     * placeholder is required rather than optional: a server on a port of its own choosing is one
     * Forge cannot find, and might be one another project is already using.
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String serve = "";

    /** The page of the served app to probe and screenshot; the root when blank. */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private String servePath = "";

    /** How to publish the project, or null for one that is never deployed. See {@link DeploySettings}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DeploySettings deploy;

    /** Ads inside the built app, or null for none. See {@link MonetizationSettings}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private MonetizationSettings monetization;

}
