package org.javaup.agent.service;

import cn.hutool.core.util.StrUtil;
import org.javaup.agent.model.AgentModels;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedIntentParser implements IntentParser {
    private static final Pattern MONEY=Pattern.compile("(?:人均|预算|每人)?\\s*(\\d{2,5})\\s*(?:元|块)?(?:以内|以下|内)?");
    private static final Pattern RADIUS=Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米)");
    private static final Pattern SCORE=Pattern.compile("(?:评分|分数)\\s*(?:不低于|至少|大于等于)?\\s*(\\d(?:\\.\\d)?)");
    @Resource
    private DeepSeekClient deepSeekClient;
    @Override public AgentModels.Intent parse(String message,List<AgentModels.Message> history){
        AgentModels.Intent i = null;
        try {
            i = deepSeekClient == null ? null : deepSeekClient.parseIntent(message, history);
        } catch (RuntimeException ignored) {
            // A model outage is handled by the deterministic parser below.
        }
        if (i == null) i = parseText(message);
        mergeHistory(i, history);
        return i;
    }
    private static void mergeHistory(AgentModels.Intent i, List<AgentModels.Message> history) {
        if (history == null) return;
        for(int n=history.size()-1;n>=0;n--){AgentModels.Intent o=history.get(n).getFilters();if(o==null)continue;if(i.getBudgetMax()==null)i.setBudgetMax(o.getBudgetMax());if(i.getRadiusMeter()==null)i.setRadiusMeter(o.getRadiusMeter());if(StrUtil.isBlank(i.getKeyword()))i.setKeyword(o.getKeyword());if(StrUtil.isBlank(i.getLocation()))i.setLocation(o.getLocation());if(i.getMinScore()==null)i.setMinScore(o.getMinScore());if(i.getOpenAt()==null)i.setOpenAt(o.getOpenAt());if(i.getScene()==null)i.setScene(o.getScene());if(i.getNeedVoucher()==null)i.setNeedVoucher(o.getNeedVoucher());break;}
    }
    public static AgentModels.Intent parseText(String t){AgentModels.Intent i=new AgentModels.Intent();i.setKeyword(keyword(t));i.setRadiusMeter(radius(t));i.setBudgetMax(money(t));i.setMinScore(score(t));i.setNeedVoucher(t.contains("券")||t.contains("优惠"));i.setScene(scene(t));i.setLocation(location(t));Matcher m=Pattern.compile("(晚上|夜里)?\\s*(\\d{1,2})(?:点|:00)").matcher(t);if(m.find()){int hour=Integer.parseInt(m.group(2));if(m.group(1)!=null&&hour<12)hour+=12;i.setOpenAt(String.format("%02d:00",hour));}return i;}
    private static String keyword(String t){for(String x:new String[]{"日料","火锅","咖啡","烧烤","甜品","餐厅"})if(t.contains(x))return x;String s=t.replaceAll("拱墅区|西湖区|上城区|下城区|附近|有没有|找|推荐|适合|人均|以内|以下|的|店|商户|公里|米|晚上|营业|优惠券|代金券|有券|预算|评分|不低于|至少|现在|朋友|约会|聚餐"," ").replaceAll("\\d+"," ").trim();return s.isEmpty()?null:s;}
    private static Integer money(String t){Matcher m=MONEY.matcher(t);return m.find()?Integer.valueOf(m.group(1)):null;} private static Integer radius(String t){Matcher m=RADIUS.matcher(t);if(!m.find())return null;return (int)(m.group(2).equals("米")?Double.parseDouble(m.group(1)):Double.parseDouble(m.group(1))*1000);} private static Double score(String t){Matcher m=SCORE.matcher(t);return m.find()?Double.valueOf(m.group(1)):null;} private static String location(String t){for(String x:new String[]{"拱墅区","西湖区","上城区","下城区"})if(t.contains(x))return x;return null;} private static String scene(String t){for(String x:new String[]{"约会","聚餐","亲子","拍照","夜宵"})if(t.contains(x))return x;return null;}
}
