package org.javaup.agent.tool;

import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.dto.Result;
import org.javaup.entity.Voucher;
import org.javaup.service.IVoucherService;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class VoucherTool implements AgentTool<Long, List<AgentModels.VoucherCard>> {
    @Resource
    private IVoucherService voucherService;

    @Override
    public String name() {
        return "listShopVouchers";
    }

    @Override
    public List<AgentModels.VoucherCard> execute(Long shopId, AgentContext context) {
        Result<List<Voucher>> result = voucherService.queryVoucherOfShop(shopId);
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || result.getData() == null) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        return result.getData().stream()
                .map(voucher -> card(voucher, now))
                .filter(AgentModels.VoucherCard::getValid)
                .limit(3)
                .toList();
    }

    private AgentModels.VoucherCard card(Voucher voucher, LocalDateTime now) {
        AgentModels.VoucherCard card = new AgentModels.VoucherCard();
        card.setVoucherId(voucher.getId());
        card.setTitle(voucher.getTitle());
        card.setPayValue(voucher.getPayValue());
        card.setActualValue(voucher.getActualValue());
        card.setRules(voucher.getRules());
        card.setStock(voucher.getStock());
        card.setBeginTime(voucher.getBeginTime());
        card.setEndTime(voucher.getEndTime());
        card.setNeedSeckill(voucher.getType() != null && voucher.getType() == 1);
        card.setNeedSeckill(voucher.getType() != null && voucher.getType() == 1);
        boolean inWindow = (voucher.getBeginTime() == null || !voucher.getBeginTime().isAfter(now))
                && (voucher.getEndTime() == null || !voucher.getEndTime().isBefore(now));
        boolean inStock = voucher.getStock() == null || voucher.getStock() > 0;
        card.setValid(voucher.getStatus() != null && voucher.getStatus() == 1 && inWindow && inStock);
        return card;
    }
}
