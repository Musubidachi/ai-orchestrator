#!/usr/bin/env node
// Screenshots a running page at phone, landscape and desktop sizes, and reports what is wrong at each.
//
//   node <this skill's folder>/shoot-viewports.cjs <url> [out-dir] [--click "Start"]
//
// Run it from the project folder, so @playwright/test resolves from the project's own node_modules.
// Start the page first (your preview or dev server, on 127.0.0.1) and stop it afterwards.
// Exits 1 if any size has a problem, so it can also sit inside a test script.
"use strict";

const path = require("node:path");
const fs = require("node:fs");

let chromium;
try {
  ({ chromium } = require(require.resolve("@playwright/test", { paths: [process.cwd()] })));
} catch (e) {
  console.error("@playwright/test is not installed in this project (run from the project folder, after npm ci).");
  process.exit(2);
}

const SIZES = [
  { name: "phone-390x844", width: 390, height: 844, touch: true },
  { name: "small-phone-320x568", width: 320, height: 568, touch: true },
  { name: "phone-landscape-844x390", width: 844, height: 390, touch: true },
  { name: "laptop-1366x768", width: 1366, height: 768, touch: false },
  { name: "desktop-1920x1080", width: 1920, height: 1080, touch: false },
];

const args = process.argv.slice(2);
const clickAt = args.indexOf("--click");
const click = clickAt >= 0 ? args.splice(clickAt, 2)[1] : null;
const [url, outDir = "artifacts/viewports"] = args;
if (!url) {
  console.error("usage: node shoot-viewports.cjs <url> [out-dir] [--click \"Button name\"]");
  process.exit(2);
}

(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const browser = await chromium.launch();
  let failed = false;
  for (const size of SIZES) {
    const context = await browser.newContext({
      viewport: { width: size.width, height: size.height }, hasTouch: size.touch, isMobile: size.touch,
    });
    const page = await context.newPage();
    await page.goto(url);
    await page.waitForTimeout(600);
    if (click) {
      await page.getByRole("button", { name: click }).first().click();
      await page.waitForTimeout(1200);
    }
    const problems = await page.evaluate((touch) => {
      const found = [];
      const doc = document.documentElement;
      // Without it a phone lays the page out as a ~980px desktop page and zooms out, which also
      // hides every overflow below from this check.
      if (touch && !document.querySelector('meta[name="viewport"]')) {
        found.push('no <meta name="viewport" content="width=device-width, initial-scale=1">: phones show a zoomed-out desktop page');
      }
      if (doc.scrollWidth > doc.clientWidth + 1) found.push(`scrolls sideways: ${doc.scrollWidth}px wide in a ${doc.clientWidth}px window`);
      const controls = [...document.querySelectorAll("button, a[href], input, select, textarea, [role=button]")]
        .filter((el) => { const s = getComputedStyle(el); return s.visibility !== "hidden" && s.display !== "none" && el.getClientRects().length; });
      for (const el of controls) {
        const b = el.getBoundingClientRect();
        const name = (el.getAttribute("aria-label") || el.textContent || el.tagName).trim().slice(0, 40);
        if (b.right > doc.clientWidth + 1 || b.left < -1) found.push(`"${name}" is cut off at the side`);
        if (touch && (b.width < 44 || b.height < 44)) found.push(`"${name}" is ${Math.round(b.width)}x${Math.round(b.height)}, under the 44px a finger needs`);
        // A button is wider than tall. Center Defense's broken desktop buttons were about 130x215.
        if (el.tagName !== "TEXTAREA" && el.tagName !== "SELECT" && b.height > 1.5 * Math.max(b.width, 1) && b.height > 100) {
          found.push(`"${name}" is stretched: ${Math.round(b.width)}x${Math.round(b.height)}, taller than it is wide`);
        }
      }
      return found;
    }, size.touch);
    const shot = path.join(outDir, `${size.name}.png`);
    await page.screenshot({ path: shot });
    console.log(`${problems.length ? "PROBLEM" : "ok     "} ${size.name}  ${shot}`);
    for (const p of problems) console.log(`          - ${p}`);
    failed = failed || problems.length > 0;
    await context.close();
  }
  await browser.close();
  console.log(failed
    ? "\nSome sizes have problems. Open the screenshots too: these checks catch the obvious, not everything."
    : "\nNo problems found. Still open the screenshots and look at each one.");
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(2); });
