/**
 * The rules and shapes the layers work from.
 *
 * <p><b>Belongs here:</b> A secret's pattern, a static-analysis pattern, and one probe of a running
 * build.
 *
 * <p><b>Not here:</b> Any scanning. These describe what to look for; the layer looks.
 *
 * <p><b>Start with:</b> {@code SecretRule}, {@code PatternRule}, {@code BodyCheck}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.security.layer.model;
