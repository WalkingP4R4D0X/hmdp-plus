package org.javaup.agent.tool;

import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;

/** A read-only capability explicitly exposed to the agent orchestrator. */
public interface AgentTool<I, O> {
    String name();

    O execute(I input, AgentContext context);
}
