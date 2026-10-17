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

    /** Totals of previous windows, each recorded at the moment the vendor refused. */
    private List<Long> pastCapacities = new ArrayList<>();

    public Usage spent() {
        return new Usage(inputTokens, cachedTokens, outputTokens, costUsd);
    }
}
