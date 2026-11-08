/*
 * The report guide: a rail of the page's sections, each with what in it is worth a look, and a
 * bar that keeps your review in reach while you read.
 *
 * Attached once, after the page's last section (ProjectReport, through ReportGuide), so every
 * section is on the page when it runs. It reads the page and adds to it. It adds no form, no
 * submit button and no row the review form reads, so the form's own script sends what it always
 * sent, and the bar's Submit is the form's own submit. Take out the line that attaches it and the
 * page is as it was.
 *
 * A count says what a section shows and nothing more. A section with nothing to count gets no
 * count, never "none": a table with no rows is not proof that nothing was there to find.
 */
(() => {
  const main = document.querySelector("main");
  if (!main) return;

  const CSS = `
  main > h2 { scroll-margin-top: 1rem; }
  .rg-rail { display: none; font-size: .85rem; }
  .rg-title { margin: 0 0 .5rem .75rem; color: var(--muted); font-size: .72rem;
              text-transform: uppercase; letter-spacing: .05em; }
  .rg-rail ol { list-style: none; margin: 0; padding: 0; border-left: 1px solid var(--line); }
  .rg-rail a { display: flex; align-items: baseline; justify-content: space-between; gap: .6rem;
               margin-left: -1px; padding: .3rem .5rem .3rem .75rem;
               border-left: 2px solid transparent; color: var(--muted); text-decoration: none;
               line-height: 1.35; }
  .rg-rail a:hover { color: var(--fg); }
  .rg-rail a[aria-current] { color: var(--fg); border-left-color: var(--fg); }
  .rg-rail a[aria-current] .rg-label { font-weight: 600; }
  .rg-hold { flex: none; font-size: .75rem; font-variant-numeric: tabular-nums; }
  .rg-hold.rg-warn { color: var(--wait); }
  .rg-hold.rg-bad { color: var(--fail); font-weight: 600; }
  .rg-hold.rg-pill { padding: 0 .45rem; border: 1px solid currentColor; border-radius: 1rem;
                     color: var(--fg); }
  @media (min-width: 75rem) {
    body.rg-on { display: grid; grid-template-columns: 13.5rem minmax(0, 60rem);
                 justify-content: center; column-gap: 2.5rem; align-items: start; }
    body.rg-on > main { grid-column: 2; grid-row: 1; margin: 0; min-width: 0; }
    .rg-rail { display: block; grid-column: 1; grid-row: 1; position: sticky; top: 1rem;
               max-height: calc(100vh - 2rem); overflow: auto; }
  }
  html.rg-barred { scroll-padding-bottom: 6rem; }
  .rg-bar { position: sticky; bottom: .75rem; z-index: 5; display: flex; flex-wrap: wrap;
            align-items: center; gap: .6rem 1rem; margin: 2rem 0 0; padding: .6rem .75rem .6rem 1rem;
            background: var(--card); color: var(--fg); border: 1px solid var(--line);
            border-radius: .6rem;
            box-shadow: 0 8px 28px rgba(0, 0, 0, .14), 0 1px 3px rgba(0, 0, 0, .08);
            transition: opacity .15s, visibility .15s; }
  .rg-bar.rg-away { opacity: 0; visibility: hidden; pointer-events: none; }
  .rg-bar [hidden] { display: none !important; }
  .rg-count { display: flex; align-items: center; gap: .6rem; flex: none; font-size: .9rem; }
  .rg-segs { display: flex; gap: 3px; }
  .rg-segs span { width: .85rem; height: .4rem; border-radius: 2px; background: var(--line); }
  .rg-segs span.on { background: var(--fg); }
  .rg-n { color: var(--muted); font-size: .85rem; font-variant-numeric: tabular-nums; }
  .rg-why { flex: 1 1 16rem; min-width: 0; margin: 0; color: var(--muted); font-size: .85rem;
            line-height: 1.4; display: -webkit-box; -webkit-box-orient: vertical;
            -webkit-line-clamp: 2; overflow: hidden; }
  .rg-why.rg-said { color: var(--fg); }
  .rg-why.rg-blocked { color: var(--fg); border-left: 3px solid var(--wait); padding-left: .6rem; }
  .rg-acts { display: flex; gap: .5rem; flex: none; margin-left: auto; }
  .rg-acts button { padding: .45rem .9rem; border: 1px solid var(--line); border-radius: .35rem;
                    background: var(--card); color: var(--fg); font: inherit; font-size: .9rem;
                    cursor: pointer; }
  .rg-acts button:hover { border-color: var(--fg); }
  .rg-acts .rg-next { background: var(--fg); color: var(--bg); border-color: var(--fg); }
  .decide-item.rg-here { outline: 2px solid var(--fg); outline-offset: 2px; }
  @media (max-width: 40rem) { .rg-why { display: none; } }
  @media (prefers-reduced-motion: reduce) { .rg-bar { transition: none; } }
  @media print {
    .rg-rail, .rg-bar { display: none !important; }
    body.rg-on { display: block; }
  }
  `;

  const text = (el) => (el ? el.textContent.trim() : "");
  const plural = (count, one, many) => count + " " + (count === 1 ? one : many);

  /* What follows a heading, up to the next one. */
  function partsOf(h2) {
    const parts = [];
    for (let el = h2.nextElementSibling; el && el.tagName !== "H2"; el = el.nextElementSibling) {
      parts.push(el);
    }
    return parts;
  }

  /* Every element of a section that matches, its top-level elements included. */
  function within(parts, selector) {
    return parts.flatMap((part) =>
      (part.matches(selector) ? [part] : []).concat([...part.querySelectorAll(selector)]));
  }

  /* ---- What each section holds ---- */

  function features(parts) {
    const count = within(parts, ".feature-item").length;
    return count ? { text: plural(count, "feature", "features") } : null;
  }

  function gaps(parts) {
    const count = within(parts, "li").length;
    return count
      ? { text: String(count), tone: "warn",
          title: plural(count, "thing", "things") + " the acceptance commands do not cover" }
      : null;
  }

  function timings(parts) {
    const all = within(parts, "li");
    const slower = all.filter((li) => li.querySelector("strong")).length;
    if (slower) {
      return { text: slower + " slower", tone: "warn",
        title: slower + " of " + plural(all.length, "command", "commands") + " slower than usual" };
    }
    return all.length ? { text: plural(all.length, "command", "commands") } : null;
  }

  const SEVERITIES = ["critical", "high", "medium", "low"];
  const TONES = { critical: "bad", high: "bad", medium: "warn" };
  const rank = (level) => {
    const at = SEVERITIES.indexOf(level);
    return at < 0 ? SEVERITIES.length : at;
  };

  function security(parts) {
    const table = within(parts, "table").find((t) => text(t.querySelector("th")) === "Severity");
    if (!table) return null;
    const counts = new Map();
    for (const cell of table.querySelectorAll("tr > td:first-child")) {
      // The severity is the cell's own text; "needs your decision" follows it in a <strong>.
      const lead = cell.firstChild;
      const level = (lead && lead.nodeType === Node.TEXT_NODE ? lead.nodeValue : cell.textContent)
        .trim().toLowerCase();
      if (level) counts.set(level, (counts.get(level) || 0) + 1);
    }
    const levels = [...counts.keys()].sort((a, b) => rank(a) - rank(b));
    if (!levels.length) return null;
    const worst = levels[0];
    return { text: counts.get(worst) + " " + worst, tone: TONES[worst] || "",
      title: "Found: " + levels.map((level) => counts.get(level) + " " + level).join(", ") };
  }

  function crossReview(parts) {
    const cards = within(parts, ".card");
    const listed = (heading) => {
      const card = cards.find((c) => text(c.querySelector("strong")) === heading);
      return card ? card.querySelectorAll("li").length : 0;
    };
    const blocking = listed("Blocking");
    const notes = listed("Notes");
    const title = [blocking && plural(blocking, "blocking finding", "blocking findings"),
      notes && plural(notes, "note", "notes")].filter(Boolean).join(", ");
    if (blocking) return { text: blocking + " blocking", tone: "bad", title };
    return notes ? { text: plural(notes, "note", "notes"), title } : null;
  }

  function claims(parts) {
    const all = within(parts, "li");
    const missing = all.filter((li) =>
      [...li.querySelectorAll("strong")].some((s) => text(s) === "could not be found")).length;
    if (missing) {
      return { text: missing + " not found", tone: "warn",
        title: missing + " of " + plural(all.length, "claim", "claims") + " could not be found in the code" };
    }
    return all.length ? { text: plural(all.length, "claim", "claims") } : null;
  }

  /* By the section's id: the page's own where it gives one, otherwise the one the rail makes from
     its heading. Ids rather than headings, so the page's text is not repeated in this script, where
     a test reading the page for a phrase would find it. A reworded heading loses its count, never
     its link; report-guide.spec.js reads the real pages to catch that. */
  const HOLDS = new Map([
    ["features", features],
    ["what-this-does-not-prove", gaps],
    ["how-long-the-checks-take", timings],
    ["security", security],
    ["cross-review", crossReview],
    ["feature-claims-checked-one-by-one", claims],
  ]);

  function holdFor(h2) {
    const count = HOLDS.get(h2.id);
    if (!count) return null;
    try {
      return count(partsOf(h2));
    } catch (e) {
      console.warn("report guide: cannot count", text(h2), e);
      return null;
    }
  }

  /* ---- The review form, read the way its own script reads it ---- */

  const form = document.getElementById("decide");
  const rows = () => (form ? [...form.querySelectorAll("[data-kind=review]")] : []);
  const picked = (row) => row.querySelector("input[type=radio]:checked");
  const submit = form && form.querySelector(".decide-actions button[type=submit]");

  /* ---- The rail ---- */

  const headings = [...main.children].filter((el) => el.tagName === "H2");
  const reviewHeading = form
    ? headings.find((h2) => partsOf(h2).some((part) => part === form || part.contains(form)))
    : undefined;

  function slug(h2) {
    const base = text(h2).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "section";
    let id = base;
    for (let n = 2; document.getElementById(id); n++) id = base + "-" + n;
    return id;
  }

  function railItem(h2) {
    if (!h2.id) h2.id = slug(h2);
    const link = document.createElement("a");
    link.href = "#" + h2.id;
    const label = document.createElement("span");
    label.className = "rg-label";
    label.textContent = text(h2);
    const hold = document.createElement("span");
    hold.className = "rg-hold";
    if (h2 === reviewHeading) {
      keepCount(hold);
    } else {
      const found = holdFor(h2);
      if (found) {
        hold.textContent = found.text;
        if (found.tone) hold.classList.add("rg-" + found.tone);
        if (found.title) hold.title = found.title;
      }
    }
    link.append(label, hold);
    const item = document.createElement("li");
    item.append(link);
    return item;
  }

  /* The review's own count, kept as you tick. */
  function keepCount(hold) {
    const show = () => {
      const all = rows();
      const said = all.length ? all.filter(picked).length + " of " + all.length : "";
      hold.textContent = said;
      hold.title = said ? said + " ruled on" : "";
      hold.classList.toggle("rg-pill", all.length > 0);
    };
    show();
    form.addEventListener("change", show);
  }

  function buildRail() {
    const rail = document.createElement("nav");
    rail.className = "rg-rail";
    rail.setAttribute("aria-label", "On this page");
    const title = document.createElement("div");
    title.className = "rg-title";
    title.textContent = "On this page";
    const list = document.createElement("ol");
    list.append(...headings.map(railItem));
    rail.append(title, list);
    main.before(rail);
    document.body.classList.add("rg-on");
    follow(rail);
  }

  /* Marks the section being read: the last heading above a line 30% down the window, or the last
     one once the page is scrolled to its end. A link just clicked stays marked until the reader
     scrolls on, even where the page cannot bring its heading up to the line. */
  function follow(rail) {
    const links = [...rail.querySelectorAll("a")];
    let pinned = null;
    let queued = false;
    const mark = (current) => links.forEach((link) => {
      if (link === current) link.setAttribute("aria-current", "location");
      else link.removeAttribute("aria-current");
    });
    const update = () => {
      queued = false;
      if (pinned) {
        mark(pinned);
        return;
      }
      const line = innerHeight * 0.3;
      const room = document.documentElement.scrollHeight - innerHeight;
      let at = -1;
      headings.forEach((h2, i) => {
        if (h2.getBoundingClientRect().top <= line) at = i;
      });
      if (room > 2 && scrollY >= room - 2) at = headings.length - 1;
      mark(links[at] || null);
    };
    const queue = () => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(update);
    };
    rail.addEventListener("click", (event) => {
      const link = event.target.closest("a");
      if (!link) return;
      pinned = link;
      queue();
    });
    for (const type of ["wheel", "touchstart", "keydown"]) {
      addEventListener(type, () => { pinned = null; }, { passive: true });
    }
    addEventListener("scroll", queue, { passive: true });
    addEventListener("resize", queue);
    update();
  }

  /* ---- The bar ---- */

  function buildBar() {
    const actions = submit.closest(".decide-actions");
    const msg = document.getElementById("decide-msg");
    const blocked = document.querySelector(".decide-blocked");

    const bar = document.createElement("section");
    bar.className = "rg-bar";
    bar.setAttribute("aria-label", "Your review");
    bar.innerHTML = '<div class="rg-count"><strong>Your review</strong>'
      + '<span class="rg-segs" aria-hidden="true"></span><span class="rg-n"></span></div>'
      + '<p class="rg-why"></p>'
      + '<div class="rg-acts"><button type="button" class="rg-next">Next to rule on</button>'
      + '<button type="button" class="rg-submit"></button></div>';
    const segs = bar.querySelector(".rg-segs");
    const count = bar.querySelector(".rg-n");
    const why = bar.querySelector(".rg-why");
    const next = bar.querySelector(".rg-next");
    const send = bar.querySelector(".rg-submit");
    send.textContent = text(submit) || "Submit review";

    let said = "";       // what the form last said beside its own button
    let stale = false;   // and whether anything has been ticked since

    // What to read first. The form's own last word, while it still applies; then why it cannot be
    // acted on yet; then what is left, and what submitting will do.
    function reason(all, left) {
      if (said && !stale) return { text: said, tone: "said" };
      if (blocked && text(blocked)) return { text: text(blocked), tone: "blocked" };
      if (left) return { text: "Rule on the " + left + " left: Fine as it is, or Needs work." };
      if (!all.length) return { text: "Nothing outstanding." };
      const back = all.filter((row) => (picked(row) || {}).value === "changes").length;
      return back
        ? { text: plural(back, "item", "items") + " sent back: submitting starts the next session with your notes." }
        : { text: "Fine as it is on everything: submitting accepts the project." };
    }

    function render() {
      const all = rows();
      const done = all.filter(picked).length;
      const left = all.length - done;
      segs.replaceChildren(...(all.length <= 12 ? all.map((row) => {
        const seg = document.createElement("span");
        if (picked(row)) seg.className = "on";
        return seg;
      }) : []));
      count.textContent = all.length ? done + " of " + all.length + " ruled on" : "Nothing to rule on";
      next.hidden = left === 0;
      const now = reason(all, left);
      why.textContent = now.text;
      why.title = now.text;
      why.className = "rg-why" + (now.tone ? " rg-" + now.tone : "");
    }

    let lit = null;
    let unlight = 0;
    next.addEventListener("click", () => {
      const row = rows().find((r) => !picked(r));
      if (!row) return;
      row.scrollIntoView({ block: "center" });
      const first = row.querySelector("input[type=radio]");
      if (first) first.focus({ preventScroll: true });
      if (lit) lit.classList.remove("rg-here");
      clearTimeout(unlight);
      lit = row;
      row.classList.add("rg-here");
      unlight = setTimeout(() => row.classList.remove("rg-here"), 1600);
    });
    send.addEventListener("click", () => {
      if (form.requestSubmit) form.requestSubmit(submit);
      else submit.click();
    });
    form.addEventListener("change", () => {
      stale = true;
      render();
    });
    if (msg) {
      new MutationObserver(() => {
        said = text(msg);
        stale = false;
        render();
      }).observe(msg, { childList: true, characterData: true, subtree: true });
    }
    // Out of the way while the form's own button is on screen: two Submits side by side would be
    // one too many.
    if (actions && "IntersectionObserver" in window) {
      new IntersectionObserver((entries) => {
        bar.classList.toggle("rg-away", entries[entries.length - 1].isIntersecting);
      }).observe(actions);
    }
    render();
    main.append(bar);
    document.documentElement.classList.add("rg-barred");
  }

  const style = document.createElement("style");
  style.textContent = CSS;
  document.head.appendChild(style);
  if (headings.length >= 3) buildRail();
  if (submit) buildBar();
})();
