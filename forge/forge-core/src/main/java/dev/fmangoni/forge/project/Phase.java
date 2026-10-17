package dev.fmangoni.forge.project;

















/**
 * Where a project sits in the pipeline.
 *
 * <p>Persisted by name in {@code state.json}, so renaming a constant breaks resumption of runs
 * already on disk.
 */
public enum Phase {

    NEW,

    /** Workspace committed; the initial build turn is next. */
    SCAFFOLDED,

    /** Code on disk awaits the acceptance commands. */
    GATING,

    /** The gate failed and a repair turn is next; {@code lastFailure} says what broke. */
    REPAIRING,

    /**
     * The gate failed because the project is not finished yet, not because it is broken.
     *
     * <p>Kept separate from {@link #REPAIRING} so that needing more time does not spend the budget
     * reserved for getting unstuck.
     */
    CONTINUING,

    /** The gate passed and the other model has yet to review the diff. */
    REVIEWING,

    /** The review raised blocking findings and one repair turn is owed. */
    REVIEW_REPAIR,

    /**
     * Passed its acceptance commands and cleared review — and is now waiting for you.
     *
     * <p>Terminal for the pipeline, not for the project. It means every check Forge knows how to
     * run is satisfied, which is a weaker claim than "this is what I wanted": the commands only
     * check what the spec thought to check.
     */
    DONE,

    /** You looked at it and agreed. The only phase that means a person signed off. */
    ACCEPTED,

    /** Exhausted its repair rounds. An honest failure, reported as one. */
    PARKED,

    /** Blocked on quota or login. Resumes untouched in a later window. */
    SUSPENDED,

    /**
     * The gate passed; secrets, dependencies, risky code, the running app and another model's
     * security reading are checked before the cross-review.
     *
     * <p>Appended rather than placed beside {@link #GATING}: phases are persisted by name, and adding
     * at the end is the only change that can never alter what an existing state file means.
     */
    SECURITY_CHECK,

    /** The security check found something serious and the builder's one repair turn is next. */
    SECURITY_REPAIR,

    /**
     * The best model the building vendor has is deciding how the project will be built.
     *
     * <p>Sits between NEW and SCAFFOLDED in the run's order, and at the end of this enum, because
     * phases are persisted by name and appending is the only change that cannot alter what an
     * existing state file means. A run already past it never enters it.
     */
    PLANNING;

    public boolean terminal() {
        return this == DONE || this == ACCEPTED || this == PARKED;
    }
}
