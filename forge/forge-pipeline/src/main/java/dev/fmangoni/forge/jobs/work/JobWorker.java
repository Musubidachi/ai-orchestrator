package dev.fmangoni.forge.jobs.work;

















/**
 * One project in flight, registered the moment it is launched rather than when its thread
 * gets round to starting.
 *
 * <p>It used to be the thread itself, put into the running map as the first line the pool
 * thread ran. Everything that asks "is this running" or "is there room" therefore saw nothing
 * between the submit and that line: {@code fillCapacity} checks the cap inside a loop that
 * launches, so a single pass could start every eligible project whatever the cap said, and a
 * second click on Start could put two orchestrators on one repository.
 */
public final class JobWorker {
    public volatile Thread thread;
    public volatile boolean stopped;

    /**
     * What a button started, such as {@code design options}, or blank for a project's pipeline.
     *
     * <p>The two stop differently. A pipeline stops itself between steps while Forge is draining;
     * a button job has no steps to stop between and runs to the end, so a shutdown has to wait for
     * it or kill it in the middle of a vendor call.
     */
    public final String job;

    public JobWorker(String job) {
        this.job = job == null ? "" : job;
    }
}
