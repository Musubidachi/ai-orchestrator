const { test, expect } = require("@playwright/test");

/**
 * The report guide (forge-report, report/guide/guide.js): a rail of a project page's sections, each
 * with what in it is worth a look, and a bar that keeps the review in reach.
 *
 * Read off the pages the fixture generates, because the rail finds a section by the id the page
 * gives it or the one made from its heading: a reworded heading loses its count, and only a real
 * page shows that. 004 is held on a blocking finding and never changes during the suite. 003 is
 * accepted by dashboard.spec.js, so for 003 only what that leaves alone is pinned.
 */

const REPORT_003 = "/runs/003/report.html";
const REPORT_004 = "/runs/004/report.html";

const rail = (page) => page.locator(".rg-rail");
const bar = (page) => page.locator(".rg-bar");
const reviewRows = (page) => page.locator("#decide [data-kind=review]");

/* What the rail says, section by section. */
function entries(page) {
  return page.locator(".rg-rail a").evaluateAll((links) => links.map((link) => {
    const hold = link.querySelector(".rg-hold");
    return {
      label: link.querySelector(".rg-label").textContent,
      hold: hold.textContent,
      tone: [...hold.classList].filter((name) => name !== "rg-hold").join(" "),
      title: hold.title,
      href: link.getAttribute("href"),
    };
  }));
}

test("the rail lists the page's sections, with what in each is worth a look", async ({ page }) => {
  await page.goto(REPORT_004);

  await expect(rail(page)).toBeVisible();
  expect(await page.evaluate(() => getComputedStyle(document.body).display)).toBe("grid");
  await expect(rail(page).locator(".rg-label"))
    .toHaveText(["What this does not prove", "Cross-review", "Your review", "Where to look"]);
  await expect(rail(page).locator(".rg-hold")).toHaveText(["1", "1 blocking", "0 of 2", ""]);

  const said = await entries(page);
  expect(said.map((entry) => entry.href))
    .toEqual(["#what-this-does-not-prove", "#cross-review", "#your-review", "#where-to-look"]);
  expect(said[0]).toMatchObject({ tone: "rg-warn", title: "1 thing the acceptance commands do not cover" });
  expect(said[1]).toMatchObject({ tone: "rg-bad", title: "1 blocking finding" });
  expect(said[2]).toMatchObject({ tone: "rg-pill", title: "0 of 2 ruled on" });
  expect(said[3]).toMatchObject({ tone: "", title: "" });

  // A link goes to its section, and stays marked as the one being read.
  await rail(page).getByRole("link", { name: /Cross-review/ }).click();
  await expect(page).toHaveURL(/#cross-review$/);
  await expect(page.locator("h2#cross-review")).toBeInViewport();
  await expect(rail(page).locator("a[aria-current=location]")).toHaveText(/Cross-review/);
});

test("a finished project's rail counts its features, its gaps, its timings and its security", async ({ page }) => {
  await page.goto(REPORT_003);
  const said = await entries(page);
  const of = (label) => said.find((entry) => entry.label === label);

  expect(of("What it does")).toMatchObject({ hold: "2 features", tone: "" });
  expect(of("The approach")).toMatchObject({ hold: "", tone: "" });
  expect(of("What this does not prove")).toMatchObject({ hold: "2", tone: "rg-warn" });
  expect(of("How long the checks take"))
    .toMatchObject({ hold: "1 slower", tone: "rg-warn", title: "1 of 1 command slower than usual" });
  expect(of("Security")).toMatchObject({ hold: "1 critical", tone: "rg-bad", title: "Found: 1 critical, 1 low" });
  // The reviewer found nothing. A section with nothing to count says nothing, rather than "none".
  expect(of("Cross-review")).toMatchObject({ hold: "", tone: "" });

  // Ruled on so far, of the rows the form has, whatever dashboard.spec.js left them as.
  const rows = reviewRows(page);
  const total = await rows.count();
  const ruled = await rows.filter({ has: page.locator("input[type=radio]:checked") }).count();
  expect(total).toBeGreaterThan(0);
  expect(of("Your review")).toMatchObject({ hold: `${ruled} of ${total}`, tone: "rg-pill" });
});

test("each kind of section is counted from what it shows", async ({ page }) => {
  // The fixture's pages have no notes, no checked claims and no high findings, so the guide the
  // editor serves is run here against sections written out by hand, in the markup the report uses.
  await page.goto(REPORT_004);
  const guide = await page.locator("#report-guide").textContent();

  await page.setContent(`<main><h1>Made up</h1>
    <h2>How long the checks take</h2><div class="card"><ul>
      <li><code>npm test</code> 2s</li><li><code>npm run build</code> 5s</li></ul></div>
    <h2 id="security">Security</h2><div class="card">
      <table><tr><th>Check</th><th>Result</th></tr><tr><td>secrets</td><td>ran</td></tr></table>
      <table><tr><th>Severity</th><th>Finding</th></tr>
        <tr><td>medium</td><td>a</td></tr><tr><td>high <strong>needs your decision</strong></td><td>b</td></tr>
        <tr><td>low</td><td>c</td></tr><tr><td>high</td><td>d</td></tr></table></div>
    <h2>Cross-review</h2><div class="card"><strong>Notes</strong><ul><li>one</li><li>two</li></ul></div>
    <h2>Feature claims, checked one by one</h2><div class="card"><ul>
      <li><strong>Export</strong> found in the code</li>
      <li><strong>Import</strong> <strong>could not be found</strong></li></ul></div>
    <h2>Where to look</h2><ul><li>repo/</li></ul>
    <script>${guide}</script></main>`);

  expect(await entries(page)).toEqual([
    { label: "How long the checks take", hold: "2 commands", tone: "", title: "",
      href: "#how-long-the-checks-take" },
    { label: "Security", hold: "2 high", tone: "rg-bad", title: "Found: 2 high, 1 medium, 1 low",
      href: "#security" },
    { label: "Cross-review", hold: "2 notes", tone: "", title: "2 notes", href: "#cross-review" },
    { label: "Feature claims, checked one by one", hold: "1 not found", tone: "rg-warn",
      title: "1 of 2 claims could not be found in the code", href: "#feature-claims-checked-one-by-one" },
    { label: "Where to look", hold: "", tone: "", title: "", href: "#where-to-look" },
  ]);
  // No form, so no bar.
  await expect(bar(page)).toHaveCount(0);

  // Two sections are not worth a rail.
  await page.setContent(`<main><h1>Short</h1>
    <h2>Cross-review</h2><p class="muted">No findings.</p>
    <h2>Where to look</h2><ul><li>repo/</li></ul>
    <script>${guide}</script></main>`);
  await expect(rail(page)).toHaveCount(0);
  await expect(page.locator("body")).not.toHaveClass(/\brg-on\b/);
});

test.describe("the review bar", () => {
  // Short enough that the form's own button starts below the fold.
  test.use({ viewport: { width: 1280, height: 600 } });

  test("counts what is ruled on, and Next goes to what is not", async ({ page }) => {
    await page.goto(REPORT_004);
    const rows = reviewRows(page);
    const next = bar(page).getByRole("button", { name: "Next to rule on" });

    await expect(bar(page)).toBeVisible();
    await expect(bar(page).locator(".rg-n")).toHaveText("0 of 2 ruled on");
    await expect(bar(page).locator(".rg-segs span")).toHaveCount(2);
    await expect(bar(page).locator(".rg-segs span.on")).toHaveCount(0);
    // Why it cannot simply be accepted comes first, in the form's own words.
    await expect(bar(page).locator(".rg-why")).toContainText("Waiting for you");
    await expect(bar(page).locator(".rg-why")).toHaveClass(/\brg-blocked\b/);
    await expect(bar(page).locator(".rg-submit")).toHaveText("Submit review");

    await next.click();
    await expect(rows.nth(0)).toHaveClass(/\brg-here\b/);
    await expect(rows.nth(0).locator("input[type=radio]").first()).toBeFocused();

    await rows.nth(0).locator("input[value=changes]").check();
    await expect(bar(page).locator(".rg-n")).toHaveText("1 of 2 ruled on");
    await expect(bar(page).locator(".rg-segs span.on")).toHaveCount(1);
    await expect(rail(page).locator(".rg-pill")).toHaveText("1 of 2");

    await page.evaluate(() => window.scrollTo(0, 0));
    await next.click();
    await expect(rows.nth(1)).toHaveClass(/\brg-here\b/);
    await expect(rows.nth(1).locator("input[type=radio]").first()).toBeFocused();

    await rows.nth(1).locator("input[value=ok]").check();
    await expect(bar(page).locator(".rg-n")).toHaveText("2 of 2 ruled on");
    await expect(rail(page).locator(".rg-pill")).toHaveText("2 of 2");
    await expect(bar(page).locator(".rg-next")).toHaveAttribute("hidden", "");
  });

  test("Submit is the form's own, and the bar says what the form says", async ({ page }) => {
    let posted = false;
    await page.route("**/api/jobs/decide", (route) => {
      posted = true;
      return route.abort();
    });
    await page.goto(REPORT_004);
    const why = bar(page).locator(".rg-why");

    await bar(page).getByRole("button", { name: "Submit review" }).click();

    await expect(page.locator("#decide-msg")).toContainText("Rule on the 2 items");
    await expect(why).toContainText("Rule on the 2 items");
    await expect(why).toHaveClass(/\brg-said\b/);
    await expect(page.locator("#decide .decide-missing")).toHaveCount(2);
    expect(posted).toBe(false);

    // Once something is ticked, what the form said no longer applies.
    await reviewRows(page).first().locator("input[value=ok]").check();
    await expect(why).toContainText("Waiting for you");
    expect(posted).toBe(false);
  });

  test("adds no form, no submit button and no row the form reads", async ({ page }) => {
    const source = await (await page.request.get(REPORT_004)).text();
    const count = (pattern) => (source.match(pattern) || []).length;
    await page.goto(REPORT_004);

    await expect(bar(page)).toBeVisible();
    await expect(page.locator("form")).toHaveCount(count(/<form\b/g));
    await expect(page.locator("button[type=submit]")).toHaveCount(count(/<button type="submit"/g));
    await expect(page.locator("[data-kind]")).toHaveCount(count(/ data-kind="/g));
  });

  test("steps aside while the form's own button is on screen", async ({ page }) => {
    await page.goto(REPORT_004);
    await expect(bar(page)).toBeVisible();

    await page.locator("#decide .decide-actions").scrollIntoViewIfNeeded();
    await expect(bar(page)).toHaveClass(/\brg-away\b/);
    await expect(bar(page)).toBeHidden();

    await page.evaluate(() => window.scrollTo(0, 0));
    await expect(bar(page)).toBeVisible();
  });
});

test("a narrow window keeps the bar and leaves the rail out", async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 700 });
  await page.goto(REPORT_004);

  await expect(rail(page)).toBeHidden();
  expect(await page.evaluate(() => getComputedStyle(document.body).display)).toBe("block");
  await expect(bar(page)).toBeVisible();
});
