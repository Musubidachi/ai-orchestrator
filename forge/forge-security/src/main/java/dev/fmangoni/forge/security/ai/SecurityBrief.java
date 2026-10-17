package dev.fmangoni.forge.security.ai;

import dev.fmangoni.forge.security.model.SecurityFinding;
import dev.fmangoni.forge.spec.model.Spec;
import java.util.List;
import lombok.experimental.UtilityClass;

/**
 * The AI security review: another model reads the change for vulnerabilities, with the scanners'
 * results in front of it.
 *
 * <p>Separate from the cross-review because the question is different. The cross-review asks
 * whether the code is right; this asks how it could be abused, and a reviewer asked both at once
 * gives the second a paragraph at the end.
 *
 * <p>The scanners' notes are included so the model can do what pattern matching cannot: say whether
 * an {@code innerHTML} assignment actually carries user input. Its {@code confirmed} flag is what
 * separates a finding worth a repair turn from a hunch.
 */
@UtilityClass
public class SecurityBrief {

    public String of(Spec spec, String diff, List<SecurityFinding> scanned) {
        return """
                Review this project for security problems. Do not modify any files and do not run \
                commands; this session is read-only on purpose, and a refused tool call is expected. \
                Reply with JSON only.

                You are looking for ways the program could be abused by someone using it or sending \
                it input: injection of any kind (HTML/script, SQL, shell, path), unsafe handling of \
                files, processes or deserialization, secrets in code or in what is shipped to a \
                browser, missing authorisation on anything that changes data, requests to addresses \
                a user controls, and anything that trusts data from the network or the page.

                Judge by what the code does, not by what it resembles. An innerHTML assignment of a \
                constant string is not XSS; a query built from an internal enum is not injection. \
                Only mark a finding "confirmed" when you can point to the path untrusted input takes \
                to the dangerous call. Unconfirmed findings are still worth listing as notes.

                Automated scanners have already run. Their results are below. For each one, either \
                include it in your findings (confirmed or not) or list it under "dismissed" with the \
                reason. Do not invent problems to look thorough — an empty list is a valid answer.

                Reply with exactly this shape:

                {"findings":[{"severity":"critical|high|medium|low","confirmed":true,"file":"src/x.ts", \
                "title":"one line","detail":"the path from input to the dangerous call, and the fix", \
                "cwe":"CWE-79"}], \
                "dismissed":[{"location":"as given by the scanner","why":"one line"}]}

                ## Spec

                %s

                ## Scanner results

                %s

                ## Code

                ```diff
                %s
                ```
                """.formatted(spec.body(), scanned(scanned), diff);
    }

    private String scanned(List<SecurityFinding> findings) {
        if (findings.isEmpty()) {
            return "The scanners found nothing.\n";
        }
        StringBuilder out = new StringBuilder();
        for (SecurityFinding finding : findings) {
            out.append("- [").append(finding.layer()).append(", ").append(finding.severity()).append("] ")
                    .append(finding.title()).append(" — ").append(finding.location()).append('\n')
                    .append("  ").append(finding.evidence()).append('\n');
        }
        return out.toString();
    }
}
