package dev.fmangoni.forge.pictures.batch;

import dev.fmangoni.forge.pictures.model.PictureJob;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Reads a batch's job list: the cards a person reads, as the art tools' card makers write them.
 *
 * <p>Each job starts {@code ## Job N of M: Title}. Under it:
 * <ul>
 *   <li>{@code Only after `X` ...} names a job, by the start of its title, that must be kept first;
 *   <li>{@code Attach, in this order:} is followed by a numbered list of paths in backticks;
 *   <li>{@code Prompt:} is followed by the prompt in a fenced block;
 *   <li>{@code Save the picture as `path`} is followed by the fenced command that checks it;
 *   <li>{@code Review sheet: `path`} names what the check writes for a person to look at;
 *   <li>the fenced block after the review sheet, or after a line starting {@code Once kept}, is the
 *       command that places a kept picture;
 *   <li>a line starting {@code **STOP here.**} holds every later job until all up to it are kept.
 * </ul>
 * Everything else is said to the person and is left alone.
 */
@UtilityClass
public class JobCards {

    private final Pattern HEADING = Pattern.compile("^## Job (\\d+) of (\\d+): (.+)$");

    private final Pattern ITEM = Pattern.compile("^\\d+\\. `([^`]+)`");

    private final Pattern QUOTED = Pattern.compile("`([^`]+)`");

    private final String FENCE = "```";

    /**
     * The jobs, in list order.
     *
     * @throws IllegalArgumentException naming the first job that has no prompt, picture or check
     */
    public List<PictureJob> parse(String markdown) {
        List<String> lines = markdown.lines().toList();
        List<PictureJob> jobs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= lines.size(); i++) {
            if (i == lines.size() || HEADING.matcher(lines.get(i)).matches()) {
                if (start >= 0) {
                    jobs.add(card(lines.subList(start, i)));
                }
                start = i;
            }
        }
        return jobs;
    }

    /** One card, from its heading to the line before the next. */
    private PictureJob card(List<String> lines) {
        Matcher heading = HEADING.matcher(lines.get(0));
        heading.matches();
        String title = heading.group(3).strip();
        String after = null;
        boolean stop = false;
        List<String> attach = new ArrayList<>();
        String prompt = null;
        String draft = null;
        String check = null;
        String sheet = null;
        String place = null;
        // What the next fenced block holds, from the line that announced it: prompt, check or place.
        String next = "";
        boolean listing = false;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith(FENCE)) {
                int end = closing(lines, i);
                String block = String.join("\n", lines.subList(i + 1, end));
                switch (next) {
                    case "prompt" -> prompt = block;
                    case "check" -> check = block.strip();
                    case "place" -> place = block.strip();
                    default -> { }
                }
                next = "";
                i = end;
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (listing && item.find()) {
                attach.add(item.group(1));
                continue;
            }
            listing = line.isEmpty() && listing;
            if (line.startsWith("Attach, in this order")) {
                listing = true;
            } else if (line.startsWith("Only after ")) {
                after = quoted(line);
            } else if (line.equals("Prompt:")) {
                next = "prompt";
            } else if (line.startsWith("Save the picture as ")) {
                draft = quoted(line);
                next = "check";
            } else if (line.startsWith("Review sheet: ")) {
                sheet = quoted(line);
                next = "place";
            } else if (line.startsWith("Once kept")) {
                next = "place";
            } else if (line.startsWith("**STOP here.**")) {
                stop = true;
            }
        }
        String number = heading.group(1);
        if (prompt == null || draft == null || check == null) {
            throw new IllegalArgumentException("Job " + number + " (" + title + ") has no "
                    + (prompt == null ? "prompt" : draft == null ? "picture to save" : "check command"));
        }
        return new PictureJob(Integer.parseInt(number), Integer.parseInt(heading.group(2)), title, after, stop,
                attach, prompt, draft, check, sheet, place);
    }

    /** The line that closes the fence opened at {@code open}, or the end of the card when none does. */
    private int closing(List<String> lines, int open) {
        for (int i = open + 1; i < lines.size(); i++) {
            if (lines.get(i).strip().equals(FENCE)) {
                return i;
            }
        }
        return lines.size();
    }

    /** The first thing in backticks on a line, or null. */
    private String quoted(String line) {
        Matcher m = QUOTED.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
