package io.github.chenyouxin8.adreview.model;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * AI 复盘洞察结果（单次强 Schema 结构化输出的载体）。
 *
 * <p>原先分散的「概览 / 深度分析 / 策略建议」三次自由文本 LLM 调用，
 * 现合并为一次 {@code BeanOutputConverter<AiReportInsight>} 结构化调用，
 * 由 JSON Schema 约束字段语义，避免正则切行解析。</p>
 */
public record AiReportInsight(
        @JsonPropertyDescription("账户整体投放概览，150 字以内，先评价整体好坏，再指出 1-2 个亮点或问题")
        String overview,

        @JsonPropertyDescription("深度归因分析，300-500 字，结合异常计划与 TOP 计划，分点论述：整体表现、优质计划共性、异常原因推测、趋势判断")
        String deepAnalysis,

        @JsonPropertyDescription("3-5 条具体可执行的优化建议，每条需包含针对的计划、执行动作与预期效果")
        List<String> actionableSuggestions
) {
}
