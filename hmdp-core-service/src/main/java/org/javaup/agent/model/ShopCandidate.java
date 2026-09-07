package org.javaup.agent.model;

import lombok.Data;
import org.javaup.entity.Shop;

import java.util.Objects;

/** Only the business evidence needed by agent filtering and presentation. */
@Data
public class ShopCandidate {
    private Long id;
    private String name;
    private Long typeId;
    private String address;
    private String area;
    private Long avgPrice;
    private Integer score;
    private String openHours;
    private Double distance;

    public static ShopCandidate from(Shop shop) {
        Objects.requireNonNull(shop, "shop");
        ShopCandidate candidate = new ShopCandidate();
        candidate.setId(shop.getId());
        candidate.setName(shop.getName());
        candidate.setTypeId(shop.getTypeId());
        candidate.setAddress(shop.getAddress());
        candidate.setArea(shop.getArea());
        candidate.setAvgPrice(shop.getAvgPrice());
        candidate.setScore(shop.getScore());
        candidate.setOpenHours(shop.getOpenHours());
        candidate.setDistance(shop.getDistance());
        return candidate;
    }
}
