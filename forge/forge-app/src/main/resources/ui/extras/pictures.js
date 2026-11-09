/*
 * Picture batches on the dashboard: a card for each, above the projects.
 *
 * A batch is a list of pictures an agent outside Forge draws (pictures.PictureBatches). Forge checks
 * each picture the agent saves and keeps its review sheet; this card says where the batch stands
 * and offers the three things a person does with it: review the sheets (/pictures), copy the one
 * message that sets the agent drawing what is left, and place the kept pictures in the project
 * they are for. A placing that failed is said here, with its log, until one works.
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it. It reads /api/pictures
 * (ui.pictures.PictureRoutes) every ten seconds while the page is in view, uses none of the page's
 * helpers, and keeps its card right above the vendors' banners, putting it back there whenever the
 * page or another extra has moved what is around it. With no batches it shows nothing.
 */
(() => {

const EVERY = 10000;

const CSS = `
  .pxbox{display:flex;flex-direction:column;gap:14px;margin:0 0 28px}
  .pxbox[hidden]{display:none}
  .pxcard{display:flex;flex-direction:column;gap:10px;padding:16px 18px;background:var(--panel);
    border:1px solid var(--line);border-radius:14px}
  .pxhead{display:flex;flex-wrap:wrap;align-items:baseline;gap:4px 14px}
  .pxhead h2{margin:0;font-size:16px;font-weight:700;line-height:1.3;letter-spacing:0;text-transform:none;
    color:var(--fg)}
  .pxsay{margin:0;font-size:14px;line-height:1.45;color:var(--soft)}
  .pxcounts{display:flex;flex-wrap:wrap;gap:6px;margin:0;padding:0;list-style:none}
  .pxcounts li{font-size:12.5px;padding:2px 9px;border:1px solid var(--line);border-radius:999px;color:var(--muted)}
  .pxcounts b{color:var(--fg);font-weight:650}
  .pxacts{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
  .pxacts .pxnote{font-size:13px;color:var(--muted)}
  .pxfile{margin:0;font-size:12.5px;color:var(--muted);overflow-wrap:anywhere}
  .pxfail{margin:0;font-size:13.5px;line-height:1.45;color:var(--fail)}
`;

const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g,
  c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/* The states a job goes through, as the card counts them. Drawing again counts as to draw. */
const COUNTS = [
  ["toDraw", "to draw"], ["DRAWN", "being checked"], ["CHECKED", "to review"],
  ["KEPT", "kept"], ["PLACED", "placed"], ["WAITING", "waiting for earlier pictures"],
];

class Pictures {

  constructor() {
    this.box = null;
    this.batches = [];
    this.drawn = "";
    this.said = {};
    this.timer = 0;
  }

  start() {
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    document.addEventListener("forge:jobs", () => this.safely(() => this.place()));
    document.addEventListener("visibilitychange", () => { if (!document.hidden) this.poll(); });
    this.poll();
  }

  safely(work) {
    try { work(); } catch (e) { console.error("pictures.js", e); }
  }

  async poll() {
    clearTimeout(this.timer);
    try {
      const res = await fetch("/api/pictures");
      if (res.ok) {
        this.batches = await res.json();
        this.safely(() => this.render());
      }
    } catch (e) {
      // The editor is restarting, or gone; the next look will say.
    }
    if (!document.hidden) this.timer = setTimeout(() => this.poll(), EVERY);
  }

  /* Right above the vendors' banners, wherever the other extras have put their own boxes. */
  place() {
    const bar = document.getElementById("quotaBar");
    if (this.box && bar && this.box.nextElementSibling !== bar) bar.before(this.box);
  }

  render() {
    if (!this.box) {
      this.box = document.createElement("div");
      this.box.className = "pxbox";
      this.box.addEventListener("click", e => this.safely(() => this.clicked(e)));
    }
    this.box.hidden = !this.batches.length;
    this.place();
    const html = this.batches.map(b => this.card(b)).join("");
    // Drawn again only when something changed, so a button's answer stays until it is stale.
    if (html !== this.drawn) {
      this.box.innerHTML = html;
      this.drawn = html;
    }
  }

  card(b) {
    const head = `<div class="pxhead"><h2>Pictures: ${esc(b.title)}</h2>`
      + `<p class="pxsay">${esc([b.target ? `For ${b.target}` : "", b.agent ? `drawn by ${b.agent}` : ""]
          .filter(Boolean).join(", "))}</p></div>`;
    if (b.problem) {
      return `<section class="pxcard" data-id="${esc(b.id)}">${head}`
        + `<p class="pxfail">This batch cannot be read: ${esc(b.problem)}</p></section>`;
    }
    const c = {...b.counts, toDraw: (b.counts.TO_DRAW || 0) + (b.counts.REDRAW || 0)};
    const counts = COUNTS.filter(([key]) => c[key]).map(([key, words]) => `<li><b>${c[key]}</b> ${words}</li>`).join("");
    const placing = b.jobs.filter(j => j.placing).length;
    const acts = [];
    if (c.CHECKED) {
      acts.push(`<a class="btn primary" href="/pictures?id=${encodeURIComponent(b.id)}">`
        + `Review ${plural(c.CHECKED, "picture", "pictures")}</a>`);
    }
    if (c.toDraw) acts.push(`<button data-act="copy">Copy ${esc(b.agent)}'s prompt</button>`);
    if (b.placeable) acts.push(`<button data-act="place">Place ${plural(b.placeable, "kept picture", "kept pictures")}</button>`);
    if (placing) acts.push(`<span class="pxnote">Placing ${placing}…</span>`);
    const said = this.said[b.id];
    if (said && said.until > Date.now()) acts.push(`<span class="pxnote">${esc(said.text)}</span>`);
    const failed = b.jobs.filter(j => j.placeFailed).map(j => {
      const run = j.placeFailed;
      const last = run.lines.length ? run.lines[run.lines.length - 1] : `it stopped with ${run.exit}`;
      return `<p class="pxfail">Placing ${esc(j.title)} failed: ${esc(last)}`
        + (run.log ? ` <a href="${esc(run.log)}" target="_blank">Log</a>` : "") + `</p>`;
    }).join("");
    const done = b.jobs.length && c.PLACED === b.jobs.length;
    return `<section class="pxcard" data-id="${esc(b.id)}">${head}`
      + (done ? `<p class="pxsay">All ${b.jobs.length} are placed.</p>` : `<ul class="pxcounts">${counts}</ul>`)
      + (acts.length ? `<div class="pxacts">${acts.join("")}</div>` : "")
      + (c.toDraw ? `<p class="pxfile">${esc(b.agent)}'s prompt is also in <code>${esc(b.promptFile)}</code>, `
          + `and the same prompt carries on after a usage limit.</p>` : "")
      + failed + `</section>`;
  }

  clicked(e) {
    const button = e.target.closest("button[data-act]");
    if (!button) return;
    const batch = this.batches.find(b => b.id === button.closest(".pxcard").dataset.id);
    if (!batch) return;
    if (button.dataset.act === "copy") this.copy(batch, button);
    if (button.dataset.act === "place") this.placeKept(batch, button);
  }

  async copy(batch, button) {
    try {
      await navigator.clipboard.writeText(batch.message);
      button.textContent = "Copied";
    } catch (e) {
      button.textContent = "Could not copy: open the file";
    }
  }

  async placeKept(batch, button) {
    button.disabled = true;
    const r = await fetch("/api/pictures/place", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({id: batch.id}),
    }).then(res => res.json()).catch(e => ({error: String(e)}));
    // Said for half a minute: by then the card's own counts say how the placings went.
    this.said[batch.id] = {text: r.error ? "Could not ask: " + r.error : r.result, until: Date.now() + 30000};
    this.poll();
  }
}

new Pictures().start();

})();
