package dev.fmangoni.forge.spec;

import java.nio.file.Path;

/**
 * A wishlist entry Forge refuses to run.
 *
 * <p>Always names the offending file: these surface during an unattended run that nobody is
 * watching, so the message has to be enough to fix the spec without reproducing anything.
 */
public class SpecException extends RuntimeException {

    public SpecException(Path source, String problem) {
        super(source + ": " + problem);
    }
}
