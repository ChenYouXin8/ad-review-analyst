package io.github.chenyouxin8.adreview.controller;

import io.github.chenyouxin8.adreview.common.ApiResponse;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.service.CsvImportService;
import io.github.chenyouxin8.adreview.service.QianchuanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/data")
@Tag(name = "投放数据", description = "千川投放数据查询与 CSV 导入")
public class DataController {

    private final QianchuanService qianchuanService;
    private final CsvImportService csvImportService;

    @Autowired
    public DataController(QianchuanService qianchuanService, CsvImportService csvImportService) {
        this.qianchuanService = qianchuanService;
        this.csvImportService = csvImportService;
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

    @PostMapping("/import-csv")
    @Operation(summary = "导入千川 CSV 报表", description = "解析上传的 CSV 并写入本地库，返回成功条数与日期范围")
    public ApiResponse<CsvImportService.ImportResult> importCsv(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(csvImportService.importCsv(file));
    }

    @GetMapping("/csv-template")
    @Operation(summary = "下载标准 CSV 模板", description = "返回含中文表头与 5 条样本数据的 CSV 文件流")
    public ResponseEntity<byte[]> downloadCsvTemplate() {
        byte[] content = csvImportService.buildTemplateCsv().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"qianchuan-report-template.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(content);
    }
}
