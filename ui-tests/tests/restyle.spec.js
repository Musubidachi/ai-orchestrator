const { test, expect } = require("@playwright/test");

/**
 * The board as the mockup draws it (ui/extras/restyle.js): two sections, a table of cards
 * that says what each project waits for now, and a project's panel that stays closed until its own
 * line is chosen.
 *
 * The page's clock is held at a Saturday, 5:13 PM in New York, with Codex out of quota until 7:29 AM
 * on Sunday, so the times on the board read the same on any machine, on any day.
 */

test.use({ timezoneId: "America/New_York", locale: "en-US" });

const NOW = new Date("2026-11-07T22:13:00Z");
const CODEX_BACK = new Date(NOW.getTime() + (14 * 60 + 16) * 60_000);  // Sunday 7:29 AM
const QUOTA = {
  CODEX: { observedAt: NOW.toISOString(), retryAfter: CODEX_BACK.toISOString(), message: "You've hit your usage limit." },
};

/*
 * Lighthouse Keeper and five versions of it, as in families.spec.js: two accepted, one waiting for
 * Codex to review it, and three waiting their turn behind that one. 003 finished and needs you; 002
 * is next in the queue. Every state is set here, because the specs before this one leave the fixture
 * changed.
 */
function family(jobs) {
  const base = Object.assign(jobs.find(j => j.id === "001"), {
    title: "Lighthouse Keeper", phase: "ACCEPTED", queuePosition: 0, blockedReason: "", lastError: "",
    assignedTo: "CLAUDE", running: false, shelvedBranch: "", repoMissing: false,
  });
  Object.assign(jobs.find(j => j.id === "002"),
    { phase: "NEW", queuePosition: 1, blockedReason: "", running: false, shelvedBranch: "", repoMissing: false });
  Object.assign(jobs.find(j => j.id === "003"),
    { phase: "DONE", reviewState: "clean", blockingFindings: 0, queuePosition: 0, blockedReason: "", running: false,
      shelvedBranch: "", repoMissing: false });
  const version = (n, state) => jobs.push({
    ...base, id: `001.${n}`, title: `Lighthouse Keeper, part ${n}`, parentId: n === 1 ? "001" : `001.${n - 1}`,
    ...state,
  });
  version(1, { phase: "ACCEPTED" });
  version(2, {
    phase: "SUSPENDED", lastError: "waiting to be reviewed: RATE_LIMITED",
    blockedReason: "CODEX reviews this, and is out of quota until later",
    reviewBlockedReason: "CODEX reviews this, and is out of quota until later",
  });
  for (const n of [3, 4, 5]) {
    version(n, { phase: "NEW", blockedReason: `waiting for 001.${n - 1} to be accepted first` });
  }
}

/* The board above at that moment, with `quota` closed, after `change` has altered it further. The
   queue is off, so the You lane says when to press Resume Now. */
async function board(page, { quota = QUOTA, change = () => {} } = {}) {
  await page.clock.setFixedTime(NOW);
  await page.route("**/api/auto", (route) => route.fulfill({ json: { available: true, autoRun: false } }));
  await page.route("**/api/quota", (route) => route.fulfill({ json: quota }));
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    family(jobs);
    change(jobs);
    jobs.sort((a, b) => a.id.localeCompare(b.id, "en", { numeric: true }));
    await route.fulfill({ response, json: jobs });
  });
  await page.goto("/");
  await expect(familyRow(page)).toBeVisible();
}

const familyRow = (page) => page.locator('.fmrow[data-family="001"]');
// An id with a dot in it is not a CSS id selector.
const row = (page, id) => page.locator(`[id="row-${id}"]`);
const panel = (page, id) => page.locator(`[id="job-${id}"] .detail`);

test("the panel stays closed until a project's own line is chosen, then pushes the table aside", async ({ page }) => {
  await board(page);
  // The project that needs you is chosen, as before, but nothing is open beside the table.
  await expect(row(page, "003")).toHaveAttribute("aria-current", "true");
  await expect(page.locator("#jobs .detail:visible")).toHaveCount(0);
  const table = await page.locator("#jobs").boundingBox();
  const wide = await row(page, "002").boundingBox();
  expect(wide.x + wide.width).toBeGreaterThan(table.x + table.width - 2);

  // Opening a family chooses the version its row speaks for, and still opens no panel.
  await familyRow(page).click();
  await expect(row(page, "001.2")).toHaveAttribute("aria-current", "true");
  await expect(page.locator("#jobs .detail:visible")).toHaveCount(0);

  // Choosing the version's own line does, beside the table, which gives it the room.
  await row(page, "001.2").click();
  await expect(panel(page, "001.2")).toBeVisible();
  const line = await row(page, "001.2").boundingBox();
  const beside = await panel(page, "001.2").boundingBox();
  expect(beside.x).toBeGreaterThanOrEqual(line.x + line.width - 1);
  // Another line swaps what it shows.
  await row(page, "002").click();
  await expect(panel(page, "002")).toBeVisible();
  await expect(panel(page, "001.2")).toBeHidden();

  // Its × closes it, and the line it showed keeps the focus and stays chosen.
  await panel(page, "002").locator(".rsclose").click();
  await expect(page.locator("#jobs .detail:visible")).toHaveCount(0);
  await expect(row(page, "002")).toBeFocused();
  await expect(row(page, "002")).toHaveAttribute("aria-current", "true");
  // So does Esc.
  await row(page, "002").click();
  await expect(panel(page, "002")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator("#jobs .detail:visible")).toHaveCount(0);
  await expect(row(page, "002")).toBeFocused();
});

test("Esc is left to a field being typed in, and to a dialog", async ({ page }) => {
  await board(page, {
    change: (jobs) => Object.assign(jobs.find(j => j.id === "003"), {
      unfilmed: [{ id: "login", name: "Logging in", problem: "no clip was recorded", listed: true, sameAs: [] }],
    }),
  });
  await row(page, "003").click();
  const note = panel(page, "003").locator("input.note");
  await note.fill("it should show the real page");
  await note.press("Escape");
  await expect(panel(page, "003")).toBeVisible();

  await familyRow(page).click();
  await row(page, "001.2").click();
  await panel(page, "001.2").locator(".pzbtn").click();
  const dialog = page.locator("dialog.pzdlg");
  await expect(dialog).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(panel(page, "001.2")).toBeVisible();
  // With the dialog gone, Esc is the panel's again.
  await page.keyboard.press("Escape");
  await expect(page.locator("#jobs .detail:visible")).toHaveCount(0);
});

test("the next 24 hours hold the queue switch, and say each lane's next step inside its bar", async ({ page }) => {
  await board(page);
  const day = page.locator(".wnd24");
  await expect(day.locator(".wnhead #autoRun")).toBeAttached();
  await expect(day.locator(".wnhead h2")).toHaveText("Next 24 hours");
  // Codex's lane says what holds it before its mark; yours, what to press, after its mark.
  await expect(day.locator('.wnrow[data-vendor="CODEX"] .rsbefore')).toContainText("Out of quota until 7:29 AM");
  await expect(day.locator(".wnyou .rsafter"))
    .toHaveText("From 7:29 AM: press Resume Now on Lighthouse Keeper, part 2");
  // The projects' own heading holds their counts and buttons, above the tiles.
  const head = page.locator(".rshead");
  await expect(head.locator("h2")).toHaveText("Projects");
  await expect(head.locator(".jobsbar button", { hasText: "Renumber" })).toBeVisible();
  expect((await head.boundingBox()).y + (await head.boundingBox()).height)
    .toBeLessThanOrEqual((await page.locator("#tiles").boundingBox()).y);
});

test("with every vendor open and nothing timed, a quiet card says so and holds the switch", async ({ page }) => {
  await board(page, { quota: {} });
  const quiet = page.locator(".rsday");
  await expect(quiet).toBeVisible();
  await expect(quiet.locator(".rssay")).toHaveText("Every vendor is open, and nothing waits for a time.");
  await expect(quiet.locator("#autoRun")).toBeVisible();
});

test("each line says what it waits for now, and keeps its rank in its small print", async ({ page }) => {
  await board(page);
  const now = (id) => row(page, id).locator(".rsnow");
  await expect(now("003").locator(".rswhat")).toHaveText("Your decision");
  await expect(now("002").locator(".rswhat")).toHaveText("Next up");
  await expect(row(page, "002").locator(".sub")).toHaveAttribute("data-rsrank", /^\d+$/);
  // The family's row says it in its own words, in the colour of what it is.
  await expect(familyRow(page).locator(".fmnow")).toHaveAttribute("data-tone", "wait");

  await familyRow(page).click();
  await expect(now("001.2").locator(".rswhat")).toHaveText("Waiting for cross-review");
  await expect(now("001.2").locator(".rswhen")).toHaveText("Codex back 7:29 AM");
  await expect(now("001.2")).toHaveAttribute("data-tone", "wait");
  await expect(now("001.3").locator(".rswhat")).toHaveText("After 001.2 is accepted");
  // The table's header names the columns the lines are in.
  await expect(page.locator(".rsthead")).toBeVisible();
  await expect(page.locator(".ledgerhead")).toBeHidden();
});

test("spend, usage and the league are folded under the table, and stay as they were left", async ({ page }) => {
  await board(page);
  const foot = page.locator(".rsfoot");
  await expect(foot).toBeVisible();
  await expect(foot.locator("#league")).toBeHidden();
  // Its own summary, not the league's inside it.
  const fold = foot.locator(":scope > summary");
  await fold.click();
  await expect(foot.locator("#league")).toBeVisible();

  await page.reload();
  await expect(familyRow(page)).toBeVisible();
  await expect(foot.locator("#league")).toBeVisible();
  await fold.click();
  await page.reload();
  await expect(familyRow(page)).toBeVisible();
  await expect(foot.locator("#league")).toBeHidden();
});

test.describe("on a phone", () => {
  test.use({ viewport: { width: 412, height: 915 } });

  test("a line opens where it is, as before, under the same two headings", async ({ page }) => {
    await board(page);
    await expect(page.locator(".rshead h2")).toHaveText("Projects");
    await expect(page.locator(".rsthead")).toBeHidden();
    await row(page, "002").click();
    const line = await row(page, "002").boundingBox();
    const under = await panel(page, "002").boundingBox();
    expect(under.y).toBeGreaterThanOrEqual(line.y + line.height - 1);
    await expect(panel(page, "002").locator(".rsclose")).toBeHidden();
    // Esc is not how a phone closes it; its line is.
    await page.keyboard.press("Escape");
    await expect(panel(page, "002")).toBeVisible();
  });
});
