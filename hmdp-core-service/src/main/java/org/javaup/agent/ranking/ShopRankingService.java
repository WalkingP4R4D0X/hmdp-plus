package org.javaup.agent.ranking;

import org.javaup.agent.model.AgentModels;
import org.javaup.entity.Shop;
import org.springframework.stereotype.Service;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class ShopRankingService {
    public List<AgentModels.ShopCard> rank(List<Shop> shops, AgentModels.Intent intent) {
        return shops.stream().filter(s -> matches(s, intent)).map(s -> card(s, intent))
                .sorted(Comparator.comparingDouble(this::score).reversed()).limit(10).collect(Collectors.toList());
    }
    private boolean matches(Shop s, AgentModels.Intent i) {
        if (i.getBudgetMax() != null && s.getAvgPrice() != null && s.getAvgPrice() > i.getBudgetMax()) return false;
        if (i.getMinScore() != null && s.getScore() != null && s.getScore() / 10.0 < i.getMinScore()) return false;
        if (i.getRadiusMeter() != null && s.getDistance() != null && s.getDistance() > i.getRadiusMeter()) return false;
        return true;
    }
    private AgentModels.ShopCard card(Shop s, AgentModels.Intent i) {
        AgentModels.ShopCard c = new AgentModels.ShopCard(); c.setShopId(s.getId()); c.setName(s.getName()); c.setTypeId(s.getTypeId()); c.setAddress(s.getAddress()); c.setArea(s.getArea()); c.setDistanceMeter(s.getDistance()); c.setAveragePrice(s.getAvgPrice()); c.setScore(s.getScore() == null ? null : s.getScore() / 10.0); c.setOpenHours(s.getOpenHours()); c.setOpenNow(openNow(s.getOpenHours(), i.getOpenAt())); c.setMissingData(s.getAvgPrice() == null || s.getScore() == null || s.getOpenHours() == null); c.setReason(reason(c, i)); return c;
    }
    private boolean openNow(String hours, String at) { if (hours == null) return false; try { String[] p = hours.split("-"); LocalTime t = LocalTime.parse(at == null ? LocalTime.now().toString().substring(0,5) : at); return t.compareTo(LocalTime.parse(p[0])) >= 0 && t.compareTo(LocalTime.parse(p[1])) <= 0; } catch (Exception e) { return false; } }
    private String reason(AgentModels.ShopCard c, AgentModels.Intent i) { StringBuilder b = new StringBuilder(); if (c.getDistanceMeter()!=null) b.append("距离约").append(Math.round(c.getDistanceMeter())).append("米"); if (c.getAveragePrice()!=null) b.append(b.length()>0?"，":"").append("人均约").append(c.getAveragePrice()).append("元"); if (c.getScore()!=null) b.append(b.length()>0?"，":"").append("评分").append(String.format(Locale.ROOT,"%.1f",c.getScore())); if (Boolean.TRUE.equals(c.getOpenNow())) b.append("，当前营业"); return b.length()==0?"基于商户实时信息推荐":"符合你的条件："+b; }
    private double score(AgentModels.ShopCard c) {
        double distanceScore = c.getDistanceMeter() == null ? 0 : 1.0 / (1 + c.getDistanceMeter() / 1000);
        double priceScore = c.getAveragePrice() == null ? 0 : 1;
        double ratingScore = c.getScore() == null ? 0 : c.getScore() / 5;
        double openScore = Boolean.TRUE.equals(c.getOpenNow()) ? 1 : 0;
        return distanceScore * .25 + priceScore * .20 + ratingScore * .20 + openScore * .15;
    }
}
