const { test, expect } = require("@playwright/test");

/**
 * The waiting card (ui/extras/status.js): one status and one time for a project that is waiting.
 *
 * A review wait used to be said six ways on its panel, with two different times. These check the
 * card says it once, with the time the queue would actually start it, and that what it hides is
 * still there under "Why it is waiting".
 */

const HOUR = 3600_000;

/* The fixture's board with Codex and Antigravity closed, after `change` has altered its projects. */
async function board(page, change, autoRun = true) {
  const now = Date.now();
  const seen = new Date(now).toISOString();
  const codexBack = new Date(now + 14.5 * HOUR);
  const antigravityBack = new Date(now + 15.9 * HOUR);
  await page.route("**/api/auto", (route) => route.fulfill({ json: { available: true, autoRun } }));
  await page.route("**/api/quota", (route) => route.fulfill({ json: {
    CODEX: { observedAt: seen, retryAfter: codexBack.toISOString(), message: "You've hit your usage limit." },
    ANTIGRAVITY: { observedAt: seen, retryAfter: antigravityBack.toISOString(), message: "Daily quota used up." },
  } }));
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    change(jobs, antigravityBack);
    await route.fulfill({ response, json: jobs });
  });
  await page.goto("/");
  await expect(page.locator("#job-001")).toBeVisible();
}

/* 001 as a version that passed its gate and waits for a second model to review it. The project
   stored Antigravity's time, but Codex is back sooner, and any free reviewer will do. */
const reviewWait = (jobs, antigravityBack) => Object.assign(jobs.find(j => j.id === "001"), {
  phase: "SUSPENDED", phaseLabel: "waiting for quota", percent: 80, queuePosition: 0,
  assignedTo: "CLAUDE", sessions: 6, reviewState: "findings", blockingFindings: 2,
  lastError: "waiting to be reviewed: RATE_LIMITED",
  blockedReason: "ANTIGRAVITY is out of quota until later",
  reviewBlockedReason: "CODEX reviews this, and is out of quota until later",
  retryAfter: antigravityBack.toISOString(),
});

const panel = async (page, id) => {
  await page.locator(`#row-${id}`).click();
  const detail = page.locator(`#detail-${id}`);
  await expect(detail).toBeVisible();
  return detail;
};

test("a review wait says once what it waits for, and the time that counts", async ({ page }) => {
  await board(page, reviewWait);
  const detail = await panel(page, "001");

  await expect(detail).toHaveClass(/\bwst\b/);
  await expect(detail.locator(".wtitle")).toHaveText("Waiting for a second opinion");
  await expect(detail.locator(".wsay")).toHaveText(/^Codex reviews it, and is back .+ \(in 14h (2\d|30)m\)\.$/);
  await expect(detail.locator(".wtodo")).toHaveText("The queue starts the review by itself then.");
  for (const replaced of [".dhead .tag", ".move-label", ".move-head", ".phaseline", ":scope > .steps"]) {
    await expect(detail.locator(replaced)).toBeHidden();
  }

  await expect(detail.locator(".wsteps li")).toHaveCount(5);
  const here = detail.locator('.wsteps li[aria-current="step"]');
  await expect(here).toContainText("Cross-review");
  await expect(here).toContainText("from ");
  await expect(here).toContainText("Codex re-checks the 2 blocking finding(s) from the last review.");

  const why = detail.locator(".wwhy");
  await why.locator("summary").click();
  await expect(why).toContainText("Claude built this version, so it can’t review it.");
  await expect(why).toContainText("so Codex’s");
  await expect(why).toContainText("Forge’s state: SUSPENDED (waiting for quota).");

  // The page refills every panel on each poll, and one someone opened stays open through it.
  await page.evaluate(() => new Promise(done =>
    document.addEventListener("forge:jobs", () => done(), { once: true })));
  await expect(why).toHaveAttribute("open", "");
});

test("with the queue off, the card says the button will need pressing", async ({ page }) => {
  await board(page, reviewWait, false);
  const detail = await panel(page, "001");

  await expect(detail.locator(".wtodo")).toHaveText("Keep the queue moving is off, so press Resume Now then, "
    + "or turn the queue on and the review starts by itself.");
});

test("a version waiting its turn names the one before it", async ({ page }) => {
  await board(page, (jobs) => Object.assign(jobs.find(j => j.id === "002"),
    { queuePosition: 0, blockedReason: "waiting for 001 to be accepted first" }));
  const detail = await panel(page, "002");

  await expect(detail.locator(".wtitle")).toHaveText("Waiting its turn");
  await expect(detail.locator(".wsay")).toHaveText("It can start once 001 is accepted.");
  await expect(detail.locator('.wsteps li[aria-current="step"]')).toContainText("after 001 is accepted");
});

test("a project that is not waiting keeps its own panel", async ({ page }) => {
  // 003 is set here rather than taken from the fixture, which an earlier test leaves accepted.
  await board(page, (jobs, antigravityBack) => {
    reviewWait(jobs, antigravityBack);
    Object.assign(jobs.find(j => j.id === "003"), { phase: "DONE", reviewState: "clean", blockingFindings: 0 });
  });
  // The waiting project has its card, so the extra has been through every panel by now.
  await expect(page.locator("#detail-001")).toHaveClass(/\bwst\b/);
  const detail = await panel(page, "003");

  await expect(detail).not.toHaveClass(/\bwst\b/);
  await expect(detail.locator(".move-label")).toBeVisible();
  await expect(detail.locator(".move-label")).toHaveText("Your move");
  await expect(detail.locator(".wcard")).toHaveCount(0);
});
