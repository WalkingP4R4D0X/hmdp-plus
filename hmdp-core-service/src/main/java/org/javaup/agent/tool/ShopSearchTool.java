package org.javaup.agent.tool;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentContext;
import org.javaup.agent.model.AgentModels;
import org.javaup.entity.Shop;
import org.javaup.service.IShopService;
import org.springframework.stereotype.Component;

import java.util.List;

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
        return shopService.query()
                .and(query -> query.like(StrUtil.isNotBlank(input.getKeyword()), "name", input.getKeyword())
                        .or()
                        .like(StrUtil.isNotBlank(input.getLocation()), "area", input.getLocation()))
                .last("LIMIT 30")
                .list();
    }
}
