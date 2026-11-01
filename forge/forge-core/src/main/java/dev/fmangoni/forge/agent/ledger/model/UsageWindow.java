package dev.fmangoni.forge.agent.ledger.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.fmangoni.forge.agent.stream.Usage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UsageWindow {
    private Instant startedAt;
    private long inputTokens;
    private long cachedTokens;
    private long outputTokens;
    private double costUsd;
    private int turns;

    /**
     * When the vendor said the window before this one would reset; null when it named no time.
     * A refusal before then means that window never reset, so this one has not really begun.
     */
    private Instant opensAt;

    /** Totals of previous windows, each recorded at the moment the vendor refused. */
    private List<Long> pastCapacities = new ArrayList<>();

    public Usage spent() {
        return new Usage(inputTokens, cachedTokens, outputTokens, costUsd);
    }
}
