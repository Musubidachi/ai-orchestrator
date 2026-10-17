/**
 * The HTML a person reads and acts on: the index of every project, and one page per project.
 *
 * <p><b>Belongs here:</b> {@code ProjectReport} and {@code IndexReport}, which put the sections in
 * order and nothing else: the order is the argument the page makes.
 *
 * <p><b>Not here:</b> The sections themselves: what happened ({@code report.section}), what it
 * would take to put it in front of people ({@code report.outward}), what you act on ({@code
 * report.review}), the artefacts ({@code report.evidence}) and the page shell ({@code
 * report.shell}).
 *
 * <p><b>Start with:</b> {@code ProjectReport.write}, {@code IndexReport.write}.
 *
 * <p>Depends on: deploy, feature, project, report.outward, report.review, report.section, report.shell, review.decision, review.model, security.model, state.
 */
package dev.fmangoni.forge.report;
