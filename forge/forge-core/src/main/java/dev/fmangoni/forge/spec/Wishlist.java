package dev.fmangoni.forge.spec;

import dev.fmangoni.forge.spec.model.Spec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The wishlist as a graph: projects, and the improvements requested against them.
 *
 * <p>A sub-spec is a change request against code that already exists. It runs in its parent's
 * repository rather than a fresh one, which is what makes it an improvement rather than a rewrite.
 */
public final class Wishlist {

    private final List<Spec> specs;
    private final Map<String, Spec> byId = new LinkedHashMap<>();

    public Wishlist(List<Spec> specs) {
        this.specs = List.copyOf(specs);
        specs.forEach(spec -> byId.putIfAbsent(spec.id(), spec));
    }

    public static Wishlist load(Path wishlistDir) {
        return new Wishlist(SpecLoader.loadAll(wishlistDir));
    }

    public List<Spec> all() {
        return specs;
    }

    public Optional<Spec> byId(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<Spec> parentOf(Spec spec) {
        return spec.parentId().flatMap(this::byId);
    }

    /**
     * Ordering for everything: priority first, then id as a stable tie-break.
     *
     * <p>Id breaks ties rather than filename or load order so the sequence is reproducible across
     * machines and does not shift when a spec is renamed.
     */
    public static final Comparator<Spec> BY_PRIORITY =
            Comparator.comparingInt(Spec::priority).thenComparing(Spec::id);

    /** Improvements requested against a project, in the order they should be worked. */
    public List<Spec> improvementsTo(String parentId) {
        return specs.stream()
                .filter(spec -> spec.parentId().filter(parentId::equals).isPresent())
                .sorted(BY_PRIORITY)
                .toList();
    }

    /** Standalone projects, most important first. */
    public List<Spec> roots() {
        return specs.stream().filter(spec -> !spec.isImprovement()).sorted(BY_PRIORITY).toList();
    }

    /**
     * The id whose run directory owns the repository this spec builds in.
     *
     * <p>Improvements chain: an improvement to an improvement still works on the original project's
     * code. Following the chain to its root is what keeps them all in one repository with one
     * history, rather than scattering related work across unrelated checkouts.
     */
    public String repoOwner(Spec spec) {
        Spec current = spec;
        // Bounded by the number of specs, so a cycle someone wrote by hand cannot hang the run.
        for (int hops = 0; hops < specs.size() + 1; hops++) {
            Optional<Spec> parent = parentOf(current);
            if (parent.isEmpty()) {
                return current.id();
            }
            current = parent.get();
        }
        return current.id();
    }

    /**
     * Everything that must pass for this spec to be accepted.
     *
     * <p>An improvement is judged on its own acceptance commands <em>and</em> on every ancestor's,
     * because a change that delivers the new behaviour by breaking the old one is not an
     * improvement. Ancestors run first, so a regression is reported before the new feature's own
     * checks bury it.
     */
    public List<String> effectiveAcceptance(Spec spec) {
        List<String> commands = new ArrayList<>();
        for (Spec ancestor : ancestry(spec)) {
            ancestor.acceptance().stream()
                    .filter(command -> !commands.contains(command))
                    .forEach(commands::add);
        }
        spec.acceptance().stream()
                .filter(command -> !commands.contains(command))
                .forEach(commands::add);
        return commands;
    }

    /** Evidence the spec asks for, plus everything its ancestors already demanded. */
    public List<String> effectiveEvidence(Spec spec) {
        List<String> globs = new ArrayList<>();
        for (Spec ancestor : ancestry(spec)) {
            ancestor.evidence().stream().filter(g -> !globs.contains(g)).forEach(globs::add);
        }
        spec.evidence().stream().filter(g -> !globs.contains(g)).forEach(globs::add);
        return globs;
    }

    /** Ancestors oldest first, excluding the spec itself. */
    public List<Spec> ancestry(Spec spec) {
        List<Spec> chain = new ArrayList<>();
        Spec current = spec;
        for (int hops = 0; hops < specs.size() + 1; hops++) {
            Optional<Spec> parent = parentOf(current);
            if (parent.isEmpty()) {
                break;
            }
            chain.add(0, parent.get());
            current = parent.get();
        }
        return chain;
    }

    /**
     * An improvement naming a parent that is not in the wishlist.
     *
     * <p>Reported rather than silently treated as a standalone project: it would otherwise scaffold
     * an empty repository and try to build an improvement to nothing.
     */
    public boolean isOrphan(Spec spec) {
        return spec.parentId().isPresent() && parentOf(spec).isEmpty();
    }
}
