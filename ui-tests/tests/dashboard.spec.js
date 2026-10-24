const { test, expect } = require("@playwright/test");

/**
 * The interactions that have actually broken, and could not have been caught in Java.
 *
 * Every UI regression in this project so far was found by a person looking at the screen: a button
 * overlapping the summary line, reorder arrows that changed the data but never moved the cards, a
 * report link that served the dashboard. The third was a routing bug and is covered by
 * UiServerTest; the other two live in the DOM and the stylesheet, where only a browser can see
 * them. This suite is deliberately small — five interactions worth guarding, not a mirror of the
 * page.
 */

const card = (page, id) => page.locator(`#job-${id}`);

/*
 * Each project is one element holding its line in the table and its details, and only the chosen
 * project's details are on screen. So a test that presses something on a project picks its line
 * first, the way a person would.
 */
const open = async (page, id) => {
  await page.locator(`#row-${id}`).click();
  await expect(card(page, id).locator(".detail")).toBeVisible();
  return card(page, id);
};

/* What a group heading says, in the order the table shows them. */
const headings = (page) => page.locator("#jobs .grouphead").evaluateAll(heads => heads
  .map(h => ({ order: +getComputedStyle(h).order, text: h.querySelector(".gt").textContent }))
  .sort((a, b) => a.order - b.order)
  .map(h => h.text));

test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#job-001")).toBeVisible();
});

test("cards render in rank order", async ({ page }) => {
  const ids = await page.locator("#jobs .job").evaluateAll(
    cards => cards.map(c => c.id));

  expect(ids).toEqual(["job-001", "job-002", "job-003"]);
});

test("the table groups projects by what they wait on, with the chosen one beside it", async ({ page }) => {
  // 003 is finished and waiting for a decision; 001 and 002 have never started, and could.
  expect(await headings(page)).toEqual(["Needs you", "Queued"]);

  // With nothing chosen yet, the project that needs you is the one shown.
  await expect(card(page, "003").locator(".detail")).toBeVisible();
  await expect(card(page, "001").locator(".detail")).toBeHidden();
  await expect(page.locator("#row-003")).toHaveAttribute("aria-current", "true");
  // What it is asking for is said once, at the top, rather than left to the row of buttons.
  await expect(card(page, "003").locator(".move-label")).toHaveText("Your move");

  // Beside the table, not under it: the details start to the right of every line.
  const line = await page.locator("#row-001").boundingBox();
  const details = await card(page, "003").locator(".detail").boundingBox();
  expect(details.x).toBeGreaterThanOrEqual(line.x + line.width - 1);

  // Choosing another line swaps what is shown, and only that.
  await page.locator("#row-001").click();
  await expect(card(page, "001").locator(".detail")).toBeVisible();
  await expect(card(page, "003").locator(".detail")).toBeHidden();
  await expect(page.locator("#row-001")).toHaveAttribute("aria-current", "true");
  await expect(card(page, "001").locator(".move-head")).toHaveText("Next up.");

  // The phone's filters belong to the phone.
  await expect(page.locator("#tiles")).toBeHidden();
});

test.describe("on a phone", () => {
  test.use({ viewport: { width: 412, height: 915 } });

  test("the projects are one ranked list, and a line opens its details under itself", async ({ page }) => {
    // No table headings and no panel beside it: the filters stand in for the grouping.
    await expect(page.locator(".ledgerhead")).toBeHidden();
    await expect(page.locator("#jobs .grouphead").first()).toBeHidden();
    await expect(page.locator("#tiles")).toBeVisible();

    // Ranked, top to bottom, in the order the server ranks them.
    const ids = await page.locator("#jobs .job").evaluateAll(cards => cards.map(c => c.dataset.id));
    const tops = [];
    for (const id of ids) tops.push((await page.locator(`#row-${id}`).boundingBox()).y);
    expect([...tops].sort((a, b) => a - b)).toEqual(tops);

    // Nothing is open until a line is tapped.
    for (const id of ids) await expect(card(page, id).locator(".detail")).toBeHidden();

    // Tapping one opens its details between it and the next line.
    const middle = ids[1];
    await page.locator(`#row-${middle}`).click();
    const details = card(page, middle).locator(".detail");
    await expect(details).toBeVisible();
    await expect(page.locator(`#row-${middle}`)).toHaveAttribute("aria-expanded", "true");
    const mine = await page.locator(`#row-${middle}`).boundingBox();
    const box = await details.boundingBox();
    const next = await page.locator(`#row-${ids[2]}`).boundingBox();
    expect(box.y).toBeGreaterThanOrEqual(mine.y + mine.height - 1);
    expect(next.y).toBeGreaterThanOrEqual(box.y + box.height - 1);

    // Tapping it again closes it.
    await page.locator(`#row-${middle}`).click();
    await expect(details).toBeHidden();
  });

  test("the filters narrow the list to what needs you", async ({ page }) => {
    const you = page.locator('#tiles [data-key="you"]');
    await expect(you.locator(".n")).toHaveText("1");
    // Nothing is running in the fixture, so that filter has nothing to show and cannot be chosen.
    await expect(page.locator('#tiles [data-key="working"]')).toBeDisabled();

    await you.click();
    await expect(you).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#row-003")).toBeVisible();
    await expect(page.locator("#row-001")).toBeHidden();

    await page.locator('#tiles [data-key="all"]').click();
    await expect(page.locator("#row-001")).toBeVisible();
  });
});

test("the down arrow moves the card, not just the number", async ({ page }) => {
  // The regression this exists for: ranks changed on disk while the list stayed put, so the
  // arrows looked broken even though they worked.
  await (await open(page, "001")).locator(".down").click();

  await expect(page.locator("#jobs .job").first()).toHaveAttribute("id", "job-002");
  await expect(card(page, "001").locator(".id")).toContainText("rank 2");
});

test("the header controls do not overlap the summary", async ({ page }) => {
  const summary = await page.locator("#jobsSummary").boundingBox();
  const bar = await page.locator(".jobsbar").boundingBox();

  // Negative margins once pulled the bar up over the text whenever the note beside it was empty.
  expect(bar.y).toBeGreaterThanOrEqual(summary.y + summary.height - 1);
});

test("the report opens inside the card and closes again", async ({ page }) => {
  const gamma = await open(page, "003");
  await expect(gamma.locator(".repbox")).toBeHidden();

  await gamma.locator(".rep").click();
  await expect(gamma.locator(".repbox")).toBeVisible();
  await expect(gamma.locator(".repframe")
    .contentFrame().locator("h1")).toHaveText("Gamma Project");

  await gamma.locator(".rep").click();
  await expect(gamma.locator(".repbox")).toBeHidden();
});

test("an open report survives the poll that redraws every card", async ({ page }) => {
  const gamma = await open(page, "003");
  await gamma.locator(".rep").click();
  await expect(gamma.locator(".repbox")).toBeVisible();

  // The cards are updated in place every four seconds; an open panel must not be closed by it.
  await page.waitForTimeout(5000);

  await expect(gamma.locator(".repbox")).toBeVisible();
  await expect(gamma.locator(".rep")).toHaveText("Hide Report");
});

test("only a finished, cross-reviewed project offers a decision", async ({ page }) => {
  // 003 is DONE with a real review on disk; 001 is still building.
  await expect((await open(page, "003")).locator(".decide")).toBeVisible();
  await expect(card(page, "001").locator(".decide")).toBeHidden();

  await expect((await open(page, "001")).locator(".add")).toBeVisible();
  await expect(card(page, "001").locator(".decide")).toBeHidden();
});

test("the card separates time worked from time elapsed", async ({ page }) => {
  // The bug: "working for" was wall clock since the project was created, and nothing froze it
  // while the project sat suspended on quota. A job blocked at nine in the evening reported
  // itself as having worked all night, which is a believable number and a false one.
  const stats = (await open(page, "003")).locator(".stats");

  // 003 in the fixture: fifteen minutes of work inside an hour of wall clock.
  await expect(stats).toContainText("worked");
  await expect(stats).toContainText("15m 0s");
  await expect(stats).toContainText("total time");
  await expect(stats).toContainText("1h 0m");

  // An unstarted project shows how long it has been sitting, honestly labelled.
  await expect((await open(page, "001")).locator(".stats")).toContainText("idle for");

  // The label itself is the regression, so it is asserted gone from the whole page.
  await expect(page.locator("#jobs")).not.toContainText("working for");
});

test("a version on GitHub says which branch and commit, and Publish waits for publishing to be on", async ({ page }) => {
  await page.goto("/");
  const gamma = await open(page, "003");

  const line = gamma.locator(".githubline");
  await expect(line).toBeVisible();
  await expect(line).toContainText("On GitHub: main at 1234567");
  await expect(line.locator("a")).toHaveAttribute("href", "https://github.com/someone/gamma-project/tree/main");
  // This root has no publishing.json, so nothing here can push anywhere, and nothing offers to.
  await expect(gamma.locator(".publish")).toBeHidden();
});

test("the whole board's token spend is shown, and what no model is named for is said so", async ({ page }) => {
  // The question the dashboard could not answer before: what has this machine spent in total.
  // Per vendor and per window it could (see #usageBar below), but a window resets and remembers
  // only the last few, so its lifetime figure came to less than one project's own total.
  const spend = page.locator("#spend");
  await expect(spend).toContainText("31.4M tokens");
  await expect(spend).toContainText("across 1 project, all models");

  // Biggest share first, each with the vendor that was asked for it: a model name on its own is
  // unique only by luck.
  await expect(spend).toContainText("sonnet 20.0M (CLAUDE, 1 project)");
  await expect(spend).toContainText("gpt-5.6-sol 1.4M (CODEX, 1 project)");

  // The rest is named rather than shared out over the two models above. Most of the history on a
  // real board predates the split, and attributing it would make the largest figure here a guess.
  await expect(spend).toContainText("10.0M before the split was recorded");
});

test("what each vendor has spent is shown, with no invented remaining figure", async ({ page }) => {
  // Recorded on every turn since usage was measured, and displayed nowhere until now.
  const usage = page.locator("#usageBar");
  await expect(usage).toContainText("CLAUDE");
  await expect(usage).toContainText("3 turn(s)");
  await expect(usage).toContainText("14.9M tokens");
  await expect(usage).toContainText("$4.13 at API prices");
  // The windows seen so far varied ninefold, so the spread is the honest thing to show.
  await expect(usage).toContainText("between 15.0M and 135M");
  await expect(usage).not.toContainText("remaining");
  // A vendor that has done nothing yet has nothing to say.
  await expect(usage).not.toContainText("CODEX");
});

test("the card shows tokens, the last gate, and a suite that has slowed down", async ({ page }) => {
  const job = await open(page, "003");
  await expect(job.locator(".stats")).toContainText("31.4M");
  await expect(job.locator(".stats")).toContainText("last gate");
  await expect(job.locator(".slow")).toHaveText("Slower than usual: npm test took 60s, usually 20s");
  // Nothing to warn about on a project that has never gated.
  await expect(card(page, "001").locator(".slow")).toBeHidden();
});

test("a finished project counts as needing you, not as accepted", async ({ page }) => {
  // 003 is DONE in the fixture: the gate passed, nobody has approved it. The summary once
  // called that "accepted", which reported the unread queue back as finished work.
  const summary = page.locator("#jobsSummary");

  await expect(summary).toContainText("0 accepted");
  await expect(summary).toContainText("1 needing you");
});

test("the card says what is outstanding before sending you to decide", async ({ page }) => {
  // There is deliberately no Accept button here. The only route to accepting runs through the
  // list of what was not proven, which lives in the report.
  const gamma = await open(page, "003");

  await expect(gamma.locator(".caveat"))
    .toContainText("2 thing(s) a passing gate does not prove");
  await expect(gamma.locator(".approve")).toHaveCount(0);

  // Drawn as a button, not as a bare link. This is the action the card exists to offer once a
  // project is finished, and among a row of buttons it was the faintest thing there.
  const decide = gamma.locator(".decide");
  await expect(decide).toHaveAttribute("href", "/runs/003/report.html#decide");
  await expect(decide).toHaveClass(/\bbtn\b/);

  // The appearance is the request, so it is asserted rather than assumed: a filled surface and
  // no underline. A missing a.btn rule would leave the class on and change nothing on screen.
  const style = await decide.evaluate(el => {
    const s = getComputedStyle(el);
    return { background: s.backgroundColor, decoration: s.textDecorationLine };
  });
  expect(style.background).not.toBe("rgba(0, 0, 0, 0)");
  expect(style.decoration).toBe("none");

  // Still an anchor, so it opens a tab without going near a popup blocker.
  const [report] = await Promise.all([
    page.waitForEvent("popup"),
    decide.click(),
  ]);
  await expect(report).toHaveURL(/\/runs\/003\/report\.html#decide$/);
  await report.close();
});

test("the report carries a decision form, one row per outstanding item", async ({ page }) => {
  await page.goto("/runs/003/report.html");

  // By kind, not by class. Review rows, feature rows and the kept notes all wear .decide-item so
  // they look alike, and selecting on appearance is how the submit button broke: it read a comment
  // box off a row that has none and died before making the request.
  const items = page.locator("#decide [data-kind=review]");
  await expect(items).toHaveCount(3);
  // The fixture's security check found a committed key: it is ruled on first, because it is the
  // one that can hurt someone other than the reader.
  await expect(items.first()).toContainText("AWS access key id committed");
  const claim = items.nth(1);
  await expect(claim).toContainText("the draw branch has never been rendered");

  // Each row offers both verdicts and somewhere to say why.
  await expect(claim.locator("input[value=ok]")).toBeChecked();
  await expect(claim.locator("input[value=changes]")).toBeVisible();
  await expect(claim.locator(".decide-comment")).toBeVisible();

  // Kept notes on claims the agent has dropped are shown, and are not rows of the form: they
  // carry the same class, so anything reading the page rather than the form picks them up too.
  const past = page.locator(".decide-past");
  await expect(past).toHaveCount(1);
  await expect(past).toContainText("worth remembering anyway");
  await expect(past.locator(".decide-comment")).toHaveCount(0);
  await expect(page.locator("#decide .decide-past")).toHaveCount(0);
});

test("the clips play in the page, with no controls on them", async ({ page }) => {
  // The reason any of this exists: six projects produced one recording between them while the
  // house rules asked nicely for them. A clip is the only thing on this page that answers "is it
  // any good" rather than "did it build", so it is near the top and it is just there to watch.
  await page.goto("/runs/003/report.html");

  const gallery = page.locator("h2#features ~ .feature-item");
  await expect(gallery).toHaveCount(2);

  const filmed = gallery.first();
  await expect(filmed).toContainText("Report export");
  await expect(filmed).toContainText("export a report");
  await expect(filmed.locator("img, video")).toHaveCount(1);
  await expect(filmed).toContainText("recorded by the last gate run");

  // Not ruled on, so nothing was ever filmed for it, and the page says which of those it is.
  await expect(gallery.nth(1).locator("img, video")).toHaveCount(0);
  await expect(gallery.nth(1)).toContainText("Not ruled on yet");

  // Watching and deciding are different acts. No radio button up here.
  await expect(gallery.locator("input[type=radio]")).toHaveCount(0);
});

test("the quick review deals the same review one decision at a time", async ({ page }) => {
  // Stubbed and read back: a real submission here would send the project to an agent.
  let posted = null;
  await page.route("**/api/jobs/decide", async (route) => {
    posted = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "sent back; started" }) });
  });

  // Offered on the card beside the form it is another way of filling in.
  await expect(card(page, "003").locator(".quick")).toHaveAttribute("href", "/review?id=003");
  await page.goto("/review?id=003");
  await expect(page.locator("#title")).toHaveText("Gamma Project");

  // First, alone, with nothing chosen: the security finding. It cannot be waved through in a batch.
  const must = page.locator(".card.must");
  await expect(must.locator("h1")).toHaveText("AWS access key id committed");
  await expect(must.locator(".tag")).toHaveText("security");
  await expect(must.locator(".choices .on")).toHaveCount(0);
  await expect(page.locator("#where")).toHaveText("1 of 3");
  await expect(page.locator("#prev")).toBeDisabled();
  await must.locator("#note").fill("revoke it first");
  await must.locator(".fix").click();

  // Then the feature nobody has ruled on. The one already approved is not asked about again.
  const feature = page.locator(".card.feature");
  await expect(feature.locator("h1")).toHaveText("Monthly summary");
  await expect(feature.locator(".noclip")).toBeVisible();
  // "Change it" with nothing said is not an instruction, so it does not move on.
  await feature.locator(".change").click();
  await expect(feature.locator("#note")).toHaveClass(/wanted/);
  await expect(feature).toBeVisible();
  await feature.locator(".keep").click();

  // Then everything that starts as fine, together, with one of them sent back.
  const skim = page.locator(".card.skim");
  await expect(skim.locator("li")).toHaveCount(2);
  await expect(skim.locator(".toggle[aria-pressed=true]")).toHaveCount(0);
  const keyboard = skim.locator("li", { hasText: "keyboard controls are untested" });
  await keyboard.locator(".toggle").click();
  await keyboard.locator(".rownote").fill("tab through it once");
  await skim.locator("#skimDone").click();

  // The whole sitting, read back before anything is sent.
  const final = page.locator(".card.final");
  await expect(final.locator("#submit")).toHaveText("Send back 2 things");
  await expect(final.locator(".summary")).toContainText("AWS access key id committed");
  await expect(final.locator(".summary")).toContainText("tab through it once");
  await expect(final.locator(".summary")).toContainText("Keep: Monthly summary");
  await expect(final).toContainText("Keeping 1 feature with no recording sends the project back");
  expect(posted).toBeNull();

  // Going back keeps what was answered.
  await page.locator("#prev").click();
  await expect(skim.locator(".toggle[aria-pressed=true]")).toHaveCount(1);
  await skim.locator("#skimDone").click();

  await final.locator("#submit").click();

  await expect.poll(() => posted).not.toBeNull();
  expect(posted.id).toBe("003");
  // The same shape the form posts: every item, each with a verdict, under the form's own ids.
  expect(posted.decisions.map(d => d.verdict)).toEqual(["changes", "ok", "changes"]);
  expect(posted.decisions.map(d => d.itemId.split("-")[0])).toEqual(["security", "unproven", "unproven"]);
  expect(posted.decisions[0].comment).toBe("revoke it first");
  expect(posted.decisions[2].text).toBe("keyboard controls are untested");
  expect(posted.features).toEqual([
    { itemId: "feature-monthly-summary", text: "Monthly summary", verdict: "approved", comment: "" }]);
  await expect(page.locator(".card.done")).toContainText("sent back; started");
  await expect(page.locator("#prev")).toBeDisabled();
});

test("a card that must be decided cannot be skipped, by mouse or by key", async ({ page }) => {
  // A dealt hand, so the page is tested against every part of a card at once: a plain line, its
  // stakes, the attempts already spent, why the last fix fell short, and a ruling that came back.
  const blocking = {
    id: "blocking-1a2b3c4d", kind: "blocking", source: "Blocking review finding",
    text: "src/Price.java — Intermediate division rounding violates the store-only truncation contract",
    headline: "A price ending in half a cent is rounded up instead of down",
    detail: "src/Price.java — Intermediate division rounding…\n\ndivide() rounds HALF_UP before storing.",
    stakes: "wrong result", cameBack: "", attempts: 2,
    stillPresent: "the test added only covers whole cents", notes: [],
  };
  const cameBack = {
    id: "unproven-5e6f7a8b", kind: "unproven", source: "Not proven by the gate",
    text: "the export has never been opened in the real application",
    headline: "the export has never been opened in the real application",
    detail: "", stakes: "", cameBack: "Came back — you ruled it fine as it is on 27 Sep.",
    attempts: 0, stillPresent: "", notes: ["fine for a first version"],
  };
  await page.route("**/api/review?id=777", (route) => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({
      id: "777", title: "Tills", phase: "DONE", notice: "", mustDecide: [blocking, cameBack],
      features: [], skim: [], featuresAlreadyRuled: 0, ideasWaiting: 2, report: "/runs/777/report.html" }) }));
  let posted = null;
  await page.route("**/api/jobs/decide", async (route) => {
    posted = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "accepted" }) });
  });
  await page.goto("/review?id=777");

  const must = page.locator(".card.must");
  await expect(must.locator("h1")).toHaveText("A price ending in half a cent is rounded up instead of down");
  await expect(must.locator(".tag")).toHaveText("wrong result");
  await expect(must.locator(".meta")).toContainText("sent back 2 times already");
  await expect(must.locator(".still")).toContainText("only covers whole cents");
  // The wording a fixer needs is a click away rather than in the way.
  await expect(must.locator("details pre")).toBeHidden();
  await must.locator("details summary").click();
  await expect(must.locator("details pre")).toContainText("rounds HALF_UP");

  // No Skip, no Continue, and the keys that mean nothing on this card do nothing. The only buttons
  // are the two answers and Add image, which puts a picture in the note and moves nothing on.
  await expect(must.locator("button:not(.attach-button)")).toHaveCount(2);
  await expect(must.locator(".choices button")).toHaveText(["← Fix it", "Fine as it is →"]);
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(must.locator("h1")).toHaveText("A price ending in half a cent is rounded up instead of down");

  // Right arrow: fine as it is. Then the one that came back, which says so and shows the old note.
  await page.keyboard.press("ArrowRight");
  await expect(must.locator("h1")).toHaveText("the export has never been opened in the real application");
  await expect(must.locator(".back")).toContainText("Came back");
  await expect(must.locator(".choices .on")).toHaveCount(0);
  await must.locator("details summary").click();
  await expect(must.locator(".said")).toContainText("fine for a first version");

  // Up goes back, and the answer already given is shown as given.
  await page.keyboard.press("ArrowUp");
  await expect(must.locator(".fine")).toHaveClass(/on/);
  await page.keyboard.press("ArrowRight");
  // Typing a note is not answering the card: an arrow inside the box moves the caret, nothing else.
  await must.locator("#note").fill("still fine");
  await must.locator("#note").press("ArrowLeft");
  await expect(must.locator("h1")).toHaveText("the export has never been opened in the real application");
  await must.locator(".fine").click();

  const final = page.locator(".card.final");
  await expect(final.locator("#submit")).toHaveText("Accept Tills");
  await expect(final).toContainText("2 ideas");
  await final.locator("#submit").click();

  await expect.poll(() => posted).not.toBeNull();
  expect(posted.decisions).toEqual([
    { itemId: "blocking-1a2b3c4d", text: blocking.text, verdict: "ok", comment: "" },
    { itemId: "unproven-5e6f7a8b", text: cameBack.text, verdict: "ok", comment: "still fine" }]);
  await expect(page.locator(".card.done h1")).toHaveText("Tills is accepted");
});

test("the quick review says so when there is no such project", async ({ page }) => {
  await page.goto("/review?id=999");
  await expect(page.locator("#stage")).toContainText("Nothing to review");
  await expect(page.locator("#stage")).toContainText("no project 999");
});

test("Polish is a page: the models are asked for designs, and nothing is built until one is picked", async ({ page }) => {
  // On a finished project's card, as a link. Nothing is asked of a vendor by following it.
  await expect(card(page, "003").locator(".polish")).toHaveAttribute("href", "/polish?id=003");
  await expect(card(page, "001")).toBeVisible();
  await expect(card(page, "001").locator(".polish")).toBeHidden();

  // The board as the vendors would fill it, in three moments. Stubbed: asking for real spends a
  // call to every vendor, and building for real starts an agent session.
  const option = (id, vendor, name, pitch) => ({ id, vendor, name, pitch,
    file: `design/${vendor}/option-${id.split("-")[1]}.html` });
  const boards = {
    asking: { askedAt: "2026-10-02T20:00:00Z", note: "neon", state: "asking", refused: [], chosen: "",
      chosenAt: null, options: [option("CLAUDE-1", "CLAUDE", "Neon arcade", "glowing outlines on black")] },
    ready: { askedAt: "2026-10-02T20:00:00Z", note: "neon", state: "ready", chosen: "", chosenAt: null,
      refused: ["ANTIGRAVITY could not be asked: RATE_LIMITED"],
      options: [option("CLAUDE-1", "CLAUDE", "Neon arcade", "glowing outlines on black"),
                option("CLAUDE-2", "CLAUDE", "Paper cut", "flat shapes with soft shadows"),
                option("CODEX-1", "CODEX", "Blueprint", "thin white lines on blue")] },
  };
  let moment = "before";
  await page.route("**/api/polish?id=003", async (route) => {
    if (moment === "before") return route.continue();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      id: "003", title: "Gamma Project", running: moment === "asking", blocked: "", board: boards[moment] }) });
  });
  let asked = null;
  await page.route("**/api/polish/ask", async (route) => {
    asked = route.request().postDataJSON();
    moment = "asking";
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "asking 3 models for designs" }) });
  });
  let built = null;
  await page.route("**/api/polish/build", async (route) => {
    built = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "started" }) });
  });

  // Before anyone has been asked: the real answer from the editor, with nothing on the board.
  await page.goto("/polish?id=003");
  await expect(page.locator("#title")).toHaveText("Gamma Project");
  await expect(page.locator("#askBtn")).toHaveText("Ask the models for designs");
  await expect(page.locator("#askBtn")).toBeEnabled();
  await expect(page.locator(".option")).toHaveCount(0);
  await expect(page.locator("#blocked")).toBeHidden();

  await page.locator("#note").fill("  neon  ");
  await page.locator("#askBtn").click();
  await expect.poll(() => asked).toEqual({ id: "003", note: "neon" });
  await expect(page.locator("#result")).toHaveText("asking 3 models for designs");

  // While they draw: what has arrived is shown, and nothing can be built or asked for yet.
  await expect(page.locator("#asking")).toContainText("1 design so far");
  await expect(page.locator(".option")).toHaveCount(1);
  await expect(page.locator(".option .build")).toBeDisabled();
  await expect(page.locator("#askBtn")).toBeDisabled();

  // The page keeps looking by itself, and the rest arrive without a reload.
  moment = "ready";
  await expect(page.locator(".option")).toHaveCount(3, { timeout: 10_000 });
  await expect(page.locator("#asking")).toBeHidden();
  await expect(page.locator("#refused")).toContainText("ANTIGRAVITY could not be asked");
  const paper = page.locator(".option", { hasText: "Paper cut" });
  await expect(paper.locator(".by")).toContainText("Drawn by CLAUDE");
  await expect(paper.locator(".pitch")).toHaveText("flat shapes with soft shadows");
  // A page a model wrote is framed with scripts off, from its own folder beside the repository.
  await expect(paper.locator("iframe")).toHaveAttribute("sandbox", "");
  await expect(paper.locator("iframe")).toHaveAttribute("src", "/runs/003/design/CLAUDE/option-2.html");
  await expect(page.locator("#askBtn")).toHaveText("Ask again");

  // Picking one asks first, and saying no builds nothing.
  page.once("dialog", (dialog) => dialog.dismiss());
  await paper.locator(".build").click();
  await page.waitForTimeout(300);
  expect(built).toBeNull();

  let question = "";
  page.once("dialog", (dialog) => { question = dialog.message(); dialog.accept(); });
  await paper.locator(".build").click();

  await expect.poll(() => built).toEqual({ id: "003", option: "CLAUDE-2", note: "neon" });
  expect(question).toContain("Build this design?");
  await expect(page.locator("#result")).toContainText("started");
  await expect(page.locator(".option .build").first()).toBeDisabled();
});

test("a request for designs that was cut off says so, keeps what arrived, and can be asked again", async ({ page }) => {
  // Left saying "asking" by an editor stopped mid-request, the page waited for ever and would not
  // let another request be made. The server now reports it as interrupted, and nothing is running.
  await page.route("**/api/polish?id=003", (route) => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({
      id: "003", title: "Gamma Project", running: false, blocked: "",
      board: { askedAt: "2026-10-02T20:00:00Z", note: "", state: "interrupted", chosen: "", chosenAt: null,
        refused: ["stopped before every model had answered"],
        options: [{ id: "CLAUDE-1", vendor: "CLAUDE", name: "Neon arcade", pitch: "glowing outlines",
                    file: "design/CLAUDE/option-1.html" }] } }) }));

  await page.goto("/polish?id=003");

  await expect(page.locator("#interrupted")).toContainText("stopped before every model had answered");
  await expect(page.locator("#asking")).toBeHidden();
  await expect(page.locator("#askBtn")).toBeEnabled();
  await expect(page.locator(".option")).toHaveCount(1);
  await expect(page.locator(".option .build")).toBeEnabled();
});

test("the polish page says why a project cannot be polished, and what to do instead", async ({ page }) => {
  // 001 has never run. The real answer from the editor, not a stub.
  await page.goto("/polish?id=001");
  await expect(page.locator("#blocked")).toContainText("it has not run yet");
  await expect(page.locator("#askBtn")).toBeDisabled();
  await expect(page.locator("#plainBtn")).toBeDisabled();
});

test("one submission carries the review and the features together", async ({ page }) => {
  // Reported: ruling on features submitted on its own, which reopened the project -- and the
  // review typed in the same sitting was then refused with "a session is already running". Two
  // forms, two buttons, and the first one pressed cost the second. There is now one of each.
  await page.goto("/runs/003/report.html");

  await expect(page.locator("form")).toHaveCount(1);
  await expect(page.locator("button[type=submit]")).toHaveCount(1);

  // The feature controls are inside that form, folded away -- the clips are up the page where
  // they can be watched, and settled rows sit between a reader and what they came to do.
  const fold = page.locator("#decide details.features-fold");
  await expect(fold).toHaveCount(1);
  await expect(fold.locator("[data-kind=feature]")).toHaveCount(2);

  // Open, because one of them has not been answered yet.
  await expect(fold).toHaveAttribute("open", "");

  const unruled = fold.locator("[data-kind=feature]").nth(1);
  await expect(unruled.locator("input[value=approved]")).not.toBeChecked();
  await expect(unruled.locator("input[value=declined]")).not.toBeChecked();

  // Declining costs nothing and starts nothing, which is what makes it safe to assert here:
  // approving something unfilmed reopens the project and spends a session, so that path is
  // tested against stub agents in JobRunnerTest rather than against the real CLIs.
  await unruled.locator("input[value=declined]").check();
  await unruled.locator(".decide-comment").fill("the summary already shows it");

  // Something the agent never proposed, typed in a different case on purpose: the id is derived
  // from the name on the server, so this has to resolve to the feature already filmed rather than
  // commissioning a second recording of the same thing.
  await page.locator(".feature-add .feature-name").fill("report EXPORT");

  // And the review, in the same sitting and the same click.
  await page.locator("[data-kind=review]").first().locator(".decide-comment")
    .fill("fine, but it wants a real test");
  await page.locator("#decide button[type=submit]").click();

  await expect(page.locator("#decide-msg")).toHaveText("accepted");

  await page.goto("/");
  await expect(card(page, "003").locator(".tag")).toHaveText("ACCEPTED");
  await expect(page.locator("#jobsSummary")).toContainText("1 accepted");

  // On disk, both halves. A note is the part of this a person wrote, and an acceptance is worth
  // nothing as a record if what they said while giving it went nowhere.
  const read = (name) => require("node:fs").readFileSync(
    require("node:path").join(__dirname, "..", ".fixture", "runs", "003", name), "utf8");
  expect(read("decisions.json")).toContain("fine, but it wants a real test");
  expect(read("decisions.json")).toContain("worth remembering anyway");
  expect(read("features.json")).toContain("declined");
  expect(read("features.json")).toContain("the summary already shows it");
});

test("the plus button opens the editor prefilled for an enhancement", async ({ page }) => {
  await (await open(page, "001")).locator(".add").click();

  await expect(page.locator("#wishlistView")).toBeVisible();
  await expect(page.locator("#parent")).toHaveValue("001");
  await expect(page.locator("#id")).toHaveValue("001.1");
});

test("the editor's action bar sits flush with the bottom, with nothing scrolling past it",
  async ({ page }) => {
    // Second time a bar has covered text on this page. The first was negative margins on the
    // jobs header; this one was 4rem of bottom padding on the scroll container, which a sticky
    // bar pins above rather than fills -- leaving a strip where the document scrolled visibly
    // beneath the buttons. Neither is visible to any test that does not lay the page out.
    await page.locator("#navWishlist").click();
    await page.locator("#list .item").first().click();

    const main = page.locator("main");
    await main.evaluate(el => { el.scrollTop = el.scrollHeight * 0.7; });

    const gap = await page.evaluate(() => {
      const el = document.querySelector("main");
      const bar = document.querySelector(".bar");
      return el.getBoundingClientRect().bottom - bar.getBoundingClientRect().bottom;
    });

    expect(gap).toBeLessThan(1);
    expect(gap).toBeGreaterThanOrEqual(-1);
  });

test("switching specs with unsaved edits asks first", async ({ page }) => {
  // There was no dirty tracking at all: clicking another spec in the sidebar replaced every
  // field with no warning, and the three-part description multiplied what one click could lose.
  await page.goto("/");
  await page.locator("#navWishlist").click();
  await page.locator("#list .item").first().click();
  await expect(page.locator("#title")).not.toHaveValue("");

  await page.locator("#goal").fill("something I have not saved");

  let asked = "";
  page.on("dialog", d => { asked = d.message(); d.dismiss(); });
  await page.locator("#list .item").nth(1).click();

  expect(asked).toContain("Unsaved changes");
  // Dismissed means stay put: the edit must still be here.
  await expect(page.locator("#goal")).toHaveValue("something I have not saved");
});

test("accepting the prompt goes ahead and discards", async ({ page }) => {
  await page.goto("/");
  await page.locator("#navWishlist").click();
  await page.locator("#list .item").first().click();
  const firstTitle = await page.locator("#title").inputValue();
  await page.locator("#goal").fill("throwaway edit");

  page.on("dialog", d => d.accept());
  await page.locator("#list .item").nth(1).click();

  await expect(page.locator("#title")).not.toHaveValue(firstTitle);
});

test("an untouched spec switches without a prompt", async ({ page }) => {
  // The failure mode worse than no guard: one that fires on every click teaches you to dismiss
  // it without reading, and then it is not a guard at all.
  await page.goto("/");
  await page.locator("#navWishlist").click();
  await page.locator("#list .item").first().click();
  await expect(page.locator("#title")).not.toHaveValue("");

  let asked = false;
  page.on("dialog", d => { asked = true; d.accept(); });
  await page.locator("#list .item").nth(1).click();
  await page.waitForTimeout(400);

  expect(asked).toBe(false);
});

test("saving a spec keeps the settings the form has no field for", async ({ page }) => {
  // The form built the saved spec from its own fields: compact_after_sessions reset on every save,
  // and a hand-written deploy block would have vanished at the first edit.
  const fs = require("node:fs");
  const path = require("node:path");
  // Saved under the name Forge gives it, <id>-<title>.md, replacing the hand-named file.
  const file = path.join(__dirname, "..", ".fixture", "wishlist", "002-beta-project.md");

  await page.locator("#navWishlist").click();
  await page.locator("#list .item", { hasText: "Beta Project" }).click();
  await expect(page.locator("#title")).toHaveValue("Beta Project");
  await page.locator("#serve").fill("npm run preview -- --port {port}");
  await page.getByRole("button", { name: "Save", exact: true }).click();
  await expect(page.locator("#status")).not.toHaveClass(/err/);

  await expect.poll(() => fs.existsSync(file) ? fs.readFileSync(file, "utf8") : "").toContain("serve:");
  const saved = fs.readFileSync(file, "utf8");
  expect(saved).toContain("compact_after_sessions: 5");
  expect(saved).toContain("npx wrangler pages deploy dist");
  expect(saved).toContain("ads: test");
});

test("the card says what the security check found, and offers to run it again", async ({ page }) => {
  const job = await open(page, "003");
  await expect(job.locator(".stats")).toContainText("1 to decide");
  await expect(job.locator(".seccheck")).toHaveText("Check Security Again");
  // Unstarted projects have nothing to check yet.
  await expect(card(page, "001").locator(".seccheck")).toBeHidden();
});

test("Deploy asks first, showing the exact command, and dismissing it deploys nothing", async ({ page }) => {
  // 003 was accepted by the submission test above, with its security finding ruled on, so it is
  // deployable now. Publishing uses an account that is not Forge's: the command is shown before
  // anything runs, and saying no must leave no trace.
  const fs = require("node:fs");
  const path = require("node:path");
  const gamma = await open(page, "003");
  await expect(gamma.locator(".tag")).toHaveText("ACCEPTED");
  const deploy = gamma.locator(".deploy");
  await expect(deploy).toBeEnabled();
  await expect(deploy).toHaveAttribute("title", "Runs: echo https://gamma.pages.dev");

  let asked = "";
  page.once("dialog", d => { asked = d.message(); d.dismiss(); });
  await deploy.click();

  expect(asked).toContain("echo https://gamma.pages.dev");
  expect(asked).toContain("publishes the project");
  await page.waitForTimeout(500);
  expect(fs.existsSync(path.join(__dirname, "..", ".fixture", "runs", "003", "deploy", "deployments.json"))).toBe(false);
  // No deploy block, no button.
  await expect(card(page, "001").locator(".deploy")).toBeHidden();
});

test("saving an invalid spec is refused rather than written", async ({ page }) => {
  await page.locator("#navWishlist").click();
  await page.locator("#id").fill("009");
  await page.locator("#title").fill("No acceptance");
  // Remove the only acceptance command, which the loader requires.
  await page.locator("#acceptance .listrow button").first().click();

  await page.getByRole("button", { name: "Save", exact: true }).click();

  await expect(page.locator("#status")).toContainText("acceptance");
  await expect(page.locator("#status")).toHaveClass(/err/);
});

test("Finish and stop asks first, and saying no stops nothing", async ({ page }) => {
  // The one button on this page that ends the session. It must never be a single click, and a
  // dismissed prompt must not reach the server at all.
  let requested = 0;
  await page.route("**/api/quit*", route => { requested++; route.abort(); });

  // Waited for rather than assumed: the button reads the job list before it asks, so the prompt
  // arrives a beat after the click and a straight assertion here races it.
  const prompt = page.waitForEvent("dialog");
  await page.locator("#stopForge").click();
  const dialog = await prompt;
  const asked = dialog.message();
  await dialog.dismiss();

  expect(asked).toContain("Stop Forge?");
  // It says what it will wait for, that nothing is killed, and how to get back.
  expect(asked).toContain("Nothing is killed");
  expect(asked).toContain("forge-ui.bat");
  await page.waitForTimeout(300);
  expect(requested).toBe(0);
  await expect(page.locator("#stoppingBar")).toBeHidden();
  await expect(page.locator("#stopForge")).toBeEnabled();
});

test("a stop that finds work still in flight leaves Forge running and says so", async ({ page }) => {
  // Forge answers rather than killing anything. The dashboard has to make that legible, because
  // the tempting reading of "it did not stop" is "it is broken".
  let requested = 0;
  await page.route("**/api/quit*", route => { requested++; route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({stopped: false, working: ["003"], detail: "still mid-turn: 003"}),
  }); });
  page.once("dialog", d => d.accept());

  await page.locator("#stopForge").click();

  await expect(page.locator("#stoppingBar")).toBeVisible();
  await expect(page.locator("#stoppingWhat")).toContainText("still running");
  await expect(page.locator("#stoppingWhy")).toContainText("still mid-turn: 003");
  expect(requested).toBe(1);   // the stub answered, so no real quit reached the server
  // Back to normal: it went on taking work, so the button has to be pressable again.
  await expect(page.locator("#stopForge")).toBeEnabled();
  await expect(page.locator("#stopForge")).toHaveText("Finish and stop");
});

test("a stop that settles says Forge has stopped, and stops pretending to be live", async ({ page }) => {
  let requested = 0;
  await page.route("**/api/quit*", route => { requested++; route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify({stopped: true, working: [], detail: "no agent turn is in flight"}),
  }); });
  page.once("dialog", d => d.accept());

  await page.locator("#stopForge").click();

  await expect(page.locator("#stoppingWhat")).toHaveText("Forge has stopped.");
  await expect(page.locator("#stoppingWhy")).toContainText("forge-ui.bat");
  await expect(page.locator("#stopForge")).toBeDisabled();
  await expect(page.locator("#stopForge")).toHaveText("Stopped");
  expect(requested).toBe(1);   // the stub answered, so no real quit reached the server
});

test("when Forge actually goes, the page says so rather than quietly freezing", async ({ page }) => {
  // The failure this is here for: Forge exits, every poll after it fails, and a dashboard that
  // swallows failed polls sits there showing a snapshot of a world that has ended.
  //
  // Aborted rather than left hanging. A route handler that never answers is released to the
  // network when the page closes, so an earlier version of this test sent a real quit to the
  // fixture server, stopped it, and failed two unrelated tests that ran afterwards.
  let requested = 0;
  await page.route("**/api/quit*", route => { requested++; route.abort("connectionrefused"); });
  await page.route("**/api/jobs", route => route.abort());
  page.once("dialog", d => d.accept());

  await page.locator("#stopForge").click();
  await expect.poll(() => requested).toBe(1);

  await expect(page.locator("#stoppingWhat")).toHaveText("Forge has stopped.", {timeout: 15000});
  await expect(page.locator("#stopForge")).toBeDisabled();
});

test("Read it to me says what the project is, and leaves the words on the card", async ({ page }) => {
  // The voice is the browser's, so the test replaces it: what matters here is that the words
  // arrive, are shown as well as spoken, and are the project's own state rather than a template.
  const fs = require("node:fs");
  const path = require("node:path");
  await page.addInitScript(() => {
    window.spoken = [];
    // defineProperty, not assignment: speechSynthesis is a read-only accessor on window, so
    // "window.speechSynthesis = ..." is quietly ignored and the stub never takes.
    window.spokenBy = [];
    Object.defineProperty(window, "speechSynthesis", {
      configurable: true,
      value: {
        speaking: false,
        // The list a browser with natural voices offers: the default is the oldest one, which is
        // exactly the trap the page is supposed to avoid falling into.
        getVoices: () => ([
          {name: "Microsoft David - English (United States)", lang: "en-US", default: true},
          {name: "Microsoft Zira - English (United States)", lang: "en-US"},
          {name: "Microsoft Ava Online (Natural) - English (United States)", lang: "en-US"},
        ]),
        cancel() { this.speaking = false; },
        speak(u) {
          window.spoken.push(u.text);
          window.spokenBy.push(u.voice ? u.voice.name : "(default)");
          this.speaking = false;
          if (u.onend) u.onend();
        },
      },
    });
    window.SpeechSynthesisUtterance = function (text) { this.text = text; };
  });
  await page.reload();

  const job = await open(page, "003");
  await job.locator(".readaloud").click();

  await expect.poll(() => page.evaluate(() => window.spoken.join(" "))).toContain("Gamma");
  const said = await page.evaluate(() => window.spoken.join(" "));
  expect(said).not.toContain("*");
  await expect(job.locator(".saidtext")).toContainText("Gamma");
  await expect(job.locator(".recording")).toBeVisible();
  // The words stay put: the card refreshes every few seconds and must not wipe them.
  await page.waitForTimeout(4500);
  await expect(job.locator(".saidtext")).toContainText("Gamma");

  // The natural voice, not the one marked default. The default is usually the oldest installed,
  // and the gap between that and a natural one decides whether anyone listens to the end.
  const by = await page.evaluate(() => window.spokenBy.join(" "));
  expect(by).toContain("Natural");

  const transcript = path.join(__dirname, "..", ".fixture", "runs", "003", "walkthrough.md");
  await expect.poll(() => fs.existsSync(transcript)).toBe(true);
  expect(fs.readFileSync(transcript, "utf8")).toContain("Gamma");
});

test("a browser that offers no voice list is still read to", async ({ page }) => {
  // Older browsers, and some embedded ones, have speechSynthesis without getVoices. Asking for a
  // better voice must not cost those the reading altogether.
  await page.addInitScript(() => {
    window.spoken = [];
    Object.defineProperty(window, "speechSynthesis", {
      configurable: true,
      value: {
        speaking: false,
        cancel() { this.speaking = false; },
        speak(u) { window.spoken.push(u.text); this.speaking = false; if (u.onend) u.onend(); },
      },
    });
    window.SpeechSynthesisUtterance = function (text) { this.text = text; };
  });
  await page.reload();

  await (await open(page, "003")).locator(".readaloud").click();
  await expect.poll(() => page.evaluate(() => window.spoken.join(" "))).toContain("Gamma");
});

test("what came back, and every blocking finding, must be ruled on before anything is sent", async ({ page }) => {
  // The form used to tick "Fine as it is" by default and carry a past ruling forward as the
  // answer, so a blocking defect that came back for the third time was accepted by pressing
  // Submit without reading it. 004 in the fixture is held on one, with a claim that came back.
  await page.goto("/runs/004/report.html");

  await expect(page.locator(".decide-blocked")).toContainText("Waiting for you");
  const rows = page.locator("#decide [data-kind=review]");
  await expect(rows).toHaveCount(2);
  await expect(rows.locator("input[type=radio]:checked")).toHaveCount(0);
  await expect(rows.filter({ hasText: "the tables case is untested" }).locator(".decide-back"))
    .toContainText("Came back — you ruled it Needs work");

  let posted = false;
  page.on("request", (request) => {
    if (request.url().includes("/api/jobs/decide")) posted = true;
  });
  await page.locator("#decide button[type=submit]").click();

  await expect(page.locator("#decide-msg")).toContainText("Rule on the 2 items");
  await expect(page.locator("#decide .decide-missing")).toHaveCount(2);
  expect(posted).toBe(false);

  // Ticking one clears its outline on the next try; the other still stops the submission.
  await rows.first().locator("input[value=changes]").check();
  await page.locator("#decide button[type=submit]").click();
  await expect(page.locator("#decide-msg")).toContainText("Rule on the 1 item");
  await expect(page.locator("#decide .decide-missing")).toHaveCount(1);
  expect(posted).toBe(false);

  // What was submitted before is on the page, as it was submitted.
  const history = page.locator("table.history");
  await expect(history).toContainText("Needs work");
  await expect(history).toContainText("add a test with a table in it");
  await expect(history).toContainText("sent back; started");
});

test("a finished project with blocking findings offers to send them straight back", async ({ page }) => {
  // Nothing blocking on 003 in the fixture, so nothing is offered.
  await expect(card(page, "003").locator(".fixblocking")).toBeHidden();

  // The same project as a reviewer would leave it: finished, with two blocking findings on it.
  // The way there used to be buying a whole review first, or ruling on every item in the report.
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    jobs.filter(j => j.id === "003").forEach(j => {
      j.phase = "DONE";
      j.reviewState = "findings";
      j.blockingFindings = 2;
    });
    await route.fulfill({ response, json: jobs });
  });
  // Stubbed and counted: pressing it for real starts an agent session.
  let posted = null;
  await page.route("**/api/jobs/fix", async (route) => {
    posted = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "started" }) });
  });
  await page.reload();

  const gamma = await open(page, "003");
  const fix = gamma.locator(".fixblocking");
  await expect(fix).toBeVisible();
  await expect(fix).toHaveText("Fix Blocking Findings (2)");
  // One primary control per card: with blockers on it, fixing them is what the card asks for.
  await expect(fix).toHaveClass(/primary/);
  await expect(gamma.locator(".decide")).not.toHaveClass(/primary/);
  // And the line above the buttons no longer says the review found nothing blocking.
  await expect(gamma.locator(".caveat")).toContainText("2 blocking finding(s)");

  await fix.click();

  await expect.poll(() => posted).toEqual({ id: "003" });
  await expect(gamma.locator(".msg")).toHaveText("started");
});

test("a project stopped only by its default look shows the kept look beside the new one, and approving keeps the new", async ({ page }) => {
  // 003.4 the way it really stopped: everything passed but the look, which its navigation bar had
  // changed on purpose, and no agent can reach the reference that is kept outside its repository.
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    jobs.filter(j => j.id === "003").forEach(j => {
      j.phase = "PARKED";
      j.lastError = "only the default look differs from the one kept for it (56% changed): "
        + "approve the new look if the change was meant, or send it back if it was not";
      j.lastFailingCommand = "default look: 56% changed";
    });
    await route.fulfill({ response, json: jobs });
  });
  // Stubbed and read back: a real approval sets the fixture's look aside and starts its gate.
  let approved = null;
  await page.route("**/api/jobs/approve-look", async (route) => {
    approved = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ result: "started" }) });
  });
  await page.reload();

  const gamma = await open(page, "003");
  const looks = gamma.locator(".looks");
  await expect(looks).toBeVisible();
  await expect(looks.locator("img").nth(0)).toHaveAttribute("src", "/runs/003/look-reference.png");
  await expect(looks.locator("img").nth(1)).toHaveAttribute("src", "/runs/003/repo/artifacts/look-default.png");
  await expect(looks).toContainText("Kept");
  await expect(looks).toContainText("Now");
  await expect(gamma.locator(".recheck"), "checking again would only fail the same way").toBeHidden();

  page.once("dialog", dialog => dialog.accept());
  await gamma.locator(".approvelook").click();
  await expect.poll(() => approved).toEqual({ id: "003" });
});

test("a project parked on a clip asks about it, and can be checked again without a session", async ({ page }) => {
  // 003 the way it really parked: one feature approved under two names, the clip made under one.
  // The only way to settle it was a form row folded away under "all ruled on", which nothing
  // pointed to, and Run Another Session would have briefed the agent with the old complaint.
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    jobs.filter(j => j.id === "003").forEach(j => {
      j.phase = "PARKED";
      j.lastError = "still incomplete after 23 sessions";
      j.lastFailingCommand = "recordings: 1 feature(s) unfilmed";
      j.unfilmed = [{ id: "feature-report-customization", name: "Report customization", listed: false,
                      sameAs: ["Report export", "Empty report reset"], problem: "no recording was produced" }];
    });
    await route.fulfill({ response, json: jobs });
  });
  // Stubbed and read back: a real ruling writes the fixture, and a real check starts the pipeline.
  let ruled = null;
  await page.route("**/api/jobs/feature", async (route) => {
    ruled = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      result: "\"Report customization\" no longer needs a clip: it is \"Report export\". "
        + "Check Again runs the gate without spending a session." }) });
  });
  let rechecked = null;
  await page.route("**/api/jobs/recheck", async (route) => {
    rechecked = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "started" }) });
  });
  await page.reload();

  const gamma = await open(page, "003");
  const question = gamma.locator(".clipq");
  await expect(question).toHaveCount(1);
  await expect(question).toContainText("Report customization");
  await expect(question.locator(".clipwhy")).toContainText("no longer lists it");
  // The likeliest twin is offered first, so the usual answer is one press.
  await expect(question.locator(".twin")).toHaveValue("Report export");
  await expect(gamma.locator(".move-sub")).toContainText("Check Again runs the gate without spending a session");

  await question.locator(".same").click();
  await expect.poll(() => ruled).toEqual({ id: "003", itemId: "feature-report-customization",
                                           verdict: "declined", sameAs: "Report export", note: "" });
  await expect(gamma.locator(".msg")).toContainText("no longer needs a clip");

  const recheck = gamma.locator(".recheck");
  await expect(recheck).toBeVisible();
  await recheck.click();
  await expect.poll(() => rechecked).toEqual({ id: "003" });
});

test("still wanted, with a note, is sent, said plainly, and then reads as answered", async ({ page }) => {
  // Reported: "I hit Still Want It and nothing happened." It had worked; the only sign was a line
  // of small grey text, and the question looked exactly as unanswered as before.
  let kept = false;
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    jobs.filter(j => j.id === "003").forEach(j => {
      j.phase = "PARKED";
      j.lastError = "still incomplete after 24 sessions";
      j.lastFailingCommand = "recordings: 1 feature(s) unfilmed";
      j.unfilmed = [{ id: "feature-credit-offer-for-an-ad", name: "Credit offer for an ad", listed: false,
                      sameAs: [], problem: "no recording was produced", kept }];
    });
    await route.fulfill({ response, json: jobs });
  });
  let ruled = null;
  await page.route("**/api/jobs/feature", async (route) => {
    ruled = route.request().postDataJSON();
    kept = true;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      result: "\"Credit offer for an ad\" is still wanted, with your note. Run Another Session sends the "
        + "note to the agent, and the agent builds and films it." }) });
  });
  await page.reload();

  const gamma = await open(page, "003");
  const question = gamma.locator(".clipq");
  // Removed, not renamed, this time: the words cover both.
  await expect(question.locator(".clipwhy")).toContainText("renamed or removed");
  await expect(question.locator(".same")).toHaveCount(0);
  await question.locator(".note").fill("it should use real Google ads");
  await question.locator(".keep").click();

  await expect.poll(() => ruled).toEqual({ id: "003", itemId: "feature-credit-offer-for-an-ad",
                                           verdict: "approved", sameAs: "", note: "it should use real Google ads" });
  await expect(gamma.locator(".msg")).toContainText("with your note");
  await expect(gamma.locator(".clipq.kept")).toContainText("still wanted");

  // The answer can be changed, on this page, without anything being undone on disk.
  await gamma.locator(".reopen-q").click();
  await expect(gamma.locator(".clipq .keep")).toBeVisible();
});

test("for an unfinished project a design is kept for later, without a prompt and without building", async ({ page }) => {
  await page.route("**/api/polish?id=003", (route) => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({
      id: "003", title: "Gamma Project", running: false, blocked: "", buildAs: "later", kept: false,
      board: { askedAt: "2026-10-02T20:00:00Z", note: "", state: "ready", refused: [], chosen: "", chosenAt: null,
        options: [{ id: "CLAUDE-1", vendor: "CLAUDE", name: "Neon arcade", pitch: "glowing outlines",
                    file: "design/CLAUDE/option-1.html" }] } }) }));
  let built = null;
  await page.route("**/api/polish/build", async (route) => {
    built = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({
      result: "kept \"Neon arcade\" for when it is finished: come back here then to build it" }) });
  });
  let prompted = false;
  page.on("dialog", (dialog) => { prompted = true; dialog.dismiss(); });

  await page.goto("/polish?id=003");
  await expect(page.locator("#buildNote")).toContainText("is kept");
  // Leaving the look to the agent is a build, and there is nothing to build yet.
  await expect(page.locator("#plainBtn")).toBeHidden();
  const keep = page.locator(".option .build");
  await expect(keep).toHaveText("Keep This One for Later");
  await page.locator("#note").fill("clowns seen from above instead of the x");
  await keep.click();

  // The note goes with the pick: it used to be dropped for a project that was not finished.
  await expect.poll(() => built).toEqual({ id: "003", option: "CLAUDE-1",
                                           note: "clowns seen from above instead of the x" });
  expect(prompted).toBe(false);
  await expect(page.locator("#result")).toContainText("kept");
});

test("a design kept with a note brings the note back when it is built", async ({ page }) => {
  await page.route("**/api/polish?id=003", (route) => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({
      id: "003", title: "Gamma Project", running: false, blocked: "", buildAs: "session", kept: true,
      board: { askedAt: "2026-10-02T20:00:00Z", note: "", state: "ready", refused: [], chosen: "CLAUDE-1",
        chosenAt: "2026-10-03T20:00:00Z", chosenNote: "clowns seen from above",
        options: [{ id: "CLAUDE-1", vendor: "CLAUDE", name: "Neon arcade", pitch: "glowing outlines",
                    file: "design/CLAUDE/option-1.html" }] } }) }));
  let built = null;
  await page.route("**/api/polish/build", async (route) => {
    built = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ result: "started 003" }) });
  });
  page.on("dialog", (dialog) => dialog.accept());

  await page.goto("/polish?id=003");
  await expect(page.locator("#picked")).toContainText("with your note “clowns seen from above”");
  // Back in the box, where it can still be changed before it is sent.
  await expect(page.locator("#note")).toHaveValue("clowns seen from above");
  await page.locator(".option .build").click();

  await expect.poll(() => built).toEqual({ id: "003", option: "CLAUDE-1", note: "clowns seen from above" });
});

test("more like one design asks what to change first, then sends that design with it", async ({ page }) => {
  // 003: asked again with "I like the Bright Panel, vary the artwork", the designers never saw it
  // and redrew everything; the page that was liked went with the board.
  await page.route("**/api/polish?id=003", (route) => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({
      id: "003", title: "Gamma Project", running: false, blocked: "", buildAs: "session", kept: false,
      board: { askedAt: "2026-10-06T20:00:00Z", note: "", state: "none", refused: ["CODEX is out of quota"],
        chosen: "", chosenAt: null, chosenNote: "",
        options: [{ id: "LIKED", vendor: "CLAUDE", name: "Bright Panel", pitch: "a bright cartoon pit-lane",
                    file: "design/liked.html" }] } }) }));
  let asked = null;
  await page.route("**/api/polish/ask", async (route) => {
    asked = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "asking 2 models for designs like \"Bright Panel\"" }) });
  });

  await page.goto("/polish?id=003");
  const liked = page.locator(".option", { hasText: "Bright Panel" });
  // Nobody drew anything last time, and the design it started from is still there to pick.
  await expect(page.locator("#options")).toContainText("No model offered a design");
  await expect(liked.locator(".tag")).toHaveText("the one you started from");
  await expect(liked.locator(".build")).toBeEnabled();

  // With nothing typed, nothing is spent: the box is pointed at instead.
  await liked.locator(".more").click();
  await expect(page.locator("#result")).toContainText("Say what to change in “Bright Panel”");
  await expect(page.locator("#note")).toBeFocused();
  expect(asked).toBeNull();

  await page.locator("#note").fill("vary the artwork");
  await liked.locator(".more").click();
  await expect.poll(() => asked).toEqual({ id: "003", note: "vary the artwork", base: "LIKED" });
  await expect(page.locator("#result")).toContainText("like \"Bright Panel\"");
});

test("extras are asked for and built beside the current look, never instead of it", async ({ page }) => {
  let board = null;
  await page.route("**/api/polish?id=003", (route) => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify({
      id: "003", title: "Gamma Project", running: false, blocked: "", buildAs: "session", kept: false, board }) }));
  let asked = null;
  await page.route("**/api/polish/ask", async (route) => {
    asked = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "asking 1 model for theme designs" }) });
  });
  let built = null;
  await page.route("**/api/polish/build", async (route) => {
    built = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ result: "started" }) });
  });

  await page.goto("/polish?id=003");
  await expect(page.locator("#extraKind")).toBeHidden();
  await page.getByLabel("Add an extra, keeping the look it has").check();
  await expect(page.locator("#extraKind")).toBeVisible();
  await page.locator("#extraKind").selectOption("theme");
  await page.locator("#note").fill("Halloween");
  await page.locator("#askBtn").click();
  await expect.poll(() => asked).toEqual({ id: "003", note: "Halloween", extra: "theme" });

  // The designs came back as a theme: the page says so, and builds them as one.
  board = { askedAt: "2026-10-08T20:00:00Z", note: "Halloween", state: "ready", refused: [], chosen: "",
    chosenAt: null, chosenNote: "", extra: "theme",
    options: [{ id: "CLAUDE-1", vendor: "CLAUDE", name: "Pumpkin Patch", pitch: "orange dusk and bats",
                file: "design/CLAUDE/option-1.html" }] };
  await page.reload();
  await expect(page.locator("#extraNote")).toContainText("These are theme designs");
  await expect(page.locator("#extraNote")).toContainText("the current look stays");
  await expect(page.locator("#plainBtn")).toBeHidden();
  const patch = page.locator(".option", { hasText: "Pumpkin Patch" });
  await expect(patch.locator(".build")).toHaveText("Add This Extra");

  let question = "";
  page.once("dialog", (dialog) => { question = dialog.message(); dialog.accept(); });
  await patch.locator(".build").click();
  await expect.poll(() => built).toEqual({ id: "003", option: "CLAUDE-1", note: "", extra: "theme" });
  expect(question).toContain("beside the look the project has");
});

test("a new idea from the idea generator fills the description, and a reroll replaces it", async ({ page }) => {
  // Stubbed: the real one runs 006's program on this machine.
  await page.route("**/api/capabilities", (route) => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ suggest: true, jobs: true }) }));
  await page.route("**/api/ideas", (route) => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify({ project: "006", categories: ["cli tool", "browser extension"] }) }));
  const asked = [];
  await page.route((url) => url.pathname === "/api/idea", async (route) => {
    const params = new URL(route.request().url()).searchParams;
    asked.push({ category: params.get("category"), maxEffort: params.get("maxEffort") });
    const n = asked.length;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ project: "006",
      idea: { name: `Idea ${n}`, description: `The ${n} one.`, audience: "makers", category: "cli tool",
              buildTimeEstimate: "1-2 weeks", monetization: "one-time purchase", effort: 2 } }) });
  });

  await page.reload();
  await page.locator("#navWishlist").click();
  const button = page.locator("#ideaBtn");
  await expect(button).toHaveText("💡 New Idea");
  await expect(page.locator("#ideaCategory option")).toHaveText(["Any", "cli tool", "browser extension"]);

  await button.click();
  await expect(page.locator("#idea")).toHaveValue(/^Idea 1: The 1 one\.\n\nAudience: makers\. Category: cli tool\. /);
  await expect(page.locator("#idea")).toHaveValue(/Effort 2\/5, about 1-2 weeks to build\.$/);
  await expect(button).toHaveText("🎲 Reroll");
  await expect(page.locator("#ideaStatus")).toContainText("Press Suggest to turn it into a spec");

  await page.locator("#ideaCategory").selectOption("cli tool");
  await page.locator("#ideaEffort").selectOption("3");
  await button.click();
  await expect(page.locator("#idea")).toHaveValue(/^Idea 2: /);
  expect(asked).toEqual([{ category: "", maxEffort: "" }, { category: "cli tool", maxEffort: "3" }]);
});

test("a vendor held for quota can be tried again from the bar that says so", async ({ page }) => {
  // Codex was reset at the vendor and held here for three more days: nothing calls a held vendor,
  // and only a completed call lets a hold go.
  let held = true;
  await page.route("**/api/quota", (route) => route.fulfill({ status: 200, contentType: "application/json",
    body: JSON.stringify(held ? { CODEX: { observedAt: "2026-10-05T00:47:34Z",
      retryAfter: new Date(Date.now() + 3 * 86400_000).toISOString(), message: "You've hit your usage limit." } } : {}) }));
  let retried = null;
  await page.route((url) => url.pathname === "/api/quota/retry", async (route) => {
    retried = new URL(route.request().url()).searchParams.get("vendor");
    held = false;
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "CODEX is no longer held: the next call to it will tell." }) });
  });

  await page.reload();
  const bar = page.locator("#quotaBar");
  await expect(bar).toContainText("is out of quota");
  await bar.locator(".retry").click();

  await expect.poll(() => retried).toBe("CODEX");
  await expect(bar).toContainText("CODEX is no longer held");
  await expect(bar.locator(".retry")).toHaveCount(0);
});

test("a project with a run file can be played from its card, and one without is not offered it", async ({ page }) => {
  // 001 has never run, so it has no run.bat and nothing to open.
  await expect(card(page, "001")).toBeVisible();
  await expect(card(page, "001").locator(".play")).toBeHidden();

  // Stubbed: pressing it for real opens a console window on whatever machine runs this suite.
  let posted = null;
  await page.route("**/api/jobs/play", async (route) => {
    posted = route.request().postDataJSON();
    await route.fulfill({ status: 200, contentType: "application/json",
      body: JSON.stringify({ result: "Opened Gamma Project in its own window. Close that window to stop it." }) });
  });

  const gamma = await open(page, "003");
  const play = gamma.locator(".play");
  await expect(play).toBeVisible();
  await expect(play).toBeEnabled();
  await expect(play).toContainText("Play");

  await play.click();

  await expect.poll(() => posted).toEqual({ id: "003" });
  await expect(gamma.locator(".msg")).toContainText("in its own window");
});

test("the league ranks the vendors from what the runs record, and says how it scores", async ({ page }) => {
  const league = page.locator("#league");
  await expect(league).toBeVisible();
  // Closed until asked for: the summary alone names everyone and their points.
  const summary = page.locator("#leagueSummary");
  await expect(summary).toContainText(/CLAUDE -?\d+/);
  await expect(summary).toContainText(/CODEX -?\d+/);
  await expect(league.locator("table")).toBeHidden();

  await summary.click();

  // The counts are beside the points, because the points are only an ordering.
  const claude = league.locator("tr", { hasText: "CLAUDE" });
  await expect(claude).toContainText("built:");
  const codex = league.locator("tr", { hasText: "CODEX" });
  await expect(codex).toContainText("1 reviewed: 1 finding(s) fixed, 0 withdrawn");
  await expect(league.locator(".legend")).toContainText("3 for a project that passed the gate");
  await expect(league.locator(".legend")).toContainText("no agent sees it");

  // Still open after the page's next poll: redrawing the table must not close it under the reader.
  await page.waitForTimeout(4500);
  await expect(league.locator("table")).toBeVisible();
});

test("a screenshot pasted or picked into a note is saved in the project and named in the note", async ({ page }) => {
  // The smallest valid PNG: a 1x1 transparent pixel.
  const png = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    "base64");
  await page.goto("/review?id=003");
  const note = page.locator(".card.must #note");
  await expect(note).toHaveAttribute("data-attach", "003");

  // Picked with the button beside the box.
  await expect(page.locator(".card.must .attach-button")).toHaveText("Add image");
  await page.locator(".card.must input[type=file]").setInputFiles({ name: "shot.png", mimeType: "image/png", buffer: png });
  await expect(note).toHaveValue(/^\[image: \.forge-images\/\d{8}-\d{6}-\d+\.png\] $/);
  await expect(page.locator(".card.must .attach-status")).toHaveText("Image added.");

  // Pasted, as from a snipping tool: added at the caret, after what was typed.
  await note.fill("buttons stretch on a desktop:");
  await note.evaluate((box, bytes) => {
    const transfer = new DataTransfer();
    transfer.items.add(new File([new Uint8Array(bytes)], "clip.png", { type: "image/png" }));
    box.focus();
    box.dispatchEvent(new ClipboardEvent("paste", { clipboardData: transfer, bubbles: true, cancelable: true }));
  }, [...png]);
  await expect(note).toHaveValue(/^buttons stretch on a desktop: \[image: \.forge-images\/\S+\.png\] $/);

  // On disk where the agent can open it, and never something that is not an image.
  const saved = (await note.inputValue()).match(/\[image: (\S+)\]/)[1];
  const fs = require("node:fs");
  const path = require("node:path");
  expect(fs.existsSync(path.join(__dirname, "..", ".fixture", "runs", "003", "repo", saved))).toBe(true);
  const refused = await page.evaluate(() => fetch("/api/jobs/attach", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ id: "003", data: btoa("<script>alert(1)</script>") })
  }).then(r => r.json()));
  expect(refused.error).toContain("PNG, JPEG, GIF or WebP");
});
