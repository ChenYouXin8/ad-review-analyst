package io.github.chenyouxin8.adreview.controller;

import io.github.chenyouxin8.adreview.common.ApiResponse;
import io.github.chenyouxin8.adreview.model.ReviewConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/config")
@Tag(name = "系统配置", description = "业务阈值配置（供前端展示）")
public class ConfigController {

    private final ReviewConfig reviewConfig;

    public ConfigController(ReviewConfig reviewConfig) {
        this.reviewConfig = reviewConfig;
    }

    @GetMapping
    @Operation(summary = "获取业务阈值配置")
    public ApiResponse<ReviewConfig> getConfig() {
        return ApiResponse.ok(reviewConfig);
    }
}
