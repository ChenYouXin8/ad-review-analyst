package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportStorageServiceTest {

    @TempDir
    Path tempDir;

    private ReportStorageService storageService;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        storageService = new ReportStorageService(mapper);
        Field f = ReportStorageService.class.getDeclaredField("storagePath");
        f.setAccessible(true);
        f.set(storageService, tempDir.toString());
        storageService.init();
    }

    private ReviewReport sampleReport(String id) {
        ReviewReport report = new ReviewReport();
        report.setReportId(id);
        report.setReportType("daily");
        report.setStartDate(LocalDate.of(2026, 9, 14));
        report.setEndDate(LocalDate.of(2026, 9, 15));
        report.setGeneratedAt(LocalDateTime.of(2026, 9, 15, 10, 0));
        report.setOverview("测试概览");
        report.setSuggestions(List.of("建议一", "建议二"));
        return report;
    }

    @Test
    void saveAndGet_roundTrip() {
        ReviewReport report = sampleReport("report001");
        storageService.saveReport(report);

        ReviewReport loaded = storageService.getReport("report001");
        assertEquals("report001", loaded.getReportId());
        assertEquals("daily", loaded.getReportType());
        assertEquals(LocalDate.of(2026, 9, 14), loaded.getStartDate());
        assertEquals(LocalDate.of(2026, 9, 15), loaded.getEndDate());
        assertEquals(LocalDateTime.of(2026, 9, 15, 10, 0), loaded.getGeneratedAt());
        assertEquals("测试概览", loaded.getOverview());
        assertEquals(2, loaded.getSuggestions().size());
    }

    @Test
    void getMissingReport_throwsNotFound() {
        BusinessException e = assertThrows(BusinessException.class, () -> storageService.getReport("not-exist"));
        assertEquals(40401, e.getCode());
    }

    @Test
    void deleteReport_removesAndThenNotFound() {
        ReviewReport report = sampleReport("report002");
        storageService.saveReport(report);

        storageService.deleteReport("report002");
        assertThrows(BusinessException.class, () -> storageService.getReport("report002"));
    }

    @Test
    void deleteMissingReport_throwsNotFound() {
        BusinessException e = assertThrows(BusinessException.class, () -> storageService.deleteReport("not-exist"));
        assertEquals(40401, e.getCode());
    }

    @Test
    void listReports_returnsNewestFirstAndPagination() {
        for (int i = 1; i <= 5; i++) {
            ReviewReport r = sampleReport("r" + i);
            r.setGeneratedAt(LocalDateTime.of(2026, 9, 10 + i, 10, 0));
            storageService.saveReport(r);
        }

        assertEquals(5, storageService.countReports());

        List<ReviewReport> page1 = storageService.listReports(1, 2);
        assertEquals(2, page1.size());
        // 按生成时间倒序：r5, r4
        assertEquals("r5", page1.get(0).getReportId());
        assertEquals("r4", page1.get(1).getReportId());

        List<ReviewReport> page2 = storageService.listReports(2, 2);
        assertEquals(2, page2.size());
        assertEquals("r3", page2.get(0).getReportId());
        assertEquals("r2", page2.get(1).getReportId());
    }

    @Test
    void listReports_clampsPageAndSize() {
        for (int i = 1; i <= 3; i++) storageService.saveReport(sampleReport("c" + i));
        // page=0 -> 1, size=0 -> 1, size 上限 100
        List<ReviewReport> result = storageService.listReports(0, 0);
        assertEquals(1, result.size());
        List<ReviewReport> big = storageService.listReports(0, 500);
        assertEquals(3, big.size());
    }

    @Test
    void invalidReportId_rejected() {
        assertThrows(BusinessException.class, () -> storageService.saveReport(sampleReport("../evil")));
        assertThrows(BusinessException.class, () -> storageService.getReport("../evil"));
        assertThrows(BusinessException.class, () -> storageService.deleteReport("a/b"));
    }

    @Test
    void saveReport_withCampaignData_roundTrip() {
        ReviewReport report = sampleReport("report003");
        AdCampaign campaign = new AdCampaign();
        campaign.setCampaignId(10001L);
        campaign.setCampaignName("测试计划");
        campaign.setStatDate(LocalDate.of(2026, 9, 15));
        campaign.setCost(1234.56);
        campaign.setPayOrderRoi(2.5);
        report.setTopCampaigns(List.of(campaign));

        storageService.saveReport(report);
        ReviewReport loaded = storageService.getReport("report003");
        assertEquals(1, loaded.getTopCampaigns().size());
        assertEquals("测试计划", loaded.getTopCampaigns().get(0).getCampaignName());
        assertEquals(2.5, loaded.getTopCampaigns().get(0).getPayOrderRoi(), 0.001);
    }
}
