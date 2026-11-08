const { test, expect } = require("@playwright/test");

/**
 * Stop with a reason (ui/extras/pause.js): Stop asks why, and the project is held until Resume Now.
 *
 * The page's clock is held at a Saturday, 5:13 PM in New York, so how long a project has been paused
 * reads the same on any machine. Every press that would change something is answered here, so the
 * server only ever hears reads.
 */

test.use({ timezoneId: "America/New_York", locale: "en-US" });

const NOW = new Date("2026-11-07T22:13:00Z");
const HOUR = 3600_000;
const DAY = 24 * HOUR;
const STOPPED = "stopped; the queue leaves it alone until you press Resume Now";
const PAUSED = "paused; the queue leaves it alone until you press Resume Now";

/* 001 is being built, 002 has not started, and 003 finished and was reviewed. Every state is set
   here, because the specs before this one leave the fixture changed. */
function fixture(jobs) {
  Object.assign(jobs.find(j => j.id === "001"), {
    phase: "SCAFFOLDED", phaseLabel: "building", running: true, queuePosition: 0, blockedReason: "",
    lastError: "", assignedTo: "CLAUDE", shelvedBranch: "", repoMissing: false,
  });
  Object.assign(jobs.find(j => j.id === "002"),
    { phase: "NEW", queuePosition: 1, blockedReason: "", running: false });
  Object.assign(jobs.find(j => j.id === "003"),
    { phase: "DONE", reviewState: "clean", blockingFindings: 0, queuePosition: 0, blockedReason: "", running: false });
}

/*
 * The board with the reasons in `notes`, after `change` has altered its projects. A pause sent from
 * the page is kept in the returned state and read back from /api/pauses, as the server would, with
 * the time of a pause already on record kept; a Stop the page sends itself is only counted.
 */
async function board(page, { notes = {}, change = () => {}, quota = {} } = {}) {
  const state = { notes, posted: [], stops: [], answer: STOPPED };
  await page.clock.setFixedTime(NOW);
  await page.route("**/api/auto", (route) => route.fulfill({ json: { available: true, autoRun: true } }));
  await page.route("**/api/quota", (route) => route.fulfill({ json: quota }));
  await page.route("**/api/pauses", (route) => route.fulfill({ json: state.notes }));
  await page.route("**/api/jobs/pause", (route) => {
    const { id, reason } = route.request().postDataJSON();
    state.posted.push({ id, reason });
    const at = state.notes[id] ? state.notes[id].at : NOW.toISOString();
    state.notes = { ...state.notes, [id]: { reason, at } };
    return route.fulfill({ json: { result: state.answer } });
  });
  await page.route("**/api/jobs/stop", (route) => {
    state.stops.push(route.request().postDataJSON());
    return route.fulfill({ json: { result: "stopping" } });
  });
  await page.route("**/api/jobs", async (route) => {
    const response = await route.fetch();
    const jobs = await response.json();
    fixture(jobs);
    change(jobs, state);
    await route.fulfill({ response, json: jobs });
  });
  await page.goto("/");
  await expect(page.locator("#row-003")).toBeVisible();
  return state;
}

const stopped = (jobs) => Object.assign(jobs.find(j => j.id === "001"), { running: false });

const threeDays = () => ({ "001": { reason: "Waiting for the new art.", at: new Date(NOW - 3 * DAY).toISOString() } });

const panel = async (page, id) => {
  await page.locator(`#row-${id}`).click();
  const detail = page.locator(`#detail-${id}`);
  await expect(detail).toBeVisible();
  return detail;
};

test("Stop asks why, and the project stays stopped with the reason on its row", async ({ page }) => {
  // The server holds a project it paused: it is not running once the pause is on record.
  const state = await board(page, { change: (jobs, { notes }) => notes["001"] && stopped(jobs) });
  const detail = await panel(page, "001");
  const stop = detail.locator(".act-btn");
  const dialog = page.locator("dialog.pzdlg");
  await expect(stop).toHaveText("Stop");

  // Cancel leaves it running and sends nothing.
  await stop.click();
  await expect(dialog).toBeVisible();
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toBeHidden();
  expect(state.posted).toEqual([]);

  await stop.click();
  await expect(dialog.locator("h2")).toHaveText("Stop Alpha Project (001)?");
  await expect(dialog.locator("label")).toHaveText("Why are you stopping it?");
  await expect(dialog.locator("textarea")).toHaveValue("");
  await dialog.getByRole("button", { name: "Waiting for art" }).click();
  await expect(dialog.locator("textarea")).toHaveValue("Waiting for art");
  await dialog.getByRole("button", { name: "Stop it" }).click();

  await expect(dialog).toBeHidden();
  await expect(detail.locator(".msg")).toHaveText(STOPPED);
  const row = page.locator("#row-001");
  await expect(row.locator(".pzfor")).toHaveText("Paused by you, just now");
  await expect(row.locator(".pzwhy")).toHaveText("“Waiting for art”");
  await expect(stop).toHaveText("Resume Now");
  expect(state.posted).toEqual([{ id: "001", reason: "Waiting for art" }]);
  // The page's own Stop never heard the click.
  expect(state.stops).toEqual([]);
});

test("a paused project says how long and why, and its reason can be changed", async ({ page }) => {
  const state = await board(page, { notes: threeDays(), change: stopped });
  const row = page.locator("#row-001");

  await expect(row).toHaveClass(/\bpzheld\b/);
  await expect(row.locator(".pzfor")).toHaveText("Paused by you, 3 days");
  await expect(row.locator(".pzwhy")).toHaveText("“Waiting for the new art.”");
  await expect(row.locator(".stage .pzpill")).toHaveText("Paused");
  await expect(row.locator(".stage > .pill")).toBeHidden();

  const detail = await panel(page, "001");
  await expect(detail.locator(".pztitle")).toHaveText("Paused by you");
  await expect(detail.locator(".pzwhen")).toHaveText("Wed, Nov 4 · 3 days ago");
  await expect(detail.locator(".pzquote")).toHaveText("Waiting for the new art.");
  for (const replaced of [".move-label", ".move-head", ".move-sub"]) {
    await expect(detail.locator(replaced)).toBeHidden();
  }
  await expect(detail.locator(".act-btn")).toHaveText("Resume Now");

  const button = detail.locator(".pzbtn");
  await expect(button).toHaveText("Change the reason");
  await button.click();
  const dialog = page.locator("dialog.pzdlg");
  await expect(dialog.locator("h2")).toHaveText("Change the reason for Alpha Project (001)");
  await expect(dialog.locator("textarea")).toHaveValue("Waiting for the new art.");
  state.answer = "reason saved";
  await dialog.locator("textarea").fill("Rewriting the spec after the playtest.");
  await dialog.getByRole("button", { name: "Save" }).click();

  await expect(detail.locator(".msg")).toHaveText("reason saved");
  await expect(detail.locator(".pzquote")).toHaveText("Rewriting the spec after the playtest.");
  await expect(row.locator(".pzwhy")).toHaveText("“Rewriting the spec after the playtest.”");
  // Still paused since Wednesday: a new reason is not a new pause.
  await expect(detail.locator(".pzwhen")).toHaveText("Wed, Nov 4 · 3 days ago");
  expect(state.posted).toEqual([{ id: "001", reason: "Rewriting the spec after the playtest." }]);
});

test("a project that is not running can be paused the same way, with no reason", async ({ page }) => {
  // Queued until it is paused, as the server leaves it.
  const state = await board(page, { change: (jobs, { notes }) => Object.assign(jobs.find(j => j.id === "001"),
    { running: false, queuePosition: notes["001"] ? 0 : 1 }) });
  state.answer = PAUSED;
  const detail = await panel(page, "001");
  const pause = detail.locator(".pzbtn");

  await expect(pause).toHaveText("Pause…");
  await pause.click();
  const dialog = page.locator("dialog.pzdlg");
  await expect(dialog.locator("h2")).toHaveText("Pause Alpha Project (001)?");
  await expect(dialog.locator("label")).toHaveText("Why are you pausing it?");
  await dialog.getByRole("button", { name: "Pause it" }).click();

  await expect(detail.locator(".msg")).toHaveText(PAUSED);
  const row = page.locator("#row-001");
  await expect(row.locator(".pzfor")).toHaveText("Paused by you, just now");
  await expect(row.locator(".pzwhy")).toBeHidden();
  await expect(detail.locator(".pzquote")).toBeHidden();
  await expect(pause).toHaveText("Change the reason");
  expect(state.posted).toEqual([{ id: "001", reason: "" }]);

  // Nothing to pause in one that has not started, nor in one that is finished.
  for (const id of ["002", "003"]) {
    await expect(page.locator(`#detail-${id} .pzbtn`)).toHaveJSProperty("hidden", true);
  }
});

test("Stop on a finished project's running job stops it outright, as before", async ({ page }) => {
  // A finished project runs only a button's job, a deploy say, which a pause would refuse.
  const state = await board(page, { change: (jobs) => Object.assign(jobs.find(j => j.id === "003"), { running: true }) });
  const detail = await panel(page, "003");

  await expect(detail.locator(".act-btn")).toHaveText("Stop");
  await detail.locator(".act-btn").click();
  await expect(detail.locator(".msg")).toHaveText("stopping");
  expect(state.stops).toEqual([{ id: "003" }]);
  expect(state.posted).toEqual([]);
  await expect(page.locator("dialog.pzdlg")).toHaveCount(0);
});

test("a project you paused is not said to be waiting, whatever else it waits for", async ({ page }) => {
  const back = new Date(NOW.getTime() + 3 * HOUR).toISOString();
  const state = await board(page, {
    quota: { CLAUDE: { observedAt: NOW.toISOString(), retryAfter: back, message: "You've hit your usage limit." } },
    change: (jobs) => Object.assign(jobs.find(j => j.id === "001"), {
      phase: "SUSPENDED", phaseLabel: "waiting for quota", running: false, lastError: "RATE_LIMITED",
      blockedReason: "CLAUDE is out of quota until later", retryAfter: back,
    }),
  });
  const detail = page.locator("#detail-001");
  const strip = page.locator(".wnd24");
  await expect(detail).toHaveClass(/\bwst\b/);
  await expect(strip).toContainText("Alpha Project");

  state.notes = { "001": { reason: "Saving quota", at: NOW.toISOString() } };
  // Read again on the page's next poll, every four seconds.
  await expect(detail).toHaveClass(/\bpzheld\b/, { timeout: 10_000 });
  await expect(detail).not.toHaveClass(/\bwst\b/);
  await expect(page.locator("#row-001 .pzwhy")).toHaveText("“Saving quota”");
  // The strip stays for Claude's quota, without the project that no longer starts at any time.
  await expect(strip).toBeVisible();
  await expect(strip).not.toContainText("Alpha Project");
});

test("a version you paused is Paused on its family's row, with why", async ({ page }) => {
  await board(page, {
    notes: { "001": { reason: "Waiting for art", at: new Date(NOW - 2 * HOUR).toISOString() } },
    change: (jobs) => {
      const base = stopped(jobs);
      jobs.push({ ...base, id: "001.1", title: "Alpha Project, part 1", parentId: "001", phase: "NEW",
        queuePosition: 0, blockedReason: "waiting for 001 to be accepted first" });
    },
  });
  const family = page.locator('.fmrow[data-family="001"]');

  await expect(family.locator(".pill")).toHaveText("Paused");
  await expect(family.locator(".fmwhat")).toHaveText("Paused by you");
  await expect(family.locator(".fmwhen")).toHaveText("“Waiting for art”");
  await expect(family.locator(".fmchip")).toHaveText(["001 paused", "001.1 next"]);
});

test.describe("on a phone", () => {
  test.use({ viewport: { width: 412, height: 915 } });

  test("a paused project's row says so in place of its word", async ({ page }) => {
    await board(page, { notes: threeDays(), change: stopped });
    const row = page.locator("#row-001");

    await expect(row.locator(".pzline")).toBeVisible();
    await expect(row.locator(".pzfor")).toHaveText("Paused by you, 3 days");
    await expect(row.locator(".mob")).toBeHidden();
  });
});
