package dev.fmangoni.forge.agent.model;














/**
 * A coding agent Forge can drive headlessly.
 *
 * <p>Implementations translate an {@link AgentTask} into that vendor's CLI invocation and normalise
 * the result. They deliberately do not judge success: an agent reporting that it finished is only
 * ever an input to the report, never to the decision about whether a project is done.
 */
public interface Agent {

    AgentKind kind();

    AgentResult run(AgentTask task);
}
