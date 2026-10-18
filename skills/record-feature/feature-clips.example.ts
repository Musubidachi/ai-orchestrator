// Playwright: one test per approved feature, titled "feature: <the feature's name>", and this
// afterEach saves its video as artifacts/feature-<slug>.webm. Needs `use: { video: "on" }` (or
// "retain-on-failure" is NOT enough: a passing test must keep its video) in playwright.config.
import { test } from "@playwright/test";
import fs from "node:fs";

const PREFIX = "feature: ";

/** Forge's slug: lowercased, runs of anything but a-z and 0-9 to one hyphen, trimmed, 60 at most. */
export const slugOf = (name: string) =>
  (name.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "unnamed").slice(0, 60);

test.afterEach(async ({ page }, testInfo) => {
  if (!testInfo.title.startsWith(PREFIX) || testInfo.status !== testInfo.expectedStatus) return;
  const video = page.video();
  if (!video) throw new Error("video recording is off: set use.video to \"on\" in playwright.config");
  await page.close(); // the video file is only complete once the page has closed
  fs.mkdirSync("artifacts", { recursive: true });
  await video.saveAs(`artifacts/feature-${slugOf(testInfo.title.slice(PREFIX.length))}.webm`);
});

// Example. Drive the real feature, slowly enough to be watched: a clip of a few seconds.
test("feature: Monthly summary", async ({ page }) => {
  await page.goto("/");
  // ... run the import, and end on the new rows appearing in the table ...
});
