package dev.fmangoni.forge.tour.say;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * What one line of Java does, in a clause.
 *
 * <p>The rule this works to: say something or say nothing. A line it has no reading for comes back
 * empty and is left out, because a tour that narrates every line as "assigns the result to the
 * variable" is one nobody listens to twice. What survives is the shape of the method — its guards,
 * its refusals, its loops and what it answers with — which is what a person asking how the code
 * works was asking about.
 */
@UtilityClass
public class LineSense {

    private final Pattern RETURN = Pattern.compile("^return\\b\\s*(.*?);?$");
    private final Pattern THROW = Pattern.compile("^throw\\s+new\\s+(\\w+)\\s*\\((.*)\\)\\s*;?$");
    private final Pattern IF = Pattern.compile("^(?:}\\s*else\\s+)?if\\s*\\((.*)\\)\\s*\\{?$");
    private final Pattern FOR_EACH = Pattern.compile("^for\\s*\\(\\s*[\\w.<>,\\[\\] ?]+\\s+(\\w+)\\s*:\\s*(.+?)\\s*\\)\\s*\\{?$");
    private final Pattern WHILE = Pattern.compile("^while\\s*\\((.*)\\)\\s*\\{?$");
    private final Pattern SWITCH = Pattern.compile("^(?:.*=\\s*)?switch\\s*\\((.*)\\)\\s*\\{?$");
    private final Pattern CATCH = Pattern.compile("^}?\\s*catch\\s*\\(\\s*([\\w |]+)\\s+\\w+\\s*\\)\\s*\\{?$");
    private final Pattern TRY = Pattern.compile("^try\\s*\\((.*)\\)\\s*\\{?$");
    private final Pattern DECLARE = Pattern.compile("^(?:final\\s+)?[A-Z][\\w.<>,\\[\\] ?]*\\s+(\\w+)\\s*=\\s*(.+?);?$");
    private final Pattern ASSIGN = Pattern.compile("^(?:this\\.)?(\\w+)\\s*=\\s*(.+?);?$");
    private final Pattern CALL = Pattern.compile("^([\\w.]+)\\s*\\(.*\\)\\s*;$");
    private final Pattern QUESTION = Pattern.compile("\\s\\?\\s.*\\s:\\s");

    /** The line as a clause, or empty when it is punctuation, plumbing or beyond reading. */
    public String of(String line) {
        String text = line == null ? "" : line.strip();
        if (skippable(text) || fragment(text)) {
            return "";
        }
        String choice = choice(text);
        if (!choice.isEmpty()) {
            return choice;
        }
        String flow = flow(text);
        return flow.isEmpty() ? work(text) : flow;
    }

    /**
     * True when the line is only part of a statement.
     *
     * <p>A wrapped expression reads as a whole one — {@code return text.isEmpty()} on its own line
     * looks like an answer and is actually the first third of a condition. Saying something
     * confident about a fragment is the worst thing this can do, so a line that does not finish is
     * left to the line that finishes it.
     */
    private boolean fragment(String text) {
        return !text.endsWith(";") && !text.endsWith("{") && !text.endsWith("}");
    }

    private boolean skippable(String text) {
        return text.isEmpty()
                || text.startsWith("//")
                || text.startsWith("*")
                || text.startsWith("/*")
                || text.startsWith("@")
                || text.startsWith("import ")
                || text.startsWith("package ")
                || text.equals("}")
                || text.equals("{")
                || text.equals("};")
                || text.equals("} else {")
                || text.equals("break;")
                || text.equals("continue;");
    }

    /**
     * A choice between two values, said as a choice.
     *
     * <p>Read as a call — which is what the shape of it looks like — a ternary comes out as the
     * opposite of what it does: {@code flow.isEmpty() ? work(text) : flow} narrated as "answers
     * with is empty from flow" says the method returns the test. What a listener needs is the
     * question being asked, so that is what gets said.
     */
    private String choice(String text) {
        int question = QUESTION.matcher(text).find() ? text.indexOf(" ? ") : -1;
        if (question < 0 || !text.endsWith(";")) {
            return "";
        }
        String verb = text.startsWith("return") ? "answers" : "decides";
        String asked = Condition.of(text.substring(head(text), question));
        return asked.isEmpty() ? "" : verb + " one of two things, depending on whether " + asked;
    }

    /** Where the expression starts: past the {@code return}, or past the {@code =} of a setting. */
    private int head(String text) {
        if (text.startsWith("return")) {
            return "return".length();
        }
        int equals = text.indexOf('=');
        return equals < 0 ? 0 : equals + 1;
    }

    private String flow(String text) {
        Matcher thrown = THROW.matcher(text);
        if (thrown.matches()) {
            return refusal(thrown);
        }
        Matcher returned = RETURN.matcher(text);
        if (returned.matches()) {
            return answer(returned.group(1));
        }
        Matcher tested = IF.matcher(text);
        if (tested.matches()) {
            String said = Condition.of(tested.group(1));
            return said.isEmpty() ? "" : (text.startsWith("}") ? "otherwise, when " : "when ") + said;
        }
        return loops(text);
    }

    private String loops(String text) {
        Matcher each = FOR_EACH.matcher(text);
        if (each.matches()) {
            String over = Phrase.of(each.group(2));
            return over.isEmpty() ? "" : "for every " + Phrase.said(each.group(1)) + " in " + over;
        }
        Matcher loop = WHILE.matcher(text);
        if (loop.matches()) {
            String said = Condition.of(loop.group(1));
            return said.isEmpty() ? "" : "for as long as " + said;
        }
        Matcher branch = SWITCH.matcher(text);
        if (branch.matches()) {
            String said = Phrase.of(branch.group(1));
            return said.isEmpty() ? "" : "branches on " + said;
        }
        return guards(text);
    }

    private String guards(String text) {
        Matcher failure = CATCH.matcher(text);
        if (failure.matches()) {
            return "if that fails with " + Phrase.said(failure.group(1).replace('|', ' ').strip());
        }
        Matcher opened = TRY.matcher(text);
        if (opened.matches()) {
            String said = Phrase.of(afterEquals(opened.group(1)));
            return said.isEmpty() ? "" : "opens " + said + ", and closes it whatever happens";
        }
        return "";
    }

    private String work(String text) {
        Matcher declared = DECLARE.matcher(text);
        if (declared.matches()) {
            return takes(declared.group(1), declared.group(2));
        }
        Matcher set = ASSIGN.matcher(text);
        if (set.matches()) {
            return takes(set.group(1), set.group(2));
        }
        Matcher called = CALL.matcher(text);
        if (called.matches()) {
            String said = Phrase.of(text.substring(0, text.length() - 1));
            return said.isEmpty() ? "" : "calls " + said;
        }
        return "";
    }

    private String takes(String name, String expression) {
        String from = Phrase.of(expression);
        return from.isEmpty() ? "" : "takes " + Phrase.said(name) + " from " + from;
    }

    private String answer(String expression) {
        if (expression == null || expression.isBlank()) {
            return "and that is the end of it";
        }
        String said = Phrase.of(expression);
        return said.isEmpty() ? "" : "answers with " + said;
    }

    private String refusal(Matcher thrown) {
        Matcher message = Pattern.compile("\"([^\"]{1,80})\"").matcher(thrown.group(2));
        String kind = Phrase.said(thrown.group(1));
        return message.find()
                ? "refuses, saying " + message.group(1)
                : "refuses with " + kind;
    }

    /** The right-hand side of a resource declaration, which is the part worth naming. */
    private String afterEquals(String resource) {
        int equals = resource.indexOf('=');
        return equals < 0 ? resource : resource.substring(equals + 1);
    }
}
