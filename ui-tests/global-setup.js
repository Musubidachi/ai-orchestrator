const fs = require("node:fs");
const path = require("node:path");

/**
 * Builds a throwaway Forge root so the suite never touches real work.
 *
 * Rebuilt from scratch every run: these tests reorder specs and write files, and a fixture that
 * accumulated yesterday's edits would pass or fail for reasons nobody could reconstruct.
 */
module.exports = async () => {
  const root = path.join(__dirname, ".fixture");
  fs.rmSync(root, { recursive: true, force: true });

  const wishlist = path.join(root, "wishlist");
  fs.mkdirSync(wishlist, { recursive: true });
  fs.mkdirSync(path.join(root, "runs"), { recursive: true });

  fs.writeFileSync(path.join(root, "house-rules.md"), "# House rules\n\nBuild it well.\n");

  // One vendor mid-window, with two earlier windows on record; one that has never run.
  fs.writeFileSync(path.join(root, "usage.json"), JSON.stringify({
    CLAUDE: {
      startedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
      inputTokens: 164, cachedTokens: 14_907_049, outputTokens: 40_573, costUsd: 4.13, turns: 3,
      pastCapacities: [15_006_678, 134_585_591],
    },
    CODEX: { startedAt: new Date().toISOString(), turns: 0, pastCapacities: [] },
  }, null, 2));

  const spec = (id, title, priority) => `---
id: "${id}"
title: ${title}
priority: ${priority}
acceptance:
  - echo ok
---

## Goal
${title}.
`;

  fs.writeFileSync(path.join(wishlist, "001-alpha.md"), spec("001", "Alpha Project", 1));
  // Settings the editor has no field for, written by hand, so a save can be shown to keep them.
  fs.writeFileSync(path.join(wishlist, "002-beta.md"), spec("002", "Beta Project", 2).replace(
    "acceptance:",
    "compact_after_sessions: 5\ndeploy:\n  command: npx wrangler pages deploy dist\n"
      + "  url: https://beta.pages.dev\nmonetization:\n  ads: test\nacceptance:"));
  // Deployable, but not yet accepted: the card offers Deploy and says why it cannot be pressed.
  fs.writeFileSync(path.join(wishlist, "003-gamma.md"), spec("003", "Gamma Project", 3).replace(
    "acceptance:", "deploy:\n  command: echo https://gamma.pages.dev\nacceptance:"));

  // A finished run, so the report accordion and the approval controls have something to act on.
  const done = path.join(root, "runs", "003");
  fs.mkdirSync(path.join(done, "repo", ".git", "logs"), { recursive: true });
  fs.writeFileSync(path.join(done, "repo", ".git", "logs", "HEAD"), "a\nb\nc\n");
  // A security check that found a committed key, so the card has something to say about it.
  fs.mkdirSync(path.join(done, "security"), { recursive: true });
  fs.writeFileSync(path.join(done, "security", "security.json"), JSON.stringify({
    commit: "0123456789abcdef", tree: "t", checkedAt: new Date().toISOString(),
    layers: [
      { layer: "secrets", status: "ran", detail: "12 files", findings: [
        { layer: "secrets", severity: "critical", title: "AWS access key id committed",
          location: "src/keys.ts:3", evidence: "AKIA… (20 characters, redacted)", serious: true },
        { layer: "static analysis", severity: "low", title: "document.write",
          location: "src/ui.ts:9", evidence: "document.write(x)", serious: false }] },
      { layer: "live probe", status: "skipped", detail: "no 'serve' command", findings: [] },
    ],
  }, null, 2));

  // A real cross-review on disk, because approval is only offered once one has run.
  // Signed, and with one earlier finding rechecked as fixed, so the league has a reviewer to rank.
  fs.writeFileSync(path.join(done, "review.json"),
    JSON.stringify({ findings: [], reviewer: "CODEX (gpt-6-astra)",
                     rechecks: [{ id: "blocking-0a1b2c3d", status: "fixed", note: "gone" }] }, null, 2));

  // The file a person double-clicks to try it, which is what puts a Play button on the card.
  fs.writeFileSync(path.join(done, "repo", "run.bat"), "@echo off\r\ncall npm run dev\r\npause\r\n");

  // A note left on a claim the agent has since stopped making. The report keeps those, below the
  // form and with no controls of their own -- and reading them as though they were form rows is
  // what silently broke Submit review on the one real project that had any.
  fs.writeFileSync(path.join(done, "decisions.json"), JSON.stringify([{
    itemId: "unproven-0",
    text: "a claim the agent has since stopped making",
    verdict: "ok",
    comment: "",
    comments: [{ text: "worth remembering anyway", at: null }],
  }], null, 2));

  // Already on GitHub, so its card says where. Publishing itself stays switched off in this root
  // (there is no publishing.json): gh is logged in on the machines these tests run on, and an
  // accepting test must never reach a real account.
  fs.writeFileSync(path.join(done, "github.json"), JSON.stringify({
    repository: "someone/gamma-project",
    url: "https://github.com/someone/gamma-project",
    establishedAt: "2026-10-20T00:00:00Z",
    branches: [{ project: "003", branch: "main", commit: "1234567890abcdef1234567890abcdef12345678",
      pushedAt: "2026-10-20T00:00:00Z" }],
    outcome: "published",
    detail: "",
    attemptedAt: "2026-10-20T00:00:00Z",
  }, null, 2));

  fs.writeFileSync(path.join(done, "state.json"), JSON.stringify({
    id: "003", title: "Gamma Project", phase: "DONE", assignedTo: "CLAUDE",
    sessions: 2, repairRounds: 0, blockingFindings: [],
    // Fifteen minutes of actual work inside an hour of wall clock, which is the normal shape:
    // most of a project's elapsed time is spent waiting on a quota window, not working.
    workedMillis: 900_000,
    // A project that has spent tokens and whose suite has slowed down, so the card shows both.
    tokensSpent: 31_400_000,
    // Two-thirds of it split by model and the rest from before the split was recorded, so the
    // board's spend panel has to show both kinds. Written here, not into .fixture, which this
    // file rebuilds on every run.
    tokensByModel: { "CLAUDE/sonnet": 20_000_000, "CODEX/gpt-5.6-sol": 1_400_000 },
    commandSeconds: { "npm test": [20, 21, 19, 60] },
    baselineCommit: "0000000000000000000000000000000000000000",
    plan: {
      status: "complete",
      summary: "built it",
      completed: ["the thing"],
      remaining: [],
      // One already filmed and one not, so the report has to show both states at once.
      features: [
        { name: "Report export", demo: "pick a range, export a report" },
        { name: "Monthly summary", demo: "import a batch, see the rows appear" },
      ],
      not_verified: [
        "the draw branch has never been rendered",
        "keyboard controls are untested",
      ],
      overview: "src/Pipeline.java",
    },
    createdAt: new Date(Date.now() - 3600_000).toISOString(),
    updatedAt: new Date().toISOString(),
  }, null, 2));

  // One feature already approved and filmed. A genuine animated GIF, because the gate no longer
  // accepts a file with the right extension: it reads the container, counts the frames and checks
  // the duration, so a fixture that faked it would be testing a path the product refuses.
  fs.mkdirSync(path.join(done, "repo", "artifacts"), { recursive: true });
  fs.writeFileSync(path.join(done, "repo", "artifacts", "feature-report-export.gif"),
    animatedGif());
  fs.writeFileSync(path.join(done, "features.json"), JSON.stringify([{
    itemId: "feature-report-export",
    text: "Report export",
    verdict: "approved",
    comment: "",
    comments: [],
  }], null, 2));

  // A run held for a person: a blocking finding survived its attempts, and a claim ruled on in an
  // earlier review has come back. Run-only, with no wishlist entry, so the dashboard's cards are
  // exactly as the other tests expect; only its report page is used.
  const held = path.join(root, "runs", "004");
  fs.mkdirSync(held, { recursive: true });
  const blocking = {
    severity: "blocking", file: "src/Docx.java", summary: "text around tables comes out of order",
    detail: "paragraphs() skips table cells and appends them at the end",
    id: "blocking-" + fingerprint("src/Docx.java — text around tables comes out of order"),
    attempts: 2, stillPresent: "nested tables are still appended last",
  };
  const claim = "the tables case is untested";
  fs.writeFileSync(path.join(held, "review.json"), JSON.stringify({ findings: [blocking] }, null, 2));
  fs.writeFileSync(path.join(held, "review-history.json"), JSON.stringify([{
    at: "2026-09-27T20:00:00Z",
    outcome: "sent back; started",
    rulings: [{ itemId: "unproven-" + fingerprint(claim), text: claim, verdict: "changes",
      comment: "add a test with a table in it", comments: [] }],
    features: [],
  }], null, 2));
  fs.writeFileSync(path.join(held, "state.json"), JSON.stringify({
    id: "004", title: "Delta Project", phase: "REVIEW_REPAIR", assignedTo: "CLAUDE",
    needsPerson: "still not fixed after 2 attempts: text around tables comes out of order",
    blockingFindings: [blocking], reviewRepairs: 2, sessions: 4,
    baselineCommit: "0000000000000000000000000000000000000000",
    plan: { status: "complete", summary: "built it", completed: ["the thing"], remaining: [],
      not_verified: [claim], overview: "" },
    createdAt: new Date(Date.now() - 3600_000).toISOString(),
    updatedAt: new Date().toISOString(),
  }, null, 2));

  // Generate the report with Forge itself rather than hand-writing a stub. The decision form is
  // server-rendered, so a fixture that fakes the page would test nothing about the real one.
  const jar = path.join(__dirname, "..", "forge", "target", "forge.jar");
  if (fs.existsSync(jar)) {
    require("node:child_process").execFileSync(
      "java", ["-jar", jar, "report", "--root", root], { stdio: "ignore" });
  } else {
    // No jar yet: leave something readable so the tests that only open the report still run.
    fs.writeFileSync(path.join(done, "report.html"),
      "<!doctype html><title>Gamma Project — Forge</title><h1>Gamma Project</h1>");
  }
};


/**
 * A genuine animated GIF: a 200x150 canvas, ten frames, a block moving across it.
 *
 * Real rather than a file with the right extension, because the gate now reads the container --
 * header, frame count, total delay, dimensions -- and a fixture that faked those would be
 * exercising a path the product refuses. It is built here rather than committed as a binary so the
 * shape of it stays readable.
 *
 * The pixels go through the uncompressed-LZW trick: a clear code every 254 literals keeps the
 * dictionary from ever growing past its initial width, so each pixel is one nine-bit code and
 * there is no compressor to get wrong. Bigger than a real encoder would produce, and about forty
 * lines shorter.
 */
function animatedGif() {
  const width = 200;
  const height = 150;
  const parts = [];

  const header = Buffer.alloc(13);
  header.write("GIF89a", 0, "ascii");
  header.writeUInt16LE(width, 6);
  header.writeUInt16LE(height, 8);
  header[10] = 0xF7;                       // a global colour table of 256 entries follows
  parts.push(header);

  const palette = Buffer.alloc(768);
  for (let i = 0; i < 256; i++) {
    palette[i * 3] = i;
    palette[i * 3 + 1] = (i * 3) & 0xFF;
    palette[i * 3 + 2] = 255 - i;
  }
  parts.push(palette);

  parts.push(Buffer.from([0x21, 0xFF, 0x0B]), Buffer.from("NETSCAPE2.0", "ascii"),
    Buffer.from([0x03, 0x01, 0x00, 0x00, 0x00]));

  const frame = (left, top, w, h, colour) => {
    // Twelve hundredths of a second each: ten frames run for one and a fifth seconds.
    parts.push(Buffer.from([0x21, 0xF9, 0x04, 0x00, 0x0C, 0x00, 0x00, 0x00]));

    const descriptor = Buffer.alloc(10);
    descriptor[0] = 0x2C;
    descriptor.writeUInt16LE(left, 1);
    descriptor.writeUInt16LE(top, 3);
    descriptor.writeUInt16LE(w, 5);
    descriptor.writeUInt16LE(h, 7);
    parts.push(descriptor, Buffer.from([0x08]), Buffer.from(subBlocks(lzw(w * h, colour))));
  };

  frame(0, 0, width, height, 40);                     // the background, once
  for (let i = 1; i < 10; i++) {
    frame(i * 16, 55, 40, 40, 200 - i * 8);           // a block crossing it
  }

  parts.push(Buffer.from([0x3B]));
  return Buffer.concat(parts);
}

/** One nine-bit code per pixel, with a clear code often enough that the width never grows. */
function lzw(pixels, colour) {
  const out = [];
  let acc = 0;
  let bits = 0;
  const push = (code) => {
    acc |= code << bits;
    bits += 9;
    while (bits >= 8) {
      out.push(acc & 0xFF);
      acc >>= 8;
      bits -= 8;
    }
  };

  push(256);
  let since = 0;
  for (let i = 0; i < pixels; i++) {
    push(colour);
    if (++since === 254) {
      push(256);
      since = 0;
    }
  }
  push(257);
  if (bits > 0) {
    out.push(acc & 0xFF);
  }
  return out;
}

/** The id Forge gives a review item: eight hex characters of SHA-256 over its text. */
function fingerprint(text) {
  return require("node:crypto").createHash("sha256").update(text.trim(), "utf8").digest("hex").slice(0, 8);
}

/** GIF image data is a chain of length-prefixed runs, ending at a zero length. */
function subBlocks(bytes) {
  const out = [];
  for (let at = 0; at < bytes.length; at += 255) {
    const slice = bytes.slice(at, at + 255);
    out.push(slice.length, ...slice);
  }
  out.push(0);
  return out;
}
