// Usage: node verify-migration.js <backup.md> <candidate.md> <skillsDir>
// Every heading, paragraph, bullet and code block of the backup must still exist word for word
// (whitespace and heading level aside) in the candidate or in one of the skills. Exits 1 if not.
const fs = require("node:fs");
const path = require("node:path");

const [backupPath, candidatePath, skillsDir] = process.argv.slice(2);
const read = (p) => fs.readFileSync(p, "utf8").replace(/\r\n/g, "\n");
const norm = (s) => s.replace(/^#+\s*/gm, "").replace(/^\s*-\s+/gm, "").replace(/\s+/g, " ").trim();

function units(text) {
  const out = [];
  const lines = text.split("\n");
  let block = [];
  let fence = false;
  const flush = () => { if (block.length) out.push(block.join("\n")); block = []; };
  for (const line of lines) {
    if (line.startsWith("```")) {
      block.push(line);
      fence = !fence;
      if (!fence) flush();
      continue;
    }
    if (fence) { block.push(line); continue; }
    if (line.trim() === "") { flush(); continue; }
    // A heading, or a new top-level bullet, starts a unit of its own.
    if (/^#+ /.test(line) || /^- /.test(line)) { flush(); }
    block.push(line);
    if (/^#+ /.test(line)) flush();
  }
  flush();
  return out;
}

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
}

const candidate = norm(read(candidatePath));
const skills = walk(skillsDir).filter((f) => /\.(md|java|ts|cjs|js)$/.test(f))
  .map((f) => ({ file: path.relative(skillsDir, f), text: norm(read(f)) }));

let missing = 0, inRules = 0, inSkills = 0;
const moved = {};
for (const unit of units(read(backupPath))) {
  const n = norm(unit);
  if (!n) continue;
  if (candidate.includes(n)) { inRules++; continue; }
  const home = skills.find((s) => s.text.includes(n));
  if (home) { inSkills++; (moved[home.file] ||= []).push(n.slice(0, 70)); continue; }
  missing++;
  console.log("MISSING: " + unit.split("\n")[0].slice(0, 110));
}
console.log(`\n${inRules} units still in the house rules, ${inSkills} moved to skills, ${missing} missing.`);
for (const [file, list] of Object.entries(moved)) {
  console.log(`\nmoved to ${file}:`);
  list.forEach((l) => console.log("  - " + l + "..."));
}
process.exit(missing ? 1 : 0);
