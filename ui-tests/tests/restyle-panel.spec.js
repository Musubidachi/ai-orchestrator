const { test, expect } = require("@playwright/test");

/**
 * A project's panel as the mockup draws it (ui/extras/restyle-panel.js): its steps listed top to
 * bottom, its counts three to a row, the builder's handoff as a quote, its buttons in one row, and,
 * beside the table, its phase said once.
 *
 * The page's clock is held at a Saturday, 5:13 PM in New York, with Codex out of quota until 7:29 AM
 * on Sunday, so what the panels say reads the same on any machine, on any day.
 */

test.use({ timezoneId: "America/New_York", locale: "en-US" });

const NOW = new Date("2026-11-07T22:13:00Z");
const CODEX_BACK = new Date(NOW.getTime() + (14 * 60 + 16) * 60_000);  // Sunday 7:29 AM
const QUOTA = {
  CODEX: { observedAt: NOW.toISOString(), retryAfter: CODEX_BACK.toISOString(), message: "You've hit your usage limit." },
};
const MB = 1024 ** 2;

/* 003's last gate: all three of its commands, in 6m 25s. No other project has run one. */
const passed = (command, seconds) => ({ command, state: "passed", seconds, usual: seconds });
const GATE = {
  ran: true, quick: false, passed: true, check: "", seconds: 385, slowSeconds: 120,
  commands: [passed("npm run build", 12), passed("npm test", 41), passed("npm run test:e2e", 332)],
};
const NO_GATE = { ran: false, quick: false, passed: false, check: "", seconds: 0, slowSeconds: 120, commands: [] };

/*
 * 001 passed its gate and waits for Codex to review it, 002 is next in the queue, and 003 finished,
 * was checked and reviewed, and needs you, with three things the gate can't prove for you to rule
 * on. Every state is set here, because the specs before this one leave the fixture changed.
 */
function fixture(jobs) {
  Object.assign(jobs.find(j => j.id === "001"), {
    phase: "SUSPENDED", phaseLabel: "waiting for quota", percent: 80, queuePosition: 0, running: false,
    assignedTo: "CLAUDE", sessions: 6, reviewState: "findings", blockingFindings: 2,
    lastError: "waiting to be reviewed: RATE_LIMITED",
    blockedReason: "CODEX reviews this, and is out of quota until later",
    reviewBlockedReason: "CODEX reviews this, and is out of quota until later",
    shelvedBranch: "", repoMissing: false,
  });
  Object.assign(jobs.find(j => j.id === "002"),
    { phase: "NEW", queuePosition: 1, blockedReason: "", running: false, shelvedBranch: "", repoMissing: false });
  Object.assign(jobs.find(j => j.id === "003"), {
    phase: "DONE", percent: 100, queuePosition: 0, blockedReason: "", lastError: "", running: false,
    shelvedBranch: "", repoMissing: false, assignedTo: "CLAUDE",
    reviewState: "clean", blockingFindings: 0, securityState: "clean", securitySummary: "", notVerifiedCount: 3,
    workedSeconds: 4 * 3600 + 43 * 60, sessions: 19, tokensSpent: 20_600_000, commits: 60,
    repairRounds: 0, maxRepairRounds: 4, diskBytes: 53 * MB, gateSeconds: 385,
    planSummary: "Built the lantern editor and the share page.", remainingSteps: 0, nextStep: "",
  });
}

/* The board above at that moment, with the pauses in `notes`, after `change` has altered it. */
async function board(page, { notes = {}, change = () => {} } = {}) {
  await page.clock.setFixedTime(NOW);
  await page.route("**/api/auto", (route) => route.fulfill({ json: { available: true, autoRun: false } }));
  await page.route("**/api/quota", (route) => route.fulfill({ json: QUOTA }));
  await page.route("**/api/pauses", (route) => route.fulfill({ json: notes }));
  await page.route((url) => url.pathname === "/api/gate", (route) => {
    const id = new URL(route.request().url()).searchParams.get("id");
    return route.fulfill({ json: id === "003" ? GATE : NO_GATE });
  });
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    fixture(jobs);
    change(jobs);
    await route.fulfill({ response, json: jobs });
  });
  await page.goto("/");
  await expect(page.locator("#row-003")).toBeVisible();
}

/* A project's panel, opened by its line, once the extra has been through it. */
const panel = async (page, id) => {
  await page.locator(`#row-${id}`).click();
  const detail = page.locator(`#detail-${id}`);
  await expect(detail).toBeVisible();
  await expect(detail).toHaveClass(/\brp\b/);
  return detail;
};

/* The counts a panel shows, in the order they read, with how far down the page each one is. */
const counts = (detail) => detail.locator(".stats > .stat").evaluateAll(stats => stats
  .filter(s => s.getClientRects().length > 0)
  .map(s => ({ label: s.querySelector("span").textContent, top: Math.round(s.getBoundingClientRect().top) })));

test("a project that needs you lists its steps top to bottom, at your decision", async ({ page }) => {
  await board(page);
  const detail = await panel(page, "003");
  await expect(detail.locator(":scope > .steps")).toBeHidden();
  const list = detail.locator(".rpsteps");
  await expect(list.locator("h3")).toHaveText("What’s left, in order");
  await expect(list.locator("li .rpname"))
    .toHaveText(["Built", "Gate passed", "Security checked", "Reviewed", "Your decision"]);
  const here = list.locator('li[aria-current="step"]');
  await expect(here.locator(".rpname")).toHaveText("Your decision");
  await expect(here.locator(".rpsub")).toHaveText("your call, with 3 thing(s) the gate can’t prove for you to rule on");
  // The gate's step says what the last gate ran, once the open panel has it.
  await expect(list.locator("li", { hasText: "Gate passed" }).locator(".rpsub"))
    .toHaveText("the last gate ran all 3 commands in 6m 25s");
});

test("a security check that never ran is not said to have passed", async ({ page }) => {
  await board(page, { change: (jobs) => Object.assign(jobs.find(j => j.id === "003"), { securityState: "none" }) });
  const detail = await panel(page, "003");
  await expect(detail.locator(".rpsteps li").nth(2).locator(".rpname")).toHaveText("Security");
});

test("the counts are three to a row, worked, sessions and tokens first", async ({ page }) => {
  await board(page);
  const shown = await counts(await panel(page, "003"));
  expect(shown.slice(0, 3).map(c => c.label)).toEqual(["worked", "sessions", "tokens"]);
  expect(new Set(shown.slice(0, 3).map(c => c.top)).size).toBe(1);
  expect(shown[3].top).toBeGreaterThan(shown[2].top);
});

test("the builder's handoff is a quote said to be its, with no count of steps once none are left", async ({ page }) => {
  await board(page);
  const detail = await panel(page, "003");
  const plan = detail.locator(".plan");
  await expect(plan).toHaveAttribute("data-rpby", "The builder’s handoff, session 19");
  await expect(plan.locator(".plan-sum")).toHaveText("Built the lantern editor and the share page.");
  await expect(plan.locator(".plan-head")).toBeHidden();
});

test("a handoff with steps left still counts them, under its quote", async ({ page }) => {
  await board(page, {
    change: (jobs) => Object.assign(jobs.find(j => j.id === "003"), { remainingSteps: 2, nextStep: "the share page" }),
  });
  const plan = (await panel(page, "003")).locator(".plan");
  await expect(plan.locator(".plan-head")).toHaveText("2 step(s) remaining · expects to be finished");
  await expect(plan.locator(".plan-next")).toHaveText("next: the share page");
  const quote = await plan.locator(".plan-sum").boundingBox();
  expect(quote.y).toBeLessThan((await plan.locator(".plan-head").boundingBox()).y);
});

test("its buttons are one row, without the headings over them, and its vendor is named as the table names it", async ({ page }) => {
  await board(page);
  const detail = await panel(page, "003");
  await expect(detail.locator(".grp-h:visible")).toHaveCount(0);
  // Read it to me is in the Look group and Review Again in Check, and they share a line.
  const look = await detail.locator(".groups .readaloud").boundingBox();
  const check = await detail.locator(".groups .xreview").boundingBox();
  expect(Math.abs(look.y - check.y)).toBeLessThan(2);
  expect(check.x).toBeGreaterThan(look.x + look.width - 1);
  await expect(detail.locator(".dhead .id")).toHaveText(/ · 003 · Claude$/);
});

test("beside the table, the panel says its phase once, in its card", async ({ page }) => {
  await board(page);
  const detail = await panel(page, "003");
  await expect(detail.locator(".dhead .tag")).toBeHidden();
  await expect(detail.locator(".phaseline")).toBeHidden();
  await expect(detail.locator(".move-label")).toHaveText("Your move");
});

test("a waiting project keeps the waiting card's own list", async ({ page }) => {
  await board(page);
  const detail = await panel(page, "001");
  await expect(detail).toHaveClass(/\bwst\b/);
  await expect(detail.locator(".rpsteps")).toBeHidden();
  await expect(detail.locator(".wsteps")).toBeVisible();
  await expect(detail.locator(".wtitle")).toBeVisible();
});

test("a paused project keeps the paused card, with the page's label still hidden", async ({ page }) => {
  await board(page, { notes: { "002": { reason: "Saving quota", at: NOW.toISOString() } } });
  const detail = await panel(page, "002");
  await expect(detail).toHaveClass(/\bpzheld\b/);
  await expect(detail.locator(".move-label")).toBeHidden();
  await expect(detail.locator(".pztitle")).toHaveText("Paused by you");
});

test.describe("on a phone", () => {
  test.use({ viewport: { width: 412, height: 915 } });

  test("the panel keeps its phase tag, with the same list and counts", async ({ page }) => {
    await board(page);
    const detail = await panel(page, "003");
    await expect(detail.locator(".dhead .tag")).toBeVisible();
    await expect(detail.locator(".rpsteps li")).toHaveCount(5);
    const shown = await counts(detail);
    expect(new Set(shown.slice(0, 3).map(c => c.top)).size).toBe(1);
    expect(shown[3].top).toBeGreaterThan(shown[2].top);
  });
});
