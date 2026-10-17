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

    private static final String SITE = "https://pink-slip.pages.dev";
    private static final Set<String> APPROVED = Set.of("feature-garage-tuning", "feature-pink-slip-wager");

    private void write(String relative, String content) throws Exception {
        Path file = kit.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** A kit that passes every check; each test breaks one thing. */
    @BeforeEach
    void goodKit() throws Exception {
        write("kit.json", """
                {"tagline":"Race for pink slips",
                 "short_description":"A drag racing game where you bet your car.",
                 "long_description":"Tune a car, wager it, race.",
                 "press":"Pink Slip is a browser drag racing game.",
                 "keywords":["drag racing"],
                 "claims":[{"text":"Tune engine and gearing","feature":"feature-garage-tuning"},
                           {"text":"Win the other driver's car","feature":"feature-pink-slip-wager"}],
                 "posts":{"x":"Bet your car on a drag race: https://pink-slip.pages.dev/?utm_source=x",
                          "reddit":{"title":"I made a drag racing game where you bet your car","body":"Play it."},
                          "hacker_news":{"title":"Show HN: Pink Slip, a drag racing game"},
                          "linkedin":"New project."}}
                """);
        write("site/index.html", "<html><body><h1>Pink Slip</h1><a href=\"" + SITE + "\">Play</a></body></html>");
        write("campaigns/google-search.csv", """
                Campaign,Ad group,Headline 1,Headline 2,Description 1,Final URL
                Pink Slip,Racing,Bet Your Car,Drag Race Online,"Tune it, wager it, win theirs. Free in your browser.",https://pink-slip.pages.dev/?utm_source=google
                """);
        write("campaigns/meta.json", """
                {"headline":"Bet your car","primary_text":"A drag race where the loser hands over the keys.",
                 "url":"https://pink-slip.pages.dev/?utm_source=meta",
                 "budget":{"daily":5,"total_cap":50,"stop_when":"no plays after 200 clicks"}}
                """);
        write("campaigns/reddit.json", """
                {"headline":"A drag racing game where you bet your car","url":"https://pink-slip.pages.dev/?utm_source=reddit",
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
                "{\"text\":\"Win the other driver's car\",\"feature\":\"feature-pink-slip-wager\"}",
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
                .replace("A drag racing game", "\\\"Best racing game I have played all year\\\" — Dave. A drag racing game"));

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
                Pink Slip,Racing,The Most Exciting Drag Racing Game Ever,Short,https://pink-slip.pages.dev/?utm_source=google
                """);

        assertTrue(check().stream().anyMatch(p -> p.where().equals("google-search.csv row 1 Headline 1")
                && p.what().equals("39 characters; the limit is 30")), check().toString());
    }

    @Test
    @DisplayName("ads and posts must send people to the project's own site, tagged, with a budget that stops")
    void landingAndBudget() throws Exception {
        write("campaigns/meta.json", """
                {"headline":"Bet your car","primary_text":"Race.","url":"https://pink-slip.pages.dev/",
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
