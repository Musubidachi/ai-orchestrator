package dev.fmangoni.forge.security.session;

/**
 * One thing an agent did that the session audit judges: a command it ran, or a file it wrote.
 *
 * @param log    the log it came from, by file name
 * @param write  true for a file written, false for a command run
 * @param text   the command line, or the file's path as the agent gave it
 * @param output what the command printed, cut short; empty for a write, or when the log has none
 */
public record AgentAction(String log, boolean write, String text, String output) {

    public AgentAction {
        text = text == null ? "" : text;
        output = output == null ? "" : output;
    }

    public AgentAction withOutput(String printed) {
        return new AgentAction(log, write, text, printed);
    }
}
