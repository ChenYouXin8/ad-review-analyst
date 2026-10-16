package io.github.chenyouxin8.adreview.tool;

import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import io.github.chenyouxin8.adreview.service.AnalysisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 请求级的本地数据分析工具箱。
 * <p>该类<b>不是</b>全局无状态单例：每次自然语言问答都会用当前已取到的
 * {@code campaigns} / {@code summary} 构造一个新实例，从而保证：
 * <ul>
 *   <li>零重复取数——工具直接消费内存中的列表，不再触发 MCP/LLM 调用；</li>
 *   <li>可收集本次调用的轨迹——{@link #getInvokedTools()} 记录被调用的工具名，
 *       {@link #getReferencedCampaigns()} 按命中顺序去重记录相关计划。</li>
 * </ul></p>
 */
@Slf4j
public class AdDataAnalyticsTools {

    private final List<AdCampaign> campaigns;
    private final AccountSummary summary;
    private final AnalysisService analysisService;

    /** 本次问答实际调用的工具名称（线程安全的保序列表）。 */
    private final List<String> invokedTools = new CopyOnWriteArrayList<>();
    /** 被工具命中的相关计划，按命中顺序去重（LinkedHashMap 保序）。 */
    private final Map<Long, AdCampaign> referencedCampaigns = Collections.synchronizedMap(new LinkedHashMap<>());

    public AdDataAnalyticsTools(List<AdCampaign> campaigns, AccountSummary summary, AnalysisService analysisService) {
        this.campaigns = campaigns != null ? campaigns : List.of();
        this.summary = summary != null ? summary : new AccountSummary();
        this.analysisService = analysisService;
    }

    /**
     * 获取账户整体汇总大盘数据（总消耗、总GMV、整体ROI、CTR、CPA、计划总数等）。
     */
    @Tool(name = "getAccountOverview", description = "获取账户整体汇总大盘数据：总消耗、总GMV、整体ROI、整体CTR、整体CPA、计划总数与异常计划数。适合回答“整体情况如何”这类宏观问题。")
    public String getAccountOverview() {
        record("getAccountOverview");
        return String.format(
                "账户整体大盘：计划数 %d 个，总消耗 ¥%s，总GMV ¥%s，整体ROI %s，整体CTR %s%%，整体CPA ¥%s，总转化 %d，异常计划 %d 个。",
                n(summary.getActiveCampaignCount()),
                fmt(summary.getTotalCost()),
                fmt(summary.getTotalGmv()),
                fmt(summary.getOverallRoi()),
                fmt(summary.getOverallCtr()),
                fmt(summary.getOverallCpa()),
                summary.getTotalConvert() != null ? summary.getTotalConvert() : 0L,
                n(summary.getAbnormalCampaignCount()));
    }

    /**
     * 按指定指标升序或降序查询 Top N 计划。
     */
    @Tool(name = "queryCampaignsByMetric", description = "按指定指标查询广告计划排行。metric 支持 roi(支付ROI)、cost(消耗)、gmv(支付GMV)、ctr(点击率)、cpa(转化成本)、cvr(转化率)、conversions(转化数)；ascending=true 为升序(找最差)，false 为降序(找最好)；limit 为返回条数(1~20)。")
    public String queryCampaignsByMetric(
            @ToolParam(description = "排序指标，可选：roi / cost / gmv / ctr / cpa / cvr / conversions") String metric,
            @ToolParam(description = "是否升序：true 表示从小到大（用于找最差/最低），false 表示从大到小（用于找最好/最高）") boolean ascending,
            @ToolParam(description = "返回计划条数，限制在 1~20 之间") int limit) {
        record("queryCampaignsByMetric");
        int capped = Math.max(1, Math.min(limit, 20));
        Comparator<AdCampaign> comparator = Comparator.comparingDouble(c -> {
            Double v = metricValue(c, metric);
            return v != null ? v : 0d;
        });
        if (!ascending) {
            comparator = comparator.reversed();
        }
        List<AdCampaign> ranked = campaigns.stream()
                .sorted(comparator)
                .limit(capped)
                .collect(Collectors.toList());
        ranked.forEach(this::reference);
        String label = metricLabel(metric);
        if (ranked.isEmpty()) {
            return String.format("没有可用于按「%s」排序的计划数据。", label);
        }
        StringBuilder sb = new StringBuilder(String.format("按「%s」%s排序，Top %d 计划：\n", label, ascending ? "升序" : "降序", ranked.size()));
        for (int i = 0; i < ranked.size(); i++) {
            AdCampaign c = ranked.get(i);
            sb.append(String.format("%d. %s：%s=%s，消耗¥%s，ROI=%s\n",
                    i + 1, name(c), label, fmt(metricValue(c, metric)), fmt(c.getCost()), fmt(c.getPayOrderRoi())));
        }
        return sb.toString().trim();
    }

    /**
     * 检测并返回所有异常计划及其异常原因和诊断建议。
     */
    @Tool(name = "findAbnormalCampaigns", description = "检测并返回账户内所有异常计划（低ROI、高转化成本、低点击率等），包含异常类型、原因描述与优化建议。适合回答“哪些计划有问题/需要暂停”这类诊断问题。")
    public String findAbnormalCampaigns() {
        record("findAbnormalCampaigns");
        List<ReviewReport.AbnormalItem> abnormalities = analysisService.detectAbnormalities(campaigns);
        Map<Long, AdCampaign> byId = campaigns.stream()
                .filter(c -> c.getCampaignId() != null)
                .collect(Collectors.toMap(AdCampaign::getCampaignId, c -> c, (a, b) -> a, LinkedHashMap::new));
        abnormalities.stream()
                .map(a -> byId.get(a.getCampaignId()))
                .filter(java.util.Objects::nonNull)
                .forEach(this::reference);
        if (abnormalities.isEmpty()) {
            return "未检测到异常计划，当前账户整体投放状况平稳。";
        }
        StringBuilder sb = new StringBuilder(String.format("共检测到 %d 个异常计划：\n", abnormalities.size()));
        for (ReviewReport.AbnormalItem item : abnormalities) {
            sb.append(String.format("- %s（%s）：%s；建议：%s\n",
                    item.getCampaignName(), item.getLevel(), item.getDescription(), item.getSuggestion()));
        }
        return sb.toString().trim();
    }

    /**
     * 按计划名称关键词模糊搜索匹配的广告计划明细（最多返回 10 条）。
     */
    @Tool(name = "searchCampaignByKeyword", description = "按计划名称关键词模糊搜索广告计划明细，返回匹配计划的消耗、ROI、转化等指标，最多 10 条。适合回答“XX计划表现怎么样”这类定位具体计划的问题。")
    public String searchCampaignByKeyword(
            @ToolParam(description = "计划名称关键词，支持模糊匹配，例如“直播间”“达人带货”") String keyword) {
        record("searchCampaignByKeyword");
        if (keyword == null || keyword.isBlank()) {
            return "未提供有效的搜索关键词。";
        }
        String kw = keyword.trim().toLowerCase(Locale.ROOT);
        List<AdCampaign> matched = campaigns.stream()
                .filter(c -> c.getCampaignName() != null && c.getCampaignName().toLowerCase(Locale.ROOT).contains(kw))
                .limit(10)
                .collect(Collectors.toList());
        matched.forEach(this::reference);
        if (matched.isEmpty()) {
            return String.format("没有找到名称包含「%s」的计划。", keyword);
        }
        StringBuilder sb = new StringBuilder(String.format("匹配到 %d 个名称包含「%s」的计划：\n", matched.size(), keyword));
        for (AdCampaign c : matched) {
            sb.append(String.format("- %s：消耗¥%s，ROI=%s，转化%d，GMV¥%s\n",
                    name(c), fmt(c.getCost()), fmt(c.getPayOrderRoi()), c.getConvertCnt() != null ? c.getConvertCnt() : 0L, fmt(c.getPayOrderAmount())));
        }
        return sb.toString().trim();
    }

    /** 本次问答实际调用的工具名称（只读快照）。 */
    public List<String> getInvokedTools() {
        return List.copyOf(invokedTools);
    }

    /** 被工具命中的相关计划（按命中顺序去重，只读快照）。 */
    public List<AdCampaign> getReferencedCampaigns() {
        synchronized (referencedCampaigns) {
            return new ArrayList<>(referencedCampaigns.values());
        }
    }

    private void record(String toolName) {
        if (!invokedTools.contains(toolName)) {
            invokedTools.add(toolName);
        }
        log.debug("本地工具被调用: {}", toolName);
    }

    private void reference(AdCampaign campaign) {
        if (campaign != null) {
            referencedCampaigns.put(campaign.getCampaignId(), campaign);
        }
    }

    private Double metricValue(AdCampaign c, String metric) {
        String key = metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "roi", "payorderroi", "支付roi", "投产" -> c.getPayOrderRoi();
            case "cost", "消耗", "花费" -> c.getCost();
            case "gmv", "payorderamount", "支付gmv", "成交金额" -> c.getPayOrderAmount();
            case "ctr", "点击率" -> c.getCtr();
            case "cpa", "convertcost", "转化成本" -> c.getConvertCost();
            case "cvr", "convertrate", "转化率" -> c.getConvertRate();
            case "conversions", "convertcnt", "转化数", "转化" ->
                    c.getConvertCnt() == null ? null : c.getConvertCnt().doubleValue();
            default -> c.getPayOrderRoi();
        };
    }

    private String metricLabel(String metric) {
        String key = metric == null ? "" : metric.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "roi", "payorderroi", "支付roi", "投产" -> "支付ROI";
            case "cost", "消耗", "花费" -> "消耗";
            case "gmv", "payorderamount", "支付gmv", "成交金额" -> "支付GMV";
            case "ctr", "点击率" -> "点击率";
            case "cpa", "convertcost", "转化成本" -> "转化成本";
            case "cvr", "convertrate", "转化率" -> "转化率";
            case "conversions", "convertcnt", "转化数", "转化" -> "转化数";
            default -> "支付ROI";
        };
    }

    private String name(AdCampaign c) {
        return c.getCampaignName() != null ? c.getCampaignName() : "未命名计划";
    }

    private String fmt(Double v) {
        return String.format("%.2f", v != null ? v : 0.0);
    }

    private int n(Integer v) {
        return v != null ? v : 0;
    }
}
