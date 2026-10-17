package dev.fmangoni.forge.deploy;

import dev.fmangoni.forge.git.Git;
import dev.fmangoni.forge.process.CliRunner;
import dev.fmangoni.forge.process.ProcResult;
import dev.fmangoni.forge.process.local.ShellScript;
import dev.fmangoni.forge.project.Phase;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.review.decision.DecisionStore;
import dev.fmangoni.forge.review.decision.model.Decision;
import dev.fmangoni.forge.review.decision.model.ReviewItem;
import dev.fmangoni.forge.security.model.SecurityReport;
import dev.fmangoni.forge.spec.model.DeploySettings;
import dev.fmangoni.forge.spec.model.MonetizationSettings;
import dev.fmangoni.forge.spec.model.Spec;
import dev.fmangoni.forge.state.RunState;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Publishes an accepted project by running the deploy command its spec names — only when asked.
 *
 * <p>Nothing here holds a credential or runs a login. The command uses whatever CLI it names, logged
 * in beforehand by the person who owns the account; when that login is missing the deploy stops and
 * says so, and the fix is theirs to make. Nor is anything deployed on Forge's own initiative: this
 * runs from a button, after a confirmation that shows the exact command and commit.
 *
 * <p>This is also the only place ads can go live. The deploy build is given
 * {@code FORGE_ADS_MODE=live} and the spec's public ad ids; every gate, test and recording builds
 * without them, so nothing Forge runs routinely produces an impression on a real account.
 */
public final class Deployer {

    private static final Pattern URL = Pattern.compile("https://[^\\s\"'<>)\\]]+");

    /** What CLIs print when the account behind them is not signed in. */
    private static final Pattern NOT_LOGGED_IN = Pattern.compile(
            "(?i)not (?:logged|signed) in|please (?:run \\S+ )?log ?in|login required|you must (?:be )?(?:logged|signed) in"
                    + "|not authenticated|authentication (?:required|failed)|no credentials|unauthori[sz]ed");

    /** Only characters an ad id actually uses, so nothing in a spec can smuggle shell syntax in. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:\\-]{1,100}");

    private final Spec spec;
    private final RunPaths paths;
    private final Git git;
    private final Map<String, String> environment;

    public Deployer(Spec spec, RunPaths paths, Git git, Map<String, String> environment) {
        this.spec = spec;
        this.paths = paths;
        this.git = git;
        this.environment = environment;
    }

    /**
     * Why Deploy cannot be pressed right now, or null when it can.
     *
     * <p>Each reason is a sentence for the card, because a disabled button with no explanation is a
     * button someone clicks repeatedly.
     */
    public String blockedReason(RunState state) {
        if (spec.deploy().isEmpty()) {
            return "the spec has no deploy command";
        }
        if (state == null || state.getPhase() != Phase.ACCEPTED) {
            return "only an accepted project can be deployed";
        }
        if (state.getLastFailure() != null) {
            return "its last gate did not pass";
        }
        SecurityReport security = SecurityReport.load(paths.securityFile());
        if (security == null) {
            return "run the security check before deploying it";
        }
        String unresolved = unresolvedSecurity(security);
        if (unresolved != null) {
            return unresolved;
        }
        List<String> missing = spec.deploy().get().getRequires().stream()
                .filter(name -> !Executables.onPath(name, environment))
                .toList();
        if (!missing.isEmpty()) {
            return String.join(", ", missing) + (missing.size() == 1 ? " is" : " are")
                    + " not installed or not on PATH — install it and log in yourself";
        }
        return null;
    }

    /**
     * Security findings that still stand in the way: unruled, or sent back.
     *
     * <p>A ruling saved on an accepted project's report counts. Submissions are written before any
     * refusal, so a person can rule on a finding a later check turned up without reopening anything.
     */
    private String unresolvedSecurity(SecurityReport security) {
        Map<String, Decision> ruled = DecisionStore.load(paths.decisionsFile()).stream()
                .collect(Collectors.toMap(Decision::getItemId, d -> d, (a, b) -> b));
        List<ReviewItem> items = ReviewItem.of(null, null, security);
        long unruled = items.stream().filter(item -> !ruled.containsKey(item.id())).count();
        long sentBack = items.stream().filter(item -> ruled.containsKey(item.id())
                && ruled.get(item.id()).wantsChanges()).count();
        if (unruled > 0) {
            return unruled + " security finding(s) need a ruling on the report first";
        }
        if (sentBack > 0) {
            return sentBack + " security finding(s) were sent back and have not been fixed";
        }
        return null;
    }

    /** Runs the deploy command, checks the result, and records it. Never throws for a failed deploy. */
    public Deployment deploy() {
        DeploySettings settings = spec.deploy().orElseThrow();
        int number = Deployment.history(paths.deploymentsFile()).size() + 1;
        String commit = git.head();
        boolean dirty = git.isDirty();
        Instant started = Instant.now();
        Path log = paths.deployDir().resolve("deploy-" + number + ".log");

        Path script = ShellScript.write(paths.deployDir(), "deploy-" + number, withEnvironment(settings.getCommand()));
        List<String> invocation = ShellScript.invocation(script);
        ProcResult result = CliRunner.run(Path.of(invocation.get(0)), invocation.subList(1, invocation.size()),
                paths.repo(), null, spec.gateTimeout(), log);
        String output = result.combined();

        Deployment deployment;
        if (!result.ok()) {
            boolean login = NOT_LOGGED_IN.matcher(output).find();
            deployment = new Deployment(number, commit, dirty, started, Instant.now(), result.exitCode(),
                    login ? Deployment.NOT_LOGGED_IN : Deployment.FAILED,
                    login ? "the deploy CLI is not logged in — run its login command yourself, then deploy again"
                            : result.timedOut() ? "timed out after " + spec.gateTimeout().toMinutes() + " minutes"
                            : "the deploy command exited " + result.exitCode() + "; the log has its output",
                    "", "skipped", log.toString());
        } else {
            String url = settings.getUrl().isBlank() ? firstUrl(output) : settings.getUrl().strip();
            String smoke = smoke(settings, url);
            boolean healthy = !smoke.startsWith("failed");
            deployment = new Deployment(number, commit, dirty, started, Instant.now(), 0,
                    healthy ? Deployment.DEPLOYED : Deployment.UNHEALTHY,
                    healthy ? "" : "deployed, but the health check failed: " + smoke.replaceFirst("^failed:?\\s*", ""),
                    url, healthy ? smoke : "failed", log.toString());
        }
        Deployment.append(paths.deploymentsFile(), deployment);
        return deployment;
    }

    /**
     * The command with Forge's deploy variables set first.
     *
     * <p>{@code FORGE_ADS_MODE} is {@code live} only when the spec asks for live ads; the public ids
     * go in as {@code FORGE_AD_<NAME>}, and any id with characters an id never has is left out
     * rather than written into a script.
     */
    String withEnvironment(String command) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("FORGE_DEPLOY", "1");
        vars.put("FORGE_ADS_MODE", spec.adsMode());
        MonetizationSettings money = spec.front().getMonetization();
        if (money != null && "live".equals(spec.adsMode())) {
            money.getIds().forEach((key, value) -> {
                String name = key.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
                if (value != null && SAFE_ID.matcher(value).matches()) {
                    vars.put("FORGE_AD_" + name, value);
                }
            });
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        List<String> lines = new ArrayList<>();
        vars.forEach((key, value) -> lines.add(windows ? "set \"" + key + "=" + value + "\"" : "export " + key + "='" + value + "'"));
        lines.add(command);
        return String.join("\n", lines);
    }

    static String firstUrl(String output) {
        Matcher m = URL.matcher(output == null ? "" : output);
        String found = "";
        // The last one printed: CLIs announce the dashboard or docs first and the deployed site last.
        while (m.find()) {
            found = m.group().replaceAll("[.,;:]+$", "");
        }
        return found;
    }

    /** {@code ok …}, {@code failed …} or {@code skipped …}, with what was checked. */
    private String smoke(DeploySettings settings, String url) {
        if (!settings.getCheck().isBlank()) {
            Path script = ShellScript.write(paths.deployDir(), "deploy-check", settings.getCheck());
            List<String> invocation = ShellScript.invocation(script);
            ProcResult check = CliRunner.run(Path.of(invocation.get(0)), invocation.subList(1, invocation.size()),
                    paths.repo(), null, Duration.ofMinutes(5), paths.deployDir().resolve("deploy-check.log"));
            return check.ok() ? "ok: " + settings.getCheck() : "failed: the check command exited " + check.exitCode();
        }
        if (url.isBlank()) {
            return "skipped: no URL was given or printed";
        }
        return fetch(url);
    }

    /**
     * GETs the deployed URL a few times, since a CDN can take a moment to serve a new deploy.
     *
     * <p>This is a request to the person's own site, the one they just asked to publish.
     */
    private static String fetch(String url) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        String last = "";
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                HttpResponse<Void> response = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 400) {
                    return "ok: " + url + " answered " + response.statusCode();
                }
                last = url + " answered " + response.statusCode();
            } catch (IOException | IllegalArgumentException e) {
                last = url + ": " + e.getMessage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "failed " + url + ": interrupted";
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return "failed " + last;
    }

    /**
     * HEAD's commit, read from the files in {@code .git} rather than by running git.
     *
     * <p>Asked on every dashboard poll for every deployed project, where a process per card per four
     * seconds is the kind of cost that was taken out of the poll once already. Null when it cannot
     * tell — a detached or packed layout it does not follow — which reads as "not stale".
     */
    public static String headOf(Path repo) {
        try {
            Path git = repo.resolve(".git");
            String head = Files.readString(git.resolve("HEAD")).strip();
            if (!head.startsWith("ref: ")) {
                return head;
            }
            String ref = head.substring(5).strip();
            Path loose = git.resolve(ref);
            if (Files.isRegularFile(loose)) {
                return Files.readString(loose).strip();
            }
            Path packed = git.resolve("packed-refs");
            if (Files.isRegularFile(packed)) {
                for (String line : Files.readAllLines(packed)) {
                    if (line.endsWith(" " + ref)) {
                        return line.substring(0, line.indexOf(' '));
                    }
                }
            }
        } catch (IOException e) {
            // Unknown, not stale.
        }
        return null;
    }

    /** Whether HEAD has moved on since the last successful deploy. */
    public static boolean stale(Deployment last, String head) {
        return last != null && head != null && !head.equals(last.commit());
    }

    /** Names of the deploy-time variables, for the house rules and the confirmation dialog. */
    public static final Set<String> VARIABLES = Set.of("FORGE_DEPLOY", "FORGE_ADS_MODE", "FORGE_AD_<NAME>");
}
