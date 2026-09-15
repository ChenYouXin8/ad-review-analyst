package io.github.chenyouxin8.adreview.model;

import lombok.Data;

import java.time.LocalDate;

/**
 * 广告计划数据模型
 */
@Data
public class AdCampaign {
    private Long campaignId;
    private String campaignName;
    private Long adgroupId;
    private String adgroupName;
    private Long creativeId;
    private String creativeName;
    private LocalDate statDate;
    private Long showCnt;
    private Long clickCnt;
    private Double ctr;
    private Double avgClickCost;
    private Double cost;
    private Long convertCnt;
    private Double convertCost;
    private Double convertRate;
    private Double payOrderAmount;
    private Double payOrderRoi;
    private Long payOrderCnt;
    private Double avgShowCost;
    private Long deepConvertCnt;
    private Double deepConvertCost;
}
