package io.github.chenyouxin8.adreview.tool;

import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.service.AnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地数据分析工具箱测试：
 * 覆盖 4 个 @Tool 方法的排序（ROI/消耗升降序）、Limit 截断、关键词搜索、异常检测，
 * 以及 invokedTools / referencedCampaigns 调用轨迹收集的准确性。
 */
class AdDataAnalyticsToolsTest {

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

    /**
     * 构造 4 个对照组计划：
     * A 高ROI低CPA；B 低ROI(异常)且展示量小；C 高CPA(异常)；D 低ROI(异常)
     */
    private List<AdCampaign> fourCampaigns() {
        List<AdCampaign> list = new ArrayList<>();
        list.add(campaign(101L, "夏季新品推广-信息流", 1000.0, 3.0, 3000.0, 2.0, 40.0, 5.0, 20L, 60000L));
        list.add(campaign(102L, "品牌词搜索-精准", 500.0, 1.0, 500.0, 0.5, 80.0, 2.0, 10L, 5000L));
        list.add(campaign(103L, "达人带货-短视频", 2000.0, 2.0, 4000.0, 3.0, 150.0, 4.0, 15L, 80000L));
        list.add(campaign(104L, "直播间引流-智能", 300.0, 0.5, 150.0, 1.5, 30.0, 1.0, 5L, 9000L));
        return list;
    }

    private AdCampaign campaign(long id, String name, double cost, double roi, double gmv,
                                double ctr, double convertCost, double convertRate,
                                long convertCnt, long showCnt) {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(id);
        c.setCampaignName(name);
        c.setCost(cost);
        c.setPayOrderRoi(roi);
        c.setPayOrderAmount(gmv);
        c.setCtr(ctr);
        c.setConvertCost(convertCost);
        c.setConvertRate(convertRate);
        c.setConvertCnt(convertCnt);
        c.setShowCnt(showCnt);
        c.setClickCnt((long) (showCnt * ctr / 100));
        return c;
    }

    private AccountSummary summaryOf(List<AdCampaign> campaigns) {
        AccountSummary s = new AccountSummary();
        s.setTotalCost(3800.0);
        s.setTotalGmv(7650.0);
        s.setOverallRoi(2.01);
        s.setOverallCtr(1.75);
        s.setOverallCpa(76.0);
        s.setTotalConvert(50L);
        s.setActiveCampaignCount(campaigns.size());
        s.setAbnormalCampaignCount(3);
        return s;
    }

    private List<Long> refIds(AdDataAnalyticsTools tools) {
        return tools.getReferencedCampaigns().stream().map(AdCampaign::getCampaignId).toList();
    }

    @Test
    void getAccountOverview_returnsSummary_andRecordsToolWithoutCampaignRefs() {
        List<AdCampaign> campaigns = fourCampaigns();
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        String text = tools.getAccountOverview();

        assertTrue(text.contains("计划数 4"));
        assertTrue(text.contains("整体ROI 2.01"));
        assertEquals(List.of("getAccountOverview"), tools.getInvokedTools());
        // 大盘类工具不涉及具体计划
        assertTrue(tools.getReferencedCampaigns().isEmpty());
    }

    @Test
    void queryCampaignsByMetric_descendingByRoi_returnsSortedAndCollectsTrajectory() {
        List<AdCampaign> campaigns = fourCampaigns();
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        String text = tools.queryCampaignsByMetric("roi", false, 10);

        assertTrue(text.contains("支付ROI"));
        // 降序：A(3.0) > C(2.0) > B(1.0) > D(0.5)
        assertEquals(List.of(101L, 103L, 102L, 104L), refIds(tools));
        assertEquals(List.of("queryCampaignsByMetric"), tools.getInvokedTools());
    }

    @Test
    void queryCampaignsByMetric_ascendingByCost_findsLowestFirst() {
        List<AdCampaign> campaigns = fourCampaigns();
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        tools.queryCampaignsByMetric("cost", true, 10);

        // 升序消耗：D(300) < B(500) < A(1000) < C(2000)
        assertEquals(List.of(104L, 102L, 101L, 103L), refIds(tools));
    }

    @Test
    void queryCampaignsByMetric_clampsLimitBetween1And20() {
        List<AdCampaign> campaigns = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            campaigns.add(campaign(2000L + i, "批量计划-" + i, 100.0 + i, i * 0.1, 0.0, 1.0, 50.0, 3.0, 5L, 20000L));
        }
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        // 传入超上限 100 → 截断为 20
        tools.queryCampaignsByMetric("cost", false, 100);
        assertEquals(20, tools.getReferencedCampaigns().size());

        // 传入 0 → 兜底为 1
        AdDataAnalyticsTools tools2 = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);
        tools2.queryCampaignsByMetric("cost", false, 0);
        assertEquals(1, tools2.getReferencedCampaigns().size());
    }

    @Test
    void queryCampaignsByMetric_repeatedCall_recordsToolOnlyOnce() {
        List<AdCampaign> campaigns = fourCampaigns();
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        tools.queryCampaignsByMetric("roi", false, 5);
        tools.queryCampaignsByMetric("cost", true, 5);

        assertEquals(List.of("queryCampaignsByMetric"), tools.getInvokedTools());
    }

    @Test
    void findAbnormalCampaigns_returnsAbnormalItems_andReferencesHitCampaigns() {
        List<AdCampaign> campaigns = fourCampaigns();
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        String text = tools.findAbnormalCampaigns();

        // 异常项：B 低ROI、C 高CPA、D 低ROI
        assertTrue(text.contains("共检测到 3 个异常计划"));
        assertTrue(text.contains("品牌词搜索-精准"));
        assertTrue(text.contains("达人带货-短视频"));
        assertEquals(List.of(102L, 103L, 104L), refIds(tools));
        assertEquals(List.of("findAbnormalCampaigns"), tools.getInvokedTools());
    }

    @Test
    void searchCampaignByKeyword_matchesFuzzyAndLimits10() {
        List<AdCampaign> campaigns = fourCampaigns();
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        String text = tools.searchCampaignByKeyword("直播间");

        assertTrue(text.contains("匹配到 1 个"));
        assertTrue(text.contains("直播间引流-智能"));
        assertEquals(List.of(104L), refIds(tools));

        // 命中数量上限 10：构造 12 个同关键词计划
        List<AdCampaign> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(campaign(3000L + i, "批量计划-" + i, 100.0, 1.0, 0.0, 1.0, 50.0, 1.0, 5L, 20000L));
        }
        AdDataAnalyticsTools tools2 = new AdDataAnalyticsTools(many, summaryOf(many), analysisService);
        tools2.searchCampaignByKeyword("批量");
        assertEquals(10, tools2.getReferencedCampaigns().size());
    }

    @Test
    void searchCampaignByKeyword_noMatch_returnsEmptyMessage() {
        List<AdCampaign> campaigns = fourCampaigns();
        AdDataAnalyticsTools tools = new AdDataAnalyticsTools(campaigns, summaryOf(campaigns), analysisService);

        String text = tools.searchCampaignByKeyword("不存在的关键词");

        assertTrue(text.contains("没有找到"));
        assertTrue(tools.getReferencedCampaigns().isEmpty());
    }
}
