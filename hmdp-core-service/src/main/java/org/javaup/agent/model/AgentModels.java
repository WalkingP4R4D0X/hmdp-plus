package org.javaup.agent.model;

import lombok.Data;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.AssertTrue;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentModels {
    private AgentModels() {}

    @Data
    public static class ChatRequest {
        @Pattern(regexp = "[A-Za-z0-9_-]{1,80}")
        private String conversationId;
        @NotBlank @Size(min = 1, max = 500)
        private String message;
        @Pattern(regexp = "[A-Za-z0-9_-]{1,80}")
        private String clientRequestId;
        @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
        private Double latitude;
        @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
        private Double longitude;

        @AssertTrue(message = "latitude and longitude must be a finite coordinate pair")
        @JsonIgnore
        public boolean isLocationValid() {
            return latitude == null && longitude == null
                    || latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                    && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
        }
    }

    @Data
    public static class Intent {
        private String intent = "SHOP_RECOMMENDATION";
        /** Internal parser outcome; never treated as a user-facing model instruction. */
        private String parseStatus;
        private String keyword;
        private String location;
        @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
        private Double latitude;
        @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
        private Double longitude;
        @Min(100) @Max(50000)
        private Integer radiusMeter;
        @Min(0) @Max(100000)
        private Integer budgetMax;
        @DecimalMin(value = "0.0") @DecimalMax(value = "5.0")
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
        private Boolean needSeckill;
    }

    @Data
    public static class ChatResponse {
        private String conversationId;
        private String answer;
        private List<ShopCard> cards = new ArrayList<>();
        private Map<String, Object> filters = new LinkedHashMap<>();
        private boolean memorySaved;
        private boolean fallback;
        private String traceId;
        private String errorCode;
    }

    @Data
    public static class Message {
        private String role;
        private String content;
        private Intent filters;
        private List<String> toolCalls = new ArrayList<>();
        private LocalDateTime createTime = LocalDateTime.now();
    }
}

