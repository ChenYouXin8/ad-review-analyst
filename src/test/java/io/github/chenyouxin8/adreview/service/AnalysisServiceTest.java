package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.ReviewConfig;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisServiceTest {

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() {
        // ReviewConfig 默认值与历史硬编码保持一致：roi=1.5 / cpa=100 / ctr=1.0 / minCost=100
        analysisService = new AnalysisService(new ReviewConfig());
    }

    private AdCampaign campaign(long id, String name, double roi, double cost, double cpa, long convertCnt, double ctr, long showCnt) {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(id);
        c.setCampaignName(name);
        c.setPayOrderRoi(roi);
        c.setCost(cost);
        c.setConvertCost(cpa);
        c.setConvertCnt(convertCnt);
        c.setCtr(ctr);
        c.setShowCnt(showCnt);
        return c;
    }

    private boolean hasType(List<ReviewReport.AbnormalItem> items, String type) {
        return items.stream().anyMatch(i -> type.equals(i.getType()));
    }

    @Test
    void detectAbnormalities_detectsLowRoiHighCpaLowCtr() {
        List<AdCampaign> campaigns = new ArrayList<>();
        campaigns.add(campaign(1, "低ROI计划", 0.8, 500, 50, 10, 2.0, 50000));
        campaigns.add(campaign(2, "高CPA计划", 2.0, 500, 150, 10, 2.0, 50000));
        campaigns.add(campaign(3, "低CTR计划", 2.0, 500, 50, 10, 0.3, 50000));
        campaigns.add(campaign(4, "正常计划", 2.5, 500, 50, 10, 2.5, 50000));

        List<ReviewReport.AbnormalItem> items = analysisService.detectAbnormalities(campaigns);

        assertEquals(3, items.size());
        assertTrue(items.stream().anyMatch(i -> i.getCampaignName().equals("低ROI计划")));
        assertTrue(items.stream().anyMatch(i -> i.getCampaignName().equals("高CPA计划")));
        assertTrue(items.stream().anyMatch(i -> i.getCampaignName().equals("低CTR计划")));
    }

    @Test
    void detectAbnormalities_ignoresLowCostCampaign() {
        List<AdCampaign> campaigns = new ArrayList<>();
        // 消耗 50 元低于最低消耗门槛 100，不应判异常
        campaigns.add(campaign(1, "低消耗计划", 0.5, 50, 50, 10, 2.0, 50000));

        List<ReviewReport.AbnormalItem> items = analysisService.detectAbnormalities(campaigns);
        assertEquals(0, items.size());
    }

    // ---------- Sprint 4：补齐全套 6 种异常检测 ----------

    @Test
    void detectAbnormalities_budgetOverspend_whenCostFarAboveAverageAndLowRoi() {
        List<AdCampaign> campaigns = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            campaigns.add(campaign(i, "常规计划" + i, 3.0, 100, 50, 10, 2.0, 50000));
        }
        // 消耗 5000 远超全场平均（约 917）的 2.5 倍，且 ROI 低
        campaigns.add(campaign(99, "超支计划", 1.0, 5000, 50, 10, 2.0, 50000));

        List<ReviewReport.AbnormalItem> items = analysisService.detectAbnormalities(campaigns);

        assertTrue(hasType(items, "budget_overspend"), "应检测到预算超支异常");
        assertTrue(items.stream().anyMatch(i -> "budget_overspend".equals(i.getType())
                && "超支计划".equals(i.getCampaignName())));

        // 阈值生效：把倍数阈值调高到 10 倍后不再触发
        ReviewConfig relaxed = new ReviewConfig();
        relaxed.setOverspendRatioThreshold(10.0);
        List<ReviewReport.AbnormalItem> relaxedItems = new AnalysisService(relaxed).detectAbnormalities(campaigns);
        assertFalse(hasType(relaxedItems, "budget_overspend"), "阈值调高后不应再判超支");
    }

    @Test
    void detectAbnormalities_creativeDecay_whenHighImpressionsLowCtrAndHighCpc() {
        List<AdCampaign> campaigns = new ArrayList<>();
        AdCampaign good1 = campaign(1, "正常计划1", 3.0, 1000, 50, 10, 3.0, 50000);
        good1.setAvgClickCost(1.0);
        AdCampaign good2 = campaign(2, "正常计划2", 3.0, 1000, 50, 10, 3.0, 50000);
        good2.setAvgClickCost(1.0);
        AdCampaign decay = campaign(3, "素材衰退计划", 2.0, 1000, 50, 10, 0.8, 50000);
        decay.setAvgClickCost(10.0);   // 全场平均 CPC=4，10 > 4*1.5
        campaigns.add(good1);
        campaigns.add(good2);
        campaigns.add(decay);

        List<ReviewReport.AbnormalItem> items = analysisService.detectAbnormalities(campaigns);

        assertTrue(hasType(items, "creative_decay"), "应检测到素材衰退异常");
        assertTrue(items.stream().anyMatch(i -> "creative_decay".equals(i.getType())
                && "素材衰退计划".equals(i.getCampaignName())));

        // 阈值生效：把 CPC 倍数阈值提高到 5 倍后不再触发
        ReviewConfig relaxed = new ReviewConfig();
        relaxed.setCreativeDecayCpcMultiplier(5.0);
        List<ReviewReport.AbnormalItem> relaxedItems = new AnalysisService(relaxed).detectAbnormalities(campaigns);
        assertFalse(hasType(relaxedItems, "creative_decay"), "阈值调高后不应再判素材衰退");
    }

    @Test
    void detectAbnormalities_suddenDrop_whenMultiDayRoiPlunges() {
        List<AdCampaign> campaigns = new ArrayList<>();
        AdCampaign day1 = campaign(500, "突降计划", 3.0, 1000, 50, 10, 2.0, 50000);
        day1.setStatDate(LocalDate.of(2026, 10, 10));
        AdCampaign day2 = campaign(500, "突降计划", 1.0, 1000, 50, 10, 2.0, 50000);
        day2.setStatDate(LocalDate.of(2026, 10, 11));
        campaigns.add(day1);
        campaigns.add(day2);

        List<ReviewReport.AbnormalItem> items = analysisService.detectAbnormalities(campaigns);

        assertTrue(hasType(items, "sudden_drop"), "多日 ROI 下跌超 40% 应检测到效果突降");
        assertTrue(items.stream().anyMatch(i -> "sudden_drop".equals(i.getType())
                && i.getCampaignId() == 500L));
    }

    @Test
    void detectAbnormalities_suddenDrop_whenSingleDayHighCostZeroConversion() {
        List<AdCampaign> campaigns = new ArrayList<>();
        // 单日聚合数据：消耗 800（≥衰退门槛 500）但零转化
        campaigns.add(campaign(600, "零转化空耗计划", 2.0, 800, 0, 0, 2.0, 50000));

        List<ReviewReport.AbnormalItem> items = analysisService.detectAbnormalities(campaigns);

        assertTrue(hasType(items, "sudden_drop"), "大额零转化空耗应检测到效果突降");
    }

    @Test
    void detectAbnormalities_suddenDrop_notTriggeredWhenConversionExists() {
        List<AdCampaign> campaigns = new ArrayList<>();
        // 消耗虽高但有转化，不应判为空耗
        campaigns.add(campaign(601, "正常高消耗计划", 2.0, 800, 50, 10, 2.0, 50000));

        List<ReviewReport.AbnormalItem> items = analysisService.detectAbnormalities(campaigns);
        assertFalse(hasType(items, "sudden_drop"));
    }

    @Test
    void configThresholds_topAndDecliningMinCost_areApplied() {
        ReviewConfig config = new ReviewConfig();
        config.setTopCampaignMinCost(1000);
        config.setDecliningCampaignMinCost(2000);
        config.setRoiThreshold(2.0);
        AnalysisService service = new AnalysisService(config);

        List<AdCampaign> campaigns = new ArrayList<>();
        campaigns.add(campaign(1, "中等消耗计划", 1.0, 500, 50, 10, 2.0, 50000));
        campaigns.add(campaign(2, "高消耗计划", 1.0, 3000, 50, 10, 2.0, 50000));

        // topCampaignMinCost=1000：消耗 500 的计划被过滤
        assertEquals(List.of(3000.0), service.getTopCampaigns(campaigns, 5).stream().map(AdCampaign::getCost).toList());
        // decliningCampaignMinCost=2000 + roiThreshold=2.0：只有消耗 3000 且 ROI<2.0 的计划入选
        assertEquals(List.of(3000.0), service.getDecliningCampaigns(campaigns, 5).stream().map(AdCampaign::getCost).toList());
    }

    @Test
    void getTopCampaigns_returnsSortedByRoiDesc() {
        List<AdCampaign> campaigns = new ArrayList<>();
        campaigns.add(campaign(1, "A", 1.0, 500, 50, 10, 2.0, 50000));
        campaigns.add(campaign(2, "B", 3.0, 500, 50, 10, 2.0, 50000));
        campaigns.add(campaign(3, "C", 2.0, 500, 50, 10, 2.0, 50000));

        List<AdCampaign> top = analysisService.getTopCampaigns(campaigns, 2);

        assertEquals(2, top.size());
        assertEquals("B", top.get(0).getCampaignName());
        assertEquals("C", top.get(1).getCampaignName());
    }

    @Test
    void generateBudgetSuggestion_allocatesMoreToHighRoi() {
        List<AdCampaign> campaigns = new ArrayList<>();
        campaigns.add(campaign(1, "高ROI", 3.0, 1000, 50, 10, 2.0, 50000));
        campaigns.add(campaign(2, "低ROI", 0.5, 1000, 50, 10, 2.0, 50000));

        ReviewReport.BudgetSuggestion suggestion = analysisService.generateBudgetSuggestion(campaigns, 2000);

        assertEquals(2, suggestion.getAllocations().size());
        double highRoiBudget = suggestion.getAllocations().stream()
                .filter(a -> a.getCampaignName().equals("高ROI")).findFirst().orElseThrow().getSuggestedBudget();
        double lowRoiBudget = suggestion.getAllocations().stream()
                .filter(a -> a.getCampaignName().equals("低ROI")).findFirst().orElseThrow().getSuggestedBudget();
        assertTrue(highRoiBudget > lowRoiBudget, "高 ROI 计划应获得更多预算");
    }
}
