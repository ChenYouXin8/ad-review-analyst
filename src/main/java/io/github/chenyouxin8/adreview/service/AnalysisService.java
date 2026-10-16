package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.constant.AbnormalType;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.ReviewConfig;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 投放数据分析服务。
 *
 * <p>所有阈值均来自 {@link ReviewConfig}，代码中不再保留魔法数字。</p>
 */
@Service
@Slf4j
public class AnalysisService {

    private final ReviewConfig config;

    public AnalysisService(ReviewConfig config) {
        this.config = config;
    }

    /**
     * 全套 6 种异常检测：LOW_ROI、HIGH_CPA、LOW_CTR、BUDGET_OVERSPEND、CREATIVE_DECAY、SUDDEN_DROP。
     */
    public List<ReviewReport.AbnormalItem> detectAbnormalities(List<AdCampaign> campaigns) {
        List<ReviewReport.AbnormalItem> abnormalities = new ArrayList<>();
        if (campaigns == null || campaigns.isEmpty()) {
            return abnormalities;
        }
        double avgCost = campaigns.stream()
                .filter(c -> c.getCost() != null)
                .mapToDouble(AdCampaign::getCost)
                .average().orElse(0);
        double avgCpc = campaigns.stream()
                .map(this::cpcOf)
                .filter(v -> v != null && v > 0)
                .mapToDouble(Double::doubleValue)
                .average().orElse(0);

        for (AdCampaign c : campaigns) {
            // 1. LOW_ROI：ROI 低于阈值且消耗达到异常门槛
            if (c.getPayOrderRoi() != null && c.getPayOrderRoi() < config.getRoiThreshold()
                    && c.getCost() != null && c.getCost() > config.getAbnormalMinCost()) {
                add(abnormalities, AbnormalType.LOW_ROI, c,
                        String.format("ROI=%.2f，低于阈值%.1f，消耗%.0f元",
                                c.getPayOrderRoi(), config.getRoiThreshold(), c.getCost()),
                        "建议暂停计划或降低出价，检查落地页和人群定向");
            }
            // 2. HIGH_CPA：转化成本过高且达到最小转化数
            if (c.getConvertCost() != null && c.getConvertCost() > config.getCpaThreshold()
                    && c.getConvertCnt() != null && c.getConvertCnt() >= config.getCpaMinConversions()) {
                add(abnormalities, AbnormalType.HIGH_CPA, c,
                        String.format("转化成本=%.0f元，高于阈值%.0f元", c.getConvertCost(), config.getCpaThreshold()),
                        "建议优化创意素材，收窄人群定向，降低出价测试");
            }
            // 3. LOW_CTR：点击率过低且展示量达标
            if (c.getCtr() != null && c.getCtr() < config.getCtrThreshold()
                    && c.getShowCnt() != null && c.getShowCnt() > config.getCtrMinImpressions()) {
                add(abnormalities, AbnormalType.LOW_CTR, c,
                        String.format("CTR=%.2f%%，低于阈值%.1f%%", c.getCtr(), config.getCtrThreshold()),
                        "建议更换创意素材，优化封面和标题");
            }
            // 4. BUDGET_OVERSPEND：消耗异常偏高（超全场平均）且低产出
            if (c.getCost() != null && avgCost > 0
                    && c.getCost() > config.getAbnormalMinCost() * 2
                    && c.getCost() > avgCost * config.getOverspendRatioThreshold()
                    && c.getPayOrderRoi() != null && c.getPayOrderRoi() < config.getRoiThreshold()) {
                add(abnormalities, AbnormalType.BUDGET_OVERSPEND, c,
                        String.format("消耗%.0f元远超全场平均%.0f元的%.1f倍且ROI仅%.2f，存在超支风险",
                                c.getCost(), avgCost, config.getOverspendRatioThreshold(), c.getPayOrderRoi()),
                        "高消耗低产出超支风险，建议立即控本或设置日限额");
            }
            // 5. CREATIVE_DECAY：曝光充足但点击率低迷、点击成本攀升
            Double cpc = cpcOf(c);
            if (c.getShowCnt() != null && c.getShowCnt() >= config.getCtrMinImpressions()
                    && c.getCtr() != null && c.getCtr() < config.getCtrThreshold() * 1.2
                    && cpc != null && cpc > 0 && avgCpc > 0
                    && cpc > avgCpc * config.getCreativeDecayCpcMultiplier()) {
                add(abnormalities, AbnormalType.CREATIVE_DECAY, c,
                        String.format("曝光%d次但CTR仅%.2f%%，点击成本%.2f元高于全场平均%.2f元的%.1f倍",
                                c.getShowCnt(), c.getCtr(), cpc, avgCpc, config.getCreativeDecayCpcMultiplier()),
                        "曝光充足但点击率低迷、点击成本攀升，存在素材审美疲劳，建议更换前3秒黄金画面或翻新素材");
            }
        }
        // 6. SUDDEN_DROP：效果突降（多日环比下跌 / 单日零转化空耗）
        detectSuddenDrop(campaigns, abnormalities);
        return abnormalities;
    }

    /**
     * SUDDEN_DROP 检测。
     * <p>同一计划在列表中存在多日记录时，比较最新一天与前序历史均值；
     * 若列表为该计划的单日聚合数据，则退化为「高消耗零转化空耗」检测。</p>
     */
    private void detectSuddenDrop(List<AdCampaign> campaigns, List<ReviewReport.AbnormalItem> abnormalities) {
        Map<Long, List<AdCampaign>> byId = campaigns.stream()
                .filter(c -> c.getCampaignId() != null)
                .collect(Collectors.groupingBy(AdCampaign::getCampaignId, LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<Long, List<AdCampaign>> entry : byId.entrySet()) {
            List<AdCampaign> group = entry.getValue();
            List<AdCampaign> dated = group.stream()
                    .filter(c -> c.getStatDate() != null)
                    .sorted(Comparator.comparing(AdCampaign::getStatDate))
                    .collect(Collectors.toList());
            if (dated.size() >= 2) {
                AdCampaign latest = dated.get(dated.size() - 1);
                double historyAvgRoi = dated.subList(0, dated.size() - 1).stream()
                        .filter(c -> c.getPayOrderRoi() != null)
                        .mapToDouble(AdCampaign::getPayOrderRoi)
                        .average().orElse(0);
                double latestRoi = latest.getPayOrderRoi() != null ? latest.getPayOrderRoi() : 0;
                if (historyAvgRoi > 0 && latestRoi < historyAvgRoi * 0.6
                        && latest.getCost() != null && latest.getCost() >= config.getAbnormalMinCost()) {
                    add(abnormalities, AbnormalType.SUDDEN_DROP, latest,
                            String.format("最新ROI=%.2f较历史均值%.2f下跌超40%%（当日消耗%.0f元）",
                                    latestRoi, historyAvgRoi, latest.getCost()),
                            "计划转化效果断崖式下跌或大额空耗，建议排查定向跑偏或直播间承接异常");
                }
            } else {
                // 单日聚合数据：大额消耗且零转化
                AdCampaign c = group.get(0);
                long conversions = c.getConvertCnt() != null ? c.getConvertCnt() : 0;
                if (c.getCost() != null && c.getCost() >= config.getDecliningCampaignMinCost() && conversions == 0) {
                    add(abnormalities, AbnormalType.SUDDEN_DROP, c,
                            String.format("消耗%.0f元但转化数为0，大额空耗", c.getCost()),
                            "计划转化效果断崖式下跌或大额空耗，建议排查定向跑偏或直播间承接异常");
                }
            }
        }
    }

    private void add(List<ReviewReport.AbnormalItem> target, AbnormalType type, AdCampaign c,
                     String description, String suggestion) {
        ReviewReport.AbnormalItem item = new ReviewReport.AbnormalItem();
        item.setType(type.getCode());
        item.setLevel(type.getDefaultLevel());
        item.setCampaignId(c.getCampaignId());
        item.setCampaignName(c.getCampaignName());
        item.setDescription(description);
        item.setSuggestion(suggestion);
        target.add(item);
    }

    /** 计算平均点击成本：优先取模型字段，缺失则用 消耗/点击 推导；不可得时返回 null。 */
    private Double cpcOf(AdCampaign c) {
        if (c.getAvgClickCost() != null && c.getAvgClickCost() > 0) {
            return c.getAvgClickCost();
        }
        if (c.getClickCnt() != null && c.getClickCnt() > 0 && c.getCost() != null) {
            return c.getCost() / c.getClickCnt();
        }
        return null;
    }

    public List<AdCampaign> getTopCampaigns(List<AdCampaign> campaigns, int limit) {
        return campaigns.stream()
                .filter(c -> c.getCost() != null && c.getCost() > config.getTopCampaignMinCost())
                .sorted(Comparator.comparing((AdCampaign c) -> c.getPayOrderRoi() != null ? c.getPayOrderRoi() : 0, Comparator.reverseOrder()))
                .limit(limit).collect(Collectors.toList());
    }

    public List<AdCampaign> getDecliningCampaigns(List<AdCampaign> campaigns, int limit) {
        return campaigns.stream()
                .filter(c -> c.getCost() != null && c.getCost() > config.getDecliningCampaignMinCost())
                .filter(c -> c.getPayOrderRoi() != null && c.getPayOrderRoi() < config.getRoiThreshold())
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
            cb.setReason(roi >= config.getRoiThreshold() * 1.5 ? "ROI优秀，建议加预算"
                    : roi >= config.getRoiThreshold() ? "ROI达标，维持预算" : "ROI不达标，建议缩减");
            allocations.add(cb);
        }
        suggestion.setAllocations(allocations);
        return suggestion;
    }
}
