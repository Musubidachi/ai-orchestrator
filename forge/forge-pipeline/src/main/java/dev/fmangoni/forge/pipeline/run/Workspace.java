package dev.fmangoni.forge.pipeline.run;

import dev.fmangoni.forge.feature.Feature;
import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.spec.model.Spec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Prepares the directory a project is built in.
 *
 * <p>The scaffold is committed before the agent runs. That baseline commit is what every later
 * stage measures against: the reviewer diffs the agent's work against it, and a project that goes
 * wrong is one {@code git reset} from a clean start without re-deriving the spec.
 */
public final class Workspace {

    private final RunPaths paths;
    private final String houseRules;

    /** Forge's {@code skills/} folder, or null when there are none to install; see {@link SkillInstall}. */
    private final Path skills;

    public Workspace(RunPaths paths, String houseRules) {
        this(paths, houseRules, null);
    }

    public Workspace(RunPaths paths, String houseRules, Path skills) {
        this.paths = paths;
        this.houseRules = houseRules;
        this.skills = skills;
    }

    /**
     * Creates the layout, writes the agent-facing files, and commits them.
     *
     * @return the baseline commit SHA
     */
    public String scaffold(Spec spec) {
        createDirectories();
        writeAgentFiles(spec);

        Git git = git();
        git.init();
        git.keepOutOfHistory(Feature.RECORDINGS);
        keepSkillsOutOfHistory(git);
        return git.commitAll("Scaffold: " + spec.title());
    }

    /**
     * Prepares an improvement to an existing project.
     *
     * <p>Deliberately does not scaffold. The repository already exists, already has a history, and
     * already carries the original {@code SPEC.md} — which stays exactly where it is, because an
     * agent asked to improve something needs to know what it was originally for. The request is
     * written alongside it as {@code IMPROVEMENT.md}.
     *
     * @return the commit the improvement starts from, so the reviewer diffs only the change
     */
    public String scaffoldImprovement(Spec spec, List<String> effectiveAcceptance) {
        create(paths.logs());
        create(paths.evidence());

        write(paths.repo().resolve("IMPROVEMENT.md"), improvementDocument(spec, effectiveAcceptance));
        return git().commitAll("Requested improvement: " + spec.title());
    }

    private String improvementDocument(Spec spec, List<String> effectiveAcceptance) {
        StringBuilder out = new StringBuilder();
        out.append("# Improvement: ").append(spec.title()).append("\n\n");
        out.append("This is a change request against the project already in this repository. ")
                .append("`SPEC.md` describes what it was originally built to do; read it first, ")
                .append("and do not undo it.\n\n");
        out.append(spec.body()).append("\n\n");
        out.append("## Acceptance\n\n");
        out.append("Every command below is run by the orchestrator to decide whether this change ")
                .append("is accepted — including the original project's, which must keep passing. ")
                .append("A change that delivers the new behaviour by breaking the old one is not ")
                .append("an improvement.\n\n");
        for (String command : effectiveAcceptance) {
            out.append("- `").append(command).append("`\n");
        }
        return out.toString();
    }

    public Git git() {
        return new Git(paths.repo(), paths.gitLog());
    }

    private void createDirectories() {
        create(paths.repo());
        create(paths.logs());
        create(paths.evidence());
    }

    /**
     * Writes the spec and the house rules under both filenames the two CLIs look for, so neither
     * agent gets a weaker brief than the other purely because of what its tooling reads.
     */
    private void writeAgentFiles(Spec spec) {
        write(paths.repo().resolve("SPEC.md"), specDocument(spec));
        refreshRules(spec);
        write(paths.repo().resolve(".gitignore"), GITIGNORE);
    }

    /**
     * Brings the workspace's copy of the house rules up to date, before every turn.
     *
     * <p>They were written once, when the project was scaffolded, which meant a rule added to
     * {@code house-rules.md} afterwards applied to projects started afterwards and to nothing
     * else. The six projects on this machine were each carrying the rules as they stood on the day
     * they began.
     *
     * <p>Only the rules, not {@code SPEC.md}. The spec is the contract the project is being judged
     * against, and quietly rewriting it under a build in progress would change what "done" means
     * halfway through — the rules are how the work should be done, which is a different kind of
     * instruction and safe to correct at any point.
     */
    public void refreshRules() {
        String rules = RuleSections.all(houseRules);
        write(paths.repo().resolve("CLAUDE.md"), rules);
        write(paths.repo().resolve("AGENTS.md"), rules);
        installSkills();
    }

    /**
     * The house rules, followed by this project's own serve, deploy and ads settings.
     *
     * <p>The settings go here rather than into {@code SPEC.md} because that file is written once,
     * when the project starts, and these can be added to the spec at any time — a project gains a
     * deploy command after it is accepted. The rules file is rewritten before every turn, so the
     * agent always reads the settings as the spec states them now.
     */
    public void refreshRules(Spec spec) {
        String rules = RuleSections.forProject(houseRules, conditions(spec)) + projectSettings(spec);
        write(paths.repo().resolve("CLAUDE.md"), rules);
        write(paths.repo().resolve("AGENTS.md"), rules);
        installSkills();
    }

    /** Which of the house rules' conditional sections ({@link RuleSections}) this project gets. */
    static Set<String> conditions(Spec spec) {
        Set<String> holding = new HashSet<>();
        if (!"none".equals(spec.adsMode())) {
            holding.add("ads");
        }
        if (spec.deploy().isPresent()) {
            holding.add("deploy");
        }
        return holding;
    }

    /**
     * The skills, copied in beside the rules and kept out of git: they are Forge's, rewritten every
     * turn, and would otherwise land in the project's history and its review diff.
     */
    private void installSkills() {
        if (skills == null) {
            return;
        }
        SkillInstall.into(paths.repo(), skills);
        if (Files.isDirectory(paths.repo().resolve(".git"))) {
            keepSkillsOutOfHistory(git());
        }
    }

    private static void keepSkillsOutOfHistory(Git git) {
        for (String folder : SkillInstall.FOLDERS) {
            // No leading slash: git rm takes it as a path outside the repository. With a slash in the
            // middle the pattern is anchored to the repository root anyway.
            git.keepOutOfHistory(folder + "/");
        }
    }

    /**
     * Brings the repository's copy of the spec up to date with the wishlist, before every turn.
     *
     * <p>It used to be written once, when the project started, so that "done" could not shift under
     * a build in progress. That protected the project from the one person entitled to change it:
     * 003's spec was edited to allow a rewarded ad that gave a car, and six sessions in a row read
     * the old copy, refused to build it, and asked the person to decline the feature they had just
     * made room for. A change the person makes is a change to the contract, so it is made here, in
     * its own commit, and the agent is told about it rather than left to notice.
     *
     * <p>Only an existing copy is refreshed: before the project has started there is nothing to
     * bring up to date, and the scaffold writes the first one.
     *
     * @return the file and what changed in it, or empty when nothing did
     */
    public Optional<SpecChange> refreshSpec(Spec spec, List<String> effectiveAcceptance) {
        String name = spec.isImprovement() ? "IMPROVEMENT.md" : "SPEC.md";
        Path file = paths.repo().resolve(name);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String current = spec.isImprovement() ? improvementDocument(spec, effectiveAcceptance) : specDocument(spec);
        if (normalised(read(file)).equals(normalised(current))) {
            return Optional.empty();
        }
        Git git = git();
        String before = git.head();
        write(file, current);
        git.commitAll("The spec was changed: " + name + " brought up to date with it");
        return Optional.of(new SpecChange(name, git.diffOf(before, "HEAD", name)));
    }

    /** Line endings and trailing space are not a change anybody made. */
    private static String normalised(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    static String projectSettings(Spec spec) {
        StringBuilder out = new StringBuilder();
        spec.serve().ifPresent(serve -> out.append("- Serve command (see Security): `").append(serve)
                .append("`, page `").append(spec.servePath()).append("`\n"));
        spec.deploy().ifPresent(deploy -> out.append("- Deployed by the person, never by you, with: `")
                .append(deploy.getCommand()).append("`")
                .append(deploy.getUrl().isBlank() ? "" : " to " + deploy.getUrl()).append("\n"));
        if (!"none".equals(spec.adsMode())) {
            var money = spec.front().getMonetization();
            out.append("- Ads (see Ads): mode `").append(spec.adsMode()).append("` in deploys, test slots everywhere else")
                    .append(money.getProvider().isBlank() ? "" : ", provider `" + money.getProvider() + "`");
            if (!money.getIds().isEmpty()) {
                out.append(", ids read from ").append(String.join(", ", money.getIds().keySet().stream()
                        .map(key -> "`FORGE_AD_" + key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_") + "`")
                        .toList()));
            }
            out.append("\n");
        }
        return out.isEmpty() ? "" : "\n## This project's settings\n\n" + out;
    }

    /**
     * Keeps build output out of git.
     *
     * <p>Not merely tidiness: a dirty working tree is how Forge recognises a turn interrupted
     * before its commit, and a stray {@code target/} after every gate would make that signal
     * meaningless.
     */
    private static final String GITIGNORE = """
            # Java
            target/
            build/
            .gradle/
            *.class

            # Node
            node_modules/
            dist/
            .next/
            playwright-report/
            test-results/

            # Python
            __pycache__/
            *.py[cod]
            .pytest_cache/
            .mypy_cache/
            .ruff_cache/
            .venv/
            venv/
            *.egg-info/

            # Common
            coverage/
            *.log
            """;

    /** The spec as the agent sees it: the prose, with the acceptance contract stated explicitly. */
    private String specDocument(Spec spec) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(spec.title()).append("\n\n");
        out.append(spec.body()).append("\n\n");
        out.append("## Acceptance\n\n");
        out.append("These commands are run by the orchestrator, from the root of this repository, ")
                .append("to decide whether this project is done. Your own report of success is not ")
                .append("consulted. Do not weaken them to make them pass.\n\n");
        for (String command : spec.acceptance()) {
            out.append("- `").append(command).append("`\n");
        }
        if (!spec.evidence().isEmpty()) {
            out.append("\n## Evidence\n\nProduce these artefacts; a human will look at them.\n\n");
            for (String glob : spec.evidence()) {
                out.append("- `").append(glob).append("`\n");
            }
        }
        return out.toString();
    }

    private void create(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + dir, e);
        }
    }

    private void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
