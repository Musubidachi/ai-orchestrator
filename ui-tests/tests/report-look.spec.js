const { test, expect } = require("@playwright/test");

/**
 * The report's look (forge-report, report/look/look.js): a header that says the phase once, the
 * review's notice as a banner at the top, a feature with nothing to watch as one line, each gap a
 * card of its own, the checks' times drawn to one scale, and the review bar along the bottom of the
 * window.
 *
 * Read off the pages the fixture generates. 004 is held on a blocking finding and never changes
 * during the suite. 003 is accepted by dashboard.spec.js, so for 003 only what that leaves alone is
 * pinned: its times and which of its features has a recording.
 */

const REPORT_003 = "/runs/003/report.html";
const REPORT_004 = "/runs/004/report.html";

test("the header says the title, the phase once and who built it, above the rail and the page", async ({ page }) => {
  await page.goto(REPORT_004);
  const head = page.locator("header.rl-head");
  await expect(head.locator("h1")).toHaveText("Delta Project");
  await expect(head.locator(".rl-chip")).toHaveText("Needs you");
  await expect(head.locator(".rl-chip")).toHaveClass(/\bPARKED\b/);
  await expect(head.locator(".rl-meta")).toHaveText("004 · built by Claude · 4 sessions");
  // The page's own line under the title said the phase and the builder. It is hidden, not removed.
  await expect(page.locator("main > .sub")).toBeHidden();
  await expect(page.locator("h1")).toHaveCount(1);

  // The rail and the page sit side by side under it.
  expect(await page.evaluate(() => getComputedStyle(document.body).display)).toBe("grid");
  const header = await head.boundingBox();
  const rail = await page.locator(".rg-rail").boundingBox();
  const main = await page.locator("main").boundingBox();
  expect(rail.y).toBeGreaterThanOrEqual(header.y + header.height);
  expect(main.y).toBeGreaterThanOrEqual(header.y + header.height);
  expect(main.x).toBeGreaterThanOrEqual(rail.x + rail.width);
});

test("why it cannot simply be accepted is said at the top, in the review's own words, with a way down to it", async ({ page }) => {
  await page.goto(REPORT_004);
  const banner = page.locator("main > .rl-banner");
  await expect(banner.locator("strong"))
    .toHaveText("Waiting for you: still not fixed after 2 attempts: text around tables comes out of order.");
  await expect(banner.locator(".rl-say span")).toContainText("Send back any finding");
  // The notice itself stays where it was, once, for the bar and the form.
  await expect(page.locator(".decide-blocked")).toHaveCount(1);
  await expect(page.locator(".decide-blocked")).toContainText("Waiting for you");

  await banner.getByRole("link", { name: "Go to your review" }).click();
  await expect(page).toHaveURL(/#your-review$/);
  await expect(page.locator("h2#your-review")).toBeInViewport();
});

test("each gap is a card of its own, and the review bar runs along the bottom of the window", async ({ page }) => {
  await page.goto(REPORT_004);
  await expect(page.locator(".rl-gaps li")).toHaveCount(1);
  await expect(page.locator(".rl-gaps li")).toHaveText("the tables case is untested");

  const bar = page.locator(".rg-bar");
  await expect(bar).toBeVisible();
  const box = await bar.boundingBox();
  const view = page.viewportSize();
  expect(box.x).toBeLessThanOrEqual(1);
  expect(box.width).toBeGreaterThanOrEqual(view.width - 20);
  expect(Math.abs(box.y + box.height - view.height)).toBeLessThan(2);
  // The rail ends above it.
  const rail = await page.locator(".rg-rail").boundingBox();
  expect(rail.y + rail.height).toBeLessThanOrEqual(box.y);
});

test("the checks' times are drawn to one scale in place of the list, which the rail still counts", async ({ page }) => {
  await page.goto(REPORT_003);
  const times = page.locator("h2#how-long-the-checks-take + .rl-times");
  await expect(times).toBeVisible();
  const row = times.locator(".rl-time");
  await expect(row).toHaveCount(1);
  await expect(row.locator("code")).toHaveText("npm test");
  await expect(row.locator(".rl-took")).toHaveText("1m · usually 20s · slower than usual");
  await expect(row).toHaveClass(/\brl-slow\b/);
  // The usual time's tick sits inside the latest time's bar, a third of the way along.
  const bar = await row.locator(".rl-bar").boundingBox();
  const tick = await row.locator(".rl-tick").boundingBox();
  expect(tick.x).toBeGreaterThan(bar.x);
  expect(tick.x).toBeLessThan(bar.x + bar.width / 2);

  // The list it replaces is hidden, not removed, so the rail counts what it always counted.
  await expect(page.locator("h2#how-long-the-checks-take ~ .card.rl-gone")).toHaveCount(1);
  await expect(page.locator("h2#how-long-the-checks-take ~ .card.rl-gone")).toBeHidden();
  await expect(page.locator(".rg-rail a[href='#how-long-the-checks-take'] .rg-hold")).toHaveText("1 slower");
});

test("a feature with nothing to watch is one line, with what its recording waits for beside it", async ({ page }) => {
  await page.goto(REPORT_003);
  const flat = page.locator("#features ~ .feature-item.rl-flat");
  await expect(flat).toHaveCount(1);
  await expect(flat.locator(".decide-text")).toHaveText("Monthly summary");
  const name = await flat.locator(".decide-text").boundingBox();
  const note = await flat.locator(".rl-note").boundingBox();
  expect(note.x).toBeGreaterThan(name.x + name.width);

  // One with a recording keeps it under its name.
  const filmed = page.locator("#features ~ .feature-item", { hasText: "Report export" });
  await expect(filmed).not.toHaveClass(/\brl-flat\b/);
  await expect(filmed.locator("img")).toBeVisible();
});

test.describe("on a phone", () => {
  test.use({ viewport: { width: 412, height: 915 } });

  test("nothing makes the page scroll sideways, and the header keeps its chip", async ({ page }) => {
    await page.goto(REPORT_004);
    await expect(page.locator(".rl-head .rl-chip")).toBeVisible();
    await expect(page.locator(".rg-rail")).toBeHidden();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(412);

    // 003's security findings are a table wider than a phone. It scrolls inside its card, whole.
    await page.goto(REPORT_003);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(412);
    const card = page.locator("h2#security + .card");
    expect(await card.evaluate((el) => el.scrollWidth > el.clientWidth)).toBe(true);
  });
});
