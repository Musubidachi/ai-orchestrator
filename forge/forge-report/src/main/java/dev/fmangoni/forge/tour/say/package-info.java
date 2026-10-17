/**
 * Turning code into sentences, without asking anyone.
 *
 * <p><b>Belongs here:</b> {@code Doc} (a javadoc note as prose), {@code Phrase} and {@code
 * Condition} (an expression and a test as words), {@code LineSense} (what one line does), and the
 * three storytellers — {@code MethodStory}, {@code TypeStory}, {@code ModuleStory} — that turn what
 * was parsed into scenes and chapters.
 *
 * <p><b>Not here:</b> Reading files ({@code tour.read}) or playing the result back ({@code
 * tour.show}). And no model, ever: every sentence is derived from the source and the notes already
 * written beside it, which is what makes a tour free to regenerate and unable to describe code as
 * anything other than what it says.
 *
 * <p>The rule the wording works to: say something or say nothing. A line with no honest reading
 * comes back empty and is left out, because a tour that narrates every line as "assigns the result
 * to the variable" is one nobody listens to twice.
 *
 * <p><b>Start with:</b> {@code TypeStory.of}, {@code LineSense.of}.
 *
 * <p>Depends on: tour.model, tour.read.model, walkthrough.
 */
package dev.fmangoni.forge.tour.say;
