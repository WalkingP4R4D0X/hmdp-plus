package org.javaup.agent.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.agent.memory.ConversationMemory;
import org.javaup.agent.model.AgentModels;
import org.javaup.agent.ranking.ShopRankingService;
import org.javaup.entity.Blog;
import org.javaup.entity.Shop;
import org.javaup.entity.Voucher;
import org.javaup.service.IBlogService;
import org.javaup.service.IShopService;
import org.javaup.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AgentOrchestrator {
    private static final Pattern MONEY = Pattern.compile("(?:人均|预算|每人)?\\s*(\\d{2,5})\\s*(?:元|块)?(?:以内|以下|内)?");
    private static final Pattern RADIUS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米)");
    private static final Pattern SCORE = Pattern.compile("(?:评分|分数)\\s*(?:不低于|至少|大于等于)?\\s*(\\d(?:\\.\\d)?)");
    @Resource private IShopService shopService;
    @Resource private IVoucherService voucherService;
    @Resource private IBlogService blogService;
    @Resource private ConversationMemory memory;
    @Resource private ShopRankingService ranking;
    @Resource private StringRedisTemplate redis;

    public AgentModels.ChatResponse chat(AgentModels.ChatRequest request) {
        long started = System.currentTimeMillis();
        AgentModels.ChatResponse out = new AgentModels.ChatResponse();
        out.setTraceId("trace-" + UUID.randomUUID());
        String id = memory.ensureId(request.getConversationId()); out.setConversationId(id);
        if (request.getMessage() == null || request.getMessage().isBlank() || request.getMessage().length() > 500) { out.setAnswer("请输入1-500字的查店需求"); out.setErrorCode("AGENT_REQUEST_INVALID"); return out; }
        List<AgentModels.Message> history = memory.read(id);
        AgentModels.Intent intent = parse(request.getMessage()); merge(intent, history);
        List<Shop> shops;
        try { shops = queryShops(intent); } catch (Exception e) { log.warn("agent shop query failed traceId={}", out.getTraceId()); shops = fallbackQuery(request.getMessage()); out.setFallback(true); out.setErrorCode("AGENT_FALLBACK"); }
        List<AgentModels.ShopCard> cards = ranking.rank(shops, intent);
        for (AgentModels.ShopCard card : cards) { enrich(card); }
        out.setCards(cards); out.setFilters(filters(intent)); out.setAnswer(answer(cards, out.isFallback()));
        AgentModels.Message user = new AgentModels.Message(); user.setRole("user"); user.setContent(request.getMessage()); user.setFilters(intent); memory.append(id, user);
        AgentModels.Message assistant = new AgentModels.Message(); assistant.setRole("assistant"); assistant.setContent(out.getAnswer()); assistant.setFilters(intent); memory.append(id, assistant);
        log.info("agent_request traceId={} conversationId={} cards={} fallback={} latencyMs={}", out.getTraceId(), id, cards.size(), out.isFallback(), System.currentTimeMillis()-started);
        return out;
    }

    private AgentModels.Intent parse(String text) {
        AgentModels.Intent i = new AgentModels.Intent(); i.setKeyword(extractKeyword(text)); i.setRadiusMeter(radius(text)); i.setBudgetMax(money(text)); i.setMinScore(score(text)); i.setNeedVoucher(text.contains("券") || text.contains("优惠")); i.setScene(scene(text)); i.setLocation(location(text));
        Matcher open = Pattern.compile("(?:晚上|夜里)?\\s*(\\d{1,2})(?:点|:00)").matcher(text); if (open.find()) i.setOpenAt(String.format("%02d:00", Integer.parseInt(open.group(1))));
        return i;
    }
    private void merge(AgentModels.Intent i, List<AgentModels.Message> history) { for (int n=history.size()-1;n>=0;n--) { AgentModels.Intent old=history.get(n).getFilters(); if(old==null) continue; if(i.getBudgetMax()==null)i.setBudgetMax(old.getBudgetMax()); if(i.getRadiusMeter()==null)i.setRadiusMeter(old.getRadiusMeter()); if(StrUtil.isBlank(i.getKeyword()))i.setKeyword(old.getKeyword()); if(StrUtil.isBlank(i.getLocation()))i.setLocation(old.getLocation()); if(i.getMinScore()==null)i.setMinScore(old.getMinScore()); if(i.getOpenAt()==null)i.setOpenAt(old.getOpenAt()); break; } }
    private List<Shop> queryShops(AgentModels.Intent i) { return shopService.query().and(w -> w.like(StrUtil.isNotBlank(i.getKeyword()), "name", i.getKeyword()).or().like(StrUtil.isNotBlank(i.getKeyword()), "area", i.getKeyword())).list(); }
    private List<Shop> fallbackQuery(String text) { String keyword=extractKeyword(text); return shopService.query().like(StrUtil.isNotBlank(keyword), "name", keyword).last("LIMIT 10").list(); }
    private void enrich(AgentModels.ShopCard c) { List<Voucher> vs=voucherService.queryVoucherOfShop(c.getShopId()).getData(); if(vs!=null)c.setVouchers(vs.stream().limit(3).map(v->{AgentModels.VoucherCard x=new AgentModels.VoucherCard();x.setVoucherId(v.getId());x.setTitle(v.getTitle());x.setPayValue(v.getPayValue());x.setActualValue(v.getActualValue());x.setRules(v.getRules());x.setStock(v.getStock());x.setBeginTime(v.getBeginTime());x.setEndTime(v.getEndTime());x.setValid(true);return x;}).collect(Collectors.toList())); }
    private String answer(List<AgentModels.ShopCard> cards, boolean fallback) { if(fallback)return "智能推荐暂时不可用，已为你切换到普通搜索，共找到 "+cards.size()+" 家商户。"; return cards.isEmpty()?"暂时没有符合条件的商户，要不要放宽预算或扩大范围？":"我为你找到 "+cards.size()+" 家比较合适的商户，结果均来自实时业务数据。"; }
    private LinkedHashMap<String,Object> filters(AgentModels.Intent i){ LinkedHashMap<String,Object> m=new LinkedHashMap<>(); if(StrUtil.isNotBlank(i.getKeyword()))m.put("keyword",i.getKeyword()); if(StrUtil.isNotBlank(i.getLocation()))m.put("location",i.getLocation()); if(i.getBudgetMax()!=null)m.put("budgetMax",i.getBudgetMax()); if(i.getRadiusMeter()!=null)m.put("radiusMeter",i.getRadiusMeter()); if(i.getMinScore()!=null)m.put("minScore",i.getMinScore()); if(i.getOpenAt()!=null)m.put("openAt",i.getOpenAt()); if(i.getScene()!=null)m.put("scene",i.getScene()); if(Boolean.TRUE.equals(i.getNeedVoucher()))m.put("needVoucher",true); return m; }
    private String extractKeyword(String t){ for(String x:new String[]{"日料","火锅","咖啡","烧烤","甜品","餐厅"}) if(t.contains(x)) return x; String s=t.replaceAll("拱墅区|西湖区|上城区|下城区|附近|有没有|找|推荐|适合|人均|以内|以下|的|店|商户|公里|米|晚上|营业|优惠券|代金券|有券|预算|评分|不低于|至少|现在|朋友|约会|聚餐", " ").replaceAll("\\d+", " ").trim(); return s.length()>0?s:null; }
    private Integer money(String t){Matcher m=MONEY.matcher(t);return m.find()?Integer.valueOf(m.group(1)):null;} private Integer radius(String t){Matcher m=RADIUS.matcher(t);if(!m.find())return null;double v=Double.parseDouble(m.group(1));return (int)(m.group(2).contains("米")&&!m.group(2).contains("公里")?v:v*1000);} private Double score(String t){Matcher m=SCORE.matcher(t);return m.find()?Double.valueOf(m.group(1)):null;} private String location(String t){for(String x:new String[]{"拱墅区","西湖区","上城区","下城区"})if(t.contains(x))return x;return null;} private String scene(String t){for(String x:new String[]{"约会","聚餐","亲子","拍照","夜宵"})if(t.contains(x))return x;return null;}
    public List<AgentModels.Message> messages(String id){return memory.read(id);} public void delete(String id){memory.delete(id);} public List<String> conversations(){return new ArrayList<>();}
}
