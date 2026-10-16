package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.constant.DataSourceType;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.repository.CampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * QianchuanService 取数缓存测试：
 * 验证同一日期段二次取数命中内存缓存（下游仓储仅访问一次），
 * 以及 {@link QianchuanService#evictCache()} 主动失效后恢复访问下游。
 */
class QianchuanServiceTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 10);
    private static final LocalDate END = LocalDate.of(2026, 10, 12);

    private CampaignRepository campaignRepository;
    private QianchuanService qianchuanService;

    @BeforeEach
    void setUp() {
        campaignRepository = mock(CampaignRepository.class);
        // MCP provider 为 null：本地有数据走 LOCAL_IMPORT，无数据则走 MOCK_FALLBACK，均不触发真实 LLM
        qianchuanService = new QianchuanService(mock(ChatClient.Builder.class), null, campaignRepository);
    }

    private AdCampaign sampleCampaign() {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(10001L);
        c.setCampaignName("缓存测试计划");
        c.setStatDate(START);
        c.setCost(1000.0);
        c.setPayOrderRoi(2.0);
        return c;
    }

    @Test
    void fetchCampaigns_secondCallWithinTtl_hitsCache_andSkipsDownstream() {
        when(campaignRepository.findByDateRange(any(), any())).thenReturn(List.of(sampleCampaign()));

        QianchuanService.CampaignFetchResult first = qianchuanService.fetchCampaigns(START, END);
        QianchuanService.CampaignFetchResult second = qianchuanService.fetchCampaigns(START, END);

        assertEquals(DataSourceType.LOCAL_IMPORT, first.dataSource());
        assertEquals(DataSourceType.LOCAL_IMPORT, second.dataSource());
        // 二次取数命中缓存，下游仓储仅被访问一次
        verify(campaignRepository, times(1)).findByDateRange(any(), any());
    }

    @Test
    void evictCache_clearsCache_soNextFetchReachesDownstreamAgain() {
        when(campaignRepository.findByDateRange(any(), any())).thenReturn(List.of(sampleCampaign()));

        qianchuanService.fetchCampaigns(START, END);
        qianchuanService.evictCache();
        qianchuanService.fetchCampaigns(START, END);

        verify(campaignRepository, times(2)).findByDateRange(any(), any());
    }

    @Test
    void fetchCampaigns_mockFallbackResult_isAlsoCached() {
        when(campaignRepository.findByDateRange(any(), any())).thenReturn(List.of());

        QianchuanService.CampaignFetchResult result = qianchuanService.fetchCampaigns(START, END);
        assertEquals(DataSourceType.MOCK_FALLBACK, result.dataSource());
        assertFalse(result.campaigns().isEmpty());

        // Mock 兜底同样走缓存，避免仪表盘高频刷新重复生成
        qianchuanService.fetchCampaigns(START, END);
        verify(campaignRepository, times(1)).findByDateRange(any(), any());
    }

    @Test
    void fetchCampaigns_differentDateRange_usesDistinctCacheKeys() {
        when(campaignRepository.findByDateRange(any(), any())).thenReturn(List.of(sampleCampaign()));

        qianchuanService.fetchCampaigns(START, END);
        qianchuanService.fetchCampaigns(START, START);

        verify(campaignRepository, times(2)).findByDateRange(any(), any());
    }

    @Test
    void fetchCampaigns_startAfterEnd_throwsWithoutTouchingCache() {
        assertThrows(RuntimeException.class, () -> qianchuanService.fetchCampaigns(END, START));
        verify(campaignRepository, never()).findByDateRange(any(), any());
    }
}
