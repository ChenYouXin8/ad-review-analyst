package io.github.chenyouxin8.adreview.model;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 投流复盘报告
 */
@Data
public class ReviewReport {
    private String reportId;
    private String reportType;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime generatedAt;
    private AccountSummary summary;
    private String overview;
    private List<AbnormalItem> abnormalItems;
    private List<AdCampaign> topCampaigns;
    private List<AdCampaign> decliningCampaigns;
    private List<String> suggestions;
    private BudgetSuggestion budgetSuggestion;
    private String aiAnalysis;

    @Data
    public static class AbnormalItem {
        private String type;
        private String level;
        private Long campaignId;
        private String campaignName;
        private String description;
        private String suggestion;
    }

    @Data
    public static class BudgetSuggestion {
        private Double totalBudget;
        private List<CampaignBudget> allocations;
    }

    @Data
    public static class CampaignBudget {
        private Long campaignId;
        private String campaignName;
        private Double currentBudget;
        private Double suggestedBudget;
        private Double adjustRatio;
        private String reason;
    }
}
