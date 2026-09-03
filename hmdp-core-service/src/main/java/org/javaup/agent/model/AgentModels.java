package org.javaup.agent.model;

import lombok.Data;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentModels {
    private AgentModels() {}

    @Data
    public static class ChatRequest {
        private String conversationId;
        @Size(min = 1, max = 500)
        private String message;
        private Boolean stream;
        private String clientRequestId;
    }

    @Data
    public static class Intent {
        private String intent = "SHOP_RECOMMENDATION";
        private String keyword;
        private String location;
        private Double latitude;
        private Double longitude;
        private Integer radiusMeter;
        private Integer budgetMax;
        private Double minScore;
        private String openAt;
        private String scene;
        private Boolean needVoucher;
    }

    @Data
    public static class ShopCard {
        private Long type = 1L;
        private Long shopId;
        private String name;
        private Long typeId;
        private String address;
        private String area;
        private Double distanceMeter;
        private Double score;
        private Long averagePrice;
        private Boolean openNow;
        private String openHours;
        private String reason;
        private Boolean missingData;
        private List<VoucherCard> vouchers = new ArrayList<>();
    }

    @Data
    public static class VoucherCard {
        private Long voucherId;
        private String title;
        private Long payValue;
        private Long actualValue;
        private String rules;
        private Integer stock;
        private LocalDateTime beginTime;
        private LocalDateTime endTime;
        private Boolean valid;
    }

    @Data
    public static class ChatResponse {
        private String conversationId;
        private String answer;
        private List<ShopCard> cards = new ArrayList<>();
        private Map<String, Object> filters = new LinkedHashMap<>();
        private Object pendingAction;
        private boolean fallback;
        private String traceId;
        private String errorCode;
    }

    @Data
    public static class Message {
        private String role;
        private String content;
        private Intent filters;
        private LocalDateTime createTime = LocalDateTime.now();
    }
}

