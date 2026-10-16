package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.constant.DataSourceType;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.NaturalQueryResult;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * ReportService 编排逻辑测试：
 * 1. 验证取数仅发生一次（消除重复取数）；
 * 2. 验证 dataSource / degraded 标记在正常与降级场景下的正确性。
 */
class ReportServiceTest {

    private static final String VALID_JSON =
            "{\"overview\":\"概览文本\",\"deepAnalysis\":\"深度分析文本\",\"actionableSuggestions\":[\"建议A\",\"建议B\"]}";

    private QianchuanService qianchuanService;
    private ReportStorageService storageService;
    private ChatClient chatClient;
    private ReportService reportService;

    private final LocalDate date = LocalDate.now();

    @BeforeEach
    void setUp() throws Exception {
        qianchuanService = mock(QianchuanService.class);
        storageService = mock(ReportStorageService.class);

        AnalysisService analysisService = new AnalysisService();
        setField(analysisService, "roiThreshold", 1.5);
        setField(analysisService, "cpaThreshold", 100.0);
        setField(analysisService, "ctrThreshold", 1.0);
        setField(analysisService, "abnormalMinCost", 100.0);

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(builder.build()).thenReturn(chatClient);

        reportService = new ReportService(builder, qianchuanService, analysisService, storageService);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private List<AdCampaign> sampleCampaigns() {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(10001L);
        c.setCampaignName("测试计划");
        c.setStatDate(date);
        c.setShowCnt(50000L);
        c.setClickCnt(1000L);
        c.setCtr(2.0);
        c.setCost(500.0);
        c.setConvertCnt(10L);
        c.setConvertCost(50.0);
        c.setPayOrderAmount(1000.0);
        c.setPayOrderRoi(2.0);
        return List.of(c);
    }

    private AccountSummary sampleSummary() {
        AccountSummary s = new AccountSummary();
        s.setTotalCost(500.0);
        s.setTotalGmv(1000.0);
        s.setOverallRoi(2.0);
        s.setOverallCtr(2.0);
        s.setOverallCpa(50.0);
        s.setActiveCampaignCount(1);
        s.setAbnormalCampaignCount(0);
        return s;
    }

    /** 桩：一次取数返回指定来源，并给定汇总计算结果。 */
    private void stubFetch(DataSourceType source) {
        when(qianchuanService.fetchCampaigns(any(), any()))
                .thenReturn(new QianchuanService.CampaignFetchResult(sampleCampaigns(), source));
        when(qianchuanService.calculateAccountSummary(any(), any(), any())).thenReturn(sampleSummary());
    }

    private void stubAiContent(String content) {
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn(content);
    }

    /** 供 naturalQuery 用例使用的 6 个计划：消耗 100~600，ROI 1.0~6.0。 */
    private List<AdCampaign> queryCampaigns() {
        List<AdCampaign> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            AdCampaign c = new AdCampaign();
            c.setCampaignId(20001L + i);
            c.setCampaignName("查询计划-" + i);
            c.setStatDate(date);
            c.setCost(100.0 * (i + 1));
            c.setPayOrderRoi(1.0 + i);
            c.setPayOrderAmount(100.0 * (i + 1));
            list.add(c);
        }
        return list;
    }

    /** 桩：取数返回指定来源与计划列表，并给定汇总计算结果。 */
    private void stubQueryFetch(DataSourceType source) {
        when(qianchuanService.fetchCampaigns(any(), any()))
                .thenReturn(new QianchuanService.CampaignFetchResult(queryCampaigns(), source));
        when(qianchuanService.calculateAccountSummary(any(), any(), any())).thenReturn(sampleSummary());
    }

    @Test
    void naturalQuery_whenAiSucceeds_returnsAiAnswerWithoutDegrade() {
        stubQueryFetch(DataSourceType.LOCAL_IMPORT);
        // Tool Calling 链路上多一层 .tools(...)，需显式桩到最终 content()
        when(chatClient.prompt().user(anyString()).tools(any()).call().content())
                .thenReturn("这是 AI 基于本地工具得出的回答");

        NaturalQueryResult result = reportService.naturalQuery("哪个计划 ROI 最高？", date, date);

        assertEquals("这是 AI 基于本地工具得出的回答", result.getAnswer());
        assertFalse(result.getDegraded());
        assertEquals("LOCAL_IMPORT", result.getDataSource());
        // 取数仅发生一次，工具直接消费内存列表，零重复取数
        verify(qianchuanService, times(1)).fetchCampaigns(any(), any());
        // 用例中 Mock 不会真正触发工具，故 AI 仅回答汇总 → 默认取消耗最高的前 5 条
        assertEquals(5, result.getRelatedCampaigns().size());
        assertEquals(20006L, result.getRelatedCampaigns().get(0).getCampaignId());
        assertEquals("general", result.getIntent());
        assertTrue(result.getInvokedTools().isEmpty());
    }

    @Test
    void naturalQuery_whenAiThrows_thenDegradedWithLocalRuleFallback() {
        stubQueryFetch(DataSourceType.MOCK_FALLBACK);
        when(chatClient.prompt().user(anyString()).tools(any()).call().content())
                .thenThrow(new RuntimeException("LLM 服务不可用"));

        NaturalQueryResult result = reportService.naturalQuery("今天整体投放怎么样？", date, date);

        // 降级被显式标记，且数据来源如实透传
        assertTrue(result.getDegraded());
        assertEquals("MOCK_FALLBACK", result.getDataSource());
        // 兜底回答基于本地规则拼装，绝不返回“AI服务不可用”这类空话
        assertNotNull(result.getAnswer());
        assertTrue(result.getAnswer().contains("本地规则汇总"));
        assertTrue(result.getAnswer().contains("查询计划-5"));
        assertFalse(result.getRelatedCampaigns().isEmpty());
    }

    @Test
    void generateReport_fetchesDataOnlyOnce() {
        stubFetch(DataSourceType.MCP_LIVE);
        stubAiContent(VALID_JSON);

        reportService.generateDailyReport(date);

        // 取数仅发生一次，且不存在任何内部二次取数路径
        verify(qianchuanService, times(1)).fetchCampaigns(any(), any());
        verify(qianchuanService, never()).getCampaignReports(any(), any());
        verify(qianchuanService, never()).getAccountSummary(any(), any());
        // 汇总基于内存计算一次
        verify(qianchuanService, times(1)).calculateAccountSummary(any(), any(), any());
        verify(storageService, times(1)).saveReport(any());
    }

    @Test
    void generateReport_whenLive_thenNotDegraded() {
        stubFetch(DataSourceType.MCP_LIVE);
        stubAiContent(VALID_JSON);

        ReviewReport report = reportService.generateDailyReport(date);

        assertEquals("MCP_LIVE", report.getDataSource());
        assertFalse(report.getDegraded());
        assertEquals("MCP_LIVE", report.getSummary().getDataSource());
        assertEquals("概览文本", report.getOverview());
        assertEquals("深度分析文本", report.getAiAnalysis());
        assertEquals(List.of("建议A", "建议B"), report.getSuggestions());
    }

    @Test
    void generateReport_whenMockFallback_thenDataSourceTagged() {
        stubFetch(DataSourceType.MOCK_FALLBACK);
        stubAiContent(VALID_JSON);

        ReviewReport report = reportService.generateDailyReport(date);

        assertEquals("MOCK_FALLBACK", report.getDataSource());
        assertEquals("MOCK_FALLBACK", report.getSummary().getDataSource());
        assertFalse(report.getDegraded());
    }

    @Test
    void generateReport_whenAiThrows_thenDegradedWithRuleFallback() {
        stubFetch(DataSourceType.MCP_LIVE);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenThrow(new RuntimeException("LLM 服务不可用"));

        ReviewReport report = reportService.generateDailyReport(date);

        // 降级状态被显式标记，绝不静默吞掉
        assertTrue(report.getDegraded());
        // 数据来源仍如实标记为实时数据
        assertEquals("MCP_LIVE", report.getDataSource());
        // 兜底结论由规则确定性产出，保证报告可用
        assertNotNull(report.getOverview());
        assertNotNull(report.getAiAnalysis());
        assertFalse(report.getSuggestions().isEmpty());
    }
}
