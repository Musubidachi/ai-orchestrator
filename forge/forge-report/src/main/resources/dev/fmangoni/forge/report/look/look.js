/*
 * The report's look, as the mockup draws it: a header that says the phase once, the review's own
 * notice as a banner at the top with a way down to the review, a feature with nothing to watch as
 * one line, each gap as a card of its own, the checks' times drawn to one scale, the rail as a card
 * and the review bar along the bottom of the window. On a phone, a wide table scrolls in its card.
 *
 * Attached once, after the guide (ProjectReport, through ReportLook), so the rail and the bar are
 * there to restyle. The words it adds are the report's: the chip, the line beside the title, the
 * times and the link to the review come from the template the report writes beside this script,
 * from the project's state, and the banner says what the notice above the form says. So nothing on
 * the page is a phrase of this script's, and a test reading a page for what it does not say reads
 * the page.
 *
 * It moves the page's own elements, hides what it replaces with a class rather than removing it,
 * and adds no form, no button and no row the review form reads. Each part is done on its own, so a
 * part that cannot find what it restyles stays as it was. Take out the line that attaches it and the
 * page is as it was.
 */
(() => {
  const main = document.querySelector("main");
  const parts = document.getElementById("report-look-parts");
  if (!main || !parts || !parts.content) return;

  const CSS = `
  :root { --rl-accent: #2f5fd0; --rl-on-accent: #ffffff; --rl-accent-soft: rgba(47, 95, 208, .10); }
  @media (prefers-color-scheme: dark) {
    :root { --rl-accent: #7aa2f7; --rl-on-accent: #0d1220; --rl-accent-soft: rgba(122, 162, 247, .14); }
  }
  .rl-gone { display: none !important; }

  .rl-head { display: flex; flex-wrap: wrap; align-items: center; gap: .4rem .9rem;
             max-width: 60rem; margin: -2rem auto 1.6rem; padding: .85rem 0;
             border-image: linear-gradient(to top, var(--line) 1px, transparent 1px) fill 0 // 0 100vmax; }
  .rl-head h1 { margin: 0; font-size: 1.3rem; line-height: 1.3; }
  .rl-chip { display: inline-flex; align-items: center; gap: .35rem; padding: .15rem .65rem;
             border: 1px solid color-mix(in srgb, currentColor 45%, transparent); border-radius: 1rem;
             background: color-mix(in srgb, currentColor 10%, transparent);
             font-size: .8rem; font-weight: 600; line-height: 1.4; white-space: nowrap; }
  .rl-chip svg { width: .85rem; height: .85rem; flex: none; fill: none; stroke: currentColor;
                 stroke-width: 2.2; stroke-linecap: round; stroke-linejoin: round; }
  .rl-meta { color: var(--muted); font-size: .85rem; }

  .rl-banner { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem 1.25rem;
               margin: 0 0 1.75rem; padding: .85rem 1rem .85rem 1.1rem;
               border: 1px solid color-mix(in srgb, var(--wait) 45%, transparent); border-radius: .6rem;
               background: color-mix(in srgb, var(--wait) 8%, var(--card)); }
  .rl-say { flex: 1 1 22rem; min-width: 0; font-size: .92rem; line-height: 1.5; }
  .rl-say strong { display: block; color: var(--wait); }
  .rl-go { flex: none; padding: .45rem .9rem; border: 1px solid color-mix(in srgb, var(--wait) 60%, transparent);
           border-radius: .4rem; color: var(--wait); font-size: .9rem; font-weight: 600;
           text-decoration: none; white-space: nowrap; }
  .rl-go:hover { background: color-mix(in srgb, var(--wait) 12%, transparent); }

  .rl-feature { border: 1px solid var(--line); border-radius: .6rem; padding: .85rem 1.1rem; }
  .rl-feature > .decide-text { margin: 0 0 .15rem; font-weight: 600; }
  .rl-feature > .rl-demo { margin: 0; color: var(--fg); font-size: .92rem; letter-spacing: 0;
                           text-transform: none; }
  .rl-flat { display: grid; grid-template-columns: minmax(0, 1fr) auto; column-gap: 1.25rem;
             align-items: center; }
  .rl-flat > * { grid-column: 1; }
  .rl-flat > .rl-note { grid-column: 2; grid-row: 1 / span 2; justify-self: end; max-width: 17rem;
                        padding: .25rem .7rem; border: 1px solid var(--line); border-radius: 1rem;
                        background: var(--bg); font-size: .78rem; line-height: 1.35; letter-spacing: 0;
                        text-align: center; text-transform: none; }
  .rl-flat > .rl-note.feature-owed { border-color: color-mix(in srgb, var(--wait) 50%, transparent); }
  .rl-flat > .decide-thread { grid-column: 1 / -1; margin-top: .5rem; }

  .rl-bare { padding: 0; border: 0; border-radius: 0; background: none; }
  .rl-gaps { display: grid; gap: .6rem; margin: 0 0 .7rem; padding: 0; list-style: none; }
  .rl-gaps li { position: relative; padding: .75rem 1rem .75rem 2.6rem; border: 1px solid var(--line);
                border-radius: .6rem; background: var(--card); }
  .rl-gaps li::before { content: ""; position: absolute; left: 1rem; top: 1rem; width: 1rem; height: 1rem;
    background: var(--wait);
    -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M12 3.5 2.5 20h19z'/%3E%3Cpath d='M12 10v4.5M12 17.2v.1'/%3E%3C/svg%3E") center / contain no-repeat;
    mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M12 3.5 2.5 20h19z'/%3E%3Cpath d='M12 10v4.5M12 17.2v.1'/%3E%3C/svg%3E") center / contain no-repeat; }

  .rl-times { padding: .9rem 1.25rem; }
  .rl-time { display: grid; grid-template-columns: minmax(8rem, 16rem) minmax(4rem, 1fr) auto;
             align-items: center; gap: .3rem 1.25rem; padding: .3rem 0; }
  .rl-time code { font-size: .85rem; line-height: 1.4; overflow-wrap: anywhere; }
  .rl-track { position: relative; height: .45rem; border-radius: .25rem; background: var(--line); }
  .rl-bar { position: absolute; top: 0; bottom: 0; left: 0; min-width: 3px; border-radius: .25rem;
            background: var(--rl-accent); }
  .rl-slow .rl-bar { background: var(--wait); }
  .rl-tick { position: absolute; top: -.3rem; bottom: -.3rem; width: 2px; margin-left: -1px;
             border-radius: 1px; background: var(--fg); }
  .rl-took { color: var(--muted); font-size: .85rem; font-variant-numeric: tabular-nums;
             text-align: right; white-space: nowrap; }
  .rl-took b { color: var(--fg); font-weight: 600; }
  .rl-took em { color: var(--wait); font-style: normal; font-weight: 600; }
  .rl-foot { margin: .55rem 0 0; color: var(--muted); font-size: .8rem; }

  /* A table wider than the window scrolls inside its card instead of pushing the page sideways. */
  main .card:has(> table) { overflow-x: auto; }

  .rg-rail { padding: .85rem .55rem; border: 1px solid var(--line); border-radius: .75rem;
             background: var(--card); }
  .rg-title { margin: 0 0 .4rem .6rem; }
  .rg-rail ol { display: grid; gap: 2px; border-left: 0; }
  .rg-rail a { margin: 0; padding: .35rem .6rem; border: 1px solid transparent; border-radius: .45rem;
               color: var(--fg); }
  .rg-rail a:hover { background: color-mix(in srgb, var(--fg) 6%, transparent); }
  .rg-rail a[aria-current] { border-color: transparent; background: var(--rl-accent-soft);
                             color: var(--rl-accent); }
  .rg-rail a:has(.rg-pill) { border-color: var(--line); }
  .rg-hold { color: var(--muted); }
  .rg-hold.rg-pill { border-color: var(--rl-accent); background: var(--rl-accent); color: var(--rl-on-accent); }

  .rg-bar { position: fixed; right: 0; bottom: 0; left: 0; z-index: 20; margin: 0;
            padding: .65rem max(1.25rem, calc(50vw - 39.75rem)); border-width: 1px 0 0; border-radius: 0;
            box-shadow: 0 -10px 30px rgba(0, 0, 0, .10); }
  html.rg-barred body { padding-bottom: 6rem; }
  .rg-acts .rg-next { border-color: var(--rl-accent); background: var(--rl-accent); color: var(--rl-on-accent); }
  .rg-acts .rg-next:hover { border-color: var(--rl-accent); filter: brightness(1.08); }

  @media (min-width: 75rem) {
    body.rg-on { grid-template-columns: 17rem minmax(0, 60rem); }
    body.rg-on > .rl-head { grid-column: 1 / -1; grid-row: 1; max-width: none; margin: -2rem 0 1.6rem; }
    body.rg-on > main, body.rg-on > .rg-rail { grid-row: 2; }
    html.rg-barred .rg-rail { max-height: calc(100vh - 7.5rem); }
  }
  @media (max-width: 40rem) {
    .rl-flat { display: block; }
    .rl-flat > .rl-note { display: inline-block; max-width: none; margin-top: .5rem; text-align: left; }
    .rl-time { grid-template-columns: minmax(0, 1fr) auto; }
    .rl-time code { grid-column: 1 / -1; }
  }
  @media print {
    .rl-head { border-image: none; border-bottom: 1px solid var(--line); }
    html.rg-barred body { padding-bottom: 0; }
  }
  `;

  const text = (el) => (el ? el.textContent.trim() : "");
  const part = (selector) => parts.content.querySelector(selector);

  /* A section's heading by the id the page gives it, or the one the guide makes from its words
     (without the guide's -2 for a second heading with the same words). By id, as the guide counts,
     so this script repeats none of the page's words. */
  const idOf = (h2) => h2.id
    || text(h2).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
  const headingOf = (id) => [...main.children].find((el) => el.tagName === "H2" && idOf(el) === id);

  /* What follows a heading, up to the next one. */
  function partsOf(h2) {
    const found = [];
    for (let el = h2.nextElementSibling; el && el.tagName !== "H2"; el = el.nextElementSibling) {
      found.push(el);
    }
    return found;
  }

  /* ---- The header: the title, the phase once, and who built it, above the rail and the page ---- */

  function head() {
    const title = main.querySelector(":scope > h1");
    const chip = part(".rl-chip");
    if (!title || !chip) return;
    const sub = title.nextElementSibling;
    const header = document.createElement("header");
    header.className = "rl-head";
    header.append(title, chip);
    const meta = part(".rl-meta");
    if (meta && text(meta)) header.append(meta);
    document.body.prepend(header);
    // The page's own line under the title said the phase and the builder, which the header now says.
    if (sub && sub.classList.contains("sub")) sub.classList.add("rl-gone");
  }

  /* ---- The banner: why the review cannot simply be accepted, said at the top ---- */

  /* The heading of the section the form is in. */
  function headingAbove(form) {
    let heading = null;
    for (const el of main.children) {
      if (el === form || el.contains(form)) return heading;
      if (el.tagName === "H2") heading = el;
    }
    return null;
  }

  function banner() {
    const form = document.getElementById("decide");
    const notice = main.querySelector(".decide-blocked");
    const go = part(".rl-go");
    const said = text(notice);
    if (!form || !said || !go) return;
    // The notice's first sentence leads, and the rest follows under it.
    const end = said.search(/[.!?]\s+(?=[A-Z])/);
    const say = document.createElement("div");
    say.className = "rl-say";
    const lead = document.createElement("strong");
    lead.textContent = end < 0 ? said : said.slice(0, end + 1);
    say.append(lead);
    if (end >= 0) {
      const rest = document.createElement("span");
      rest.textContent = said.slice(end + 1).trim();
      say.append(rest);
    }
    const heading = headingAbove(form);
    if (heading && heading.id) go.setAttribute("href", "#" + heading.id);
    const box = document.createElement("div");
    box.className = "rl-banner";
    box.append(say, go);
    main.prepend(box);
  }

  /* ---- What it does: a feature with nothing to watch is one line, what it waits for beside it ---- */

  function features() {
    const heading = headingOf("features");
    if (!heading) return;
    for (const card of partsOf(heading)) {
      if (!card.classList.contains("feature-item")) continue;
      // What it shows comes first, and what the report says of its recording last.
      const notes = [...card.children].filter((el) => el.classList.contains("decide-src"));
      if (!notes.length) continue;
      card.classList.add("rl-feature");
      notes.forEach((el, at) => el.classList.add(at === notes.length - 1 ? "rl-note" : "rl-demo"));
      if (!card.querySelector(":scope > img, :scope > video")) card.classList.add("rl-flat");
    }
  }

  /* ---- What this does not prove: each gap a card of its own ---- */

  function gaps() {
    const heading = headingOf("what-this-does-not-prove");
    const card = heading && partsOf(heading).find((el) => el.classList.contains("card"));
    const list = card && card.querySelector(":scope > ul");
    if (!list) return;
    card.classList.add("rl-bare");
    list.classList.add("rl-gaps");
  }

  /* ---- How long the checks take: drawn to one scale, in place of the list ---- */

  function times() {
    const heading = headingOf("how-long-the-checks-take");
    const chart = part(".rl-times");
    if (!heading || !chart) return;
    const list = partsOf(heading).find((el) => el.classList.contains("card"));
    heading.after(chart);
    if (list) list.classList.add("rl-gone");
  }

  const style = document.createElement("style");
  style.textContent = CSS;
  document.head.appendChild(style);
  for (const step of [head, banner, features, gaps, times]) {
    try {
      step();
    } catch (e) {
      console.warn("report look: cannot restyle", step.name, e);
    }
  }
})();
