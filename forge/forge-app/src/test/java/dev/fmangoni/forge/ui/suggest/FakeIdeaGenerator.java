package dev.fmangoni.forge.ui.suggest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Stands in for 006 in {@code IdeaSourceTest}: run the way the real generator is, it writes one idea
 * as {@code out.json} in the folder it was started in, naming the seed and filters it was given, so
 * a test can see what it was asked. {@code --category broken} makes it fail the way a bad flag does.
 */
public final class FakeIdeaGenerator {

    private FakeIdeaGenerator() {
    }

    public static void main(String[] args) throws Exception {
        List<String> given = Arrays.asList(args);
        String seed = after(given, "--seed");
        String category = after(given, "--category");
        String effort = after(given, "--max-effort");
        if ("broken".equals(category)) {
            System.err.println("Unknown category: broken");
            System.exit(2);
        }
        Files.writeString(Path.of("out.json"), """
                [{"name": "Idea %s", "description": "category=%s effort=%s",
                  "audience": "makers", "category": "%s", "buildTimeEstimate": "1-2 weeks",
                  "monetization": "one-time purchase", "effort": 2}]
                """.formatted(seed, category, effort, category));
        Files.writeString(Path.of("out.html"), "<html></html>");
    }

    private static String after(List<String> args, String flag) {
        int at = args.indexOf(flag);
        return at < 0 || at + 1 >= args.size() ? "" : args.get(at + 1);
    }
}
