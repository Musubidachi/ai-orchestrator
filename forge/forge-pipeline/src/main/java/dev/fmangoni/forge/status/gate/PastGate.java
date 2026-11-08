package dev.fmangoni.forge.status.gate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/**
 * One gate a version went through, as its panel lists it under "Gates on this version".
 *
 * @param session the session it followed
 * @param quick   a quick gate, which left the slow commands for the full one
 * @param passed  whether everything it ran passed, the gate's own checks included
 * @param failed  the command or check it stopped at, or empty
 * @param seconds what the commands it ran took
 * @param spared  for a quick gate, the usual time of the commands it left out: time nobody spent
 *                waiting. Zero for a full gate.
 * @param at      when it finished
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PastGate(int session, boolean quick, boolean passed, String failed, long seconds, long spared,
                       Instant at) {

    /** The gate {@code gate} describes, which followed session {@code session}. */
    static PastGate of(LastGate gate, int session, Instant at) {
        String failed = gate.commands().stream()
                .filter(command -> GateCommand.FAILED.equals(command.state()))
                .map(GateCommand::command)
                .findFirst()
                .orElse(gate.check());
        long spared = gate.commands().stream()
                .filter(command -> GateCommand.WAITS.equals(command.state()))
                .mapToLong(command -> command.usual() == null ? 0 : command.usual())
                .sum();
        return new PastGate(session, gate.quick(), gate.passed(), gate.passed() ? "" : failed, gate.seconds(),
                spared, at);
    }
}
