package dev.fmangoni.forge.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import lombok.experimental.UtilityClass;

/**
 * Reads a JSON file the way a person is likely to have left it.
 *
 * <p>Every configuration file Forge keeps is meant to be hand-edited, and on Windows the editors
 * people reach for write a UTF-8 byte-order mark. Jackson rejects it outright — {@code Unexpected
 * character ('?' (code 65279))} — so a file that looks perfectly correct in Notepad is silently
 * ignored, and the setting it held appears not to work.
 *
 * <p>Hit in practice within a minute of writing one of these files from PowerShell, whose
 * {@code Out-File -Encoding utf8} adds the mark. Anything meant for human hands goes through here.
 */
@UtilityClass
public class JsonFile {

    private final char BOM = '﻿';

    /** The file's text, with a leading byte-order mark removed if one is present. */
    public String read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.isEmpty() || text.charAt(0) != BOM ? text : text.substring(1);
    }

    /**
     * Replaces a file's contents all at once: written beside it, then moved into place.
     *
     * <p>Only {@code state.json} used to be written this way. Every other store wrote in place, and
     * each one's loader treats an unreadable file as empty rather than failing — which is right for
     * a reader and ruinous combined with an in-place writer. A process killed half-way through
     * writing {@code decisions.json} leaves a truncated file; the next load reads that as "no
     * decisions"; the next submission saves only itself over it; and every note a person has left
     * on the project is gone, silently. Forge is killed mid-flight often enough on this machine
     * that this is a matter of when.
     */
    public void write(Path file, String content) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Some Windows filesystems refuse an atomic replace; a plain replace still beats
            // writing the target in place, which is the failure mode actually worth avoiding.
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
