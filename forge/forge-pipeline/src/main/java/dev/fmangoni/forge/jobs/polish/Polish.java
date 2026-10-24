package dev.fmangoni.forge.jobs.polish;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.design.DesignBoard;
import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.design.DesignStudio;
import dev.fmangoni.forge.gate.LookCheck;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.jobs.button.ButtonJob;
import dev.fmangoni.forge.jobs.queue.PipelineJobs;
import dev.fmangoni.forge.jobs.stop.Shutdown;
import dev.fmangoni.forge.jobs.work.JobPool;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import dev.fmangoni.forge.state.RunStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Optional;

/**
 * Suggesting an updated design for a project: asking the vendors for looks, and building the one
 * that was picked.
 *
 * <p>Two presses, on purpose. The first spends a call to each vendor and changes nothing: it fills
 * a board with mock-ups. The second is the decision, and only then does anything happen to the
 * project, with the chosen picture in its repository and a request that is the person's own words.
 *
 * <p>Asking is offered on any project that has run and is not being worked on: it reads the project
 * and writes nothing into it. What building does depends on where the project is:
 * <ul>
 *   <li><b>DONE</b>: an ordinary session, sent back with the design. The gate and the security
 *       check follow, and the review is decided by how much the polish actually changed.</li>
 *   <li><b>ACCEPTED</b>: an enhancement, {@code <id>.<n>}, with the design as its brief. The
 *       acceptance stands; changing an accepted project is what an enhancement is for.</li>
 *   <li><b>Anything else</b>: the pick is kept, and can be built once the project finishes. A
 *       restyle sent into a project that is still being built, or is stuck on something else,
 *       would only get in the way of what it actually needs.</li>
 * </ul>
 */
public final class Polish {

    /** What pressing Build does, as the page is told it. */
    public static final String NOW = "session";
    public static final String ENHANCEMENT = "enhancement";
    public static final String LATER = "later";

    private final Projects projects;
    private final JobPool pool;
    private final Shutdown shutdown;
    private final Agents agents;
    private final PipelineJobs pipeline;

    public Polish(Projects projects, JobPool pool, Shutdown shutdown, Agents agents, PipelineJobs pipeline) {
        this.projects = projects;
        this.pool = pool;
        this.shutdown = shutdown;
        this.agents = agents;
        this.pipeline = pipeline;
    }

    /**
     * What the page shows for a project, or empty when there is no such project.
     *
     * <p>A board still saying {@code asking} with nothing running is one whose request was cut off —
     * the editor stopped mid-request, or the job died — and is reported as interrupted. Shown as it
     * was, the page waited for ever and would not let another request be made.
     */
    public Optional<PolishView> view(String id) {
        return projects.find(id).map(project -> {
            boolean running = pool.isRunning(id);
            DesignBoard board = DesignBoard.load(project.paths().designBoardFile());
            if (board != null && DesignBoard.ASKING.equals(board.state()) && !running) {
                board = board.interrupted();
            }
            return new PolishView(id, project.spec().title(), running, blocked(id), buildAs(project),
                    DesignStudio.keptForLater(project.paths()).isPresent(), board);
        });
    }

    /** Why designs cannot be suggested or built for this project now, or blank when they can. */
    public String blocked(String id) {
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            return Projects.unknown(id);
        }
        if (pool.isRunning(id)) {
            return "it is being worked on; ask once that stops";
        }
        return RunStore.survey(found.get().paths().stateFile()).isEmpty() ? "it has not run yet" : "";
    }

    /** What pressing Build would do: {@link #NOW}, {@link #ENHANCEMENT} or {@link #LATER}. */
    private static String buildAs(Project project) {
        Phase phase = RunStore.survey(project.paths().stateFile()).map(RunState::getPhase).orElse(null);
        return phase == Phase.DONE ? NOW : phase == Phase.ACCEPTED ? ENHANCEMENT : LATER;
    }

    /**
     * Asks every vendor for designs, on a worker.
     *
     * @param note what the person said they wanted, or blank
     * @param base  a design on the board to ask for more like, or blank to start from nothing. It
     *              needs a note: what to change is the whole of such a request.
     * @param extra the kind of extra to draw beside the current look, or blank for looks to replace it
     */
    public String ask(String id, String note, String base, String extra) {
        if (shutdown.isDraining()) {
            return "Forge is shutting down; try again once it has restarted";
        }
        String blocked = blocked(id);
        if (!blocked.isBlank()) {
            return blocked;
        }
        Project project = projects.find(id).orElseThrow();
        RunPaths paths = project.paths();
        String liked = "";
        if (base != null && !base.isBlank()) {
            Optional<DesignOption> start = DesignStudio.startingPoint(paths, base);
            if (start.isEmpty()) {
                return "there is no design " + base + " to start from — ask for designs again";
            }
            if (note == null || note.isBlank()) {
                return "say what to change in \"" + start.get().name() + "\" first: everything else about it is kept";
            }
            liked = " like \"" + start.get().name() + "\"";
        }
        if (agents.kinds().stream().allMatch(kind -> agents.closedUntil(kind).isPresent())) {
            return "every model is out of quota at the moment, so there is nobody to ask";
        }
        ButtonJob.submit(pool, id, "design options", paths, () -> {
            DesignBoard board = new DesignStudio(agents).ask(project.spec(), paths, note, digestOf(paths), base, extra);
            System.out.println("[" + id + "] design options: " + board.options().size() + " on the board"
                    + (board.refused().isEmpty() ? "" : "; " + String.join("; ", board.refused())));
        });
        long open = agents.kinds().stream().filter(kind -> agents.closedUntil(kind).isEmpty()).count();
        long closed = agents.kinds().size() - open;
        String what = DesignBoard.isExtra(extra) ? extra + " designs" : "designs";
        return "asking " + open + (open == 1 ? " model" : " models") + " for " + what + liked
                + (closed == 0 ? "" : "; " + closed + " out of quota and not asked");
    }

    /**
     * Sends the project back to be made to look like the chosen design.
     *
     * @param optionId the option picked from the board, or blank to leave the look to the agent
     * @param note     anything the person wants to add, or blank
     * @param extra    the kind of extra to add the design as, beside the current look; blank replaces
     *                 the look, unless the board's designs were drawn as extras, which are built as such
     */
    public String build(String id, String optionId, String note, String extra) {
        String blocked = blocked(id);
        if (!blocked.isBlank()) {
            return blocked;
        }
        Project project = projects.find(id).orElseThrow();
        RunPaths paths = project.paths();
        RunState state = RunStore.load(paths.stateFile()).orElseThrow();
        String kind = extraKind(paths, extra);
        if (!kind.isBlank() && (optionId == null || optionId.isBlank())) {
            return "pick a design for the " + kind + ": its name is the new pack's name";
        }
        String as = buildAs(project);
        if (LATER.equals(as)) {
            return keep(paths, optionId, note);
        }
        DesignOption chosen = null;
        if (optionId != null && !optionId.isBlank()) {
            chosen = DesignStudio.choose(paths, optionId, note).orElse(null);
            if (chosen == null) {
                return "there is no design " + optionId + " to build — ask for designs again";
            }
        }
        String request = kind.isBlank()
                ? PolishBrief.request(note, chosen)
                : ExtraBrief.request(kind, chosen.name(), note, chosen, hasPackSlot(paths));
        if (kind.isBlank()) {
            // Replacing the look on purpose: the next gate adopts the new one as the default.
            LookCheck.forget(paths.lookReference());
        }
        if (ENHANCEMENT.equals(as)) {
            Spec enhancement = kind.isBlank()
                    ? DesignEnhancement.write(projects.home(), project.wishlist(), project.spec(), chosen, note)
                    : DesignEnhancement.writeExtra(projects.home(), project.wishlist(), project.spec(),
                            chosen.name() + " " + kind, request, chosen.name());
            return pipeline.start(enhancement.id()) + ": added " + enhancement.id() + " \""
                    + enhancement.title() + "\", an enhancement with this design as its brief";
        }
        String waiting = state.getUserFeedback();
        // Anything already waiting to be delivered goes too, rather than being written over.
        state.setUserFeedback(waiting == null || waiting.isBlank() ? request : waiting + "\n\n" + request);
        state.setPhase(Phase.CONTINUING);
        state.setLastError(null);
        state.setRepairRounds(0);
        // Its review loop is its own: 003's Christmas theme inherited four repair turns spent on
        // earlier requests, and was held for a person on its first finding.
        state.setReviewRepairs(0);
        // Where the polish starts from, so its review can ask what it changed and nothing more.
        state.setPolishBase(headOf(paths));
        state.setPolishDesign(chosen == null ? null : chosen.name());
        state.setPolishCheck(null);
        RunStore.save(paths.stateFile(), state);
        return pipeline.start(id);
    }

    /**
     * The kind of extra a build is: what the page said, or else what the board's designs were drawn
     * as. Designs drawn as a theme are built as one even from a page that did not say so.
     */
    private static String extraKind(RunPaths paths, String extra) {
        if (DesignBoard.isExtra(extra)) {
            return extra;
        }
        DesignBoard board = DesignBoard.load(paths.designBoardFile());
        return board != null && DesignBoard.isExtra(board.extra()) ? board.extra() : "";
    }

    /** Whether the project already has a place for packs: it saves its default look, or did. */
    private static boolean hasPackSlot(RunPaths paths) {
        return Files.isRegularFile(paths.repo().resolve(LookCheck.DEFAULT_LOOK))
                || Files.isRegularFile(paths.lookReference());
    }

    /**
     * The commit the polish starts from, or null when there is no history to read it from — in
     * which case nothing is scoped and the review runs in full, as it did before scoping existed.
     */
    private static String headOf(RunPaths paths) {
        try {
            return new Git(paths.repo(), paths.gitLog()).head();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Keeps a pick for a project that is not finished, to be built when it is.
     *
     * <p>With the note. It used to be dropped here, so what someone typed beside the design they
     * kept was gone by the time it was built, and nothing said so.
     */
    private static String keep(RunPaths paths, String optionId, String note) {
        if (optionId == null || optionId.isBlank()) {
            return "it is not finished, so pick a design to keep for it; leaving the look to the agent "
                    + "can wait until it is";
        }
        boolean noted = note != null && !note.isBlank();
        return DesignStudio.pick(paths, optionId, note)
                .map(option -> "kept \"" + option.name() + "\"" + (noted ? " and your note" : "")
                        + " for when it is finished: come back here then to build it")
                .orElse("there is no design " + optionId + " to keep — ask for designs again");
    }

    private static String digestOf(RunPaths paths) {
        try {
            return Files.isRegularFile(paths.digestFile()) ? Files.readString(paths.digestFile()) : "";
        } catch (IOException e) {
            return "";
        }
    }
}
