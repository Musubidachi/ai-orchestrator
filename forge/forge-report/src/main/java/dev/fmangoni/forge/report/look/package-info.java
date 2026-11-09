/**
 * The look of a project page, as the mockup draws it: a header that says the phase once, the
 * page's own notice as a banner with a way to the review, features without a recording as one line,
 * each gap as a card of its own, the checks' times drawn to scale, the rail as a card and the review
 * bar along the bottom of the window.
 *
 * <p><b>Belongs here:</b> {@code ReportLook}, the script it puts after the guide ({@code look.js},
 * in the resources beside it), and the parts the script places, which are written from the
 * project's state: the header's chip and line ({@code LookHead}) and the times ({@code LookTimes}).
 * They go in a template, so the words are the page's and none of them is in the script.
 *
 * <p><b>Not here:</b> Any section's content, and anything the review form reads or sends. The look
 * moves and hides the page's own elements; it adds no form, no button and no row the form reads.
 *
 * <p><b>Start with:</b> {@code ReportLook.render}, then {@code look.js}.
 *
 * <p>Depends on: project, report.shell, state.
 */
package dev.fmangoni.forge.report.look;
