package dev.fmangoni.forge.ui.pictures;

import lombok.Data;

/** What a person said about one picture on the review. */
@Data
public class RuleItem {

    /** The job's picture path, which names the job. */
    private String draft;

    /** The picture they saw: a ruling about an earlier picture is refused. */
    private String stamp;

    /** {@code keep} or {@code redo}. */
    private String verdict;

    /** What to fix, for a redo; why it stands, for a kept picture whose check found a problem. */
    private String note;
}
