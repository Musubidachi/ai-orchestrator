/**
 * The rail of a project page's sections, and the bar that keeps the review in reach.
 *
 * <p><b>Belongs here:</b> {@code ReportGuide}, and the script it puts at the end of the page
 * ({@code guide.js}, in the resources beside it). What the rail counts and what the bar says are
 * read off the page in the browser.
 *
 * <p><b>Not here:</b> Any section's content, and anything the review form sends. The guide adds no
 * form, no submit button and no row the form reads; its Submit is the form's own.
 *
 * <p><b>Start with:</b> {@code ReportGuide.render}, then {@code guide.js}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.report.guide;
