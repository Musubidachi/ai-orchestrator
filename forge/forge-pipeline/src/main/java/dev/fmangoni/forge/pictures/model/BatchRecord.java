package dev.fmangoni.forge.pictures.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/** A batch's {@code record.json}: what Forge kept about each job, by the job's draft path. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BatchRecord {

    private Map<String, JobRecord> jobs = new LinkedHashMap<>();

    /** The job's record, or an empty one, which is not added. */
    public JobRecord find(String draft) {
        return jobs.getOrDefault(draft, new JobRecord());
    }

    /** The job's record, added when it has none, for a change that is about to be saved. */
    public JobRecord edit(String draft) {
        return jobs.computeIfAbsent(draft, key -> new JobRecord());
    }
}
