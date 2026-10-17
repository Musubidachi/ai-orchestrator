package dev.fmangoni.forge.tour;

import dev.fmangoni.forge.tour.model.Chapter;
import dev.fmangoni.forge.tour.model.Tour;
import dev.fmangoni.forge.tour.read.JavaSource;
import dev.fmangoni.forge.tour.read.MavenParent;
import dev.fmangoni.forge.tour.read.SourceFiles;
import dev.fmangoni.forge.tour.read.model.ParsedType;
import dev.fmangoni.forge.tour.say.ModuleStory;
import dev.fmangoni.forge.tour.say.TypeStory;
import dev.fmangoni.forge.walkthrough.Spoken;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.experimental.UtilityClass;

/**
 * A narrated tour of a Maven parent and everything under it.
 *
 * <p>Module by module, in the order the parent lists them — which in a layered build is the order
 * the layers stack, so the tour arrives at each class having already explained what it is built on.
 * Inside a module it is package by package and file by file, and inside a file it is top to bottom,
 * because that is the order the code was written to be read in.
 *
 * <p>Nothing here calls a model. Every sentence comes from the source and the notes beside it, so a
 * tour costs nothing to regenerate, can be run on every change, and cannot describe the code as
 * anything other than what it says.
 */
@UtilityClass
public class CodeTour {

    /** The whole parent. */
    public Tour of(Path parent) {
        return of(parent, "");
    }

    /**
     * The parent, narrowed to modules and classes whose names contain {@code only}.
     *
     * <p>A whole codebase is hours of narration. Narrowing is what makes the tool usable for the
     * question people actually have, which is almost never "explain all of it" and almost always
     * "explain this part".
     */
    public Tour of(Path parent, String only) {
        Path pom = MavenParent.pomIn(parent);
        Path root = MavenParent.directoryOf(pom);
        List<Chapter> chapters = new ArrayList<>();
        for (Path module : MavenParent.modulesOf(pom)) {
            chapters.addAll(chaptersOf(module, only));
        }
        // A summary of nothing is still a page that plays, which is how a mistyped --only ends up
        // looking like a tour with one chapter rather than like a miss.
        if (!chapters.isEmpty()) {
            chapters.add(TourSummary.closing(root, chapters));
        }
        return new Tour(title(root), subtitle(only), chapters);
    }

    private List<Chapter> chaptersOf(Path module, String only) {
        List<ParsedType> types = typesIn(module, only);
        if (types.isEmpty()) {
            return List.of();
        }
        List<Chapter> chapters = new ArrayList<>();
        chapters.add(ModuleStory.of(module, types));
        types.forEach(type -> chapters.add(TypeStory.of(type)));
        return chapters;
    }

    private List<ParsedType> typesIn(Path module, String only) {
        boolean whole = wanted(module.getFileName().toString(), only);
        List<ParsedType> types = new ArrayList<>();
        for (Path file : SourceFiles.mainJava(module)) {
            String name = file.getFileName().toString().replace(".java", "");
            if (whole || wanted(name, only)) {
                JavaSource.parse(file).ifPresent(types::add);
            }
        }
        return types;
    }

    private boolean wanted(String name, String only) {
        return only.isBlank() || name.toLowerCase(Locale.ROOT).contains(only.toLowerCase(Locale.ROOT));
    }

    private String title(Path root) {
        return Spoken.words(root.getFileName().toString());
    }

    private String subtitle(String only) {
        return only.isBlank() ? "every module, top to bottom" : "the parts matching " + only;
    }
}
