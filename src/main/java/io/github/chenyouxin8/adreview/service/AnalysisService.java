package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.constant.AbnormalType;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AnalysisService {

    @Value("${ad-review.roi-threshold:1.5}")
    private double roiThreshold;
    @Value("${ad-review.cpa-threshold:100}")
    private double cpaThreshold;
    @Value("${ad-review.ctr-threshold:1.0}")
    private double ctrThreshold;
    @Value("${ad-review.abnormal-min-cost:100}")
    private double abnormalMinCost;

    public List<ReviewReport.AbnormalItem> detectAbnormalities(List<AdCampaign> campaigns) {
        List<ReviewReport.AbnormalItem> abnormalities = new ArrayList<>();
        for (AdCampaign c : campaigns) {
            if (c.getPayOrderRoi() != null && c.getPayOrderRoi() < roiThreshold && c.getCost() != null && c.getCost() > abnormalMinCost) {
                ReviewReport.AbnormalItem item = new ReviewReport.AbnormalItem();
                item.setType(AbnormalType.LOW_ROI.getCode());
                item.setLevel(AbnormalType.LOW_ROI.getDefaultLevel());
                item.setCampaignId(c.getCampaignId());
                item.setCampaignName(c.getCampaignName());
                item.setDescription(String.format("ROI=%.2f，低于阈值%.1f，消耗%.0f元", c.getPayOrderRoi(), roiThreshold, c.getCost()));
                item.setSuggestion("建议暂停计划或降低出价，检查落地页和人群定向");
                abnormalities.add(item);
            }
            if (c.getConvertCost() != null && c.getConvertCost() > cpaThreshold && c.getConvertCnt() != null && c.getConvertCnt() >= 3) {
                ReviewReport.AbnormalItem item = new ReviewReport.AbnormalItem();
                item.setType(AbnormalType.HIGH_CPA.getCode());
                item.setLevel(AbnormalType.HIGH_CPA.getDefaultLevel());
                item.setCampaignId(c.getCampaignId());
                item.setCampaignName(c.getCampaignName());
                item.setDescription(String.format("转化成本=%.0f元，高于阈值%.0f元", c.getConvertCost(), cpaThreshold));
                item.setSuggestion("建议优化创意素材，收窄人群定向，降低出价测试");
                abnormalities.add(item);
            }
            if (c.getCtr() != null && c.getCtr() < ctrThreshold && c.getShowCnt() != null && c.getShowCnt() > 10000) {
                ReviewReport.AbnormalItem item = new ReviewReport.AbnormalItem();
                item.setType(AbnormalType.LOW_CTR.getCode());
                item.setLevel(AbnormalType.LOW_CTR.getDefaultLevel());
                item.setCampaignId(c.getCampaignId());
                item.setCampaignName(c.getCampaignName());
                item.setDescription(String.format("CTR=%.2f%%，低于阈值%.1f%%", c.getCtr(), ctrThreshold));
                item.setSuggestion("建议更换创意素材，优化封面和标题");
                abnormalities.add(item);
            }
        }
        return abnormalities;
    }

    public List<AdCampaign> getTopCampaigns(List<AdCampaign> campaigns, int limit) {
        return campaigns.stream()
                .filter(c -> c.getCost() != null && c.getCost() > 200)
                .sorted(Comparator.comparing((AdCampaign c) -> c.getPayOrderRoi() != null ? c.getPayOrderRoi() : 0, Comparator.reverseOrder()))
                .limit(limit).collect(Collectors.toList());
    }

    public List<AdCampaign> getDecliningCampaigns(List<AdCampaign> campaigns, int limit) {
        return campaigns.stream()
                .filter(c -> c.getCost() != null && c.getCost() > 500)
                .filter(c -> c.getPayOrderRoi() != null && c.getPayOrderRoi() < roiThreshold)
                .sorted(Comparator.comparing(AdCampaign::getCost, Comparator.reverseOrder()))
                .limit(limit).collect(Collectors.toList());
    }

    public ReviewReport.BudgetSuggestion generateBudgetSuggestion(List<AdCampaign> campaigns, double totalBudget) {
        ReviewReport.BudgetSuggestion suggestion = new ReviewReport.BudgetSuggestion();
        suggestion.setTotalBudget(totalBudget);
        List<ReviewReport.CampaignBudget> allocations = new ArrayList<>();
        double totalScore = 0;
        for (AdCampaign c : campaigns) {
            double roi = c.getPayOrderRoi() != null ? c.getPayOrderRoi() : 0;
            totalScore += Math.max(roi, 0.1) * (c.getCost() != null ? Math.log10(c.getCost() + 1) : 1);
        }
        for (AdCampaign c : campaigns) {
            double roi = c.getPayOrderRoi() != null ? c.getPayOrderRoi() : 0;
            double score = Math.max(roi, 0.1) * (c.getCost() != null ? Math.log10(c.getCost() + 1) : 1);
            double suggestedBudget = totalScore > 0 ? totalBudget * score / totalScore : totalBudget / campaigns.size();
            ReviewReport.CampaignBudget cb = new ReviewReport.CampaignBudget();
            cb.setCampaignId(c.getCampaignId());
            cb.setCampaignName(c.getCampaignName());
            cb.setCurrentBudget(c.getCost());
            cb.setSuggestedBudget(Math.round(suggestedBudget * 100) / 100.0);
            cb.setAdjustRatio(c.getCost() != null && c.getCost() > 0 ? suggestedBudget / c.getCost() : 1);
            cb.setReason(roi >= roiThreshold * 1.5 ? "ROI优秀，建议加预算" : roi >= roiThreshold ? "ROI达标，维持预算" : "ROI不达标，建议缩减");
            allocations.add(cb);
        }
        suggestion.setAllocations(allocations);
        return suggestion;
    }
}
