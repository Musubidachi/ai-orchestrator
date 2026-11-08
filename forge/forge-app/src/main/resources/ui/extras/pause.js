/*
 * Stop with a reason: a project held until you press Resume Now, and why it is held.
 *
 * A plain Stop freed the project's slot, and with the queue on, the fill that followed started the
 * same project again before anyone could say why it had been stopped. Stop now asks why first, and
 * the project is held until Resume Now; one that is not running can be paused the same way. While it
 * is held its row says so, with how long and why, and so does its panel, in place of the page's own
 * three lines, with a button to change the reason.
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it. It hears forge:jobs,
 * reads the reasons from /api/pauses, and gives the page one name, ForgePause, which the waiting
 * card, the next 24 hours and the family rows ask, so a project you paused is never also said to be
 * waiting; forge:pauses tells them when the reasons change. Of the page's own it changes classes on
 * a paused project's row and panel, and one click: Stop on a project a pause can hold opens the
 * dialog here instead of stopping it outright. All else it shows is its own: a line and a word on the
 * row, a box and a button in the panel, and the dialog.
 */
(() => {

const MINUTE = 60 * 1000;

/* Reasons given often enough to offer with one press. */
const REASONS = ["Waiting for art", "I’m trying it myself", "Rewriting the spec", "Saving quota"];

const ICON = '<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
  + 'stroke-width="3" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'
  + '<path d="M9 6v12"/><path d="M15 6v12"/></svg>';

/* What the dialog says on each way into it, true whatever phase the project is in. */
const MODES = {
  stop: {
    title: j => `Stop ${j.title} (${j.id})?`, ask: "Why are you stopping it?",
    body: "The turn in progress ends now. What the builder has written stays on disk, and the queue "
      + "leaves the project alone until you press Resume Now.",
    go: "Stop it", tone: "pzstop", busy: "Stopping…",
  },
  pause: {
    title: j => `Pause ${j.title} (${j.id})?`, ask: "Why are you pausing it?",
    body: "Nothing is running it now. The queue leaves it alone until you press Resume Now.",
    go: "Pause it", tone: "primary", busy: "Pausing…",
  },
  change: {
    title: j => `Change the reason for ${j.title} (${j.id})`, ask: "Why is it paused?",
    body: "It stays paused until you press Resume Now.",
    go: "Save", tone: "primary", busy: "Saving…",
  },
};

const CSS = `
  .lrow .pzline, .lrow .pzpill, .detail .pzbox{display:none}
  .lrow.pzheld .pzline{display:flex;flex-wrap:wrap;align-items:center;gap:.1rem .5rem;margin-top:.15rem;
                       min-width:0;font-size:.8rem}
  .lrow.pzheld .stage > .pill, .lrow.pzheld .mob{display:none}
  .lrow.pzheld .pzpill{display:inline-block;padding:.05rem .55rem;border-radius:999px;font-size:.74rem;
                       font-weight:600;white-space:nowrap;background:var(--blue-bg);color:var(--blue-fg)}
  .pzwho{display:inline-flex;align-items:center;gap:.3rem;color:var(--blue-fg);font-weight:600;
         white-space:nowrap}
  .pzwhy{min-width:0;max-width:100%;color:var(--soft);white-space:nowrap;overflow:hidden;
         text-overflow:ellipsis}
  .detail.pzheld .move-label, .detail.pzheld .move-head, .detail.pzheld .move-sub{display:none}
  /* A blue between the page's blue fill and its selected line: about the pause, not asking for a choice. */
  .detail.pzheld .move{border-color:#2c3a5c}
  .detail.pzheld .pzbox{display:flex;flex-direction:column;gap:.6rem}
  .pzhead{display:flex;flex-wrap:wrap;align-items:baseline;gap:.1rem .6rem}
  .pztitle{font-size:1.05rem;font-weight:600;color:var(--blue-fg)}
  .pzwhen{font-size:.8rem;color:var(--muted)}
  .pzquote{margin:0;padding:.5rem .75rem;border-radius:.5rem;background:var(--blue-bg);color:var(--fg);
           white-space:pre-wrap;overflow-wrap:anywhere}
  .pznote{margin:0;color:var(--soft);font-size:.88rem}
  .pzdlg{width:min(34rem,calc(100vw - 2rem));padding:0;border:1px solid #3a3a38;border-radius:.9rem;
         background:var(--raise);color:var(--fg)}
  .pzdlg::backdrop{background:rgba(0,0,0,.55)}
  .pzform{display:flex;flex-direction:column;gap:.9rem;padding:1.1rem 1.25rem}
  .pzform h2{margin:0;font-size:1.05rem;line-height:1.35;overflow-wrap:anywhere}
  .pzform p{margin:0;color:var(--soft);font-size:.9rem;line-height:1.45}
  .pzfield{display:flex;flex-direction:column;gap:.4rem}
  .pzfield label{font-weight:600}
  .pzfield textarea{box-sizing:border-box;width:100%;resize:vertical;padding:.6rem .75rem;
                    border:1px solid var(--selline);border-radius:.5rem;background:var(--field);
                    color:var(--fg);font:inherit;font-size:.9rem;line-height:1.5}
  .pzhint{font-size:.78rem;color:var(--muted)}
  .pzchips{display:flex;flex-wrap:wrap;gap:.4rem}
  .pzchips button{padding:0 .75rem;border-radius:999px;background:var(--card);color:var(--soft);
                  font-size:.82rem}
  .pzfoot{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:.5rem}
  .pzfoot .pzcancel{background:transparent}
  .pzfoot .pzstop{background:var(--red-bg);color:var(--red-fg);border-color:#5a2a2a;font-weight:600}
`;

/* Must match Phase.terminal() on the Java side, as the page's own panel does. */
const terminal = j => j.phase === "DONE" || j.phase === "PARKED" || j.phase === "ACCEPTED";

/* What a pause can hold that is not held yet: begun, not finished, here, and not waiting on you. */
const pausable = j => !j.running && !terminal(j) && j.phase !== "NEW" && !notHere(j) && !needsYou(j);

/* Text set only when it changed: the page redraws every four seconds. */
const put = (el, text) => {
  if (el.textContent !== text) el.textContent = text;
};

const make = (tag, name, html) => {
  const el = document.createElement(tag);
  el.className = name;
  el.innerHTML = html;
  return el;
};

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

/* "3 days", "5 minutes"; nothing under a minute. */
function lasted(at, now) {
  const minutes = Math.floor((now - at) / MINUTE);
  if (minutes < 1) return "";
  if (minutes < 60) return plural(minutes, "minute");
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? plural(hours, "hour") : plural(Math.floor(hours / 24), "day");
}

/* The row's words: "Paused by you, 3 days". A pause with no time on record says only who. */
function byYou(note, now) {
  if (!note.at) return "Paused by you";
  return `Paused by you, ${lasted(new Date(note.at), now) || "just now"}`;
}

/* The panel's: "Wed, Nov 4 · 3 days ago". */
function since(note, now) {
  if (!note.at) return "";
  const at = new Date(note.at);
  const ago = lasted(at, now);
  return `${at.toLocaleDateString(undefined, {weekday: "short", month: "short", day: "numeric"})} · `
    + (ago ? ago + " ago" : "just now");
}

/* Where the page puts a button's answer, cleared after a minute unless something newer replaced it,
   as the page's own buttons do. */
function say(id, text) {
  const el = jobMessage(id);
  if (!el) return;
  el.textContent = text;
  setTimeout(() => {
    if (el.textContent === text) el.textContent = "";
  }, 60000);
}

/* The reasons /api/pauses gives, by project. */
class Notes {
  constructor() {
    this.map = {};
    this.text = "{}";
    this.asked = 0;
  }

  of(id) {
    return this.map[id] || null;
  }

  /* Whether a fresh read changed them. One overtaken by a later read is dropped, so an old answer
     cannot undo a newer one; one that fails changes nothing. */
  async read() {
    const asked = ++this.asked;
    const map = await fetch("/api/pauses").then(r => (r.ok ? r.json() : null)).catch(() => null);
    if (!map || asked !== this.asked) return false;
    const text = JSON.stringify(map);
    if (text === this.text) return false;
    this.map = map;
    this.text = text;
    return true;
  }
}

/* A paused project's row: under its title who paused it, how long ago and why, and Paused for its
   word. The page's word stays in the row, hidden by the class. */
class RowMark {
  constructor(row) {
    this.row = row;
  }

  show(note, now) {
    if (note) this.fill(note, now);
    this.row.classList.toggle("pzheld", !!note);
  }

  fill(note, now) {
    const main = this.row.querySelector(".main");
    const line = main.querySelector(".pzline") || main.appendChild(make("span", "pzline",
      `<span class="pzwho">${ICON}<span class="pzfor"></span></span><span class="pzwhy"></span>`));
    const stage = this.row.querySelector(".stage");
    if (!stage.querySelector(".pzpill")) stage.prepend(make("span", "pzpill", "Paused"));
    put(line.querySelector(".pzfor"), byYou(note, now));
    const why = line.querySelector(".pzwhy");
    put(why, note.reason ? `“${note.reason}”` : "");
    why.hidden = !note.reason;
  }
}

/* A paused project's panel: who paused it, when and why, in place of the page's three lines, which
   stay in the panel hidden by the class. */
class PanelMark {
  constructor(panel) {
    this.panel = panel;
  }

  show(note, now) {
    if (note) this.fill(note, now);
    this.panel.classList.toggle("pzheld", !!note);
  }

  fill(note, now) {
    let box = this.panel.querySelector(".pzbox");
    if (!box) {
      box = make("div", "pzbox", '<div class="pzhead"><span class="pztitle">Paused by you</span>'
        + '<span class="pzwhen"></span></div><blockquote class="pzquote"></blockquote>'
        + '<p class="pznote">Nothing is running it, and the queue leaves it alone until you press '
        + 'Resume Now.</p>');
      this.panel.querySelector(".move-sub").after(box);
    }
    put(box.querySelector(".pzwhen"), since(note, now));
    const quote = box.querySelector(".pzquote");
    put(quote, note.reason || "");
    quote.hidden = !note.reason;
  }
}

/* The question asked before a project is held: why. Built the first time it is needed, since the
   page's helpers are there only once one of its events has fired. */
class Dialog {
  constructor(send) {
    this.send = send;
    this.el = null;
    this.j = null;
    this.mode = "";
  }

  open(j, mode, reason) {
    if (!this.el) this.build();
    const words = MODES[mode];
    this.j = j;
    this.mode = mode;
    put(this.el.querySelector("h2"), words.title(j));
    put(this.el.querySelector(".pzbody"), words.body);
    put(this.el.querySelector("label"), words.ask);
    const go = this.el.querySelector(".pzgo");
    put(go, words.go);
    go.className = "pzgo " + words.tone;
    this.why.value = reason;
    if (!this.el.open) this.el.showModal();
    this.why.focus();
  }

  build() {
    const el = make("dialog", "pzdlg", '<form class="pzform"><h2 id="pzTitle"></h2>'
      + '<p id="pzBody" class="pzbody"></p>'
      + '<div class="pzfield"><label for="pzWhy"></label>'
      + '<textarea id="pzWhy" rows="2" maxlength="500" placeholder="Waiting for the new art."></textarea>'
      + '<div class="pzhint">Optional. It shows on the project’s row and in its panel until you resume '
      + 'it.</div></div>'
      + '<div class="pzchips" role="group" aria-label="Common reasons">'
      + REASONS.map(reason => `<button type="button">${reason}</button>`).join("") + '</div>'
      + '<div class="pzfoot"><button type="button" class="pzcancel">Cancel</button>'
      + '<button type="submit" class="pzgo"></button></div></form>');
    el.setAttribute("aria-labelledby", "pzTitle");
    el.setAttribute("aria-describedby", "pzBody");
    document.body.append(el);
    this.el = el;
    this.why = el.querySelector("textarea");
    el.querySelectorAll(".pzchips button").forEach(chip => {
      chip.onclick = () => {
        this.why.value = chip.textContent;
        this.why.focus();
      };
    });
    el.querySelector(".pzcancel").onclick = () => el.close();
    el.querySelector("form").onsubmit = e => {
      e.preventDefault();
      el.close();
      this.send(this.j, this.mode, this.why.value.trim());
    };
  }
}

/* Marks each paused project on every poll, and asks why wherever a pause can begin. */
class Pauses {
  constructor() {
    this.notes = new Notes();
    this.jobs = new Map();
    this.dialog = new Dialog((j, mode, reason) => this.send(j, mode, reason));
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    window.ForgePause = {of: j => this.shown(j)};
    document.addEventListener("forge:jobs", e => {
      this.jobs = new Map(e.detail.map(j => [j.id, j]));
      this.render();
      this.refresh();
    });
    // Heard on the way down, so the page's own Stop never hears a click this one answers.
    document.addEventListener("click", e => this.intercept(e), true);
    this.refresh();
  }

  /* The note a project's row shows: none while it runs, or while it is not on this machine. */
  shown(j) {
    return j.running || notHere(j) ? null : this.notes.of(j.id);
  }

  async refresh() {
    if (!await this.notes.read()) return;
    this.render();
    document.dispatchEvent(new CustomEvent("forge:pauses", {detail: this.notes.map}));
  }

  render() {
    const now = new Date();
    this.jobs.forEach(j => {
      try {
        this.mark(j, now);
      } catch (e) {
        console.error("extras/pause.js", j.id, e);
      }
    });
  }

  mark(j, now) {
    const row = document.getElementById("row-" + j.id);
    const panel = document.getElementById("detail-" + j.id);
    if (!row || !panel) return;
    const note = this.shown(j);
    new RowMark(row).show(note, now);
    new PanelMark(panel).show(note, now);
    this.button(panel, j, note);
  }

  /* Beside the page's act button, made once; what it says follows the project on every poll. */
  button(panel, j, note) {
    let btn = panel.querySelector(".pzbtn");
    if (!btn) {
      btn = make("button", "pzbtn", "");
      btn.type = "button";
      btn.onclick = () => this.ask(j.id);
      panel.querySelector(".act-btn").after(btn);
    }
    put(btn, note ? "Change the reason" : "Pause…");
    btn.hidden = !note && !pausable(j);
  }

  /* The button's click, read against the project as the last poll saw it. */
  ask(id) {
    const j = this.jobs.get(id);
    if (!j) return;
    const note = this.shown(j);
    if (note) this.dialog.open(j, "change", note.reason || "");
    else if (pausable(j)) this.dialog.open(j, "pause", "");
  }

  /* Stop on a project a pause can hold asks why first. On a finished project Stop ends a button job,
     a deploy say, which a pause would refuse, so the page's own Stop keeps that one. */
  intercept(e) {
    const btn = e.target instanceof Element ? e.target.closest(".detail .act-btn") : null;
    const card = btn ? btn.closest(".job") : null;
    const j = card ? this.jobs.get(card.dataset.id) : null;
    if (!j || !j.running || terminal(j)) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    this.dialog.open(j, "stop", "");
  }

  /* Sends the pause, says the answer where the page says its buttons', and redraws. */
  async send(j, mode, reason) {
    say(j.id, MODES[mode].busy);
    const r = await post("/api/jobs/pause", {id: j.id, reason}).catch(e => ({result: String(e)}));
    say(j.id, r.result || r.error || "");
    await this.refresh();
    loadJobs();
  }
}

new Pauses();

})();
