package io.github.chenyouxin8.adreview.model;

import lombok.Data;

import java.time.LocalDate;

/**
 * 账户汇总数据
 */
@Data
public class AccountSummary {
    private Long advertiserId;
    private String advertiserName;
    private LocalDate statDate;
    private Long totalShow;
    private Long totalClick;
    private Double totalCost;
    private Long totalConvert;
    private Double totalGmv;
    private Double overallRoi;
    private Double overallCtr;
    private Double overallCpc;
    private Double overallCpa;
    private Integer activeCampaignCount;
    private Integer pausedCampaignCount;
    private Integer abnormalCampaignCount;
}
