package dev.fmangoni.forge.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ad campaign drafts, held to the limits the platforms enforce on upload.
 *
 * <p>Limits are reported, never fixed by truncation. A headline cut at 30 characters usually stops
 * mid-word and says something nobody wrote; the draft goes back as it is, with the count.
 *
 * <p>Drafts only. Nothing here or anywhere in Forge calls an ad platform or spends money; these files
 * are for the person to review and upload with their own account, if they choose to.
 */
final class CampaignCheck {

    private final Path dir;
    private final String deployUrl;

    CampaignCheck(Path dir, String deployUrl) {
        this.dir = dir;
        this.deployUrl = deployUrl;
    }

    List<KitProblem> run() {
        List<KitProblem> problems = new ArrayList<>();
        google(problems);
        meta(problems);
        reddit(problems);
        return problems;
    }

    /**
     * Google Ads Editor's import columns: headlines of 30, descriptions of 90, a final URL.
     *
     * <p>Read as CSV with quoted fields, since descriptions contain commas.
     */
    private void google(List<KitProblem> problems) {
        Path file = dir.resolve("google-search.csv");
        if (!Files.isRegularFile(file)) {
            problems.add(new KitProblem("campaigns/google-search.csv", "was not written"));
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty()) {
                problems.add(new KitProblem("campaigns/google-search.csv", "is empty"));
                return;
            }
            List<String> header = csv(lines.get(0));
            for (int row = 1; row < lines.size(); row++) {
                if (lines.get(row).isBlank()) {
                    continue;
                }
                List<String> cells = csv(lines.get(row));
                for (int col = 0; col < Math.min(header.size(), cells.size()); col++) {
                    String name = header.get(col).strip();
                    String where = "google-search.csv row " + row + " " + name;
                    if (name.startsWith("Headline")) {
                        KitCheck.limit(problems, where, cells.get(col), 30);
                    } else if (name.startsWith("Description")) {
                        KitCheck.limit(problems, where, cells.get(col), 90);
                    } else if (name.equals("Final URL")) {
                        landing(problems, where, cells.get(col));
                    }
                }
            }
            if (!header.contains("Final URL")) {
                problems.add(new KitProblem("campaigns/google-search.csv", "has no 'Final URL' column"));
            }
        } catch (IOException e) {
            problems.add(new KitProblem("campaigns/google-search.csv", "could not be read: " + e.getMessage()));
        }
    }

    private void meta(List<KitProblem> problems) {
        JsonNode meta = KitCheck.read(dir.resolve("meta.json"), "campaigns/meta.json", problems);
        if (meta == null) {
            return;
        }
        KitCheck.limit(problems, "meta.json headline", meta.path("headline").asText(""), 40);
        KitCheck.limit(problems, "meta.json primary text", meta.path("primary_text").asText(""), 125);
        landing(problems, "meta.json url", meta.path("url").asText(""));
        budget(problems, "meta.json", meta);
    }

    private void reddit(List<KitProblem> problems) {
        JsonNode reddit = KitCheck.read(dir.resolve("reddit.json"), "campaigns/reddit.json", problems);
        if (reddit == null) {
            return;
        }
        KitCheck.limit(problems, "reddit.json headline", reddit.path("headline").asText(""), 300);
        landing(problems, "reddit.json url", reddit.path("url").asText(""));
        budget(problems, "reddit.json", reddit);
    }

    /** A daily budget, a hard cap, and when to stop — a campaign without a stop is an open tab. */
    private void budget(List<KitProblem> problems, String where, JsonNode campaign) {
        JsonNode budget = campaign.path("budget");
        if (budget.path("daily").asDouble(0) <= 0 || budget.path("total_cap").asDouble(0) <= 0) {
            problems.add(new KitProblem(where, "needs a positive budget.daily and budget.total_cap"));
        } else if (budget.path("daily").asDouble() > budget.path("total_cap").asDouble()) {
            problems.add(new KitProblem(where, "its daily budget is larger than its total cap"));
        }
        if (budget.path("stop_when").asText("").isBlank()) {
            problems.add(new KitProblem(where, "needs budget.stop_when: what result, or lack of one, ends it"));
        }
    }

    /** Ads point at the project's own site, tagged so the person can see which campaign brought whom. */
    private void landing(List<KitProblem> problems, String where, String url) {
        if (deployUrl.isBlank()) {
            problems.add(new KitProblem(where, "there is nowhere to send people: the project is not deployed"));
        } else if (!url.startsWith(deployUrl)) {
            problems.add(new KitProblem(where, "points at " + url + ", not the project's site " + deployUrl));
        } else if (!url.contains("utm_source=")) {
            problems.add(new KitProblem(where, "has no utm_source, so its visits cannot be told apart"));
        }
    }

    /** One CSV line, honouring double-quoted fields. */
    static List<String> csv(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
