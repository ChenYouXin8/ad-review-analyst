package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.constant.DataSourceType;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.repository.CampaignRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class CsvImportServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 10);

    private static final String HEADER = "计划ID,计划名称,日期,消耗,展示数,点击数,转化数,支付GMV,支付订单数\n";
    private static final String ROW_1 =
            "10001,夏季新品连衣裙-信息流,2026-10-10,1000,50000,1000,50,2500,30\n";
    private static final String ROW_1_UPDATED =
            "10001,夏季新品连衣裙-信息流,2026-10-10,2000,50000,1000,50,2500,30\n";

    private CampaignRepository campaignRepository;
    private QianchuanService qianchuanService;
    private CsvImportService csvImportService;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;MODE=MySQL";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        dataSource.setDriverClassName("org.h2.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);

        campaignRepository = new CampaignRepository(new JdbcTemplate(dataSource));
        // MCP provider 为 null（不可用），本地库命中即走 LOCAL_IMPORT，无需真实 LLM
        qianchuanService = new QianchuanService(mock(ChatClient.Builder.class), null, campaignRepository);
        csvImportService = new CsvImportService(campaignRepository, qianchuanService);
    }

    private MockMultipartFile csvFile(String content, Charset charset) {
        return new MockMultipartFile("file", "report.csv", "text/csv", content.getBytes(charset));
    }

    @Test
    void importCsv_parsesChineseHeader_andComputesDerivedMetrics() {
        CsvImportService.ImportResult result = csvImportService.importCsv(csvFile(HEADER + ROW_1, StandardCharsets.UTF_8));

        assertEquals(1, result.importedCount());
        assertEquals(DATE, result.startDate());
        assertEquals(DATE, result.endDate());

        List<AdCampaign> saved = campaignRepository.findByDateRange(DATE, DATE);
        assertEquals(1, saved.size());
        AdCampaign c = saved.get(0);
        assertEquals(10001L, c.getCampaignId());
        assertEquals("夏季新品连衣裙-信息流", c.getCampaignName());
        assertEquals(1000.0, c.getCost(), 1e-6);
        // 衍生指标自动计算
        assertEquals(2.0, c.getCtr(), 1e-6);            // 1000 / 50000 * 100
        assertEquals(1.0, c.getAvgClickCost(), 1e-6);   // 1000 / 1000
        assertEquals(20.0, c.getConvertCost(), 1e-6);   // 1000 / 50
        assertEquals(5.0, c.getConvertRate(), 1e-6);    // 50 / 1000 * 100
        assertEquals(20.0, c.getAvgShowCost(), 1e-6);   // 1000 / 50000 * 1000
        assertEquals(2.5, c.getPayOrderRoi(), 1e-6);    // 2500 / 1000
    }

    @Test
    void importCsv_gbkEncoding_supported() {
        CsvImportService.ImportResult result = csvImportService.importCsv(csvFile(HEADER + ROW_1, Charset.forName("GBK")));

        assertEquals(1, result.importedCount());
        List<AdCampaign> saved = campaignRepository.findByDateRange(DATE, DATE);
        assertEquals("夏季新品连衣裙-信息流", saved.get(0).getCampaignName());
    }

    @Test
    void importCsv_writesWithLocalImportSource_andUpsertsExisting() {
        csvImportService.importCsv(csvFile(HEADER + ROW_1, StandardCharsets.UTF_8));
        // 同一 (campaign_id, stat_date) 再次导入应更新而非新增
        csvImportService.importCsv(csvFile(HEADER + ROW_1_UPDATED, StandardCharsets.UTF_8));

        List<AdCampaign> saved = campaignRepository.findByDateRange(DATE, DATE);
        assertEquals(1, saved.size(), "Upsert 应保证唯一键不重复");
        assertEquals(2000.0, saved.get(0).getCost(), 1e-6);
    }

    @Test
    void qianchuanService_prefersLocalImport_overMcp() {
        csvImportService.importCsv(csvFile(HEADER + ROW_1, StandardCharsets.UTF_8));

        // MCP provider 为 null（不可用），但因本地库已有数据，应优先命中 LOCAL_IMPORT
        QianchuanService.CampaignFetchResult result = qianchuanService.fetchCampaigns(DATE, DATE);

        assertEquals(DataSourceType.LOCAL_IMPORT, result.dataSource());
        assertEquals(1, result.campaigns().size());
    }

    @Test
    void qianchuanService_fallsBackToMock_whenNoLocalAndNoMcp() {
        QianchuanService.CampaignFetchResult result = qianchuanService.fetchCampaigns(DATE, DATE);

        assertEquals(DataSourceType.MOCK_FALLBACK, result.dataSource());
        assertFalse(result.campaigns().isEmpty());
    }

    @Test
    void importCsv_evictsQianchuanCache_soDataIsImmediatelyVisible() {
        // 取数一次 → 缓存为 Mock 兜底数据（本地库为空）
        QianchuanService.CampaignFetchResult before = qianchuanService.fetchCampaigns(DATE, DATE);
        assertEquals(DataSourceType.MOCK_FALLBACK, before.dataSource());

        // 导入 CSV 应主动失效缓存，使后续取数立即命中最新导入数据
        csvImportService.importCsv(csvFile(HEADER + ROW_1, StandardCharsets.UTF_8));
        QianchuanService.CampaignFetchResult after = qianchuanService.fetchCampaigns(DATE, DATE);

        assertEquals(DataSourceType.LOCAL_IMPORT, after.dataSource());
        assertEquals(1, after.campaigns().size());
    }
}
