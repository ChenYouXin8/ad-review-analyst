package io.github.chenyouxin8.adreview.job;

import io.github.chenyouxin8.adreview.model.ReviewReport;
import io.github.chenyouxin8.adreview.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@Slf4j
public class DailyReportJob {

    private final ReportService reportService;

    @Autowired
    public DailyReportJob(ReportService reportService) {
        this.reportService = reportService;
    }

    @Scheduled(cron = "${ad-review.daily-report-cron:0 0 9 * * ?}")
    public void generateDailyReport() {
        log.info("========== 开始每日自动复盘 ==========");
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            ReviewReport report = reportService.generateDailyReport(yesterday);
            log.info("日报生成成功: reportId={}, ROI={}", report.getReportId(), report.getSummary().getOverallRoi());
        } catch (Exception e) { log.error("日报生成失败", e); }
        log.info("========== 每日自动复盘结束 ==========");
    }
}
