package io.github.chenyouxin8.adreview.model;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务阈值配置（可配置化，消除代码中的魔法数字）。
 *
 * <p>所有字段均可通过 {@code application.yml} 的 {@code ad-review.*} 前缀覆盖，
 * Java 侧默认值与历史实现保持一致，保证向后兼容；同时由 {@code GET /api/config} 暴露给前端动态渲染。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "ad-review")
public class ReviewConfig {

    /** ROI 预警阈值 */
    private double roiThreshold = 1.5;
    /** CPA 预警阈值（元） */
    private double cpaThreshold = 100.0;
    /** CTR 预警阈值（%） */
    private double ctrThreshold = 1.0;
    /** 异常诊断的最低消耗门槛（元），低于该消耗不判异常 */
    private double abnormalMinCost = 100.0;

    /** 入选 TOP 计划的最低消耗门槛（元） */
    private double topCampaignMinCost = 200.0;
    /** 判定衰退计划的最低消耗门槛（元） */
    private double decliningCampaignMinCost = 500.0;
    /** 判定高 CPA 的最小转化数 */
    private int cpaMinConversions = 3;
    /** 判定低 CTR 的最小展示量 */
    private long ctrMinImpressions = 10000L;
    /** 默认预算规划乘数 */
    private double budgetGrowthFactor = 1.1;
    /** 单计划消耗超过全场平均消耗的倍数阈值（用于预算超支检测） */
    private double overspendRatioThreshold = 2.5;
    /** 平均点击成本高于全场平均 CPC 的倍数阈值（用于素材衰退检测） */
    private double creativeDecayCpcMultiplier = 1.5;
}
