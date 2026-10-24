package dev.fmangoni.forge.deploy.shelf;

import dev.fmangoni.forge.json.Json;
import dev.fmangoni.forge.json.JsonFile;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Packs what a shelf carries besides the project's history, and puts it back.
 *
 * <p>That is everything Forge keeps for each version outside the repository (state, reviews,
 * rulings, logs, evidence, the look reference, the GitHub record, anything else in the run folders),
 * the repository's own git settings, and the files in its working tree that its history does not
 * hold: attached pictures, feature recordings, and changes nobody committed. Without them a resumed
 * project would be a different one.
 *
 * <p>A file over the part size goes in pieces, because GitHub refuses any one file over 100 MB and
 * a log here has reached 181 MB. Every file is checksummed going out, and checked coming back.
 */
final class ShelfFiles {

    /** Where the payload sits in the repository, on the shelf branch and nowhere else. */
    static final String DIR = ".forge-shelf";

    static final String MANIFEST = "manifest.json";

    private static final String FILES = "files";

    /** Kept in a run folder but never carried: the lock and the shelf's own bookkeeping. */
    static final Set<String> LOCAL_ONLY = Set.of("repo.lock", ShelfMark.FILE, "shelf.log", ".shelf-incoming");

    private final long partSize;

    ShelfFiles(long partSize) {
        this.partSize = partSize;
    }

    /**
     * Copies what the shelf carries into the repository's {@link #DIR}, and writes its manifest.
     *
     * @param changed working-tree files that differ from HEAD or are new, from git
     * @param deleted working-tree files deleted without a commit, from git
     */
    ShelfManifest pack(Path runsDir, String family, List<String> versions, Path repo, String head, String localBranch,
                       List<String> changed, List<String> deleted) throws IOException {
        Path out = repo.resolve(DIR).resolve(FILES);
        List<ShelfEntry> entries = new ArrayList<>();
        for (String version : versions) {
            Path dir = runsDir.resolve(version);
            for (Path file : filesUnder(dir)) {
                String rel = slashed(dir.relativize(file));
                boolean ownRepo = version.equals(family) && (rel.equals("repo") || rel.startsWith("repo/"));
                if (!ownRepo && !LOCAL_ONLY.contains(rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : rel)) {
                    entries.add(add(file, "runs/" + version + "/" + rel, out));
                }
            }
        }
        for (String setting : List.of("config", "info/exclude")) {
            Path file = repo.resolve(".git").resolve(setting);
            if (Files.isRegularFile(file)) {
                entries.add(add(file, "git/" + setting, out));
            }
        }
        for (String rel : workingTree(repo, changed)) {
            entries.add(add(repo.resolve(rel), "worktree/" + rel, out));
        }
        ShelfManifest manifest = new ShelfManifest(family, versions, head, localBranch, entries, deleted);
        JsonFile.write(repo.resolve(DIR).resolve(MANIFEST), Json.mapper().writeValueAsString(manifest));
        // Every byte as it was. Git on Windows rewrites line endings on the way out and in, which
        // changed a log's checksum between shelving and resuming, so git is told none of it is text.
        Files.writeString(repo.resolve(DIR).resolve(".gitattributes"), "* binary\n");
        return manifest;
    }

    /** What the working tree holds that history does not: changes, and what Forge keeps out of git. */
    private static Set<String> workingTree(Path repo, List<String> changed) throws IOException {
        Set<String> carried = new TreeSet<>();
        for (String rel : changed) {
            if (!rel.startsWith(DIR + "/") && Files.isRegularFile(repo.resolve(rel))) {
                carried.add(rel);
            }
        }
        for (Path file : filesUnder(repo.resolve(".forge-images"))) {
            carried.add(slashed(repo.relativize(file)));
        }
        Path artifacts = repo.resolve("artifacts");
        if (Files.isDirectory(artifacts)) {
            try (Stream<Path> files = Files.list(artifacts)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().startsWith("feature-"))
                        .forEach(file -> carried.add(slashed(repo.relativize(file))));
            }
        }
        return carried;
    }

    private ShelfEntry add(Path source, String dest, Path out) throws IOException {
        MessageDigest sha = sha256();
        long size = 0;
        int part = 0;
        try (InputStream in = new DigestInputStream(Files.newInputStream(source), sha)) {
            long total = Files.size(source);
            boolean pieces = total > partSize;
            do {
                part++;
                Path target = place(out, dest, pieces ? suffix(part) : "");
                Files.createDirectories(target.getParent());
                long copied;
                try (OutputStream piece = Files.newOutputStream(target)) {
                    copied = copy(in, piece, pieces ? partSize : Long.MAX_VALUE);
                }
                size += copied;
                if (pieces && copied == 0 && part > 1) {
                    // The file ended on a piece boundary: the empty last piece is not one.
                    Files.delete(target);
                    part--;
                    break;
                }
                if (!pieces || copied < partSize) {
                    break;
                }
            } while (true);
        }
        return new ShelfEntry(dest, size, HexFormat.of().formatHex(sha.digest()), part);
    }

    static ShelfManifest manifest(Path payload) throws IOException {
        return Json.mapper().readValue(JsonFile.read(payload.resolve(MANIFEST)), ShelfManifest.class);
    }

    /**
     * Puts back every file whose path starts with {@code prefix}, under {@code root}.
     *
     * <p>Each is written beside its place and moved in only once its size and checksum match, so a
     * damaged or incomplete shelf leaves nothing half-written where a good file was.
     */
    void restore(Path payload, ShelfManifest manifest, String prefix, Path root) throws IOException {
        for (ShelfEntry entry : manifest.files()) {
            if (!entry.path().startsWith(prefix)) {
                continue;
            }
            Path target = place(root, entry.path().substring(prefix.length()), "");
            Files.createDirectories(target.getParent());
            Path temp = target.resolveSibling(target.getFileName() + ".restoring");
            MessageDigest sha = sha256();
            long size = 0;
            try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), sha)) {
                for (int part = 1; part <= entry.parts(); part++) {
                    size += Files.copy(place(payload.resolve(FILES), entry.path(), entry.parts() == 1 ? "" : suffix(part)), out);
                }
            }
            if (size != entry.size() || !HexFormat.of().formatHex(sha.digest()).equals(entry.sha256())) {
                Files.deleteIfExists(temp);
                throw new IllegalStateException(entry.path() + " did not come back intact from the shelf");
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Where a manifest path lands under a folder.
     *
     * <p>Never outside it. The payload comes back from GitHub, and a branch someone else changed must
     * not be able to write anywhere else on this machine.
     */
    private static Path place(Path root, String slashed, String suffix) {
        Path target = root;
        for (String part : slashed.split("/")) {
            target = target.resolve(part);
        }
        target = target.resolveSibling(target.getFileName() + suffix).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IllegalStateException("the shelf names a file outside its place: " + slashed);
        }
        return target;
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static long copy(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buffer = new byte[1 << 16];
        long copied = 0;
        while (copied < limit) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, limit - copied));
            if (read < 0) {
                break;
            }
            out.write(buffer, 0, read);
            copied += read;
        }
        return copied;
    }

    private static String suffix(int part) {
        return String.format(".part%03d", part);
    }

    private static String slashed(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("this JVM has no SHA-256", e);
        }
    }
}
