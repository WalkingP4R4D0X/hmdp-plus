package org.javaup.agent.tool;

/** Identifies the nearby-search boundary that failed without exposing internals to clients. */
public class NearbyShopQueryException extends RuntimeException {
    private final Stage stage;

    public NearbyShopQueryException(Stage stage, Throwable cause) {
        super(stage.name(), cause);
        this.stage = stage;
    }

    public Stage getStage() {
        return stage;
    }

    public enum Stage {
        GEO_SEARCH,
        GEO_RESULT_PARSE,
        SHOP_LOOKUP
    }
}
