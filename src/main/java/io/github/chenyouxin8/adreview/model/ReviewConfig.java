package io.github.chenyouxin8.adreview.model;

/**
 * 前端展示所需的业务阈值配置
 */
public record ReviewConfig(
        double roiThreshold,
        double cpaThreshold,
        double ctrThreshold,
        double abnormalMinCost
) {
}
