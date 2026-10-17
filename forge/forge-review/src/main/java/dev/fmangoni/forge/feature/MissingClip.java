package dev.fmangoni.forge.feature;

import java.util.List;

/**
 * An approved feature the last gate found without a clip, as a question for a person.
 *
 * @param id      the feature's id, which a ruling on it is filed under
 * @param name    as it was approved
 * @param listed  whether the agent's handoff still lists it. An approved feature the agent has
 *                stopped listing has often only been renamed, and is then one feature owing two clips
 * @param sameAs  features the agent does list that have a clip, likeliest first: what it might be
 *                the same feature as
 * @param problem why it is still owed, as the gate put it
 * @param kept    whether a person said they still want it since the gate found it missing, so the
 *                question is answered and what remains is a session to film it
 */
public record MissingClip(String id, String name, boolean listed, List<String> sameAs, String problem,
                          boolean kept) {
}
