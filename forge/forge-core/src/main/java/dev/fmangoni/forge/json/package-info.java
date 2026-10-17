/**
 * The one configured Jackson mapper, and forgiving readers for JSON that people and models leave
 * behind.
 *
 * <p><b>Belongs here:</b> Mapper configuration ({@code Json}), reading a JSON file that may be
 * hand-edited or half-written ({@code JsonFile}), and finding a JSON object inside prose ({@code
 * JsonText}).
 *
 * <p><b>Not here:</b> Anything that knows what the JSON means. Types that are persisted as JSON
 * live with their domain and use {@code Json.mapper()}; {@code JsonTest} forbids private mappers.
 *
 * <p><b>Start with:</b> {@code Json.mapper()}, {@code JsonFile}, {@code JsonText}.
 *
 * <p>Depends on: nothing.
 */
package dev.fmangoni.forge.json;
