package dev.fmangoni.forge.design;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.agent.model.AgentResult;
import dev.fmangoni.forge.agent.model.AgentTask;
import dev.fmangoni.forge.process.ProcessRegistry;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Asks every vendor for a few looks, and hands the chosen one to the project.
 *
 * <p>Every vendor that is installed, not only the one that built the project: a choice between
 * three ideas from one model is narrower than it looks, and the vendors do not draw alike. Each
 * works in {@code runs/<id>/design/<VENDOR>}, beside the repository and never in it, so nothing a
 * designer writes can change the product. A vendor that is out of quota, or offers nothing usable,
 * is named on the board and the rest go ahead.
 *
 * <p>The board is saved as each vendor answers, so a page watching it shows the options as they
 * arrive rather than nothing for fifteen minutes and then all of them.
 */
public final class DesignStudio {

    /** Where the chosen mock-up is put in the repository, for the building agent to open. */
    public static final String CHOSEN_IN_REPO = "artifacts/polish-design.html";

    /**
     * Where the building agent is asked to save a picture of the program before and after, relative
     * to the repository. Here because both the request that asks for them and the report that shows
     * them need the same two names.
     */
    public static final String PICTURE_BEFORE = "artifacts/polish-before.png";
    public static final String PICTURE_AFTER = "artifacts/polish-after.png";

    static final int PER_VENDOR = 2;

    private static final Duration CAP = Duration.ofMinutes(15);

    /** How long a stopped request waits for its vendors' threads to notice. */
    private static final Duration UNWIND = Duration.ofSeconds(30);

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final Agents agents;

    public DesignStudio(Agents agents) {
        this.agents = agents;
    }

    /**
     * Asks every vendor at once, each on a thread of its own, and waits for all of them.
     *
     * <p>At once because each works in its own folder and each is throttled by its own vendor's
     * limit, so nothing is gained by queueing them, and one after another made the slowest request
     * take three fifteen-minute turns end to end. A vendor the quota ledger already knows to be
     * closed is not called: it would only be refused, and saying so on the board is more use.
     *
     * <p>Interrupted — Stop on the dashboard, or a shutdown — every vendor's process is killed and
     * the board says it was cut short. Left saying "asking", the page would wait for ever.
     *
     * @param note   what the person said they wanted, or blank
     * @param digest what the builder last wrote about the project, or blank
     */
    public DesignBoard ask(Spec spec, RunPaths paths, String note, String digest) {
        return ask(spec, paths, note, digest, "");
    }

    /**
     * Asks every vendor for more like one design already on the board: that page, with only what
     * the person asked to change changed.
     *
     * <p>Asking used to start from nothing, with the note as the only thing carried over. "I like
     * the Bright Panel, vary the artwork" reached the designers as a sentence about a page they
     * could not see, they drew new looks from scratch, and the page that was liked was deleted with
     * the rest of the board. Now it is kept on the new board as {@link DesignBoard#LIKED}, where it
     * can still be picked, and every designer is handed it and told to keep it.
     *
     * @param base the id of the design to start from, or blank to start from nothing
     */
    public DesignBoard ask(Spec spec, RunPaths paths, String note, String digest, String base) {
        return ask(spec, paths, note, digest, base, "");
    }

    /**
     * Asks for extras rather than replacements: themes, variants of one thing, or animations, each
     * drawn on top of the look the project has now, which stays.
     *
     * @param extra the kind of extra ({@link DesignBoard#THEME}, {@link DesignBoard#VARIANTS} or
     *              {@link DesignBoard#ANIMATION}), or blank for looks that replace the current one
     */
    public DesignBoard ask(Spec spec, RunPaths paths, String note, String digest, String base, String extra) {
        String extraKind = DesignBoard.isExtra(extra) ? extra : "";
        // Read before the folder is cleared: the page it names is in that folder.
        Optional<DesignOption> start = startingPoint(paths, base);
        String likedPage = start.map(option -> read(paths.root().resolve(option.file()))).orElse(null);
        clear(paths.designDir());
        DesignOption kept = likedPage == null ? null : keep(paths, start.get(), likedPage);
        String wanted = note == null ? "" : note.strip();
        DesignRound round = new DesignRound(Instant.now(), wanted, paths.designBoardFile(), kept, extraKind);

        List<Thread> drawers = new ArrayList<>();
        for (AgentKind kind : agents.kinds()) {
            Optional<Instant> closed = agents.closedUntil(kind);
            if (closed.isPresent()) {
                round.refuse(kind, kind + " is out of quota until " + WHEN.format(closed.get()) + ", so it was not asked");
                continue;
            }
            drawers.add(Thread.ofPlatform().name("design-" + kind).daemon()
                    .start(() -> draw(kind, spec, paths, wanted, digest, kept, extraKind, round)));
        }
        try {
            for (Thread drawer : drawers) {
                drawer.join();
            }
        } catch (InterruptedException e) {
            for (Thread drawer : drawers) {
                ProcessRegistry.killWhatever(drawer);
                drawer.interrupt();
            }
            // Their processes are gone; let their threads unwind, so nothing is written after this.
            unwind(drawers);
            Thread.currentThread().interrupt();
            return round.interrupt("stopped before every model had answered");
        }
        return round.finish();
    }

    /** Waits a little for threads whose processes were just killed; the board ignores any later. */
    private static void unwind(List<Thread> drawers) {
        long until = System.nanoTime() + UNWIND.toNanos();
        for (Thread drawer : drawers) {
            try {
                drawer.join(Math.max(1, (until - System.nanoTime()) / 1_000_000));
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * One vendor's turn: its folder, its copy of the project, its pages, and what survived the check.
     *
     * @param liked the design to start from, already kept on the board, or null
     * @param extra the kind of extra asked for, or blank
     */
    private void draw(AgentKind kind, Spec spec, RunPaths paths, String wanted, String digest, DesignOption liked,
                      String extra, DesignRound round) {
        try {
            Path studio = paths.designDir().resolve(kind.name());
            create(studio);
            References.copy(paths, studio.resolve("reference"));
            if (liked != null) {
                Path reference = Files.createDirectories(studio.resolve("reference"));
                Files.copy(paths.root().resolve(liked.file()), reference.resolve(DesignBrief.LIKED_PAGE),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            AgentResult result = agents.get(kind).run(AgentTask.writable(studio,
                    DesignBrief.of(spec, wanted, digest, PER_VENDOR, liked, extra), CAP, paths.logs()).using(spec.modelFor(kind)));
            if (result.blockedExternally()) {
                round.refuse(kind, kind + " could not be asked: " + result.outcome());
                return;
            }
            DesignCheck check = new DesignCheck(studio, kind, paths.root());
            List<DesignOption> made = check.usable(PER_VENDOR);
            round.offer(kind, made, check.dropped());
            if (made.isEmpty() && check.dropped().isEmpty()) {
                round.refuse(kind, kind + " offered nothing");
            }
        } catch (IOException | RuntimeException e) {
            // One vendor failing is that vendor's line on the board, not the end of the request.
            round.refuse(kind, kind + " failed: " + e.getMessage());
        }
    }

    /**
     * The design on the board that a request starts from, if the id names one whose page is there.
     *
     * <p>Only a page inside the design folder, as for {@link #choose}: the board is a file on disk,
     * and the path in it is not trusted to stay inside the run.
     */
    public static Optional<DesignOption> startingPoint(RunPaths paths, String id) {
        DesignBoard board = id == null || id.isBlank() ? null : DesignBoard.load(paths.designBoardFile());
        return (board == null ? Optional.<DesignOption>empty() : board.option(id)).filter(option -> {
            Path page = paths.root().resolve(option.file()).normalize();
            return page.startsWith(paths.designDir().normalize()) && Files.isRegularFile(page);
        });
    }

    /** Puts the liked page back after the folder was cleared, and the option that now points at it. */
    private static DesignOption keep(RunPaths paths, DesignOption liked, String page) {
        try {
            Files.createDirectories(paths.designDir());
            Files.writeString(paths.designDir().resolve(DesignBrief.LIKED_PAGE), page);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot keep the liked design in " + paths.designDir(), e);
        }
        return new DesignOption(DesignBoard.LIKED, liked.vendor(), liked.name(), liked.pitch(),
                paths.root().relativize(paths.designDir().resolve(DesignBrief.LIKED_PAGE)).toString().replace('\\', '/'));
    }

    private static String read(Path page) {
        try {
            return Files.readString(page);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the liked design " + page, e);
        }
    }

    /**
     * Marks an option as the one to build and puts its mock-up in the repository.
     *
     * <p>Into the repository because that is the only place the building agent can read. One file,
     * at one fixed path, which the next choice overwrites. The choice is also recorded apart from the
     * board ({@link PolishRecord}), because the next request for designs empties the board.
     *
     * @param note what the person added, or blank
     * @return the option, or empty when there is no such option on the board
     */
    public static Optional<DesignOption> choose(RunPaths paths, String optionId, String note) {
        DesignBoard board = DesignBoard.load(paths.designBoardFile());
        Optional<DesignOption> picked = board == null ? Optional.empty() : board.option(optionId);
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        Path mockup = paths.root().resolve(picked.get().file()).normalize();
        if (!mockup.startsWith(paths.designDir().normalize()) || !Files.isRegularFile(mockup)) {
            return Optional.empty();
        }
        try {
            Path target = paths.repo().resolve(CHOSEN_IN_REPO);
            Files.createDirectories(target.getParent());
            Files.copy(mockup, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot put the chosen design in " + paths.repo(), e);
        }
        DesignBoard chosen = board.choosing(optionId, note);
        chosen.save(paths.designBoardFile());
        new PolishRecord(picked.get(), chosen.chosenAt()).save(paths.polishFile());
        return picked;
    }

    /**
     * Records a picked design without building it, for a project that is not finished yet.
     *
     * <p>Nothing goes into the repository. A file put there now would be committed by whatever
     * session runs next, as part of work that has nothing to do with it. What the person added is
     * kept with the pick, for when it is built.
     *
     * @param note what the person added, or blank
     */
    public static Optional<DesignOption> pick(RunPaths paths, String optionId, String note) {
        DesignBoard board = DesignBoard.load(paths.designBoardFile());
        Optional<DesignOption> picked = board == null ? Optional.empty() : board.option(optionId);
        picked.ifPresent(option -> board.choosing(optionId, note).save(paths.designBoardFile()));
        return picked;
    }

    /**
     * The design picked and not yet built, if there is one.
     *
     * <p>A pick that was built has a {@link PolishRecord} with the same option and moment; one kept
     * for later does not, or has a record of an earlier build.
     */
    public static Optional<DesignOption> keptForLater(RunPaths paths) {
        DesignBoard board = DesignBoard.load(paths.designBoardFile());
        if (board == null || board.chosen() == null || board.chosen().isBlank()) {
            return Optional.empty();
        }
        PolishRecord built = PolishRecord.load(paths.polishFile());
        boolean wasBuilt = built != null && built.option() != null
                && board.chosen().equals(built.option().id())
                && Objects.equals(board.chosenAt(), built.chosenAt());
        return wasBuilt ? Optional.empty() : board.chosenOption();
    }

    private static void create(Path studio) {
        try {
            Files.createDirectories(studio);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + studio, e);
        }
    }

    /** Empties the design folder: a mock-up left from last time is not an answer to this request. */
    private static void clear(Path dir) {
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                deleteTree(dir);
            }
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot clear " + dir, e);
        }
    }

    /** Link-safe: a junction is removed as itself, never followed. */
    private static void deleteTree(Path path) throws IOException {
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
            try (var children = Files.list(path)) {
                for (Path child : children.toList()) {
                    deleteTree(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
