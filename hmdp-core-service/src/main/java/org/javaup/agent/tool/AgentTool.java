package org.javaup.agent.tool;

import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** A read-only capability explicitly exposed to the agent orchestrator. */
public interface AgentTool<I, O> {
    String name();

    O execute(@NotNull @Valid I input, @Valid AgentContext context);

    static void requireShopId(Long shopId) {
        if (shopId == null || shopId <= 0) throw new IllegalArgumentException("shopId must be positive");
    }

    /** Limits text fields not constrained by the shared intent model. */
    static void validateIntent(AgentModels.Intent input) {
        if (input == null) throw new IllegalArgumentException("intent is required");
        if ((input.getKeyword() != null && input.getKeyword().length() > 100)
                || (input.getLocation() != null && input.getLocation().length() > 100)
                || (input.getScene() != null && input.getScene().length() > 100)
                || (input.getOpenAt() != null && !input.getOpenAt().matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))) {
            throw new IllegalArgumentException("intent text exceeds tool limits or openAt is invalid");
        }
    }
}
