package org.javaup.agent.tool;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.agent.model.ShopCandidate;
import org.javaup.agent.service.KeywordNormalizer;
import org.javaup.service.IShopService;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Component
@Validated
public class ShopSearchTool implements AgentTool<AgentModels.Intent, List<ShopCandidate>> {
    @Resource
    private IShopService shopService;

    @Override
    public String name() {
        return "searchShops";
    }

    @Override
    public List<ShopCandidate> execute(AgentModels.Intent input, AgentContext context) {
        AgentTool.validateIntent(input);
        String keyword = KeywordNormalizer.normalize(input.getKeyword());
        String location = KeywordNormalizer.normalize(input.getLocation());
        Long typeId = NearbyShopTool.typeIdForKeyword(keyword);
        return shopService.query()
                .and(StrUtil.isNotBlank(keyword), w -> {
                    if (NearbyShopTool.isBroadFoodKeyword(keyword)) {
                        w.eq("type_id", typeId);
                    } else {
                        if (typeId != null && !NearbyShopTool.isFoodKeyword(keyword)) {
                            w.eq("type_id", typeId).or();
                        }
                        int index = 0;
                        for (String alias : NearbyShopTool.aliases(keyword)) {
                            if (index++ > 0) w.or();
                            w.like("name", alias);
                        }
                    }
                })
                .and(StrUtil.isNotBlank(location), w -> w.like("area", location).or().like("address", location))
                .last("LIMIT 30")
                .list().stream()
                .filter(shop -> NearbyShopTool.matchesKeywordAndLocation(shop, input))
                .limit(30).map(ShopCandidate::from).toList();
    }
}
