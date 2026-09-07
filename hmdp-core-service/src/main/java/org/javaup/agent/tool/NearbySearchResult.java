package org.javaup.agent.tool;

import org.javaup.agent.model.ShopCandidate;

import java.util.List;

/** Structured outcome for nearby search so infrastructure failures are not reported as no results. */
public record NearbySearchResult(Status status, List<ShopCandidate> shops, int geoCandidateCount) {
    public NearbySearchResult {
        shops = shops == null ? List.of() : List.copyOf(shops);
    }

    public enum Status {
        SUCCESS,
        NO_LOCATION,
        INDEX_EMPTY,
        REDIS_UNAVAILABLE,
        NO_MATCH
    }

    public static NearbySearchResult success(List<ShopCandidate> shops, int geoCandidateCount) {
        return new NearbySearchResult(Status.SUCCESS, shops, geoCandidateCount);
    }

    public static NearbySearchResult empty(Status status) {
        return new NearbySearchResult(status, List.of(), 0);
    }
}
