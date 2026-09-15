package io.github.chenyouxin8.adreview.controller;

import io.github.chenyouxin8.adreview.common.ApiResponse;
import io.github.chenyouxin8.adreview.model.ReviewConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
@Tag(name = "系统配置", description = "业务阈值配置（供前端展示）")
public class ConfigController {

    @Value("${ad-review.roi-threshold:1.5}")
    private double roiThreshold;
    @Value("${ad-review.cpa-threshold:100}")
    private double cpaThreshold;
    @Value("${ad-review.ctr-threshold:1.0}")
    private double ctrThreshold;
    @Value("${ad-review.abnormal-min-cost:100}")
    private double abnormalMinCost;

    @GetMapping
    @Operation(summary = "获取业务阈值配置")
    public ApiResponse<ReviewConfig> getConfig() {
        return ApiResponse.ok(new ReviewConfig(roiThreshold, cpaThreshold, ctrThreshold, abnormalMinCost));
    }
}
