package dev.fmangoni.forge.status.spend;

import dev.fmangoni.forge.state.RunState;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The running sums while the runs are read, and the one place the arithmetic happens. */
final class Totals {

    private long total;
    private int projects;
    private final Map<String, Long> byModel = new LinkedHashMap<>();
    private final Map<String, Integer> projectsPerModel = new LinkedHashMap<>();

    /** Counts one project, if it has spent anything at all. */
    void add(RunState state) {
        Map<String, Long> split = state.getTokensByModel();
        if (state.getTokensSpent() <= 0 && (split == null || split.isEmpty())) {
            return;
        }
        projects++;
        total += state.getTokensSpent();
        if (split == null) {
            return;
        }
        split.forEach((model, tokens) -> {
            byModel.merge(model, tokens, Long::sum);
            projectsPerModel.merge(model, 1, Integer::sum);
        });
    }

    Spending spending() {
        long attributed = byModel.values().stream().mapToLong(Long::longValue).sum();
        return Spending.builder()
                .total(total)
                .attributed(attributed)
                // A project's own total is the authority, so a split that somehow came out larger
                // is a bug in the recording rather than headroom to report as negative.
                .unattributed(Math.max(0, total - attributed))
                .projects(projects)
                .byModel(rows())
                .build();
    }

    private List<ModelSpend> rows() {
        return byModel.entrySet().stream()
                .map(entry -> row(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(ModelSpend::getTokens).reversed()
                        .thenComparing(ModelSpend::getModel))
                .toList();
    }

    /** Splits a {@code VENDOR/model} key, tolerating one written before the vendor was included. */
    private ModelSpend row(String key, long tokens) {
        int slash = key.indexOf('/');
        return ModelSpend.builder()
                .vendor(slash < 0 ? "" : key.substring(0, slash))
                .model(slash < 0 ? key : key.substring(slash + 1))
                .tokens(tokens)
                .projects(projectsPerModel.getOrDefault(key, 0))
                .build();
    }
}
