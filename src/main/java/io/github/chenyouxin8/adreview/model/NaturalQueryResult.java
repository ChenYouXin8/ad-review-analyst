package io.github.chenyouxin8.adreview.model;

import lombok.Data;

import java.util.List;

/**
 * 自然语言查询结果
 */
@Data
public class NaturalQueryResult {
    private String question;
    private String intent;
    private String data;
    private String answer;
    private List<AdCampaign> relatedCampaigns;
}
