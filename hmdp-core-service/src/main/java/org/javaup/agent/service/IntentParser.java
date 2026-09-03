package org.javaup.agent.service;

import org.javaup.agent.model.AgentModels;
import java.util.List;

public interface IntentParser {
    AgentModels.Intent parse(String message, List<AgentModels.Message> history);
}
