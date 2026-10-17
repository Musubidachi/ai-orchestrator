package dev.fmangoni.forge.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An agent's account of a turn: what it finished, what is left, and how to carry on.
 *
 * <p>This is the handoff between sessions. Without it a project too large for one turn restarts
 * from the spec every time and rediscovers the same design decisions at full price; with it each
 * session begins where the last one stopped.
 *
 * <p>It is a claim, not evidence. The gate still decides whether the project is done — a plan
 * saying {@code complete} changes nothing about that.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Plan {

    /** {@code complete} or {@code partial}, as the agent judged it. */
    private String status = "";

    private String summary = "";

    private List<String> completed = new ArrayList<>();

    private List<PlanStep> remaining = new ArrayList<>();

    /** How many further sessions the agent thinks the rest needs. */
    private int sessionsNeeded;

    /** Things the next session should know that are not steps — blockers, doubts, assumptions. */
    private List<String> risks = new ArrayList<>();

    /**
     * Path to the class that reads as the program's top-level flow.
     *
     * <p>Named by the agent so Forge can check it exists and show it in the report. The point is a
     * reader deciding whether they agree with the approach without opening the repository.
     */
    private String overview = "";

    /**
     * The capabilities this project has, each put forward for a recorded demonstration.
     *
     * <p>Proposed here and settled elsewhere: the person the program is for approves or declines
     * each one, and every approved feature owes a recording that the gate then insists on. The
     * agent is asked because it is the only party that knows what it built; it is not asked to
     * decide, because an agent allowed to set the number of demos it owes will find that the
     * project has one feature.
     */
    private List<PlanFeature> features = new ArrayList<>();

    /**
     * What the project could do next, which it does not do yet.
     *
     * <p>Distinct from {@link #features} because one is a claim about the program and the other is
     * a suggestion for it, and conflating them would let an idea be approved as though it existed —
     * and then owe a recording of something nobody has built. An approved idea is a request to build
     * it; once built, the agent lists it under {@code features} like anything else.
     */
    private List<PlanFeature> ideas = new ArrayList<>();

    /**
     * What a passing gate still does not prove.
     *
     * <p>The acceptance commands can only check what the spec author thought to check, which is the
     * standing blind spot in the whole design. This is the agent pointing at it — and it is worth
     * more than any argument that the work is correct, because the gate already settles that
     * question and a confident case for broken code is actively harmful.
     */
    private List<String> notVerified = new ArrayList<>();

    /**
     * What a review-repair turn did about each blocking finding it was given, by id.
     *
     * <p>Empty on any other turn. Read by the next review, which decides whether each is fixed.
     */
    private List<FindingAnswer> reviewFindings = new ArrayList<>();

    /**
     * A briefing for a successor who has not read this conversation.
     *
     * <p>The rest of this record describes the <em>task</em>: what is done, what is next. This
     * describes the <em>project</em>: how it is built, which decisions were made and why, and which
     * approaches were tried and abandoned. It is what makes a fresh session as capable as a resumed
     * one, and it is written while the agent still has the whole conversation in front of it —
     * afterwards, nobody can reconstruct why an approach was rejected.
     *
     * <p>Replaced wholesale each turn rather than appended to. An accumulating digest would grow
     * without bound, which is the problem it exists to solve.
     */
    private String digest = "";

    /** Set when the reply could not be parsed, so the report says so instead of showing nothing. */
    private String parseError;

    public boolean partial() {
        return "partial".equalsIgnoreCase(status) || !remaining.isEmpty();
    }

    public boolean usable() {
        return parseError == null && (!summary.isBlank() || !completed.isEmpty() || !remaining.isEmpty());
    }

    public int remainingMinutes() {
        return remaining.stream().mapToInt(PlanStep::getEstimateMinutes).sum();
    }

    public static Plan failed(String reason) {
        Plan plan = new Plan();
        plan.setParseError(reason);
        return plan;
    }
}
