package dev.fmangoni.forge.git;

import java.util.Locale;
import java.util.Set;

/** One file's section of the diff. */
record DiffSection(String path, String body) {

    /** What counts as the part worth reading first. */
    private static final Set<String> CODE = Set.of(
            "java", "kt", "kts", "scala", "groovy", "ts", "tsx", "js", "jsx", "mjs", "cjs",
            "py", "go", "rs", "rb", "cs", "fs", "c", "cc", "cpp", "h", "hpp", "swift", "m",
            "php", "lua", "dart", "vue", "svelte", "sql", "sh", "ps1", "bat");

    String extension() {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    boolean code() {
        return CODE.contains(extension());
    }

    boolean test() {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/test/") || lower.contains("test.") || lower.contains(".spec.")
                || lower.startsWith("test/") || lower.startsWith("tests/");
    }
}
