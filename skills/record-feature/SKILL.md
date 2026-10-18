---
name: record-feature
description: How to record an approved feature's clip (artifacts/feature-<slug>.webm, .mp4 or .gif) from an acceptance command, for a browser app (Playwright), a desktop UI (java.awt.Robot to GIF) or a command-line tool (rendered GIF). Use when adding or changing a feature, or when the gate says a feature has no usable recording.
---

# Recording a feature

The contract is in the house rules (`CLAUDE.md` / `AGENTS.md`, "Features, and recording them"): one
clip per approved feature, at `artifacts/feature-<slug>.webm` (or `.mp4` / `.gif`), produced by one
of the acceptance commands, never committed. This skill is how to make one.

## Why it has to come from an acceptance command

This is deliberate and it is not about trust. Nothing here can watch a video, so rather than trying
to judge whether a clip is genuine, Forge requires it to be produced from source by a command it
ran itself. It has a second effect worth as much: a recording regenerated on every gate is a
recording of what the code does *today*. A feature that quietly breaks in session nine stops having
a clip, and that shows up on the report as a feature with nothing to watch.

The file is also read rather than merely counted. A container that is not a video, a single frame,
a clip under a second, or a canvas under 160x120 is refused with the reason — so there is no
shortcut in writing an empty file with the right name. (Also refused: anything under 2 KB.)

## The file name

The slug is the feature's name lowercased, every run of characters other than `a-z` and `0-9`
turned into one hyphen, hyphens trimmed from both ends, and cut to 60 characters: `Monthly summary`
becomes `artifacts/feature-monthly-summary.webm`. The gate names the exact path for each clip it is
still waiting for, so copy it from there rather than working it out.

## Recording a feature

Record the real program doing the real thing. A clip of a mock, or of a screen the feature does not
actually drive, is worse than no clip — it is evidence of something that was never true.

- **Anything in a browser.** Playwright records for free: `use: { video: "on" }`, or
  `contextOptions.recordVideo: { dir: "artifacts/" }`. Drive the feature in a test, then rename the
  produced `.webm` to the feature's path in an `afterEach`. One test per feature, so one clip per
  feature — and because the test run is an acceptance command, the clip is regenerated every gate.
  `feature-clips.example.ts` beside this file is a working `afterEach` that does the renaming from
  the test's title.
- **A desktop UI (Swing, JavaFX, AWT).** Drive it with `java.awt.Robot` — `mouseMove`, `mousePress`,
  `keyPress` — and capture the window with `Robot.createScreenCapture` on a timer, writing the
  frames out as an animated GIF through `ImageIO` (`GIFSequenceWriter`, ~60 lines, no dependency).
  Ten to fifteen frames a second for a few seconds is plenty. Run it headed; a headless JVM has no
  `Robot` and no window to film. `GifSequenceWriter.java` beside this file is that class, tested:
  copy it into the project's test sources and change its package line.
- **A command-line tool.** Render the session rather than the terminal: run the real commands,
  capture the real output, and write it out as an animated GIF a few lines at a time so it reads as
  the tool running. The point is that someone can watch what using it is like without installing it.
  `TerminalGif.java` beside this file does the drawing (it needs `GifSequenceWriter.java`); feed it
  the real command and the real output your test just captured, never text typed into the test.

Whatever the stack: the clip should be a handful of seconds, start on something recognisable, and
end having shown the feature do its job. Nobody is going to watch a minute of it.

## Before you finish

Run the acceptance command that makes the clips, then check each `artifacts/feature-*.` file exists,
is more than a second long, and shows the feature — open it and look. Do not commit the clips;
Forge keeps `artifacts/feature-*` out of git and records them afresh at every gate.
