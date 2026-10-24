/**
 * The groups a project's status is told in: identity, progress, budget, handoff, review and
 * outward.
 *
 * <p><b>Belongs here:</b> One {@code @Value} class per group, each knowing how to build itself
 * from the run's files. Classes rather than records because they carry a Lombok builder, and
 * Lombok builds a record under javac but not under Eclipse's compiler — {@code
 * ArchitectureTest.noBuilderOnRecords} keeps that from coming back.
 *
 * <p><b>Not here:</b> Assembling them, which is {@code JobStatus}. Each group is unwrapped on the
 * wire, so the dashboard still receives one flat object.
 *
 * <p><b>Start with:</b> {@code StatusProgress.of}, {@code StatusBudget.of} and the other groups.
 *
 * <p>Depends on: deploy, deploy.github, deploy.shelf, design, feature, gate, marketing, plan, project, review, review.decision, review.model, security.model, spec.model, state, status.read.
 */
package dev.fmangoni.forge.status.model;
