/*
 * One status and one time for a project that is waiting.
 *
 * A waiting project's panel said it six ways, with two different times: its phase (SUSPENDED), the
 * box's label, the blocked reason with one vendor's time, a "resumes" line with another vendor's,
 * and the raw error (RATE_LIMITED). This puts one card in their place: what it waits for, the time
 * that counts, and what you will have to do then, with the steps left in order beneath. What it
 * hides is still said, once, under "Why it is waiting".
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it. It hears forge:jobs
 * and forge:quota, takes what each project waits for and when from wait.js, calls the page's helpers
 * only when one fires, and changes nothing of the page's but a class on each waiting project's
 * panel, set only once that project's card is filled.
 */
(() => {

const MINUTE = 60 * 1000;

const icon = (path, weight, size) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" `
  + `fill="none" stroke="currentColor" stroke-width="${weight}" stroke-linecap="round" `
  + `stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
const CLOCK = icon('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>', 2.2, 18);
const MARKS = {
  pass: icon('<path d="M5 12l5 5L20 7"/>', 3, 13),
  wait: icon('<path d="M12 7v5l3 2"/>', 3, 12),
  fail: icon('<path d="M6 6l12 12M18 6L6 18"/>', 3, 12),
};

/* What a step of the page's steps() is called once it is done, and while it is still ahead. */
const DONE = {Build: "Built", Gate: "Gate passed", Security: "Security checked", Review: "Reviewed"};
const AHEAD = {Security: "Security check", Review: "Cross-review", Accepted: "Your decision"};

const CSS = `
  .detail.wst > .dhead .tag, .detail.wst .move-label, .detail.wst .move-head, .detail.wst .move-sub,
  .detail.wst > .steps, .detail.wst > .phaseline, .detail.wst > .doing.prob{display:none !important}
  .detail:not(.wst) .wcard, .detail:not(.wst) .wwhy, .detail:not(.wst) .wsteps{display:none}
  .detail.wst .move{background:var(--hold-bg);border-color:var(--hold-line)}
  .wcard{display:flex;flex-direction:column;gap:.4rem}
  .wtitle{display:flex;align-items:center;gap:.5rem;margin:0;font-size:1.05rem;line-height:1.3;
          color:var(--amber-fg)}
  .wtitle svg{flex:none}
  .wsay{margin:0;font-size:.95rem;line-height:1.45}
  .wtodo{margin:0;color:var(--soft);font-size:.88rem}
  .wwhy{font-size:.84rem;color:var(--soft)}
  .wwhy summary{cursor:pointer;padding:.55rem 0}
  .wwhy ul{display:flex;flex-direction:column;gap:.3rem;margin:0 0 .2rem;padding-left:1.1rem}
  .wwhy .wraw{color:var(--muted);overflow-wrap:anywhere}
  .wsteps h3{margin:0 0 .55rem;font-size:.72rem;font-weight:700;letter-spacing:.08em;
             text-transform:uppercase;color:var(--muted)}
  .wsteps ol{list-style:none;margin:0;padding:0}
  .wsteps li{display:grid;grid-template-columns:1.6rem minmax(0,1fr);gap:.7rem}
  .wrail{display:flex;flex-direction:column;align-items:center}
  .wmark{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;
         width:1.5rem;height:1.5rem;border-radius:50%;font-size:.7rem;font-weight:700}
  .wline{flex:1 1 0;width:2px;min-height:.75rem;background:var(--line)}
  .wsteps li:last-child .wline{display:none}
  .wtxt{display:flex;flex-direction:column;min-width:0;padding:.1rem 0 .9rem}
  .wsteps li:last-child .wtxt{padding-bottom:0}
  .whead{display:flex;flex-wrap:wrap;align-items:baseline;gap:.2rem .6rem}
  .wname{font-weight:600}
  .wwhen,.wsub{font-size:.82rem}
  .wsub{color:var(--muted)}
  .w-pass .wmark{background:var(--green-bg);color:var(--green-fg)}
  .w-pass .wline{background:#1f4a30}
  .w-wait .wmark{border:2px solid var(--wait);background:var(--hold-bg);color:var(--amber-fg)}
  .w-wait .wname,.w-wait .wwhen{color:var(--amber-fg)}
  .w-wait .wsub{color:var(--soft)}
  .w-fail .wmark{background:var(--red-bg);color:var(--red-fg)}
  .w-todo .wmark{border:2px solid #4a4a47;background:var(--card);color:var(--soft)}`;

/* The card's sentences. Only the say() sentence is HTML, and every value put into it is escaped. */
class Words {
  constructor(wait, queue) {
    this.w = wait;
    this.queue = queue;
  }

  title() {
    const w = this.w;
    if (w.kind === "review") return "Waiting for a second opinion";
    if (w.kind === "turn") return "Waiting its turn";
    if (w.kind === "quota") return this.unavailable() ? `Waiting for ${vendorName(w.vendor)}` : "Waiting for quota";
    return "Waiting";
  }

  unavailable() {
    const c = this.w.closed[this.w.vendor];
    return !!c && c.what === "is unavailable";
  }

  /* The one sentence, with the time that counts in bold. */
  say() {
    const w = this.w;
    if (w.kind === "turn") return `It can start once <b>${esc(w.earlier)}</b> is accepted.`;
    const server = esc(capital(w.serverSays() || "its wait is over")) + ".";
    if (!w.until) return server;
    const at = `<b>${esc(whenNear(w.until))}</b> (in ${esc(duration((w.until - w.now) / 1000))})`;
    if (w.kind === "other") return `${server} It can start again ${at}.`;
    if (!w.vendorsTime()) return `It can start again ${at}.`;
    const back = this.unavailable() ? "is unavailable until" : "is back";
    const does = w.kind === "review" ? "reviews" : "builds";
    return `${esc(vendorName(w.vendor))} ${does} it, and ${back} ${at}.`;
  }

  /* What you will have to do, which with the queue off is press the button yourself. */
  todo() {
    const w = this.w;
    const later = !!w.until || w.kind === "turn" || !!w.serverSays();
    const what = w.kind === "review" ? "the review" : "it";
    if (this.queue.on) {
      return w.kind === "other" ? "" : `The queue starts ${what} by itself ${later ? "then" : "on its next pass"}.`;
    }
    const press = w.j.phase === "NEW" ? "Start" : "Resume Now";
    return `Keep the queue moving is off, so press ${press}${later ? " then" : ""}`
      + (this.queue.canTurnOn ? `, or turn the queue on and ${what} starts by itself.` : ".");
  }

  /* Why it is waiting, in sentences. */
  why() {
    const w = this.w;
    const said = [];
    if (w.kind === "review") {
      w.reviewers().forEach(c => said.push(`${vendorName(c.vendor)} ${c.what} until ${whenNear(c.until)}.`));
      if (w.j.assignedTo) said.push(`${vendorName(w.j.assignedTo)} built this version, so it can’t review it.`);
    }
    if (w.kind === "quota" && w.window) {
      said.push(`${vendorName(w.vendor)} ${w.closed[w.vendor].what} until ${whenNear(w.window)}.`);
    }
    if (w.kind === "turn") {
      said.push("Versions of one family are built one at a time, in order, so this one waits for "
        + `${w.earlier} to be accepted.`);
    }
    return said.concat(this.storedTime());
  }

  /* Said only when the time the project stored is not the one that counts. */
  storedTime() {
    const w = this.w;
    const stored = w.stored();
    const differs = t => !!stored && !!t && Math.abs(stored - t) >= MINUTE;
    if (w.kind === "review" && differs(w.until)) {
      return [`The project still stores ${whenNear(stored)} as its retry time, but it may start as soon `
        + `as any reviewer is free, so ${vendorName(w.vendor)}’s ${whenNear(w.until)} is the time that counts.`];
    }
    if (w.kind === "quota" && differs(w.window)) {
      return [`${vendorName(w.vendor)} is back at ${whenNear(w.window)}, but the project stored `
        + `${whenNear(stored)} as its retry time, so it waits for the later of the two.`];
    }
    return [];
  }

  /* What the panel used to show and the card hides, word for word. */
  raw() {
    const w = this.w;
    const j = w.j;
    const lines = [`Forge’s state: ${j.phase}${j.phaseLabel ? ` (${j.phaseLabel})` : ""}.`];
    if (j.lastError) lines.push(`Last error: ${j.lastError}`);
    const vendors = w.kind === "review" ? w.reviewers() : w.window ? [w.closed[w.vendor]] : [];
    vendors.filter(c => c.message).forEach(c => lines.push(`${vendorName(c.vendor)} said: “${c.message}”`));
    return lines;
  }
}

/* The page's five steps, top to bottom, with the time on the one the project waits at. */
class StepList {
  constructor(wait) {
    this.w = wait;
  }

  html() {
    const all = steps(this.w.j);
    const at = this.w.kind === "review" ? 3 : all.findIndex(s => s.tone !== "pass");
    return all.map((s, i) => this.item(s, i, i === at)).join("");
  }

  item(s, i, here) {
    const done = s.tone === "pass" && !here;
    const tone = here ? "wait" : done ? "pass" : s.tone === "fail" ? "fail" : "todo";
    const name = done ? this.doneName(s) : AHEAD[s.name] || s.name;
    const when = here ? this.when() : "";
    return `<li class="w-${tone}"${here ? ' aria-current="step"' : ""}>`
      + `<span class="wrail"><span class="wmark">${MARKS[tone] || i + 1}</span><span class="wline"></span></span>`
      + `<span class="wtxt"><span class="whead"><span class="wname">${esc(name)}</span>`
      + (when ? `<span class="wwhen">${esc(when)}</span>` : "") + `</span>`
      + `<span class="wsub">${esc(this.sub(s, here))}</span></span></li>`;
  }

  /* The page passes the security step of a project that was never checked, and "Security checked"
     would then say something that did not happen. */
  doneName(s) {
    const unchecked = s.name === "Security" && (this.w.j.securityState || "none") === "none";
    return unchecked ? s.name : DONE[s.name] || s.name;
  }

  when() {
    const w = this.w;
    if (w.kind === "turn") return `after ${w.earlier} is accepted`;
    return w.until ? `from ${whenNear(w.until)}` : "";
  }

  sub(s, here) {
    const j = this.w.j;
    if (s.name === "Gate" && s.tone === "pass" && j.gateSeconds) {
      return `the last run took ${duration(j.gateSeconds)}`;
    }
    if (s.name === "Review" && here && this.w.kind === "review") return this.review();
    if (s.name === "Accepted" && s.tone !== "pass") {
      return j.notVerifiedCount
        ? `after the review, with ${j.notVerifiedCount} thing(s) the gate can’t prove for you to rule on`
        : "after the review";
    }
    return s.sub;
  }

  review() {
    const who = vendorName(this.w.vendor) || "Another model";
    const n = this.w.j.blockingFindings;
    return n ? `${who} re-checks the ${n} blocking finding(s) from the last review.`
      : `${who} reads it and reports what it finds.`;
  }
}

/* Rewritten only when it changed, so a poll does not rebuild what is under the pointer. */
const written = new WeakMap();

/* A waiting project's card, built once per panel and then refilled in place like the rest of the
   page, so a "Why it is waiting" someone opened stays open through every poll. */
class Card {
  constructor(panel) {
    this.panel = panel;
    if (!panel.querySelector(".wcard")) this.build(panel.querySelector(".move"));
  }

  build(box) {
    const top = document.createElement("div");
    top.className = "wcard";
    top.innerHTML = `<h3 class="wtitle">${CLOCK}<span></span></h3><p class="wsay"></p><p class="wtodo"></p>`;
    box.prepend(top);
    const why = document.createElement("details");
    why.className = "wwhy";
    why.innerHTML = "<summary>Why it is waiting</summary><ul></ul>";
    box.append(why);
    const list = document.createElement("section");
    list.className = "wsteps";
    list.innerHTML = "<h3>What’s left, in order</h3><ol></ol>";
    this.panel.querySelector(":scope > .steps").after(list);
  }

  show(wait, queue) {
    const words = new Words(wait, queue);
    const todo = words.todo();
    this.put(".wtitle span", esc(words.title()));
    this.put(".wsay", words.say());
    this.put(".wtodo", esc(todo));
    this.panel.querySelector(".wtodo").hidden = !todo;
    this.put(".wwhy ul", words.why().map(t => `<li>${esc(t)}</li>`).join("")
      + words.raw().map(t => `<li class="wraw">${esc(t)}</li>`).join(""));
    this.put(".wsteps ol", new StepList(wait).html());
  }

  put(selector, html) {
    const el = this.panel.querySelector(selector);
    if (written.get(el) !== html) {
      el.innerHTML = html;
      written.set(el, html);
    }
  }
}

/* Puts the card on each waiting project's panel, and takes it off once the project stops waiting. */
class WaitingStatus {
  constructor() {
    this.jobs = null;
    this.closed = null;
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    document.addEventListener("forge:jobs", e => { this.jobs = e.detail; this.render(); });
    document.addEventListener("forge:quota", e => { this.closed = e.detail; this.render(); });
  }

  /* Waits for both: a time said before the vendors are known could be the wrong one. */
  render() {
    if (!this.jobs || !this.closed) return;
    const now = new Date();
    const queue = {on: $("autoRun").checked, canTurnOn: !$("autoRun").disabled};
    this.jobs.forEach(j => this.place(j, now, queue));
  }

  /* The class goes on only once the card is filled, so a failure here leaves the page's own panel. */
  place(j, now, queue) {
    const panel = document.getElementById("detail-" + j.id);
    if (!panel) return;
    let shown = false;
    if (kindOf(groupOf(j).key) === "waiting") {
      try {
        new Card(panel).show(new ForgeWait(j, this.closed, now), queue);
        shown = true;
      } catch (e) {
        console.error("extras/status.js", j.id, e);
      }
    }
    panel.classList.toggle("wst", shown);
  }
}

new WaitingStatus();

})();
