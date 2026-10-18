package dev.fmangoni.forge.marketing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KitCheckTest {

    @TempDir
    Path kit;

    private static final String SITE = "https://invoice-tool.pages.dev";
    private static final Set<String> APPROVED = Set.of("feature-report-export", "feature-monthly-summary");

    private void write(String relative, String content) throws Exception {
        Path file = kit.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** A kit that passes every check; each test breaks one thing. */
    @BeforeEach
    void goodKit() throws Exception {
        write("kit.json", """
                {"tagline":"Import a batch of invoices",
                 "short_description":"A browser tool that turns a folder of receipts into one invoice.",
                 "long_description":"Drop the files in, check the totals, export.",
                 "press":"Invoice Tool is a browser invoicing tool.",
                 "keywords":["invoicing"],
                 "claims":[{"text":"Export a report for any date range","feature":"feature-report-export"},
                           {"text":"Summarise a month in one click","feature":"feature-monthly-summary"}],
                 "posts":{"x":"Turn a folder of receipts into one invoice: https://invoice-tool.pages.dev/?utm_source=x",
                          "reddit":{"title":"I made a tool that invoices from a folder of receipts","body":"Try it."},
                          "hacker_news":{"title":"Show HN: Invoice Tool, invoicing from a folder"},
                          "linkedin":"New project."}}
                """);
        write("site/index.html", "<html><body><h1>Invoice Tool</h1><a href=\"" + SITE + "\">Open</a></body></html>");
        write("campaigns/google-search.csv", """
                Campaign,Ad group,Headline 1,Headline 2,Description 1,Final URL
                Invoice Tool,Invoicing,Invoice In Seconds,Receipts To Invoice,"Drop them in, check the totals, export. Free in your browser.",https://invoice-tool.pages.dev/?utm_source=google
                """);
        write("campaigns/meta.json", """
                {"headline":"Invoice in seconds","primary_text":"A folder of receipts in, one invoice out.",
                 "url":"https://invoice-tool.pages.dev/?utm_source=meta",
                 "budget":{"daily":5,"total_cap":50,"stop_when":"no exports after 200 clicks"}}
                """);
        write("campaigns/reddit.json", """
                {"headline":"Invoicing from a folder of receipts","url":"https://invoice-tool.pages.dev/?utm_source=reddit",
                 "budget":{"daily":5,"total_cap":30,"stop_when":"under 1% click-through after 3 days"}}
                """);
    }

    private List<KitProblem> check() {
        return new KitCheck(kit, APPROVED, SITE).run();
    }

    @Test
    @DisplayName("a kit that claims only what was approved, and links only to the site, passes")
    void goodKitPasses() {
        assertEquals(List.of(), check());
    }

    @Test
    @DisplayName("a claim about something nobody approved is caught")
    void unapprovedClaim() throws Exception {
        String json = Files.readString(kit.resolve("kit.json")).replace(
                "{\"text\":\"Summarise a month in one click\",\"feature\":\"feature-monthly-summary\"}",
                "{\"text\":\"Online multiplayer\",\"feature\":\"feature-multiplayer\"}");
        write("kit.json", json);

        assertTrue(check().stream().anyMatch(p -> p.what().contains("feature-multiplayer, which is not an approved feature")),
                check().toString());
    }

    @Test
    @DisplayName("invented users, ratings and testimonials are refused wherever they appear")
    void inventedSocialProof() throws Exception {
        write("site/index.html", "<p>Loved by 10,000+ players!</p><p>★★★★★</p>");
        write("campaigns/reddit.json", Files.readString(kit.resolve("campaigns/reddit.json"))
                .replace("Invoicing from", "\\\"Best invoicing tool I have used all year\\\" — Dave. Invoicing from"));

        List<KitProblem> problems = check();

        assertTrue(problems.stream().anyMatch(p -> p.where().equals("site/index.html") && p.what().startsWith("invented social proof")),
                problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.where().equals("campaigns/reddit.json")), problems.toString());
    }

    @Test
    @DisplayName("platform limits are reported with the count, not quietly truncated")
    void limits() throws Exception {
        write("campaigns/google-search.csv", """
                Campaign,Ad group,Headline 1,Description 1,Final URL
                Invoice Tool,Invoicing,The Most Exciting Invoice Software Ever,Short,https://invoice-tool.pages.dev/?utm_source=google
                """);

        assertTrue(check().stream().anyMatch(p -> p.where().equals("google-search.csv row 1 Headline 1")
                && p.what().equals("39 characters; the limit is 30")), check().toString());
    }

    @Test
    @DisplayName("ads and posts must send people to the project's own site, tagged, with a budget that stops")
    void landingAndBudget() throws Exception {
        write("campaigns/meta.json", """
                {"headline":"Invoice in seconds","primary_text":"Export.","url":"https://invoice-tool.pages.dev/",
                 "budget":{"daily":50,"total_cap":20}}
                """);
        write("site/index.html", "<a href=\"https://some-affiliate.example/track\">Play</a>");

        List<String> problems = check().stream().map(p -> p.where() + ": " + p.what()).toList();

        assertTrue(problems.contains("meta.json url: has no utm_source, so its visits cannot be told apart"), problems.toString());
        assertTrue(problems.contains("meta.json: its daily budget is larger than its total cap"), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("meta.json: needs budget.stop_when")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("site/index.html: links to https://some-affiliate")), problems.toString());
    }

    @Test
    @DisplayName("a kit for a project that is not deployed has nowhere to send anyone, and says so")
    void notDeployed() {
        List<KitProblem> problems = new KitCheck(kit, APPROVED, "").run();

        assertTrue(problems.stream().anyMatch(p -> p.what().contains("not deployed")), problems.toString());
    }
}
