package dev.fmangoni.forge.deploy.github;

import java.text.Normalizer;
import java.util.Locale;

/**
 * What a family's repository and each of its versions are called on GitHub.
 *
 * <p>The initial version is {@code main}, and stays there: the person chose that main is never
 * moved up to a later version. Each enhancement is a branch of its own, named for its id and title
 * so the list of branches reads as the project's history.
 */
public final class Branches {

    public static final String MAIN = "main";

    private static final int LONGEST_SLUG = 60;

    private Branches() {
    }

    /** The family a project belongs to: everything before the first dot of its id. */
    public static String familyOf(String id) {
        int dot = id.indexOf('.');
        return dot < 0 ? id : id.substring(0, dot);
    }

    public static boolean isInitial(String id) {
        return id.indexOf('.') < 0;
    }

    /** {@code main} for the initial version; for an enhancement, such as {@code 003.2-game-customization}. */
    public static String of(String id, String title) {
        if (isInitial(id)) {
            return MAIN;
        }
        String slug = slug(title);
        return slug.isEmpty() ? id : id + "-" + slug;
    }

    /** The repository's name, from the initial version's title. */
    public static String repositoryName(String title) {
        String slug = slug(title);
        return slug.isEmpty() ? "forge-project" : slug;
    }

    /** Lower case, accents dropped, and every run of anything else a single hyphen. */
    static String slug(String text) {
        String plain = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return slug.length() <= LONGEST_SLUG ? slug : slug.substring(0, LONGEST_SLUG).replaceAll("-+$", "");
    }
}
