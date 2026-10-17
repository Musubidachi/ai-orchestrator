# forge-report

What a person reads and hears about a project: the index and one page per project, each section a class of its own, the walkthrough the dashboard reads aloud, and the narrated code tour.

**Packages:** `report`, `report.deck`, `report.evidence`, `report.outward`, `report.review`, `report.section`, `report.shell`, `walkthrough`, `tour`, `tour.film`, `tour.model`, `tour.read`, `tour.read.model`, `tour.say`, `tour.show`, `tour.sound`. Each has a `package-info.java` saying what belongs in it, what does not, and what it may import.

**May use:** forge-marketing and below. Maven refuses anything higher; `LayersTest` holds the direction between packages inside.

**Rules:** It shows what other modules decided and decides nothing itself. Nothing here calls a model — the walkthrough is assembled from the run's files and the tour from the source and the notes beside it, so both cost no quota and cannot describe a project as anything other than what is on disk.

**The tour** (`forge tour`) reads a Maven parent, walks its child projects in the order the parent lists them, and explains each class a line at a time. Its layers are one-way and worth keeping that way: `tour.read` finds and parses (with the JDK's own parser, so there is no library to disagree with the language), `tour.say` decides the words, `tour` decides what the tour is and in what order, `tour.show` plays it back as a page or a folder of recordings, and `tour.film` as an mp4 when ffmpeg happens to be on the path. Adding another way to play it goes below `tour` and changes nothing above it.

**Sound and pictures** (`tour.sound`, `tour.film`) are generated, never fetched: the lo-fi bed, the tick and the chime are arithmetic, and the frames are drawn with Java2D and piped straight into ffmpeg rather than written out as tens of thousands of PNGs. Keep the music generator seeded — a build tool whose output differs between two runs of the same input cannot be diffed.

The wording rule in `tour.say` is *say something or say nothing*: a line with no honest reading comes back empty and is left out. Resist the temptation to add a fallback clause for the leftovers — narrating every line as "assigns the result to the variable" is what makes a tour unlistenable, and the empty string is the feature.

**Tests:** `ProjectReportTest` renders real pages from fixture runs; `WalkthroughTest` pins what is said aloud, including that a parked or unreviewed project is never spoken as though it were fine; `CodeTourTest` pins that the line lit up is the line being talked about; `LineSenseTest` pins the wording, including the lines that stay silent; `MotionTest` pins that the picture serves the voice (the sentence is up before the narration ends, the code has stopped sliding by then); `ScoreTest` pins that the score is exactly as long as the scenes and identical between runs. Run them alone with `mvn test -pl forge-report -am`.
