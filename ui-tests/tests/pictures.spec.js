const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");

/**
 * Picture batches (pictures.PictureBatches): pictures an agent outside Forge draws, which Forge checks
 * as each one is saved. The dashboard's card (ui/extras/pictures.js) says where a batch stands and
 * copies the agent's prompt; the review page (pictures.html) deals each checked picture and sends every
 * answer at once; a click places the kept ones.
 *
 * The batch is made here and removed afterwards, so no other spec sees its card. Its checks and its
 * placing are the editor's own, run by its watcher every few seconds, so the steps wait for them.
 */

test.describe.configure({ mode: "serial" });

const ID = "042-pictures";
const BATCH = path.join(__dirname, "..", ".fixture", "runs", ID);
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==", "base64");
const slashes = (file) => file.replace(/\\/g, "/");

/* A job card as the art tools write one; the batch reads every part of it. */
const card = (number, title, prompt, draft, check, after = []) => [
  `## Job ${number} of 3: Lantern Fair, ${title}`, "",
  "Prompt:", "", "```text", prompt, "```", "",
  "Save the picture as `" + draft + "`, then run:", "", "```bat", ...check, "```", "",
  ...after,
].join("\n");

/* The market's check passes with something to look at and draws a review sheet; placing it copies it. */
const CARDS = ["# Lantern screens", "",
  card(1, "Market", "Paint the market square at dusk.", "repo/drafts/market.png", [
    "copy /y sheet-src.png checks\\market-review.png >nul",
    "echo LOOK: the sky is pale",
    "exit /b 42",
  ], [
    "Review sheet: `checks/market-review.png`", "",
    "Once kept, Forge places it with:", "",
    "```bat", "copy /y repo\\drafts\\market.png market-placed.png >nul", "echo placed market", "```", "",
  ]),
  card(2, "Harbour", "The harbour at dawn.", "repo/drafts/harbour.png", ["echo All good."]),
  card(3, "Bridge", "The bridge in the rain.", "repo/drafts/bridge.png", ["echo All good."]),
].join("\n");

const states = async (api) => {
  const res = await api.get(`/api/pictures/batch?id=${ID}`);
  return res.ok() ? (await res.json()).jobs.map((job) => job.state) : [];
};

test.beforeAll(async ({ playwright }, testInfo) => {
  fs.rmSync(BATCH, { recursive: true, force: true });
  for (const dir of ["checks", "repo/drafts"]) fs.mkdirSync(path.join(BATCH, dir), { recursive: true });
  fs.writeFileSync(path.join(BATCH, "batch.json"), JSON.stringify({
    title: "Lantern screens", cards: "job-cards.md", rules: "rules.md", target: "003",
  }));
  fs.writeFileSync(path.join(BATCH, "job-cards.md"), CARDS);
  fs.writeFileSync(path.join(BATCH, "rules.md"), "# What to check\n\nThe lanterns glow.\n");
  fs.writeFileSync(path.join(BATCH, "sheet-src.png"), PNG);
  // Saved a minute ago, as the agent would have: a picture still being written is not checked yet.
  const before = new Date(Date.now() - 60_000);
  for (const name of ["market", "harbour"]) {
    const file = path.join(BATCH, "repo", "drafts", `${name}.png`);
    fs.writeFileSync(file, PNG);
    fs.utimesSync(file, before, before);
  }

  const api = await playwright.request.newContext({ baseURL: testInfo.project.use.baseURL });
  await expect.poll(() => states(api), { timeout: 20_000 }).toEqual(["CHECKED", "CHECKED", "TO_DRAW"]);
  await api.dispose();
});

test.afterAll(() => {
  fs.rmSync(BATCH, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
});

test("the dashboard says where the batch stands, and copies the agent's prompt", async ({ page, context, request }) => {
  await context.grantPermissions(["clipboard-read", "clipboard-write"]);
  await page.goto("/");
  const box = page.locator(`.pxcard[data-id="${ID}"]`);

  await expect(box.locator("h2")).toHaveText("Pictures: Lantern screens");
  await expect(box.locator(".pxhead .pxsay")).toHaveText("For 003, drawn by Astra");
  await expect(box.locator(".pxcounts li")).toHaveText(["1 to draw", "2 to review"]);
  await expect(box.getByRole("link", { name: "Review 2 pictures" })).toHaveAttribute("href", `/pictures?id=${ID}`);
  await expect(box.locator(".pxfile")).toContainText(`${slashes(BATCH)}/repo/astra-prompt.md`);

  await box.getByRole("button", { name: "Copy Astra's prompt" }).click();
  await expect(box.locator("button[data-act=copy]")).toHaveText("Copied");
  // Windows' clipboard ends lines as Windows does.
  const copied = (await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, "\n");
  const batch = await (await request.get(`/api/pictures/batch?id=${ID}`)).json();
  expect(copied).toBe(batch.message);
  expect(copied).toContain(`Read ${slashes(BATCH)}/repo/next-jobs.md and draw every job it lists`);
  // The same message is in the file the card names, beside the list it reads.
  expect(fs.readFileSync(path.join(BATCH, "repo", "astra-prompt.md"), "utf8")).toContain(copied);
});

test("the review deals each checked picture, wants a note to draw one again, and sends the answers at once", async ({ page }) => {
  await page.goto(`/pictures?id=${ID}`);
  const job = page.locator("section.card.job");
  const note = page.locator("#note");

  await expect(page.locator("#title")).toHaveText("Lantern screens");
  await expect(page.locator("#rules")).toHaveAttribute("href", `/runs/${ID}/rules.md`);
  await expect(page.locator("#where")).toHaveText("1 of 2");
  await expect(job).toHaveAttribute("data-draft", "repo/drafts/market.png");
  await expect(job.locator(".meta > span").first()).toHaveText("Job 1 of 3");
  await expect(job.locator(".tag")).toHaveText("Look closely");
  await expect(job.locator("h1")).toHaveText("Lantern Fair, Market");
  await expect(job.locator("pre.lines")).toHaveText("LOOK: the sky is pale");
  const sheet = job.locator("a.sheet img");
  await expect(sheet).toHaveAttribute("src", new RegExp(`^/runs/${ID}/checks/market-review\\.png\\?v=\\d+$`));
  // Served by the editor from the batch folder, so the sheet is there to look at.
  await expect.poll(() => sheet.evaluate((img) => img.naturalWidth)).toBe(1);

  await job.locator("button.keep").click();

  await expect(page.locator("#where")).toHaveText("2 of 2");
  await expect(job).toHaveAttribute("data-draft", "repo/drafts/harbour.png");
  await expect(job.locator(".tag")).toHaveText("Check passed");
  // No review sheet on this card, so the picture itself is shown.
  await expect(job.locator("a.sheet img")).toHaveAttribute("src", new RegExp(`^/runs/${ID}/repo/drafts/harbour\\.png\\?v=`));

  await job.locator("button.redo").click();
  await expect(note).toHaveClass(/wanted/);
  await expect(note).toHaveAttribute("placeholder", "Say what to fix, then press Draw again");
  await expect(job).toHaveAttribute("data-draft", "repo/drafts/harbour.png");

  await note.fill("the boats should face the pier");
  await job.locator("button.redo").click();

  const final = page.locator("section.card.final");
  await expect(final.locator("h1")).toHaveText("Some to draw again");
  await expect(final.locator("ul.summary li")).toHaveText([
    "Keep: Lantern Fair, Market",
    "Draw again: Lantern Fair, Harbour — “the boats should face the pier”",
  ]);
  await expect(final.locator(".warn")).toHaveCount(0);
  await final.getByRole("button", { name: "Send 2 answers" }).click();

  const done = page.locator("section.card.done");
  await expect(done.locator("h1")).toHaveText("Sent");
  await expect(done.locator(":scope > p").first()).toHaveText("2 rulings saved.");
  await expect(page.locator("#next")).toContainText("2 pictures to draw. Send Astra its prompt");
  await expect(page.locator("#next")).toContainText("1 kept picture is ready to place in the project.");
  await page.getByRole("button", { name: "Place 1 kept picture" }).click();
  await expect(page.locator("#placed")).toHaveText("Placing 1 picture, one at a time. The dashboard shows how it went.");
  await expect(page.locator("#place")).toBeDisabled();
});

test("the kept picture is placed, and the agent's list asks for the redraw with the note", async ({ page, request }) => {
  await expect.poll(() => states(request), { timeout: 20_000 }).toEqual(["PLACED", "REDRAW", "TO_DRAW"]);
  expect(fs.existsSync(path.join(BATCH, "market-placed.png"))).toBe(true);

  const list = path.join(BATCH, "repo", "next-jobs.md");
  const read = () => (fs.existsSync(list) ? fs.readFileSync(list, "utf8") : "");
  await expect.poll(read, { timeout: 15_000 }).toContain("The harbour at dawn. The boats should face the pier.");
  const text = read();
  expect(text).toContain("Draw these 2 jobs, in order.");
  expect(text).toContain("## Job 2 of 3: Lantern Fair, Harbour\n\nDrawn again: the prompt ends with what to fix.");
  expect(text).toContain("Save the picture as `" + slashes(BATCH) + "/repo/drafts/harbour.png`.");
  expect(text).toContain("## Job 3 of 3: Lantern Fair, Bridge");
  expect(text).not.toContain("Paint the market square at dusk.");

  await page.goto("/");
  const box = page.locator(`.pxcard[data-id="${ID}"]`);
  await expect(box.locator(".pxcounts li")).toHaveText(["2 to draw", "1 placed"]);
  await expect(box.getByRole("link", { name: /Review/ })).toHaveCount(0);
  await expect(box.locator("button[data-act=place]")).toHaveCount(0);
});
