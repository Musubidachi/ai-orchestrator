package dev.fmangoni.forge.agent.model;

/**
 * How an agent invocation ended.
 *
 * <p>The distinction that matters most is between {@link #FAILED} and everything above it:
 * a rate limit, a missing login or a timeout say nothing about the code the agent produced, so
 * treating them as build failures would burn repair rounds on a problem no amount of repair fixes.
 *
 * <p>No outcome costs a repair round by itself. A round is spent when the gate fails on work a turn
 * left behind, and only a turn that did something reaches the gate: {@link #COMPLETED},
 * {@link #TIMED_OUT}, and a {@link #FAILED} turn that changed files. A failed turn that changed
 * nothing is retried without charging anything, and handed to a person if it keeps happening.
 */
public enum AgentOutcome {

    /** The agent ran to completion. Says nothing about whether the work is correct — that is the gate's call. */
    COMPLETED,

    /** Subscription quota exhausted. The run suspends and resumes in a later window. */
    RATE_LIMITED,

    /** The CLI has no valid session. Needs a human to log in; the project waits for one. */
    NOT_AUTHENTICATED,

    /**
     * Exceeded its wall-clock cap and was killed. Whatever it wrote so far is still committed and
     * gated, and the turn counts as a session.
     */
    TIMED_OUT,

    /**
     * The CLI ran, but its own sandbox refused to start any process, so nothing could be read or
     * written. Needs a person: no amount of retrying fixes a broken harness, and the project waits
     * for one.
     *
     * <p>The turn still exits cleanly and the model still writes a summary, which is what makes
     * this worth naming. A project spent seven turns here explaining, each time, that it had been
     * unable to touch its own repository — and every one of those counted as work, because from
     * the outside the turn looked exactly like a turn that had failed on the merits.
     */
    TOOL_BROKEN,

    /**
     * Ran, and genuinely failed. With changes on disk it goes to the gate like any other turn;
     * without them it is retried and eventually handed to a person.
     */
    FAILED
}
