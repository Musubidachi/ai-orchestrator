/*
 * The board as the mockup draws it: two sections, the next 24 hours and the projects, and
 * the projects as a table of cards that says what each one waits for now.
 *
 * The board said everything in small grey type: a rank, a stage and a column of open items on every
 * line, a heading over every group, the queue switch beside Renumber, and the spend, usage and league
 * under the table, with the open project's panel taking a third of the screen whether or not anyone
 * had asked to see it. Here each line has five columns, Family, Versions, Now, Worked and Tokens, and
 * keeps its rank in its small print; the tiles stand for the headings; the queue switch is in the
 * heading of the next 24 hours, which say what it will start; the spend, usage and league are folded
 * under the table; and the panel stays closed until a project's own line is chosen, then pushes the
 * table aside until its × or Esc closes it. The project chosen stays marked either way.
 *
 * An extra (see ui/CLAUDE.md): its tag in index.html is all that attaches it, and taking the tag out
 * brings the board back as it was. It hears forge:jobs, forge:quota and forge:pauses, takes a waiting
 * project's time from wait.js and a paused one's words from the line pause.js adds, and calls the
 * page's helpers only when one fires. Of the page's own it moves the queue switch, the line of counts,
 * the note, the toolbar, and the spend, usage and league into boxes of its own, each by id and none
 * rebuilt, and sets classes and data attributes. A phone keeps its layout but for the two headings.
 * All else is its own: the headings, the table's header, each line's Now, the panel's ×, the
 * captions drawn inside the next 24 hours' bars, and the card that stands for those 24 hours when
 * nextday.js has nothing to draw.
 */
(() => {

const THEAD = '<span>Family</span><span>Versions</span><span>Now</span>'
  + '<span class="num rswork">Worked</span><span class="num rstok">Tokens</span>';

const HINT = "Open a family to see its versions. Choose a project or a version to see its panel "
  + "beside the table; Esc closes it.";

/* Where the folded spend, usage and league remember being open, in this browser only. */
const FOOT = "forge.restyle.foot";

/* The bars' marks are drawn this wide, a vendor's and yours. */
const MARK = 24;
const YOUR_MARK = 10;

const CSS = `
  .rsthead, .rsnow, .rshint, .rsclose, .rscap{display:none}

  /* Both sections: a card, and a heading. */
  #jobsView.rs .wnd24, .rsday{display:flex;flex-direction:column;gap:14px;margin:0 0 28px;
    padding:16px 18px;background:var(--panel);border:1px solid var(--line);border-radius:14px}
  #jobsView.rs .wnhead, .rsdhead{display:flex;flex-wrap:wrap;align-items:center;gap:6px 14px;margin:0}
  #jobsView.rs .wnhead h2, .rsdhead h2, .rshead h2{margin:0;font-size:16px;font-weight:700;
    line-height:1.3;letter-spacing:0;text-transform:none;color:var(--fg);white-space:nowrap}
  #jobsView.rs .wnsay, .rssay{flex:1 1 18rem;min-width:0;margin:0;font-size:14px;line-height:1.45;
    color:var(--soft)}
  #jobsView.rs .wnhead .auto, .rsdhead .auto{margin-left:auto;font-size:13.5px}
  .rshead{display:flex;flex-wrap:wrap;align-items:center;gap:8px 14px;margin:0 0 16px}
  .rshead #jobsSummary{margin:0}
  .rshead #jobsNote{flex:1 1 16rem;min-width:0;margin:0;font-size:13.5px;line-height:1.45;color:var(--muted)}
  .rshead #jobsNote:empty{display:none}
  .rshead .jobsbar{flex-wrap:nowrap;gap:10px;margin:0 0 0 auto}
  .rshead #stopForge{border-color:#5a2a2a;background:var(--red-bg);color:var(--red-fg)}
  /* While the strip hides the banners its box would still keep their margin between the sections. */
  #jobsView.rs #quotaBar.wnd{margin:0}
  #jobsView.rs #quotaBar.wnd > .quota{margin:0 0 .9rem}

  /* The spend, usage and league, folded under the table, and gone while none of them has anything. */
  .rsfoot{margin:22px 0 0;font-size:13px;color:var(--muted)}
  .rsfoot > summary{width:max-content;padding:4px 0;cursor:pointer;color:var(--soft)}
  .rsfoot #spend, .rsfoot #usageBar{margin:12px 0 0}
  .rsfoot #league{margin:12px 0 0}
  .rsfoot:not(:has(#usageBar:not(:empty), #spend:not([hidden]), #league:not([hidden]))){display:none}

  @media (min-width:1200px){
    /* The next 24 hours: taller bars, with what each lane says drawn inside its bar. */
    #jobsView.rs .wnlanes{display:flex;flex-direction:column;gap:8px}
    #jobsView.rs .wnrow{grid-template-columns:104px minmax(0,1fr);gap:12px}
    #jobsView.rs .wnrow > div{position:relative;min-width:0}
    #jobsView.rs .wnname{font-size:13px;font-weight:600;line-height:34px}
    #jobsView.rs .wnticks .wnname{font-size:11.5px;line-height:16px;color:var(--fg)}
    #jobsView.rs .wnaxis{font-size:11.5px}
    #jobsView.rs .wnbar{height:34px;border-radius:7px;background:#152a1d}
    #jobsView.rs .wnyou .wnbar{background:var(--field)}
    #jobsView.rs .wnmark{top:5px;bottom:5px;width:${MARK}px;border-radius:4px}
    #jobsView.rs .wnyou .wnmark{top:7px;bottom:7px;width:${YOUR_MARK}px;border-radius:3px}
    #jobsView.rs .rscap{display:block;position:absolute;top:0;height:34px;line-height:34px;
      font-size:12.5px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--fg)}
    #jobsView.rs .rscap.rsbefore{color:var(--green-fg)}
    #jobsView.rs .rscap.rsbefore.rsheld{color:var(--amber-fg)}
    #jobsView.rs .wnyou .rscap{color:var(--amber-fg);font-weight:600}
    #jobsView.rs .wnyou .rscap.rsquiet{color:var(--muted);font-weight:400}
    #jobsView.rs .rscapped > .wncap{position:absolute;width:1px;height:1px;margin:-1px;padding:0;
      overflow:hidden;clip-path:inset(50%);white-space:nowrap}

    #jobsView.rs .rshead .jobsbar button{min-height:40px;padding:0 14px;border-radius:8px;font-size:13.5px}

    #jobsView.rs #tiles{gap:8px;margin:0 0 18px}
    #jobsView.rs .tile{min-width:96px;min-height:0;padding:8px 14px;border:1px solid var(--line);
      border-radius:12px;background:var(--panel)}
    #jobsView.rs .tile .n{font-size:20px;line-height:1.25}
    #jobsView.rs .tile .l{font-size:12px;color:var(--muted)}
    #jobsView.rs .tile[aria-pressed=true]{border-color:var(--selline);background:var(--sel)}
    #jobsView.rs .tile[aria-pressed=true] .l{color:var(--soft)}
    #jobsView.rs .tile[disabled]{opacity:1}
    #jobsView.rs .tile[disabled] .n{color:var(--muted)}

    /* The table: one card per line, five columns, and no panel until a line is chosen. The columns
       after the first are sized from the table's width, not the line's, so that a family's versions,
       drawn narrower under it, keep its columns. 258px is the lines' edges, gaps and fixed columns. */
    #jobs.rs{gap:6px;container-type:inline-size}
    #jobs.rs:not(.rspeek){padding-right:0}
    #jobs.rs:not(.rspeek) > .job > .detail{display:none}
    #jobs.rs > .ledgerhead, #jobs.rs > .grouphead{display:none}
    #jobs.rs > .rsthead{order:-2;display:grid;align-items:end;column-gap:16px;padding:4px 17px;
      font-size:11.5px;font-weight:600;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}
    #jobs.rs > .rsthead, #jobs.rs .lrow{grid-template-columns:minmax(0,1fr)
      calc((100cqw - 258px) * .46) calc((100cqw - 258px) * .28) 84px 76px}
    #jobs.rs .lrow{column-gap:16px;grid-template-rows:auto;row-gap:0;min-height:0;padding:14px 16px;
      border:1px solid var(--line);border-radius:12px;background:var(--panel);font-size:13.5px}
    #jobs.rs .lrow:hover{background:var(--card);border-color:var(--line)}
    #jobs.rs .lrow.fmrow[aria-expanded=true]{background:var(--card)}
    #jobs.rs .lrow[aria-current=true]{background:var(--sel);border-color:var(--selline);box-shadow:none}
    #jobs.rs .lrow > *{grid-row:1}
    #jobs.rs .lrow > .rank, #jobs.rs .lrow > .flags, #jobs.rs .fmrow > .stage, #jobs.rs .lrow .pzline{display:none}
    #jobs.rs .lrow > .main{grid-column:1}
    #jobs.rs .lrow > .stage, #jobs.rs .lrow > .fmchain{grid-column:2}
    #jobs.rs .lrow > .rsnow, #jobs.rs .lrow > .fmnow{grid-column:3}
    #jobs.rs .lrow > .worked, #jobs.rs > .rsthead > .rswork{grid-column:4}
    #jobs.rs .lrow > .tokens, #jobs.rs > .rsthead > .rstok{grid-column:5}
    #jobs.rs .lrow .ttl{font-size:14px}
    #jobs.rs .lrow .sub{margin-top:2px;font-size:12.5px}
    #jobs.rs .lrow .sub[data-rsrank]::before{content:"rank " attr(data-rsrank) " · "}
    #jobs.rs .lrow > .num{font-size:13.5px;color:var(--fg)}
    #jobs.rs > .job > .lrow.fmin, #jobs.rs > .foldline.fmin{margin-left:24px;width:calc(100% - 24px)}
    #jobs.rs > .job > .lrow.fmin .main{padding-left:0}
    #jobs.rs > .foldline{padding:10px 16px;border:1px dashed #4a4a47;border-radius:12px;
      background:transparent;font-size:12.5px}
    #jobs.rs > .foldline:hover{background:var(--card)}

    /* Now: what it waits for, in the colour of what it is, and when. */
    #jobs.rs .lrow > .rsnow, #jobs.rs .lrow > .fmnow{display:flex;flex-direction:column;gap:1px;
      min-width:0;font-size:12.5px;line-height:1.4}
    #jobs.rs .rsnow > span, #jobs.rs .fmnow > span{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
    #jobs.rs .rsnow > span:empty{display:none}
    #jobs.rs .rsnow > .rswhat, #jobs.rs .fmnow > .fmwhat{font-size:13.5px;font-weight:600;color:var(--soft)}
    #jobs.rs .rsnow > .rswhen, #jobs.rs .fmnow > .fmwhen{color:var(--muted)}
    #jobs.rs .lrow > [data-tone=pass] > :first-child{color:var(--green-fg)}
    #jobs.rs .lrow > [data-tone=wait] > :first-child{color:var(--amber-fg)}
    #jobs.rs .lrow > [data-tone=accent] > :first-child{color:var(--blue-fg)}

    /* The words on a line: an edge in their own colour. */
    #jobs.rs .lrow .pill, #jobs.rs .fmchip, #jobs.rs .lrow .pzpill{padding:3px 9px;border:1px solid transparent;
      font-size:12px;font-weight:600;line-height:1.35}
    #jobs.rs .lrow .t-pass{border-color:#1f4a30}
    #jobs.rs .lrow .t-wait{border-color:var(--hold-line)}
    #jobs.rs .lrow .t-todo{border-color:var(--line)}
    #jobs.rs .lrow .t-accent, #jobs.rs .lrow .pzpill{border-color:#2c3a5c}
    #jobs.rs .lrow .t-fail{border-color:#5a2c2c}
    #jobs.rs .fmchip.fmmore{border-style:dashed;border-color:#4a4a47}
    #jobs.rs .fmchain{gap:6px 0}
    #jobs.rs .fmlink{width:12px}

    /* With the panel open the table is narrower: Tokens goes, as it does on a smaller screen. */
    #jobs.rs.rspeek > .rsthead, #jobs.rs.rspeek .lrow{grid-template-columns:minmax(0,1fr)
      calc((100cqw - 158px) * .425) calc((100cqw - 158px) * .3) 76px}
    #jobs.rs.rspeek .lrow > .tokens, #jobs.rs.rspeek > .rsthead > .rstok{display:none}
    #jobs.rs .rsclose{display:inline-flex;align-items:center;justify-content:center;font-size:1.2rem}

    #jobsView.rs .rshint{display:block;margin:14px 0 0;font-size:13px;color:var(--muted)}
  }
  @media (min-width:1200px) and (max-width:1399px){
    #jobs.rs > .rsthead, #jobs.rs .lrow{grid-template-columns:minmax(0,1fr)
      calc((100cqw - 158px) * .425) calc((100cqw - 158px) * .3) 76px}
    #jobs.rs .lrow > .tokens, #jobs.rs > .rsthead > .rstok{display:none}
    /* and with the panel open, Worked too. */
    #jobs.rs.rspeek > .rsthead, #jobs.rs.rspeek .lrow{grid-template-columns:minmax(0,1fr)
      calc((100cqw - 66px) * .36) calc((100cqw - 66px) * .3)}
    #jobs.rs.rspeek .lrow > .worked, #jobs.rs.rspeek > .rsthead > .rswork{display:none}
  }
`;

const make = (tag, cls, html) => {
  const el = document.createElement(tag);
  el.className = cls;
  if (html) el.innerHTML = html;
  return el;
};

/* Text set only when it changed, so a poll does not touch what is under the pointer. */
const say = (el, text) => {
  if (el.textContent !== text) el.textContent = text;
};

const shown = el => !!el && el.getClientRects().length > 0;

/* What pause.js shows for a project you paused, or null; always null when pause.js is not attached. */
const pauseOf = j => (window.ForgePause ? window.ForgePause.of(j) : null);

/* A key pressed while typing belongs to the field. */
const typing = el => !!el && (el.isContentEditable || el.tagName === "TEXTAREA" || el.tagName === "SELECT"
  || (el.tagName === "INPUT" && !/^(checkbox|radio|button|submit|reset|range|color|file)$/.test(el.type)));

/* Section 1 when nextday.js draws nothing, every vendor being open and nothing waiting for a time:
   a quiet card that says so, where the strip would be. Either way the queue switch is in the
   section's heading, as what it does is start what the next 24 hours show. */
class DayCard {
  constructor() {
    this.box = null;
  }

  place(closed) {
    const auto = document.getElementById("autoRun").closest("label");
    const strip = document.querySelector("#jobsView .wnd24");
    let head;
    if (strip && !strip.hidden) {
      if (this.box) this.box.hidden = true;
      head = strip.querySelector(".wnhead");
    } else {
      const box = this.build();
      say(box.querySelector(".rssay"), this.said(closed));
      box.hidden = false;
      head = box.querySelector(".rsdhead");
    }
    if (auto.parentElement !== head) head.append(auto);
  }

  build() {
    if (this.box) return this.box;
    this.box = make("section", "rsday",
      '<div class="rsdhead"><h2 id="rsday">Next 24 hours</h2><p class="rssay"></p></div>');
    this.box.setAttribute("aria-labelledby", "rsday");
    document.getElementById("quotaBar").before(this.box);
    return this.box;
  }

  said(closed) {
    const now = Date.now();
    const shut = Object.values(closed).filter(c => c.until > now).map(c => vendorName(c.vendor));
    if (!shut.length) return "Every vendor is open, and nothing waits for a time.";
    return `${shut.join(", ")} ${shut.length === 1 ? "is" : "are"} closed for now.`;
  }
}

/* What a lane of the next 24 hours says, drawn inside its bar on a wide screen: before its first
   mark what comes before it, and after the mark the rest, or all of it on the side with room. In
   your lane what you are to do goes after its mark, as the mark is when. nextday.js's own line
   stays in the lane for anything reading the page, hidden by a class only once these are drawn,
   and is redrawn by it whenever what it says changes. */
class Captions {
  draw() {
    document.querySelectorAll("#jobsView .wnlanes .wnrow").forEach(lane => {
      try {
        this.lane(lane);
      } catch (e) {
        console.error("extras/restyle.js", e);
      }
    });
  }

  lane(lane) {
    const box = lane.querySelector(":scope > div");
    const cap = box && box.querySelector(":scope > .wncap");
    if (!cap) return;
    const mark = box.querySelector(".wnmark");
    const at = mark ? parseFloat(mark.style.left) : NaN;
    const you = lane.classList.contains("wnyou");
    const [before, after] = this.split(cap.textContent, at, you);
    const one = this.span(box, "rsbefore", before);
    const two = this.span(box, "rsafter", after);
    one.style.left = "14px";
    one.style.right = Number.isNaN(at) ? "1%" : `calc(${100 - at}% + 8px)`;
    two.style.left = Number.isNaN(at) ? "14px" : `calc(${at}% + ${(you ? YOUR_MARK : MARK) + 8}px)`;
    two.style.right = "1%";
    one.classList.toggle("rsheld", cap.classList.contains("wnheld"));
    one.classList.toggle("rsquiet", Number.isNaN(at));
    box.classList.add("rscapped");
  }

  /* [before the mark, after it]. With the mark well inside the bar, a vendor's lane is split at its
     first " · " and yours where what you are to do begins; otherwise all of it goes to the side
     with room, which in your lane is after the mark while that leaves a third of the bar. */
  split(text, at, you) {
    if (Number.isNaN(at)) return [text, ""];
    const cut = you ? text.indexOf("From ") : text.indexOf(" · ");
    if (cut > 0 && at >= 15 && at < 85) {
      return [text.slice(0, cut).replace(/ · $/, ""), text.slice(you ? cut : cut + 3)];
    }
    return at < (you ? 67 : 50) ? ["", text] : [text, ""];
  }

  span(box, cls, text) {
    let el = box.querySelector(":scope > ." + cls);
    if (!el) {
      el = make("span", "rscap " + cls);
      el.setAttribute("aria-hidden", "true");
      box.append(el);
    }
    say(el, text);
    el.title = text;
    el.hidden = !text;
    return el;
  }
}

/* What a project's own line says under Now, in the words its family's row uses for its first
   version (families.js), so a version says what its family would of it. */
class NowLine {
  constructor(row, j, closed, now) {
    this.row = row;
    this.j = j;
    this.wait = window.ForgeWait ? new ForgeWait(j, closed, now) : null;
  }

  write() {
    let cell = this.row.querySelector(":scope > .rsnow");
    if (!cell) {
      cell = make("span", "rsnow", '<span class="rswhat"></span><span class="rswhen"></span>');
      this.row.append(cell);
    }
    const [what, when, tone] = this.lines();
    say(cell.firstChild, what);
    say(cell.lastChild, when || flags(this.j).map(f => f.text).join(" · "));
    cell.dataset.tone = tone;
  }

  /* [what, when, the colour of what]. */
  lines() {
    const j = this.j;
    if (j.running) return [j.activity || capital(j.phaseLabel), "", "pass"];
    if (notHere(j)) return [j.shelvedBranch ? "Bring it back to work on it" : "", "", "soft"];
    if (pauseOf(j)) return this.paused();
    if (needsYou(j)) return ["Your decision", "", "wait"];
    if (j.phase === "ACCEPTED") return ["", "", "pass"];
    if (j.queuePosition > 0 && !j.blockedReason) {
      return [j.queuePosition === 1 ? "Next up" : `Number ${j.queuePosition} in the queue`, "", "soft"];
    }
    if (waitsItsTurn(j)) {
      return [this.wait && this.wait.earlier ? `After ${this.wait.earlier} is accepted` : capital(j.blockedReason), "", "soft"];
    }
    if (j.phase === "SUSPENDED" || j.blockedReason) return [this.waitsFor(), this.when(), "wait"];
    return ["", "", "soft"];
  }

  /* pause.js's words from the line it adds under the title, which the table hides. */
  paused() {
    const text = sel => {
      const el = this.row.querySelector(".pzline " + sel);
      return el && !el.hidden ? el.textContent : "";
    };
    return [[text(".pzfor"), text(".pzdue")].filter(Boolean).join(" · ") || "Paused by you", text(".pzwhy"), "accent"];
  }

  waitsFor() {
    const kind = this.wait && this.wait.kind;
    return kind === "review" ? "Waiting for cross-review"
      : kind === "quota" ? "Waiting for quota" : capital(this.j.blockedReason) || "Waiting";
  }

  /* The time that counts, the one the project's card and the next 24 hours give. */
  when() {
    const w = this.wait;
    if (!w || !w.until) return "";
    return w.vendorsTime() ? `${vendorName(w.vendor)} back ${whenNear(w.until)}` : `Can resume ${whenNear(w.until)}`;
  }
}

/* A family row's Now in the colour of what it says. families.js writes the words; the colour is its
   first version's word's, but amber for anything left to your decision. */
const TONES = {pass: "pass", wait: "wait", accent: "accent", fail: "wait", todo: "soft"};

function toneFamily(row) {
  const now = row.querySelector(":scope > .fmnow");
  const what = now && now.querySelector(".fmwhat");
  if (!what) return;
  const pill = row.querySelector(":scope > .stage > .pill");
  const word = pill && /\bt-(\w+)/.exec(pill.className);
  now.dataset.tone = /decision/.test(what.textContent) ? "wait" : TONES[word && word[1]] || "soft";
}

/* The open project's panel: closed until a project's own line is chosen, then beside the table and
   pushing it aside, until its × or Esc closes it. The page still chooses and marks the project, as
   do the family rows; this only says whether the panel shows, by a class on the table. */
class Panel {
  constructor(box) {
    this.box = box;
    box.addEventListener("click", e => this.clicked(e));
    document.addEventListener("keydown", e => this.key(e));
  }

  clicked(e) {
    try {
      const row = e.target.closest(".lrow");
      if (row && row.parentElement.classList.contains("job") && this.box.contains(row)) this.open();
    } catch (err) {
      console.error("extras/restyle.js", err);
    }
  }

  open() {
    if (this.box.classList.contains("rspeek")) return;
    this.box.classList.add("rspeek");
    fit();
  }

  close() {
    this.box.classList.remove("rspeek");
    fit();
    const row = document.getElementById("row-" + selected);
    const back = shown(row) ? row : this.box.querySelector(":scope > .fmrow[aria-current=true]");
    if (back) back.focus({preventScroll: true});
  }

  key(e) {
    try {
      if (e.key !== "Escape" || e.defaultPrevented || !this.box.classList.contains("rspeek")) return;
      if (narrow.matches || !shown(this.box) || document.querySelector("dialog[open]") || typing(e.target)) return;
      e.preventDefault();
      this.close();
    } catch (err) {
      console.error("extras/restyle.js", err);
    }
  }

  /* An × at the end of each panel's tools, made once for each. */
  buttons() {
    this.box.querySelectorAll(":scope > .job > .detail .dtools").forEach(tools => {
      if (tools.querySelector(".rsclose")) return;
      const b = make("button", "rsclose", "&times;");
      b.type = "button";
      b.title = "Close the panel (Esc)";
      b.setAttribute("aria-label", "Close the panel");
      b.onclick = () => this.close();
      tools.append(b);
    });
  }
}

/* Section 2's heading, the table's header and the hint under it, and the folded spend, usage and
   league: made once, from the page's own elements, which keep their ids. */
class Frame {
  build(view, box) {
    const head = make("div", "rshead", "<h2>Projects</h2>");
    document.getElementById("tiles").before(head);
    head.append(document.getElementById("jobsSummary"), document.getElementById("jobsNote"),
      view.querySelector(".jobsbar"));
    const top = make("div", "rsthead", THEAD);
    top.setAttribute("aria-hidden", "true");
    box.prepend(top);
    const hint = make("p", "rshint");
    hint.textContent = HINT;
    box.after(hint);
    hint.after(this.foot());
  }

  foot() {
    const foot = make("details", "rsfoot", '<summary>Spend, usage and the league</summary><div class="rsbody"></div>');
    foot.querySelector(".rsbody").append(...["spend", "usageBar", "league"].map(id => document.getElementById(id)));
    try {
      foot.open = localStorage.getItem(FOOT) === "open";
    } catch (e) { /* a private window */ }
    foot.addEventListener("toggle", () => {
      try {
        localStorage.setItem(FOOT, foot.open ? "open" : "");
      } catch (e) { /* a private window */ }
    });
    return foot;
  }
}

/* Lays the board out once, then keeps each line's Now, the family rows' colours, the panels' ×,
   the queue switch's place and the bars' captions up to date on every poll. */
class Restyle {
  constructor() {
    const view = document.getElementById("jobsView");
    const box = document.getElementById("jobs");
    if (!view || !box) return;
    const style = document.createElement("style");
    style.textContent = CSS;
    document.head.append(style);
    this.jobs = null;
    this.closed = null;
    this.day = new DayCard();
    this.captions = new Captions();
    this.safely(() => {
      new Frame().build(view, box);
      // Last, so that a board this could not lay out keeps the page's own look.
      view.classList.add("rs");
      box.classList.add("rs");
    });
    this.panel = new Panel(box);
    document.addEventListener("forge:jobs", e => {
      this.jobs = e.detail;
      this.safely(() => this.render());
    });
    document.addEventListener("forge:quota", e => {
      this.closed = e.detail || {};
      this.safely(() => this.render());
    });
    document.addEventListener("forge:pauses", () => this.safely(() => this.render()));
  }

  safely(work) {
    try {
      work();
    } catch (e) {
      console.error("extras/restyle.js", e);
    }
  }

  render() {
    if (!this.jobs) return;
    const now = new Date();
    this.jobs.forEach(j => this.safely(() => this.line(j, now)));
    document.querySelectorAll("#jobs > .fmrow").forEach(row => this.safely(() => toneFamily(row)));
    this.panel.buttons();
    if (!this.closed) return;
    this.day.place(this.closed);
    this.captions.draw();
  }

  line(j, now) {
    const row = document.getElementById("row-" + j.id);
    if (!row) return;
    const sub = row.querySelector(".sub");
    const rank = row.querySelector(".rank").textContent;
    if (rank) sub.dataset.rsrank = rank; else delete sub.dataset.rsrank;
    new NowLine(row, j, this.closed || {}, now).write();
  }
}

new Restyle();

})();
