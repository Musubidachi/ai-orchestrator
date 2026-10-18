package dev.fmangoni.forge.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import dev.fmangoni.forge.json.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a marketing kit before anyone sees it: nothing claimed that was not built, nothing
 * invented, nothing a platform would reject.
 *
 * <p>Deterministic on purpose. The kit was written by a model asked to sell something, which is the
 * situation in which models most reliably make things up: a user count, a five-star rating, a
 * testimonial from nobody. Those are not style problems; published, they are false advertising under
 * the person's name. So they are looked for by rule, and every claim must cite a feature the person
 * approved — the same features that have recordings proving they work.
 */
public final class KitCheck {

    /** Social proof that no new project can honestly have. */
    private static final Pattern INVENTED_PROOF = Pattern.compile(
            "(?i)\\b\\d[\\d,.]*\\s*[kKmM+]?\\+?\\s*(?:happy\\s+)?(?:users|customers|downloads|players|installs|people|teams|companies)\\b"
                    + "|★|\\b\\d(?:\\.\\d)?\\s*(?:/\\s*5|out of 5|stars?)\\b|\\brated\\s+#?\\d"
                    + "|\\b(?:testimonial|as seen (?:on|in)|award[- ]winning|trusted by|loved by)\\b"
                    + "|[\"“][^\"”]{10,}[\"”]\\s*[—–-]\\s*[A-Z][a-z]+");

    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>)\\]]+");

    private final Path kitDir;
    private final Set<String> approvedFeatures;
    private final String deployUrl;

    /**
     * @param approvedFeatures ids of features the person approved, e.g. {@code feature-report-export}
     * @param deployUrl        where the project lives, or blank when it is not deployed
     */
    public KitCheck(Path kitDir, Set<String> approvedFeatures, String deployUrl) {
        this.kitDir = kitDir;
        this.approvedFeatures = approvedFeatures;
        this.deployUrl = deployUrl == null ? "" : deployUrl.strip();
    }

    public List<KitProblem> run() {
        List<KitProblem> problems = new ArrayList<>();
        JsonNode kit = read(kitDir.resolve("kit.json"), "kit.json", problems);
        if (kit == null) {
            return problems;
        }
        for (String field : List.of("tagline", "short_description", "long_description", "press")) {
            if (kit.path(field).asText("").isBlank()) {
                problems.add(new KitProblem("kit.json", "'" + field + "' is missing"));
            }
        }
        limit(problems, "short description", kit.path("short_description").asText(""), 80);
        limit(problems, "X post", kit.path("posts").path("x").asText(""), 280);
        limit(problems, "Hacker News title", kit.path("posts").path("hacker_news").path("title").asText(""), 80);
        limit(problems, "Reddit title", kit.path("posts").path("reddit").path("title").asText(""), 300);

        claims(kit, problems);
        if (!Files.isRegularFile(kitDir.resolve("site").resolve("index.html"))) {
            problems.add(new KitProblem("site/index.html", "the landing page was not written"));
        }
        honesty(kitDir, problems);
        problems.addAll(new CampaignCheck(kitDir.resolve("campaigns"), deployUrl).run());
        return problems;
    }

    /** Every claim names the feature it describes, and that feature was approved. */
    private void claims(JsonNode kit, List<KitProblem> problems) {
        JsonNode claims = kit.path("claims");
        if (!claims.isArray() || claims.isEmpty()) {
            problems.add(new KitProblem("kit.json", "no 'claims': each thing the copy says the product does must be listed with its feature"));
            return;
        }
        for (JsonNode claim : claims) {
            String feature = claim.path("feature").asText("");
            String text = claim.path("text").asText("");
            if (!approvedFeatures.contains(feature)) {
                problems.add(new KitProblem("claim \"" + shorten(text) + "\"", feature.isBlank()
                        ? "cites no feature" : "cites " + feature + ", which is not an approved feature"));
            }
        }
    }

    /** Invented social proof, and links anywhere but the project's own site, in every text file. */
    private void honesty(Path dir, List<KitProblem> problems) {
        try (var files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = dir.relativize(file).toString().replace('\\', '/');
                if (!name.matches(".*\\.(json|csv|html|md|txt)$") || name.startsWith("media/")) {
                    continue;
                }
                String text = Files.readString(file);
                Matcher proof = INVENTED_PROOF.matcher(text);
                if (proof.find()) {
                    problems.add(new KitProblem(name, "invented social proof: \"" + shorten(proof.group()) + "\" — "
                            + "a new project has no users, ratings or testimonials to cite"));
                }
                Matcher url = URL.matcher(text);
                while (url.find()) {
                    String link = url.group();
                    if (deployUrl.isBlank() || !link.startsWith(deployUrl)) {
                        if (!isSchema(link)) {
                            problems.add(new KitProblem(name, "links to " + shorten(link) + (deployUrl.isBlank()
                                    ? ", but the project is not deployed — deploy it first and make the kit again"
                                    : ", not to the project's own site " + deployUrl)));
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            problems.add(new KitProblem(dir.toString(), "could not be read: " + e.getMessage()));
        }
    }

    /** XML namespaces and the like, which are not links anyone follows. */
    private static boolean isSchema(String link) {
        return link.startsWith("http://www.w3.org/") || link.startsWith("https://www.w3.org/")
                || link.startsWith("https://schema.org") || link.startsWith("http://schema.org");
    }

    static void limit(List<KitProblem> problems, String what, String text, int max) {
        if (text.codePointCount(0, text.length()) > max) {
            problems.add(new KitProblem(what, text.codePointCount(0, text.length()) + " characters; the limit is " + max));
        }
    }

    static JsonNode read(Path file, String name, List<KitProblem> problems) {
        if (!Files.isRegularFile(file)) {
            problems.add(new KitProblem(name, "was not written"));
            return null;
        }
        try {
            return Json.mapper().readTree(Files.readString(file));
        } catch (IOException e) {
            problems.add(new KitProblem(name, "is not valid JSON: " + e.getMessage()));
            return null;
        }
    }

    private static String shorten(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
}
