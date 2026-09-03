package org.javaup.agent.tool;

import jakarta.annotation.Resource;
import org.javaup.agent.model.AgentContext;
import org.javaup.entity.Blog;
import org.javaup.service.IBlogService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ShopContentTool implements AgentTool<Long, List<String>> {
    @Resource
    private IBlogService blogService;

    @Override
    public String name() {
        return "getShopContent";
    }

    @Override
    public List<String> execute(Long shopId, AgentContext context) {
        return blogService.lambdaQuery().eq(Blog::getShopId, shopId).orderByDesc(Blog::getLiked)
                .last("LIMIT 3").list().stream()
                .map(blog -> truncate(blog.getTitle() + " " + blog.getContent()))
                .toList();
    }

    private String truncate(String content) {
        return content == null ? "" : content.substring(0, Math.min(content.length(), 160));
    }
}
