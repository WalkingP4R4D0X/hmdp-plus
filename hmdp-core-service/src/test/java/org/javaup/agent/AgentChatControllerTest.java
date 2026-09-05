package org.javaup.agent;

import org.javaup.agent.controller.AgentChatController;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentChatControllerTest {
    @Test
    void conversationEndpointsDeclareTheirPathVariableName() throws NoSuchMethodException {
        Method messages = AgentChatController.class.getMethod("messages", String.class);
        Method delete = AgentChatController.class.getMethod("delete", String.class);

        assertEquals("id", new MethodParameter(messages, 0).getParameterAnnotation(PathVariable.class).value());
        assertEquals("id", new MethodParameter(delete, 0).getParameterAnnotation(PathVariable.class).value());
    }

    @Test
    void controllerIsGuardedByTheAgentFeatureFlag() {
        ConditionalOnProperty condition = AgentChatController.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("agent", condition.prefix());
        assertEquals("enabled", condition.name()[0]);
        assertEquals("true", condition.havingValue());
    }
}
