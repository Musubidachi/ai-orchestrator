/*
 * One row per family: a project and its versions on one line of the table, the versions drawn as a
 * chain.
 *
 * A game built one version at a time put a line in the table for every version, thirty-eight for
 * one family, most of them saying the same thing. This puts one row in their place: where each
 * version stands, what the family waits for now and when, and what all of it has cost. Opening the
 * row shows each version's own line, rank and buttons, as before. The tiles become the table's only
 * counts, on a wide screen as on a phone, so the line of counts above them goes; it once said
 * "4 needing you" beside a tile that said 0.
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it. It hears forge:jobs
 * and forge:quota, takes a waiting version's time from wait.js, and calls the page's helpers only
 * when one fires. Its rows go into the table where the page put the lines they stand for. Of the
 * page's own it changes only the order of the lines, classes and data attributes, and it adds two
 * short lines to the tiles: a version's line is hidden while its family is closed, never removed.
 * A phone keeps its ranked list, with no family rows. Projects with no other versions keep their
 * own lines.
 */
(() => {

/* More accepted versions than this are one link of the chain, and so are more of any other kind. */
const MANY = 4;

/* The phone's filters, which the tiles are on a wide screen too. */
const KINDS = ["you", "waiting", "working", "accepted", "away"];

const icon = (path, weight, size) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" `
  + `fill="none" stroke="currentColor" stroke-width="${weight}" stroke-linecap="round" `
  + `stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
const CHEVRON = icon('<path d="M9 6l6 6-6 6"/>', 2, 16);

/* The mark on a version's link, by the word on its line in the table. */
const MARKS = {
  "Accepted": icon('<path d="M5 12l5 5L20 7"/>', 3, 12),
  "Waiting": icon('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>', 2.4, 12),
  "Paused": icon('<path d="M9 6v12"/><path d="M15 6v12"/>', 3, 12),
  "On GitHub only": icon('<path d="M7 18h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.2 9.2 4.5 4.5 0 0 0 7 18z"/>', 2.2, 13),
};

/* What a version's link says after its number, by the same word. */
const WORDS = {
  "Working": "working", "Repository missing": "missing", "Held for you": "held", "Finished": "done",
  "Parked": "parked", "Queued": "queued", "Waiting": "waiting", "Not started": "new", "Paused": "paused",
};

const filtered = k => `#jobs.fmon[data-filter=${k}] > .job:not([data-kinds~=${k}]),
    #jobs.fmon[data-filter=${k}] > .fmrow:not([data-kinds~=${k}]),
    #jobs.fmon[data-filter=${k}] > .grouphead:not([data-kinds~=${k}])`;

const CSS = `
  #jobsSummary.fmgone{display:none}
  #tiles.fmon .fmsub{display:block;font-size:.7rem;color:var(--amber-fg);white-space:nowrap}
  #tiles.fmon .fmsub[hidden]{display:none}
  #tiles.fmon .fmdisk{flex:none;align-self:center;margin-left:auto;padding:0 .25rem;font-size:.8rem;
                      color:var(--muted);white-space:nowrap}
  .lrow.fmrow{grid-template-rows:auto auto;row-gap:.35rem;padding-top:.6rem;padding-bottom:.6rem}
  .fmrow .rank{display:flex;align-items:center;color:var(--soft)}
  .fmrow .rank svg{transition:transform .15s}
  .fmrow[aria-expanded=true] .rank svg{transform:rotate(90deg)}
  .fmchain{grid-column:2/-1;grid-row:2;display:flex;flex-wrap:wrap;align-items:center;gap:.3rem 0;
           min-width:0}
  .fmchip{display:inline-flex;align-items:center;gap:.3rem;padding:.08rem .5rem;border-radius:999px;
          border:1px solid transparent;font-size:.72rem;font-weight:600;white-space:nowrap}
  .fmchip.fmlead{box-shadow:0 0 0 1px currentColor}
  /* Greys between the page's lines and its muted text: --line is too faint for a dashed edge. */
  .fmchip.fmmore{border-style:dashed;border-color:#4a4a47;color:var(--muted)}
  .fmlink{flex:none;width:.6rem;height:2px;background:#3a3a38}
  .fmnow{display:flex;flex-direction:column;min-width:0;font-size:.78rem;line-height:1.35}
  .fmnow span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .fmnow .fmwhat{color:var(--soft)}
  .fmnow .fmwhen{color:var(--muted)}
  @media (min-width:1200px){
    #tiles.fmon{display:flex;flex-wrap:wrap;gap:.5rem;margin:0 0 .75rem}
    #jobs.fmon .grouphead .gn,
    #jobs.fmon > .grouphead.fmempty,
    #jobs.fmon > .job > .lrow.fmhid,
    #jobs.fmon > .foldline.fmhid,
    ${KINDS.map(filtered).join(",\n    ")},
    #jobs.fmon:not([data-filter=all]):not([data-filter=waiting]) > .foldline{display:none}
    #jobs.fmon > .job > .lrow.fmin .main{padding-left:1.25rem}
  }
  @media (max-width:1199px){
    .lrow.fmrow{display:none}
  }
`;

const written = new WeakMap();

/* Writes markup only when it changed, so a poll does not rebuild what is under the pointer. */
function put(el, html) {
  if (written.get(el) === html) return;
  written.set(el, html);
  el.innerHTML = html;
}

/* The family a project belongs to: 008 for 008, 008.1 and 008.12. */
const rootOf = id => id.split(".")[0];

const card = id => document.getElementById("job-" + id);

/* 001 before 001.1, and 001.10 after 001.9. */
function byVersion(a, b) {
  const x = a.id.split(".").map(Number);
  const y = b.id.split(".").map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    const d = (x[i] ?? -1) - (y[i] ?? -1);
    if (d) return d;
  }
  return 0;
}

/* A project and every version of it on the board, the one most in need of saying first. */
class Family {
  constructor(name, members, rank) {
    this.name = name;
    this.members = [...members].sort((a, b) => rank(a.id) - rank(b.id));
    this.lead = this.members[0];
    this.title = (members.find(j => j.id === name) || this.lead).title;
  }

  has(id) {
    return this.members.some(j => j.id === id);
  }

  /* The first version a filter leaves on screen, if any. */
  firstShown() {
    return this.members.find(j => getComputedStyle(card(j.id)).display !== "none");
  }

  /* Every filter the row answers to: one of its versions answering is enough. */
  kinds() {
    return [...new Set(this.members.map(j => card(j.id).dataset.kinds))].join(" ");
  }

  sub() {
    const vendors = [...new Set(this.members.map(j => vendorName(j.assignedTo)).filter(Boolean))];
    return [this.name, vendors.join(" and "), `${this.members.length} versions`].filter(Boolean).join(" · ");
  }

  total(field) {
    return this.members.reduce((t, j) => t + (j[field] || 0), 0);
  }
}

/* The versions as a chain: those accepted, the rest by number, then the next one waiting its turn,
   with any after it counted rather than drawn. */
class Chain {
  constructor(family, closed, now) {
    this.family = family;
    this.closed = closed;
    this.now = now;
  }

  html() {
    const sorted = [...this.family.members].sort(byVersion);
    const turn = sorted.filter(j => waitsItsTurn(j));
    const accepted = sorted.filter(j => !turn.includes(j) && stageOf(j).text === "Accepted");
    const rest = sorted.filter(j => !turn.includes(j) && !accepted.includes(j));
    const links = accepted.length > MANY
      ? [this.link("t-pass", MARKS.Accepted, `${accepted.length} accepted`)]
      : accepted.map(j => this.version(j));
    rest.slice(0, MANY).forEach(j => links.push(this.version(j)));
    if (turn.length) links.push(this.link("t-todo", "", `${turn[0].id} next`, turn[0]));
    const more = Math.max(0, rest.length - MANY) + Math.max(0, turn.length - 1);
    if (more) links.push(`<span class="fmchip fmmore">+${more} more</span>`);
    return links.join('<i class="fmlink" aria-hidden="true"></i>');
  }

  version(j) {
    const stage = stageOf(j);
    const word = stage.text === "Waiting" && new ForgeWait(j, this.closed, this.now).kind === "review"
      ? "review" : WORDS[stage.text] || "";
    return this.link("t-" + stage.tone, MARKS[stage.text] || "", word ? `${j.id} ${word}` : j.id, j);
  }

  link(tone, mark, text, j) {
    const lead = j && j === this.family.lead ? " fmlead" : "";
    const title = j ? ` title="${esc(j.title)}"` : "";
    return `<span class="fmchip ${tone}${lead}"${title}>${mark}${esc(text)}</span>`;
  }
}

/* What the family waits for now and when, said for its first version: two short lines beside the
   word on its row, never that word again. */
class Now {
  constructor(family, closed, now) {
    this.family = family;
    this.j = family.lead;
    this.wait = new ForgeWait(this.j, closed, now);
  }

  lines() {
    const j = this.j;
    if (j.running) return [j.activity || capital(j.phaseLabel), ""];
    if (notHere(j)) return [j.shelvedBranch ? "Bring it back to work on it" : "", ""];
    if (needsYou(j)) return ["Your decision", ""];
    if (j.phase === "ACCEPTED") return [`All ${this.family.members.length} accepted`, ""];
    if (j.queuePosition > 0 && !j.blockedReason) {
      return [j.queuePosition === 1 ? "Next up" : `Number ${j.queuePosition} in the queue`, ""];
    }
    if (waitsItsTurn(j)) return [`After ${this.wait.earlier} is accepted`, ""];
    if (j.phase === "SUSPENDED" || j.blockedReason) return [this.waitsFor(), this.when()];
    return ["", ""];
  }

  waitsFor() {
    const kind = this.wait.kind;
    return kind === "review" ? "Waiting for cross-review"
      : kind === "quota" ? "Waiting for quota" : capital(this.j.blockedReason);
  }

  /* The time that counts, the same one the version's own card and the next 24 hours give. */
  when() {
    const w = this.wait;
    if (!w.until) return "";
    return w.vendorsTime() ? `${vendorName(w.vendor)} back ${whenNear(w.until)}`
      : `Can resume ${whenNear(w.until)}`;
  }
}

/* The family's line in the table: a button, like every line, that opens and closes the family. */
class Row {
  constructor(name, toggle) {
    const el = document.createElement("button");
    el.type = "button";
    el.className = "lrow fmrow";
    el.dataset.family = name;
    el.innerHTML = `<span class="rank">${CHEVRON}</span>`
      + `<span class="main"><span class="ttl"></span><span class="sub"></span></span>`
      + `<span class="stage"><span class="pill"></span></span>`
      + `<span class="num worked"></span><span class="num tokens"></span>`
      + `<span class="fmnow"><span class="fmwhat"></span><span class="fmwhen"></span></span>`
      + `<span class="fmchain"></span>`;
    el.onclick = () => toggle(name);
    $("jobs").append(el);
    this.el = el;
  }

  fill(family, closed, now) {
    const q = sel => this.el.querySelector(sel);
    const stage = stageOf(family.lead);
    const [what, when] = new Now(family, closed, now).lines();
    const tokens = family.total("tokensSpent");
    q(".ttl").textContent = family.title;
    q(".sub").textContent = family.sub();
    q(".pill").textContent = stage.text;
    q(".pill").className = "pill t-" + stage.tone;
    q(".worked").textContent = duration(family.total("workedSeconds"));
    q(".tokens").textContent = tokens ? count(tokens) : "—";
    q(".fmwhat").textContent = what;
    q(".fmwhen").textContent = when;
    put(q(".fmchain"), new Chain(family, closed, now).html());
    this.el.dataset.kinds = family.kinds();
  }

  remove() {
    this.el.remove();
  }
}

/*
 * The table's lines in the order the page placed them, with each family's row put in where its first
 * version was and its versions straight after it.
 *
 * The page gives every heading, line and fold line its place, as --od, on each poll. This reads that
 * order back right after, and keeps it for opening and closing a family between polls.
 */
class Order {
  constructor(box) {
    this.box = box;
    this.page = [];
    this.at = new Map();
    this.placed = [];
  }

  read() {
    const od = el => parseFloat(el.style.getPropertyValue("--od")) || 0;
    this.page = [...this.box.querySelectorAll(":scope > .grouphead, :scope > .foldline, :scope > .job > .lrow")]
      .sort((a, b) => od(a) - od(b));
    this.at = new Map(this.page.map((el, i) => [el, i]));
  }

  /* Where a project's line was in the page's order. */
  rank(id) {
    const at = this.at.get(document.getElementById("row-" + id));
    return at === undefined ? Infinity : at;
  }

  place(families, rows, open) {
    const out = [];
    const done = new Set();
    this.page.forEach(el => {
      const name = this.familyOf(el);
      if (!families.has(name)) {
        out.push(el);
        this.mark(el, null);
        return;
      }
      if (done.has(name)) return;
      done.add(name);
      out.push(rows.get(name).el);
      this.page.filter(x => this.familyOf(x) === name).forEach(x => {
        out.push(x);
        this.mark(x, open.has(name));
      });
    });
    out.forEach((el, i) => {
      el.style.setProperty("--od", i);
      // A heading whose lines all went under a family row elsewhere has nothing left to head.
      if (el.classList.contains("grouphead")) {
        const next = out[i + 1];
        el.classList.toggle("fmempty", !next || next.classList.contains("grouphead"));
      }
    });
    this.placed = out;
  }

  /* The family a line belongs to; none for a heading. */
  familyOf(el) {
    if (el.classList.contains("grouphead")) return null;
    if (el.classList.contains("foldline")) return el.dataset.family;
    return rootOf(el.parentElement.dataset.id);
  }

  mark(el, open) {
    el.classList.toggle("fmhid", open === false);
    el.classList.toggle("fmin", open === true);
  }
}

/* The tiles as the table's only counts, with the two things only the line of counts said: what the
   projects not here still need from you, and the disk all of them take. */
class Counts {
  show(jobs) {
    const tiles = $("tiles");
    this.away(tiles, jobs);
    this.disk(tiles, jobs);
    tiles.classList.add("fmon");
    $("jobsSummary").classList.add("fmgone");
  }

  away(tiles, jobs) {
    const tile = tiles.querySelector('[data-key="away"]');
    if (!tile) return;
    const n = jobs.filter(j => groupOf(j).key === "away" && needsYou(j)).length;
    const line = this.line(tile, "fmsub");
    line.textContent = `${n} ${n === 1 ? "awaits" : "await"} your decision`;
    line.hidden = n === 0;
  }

  disk(tiles, jobs) {
    const total = jobs.reduce((t, j) => t + (j.diskBytes || 0), 0);
    this.line(tiles, "fmdisk").textContent = total ? `${bytes(total)} on disk` : "";
  }

  line(parent, name) {
    let el = parent.querySelector("." + name);
    if (!el) {
      el = document.createElement("span");
      el.className = name;
      parent.append(el);
    }
    return el;
  }
}

/* Groups the board into families on every poll, and opens and closes them between polls. */
class Families {
  constructor() {
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    this.closed = {};
    this.families = new Map();
    this.rows = new Map();
    this.open = new Set();
    this.counts = new Counts();
    this.order = null;
    document.addEventListener("forge:jobs", e => this.safely(() => this.update(e.detail)));
    document.addEventListener("forge:quota", e => {
      this.closed = e.detail || {};
      this.safely(() => this.fill());
    });
  }

  safely(work) {
    try {
      work();
    } catch (e) {
      console.error("extras/families.js", e);
    }
  }

  update(jobs) {
    const box = $("jobs");
    if (!this.order) this.start(box);
    this.order.read();
    this.group(jobs);
    this.fill();
    this.place();
    this.counts.show(jobs);
    box.classList.add("fmon");
  }

  /* Once: a click on a line or a tile, or the window crossing a phone's width, can change which
     project is marked, and the rows follow it. */
  start(box) {
    this.order = new Order(box);
    box.addEventListener("click", () => this.safely(() => this.mark()));
    $("tiles").addEventListener("click", () => this.safely(() => this.keepInView()));
    narrow.addEventListener("change", () => this.safely(() => this.mark()));
  }

  group(jobs) {
    const members = new Map();
    jobs.forEach(j => {
      const name = rootOf(j.id);
      if (!members.has(name)) members.set(name, []);
      members.get(name).push(j);
    });
    this.families = new Map([...members]
      .filter(([, list]) => list.length > 1)
      .map(([name, list]) => [name, new Family(name, list, id => this.order.rank(id))]));
    this.rows.forEach((row, name) => {
      if (this.families.has(name)) return;
      row.remove();
      this.rows.delete(name);
    });
    this.families.forEach((family, name) => {
      if (!this.rows.has(name)) this.rows.set(name, new Row(name, n => this.toggle(n)));
    });
  }

  fill() {
    const now = new Date();
    this.families.forEach((family, name) => this.rows.get(name).fill(family, this.closed, now));
  }

  place() {
    this.order.place(this.families, this.rows, this.open);
    heads.forEach((head, key) => { head.dataset.kinds = kindOf(key); });
    this.mark();
  }

  /* Opening a family shows the version its row spoke for, unless one of them is open already. */
  toggle(name) {
    this.safely(() => {
      if (this.open.has(name)) this.open.delete(name); else this.open.add(name);
      this.place();
      const family = this.families.get(name);
      if (family && this.open.has(name) && !family.has(selected)) pick(family.lead.id);
      this.mark();
    });
  }

  /* An open family's chosen version is marked on its own line; a closed family on its row. */
  mark() {
    this.families.forEach((family, name) => {
      const open = this.open.has(name);
      const el = this.rows.get(name).el;
      el.setAttribute("aria-expanded", String(open));
      el.setAttribute("aria-current", String(!open && !narrow.matches && family.has(selected)));
    });
  }

  /* A filter that hides the open project opens the first one it leaves, as a click on it would. */
  keepInView() {
    const open = selected ? card(selected) : null;
    if (narrow.matches || (open && getComputedStyle(open).display !== "none")) return;
    for (const el of this.order.placed) {
      if (!el.classList.contains("lrow") || !el.getClientRects().length) continue;
      const family = this.families.get(el.dataset.family);
      const shown = family ? family.firstShown() : null;
      const id = family ? shown && shown.id : el.parentElement.dataset.id;
      if (!id) continue;
      pick(id);
      this.mark();
      return;
    }
  }
}

new Families();

})();
