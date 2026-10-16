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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public NaturalQueryResult naturalQuery(String question, LocalDate date) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(40004, "查询问题不能为空");
        }
        QianchuanService.CampaignFetchResult fetch = qianchuanService.fetchCampaigns(date, date);
        List<AdCampaign> campaigns = fetch.campaigns();
        AccountSummary summary = qianchuanService.calculateAccountSummary(campaigns, date, date);
        summary.setDataSource(fetch.dataSource().name());
        StringBuilder data = new StringBuilder();
        data.append("账户汇总：消耗").append(fmt(summary.getTotalCost())).append("元，ROI").append(fmt(summary.getOverallRoi())).append("\n");
        for (AdCampaign c : campaigns) {
            data.append("- ").append(c.getCampaignName()).append(": 消耗").append(fmt(c.getCost())).append("元，ROI").append(fmt(c.getPayOrderRoi())).append("\n");
        }
        String prompt = String.format("你是千川投流数据助手，用户问：%s\n数据：%s\n请直接回答，200字内。", question, data);
        NaturalQueryResult result = new NaturalQueryResult();
        result.setQuestion(question);
        result.setIntent(detectIntent(question));
        result.setData(data.toString());
        try { result.setAnswer(chatClient.prompt().user(prompt).call().content()); }
        catch (Exception e) { result.setAnswer("AI服务不可用，今日消耗" + fmt(summary.getTotalCost()) + "元，ROI" + fmt(summary.getOverallRoi())); }
        result.setRelatedCampaigns(campaigns);
        return result;
    }

    /**
     * 简单规则识别查询意图，供前端展示和后续扩展
     */
    private String detectIntent(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if (q.contains("暂停") || q.contains("废") || q.contains("跑") || q.contains("停")) return "abnormal_detect";
        if (q.contains("消耗") || q.contains("花钱") || q.contains("花费")) return "cost_analysis";
        if (q.contains("roi") || q.contains("投产")) return "roi_analysis";
        if (q.contains("转化") || q.contains("下单")) return "conversion_analysis";
        if (q.contains("点击") || q.contains("ctr")) return "traffic_analysis";
        if (q.contains("对比") || q.contains("哪个") || q.contains("最好") || q.contains("最差")) return "campaign_compare";
        return "general";
    }

    public PageResult<ReviewReport> getHistoryReports(int page, int size) {
        return new PageResult<>(storageService.listReports(page, size), storageService.countReports(), page, size);
    }
    public ReviewReport getReportById(String reportId) { return storageService.getReport(reportId); }
    public void deleteReport(String reportId) { storageService.deleteReport(reportId); }
}
