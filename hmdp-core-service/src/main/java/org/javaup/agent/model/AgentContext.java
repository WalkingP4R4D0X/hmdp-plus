package org.javaup.agent.model;

import lombok.Value;

@Value
public class AgentContext {
    Long userId;
    String conversationId;
}
