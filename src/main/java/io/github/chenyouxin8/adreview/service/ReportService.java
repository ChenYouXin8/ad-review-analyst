package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.constant.DataSourceType;
import io.github.chenyouxin8.adreview.constant.ReportType;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.AiReportInsight;
import io.github.chenyouxin8.adreview.model.NaturalQueryResult;
import io.github.chenyouxin8.adreview.model.PageResult;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import io.github.chenyouxin8.adreview.tool.AdDataAnalyticsTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportService {

    private final ChatClient chatClient;
    private final QianchuanService qianchuanService;
    private final AnalysisService analysisService;
    private final ReportStorageService storageService;

    @Value("${ad-review.roi-threshold:1.5}")
    private double roiThreshold;

    @Autowired
    public ReportService(ChatClient.Builder chatClientBuilder,
                         QianchuanService qianchuanService,
                         AnalysisService analysisService,
                         ReportStorageService storageService) {
        this.chatClient = chatClientBuilder.build();
        this.qianchuanService = qianchuanService;
        this.analysisService = analysisService;
        this.storageService = storageService;
    }

    public ReviewReport generateDailyReport(LocalDate date) {
        return generateReport(date, date, ReportType.DAILY);
    }

    public ReviewReport generateWeeklyReport(LocalDate endDate) {
        return generateReport(endDate.minusDays(6), endDate, ReportType.WEEKLY);
    }

    public ReviewReport generateCustomReport(LocalDate startDate, LocalDate endDate) {
        return generateReport(startDate, endDate, ReportType.CUSTOM);
    }

    private ReviewReport generateReport(LocalDate startDate, LocalDate endDate, ReportType type) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(40002, "开始日期不能晚于结束日期");
        }
        if (startDate.isBefore(LocalDate.now().minusYears(5)) || endDate.isAfter(LocalDate.now())) {
            throw new BusinessException(40003, "日期范围超出允许区间（最多追溯 5 年，且不能晚于今天）");
        }

        // 第一步：仅取数一次，并携带明确的数据来源标识
        QianchuanService.CampaignFetchResult fetch = qianchuanService.fetchCampaigns(startDate, endDate);
        List<AdCampaign> campaigns = fetch.campaigns();
        DataSourceType dataSource = fetch.dataSource();
        if (campaigns.isEmpty()) throw new BusinessException(40001, "该时间段内没有投放数据");

        // 第二步：基于同一份列表在内存聚合，彻底消除二次取数
        AccountSummary summary = qianchuanService.calculateAccountSummary(campaigns, startDate, endDate);
        summary.setDataSource(dataSource.name());
        summary.setDegraded(false);

        List<ReviewReport.AbnormalItem> abnormalities = analysisService.detectAbnormalities(campaigns);
        List<AdCampaign> topCampaigns = analysisService.getTopCampaigns(campaigns, 5);
        List<AdCampaign> decliningCampaigns = analysisService.getDecliningCampaigns(campaigns, 5);
        ReviewReport.BudgetSuggestion budgetSuggestion = analysisService.generateBudgetSuggestion(campaigns, summary.getTotalCost() * 1.1);

        // 第三步：单次强 Schema 结构化调用，一次产出概览 / 深度分析 / 建议
        AiInsightResult insightResult = generateAiInsight(summary, abnormalities, topCampaigns);

        ReviewReport report = new ReviewReport();
        report.setReportId(UUID.randomUUID().toString().replace("-", ""));
        report.setReportType(type.getCode());
        report.setStartDate(startDate);
        report.setEndDate(endDate);
        report.setGeneratedAt(LocalDateTime.now());
        report.setSummary(summary);
        report.setOverview(insightResult.insight().overview());
        report.setAbnormalItems(abnormalities);
        report.setTopCampaigns(topCampaigns);
        report.setDecliningCampaigns(decliningCampaigns);
        report.setSuggestions(insightResult.insight().actionableSuggestions());
        report.setBudgetSuggestion(budgetSuggestion);
        report.setAiAnalysis(insightResult.insight().deepAnalysis());
        report.setDataSource(dataSource.name());
        report.setDegraded(insightResult.degraded());
        storageService.saveReport(report);
        return report;
    }

    /** 单次结构化调用的结果：洞察内容 + 是否降级。 */
    private record AiInsightResult(AiReportInsight insight, boolean degraded) {
    }

    /**
     * 单次 LLM 结构化调用生成复盘洞察。
     * <p>失败时记录完整 error 日志（含耗时），并降级为基于规则计算的确定性结论，
     * 同时将降级状态透传给上层，绝不静默吞掉。</p>
     */
    private AiInsightResult generateAiInsight(AccountSummary summary,
                                              List<ReviewReport.AbnormalItem> abnormalities,
                                              List<AdCampaign> topCampaigns) {
        StringBuilder data = new StringBuilder();
        data.append("账户汇总：消耗").append(fmt(summary.getTotalCost())).append("元，ROI").append(fmt(summary.getOverallRoi()))
                .append("，CTR").append(fmt(summary.getOverallCtr())).append("%，CPA").append(fmt(summary.getOverallCpa())).append("元\n");
        data.append("TOP计划：\n");
        for (AdCampaign c : topCampaigns) {
            data.append("- ").append(c.getCampaignName()).append(": ROI").append(fmt(c.getPayOrderRoi())).append("\n");
        }
        data.append("异常项：\n");
        for (ReviewReport.AbnormalItem item : abnormalities) {
            data.append("- ").append(item.getCampaignName()).append(": ").append(item.getDescription()).append("\n");
        }
        long start = System.currentTimeMillis();
        try {
            BeanOutputConverter<AiReportInsight> converter = new BeanOutputConverter<>(AiReportInsight.class);
            String prompt = String.format("""
                    你是资深千川投流分析师，请根据以下投放数据一次性生成复盘洞察。
                    %s
                    请严格按照以下 JSON Schema 输出 JSON 对象，不要输出任何额外解释文字：
                    %s
                    """, data, converter.getFormat());
            String content = chatClient.prompt().user(prompt).call().content();
            AiReportInsight insight = converter.convert(content);
            if (insight == null) {
                throw new IllegalStateException("AI 结构化输出解析结果为空");
            }
            log.info("AI 复盘洞察生成成功，耗时 {} ms", System.currentTimeMillis() - start);
            return new AiInsightResult(insight, false);
        } catch (Exception e) {
            log.error("AI 复盘洞察生成失败，耗时 {} ms，已降级为规则结论", System.currentTimeMillis() - start, e);
            return new AiInsightResult(buildFallbackInsight(summary, abnormalities), true);
        }
    }

    /**
     * 基于本地规则计算出确定性兜底结论，保证降级时报告依旧可用且可解释。
     */
    private AiReportInsight buildFallbackInsight(AccountSummary summary, List<ReviewReport.AbnormalItem> abnormalities) {
        int abnormalCount = summary.getAbnormalCampaignCount() != null ? summary.getAbnormalCampaignCount() : abnormalities.size();
        String overview = String.format("投放概览：消耗%s元，ROI%s，异常计划%d个。",
                fmt(summary.getTotalCost()), fmt(summary.getOverallRoi()), abnormalCount);
        String deepAnalysis = abnormalities.isEmpty()
                ? "本周期未识别到明显异常计划，整体投放平稳，建议维持当前预算结构并持续监控 ROI 波动。"
                : String.format("本周期识别到 %d 个异常计划，主要问题为：%s。建议优先调整高消耗低效计划，再逐步优化出价与素材。",
                        abnormalities.size(),
                        abnormalities.stream().map(ReviewReport.AbnormalItem::getDescription).limit(3).collect(Collectors.joining("；")));
        List<String> suggestions = abnormalities.stream()
                .map(i -> i.getCampaignName() + "：" + i.getSuggestion())
                .distinct()
                .limit(5)
                .collect(Collectors.toList());
        if (suggestions.isEmpty()) {
            suggestions = List.of("维持当前预算结构，持续监控 ROI 波动", "对高消耗计划做小步出价测试");
        }
        return new AiReportInsight(overview, deepAnalysis, suggestions);
    }

    private String fmt(Double v) {
        return String.format("%.2f", v != null ? v : 0.0);
    }

    /** 兼容单日查询的旧签名。 */
    public NaturalQueryResult naturalQuery(String question, LocalDate date) {
        return naturalQuery(question, date, date);
    }

    /**
     * 基于 Spring AI Tool Calling 的自然语言问答。
     * <p>不再把全量计划拼接进 Prompt（消除长文本膨胀），也不再依赖关键词 if-else 识别意图
     * （消除伪 NLU）。AI 必须通过调用 {@link AdDataAnalyticsTools} 暴露的本地工具获取真实数据后作答，
     * 工具调用轨迹与命中计划会被自动收集，供前端展示与结果组装。</p>
     */
    public NaturalQueryResult naturalQuery(String question, LocalDate startDate, LocalDate endDate) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(40004, "查询问题不能为空");
        }
        // 第一步：仅取数一次，同一份列表同时用于汇总与工具上下文的构造
        QianchuanService.CampaignFetchResult fetch = qianchuanService.fetchCampaigns(startDate, endDate);
        List<AdCampaign> campaigns = fetch.campaigns();
        AccountSummary summary = qianchuanService.calculateAccountSummary(campaigns, startDate, endDate);
        summary.setDataSource(fetch.dataSource().name());

        // 第二步：构造请求级工具上下文（零重复取数，且能收集调用轨迹）
        AdDataAnalyticsTools toolContext = new AdDataAnalyticsTools(campaigns, summary, analysisService);

        NaturalQueryResult result = new NaturalQueryResult();
        result.setQuestion(question);
        result.setDataSource(fetch.dataSource().name());
        result.setDegraded(false);

        long start = System.currentTimeMillis();
        try {
            String answer = chatClient.prompt()
                    .user(buildQueryPrompt(question, startDate, endDate, campaigns.size()))
                    .tools(toolContext)
                    .call()
                    .content();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("AI 返回内容为空");
            }
            result.setAnswer(answer);
            log.info("自然语言问答成功，耗时 {} ms，调用工具 {}",
                    System.currentTimeMillis() - start, toolContext.getInvokedTools());
        } catch (Exception e) {
            // 优雅降级：记录含耗时的 error 日志，改用本地规则汇总，绝不返回空话
            log.error("自然语言问答失败，耗时 {} ms，已降级为本地规则回答", System.currentTimeMillis() - start, e);
            result.setDegraded(true);
            result.setAnswer(buildFallbackAnswer(summary, campaigns));
        }

        result.setIntent(mapIntent(toolContext.getInvokedTools()));
        result.setInvokedTools(toolContext.getInvokedTools());
        // 相关计划优先取工具命中的计划；若 AI 仅回答汇总未触发计划级工具，则默认取消耗最高的前 5 条
        List<AdCampaign> related = toolContext.getReferencedCampaigns();
        if (related.isEmpty()) {
            related = campaigns.stream()
                    .sorted(Comparator.comparingDouble((AdCampaign c) -> c.getCost() != null ? c.getCost() : 0d).reversed())
                    .limit(5)
                    .collect(Collectors.toList());
        }
        result.setRelatedCampaigns(related.stream().limit(10).collect(Collectors.toList()));
        return result;
    }

    /**
     * 构建精简、专业的问答 Prompt：仅告知范围与规模，要求 AI 必须调用工具取真实数据。
     */
    private String buildQueryPrompt(String question, LocalDate startDate, LocalDate endDate, int campaignCount) {
        return String.format("""
                你是资深千川投流数据分析师，正在为用户解答投放数据问题。
                数据范围：%s ~ %s，共 %d 个广告计划。
                你必须先调用提供的工具获取真实数据，再依据工具返回的数据作答；严禁凭空编造任何数字。
                若工具未覆盖用户问题涉及的维度，请如实说明无法从现有数据得出。
                回答要求：结论先行、条理清晰，控制在 250 字以内。

                用户问题：%s
                """, startDate, endDate, campaignCount, question);
    }

    /**
     * 基于本次实际调用的工具动态推断查询类型，替代硬编码的关键词匹配。
     */
    private String mapIntent(List<String> invokedTools) {
        if (invokedTools.contains("findAbnormalCampaigns")) return "abnormal_check";
        if (invokedTools.contains("queryCampaignsByMetric")) return "campaign_ranking";
        if (invokedTools.contains("searchCampaignByKeyword")) return "campaign_search";
        if (invokedTools.contains("getAccountOverview")) return "account_overview";
        return "general";
    }

    /**
     * 基于本地规则拼装可读的兜底回答：大盘汇总 + 异常数 + 最高 ROI 计划。
     */
    private String buildFallbackAnswer(AccountSummary summary, List<AdCampaign> campaigns) {
        int abnormalCount = summary.getAbnormalCampaignCount() != null
                ? summary.getAbnormalCampaignCount() : 0;
        AdCampaign topRoi = campaigns.stream()
                .filter(c -> c.getPayOrderRoi() != null)
                .max(Comparator.comparingDouble(AdCampaign::getPayOrderRoi))
                .orElse(null);
        String topDesc = topRoi != null
                ? String.format("，其中 ROI 最高的计划为「%s」（ROI %s）", topRoi.getCampaignName(), fmt(topRoi.getPayOrderRoi()))
                : "";
        return String.format("（AI 服务暂不可用，以下为本地规则汇总）本周期共 %d 个计划，总消耗 ¥%s，整体 ROI %s，异常计划 %d 个%s。",
                campaigns.size(), fmt(summary.getTotalCost()), fmt(summary.getOverallRoi()), abnormalCount, topDesc);
    }

    public PageResult<ReviewReport> getHistoryReports(int page, int size) {
        return new PageResult<>(storageService.listReports(page, size), storageService.countReports(), page, size);
    }
    public ReviewReport getReportById(String reportId) { return storageService.getReport(reportId); }
    public void deleteReport(String reportId) { storageService.deleteReport(reportId); }
}
