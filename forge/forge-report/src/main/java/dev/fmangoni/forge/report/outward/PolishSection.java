package dev.fmangoni.forge.report.outward;

import dev.fmangoni.forge.design.DesignOption;
import dev.fmangoni.forge.design.DesignStudio;
import dev.fmangoni.forge.design.PolishRecord;
import dev.fmangoni.forge.project.RunPaths;
import dev.fmangoni.forge.report.shell.Html;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import lombok.experimental.UtilityClass;

/**
 * What a polish was asked to look like, and what it looked like before and after.
 *
 * <p>Three pictures, side by side where there are three: the design that was chosen, the program
 * before, and the program after. Together they answer the only question a polish raises — did it
 * come out like the thing that was picked — which neither a passing gate nor a review can.
 *
 * <p>The two screenshots are the agent's own, taken because it was asked to. Nothing checks them,
 * and the section says so rather than letting two pictures stand as proof. It also says when the
 * "after" is older than the "before", which is what a polish that stopped half way leaves behind.
 */
@UtilityClass
public class PolishSection {

    private final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    public String render(RunPaths paths) {
        Path before = paths.repo().resolve(DesignStudio.PICTURE_BEFORE);
        Path after = paths.repo().resolve(DesignStudio.PICTURE_AFTER);
        PolishRecord record = PolishRecord.load(paths.polishFile());
        Optional<DesignOption> chosen = record == null ? Optional.empty() : Optional.ofNullable(record.option());
        boolean hasBefore = Files.isRegularFile(before);
        boolean hasAfter = Files.isRegularFile(after);
        if (!hasBefore && !hasAfter && chosen.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder("<h2 id=\"polish\">Polish</h2><div class=\"card\">");
        chosen.ifPresent(option -> out.append(chosenLine(option, record.chosenAt())));
        out.append("<div class=\"polish-pair\">");
        chosen.flatMap(option -> design(paths, option)).ifPresent(out::append);
        out.append(hasBefore ? picture(paths, before, "Before") : missing("Before", "No picture was taken before the work."));
        out.append(hasAfter ? picture(paths, after, "After") : missing("After", chosen.isPresent() || hasBefore
                ? "No picture has been taken since. The polish has not finished, or the agent did not take one."
                : "No picture yet."));
        out.append("</div>");

        if (hasBefore && hasAfter && modified(after).isBefore(modified(before))) {
            out.append("<p class=\"decide-blocked\">The “after” picture is older than the “before” one, so it is "
                    + "from an earlier polish and does not show the latest work.</p>");
        }
        return out.append("<p class=\"muted\">The two screenshots were taken by the agent that did the work, "
                + "because it was asked to. Nothing checks them. Press Play on the project's card to see it "
                + "for yourself.</p></div>").toString();
    }

    private String chosenLine(DesignOption option, Instant chosenAt) {
        String pitch = option.pitch() == null || option.pitch().isBlank() ? "" : " — " + Html.escape(option.pitch());
        return "<p>Asked to look like <strong>" + Html.escape(option.name()) + "</strong>, drawn by "
                + Html.escape(option.vendor()) + pitch
                + (chosenAt == null ? "" : ". Chosen " + Html.escape(WHEN.format(chosenAt))) + ".</p>";
    }

    /**
     * The mock-up itself, framed. Scripts off: it was written by a model.
     *
     * <p>The copy in the repository first, which is the one the agent was given and which no later
     * request for designs removes; the original in the design folder if that copy has gone.
     */
    private Optional<String> design(RunPaths paths, DesignOption option) {
        Path copy = paths.repo().resolve(DesignStudio.CHOSEN_IN_REPO);
        Path original = paths.root().resolve(option.file()).normalize();
        boolean originalOk = original.startsWith(paths.designDir().normalize()) && Files.isRegularFile(original);
        Path shown = Files.isRegularFile(copy) ? copy : originalOk ? original : null;
        if (shown == null) {
            return Optional.empty();
        }
        String href = Html.escape(paths.root().relativize(shown).toString().replace('\\', '/'));
        return Optional.of("<figure><div class=\"polish-frame\"><iframe sandbox=\"\" loading=\"lazy\" title=\"The chosen design\" "
                + "src=\"" + href + "\"></iframe></div><figcaption>The design you chose · <a href=\"" + href
                + "\" target=\"_blank\">open it</a></figcaption></figure>");
    }

    private String picture(RunPaths paths, Path file, String label) {
        String href = Html.escape(paths.root().relativize(file).toString().replace('\\', '/'));
        return "<figure><a href=\"" + href + "\" target=\"_blank\"><img src=\"" + href + "\" alt=\"" + label
                + " the polish\"></a><figcaption>" + label + " · " + Html.escape(WHEN.format(modified(file)))
                + "</figcaption></figure>";
    }

    private String missing(String label, String why) {
        return "<figure><div class=\"polish-missing\">" + Html.escape(why) + "</div><figcaption>" + label
                + "</figcaption></figure>";
    }

    private Instant modified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }
}
