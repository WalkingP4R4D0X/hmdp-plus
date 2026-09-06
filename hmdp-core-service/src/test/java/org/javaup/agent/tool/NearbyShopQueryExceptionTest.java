package org.javaup.agent.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class NearbyShopQueryExceptionTest {
    @Test
    void retainsTheFailedBoundaryAndOriginalCause() {
        IllegalStateException cause = new IllegalStateException("Redis GEO command failed");
        NearbyShopQueryException exception = new NearbyShopQueryException(
                NearbyShopQueryException.Stage.GEO_SEARCH, cause);

        assertEquals(NearbyShopQueryException.Stage.GEO_SEARCH, exception.getStage());
        assertSame(cause, exception.getCause());
    }
}
