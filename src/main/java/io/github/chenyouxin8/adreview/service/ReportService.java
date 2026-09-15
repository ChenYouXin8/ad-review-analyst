package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.constant.ReportType;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.NaturalQueryResult;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
        List<AdCampaign> campaigns = qianchuanService.getCampaignReports(startDate, endDate);
        AccountSummary summary = qianchuanService.getAccountSummary(endDate);
        if (campaigns.isEmpty()) throw new BusinessException(40001, "该时间段内没有投放数据");

        List<ReviewReport.AbnormalItem> abnormalities = analysisService.detectAbnormalities(campaigns);
        List<AdCampaign> topCampaigns = analysisService.getTopCampaigns(campaigns, 5);
        List<AdCampaign> decliningCampaigns = analysisService.getDecliningCampaigns(campaigns, 5);
        ReviewReport.BudgetSuggestion budgetSuggestion = analysisService.generateBudgetSuggestion(campaigns, summary.getTotalCost() * 1.1);

        String aiAnalysis = generateAiAnalysis(summary, campaigns, abnormalities, topCampaigns);
        List<String> suggestions = generateAiSuggestions(summary, abnormalities, topCampaigns, decliningCampaigns);
        String overview = generateOverview(summary, startDate, endDate, type);

        ReviewReport report = new ReviewReport();
        report.setReportId(UUID.randomUUID().toString().replace("-", ""));
        report.setReportType(type.getCode());
        report.setStartDate(startDate);
        report.setEndDate(endDate);
        report.setGeneratedAt(LocalDateTime.now());
        report.setSummary(summary);
        report.setOverview(overview);
        report.setAbnormalItems(abnormalities);
        report.setTopCampaigns(topCampaigns);
        report.setDecliningCampaigns(decliningCampaigns);
        report.setSuggestions(suggestions);
        report.setBudgetSuggestion(budgetSuggestion);
        report.setAiAnalysis(aiAnalysis);
        storageService.saveReport(report);
        return report;
    }

    private String generateOverview(AccountSummary summary, LocalDate start, LocalDate end, ReportType type) {
        String prompt = String.format("你是资深抖音投流运营专家，根据数据生成%s概览（150字内）：周期%s至%s，消耗%.2f元，展示%d，点击%d，CTR%.2f%%，CPC%.2f元，转化%d，CPA%.2f元，GMV%.2f元，ROI%.2f，异常计划%d个。要求：先评价好坏，再指出1-2个亮点或问题。",
                type.getDesc(), start, end, summary.getTotalCost(), summary.getTotalShow(), summary.getTotalClick(),
                summary.getOverallCtr(), summary.getOverallCpc(), summary.getTotalConvert(), summary.getOverallCpa(),
                summary.getTotalGmv(), summary.getOverallRoi(), summary.getAbnormalCampaignCount());
        try { return chatClient.prompt().user(prompt).call().content(); }
        catch (Exception e) { return String.format("%s投放概览：消耗%.2f元，ROI%.2f，%d个计划异常。", type.getDesc(), summary.getTotalCost(), summary.getOverallRoi(), summary.getAbnormalCampaignCount()); }
    }

    private String generateAiAnalysis(AccountSummary summary, List<AdCampaign> campaigns,
                                      List<ReviewReport.AbnormalItem> abnormalities, List<AdCampaign> topCampaigns) {
        StringBuilder data = new StringBuilder();
        data.append("账户汇总：消耗").append(String.format("%.2f", summary.getTotalCost())).append("元，ROI").append(String.format("%.2f", summary.getOverallRoi())).append("\n");
        data.append("TOP计划：\n");
        for (AdCampaign c : topCampaigns) data.append("- ").append(c.getCampaignName()).append(": ROI").append(String.format("%.2f", c.getPayOrderRoi())).append("\n");
        data.append("异常项：\n");
        for (ReviewReport.AbnormalItem item : abnormalities) data.append("- ").append(item.getCampaignName()).append(": ").append(item.getDescription()).append("\n");
        String prompt = String.format("你是资深千川投流分析师，根据数据深度分析：\n%s\n要求：1.整体表现评价 2.优质计划共性 3.异常原因推测 4.趋势判断，400字内分点论述。", data);
        try { return chatClient.prompt().user(prompt).call().content(); }
        catch (Exception e) { return "AI分析服务暂时不可用，请查看数据明细。"; }
    }

    private List<String> generateAiSuggestions(AccountSummary summary, List<ReviewReport.AbnormalItem> abnormalities,
                                               List<AdCampaign> topCampaigns, List<AdCampaign> decliningCampaigns) {
        String prompt = String.format("你是资深千川投流优化师，整体ROI%.2f，异常计划%d个，给出3-5条具体可执行的优化建议，涵盖预算、出价、素材、人群维度，每条不超50字。", summary.getOverallRoi(), abnormalities.size());
        try {
            String result = chatClient.prompt().user(prompt).call().content();
            List<String> suggestions = new ArrayList<>();
            for (String line : result.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && (line.startsWith("-") || line.matches("^\\d+[.、].*")))
                    suggestions.add(line.replaceAll("^[-\\d.、]+\\s*", ""));
            }
            return suggestions.isEmpty() ? List.of("优化异常计划，加大优质计划预算") : suggestions;
        } catch (Exception e) { return List.of("暂停低ROI计划", "加大优质计划预算"); }
    }

    public NaturalQueryResult naturalQuery(String question, LocalDate date) {
        List<AdCampaign> campaigns = qianchuanService.getCampaignReports(date, date);
        AccountSummary summary = qianchuanService.getAccountSummary(date);
        StringBuilder data = new StringBuilder();
        data.append("账户汇总：消耗").append(String.format("%.2f", summary.getTotalCost())).append("元，ROI").append(String.format("%.2f", summary.getOverallRoi())).append("\n");
        for (AdCampaign c : campaigns) data.append("- ").append(c.getCampaignName()).append(": 消耗").append(String.format("%.0f", c.getCost())).append("元，ROI").append(String.format("%.2f", c.getPayOrderRoi())).append("\n");
        String prompt = String.format("你是千川投流数据助手，用户问：%s\n数据：%s\n请直接回答，200字内。", question, data);
        NaturalQueryResult result = new NaturalQueryResult();
        result.setQuestion(question);
        result.setData(data.toString());
        try { result.setAnswer(chatClient.prompt().user(prompt).call().content()); }
        catch (Exception e) { result.setAnswer("AI服务不可用，今日消耗" + String.format("%.2f", summary.getTotalCost()) + "元，ROI" + String.format("%.2f", summary.getOverallRoi())); }
        result.setRelatedCampaigns(campaigns);
        return result;
    }

    public List<ReviewReport> getHistoryReports(int page, int size) { return storageService.listReports(page, size); }
    public ReviewReport getReportById(String reportId) { return storageService.getReport(reportId); }
}
