package io.github.chenyouxin8.adreview.controller;

import io.github.chenyouxin8.adreview.common.ApiResponse;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import io.github.chenyouxin8.adreview.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/report")
@Tag(name = "复盘报告", description = "投流复盘报告生成与管理")
public class ReportController {

    private final ReportService reportService;

    @Autowired
    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/daily")
    @Operation(summary = "生成日报")
    public ApiResponse<ReviewReport> generateDailyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) date = LocalDate.now().minusDays(1);
        return ApiResponse.ok(reportService.generateDailyReport(date));
    }

    @GetMapping("/weekly")
    @Operation(summary = "生成周报")
    public ApiResponse<ReviewReport> generateWeeklyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        if (endDate == null) endDate = LocalDate.now();
        return ApiResponse.ok(reportService.generateWeeklyReport(endDate));
    }

    @GetMapping("/custom")
    @Operation(summary = "生成自定义时间段报告")
    public ApiResponse<ReviewReport> generateCustomReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(reportService.generateCustomReport(startDate, endDate));
    }

    @GetMapping("/list")
    @Operation(summary = "获取历史报告列表")
    public ApiResponse<List<ReviewReport>> getHistoryReports(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(reportService.getHistoryReports(page, size));
    }

    @GetMapping("/{reportId}")
    @Operation(summary = "获取报告详情")
    public ApiResponse<ReviewReport> getReportDetail(@PathVariable String reportId) {
        return ApiResponse.ok(reportService.getReportById(reportId));
    }
}
