package dev.fmangoni.forge.cli.command;

import dev.fmangoni.forge.cli.support.Args;
import dev.fmangoni.forge.project.ForgeHome;
import dev.fmangoni.forge.tour.CodeTour;
import dev.fmangoni.forge.tour.film.TourFilm;
import dev.fmangoni.forge.tour.model.Tour;
import dev.fmangoni.forge.tour.show.TourPage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * {@code forge tour}: explains a Maven project out loud, module by module and line by line.
 *
 * <p>Writes a page that plays itself immediately, because that is the version worth having in the
 * ten seconds after asking. The video is the slow one — a recording runs at the speed of speech,
 * so an hour of tour takes an hour to record — and is only made when it is asked for.
 */
@UtilityClass
public class TourCommand {

    public void run(ForgeHome home, String[] args) {
        Path of = Args.option(args, "--of").map(Path::of).orElseGet(() -> defaultParent(home));
        Path out = Args.option(args, "--out").map(Path::of)
                .orElseGet(() -> home.root().resolve("logs").resolve("tour"));
        String only = Args.option(args, "--only").orElse("");

        if (!Files.isRegularFile(of.resolve("pom.xml")) && !Files.isRegularFile(of)) {
            System.out.println("No pom.xml in " + of + ". Point --of at a Maven project.");
            return;
        }
        Tour tour = CodeTour.of(of, only);
        if (tour.scenes().isEmpty()) {
            System.out.println("Nothing to tour in " + of
                    + (only.isBlank() ? "." : " matching " + only + "."));
            return;
        }
        write(tour, out, args);
    }

    private void write(Tour tour, Path out, String[] args) {
        try {
            Path page = out.resolve("tour.html");
            TourPage.write(tour, page);
            System.out.println("Tour: " + page);
            System.out.println("      " + tour.chapters().size() + " chapters, "
                    + tour.scenes().size() + " steps, about "
                    + Math.round(tour.seconds() / 60) + " minutes spoken");
            if (Args.has(args, "--video")) {
                film(tour, out, args);
            } else {
                System.out.println("      --video also records it and writes an mp4");
            }
        } catch (IOException e) {
            System.out.println("Could not write the tour: " + e.getMessage());
        }
    }

    private void film(Tour tour, Path out, String[] args) throws IOException {
        if (!TourFilm.available()) {
            System.out.println("      no ffmpeg on the path, so no video was made."
                    + " The page above plays itself without one.");
            return;
        }
        boolean music = !Args.has(args, "--no-music");
        System.out.println("      recording " + tour.scenes().size()
                + " steps, which takes about as long as saying them"
                + (music ? ", then drawing and scoring it..." : ", then drawing it..."));
        Optional<Path> film =
                TourFilm.write(tour, out.resolve("scenes"), out.resolve("tour.mp4"), music);
        System.out.println(film.map(path -> "Video: " + path)
                .orElse("      ffmpeg refused the encode; the page and the recordings are still there"));
    }

    /**
     * What to tour when nothing was named: Forge itself.
     *
     * <p>The parent sits in {@code forge/} beside the runs, so a bare {@code forge tour} from the
     * root explains the thing you are standing in.
     */
    private Path defaultParent(ForgeHome home) {
        Path forge = home.root().resolve("forge");
        return Files.isRegularFile(forge.resolve("pom.xml")) ? forge : home.root();
    }
}
