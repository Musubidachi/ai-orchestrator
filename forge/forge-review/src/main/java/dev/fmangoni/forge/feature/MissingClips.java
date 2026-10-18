package dev.fmangoni.forge.feature;

import dev.fmangoni.forge.plan.Plan;
import dev.fmangoni.forge.review.decision.model.Decision;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.experimental.UtilityClass;

/**
 * The clips a project still owes, put as questions a person can answer, and the sign that an agent
 * is chasing one feature under two names.
 *
 * <p>Both exist because of one project. 003 had "Report customization" approved, the agent renamed
 * the feature "Report export", and that was approved too: one feature, two approvals, two clips
 * owed. The agent had one test for it and renamed its clip from one name to the other each session,
 * so every gate found exactly one of the two missing — a different one each time — until the
 * session budget ran out. Nothing the agent can do settles that; only a person can say which name
 * stays, and until now nothing asked them.
 */
@UtilityClass
public class MissingClips {

    /** Words too common to say two feature names are about the same thing. */
    private static final Set<String> COMMON = Set.of("a", "an", "the", "and", "or", "of", "for",
            "to", "from", "in", "on", "at", "with", "after", "before", "by", "is");

    /**
     * Every approved feature without a usable clip on disk, with what it might be the same as.
     *
     * <p>Read from the repository as the last gate left it, so it answers "what did the gate find
     * missing" without running anything.
     */
    public List<MissingClip> of(Path repo, Plan plan, List<Decision> rulings) {
        return of(repo, plan, rulings, List.of());
    }

    /** @param kept ids of the missing clips a person has said they still want since the last gate */
    public List<MissingClip> of(Path repo, Plan plan, List<Decision> rulings, List<String> kept) {
        List<Feature> listed = Feature.of(plan);
        List<Feature> owed = Features.owed(Features.knownTo(plan), rulings);
        Set<String> listedIds = ids(listed);
        Set<String> owedIds = ids(owed);
        List<String> filmed = listed.stream()
                .filter(feature -> owedIds.contains(feature.id()))
                .filter(feature -> Features.recordingOf(repo, feature).isPresent())
                .map(Feature::name)
                .toList();

        List<MissingClip> out = new ArrayList<>();
        for (RecordingTake take : Features.unrecorded(repo, owed)) {
            Feature feature = take.feature();
            out.add(new MissingClip(feature.id(), feature.name(), listedIds.contains(feature.id()),
                    likeliest(feature.name(), filmed), take.problem(),
                    kept != null && kept.contains(feature.id())));
        }
        return out;
    }

    /**
     * Why two gates in a row look like one feature filmed under two names, or empty when they do not.
     *
     * <p>The sign is a clip that was missing and is now there, beside a clip that was there and is
     * now missing. A feature newly approved since the last gate is missing without ever having been
     * filmed, so it does not count: only a feature both gates owed can have been broken by the fix.
     *
     * @param owedBefore    ids the previous gate owed a clip
     * @param missingBefore ids the previous gate found without one
     */
    public Optional<String> moved(List<String> owedBefore, List<String> missingBefore,
                                  List<Feature> owedNow, List<RecordingTake> missingNow) {
        Map<String, Feature> owed = owedNow.stream()
                .collect(Collectors.toMap(Feature::id, Function.identity(), (a, b) -> a));
        Set<String> stillMissing = missingNow.stream()
                .map(take -> take.feature().id())
                .collect(Collectors.toSet());

        List<String> fixed = missingBefore.stream()
                .filter(owed::containsKey)
                .filter(id -> !stillMissing.contains(id))
                .map(id -> owed.get(id).name())
                .toList();
        List<String> broken = missingNow.stream()
                .map(RecordingTake::feature)
                .filter(feature -> owedBefore.contains(feature.id()))
                .filter(feature -> !missingBefore.contains(feature.id()))
                .map(Feature::name)
                .toList();
        if (fixed.isEmpty() || broken.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("the recordings moved between names: the last gate was missing "
                + quoted(fixed) + ", and this one has it but is missing " + quoted(broken)
                + " instead. That is usually one feature approved under two names, which only a "
                + "person can settle");
    }

    /** The filmed features sharing the most words with this one, keeping their order on a tie. */
    private List<String> likeliest(String name, List<String> filmed) {
        Set<String> words = words(name);
        return filmed.stream()
                .filter(other -> !other.equalsIgnoreCase(name))
                .sorted(Comparator.comparingInt((String other) -> shared(words, other)).reversed())
                .toList();
    }

    private int shared(Set<String> words, String other) {
        Set<String> common = new HashSet<>(words(other));
        common.retainAll(words);
        return common.size();
    }

    private Set<String> words(String name) {
        return Arrays.stream(name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isBlank() && !COMMON.contains(word))
                .collect(Collectors.toSet());
    }

    private Set<String> ids(List<Feature> features) {
        return features.stream().map(Feature::id).collect(Collectors.toSet());
    }

    private String quoted(List<String> names) {
        return names.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(" and "));
    }
}
