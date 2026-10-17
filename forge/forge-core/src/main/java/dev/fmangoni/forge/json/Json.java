package dev.fmangoni.forge.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.experimental.UtilityClass;

/**
 * The one configured Jackson mapper.
 *
 * <p>Sixteen classes used to build their own, and the configuration drifted the way it always
 * does — quietly, and only where it had not yet mattered. Two failures came out of that, both
 * invisible:
 *
 * <ul>
 *   <li>{@code Http} had no {@link JavaTimeModule}, so when a quota window gained two
 *       {@code Instant}s the endpoint answered <b>200 with a truncated body</b>. Jackson throws
 *       part-way through writing, the status line has already gone, and a partial response reads
 *       downstream as success. The quota banner simply never appeared, with nothing anywhere
 *       saying why.
 *   <li>{@code DecisionStore} needed the same module added by hand the moment a comment gained a
 *       timestamp, which is the same bug arriving a second time and being caught by luck.
 * </ul>
 *
 * <p>Three more mappers are still one {@code Instant} away from the first of those. Rather than
 * wait to find out which, they all come from here.
 *
 * <p>Dates as ISO-8601 strings rather than epoch numbers, because these files are read by people:
 * a state file is the first thing anyone opens when a run has gone wrong, and
 * {@code 1789264033.417} is not an answer to "when did this stop".
 *
 * <p>A configured mapper is thread-safe for reading and writing, which is why one shared instance
 * is right rather than merely convenient.
 */
@UtilityClass
public class Json {

    private final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * The shared mapper.
     *
     * <p>Never reconfigure the returned instance — it is shared, and a {@code configure} call on
     * it changes behaviour for every caller in the process. If a genuinely different shape is ever
     * needed, {@link ObjectMapper#copy()} it.
     */
    public ObjectMapper mapper() {
        return MAPPER;
    }
}
