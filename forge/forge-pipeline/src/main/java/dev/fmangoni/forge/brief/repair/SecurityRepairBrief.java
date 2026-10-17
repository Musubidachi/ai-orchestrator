package dev.fmangoni.forge.brief.repair;

import dev.fmangoni.forge.brief.TimeContract;
import dev.fmangoni.forge.security.model.SecurityFinding;
import java.time.Duration;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The one repair turn a serious security finding earns.
 *
 * <p>Different from the review repair in one respect that matters: several of these findings are
 * facts, not opinions. A committed key is in the history whether or not anyone agrees it matters,
 * and deleting the line does not remove it — so the brief says what a real fix is, and that the
 * person has to rotate the key themselves.
 */
@UtilityClass
public class SecurityRepairBrief {

    public String of(List<SecurityFinding> serious, Duration cap) {
        return """
                Forge's security check found the problems below in code that already passes its \
                acceptance commands. Fix each one, then make sure the acceptance commands still pass.

                %s
                How to fix the common ones properly:

                - A committed secret: remove it from the code and read it from an environment \
                variable or an untracked config file, and add that file to .gitignore. It stays in \
                the git history regardless, so say in your handoff which credential it was: the \
                person must revoke and reissue it, which you cannot do. Do not rewrite git history.
                - A vulnerable dependency: upgrade to the fixed version named, and run the tests. If \
                the upgrade breaks something, say so rather than pinning an older version back.
                - Input reflected into a page: escape it where it is written into HTML, or build the \
                element with textContent rather than innerHTML.
                - A served .git directory or .env file: stop the server from serving it — usually \
                by building into a separate output directory and serving only that.

                A finding marked "AI security review" came from another model reading the code, \
                which may be wrong. If one is mistaken, explain why instead of changing working \
                code to satisfy it.

                %s""".formatted(render(serious), TimeContract.of(cap));
    }

    private String render(List<SecurityFinding> findings) {
        StringBuilder out = new StringBuilder();
        int n = 1;
        for (SecurityFinding finding : findings) {
            out.append(n++).append(". [").append(finding.severity()).append(", ").append(finding.layer())
                    .append("] ").append(finding.title()).append('\n')
                    .append("   where: ").append(finding.location()).append('\n')
                    .append("   ").append(finding.evidence()).append("\n\n");
        }
        return out.toString();
    }
}
