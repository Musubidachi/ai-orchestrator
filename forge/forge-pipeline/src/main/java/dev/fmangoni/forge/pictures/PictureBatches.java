package dev.fmangoni.forge.pictures;

import dev.fmangoni.forge.pictures.batch.Batch;
import dev.fmangoni.forge.pictures.batch.Batches;
import dev.fmangoni.forge.pictures.batch.RecordStore;
import dev.fmangoni.forge.pictures.view.BatchView;
import dev.fmangoni.forge.pictures.view.Views;
import dev.fmangoni.forge.pictures.work.NextJobs;
import dev.fmangoni.forge.pictures.work.Placing;
import dev.fmangoni.forge.pictures.work.PictureWatch;
import dev.fmangoni.forge.pictures.work.Rulings;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Picture batches: lists of pictures an agent outside Forge draws, which Forge checks, deals out for
 * review, and places in the project they are for once a person keeps them.
 *
 * <p>A batch is a folder under {@code runs/} with a {@code batch.json}, named like a run. The agent
 * works in its {@code repo/}, where Forge writes the agent's list and the message to send it; the
 * list, the tools that check, the review sheets and the record stay beside it. Everything a page
 * does with a batch comes through here.
 */
public final class PictureBatches {

    private final Path runsDir;

    public PictureBatches(Path runsDir) {
        this.runsDir = runsDir;
    }

    /** Starts looking at the batches every few seconds, once for this runs folder. */
    public void start() {
        PictureWatch.start(runsDir);
    }

    public List<BatchView> all() {
        return Batches.folders(runsDir).stream().map(Views::of).toList();
    }

    public Optional<BatchView> one(String id) {
        return Batches.folder(runsDir, id).map(Views::of);
    }

    /**
     * Keeps a picture, or has it drawn again with the note added to its prompt.
     *
     * @param stamp the picture the person saw, as the page was given it
     * @return why it was not saved, or empty when it was
     */
    public Optional<String> rule(String id, String draft, String stamp, boolean keep, String note) {
        Optional<Path> folder = Batches.folder(runsDir, id);
        if (folder.isEmpty()) {
            return Optional.of("no picture batch " + id);
        }
        Batch batch = Batches.load(folder.get());
        Optional<String> refused = Rulings.save(batch, draft, stamp, keep, note);
        if (refused.isEmpty()) {
            NextJobs.write(batch);
        }
        return refused;
    }

    /**
     * Asks for every kept picture to be placed; the watcher runs the placings one at a time.
     *
     * @return how many were asked for, or empty when there is no such batch
     */
    public Optional<Integer> place(String id) {
        return Batches.folder(runsDir, id)
                .map(folder -> Placing.ask(Batches.load(folder), new RecordStore(folder)));
    }
}
