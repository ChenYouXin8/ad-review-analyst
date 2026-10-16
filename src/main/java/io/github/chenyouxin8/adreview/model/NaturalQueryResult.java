package io.github.chenyouxin8.adreview.model;

import lombok.Data;

import java.util.ArrayList;
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
    /** 当前数据来源类型（DataSourceType.name()：LOCAL_IMPORT / MCP_LIVE / MOCK_FALLBACK） */
    private String dataSource;
    /** AI 回答是否降级为本地规则兜底（默认 false） */
    private Boolean degraded = false;
    /** 本次回答过程中 AI 实际调用的本地工具名称列表 */
    private List<String> invokedTools = new ArrayList<>();
}
