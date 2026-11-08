const { test, expect } = require("@playwright/test");

/**
 * The spec's pictures on the quick review (ui/extras/review-pictures.js): the card that asks
 * whether the screens look like them shows each picture beside its screenshot.
 *
 * The deck, the report and the images are answered here. Which projects are asked, and in which
 * words, is the server's (PictureLookTest); the report below is in the shape PicturesSection writes.
 */

const QUESTION = "The screens should look like the spec's pictures: compare each picture with its screenshot.";

const card = (id, source, text) => ({
  id, kind: id.split("-")[0], source, text, headline: text,
  detail: "", stakes: "", cameBack: "", attempts: 0, stillPresent: "", notes: [],
});
const LOOK = card("look-5d41402a", "The gate asks you to look", QUESTION);
const BLOCKING = card("blocking-1a2b3c4d", "Blocking review finding", "The board's score is never saved");

const deck = (id, mustDecide) => ({
  id, title: "Screens", phase: "DONE", notice: "", mustDecide, features: [], skim: [],
  featuresAlreadyRuled: 0, ideasWaiting: 0, report: `/runs/${id}/report.html`,
});

/* A version's report: its repository is its family's, one folder up, as PicturesSection links it. */
const figure = (path, label) => `<figure><a href="../777/repo/${path}" target="_blank">`
  + `<img src="../777/repo/${path}" alt="${label}"></a><figcaption>${label} · ${path}</figcaption></figure>`;
const REPORT = `<!doctype html><html><body><main>
<h2 id="verdict">Verdict</h2><div class="card"><h3>Not a picture</h3><p>Passed.</p></div>
<h2 id="pictures">Pictures</h2><div class="card"><p>Each picture the spec was built to, beside the screenshot of the same state.</p>
<h3>The title screen</h3><div class="polish-pair">${figure(".forge-images/title.png", "The picture")}${figure("artifacts/title.png", "The screenshot")}</div>
<h3>The board</h3><div class="polish-pair">${figure(".forge-images/board.png", "The picture")}<figure><div class="polish-missing">No screenshot yet. The gate has not passed, or the suite does not save it.</div><figcaption>The screenshot · artifacts/board.png</figcaption></figure></div>
<p class="muted">The screenshots are saved by the project's own suite.</p></div>
</main></body></html>`;

/* A portrait screen, drawn: the picture in one colour, the screenshot in another. */
const screen = (fill, words) => `<svg xmlns="http://www.w3.org/2000/svg" width="360" height="640">`
  + `<rect width="360" height="640" fill="${fill}"/><rect x="24" y="48" width="312" height="72" rx="12" fill="#f4d35e"/>`
  + `<text x="180" y="340" font-family="sans-serif" font-size="30" text-anchor="middle" fill="#fff">${words}</text></svg>`;

async function answer(page, id, mustDecide, report) {
  await page.route(`**/api/review?id=${id}`, (route) => route.fulfill({
    status: 200, contentType: "application/json", body: JSON.stringify(deck(id, mustDecide)) }));
  await page.route(`**/runs/${id}/report.html`, (route) => report
    ? route.fulfill({ status: 200, contentType: "text/html", body: report })
    : route.fulfill({ status: 404, contentType: "text/plain", body: "no report" }));
  await page.route("**/runs/777/repo/**", (route) => route.fulfill({
    status: 200, contentType: "image/svg+xml",
    body: route.request().url().includes(".forge-images") ? screen("#3d5a80", "Picture") : screen("#5c677d", "Screenshot") }));
  const posted = [];
  await page.route("**/api/jobs/decide", async (route) => {
    posted.push(route.request().postDataJSON());
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify({ result: "sent back; started" }) });
  });
  return posted;
}

test("the card that asks about the pictures shows each beside its screenshot", async ({ page }) => {
  const posted = await answer(page, "778", [LOOK], REPORT);
  await page.goto("/review?id=778");

  const must = page.locator(".card.must");
  await expect(must.locator("h1")).toHaveText(QUESTION);
  const rows = must.locator(".rp-row");
  await expect(rows).toHaveCount(2);
  await expect(rows.locator(".rp-state")).toHaveText(["The title screen", "The board"]);

  // The picture and its screenshot, each loaded from where the report links it, and each opening
  // full size on its own.
  const title = rows.nth(0).locator("img");
  await expect(title).toHaveCount(2);
  await expect(title.nth(0)).toHaveAttribute("src", /\/runs\/777\/repo\/\.forge-images\/title\.png$/);
  await expect(title.nth(1)).toHaveAttribute("src", /\/runs\/777\/repo\/artifacts\/title\.png$/);
  await expect.poll(() => title.nth(1).evaluate(img => img.naturalWidth)).toBeGreaterThan(0);
  await expect(rows.nth(0).locator("a").nth(0)).toHaveAttribute("target", "_blank");
  await expect(rows.nth(0).locator("figcaption")).toHaveText(["The picture", "The screenshot"]);
  // A screenshot the gate has not saved says why, rather than leaving a gap.
  await expect(rows.nth(1).locator(".rp-missing")).toContainText("No screenshot yet");

  // Look first, then say: the pairs come before the note, and the card's only buttons are still its
  // two answers (and Add image, which only puts a picture in the note).
  expect(await page.evaluate(() => Boolean(document.querySelector(".rp")
    .compareDocumentPosition(document.querySelector("#note")) & Node.DOCUMENT_POSITION_FOLLOWING))).toBe(true);
  await expect(must.locator("button:not(.attach-button)")).toHaveCount(2);

  await must.locator("#note").fill("the board's header is twice as tall as the picture's");
  await must.locator(".fix").click();
  const final = page.locator(".card.final");
  await expect(final.locator("#submit")).toHaveText("Send back 1 thing");

  // Going back deals the card again, pictures and all, with the answer as it was given.
  await page.locator("#prev").click();
  await expect(must.locator(".rp-row")).toHaveCount(2);
  await expect(must.locator(".fix")).toHaveClass(/on/);
  await must.locator(".fix").click();

  await final.locator("#submit").click();
  await expect.poll(() => posted.length).toBe(1);
  expect(posted[0].decisions).toEqual([{ itemId: LOOK.id, text: QUESTION, verdict: "changes",
    comment: "the board's header is twice as tall as the picture's" }]);
});

test("other cards are left alone, and a report it cannot read is said, not hidden", async ({ page }) => {
  await answer(page, "779", [BLOCKING, LOOK], null);
  await page.goto("/review?id=779");

  const must = page.locator(".card.must");
  await expect(must.locator("h1")).toHaveText("The board's score is never saved");
  await expect(must.locator(".rp")).toHaveCount(0);
  await must.locator(".fine").click();

  await expect(must.locator("h1")).toHaveText(QUESTION);
  await expect(must.locator(".rp-note")).toContainText("could not be read");
  await expect(must.locator(".rp a")).toHaveAttribute("href", "/runs/779/report.html#pictures");
  await expect(must.locator(".rp-row")).toHaveCount(0);
});
