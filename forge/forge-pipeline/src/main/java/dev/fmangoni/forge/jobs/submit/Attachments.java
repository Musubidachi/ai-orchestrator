package dev.fmangoni.forge.jobs.submit;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.jobs.work.Project;
import dev.fmangoni.forge.jobs.work.Projects;
import dev.fmangoni.forge.project.RunPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * A picture a person pastes or picks into a note, kept where the agent that reads the note can open it.
 *
 * <p>"The buttons turn into tall pills on a desktop" is one sentence; the screenshot is the whole
 * defect, and a person who had it in their clipboard had no way to hand it over. Saved in the
 * project's own repository, because that is the one folder every vendor's sandbox lets its agent
 * read, under {@value #FOLDER}, which is kept out of git so a screenshot never lands in the
 * project's history or its review diff. The note itself carries the path as plain text — see
 * {@link #reference} — so every route a note already takes to an agent carries the picture with it.
 *
 * <p>Only images, judged by their first bytes rather than by the name or type the page claims, and
 * named by Forge rather than by the person's file name, which may hold anything.
 */
public final class Attachments {

    /** Inside the repository, relative to it. */
    public static final String FOLDER = ".forge-images";

    /** A full-screen PNG of a 4K display is a few megabytes; anything past this is not a screenshot. */
    static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Projects projects;

    public Attachments(Projects projects) {
        this.projects = projects;
    }

    /**
     * Saves one image for a project and answers with the text to put in the note, or throws
     * {@link IllegalArgumentException} saying why it was refused.
     */
    public String save(String id, byte[] image) {
        Optional<Project> found = projects.find(id);
        if (found.isEmpty()) {
            throw new IllegalArgumentException(Projects.unknown(id));
        }
        String extension = extensionOf(image);
        RunPaths paths = found.get().paths();
        Path folder = paths.repo().resolve(FOLDER);
        try {
            Files.createDirectories(folder);
            keepOutOfHistory(paths);
            Path file = freeName(folder, extension);
            Files.write(file, image);
            return reference(FOLDER + "/" + file.getFileName());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save the image for " + id, e);
        }
    }

    /**
     * The exclude line is written before git is called, so a turn holding the index at that moment
     * only fails the untracking that follows it — and nothing in this folder was ever tracked.
     */
    private static void keepOutOfHistory(RunPaths paths) {
        try {
            // No leading slash: git rm rejects one as a path outside the repository.
            new Git(paths.repo(), paths.gitLog()).keepOutOfHistory(FOLDER + "/");
        } catch (RuntimeException e) {
            // See above: the picture is still kept out of the next commit.
        }
    }

    /**
     * How a note names a picture: plain text, so it survives every place a note is stored and shown,
     * and says what to do with it to whichever model reads it.
     */
    public static String reference(String relativePath) {
        return "[image: " + relativePath + "]";
    }

    /** The image's own type, from its first bytes; anything else is refused. */
    static String extensionOf(byte[] image) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("the image was empty");
        }
        if (image.length > MAX_BYTES) {
            throw new IllegalArgumentException("the image is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        if (startsWith(image, 0x89, 'P', 'N', 'G')) {
            return "png";
        }
        if (startsWith(image, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }
        if (startsWith(image, 'G', 'I', 'F', '8')) {
            return "gif";
        }
        if (image.length >= 12 && startsWith(image, 'R', 'I', 'F', 'F')
                && image[8] == 'W' && image[9] == 'E' && image[10] == 'B' && image[11] == 'P') {
            return "webp";
        }
        throw new IllegalArgumentException("only PNG, JPEG, GIF or WebP images can be attached");
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static Path freeName(Path folder, String extension) {
        String stamp = LocalDateTime.now().format(STAMP);
        for (int n = 1; ; n++) {
            Path candidate = folder.resolve(stamp + "-" + n + "." + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }
}
