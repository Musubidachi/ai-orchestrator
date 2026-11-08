const { test, expect } = require("@playwright/test");

/**
 * The next 24 hours (ui/extras/nextday.js): one strip above the table, in place of the quota
 * banners, saying when each vendor is back, what can start then, and what you will have to do.
 *
 * The page's clock is held at a Saturday, 5:13 PM in New York, so the hours along the top and every
 * time on the strip read the same on any machine, on any day.
 */

test.use({ timezoneId: "America/New_York", locale: "en-US" });

const NOW = new Date("2026-11-07T22:13:00Z");
const later = (hours, minutes) => new Date(NOW.getTime() + (hours * 60 + minutes) * 60_000);
const CODEX_BACK = later(14, 16);        // Sunday 7:29 AM
const ANTIGRAVITY_BACK = later(15, 38);  // Sunday 8:51 AM

const CLOSED = {
  CODEX: { observedAt: NOW.toISOString(), retryAfter: CODEX_BACK.toISOString(), message: "You've hit your usage limit." },
  ANTIGRAVITY: { observedAt: NOW.toISOString(), retryAfter: ANTIGRAVITY_BACK.toISOString(), message: "Individual quota reached." },
};

/* The fixture's board at that moment, after `change` has altered its projects. */
async function board(page, change, { autoRun = true, quota = CLOSED } = {}) {
  await page.clock.setFixedTime(NOW);
  await page.route("**/api/auto", (route) => route.fulfill({ json: { available: true, autoRun } }));
  await page.route("**/api/quota", (route) => route.fulfill({ json: quota }));
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    change(jobs);
    await route.fulfill({ response, json: jobs });
  });
  await page.goto("/");
  await expect(page.locator("#job-001")).toBeVisible();
}

/* 001 passed its gate and waits for a second model. Claude built it, so Codex, back first, reviews it. */
const reviewWait = (jobs) => Object.assign(jobs.find(j => j.id === "001"), {
  title: "Lighthouse Keeper", phase: "SUSPENDED", phaseLabel: "waiting for quota", queuePosition: 0,
  assignedTo: "CLAUDE", lastError: "waiting to be reviewed: RATE_LIMITED",
  blockedReason: "ANTIGRAVITY is out of quota until later",
  reviewBlockedReason: "CODEX reviews this, and is out of quota until later",
  retryAfter: ANTIGRAVITY_BACK.toISOString(),
});

/* 003 finished and reviewed, so it needs you. Set here: an earlier test leaves the fixture's accepted. */
const finished = (jobs) => Object.assign(jobs.find(j => j.id === "003"),
  { phase: "DONE", reviewState: "clean", blockingFindings: 0 });

const caption = (strip, vendor) => strip.locator(`.wnrow[data-vendor="${vendor}"] .wncap`);

test("the strip says when each vendor is back, and what can start then", async ({ page }) => {
  await board(page, (jobs) => { reviewWait(jobs); finished(jobs); });
  const strip = page.locator(".wnd24");

  await expect(strip.locator("h2")).toHaveText("Next 24 hours");
  await expect(strip.locator(".wnsay")).toHaveText("Codex and Antigravity are out of quota. "
    + "The queue starts Lighthouse Keeper’s cross-review by itself at 7:29 AM.");
  await expect(strip.locator(".wnnow")).toHaveText("Now 5:13 PM");
  await expect(strip.locator(".wnaxis span")).toHaveText(["9 PM", "Sun 12 AM", "3 AM", "6 AM", "9 AM", "Noon", "3 PM"]);

  await expect(caption(strip, "CODEX")).toHaveText("Out of quota until 7:29 AM, then Lighthouse Keeper’s cross-review can run");
  await expect(caption(strip, "ANTIGRAVITY")).toHaveText("Out of quota until 8:51 AM");
  // Claude is known from what it has spent, which the page loads beside the quota.
  await expect(caption(strip, "CLAUDE")).toHaveText("Open", { timeout: 10_000 });
  await expect(strip.locator(".wnyou .wncap")).toHaveText("1 project needs you now");

  // 14h16m of the 24: the hold ends, and the review is marked, at the same place.
  await expect(strip.locator('.wnrow[data-vendor="CODEX"] .wnshut')).toHaveAttribute("style", "width:59.4%");
  await expect(strip.locator('.wnrow[data-vendor="CODEX"] .wnmark')).toHaveAttribute("style", "left:59.4%");
  await expect(strip.locator('.wnrow[data-vendor="ANTIGRAVITY"] .wnmark')).toHaveCount(0);
});

test("with the queue off, the strip says what you will have to press, and when", async ({ page }) => {
  await board(page, reviewWait, { autoRun: false });
  const strip = page.locator(".wnd24");

  await expect(strip.locator(".wnsay")).toHaveText("Codex and Antigravity are out of quota. The queue is off, "
    + "so nothing starts on its own: Lighthouse Keeper’s cross-review waits for your Resume Now after 7:29 AM.");
  await expect(strip.locator(".wnyou .wncap")).toContainText("From 7:29 AM: press Resume Now on Lighthouse Keeper");
  await expect(strip.locator(".wnyou .wnmark")).toHaveAttribute("style", "left:59.4%");
});

test("the vendor banners give way to the strip, and Try Now is under its messages", async ({ page }) => {
  await board(page, reviewWait);
  const strip = page.locator(".wnd24");
  await expect(strip).toBeVisible();

  await expect(page.locator("#quotaBar")).toHaveClass(/\bwnd\b/);
  await expect(page.locator("#quotaBar .retry")).toHaveCount(2);
  await expect(page.locator("#quotaBar .quota").first()).toBeHidden();

  const messages = strip.locator(".wnmsgs");
  await expect(messages.locator("summary")).toHaveText("Vendor messages (2)");
  await messages.locator("summary").click();
  await expect(messages).toContainText("Codex: “You've hit your usage limit.” Forge expects it back 7:29 AM.");
  await expect(messages.locator(".wnretry")).toHaveText(["Try Codex Now", "Try Antigravity Now"]);
});

test("with every vendor open, a project's own retry time is still on the strip", async ({ page }) => {
  // Suspended by Claude's limit, which the ledger has since let go: what is left is the time it stored.
  await board(page, (jobs) => Object.assign(jobs.find(j => j.id === "001"), {
    title: "Lighthouse Keeper", phase: "SUSPENDED", queuePosition: 0, assignedTo: "CLAUDE",
    blockedReason: "", lastError: "RATE_LIMITED", retryAfter: later(3, 0).toISOString(),
  }), { quota: {} });
  const strip = page.locator(".wnd24");

  await expect(strip.locator(".wnsay")).toHaveText("The queue starts Lighthouse Keeper by itself at 8:13 PM.");
  await expect(caption(strip, "CLAUDE")).toHaveText("Open · Lighthouse Keeper can resume from 8:13 PM", { timeout: 10_000 });
  await expect(strip.locator(".wnmsgs")).toBeHidden();
});

test("with every vendor open and nothing due, there is no strip and the bar is left alone", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator("#job-001")).toBeVisible();
  // Once the page has said which vendors are closed, the strip has had its chance to appear.
  await page.evaluate(() => new Promise(done =>
    document.addEventListener("forge:quota", () => done(), { once: true })));

  await expect(page.locator(".wnd24")).toHaveCount(0);
  await expect(page.locator("#quotaBar")).not.toHaveClass(/\bwnd\b/);
});
