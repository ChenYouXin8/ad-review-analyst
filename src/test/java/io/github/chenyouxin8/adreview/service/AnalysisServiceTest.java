package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisServiceTest {

    private AnalysisService analysisService;

    @BeforeEach
    void setUp() throws Exception {
        analysisService = new AnalysisService();
        setField(analysisService, "roiThreshold", 1.5);
        setField(analysisService, "cpaThreshold", 100.0);
        setField(analysisService, "ctrThreshold", 1.0);
        setField(analysisService, "abnormalMinCost", 100.0);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
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
