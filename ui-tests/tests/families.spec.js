const { test, expect } = require("@playwright/test");

/**
 * One row per family (ui/extras/families.js): a project and its versions on one line of the table,
 * the versions drawn as a chain, and opened to show each version's own line.
 *
 * The page's clock is held at a Saturday, 5:13 PM in New York, so the time on the family's row
 * reads the same on any machine, on any day.
 */

test.use({ timezoneId: "America/New_York", locale: "en-US" });

const NOW = new Date("2026-11-07T22:13:00Z");
const CODEX_BACK = new Date(NOW.getTime() + (14 * 60 + 16) * 60_000);  // Sunday 7:29 AM
const QUOTA = {
  CODEX: { observedAt: NOW.toISOString(), retryAfter: CODEX_BACK.toISOString(), message: "You've hit your usage limit." },
};
const GB = 1024 ** 3;

/*
 * Lighthouse Keeper and five versions of it: two accepted, one built and waiting for Codex to review
 * it, and three waiting their turn behind that one. 003 finished and needs you; 002 is next in the
 * queue. Every state is set here, because the specs before this one leave the fixture changed.
 */
function family(jobs) {
  jobs.forEach(j => Object.assign(j, { workedSeconds: 0, tokensSpent: 0, diskBytes: 0 }));
  const base = Object.assign(jobs.find(j => j.id === "001"), {
    title: "Lighthouse Keeper", phase: "ACCEPTED", queuePosition: 0, blockedReason: "", lastError: "",
    assignedTo: "CLAUDE", running: false, workedSeconds: 2 * 3600, tokensSpent: 20_000_000, diskBytes: 2 * GB,
  });
  Object.assign(jobs.find(j => j.id === "002"),
    { phase: "NEW", queuePosition: 1, blockedReason: "", running: false });
  Object.assign(jobs.find(j => j.id === "003"),
    { phase: "DONE", reviewState: "clean", blockingFindings: 0, queuePosition: 0, blockedReason: "", running: false });
  const version = (n, state) => jobs.push({
    ...base, id: `001.${n}`, title: `Lighthouse Keeper, part ${n}`, parentId: n === 1 ? "001" : `001.${n - 1}`,
    workedSeconds: 0, tokensSpent: 0, diskBytes: 0, ...state,
  });
  version(1, { phase: "ACCEPTED", workedSeconds: 5400, tokensSpent: 9_000_000, diskBytes: 1.5 * GB });
  version(2, {
    phase: "SUSPENDED", lastError: "waiting to be reviewed: RATE_LIMITED",
    blockedReason: "CODEX reviews this, and is out of quota until later",
    reviewBlockedReason: "CODEX reviews this, and is out of quota until later",
    workedSeconds: 2700, tokensSpent: 2_400_000, diskBytes: GB,
  });
  for (const n of [3, 4, 5]) {
    version(n, { phase: "NEW", blockedReason: `waiting for 001.${n - 1} to be accepted first` });
  }
}

/* The board above at that moment, after `change` has altered it further. */
async function board(page, change = () => {}) {
  await page.clock.setFixedTime(NOW);
  await page.route("**/api/quota", (route) => route.fulfill({ json: QUOTA }));
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    family(jobs);
    change(jobs);
    await route.fulfill({ response, json: jobs });
  });
  await page.goto("/");
  await expect(page.locator("#row-003")).toBeVisible();
}

const familyRow = (page) => page.locator('.fmrow[data-family="001"]');
// An id with a dot in it is not a CSS id selector.
const row = (page, id) => page.locator(`[id="row-${id}"]`);
const heading = (page, text) => page.locator(".grouphead", { hasText: text });

test("a project and its versions are one row, the versions drawn as a chain", async ({ page }) => {
  await board(page);
  const fam = familyRow(page);

  await expect(fam.locator(".ttl")).toHaveText("Lighthouse Keeper");
  await expect(fam.locator(".sub")).toHaveText("001 · Claude · 6 versions");
  // The version most in need of saying speaks for the family: the one waiting for its review.
  await expect(fam.locator(".pill")).toHaveText("Waiting");
  await expect(fam.locator(".fmwhat")).toHaveText("Waiting for cross-review");
  await expect(fam.locator(".fmwhen")).toHaveText("Codex back 7:29 AM");
  await expect(fam.locator(".fmchip")).toHaveText(["001", "001.1", "001.2 review", "001.3 next", "+2 more"]);
  await expect(fam.locator(".fmchip.fmlead")).toHaveText("001.2 review");
  // What the whole family has cost.
  await expect(fam.locator(".worked")).toHaveText("4h 15m");
  await expect(fam.locator(".tokens")).toHaveText("31.4M");

  // Its versions' own lines wait under it, and it stands where the first of them stood.
  for (const id of ["001", "001.1", "001.2", "001.3"]) await expect(row(page, id)).toBeHidden();
  await expect(page.locator('.foldline[data-family="001"]')).toBeHidden();
  const above = await row(page, "003").boundingBox();
  const mine = await fam.boundingBox();
  const below = await row(page, "002").boundingBox();
  expect(mine.y).toBeGreaterThan(above.y);
  expect(below.y).toBeGreaterThan(mine.y);

  // A heading whose lines all went under the family's row has nothing left to head.
  await expect(heading(page, "Waiting for review")).toBeVisible();
  await expect(heading(page, "Waiting their turn")).toBeHidden();
  await expect(heading(page, /^Accepted/)).toBeHidden();
});

test("opening the family shows each version's own line, and closed, its row stands for them", async ({ page }) => {
  await board(page);
  const fam = familyRow(page);
  // With nothing chosen yet, the project that needs you is the one open.
  await expect(row(page, "003")).toHaveAttribute("aria-current", "true");

  await fam.click();
  await expect(fam).toHaveAttribute("aria-expanded", "true");
  for (const id of ["001", "001.1", "001.2", "001.3"]) await expect(row(page, id)).toBeVisible();
  // The page's own fold still holds the versions waiting behind the next one.
  await expect(row(page, "001.4")).toBeHidden();
  await expect(page.locator('.foldline[data-family="001"]'))
    .toHaveText(/^and 2 more Lighthouse Keeper versions after it/);
  // Opening it shows the version its row spoke for.
  await expect(page.locator('[id="job-001.2"] .detail')).toBeVisible();
  await expect(row(page, "001.2")).toHaveAttribute("aria-current", "true");
  await expect(fam).toHaveAttribute("aria-current", "false");

  // Under the family's row, and before whatever follows it.
  const top = (await fam.boundingBox()).y;
  const first = (await row(page, "001.2").boundingBox()).y;
  const last = (await row(page, "001.1").boundingBox()).y;
  const next = (await row(page, "002").boundingBox()).y;
  expect(first).toBeGreaterThan(top);
  expect(last).toBeGreaterThan(first);
  expect(next).toBeGreaterThan(last);

  // Closed again, its versions are hidden, and its row is marked for the one still open beside it.
  await fam.click();
  await expect(fam).toHaveAttribute("aria-expanded", "false");
  await expect(row(page, "001.2")).toBeHidden();
  await expect(page.locator('[id="job-001.2"] .detail')).toBeVisible();
  await expect(fam).toHaveAttribute("aria-current", "true");

  // Choosing another project takes the mark with it.
  await row(page, "002").click();
  await expect(fam).toHaveAttribute("aria-current", "false");
  await expect(row(page, "002")).toHaveAttribute("aria-current", "true");
});

test("the tiles are the table's only counts, with the disk every project takes", async ({ page }) => {
  await board(page);

  await expect(page.locator("#tiles")).toBeVisible();
  await expect(page.locator("#jobsSummary")).toBeHidden();
  await expect(heading(page, "Needs you").locator(".gn")).toBeHidden();
  // Every version counts, as every version has its own line.
  await expect(page.locator('#tiles [data-key="all"] .n')).toHaveText("8");
  await expect(page.locator('#tiles [data-key="you"] .n')).toHaveText("1");
  await expect(page.locator('#tiles [data-key="waiting"] .n')).toHaveText("4");
  await expect(page.locator('#tiles [data-key="accepted"] .n')).toHaveText("2");
  await expect(page.locator("#tiles .fmdisk")).toHaveText("4.5 GB on disk");
  await expect(page.locator('#tiles [data-key="away"] .fmsub')).toBeHidden();
});

test("the not-here tile says how many of those projects wait for your decision", async ({ page }) => {
  // 003 finished, and was shelved on GitHub before anyone ruled on it.
  await board(page, (jobs) => Object.assign(jobs.find(j => j.id === "003"), { shelvedBranch: "shelf/003" }));
  const away = page.locator('#tiles [data-key="away"]');

  await expect(away.locator(".n")).toHaveText("1");
  await expect(away.locator(".fmsub")).toBeVisible();
  await expect(away.locator(".fmsub")).toHaveText("1 awaits your decision");
});

test("a tile filters the wide table too, keeping a family whose versions answer to it", async ({ page }) => {
  await board(page);
  await page.locator('#tiles [data-key="waiting"]').click();

  await expect(familyRow(page)).toBeVisible();
  await expect(row(page, "002")).toBeHidden();
  await expect(row(page, "003")).toBeHidden();
  await expect(heading(page, "Needs you")).toBeHidden();
  // The project that was open is filtered away, so the first one the filter leaves opens instead.
  await expect(page.locator('[id="job-001.2"] .detail')).toBeVisible();
  await expect(familyRow(page)).toHaveAttribute("aria-current", "true");

  await page.locator('#tiles [data-key="all"]').click();
  await expect(row(page, "002")).toBeVisible();
});

test.describe("on a phone", () => {
  test.use({ viewport: { width: 412, height: 915 } });

  test("the ranked list keeps every version's own line, with no family rows", async ({ page }) => {
    await board(page);

    await expect(familyRow(page)).toBeHidden();
    for (const id of ["001", "001.1", "001.2", "001.3"]) await expect(row(page, id)).toBeVisible();
    await expect(page.locator('.foldline[data-family="001"]')).toBeVisible();
  });
});
