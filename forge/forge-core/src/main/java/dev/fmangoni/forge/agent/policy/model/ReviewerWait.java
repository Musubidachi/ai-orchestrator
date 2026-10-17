package dev.fmangoni.forge.agent.policy.model;

import dev.fmangoni.forge.agent.model.AgentKind;
import java.time.Instant;

/** A reviewer and when its window reopens. */
public record ReviewerWait(AgentKind kind, Instant until) { }
