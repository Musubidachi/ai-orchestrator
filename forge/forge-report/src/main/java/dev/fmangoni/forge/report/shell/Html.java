package dev.fmangoni.forge.report.shell;

import lombok.experimental.UtilityClass;

/** Escaping and the shared page shell for the generated reports. */
@UtilityClass
public class Html {

    /**
     * Escapes text for HTML.
     *
     * <p>Applied to everything that reaches a page, because almost all of it — agent messages,
     * compiler output, review findings — is model-written text that routinely contains angle
     * brackets and would otherwise silently swallow the rest of the report.
     */
    public String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    public String page(String title, String body) {
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s</title>
                <style>%s</style>
                </head>
                <body>
                <main>
                %s
                </main>
                </body>
                </html>
                """.formatted(escape(title), CSS, body);
    }

    private final String CSS = """
            :root {
              --bg: #fbfbfa; --fg: #1a1a19; --muted: #6b6b68; --line: #e4e4e1;
              --card: #ffffff; --pass: #1a7f4b; --fail: #b3261e; --wait: #8a6d1f;
            }
            @media (prefers-color-scheme: dark) {
              :root {
                --bg: #161615; --fg: #ededeb; --muted: #9a9a96; --line: #2e2e2c;
                --card: #1e1e1d; --pass: #4ade80; --fail: #f87171; --wait: #fbbf24;
              }
            }
            .decide-item {
              border: 1px solid var(--line); border-left: 3px solid var(--wait);
              border-radius: .4rem; background: var(--card);
              padding: .7rem .9rem; margin: .6rem 0;
            }
            .decide-src {
              font-size: .75rem; text-transform: uppercase; letter-spacing: .04em;
              color: var(--muted);
            }
            .decide-text { margin: .2rem 0 .5rem; }
            .decide-item label { margin-right: 1.1rem; font-size: .9rem; cursor: pointer; }
            .decide-comment {
              display: block; width: 100%; margin-top: .5rem; padding: .4rem .55rem;
              border: 1px solid var(--line); border-radius: .3rem;
              background: var(--bg); color: var(--fg); font: inherit; font-size: .9rem;
            }
            .decide-past { border-left-color: var(--line); opacity: .85; }
            /* Came back after a submitted review: nothing ticked, and a line saying it has been
               here before, so it reads as a question again rather than as last week's answer. */
            .decide-back { font-size: .85rem; color: var(--wait); margin: 0 0 .45rem; }
            .decide-missing { border-color: var(--fail); border-left-color: var(--fail); }
            .history-wrap { overflow-x: auto; }
            .history td, .history th { vertical-align: top; font-size: .9rem; }
            .history .history-when { white-space: nowrap; color: var(--muted); }
            .decide-thread { margin: .1rem 0 .6rem; }
            .decide-note {
              border-left: 2px solid var(--line); padding: .2rem 0 .2rem .6rem;
              margin: .25rem 0; font-size: .9rem;
            }
            .decide-when { color: var(--muted); font-size: .8rem; }
            .decide-blocked {
              border-left: 3px solid var(--wait); background: var(--card);
              padding: .5rem .8rem; margin: .6rem 0; font-size: .9rem;
            }
            .decide-actions { margin: 1rem 0 0; display: flex; gap: .75rem; align-items: center; }
            #decide button {
              padding: .5rem 1rem; border: 1px solid var(--line); border-radius: .35rem;
              background: var(--card); color: var(--fg); font: inherit; cursor: pointer;
            }
            #decide button:hover { border-color: var(--fg); }
            /* A feature that has been filmed is settled; one that owes a clip is the open item, so
               the marker colour follows the recording rather than the approval. */
            .feature-item { border-left-color: var(--pass); }
            .feature-item:has(.feature-owed) { border-left-color: var(--wait); }
            .feature-owed { color: var(--wait); text-transform: none; letter-spacing: 0; }
            /* Capped in both directions. A recording is whatever shape the window it filmed
               happened to be, and a tall one with the width filled would push everything else off
               the page -- a 1x1 frame from a broken encode did exactly that. */
            .feature-item video, .feature-item img {
              display: block; width: 100%; max-width: 34rem; max-height: 24rem;
              object-fit: contain; margin: .5rem 0;
              border: 1px solid var(--line); border-radius: .3rem; background: #000;
            }
            /* The rulings fold away; the clips they are about stay up the page where they can be
               watched. Most visits are about the review, and rows for features settled three
               sessions ago are noise between a reader and what they came to do. */
            .features-fold { margin: .6rem 0 1rem; }
            /* The design that was chosen, then before and after, side by side while there is room. */
            .polish-pair { display: grid; grid-template-columns: repeat(auto-fit, minmax(15rem, 1fr));
                           gap: 1rem; margin: .6rem 0; align-items: start; }
            .polish-pair figure { margin: 0; }
            .polish-pair img { display: block; width: 100%; max-height: 30rem; object-fit: contain;
                               border: 1px solid var(--line); border-radius: .3rem; background: #000; }
            .polish-pair figcaption { font-size: .85rem; color: var(--muted); margin-top: .3rem; }
            .polish-frame { height: 18rem; overflow: hidden; border: 1px solid var(--line);
                            border-radius: .3rem; background: #fff; }
            .polish-frame iframe { width: 200%; height: 200%; border: 0; transform: scale(.5);
                                   transform-origin: 0 0; pointer-events: none; }
            .polish-missing { border: 1px dashed var(--line); border-radius: .3rem; padding: 2rem 1rem;
                              color: var(--muted); font-size: .85rem; text-align: center; }
            .features-fold > summary {
              cursor: pointer; padding: .5rem .7rem; border: 1px solid var(--line);
              border-radius: .4rem; background: var(--card); font-size: .9rem;
            }
            .features-fold > summary:hover { border-color: var(--fg); }
            .features-fold[open] > summary { margin-bottom: .4rem; }
            .feature-add input { display: block; width: 100%; margin-top: .4rem; }
            .feature-add .feature-name {
              padding: .4rem .55rem; border: 1px solid var(--line); border-radius: .3rem;
              background: var(--bg); color: var(--fg); font: inherit; font-size: .9rem;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0; padding: 2rem 1.25rem; background: var(--bg); color: var(--fg);
              font: 15px/1.6 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
            }
            main { max-width: 60rem; margin: 0 auto; }
            h1 { font-size: 1.5rem; margin: 0 0 .25rem; }
            h2 { font-size: 1.05rem; margin: 2rem 0 .75rem; }
            .sub { color: var(--muted); margin: 0 0 2rem; }
            a { color: inherit; }
            table { width: 100%; border-collapse: collapse; }
            th, td { text-align: left; padding: .6rem .5rem; border-bottom: 1px solid var(--line); }
            th { font-weight: 600; color: var(--muted); font-size: .8rem;
                 text-transform: uppercase; letter-spacing: .04em; }
            .card { background: var(--card); border: 1px solid var(--line);
                    border-radius: .5rem; padding: 1rem 1.25rem; margin-bottom: 1rem; }
            .tag { display: inline-block; font-size: .75rem; font-weight: 600;
                   padding: .15rem .5rem; border-radius: 1rem; border: 1px solid currentColor; }
            .DONE, .ACCEPTED { color: var(--pass); }
            .PARKED { color: var(--fail); }
            .SUSPENDED, .GATING, .REPAIRING, .CONTINUING, .REVIEWING, .REVIEW_REPAIR,
            .SECURITY_CHECK, .SECURITY_REPAIR,
            .SCAFFOLDED, .NEW {
              color: var(--wait);
            }
            pre { background: var(--bg); border: 1px solid var(--line); border-radius: .375rem;
                  padding: .75rem; overflow-x: auto; font-size: .8rem; margin: .5rem 0 0; }
            .muted { color: var(--muted); font-size: .85rem; }
            ul { padding-left: 1.1rem; }
            img { max-width: 100%; border: 1px solid var(--line); border-radius: .375rem; }
            """;
}
