/*
 * A project's panel as the mockup draws it.
 *
 * The panel set its five steps side by side, as boxes with a word under each in small grey type, and
 * said the phase twice more, as a pill beside the title and a line under the steps. Its counts ran as
 * many to a row as fitted, in the order they were added; the builder's handoff sat in a grey box led
 * by a count of steps; and its buttons sat in boxes under headings in capitals. Here the steps are
 * listed top to bottom, as a waiting project's already were (status.js); the counts are three to a
 * row, worked, sessions and tokens first; the handoff is a quote, said to be the builder's; the
 * buttons are one row; and the card's label is its title. Beside the table the pill and the line are
 * left out: the card and the line's Now column say the phase in words.
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it, and taking the tag out
 * brings the panel back as it was. It hears forge:jobs and forge:gate, and calls the page's steps()
 * and helpers only when one fires. Of the page's own it reorders the counts, names the builder's
 * vendor as the table does, and sets two classes and a data attribute; the list is its own. A panel
 * gets its class only once its list is filled, so a failure leaves the page's own panel.
 */
(() => {

/* What a step of the page's steps() is called once it is done, and while it is still ahead, as the
   waiting card's list calls them. */
const DONE = {Build: "Built", Gate: "Gate passed", Security: "Security checked", Review: "Reviewed"};
const AHEAD = {Security: "Security check", Review: "Cross-review", Accepted: "Your decision"};

/* The counts the mockup shows, in its order. The rest follow in the page's. */
const FIRST = ["worked", "sessions", "tokens", "commits", "repair rounds", "on disk"];

const icon = (path, weight, size) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" `
  + `fill="none" stroke="currentColor" stroke-width="${weight}" stroke-linecap="round" `
  + `stroke-linejoin="round" aria-hidden="true">${path}</svg>`;
const MARKS = {
  pass: icon('<path d="M5 12l5 5L20 7"/>', 3, 13),
  wait: icon('<path d="M12 7v5l3 2"/>', 3, 12),
  fail: icon('<path d="M6 6l12 12M18 6L6 18"/>', 3, 12),
};
/* A step held up by something to look at rather than by time: findings, or a security call. */
const LOOK = icon('<path d="M12 6v7M12 17.5v.5"/>', 3, 12);

const CSS = `
  .rpsteps{display:none}
  .detail.rp:not(.wst) > .rpsteps{display:block}
  .detail.rp > .steps{display:none}
  .detail.rp > .phaseline{margin-top:0}

  /* The steps, top to bottom, drawn as the waiting card draws its own. */
  .rpsteps h3{margin:0 0 .55rem;font-size:.72rem;font-weight:700;letter-spacing:.08em;
    text-transform:uppercase;color:var(--muted)}
  .rpsteps ol{list-style:none;margin:0;padding:0}
  .rpsteps li{display:grid;grid-template-columns:1.6rem minmax(0,1fr);gap:.7rem}
  .rprail{display:flex;flex-direction:column;align-items:center}
  .rpmark{display:inline-flex;align-items:center;justify-content:center;box-sizing:border-box;
    width:1.5rem;height:1.5rem;border-radius:50%;font-size:.7rem;font-weight:700}
  .rpline{flex:1 1 0;width:2px;min-height:.75rem;background:var(--line)}
  .rpsteps li:last-child .rpline{display:none}
  .rptxt{display:flex;flex-direction:column;min-width:0;padding:.1rem 0 .9rem}
  .rpsteps li:last-child .rptxt{padding-bottom:0}
  .rpname{font-weight:600}
  .rpsub{font-size:.82rem;color:var(--muted)}
  .rpsteps li[aria-current] .rpsub{color:var(--soft)}
  .rp-pass .rpmark{background:var(--green-bg);color:var(--green-fg)}
  .rp-pass .rpline{background:#1f4a30}
  .rp-accent .rpmark{border:2px solid var(--accent);background:var(--blue-bg);color:var(--blue-fg)}
  .rp-accent .rpname{color:var(--blue-fg)}
  .rp-wait .rpmark{border:2px solid var(--wait);background:var(--hold-bg);color:var(--amber-fg)}
  .rp-wait .rpname{color:var(--amber-fg)}
  .rp-fail .rpmark{background:var(--red-bg);color:var(--red-fg)}
  .rp-fail .rpname{color:var(--red-fg)}
  .rp-todo .rpmark{border:2px solid #4a4a47;background:var(--card);color:var(--soft)}

  /* The card's label is its title, and what it says is the sentence under it. */
  .detail.rp .move-label{font-size:16px;font-weight:700;line-height:1.3;letter-spacing:0;
    text-transform:none;color:var(--fg)}
  .detail.rp .move-label.accent{color:var(--blue-fg)}
  .detail.rp .move-label.pass{color:var(--green-fg)}
  .detail.rp .move-head{font-size:15px;font-weight:400;line-height:1.45}

  /* The counts, three to a row, ruled between. Each count rules its own right and bottom edge, and
     the box clips the rules along its outside, so a short last row is still ruled where it has one. */
  .detail.rp > .stats{grid-template-columns:repeat(3,minmax(0,1fr));gap:0;background:none;
    border-radius:12px}
  .detail.rp > .stats > .stat{padding:12px 16px;background:none;box-shadow:1px 1px 0 var(--line)}
  .detail.rp > .stats > .stat b{font-size:17px;font-weight:700}
  .detail.rp > .stats > .stat span{font-size:12.5px}

  /* The builder's handoff, as a quote said to be its. */
  .detail.rp > .plan{display:flex;flex-direction:column;gap:6px;padding:14px 16px;background:none;
    border:1px solid var(--line);border-radius:12px}
  .detail.rp > .plan::before{content:attr(data-rpby);order:-1;font-size:12.5px;color:var(--muted)}
  .detail.rp > .plan > .plan-sum{order:-2;margin:0;font-size:14.5px;line-height:1.5;color:var(--fg)}
  .detail.rp > .plan > .plan-sum::before{content:"“"}
  .detail.rp > .plan > .plan-sum::after{content:"”"}
  .detail.rp > .plan > .plan-head, .detail.rp > .plan > .plan-next{margin:0;font-size:12.5px;
    font-weight:400;color:var(--muted)}
  .detail.rp > .plan > :empty, .detail.rp > .plan > .rpnone{display:none}

  /* The buttons, one row. The page hides a group with nothing to press; with none left, the row goes. */
  .detail.rp > .groups{display:flex;flex-wrap:wrap;gap:8px}
  .detail.rp > .groups > .grp{display:contents}
  .detail.rp > .groups .grp-h{display:none}
  .detail.rp > .groups:not(:has(> .grp:not([hidden]))){display:none}

  @media (min-width:1200px){
    .detail.rp > .dhead > .tag, .detail.rp > .phaseline{display:none}
  }`;

/* Rewritten only when it changed, so a poll does not rebuild what is under the pointer. */
const written = new WeakMap();

const put = (el, html) => {
  if (written.get(el) === html) return;
  el.innerHTML = html;
  written.set(el, html);
};

const make = (tag, cls, html) => {
  const el = document.createElement(tag);
  el.className = cls;
  el.innerHTML = html;
  return el;
};

/* The page's five steps, top to bottom. The one it is at is the first that is yours or has failed,
   or else the first not done. */
class StepList {
  constructor(j) {
    this.j = j;
  }

  html() {
    const all = steps(this.j);
    let at = all.findIndex(s => s.tone === "accent" || s.tone === "fail");
    if (at < 0) at = all.findIndex(s => s.tone !== "pass");
    return all.map((s, i) => this.item(s, i, i === at)).join("");
  }

  /* A step that waits but is not the one it is at waits on findings or a security call, not a clock. */
  item(s, i, here) {
    const tone = ["pass", "accent", "wait", "fail"].includes(s.tone) ? s.tone : "todo";
    const mark = tone === "wait" && !here ? LOOK : MARKS[tone] || i + 1;
    const name = tone === "pass" ? this.doneName(s) : AHEAD[s.name] || s.name;
    return `<li class="rp-${tone}"${here ? ' aria-current="step"' : ""}>`
      + `<span class="rprail"><span class="rpmark">${mark}</span><span class="rpline"></span></span>`
      + `<span class="rptxt"><span class="rpname">${esc(name)}</span>`
      + `<span class="rpsub">${esc(this.sub(s))}</span></span></li>`;
  }

  /* The page passes the security step of a project that was never checked, and "Security checked"
     would then say something that did not happen. */
  doneName(s) {
    const unchecked = s.name === "Security" && (this.j.securityState || "none") === "none";
    return unchecked ? s.name : DONE[s.name] || s.name;
  }

  sub(s) {
    if (s.name === "Gate" && s.tone === "pass") return this.gate() || s.sub;
    if (s.name === "Accepted" && s.tone !== "pass") {
      const n = this.j.notVerifiedCount;
      const rule = n ? `, with ${n} thing(s) the gate can’t prove for you to rule on` : "";
      return (s.tone === "accent" ? "your call" : "after the review") + rule;
    }
    return s.sub;
  }

  /* What the last gate ran and what that took, from gate.js once the open panel has fetched it, said
     as the waiting card says it. */
  gate() {
    const g = window.ForgeGate && window.ForgeGate.of(this.j);
    if (!g || !g.ran || !Array.isArray(g.commands) || !g.commands.length) return "";
    const n = g.commands.length;
    const ran = g.commands.filter(c => c.state === "passed" || c.state === "failed").length;
    const which = ran === n ? (n === 1 ? "its one command" : `all ${n} commands`) : `${ran} of ${n} commands`;
    const took = g.seconds > 0 ? duration(g.seconds) : "under 1s";
    return `the last gate${g.quick ? " was quick and" : ""} ran ${which} in ${took}`;
  }
}

/* What it does to one project's panel. The page refills the panel on every poll, so this runs on
   every poll too. */
class PanelLook {
  constructor(panel, j) {
    this.panel = panel;
    this.j = j;
  }

  fill() {
    this.list();
    this.counts();
    this.handoff();
    this.vendor();
  }

  list() {
    let box = this.panel.querySelector(":scope > .rpsteps");
    if (!box) {
      box = make("section", "rpsteps", "<h3></h3><ol></ol>");
      this.panel.querySelector(":scope > .steps").after(box);
    }
    const left = steps(this.j).some(s => s.tone !== "pass");
    put(box.querySelector("h3"), left ? "What’s left, in order" : "Every step is done");
    put(box.querySelector("ol"), new StepList(this.j).html());
  }

  /* Moved only when the order differs. The one gate.js hides goes last, where it leaves no gap. */
  counts() {
    const box = this.panel.querySelector(":scope > .stats");
    const all = [...box.children];
    const rank = el => {
      if (el.classList.contains("gthide")) return FIRST.length + 1;
      const label = el.querySelector("span");
      const i = FIRST.indexOf(label ? label.textContent : "");
      return i < 0 ? FIRST.length : i;
    };
    const sorted = all.slice().sort((a, b) => rank(a) - rank(b));
    if (sorted.some((el, i) => el !== all[i])) sorted.forEach(el => box.append(el));
  }

  /* Said to be the builder's. Its count of steps goes once none are left: the list says so. */
  handoff() {
    const plan = this.panel.querySelector(":scope > .plan");
    const by = this.j.sessions ? `The builder’s handoff, session ${this.j.sessions}` : "The builder’s handoff";
    if (plan.dataset.rpby !== by) plan.dataset.rpby = by;
    plan.querySelector(".plan-head").classList.toggle("rpnone", !this.j.remainingSteps);
  }

  /* "CLAUDE" becomes "Claude", as the line in the table says it. */
  vendor() {
    const v = this.j.assignedTo;
    const id = this.panel.querySelector(".dhead .id");
    if (!v || !id) return;
    const said = id.textContent.replace(` · ${v}`, ` · ${vendorName(v)}`);
    if (said !== id.textContent) id.textContent = said;
  }
}

/* Restyles every project's panel on each poll, and again when the open one's gate arrives. */
class PanelStyle {
  constructor() {
    this.jobs = null;
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    document.addEventListener("forge:jobs", e => { this.jobs = e.detail; this.render(); });
    document.addEventListener("forge:gate", () => this.render());
  }

  render() {
    if (this.jobs) this.jobs.forEach(j => this.place(j));
  }

  /* The class goes on only once the panel is done, so a failure here leaves the page's own. */
  place(j) {
    const panel = document.getElementById("detail-" + j.id);
    if (!panel) return;
    let done = false;
    try {
      new PanelLook(panel, j).fill();
      done = true;
    } catch (e) {
      console.error("extras/restyle-panel.js", j.id, e);
    }
    panel.classList.toggle("rp", done);
  }
}

new PanelStyle();

})();
