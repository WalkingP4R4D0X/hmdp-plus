package org.javaup.agent.tool;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.entity.Shop;
import org.javaup.service.IShopService;
import org.springframework.stereotype.Component;

import java.util.List;
import static org.javaup.agent.tool.NearbyShopTool.typeIdForKeyword;
import org.javaup.agent.service.KeywordNormalizer;

@Component
public class ShopSearchTool implements AgentTool<AgentModels.Intent, List<Shop>> {
    @Resource
    private IShopService shopService;

    @Override
    public String name() {
        return "searchShops";
    }

    @Override
    public List<Shop> execute(AgentModels.Intent input, AgentContext context) {
        String keyword = normalizeKeyword(input.getKeyword());
        Long typeId = typeIdForKeyword(keyword);
        return shopService.query()
                .and(StrUtil.isNotBlank(keyword), w -> {
                    if (typeId != null && !isFoodKeyword(keyword)) {
                        w.eq("type_id", typeId).or();
                    }
                    w.like("name", keyword).or().like("name", keywordAlias(keyword));
                })
                .and(StrUtil.isNotBlank(input.getLocation()), w -> w
                        .like("area", input.getLocation())
                        .or()
                        .like("address", input.getLocation()))
                .last("LIMIT 30")
                .list();
    }

    private static String normalizeKeyword(String keyword) {
        return KeywordNormalizer.normalize(keyword);
    }

    private static String keywordAlias(String keyword) {
        if (keyword == null) return null;
        return switch (keyword) {
            case "日料", "刺身" -> "寿司";
            case "寿司" -> "日料";
            case "火锅" -> "涮锅";
            default -> keyword;
        };
    }

    private static boolean isFoodKeyword(String keyword) {
        return java.util.Set.of("美食", "餐厅", "吃饭", "好吃的", "日料", "寿司", "刺身", "火锅", "咖啡", "烧烤", "甜品")
                .contains(keyword);
    }
}
