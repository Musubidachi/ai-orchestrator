/*
 * The next 24 hours on one strip above the table: when each vendor is closed and when it is back,
 * what can start then, and what you will have to do. It takes the place of the quota banners, which
 * gave each vendor's hold a paragraph of its own and left the times for you to put together.
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it. It hears forge:jobs
 * and forge:quota, takes each waiting project's time from wait.js, the same time the project's own
 * card gives, and calls the page's helpers only when one fires. It changes nothing of the page's but
 * a class on #quotaBar, set only once the strip is filled, which hides the vendor banners there.
 * Their Try Now buttons are under the strip's vendor messages, and press the page's own retryVendor.
 * A project you paused (pause.js) starts at no time, so the strip leaves it out.
 */
(() => {

const MINUTE = 60 * 1000;
const SPAN = 24 * 60 * MINUTE;

/* Paused by you: nothing starts it until you press Resume Now, whatever else it waits for. */
const held = j => !!(window.ForgePause && window.ForgePause.of(j));

const CSS = `
  #quotaBar.wnd > .quota:has(.retry){display:none}
  .wnd24{display:flex;flex-direction:column;gap:.55rem;margin:0 0 1rem;padding:.9rem 1.1rem;
         background:var(--panel);border:1px solid var(--line);border-radius:.85rem}
  .wnd24[hidden]{display:none}
  .wnhead{display:flex;flex-wrap:wrap;align-items:baseline;gap:.25rem .9rem;margin-bottom:.2rem}
  .wnhead h2{margin:0;font-size:1rem}
  .wnsay{margin:0;color:var(--soft);font-size:.9rem;line-height:1.45}
  .wnrow{display:grid;grid-template-columns:6.5rem minmax(0,1fr);gap:.75rem;align-items:start}
  .wnname{font-weight:600;font-size:.85rem;line-height:1.5rem}
  .wnyou .wnname{color:var(--amber-fg)}
  .wnticks .wnname{font-size:.75rem;line-height:1rem}
  .wnaxis{position:relative;height:1rem;overflow:hidden;font-size:.72rem;line-height:1rem;color:var(--muted)}
  .wnaxis span{position:absolute;top:0;transform:translateX(-50%);white-space:nowrap}
  .wnbar{position:relative;height:1.5rem;box-sizing:border-box;overflow:hidden;border-radius:.4rem;
         border-left:2px solid var(--fg);background:var(--green-bg)}
  .wnyou .wnbar{border:1px dashed var(--line);border-left:2px solid var(--fg);background:var(--field)}
  .wnshut{position:absolute;left:0;top:0;bottom:0;border-right:1px solid var(--hold-line);
          background:repeating-linear-gradient(135deg,var(--amber-bg) 0 6px,var(--hold-bg) 6px 12px)}
  .wnmark{position:absolute;top:4px;bottom:4px;width:.65rem;box-sizing:border-box;border-radius:.2rem;
          border:1px dashed var(--accent);background:var(--blue-bg)}
  .wnyou .wnmark{border:0;background:var(--wait)}
  .wncap{margin:.2rem 0 .15rem;font-size:.8rem;line-height:1.35;color:var(--green-fg)}
  .wncap.wnheld{color:var(--amber-fg)}
  .wnyou .wncap{color:var(--soft)}
  .wnmsgs{font-size:.84rem;color:var(--muted)}
  .wnmsgs summary{cursor:pointer;padding:.3rem 0;color:var(--soft)}
  .wnmsgs p{margin:.35rem 0;overflow-wrap:anywhere}
  .wntry{display:flex;flex-wrap:wrap;gap:.5rem;margin-top:.45rem}
  @media (max-width: 700px){
    .wnrow{grid-template-columns:4.75rem minmax(0,1fr);gap:.5rem}
    .wnaxis .wnodd{display:none}
  }`;

/* Where an instant falls along the next 24 hours, as a percentage of the strip's width. Rounded, so
   the strip changes about once a minute rather than on every poll. */
const place = (time, now) => Math.max(0, Math.min(100, (time - now) / SPAN * 100)).toFixed(1);
const clock = date => date.toLocaleTimeString([], {hour: "numeric", minute: "2-digit"});
const list = names => names.length < 2 ? names.join("") : `${names.slice(0, -1).join(", ")} and ${names.at(-1)}`;
const press = j => j.phase === "NEW" ? "Start" : "Resume Now";
const titleOf = j => j.title || j.id;

/* Written only when it changed, so a poll does not rebuild what is under the pointer. */
const written = new WeakMap();
const put = (el, html) => {
  if (written.get(el) === html) return false;
  el.innerHTML = html;
  written.set(el, html);
  return true;
};

/* What the next 24 hours hold, from what the page loaded. Reads no DOM. */
class Day {
  constructor(jobs, closed, queueOn, now) {
    this.jobs = jobs;
    this.closed = closed;
    this.queueOn = queueOn;
    this.now = now;
    // Drawn only with a time inside the 24 hours: one waiting its turn has none.
    this.timed = jobs.filter(j => kindOf(groupOf(j).key) === "waiting" && !held(j))
      .map(j => new ForgeWait(j, closed, now))
      .filter(w => w.until && w.until - now < SPAN)
      .sort((a, b) => a.until - b.until);
  }

  /* The vendors closed now, soonest back first. */
  shut() {
    return Object.values(this.closed).filter(c => c.until > this.now).sort((a, b) => a.until - b.until);
  }

  /* Every vendor anything is known about, as the chips in the top bar list them. */
  vendors() {
    return [...new Set([...knownVendors, ...Object.keys(this.closed)])];
  }

  /* With every vendor open and nothing due, there is nothing to say. */
  worthShowing() {
    return this.shut().length > 0 || this.timed.length > 0;
  }

  startsOn(vendor) {
    return this.timed.filter(w => w.vendor === vendor);
  }

  needingYou() {
    return this.jobs.filter(j => groupOf(j).key === "you").length;
  }

  /* "Main Game’s cross-review", or "Main Game". */
  what(w) {
    return w.kind === "review" ? `${titleOf(w.j)}’s cross-review` : titleOf(w.j);
  }

  /* Who is closed, then what happens first. */
  say() {
    const first = this.timed[0];
    const then = !first ? ""
      : this.queueOn ? `The queue starts ${this.what(first)} by itself at ${whenNear(first.until)}.`
      : `The queue is off, so nothing starts on its own: ${this.what(first)} waits for your `
        + `${press(first.j)} after ${whenNear(first.until)}.`;
    return [this.closedSentence(), then].filter(Boolean).join(" ");
  }

  /* "Codex and Antigravity are out of quota.", with a clause for each way a vendor can be closed. */
  closedSentence() {
    const byWhat = new Map();
    this.shut().forEach(c => byWhat.set(c.what, [...(byWhat.get(c.what) || []), vendorName(c.vendor)]));
    const clauses = [...byWhat].map(([what, names]) =>
      `${list(names)} ${names.length > 1 ? what.replace(/^is /, "are ") : what}`);
    return clauses.length ? `${clauses.join(", and ")}.` : "";
  }
}

/* The hours along the top, every third one, with the day named at midnight. */
class Axis {
  constructor(now) {
    this.now = now;
  }

  html() {
    const tick = new Date(this.now);
    tick.setMinutes(0, 0, 0);
    do tick.setHours(tick.getHours() + 1); while (tick.getHours() % 3);
    let html = "";
    for (let i = 0; tick - this.now < SPAN; i++, tick.setHours(tick.getHours() + 3)) {
      const at = (tick - this.now) / SPAN * 100;
      // Half of a label this near an edge would be cut off.
      if (at < 4 || at > 95) continue;
      html += `<span${i % 2 ? ' class="wnodd"' : ""} style="left:${at.toFixed(1)}%">${esc(this.label(tick))}</span>`;
    }
    return html;
  }

  label(date) {
    if (date.getHours() === 12) return "Noon";
    const hour = date.toLocaleTimeString([], {hour: "numeric"});
    return date.getHours() ? hour : `${date.toLocaleDateString([], {weekday: "short"})} ${hour}`;
  }
}

/* One vendor's row: closed until its time and open after it, with what can start on it marked. */
class Lane {
  constructor(day, vendor) {
    this.day = day;
    this.vendor = vendor;
    const c = day.closed[vendor];
    this.shut = c && c.until > day.now ? c : null;
    this.starts = day.startsOn(vendor);
  }

  html() {
    const now = this.day.now;
    const shut = this.shut ? `<span class="wnshut" style="width:${place(this.shut.until, now)}%"></span>` : "";
    const marks = this.starts.map(w => `<span class="wnmark" style="left:${place(w.until, now)}%" `
      + `title="${esc(this.from(w))}"></span>`).join("");
    return `<div class="wnrow" data-vendor="${esc(this.vendor)}">`
      + `<span class="wnname">${esc(vendorName(this.vendor))}</span>`
      + `<div><div class="wnbar" aria-hidden="true">${shut}${marks}</div>`
      + `<p class="wncap${this.shut ? " wnheld" : ""}">${esc(this.caption())}</p></div></div>`;
  }

  /* "Out of quota until 7:29 AM, then Main Game’s cross-review can run", or "Open". */
  caption() {
    const first = this.starts[0];
    const more = this.starts.length - 1;
    let said = this.shut ? `${capital(this.shut.what.replace(/^is /, ""))} until ${whenNear(this.shut.until)}` : "Open";
    if (first) {
      const together = this.shut && Math.abs(first.until - this.shut.until) < MINUTE;
      said += together ? `, then ${this.can(first)}` : ` · ${this.from(first)}`;
    }
    return more > 0 ? `${said} · ${more} more after it` : said;
  }

  can(w) {
    const does = w.kind === "review" ? "run" : w.j.phase === "NEW" ? "start" : "resume";
    return `${this.day.what(w)} can ${does}`;
  }

  from(w) {
    return `${this.can(w)} from ${whenNear(w.until)}`;
  }
}

/* What you will have to do, and when. With the queue on, starting what waits is the queue's job. */
class You {
  constructor(day) {
    this.day = day;
    this.due = day.queueOn ? [] : day.timed;
  }

  html() {
    const marks = this.due.map(w => `<span class="wnmark" style="left:${place(w.until, this.day.now)}%" `
      + `title="${esc(this.press(w))}"></span>`).join("");
    return `<div class="wnrow wnyou"><span class="wnname">You</span>`
      + `<div><div class="wnbar" aria-hidden="true">${marks}</div>`
      + `<p class="wncap">${esc(this.caption())}</p></div></div>`;
  }

  caption() {
    const n = this.day.needingYou();
    const more = this.due.length - 1;
    return [n ? `${n} project${n === 1 ? " needs" : "s need"} you now` : "",
      this.due.length ? this.press(this.due[0]) : "",
      more > 0 ? `${more} more after it` : ""].filter(Boolean).join(" · ") || "Nothing is waiting on you";
  }

  press(w) {
    return `From ${whenNear(w.until)}: press ${press(w.j)} on ${titleOf(w.j)}`;
  }
}

/* The strip: built the first time there is something to show, above the quota bar, and refilled
   in place on every poll after that. */
class Strip {
  constructor() {
    this.jobs = null;
    this.closed = null;
    this.box = null;
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    document.addEventListener("forge:jobs", e => { this.jobs = e.detail; this.render(); });
    document.addEventListener("forge:quota", e => { this.closed = e.detail; this.render(); });
    document.addEventListener("forge:pauses", () => this.render());
  }

  /* The banners are hidden only once the strip is filled, so a failure here leaves them showing. */
  render() {
    if (!this.jobs || !this.closed) return;
    let shown = false;
    try {
      const day = new Day(this.jobs, this.closed, $("autoRun").checked, new Date());
      if (day.worthShowing()) {
        this.fill(day);
        shown = true;
      }
    } catch (e) {
      console.error("extras/nextday.js", e);
    }
    if (this.box) this.box.hidden = !shown;
    $("quotaBar").classList.toggle("wnd", shown);
  }

  fill(day) {
    this.box = this.box || this.build();
    put(this.box.querySelector(".wnsay"), esc(day.say()));
    put(this.box.querySelector(".wnnow"), esc(`Now ${clock(day.now)}`));
    put(this.box.querySelector(".wnaxis"), new Axis(day.now).html());
    put(this.box.querySelector(".wnlanes"),
      day.vendors().map(v => new Lane(day, v).html()).join("") + new You(day).html());
    this.messages(day.shut());
  }

  build() {
    const box = document.createElement("section");
    box.className = "wnd24";
    box.setAttribute("aria-labelledby", "wn24");
    box.innerHTML = `<div class="wnhead"><h2 id="wn24">Next 24 hours</h2><p class="wnsay"></p></div>`
      + `<div class="wnrow wnticks"><span class="wnname wnnow"></span><div class="wnaxis" aria-hidden="true"></div></div>`
      + `<div class="wnlanes"></div>`
      + `<details class="wnmsgs"><summary></summary><div class="wnbody"></div></details>`;
    $("quotaBar").before(box);
    return box;
  }

  /* What each closed vendor said, and a Try Now for each: the banners' buttons, moved here. */
  messages(shut) {
    const box = this.box.querySelector(".wnmsgs");
    box.hidden = !shut.length;
    put(box.querySelector("summary"), esc(`Vendor messages (${shut.length})`));
    const body = box.querySelector(".wnbody");
    const html = shut.map(c => `<p>${esc(this.said(c))}</p>`).join("")
      + `<div class="wntry">${shut.map(c => this.button(c)).join("")}</div>`;
    if (put(body, html)) body.querySelectorAll(".wnretry").forEach(b => { b.onclick = () => this.retry(b); });
  }

  said(c) {
    const words = c.message ? `${vendorName(c.vendor)}: “${c.message}”` : `${vendorName(c.vendor)} gave no message.`;
    return `${words} Forge expects it back ${whenNear(c.until)}.`;
  }

  button(c) {
    return `<button type="button" class="wnretry" data-vendor="${esc(c.vendor)}" title="Reset or topped up `
      + `its limit? Forge lets the hold go, and the next call finds out">Try ${esc(vendorName(c.vendor))} Now</button>`;
  }

  /* The page's own Try Now. A vendor still closed afterwards keeps its button, ready to try again. */
  async retry(button) {
    try {
      await retryVendor(button);
    } finally {
      button.disabled = false;
    }
  }
}

new Strip();

})();
