package io.github.chenyouxin8.adreview.controller;

import io.github.chenyouxin8.adreview.common.ApiResponse;
import io.github.chenyouxin8.adreview.model.NaturalQueryResult;
import io.github.chenyouxin8.adreview.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/query")
@Tag(name = "智能查询", description = "自然语言查询投放数据")
public class QueryController {

    private final ReportService reportService;

    @Autowired
    public QueryController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/ask")
    @Operation(summary = "自然语言查询")
    public ApiResponse<NaturalQueryResult> naturalQuery(
            @RequestParam String question,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) date = LocalDate.now();
        return ApiResponse.ok(reportService.naturalQuery(question, date));
    }
}
