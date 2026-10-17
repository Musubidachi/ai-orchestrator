package dev.fmangoni.forge.agent.ledger.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One vendor's last known quota state. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QuotaWindow {

    /** When the CLI told us it was out. */
    private Instant observedAt;

    /**
     * When it said to try again, or null when it did not say.
     *
     * <p>A limit reported without a reset time is recorded anyway: knowing a vendor is out
     * matters even when the wait is unknown, and the alternative is treating it as available.
     */
    private Instant retryAfter;

    private String message;
}
