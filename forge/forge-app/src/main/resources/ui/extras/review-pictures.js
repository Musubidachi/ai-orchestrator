/*
 * The spec's pictures, on the quick review's card that asks about them.
 *
 * A spec built to pictures asks its person whether the screens came out like them
 * (pipeline.PictureLook), and the report puts each picture beside its screenshot under Pictures
 * (report.outward.PicturesSection). The quick review dealt the question without the pictures, so
 * answering it meant opening the report and finding them. This puts the pairs on the card, between
 * the question and the note, read from the report itself so they are the very ones it shows.
 *
 * An extra (see ui/CLAUDE.md): its tag in review.html is all that attaches it. It watches the stage
 * for the card whose headline names "the spec's pictures", the words PictureLook asks in, and adds
 * to that card only. Nothing else on the page changes: Fix it and Fine as it is still answer the
 * card, and the note still goes with the answer.
 */
(() => {

const WORDS = /the spec's pictures/i;
const stage = document.getElementById("stage");
/* The report's pairs, fetched once for the page: {url, rows}. */
let report = null;

const esc = s => String(s == null ? "" : s).replace(/[&<>"']/g,
  c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));

/* Where the report is: the page's own link to it, which it sets before dealing the first card. */
function reportUrl() {
  const link = document.getElementById("full");
  const href = link && link.getAttribute("href");
  const id = new URLSearchParams(location.search).get("id") || "";
  return new URL(href || `/runs/${encodeURIComponent(id)}/report.html`, location.href);
}

/* Each state with its picture and screenshot, as the report's Pictures section lists them. */
function rowsIn(html, base) {
  const doc = new DOMParser().parseFromString(html, "text/html");
  const heading = doc.getElementById("pictures");
  const section = heading && heading.nextElementSibling;
  if (!section) return [];
  return [...section.querySelectorAll("h3")].map(h3 => {
    const pair = h3.nextElementSibling;
    const figures = pair && pair.classList.contains("polish-pair") ? [...pair.querySelectorAll("figure")] : [];
    return {
      state: h3.textContent.trim(),
      figures: figures.map(figure => {
        const img = figure.querySelector("img");
        const missing = figure.querySelector(".polish-missing");
        const caption = figure.querySelector("figcaption");
        return {
          label: caption ? caption.textContent.split(" · ")[0].trim() : "",
          src: img ? new URL(img.getAttribute("src"), base).href : "",
          missing: missing ? missing.textContent.trim() : "",
        };
      }),
    };
  }).filter(row => row.figures.length);
}

function load() {
  if (!report) {
    const url = reportUrl();
    report = fetch(url)
      .then(res => res.ok ? res.text() : Promise.reject(new Error("HTTP " + res.status)))
      .then(html => ({url, rows: rowsIn(html, url)}))
      .catch(() => ({url, rows: []}));
  }
  return report;
}

function figureHtml(figure) {
  const body = figure.src
    ? `<a href="${esc(figure.src)}" target="_blank" rel="noopener"><img src="${esc(figure.src)}" `
      + `alt="${esc(figure.label)}" loading="lazy"></a>`
    : `<div class="rp-missing">${esc(figure.missing)}</div>`;
  return `<figure>${body}<figcaption>${esc(figure.label)}</figcaption></figure>`;
}

function fill(block, {url, rows}) {
  if (!rows.length) {
    block.innerHTML = `<p class="rp-note">The pictures could not be read from `
      + `<a href="${esc(url.pathname)}#pictures" target="_blank">the report</a>.</p>`;
    return;
  }
  block.innerHTML = `<p class="rp-note">Each picture the spec was built to, beside the screenshot of the `
    + `same state. Open one to see it full size.</p>`
    + rows.map(row => `<div class="rp-row"><div class="rp-state">${esc(row.state)}</div>`
      + `<div class="rp-pair">${row.figures.map(figureHtml).join("")}</div></div>`).join("");
}

function decorate() {
  const card = stage.querySelector("section.card.must");
  if (!card || card.querySelector(".rp")) return;
  const headline = card.querySelector("h1");
  if (!headline || !WORDS.test(headline.textContent)) return;
  const block = document.createElement("div");
  block.className = "rp";
  block.innerHTML = `<p class="rp-note">Loading the pictures…</p>`;
  // Between the question and the note: look first, then say.
  let note = card.querySelector("#note");
  while (note && note.parentElement !== card) note = note.parentElement;
  card.insertBefore(block, note || card.querySelector(".choices"));
  load().then(found => { if (block.isConnected) fill(block, found); });
}

const CSS = `
.rp{margin:.2rem 0 1rem}
.rp-note{font-size:.85rem;color:var(--muted);margin:.2rem 0 .6rem}
.rp-row{border-top:1px solid var(--line);padding:.7rem 0 .3rem}
.rp-state{font-weight:650;font-size:.92rem;margin-bottom:.45rem}
.rp-pair{display:grid;grid-template-columns:1fr 1fr;gap:.7rem;align-items:start}
.rp-pair figure{margin:0;min-width:0}
.rp-pair a{display:flex;justify-content:center;background:var(--bg);border:1px solid var(--line);
  border-radius:.45rem;overflow:hidden}
.rp-pair img{display:block;max-width:100%;max-height:24rem;width:auto;height:auto}
.rp-pair figcaption{font-size:.75rem;color:var(--muted);margin-top:.3rem}
.rp-missing{display:flex;align-items:center;justify-content:center;min-height:8rem;padding:1rem;
  text-align:center;border:1px dashed var(--line);border-radius:.45rem;font-size:.82rem;color:var(--muted)}
`;

const style = document.createElement("style");
style.textContent = CSS;
document.head.appendChild(style);
new MutationObserver(decorate).observe(stage, {childList: true});

})();
