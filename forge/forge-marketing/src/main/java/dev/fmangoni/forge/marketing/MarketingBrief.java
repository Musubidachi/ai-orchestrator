package dev.fmangoni.forge.marketing;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.spec.model.Spec;
import java.util.List;
import java.util.Map;
import lombok.experimental.UtilityClass;

/**
 * The marketing kit prompt: launch copy, a landing page and ad campaign drafts for a finished project.
 *
 * <p>The constraints are the point of the brief. A model asked to sell something will round up — a
 * feature half built becomes a headline, a project with no users acquires "thousands". The kit is
 * checked afterwards by rule, and the brief says exactly what the check will refuse so the first
 * draft is the usable one.
 */
@UtilityClass
public class MarketingBrief {

    /**
     * @param recordings feature id to the file name of its clip under {@code media/}, when there is one
     * @param deployUrl  the project's live address, or blank when it is not deployed
     */
    public String of(Spec spec, List<Feature> approved, Map<String, String> recordings, String digest,
                     String deployUrl) {
        StringBuilder features = new StringBuilder();
        for (Feature feature : approved) {
            features.append("- `").append(feature.id()).append("` — ").append(feature.name());
            if (!feature.demo().isBlank()) {
                features.append(": ").append(feature.demo());
            }
            String clip = recordings.get(feature.id());
            features.append(clip == null ? " (no recording)" : " (recording: `media/" + clip + "`)").append('\n');
        }
        String site = deployUrl.isBlank()
                ? "The project is not deployed yet, so there is no address to send anyone to. Write the copy "
                        + "and campaigns anyway, using `https://example.invalid/` wherever the address goes; the "
                        + "kit will be marked as needing a deploy before it can be used."
                : "The project is live at " + deployUrl + ". Every link in everything you write goes there, "
                        + "with a `utm_source` naming where the link appears (`utm_source=x`, `utm_source=google`).";

        return """
                Write a marketing kit for the finished project below. You are in an empty folder that \
                contains only `media/` (recordings of the features working). Write every file into this \
                folder. Do not open, modify or run the project itself, do not publish, post or upload \
                anything, and do not log in to anything — a person reviews this kit and does all of \
                that themselves.

                ## What must be true of everything you write

                - **Say only what the project does.** Every capability you mention must be one of the \
                approved features listed below, and must be listed in `claims` with that feature's id. \
                Something not on the list is not in the product, however natural it would be to mention.
                - **Invent nothing.** The project is new: it has no users, downloads, ratings, reviews, \
                awards, press coverage or testimonials. Do not write any — not "loved by", not "trusted \
                by", not a star rating, not a quote attributed to anyone. They will be caught and the \
                kit refused.
                - **Link only to the project's own site.** %s
                - Plain, specific language about what someone can do with it. No superlatives it cannot \
                support ("the best", "revolutionary").

                ## Files to write

                1. `kit.json`:
                   {"tagline":"under 60 characters",
                    "short_description":"at most 80 characters",
                    "long_description":"two or three short paragraphs",
                    "keywords":["..."],
                    "claims":[{"text":"what the copy says it does","feature":"feature-..."}],
                    "posts":{"x":"at most 280 characters",
                             "reddit":{"title":"at most 300 characters","body":"..."},
                             "hacker_news":{"title":"at most 80 characters, starting 'Show HN: '"},
                             "linkedin":"..."},
                    "press":"a short factual paragraph a journalist could quote"}
                2. `site/index.html` — a single self-contained landing page: inline CSS, no external \
                scripts, fonts or trackers. Show the recordings from `media/` (as `../media/<file>`) \
                next to the features they demonstrate, and one clear link to the project.
                3. `campaigns/google-search.csv` — Google Ads Editor import format with columns \
                `Campaign,Ad group,Headline 1,Headline 2,Headline 3,Description 1,Description 2,Final URL` \
                (headlines at most 30 characters, descriptions at most 90; quote any field with a comma).
                4. `campaigns/meta.json` — {"headline":"at most 40","primary_text":"at most 125","url":"...", \
                "audience":"who, in words","placements":["..."],"budget":{"daily":5,"total_cap":50, \
                "stop_when":"the result, or lack of one, that should end it"}}
                5. `campaigns/reddit.json` — {"headline":"at most 300","subreddits":["..."],"url":"...", \
                "budget":{...as above}}

                Budgets are suggestions for a first test of a new project: small daily amounts in US \
                dollars, a hard total cap, and a concrete reason to stop. Nothing will be spent unless the \
                person uploads a campaign and funds it themselves.

                Count characters for every limited field before you finish. A field over its limit is \
                reported, not shortened for you.

                ## The project

                # %s

                %s

                ## Approved features

                %s
                ## What the builder says about it

                %s
                """.formatted(site, spec.title(), spec.body(), features.isEmpty() ? "(none approved)\n" : features,
                digest == null || digest.isBlank() ? "(no notes)" : digest);
    }
}
