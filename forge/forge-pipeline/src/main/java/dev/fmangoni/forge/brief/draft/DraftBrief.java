package dev.fmangoni.forge.brief.draft;

import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * Turns a rough description into a complete spec draft.
 *
 * <p>Most of this prompt is about the acceptance commands, because they are the part a model is
 * most likely to get plausibly wrong. A command that always passes looks exactly like a command
 * that works, and it would quietly disable the only real check in the pipeline — so the prompt
 * pushes hard for commands that actually fail on a broken project, and says outright that a short
 * honest list beats a long impressive one.
 *
 * <p>That emphasis has a side effect worth countering explicitly, and the prompt does. Commands
 * are easiest to write for headless tools, so a model asked for something interactive will tend to
 * propose a command-line version of it and record the reduction as a careful assumption. It reads
 * as prudence and is in fact a substitution: the person asked for one product and was handed a
 * smaller different one. An earlier version of this prompt made it worse by asking outright for
 * "the smaller, more concrete reading".
 */
@UtilityClass
public class DraftBrief {

    public String of(String description, List<String> usedIds) {
        return """
                Turn the rough idea below into a build specification. Reply with JSON only, no \
                preamble and no code fence.

                {"id":"003","title":"Short noun phrase","stack":"java",
                 "complexity":"routine" | "involved" | "hard",
                 "complexity_reason":"one sentence saying what makes it that",
                 "acceptance":["mvn -q test"],
                 "evidence":["out.html"],
                 "serve":"npm run preview -- --port {port} --host 127.0.0.1",
                 "body":"## Goal\\n...\\n\\n## Non-goals\\n...\\n\\n## Notes\\n..."}

                ## acceptance — the part that matters

                These commands get run by an orchestrator, from the project root, to decide whether \
                the finished project is accepted. They are the only real check: the building agent's \
                own report of success is ignored entirely.

                So they must actually discriminate. Ask yourself, for each command you propose, \
                "would this fail if the program were subtly wrong?" — if the answer is no, it is \
                worse than useless, because it makes a broken project look finished.

                - Use the ordinary commands for the stack (`mvn -q test`, `npm test`, and so on).
                - Prefer a build-and-test command plus one command that exercises the program the \
                way a user would, on a real input.
                - Do not invent sample files that will not exist. If a command needs an input file, \
                say so in the Notes so the builder creates it.
                - Three commands that genuinely fail on a broken build beat eight that always pass.

                ## complexity

                Judge how demanding the work is, so a model can be matched to it. You are not being
                asked which model to use — you cannot see what is installed, what it costs, or how
                much quota is left, and guessing at that would be worse than not answering.

                - `routine` — well-trodden work with a clear shape: a CLI, a parser, a report
                  generator. Someone competent could sit down and write it.
                - `involved` — several moving parts, or a domain with real edge cases: formats that
                  vary in practice, concurrency, a protocol that has to be got exactly right.
                - `hard` — the approach itself is unclear, or it needs heavy algorithms, numerical
                  work, or research. Not "large": a big pile of ordinary code is `involved`.

                Size the *thinking*, not the typing. Ten routine files is `routine`; one function
                nobody knows how to write is `hard`.

                ## the rest

                - `id`: a zero-padded number not already used. Taken: %s
                - `title`: short, specific, no trailing explanation.
                - `stack`: one word, e.g. java, node, python.
                - `evidence`: files a human should look at afterwards — a rendered page, a generated \
                document, a screenshot. If the project has a user interface, name a screenshot of \
                it, and a short screen recording where the behaviour rather than the layout is the \
                point: a still frame cannot show whether the thing responds. This holds for any \
                toolkit, not only the web. Omit only when the project genuinely has nothing to look \
                at.
                - `serve`: how to run the finished app locally, with `{port}` where the port goes                 and bound to 127.0.0.1 — Forge starts it to probe the app for security problems.                 Only for something served over HTTP (a web app, a site, an API); blank for a CLI,                 a library or a desktop program.
                - `body`: Markdown with exactly the headings `## Goal`, `## Non-goals`, `## Notes`. \
                Goal says what it does and why. Non-goals says what it must not grow into. Notes \
                says how it should be built — libraries, CLI shape, formats, edge cases.

                ## ambition — match the idea, do not trim it

                Build the thing that was asked for. If someone asks for a word processor better \
                than Word, they want a word processor; proposing a headless Markdown-to-HTML \
                converter and recording "read narrowly" as an assumption is not a cautious answer, \
                it is a different product. The person can cut scope themselves — every field you \
                return lands in an editor for them to change — but they cannot recover an idea you \
                quietly replaced with a smaller one.

                The usual reason for shrinking is that the honest version looks hard to check. That \
                is a reason to work harder on the acceptance commands, not to change the product:

                - A user interface is testable. Drive the real thing in a real browser with \
                Playwright — click, type, assert on what the user sees — and list a screenshot and \
                a short recording under `evidence`. A game, an editor and a dashboard have all been \
                gated this way here.
                - A desktop program is testable through its own logic plus a driven UI; a service \
                through its HTTP surface; a renderer by rasterising its output and asserting on the \
                pixels.
                - Only fall back to a headless tool when that is genuinely what was asked for.

                Ambition means the full shape of the idea, not extra ideas bolted on. Do not add \
                features nobody asked for, and do not propose something that could not be built at \
                all. Where the idea is genuinely ambiguous, take the reading that makes it the thing \
                the person clearly wanted and record the assumption in Notes.

                `## Non-goals` is for stopping this project turning into a different, larger one — \
                a plugin system, a marketplace, an account system. It is not for removing the parts \
                of the request that are hard to build.

                ## the idea

                %s
                """.formatted(usedIds.isEmpty() ? "(none)" : String.join(", ", usedIds), description);
    }
}
