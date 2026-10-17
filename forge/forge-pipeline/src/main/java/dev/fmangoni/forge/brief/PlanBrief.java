package dev.fmangoni.forge.brief;

import dev.fmangoni.forge.spec.model.Spec;
import java.time.Duration;
import lombok.experimental.UtilityClass;

/**
 * The planning prompt: decide how this project should be built, and write it down.
 *
 * <p>One read-only turn on the best model the vendor has, before any code exists. The turns that
 * follow run on a cheaper model, and what makes that affordable rather than merely cheap is that
 * they are not being asked to decide anything: the approach, the shape of the code and the order
 * of the work are already settled here by the model best able to settle them.
 *
 * <p>So this asks for judgement and nothing else. No code is written in this turn — the session is
 * read-only, and a plan that starts implementing has spent the expensive call on the cheap part of
 * the job.
 */
@UtilityClass
public class PlanBrief {

    public String of(Spec spec, Duration cap) {
        return """
                Plan how to build the project described in SPEC.md, in this repository. Do not \
                write any code: this session is read-only on purpose, and refused tool calls are \
                expected. Reply with the plan itself and nothing else.

                What you write is the brief for the sessions that do the work. They will run on a \
                smaller model than you, they will not see this conversation, and they will follow \
                what you write here rather than re-deciding it. Be concrete where a decision has \
                been made, and say plainly where one has not.

                Cover, in this order and in Markdown:

                ## Approach
                How the project should be built, in a paragraph or two: the shape of the solution \
                and why this one rather than the obvious alternative.

                ## The class that shows the flow
                AGENTS.md requires exactly one class that reads as the whole program's top-level \
                flow. Name the file it should live in and list the steps it will run in order. \
                This is the thing a reader judges the project by, so decide it now.

                ## Structure
                The files or modules to create, one line each, saying what each is responsible for. \
                Keep responsibilities apart: a class doing two unrelated jobs is the defect the \
                cross-review raises most often.

                ## Steps
                The work in the order it should be done, each step small enough to finish and check \
                in one sitting, and each one ending somewhere the acceptance commands can run. Say \
                which acceptance command each step is aiming at.

                ## Risks
                What is most likely to go wrong or be got wrong, and what to do about it. Include \
                anything in the spec that looks ambiguous, and say which reading to take.

                You have %d minutes. Read SPEC.md and AGENTS.md first; the acceptance commands in \
                SPEC.md are what decide whether this project is done, and they are run by the \
                orchestrator against the files left on disk.

                Project: %s
                """.formatted(cap.toMinutes(), spec.title());
    }
}
