/*
 * The last gate, as a project's panel shows it: quick or full, and what it did with each command.
 *
 * A quick gate runs a spec's commands up to the first slow one and leaves the rest for the full gate
 * (pipeline.step.QuickGate). Nothing on the panel said which kind had run: its "last gate" figure
 * added the waiting commands' old times to the new ones, and nothing said a browser suite had not
 * run at all. This puts a section under the stats that says which gate ran and whether it passed,
 * and what it did with each command, each drawn by how long it usually takes against the line a
 * quick gate stops at.
 *
 * Under them, once a version has had more than one gate, the gates on it, oldest first, each with
 * the session it followed, and the time its quick gates spared (status.gate.GateHistory).
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it. It reads /api/gate
 * (route.GateRoutes) for the open project only, and again only when what the poll says about that
 * project changes. It calls the page's helpers only when an event fires, and hides one thing of the
 * page's while its section shows: the "last gate" stat, whose sum it replaces. What it fetched is
 * window.ForgeGate.of(job) for the other extras, null until it is fresh; forge:gate says it arrived.
 */
(() => {

const icon = (path, weight, size) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" `
  + `fill="none" stroke="currentColor" stroke-width="${weight}" stroke-linecap="round" `
  + `stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
const BOLT = icon('<path d="M13 3L5 14h6l-1 7 8-11h-6z"/>', 2.2, 12);
const PASS = icon('<path d="M5 12l5 5L20 7"/>', 3, 12);
const FAIL = icon('<path d="M6 6l12 12M18 6L6 18"/>', 3, 12);
const INFO = icon('<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>', 2.2, 13);
const MARKS = {
  passed: PASS,
  failed: FAIL,
  unreached: icon('<path d="M6 12h12"/>', 3, 12),
  waits: icon('<path d="M5 5l7 7-7 7"/><path d="M13 5l7 7-7 7"/>', 2.4, 12),
  new: icon('<circle cx="12" cy="12" r="7"/>', 2.4, 12),
};
const PARTS = {
  passed: "Ran, passed",
  failed: "Failed",
  unreached: "Not reached",
  waits: "Waits for the full gate",
  new: "Not run yet",
};

/* What a quick gate leaves for the full one besides commands, as QuickGate's own comment says. */
const LEFT = "Also left for the full gate: the screenshots and recordings, the words and look checks, "
  + "and the owned-files check. The overview class and the run file are checked every time.";
const NEVER = "A quick gate never passes a version. The full gate runs when the builder reports the "
  + "work complete, when sessions run out, after an interrupted turn, and for Check Again.";

const CSS = `
  .gtsec{display:flex;flex-direction:column;gap:.6rem;padding:.75rem .85rem .8rem;
         border:1px solid var(--line);border-radius:.6rem;background:var(--card)}
  .gtsec[hidden], .stat.gthide{display:none}
  .gtsec h3{margin:0;font-size:.72rem;font-weight:700;letter-spacing:.08em;text-transform:uppercase;
            color:var(--muted)}
  .gthead{display:flex;flex-wrap:wrap;align-items:center;gap:.4rem .5rem}
  .gtpill{display:inline-flex;align-items:center;gap:.3rem;padding:.1rem .55rem;border-radius:999px;
          font-size:.78rem;font-weight:600;line-height:1.5}
  .gtquick{background:var(--blue-bg);color:var(--blue-fg)}
  .gtfull{background:var(--grey-bg);color:var(--grey-fg)}
  .gtok{background:var(--green-bg);color:var(--green-fg)}
  .gtbad{background:var(--red-bg);color:var(--red-fg)}
  .gtcount{font-size:.82rem;color:var(--soft)}
  .gtsay{margin:0;font-size:.86rem;line-height:1.45;color:var(--soft)}
  .gtsay b{color:var(--fg);font-weight:600;overflow-wrap:anywhere}
  .gtscale, .gttwo{display:grid;grid-template-columns:minmax(0,10.5rem) minmax(0,1fr);gap:.75rem}
  .gtscale{margin-bottom:-.3rem}
  .gtscale span{grid-column:2;position:relative;height:.9rem}
  .gtscale b{position:absolute;transform:translateX(-50%);font-size:.68rem;font-weight:600;
             color:var(--amber-fg);white-space:nowrap}
  .gtcmds{display:flex;flex-direction:column;gap:.6rem;list-style:none;margin:0;padding:0}
  .gtcmds li{display:flex;flex-direction:column;gap:.2rem;min-width:0}
  .gtone{display:flex;align-items:baseline;gap:.75rem;min-width:0}
  .gtone code{flex:1 1 auto;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
              font-size:.8rem;color:var(--fg)}
  .gttime{flex:none;font-size:.78rem;color:var(--soft);font-variant-numeric:tabular-nums}
  .gttwo{align-items:center}
  .gtpart{display:inline-flex;align-items:center;gap:.35rem;font-size:.78rem}
  .gtpart svg{flex:none}
  .gttrack{position:relative;height:.45rem;border-radius:.25rem;background:var(--raise)}
  .gtfill{position:absolute;left:0;top:0;bottom:0;min-width:4px;border-radius:.25rem}
  .gtmark{position:absolute;top:-.25rem;bottom:-.25rem;border-left:1px dashed var(--hold-line)}
  .gt-slow .gtmark{border-left-color:var(--wait)}
  .gt-passed .gtpart{color:var(--green-fg)}
  .gt-passed .gtfill{background:var(--pass)}
  .gt-failed .gtpart{color:var(--red-fg)}
  .gt-failed .gtfill{background:var(--fail)}
  .gt-unreached .gtpart, .gt-new .gtpart{color:var(--muted)}
  .gt-unreached .gtfill{background:repeating-linear-gradient(135deg,#3d3d3a 0 3px,transparent 3px 6px)}
  .gt-waits .gtpart{color:var(--soft)}
  .gt-waits .gtfill{background:repeating-linear-gradient(135deg,#2a2a28 0 5px,#1e1e1d 5px 10px);
                    box-shadow:inset 0 0 0 1px #3a3a38}
  .gt-new .gtfill{display:none}
  .gtnote{margin:0;font-size:.8rem;line-height:1.45;color:var(--muted)}
  .gtfoot{display:flex;gap:.4rem;margin:0;padding-top:.55rem;border-top:1px solid var(--line);
          font-size:.78rem;line-height:1.45;color:var(--muted)}
  .gtfoot svg{flex:none;margin-top:.15rem}
  .gthist{display:flex;flex-direction:column;gap:.5rem;padding-top:.55rem;border-top:1px solid var(--line)}
  .gthtop{display:flex;flex-wrap:wrap;align-items:baseline;justify-content:space-between;gap:.2rem .75rem}
  .gtmore{font-size:.78rem;color:var(--muted)}
  .gtspared{font-size:.8rem;color:var(--green-fg)}
  .gtpast{display:grid;grid-template-columns:repeat(auto-fill,minmax(8.25rem,1fr));gap:.45rem;
          list-style:none;margin:0;padding:0}
  .gtpast li{display:flex;flex-direction:column;gap:.1rem;min-width:0;padding:.4rem .6rem;
             border:1px solid var(--line);border-radius:.5rem;background:var(--field)}
  .gtpwhen, .gtpnote{font-size:.74rem;color:var(--muted)}
  .gtpkind{display:flex;align-items:center;gap:.35rem;font-size:.84rem;font-weight:600}
  .gtpkind svg{flex:none}
  .gtpkind span{font-weight:400;color:var(--soft);font-variant-numeric:tabular-nums}
  .gtpnote{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .gtpnote code{font-size:.72rem}
  .gtp-pass .gtpkind svg{color:var(--pass)}
  .gtp-fail{border-color:var(--red-bg)}
  .gtp-fail .gtpkind svg{color:var(--fail)}
  .gtp-fail .gtpnote{color:var(--red-fg)}
  .gtpast li.gtp-now{border-color:var(--selline);background:var(--sel)}
  .gtp-now .gtpwhen{color:var(--blue-fg)}
  .gtp-now .gtpnote{color:var(--soft)}
  .gtp-now.gtp-fail .gtpnote{color:var(--red-fg)}
  .gtsr{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0);white-space:nowrap}`;

/* How many of a version's gates the list shows: the latest. */
const SHOWN = 8;

const RAN = c => c.state === "passed" || c.state === "failed";
const took = s => (s == null ? "" : s > 0 ? duration(s) : "under 1s");
const several = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/* Where on a bar a number of seconds falls: the longest usual time, or the line, near the end. */
class Scale {
  constructor(gate) {
    const usual = gate.commands.map(c => c.usual || 0);
    this.slow = gate.slowSeconds;
    // The line is drawn only when it divides something: some command usually takes that long.
    this.shown = usual.some(u => u >= this.slow);
    this.full = Math.max(this.slow, ...usual) / 0.94;
  }

  at(seconds) {
    return `${(100 * seconds / this.full).toFixed(1)}%`;
  }

  label() {
    const s = this.slow;
    return s % 60 === 0 ? `${s / 60} min` : duration(s);
  }
}

/* The section's markup, from /api/gate. Every value put into it is escaped. */
class Markup {
  constructor(gate) {
    this.g = gate;
    this.scale = new Scale(gate);
  }

  html() {
    const g = this.g;
    return `<h3>Last gate</h3>${this.head()}${this.says()}`
      + (this.scale.shown ? `<div class="gtscale" aria-hidden="true"><span>`
        + `<b style="left:${this.scale.at(this.scale.slow)}">${esc(this.scale.label())}</b></span></div>` : "")
      + `<ol class="gtcmds">${g.commands.map(c => this.command(c)).join("")}</ol>`
      + (g.quick ? `<p class="gtnote">${esc(LEFT)}</p>` : "")
      + new Past(g.history).html()
      + (g.quick ? `<p class="gtfoot">${INFO}<span>${esc(NEVER)}</span></p>` : "");
  }

  head() {
    const g = this.g;
    const kind = g.quick
      ? `<span class="gtpill gtquick">${BOLT}Quick gate</span>`
      : `<span class="gtpill gtfull">Full gate</span>`;
    const verdict = g.passed
      ? `<span class="gtpill gtok">${PASS}Passed</span>`
      : `<span class="gtpill gtbad">${FAIL}Failed</span>`;
    const ran = g.commands.filter(RAN).length;
    const after = g.after > 0 ? `after session ${g.after} · ` : "";
    return `<div class="gthead">${kind}${verdict}<span class="gtcount">`
      + `${esc(`${after}${ran} of ${several(g.commands.length, "command", "commands")} · ${took(g.seconds)}`)}`
      + `</span></div>`;
  }

  says() {
    const g = this.g;
    const said = [];
    if (g.check) {
      said.push(`Every command ${g.quick ? "it ran " : ""}passed; it stopped at a check: <b>${esc(g.check)}</b>.`);
    }
    if (g.quick) {
      const n = g.commands.filter(c => c.state === "waits").length;
      said.push(esc(`${several(n, "command waits", "commands wait")} for the full gate, which runs when `
        + `the builder reports the work complete.`));
    }
    return said.map(s => `<p class="gtsay">${s}</p>`).join("");
  }

  command(c) {
    const slow = (c.usual || 0) >= this.scale.slow ? " gt-slow" : "";
    const time = RAN(c) ? took(c.seconds) : c.usual != null ? `usually ${took(c.usual)}` : "";
    const fill = c.usual != null ? `<span class="gtfill" style="width:${this.scale.at(c.usual)}"></span>` : "";
    const mark = this.scale.shown ? `<span class="gtmark" style="left:${this.scale.at(this.scale.slow)}"></span>` : "";
    return `<li class="gt-${esc(c.state)}${slow}">`
      + `<div class="gtone"><code title="${esc(c.command)}">${esc(c.command)}</code>`
      + `<span class="gttime">${esc(time)}</span></div>`
      + `<div class="gttwo"><span class="gtpart">${MARKS[c.state] || ""}${esc(PARTS[c.state] || c.state)}</span>`
      + `<span class="gttrack" aria-hidden="true">${fill}${mark}</span></div></li>`;
  }
}

/* The gates on this version, oldest first; the last of them is the one the section is about. */
class Past {
  constructor(history) {
    this.all = Array.isArray(history) ? history : [];
  }

  /* Nothing for a single gate, which the section above already is. */
  html() {
    const all = this.all;
    if (all.length < 2) return "";
    const shown = all.slice(-SHOWN);
    const more = all.length > shown.length
      ? `<span class="gtmore">${esc(`the last ${shown.length} of ${all.length}`)}</span>` : "";
    const spared = this.spared();
    return `<div class="gthist"><div class="gthtop"><h3>Gates on this version</h3>${more}`
      + (spared ? `<span class="gtspared">${esc(spared)}</span>` : "") + `</div>`
      + `<ol class="gtpast">${shown.map((p, i) => this.item(p, i === shown.length - 1)).join("")}</ol></div>`;
  }

  /* What the quick gates left for the full one, which nobody sat waiting for. */
  spared() {
    const minutes = Math.round(this.all.reduce((sum, p) => sum + (p.spared || 0), 0) / 60);
    if (minutes < 1) return "";
    const about = minutes < 90 ? several(minutes, "minute", "minutes")
      : `${(minutes / 60).toFixed(1).replace(/\.0$/, "")} hours`;
    return `About ${about} not spent waiting`;
  }

  item(p, now) {
    const failed = p.failed ? `<code>${esc(p.failed)}</code> failed` : "Failed";
    const note = p.passed ? (now ? "Latest" : "Passed") : failed;
    const title = !p.passed && p.failed ? ` title="${esc(p.failed)}"` : "";
    return `<li class="gtp-${p.passed ? "pass" : "fail"}${now ? " gtp-now" : ""}"${now ? ' aria-current="true"' : ""}>`
      + `<span class="gtpwhen">${esc(`After session ${p.session}`)}</span>`
      + `<span class="gtpkind">${esc(p.quick ? "Quick" : "Full")}${p.passed ? PASS : FAIL}`
      + `<span class="gtsr">${p.passed ? "passed" : "failed"}</span><span>${esc(took(p.seconds))}</span></span>`
      + `<span class="gtpnote"${title}>${note}</span></li>`;
  }
}

/* Fetches the open project's last gate, and keeps its section and the stat it replaces in step. */
class GateView {
  constructor() {
    this.jobs = [];
    this.cache = new Map();       // id -> {key, data}: the last answer, and what the poll said then
    this.asking = new Map();      // id -> key of the request in flight
    this.written = new WeakMap(); // section -> the markup last put in it
    document.addEventListener("forge:jobs", e => {
      this.jobs = e.detail || [];
      this.watch();
      this.sweep();
    });
  }

  /* Anything the poll says that changes when a gate runs, or begins to. */
  key(j) {
    return [j.phase, j.sessions, j.gateSeconds, j.lastFailingCommand, j.repairRounds].join("|");
  }

  /* What was fetched for a project, while the poll still says what it said then; otherwise null. */
  fresh(j) {
    const had = j && this.cache.get(j.id);
    return had && had.key === this.key(j) ? had.data : null;
  }

  /* A panel opened by a click is not a poll; a class on a card is how the page opens one. */
  watch() {
    const box = document.getElementById("jobs");
    if (this.observer || !box) return;
    this.observer = new MutationObserver(records => {
      if (records.some(r => r.target.parentElement === box && r.target.classList.contains("job"))) this.sweep();
    });
    this.observer.observe(box, {attributes: true, attributeFilter: ["class"], subtree: true});
  }

  sweep() {
    const card = document.querySelector("#jobs > .job.open");
    const j = card && this.jobs.find(x => x.id === card.dataset.id);
    const panel = card && card.querySelector(":scope > .detail");
    if (!j || !panel) return;
    const key = this.key(j);
    const had = this.cache.get(j.id);
    if ((!had || had.key !== key) && this.asking.get(j.id) !== key) this.ask(j.id, key);
    this.show(panel, had ? had.data : null);
  }

  ask(id, key) {
    this.asking.set(id, key);
    fetch(`/api/gate?id=${encodeURIComponent(id)}`)
      .then(r => (r.ok ? r.json() : null))
      .catch(() => null)
      .then(data => {
        if (this.asking.get(id) === key) this.asking.delete(id);
        this.cache.set(id, {key, data});
        this.sweep();
        document.dispatchEvent(new CustomEvent("forge:gate", {detail: {id}}));
      });
  }

  show(panel, gate) {
    let section = panel.querySelector(":scope > .gtsec");
    const shown = !!(gate && gate.ran && Array.isArray(gate.commands));
    if (shown && !section) {
      const anchor = panel.querySelector(":scope > .slow") || panel.querySelector(":scope > .stats");
      if (!anchor) return;
      section = document.createElement("section");
      section.className = "gtsec";
      section.setAttribute("aria-label", "Last gate");
      anchor.after(section);
    }
    if (section) section.hidden = !shown;
    if (shown) {
      const html = new Markup(gate).html();
      if (this.written.get(section) !== html) {
        section.innerHTML = html;
        this.written.set(section, html);
      }
    }
    // The stats are rebuilt on every poll, so the stat is found again each time.
    panel.querySelectorAll(":scope > .stats > .stat").forEach(stat => {
      const label = stat.querySelector("span");
      stat.classList.toggle("gthide", shown && !!label && label.textContent === "last gate");
    });
  }
}

const style = document.createElement("style");
style.textContent = CSS;
document.head.appendChild(style);
const view = new GateView();
window.ForgeGate = {of: j => view.fresh(j)};

})();
