# forge-review

Judging a project: the gate (Forge runs the acceptance commands itself), the cross-review and a person's rulings, the approved features and their recordings, and `state.json`, which records all of their results.

**Packages:** `feature`, `gate`, `review`, `review.decision`, `review.decision.model`, `review.feature`, `review.model`, `state`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** forge-security, forge-core. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** The gate decides done; nothing an agent says reaches its verdict. See `gate/CLAUDE.md`.
The cross-review is two passes by one reviewer: `FeatureReview` asks its cheapest model about each
claimed feature, then `ReviewStage` reviews the change once on its best model, holding what that
pass could not find. A refusal stops the cheap pass rather than repeating it per feature, and an
unchecked claim never reads as one that held up.

A review after a repair is also a recheck: `ReviewRound` hands the reviewer the previous blocking
findings by id, with the builder's answer to each, and the reply's `rechecks` say which are fixed.
A finding's id is `ReviewItem.idOf` — the same one the review form rules on — so a person's ruling,
the history, and the reviewer's verdict all name the same thing.

A recheck shows the reviewer the files its findings name, as they stand now, at most 30k characters
each. A longer file is shown around what the findings mention (`FileExcerpt`: the rarest code name in
their text, at its declaration), never just its top: 001's fix sat past a top-down cut for three
rounds. Every review also gets a counted, never-blocking note per source file over 400 lines
(`LongFiles`, tests and generated output excluded); the house rules say the same.

A person's rulings are kept twice, on purpose: `decisions.json` is the latest position on each item
(what a pending submission acts on), and `ReviewHistory` is every submitted sitting, only ever
appended to. The form never pre-ticks an item that appears in either — a ruling is about the item as
it stood, and carrying it forward is how an unfixed defect got waved through again.

**Tests:** `Clips` (writes real video files for recording tests) is published in this module's test-jar. Run them alone with `mvn test -pl forge-review -am`.

Package notes: `gate/CLAUDE.md`.
