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
 * Projects with no other versions share a row the same way once two of them are under one heading
 * whose projects want nothing done here: Not on this machine, or Accepted. "Five single projects"
 * stands for five lines that each said the same thing. Under any other heading each keeps its own
 * line, as each says something different. A phone shows the same rows in its ranked list, each where
 * the first of its projects ranks, with its projects under it once it is opened.
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it. It hears forge:jobs
 * and forge:quota, takes a waiting version's time from wait.js, and calls the page's helpers only
 * when one fires. Its rows go into the table where the page put the lines they stand for. Of the
 * page's own it changes only the order of the lines, classes and data attributes, and it adds two
 * short lines to the tiles: a version's line is hidden while its family is closed, never removed.
 * A version you paused is Paused on the row and in the chain, as pause.js says on its line.
 */
(() => {

/* More accepted versions than this are one link of the chain, and so are more of any other kind. */
const MANY = 4;

/* A row of single projects draws this many of them, and counts the rest. */
const CHIPS = 8;

/* The headings whose single projects share a row: nothing under them is to be done here. */
const QUIET = new Set(["away", "accepted"]);

/* "Five single projects", as a person would say it. */
const SAID = ["No", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten"];
const say = n => SAID[n] || String(n);

/* What each order places: headings, fold lines and lines on a wide screen; on a phone, which has
   no headings, the lines, fold lines and each project's details. */
const WIDE = ":scope > .grouphead, :scope > .foldline, :scope > .job > .lrow";
const PHONE = ":scope > .foldline, :scope > .job > .lrow, :scope > .job > .detail";

/* What pause.js shows for a version you paused, or null; always null when pause.js is not attached. */
const pauseOf = j => (window.ForgePause ? window.ForgePause.of(j) : null);

/* The page's word for a version, but Paused for one you paused. */
const PAUSED = {text: "Paused", tone: "accent"};
const stageFor = j => (pauseOf(j) ? PAUSED : stageOf(j));

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
  .fmsingles .fmchain{gap:.3rem .4rem}
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
  ${KINDS.map(filtered).join(",\n  ")},
  #jobs.fmon > .foldline.fmhid,
  #jobs.fmon:not([data-filter=all]):not([data-filter=waiting]) > .foldline{display:none}
  @media (min-width:1200px){
    #tiles.fmon{display:flex;flex-wrap:wrap;gap:.5rem;margin:0 0 .75rem}
    #jobs.fmon .grouphead .gn,
    #jobs.fmon > .grouphead.fmempty,
    #jobs.fmon > .job > .lrow.fmhid{display:none}
    #jobs.fmon > .job > .lrow.fmin .main{padding-left:1.25rem}
  }
  /* A phone: a closed row hides its projects' details as well as their lines, and an open one has
     them under it, set in, each with its details under its own line as before. The row's two short
     lines go under its name, and its chain loses its links, which a wrapped chain began a line with. */
  @media (max-width:1199px){
    #jobs.fmon > .job > .fmhid{display:none}
    #jobs.fmon > .job > .fmin{margin-left:1rem}
    #jobs.fmon > .job > .lrow.fmin{width:calc(100% - 1rem)}
    .lrow.fmrow{grid-template-rows:auto auto auto;row-gap:.3rem}
    .lrow.fmrow[aria-expanded=true]{border-radius:1rem;border-bottom-color:var(--line);
                                    background:var(--card)}
    .fmrow .fmnow{grid-column:2/-1;grid-row:2;font-size:.75rem}
    .fmrow .fmchain{grid-row:3;gap:.3rem .4rem}
    .fmrow .fmlink{display:none}
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

/* The board by family: 008 with 008.1 and 008.12, and a project with no versions on its own. */
function byFamily(jobs) {
  const members = new Map();
  jobs.forEach(j => {
    const name = rootOf(j.id);
    if (!members.has(name)) members.set(name, []);
    members.get(name).push(j);
  });
  return members;
}

/* The lines that share a row, by the row's name: each family of two or more, and the single
   projects of a quiet heading once there are two of them. */
function grouped(jobs, rank) {
  const groups = new Map();
  const lone = new Map();
  byFamily(jobs).forEach((list, name) => {
    if (list.length > 1) {
      groups.set(name, new Family(name, list, rank));
      return;
    }
    const key = groupOf(list[0]).key;
    if (!QUIET.has(key)) return;
    if (!lone.has(key)) lone.set(key, []);
    lone.get(key).push(list[0]);
  });
  lone.forEach((list, key) => {
    if (list.length < 2) return;
    const singles = new Singles(key, list, rank);
    groups.set(singles.name, singles);
  });
  return groups;
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

  vendors() {
    return [...new Set(this.members.map(j => vendorName(j.assignedTo)).filter(Boolean))].join(" and ");
  }

  sub() {
    return [this.name, this.vendors(), `${this.members.length} versions`].filter(Boolean).join(" · ");
  }

  total(field) {
    return this.members.reduce((t, j) => t + (j[field] || 0), 0);
  }

  /* The middle of its row. */
  chain(closed, now) {
    return new Chain(this, closed, now).html();
  }

  /* The two short lines beside its word. */
  now(closed, now) {
    return new Now(this, closed, now).lines();
  }
}

/* Projects with no other versions under one quiet heading, on one row as a family's versions are. */
class Singles extends Family {
  constructor(key, members, rank) {
    super("singles-" + key, members, rank);
    this.title = `${say(members.length)} single projects`;
  }

  sub() {
    const ids = [...this.members].sort(byVersion).map(j => j.id).join(", ");
    return [ids, this.vendors()].filter(Boolean).join(" · ");
  }

  chain(closed, now) {
    return new Chips(this, closed, now).html();
  }

  now() {
    return new Together(this.members).lines();
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
    const stage = stageFor(j);
    const word = stage.text === "Waiting" && new ForgeWait(j, this.closed, this.now).kind === "review"
      ? "review" : WORDS[stage.text] || "";
    return this.link("t-" + stage.tone, MARKS[stage.text] || "", word ? `${j.id} ${word}` : j.id, j);
  }

  link(tone, mark, text, j) {
    const lead = j && j.id === this.family.lead.id ? " fmlead" : "";
    const title = j ? ` title="${esc(j.title)}"` : "";
    return `<span class="fmchip ${tone}${lead}"${title}>${mark}${esc(text)}</span>`;
  }
}

/* A row of single projects: each its own link, with nothing between them, as none comes after
   another. One on GitHub says what it was when it went there, as the shelf is said once beside them;
   one whose repository is missing says so on its link, as that is something to look into. */
class Chips extends Chain {
  html() {
    const sorted = [...this.family.members].sort(byVersion);
    const chips = sorted.slice(0, CHIPS).map(j => this.version(j.shelvedBranch ? {...j, shelvedBranch: ""} : j));
    if (sorted.length > CHIPS) chips.push(`<span class="fmchip fmmore">+${sorted.length - CHIPS} more</span>`);
    return chips.join("");
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
    const paused = pauseOf(j);
    if (paused) return ["Paused by you", paused.reason ? `“${paused.reason}”` : ""];
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

/* The same two lines for a row of single projects: how many of them want your decision, and how many
   are on GitHub, said once for all of them. */
class Together {
  constructor(members) {
    this.members = members;
  }

  lines() {
    return [this.what(), this.where()];
  }

  what() {
    const n = this.members.filter(needsYou).length;
    if (n) return `${n} ${n === 1 ? "awaits" : "await"} your decision`;
    return this.members.every(j => j.phase === "ACCEPTED") ? `All ${this.members.length} accepted` : "";
  }

  where() {
    const shelved = this.members.filter(j => j.shelvedBranch).length;
    if (!shelved) return "";
    return `${shelved === this.members.length ? "All" : shelved} on GitHub only`;
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
    const stage = stageFor(family.lead);
    const [what, when] = family.now(closed, now);
    const tokens = family.total("tokensSpent");
    q(".ttl").textContent = family.title;
    q(".sub").textContent = family.sub();
    q(".pill").textContent = stage.text;
    q(".pill").className = "pill t-" + stage.tone;
    q(".worked").textContent = duration(family.total("workedSeconds"));
    q(".tokens").textContent = tokens ? count(tokens) : "—";
    q(".fmwhat").textContent = what;
    q(".fmwhen").textContent = when;
    put(q(".fmchain"), family.chain(closed, now));
    this.el.classList.toggle("fmsingles", family instanceof Singles);
    this.el.dataset.kinds = family.kinds();
  }

  remove() {
    this.el.remove();
  }
}

/*
 * The table's lines in one of the page's two orders, with each row put in where the first of its
 * lines was and those lines straight after it.
 *
 * The page gives every line its place on each poll, in two orders the stylesheet picks between: --od
 * on a wide screen, by heading, and --om on a phone, by rank, each project's details after its line.
 * This reads one of them back right after, and keeps it for opening and closing a row between polls.
 */
class Order {
  constructor(box, prop, parts) {
    this.box = box;
    this.prop = prop;
    this.parts = parts;
    this.page = [];
    this.at = new Map();
    this.names = new Map();
    this.placed = [];
  }

  read() {
    const at = el => parseFloat(el.style.getPropertyValue(this.prop)) || 0;
    this.page = [...this.box.querySelectorAll(this.parts)].sort((a, b) => at(a) - at(b));
    this.at = new Map(this.page.map((el, i) => [el, i]));
  }

  /* Where a project's line was in the page's order. */
  rank(id) {
    const at = this.at.get(document.getElementById("row-" + id));
    return at === undefined ? Infinity : at;
  }

  /* `names` says which row each project's lines go under, by its id; a project in none keeps its own. */
  place(families, rows, open, names) {
    this.names = names;
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
      el.style.setProperty(this.prop, i);
      // A heading whose lines all went under a family row elsewhere has nothing left to head.
      if (el.classList.contains("grouphead")) {
        const next = out[i + 1];
        el.classList.toggle("fmempty", !next || next.classList.contains("grouphead"));
      }
    });
    this.placed = out;
  }

  /* The row a line or a project's details go under; none for a heading. */
  familyOf(el) {
    if (el.classList.contains("grouphead")) return null;
    if (el.classList.contains("foldline")) return el.dataset.family;
    return this.names.get(el.parentElement.dataset.id) || null;
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

/* Groups the board into rows on every poll, and opens and closes them between polls. */
class Families {
  constructor() {
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    this.closed = {};
    this.families = new Map();
    this.names = new Map();
    this.rows = new Map();
    this.open = new Set();
    this.counts = new Counts();
    this.order = null;
    this.phone = null;
    document.addEventListener("forge:jobs", e => this.safely(() => this.update(e.detail)));
    document.addEventListener("forge:quota", e => {
      this.closed = e.detail || {};
      this.safely(() => this.fill());
    });
    document.addEventListener("forge:pauses", () => this.safely(() => this.fill()));
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
    this.phone.read();
    this.group(jobs);
    this.fill();
    this.place();
    this.counts.show(jobs);
    box.classList.add("fmon");
  }

  /* Once: a click on a line or a tile, or the window crossing a phone's width, can change which
     project is marked, and the rows follow it. */
  start(box) {
    this.order = new Order(box, "--od", WIDE);
    this.phone = new Order(box, "--om", PHONE);
    box.addEventListener("click", () => this.safely(() => this.mark()));
    $("tiles").addEventListener("click", () => this.safely(() => this.keepInView()));
    narrow.addEventListener("change", () => this.safely(() => this.mark()));
  }

  group(jobs) {
    this.families = grouped(jobs, id => this.order.rank(id));
    this.names = new Map([...this.families].flatMap(([name, g]) => g.members.map(j => [j.id, name])));
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
    [this.order, this.phone].forEach(order => order.place(this.families, this.rows, this.open, this.names));
    heads.forEach((head, key) => { head.dataset.kinds = kindOf(key); });
    this.mark();
  }

  /* Opening a family shows the version its row spoke for, unless one of them is open already. On a
     phone it only shows their lines, each of which opens its own details under it. */
  toggle(name) {
    this.safely(() => {
      if (this.open.has(name)) this.open.delete(name); else this.open.add(name);
      this.place();
      const family = this.families.get(name);
      if (family && this.open.has(name) && !narrow.matches && !family.has(selected)) pick(family.lead.id);
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
