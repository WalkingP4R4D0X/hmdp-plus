package org.javaup.agent.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.entity.SeckillVoucher;
import org.javaup.entity.Voucher;
import org.javaup.mapper.SeckillVoucherMapper;
import org.javaup.mapper.VoucherMapper;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@Validated
public class VoucherTool implements AgentTool<Long, List<AgentModels.VoucherCard>> {
    @Resource
    private VoucherMapper voucherMapper;
    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Override
    public String name() {
        return "listShopVouchers";
    }

    @Override
    public List<AgentModels.VoucherCard> execute(Long shopId, AgentContext context) {
        AgentTool.requireShopId(shopId);
        return executeBatch(List.of(shopId), context).get(shopId);
    }

    /** One agent call, at most two database reads, and up to three valid vouchers per shop. */
    public Map<Long, List<AgentModels.VoucherCard>> executeBatch(
            @NotNull @Size(max = 30) List<@NotNull @Positive Long> shopIds, @Valid AgentContext context) {
        if (shopIds == null || shopIds.size() > 30) throw new IllegalArgumentException("at most 30 shopIds are allowed");
        shopIds.forEach(AgentTool::requireShopId);
        Map<Long, List<AgentModels.VoucherCard>> result = new LinkedHashMap<>();
        shopIds.forEach(id -> result.putIfAbsent(id, new ArrayList<>()));
        if (result.isEmpty()) return Map.of();

        List<Voucher> vouchers = voucherMapper.selectList(new QueryWrapper<Voucher>()
                .in("shop_id", result.keySet()).eq("status", 1).orderByAsc("id"));
        List<Long> seckillIds = vouchers.stream().filter(Objects::nonNull)
                .filter(v -> Integer.valueOf(1).equals(v.getStatus()) && Integer.valueOf(1).equals(v.getType()))
                .filter(v -> result.containsKey(v.getShopId()))
                .map(Voucher::getId).filter(Objects::nonNull).distinct().toList();
        Map<Long, SeckillVoucher> seckills = new LinkedHashMap<>();
        if (!seckillIds.isEmpty()) {
            for (SeckillVoucher seckill : seckillVoucherMapper.selectList(
                    new QueryWrapper<SeckillVoucher>().in("voucher_id", seckillIds))) {
                if (seckill != null && seckill.getVoucherId() != null) {
                    seckills.putIfAbsent(seckill.getVoucherId(), seckill);
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        for (Voucher voucher : vouchers) {
            if (voucher == null || !result.containsKey(voucher.getShopId())) continue;
            AgentModels.VoucherCard card = card(voucher, seckills.get(voucher.getId()), now);
            List<AgentModels.VoucherCard> cards = result.get(voucher.getShopId());
            if (card != null && cards.size() < 3
                    && cards.stream().noneMatch(existing -> existing.getVoucherId().equals(card.getVoucherId()))) {
                cards.add(card);
            }
        }
        result.replaceAll((id, cards) -> List.copyOf(cards));
        return Collections.unmodifiableMap(result);
    }

    private AgentModels.VoucherCard card(Voucher voucher, SeckillVoucher seckill, LocalDateTime now) {
        if (voucher.getId() == null || voucher.getId() <= 0 || !Integer.valueOf(1).equals(voucher.getStatus())) return null;
        boolean needSeckill = Integer.valueOf(1).equals(voucher.getType());
        if (!needSeckill && !Integer.valueOf(0).equals(voucher.getType())) return null;
        // Ordinary vouchers have no persisted window/stock. Seckill evidence must be complete.
        LocalDateTime begin = needSeckill && seckill != null ? seckill.getBeginTime() : voucher.getBeginTime();
        LocalDateTime end = needSeckill && seckill != null ? seckill.getEndTime() : voucher.getEndTime();
        Integer stock = needSeckill && seckill != null ? seckill.getStock() : voucher.getStock();
        if (needSeckill && (seckill == null || begin == null || end == null || stock == null || stock <= 0)) return null;
        if ((begin != null && begin.isAfter(now)) || (end != null && !now.isBefore(end))
                || (begin != null && end != null && !begin.isBefore(end))) return null;
        if (!needSeckill && stock != null && stock <= 0) return null;

        AgentModels.VoucherCard card = new AgentModels.VoucherCard();
        card.setVoucherId(voucher.getId());
        card.setTitle(voucher.getTitle());
        card.setPayValue(voucher.getPayValue());
        card.setActualValue(voucher.getActualValue());
        card.setRules(voucher.getRules());
        card.setStock(stock);
        card.setBeginTime(begin);
        card.setEndTime(end);
        card.setNeedSeckill(needSeckill);
        card.setValid(true);
        return card;
    }
}
