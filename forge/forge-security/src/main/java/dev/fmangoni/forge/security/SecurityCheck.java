package dev.fmangoni.forge.security;

import dev.fmangoni.forge.agent.Agents;
import dev.fmangoni.forge.agent.model.AgentKind;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.local.LocalServer;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.security.ai.SecurityReview;
import dev.fmangoni.forge.security.layer.DependencyAudit;
import dev.fmangoni.forge.security.layer.LiveProbe;
import dev.fmangoni.forge.security.layer.SecretScan;
import dev.fmangoni.forge.security.layer.Shell;
import dev.fmangoni.forge.security.layer.StaticAnalysis;
import dev.fmangoni.forge.security.model.LayerResult;
import dev.fmangoni.forge.security.model.LayerRun;
import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.security.osv.OsvClient;
import dev.fmangoni.forge.security.session.SessionAudit;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs every security layer over the project as it stands at HEAD, and keeps the report.
 *
 * <p>The four local layers always run: they cost nothing but time. The AI layer is a call against a
 * subscription, so it is skipped when the previous report already had it read this exact code —
 * the pipeline passes the gate again after every repair, and paying to re-read unchanged code is
 * the kind of spend nobody notices until the window runs out.
 *
 * <p>No layer failing stops the others. A dev server that will not start still leaves the secrets
 * and dependencies checked, and the report shows which layer did not run and why.
 */
public final class SecurityCheck {

    /** Long enough for {@code npm run build && npm run preview}; a server slower than this is broken. */
    private static final Duration SERVE_READY = Duration.ofMinutes(3);

    private final Agents agents;
    private final OsvClient osv;

    public SecurityCheck(Agents agents, OsvClient osv) {
        this.agents = agents;
        this.osv = osv;
    }

    public SecurityReport run(Spec spec, RunPaths paths, Git git, AgentKind builder, String baseline,
                              int diffCap) {
        String head = git.head();
        String tree = git.tree();
        SecurityReport previous = SecurityReport.load(paths.securityFile());
        Shell shell = Shell.in(paths.repo(), paths.securityDir(), spec.gateTimeout());

        List<LayerResult> layers = new ArrayList<>();
        layers.add(guarded(SecretScan.LAYER, () -> new SecretScan(paths.repo(), git).run()));
        layers.add(guarded(DependencyAudit.LAYER,
                () -> new DependencyAudit(paths.repo(), paths.securityDir(), shell, osv).run()));
        layers.add(guarded(StaticAnalysis.LAYER,
                () -> new StaticAnalysis(paths.repo(), git, shell, paths.securityDir()).run()));
        layers.add(guarded(LiveProbe.LAYER, () -> probe(spec, paths)));

        LayerResult ai = previousAi(previous, tree);
        if (ai == null) {
            List<SecurityFinding> scanned = layers.stream().flatMap(l -> l.findings().stream()).toList();
            String diff = baseline == null || baseline.isBlank() ? "" : git.reviewDiff(baseline, "HEAD", diffCap);
            ai = guarded(SecurityReview.LAYER,
                    () -> new SecurityReview(agents, paths.repo(), paths.logs()).run(spec, builder, diff, scanned));
        }
        layers.add(ai);
        layers.add(guarded(SessionAudit.LAYER, () -> new SessionAudit(paths.logs(),
                List.of(paths.repo(), paths.designDir(), paths.marketingDir()),
                Path.of(System.getProperty("java.io.tmpdir"))).run()));

        SecurityReport report = new SecurityReport(head, tree, layers);
        report.save(paths.securityFile());
        return report;
    }

    /** The AI layer from a report on this same code, when it actually ran; otherwise null. */
    private static LayerResult previousAi(SecurityReport previous, String tree) {
        if (previous == null || !tree.equals(previous.getTree())) {
            return null;
        }
        return previous.getLayers().stream()
                .filter(layer -> SecurityReview.LAYER.equals(layer.layer()) && LayerResult.RAN.equals(layer.status()))
                .findFirst()
                .orElse(null);
    }

    private static LayerResult probe(Spec spec, RunPaths paths) {
        if (spec.serve().isEmpty()) {
            return LayerResult.skipped(LiveProbe.LAYER, "the spec has no 'serve' command, so there is nothing running to probe");
        }
        try (LocalServer server = LocalServer.start(paths.repo(), spec.serve().get(), paths.securityDir(), SERVE_READY)) {
            return new LiveProbe(server.base(), spec.servePath()).run();
        } catch (IOException e) {
            return LayerResult.failed(LiveProbe.LAYER, "the app could not be started: " + e.getMessage());
        }
    }

    private static LayerResult guarded(String name, LayerRun layer) {
        try {
            return layer.run();
        } catch (Exception e) {
            return LayerResult.failed(name, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
