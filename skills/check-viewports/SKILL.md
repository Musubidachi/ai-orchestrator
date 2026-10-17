---
name: check-viewports
description: Check that a web page or browser game lays out correctly at phone, phone-landscape, laptop and desktop sizes, with a script that screenshots each size and reports sideways scrolling, cut-off, undersized or stretched controls. Use after changing a web UI's layout, styles or theme, and before finishing any session that touched what a browser shows.
---

# Checking a page at every size it will be opened at

A layout tested only at a phone size can be broken on a desktop, and the other way round. It has
happened here: a restyle that made a game a phone-shaped column at every width left an old desktop
rule in force, and on a desktop window the buttons stretched into tall pills over the timer while
the start card was squeezed to half its width. Every test and screenshot was phone-sized, so the
gate passed it and a person found it.

## How

1. Build and start the page the way the person will see it (the preview of the built output, not
   the dev server's error overlay), on 127.0.0.1 and a port nothing else is using.
2. From the project folder, run the script beside this file:

   ```
   node <this skill's folder>/shoot-viewports.cjs http://127.0.0.1:4317/ artifacts/viewports
   node <this skill's folder>/shoot-viewports.cjs http://127.0.0.1:4317/ artifacts/viewports-playing --click "Start"
   ```

   It writes one screenshot per size and lists, for each, sideways scrolling, controls cut off at
   the edge, touch targets under 44px, and controls stretched far taller than wide. It exits 1 if
   anything was found.
3. **Open every screenshot and look at it.** The script catches the obvious; overlapping blocks,
   squeezed text and a layout that is merely ugly are for your eyes.
4. Stop the server you started.

## Make it stick

What you just checked by hand belongs in the browser tests the acceptance commands run, so the gate
catches the next restyle that breaks it: for each size the spec names (and at least one phone and
one desktop for anything a person opens in a browser), assert no sideways overflow, that the main
controls are inside the window and reachable, and that the main blocks keep their order without
overlapping. Save a screenshot per size into `artifacts/` from the test, as evidence.
