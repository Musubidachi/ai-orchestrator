package dev.fmangoni.forge.deploy.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * One version of a project as it stands on GitHub.
 *
 * @param project  the version's id, such as {@code 003.2}
 * @param branch   where it is, such as {@code main} or {@code 003.2-game-customization-and-animation}
 * @param commit   the commit the branch was last pushed to
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PublishedBranch(String project, String branch, String commit, Instant pushedAt) {
}
