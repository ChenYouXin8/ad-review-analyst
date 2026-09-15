package io.github.chenyouxin8.adreview.controller;

import io.github.chenyouxin8.adreview.common.ApiResponse;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.service.QianchuanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/data")
@Tag(name = "投放数据", description = "千川投放数据查询")
public class DataController {

    private final QianchuanService qianchuanService;

    @Autowired
    public DataController(QianchuanService qianchuanService) {
        this.qianchuanService = qianchuanService;
    }

    @GetMapping("/summary")
    @Operation(summary = "获取账户汇总数据")
    public ApiResponse<AccountSummary> getAccountSummary(
            @Parameter(description = "日期，默认今天") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) date = LocalDate.now();
        return ApiResponse.ok(qianchuanService.getAccountSummary(date));
    }

    @GetMapping("/campaigns")
    @Operation(summary = "获取计划级报表")
    public ApiResponse<List<AdCampaign>> getCampaignReports(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ApiResponse.ok(qianchuanService.getCampaignReports(startDate, endDate));
    }
}
