package dev.fmangoni.forge.git;

/**
 * One commit in a repository's history, as {@link Git#history()} reads it.
 *
 * @param sha     the full 40-character id
 * @param subject the first line of its message, as written: Forge's own commits name what made them
 */
public record Commit(String sha, String subject) {
}
