const { test, expect } = require("@playwright/test");

/**
 * The last gate (ui/extras/gate.js): quick or full, passed or failed, and what it did with each
 * command, under the open project's stats.
 *
 * /api/gate is answered here with each kind of gate, so these are about what the page makes of it.
 * Which part each command gets is worked out on the server and tested there (LastGateTest).
 */

const BUILD = "npm run build";
const TEST = "npm test";
const E2E = "npx playwright install chromium && npm run test:e2e";

const passed = (command, seconds, usual) => ({ command, state: "passed", seconds, usual });

const QUICK_PASSED = {
  ran: true, quick: true, passed: true, check: "", seconds: 2, slowSeconds: 120,
  commands: [passed(BUILD, 1, 1), passed(TEST, 1, 1),
    { command: E2E, state: "waits", seconds: 390, usual: 380 }],
};
const FULL_FAILED = {
  ran: true, quick: false, passed: false, check: "", seconds: 4, slowSeconds: 120,
  commands: [passed(BUILD, 1, 1), { command: TEST, state: "failed", seconds: 3, usual: 2 },
    { command: E2E, state: "unreached", seconds: 370, usual: 375 }],
};
const CHECK_FAILED = {
  ran: true, quick: false, passed: false, check: "run file: run.bat", seconds: 374, slowSeconds: 120,
  commands: [passed(BUILD, 1, 1), passed(TEST, 3, 3), passed(E2E, 370, 375)],
};
const NO_GATE = { ran: false, quick: false, passed: false, check: "", seconds: 0, slowSeconds: 120, commands: [] };

/* One noted gate (status.gate.PastGate). Each quick one here left the browser suite, 380s usually. */
const past = (session, quick, passed, seconds, failed = "") =>
  ({ session, quick, passed, failed, seconds, spared: quick ? 380 : 0, at: "2026-11-08T10:00:00Z" });
const FOUR_GATES = {
  ...QUICK_PASSED, after: 4,
  history: [past(1, false, true, 385), past(2, true, true, 2), past(3, true, false, 1, TEST), past(4, true, true, 2)],
};

/* 001 is being built, 002 has not started, and 003 finished. Every state is set here, because the
   specs before this one leave the fixture changed. 001's poll still has the page's "last gate" sum. */
function fixture(jobs) {
  Object.assign(jobs.find(j => j.id === "001"), {
    phase: "CONTINUING", phaseLabel: "continuing", running: false, queuePosition: 0, blockedReason: "",
    lastError: "", assignedTo: "CLAUDE", shelvedBranch: "", repoMissing: false, gateSeconds: 392,
  });
  Object.assign(jobs.find(j => j.id === "002"),
    { phase: "NEW", queuePosition: 1, blockedReason: "", running: false });
  Object.assign(jobs.find(j => j.id === "003"),
    { phase: "DONE", reviewState: "clean", blockingFindings: 0, queuePosition: 0, blockedReason: "", running: false });
}

/* The board, with 001's last gate answered as `gate` and every other project's as none. */
async function board(page, gate) {
  const state = { asked: [] };
  await page.route("**/api/auto", (route) => route.fulfill({ json: { available: true, autoRun: true } }));
  await page.route("**/api/quota", (route) => route.fulfill({ json: {} }));
  await page.route("**/api/pauses", (route) => route.fulfill({ json: {} }));
  await page.route((url) => url.pathname === "/api/gate", (route) => {
    const id = new URL(route.request().url()).searchParams.get("id");
    state.asked.push(id);
    return route.fulfill({ json: id === "001" ? gate : NO_GATE });
  });
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    fixture(jobs);
    await route.fulfill({ response, json: jobs });
  });
  await page.goto("/");
  await expect(page.locator("#row-003")).toBeVisible();
  return state;
}

const panel = async (page, id) => {
  await page.locator(`#row-${id}`).click();
  const detail = page.locator(`#detail-${id}`);
  await expect(detail).toBeVisible();
  return detail;
};

const lastGateStat = (detail) => detail.locator(".stats > .stat", { hasText: "last gate" });

test("a quick gate says so, and what waits for the full one", async ({ page }) => {
  const state = await board(page, QUICK_PASSED);
  const detail = await panel(page, "001");
  const gate = detail.locator(".gtsec");

  await expect(gate).toBeVisible();
  await expect(gate.locator("h3")).toHaveText("Last gate");
  await expect(gate.locator(".gtpill")).toHaveText(["Quick gate", "Passed"]);
  await expect(gate.locator(".gtcount")).toHaveText("2 of 3 commands · 2s");
  await expect(gate.locator(".gtsay")).toHaveText(
    "1 command waits for the full gate, which runs when the builder reports the work complete.");
  await expect(gate.locator(".gtcmds code")).toHaveText([BUILD, TEST, E2E]);
  await expect(gate.locator(".gtpart")).toHaveText(["Ran, passed", "Ran, passed", "Waits for the full gate"]);
  await expect(gate.locator(".gttime")).toHaveText(["1s", "1s", "usually 6m 20s"]);
  // The line a quick gate stops at is drawn, because one command usually takes longer.
  await expect(gate.locator(".gtscale")).toHaveText("2 min");
  await expect(gate.locator(".gtcmds li").nth(2)).toHaveClass(/\bgt-slow\b/);
  await expect(gate.locator(".gtnote")).toContainText("Also left for the full gate: the screenshots");
  await expect(gate.locator(".gtfoot")).toHaveText(/^A quick gate never passes a version\./);
  // The page's sum added the waiting command's old time to the new ones; while this shows, it is hidden.
  await expect(lastGateStat(detail)).toBeHidden();
  // Only a panel that has been open is asked about.
  expect(state.asked).toContain("001");
  expect(state.asked).not.toContain("002");
});

test("a full gate that failed shows the command it stopped at, and the ones it never reached", async ({ page }) => {
  await board(page, FULL_FAILED);
  const gate = (await panel(page, "001")).locator(".gtsec");

  await expect(gate.locator(".gtpill")).toHaveText(["Full gate", "Failed"]);
  await expect(gate.locator(".gtcount")).toHaveText("2 of 3 commands · 4s");
  await expect(gate.locator(".gtpart")).toHaveText(["Ran, passed", "Failed", "Not reached"]);
  await expect(gate.locator(".gttime")).toHaveText(["1s", "3s", "usually 6m 15s"]);
  await expect(gate.locator(".gtcmds li").nth(1)).toHaveClass(/\bgt-failed\b/);
  // Nothing was left for later, so nothing says so.
  await expect(gate.locator(".gtsay")).toHaveCount(0);
  await expect(gate.locator(".gtnote")).toHaveCount(0);
  await expect(gate.locator(".gtfoot")).toHaveCount(0);
});

test("a gate whose commands all passed says which check it stopped at", async ({ page }) => {
  await board(page, CHECK_FAILED);
  const gate = (await panel(page, "001")).locator(".gtsec");

  await expect(gate.locator(".gtpill")).toHaveText(["Full gate", "Failed"]);
  await expect(gate.locator(".gtcount")).toHaveText("3 of 3 commands · 6m 14s");
  await expect(gate.locator(".gtsay")).toHaveText("Every command passed; it stopped at a check: run file: run.bat.");
  await expect(gate.locator(".gtsay b")).toHaveText("run file: run.bat");
  await expect(gate.locator(".gtpart")).toHaveText(["Ran, passed", "Ran, passed", "Ran, passed"]);
});

test("the gate says which session it followed, and lists the gates before it", async ({ page }) => {
  await board(page, FOUR_GATES);
  const gate = (await panel(page, "001")).locator(".gtsec");

  await expect(gate.locator(".gtcount")).toHaveText("after session 4 · 2 of 3 commands · 2s");
  const list = gate.locator(".gthist");
  await expect(list.locator("h3")).toHaveText("Gates on this version");
  // Three quick gates, each leaving a suite that usually takes 380 seconds: 19 minutes.
  await expect(list.locator(".gtspared")).toHaveText("About 19 minutes not spent waiting");
  await expect(list.locator(".gtmore")).toHaveCount(0);
  await expect(list.locator(".gtpwhen")).toHaveText(
    ["After session 1", "After session 2", "After session 3", "After session 4"]);
  await expect(list.locator(".gtpkind")).toHaveText(
    ["Fullpassed6m 25s", "Quickpassed2s", "Quickfailed1s", "Quickpassed2s"]);
  await expect(list.locator(".gtpnote")).toHaveText(["Passed", "Passed", "npm test failed", "Latest"]);
  await expect(list.locator("li").nth(2)).toHaveClass(/\bgtp-fail\b/);
  await expect(list.locator("li").nth(3)).toHaveClass(/\bgtp-now\b/);
  await expect(list.locator('li[aria-current="true"]')).toHaveCount(1);
  // The list sits between what a quick gate leaves and the footnote about it.
  await expect(gate.locator(":scope > .gtnote + .gthist + .gtfoot")).toHaveCount(1);
});

test("a long list shows the latest gates, and a single gate has none", async ({ page }) => {
  // Eleven full gates that passed, and the one this section is about, which failed at npm test.
  const twelve = Array.from({ length: 12 }, (_, i) => (i < 11 ? past(i + 1, false, true, 300) : past(12, false, false, 4, TEST)));
  await board(page, { ...FULL_FAILED, after: 12, history: twelve });
  let gate = (await panel(page, "001")).locator(".gtsec");

  await expect(gate.locator(".gtpast li")).toHaveCount(8);
  await expect(gate.locator(".gtmore")).toHaveText("the last 8 of 12");
  await expect(gate.locator(".gtpwhen").first()).toHaveText("After session 5");
  // Only full gates: nothing was left for later, so no time is said to be spared.
  await expect(gate.locator(".gtspared")).toHaveCount(0);

  await page.unrouteAll({ behavior: "ignoreErrors" });
  await board(page, { ...FULL_FAILED, after: 1, history: [past(1, false, false, 4, TEST)] });
  gate = (await panel(page, "001")).locator(".gtsec");
  await expect(gate.locator(".gtcount")).toHaveText("after session 1 · 2 of 3 commands · 4s");
  await expect(gate.locator(".gthist")).toHaveCount(0);
});

test("a project with no gate yet has no section, and keeps the page's own stat", async ({ page }) => {
  const state = await board(page, NO_GATE);
  const detail = await panel(page, "001");

  await expect.poll(() => state.asked.includes("001")).toBe(true);
  // Something absent cannot be waited for: give the answer time to arrive and be shown, if it were.
  await page.waitForTimeout(300);
  await expect(lastGateStat(detail)).toBeVisible();
  await expect(detail.locator(".gtsec")).toHaveCount(0);
});
