package dev.fmangoni.forge.report;

import dev.fmangoni.forge.deploy.Deployment;
import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.outward.DeploymentsSection;
import dev.fmangoni.forge.report.outward.MarketingSection;
import dev.fmangoni.forge.report.outward.PolishSection;
import dev.fmangoni.forge.report.outward.SecuritySection;
import dev.fmangoni.forge.report.review.DecisionSection;
import dev.fmangoni.forge.report.review.ReviewSection;
import dev.fmangoni.forge.report.section.ApproachSection;
import dev.fmangoni.forge.report.section.ChangesSection;
import dev.fmangoni.forge.report.section.EvidenceSection;
import dev.fmangoni.forge.report.section.FeatureGallery;
import dev.fmangoni.forge.report.section.LimitationsSection;
import dev.fmangoni.forge.report.section.TimingsSection;
import dev.fmangoni.forge.report.section.VerdictSection;
import dev.fmangoni.forge.report.shell.Html;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.model.Review;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import lombok.RequiredArgsConstructor;

/**
 * One project's page: what happened, what was checked, and what a human should look at.
 *
 * <p>Written for a two-minute skim. The reader needs to know whether to trust the result, so the
 * page leads with the acceptance commands and their exit codes — the evidence — and treats the
 * agent's own account as commentary rather than as the finding.
 *
 * <p>This class only puts the sections in order. Each is its own class, and the order is the
 * argument: verdict, then what it does, then how, then what it does not prove.
 */
@RequiredArgsConstructor
public final class ProjectReport {

    private static final String WHERE_TO_LOOK = """
            <h2>Where to look</h2>
            <ul>
              <li><a href="repo/">repo/</a> — the project, with a commit per attempt</li>
              <li><a href="logs/">logs/</a> — agent transcripts and gate output</li>
              <li><a href="state.json">state.json</a> — the run's recorded state</li>
            </ul>
            """;

    private final RunPaths paths;
    private final RunState state;
    private final Review review;

    public void write() {
        String body = VerdictSection.render(state)
                + features()
                + ApproachSection.render(paths, state)
                + ChangesSection.render(paths, state)
                + LimitationsSection.render(state)
                + TimingsSection.render(state)
                + EvidenceSection.render(paths, state)
                + PolishSection.render(paths)
                + SecuritySection.render(SecurityReport.load(paths.securityFile()))
                + DeploymentsSection.render(Deployment.history(paths.deploymentsFile()))
                + MarketingSection.render(paths, state.getId())
                + ReviewSection.render(review)
                + DecisionSection.render(paths, state, review)
                + WHERE_TO_LOOK;
        try {
            Files.writeString(paths.reportFile(), Html.page(state.getTitle() + " — Forge", body), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + paths.reportFile(), e);
        }
    }

    /**
     * What the project does, each thing with the clip that shows it.
     *
     * <p>Second on the page, under the verdict. A recording is the only part of this report that
     * answers "is it any good" rather than "did it build", and burying it under the timings table
     * would be putting the machine's reading ahead of the person's.
     */
    private String features() {
        return FeatureGallery.render(
                Feature.of(state.getPlan()),
                Feature.ideas(state.getPlan()),
                DecisionStore.load(paths.featuresFile()),
                paths.repo(),
                paths.evidence(),
                paths.root());
    }
}
