package dev.fmangoni.forge.pipeline.run;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parts of the house rules that apply only to some projects.
 *
 * <p>Marked in {@code house-rules.md} as {@code <!-- forge:only ads -->} … {@code <!-- forge:end -->}.
 * How to build ads is a page of instructions that matter to the projects with ads and are noise in
 * every turn of every other one, and Forge knows from the spec which is which — more reliably than an
 * agent deciding whether to load a skill. A section is kept for a project the condition holds for,
 * and dropped for the rest, markers and all.
 *
 * <p>An unknown name keeps its section: a typo in a marker must never be how a rule quietly
 * disappears from every project.
 */
final class RuleSections {

    /** The conditions Forge can decide from a spec. */
    static final Set<String> KNOWN = Set.of("ads", "deploy");

    private static final Pattern SECTION = Pattern.compile(
            "<!-- forge:only ([a-z-]+) -->\\R?(.*?)<!-- forge:end -->\\R?", Pattern.DOTALL);

    private RuleSections() {
    }

    /** The rules for a project to which the conditions in {@code applying} hold. */
    static String forProject(String rules, Set<String> applying) {
        Matcher section = SECTION.matcher(rules);
        StringBuilder out = new StringBuilder();
        while (section.find()) {
            String name = section.group(1);
            boolean keep = applying.contains(name) || !KNOWN.contains(name);
            section.appendReplacement(out, Matcher.quoteReplacement(keep ? section.group(2) : ""));
        }
        section.appendTail(out);
        return out.toString();
    }

    /** Every section kept, markers removed: for when there is no spec to decide by. */
    static String all(String rules) {
        return forProject(rules, KNOWN);
    }
}
