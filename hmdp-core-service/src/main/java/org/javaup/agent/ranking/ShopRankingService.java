package org.javaup.agent.ranking;

import org.javaup.agent.model.AgentModels;
import org.javaup.agent.model.ShopCandidate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ShopRankingService {
    private static final Pattern INTERVAL = Pattern.compile("(\\d{1,2}):([0-5]\\d)\\s*[-~～—–至]\\s*(\\d{1,2}):([0-5]\\d)");
    private final Clock clock;

    public ShopRankingService() {
        this(Clock.systemDefaultZone());
    }

    ShopRankingService(Clock clock) {
        this.clock = clock;
    }

    public List<AgentModels.ShopCard> rank(List<ShopCandidate> shops, AgentModels.Intent intent) {
        return rankCandidates(shops, intent).stream().limit(10).toList();
    }

    /** Keep all filtered candidates until voucher enrichment and voucher filtering finish. */
    public List<AgentModels.ShopCard> rankCandidates(List<ShopCandidate> shops, AgentModels.Intent intent) {
        LocalTime now = LocalTime.now(clock);
        return shops.stream().filter(s -> matches(s, intent)).map(s -> card(s, now))
                .sorted(Comparator.comparingDouble(this::score).reversed()).toList();
    }

    private boolean matches(ShopCandidate shop, AgentModels.Intent intent) {
        if (shop == null) return false;
        if (shop.getDistance() != null && (!Double.isFinite(shop.getDistance()) || shop.getDistance() < 0)) return false;
        if (intent.getBudgetMax() != null && (shop.getAvgPrice() == null || shop.getAvgPrice() < 0
                || shop.getAvgPrice() > intent.getBudgetMax())) return false;
        if (intent.getMinScore() != null && (shop.getScore() == null || shop.getScore() < 0
                || shop.getScore() > 50 || shop.getScore() / 10.0 < intent.getMinScore())) return false;
        if (intent.getRadiusMeter() != null && (shop.getDistance() == null
                || shop.getDistance() > intent.getRadiusMeter())) return false;
        if (intent.getOpenAt() != null) {
            try {
                if (!Boolean.TRUE.equals(openAt(shop.getOpenHours(), LocalTime.parse(intent.getOpenAt())))) return false;
            } catch (DateTimeParseException e) {
                return false;
            }
        }
        return true;
    }

    private AgentModels.ShopCard card(ShopCandidate shop, LocalTime now) {
        AgentModels.ShopCard card = new AgentModels.ShopCard();
        card.setShopId(shop.getId());
        card.setName(shop.getName());
        card.setTypeId(shop.getTypeId());
        card.setAddress(shop.getAddress());
        card.setArea(shop.getArea());
        card.setDistanceMeter(shop.getDistance());
        card.setAveragePrice(shop.getAvgPrice() == null || shop.getAvgPrice() < 0 ? null : shop.getAvgPrice());
        card.setScore(shop.getScore() == null || shop.getScore() < 0 || shop.getScore() > 50 ? null : shop.getScore() / 10.0);
        card.setOpenHours(shop.getOpenHours());
        // openAt is a requested filter time; the card reports actual current opening state.
        card.setOpenNow(openAt(shop.getOpenHours(), now));
        card.setMissingData(card.getAveragePrice() == null || card.getScore() == null
                || card.getOpenNow() == null || card.getDistanceMeter() == null
                || card.getShopId() == null || blank(card.getName()) || blank(card.getAddress()));
        card.setReason(reason(card));
        return card;
    }

    /** Unknown or partly malformed schedules remain unknown; closing endpoints are exclusive. */
    static Boolean openAt(String hours, LocalTime at) {
        if (blank(hours)) return null;
        String schedule = hours.trim().replace('：', ':');
        if (List.of("24小时", "24小时营业", "全天", "全天营业").contains(schedule)) return true;
        Matcher matcher = INTERVAL.matcher(schedule);
        int lastEnd = 0;
        boolean found = false;
        boolean open = false;
        int minute = at.getHour() * 60 + at.getMinute();
        while (matcher.find()) {
            if (!schedule.substring(lastEnd, matcher.start()).matches("[\\s,，;；、/|]*")) return null;
            int startHour = Integer.parseInt(matcher.group(1));
            int endHour = Integer.parseInt(matcher.group(3));
            int start = startHour * 60 + Integer.parseInt(matcher.group(2));
            int end = endHour * 60 + Integer.parseInt(matcher.group(4));
            if (startHour > 23 || endHour > 24 || end > 1440 || start == end) return null;
            open |= start < end ? minute >= start && minute < end : minute >= start || minute < end;
            found = true;
            lastEnd = matcher.end();
        }
        if (!found || !schedule.substring(lastEnd).matches("[\\s,，;；、/|]*")) return null;
        return open;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String reason(AgentModels.ShopCard card) {
        StringJoiner reason = new StringJoiner("，");
        if (card.getDistanceMeter() != null) reason.add("距离约" + Math.round(card.getDistanceMeter()) + "米");
        if (card.getAveragePrice() != null) reason.add("人均约" + card.getAveragePrice() + "元");
        if (card.getScore() != null) reason.add("评分" + String.format(Locale.ROOT, "%.1f", card.getScore()));
        if (Boolean.TRUE.equals(card.getOpenNow())) reason.add("当前营业");
        return reason.length() == 0 ? "商户信息缺失，暂无可核实的推荐理由" : "符合你的条件：" + reason;
    }

    private double score(AgentModels.ShopCard card) {
        double distanceScore = card.getDistanceMeter() == null ? 0 : 1.0 / (1 + card.getDistanceMeter() / 1000);
        double priceScore = card.getAveragePrice() == null ? 0 : 1;
        double ratingScore = card.getScore() == null ? 0 : card.getScore() / 5;
        double openScore = Boolean.TRUE.equals(card.getOpenNow()) ? 1 : 0;
        return distanceScore * .25 + priceScore * .20 + ratingScore * .20 + openScore * .15;
    }
}
